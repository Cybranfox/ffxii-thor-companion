# FFXII Thor Companion (public beta)

A live second-screen companion for **FINAL FANTASY XII THE ZODIAC AGE** (Steam, PC) running under **GameNative** on
the **AYN Thor** dual-screen handheld. The game plays on the top screen. The companion app on the bottom screen follows
the game in real time.

> Unofficial fan project. Not affiliated with or endorsed by Square Enix. **No game files are included**: the app builds
> what it needs from your own copy of the game. See [CREDITS](CREDITS.md).

## What it does

| Tab | What you get |
|---|---|
| **Status** | Where you are, a map of the areas you have explored with a "you are here" marker, and live party HP/MP. |
| **Gambits** | Every member's gambits as the game has them right now, with item counts ("Potion ×14", "0 left" warnings). **Suggest** builds a gambit set from what that character owns and is licensed for. The optional **editor** can apply a change or a whole suggested set to the running game, and switch which set (I/II/III) a character uses. |
| **Equip** | Each member's equipped gear, plus what the game's own **Optimize** would change. Descriptions come from optional mods. |
| **Bestiary** | Bestiary pages by location (with an optional mod). |
| **Licenses** | Both job boards per character: owned, buyable now (the game's exact rules, including Espers, Quickenings and the Second Board), and the cheapest route to any license. |

There are three pieces, and each only does its own job:

1. **The app** (`.apk`) runs on the Thor's bottom screen and reads files. It has no network access.
2. **The game script** (`ThorCompanion.lua`) runs inside the game under ffgriever's LUA Script Loader. It reads the
   game's state and writes it as small JSON files to `Download/FFXII_companion/live` on the device.
3. **The data builder** (PC, Python) builds names, licence boards and map sheets from **your own** game files. You run it
   once and copy its output to the Thor.

**[▶ Watch the 90-second demo](https://github.com/Cybranfox/ffxii-thor-companion/releases/download/v0.9.0-beta/FFXII_Thor_Companion_launch_0.9.0-beta.mp4)** · Install guide: **[docs/INSTALL.md](docs/INSTALL.md)**

## Requirements

- **AYN Thor** (Android 13) with **GameNative** and **FINAL FANTASY XII THE ZODIAC AGE from Steam, version 1.0.4.0**.
  Other game builds are detected and the companion stays read-only and blank.
- **Required mods:** ffgriever's [FF12 External File Loader](https://www.nexusmods.com/finalfantasy12/mods/170) and
  [FF12 LUA Script Loader](https://www.nexusmods.com/finalfantasy12/mods/171). Tested with LUA Loader 1.7.1.
- **Optional mods** that add text: [Dynamic Equipment Description](https://www.nexusmods.com/finalfantasy12/mods/441)
  with *The Insurgent's Descriptive Inventory* (Equip tab descriptions), and *Foedex Clan Primer Text Replacer* (Bestiary
  tab). Everything else works without them.
- **A PC with your Steam copy of the game** and Python 3.10+, to run the data builder once.

No other mods are needed. If you use *The Insurgent's Manifesto*, its 36-slot single gambit list mode is not supported
by the editor (the editor refuses to run then). Everything else still works.

## How credentials are handled

**There are none.** The companion has no accounts, logins, tokens, API keys, analytics, ads or telemetry.

- The app **does not request Internet access**, so Android won't let it reach the network at all. You can check this
  in Android's app info, or with `aapt dump permissions` on the APK.
- The game script never opens a network connection. It only writes inside `Download/FFXII_companion/`.
- The data builder reads your local game files and writes a local folder. The only network use is the first-run
  `pip install` of numpy and Pillow from PyPI into a private folder next to the builder.
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

Install:
1. Run the **data builder** on your PC, then copy `FFXII_companion` into the Thor's `Download` folder.
2. Install the **APK** on the Thor and open it once. Grant "All files access" when asked. It puts the matching game
   script and installer in `Download/FFXII_companion/install`.
3. In GameNative, open the game's container and run `D:\FFXII_companion\install\install.cmd`. Then play.

Remove: run `uninstall.cmd` the same way (it deletes only `ThorCompanion.lua`), uninstall the app, and delete
`Download/FFXII_companion`.

Full steps, including the one-time GameNative setup for the loaders: **[docs/INSTALL.md](docs/INSTALL.md)**.

## Source code, building and releases

Everything is in this repository: `app/` (Android, plain Java, no Gradle), `game-script/` (Lua), `builder/` (Python),
and `scripts/release.py` (the one-command release). The data protocol is in [docs/PROTOCOL.md](docs/PROTOCOL.md).
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
