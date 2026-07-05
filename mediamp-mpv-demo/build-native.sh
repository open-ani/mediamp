#!/bin/zsh
# Compiles the mpv → IOSurface → Metal JNI bridge (macOS only).
# Usage: build-native.sh <source.mm> <output.dylib>
set -e

SRC="$1"
OUT="$2"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
MPV_PREFIX="${MPV_PREFIX:-/opt/homebrew}"

mkdir -p "$(dirname "$OUT")"

clang++ \
  -std=c++17 -fobjc-arc -O2 -shared -fPIC \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/darwin" \
  -I "$MPV_PREFIX/include" \
  -L "$MPV_PREFIX/lib" -lmpv \
  -framework Foundation \
  -framework Metal \
  -framework IOSurface \
  -framework OpenGL \
  -framework QuartzCore \
  "$SRC" -o "$OUT"

echo "built $OUT"
