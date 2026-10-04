#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "Checking .ui documents..."
python3 scripts/check_ui.py

echo "Building Sproutwatch..."
./gradlew build

VERSION=$(grep '^version' gradle.properties | sed 's/version *= *//' | tr -d ' ')
JAR="build/libs/sproutwatch-${VERSION}.jar"
MODS="$HOME/Library/Application Support/Hytale/UserData/Mods"

[ -f "$JAR" ] || { echo "Jar not found: $JAR"; ls build/libs/; exit 1; }

echo "Deploying ${JAR}..."
mkdir -p "$MODS"
rm -f "$MODS"/sproutwatch-*.jar
cp "$JAR" "$MODS/"
echo "✓ Deployed $(basename "$JAR") to $MODS"
