# 功能 3：多 Key 模式（参考 FLIT）

## 需求

在原"单 Key"输入框下方增加"多 Key 模式"开关（参考 FLIT-feature 的功能），
风格适配本项目。支持：多个 Key 分开管理、别名、单独启用/禁用、
随机/轮询策略、粘贴批量导入、单 Key 连通测试。

## 上游现状（2.5.4）

上游已有**隐式多 Key 通道**：`apiKey` 字符串可含多个 Key（空格/逗号/换行分隔），
各 provider 实现每次请求调用 `KeyRoulette.next(providerSetting.apiKey, providerId)`：

- `KeyRoulette.lru(context)`：LRU（最久未用优先），持久化到 cacheDir（OpenAI/Google/Claude provider 使用）。
- `KeyRoulette.default()`：随机。

缺失：管理 UI、每 Key 启用/禁用、别名、策略选择（用户不可控）。

## FLIT 的方案（参考源）

- `ProviderApiKey(id, value, enabled, alias)` 结构化列表 + `multiKeyEnabled` +
  `keyStrategy(RANDOM/ROUND_ROBIN)` 内嵌于各 ProviderSetting。
- `syncEnabledApiKeysToLegacyField()`：把启用的 Key 逗号拼接写回旧 `apiKey` 字段 →
  所有既有请求路径无感知兼容。
- `withSingleApiKeyForRequest(key)`：构造单 Key 副本用于测试。
- UI：`ProviderMultiKeySection`（开关 + 管理入口按钮）+ `ProviderKeyManagerSheet`
  （BottomSheet：策略分段按钮、添加/粘贴导入、Key 卡片列表、单 Key 测试）。

## 实现方案（适配 rikkahub 2.5.4）

### ai 模块：数据模型（新文件 `ai/.../provider/ProviderApiKeys.kt`）

移植 FLIT 模型并适配 rikkahub 的 3 种 ProviderSetting（OpenAI/Google/Claude）：

```kotlin
@Serializable data class ProviderApiKey(id: Uuid, value: String, enabled: Boolean = true, alias: String = "")
@Serializable enum class ProviderKeyStrategy { RANDOM, ROUND_ROBIN }
```

扩展函数：`isMultiKeyEnabled()` / `getProviderApiKeys()` / `copyWithApiKeyConfig()` /
`syncEnabledApiKeysToLegacyField()` / `normalizeProviderApiKeys()`（去重去空）/
`activeApiKeyValuesForRequest()` / `withSingleApiKeyForRequest(key)`。

### ai 模块：ProviderSetting 字段（3 处 additive 修改）

`OpenAI` / `Google` / `Claude` 各追加（带默认值，序列化前后兼容）：

```kotlin
var multiKeyEnabled: Boolean = false,
var apiKeys: List<ProviderApiKey> = emptyList(),
var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
```

### ai 模块：策略注册表（`KeyRoulette.kt` 小改）

```kotlin
object KeyRotationPolicy {
    fun sync(providers: List<ProviderSetting>)   // providerId -> strategy（仅多 Key 启用者）
    fun strategyOf(providerId: String): ProviderKeyStrategy?  // null = 保持上游原行为
}
```

- `DefaultKeyRoulette` / `LruKeyRoulette` 的 `next()` 开头查询注册表：
  - `ROUND_ROBIN` → 内存计数器轮询（`ConcurrentHashMap<providerId, AtomicInteger>`）。
  - `RANDOM` → 随机。
  - `null`（未启用多 Key 的 provider）→ **完全保持上游原行为（LRU/随机）**，零回归。
- App 启动时（`RikkaHubApp.onCreate` AppScope collect settingsFlow）同步注册表，
  设置变更即时生效。

> 复用上游"Key 字符串通道"的含义：启用多 Key 后，`apiKey` 字段=启用 Key 的逗号拼接。
> 模型拉取、连接测试、生成、Web 端等**所有**请求路径自动获得多 Key 轮换，
> 无需逐个改 13 处 `keyRoulette.next` 调用点。禁用的 Key 不进拼接串 → 立即退出轮换。

### app 模块：UI（新文件 `ext/keys/`）

`ProviderMultiKeySection.kt`：

- 行布局 = 标题"多 Key 模式" + 副标题说明 + 本项目自定义 `Switch`。
- 开启时：从当前 `apiKey` 字符串导入拆分（`normalizeProviderApiKeys`）；
  关闭时：保留列表仅停用（`multiKeyEnabled=false`，`apiKey` 还原为启用串）。
- 开启后显示 `FilledTonalButton`："管理 Key（已启用 N/M）" → 打开 Sheet。

`ProviderKeyManagerSheet.kt`（ModalBottomSheet，Material3）：

- 头部：标题 + "已启用 N/M 个 Key"摘要。
- 策略：`SingleChoiceSegmentedButtonRow`（随机 / 轮询）。
- 操作行：`添加 Key`（对话框：Key + 别名）/ `粘贴导入`（读剪贴板 →
  按逗号/空格/换行拆分去重 → 显示将导入数量确认）。
- Key 列表：卡片显示 别名（缺省 "Key N"）、掩码值（前6后4）、启用 Switch、
  编辑、删除（RikkaConfirmDialog 确认）、单 Key 测试
  （`withSingleApiKeyForRequest` + `generateText("hello")`，Loading/Success/Error 状态）。
- 所有变更经 `copyWithApiKeyConfig(...).syncEnabledApiKeysToLegacyField()` 写回
  provider → 既有 `onEdit` 回调保存 Settings。

### 官方文件触点

- `ProviderConfigure.kt`：三个 provider 配置函数在 API Key 输入框后各插入一行
  `ProviderMultiKeySection(provider, onEdit适配)`；`convertTo()` 转换时保留
  multiKeyEnabled/apiKeys/keyStrategy 字段。
- `RikkaHubApp.kt`：启动时同步 `KeyRotationPolicy`。
- `strings.xml`（values / values-zh）：新增文案。

## 交互细节

- 单 Key 模式下行为与上游完全一致（字段默认 false/空，注册表返回 null → LRU 原样）。
- 多 Key 开启但列表为空 → `activeApiKeyValuesForRequest()` 为空 →
  拼接串为空 → 回退原 `apiKey` 值，不会导致无 Key 请求。
- Key 掩码显示，防肩窥；完整值仅在编辑对话框可见（密码样式可切换）。

## 验证

1. 单 Key 用户升级后：行为不变（LRU 轮换多 Key 字符串照旧）。
2. 开启多 Key：原 apiKey 中逗号分隔的 Key 自动导入列表。
3. 添加 3 个 Key，禁用 1 个 → 生成请求只会轮到 2 个启用 Key（日志 Authorization 验证）。
4. 策略切"轮询" → 连续请求按序轮换；切"随机" → 随机。
5. 单 Key 测试：正确 Key 显示成功，错误 Key 显示错误信息。
6. 粘贴导入：含重复/空行的文本 → 去重导入并 Toast 数量。
7. 重启应用：配置保留；provider 类型转换（OpenAI→Claude）后 Key 列表保留。
