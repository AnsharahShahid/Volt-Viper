#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/VoltViper.java
java -cp out VoltViper
