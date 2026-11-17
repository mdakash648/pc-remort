"""Low-latency UDP microphone receiver & Windows waveOut player for RemotePad PC Agent.

Receives 48 kHz, 16-bit mono PCM frames from the Android phone over UDP (default port 8768)
and plays them into a Windows Virtual Audio Cable render endpoint (e.g. VB-Cable's
"CABLE Input"), so Windows and all PC apps (Discord, Zoom, Meet, OBS, Chrome, Games)
receive the phone's microphone via the paired capture endpoint ("CABLE Output").

Zero external dependencies — uses pure Python ctypes + winmm.dll.
"""

from __future__ import annotations

import asyncio
import ctypes
import logging
import socket
import struct
import sys
import threading
import time
from ctypes import wintypes
from typing import Callable, Protocol

logger = logging.getLogger(__name__)

DEFAULT_MIC_PORT = 8768
MIC_MAGIC = b"RMIC"
MIC_HEADER_FMT = ">4sI"
MIC_HEADER_SIZE = 8

SAMPLE_RATE = 48000
CHANNELS = 1
BITS_PER_SAMPLE = 16

_WAVE_FORMAT_PCM = 1
_WHDR_DONE = 0x00000001
_MAX_IN_FLIGHT_BUFFERS = 8  # ~80 ms cap at 10 ms/packet to keep voice latency tight
_IDLE_CLOSE_SECONDS = 2.0

_VIRTUAL_CABLE_KEYWORDS = (
    "cable input",
    "voicemeeter input",
    "voicemeeter",
    "virtual",
)


class _WAVEOUTCAPSW(ctypes.Structure):
    _fields_ = [
        ("wMid", wintypes.WORD),
        ("wPid", wintypes.WORD),
        ("vDriverVersion", wintypes.UINT),
        ("szPname", wintypes.WCHAR * 32),
        ("dwFormats", wintypes.DWORD),
        ("wChannels", wintypes.WORD),
        ("wReserved1", wintypes.WORD),
        ("dwSupport", wintypes.DWORD),
    ]


class _WAVEFORMATEX(ctypes.Structure):
    _pack_ = 1
    _fields_ = [
        ("wFormatTag", wintypes.WORD),
        ("nChannels", wintypes.WORD),
        ("nSamplesPerSec", wintypes.DWORD),
        ("nAvgBytesPerSec", wintypes.DWORD),
        ("nBlockAlign", wintypes.WORD),
        ("wBitsPerSample", wintypes.WORD),
        ("cbSize", wintypes.WORD),
    ]


class _WAVEHDR(ctypes.Structure):
    _fields_ = [
        ("lpData", ctypes.c_void_p),
        ("dwBufferLength", wintypes.DWORD),
        ("dwBytesRecorded", wintypes.DWORD),
        ("dwUser", ctypes.c_size_t),
        ("dwFlags", wintypes.DWORD),
        ("dwLoops", wintypes.DWORD),
        ("lpNext", ctypes.c_void_p),
        ("reserved", ctypes.c_size_t),
    ]


def _load_winmm() -> ctypes.WinDLL | None:
    if sys.platform != "win32" or not hasattr(ctypes, "WinDLL"):
        return None
    dll = ctypes.WinDLL("winmm")
    dll.waveOutGetNumDevs.argtypes = ()
    dll.waveOutGetNumDevs.restype = wintypes.UINT
    dll.waveOutGetDevCapsW.argtypes = (ctypes.c_size_t, ctypes.POINTER(_WAVEOUTCAPSW), wintypes.UINT)
    dll.waveOutGetDevCapsW.restype = wintypes.UINT
    dll.waveOutOpen.argtypes = (
        ctypes.POINTER(ctypes.c_void_p),
        wintypes.UINT,
        ctypes.POINTER(_WAVEFORMATEX),
        ctypes.c_size_t,
        ctypes.c_size_t,
        wintypes.DWORD,
    )
    dll.waveOutOpen.restype = wintypes.UINT
    dll.waveOutPrepareHeader.argtypes = (ctypes.c_void_p, ctypes.POINTER(_WAVEHDR), wintypes.UINT)
    dll.waveOutPrepareHeader.restype = wintypes.UINT
    dll.waveOutUnprepareHeader.argtypes = (ctypes.c_void_p, ctypes.POINTER(_WAVEHDR), wintypes.UINT)
    dll.waveOutUnprepareHeader.restype = wintypes.UINT
    dll.waveOutWrite.argtypes = (ctypes.c_void_p, ctypes.POINTER(_WAVEHDR), wintypes.UINT)
    dll.waveOutWrite.restype = wintypes.UINT
    dll.waveOutReset.argtypes = (ctypes.c_void_p,)
    dll.waveOutReset.restype = wintypes.UINT
    dll.waveOutClose.argtypes = (ctypes.c_void_p,)
    dll.waveOutClose.restype = wintypes.UINT
    return dll


_winmm = _load_winmm()


def list_playback_devices() -> list[tuple[int, str]]:
    """Enumerate Windows multimedia playback devices."""
    if _winmm is None:
        return []
    devices: list[tuple[int, str]] = []
    count = int(_winmm.waveOutGetNumDevs())
    for idx in range(count):
        caps = _WAVEOUTCAPSW()
        if _winmm.waveOutGetDevCapsW(idx, ctypes.byref(caps), ctypes.sizeof(caps)) == 0:
            devices.append((idx, str(caps.szPname).strip()))
    return devices


def find_virtual_mic_device(preferred_name: str = "") -> tuple[int, str] | None:
    """Locate the target Virtual Audio Cable playback device (e.g. VB-Cable 'CABLE Input')."""
    devices = list_playback_devices()
    if not devices:
        return None

    pref = preferred_name.strip().lower()
    if pref:
        for idx, name in devices:
            if pref in name.lower():
                return (idx, name)

    for keyword in _VIRTUAL_CABLE_KEYWORDS:
        for idx, name in devices:
            if keyword in name.lower():
                return (idx, name)

    return None


def parse_mic_packet(data: bytes) -> tuple[int, bytes] | None:
    """Parse an RMIC UDP packet into (sequence_number, pcm_payload)."""
    if len(data) <= MIC_HEADER_SIZE:
        return None
    magic, seq = struct.unpack_from(MIC_HEADER_FMT, data, 0)
    if magic != MIC_MAGIC:
        return None
    pcm = data[MIC_HEADER_SIZE:]
    # Must be even number of bytes for 16-bit PCM
    if len(pcm) < 2 or (len(pcm) % 2) != 0:
        return None
    return (seq, pcm)


class AudioSink(Protocol):
    @property
    def device_name(self) -> str: ...
    @property
    def is_ready(self) -> bool: ...
    def refresh_device(self) -> str: ...
    def write_pcm(self, pcm: bytes) -> None: ...
    def check_idle_close(self, now: float | None = None) -> None: ...
    def close(self) -> None: ...


class WinMMWaveOutSink:
    """Low-latency Windows waveOut PCM sink targeting a Virtual Audio Cable."""

    def __init__(self, preferred_device: str = "") -> None:
        self._preferred_device = preferred_device
        self._lock = threading.Lock()
        self._device_id: int | None = None
        self._device_name: str = ""
        self._handle: ctypes.c_void_p | None = None
        self._in_flight: list[tuple[_WAVEHDR, ctypes.Array[ctypes.c_char]]] = []
        self._last_write_time: float = 0.0
        self.refresh_device()

    @property
    def device_name(self) -> str:
        return self._device_name

    @property
    def is_ready(self) -> bool:
        return self._device_id is not None

    def refresh_device(self) -> str:
        with self._lock:
            found = find_virtual_mic_device(self._preferred_device)
            if found is not None:
                self._device_id, self._device_name = found
            else:
                self._device_id = None
                self._device_name = ""
            return self._device_name

    def _ensure_open_locked(self) -> bool:
        if _winmm is None:
            return False
        if self._handle is not None:
            return True
        if self._device_id is None:
            found = find_virtual_mic_device(self._preferred_device)
            if found is None:
                return False
            self._device_id, self._device_name = found

        block_align = CHANNELS * (BITS_PER_SAMPLE // 8)
        wfx = _WAVEFORMATEX(
            wFormatTag=_WAVE_FORMAT_PCM,
            nChannels=CHANNELS,
            nSamplesPerSec=SAMPLE_RATE,
            nAvgBytesPerSec=SAMPLE_RATE * block_align,
            nBlockAlign=block_align,
            wBitsPerSample=BITS_PER_SAMPLE,
            cbSize=0,
        )
        handle = ctypes.c_void_p()
        res = _winmm.waveOutOpen(
            ctypes.byref(handle),
            self._device_id,
            ctypes.byref(wfx),
            0,
            0,
            0,
        )
        if res != 0 or not handle.value:
            logger.warning("waveOutOpen failed for '%s' (err=%d)", self._device_name, res)
            return False
        self._handle = handle
        logger.info("Opened virtual mic output: %s", self._device_name)
        return True

    def _reclaim_done_locked(self) -> None:
        if _winmm is None or self._handle is None:
            return
        still_active: list[tuple[_WAVEHDR, ctypes.Array[ctypes.c_char]]] = []
        for hdr, buf in self._in_flight:
            if (hdr.dwFlags & _WHDR_DONE) != 0:
                _winmm.waveOutUnprepareHeader(self._handle, ctypes.byref(hdr), ctypes.sizeof(hdr))
            else:
                still_active.append((hdr, buf))
        self._in_flight = still_active

    def write_pcm(self, pcm: bytes) -> None:
        if not pcm or _winmm is None:
            return
        with self._lock:
            if not self._ensure_open_locked():
                return
            assert self._handle is not None
            self._reclaim_done_locked()

            # Drop frame if playback queue is already full (~80ms) to prevent voice lag buildup
            if len(self._in_flight) >= _MAX_IN_FLIGHT_BUFFERS:
                return

            buf = ctypes.create_string_buffer(pcm, len(pcm))
            hdr = _WAVEHDR(
                lpData=ctypes.cast(buf, ctypes.c_void_p),
                dwBufferLength=len(pcm),
                dwBytesRecorded=0,
                dwUser=0,
                dwFlags=0,
                dwLoops=0,
                lpNext=None,
                reserved=0,
            )
            if _winmm.waveOutPrepareHeader(self._handle, ctypes.byref(hdr), ctypes.sizeof(hdr)) != 0:
                return
            if _winmm.waveOutWrite(self._handle, ctypes.byref(hdr), ctypes.sizeof(hdr)) != 0:
                _winmm.waveOutUnprepareHeader(self._handle, ctypes.byref(hdr), ctypes.sizeof(hdr))
                return
            self._in_flight.append((hdr, buf))
            self._last_write_time = time.monotonic()

    def check_idle_close(self, now: float | None = None) -> None:
        ts = time.monotonic() if now is None else now
        with self._lock:
            if self._handle is None:
                return
            if (ts - self._last_write_time) >= _IDLE_CLOSE_SECONDS:
                self._close_locked()

    def close(self) -> None:
        with self._lock:
            self._close_locked()

    def _close_locked(self) -> None:
        if _winmm is None or self._handle is None:
            self._in_flight.clear()
            return
        try:
            _winmm.waveOutReset(self._handle)
            for hdr, _ in self._in_flight:
                _winmm.waveOutUnprepareHeader(self._handle, ctypes.byref(hdr), ctypes.sizeof(hdr))
            _winmm.waveOutClose(self._handle)
        except Exception:
            pass
        finally:
            self._in_flight.clear()
            self._handle = None


class _MicDatagramProtocol(asyncio.DatagramProtocol):
    def __init__(self, sink: AudioSink, on_packet: Callable[[int, bytes], None] | None = None) -> None:
        self._sink = sink
        self._on_packet = on_packet
        self._last_seq: int | None = None
        self._last_packet_time: float = 0.0

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        parsed = parse_mic_packet(data)
        if parsed is None:
            return
        seq, pcm = parsed
        now = time.monotonic()

        # Reset sequence tracking after >1.5s gap (new mic session) or fresh start (seq <= 2)
        if self._last_seq is None or (now - self._last_packet_time) > 1.5 or seq <= 2:
            self._last_seq = seq
        else:
            # Drop stale out-of-order packets (signed 32-bit modular diff)
            diff = (seq - self._last_seq) & 0xFFFFFFFF
            if diff == 0 or diff > 0x80000000:
                return
            self._last_seq = seq

        self._last_packet_time = now
        self._sink.write_pcm(pcm)
        if self._on_packet is not None:
            self._on_packet(seq, pcm)

    def error_received(self, exc: Exception) -> None:
        logger.debug("Mic UDP socket error: %s", exc)


class MicReceiverService:
    """Async UDP server receiving phone microphone PCM audio and routing to PC virtual mic."""

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = DEFAULT_MIC_PORT,
        preferred_device: str = "",
        sink: AudioSink | None = None,
    ) -> None:
        self.host = host
        self._configured_port = port
        self._sink: AudioSink = sink or WinMMWaveOutSink(preferred_device=preferred_device)
        self._transport: asyncio.DatagramTransport | None = None
        self._idle_task: asyncio.Task[None] | None = None

    @property
    def port(self) -> int:
        if self._transport is not None:
            sockname = self._transport.get_extra_info("sockname")
            if isinstance(sockname, tuple) and len(sockname) >= 2:
                return int(sockname[1])
        return self._configured_port

    @property
    def device_name(self) -> str:
        return self._sink.device_name

    @property
    def is_ready(self) -> bool:
        return self._sink.is_ready

    def refresh_device(self) -> str:
        return self._sink.refresh_device()

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            # Increase receive buffer so Wi-Fi jitter bursts don't drop audio packets
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 256 * 1024)
            except OSError:
                pass
            sock.bind((self.host, self._configured_port))
            sock.setblocking(False)
            transport, _ = await loop.create_datagram_endpoint(
                lambda: _MicDatagramProtocol(self._sink),
                sock=sock,
            )
            self._transport = transport
            self._idle_task = asyncio.create_task(self._idle_watchdog())
            logger.info(
                "Microphone UDP receiver listening on %s:%d (device=%s)",
                self.host,
                self.port,
                self.device_name or "NONE - install VB-Cable",
            )
        except OSError as exc:
            sock.close()
            logger.warning("Could not start microphone UDP receiver on port %d: %s", self._configured_port, exc)

    async def stop(self) -> None:
        if self._idle_task is not None:
            self._idle_task.cancel()
            try:
                await self._idle_task
            except asyncio.CancelledError:
                pass
            self._idle_task = None
        if self._transport is not None:
            self._transport.close()
            self._transport = None
        self._sink.close()
        logger.info("Microphone UDP receiver stopped")

    async def _idle_watchdog(self) -> None:
        while True:
            await asyncio.sleep(1.0)
            self._sink.check_idle_close()
