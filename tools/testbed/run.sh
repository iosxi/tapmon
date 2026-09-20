#!/usr/bin/env bash
# 判定器 (TapDetector) だけを取り出して、波形を流し込んで確かめる。
# 端末も Android も要らない。android.os.Handler / SystemClock は偽物に差し替える。
#
#   使い方:  bash tools/testbed/run.sh
#
# 流すのは二種類。
#   ・作った波形  … 叩き・歩行・持ち上げ・画面側を叩く、など筋書きごと
#   ・実機の波形  … traces.csv (Xperia XQ-FS44 で記録した背面タップ 22 本)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
rm -rf out
mkdir -p out io/tapmon
cp ../../app/src/main/java/io/tapmon/TapDetector.java io/tapmon/
javac -encoding UTF-8 -nowarn -d out android/os/*.java io/tapmon/*.java
java -Dfile.encoding=UTF-8 -cp out io.tapmon.Driver
