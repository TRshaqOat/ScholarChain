#!/bin/bash
# usage: disable.sh <label>   (patches Verifier.scala, runs rag/test, restores, checks sha256)
set -u
label=$1
repo=/Users/achyutpatel/Developer/Cloud/ScholarChain
file=modules/rag/src/main/scala/scholarchain/rag/runtime/Verifier.scala
logs=$repo/scratch/phase-11-logs/repair-02
here=/private/tmp/claude-501/-Users-achyutpatel-Developer-Cloud-ScholarChain/c8242cd7-266e-4b64-b940-735f198f56c6/scratchpad/repair-02-partA
cd "$repo"
shasum -a 256 "$file" > "$here/Verifier.sha256"
cp "$file" "$here/Verifier.scala.orig"
{
  echo "label: $label"; echo "file: $file"; echo "sha256 before: $(cut -d' ' -f1 "$here/Verifier.sha256")"
  python3 -I "$here/patch.py" "$file" "$label" || { cp "$here/Verifier.scala.orig" "$file"; echo "patch failed, restored"; exit 2; }
  echo "patched sha256: $(shasum -a 256 "$file" | cut -d' ' -f1)"
  diff "$here/Verifier.scala.orig" "$file"
  SPARK_LOCAL_IP=127.0.0.1 sbt -J-Xmx4g -batch "rag/test" > "$logs/disable-$label.sbt.log" 2>&1
  echo "sbt exit: $?"
  grep -E "\*\*\* FAILED|Tests: " "$logs/disable-$label.sbt.log" | cut -c1-300
  cp "$here/Verifier.scala.orig" "$file"
  echo "restore check:"; shasum -a 256 -c "$here/Verifier.sha256"
} > "$logs/disable-$label.log" 2>&1
cat "$logs/disable-$label.log"
