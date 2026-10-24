# Install, update and remove

Tested setup: AYN Thor (Android 13), GameNative, FINAL FANTASY XII THE ZODIAC AGE (Steam) 1.0.4.0,
FF12 External File Loader 1.6.1 + FF12 LUA Script Loader 2.0.1 (1.4.x + 1.7.1 also work, but 1.7.1 often hangs on exit).

In GameNative's container (Wine), drive **A:** is the game folder and drive **D:** is the Thor's **Download** folder.
Everything the companion uses lives in `Download/FFXII_companion` (`D:\FFXII_companion`).

## 0. One time: mod loaders under GameNative

Skip this if the loaders already work for you (other Lua mods load).

1. Download ffgriever's [FF12 External File Loader](https://www.nexusmods.com/finalfantasy12/mods/170) and
   [FF12 LUA Script Loader](https://www.nexusmods.com/finalfantasy12/mods/171) (use the current version; 2.0.1 with External File Loader 1.6.1 is tested).
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

## 1. Install the app (Thor)

1. Download `FFXII-Thor-Companion-<version>.apk` and install it. You may need to allow installs from your file manager
   or browser.
2. Open **FFXII Companion**, ideally on the bottom screen. It opens on a **Setup** card: tap **Allow file access** and
   switch on "All files access". This is needed to read the files GameNative writes (see the README for why).
3. Back in the app, the Setup card ticks off file access and puts the matching game script and its installer in
   `Download/FFXII_companion/install`.

From here the Setup card always shows the next step and a button for it, and it hides itself once everything is ready.

## 2. Install the loader (GameNative container, once)

This puts the companion's small loader in the game. You do it once: after this, new versions of the game script
arrive with the app and the loader checks and runs them by itself.

1. In GameNative: FINAL FANTASY XII › Options › **Open container**.
2. In the file manager: **D:** › `FFXII_companion` › `install` › double-tap **`install.cmd`**.
3. `result.txt` in the same folder should say `INSTALLED` (the file list may need a refresh to show it). Warnings there
   point to step 0. Exit the container (GameNative Quick Menu › Exit).

## 3. Start the game

Tap **Start FFXII** on the Setup card. It starts the game on the top screen through GameNative (it doesn't change any
of your GameNative settings). The header changes from "Waiting" to **Live** once the game script reports in; at the
title screen is enough.

## 4. Build your game data (on the Thor, once)

The names, licence boards and maps come from **your own** game files. The companion builds them on the Thor:

1. On the Setup card (or Status › Game data), tap **Build game data**. You can tap it before or after starting the game.
2. With the game running, the game script copies the files it needs a little at a time while you play or sit at the
   title screen. It takes **about 5 minutes**, pauses during fights and carries on where it left off after a restart.
3. The app then builds and checks the data (about 10 seconds) and swaps it in. Status › Game data says
   **Game data ready**.

Tap **Rebuild** after you add or remove the optional text mods (Dynamic Equipment Description / Descriptive Inventory,
Foedex Clan Primer). The data is built from your files for your own use. Please don't share it.

**Alternative: build on a PC.** The data builder from Releases does the same from your Steam folder:
1. Download `FFXII-Thor-Companion-data-builder-<version>.zip` and unzip it. Install
   [Python 3.10+](https://www.python.org/downloads/) if you don't have it (tick "Add python.exe to PATH").
2. Double-click **`Build data pack.cmd`**. It finds the game in your Steam libraries (or pass `--game "<folder>"`) and
   writes `output\FFXII_companion` in about a minute.
3. Copy the **`FFXII_companion`** folder into the Thor's **Download** folder, merging it with the existing one.

## 5. Open the companion with the game (Cocoon)

If you launch games from **Cocoon**, it can open the companion on the bottom screen every time you start FFXII:
find FINAL FANTASY XII (the PC entry) › press **Y** › **Edit** › **Companion App** › **FFXII Companion** › save with the
check mark. Updating over the old version keeps it, and on the Thor it also survived uninstalling and reinstalling the
app. If it's ever gone, set it again the same way. If you start the game from GameNative directly, open FFXII Companion on the bottom screen yourself.

## Gambit editor (optional)

Gambits tab › **Gambit editor: off › Turn on…** › read the note › **Yes, turn on**. It starts with the **next game
launch**. **Turn off** works immediately. Edits only happen in the field, one slot at a time, after you confirm each
one.

## Updating

- **App and game script:** install the new APK over the old one (releases share one signing key), open the app once,
  then restart the game. From 0.11.0 the game script updates itself: the loader checks the new script's signature at
  the next game start and runs it. The app's Details panel shows the running script's version.
- **Coming from 0.10.0 or older:** run `install.cmd` in the container **one last time** to install the loader.
- **The loader itself:** only if a release note says so (rare), run `install.cmd` again.
- **Game data:** tap **Rebuild** only if a release note says so, or after changing the optional text mods.

If the app ever says "The game reports: …", the loader refused to run the game script and the message says what to do.
Usually it's "open this app once, then restart the game".

## Removing everything

1. Container: run `D:\FFXII_companion\install\uninstall.cmd`. It deletes `A:\x64\scripts\ThorCompanion.lua` and the
   loader's record folder `A:\x64\scripts\ThorCompanion-state`.
2. Uninstall the **FFXII Companion** app.
3. Delete `Download/FFXII_companion`.

Your saves, the loaders and your other mods aren't touched. To also remove the loaders, follow their own pages.

## Troubleshooting

| You see | Do this |
|---|---|
| "Waiting" forever in game | The script isn't running: check `install/result.txt`, and that other Lua mods load (step 0). |
| Game crashes at start (`c0000005`) after adding loaders | Step 0.4, the C++ runtime. |
| Status shows "Game build not verified" | Your FFXII build isn't 1.0.4.0. The companion stays blank by design. |
| Names say "Map 123", no boards or map | No game data yet: Status › Game data › **Build game data** (step 4). |
| Setup card says "Run install.cmd once…" | The loader has never run: step 2. |
| Game doesn't close after quitting | GameNative Quick Menu › Exit (known exit hang). |
| Editor says it refuses | It names the reason: not in the field, slot changed in game, Manifesto 36-slot mode, and so on. |
