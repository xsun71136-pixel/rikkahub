# 新版三项定制移植方案（先设计，后修改）

## 1. 基线与范围

- 工作目录：`/workspace/rikkahub`。
- 官方与 fork 当前基线：`00c8d53a5c74554c92848cf821d551a17f576995`（2026-09-28）。
- 参考：旧定制 `a2b2579` 及 `8974aea` 的 FULL-SPEC.md；不能用旧文件覆盖新版。
- 本轮顺序：静态调查 → 本方案 → 局部移植和增强 → 静态验证 → 提交并更新 GitHub。
- **不编译、不执行 Gradle、不触发 Actions、不打包发布。** 保留新版图表、workspace、工具审批、消息队列、文件夹等功能；不做 Firebase/签名/版本等无关改动。

## 2. 新版实况（不是照搬旧版结论）

1. `ChatService.handleMessageComplete` 的 collect 只更新内存，onCompletion 才保存数据库；进程死亡前的流式增量仍可能丢失。
2. **新版已经**在 `ConversationSession.finishGeneration` 使用 NonCancellable，也已修复活跃 session 重新加载覆盖及部分元数据覆盖问题；保留这些实现。
3. `Conversation.currentMessages` 和多处 UI 直接用 selectIndex 下标；非法下标/空节点仍有崩溃风险。
4. `GenerationLoop` 已有同消息 ID 的流式重试、StreamChunkHandlingException 隔离和取消检查；但次数/延迟硬编码，HTTP 失败通常不是 IOException。
5. `KeyRoulette` 只有随机/LRU；ProviderSetting 没有独立 Key 条目、启停、别名及健康管理。

## 3. 消息防丢方案

### 3.1 保存与竞态

- 新增 StreamDraftSaver，流式期间约 2.5 秒一次节流保存最新节点；持续有增量时持续工作，不能用 debounce 导致长流永不保存。
- 草稿仅更新节点，不写会话元数据、不反复刷新 FTS；一批节点更新放在 Room 事务中。
- 使用串行锁协调草稿与普通/终态保存。普通保存先清除过时待写草稿，再写终态，避免迟到草稿覆盖编辑、删除、分支切换或终态。
- 写失败保留 pending、后续重试；不得吞 CancellationException。思考状态只在落盘副本结束，不影响前台继续流式显示。
- Activity 退后台触发应用级 flush；崩溃回调限时尽力刷盘；保留新版 finishGeneration 的 NonCancellable。
- 明确边界：周期快照降低损失窗口，**不能承诺强杀、断电、磁盘故障时零丢失**。崩溃回调也不是保证。

### 3.2 索引与旧快照

- currentMessages 跳过空节点，selectIndex 钳制；显示组件先判空再访问。
- 分支切换以会话 ID/节点 ID 表达操作，从服务的最新内存状态计算，禁止旧页面快照整份覆盖。
- 检查生成期间切换分支的行为，避免生成结果写入错误分支。

## 4. 高级重试

- NetworkSetting 新增带默认值的 AutoRetryConfig，旧备份兼容；保留自动重试总开关。
- 独立设置面板支持次数、初始/最大等待、指数退避、抖动、网络错误、HTTP 状态码及错误文本规则。
- HTTP 状态优先识别；取消、下游转换/UI 错误、Key 池耗尽不进入普通网络重试。
- 网络重试与 Key 故障切换有界；单 Key 失效不要盲目重复请求。
- 保留新版固定回复 ID 与重试快照规则，不把第二次回复直接追加在半截回复后；已生成内容仍可由保存机制保留。中途重试是重放模型请求而非服务端续传，可能重复计费。

## 5. 多 Key

- OpenAI/Google/Claude 增加 `multiKeyEnabled/apiKeys/keyStrategy` 默认字段；兼容旧 apiKey 分隔串。
- 入口：供应商配置中的多 Key 管理；支持手动增删、批量粘贴导入、别名、启停、随机/顺序轮换、健康状态和单 Key 测试。
- 手动启用可清除健康停用；导入对话框不自动读剪贴板。
- 全禁用/全停用时明确失败，**不能回退使用已经禁用的旧 apiKey 字符串**。
- 401/402/明确 Key 错误停用，429 冷却；网络故障/普通 5xx 不惩罚 Key。缩小额度关键词，避免把模型上下文/速率配额误判为余额耗尽。
- 健康写入串行、原子替换；错误摘要不得保存原始 Key。
- 并行会话必须将失败归因于**本次请求实际选择的 Key**，不能用 provider 全局 lastKey 猜测；单 Key 测试也不能被全局轮换替换。
- Google 服务账号路径不强行套用 API Key 池。

## 6. 修改边界

- 新功能集中于 `ext/resilience`、`ext/retry`、`ext/keys`、`ai/provider/ProviderApiKeys.kt`、`ai/util/KeyHealth.kt`。
- 最小挂接：ChatService/ConversationRepository/Conversation/聊天显示与 VM、RikkaHubApp/RouteActivity/CrashHandler、GenerationLoop/PreferencesStore/网络设置页、ProviderSetting/KeyRoulette/ProviderConfigure。
- en/zh 字符串成对补充。保留所有官方新字段和方法参数。

## 7. 静态验收与交付

- git diff --check；新增/修改 Kotlin 的词法括号与导入检查；资源 XML 解析及新字符串引用核验。
- 检查原有新版关键入口未被覆盖、配置默认值、Provider 类型转换字段传递、全部禁用行为、取消语义、草稿终态顺序、并发 Key 归因。
- 增加纯源码静态门禁和必要单元测试源码（不运行 Kotlin/Gradle）。静态检查不等同于编译通过或设备实测。
- 修改完成补充本轮实施结果与剩余验证项。
- 推送前重新核实远端 HEAD；若他人更新则停止覆盖并重新对齐。仅快进提交，禁止 force push；提交信息带 `[skip ci]`，不主动启动构建。

## 8. 后续人工运行验证清单（本轮不执行）

- 长回复期间切页、退后台、主动停止、异常退出重开；核对文本/思考/工具状态。
- 429/500/超时重试、取消等待、错误次数上限；部分流后重试的显示与计费提示。
- 双会话并发使用同供应商，故障仅归属于真实失败 Key。
- Key 全禁用/全冷却/恢复、导入去重、Provider 类型转换、旧配置导入。

## 9. 实施记录与静态验收

### 9.1 实际落地与旧实现的差异

- 保留官方 `ConversationSession` 的初始化保护、NonCancellable 收尾、工具审批链、消息队列、图表工具和文件夹逻辑。没有移除 Firebase，没有改签名或构建脚本。
- 草稿改为应用作用域 IO 定时 worker（不是每个 chunk 重启 debounce），引用比较只清除自己写入的 pending；保存中收到的新 chunk 留待下一次写入。
- Room 内一笔事务处理整个节点差异；不重复刷新 FTS，不重建已删除的会话。
- 普通保存与草稿使用同组锁；保存期间持有 session 引用，防止切页后 session 被空闲回收；普通保存也保护为 NonCancellable。草稿失败保留 pending；崩溃钩子不再临时解析 Koin 依赖图。
- 修复非法 selectIndex、预览模式空节点、currentMessages 跳过空节点后 updateCurrentMessages 索引错位。切换分支时，若仍在生成，先停止并保存已有输出再切换。
- 高级重试配置界面：点击或长按“自动重试”行进入；旧网络开关继续控制普通重试。默认最多额外 3 次，1 秒起步、倍率 2、30 秒上限、±20% 抖动；可设置次数/等待/状态码/关键词。
- **新增默认保护：收到文本、思考或工具部分后不自动重放。** 可手动打开“允许收到部分内容后重试”；重放不是续传，可能替换内容和重复计费。
- 新增 `ProviderHttpException`，四个流式通道（OpenAI ChatCompletions/Responses、Claude、Google）即使返回 HTML、空错误体或无法解析 JSON，HTTP 状态也不会丢失；无错误原因的 onFailure 不再被当作正常流结束。
- 所有现有 Provider 的 API Key 认证统一读取 `KeyRoulette.next(ProviderSetting)`；生成重试每次显式选择并固定单 Key 副本，失败回写此副本的 Key；移除旧的 provider 全局 inFlight 槽位。两条并发会话和单 Key 测试不再串用/串罚。
- 空池/全禁用/全停用/全冷却明确失败，不回退旧 Key，不强行调用冷却 Key。Google Vertex 服务账号不进入 Key 池。
- 401/402/明确密钥或余额错误才停用；普通 403 模型权限错误和 5xx 不停 Key；429 普通速率配额冷却，`insufficient_quota` 按额度不足处理。停用 24 小时后允许再试；冷却从 1 分钟指数增加，上限 30 分钟。
- Key 故障切换独立于普通重试总开关，总尝试仍有上限（切换预算至少 8 次，与当前重试计数共用；不是无限尝试）。已收到部分内容时默认不切换重放。
- 健康文件使用 AtomicFile、串行修改，错误摘要只保存分类不保存上游错误原文。配置 Key 本身仍是 app 私有目录中的本地数据，不等同于硬件密钥库加密。
- 多 Key 管理支持启停/恢复、别名、单条编辑、删除、粘贴批量导入去重、随机/顺序轮换、单 Key 测试、冷却状态。单条编辑拒绝多个 Key；批量导入不自动读剪贴板。
- en/zh 新增 63 对资源，并更新原自动重试说明。

### 9.2 检查方式与结果

- 静态脚本：`scripts/check_custom_static.py`。
- 结果文件：`docs/custom/static-check-report.json`。
- 当前 253 项静态检查通过：diff 空白、24 个资源 XML、资源重名/引用/格式占位符、配置字段/认证路径/取消路径/保存顺序等源码约束。
- 对 36 个新增或修改 Kotlin 文件进行 Tree-sitter 语法扫描：没有新增解析问题。两个官方文件已有语法解析器局限（ProviderSetting 的函数类型注解、GoogleProvider 的既有表达式）；与基线分别同为 6 / 2 个解析诊断。这不是 Kotlin 编译器结论。
- 新增 4 个测试源码文件、18 个 `@Test`，涵盖空/禁用 Key 池、固定请求副本、旧配置、错误分类、退避、空节点、写入中更新、草稿/终态顺序、失败重试；**遵照要求没有编译或运行这些 Kotlin 测试**。
- 仅使用预编译 Tree-sitter Python wheel 做静态解析；没有运行 Gradle、Android Lint、Kotlin 编译器或 Actions。

### 9.3 使用入口与验证边界

1. 消息周期保存自动生效，不新增开关。
2. 设置 → 偏好设置 → 网络 → 点击/长按自动重试，保存高级配置。
3. 提供商配置 → 多 Key 模式 → 管理 Key；恢复自动停用 Key 可直接重新打开条目开关。
4. 本轮覆盖聊天 GenerationLoop 的自动重试和 Key 健康归因、Key 管理器的测试归因；模型列表/图片/嵌入等路径使用相同的启停与轮换选择，但不额外新增业务层自动重试循环。
5. 存储故障、强杀和硬件断电不能承诺零丢失；周期约 2.5 秒并非严格实时 SLA。回前台和进程重启后的完整行为仍须按第 8 节人工验证。
6. 不主动触发云端构建；仓库原有定时工作流未改动。同步采用非强制快进更新，提交消息附 `[skip ci]`。
