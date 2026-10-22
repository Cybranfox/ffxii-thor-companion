@echo off
rem FFXII Thor Companion: removes the game-side script. Run inside the game's GameNative container.
rem Deletes exactly one file, A:\x64\scripts\ThorCompanion.lua. Your saves, the loaders and other mods are not touched.
setlocal
set "HERE=%~dp0"
set "RES=%HERE%result.txt"
echo FFXII Thor Companion uninstall > "%RES%"
if exist "A:\x64\scripts\ThorCompanion.lua" del /f /q "A:\x64\scripts\ThorCompanion.lua"
if exist "A:\x64\scripts\ThorCompanion.lua" (echo FAILED: could not delete A:\x64\scripts\ThorCompanion.lua >> "%RES%") else (echo REMOVED: the game runs exactly as before. Uninstall the app to finish. >> "%RES%")
type "%RES%"
endlocal
