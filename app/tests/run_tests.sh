#!/usr/bin/env bash
# Offline JVM tests for the receiver model (no device, no emulator). Pure-Java sources only.
set -euo pipefail
cd "$(dirname "$0")/.."
# Private real-game inputs (paths to your own dumps/packs) go in tests/local.env, which is git-ignored:
#   FFXII_EVIDENCE REFERENCE_JSON BOARDS_JSON ATLAS_INDEX GAMBIT_DUMP FFXII_RULES_REPLICA FFXII_RAW_RUN FFXII_PY_PACK FFXII_RAW_EXTRA
[ -f tests/local.env ] && . tests/local.env
JDK="${JDK:-C:/Program Files/Eclipse Adoptium/jdk-17.0.16.8-hotspot}"
python -I tests/gen_fixtures.py
rm -rf tests/classes && mkdir -p tests/classes
P=src/local/thor/ffxiicompanion
"$JDK/bin/javac" -source 11 -target 11 -Xlint:-options -encoding UTF-8 -d tests/classes \
  $P/Json.java $P/Flags.java $P/Objectives.java $P/Target.java $P/DataFiles.java $P/Details.java $P/Snapshot.java $P/Party.java $P/ReceiverState.java $P/SnapshotOrder.java $P/Place.java $P/LoaderStatus.java \
  $P/AutoClosePolicy.java $P/Atlas.java $P/AtlasFiles.java $P/Reveal.java $P/Regions.java $P/Position.java $P/Reference.java $P/Gambits.java $P/GambitEdit.java $P/Build.java $P/LicenceNodes.java $P/GambitPlan.java $P/ApplyPlan.java $P/Equipped.java $P/EquipTable.java $P/Optimize.java $P/ItemCheck.java $P/LicenceBoards.java $P/LicenceRules.java \
  $P/data/*.java \
  tests/SnapshotOrderTest.java tests/ReceiverStateTest.java tests/AutoClosePolicyTest.java tests/RevealTest.java tests/AtlasTest.java tests/ReferenceTest.java tests/GambitsTest.java tests/GambitEditTest.java tests/BuildTest.java tests/GambitPlanTest.java tests/ApplyTest.java tests/OptimizeTest.java tests/LicenceBoardsTest.java tests/LicenceRulesTest.java tests/PlaceTest.java tests/ActionNamesTest.java tests/SetActiveTest.java tests/LoaderStatusTest.java tests/DataFilesTest.java tests/TargetTest.java tests/FlagsTest.java tests/ObjectivesTest.java tests/DataBuilderTest.java
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.AutoClosePolicyTest
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.SnapshotOrderTest
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.PlaceTest
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.LoaderStatusTest
mkdir -p tests/out
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.DataFilesTest tests/out
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.TargetTest
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.FlagsTest
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.ObjectivesTest
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.ActionNamesTest
mkdir -p tests/out
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.SetActiveTest tests/out
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.ReceiverStateTest tests/fixtures
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.RevealTest tests/fixtures "${ATLAS_INDEX:-}"
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.AtlasTest "${ATLAS2_DIR:-}"
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.ReferenceTest "${REFERENCE_JSON:-}"
mkdir -p tests/out
"$JDK/bin/java" -ea -Xmx256m -cp tests/classes local.thor.ffxiicompanion.data.DataBuilderTest tests/out "${FFXII_RAW_RUN:-}" "${FFXII_PY_PACK:-}" "${FFXII_RAW_EXTRA:-}"
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.GambitsTest "${GAMBIT_DUMP:-}" "${REFERENCE_JSON:-}"
mkdir -p tests/out
"$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.GambitEditTest "${GAMBIT_DUMP:-}" tests/out/java_gambit_cmd.txt tests/out/java_gambit_set_cmd.txt
E="${FFXII_EVIDENCE:-}"   # private real-game dumps; tests skip without them
if [ -f "$E/save_27.bin" ] && [ -f "$E/save_185.bin" ]; then
  python -I tests/gen_build_fixture.py "$E/licence_nodes.json" tests/out/build before="$E/save_27.bin" after="$E/save_185.bin"
  "$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.BuildTest tests/out/build
  "$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.GambitPlanTest tests/out/build "${REFERENCE_JSON:-}" "${GAMBIT_DUMP:-}" "${BOARDS_JSON:-}"
  if [ -f "${BOARDS_JSON:-}" ]; then
    python -I tests/gen_boards_expected.py "${BOARDS_JSON:-}" "$E/save_27.bin" tests/out/boards_expected.json
    "$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.LicenceBoardsTest "${BOARDS_JSON:-}" tests/out/build/build_before.json tests/out/boards_expected.json
    "$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.LicenceRulesTest tests/out/build "${BOARDS_JSON:-}" "${FFXII_RULES_REPLICA:-}"
  fi
  if [ -f "$E/save_gear_1918.bin" ]; then
    python -I tests/gen_optimize_fixture.py "${BATTLE_PACK:-C:/Program Files (x86)/Steam/steamapps/common/FINAL FANTASY XII THE ZODIAC AGE/mods/deploy/ff12data/ps2data/image/ff12/test_battle/us/binaryfile/battle_pack.bin}" "$E/licence_nodes.json" "$E/save_gear_1918.bin" tests/out/optimize
    "$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.OptimizeTest tests/out/optimize "$E/licence_nodes.json"
  fi
  "$JDK/bin/java" -ea -cp tests/classes local.thor.ffxiicompanion.ApplyTest tests/out/build "${REFERENCE_JSON:-}" tests/out/java_gambit_setslot_cmd.txt
else
  echo "SKIP BuildTest: real dumps not present"
fi
