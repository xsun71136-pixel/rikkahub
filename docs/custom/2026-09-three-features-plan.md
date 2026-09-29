# RikkaHub 定制功能唯一维护文档（R3 紧凑 Key 管理）

# R3：恢复紧凑 Key 管理，按错误独立配置

> **本节是 Key 管理的最新规范。** 用户明确否定 R2 的大卡片、全高窗口和复杂首屏；R3 收回这部分 UI，不再以“控件更多、页面更大”为目标。R2 的自动重试界面与 R1 消息防丢链不变。

## R4 最终打磨：自动重试、多 Key、会话可靠性（2026-09-29）

本轮目标不是削弱已有功能，而是降低首屏复杂度、修正错误归因，并审查生成生命周期。

### 自动重试 UI

`AutoRetrySettingsSheet` 改为有固定标题/底部操作栏的紧凑窗口，正文限制为屏幕约88%，分为“基本/规则”两页。基本页只显示：总开关、确定性等待预览、推荐/快速/耐心预设、重试时序摘要、安全摘要；状态码、重试词、停止词进入规则页或单独编辑对话框。原有字段全部保留：网络异常、状态码、重试关键词、停止关键词、最大重试次数、初始等待、指数倍率、最大等待、抖动、半截响应重放。

- 点“时序”精确输入次数、初始等待、倍率、最大等待和抖动；最大等待不能小于初始等待。
- 点“安全”修改网络错误与半截响应策略；半截响应重放仍默认关闭，开启必须确认。
- 等待预览关闭抖动计算，不会每次打开页面显示不同数字；真实请求仍按配置抖动。
- 取消、放弃修改、恢复默认和保存语义保留；保存才写入设置，子编辑器只是修改草稿。
- 自动重试实际判定未削弱：取消、Key池耗尽和下游消息处理异常仍不重试；HTTP状态码优先于IOException包装；5xx/429可按状态码重试，停止词仍优先阻止重试。

### 外部“测试连接”与正常对话的 Key 归因

修复前，测试连接的非流式、流式、工具三个并发请求都直接接收多 Key Provider，每个请求会自行重新选Key；失败也没有把实际Key提交到健康注册表。因此外部测试失败不会停用对应Key，且三个测试可能测试不同Key。

现在一次测试先从健康池选择一个Key，构造 `withSingleApiKeyForRequest()` 副本，三个探测共用此副本并各自最多30秒。测试结果按实际选中的Key处理：

```text
401 / 明确无效密钥 / 额度不足 / 429    → reportFailure(实际Key)
普通403 / 5xx / 网络异常 / 超时 / 取消 → 不处罚
三个探测全部成功                     → 清除该Key旧健康限制
```

三个探测中多个同时返回Key级错误只报告一次；混合成功/普通错误不会因为其中一个成功而清除健康记录。超时和取消不会处罚。正常对话原有 `GenerationLoop` 的请求级固定Key、失败归因、切换备用Key和全部耗尽提示继续保留，不依赖外部测试路径。

### 生成、异常、切换消息可靠性审查

- `StreamDraftSaver` 仍以会话ID分片加锁，周期约2.5秒保存；普通保存、终态保存、草稿保存共享锁，失败保留pending快照。
- 生成成功、异常、取消均经过 `finishGeneration` 的 `NonCancellable` 终态保存；退后台、进程崩溃前继续执行尽力flush。
- 会话内对消息节点列表身份记录运行时版本。延迟数据库/页面旧快照不能覆盖已收到的新流；被拒绝的旧快照也不能触发附件删除。已有消息节点、非法分支索引、空损坏节点的服务层和聊天列表访问使用安全访问器，跳过空节点或钳制索引，避免渲染期闪退。
- 分支切换仍先停止当前生成，再从服务层读取最新会话并校验node/index；不接受页面陈旧对象整段覆盖。
- 新增/扩展回归测试：自动重试边界与等待上限、Key策略普通403/5xx/无效Key、草稿写入失败与终态顺序、会话初始化竞态、取消/异常保留半截内容、旧快照不能覆盖新流。CI新增执行 `RetryPolicyTest`、`StreamDraftSaverTest`、`MessageResilienceTest`、`ConversationSessionTest`。

### 验收边界

本轮必须以云端 Kotlin/Android 测试和 `assembleRelease` 成功为打包前提。静态门禁不能代替编译；APK下载后还要校验ZIP CRC、asset SHA-256、APK v2内容摘要和RSA签名。没有真实设备安装、切换消息、杀进程和外部测试连接实测前，不宣称UI观感和全部设备生命周期已经验收。


## R4.0 最终打磨构建与安装测试包（2026-09-29）

- 功能源码提交：`bc3b410e7606a39e443d637643edf3396fe91804`。
- CI/Firebase Debug 修复提交：`584e8bc5074eb6f3c6b6bc99c70d8409de2556ed`。
- Actions：https://github.com/xsun71136-pixel/rikkahub/actions/runs/36517650377 。
- AI Key policy tests：成功。
- 应用可靠性/重试测试：成功；包含 `RetryPolicyTest`、`StreamDraftSaverTest`、`MessageResilienceTest`、`ConversationSessionTest`。
- `assembleRelease`：成功；Nightly 发布成功。
- 静态门禁：476项通过，0失败。
- Debug 测试第一次失败的原因是 CI 占位 `google-services.json` 缺少 `me.rerere.rikkahub.debug` client，不是源码错误；已在CI准备步骤为真实Secret和占位配置补齐debug client，重跑成功。
- 设备UI、切换消息、杀进程和外部测试连接尚未由助手实际操作验证；APK交付给用户进行安装测试。

产物目录：`/workspace/RikkaHub-R4-584e8bc5/`。

| APK | 字节数 | SHA-256 |
|---|---:|---|
| `app-arm64-v8a-release.apk` | 40,408,494 | `afe9fd1f6380e251c42e17baebf9df105a88787a7e99c6a65603acd9c4492636` |
| `app-universal-release.apk` | 50,328,596 | `cbcb823c10b9e185240072db959777463428685a07ff30877eb227525033d59a` |
| `app-x86_64-release.apk` | 41,074,984 | `f3dd42c911cf35de334f4f8574780515cc811fd9069bb7d0e36f9932ef673246` |

三个APK已通过ZIP CRC、Release asset SHA-256、APK v2 Signing Block、v2内容摘要及OpenSSL RSA签名验证。签名证书SHA-256：`A3034D498769D47DEB45826D222CBBFC633A74E00CAECAB4F8ABB32B2812CD33`；算法：RSA PKCS#1 v1.5 SHA-256 (`0x0103`)。

安装建议：优先安装`app-arm64-v8a-release.apk`；如果设备ABI不确定，安装`app-universal-release.apk`。不要同时覆盖安装不同签名的官方包；本包使用RikkaHub项目既有签名。

本轮重点验收：先测试外部“测试连接”中的401/429/403/5xx和超时，再做正常对话；确认401/额度/429按实际Key进入停用或冷却，普通403/5xx/网络异常不误停用。随后在流式生成中切换会话、返回原会话、取消、杀后台再打开，确认半截内容和最终消息仍在。

## R3.0 构建、验证与工作区交付（2026-09-29）

- 源码提交：`69d2594378440a4f49e7bc243c87732b5d33f722`。
- Actions：https://github.com/xsun71136-pixel/rikkahub/actions/runs/36505508043 。测试、Release编译、Nightly发布均成功。
- 云端选定测试：KeyManagementPolicyTest 16项 + ManagedKeysTest 7项，共23项；单测步骤成功，构建日志约1分5秒。
- `assembleRelease`：成功，日志6分21秒；未本地编译。
- 静态：441项通过，0失败；42个Kotlin参与、24个XML；R3新增35对文案，累积195对。
- APK继续使用原项目签名，版本仍2.5.5/190。未卸载、安装或修改设备上的应用数据。
- **设备UI实测未执行**。不能将本节结论理解为用户已经认可观感，下一次仍应以用户实际使用反馈为准。

下载目录：`/workspace/RikkaHub-R3-69d25943/`。

| APK | 字节数 | SHA-256 |
|---|---:|---|
| `app-arm64-v8a-release.apk` | 40,403,914 | `dbbf5f8d100aaab3273e30fbc98d8ff7e557b976f4488c78e8a1ee97c9cb2f2a` |
| `app-universal-release.apk` | 50,324,016 | `710cbe9a12757f9022caca3dc28e601d25f2a23558805cb6b3ab724beedac138` |
| `app-x86_64-release.apk` | 41,070,404 | `53f948e6cdc1830b756946d299d9aa8785789de75caaa52285343a6b0546c6f8` |

三个APK：ZIP全CRC、GitHub asset SHA-256、v2签名块内容摘要、OpenSSL RSA签名均验证通过。证书SHA-256仍是`A3034D498769D47DEB45826D222CBBFC633A74E00CAECAB4F8ABB32B2812CD33`。

目录附`SHA256SUMS`、`artifacts.json`、`signature-verification.json`、主MD副本、`COMMITS.txt`。仓库证据为`docs/custom/r3-build-report.json`；工作区脚本与完整云端日志在`/workspace/r3/`。

源码发布与文档提交分开记录；后续文档快进提交不改变此次APK。静态报告的compiled=false仍只表示静态脚本不编译，真实云端结果以上述run为准。

## R3.1 本轮改动边界

- 基线：R2 源码25c904bd、文档0c311ef；本地快照5d86c01。
- 先行设计：`docs/custom/2026-09-r3-compact-keys.md`。
- 备份：`/workspace/r3/pre-r3.tar.gz`（Git跟踪快照，不含签名文件与令牌）。
- **未改** AutoRetrySettingsSheet、RetryPolicy、StreamDraftSaver、GenerationLoop 的尝试/取消/部分回复逻辑、认证实现、签名、版本号与 Firebase。
- 目标是：默认少看、常用易找、复杂项点开才看，而不是删掉 R2 的防串Key、超时、去重等修复。

## R3.2 多 Key 默认界面

```text
管理 Key                    关闭
可用 3 / 共 5
[随机] [顺序轮询]
添加  导入              搜索  ⋮
──────────────────────────
Key别名        启停   测试  ⋮
掩码 / 有异常时显示状态
──────────────────────────
下一条…
```

- 不再用 `PolicyScreen`，改为 `CompactKeySheet` 底部弹窗。
- 小列表随内容高度；大列表限制正文滚动，最大内容高度为屏幕高度82%，最大宽560dp；不为一两个Key强制占满屏幕。
- 该82%是代码中的内容上限，系统栏、键盘、拖动柄仍由Compose约束，不能视作所有设备实测像素值。
- 每条使用约64dp最小高度的紧凑列表行，正常情况别名+掩码两行。受限时才附状态，测试完成才附一条结果。不为每条建立大圆角卡片。
- 显示启停与测试图标；编辑、删除、恢复在条目的更多菜单。IconButton保留Material默认触点，不为了密度强制变成难点的小按钮。
- 搜索默认关闭，点击搜索图标后出现输入框与单一状态菜单；撤下五个常驻筛选标签和大数字统计区。再次关闭搜索会清空搜索和筛选，避免“隐藏筛选后列表少了但不知道为什么”。
- 顶部更多菜单中保留全部启用/关闭/恢复；仍需确认，范围仍是本供应商全部条目。
- 轮换方式不加说明卡。帮助菜单收纳测试计费、30秒超时、批量作用范围等说明，不重复占列表空间。
- 供应商配置页撤掉R2大卡及重复标题，恢复原简洁开关与管理入口。
- 测试成功才恢复健康；超时/取消不处罚；测试按实际单Key副本归因；结果存页面状态不随列表离屏丢失。重复编辑拒绝和导入预览继续保留。

## R3.3 KEY 策略默认界面

```text
KEY 管理
点一条规则，设置处理方式与恢复时间
启用 Key 健康管理              开关
──────────────────────────
无效密钥      停用 → 24小时后再试 >
额度不足      停用 → 24小时后再试 >
请求限流      冷却 → 首次1分钟   >
──────────────────────────
故障切换      最多8次 / 300ms   >
自定义错误规则   0条 / 展开      >
──────────────────────────
更多                    取消 保存
```

- 去掉主页面所有大分组卡、滑条、规则动作芯片墙和长说明段落。
- 点击哪条就编辑哪类。动作通过下拉选择“不处理/冷却/停用”。
- 停用只显示“仅手动恢复”与恢复分钟数；有1小时/24小时/7天快捷值。
- 冷却先显示首次秒数，提供1分钟/5分钟/30分钟快捷值；上限与倍率放进“重复失败怎么等待？”折叠项。
- 参数用精确数字输入而不是拉一条范围巨大的滑条；空、越界、非有限数不可确定。冷却基数变大时上限至少跟上基数。
- 保存语义分两层：子编辑器确定只是改父页面草稿；父页面点击保存才更新 DataStore。取消子编辑器不影响父草稿；关闭父页面有未保存确认。
- 恢复默认、清除全部健康限制、帮助位于底部更多菜单。清除健康仍是二次确认后的即时操作，不需要再保存。
- 子编辑器正文有滚动和最大高度，降低横屏/输入法挤压风险；设备实测仍待进行。

## R3.4 自定义能力不再共用时长

### 新模型 `KeyFailureRule`

| 字段 | 默认 | 范围 |
|---|---|---|
| action | SUSPEND | IGNORE / COOLDOWN / SUSPEND |
| suspendMinutes | 1440 | 1～43200分钟（30天） |
| manualRecoveryOnly | false | 永久限制用 Long.MAX_VALUE，不用0表达 |
| cooldownSeconds | 60 | 1～86400秒 |
| maxCooldownSeconds | 1800 | 不低于基数，最高604800秒（7天） |
| multiplier | 2.0 | 1～5；1为固定等待 |

新增到 `KeyManagementPolicy`：

```kotlin
val invalidRule: KeyFailureRule? = null
val quotaRule: KeyFailureRule? = null
val rateLimitRule: KeyFailureRule? = null
val customRules: List<CustomKeyRule> = emptyList()
```

旧字段保留。独立规则为空时，`ruleFor(0/1/2)` 从旧 action/时长生成行为，保证旧备份不突然改策略；编辑某类时只替换该类规则，不影响其他类。旧 `cooldownDurationMs` 保留兼容测试，但健康写入使用 Decision 内规则的 `durationMs()`，没有继续偷用旧共享时长。

## R3.5 自定义错误规则

每条包含：稳定ID、名称、启用、HTTP状态集合、关键词列表、独立行为。

- 名称必填，最多80字符；最多30条规则。
- HTTP支持400～499，逗号或空白分隔。关键词每行一个，最多30个、每个最多120字符；不支持正则。
- 两组条件都空不能保存；只有一组时按该组判断；两组都有时必须两组都满足。组内任意一项命中即可。
- 关键词匹配忽略大小写。自定义匹配只用于错误对象（包括既有上游错误信息和有限cause链），不检查正常响应正文。
- 第一条启用且匹配的规则生效，在内置分类之前；“上移”提高优先级。禁用项跳过，删除需确认。
- 自定义选择IGNORE时，覆盖后面的默认处罚，允许为特定错误设置例外。
- 规则只影响当前请求真正选中的Key；不按供应商全局最后Key猜测。

例子：

```text
名称：中转站Key过期
HTTP：403
包含词：key expired
动作：停用
恢复：仅手动
```

只有403并且错误文本含key expired才命中。普通403模型权限错误不会被这条误伤。

另例：额度不足默认规则可设冷却5分钟，而无效Key默认规则设永久停用；两类不再被同一个24h参数绑定。

## R3.6 硬保护与判定顺序

```text
取消 / cause链取消 / Key池耗尽 → IGNORE
健康总开关关闭              → IGNORE
HTTP >= 500                → IGNORE（自定义也不能覆盖）
第一条自定义匹配            → 用该条行为
没有自定义匹配              → 内置 classify → 独立默认行为
```

- 普通403没有自定义命中时仍沿用默认中立分类；明确无效/额度文本仍按原分类。
- 导入策略的异常状态码要失败关闭：若规则包含不支持的状态码，`clamped()` 会禁用该条，不能删除非法状态后扩大成关键词匹配。这点有回归测试。
- 空条件永不匹配，避免意外变成全局封禁。
- 自定义处理普通4xx是显式用户规则；不默认把网络故障或5xx归罪于Key。
- 错误关键词来自用户设置，可能含敏感文本，不复制进健康日志。健康 reason 仅存 invalid/quota/cooldown/custom；新 `ruleId: String?=null` 关联规则，不存原始上游内容。
- 冷却连续次数仅在同reason、同ruleId且24h窗口内继承，切换规则不串累积；旧记录缺ruleId可读。
- 策略变更仍只影响新失败；历史until不重算。禁用/删除自定义规则不自动撤销它以前留下的限制，需要显式恢复或等待到期。
- R2总开关忽略旧记录、重启加载、AtomicFile、人工禁用优先的规则保持不变。

## R3.7 文件与迁移

新增：

```text
ai/.../util/KeyFailureRule.kt
app/.../ext/keys/CompactKeySheet.kt
docs/custom/2026-09-r3-compact-keys.md
```

修改：KeyManagementPolicy、KeyHealth、KeyRoulette、KeyManagementPolicyTest；两个Key管理UI及ProviderMultiKeySection；英/中文strings；静态脚本。

迁移顺序：

1. 保留R2自动重试和R1消息存储；不要顺手回退这些功能。
2. 新模型与可空字段先迁移，确认旧JSON能读。
3. Decision入口统一提供`reportFailure/isKeyLevelError`的规则判定；不要只让UI支持自定义而后端继续硬编码。
4. KeyHealth写入读取Decision的duration，保留ruleId默认值及旧记录兼容。
5. 引入CompactKeySheet，仅替换Key相关界面；不修改共享PolicyUi以免自动重试页面跟着变化。
6. 双语`key3_*`资源与静态门禁、单元测试一并移植；云端编译再验证API兼容。

## R3.8 验收重点

静态：紧凑弹窗、非全高、自适应高度、非卡片列表、搜索折叠、策略无滑条；真实策略接线、无条件拒绝、5xx/取消硬保护、AND语义、旧字段兼容。

新增8项策略测试（同原KeyManagementPolicyTest类）：独立参数兼容回退、规则时长/指数/封顶、双条件AND、无条件及5xx拒绝、首匹配IGNORE覆盖、取消及池耗尽保护、显式403与总开关、字段序列化。加上R2的8项和ManagedKeysTest的7项，云端选定测试共23项。

设备回归重点（未执行不能写已通过）：1/5/50个Key的小屏与横屏；键盘和大字体；搜索收起清条件；更多菜单可达；父子草稿保存/取消；403规则命中/不命中；不同规则的恢复时长；旧健康记录与人工禁用不受误恢复。

---

# R2 补充：重试与多 Key 界面重构、全局 KEY 管理策略

> R2 是自动重试与全局策略基础规范；Key界面和独立规则以 R3 为准，与下方 R1 章节冲突时以较新章节为准。消息防丢失仍沿用 R1，未改变保存、事务、锁和取消语义。R1 的“固定 24h、固定 1～30min、固定8次/300ms”现在仅代表默认值，不再是不可更改的行为。

## R2.0 最终构建与下载交付（2026-09-29）

- **源码/CI 提交**：`25c904bd097ce87849eefa16795f6143053c4e66`。
- **成功 Actions**：https://github.com/xsun71136-pixel/rikkahub/actions/runs/36491204264 。
- **测试**：`Key policy unit tests` 成功；选中执行新增8项策略测试 + 原7项ManagedKeysTest，共15项；不是仅添加测试源码。
- **编译**：`Gradle Build` 成功（`assembleRelease`，8分39秒）；本轮没有本地Gradle编译。
- **发布**：`Publish nightly prerelease` 成功；https://github.com/xsun71136-pixel/rikkahub/releases/tag/nightly 。
- **静态门禁**：385项、0失败；40个Kotlin参与、24个XML解析、双语累计160个新增字符串（R2本轮97对）。
- **实际设备UI测试**：未执行。构建/测试通过不等于小屏、横屏、大字体和真实网络回归已经验证。
- **文档提交**：本节记录源码提交；之后文档单独快进提交，不改变已构建源码与APK。

### 工作区产物

目录：`/workspace/RikkaHub-R2-25c904bd/`。

| APK | 精确大小 | SHA-256 |
|---|---:|---|
| `app-arm64-v8a-release.apk` | 40,367,330 B | `f2d88a255cc5253eda35c923018fe86a3d1dcecbe6f28955112f7ed4f251dcc6` |
| `app-universal-release.apk` | 50,287,432 B | `5ffa1846fea09590dddbdbad37eec29cf9a153116e62890beaadf40d35668df2` |
| `app-x86_64-release.apk` | 41,033,824 B | `75bd84d463e9ccf30fd87dbd83b972dacefc20ac238124c6a5889c151c5190e1` |

额外校验文件：`SHA256SUMS`、`artifacts.json`、`signature-verification.json`；本详细文档也复制到该目录，便于APK离线归档。

### 签名与完整性校验

三个APK均：

1. 下载大小与GitHub asset一致；SHA-256与GitHub返回的asset digest一致。
2. ZIP全文件CRC检查通过，不是只检查能打开zip目录。
3. 定位APK Signing Block并识别v2 ID `0x7109871a`。
4. 从v2签名块解析RSA证书、公钥、签名及signed-data。
5. 按v2规范对去除签名块后的ZIP内容分1MiB块计算内容摘要，修正EOCD偏移，内容digest一致。
6. 用OpenSSL验证RSA PKCS#1 v1.5 SHA256签名（algorithm `0x0103`），三个均 `Verified OK`；证书公钥与签名公钥相符。
7. 证书SHA-256仍为既有项目签名：`A3:03:4D:49:87:69:D4:7D:EB:45:82:6D:22:2C:BB:FC:63:3A:74:E0:0C:AE:CA:B4:F8:AB:B3:2B:28:12:CD:33`。

这次不止“发现签名块”，而是校验了v2内容摘要与签名；但未实际安装或执行Android系统`apksigner`兼容性验证。相同applicationId与签名支持同证书版本覆盖安装；若现装官方签名或其他签名，应先自行确认兼容并备份，不要自动卸载清数据。

### 证据与已知警告

- 仓库报告：`docs/custom/r2-build-report.json`。
- 工作区云端日志：`/workspace/polish/build-success.log`。
- APK校验脚本：`/workspace/polish/verify_v2.py`。
- 本轮新增共享Slider使用的Material3旧参数重载有弃用警告（官方其他设置页也存在同类警告），不影响编译；未来升级应统一迁移到SliderState重载。
- `static-check-report.json` 中 `compiled=false/kotlin_tests_executed=false` 表示**静态脚本不执行编译/测试**，并非本轮云端没有编译/测试。云端结果以本节、Actions与r2-build-report.json为准。

## R2.1 目标、基线与执行顺序

- 原远端基线：`88cc9ff3258d74c3e9c813c5c9e3f48e265d6a3d`。
- 原源码/CI：`df99692a684360c4b23b4f9a17e3d665c3825e46`。
- 设计先行文档：`docs/custom/2026-09-ui-policy-design.md`。该文件保留本轮动工前设计，最终行为以本文为准。
- 顺序：通读 UI 与健康/重试调用链 → 备份 → 写设计 → 统一 UI 基础组件 → 健康策略模型及真实接线 → 界面重构 → 静态门禁 → Git Data API 非强制提交 → 云端单元测试 → 云端 Release → 下载工作区 → 更新本文。
- 工作区最初 Git HEAD 仍是官方 `00c8d53`，但未提交文件已等价远端；通过 GitHub tree 与本地每个 blob 比较证明零差异后，创建本地快照。**本地快照提交不是远端发布提交**，不要拿本地 `git rev-parse HEAD` 代替本文记录的 Actions head_sha。
- 备份：`/workspace/pre-polish.patch`、`/workspace/pre-polish-ext.tar.gz`。
- 保持 applicationId、版本号 2.5.5/190 和项目签名不变；用源码 SHA 与 APK SHA 区分本轮产物，不靠版本名区分。

## R2.2 原界面的具体问题与新信息结构

| 原问题 | 本轮处理 |
|---|---|
| 重试页所有参数堆成长表，用户不知道该选什么 | 推荐/快速/耐心预设；基本设置与触发规则分开 |
| 看不出配置会等待多久 | 普通请求次数、等待序列、等待总和预览 |
| 保存按钮滚到很下面 | 固定页头与底部操作栏，正文独立滚动 |
| 整个弹窗丢弃修改没有提醒 | dirty 检查；关闭/返回统一触发放弃确认 |
| 允许重放半截回复风险不明显 | 风险文案和二次确认，默认仍关闭 |
| Key 名称、开关、状态、三图标挤在一行 | 卡片标题、状态/掩码/开关、操作行分离；文本操作符合 Material 默认触摸尺寸 |
| Key 一多就找不到目标 | 别名/Key片段搜索，与状态筛选组合 |
| 小屏固定480dp列表导致内容被裁切 | 一个 LazyColumn 覆盖统计、操作、列表，不嵌套固定高度列表 |
| 测试只有短 Toast，离开视线即找不到结果 | 页级结果表，展示成功耗时或脱敏失败摘要，筛选/滚动不会把结果丢掉 |
| 健康限制一律固定时长 | 网络设置新增全局 KEY 管理，可配置错误动作、恢复、冷却和切换 |

## R2.3 共享布局 `PolicyUi.kt`

路径：`app/src/main/java/me/rerere/rikkahub/ext/ui/PolicyUi.kt`。

### PolicyScreen

- Compose `Dialog`，`usePlatformDefaultWidth=false`；不新增 Navigation route，不破坏官方导航返回栈。
- 全高窗口、大屏最大宽 840dp；默认仍受屏幕可用尺寸约束。
- `Surface` 使用当前 Material3 动态主题，不硬编码亮色背景；深色模式同一套结构。
- `safeDrawingPadding()` + `imePadding()`；固定标题、副标题、关闭按钮；正文占剩余空间；保存栏在正文之外。
- 调用方提供正文与 footer；设置页采用草稿，Key 条目管理采用即时保存，不混淆两种语义。

### PolicyCard / PolicyHint / PolicyToggle

- 24dp 圆角、`surfaceContainerLow`、16dp 内边距、12dp 组内间距。
- 标题/说明/控件层次分明；警告使用主题 error 色，但同时保留文字，不只依赖颜色。
- 开关行可整行点按；总开关关闭后仍允许编辑下一次开启要使用的参数，并在概览明确当前是否生效。

### PolicySlider

- 标题与数值标签并排；滑条为快捷操作，点数值标签可打开数字输入。
- 数字必须有限且在上下限内；非法/空/NaN/无穷值不可确认。
- 数字标签与输入单位保持一致：重试等待以秒显示，内部持久化仍为毫秒；停用以小时、冷却以秒。
- 离散项和滑条统一归一化到配置模型边界；最后保存还会再次 `clamped()`。

### PolicyFooter / PolicyConfirm

- 没有修改时显示“已保存”，保存按钮禁用；有修改时显示“保存更改”。
- 重置需要确认，仅修改草稿，保存后才影响请求。
- 关闭有修改的界面时确认放弃，取消确认则继续编辑。
- Key 健康清除属于即时操作，确认文案明确不需要再点保存。

## R2.4 自动重试界面与交互

文件：`ext/retry/AutoRetrySettingsSheet.kt`。保留函数名减少挂接改动，实际已不再是旧的长底部弹窗。

入口保持：偏好设置 → 网络 → 自动重试行，点击或长按均可；行尾开关仍可直接启停。

### 基本设置页

1. 重试概览：总开关；普通尝试最多 `maxRetries + 1`；关闭时为1。
2. 基准等待序列：`RetryPolicy.backoffDelay(index, config.copy(jitter=false))`，显示如 `1s → 2s → 4s`。
3. 总等待：只加等待时间，不包含请求时间；抖动开时注明 ±20% 且不超过上限。
4. 快速配置：

| 预设 | 额外重试 | 初始等待 | 倍率 | 等待上限 | 部分重放 |
|---|---:|---:|---:|---:|---|
| 推荐 | 3 | 1秒 | 2 | 30秒 | 关闭 |
| 快速 | 2 | 0.5秒 | 2 | 5秒 | 关闭 |
| 耐心 | 5 | 2秒 | 2 | 60秒 | 关闭 |

预设替换整个 AutoRetryConfig（含规则、安全选项），总开关保持不变；通过完整结构相等判断选中状态。手改后不等于任何预设则标为自定义，避免“已经改了但仍写推荐”。

5. 次数与等待：次数、初始等待、倍率、上限、抖动。
6. 安全与边界：网络异常重试；部分响应重放开关，开启须确认；说明自动切换另在 KEY 管理设置。

### 触发规则页

- HTTP 标签包含默认码与已选自定义码；点击切换选中。
- 输入支持 400～599 整数；非法状态码显示错误，不再悄悄丢掉。
- 重试关键词、停止关键词分别成组；标签点击删除；输入添加时 trim、去重。
- 明确判定顺序仍是停止词 → 有HTTP码按列表 → 传输异常 → 无码关键词，不改变 R1 RetryPolicy。
- 空规则集合明确显示“暂无规则”，不是静默空白。

### 草稿生命周期

- `rememberSaveable` 保存启停与 JSON 编码的配置草稿，旋转后不丢已修改参数；入口可见状态同样 saveable。
- 保存时从当前 `settings` 复制，只替换 `networkSetting.enableAutoRetry/autoRetry`，不会把打开时捕获的整份旧设置长期保留到保存。
- 未添加真实服务端断点续传；部分重放风险仍在，默认关闭。

## R2.5 多 Key 管理

文件：`ext/keys/ProviderKeyManagerSheet.kt`、`ProviderMultiKeySection.kt`。

### 统计与筛选

状态互斥优先级：

```text
enabled=false                   -> 人工关闭
没有当前有效健康限制             -> 可用
限制.state=COOLDOWN              -> 冷却
其他有效限制                     -> 自动停用
```

- 每个筛选标签显示本供应商对应总数，不随搜索文本变化。
- 搜索匹配 alias 或完整 Key 的片段（大小写不敏感）；列表展示仍是掩码，不显示搜索命中的完整 Key。
- 搜索+状态为 AND 组合；没有匹配项和 Key 池为空使用不同说明。
- Key 的默认序号基于完整池，不因筛选改变；LazyColumn key 使用稳定 UUID。
- 全部不可用时显示原因提示，请求层仍明确失败，禁止旧 apiKey 回退。
- 全局健康管理关闭时，健康限制暂不参与筛选，人工关闭项仍被排除。

### 条目卡与批量操作

- 标题使用别名或默认序号；掩码+状态和开关一行，测试/编辑/删除/恢复另外一行。
- 自动停用时开关视为关闭；手动打开同时恢复健康记录。冷却不等于人工关闭，仍保留启用开关和单独冷却状态。
- 健康原因由记录 reason 显示（无效/额度/限流），不是简单按 state 猜；因此“429 选择停用”仍显示真实限流原因。
- 有期限显示剩余可重试时间；`Long.MAX_VALUE` 显示“仅手动恢复”，不会显示天文倒计时。
- 删除需确认；测试中的 Key 不允许编辑/删除。
- 批量启用/关闭/恢复作用于**本供应商全部 Key**，不是搜索结果；确认文案明确作用范围。
- 批量启用仅改人工启停，不清健康限制；恢复健康仅清记录，不打开人工关闭的 Key；批量关闭不删 Key。

### 添加、编辑、导入

- 单条保留掩码输入和可见性切换；编辑重复值时直接报错，禁止旧版 normalization 静默吞掉重复项。
- 非法分隔串仍报“只能单 Key”，批量内容需走导入。
- 导入默认空白，不读剪贴板；按空白/逗号拆分并去重，显示将新增数量与已存在跳过数量。
- 只有真正新增数量>0时才可确认，避免“全部重复还导入成功”。
- 变更都调用 `syncEnabledApiKeysToLegacyField()`；人工全关闭时旧兼容字段也清空。

### 测试

- 仍使用供应商第一个 CHAT 模型；没有模型时解释原因并禁用测试按钮。
- 实际 `hello` 请求；文案提示可能计费；本次 Provider 副本 `multiKeyEnabled=false`，不再轮换。
- timeout=30秒；测试作用域是管理页面；关闭页面会取消。
- 结果按 Key 值存于页级 `mutableStateMapOf`，滚出列表和筛选后仍能看到；进程重启/关闭页面后不持久化探测结果。
- 成功显示单调时钟 `SystemClock.elapsedRealtime()` 测量的毫秒耗时，成功后才清当前 Key 健康限制。
- 超时和取消不写失败处罚；其他失败按本次测试值归因。
- 不展示上游原始响应，只显示 HTTP码或异常类型，防响应正文泄漏其他 Key。
- 测试成功不会把原先人工关闭的 Key 自动打开。

## R2.6 全局 KEY 管理策略

新入口：**设置 → 偏好设置 → 网络 → KEY 管理**。

新文件：

- `ai/.../util/KeyManagementPolicy.kt`：序列化策略与纯计算函数。
- `app/.../ext/keys/KeyPolicySettingsScreen.kt`：策略编辑界面。

范围：开启多 Key 的 OpenAI / Google / Claude；Vertex 服务账号不纳入。

### 配置字段与默认值

| 字段 | 默认 | 范围 / 含义 |
|---|---|---|
| enabled | true | 管理总开关 |
| invalidAction | SUSPEND | 无效密钥：IGNORE / COOLDOWN / SUSPEND |
| quotaAction | SUSPEND | 明确余额不足：同上 |
| rateLimitAction | COOLDOWN | 普通429：同上 |
| suspendHours | 24 | 1～720小时 |
| manualRecoveryOnly | false | 开启后停用无自动到期 |
| cooldownSeconds | 60 | 1～3600秒 |
| maxCooldownSeconds | 1800 | 不低于基数，最高86400秒 |
| cooldownMultiplier | 2.0 | 1～5，非有限值退默认 |
| autoSwitch | true | Key级故障是否允许切换尝试 |
| maxSwitches | 8 | 0～20次额外尝试，0禁止专用切换 |
| switchDelayMs | 300 | 0～10000毫秒 |

### 判定与动作必须分离

```text
Throwable
  -> KeyHealthRegistry.classify()
     >=500           NEUTRAL（优先，正文含Key错误也不处罚）
     401             INVALID
     402             QUOTA
     明确余额词       QUOTA
     明确Key无效词    INVALID
     普通429          COOLDOWN
     其余             NEUTRAL
  -> policy.action(verdict)
     总开关关闭 / NEUTRAL -> IGNORE
     其他按三类用户配置
  -> mark()
     IGNORE   不创建/刷新健康限制
     COOLDOWN 保存冷却与真实reason
     SUSPEND  保存停用与真实reason
```

其中 `KeyVerdict.COOLDOWN` 是旧命名，语义是“普通限流错误”；用户可以把该类改为 SUSPEND 或 IGNORE，不要因枚举名把动作写死。

### 恢复语义

- 策略更改只处理后续失败，不重写已有记录的 until；否则调一个滑条就让历史全部 Key 状态突变。
- `enabled=false` 时请求选择和UI都忽略已有健康记录，但保留记录文件；恢复总开关后未过期记录再次限制。
- 人工 `enabled=false` 永远优先，策略不能偷偷恢复人工关闭项。
- 手动恢复、测试成功、全局清除等显式操作可以清记录。
- `manualRecoveryOnly=true` 的停用记录 `until=Long.MAX_VALUE`，重启后继续生效；UI计时器不为永久记录每秒唤醒。
- 清除全部供应商健康限制是即时操作，二次确认，不影响草稿策略，也不删除条目或别名。

### 冷却公式与记录兼容

```text
n = 1..32
wait = min(baseSeconds * 1000 * multiplier^(n-1), maxSeconds*1000)
```

- 24h内重复冷却累加次数，超过窗口归1；倍率1为固定冷却。
- 旧记录没有 action 字段时，用旧 state==COOLDOWN 判断连续冷却历史。
- KeyHealthRecord 新增可选 `action: KeyFailureAction?=null`，旧 JSON 可读。
- 非冷却处罚打断冷却累积；当前进程到期记录保留供下一次指数计算，启动加载仍会过滤已到期记录，因此跨重启的已过期冷却历史不保证累积。
- AtomicFile 与串行修改保留；持久化失败仅记录警告，当次内存状态仍生效，不能宣称任何磁盘故障下都能保存。

### 策略同步与层次

```text
NetworkSetting.keyManagement（DataStore序列化）
  -> SettingsStore.settingsFlowRaw.onEach
  -> KeyRotationPolicy.configure(clamped)
  -> KeyHealthRegistry.policy
  -> policyFlow（UI响应）

SettingsStore.update(settings)
  -> 同步 configure
  -> 发布 settingsFlow + 写 DataStore
```

不依赖进入设置页才生效。策略属于 ai 层，ai 不反向依赖 app 数据类。

### 与重试的准确关系

- `isKeyLevelError` 不再只判断分类非 NEUTRAL，还判断当前策略是否真的处理该错误；IGNORE 不走专用 Key 切换。
- 切换需总开关、autoSwitch、maxSwitches>0、Key级错误以及存在 ready alternative。
- 专用切换使用用户预算和延迟，不再偷偷 `maxOf(maxRetries,8)`。
- 普通网络重试和专用切换共用当前失败计数，不是两个可以相加的独立计数器；已经用掉的普通重试会计入后续切换预算。
- `autoSwitch=false` 仅禁用专用Key故障切换；普通重试若被 RetryPolicy 允许，每次 attempt 仍正常挑选可用Key。因此不应承诺“关闭autoSwitch后所有重试永远固定同一Key”。
- 全池实际无 ready Key 时仍报池耗尽；只是用户关闭切换但池中有可用Key时，不应错误提示所有Key耗尽。
- 部分响应保护仍先于重试/切换，默认不重放；取消始终中断。

## R2.7 文件地图

### 新增（4个Kotlin文件）

```text
ai/src/main/java/me/rerere/ai/util/KeyManagementPolicy.kt
ai/src/test/java/me/rerere/ai/util/KeyManagementPolicyTest.kt
app/src/main/java/me/rerere/rikkahub/ext/ui/PolicyUi.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/KeyPolicySettingsScreen.kt
```

### 修改

```text
ai/.../util/KeyHealth.kt
ai/.../util/KeyRoulette.kt
app/.../data/ai/GenerationLoop.kt
app/.../data/datastore/PreferencesStore.kt
app/.../ext/retry/AutoRetrySettingsSheet.kt
app/.../ext/keys/ProviderKeyManagerSheet.kt
app/.../ext/keys/ProviderMultiKeySection.kt
app/.../ui/pages/setting/SettingPreferencesNetworkPage.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
scripts/check_custom_static.py
.github/workflows/daily-build.yml
```

未改：三类 Provider 认证实现、消息防丢保存链、Gradle版本、签名证书、Firebase占位机制、applicationId。

## R2.8 验证方案与后续迁移

### 静态检查

命令仍使用原 `scripts/check_custom_static.py`，R2增加策略边界、UI生命周期、测试脱敏、搜索筛选、超时取消、参数接线等源码断言。基于 `00c8d53` 覆盖前两轮累积改动，不能把检查数字当真实运行测试数量。

### 云端单元测试

在 `Gradle Build` 前增加：

```bash
./gradlew :ai:testDebugUnitTest \
  --tests 'me.rerere.ai.util.KeyManagementPolicyTest' \
  --tests 'me.rerere.ai.util.ManagedKeysTest'
```

新8项：旧配置默认值、总开关关闭、三类独立动作、默认指数及封顶、固定冷却与边界次数、数值clamp/NaN、永久恢复配置序列化、普通403/5xx中立。

原7项：旧单Key、导入去重、全关闭不回退、兼容字段清空、轮询/固定副本、错误分类、服务账号绕过。

### 人工设备回归（未执行时不能写成已通过）

- 小屏、横屏、大字体：标题/关闭和保存可达，滚动到底不裁切；输入法弹出后可确认。
- 旋转期间修改草稿；返回放弃/取消；预设后手改显示自定义。
- 429冷却、401停用、额度冷却、限流停用、总开关关闭等矩阵。
- 关闭autoSwitch与普通重试分别测试，确认无误导池耗尽。
- Key足够多：搜索、各状态切换、编辑重复、只含重复Key的导入。
- 测试成功、HTTP失败、超时、取消、滚动离屏；结果不泄漏Key。
- 人工关闭后清健康、永久停用重启、临时关闭健康总开关后恢复。
- 消息防丢与半截回复默认不重放回归。

### 官方新版本迁移顺序

1. 保留 R1 保存链，先验证新官方是否改变 SettingsStore/ProviderSetting/GenerationLoop。
2. 移植 KeyManagementPolicy 和 KeyHealthRecord.action 默认值。
3. 对齐 KeyHealthRegistry 的分类→动作、configure、recordOf总开关、clearAll。
4. 对齐 KeyRotationPolicy 的配置flow、isKeyLevelError语义。
5. NetworkSetting 添加默认策略；SettingsStore 加载/update挂接。
6. GenerationLoop 替换固定切换常量，不删取消与部分响应保护。
7. 引入 PolicyUi 及三个管理界面；网络页入口、Provider页入口；复制双语 `polish_*` 资源。
8. 运行静态门禁及15项ai测试，云端assembleRelease，核验APK签名和SHA。
9. 下载产物并更新本节构建记录；源码与文档提交分开记录。

---

# R1：消息防丢、重试与多 Key 基础实现（历史基线）

> 以下原规范完整保留作为保存链、基础算法和构建环境说明；R2 已覆盖的 UI、固定健康参数和构建产物，以前文 R2 为准。


> **文档性质：唯一维护依据（FINAL / AUTHORITATIVE）**
>
> 后续如果官方源码继续更新，先阅读本文，再按本文的“新版移植流程”逐项迁移。本文只记录最终正确的实现、文件位置、数据流、约束、静态检查、云端构建环境和已成功发布的产物；不以旧版 FULL-SPEC、旧补丁或聊天记录作为实施依据。

---

## 0. 当前最终状态

### 0.1 仓库

- GitHub：`https://github.com/xsun71136-pixel/rikkahub`
- 分支：`master`
- 工作区：`/workspace/rikkahub`
- 上游仓库：`https://github.com/rikkahub/rikkahub`
- 本轮官方/fork 代码基线：`00c8d53a5c74554c92848cf821d551a17f576995`
- 三项功能实现提交：`9371fd28d95073fa8c262f9fc994e75961a7a8a2`
- 最终 CI 配置提交：`df99692a684360c4b23b4f9a17e3d665c3825e46`
- 最终源码/CI 提交（文档更新前已验证）：`df99692a684360c4b23b4f9a17e3d665c3825e46`
- 文档提交：以本文件所在的最新 Git 提交为准；不要把文档提交误当作源码功能提交。

最终 HEAD 包含：

1. 三项功能实现；
2. 完整静态检查脚本与测试源码；
3. 能处理空/非法 Firebase 配置的云端工作流；
4. 正确的 Firebase build-only 占位配置结构；
5. 已验证的签名 Secrets 使用方式。

### 0.2 已成功云端构建

- Actions：`https://github.com/xsun71136-pixel/rikkahub/actions/runs/36479462858`
- 结果：`Gradle Build = success`，`Publish nightly prerelease = success`
- Nightly Release：`https://github.com/xsun71136-pixel/rikkahub/releases/tag/nightly`
- Release 为 prerelease，tag 固定为 `nightly`。

成功发布的 APK：

| 文件 | 大小 |
|---|---:|
| `app-arm64-v8a-release.apk` | 40,304,566 B |
| `app-universal-release.apk` | 50,224,668 B |
| `app-x86_64-release.apk` | 40,971,060 B |

已下载并核验 arm64 APK：

```text
下载地址：
https://github.com/xsun71136-pixel/rikkahub/releases/download/nightly/app-arm64-v8a-release.apk

SHA-256：
642e3cda59b084e32930935a55cd9586e7c0278df318ad700f0637c9262bebf3

ZIP：有效
APK Sig Block 42：存在
v2：存在
v1 META-INF/*.RSA：无（当前构建为 v2-only，属于正常结果）
```

### 0.3 本轮没有改变的官方能力

迁移时必须保留当前官方实现，不能用旧文件整文件覆盖：

- chart_display 工具；
- workspace / web 模块；
- 工具审批和工具恢复流程；
- 消息队列与后台生成；
- `ConversationSession` 的初始化、元数据并发保护和生成收尾；
- 文件夹、搜索、翻译、附件、图表及其他官方功能；
- 当前 Firebase、Crashlytics、Gradle、签名和版本结构。

---

## 1. 本轮功能总览

| 编号 | 功能 | 最终方案 |
|---|---|---|
| 1 | 已生成消息因异常闪退、退出、切换页面而丢失 | 流式消息周期草稿落库 + 页面退后台 flush + 崩溃线程尽力 flush + NonCancellable 终态保存 + 索引安全 |
| 2 | 重试策略 | 配置驱动的网络/HTTP 重试 + 状态码/关键词判定 + 指数退避 + 抖动 + 部分响应默认不重放 |
| 3 | 多 Key 模式 | Key 条目管理 + 启停 + 随机/轮询 + Key 健康状态 + 401/402/429 分类 + 请求级 Key 归因 + 自动切换 |

设计原则：

- 新增逻辑集中在扩展包，官方文件只做必要挂接；
- 所有新增序列化字段都有默认值，旧配置可以继续读取；
- 不用全局“最后使用的 Key”推断并发请求的失败归属；
- 不允许禁用/停用/冷却的 Key 通过旧 `apiKey` 字段偷偷回退使用；
- 不把网络错误、普通 403、5xx 错误错误地永久停用 Key；
- 取消、下游转换异常和工具/UI 异常不能被误判为普通网络重试；
- 周期草稿保存是降低损失窗口的机制，不承诺强杀、断电或存储损坏时绝对零丢失。

---

## 2. 功能一：消息防丢失与闪退修复

### 2.1 原始生命周期与最终改造

官方生成过程的关键位置：

```text
GenerationLoop.generateText()
    -> GenerationChunk.Messages
    -> ChatService.handleMessageComplete().collect
    -> session.updateConversation(内存)
    -> 生成 onCompletion
    -> ConversationSession.finishGeneration()
    -> ChatService.saveConversation()
    -> ConversationRepository.update/insert
```

最终改造后：

```text
每个流式 chunk
    -> 更新 ConversationSession 内存状态
    -> StreamDraftSaver.schedule(conversationId, snapshot)
    -> 应用级周期 worker 约 2500ms flush
    -> Room 事务只更新 message_node 表
    -> 不重复更新 conversation 元数据
    -> 不触碰 FTS

生成成功 / 失败 / 取消
    -> ConversationSession.finishGeneration()
    -> finishReasoning()
    -> NonCancellable
    -> StreamDraftSaver 与终态保存使用同一会话锁
    -> ConversationRepository 全量保存
    -> 最终补齐 FTS

Activity.onStop
    -> StreamDraftSaver.flushActive()

未捕获异常
    -> CrashHandler 回调
    -> StreamDraftSaver.flushActiveBlocking(1500ms)
```

### 2.2 新增安全访问器

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt
```

提供：

```kotlin
MessageNode.clampedSelectIndex
MessageNode.safeCurrentMessage
Conversation.safeCurrentMessages
```

规则：

- 空 `messages` 返回 `-1` 或 `null`；
- 非法 `selectIndex` 使用 `coerceIn(0, lastIndex)`；
- `safeCurrentMessages` 跳过空节点；
- UI 组合期不能直接访问可能越界的 `messages[selectIndex]`；
- 该层只负责读取安全，不修改数据库数据。

### 2.3 数据模型修改

文件：

```text
app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
```

修改点：

1. `Conversation.currentMessages`
   - `map` 改为安全的 `mapNotNull`；
   - 空节点跳过；
   - `selectIndex` 钳制到合法范围。

2. `Conversation.updateCurrentMessages`
   - 因为 `currentMessages` 会跳过空节点，所以更新前先过滤空节点；
   - 保持“当前消息列表下标”和“messageNodes 下标”一致；
   - 对旧节点的 `selectIndex` 先做合法化；
   - 保持流式更新时同一助手消息 ID 的覆盖语义。

3. `MessageNode.currentMessage`
   - 空节点仍抛出明确异常，供服务层发现损坏状态；
   - 非空节点的非法 `selectIndex` 自动 clamp，不再因旧数据或陈旧快照直接越界崩溃。

### 2.4 StreamDraftSaver

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt
```

最终实现要点：

- 周期 worker，不是 debounce：持续生成时也会持续保存；
- `DRAFT_INTERVAL_MS = 2500L`；
- `pending: ConcurrentHashMap<Uuid, Conversation>` 保存每个会话最新快照；
- `lastDraft` 保存最近写入的节点引用，用于引用级增量判断；
- 使用固定 `Array(64) { Mutex() }`，避免动态锁表删除造成并发竞态；
- 每个会话通过 `lockFor(conversationId)` 串行化草稿和终态保存；
- worker 安装后再启动，避免快速 chunk 到达时丢失 worker 状态；
- 写入成功后只移除“仍然是本次写入快照”的 pending；
- 写入期间如果有新 chunk，新快照不会被旧写入清掉，下一轮继续写；
- 写失败保留 pending，下次 flush 重试；
- `CancellationException` 必须继续抛出，不能把取消当作普通写失败吞掉；
- 落盘副本对消息执行 `finishReasoning()`，不修改前台正在显示的内存消息；
- 提供 `flushAllBlocking(timeoutMs)` 给崩溃回调使用；
- 提供 `flushActive()` / `flushActiveBlocking()` 给 Activity/Application 使用。

`persist()` 的正确顺序：

```text
取得会话锁
    -> 从 latest() 读取锁内最新状态
    -> 将该状态加入 pending
    -> 执行最终 conversation save
    -> 清理本会话 lastDraft
    -> 只删除与本次 snapshot 同引用的 pending
释放会话锁
```

这保证普通编辑、删除、分支切换或终态保存不会被迟到的旧草稿覆盖。

### 2.5 Repository 草稿写入

文件：

```text
app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
```

新增：

```kotlin
suspend fun writeMessageNodesDraft(
    conversationId: Uuid,
    nodes: List<MessageNode>,
    previous: List<MessageNode>?,
)
```

规则：

- 使用 `database.withTransaction`；
- 会话不存在时直接结束，不用草稿重新创建已删除会话；
- 首次写入没有 previous 时，从数据库读取旧节点 ID；
- 删除已经消失的节点；
- 只 `insert` 引用或内容变化的节点；
- `node_index` 始终按当前列表顺序写入；
- `selectIndex` 在写入前 clamp；
- 只操作 `message_node`，不更新 conversation 行；
- 不调用 FTS；
- 生成结束时由正常全量保存重建 FTS。

### 2.6 ChatService、页面和崩溃挂接

文件：

```text
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
```

`ChatService`：

- 构造 `StreamDraftSaver(appScope, conversationRepo)`；
- 每次 `GenerationChunk.Messages` 更新内存后调用 `draftSaver.schedule()`；
- `saveConversation()` 使用 `NonCancellable`；
- `saveConversation()` 使用 `sessionManager.withSession()` 持有活跃 session 引用，防止页面切换期间 session 被空闲清理；
- 普通保存与草稿保存共享会话锁；
- 分支切换前，如果会话仍在生成，先停止当前生成并保存已经收到的内容；
- 删除/工具收尾路径对空节点做保护；
- 生成结束仍使用官方 `ConversationSession.finishGeneration()`，保证成功、失败、取消都能终态落库。

`RouteActivity.onStop()`：

```text
调用 StreamDraftSaver.flushActive()
```

这是尽力保存，不依赖重新解析 Koin 或懒加载 ChatService。

`RikkaHubApp`：

```text
KeyRotationPolicy.init(this)
CrashHandler.install(this) {
    StreamDraftSaver.flushActiveBlocking(1500)
}
```

崩溃回调只使用已经存在的静态 active saver，不在崩溃线程上初始化完整依赖图。

### 2.7 UI 安全修改文件

```text
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
```

正确规则：

- `ChatMessage` 先判空，再读取当前消息；
- `ChatMessageBranch` 先 clamp，再显示左右分支；
- `ChatList` 对空节点直接跳过；
- 预览和搜索使用 `safeCurrentMessage`；
- 分支切换通过 `ChatVM -> ChatService.selectMessageNode()`；
- 不从 UI 旧 `Conversation` 快照整段写回；
- 服务层基于最新 session 状态校验 `nodeId/selectIndex` 后保存；
- 分支切换期间生成中的任务先停止，避免旧生成继续写入错误分支。

### 2.8 功能一的边界

该方案保证：

- 正常页面切换不会因为保存窗口而丢失已落库内容；
- 正常取消、失败、成功都有终态保存；
- 进程退后台时会尽力 flush；
- 崩溃处理器会在限定时间内尽力 flush；
- 非法分支索引和空节点不会直接导致 Compose 组合期崩溃。

该方案不承诺：

- 强制 kill、断电、文件系统损坏时的绝对零丢失；
- 2.5 秒窗口内的增量一定已经写入磁盘；
- 网络服务端已经产生的流可以断点续传。

---

## 3. 功能二：高级自动重试

### 3.1 配置模型

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt
app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
```

`NetworkSetting` 新增：

```kotlin
val autoRetry: AutoRetryConfig = AutoRetryConfig()
```

旧字段继续存在：

```kotlin
val enableAutoRetry: Boolean = true
```

`AutoRetryConfig` 字段：

| 字段 | 默认值 | 作用 |
|---|---:|---|
| `maxRetries` | 3 | 首次请求之外的额外次数，范围 0..10 |
| `initialDelayMs` | 1000 | 第一次重试等待 |
| `multiplier` | 2.0 | 指数退避倍率，范围 1..5 |
| `maxDelayMs` | 30000 | 单次等待上限，范围 0..120000 |
| `jitter` | true | 等待时间乘以 0.8..1.2 |
| `retryOnNetworkError` | true | 是否重试 IOException/传输异常 |
| `retryAfterPartialResponse` | false | 是否允许收到部分响应后重放 |
| `retryStatusCodes` | 408、425、429、500、502、503、504、520、521、522、524、529 | HTTP 状态码重试集合 |
| `retryKeywords` | 并发、限流、rate limit、timeout、capacity 等 | 无状态码时的重试词 |
| `stopKeywords` | 余额不足、insufficient_quota、invalid api key、context length 等 | 命中后立即停止 |

所有字段都有默认值，旧设置 JSON 读取时不需要迁移脚本。

`clamped()` 在使用前统一约束：

- 次数限制在 0..10；
- 初始等待限制在 0..10000ms；
- 倍率限制在 1..5，NaN/Infinity 回退到 2.0；
- 最大等待限制在 0..120000ms；
- 状态码只保留 400..599；
- 关键词 trim、去空、去重。

### 3.2 重试判定顺序

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt
```

最终判定顺序：

```text
1. CancellationException / AllKeysSuspendedException -> 不重试
2. 命中 stopKeywords -> 不重试
3. 能提取 HTTP 状态码 -> 只看 retryStatusCodes
4. 无 HTTP 状态码且属于 IOException -> 看 retryOnNetworkError
5. 无状态码且命中 retryKeywords -> 重试
6. 其他异常 -> 不重试
```

状态码来源支持：

- `ProviderHttpException.statusCode`；
- cause 链中的 `ProviderHttpException`；
- `response: 429`；
- `HTTP 503`；
- `status code: 503`；
- `error code: 503`。

退避：

```text
delay = initialDelayMs * multiplier ^ attemptIndex
delay <= maxDelayMs
jitter 开启时再乘 0.8..1.2
```

### 3.3 ProviderHttpException 与流式错误

文件：

```text
ai/src/main/java/me/rerere/ai/util/ProviderHttpException.kt
```

定义：

```kotlin
class ProviderHttpException(
    val statusCode: Int,
    detail: String = "",
    cause: Throwable? = null,
) : RuntimeException("HTTP $statusCode: ${detail.take(2048)}", cause)
```

流式失败统一经过：

```kotlin
providerStreamFailure(response, cause)
```

已接入：

```text
ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt
```

效果：

- 空错误体仍能保留 HTTP 状态；
- HTML 或非法 JSON 不会吞掉状态码；
- SSE `response != null` 且失败时不会被当作正常结束；
- 非流式 HTTP 失败也使用 `ProviderHttpException`。

### 3.4 GenerationLoop 接线

文件：

```text
app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
```

重要常量：

```kotlin
private const val KEY_SWITCH_BUDGET = 8
private const val KEY_SWITCH_DELAY_MS = 300L
```

普通重试：

- 由 `settings.networkSetting.enableAutoRetry` 控制；
- 由 `settings.networkSetting.autoRetry` 提供次数和判定；
- 非流式和流式均使用同一 `RetryPolicy`；
- 每次请求前由 `prepareKeyAttempt()` 选定本次 Key；
- 取消会在进入下一次重试前通过 `ensureActive()` 截断。

流式重试：

- 预先创建/复用同一个 assistant message ID；
- 每次 attempt 从相同 `responseBaseMessages` 开始；
- 防止第二次响应追加到第一次半截响应后；
- `StreamChunkHandlingException` 表示下游消息处理/UI 转换失败，不进入网络重试；
- 如果已经收到文本、思考或工具部分，默认 `retryAfterPartialResponse=false`，直接保留已收到内容并结束本次重试；
- 用户主动开启该选项后才允许重放；重放是新请求，不是服务端续传，可能重复计费；
- 多 Key 故障切换也受“部分响应默认不重放”保护。

### 3.5 高级设置入口

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
```

入口：

```text
设置
  -> 偏好设置
    -> 网络
      -> 自动重试行：点击或长按
```

界面可配置：

- 总开关；
- 最大重试次数；
- 初始等待；
- 指数倍率；
- 最大等待；
- 抖动；
- 网络错误开关；
- 部分响应后是否重试；
- 状态码列表；
- 重试关键词；
- 停止关键词；
- 恢复默认值。

### 3.6 重试与多 Key 的关系

两者不是同一个开关：

- 普通网络/服务端重试受 `enableAutoRetry` 和 `AutoRetryConfig` 控制；
- Key 级失效切换由 Key 池是否有可用替代 Key 决定；
- Key 切换单次等待为 300ms；
- Key 切换预算至少 8 次，并与当前 attempt 计数共用，不是无限循环；
- 如果已经收到部分响应，默认不切换重放；
- `AllKeysSuspendedException` 不进入普通重试。

---

## 4. 功能三：多 Key 模式

### 4.1 ProviderSetting 数据字段

文件：

```text
ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
```

以下三种 Provider 都新增相同字段，默认值如下：

```kotlin
var multiKeyEnabled: Boolean = false
var apiKeys: List<ProviderApiKey> = emptyList()
var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM
```

Provider：

```text
ProviderSetting.OpenAI
ProviderSetting.Google
ProviderSetting.Claude
```

### 4.2 Key 数据模型与兼容字段

文件：

```text
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt
```

模型：

```kotlin
@Serializable
data class ProviderApiKey(
    val id: Uuid = Uuid.random(),
    val value: String = "",
    val enabled: Boolean = true,
    val alias: String = "",
)
```

策略：

```kotlin
enum class ProviderKeyStrategy {
    RANDOM,
    ROUND_ROBIN,
}
```

兼容规则：

- 旧版只有一个 `apiKey`，默认 `multiKeyEnabled=false`，旧配置直接继续工作；
- 打开多 Key 时，可以把当前 `apiKey` 按空格、逗号、换行导入条目；
- 条目 trim、去空、去重；单条编辑不允许包含分隔符；
- 启用条目会同步拼接回旧 `apiKey` 字段，方便旧版导出/兼容；
- 多 Key 请求的真实权威是 `apiKeys` 中 `enabled=true` 的条目，不是兼容字段；
- 全部条目禁用时兼容 `apiKey` 也被清空；
- 空池、全部禁用、全部健康停用或全部冷却时抛出 `AllKeysSuspendedException`，不会回退旧字段；
- `withSingleApiKeyForRequest()` 会生成关闭多 Key 的单 Key 副本，防止一次请求内部再次轮换。

### 4.3 KeyRotationPolicy

文件：

```text
ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt
```

核心方法：

```kotlin
KeyRotationPolicy.manages(provider)
KeyRotationPolicy.pick(provider)
KeyRotationPolicy.reportFailure(providerId, keyValue, error)
KeyRotationPolicy.isKeyLevelError(error)
KeyRotationPolicy.hasReadyAlternative(provider)
KeyRotationPolicy.clearKeyHealth(providerId, keyValue)
KeyRotationPolicy.clearProviderHealth(providerId)
```

选择规则：

```text
读取 provider.id
    -> 读取 apiKeys(enabled=true)
    -> 读取 KeyHealthRegistry
    -> 过滤未到期的 INVALID / QUOTA / COOLDOWN
    -> 没有 ready Key 时明确失败
    -> RANDOM：ready.random()
    -> ROUND_ROBIN：按 providerId 的 AtomicInteger 顺序选择
```

最终实现**不强行选择冷却中 Key**，也不从旧 `apiKey` 回退。

`Google` 的以下路径不纳入 API Key 池：

```text
provider.vertexAI && provider.useServiceAccount
```

### 4.4 请求级 Key 固定与并发安全

`GenerationLoop` 每次 attempt：

```text
原始 ProviderSetting
    -> KeyRotationPolicy.pick(provider)
    -> withSingleApiKeyForRequest(selectedKey)
    -> providerImpl.streamText/generateText(singleKeyProvider)
```

失败时使用当前 attempt 的 `requestProvider.getApiKeyValue()` 归因：

```text
reportFailure(provider.id, requestProvider.apiKey, error)
```

因此：

- 两个并行会话互不覆盖 Key 归因；
- 重试 attempt 重新选择 Key；
- 不存在 provider 全局 `lastKey` 或 in-flight 单槽位；
- 单 Key 测试使用测试条目的实际值归因；
- 取消不惩罚 Key；
- 成功请求不会因为另一个并行请求失败而误伤。

所有认证入口已改为：

```kotlin
keyRoulette.next(providerSetting)
```

涉及 Provider 文件：

```text
ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt
```

### 4.5 KeyHealthRegistry

文件：

```text
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt
```

持久化文件：

```text
应用私有 filesDir/ai_key_health.json
```

状态：

```text
INVALID  -> 无效 Key，停用 24 小时
QUOTA    -> 无额度/欠费，停用 24 小时
COOLDOWN -> 429 限流，按 1m/2m/4m/... 指数冷却，最多 30m
```

分类顺序：

```text
HTTP >= 500                         -> NEUTRAL，不惩罚 Key
HTTP 401                            -> INVALID
HTTP 402                            -> QUOTA
明确额度词                          -> QUOTA
明确无效 Key 词                     -> INVALID
HTTP 429                            -> COOLDOWN
其他                                -> NEUTRAL
```

明确额度词示例：

```text
insufficient_quota
insufficient quota
exceeded your current quota
insufficient balance
insufficient credits
out of credits
credit balance is too low
余额不足
额度不足
欠费
```

明确无效词示例：

```text
invalid api key
invalid_api_key
incorrect api key
api key not valid
invalid x-api-key
invalid authentication
密钥无效
无效的密钥
无效key
```

持久化安全：

- `AtomicFile` 写入；
- 状态变更使用 `@Synchronized`；
- 错误摘要只记录 `invalid/quota/cooldown` 分类，不存上游原始响应；
- 旧记录启动时过滤掉已经到期的记录；
- 选择时只允许 ready Key；
- UI 可手动恢复单个 Key 或全部 Key。

### 4.6 多 Key UI

文件：

```text
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
```

入口：

```text
设置
  -> Provider 配置
    -> 多 Key 模式
      -> 管理 Key
```

功能：

- 打开/关闭多 Key；
- 从当前 `apiKey` 导入；
- 手动添加；
- 批量粘贴导入；
- 导入自动去重；
- 编辑 Key；
- 修改别名；
- 单独启用/禁用；
- 删除；
- 随机/轮询策略；
- 单 Key 连接测试；
- 显示 masked Key；
- 显示 invalid/quota/cooldown 状态；
- 恢复单个 Key；
- 恢复全部 Key；
- 导入对话框初始文本为空，不自动读取系统剪贴板。

Provider 类型转换时必须保留：

```text
multiKeyEnabled
apiKeys
keyStrategy
```

### 4.7 多 Key 生命周期

```text
用户启用多 Key
    -> enableMultiKeyFromCurrentValue()
    -> 生成 ProviderApiKey 列表
    -> syncEnabledApiKeysToLegacyField()

开始请求
    -> pick ready Key
    -> 生成单 Key ProviderSetting 副本
    -> 发送请求

请求成功
    -> 本次请求结束，不清除其他 Key 的失败状态

请求失败
    -> 根据本次 requestProvider 的真实 Key 分类
    -> INVALID/QUOTA：停用 24h
    -> COOLDOWN：指数冷却
    -> NEUTRAL：不处罚
    -> 有 ready alternative：300ms 后重新选择
    -> 没有 alternative：明确结束

用户手动启用/测试
    -> clearKeyHealth()
    -> 使用单 Key ProviderSetting 测试
    -> 失败只归因测试条目
```

---

## 5. 最终文件清单

### 5.1 新增主源码文件

```text
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt
ai/src/main/java/me/rerere/ai/util/ProviderHttpException.kt

app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderKeyManagerSheet.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/ProviderMultiKeySection.kt
app/src/main/java/me/rerere/rikkahub/ext/resilience/SafeMessageAccess.kt
app/src/main/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaver.kt
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetryConfig.kt
app/src/main/java/me/rerere/rikkahub/ext/retry/AutoRetrySettingsSheet.kt
app/src/main/java/me/rerere/rikkahub/ext/retry/RetryPolicy.kt
```

### 5.2 新增测试源码

```text
ai/src/test/java/me/rerere/ai/util/ManagedKeysTest.kt
app/src/test/java/me/rerere/rikkahub/ext/resilience/MessageResilienceTest.kt
app/src/test/java/me/rerere/rikkahub/ext/resilience/StreamDraftSaverTest.kt
app/src/test/java/me/rerere/rikkahub/ext/retry/RetryPolicyTest.kt
```

共 18 个 `@Test`，覆盖：

- 旧配置兼容；
- 多 Key 去重；
- 禁用池不能回退旧 Key；
- 轮询与单 Key 副本；
- 401/403/429/402/5xx 分类；
- 取消不重试；
- HTTP 状态码保留；
- 退避和边界；
- 非法分支索引；
- 空节点索引对齐；
- 写入期间到达新 chunk；
- 终态保存不能被旧草稿覆盖；
- 写失败后 pending 保留。

### 5.3 修改的核心官方文件

```text
ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt
ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt
ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt
ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt

app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt
app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt
app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt
app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNetworkPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt
app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt
```

### 5.4 资源文件

```text
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
```

新增并同步英文/中文字符串，包括：

- 消息错误和操作提示；
- 自动重试设置；
- HTTP/网络重试状态；
- Key 管理、健康状态、导入、测试、删除；
- 部分响应后重试的风险提示。

### 5.5 CI 文件和辅助文件

```text
.github/workflows/daily-build.yml
scripts/check_custom_static.py
docs/custom/static-check-report.json
```

---

## 6. 静态检查与正确验收方式

### 6.1 静态门禁

脚本：

```text
scripts/check_custom_static.py
```

执行：

```bash
cd /workspace/rikkahub
PYTHONPATH=/workspace/static-tools \
python3 scripts/check_custom_static.py \
  --report docs/custom/static-check-report.json
```

最终结果：

```text
检查项：253
失败：0
XML：24 个成功解析
Kotlin 文件：36 个参与静态检查
新增字符串：63 个
compiled：false（该字段表示本脚本本身不编译）
kotlin_tests_executed：false（该次门禁不运行 Kotlin 测试）
```

检查内容：

- `git diff --check`；
- XML 格式；
- 字符串资源重名；
- 英文/中文字符串对应；
- `%s/%d/%f` 占位符一致；
- `R.string` 引用存在；
- Kotlin 重复 import；
- 冲突标记；
- 草稿 worker 周期保存；
- pending 引用安全；
- 草稿/终态共享锁；
- Room 事务和会话存在性；
- NonCancellable 终态保存；
- 崩溃路径不解析懒加载 DI；
- 请求级 Key 归因；
- 空池不回退旧 Key；
- Vertex Service Account 绕过 Key 池；
- AtomicFile 健康状态；
- 5xx 不处罚 Key；
- HTTP 状态优先于 IOException；
- 部分响应默认保护；
- 取消保护；
- Provider SSE 错误接线；
- 剪贴板导入不自动读取；
- 构建文件没有被静态功能补丁意外改写。

### 6.2 Kotlin 静态语法说明

使用预编译 Tree-sitter Kotlin 做结构扫描，不代替 Kotlin 编译器。

官方基线中 `ProviderSetting.kt` 和 `GoogleProvider.kt` 已有解析器限制；最终版本与基线诊断数量一致，没有新增解析问题。

### 6.3 后续真正编译验收

必须由 GitHub Actions 完成：

```bash
./gradlew assembleRelease
```

不要把“静态门禁通过”当作“设备运行验证通过”。以下仍需在真实设备上人工验证：

```text
长回复期间切换页面
退后台后返回
主动停止生成
异常退出后重新打开
429 / 500 / 超时
部分响应后的重试开关
两个会话并行使用同一 Provider
Key 全禁用、全停用、冷却和手动恢复
Provider 类型转换
旧配置导入
真实 Firebase 行为
```

---

## 7. 最终云端构建环境

### 7.1 Android/Gradle 项目版本

当前 `app`：

```text
applicationId = me.rerere.rikkahub
versionCode = 190
versionName = 2.5.5
minSdk = 26
targetSdk = 37
compileSdk = Android 37 minorApiLevel 2
ABI = arm64-v8a, x86_64
split = arm64-v8a / x86_64 / universal
```

当前版本锁定：

```text
Gradle Wrapper = 9.6.0
Android Gradle Plugin = 9.4.0
Kotlin = 2.4.20
Compose BOM = 2026.09.00
```

Gradle Wrapper 文件：

```text
gradle/wrapper/gradle-wrapper.properties
```

### 7.2 GitHub Actions Runner

工作流：

```text
.github/workflows/daily-build.yml
```

Runner：

```text
ubuntu-latest
```

工具链：

```text
JDK：Temurin 17
action：actions/setup-java@v4
Node：22
action：actions/setup-node@v4
pnpm：11
action：pnpm/action-setup@v4
```

前端依赖：

```bash
cd web-ui
pnpm install --frozen-lockfile
```

原因：`web` 模块的 `preBuild` 会执行前端构建，必须先安装 `web-ui` 依赖。

Gradle 缓存：

```text
~/.gradle/caches
~/.gradle/wrapper
```

缓存 key：

```text
${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
```

### 7.3 构建文件准备

工作流在 Runner 上创建以下临时文件：

```text
app/app.key
local.properties
app/google-services.json
```

签名：

```bash
echo "${{ secrets.KEY_BASE64 }}" | base64 -d > app/app.key
echo "${{ secrets.SIGNING_CONFIG }}" > local.properties
```

`SIGNING_CONFIG` 的正确语义：

```properties
storeFile=app.key
storePassword=<GitHub Secret 中的签名密码>
keyAlias=rikkahub
keyPassword=<GitHub Secret 中的签名密码>
```

注意：

- JKS 不进 Git；
- 密码不进源码和本文档；
- `KEY_BASE64` 与 `SIGNING_CONFIG` 只保存在 GitHub Actions Secrets；
- 本地已验证的 JKS 路径为 Termux：`~/rikkahub_fork.jks`；
- JKS alias：`rikkahub`；
- 当前 APK 的签名使用该项目专用 JKS；
- 密钥文件、密码和 PAT 不应写入任何 Markdown、源码或 Release 日志。

### 7.4 Firebase 配置

Secret：

```text
GOOGLE_SERVICES_JSON
```

工作流逻辑：

```bash
if printf '%s' "$GOOGLE_SERVICES_JSON" | jq -e . >/dev/null 2>&1; then
    printf '%s' "$GOOGLE_SERVICES_JSON" > app/google-services.json
else
    写入合法的 build-only Firebase placeholder
fi
```

合法 placeholder 的结构必须是：

```json
{
  "project_info": {
    "project_number": "000000000000",
    "project_id": "rikkahub-build",
    "storage_bucket": "rikkahub-build.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
        "android_client_info": {
          "package_name": "me.rerere.rikkahub"
        }
      },
      "api_key": [
        {
          "current_key": "AIzaSyDUMMY_RIKKAHUB_BUILD_ONLY_KEY"
        }
      ]
    }
  ],
  "configuration_version": "1"
}
```

关键结构要求：`api_key` 必须放在与 `package_name` 匹配的 `client` 节点内部。

该 placeholder 只保证 Google Services Gradle 插件和 Release 构建通过，不代表真实 Firebase 项目配置。若需要真实 Analytics/Crashlytics，配置真实 `GOOGLE_SERVICES_JSON` Secret。

### 7.5 最终 Gradle 与发布步骤

```bash
chmod +x gradlew
./gradlew assembleRelease
```

发布：

```text
softprops/action-gh-release@v2
 tag_name: nightly
 name: Nightly Build
 prerelease: true
 files: app/build/outputs/apk/release/*.apk
```

工作流触发：

- 每天 UTC 09:00、18:00；
- 支持 `workflow_dispatch` 手动触发；
- 手动触发时无条件构建；
- 定时触发时检查过去 24 小时是否有提交。

---

## 8. 后续官方源码更新时的唯一移植流程

### 第一步：确认基线

```bash
cd /workspace/rikkahub
git fetch origin master
git log -1 --oneline origin/master
git status --short
```

确认没有未保存本地改动，再开始迁移。若远端已经包含别人新提交，不能直接覆盖。

### 第二步：先读新版对应文件

优先重新阅读：

```text
app/.../ChatService.kt
app/.../ConversationSession.kt
app/.../ConversationRepository.kt
app/.../Conversation.kt
app/.../GenerationLoop.kt
ai/.../ProviderSetting.kt
ai/.../KeyRoulette.kt
ai/.../OpenAIProvider.kt
ai/.../GoogleProvider.kt
ai/.../ClaudeProvider.kt
```

不要用旧版整文件替换；只寻找本文列出的函数/调用点。

### 第三步：复制新增扩展文件

整体复制以下目录/文件：

```text
ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt
ai/src/main/java/me/rerere/ai/util/KeyHealth.kt
ai/src/main/java/me/rerere/ai/util/ProviderHttpException.kt
app/src/main/java/me/rerere/rikkahub/ext/keys/
app/src/main/java/me/rerere/rikkahub/ext/resilience/
app/src/main/java/me/rerere/rikkahub/ext/retry/
```

如果官方包名、`UIMessage`、Provider 泛型或 Room Entity 改名，按新版 API 调整 import 和类型，不改本文的行为约束。

### 第四步：按功能一挂接

顺序：

```text
1. Conversation/MessageNode 安全索引
2. ConversationRepository.writeMessageNodesDraft
3. StreamDraftSaver
4. ChatService chunk schedule
5. ChatService saveConversation 锁与 NonCancellable
6. RouteActivity.onStop
7. RikkaHubApp/CrashHandler
8. ChatMessage/ChatList/ChatMessageBranch
9. ChatPage/ChatVM 分支切换
```

检查：

```bash
grep -RIn "messages\[.*selectIndex\|currentMessage" app/src/main/java
```

每个 UI 消费点都必须能证明空节点和非法索引安全。

### 第五步：按功能二挂接

顺序：

```text
1. AutoRetryConfig
2. NetworkSetting.autoRetry
3. RetryPolicy
4. ProviderHttpException
5. 四个 Provider 非流式/流式错误接线
6. GenerationLoop 普通重试
7. GenerationLoop 流式重试
8. AutoRetrySettingsSheet
9. 网络设置入口
10. en/zh 字符串
```

检查：

```text
取消不重试；
下游消息处理异常不重试；
HTTP 状态码不因 IOException 包装丢失；
部分响应默认不重放；
次数和延迟有边界；
```

### 第六步：按功能三挂接

顺序：

```text
1. ProviderSetting 三类新增字段
2. ProviderApiKeys
3. KeyHealth
4. KeyRoulette / KeyRotationPolicy
5. 五个认证 Provider 调用 next(providerSetting)
6. GenerationLoop 请求级 Key 副本与失败归因
7. ProviderConfigure 类型转换和入口
8. ProviderMultiKeySection
9. ProviderKeyManagerSheet
10. RikkaHubApp 初始化
11. en/zh 字符串
```

检查：

```text
所有请求是否读取实际启用 Key；
禁用 Key 是否绝不会从旧 apiKey 回退；
并发请求是否按 requestProvider 归因；
403/5xx 是否不误停用；
全池耗尽是否明确失败；
Vertex Service Account 是否绕过 Key 池；
```

### 第七步：静态门禁

```bash
PYTHONPATH=/workspace/static-tools \
python3 scripts/check_custom_static.py \
  --report docs/custom/static-check-report.json
```

必须满足：

```text
failures = []
git diff --check = success
```

### 第八步：提交与云端构建

源码和文档检查通过后：

```bash
git add <明确的源码、资源、文档和 workflow 文件>
git commit -m "feat: ... [skip ci]"
git push origin master
```

然后通过 GitHub Actions 的 `workflow_dispatch` 构建，不要把签名文件放进仓库。

### 第九步：构建后检查

必须检查：

```text
Actions 运行结果为 success
Gradle Build 为 success
Publish nightly prerelease 为 success
Release 有 arm64/universal/x86_64 三个 APK
APK ZIP 有效
APK Sig Block 42 存在
arm64 SHA-256 已记录
```

---

## 9. 维护禁忌与最终规则

1. 不要把旧版 `FULL-SPEC.md` 的 diff 直接套在新官方源码上；以本文的函数锚点和行为规则为准。
2. 不要整文件覆盖新版 `ChatService.kt`、`GenerationLoop.kt`、`ProviderSetting.kt` 或 Provider 实现。
3. 不要删掉新版 `ConversationSession.finishGeneration()` 的 `NonCancellable` 逻辑。
4. 不要用 debounce 代替周期草稿保存。
5. 不要在草稿线程写 conversation 元数据或每次刷新 FTS。
6. 不要在保存 pending 后无条件清空 pending；必须按对象引用判断是否是旧快照。
7. 不要使用全局 `lastKey` 归因并发请求。
8. 不要把 403、5xx、普通网络异常直接标记为 Key 无效。
9. 不要在全 Key 禁用时回退旧 `apiKey`。
10. 不要允许已经收到部分内容的请求默认自动重放。
11. 不要把签名 JKS、签名密码、PAT、真实 Firebase JSON 写入 Git 或本文档。
12. 不要把 `GOOGLE_SERVICES_JSON` 的 build-only placeholder 当作真实 Firebase 配置。
13. 不要把静态检查通过当作设备实测通过。
14. 每次官方更新后，都要重新跑静态门禁并通过一次云端 Release 构建。

---

## 10. 最终交付索引

```text
唯一实施文档：
docs/custom/2026-09-three-features-plan.md

静态门禁：
scripts/check_custom_static.py

静态报告：
docs/custom/static-check-report.json

源码基线：
00c8d53a5c74554c92848cf821d551a17f576995

功能实现：
9371fd28d95073fa8c262f9fc994e75961a7a8a2

源码/CI 最终提交（文档提交之前）：
df99692a684360c4b23b4f9a17e3d665c3825e46

当前远端 HEAD：以 `master` 最新提交为准；文档提交是 docs-only 提交，不改变源码构建结果。

成功构建：
Actions run 36479462858

Nightly Release：
https://github.com/xsun71136-pixel/rikkahub/releases/tag/nightly
```

以后修改这三个功能时，先只看本文件，再检查当前远端 HEAD 是否已经比本文记录更新；如果更新，先把本文和新版源码对齐，再开始改代码。
