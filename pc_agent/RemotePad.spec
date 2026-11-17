# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for RemotePad PC Agent (windowed system-tray app)."""

from pathlib import Path

project_root = Path(SPECPATH)

a = Analysis(
    [str(project_root / "main.py")],
    pathex=[str(project_root)],
    binaries=[],
    datas=[
        (str(project_root / "assets" / "app_icon.ico"), "assets"),
        (str(project_root / "assets" / "app_icon.png"), "assets"),
    ],
    hiddenimports=[
        "pystray._win32",
        "PIL._tkinter_finder",
        "websockets",
        "websockets.asyncio",
        "websockets.asyncio.server",
        "websockets.asyncio.client",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "pytest",
        "_pytest",
        "unittest",
        "pygments",
    ],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="RemotePad",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(project_root / "assets" / "app_icon.ico"),
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="RemotePad",
)
