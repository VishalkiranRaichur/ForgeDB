#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
rm -rf build/test-classes
mkdir -p build/test-classes
find src/main/java src/test/java -name '*.java' -print0 | xargs -0 javac --release 17 -d build/test-classes
java -cp build/test-classes io.forgedb.BPlusTreeTest
java -cp build/test-classes io.forgedb.ForgeDBSmokeTest
java -cp build/test-classes io.forgedb.ForgeDBStressTest
