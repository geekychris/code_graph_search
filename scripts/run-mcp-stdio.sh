#!/bin/bash
JAR="$(dirname "$0")/../app/target/code-graph-search.jar"
CONFIG="${1:-config.yaml}"
exec java -Xmx2g --enable-preview -jar "$JAR" --config "$CONFIG" --mcp-stdio
