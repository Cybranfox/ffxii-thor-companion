# Changelog

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
