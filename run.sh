#!/usr/bin/env bash
# Start Code Graph Search (REST + UI + MCP HTTP)
#
# Usage:
#   ./run.sh                      # uses config.yaml (or defaults if missing)
#   ./run.sh --config my.yaml     # use a specific config file

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/app/target/code-graph-search.jar"

if [ ! -f "$JAR" ]; then
  echo "Fat JAR not found at $JAR"
  echo "Build first:  mvn package -DskipTests"
  exit 1
fi

exec java --enable-preview -jar "$JAR" "$@"
