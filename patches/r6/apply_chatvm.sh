#!/bin/bash
# ChatVM：暴露 compressEvents（只转发本会话）+ 两个 import
set -euo pipefail
F="$1"
perl "$(dirname "$0")/vm_patch.pl" "$F"
grep -q 'val compressEvents: Flow<CompressEvent>' "$F"
echo "chatvm ok: $(wc -l < "$F") lines"
