#!/bin/bash
# usage: disable.sh <label> <file> <old> <new>
set -u
label=$1; file=$2; old=$3; new=$4
repo=/Users/achyutpatel/Developer/Cloud/ScholarChain
logs=$repo/scratch/phase-11-logs/repair-01/partB
here=$(cd "$(dirname "$0")" && pwd)
cd "$repo"
before=$(shasum -a 256 "$file" | cut -d' ' -f1)
cp "$file" "$here/backup.scala"
{
  echo "label: $label"; echo "file: $file"; echo "sha256 before: $before"
  python3 -I "$here/patch.py" "$file" "$old" "$new" || { echo "patch failed"; exit 2; }
  echo "patched sha256: $(shasum -a 256 "$file" | cut -d' ' -f1)"
  SPARK_LOCAL_IP=127.0.0.1 timeout 900 sbt -J-Xmx4g -batch "rag/test" > "$logs/disable-$label.sbt.log" 2>&1
  echo "sbt exit: $?"
  grep -E "\*\*\* FAILED|Tests: " "$logs/disable-$label.sbt.log" | cut -c1-300
  cp "$here/backup.scala" "$file"
  after=$(shasum -a 256 "$file" | cut -d' ' -f1)
  echo "sha256 restored: $after"
  [ "$before" = "$after" ] && echo "RESTORE OK" || echo "RESTORE MISMATCH"
} > "$logs/disable-$label.log" 2>&1
cat "$logs/disable-$label.log"
