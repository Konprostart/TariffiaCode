#!/usr/bin/env bash
# Compiles and runs the router on a plain JVM, with a mock upstream behind it.
#
# Why this exists: the router is an Android module, and without an Android SDK there was no way to
# run it at all outside the app - so nothing could ever point a real client at it. This builds the
# production sources directly (not copies), leaving out only RouterKeys.kt, which is the one file that
# cannot exist off a phone.
#
# The result prints the port and token to point Claude Code at, with:
#   ANTHROPIC_BASE_URL=http://127.0.0.1:$PORT
#   ANTHROPIC_AUTH_TOKEN=$TOKEN
#
# Usage: scripts/run-router-harness.sh [path-to-claude_tiers.json]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TABLE="${1:-$REPO_ROOT/app/src/main/assets/claude_tiers.json}"
CACHE="${ROUTER_HARNESS_CACHE:-/tmp/opencode/harness}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
SERIALIZATION_VERSION="${SERIALIZATION_VERSION:-1.7.3}"
OKHTTP_VERSION="${OKHTTP_VERSION:-4.12.0}"
OKIO_VERSION="${OKIO_VERSION:-3.6.0}"

ROUTER="$REPO_ROOT/app/src/main/java/com/konprostart/tariffiacode/core/router"
HARNESS="$REPO_ROOT/tools/router-harness"

# RouterKeys is excluded on purpose: it needs an Android Context and an encrypted SharedPreferences.
# Everything else the router needs is plain Kotlin.
PURE_ROUTER=(
  AnthropicRequest CircuitBreaker KeyStore OpenAiCompatibleAdapter
  ProviderAdapters RouterConfigTable RouterEngine RouterModels RouterServer
)

log() { printf '  %s\n' "$*"; }

mkdir -p "$CACHE/lib"

if [ ! -x "$CACHE/kotlinc/bin/kotlinc" ]; then
  log "skidam kotlinc $KOTLIN_VERSION (jednom)"
  curl -sL --max-time 900 -o "$CACHE/kc.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$CACHE/kc.zip" "$CACHE"
  rm -f "$CACHE/kc.zip"
  chmod +x "$CACHE"/kotlinc/bin/*
fi

fetch() {
  local name="$1" path="$2" dest="$CACHE/lib/$1"
  [ -f "$dest" ] && return
  log "skidam $name"
  curl -sL --max-time 300 -o "$dest" "https://repo1.maven.org/maven2/$path"
}

fetch "kotlin-stdlib-$KOTLIN_VERSION.jar" "org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar"
fetch "kotlinx-serialization-core-jvm-$SERIALIZATION_VERSION.jar" "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/$SERIALIZATION_VERSION/kotlinx-serialization-core-jvm-$SERIALIZATION_VERSION.jar"
fetch "kotlinx-serialization-json-jvm-$SERIALIZATION_VERSION.jar" "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/$SERIALIZATION_VERSION/kotlinx-serialization-json-jvm-$SERIALIZATION_VERSION.jar"
fetch "okhttp-$OKHTTP_VERSION.jar" "com/squareup/okhttp3/okhttp/$OKHTTP_VERSION/okhttp-$OKHTTP_VERSION.jar"
fetch "okio-jvm-$OKIO_VERSION.jar" "com/squareup/okio/okio-jvm/$OKIO_VERSION/okio-jvm-$OKIO_VERSION.jar"

CP="$CACHE/lib/kotlin-stdlib-$KOTLIN_VERSION.jar"
CP="$CP:$CACHE/lib/kotlinx-serialization-core-jvm-$SERIALIZATION_VERSION.jar"
CP="$CP:$CACHE/lib/kotlinx-serialization-json-jvm-$SERIALIZATION_VERSION.jar"
CP="$CP:$CACHE/lib/okhttp-$OKHTTP_VERSION.jar"
CP="$CP:$CACHE/lib/okio-jvm-$OKIO_VERSION.jar"

SOURCES=()
for name in "${PURE_ROUTER[@]}"; do SOURCES+=("$ROUTER/$name.kt"); done
SOURCES+=("$HARNESS/Harness.kt")

log "kompajliram ${#SOURCES[@]} fajlova (produkcijski kod, ne kopije)"
rm -rf "$CACHE/out"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}" \
  "$CACHE/kotlinc/bin/kotlinc" "${SOURCES[@]}" \
  -Xplugin="$CACHE/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar" \
  -classpath "$CP" \
  -d "$CACHE/out" 2>&1 | grep -vE '^(warning|info):' || true

[ -f "$CACHE/out/harness/Harness.class" ] || { echo "kompajliranje nije uspjelo" >&2; exit 1; }

echo
echo "  Mock upstream se nalazi iza rotera; nijedan stvarni ključ nije potreban."
echo "  Claude Code:"
echo "    ANTHROPIC_BASE_URL=http://127.0.0.1:<ROUTER_PORT> \\"
echo "    ANTHROPIC_AUTH_TOKEN=<ROUTER_TOKEN> claude -p '...'"

exec "${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}/bin/java" \
  -cp "$CACHE/out:$CP" harness.Harness "$TABLE"