#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "Checking .ui documents..."
python3 scripts/check_ui.py

echo "Building Sproutwatch..."
./gradlew build

VERSION=$(grep '^version' gradle.properties | sed 's/version *= *//' | tr -d ' ')
JAR="build/libs/sproutwatch-${VERSION}.jar"
# The launcher keeps each patchline's user data apart: release in UserData, pre-release in data/pre-release.
PATCHLINE=$(grep '^patchline' gradle.properties | sed 's/patchline *= *//' | tr -d ' ')
if [ "$PATCHLINE" = "pre-release" ]; then
    MODS="$HOME/Library/Application Support/Hytale/data/pre-release/Mods"
else
    MODS="$HOME/Library/Application Support/Hytale/UserData/Mods"
fi

[ -f "$JAR" ] || { echo "Jar not found: $JAR"; ls build/libs/; exit 1; }

echo "Deploying ${JAR}..."
mkdir -p "$MODS"
rm -f "$MODS"/sproutwatch-*.jar
cp "$JAR" "$MODS/"
echo "✓ Deployed $(basename "$JAR") to $MODS"
