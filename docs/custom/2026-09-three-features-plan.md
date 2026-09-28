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
