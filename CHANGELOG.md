# Changelog

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
- **Data builder:** builds all core data from the unmodded game files. Optional mods add gear descriptions and the
  bestiary.

Development history before the public beta (logger 0.2–0.5.14, app 0.4–0.23.1) is kept in the project's research notes.
