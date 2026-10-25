#!/bin/bash
# 工作区详情页：「基本」「文件」两个 tab 对调（文件在前）
set -euo pipefail
F="$1"
perl -0pi -e 's/    BASIC\(HugeIcons\.Settings03, R\.string\.workspace_detail_tab_basic\),\n    FILES\(HugeIcons\.File02, R\.string\.workspace_detail_tab_files\);/    \/\/ 文件在前：进工作区绠大多数时候是看文件，基本设置用得少\n    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files),\n    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic);/' "$F"
grep -q "FILES(HugeIcons.File02" "$F"
echo "wdp ok: $(wc -l < "$F") lines"
