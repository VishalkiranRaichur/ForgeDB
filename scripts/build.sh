#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
rm -rf build
mkdir -p build/classes
find src/main/java -name '*.java' -print0 | xargs -0 javac --release 17 -d build/classes
jar --create --file build/forgedb.jar --main-class io.forgedb.ForgeDB -C build/classes .
echo "Built: $ROOT/build/forgedb.jar"
