#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
rm -rf out-test
mkdir -p out-test
find src/main/java src/test/java -name '*.java' -print0 | xargs -0 javac --release 17 -d out-test
java -cp out-test io.forgedb.ForgeDBSmokeTest
