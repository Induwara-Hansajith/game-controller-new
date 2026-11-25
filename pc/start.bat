@echo off
cd /d "%~dp0"
where pythonw >nul 2>nul
if errorlevel 1 (
  python desktop_app.py
) else (
  pythonw desktop_app.py
)
