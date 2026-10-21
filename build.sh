#!/bin/bash
# Compiles localpickew into build/localpickew.jar (Java 8, no Gradle needed).
set -e
cd "$(dirname "$0")"

rm -rf build/classes
mkdir -p build/classes

javac -source 1.8 -target 1.8 -encoding UTF-8 -cp 'libs/*' -d build/classes \
    $(find src -name '*.java')

# Class-Path entries are relative to the jar's own directory (build/).
cp_entries=$(cd libs && ls *.jar | sed 's|^|../libs/|' | tr '\n' ' ')
cat > build/manifest.txt <<EOF
Main-Class: edu.caltech.scedc.localpickew.LocalPickEW
Class-Path: ${cp_entries}
EOF

jar cfm build/localpickew.jar build/manifest.txt -C build/classes .
echo "Built build/localpickew.jar"
