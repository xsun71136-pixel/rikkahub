# 自定义功能插件层（Custom Plugin Layer）

本目录记录本 fork 相对上游 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的全部自定义功能。

## 设计原则：插件式修改

为了在上游更新源码后可以低成本地重新套用自定义功能，所有修改遵循：

1. **新代码集中**：所有新增逻辑放在独立的新文件（`ext` 包 / ai 模块新文件），不分散进官方文件。
2. **官方文件只留钩子**：对上游既有文件的修改尽量是"单行调用 / 小段替换"，全部登记在 [PATCHES.md](PATCHES.md)。
3. **文档随功能走**：每个功能一篇设计文档，说明动机、根因、实现、触点、验证方式。

## 功能清单

| # | 功能 | 文档 | 状态 |
|---|------|------|------|
| 1 | 消息防丢失与切换崩溃修复（流式草稿落库 + 索引安全 + 崩溃应急保存） | [01-message-resilience.md](01-message-resilience.md) | ✅ |
| 2 | 可自定义自动重试（参考 kelivo，长按设置行进入高级配置） | [02-auto-retry.md](02-auto-retry.md) | ✅ |
| 3 | 多 Key 模式（参考 FLIT，单 Key 输入框下方增加开关 + Key 管理器） | [03-multi-key.md](03-multi-key.md) | ✅ |

## 上游更新后如何回归（Re-apply Guide)

1. 同步上游（Sync fork / merge）。
2. 打开 [PATCHES.md](PATCHES.md)，按"官方文件触点清单"逐条检查：
   - 每条触点给出：文件、函数/锚点、修改内容、为什么。
   - 若上游重构了锚点所在函数，按"意图"一栏在新代码里找到等价位置重新套用。
3. 新增文件（`ext/`、`ai/.../ProviderApiKeys.kt`、`docs/custom/`）不与上游冲突，直接保留。
4. 编译验证：`./gradlew assembleDebug` 或触发云端 daily-build。
5. 冒烟测试清单见各功能文档末尾的"验证"一节。

## 目录结构约定

```
app/src/main/java/me/rerere/rikkahub/ext/   # 应用层插件代码
├── resilience/                              # 功能1：防丢失/防崩溃
├── retry/                                   # 功能2：高级自动重试
└── keys/                                    # 功能3：多 Key UI

ai/src/main/java/me/rerere/ai/provider/ProviderApiKeys.kt   # 功能3：ai 层数据模型
```
