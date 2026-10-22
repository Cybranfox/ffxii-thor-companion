# Security

## What each part can and cannot do
- **App:** its only permission is all-files storage access, to read and write `Download/FFXII_companion`. It has
  **no Internet permission**, so it can't send or receive anything over the network. No accounts, credentials,
  analytics or ads.
- **Loader (`ThorCompanion.lua`, installed once in the game's `x64/scripts`):** at every game start it reads the game
  script (`ThorCompanion-core.lua`) and its signature from `Download/FFXII_companion/install`, checks the Ed25519
  signature against public keys built into the loader, refuses anything older than what it has already accepted, and
  only then runs exactly the bytes it checked. Unsigned, altered, older or unknown-key files never run. It keeps a
  small private record (`x64/scripts/ThorCompanion-state/`) of the newest release it accepted.
- **Game script:** reads game memory and writes JSON files only under `D:\FFXII_companion\`. It writes game memory only
  for the opt-in gambit editor, after re-checking the live game. It never calls game functions and never opens network
  connections. It reads one other mod's config (the Insurgent's Manifesto, to know whether its 36-slot gambit mode is
  on); that config is a Lua chunk, run with an empty environment. That removes globals and I/O but is not a sandbox: a
  hostile file there could still hang the game. It sits in your game folder, written by a mod you installed.
- **Data builder:** reads your local game files and writes a local folder. Its only network use is the first-run
  `pip install` of numpy and Pillow from PyPI.

## Credentials and keys
There are none of yours. No accounts, logins, tokens or telemetry. The game-script signing keys belong to the
developer and never enter this repository: the release key is passphrase-encrypted on the developer's PC and is used
only by hand, once per release (nothing signs automatically, including CI); the recovery key is passphrase-encrypted
and kept offline on USB. The loader and the app only contain the **public** halves, which authenticate a release, not
you. The recovery key can only replace a lost or leaked release key; it never signs game scripts. Never send anyone your
saves, data-builder output or keys.

## Limits, honestly
- **First install is trust-on-first-use.** `install.cmd` is itself a script in shared storage. A signed game script
  can't protect a loader installed by a tampered installer, so only use the installer the official app put there.
- **Shared storage is shared.** Another app with all-files access can delete the companion's files, forge the live
  readings the app shows (wrong numbers, a fake "game closed" that makes the app close itself), or create the editor's
  files. The editor's on-switch (`cmd/ARMED`) and its request files are **not authenticated**: such an app could request
  gambit changes, which still have to pass every game-side check (field only, the slot must be exactly as expected, the
  gambit owned and the ability licensed). The editor is off by default; keep it off if you don't trust your other apps
  with storage access, and make changes in the game's own menu instead. Stronger authentication (pairing the app with
  the game) is planned.
- **Not a sandbox.** Other mods run inside the same game process and can do anything, including changing the loader.
  A signature says who released a script, not that it has no bugs.
- **Rollback protection** depends on the game folder staying private (GameNative keeps it in its own app storage) and
  on the loader's record staying intact. Restoring an old container backup or reinstalling the loader resets it.
- **Key changes reach you only through an update.** An offline device can't learn that a key was revoked until you
  install a new app version. A broken loader, or a lost recovery key, means running `install.cmd` again.
- **The signature check is new code.** It's pure Lua (the loader can't use native libraries), MIT-licensed, passes the
  RFC 8032 test vectors and hundreds of tamper tests, and is cross-checked against a standard library, but it has **not
  been independently audited**.

## Verifying a download
- **Checksums:** every release has `SHA256SUMS.txt` (`sha256sum -c SHA256SUMS.txt`, or `Get-FileHash -Algorithm SHA256
  <file>` in PowerShell). They catch broken or altered downloads. A checksum from the same page as a tampered file
  proves nothing, so prefer this repository's Releases.
- **The app:** every APK is signed with the same certificate:
  `SHA-256 23:C1:5B:E9:7B:CD:14:51:84:DB:3E:0A:A1:D6:8C:AA:24:23:5F:36:39:A0:64:B7:E4:F8:12:10:8D:CB:6B:0B`.
  Check it with `apksigner verify --print-certs <apk>`. Never uninstall the real app to get past an update-signature
  warning.
- **The game script:** `python scripts/verify_core.py ThorCompanion-core.lua ThorCompanion-core.sig` checks it against
  the release key in `game-script/keys/`. Public key fingerprints (SHA-256):
  - release key (epoch 1): `8b43c8c3f8727e32b1a953b80de29c05876daef04d9f6c5a00f9c92b99322a7f`
  - recovery key: `3bbb421a6fa43c7c6dfe094ecc4c14b332e7dd7c0dba3cf0c475b19b9b480a24`

## Reporting a problem
Please report security issues privately through GitHub's "Report a vulnerability" (Security tab), not as a public
issue. Include what you saw and how to reproduce it. Don't attach save files or data-builder output.
