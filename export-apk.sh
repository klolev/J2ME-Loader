#!/usr/bin/env bash
#
# Builds a standalone Android app from a MIDlet suite: the emulator and the suite in one
# APK, with the suite's own name and icon, launching straight into the game.
#
#   ./export-apk.sh game.jar
#   ./export-apk.sh game.jad --debug
#   ./export-apk.sh game.jar --package com.example.mygame
#
# A JAD whose MIDlet-Jar-URL points at a remote file is downloaded, exactly as the emulator
# would when installing the suite on a device.

set -euo pipefail

usage() {
	cat >&2 <<'EOF'
Usage: ./export-apk.sh <suite.jar|suite.jad> [options]

Options:
  --debug                 Build the debug variant (no shrinking; faster, larger APK)
  --release               Build the release variant (default)
  --package <id>          Application id, instead of one derived from the suite name
  --version-code <n>      Android version code, instead of one derived from MIDlet-Version
  -h, --help              Show this message
EOF
	exit 2
}

[ $# -ge 1 ] || usage

SUITE=""
VARIANT="release"
GRADLE_ARGS=()

while [ $# -gt 0 ]; do
	case "$1" in
		--debug) VARIANT="debug"; shift ;;
		--release) VARIANT="release"; shift ;;
		--package)
			[ $# -ge 2 ] || usage
			GRADLE_ARGS+=("-PmidletPackage=$2"); shift 2 ;;
		--version-code)
			[ $# -ge 2 ] || usage
			GRADLE_ARGS+=("-PmidletVersionCode=$2"); shift 2 ;;
		-h|--help) usage ;;
		-*) echo "Unknown option: $1" >&2; usage ;;
		*)
			[ -z "$SUITE" ] || { echo "Only one suite can be exported at a time." >&2; usage; }
			SUITE="$1"; shift ;;
	esac
done

[ -n "$SUITE" ] || usage
[ -f "$SUITE" ] || { echo "No such file: $SUITE" >&2; exit 1; }

# Gradle resolves -Pmidlet against the directory it was started in, but the wrapper is run
# from the project root, so hand it a path that does not depend on either.
SUITE="$(cd "$(dirname "$SUITE")" && pwd)/$(basename "$SUITE")"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

case "$VARIANT" in
	debug) TASK="assembleMidletDebug" ;;
	release) TASK="assembleMidletRelease" ;;
esac

# The wrapper is checked in without its executable bit, so run it through bash when the
# working copy did not get one either.
if [ -x ./gradlew ]; then
	GRADLE=(./gradlew)
else
	GRADLE=(bash ./gradlew)
fi

"${GRADLE[@]}" "$TASK" "-Pmidlet=$SUITE" "${GRADLE_ARGS[@]}"

OUTPUT_DIR="app/build/outputs/apk/midlet/$VARIANT"
APK="$(ls -t "$OUTPUT_DIR"/*.apk 2>/dev/null | head -1 || true)"
if [ -z "$APK" ]; then
	echo "The build reported success but produced no APK in $OUTPUT_DIR." >&2
	exit 1
fi

echo
echo "APK: $APK"
echo "Install it with: adb install -r \"$APK\""
