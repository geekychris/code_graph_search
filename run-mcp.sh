#!/usr/bin/env bash
# Start Code Graph Search as an MCP stdio server.
# This is the script you point Claude Desktop / Claude Code at.
#
# Usage:
#   ./run-mcp.sh                      # uses config.yaml (or defaults)
#   ./run-mcp.sh --config my.yaml     # use a specific config file

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/app/target/code-graph-search.jar"

if [ ! -f "$JAR" ]; then
  echo "Fat JAR not found at $JAR" >&2
  echo "Build first:  mvn package -DskipTests" >&2
  exit 1
fi

exec java --enable-preview -jar "$JAR" --mcp-stdio "$@"
