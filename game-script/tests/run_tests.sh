#!/usr/bin/env bash
# Stub-API tests for ThorCompanion.lua (needs Python + lupa). test_reveal_family runs anywhere; the others need private
# real-game inputs (battle pack, a recorded probe history, a save-region dump) and are skipped without them:
#   FFXII_BATTLE_PACK  FFXII_PROBE_HISTORY  FFXII_SAVE_DUMP
set -euo pipefail
cd "$(dirname "$0")"
[ -f local.env ] && . ./local.env   # git-ignored: FFXII_BATTLE_PACK FFXII_PROBE_HISTORY FFXII_SAVE_DUMP
python test_reveal_family.py
P="${FFXII_BATTLE_PACK:-}"; H="${FFXII_PROBE_HISTORY:-}"; S="${FFXII_SAVE_DUMP:-}"
if [ -f "$P" ] && [ -f "$H" ]; then
  python test_gambit_editor.py "$P" "$H"
  python test_battle_family.py "$P" "$H"
  if [ -f "$S" ]; then
    python test_build_family.py "$P" "$H" "$S"
    python test_party_family.py "$P" "$H" "$S"
  else
    echo "SKIP build/party families: FFXII_SAVE_DUMP not set"
  fi
else
  echo "SKIP editor/battle/build/party families: FFXII_BATTLE_PACK / FFXII_PROBE_HISTORY not set"
fi
