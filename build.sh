#!/usr/bin/env bash
# Build Code Graph Search fat JAR
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
mvn package -DskipTests "$@"
echo ""
echo "Built: app/target/code-graph-search.jar"
