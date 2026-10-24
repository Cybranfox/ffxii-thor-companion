@echo off
rem FFXII Thor Companion: installs the companion's small loader, ThorCompanion.lua, into the game. Needed once (and
rem again only if a release note says the loader itself changed): after that, installing a new companion APK is the
rem whole update, because the loader checks the signed game script at every game start.
rem Run inside the game's GameNative container (Wine): A: = the game folder, D: = the device's Download folder.
rem Changes: A:\x64\scripts\ThorCompanion.lua (the copy it replaces is kept in this folder first) and creates
rem A:\x64\scripts\ThorCompanion-state\ (the loader's private record of the newest version it has accepted).
setlocal
set "HERE=%~dp0"
set "X=A:\x64"
set "RES=%HERE%result.txt"
echo FFXII Thor Companion install > "%RES%"
if not exist "%X%\FFXII_TZA.exe" (echo FAILED: game not found at %X% - is this the FFXII container? >> "%RES%" & goto end)
if not exist "%HERE%ThorCompanion.lua" (echo FAILED: ThorCompanion.lua missing next to install.cmd - open the companion app once, then try again >> "%RES%" & goto end)
set "MISSING="
if not exist "%X%\dinput8.dll" set "MISSING=1"
if not exist "%X%\modules\ff12-lua-loader.dll" set "MISSING=1"
if defined MISSING echo WARNING: FF12 External File Loader / LUA Script Loader not found. Install them first (see INSTALL). >> "%RES%"
if not exist "%X%\msvcp140.dll" echo WARNING: no msvcp140.dll in x64. Under GameNative the loaders need the app-local C++ runtime (see INSTALL). >> "%RES%"
if not exist "%X%\scripts" mkdir "%X%\scripts"
if not exist "%X%\scripts\ThorCompanion-state" mkdir "%X%\scripts\ThorCompanion-state"
rem Keep the copy that is being replaced. Wine has no fc, so nothing is compared: the first copy ever replaced stays
rem in ThorCompanion.previous.lua for good (running install.cmd again can't overwrite it with the new file), and the
rem copy this run replaces goes to ThorCompanion.replaced.lua (0.16.0: same-size files used to be taken as the same).
if exist "%X%\scripts\ThorCompanion.lua" if not exist "%HERE%ThorCompanion.previous.lua" copy /b /y "%X%\scripts\ThorCompanion.lua" "%HERE%ThorCompanion.previous.lua" > nul
if exist "%X%\scripts\ThorCompanion.lua" copy /b /y "%X%\scripts\ThorCompanion.lua" "%HERE%ThorCompanion.replaced.lua" > nul
copy /b /y "%HERE%ThorCompanion.lua" "%X%\scripts\ThorCompanion.lua" > nul
if errorlevel 1 (echo FAILED: could not copy into %X%\scripts >> "%RES%" & goto end)
if not exist "%X%\scripts\ThorCompanion.lua" (echo FAILED: copy did not land >> "%RES%" & goto end)
echo INSTALLED: %X%\scripts\ThorCompanion.lua (loader). Future companion updates install themselves. >> "%RES%"
:end
type "%RES%"
rem 0.16.0: show the result where the player can see it (the container file list does not refresh).
start "" notepad.exe "%RES%"
endlocal
