<#
.SYNOPSIS
    Builds RemotePad.exe (PyInstaller) and RemotePad-Setup.exe (Inno Setup Installer with bundled VB-Cable driver).
#>
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
Set-Location $ScriptDir

$VenvPython = Join-Path $ScriptDir ".venv\Scripts\python.exe"
if (-not (Test-Path $VenvPython)) {
    throw "Virtual environment Python not found at: $VenvPython"
}

Write-Host "[1/4] Running test suite..." -ForegroundColor Cyan
& $VenvPython -m pytest -q
if ($LASTEXITCODE -ne 0) {
    throw "Unit tests failed. Aborting build."
}

Write-Host "[2/4] Building RemotePad.exe with PyInstaller..." -ForegroundColor Cyan
& $VenvPython -m PyInstaller --noconfirm --clean "RemotePad.spec"
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller build failed."
}

$BuiltExe = Join-Path $ScriptDir "dist\RemotePad\RemotePad.exe"
if (-not (Test-Path $BuiltExe)) {
    throw "Expected executable not found at: $BuiltExe"
}

Write-Host "[3/4] Staging VBCABLE_Driver_Pack46.zip..." -ForegroundColor Cyan
$VbCableZip = Join-Path $RepoRoot "VBCABLE_Driver_Pack46.zip"
if (-not (Test-Path $VbCableZip)) {
    throw "VBCABLE_Driver_Pack46.zip not found at: $VbCableZip"
}
$VbCableStage = Join-Path $ScriptDir "build\vbcable_driver"
if (Test-Path $VbCableStage) {
    Remove-Item -Path $VbCableStage -Recurse -Force
}
New-Item -ItemType Directory -Path $VbCableStage -Force | Out-Null
Expand-Archive -Path $VbCableZip -DestinationPath $VbCableStage -Force

$VbSetupExe = Join-Path $VbCableStage "VBCABLE_Setup_x64.exe"
if (-not (Test-Path $VbSetupExe)) {
    throw "VBCABLE_Setup_x64.exe not found inside $VbCableZip"
}
Write-Host "Staged VB-Cable files at: $VbCableStage" -ForegroundColor DarkGray

Write-Host "[4/4] Building RemotePad-Setup.exe with Inno Setup..." -ForegroundColor Cyan
$IsccCandidates = @(
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 7\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe",
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
)
$Iscc = $IsccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Iscc) {
    $Cmd = Get-Command iscc -ErrorAction SilentlyContinue
    if ($Cmd) { $Iscc = $Cmd.Source }
}
if (-not $Iscc) {
    throw "Inno Setup compiler (ISCC.exe) not found. Please install Inno Setup 6 or 7."
}

Write-Host "Using Inno Setup compiler: $Iscc" -ForegroundColor DarkGray
& $Iscc "installer\RemotePad.iss"
if ($LASTEXITCODE -ne 0) {
    throw "Inno Setup compilation failed."
}

$SetupExe = Join-Path $ScriptDir "dist\RemotePad-Setup.exe"
if (-not (Test-Path $SetupExe)) {
    throw "Expected installer not found at: $SetupExe"
}

Write-Host ""
Write-Host "Build succeeded!" -ForegroundColor Green
Write-Host "  App EXE   : $BuiltExe"
Write-Host "  Installer : $SetupExe (includes VB-Cable Driver Pack 46)"
