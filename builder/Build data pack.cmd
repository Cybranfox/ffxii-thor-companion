@echo off
rem FFXII Thor Companion data builder (Windows). Double-click. Needs Python 3.10+ from python.org.
rem Makes a private Python environment in this folder (.venv) the first time; nothing is installed system-wide.
setlocal
cd /d "%~dp0"
where py >nul 2>nul && (set "PY=py -3") || (set "PY=python")
%PY% -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>nul
if errorlevel 1 (
  echo Python 3.10 or newer is needed: https://www.python.org/downloads/  ^(tick "Add python.exe to PATH"^)
  pause & exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  echo Setting up the builder the first time...
  %PY% -m venv .venv || (echo Could not create .venv & pause & exit /b 1)
  ".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -r requirements.txt || (echo Could not install numpy/Pillow & pause & exit /b 1)
)
".venv\Scripts\python.exe" -B build_data_pack.py --out "%~dp0output" %*
echo.
echo The data pack is in: %~dp0output\FFXII_companion
pause
