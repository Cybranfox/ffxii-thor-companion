@echo off
rem FFXII Thor Companion: installs the game-side script ThorCompanion.lua into the game.
rem Run inside the game's GameNative container (Wine): A: = the game folder, D: = the device's Download folder.
rem Changes exactly one file: A:\x64\scripts\ThorCompanion.lua. Any previous copy is kept in this folder first.
setlocal
set "HERE=%~dp0"
set "X=A:\x64"
set "RES=%HERE%result.txt"
echo FFXII Thor Companion install > "%RES%"
if not exist "%X%\FFXII_TZA.exe" (echo FAILED: game not found at %X% - is this the FFXII container? >> "%RES%" & goto end)
if not exist "%HERE%ThorCompanion.lua" (echo FAILED: ThorCompanion.lua missing next to install.cmd >> "%RES%" & goto end)
set "MISSING="
if not exist "%X%\dinput8.dll" set "MISSING=1"
if not exist "%X%\modules\ff12-lua-loader.dll" set "MISSING=1"
if defined MISSING echo WARNING: FF12 External File Loader / LUA Script Loader not found. Install them first (see INSTALL). >> "%RES%"
if not exist "%X%\msvcp140.dll" echo WARNING: no msvcp140.dll in x64. Under GameNative the loaders need the app-local C++ runtime (see INSTALL). >> "%RES%"
if not exist "%X%\scripts" mkdir "%X%\scripts"
rem Keep the copy that is being replaced, but not when it is this same script (running install.cmd twice must not
rem overwrite the backup with the new file). Wine has no fc, so "same" = same size as the new script.
if exist "%X%\scripts\ThorCompanion.lua" for %%A in ("%X%\scripts\ThorCompanion.lua") do for %%B in ("%HERE%ThorCompanion.lua") do if not "%%~zA"=="%%~zB" copy /b /y "%X%\scripts\ThorCompanion.lua" "%HERE%ThorCompanion.previous.lua" > nul
copy /b /y "%HERE%ThorCompanion.lua" "%X%\scripts\ThorCompanion.lua" > nul
if errorlevel 1 (echo FAILED: could not copy into %X%\scripts >> "%RES%" & goto end)
if not exist "%X%\scripts\ThorCompanion.lua" (echo FAILED: copy did not land >> "%RES%" & goto end)
echo INSTALLED: %X%\scripts\ThorCompanion.lua >> "%RES%"
:end
type "%RES%"
endlocal
