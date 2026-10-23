# Changelog

## 0.13.0-beta
- **Targeting card.** When your party is about to act on an enemy, the Status page shows it: name (from your data
  pack's bestiary), level, HP, and what it's weak to, absorbs or is immune to, live from the game, so gear and effects
  count. It says who's targeting it, and tapping it opens that enemy's bestiary page. This is the target of the action
  each character has queued, which isn't always the same as the cursor you see on screen.
- Game script: reads each field member's pending action target through the game's own handle checks. Read-only, like
  everything else it reads.
- **Map symbols.** The map now shows the game's own symbols (crystals, shop signs and so on) exactly where and when the
  game's map shows them: only on ground you've explored and only once the story has unlocked them. They're the game's
  pictures, cut from your own game files by the data builder. The companion doesn't label them, because nothing in the
  game's code says for certain which symbol means what.
- **Locked exits are greyed out**, like on the game's map, until the story opens them.
- Game script: shares the game's story flags (read-only) so the app can do the two things above.
- Kept the game script light: it remembers where your party is in the game's actor list instead of searching the whole
  list twice a second, and reads the story flags in a few large reads. (An early 0.13 test build made the frame rate
  jump between 30 and 60; this is the fix.)
- **Re-run the data builder once** for the symbols.
- Fixed: at the title screen the game script could treat leftover memory as a loaded save (the party card showed
  "invalid" HP for a moment). It now also checks the game's own "save loaded" marker before reading anything.

## 0.12.0-beta
- **Exit labels on the map.** Where an area leads out of the region, the map now says where to, like "To the
  Estersand", the same names the game's map uses. They show up next to areas you've already visited.
- **A bigger map you can move around.** Tap the map on the Status page to open it full size. It shows everything
  you've explored in the region. Pinch to zoom, drag to move, double-tap to jump between the area you're in and the
  whole region. Re-centre puts it back.
- The short lines from an area out to its exits are drawn now too.
- **Re-run the data builder once** to get the exit labels. Without it the map works as before, just without labels.
- Under the hood: some tidying in the app's code. Nothing you should notice.

## 0.11.0-beta
- **Updates install themselves.** Run `install.cmd` one last time and it puts a small loader in the game. After that,
  installing a new APK is the whole update: the loader picks up the new game script at the next game start.
- **Game scripts are signed.** The loader checks each script's Ed25519 signature before running it. If it's been
  changed, is missing, is older than one you already had or is signed with a key it doesn't know, it doesn't run, and
  the app says what happened ("The game reports: ..."). For a changed or missing script the app puts its own copy back
  by itself.
- **Two keys.** I sign each release by hand with a passphrase-protected key that never goes in this repo. A second key
  kept offline can only replace the first if it's ever lost or leaked.
- **Editor:** request files are capped at 4 KB with a strict format, and turning the editor off also clears any request
  that was waiting. The app's JSON reader is stricter too, and install files are written through temp files.
- **SECURITY.md rewritten** to say plainly what the signing protects and what it doesn't (the first install, the
  editor's files, other mods), and that the signature check is new code nobody else has audited yet.

## 0.10.0-beta
- **Switch gambit sets from the companion.** With the editor on, each set's heading shows "· in use" or a **Use this
  set…** button. The switch happens straight away in the field and is checked by the game first. It's refused while
  the game's Gambits menu is open, or if anything changed since the companion last read it. It does what the game's own
  LT/RT does: changes which set is active and rebuilds the AI list from it. Nothing else is touched.
- **Title screen:** Status says "Title screen" instead of "Map 12", and the party card waits for a save instead of
  showing leftover numbers.
- **Data builder: 401 more area names.** The builder misread the area-name table's header and stopped after the
  first 648 entries, so many places showed as "Map 1234". It now reads all 707 named areas. **Re-run the data
  builder once** and copy the new `FFXII_companion` folder to the Thor.
- **Update hint:** if the game is still running an older game script than this app's, Status says so and points to
  `install.cmd`.
- Game script 0.10.0-beta: publishes each member's active set and adds the `setactive` editor operation (count 0 →
  index → entries → count, read back, rolled back on any mismatch, re-checked 2.5 s later).

## 0.9.0-beta (first public beta)
- **App:** five tabs.
  - Status: location, a map of explored areas with your position, and live party HP/MP.
  - Gambits: live sets, item counts with out-of-stock warnings, Suggest, and an opt-in editor with an on/off switch in
    the app.
  - Equip: equipped gear and the result of the game's Optimize.
  - Bestiary.
  - Licenses: both boards, the game's exact "can buy" rules, and the cheapest route to any license.

  The app has an animated FFXII-style theme.
- **No network access:** removed the experimental TCP transport and the Internet permission.
- **No game text inside the app:** area names now come from your data pack (`reference/areas.json`).
- **Matching game script included:** the app carries its game script and writes it, with `install.cmd` and
  `uninstall.cmd`, to `Download/FFXII_companion/install`.
- **Game script:** removed research-only features (save dump, battle log file, running other mods' config files as
  code). The editor now also works without *The Insurgent's Manifesto*, on the normal three gambit sets.
- **Gambit actions use the game's exact menu names:** the data pack now includes them, so the companion matches the
  game word for word, even with description mods installed. The editor never writes the 13 reserved records that sit
  inside the item range.
- **Data builder:** builds all core data from the unmodded game files. Optional mods add gear descriptions and the
  bestiary.

Development history before the public beta (logger 0.2–0.5.14, app 0.4–0.23.1) is kept in the project's research notes.
