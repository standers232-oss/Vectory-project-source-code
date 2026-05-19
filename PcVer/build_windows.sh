#!/usr/bin/env bash
set -euo pipefail
mkdir -p build dist
x86_64-w64-mingw32-windres src/version.rc -O coff -o build/version.res
x86_64-w64-mingw32-g++ -std=c++17 -O2 -static -static-libgcc -static-libstdc++ -municode -mwindows src/NexusMassPC.cpp build/version.res -o dist/NexusMassPC.exe -lwinhttp -lcomctl32 -lgdi32 -luser32
