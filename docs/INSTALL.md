# Install, update and remove

Tested setup: AYN Thor (Android 13), GameNative, FINAL FANTASY XII THE ZODIAC AGE (Steam) 1.0.4.0,
FF12 External File Loader 1.4.x + FF12 LUA Script Loader 1.7.1.

In GameNative's container (Wine), drive **A:** is the game folder and drive **D:** is the Thor's **Download** folder.
Everything the companion uses lives in `Download/FFXII_companion` (`D:\FFXII_companion`).

## 0. One time: mod loaders under GameNative

Skip this if the loaders already work for you (other Lua mods load).

1. Download ffgriever's [FF12 External File Loader](https://www.nexusmods.com/finalfantasy12/mods/170) and
   [FF12 LUA Script Loader](https://www.nexusmods.com/finalfantasy12/mods/171) (1.7.1 is the tested version).
2. Copy their files into the game folder's `x64` folder, as their pages describe: put them in the Thor's `Download`
   folder, then in GameNative open the game › Options › **Open container** and copy them into `A:\x64` with the file
   manager. After this, `A:\x64` has `dinput8.dll`, `ff12-trampoline.dll`, `hook.ini`, and a `modules` folder with
   `ff12-file-loader.dll` and `ff12-lua-loader.dll`.
3. In the game's GameNative settings, turn on **Enable mod loaders**. It makes Wine load the loader's `dinput8.dll`.
4. **C++ runtime (needed under GameNative).** The loaders need Microsoft's Visual C++ runtime **14.40 or newer**. The
   container's built-in one is older, and the game then crashes at start with `c0000005`. Copy `msvcp140.dll`,
   `vcruntime140.dll` and `vcruntime140_1.dll` from a Windows PC with the current
   [Visual C++ Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist) (in
   `C:\Windows\System32`) into `A:\x64`, next to the game's exe. You can also install `vc_redist.x64.exe` inside the
   container.
5. Start the game once. It should reach the title screen as normal.

## 1. Build your data pack (PC, once per game/mod change)

1. Download `FFXII-Thor-Companion-data-builder-<version>.zip` from Releases and unzip it.
2. Install [Python 3.10+](https://www.python.org/downloads/) if you don't have it (tick "Add python.exe to PATH").
3. Double-click **`Build data pack.cmd`**. The first run sets up numpy and Pillow in a private `.venv` folder. It finds
   the game in your Steam libraries (or pass `--game "<folder>"`) and writes `output\FFXII_companion` in about a minute.
   It prints what it included and which optional mods it found.
4. Copy the **`FFXII_companion`** folder into the Thor's **Download** folder (USB, adb or any file transfer). Merge it
   with any existing one.

Run it again after you add or remove the optional text mods (Dynamic Equipment Description / Descriptive Inventory,
Foedex Clan Primer). The folder is built from your files for your own use. Please don't share it.

## 2. Install the app (Thor)

1. Download `FFXII-Thor-Companion-<version>.apk` and install it. You may need to allow installs from your file manager
   or browser.
2. Open **FFXII Companion**, ideally on the bottom screen, and tap **Allow file access** to allow "All files access". This
   is needed to read the files GameNative writes (see the README for why).
3. The app now puts the matching game script and its installer in `Download/FFXII_companion/install`.

## 3. Install the game script (GameNative container)

1. In GameNative: FINAL FANTASY XII › Options › **Open container**.
2. In the file manager: **D:** › `FFXII_companion` › `install` › double-tap **`install.cmd`**.
3. `result.txt` in the same folder should say `INSTALLED`. Warnings there point to step 0. Exit the container.
4. Launch the game with the companion open on the bottom screen. The header changes from "Waiting" to **Live** once
   you're in game.

## Gambit editor (optional)

Gambits tab › **Gambit editor: off › Turn on…** › read the note › **Yes, turn on**. It starts with the **next game
launch**. **Turn off** works immediately. Edits only happen in the field, one slot at a time, after you confirm each
one.

## Updating

- **App:** install the new APK over the old one (releases share one signing key). On next start, it refreshes
  `Download/FFXII_companion/install`.
- **Game script:** after updating the app, run `install.cmd` again in the container. The app's Details panel shows the
  running script's version.
- **Data pack:** only needed again if a release note says so, or after changing the optional text mods.

## Removing everything

1. Container: run `D:\FFXII_companion\install\uninstall.cmd`. It deletes only `A:\x64\scripts\ThorCompanion.lua`.
2. Uninstall the **FFXII Companion** app.
3. Delete `Download/FFXII_companion`.

Your saves, the loaders and your other mods aren't touched. To also remove the loaders, follow their own pages.

## Troubleshooting

| You see | Do this |
|---|---|
| "Waiting" forever in game | The script isn't running: check `install/result.txt`, and that other Lua mods load (step 0). |
| Game crashes at start (`c0000005`) after adding loaders | Step 0.4, the C++ runtime. |
| Status shows "Game build not verified" | Your FFXII build isn't 1.0.4.0. The companion stays blank by design. |
| Names say "Map 123", no boards or map | The data pack isn't in `Download/FFXII_companion/reference` (step 1). |
| Game doesn't close after quitting | GameNative Quick Menu › Exit (known exit hang). |
| Editor says it refuses | It names the reason: not in the field, slot changed in game, Manifesto 36-slot mode, and so on. |
