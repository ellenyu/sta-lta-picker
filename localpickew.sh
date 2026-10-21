#!/bin/bash
# Runs the local PickEW picker. See README.md for options.
home="$(cd "$(dirname "$0")" && pwd)"
exec java -Djava.awt.headless=true -Dlocalpickew.home="$home" -jar "$home/build/localpickew.jar" "$@"
