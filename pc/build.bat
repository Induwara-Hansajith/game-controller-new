@echo off
setlocal
cd /d "%~dp0"

if not exist ".venv-build\Scripts\python.exe" (
  py -3 -m venv .venv-build
  if errorlevel 1 goto :failed
)

".venv-build\Scripts\python.exe" -m pip install --disable-pip-version-check -r requirements.txt
if errorlevel 1 goto :failed

".venv-build\Scripts\python.exe" -m PyInstaller --noconfirm --clean --onefile --windowed --name PocketControllerPC --add-data "web;web" --add-data "vgamepad;vgamepad" desktop_app.py
if errorlevel 1 goto :failed

echo.
echo Build complete: %CD%\dist\PocketControllerPC.exe
exit /b 0

:failed
echo.
echo PC app build failed. See the error above.
exit /b 1
