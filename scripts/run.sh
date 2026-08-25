#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ ! -f "$ROOT/build/forgedb.jar" ]]; then
  "$ROOT/scripts/build.sh"
fi
exec java -jar "$ROOT/build/forgedb.jar"
