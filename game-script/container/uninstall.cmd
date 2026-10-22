@echo off
rem FFXII Thor Companion: removes the companion from the game. Run inside the game's GameNative container.
rem Deletes A:\x64\scripts\ThorCompanion.lua and the ThorCompanion-state folder next to it. Your saves, the loaders and
rem other mods are not touched.
setlocal
set "HERE=%~dp0"
set "RES=%HERE%result.txt"
echo FFXII Thor Companion uninstall > "%RES%"
if exist "A:\x64\scripts\ThorCompanion.lua" del /f /q "A:\x64\scripts\ThorCompanion.lua"
if exist "A:\x64\scripts\ThorCompanion-state" rmdir /s /q "A:\x64\scripts\ThorCompanion-state"
if exist "A:\x64\scripts\ThorCompanion.lua" (echo FAILED: could not delete A:\x64\scripts\ThorCompanion.lua >> "%RES%") else (echo REMOVED: the game runs exactly as before. Uninstall the app to finish. >> "%RES%")
type "%RES%"
endlocal
