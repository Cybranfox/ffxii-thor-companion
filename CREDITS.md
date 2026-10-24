# Credits and acknowledgements

FFXII Thor Companion is an unofficial fan project. It is **not affiliated with or endorsed by Square Enix**. FINAL FANTASY and FINAL FANTASY XII THE ZODIAC AGE are trademarks of Square Enix. All game names, text and data belong to their owners.

## What this repository contains, and what it never contains
- **No game assets are included or distributed.** No sprites, fonts, icons, maps, text tables or data files from the game are in the APK or the source tree. The source uses a small number of names as labels and lookup keys (the six party members, the twelve jobs, and a few items, spells and gambit conditions). All other game text comes from the user's own data pack.
- Data the app shows (area names, map sheets, licence boards and names, gambit/condition names, item/gear/bestiary text) is **built on the user's own PC from the user's own installed copy of the game and mods** by the data builder in `builder/`, then copied to the user's own device. It is for that user's personal use and is not for redistribution.
- Live values (HP/MP, gambits, licences, gear, stock, location) are read at run time from the user's own running game by `ThorCompanion.lua`, a read-only script except for the opt-in, armed-before-launch gambit editor.

## Original artwork and code in this project (no third-party licence involved)
All of the following were written or drawn in code for this project:
- **Visual theme** (`Theme.java`): palette, panel style, the selection highlight with its bobbing pointer and sheen. Inspired by the general mood of FFXII's menus; no game asset was copied or traced.
- **Animated background** (`ZodiacBackground.java`): gradient sky, twinkling star field, drifting mist, and simplified **zodiac constellation outlines**. The constellation shapes are simplified from real-sky star patterns, which are public-domain astronomy, not game art.
- **Licence board drawing** (`LicencesView.java`), the map renderer, and all other UI.

## Typefaces
- Headings use the **device's built-in serif** (on the AYN Thor: Google's Noto Serif, SIL Open Font License 1.1). Body text uses the device default (Roboto, Apache License 2.0). **No font files are bundled.**

## Mods whose work the companion reads or works alongside
The companion only reads files these mods write or install on the user's machine, so it can show their descriptions and respect their behaviour. Thank you to their authors:
- **Xeavin**: *The Insurgent's Manifesto*, *The Insurgent's Companions*, *The Insurgent's Vision*, *The Insurgent's Bountiful Bundle*, *The Insurgent's Descriptive Inventory* (its generated item/gear descriptions feed the Gear tab), *Auto Loot Pickup* (https://www.nexusmods.com/finalfantasy12/mods/364), *Summons For All* (https://www.nexusmods.com/finalfantasy12/mods/381), *Full Party Experience Share* (https://www.nexusmods.com/finalfantasy12/mods/460), *Smart Stealing Gambits* (https://www.nexusmods.com/finalfantasy12/mods/453), *Accurate Overhead HP Bars* (https://www.nexusmods.com/finalfantasy12/mods/496).
- **FehDead**: *Dynamic Equipment Description* (https://www.nexusmods.com/finalfantasy12/mods/441). Optional: its generated descriptions feed the Equip tab.
- **LowPriorityCitizen**: *Foedex Clan Primer Text Replacer* (its bestiary text feeds the Bestiary tab).
- **ffgriever**: *FF12 External File Loader* (https://www.nexusmods.com/finalfantasy12/mods/170) and *FF12 LUA Script Loader* (https://www.nexusmods.com/finalfantasy12/mods/171), which host the companion's game-side script. **Required.**
- Author names above are taken from the mods' own files (`--Made by …` headers and FOMOD `info.xml`). Mod pages: Nexus Mods, Final Fantasy XII section.

## Research references
- **Bartz24, FFModdingWPF** (MIT licence): its documented readers informed the licence-board file format (`licd` header, 24×24 u16 grid) and other offline formats used by the data builder. No code is copied into the app.

## AI assistance

This project was built with substantial help from AI coding assistants, which wrote much of the code and research
tooling under my direction. Every feature was tested on real hardware (an AYN Thor) before release.

## Tools used to build and test (not distributed with the app)
- Python with **numpy** (BSD-3-Clause) and **Pillow** (MIT-CMU) in the data builder; **Capstone** disassembler (BSD-3-Clause) for read-only static analysis during research; **lupa** (MIT) to run the Lua script in tests; **imageio-ffmpeg** (BSD-2-Clause) bundling **FFmpeg** (LGPL/GPL) to assemble the showcase video; the Android SDK and a JDK.
- Runs on the user's device under **GameNative** (Wine/FEX/DXVK) and is launched from **Cocoon**; neither is part of this project.

If you believe something here is missing or wrongly credited, please open an issue and it will be fixed.
