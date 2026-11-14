#!/bin/bash
# usage: disable-n5.sh <log>
set -u
R=/Users/achyutpatel/Developer/Cloud/ScholarChain
S=/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c3d10439-24df-4d91-8634-3d8a72c4c764/scratchpad/wo2
F=$R/modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala
LOG=$1
cp "$F" "$S/Verifier.scala.bak"
shasum -a 256 "$F" > "$S/verifier.sha"
python3 -I "$S/n5patch.py" "$F" || { echo "patch failed" >> "$LOG"; exit 1; }
diff "$S/Verifier.scala.bak" "$F" > "$LOG"
cd $R && SPARK_LOCAL_IP=127.0.0.1 sbt -batch -J-Xmx4g "rag/test" >> "$LOG" 2>&1
echo "sbt exit $?" >> "$LOG"
cp "$S/Verifier.scala.bak" "$F"
shasum -a 256 -c "$S/verifier.sha" >> "$LOG" 2>&1
