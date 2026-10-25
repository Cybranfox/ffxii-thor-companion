# FFXII Thor Companion (public beta)

A live second-screen companion for **FINAL FANTASY XII THE ZODIAC AGE** (Steam, PC) running under **GameNative** on
the **AYN Thor** dual-screen handheld. The game plays on the top screen. The companion app on the bottom screen follows
the game in real time.

> Unofficial fan project. Not affiliated with or endorsed by Square Enix. **No game files are included**: the app builds
> what it needs from your own copy of the game. See [CREDITS](CREDITS.md).

## What it does

| Tab | What you get |
|---|---|
| **Status** | Where you are, a map of the areas you have explored with a "you are here" marker, the game's map symbols and exit labels (tap it for a full-size map you can zoom and drag), live party HP/MP and status effects, the enemy your party is targeting (level, HP, weaknesses), the game's own "where to go next" hint, and (optional) your accepted hunts. Tap a hunt to see where the game's hunt map points. |
| **Gambits** | Every member's gambits as the game has them right now, with item counts ("Potion ×14", "0 left" warnings). **Suggest** builds a gambit set from what that character owns and is licensed for. The optional **editor** can apply a change or a whole suggested set to the running game, and switch which set (I/II/III) a character uses. |
| **Equip** | Each member's equipped gear, plus what the game's own **Optimize** would change. Descriptions come from optional mods. |
| **Bestiary** | Bestiary pages by location (with an optional mod). |
| **Licenses** | Both job boards per character: owned, buyable now (the game's exact rules, including Espers, Quickenings and the Second Board), and the cheapest route to any license. |

## New in 0.17

I've brought the bottom screen closer to the game's menus, with a softly moving slate background, plain menu words
down the left, a pointing-glove cursor, and paper pages for Bestiary and the full map. Thanks for the Reddit feedback.
It helped shape this update. The interface art is drawn for this app, and Roboto and Michroma use the SIL Open Font
License. No Square Enix artwork, fonts or sounds are bundled.

Gambits now shows one set at a time. Tap I, II or III to look through them. The gambit editor, its safety checks and
confirmation steps are unchanged.

Accepted hunts have a map link under Status › Hunts. It follows your progress and opens the place the game's own
**View Hunt Map** points to. Most have a marker; one offers several regions to choose from. These are the game's
hunt-map destinations, not exact spawn points. Ground you haven't explored stays hidden. Hunts remain optional.

**Updating from an earlier version? Rebuild your game data once** for the hunt maps: Status › Details › Rebuild, with
the game running (playing or at the title screen) until it finishes. New installs get this in their first build. You
don't need to run `install.cmd` again.

There are three pieces, and each only does its own job:

1. **The app** (`.apk`) runs on the Thor's bottom screen and reads files. It has no network access.
2. **The game script** runs inside the game under ffgriever's LUA Script Loader. It reads the game's state and writes
   it as small JSON files to `Download/FFXII_companion/live` on the device. It comes inside the app: a small **loader**
   (`ThorCompanion.lua`), installed once, checks the script's signature at every game start and only runs a genuine,
   current copy. So after the first install, **updating is just installing the new app**.
3. **Your game data** (names, licence boards, map sheets) is built from **your own** game files, on the Thor: the game
   script copies the files it needs while you play (about 5 minutes) and the app builds the data. A PC data builder is
   still available as an alternative.

**[▶ Watch the 0.17 video: the new look and hunt maps](https://github.com/Cybranfox/ffxii-thor-companion/releases/download/v0.17.0-beta/FFXII_Thor_Companion_0.17.0-beta.mp4)** (2 min) · [0.16 video](https://github.com/Cybranfox/ffxii-thor-companion/releases/download/v0.16.0-beta/FFXII_Thor_Companion_0.16.0-beta.mp4) · [0.15 setup walkthrough](https://github.com/Cybranfox/ffxii-thor-companion/releases/download/v0.15.0-beta/FFXII_Thor_Companion_0.15.0-beta.mp4) · [first demo (0.9)](https://github.com/Cybranfox/ffxii-thor-companion/releases/download/v0.9.0-beta/FFXII_Thor_Companion_launch_0.9.0-beta.mp4) · Install guide: **[docs/INSTALL.md](docs/INSTALL.md)**

## Requirements

- **AYN Thor** (Android 13; I use the Thor Max) with **GameNative** and **FINAL FANTASY XII THE ZODIAC AGE from Steam,
  version 1.0.4.0**. My exact GameNative settings are below.
  Other game builds are detected and the companion stays read-only and blank.
- **Required mods:** ffgriever's [FF12 External File Loader](https://www.nexusmods.com/finalfantasy12/mods/170) and
  [FF12 LUA Script Loader](https://www.nexusmods.com/finalfantasy12/mods/171). Use the current versions (tested: LUA Script Loader 2.0.1 with External File Loader 1.6.1). 1.7.1 still works but often hangs when the game closes.
- **Optional mods** that add text: [Dynamic Equipment Description](https://www.nexusmods.com/finalfantasy12/mods/441)
  with *The Insurgent's Descriptive Inventory* (Equip tab descriptions), and *Foedex Clan Primer Text Replacer* (Bestiary
  tab). Everything else works without them.
- **No PC needed** for the game data any more. (The optional PC data builder needs Python 3.10+.)

No other mods are needed. If you use *The Insurgent's Manifesto*, its 36-slot single gambit list mode is not supported
by the editor (the editor refuses to run then). Everything else still works.

## My setup (recommended)

This is exactly what I play on. If you're setting up from scratch, copy it and you should be up and running quickly.

- **Device:** AYN Thor **Max** (16 GB RAM, 1 TB), Android 13. Other Thor models should work, but I haven't tested them.
- **GameNative:** 1.3.0.
- **Game:** FINAL FANTASY XII THE ZODIAC AGE from Steam, 1.0.4.0.
- **Loaders:** FF12 External File Loader 1.6.1 + FF12 LUA Script Loader 2.0.1.

In GameNative: FINAL FANTASY XII › ⚙ › **Edit container**.

| Tab | Setting | What I use |
|---|---|---|
| General | Container Variant | bionic |
| General | Wine Version | proton-10.0-arm64ec-2 |
| General | Screen Size | 1280x720 (16:9) |
| General | Audio Driver | PulseAudio (low latency mode off) |
| General | **Enable mod loaders** | **On** (the loaders need it) |
| Graphics | Graphics Driver | Wrapper |
| Graphics | Graphics Driver Version | turnip_v26.0.0_R8 |
| Graphics | Use Adrenotools Turnip | On |
| Graphics | Display Renderer | Vulkan |
| Graphics | DX Wrapper | DXVK, version 2.4.1-gplasync |
| Graphics | Max Device Memory | 4096 MB |
| Graphics | Present Modes / Renderer Present Mode | mailbox / fifo |
| Graphics | Use DRI3 | On |
| Emulation | 64-bit and 32-bit Emulator | FEXCore (version 2512, preset Intermediate) |
| Emulation | Box64 | 0.3.6, preset Compatibility |

Everything else is left at GameNative's defaults. Most of the table is what GameNative sets up for FFXII by itself; the
one you have to change is **Enable mod loaders**. In the in-game Quick Menu I keep the FPS limiter on at 60.

What to expect: over a 10-minute session with these settings it averaged 58 fps (median 60), dipping into the high
40s in busy fights and when a new area loads. GameNative's compatibility page lists FFXII as "Unreliable" on the Thor,
but with this setup it plays well for me. The one real problem is the exit hang below.

## How credentials are handled

**There are none.** The companion has no accounts, logins, tokens, API keys, analytics, ads or telemetry.

- The app **does not request Internet access**, so Android won't let it reach the network at all. You can check this
  in Android's app info, or with `aapt dump permissions` on the APK.
- The game script never opens a network connection. It only writes inside `Download/FFXII_companion/`.
- The only keys involved are the developer's game-script signing keys (kept passphrase-encrypted, never in this
  repository); the app and the loader carry only their public halves. They prove a release is genuine, not who you are.
- The game data is built from your local game files, on the Thor or with the optional PC builder, and stays local. The
  PC builder's only network use is the first-run `pip install` of numpy and Pillow from PyPI into a private folder.
- Nothing is uploaded anywhere. Your saves are never read as files and never written.

## Permissions, limitations and security considerations

- **All-files storage access (Android).** The game runs in GameNative, a different app, and writes the live files into
  `Download/FFXII_companion`. Android 13 only lets another app read those through "All files access". The companion
  uses it to read that folder and to write its install files and the editor switch there. It touches nothing else. To
  revoke it: Settings › Apps › Special app access › All files access › FFXII Companion.
- **The gambit editor writes game memory.** It is **off by default**. You turn it on in the Gambits tab, and it only
  starts with the next game launch. It changes one gambit slot at a time, only in the field. The game script re-checks
  every change against the live game before writing: right build, slot still as expected, gambit owned, action owned
  and licensed. It never edits saves. Your next in-game save stores the change, like any change you make in the menu.
  Turning it off works immediately.
- **The editor's files aren't authenticated.** Its on-switch and requests are files in shared storage, so another app
  with all-files access could create them. Requests still have to pass every game-side check above, so they can only
  make gambit changes you could make yourself, but keep the editor off if you don't trust your other apps with storage
  access. Pairing the app with the game is planned. Details: [SECURITY.md](SECURITY.md).
- **Signed game script.** The loader runs only scripts signed with the developer's key and never an older one than it
  has already run. The check is pure Lua, passes the RFC 8032 test vectors and tamper tests, but isn't independently
  audited. The very first `install.cmd` has to be trusted as-is.
- **Read-only everywhere else.** The game script reads memory and writes only its own JSON files. It calls no game
  functions.
- **Supported build only.** The script checks the game executable's build stamp and stays blank on anything else.
  Memory layouts can differ between builds.
- **Exit hang (GameNative).** Sometimes, after quitting from the game's own menu, the game doesn't fully close under
  GameNative. Use GameNative's Quick Menu › Exit. This is being investigated, and is not known to be caused by the
  companion.
- **Thor first.** Other dual-screen Android devices and GameNative setups are untested. PC-only use (no Android) isn't a
  goal yet.
- **English (US) game text only** for this beta. Korean and Chinese licence boards differ.
- **Beta.** Expect rough edges. Please report issues (see below).

## Install / remove (short version)

Install (the app's Setup card walks you through it and shows the next step):
0. **First, ffgriever's two mod loaders have to be working in FFXII** (the companion's script runs through them):
   [FF12 External File Loader](https://www.nexusmods.com/finalfantasy12/mods/170) and
   [FF12 LUA Script Loader](https://www.nexusmods.com/finalfantasy12/mods/171) copied into the game's `x64` folder, the
   newer C++ runtime next to them, and GameNative's **Enable mod loaders** switched on. If you've never set those up,
   do [step 0 of the install guide](docs/INSTALL.md#0-one-time-mod-loaders-under-gamenative) first (about 10 minutes).
   Without them the game runs fine but the companion just waits.
1. Install the **APK** on the Thor, open it on the bottom screen and tap **Allow file access**. It puts the loader, the
   signed game script and the installer in `Download/FFXII_companion/install`.
2. Once only: in GameNative, open the game's container and run `D:\FFXII_companion\install\install.cmd`.
3. Tap **Start FFXII**, then **Build game data**. The game data builds itself while you play (about 5 minutes).

Update: install the new APK over the old one and restart the game. That's it.

Remove: run `uninstall.cmd` the same way (it deletes the loader and its record), uninstall the app, and delete
`Download/FFXII_companion`.

Full steps, including the one-time GameNative setup for the loaders: **[docs/INSTALL.md](docs/INSTALL.md)**.

## Source code, building and releases

Everything is in this repository: `app/` (Android, plain Java, no Gradle), `game-script/` (Lua), `builder/` (Python),
and `scripts/release.py` (the release; the developer signs each game script by hand with `scripts/sign_core.py`). The data protocol is in [docs/PROTOCOL.md](docs/PROTOCOL.md).
Releases on GitHub carry the APK, the game-script zip, the data-builder zip and `SHA256SUMS.txt`. Every APK is signed
with the same release key, so updates install over the previous version.

## Feedback

Open an issue with your device, GameNative version, game version and what you saw. Screenshots of both screens help.
Please don't attach save files or anything built by the data builder: it contains game text.

## How it was made

I built this with a lot of help from AI coding assistants. They wrote much of the code and the research tooling, and
helped work out how the game stores its data. The idea, the design, and the decisions about what's safe to ship are
mine, and I tested every feature by playing on my own Thor before it went in. If you spot something that looks off,
please tell me.

## Credits and licence

Our code is under the [MIT licence](LICENSE). Mod authors, tools and references are credited in
[CREDITS.md](CREDITS.md). Thank you to ffgriever, Xeavin, FehDead, LowPriorityCitizen and the FFXII modding community.
