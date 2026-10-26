#!/usr/bin/perl
# 轮 8 · 11/17：工作区文件页 —— 选中态同步修复 + 硬编码中文资源化
#
# 修的两个真 bug：
#   B1 selectedPaths 不跟随列表刷新裁剪。批量删除/解压后列表变了，被删掉的路径还留在选中集合里，
#      于是 allSelected(= selectedPaths.size == state.entries.size) 永远为 false，
#      「全选」图标状态一直错，工具栏也停在一堆幽灵选中项上。
#   B2 批量操作跑起来后不退出多选，工具栏留着「导出 (0)」灰按钮，看着像卡死。
#      不能在点击时就退出——用户在确认框点取消会白白丢掉选择，所以改成观察 state.bulk / state.exporting。
#
# 另外把这一页所有硬编码中文字面量收进 round8_ext.xml（项目约定：字符串统一 stringResource）。
use strict; use warnings;

my $F = 'app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt';
open(my $in, '<', $F) or die "open $F: $!";
my $s = do { local $/; <$in> }; close $in;
my $n = 0;

sub rep {
    my ($old, $new, $tag) = @_;
    my $p = index($s, $old);
    die "anchor not found: $tag\n" if $p < 0;
    die "anchor not unique: $tag\n" if index($s, $old, $p + 1) >= 0;
    substr($s, $p, length($old)) = $new;
    $n++;
}

# ---- 0) 注释错字（轮 6 的 0002-wdp-a.patch 带进来的）----
rep(<<'OLD', <<'NEW', 'comment typo');
    // 文件在前：进工作区绠大多数时候是看文件，基本设置用得少
OLD
    // 文件在前：进工作区绝大多数时候是看文件，基本设置用得少
NEW

# ---- 1) 选中态同步 + 批量操作后自动退出多选 ----
rep(<<'OLD', <<'NEW', 'selection state');
    val selectedEntries = state.entries.filter { it.path in selectedPaths }
    val selectedFiles = selectedEntries.filterNot { it.isDirectory }
    val allSelected = state.entries.isNotEmpty() && selectedPaths.size == state.entries.size
    fun toggleSelection(entry: WorkspaceFileEntry) {
        selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
    }
    fun exitSelection() {
        selecting = false
        selectedPaths = emptySet()
    }
    BackHandler(enabled = selecting && isActive) {
        exitSelection()
    }
OLD
    val selectedEntries = state.entries.filter { it.path in selectedPaths }
    val selectedFiles = selectedEntries.filterNot { it.isDirectory }
    // 用过滤后的 selectedEntries 计数：selectedPaths 里可能有已删除的残留路径，直接比 size 会永远选不满
    val allSelected = state.entries.isNotEmpty() && selectedEntries.size == state.entries.size
    // 列表刷新（删除/解压/导入完成）后裁掉已不存在的路径，否则多选态会留着一堆幽灵选中项
    LaunchedEffect(state.entries) {
        if (selectedPaths.isEmpty()) return@LaunchedEffect
        val alive = state.entries.mapTo(HashSet()) { it.path }
        if (selectedPaths.any { it !in alive }) selectedPaths = selectedPaths.intersect(alive)
    }
    fun toggleSelection(entry: WorkspaceFileEntry) {
        selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
    }
    fun exitSelection() {
        selecting = false
        selectedPaths = emptySet()
    }
    // 批量操作真正跑起来才退出多选：点删除后在确认框里取消，选择还留着
    LaunchedEffect(state.bulk) {
        if (state.bulk != null) exitSelection()
    }
    LaunchedEffect(state.exporting) {
        if (state.exporting) exitSelection()
    }
    BackHandler(enabled = selecting && isActive) {
        exitSelection()
    }
NEW

# ---- 2) 多选工具栏：取消多选 / 全选 / 取消全选 ----
rep(<<'OLD', <<'NEW', 'toolbar descriptions');
                leadingContent = {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "取消多选")
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            selectedPaths = if (allSelected) emptySet() else state.entries.map { it.path }.toSet()
                        },
                    ) {
                        Icon(HugeIcons.CheckList, contentDescription = if (allSelected) "取消全选" else "全选")
                    }
                },
OLD
                leadingContent = {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.workspace_detail_exit_select),
                        )
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            selectedPaths = if (allSelected) emptySet() else state.entries.map { it.path }.toSet()
                        },
                    ) {
                        Icon(
                            HugeIcons.CheckList,
                            contentDescription = stringResource(
                                if (allSelected) {
                                    R.string.workspace_detail_unselect_all
                                } else {
                                    R.string.workspace_detail_select_all
                                }
                            ),
                        )
                    }
                },
NEW

# ---- 3) 导出按钮文案 ----
rep(<<'OLD', <<'NEW', 'export button label');
                    Text("导出 (${selectedFiles.size})")
OLD
                    Text(stringResource(R.string.workspace_detail_export_action_count, selectedFiles.size))
NEW

# ---- 4) 导出结果弹窗标题 ----
rep(<<'OLD', <<'NEW', 'export result title');
            title = { Text("导出结果") },
OLD
            title = { Text(stringResource(R.string.workspace_detail_export_result_title)) },
NEW

# ---- 5) 导出进度卡文案 ----
rep(<<'OLD', <<'NEW', 'exporting progress');
                text = "正在导出 $completed/$total",
OLD
                text = stringResource(R.string.workspace_detail_exporting_progress, completed, total),
NEW

# ---- 6) ZIP 默认名 / 兜底名资源化 ----
# remember{} 和 onConfirm{} 都不是 @Composable 作用域，取不了 stringResource，先把模板提到外面
rep(<<'OLD', <<'NEW', 'zip name template');
    zipTargets?.let { targets ->
        var zipName by remember(targets) {
            mutableStateOf("归档-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())}.zip")
        }
OLD
    zipTargets?.let { targets ->
        // 模板走资源，不能在 remember/onConfirm 这些非 Composable lambda 里现取，先提到外面
        val zipNameTemplate = stringResource(R.string.workspace_detail_zip_default_name)
        val zipFallbackName = stringResource(R.string.workspace_detail_zip_fallback_name)
        var zipName by remember(targets, zipNameTemplate) {
            mutableStateOf(
                String.format(
                    Locale.getDefault(),
                    zipNameTemplate,
                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()),
                )
            )
        }
NEW

rep(<<'OLD', <<'NEW', 'zip fallback name');
                val finalName = zipName.trim().ifBlank { "archive.zip" }.let { name ->
OLD
                val finalName = zipName.trim().ifBlank { zipFallbackName }.let { name ->
NEW

# ---- 7) 长按多选的无障碍标签 ----
rep(<<'OLD', <<'NEW', 'long click label');
                onLongClickLabel = "选择",
OLD
                onLongClickLabel = stringResource(R.string.workspace_detail_long_press_select),
NEW

open(my $o, '>', $F) or die "write $F: $!"; print $o $s; close $o;
my $lines = () = $s =~ /\n/g;
print "wdp files-page edits=$n, lines=$lines\n";
