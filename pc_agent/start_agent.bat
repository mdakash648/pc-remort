@echo off
cd /d "%~dp0"
echo Starting RemotePad PC Agent...
".venv\Scripts\python.exe" -u main.py %*
pause
