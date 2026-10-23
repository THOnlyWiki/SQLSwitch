#!/usr/bin/env sh
set -eu
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$DIR/build/libs/SQLSwitch-1.0.0-all.jar"
if [ ! -f "$JAR" ]; then
  echo "Building SQLSwitch..." >&2
  (cd "$DIR" && ./gradlew fatJar)
fi
exec java -jar "$JAR" "$@"
