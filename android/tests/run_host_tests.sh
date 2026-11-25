#!/usr/bin/env bash
# Builds and runs, on a normal Linux/macOS machine (no phone, no NDK):
#   1. the Switch app's own unit tests (tests/*.cpp), and
#   2. the Android engine test (android/tests/engine_host_test.cpp): installs mods from folders and checks
#      that PATCH1.CPK lands in <mods>/Compendium/romfs/CPK/, load order, on/off and removal work.
# libarchive and libcurl are replaced by stubs (host_stubs.cpp), so nothing has to be installed but g++ and zlib.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="${TMPDIR:-/tmp}/compendium-host-tests"
mkdir -p "$OUT"
CORE=$(ls source/core/*.cpp | grep -v 'archive\.cpp\|http_curl\.cpp')
FLAGS="-std=c++20 -O1 -w -Isource/core -Isource/third_party"

echo "== core unit tests"
g++ $FLAGS -DMM_FIXTURES="\"$PWD/tests/fixtures\"" $CORE android/tests/host_stubs.cpp tests/*.cpp -o "$OUT/core_tests" -lz
"$OUT/core_tests" | tail -3

echo "== Android engine test"
g++ $FLAGS -DMM_ANDROID $CORE android/tests/host_stubs.cpp android/app/src/main/cpp/engine.cpp \
    android/tests/engine_host_test.cpp -o "$OUT/engine_test" -lz
"$OUT/engine_test"
