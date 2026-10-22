# Security

## What the companion can and cannot do
- **App:** its only permission is all-files storage access (to read `Download/FFXII_companion`). There is **no
  Internet permission**, so it can't send or receive anything over the network. It has no accounts, credentials,
  analytics or ads.
- **Game script (`ThorCompanion.lua`):** reads game memory and writes JSON files only under `D:\FFXII_companion\`.
  It runs no other code: it reads optional mods' config files only as plain data tables, in an empty Lua environment.
  It writes game memory **only** for the opt-in gambit editor. The editor stays off unless
  `FFXII_companion\cmd\ARMED` existed at game start, and every change is re-checked against the live game before the
  write. The script never calls game functions and never opens network connections.
- **Data builder:** reads your local game files and writes a local folder. Its only network use is the first-run
  `pip install` of numpy and Pillow from PyPI.

## Verifying a download
Every release lists SHA-256 hashes in `SHA256SUMS.txt`. All APKs are signed with the same release certificate:
`SHA-256 23:C1:5B:E9:7B:CD:14:51:84:DB:3E:0A:A1:D6:8C:AA:24:23:5F:36:39:A0:64:B7:E4:F8:12:10:8D:CB:6B:0B`.
Check it with `apksigner verify --print-certs <apk>`. Only download from this repository's Releases. Mirrors on
Nexus Mods and Ko-fi link here.

## Reporting a problem
Please report security issues privately through GitHub's "Report a vulnerability" (Security tab), not as a public
issue. Include what you saw and how to reproduce it. Don't attach save files or data-builder output.
