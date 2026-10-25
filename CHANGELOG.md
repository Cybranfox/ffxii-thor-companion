# Changelog

## 0.17.0-beta
- **A new look for the bottom screen.** I've brought the companion closer to the game's menus: a softly moving slate
  background, black help and detail bars, plain menu words down the left, and a pointing-glove cursor. Thanks for the
  Reddit feedback on how the app looked. It helped shape this update.
- **Gambits show one set at a time.** Tap I, II or III to look through them. The set the game is using is still marked.
  The gambit editor, its safety checks and all confirmation steps are unchanged.
- **Quieter party rows.** HP and MP sit beside the name, with a thin line under HP and smaller maximum values. Status
  effects are coloured words underneath, with the bad ones first.
- **Plain lists and paper pages.** Equip and Bestiary entries are one line, with the details on the right. Bestiary
  and the full map use a parchment background; the full map has darker ground and labels so they stay readable.
- **Targeting, Setup and Details fit the new menus.** Element names are words with a small coloured dot instead of
  chips, and Targeting says "No target" when the live game reports none. Setup keeps its ordered checks and one next
  action, and there's only one Build button at a time now.
- **Hunt maps.** Tap an accepted hunt under Status › Hunts and the full map opens on the map the game's own hunt map
  would show you right now, with its marker ringed in gold. It follows your progress the same way the game does (town,
  then the mark's area, then back to town to report). It's where the game points you, not where the mark spawns, and
  ground you haven't explored stays hidden. The one hunt whose map covers several regions lets you pick a region.
- **The whole hunt journal shows.** A few journal lines name a person (like the petitioner you report back to) and
  used to be left out. They now show with the name filled in from your game files.
- All the new interface artwork is drawn for this app, with Roboto and Michroma under the SIL Open Font License.
  No Square Enix artwork, fonts or sounds are bundled.
- Updating: install the new APK and restart the game. Then **rebuild your game data once** (Status › Details ›
  Rebuild) for the hunt maps and journal lines; it copies two more small files from your game. If you use the PC
  builder, re-run the 0.17 one.
- Still open: the exit hang under GameNative. Use the Quick Menu › Exit for now.

## 0.16.1-beta
- **Status chips on the party.** Each party member now shows their status effects under the HP bar, with the game's own
  names: bad ones (Poison, Silence, Slow and so on) as filled red chips first, buffs (Protect, Shell, Haste, Regen...)
  outlined in blue. KO and HP Critical aren't repeated, since the row already shows them.
- **All four party members fit again.** The rows are more compact now: job icons, HP bar and MP on one line, and the
  chips underneath only when someone actually has a status.
- **Element names from your game.** The Targeting card's element chips use the game's own names.
- **A clearer setup when the mod loaders aren't working.** If you start FFXII from the companion and nothing comes back
  from the game after a minute and a half, the Setup card now says the mod loaders didn't run and what to check:
  GameNative's Enable mod loaders, ffgriever's loader files in `x64`, and the C++ runtime. Before, it just kept
  waiting. Thanks to the Redditor who reported this.
- The Game data line no longer shows a red warning before the game script is even running; it says it comes next.
- **README:** ffgriever's two loaders are now an explicit step 0 in the install steps, since they're the part people
  most often miss.
- Updating: install the new APK and restart the game. The app rebuilds its game data by itself (a few seconds) to pick
  up the status names. If you built your data with the PC builder, re-run it once.
- Still open: the exit hang under GameNative. Use the Quick Menu › Exit for now.

## 0.16.0-beta
- **The map fills in as you explore.** The game only saves an area's exploration when you leave it, so until now the
  companion showed the area you're in as it was last time (or nothing, the first time you walk in). The game script
  now also reads the game's own live progress for the area you're in, so it fills in as you go, like the game's map.
  It only uses it once the game has finished loading the area, and never shows less than what's saved.
- **A much lighter game script.** It was re-reading a couple of big data tables byte by byte every 2 seconds just to
  see if they'd changed. Now it checks first and only reads them when they have. In the field it reads about 85% less
  from the game's memory, and the bigger spikes every few seconds are gone. That should help keep the frame rate
  steady; I'm still measuring it on the Thor.
- **Status in two columns.** The bottom screen is nearly square, so Status now uses it that way: the map on the right,
  filling its column, and Location, Targeting and Party on the left. In normal play you shouldn't need to scroll. Hunts
  and Details are full width below. The map card keeps the same height, so it no longer jumps when a fight starts.
- **Party rows** show name and HP on one line, with the two job icons at the start of the HP bar. The bar turns amber
  below half, red below a quarter and grey at 0 or KO, and MP sits next to it.
- **New icons.** A new app icon (twelve zodiac stars around a crystal), an icon beside each tab name, and an icon for
  each job. All drawn for this app, nothing taken from the game.
- **Element chips on the Targeting card.** What the enemy is weak to, halves, absorbs or is immune to is shown as
  coloured chips in the game's element order: filled for weak, dimmed for halves, outlined for absorbs, struck through
  for immune. The words are still there, so you don't have to learn the colours. HP numbers and a coloured HP bar too,
  and who's targeting it gets its own line.
- **Bestiary tables.** Drops, steals and poaches are small tables now, item on the left and chance on the right, instead
  of plain text.
- **Quieter cards.** When your game data is ready, the Game data card shrinks to one line in Details ("Game data ready"
  with its own Rebuild button, so tapping the sentence no longer starts a copy). If the data is damaged, the card
  stays and says so. The header is one short line ("Waiting for FFXII."). The Location card says "Last known" when the
  game has stopped updating and never shows a bare map number. The map card says Loading…, Title screen or "The map
  comes with the game data." instead of the same sentence for everything.
- **Setup card reads install.cmd's result** before your first game start: "Loader installed. Start FFXII to finish
  setup.", a warning if the mod loaders or the C++ runtime look missing, or a clear message if install.cmd couldn't
  install the loader. Once the game itself reports in, that wins. The card only uses its own fixed wording, never text
  from the file.
- **install.cmd and uninstall.cmd open their result in Notepad**, so you can see INSTALLED (or what went wrong) right
  away. GameNative's file list doesn't refresh on its own, so before you couldn't easily tell.
- **Suggest knows what you're fighting.** Gambits that hit the current target's weaknesses come first, marked "(what
  you're fighting now is weak to it)", and they're the last to be dropped when 12 slots aren't enough.
- **Guest party members are named.** Party rows and the Targeting card's "targeted by" line now name guests from your
  own game data instead of "Member N". The app rebuilds its game data by itself after the update to pick up the names
  (a few seconds on the Thor). If you built your data with the PC builder, re-run it once for the names.
- **More map fixes:**
  - When there's nothing to show for the area you're in yet, the map frames that area's own outline with your
    position in it, instead of a blank card.
  - On the full map, pinching out past "this area" now carries on out to the whole region. Before, you could get stuck
    zoomed in.
  - Soft edges: explored ground now fades out at its edges, like on the game's map, instead of stopping in hard
    square steps.
- **Loader advice.** If the game reports a LUA Script Loader version the companion doesn't support, the message now
  names the tested pair: LUA Script Loader 2.0.1 with External File Loader 1.6.1. The README recommends the same.
  Thanks to ffgriever for both loaders.
- **install.cmd keeps its backups properly.** The first loader it ever replaced stays in `ThorCompanion.previous.lua`
  for good, and the one each run replaces goes to `ThorCompanion.replaced.lua`. Before, it decided whether two files
  were the same by their size alone, so it could skip a backup.
- Game script: also reports each party member's status effects (for status chips in a later update; nothing new on
  screen yet), and the game-data copy is more robust when a file write fails or the app stops mid-copy.
- **Release builds can't be signed with the debug key by accident.** The build now needs the release key, or an
  explicit opt-in for a test build, and checks the certificate against the one in SECURITY.md. A debug-signed APK can't
  update a release install, so this stops a broken update from ever going out.
- **README: "My setup".** The exact device, GameNative version, container settings and loaders I play on, plus the
  frame rate to expect.
- Updating: install the new APK and restart the game. The new game script comes with it; no need to run install.cmd
  again.
- Still open: sometimes the game doesn't fully close under GameNative after quitting from its own menu. Use GameNative's
  Quick Menu › Exit for now. I'm still looking into it.

## 0.15.0-beta
- **No PC needed any more.** The app now builds your game data (names, licence boards, maps) on the Thor, from your
  own game files. Tap **Build game data**, start the game, and the game script copies what it needs a little at a time
  while you play or sit at the title screen. About 5 minutes, it pauses during fights, and it picks up where it left off
  if you quit. The app then builds, checks and swaps the data in on its own. The PC data builder still works if you
  prefer it.
- **Setup card.** A first-run checklist on the Status page: file access, setup files, game loaders, game script and
  game data, each with a tick and the one button you need next ("Allow file access", "Start FFXII", "Build game data").
  It tells you to run install.cmd if the loader has never run, says "All set" when you're done, then gets out of the
  way. You can open it again any time from Details › Setup checks.
- **Start FFXII button.** Launches the game straight from the companion (the same way Cocoon does), instead of just
  opening GameNative's library.
- **Game data card** on the Status page shows whether your data is ready, being copied or being built, with a
  Rebuild button.
- **Works with LUA Script Loader 2.0.1.** The loader file the companion installs now runs on ffgriever's new loader
  (and still on 1.7.1). If you switch to 2.0.1, run install.cmd once more.
- **Map data format 2.** The map data now records what each picture looks like instead of the exact file, which is
  what lets the phone build it. Your current map data still works. If you re-run the PC data builder, update the app
  first: older apps can't read the new format.
- Fixed: an "isn't responding" message on the bottom screen right after the app opened (the Thor's own settings app
  sends a key press when it switches the controller between screens).
- Fixed: the Targeting card said "Enemy" instead of the enemy's name unless you'd opened another tab first.

## 0.14.0-beta
- **Story hint.** The Location card shows the game's own "where to go next" line, the one at the bottom of the
  game's map screen, picked the same way the game picks it from your story progress. Text only: the game's files
  don't say where on the map the place is, so there's no pin.
- **Hunt journal (off by default).** Turn on Hunts and the Status page lists the hunts you've accepted, with the
  game's own journal text for where you are in each one. Only hunts the game's own menu would list, so no spoilers.
  No map pins for hunts either: the game's files don't hold their locations.
- **Map options.** On the full map: Symbols, Story hint and Hunts switches. The app remembers your choice.
- Game script: shares the hunt progress the game's own menu uses (read-only, every 2 seconds).
- **Re-run the data builder once** for the hint and the hunt journal. Everything comes from your own game files.

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
