# Issue #44: EnhancedCore 原生机器仓室兼容

## 原因与修复

EnhancedCore 的 `IvNativeHatches.materials(SimplePredicate)` 会在任一候选具有材料 IO 能力时，将整个谓词视为材料仓。山海的多能力仓同时注册能源、维护和 IO，因此能源/维护谓词可能被 `restrict` 整项删除。超级冶炼炉的标识为 `super_blast_smelter`，不是 `large_furnace`。

修复在 GTCEu `abilities` / `ability` 工厂返回时记录能力来源。只要请求包含非材料能力，兼容注入便阻止该谓词被归为材料仓。标记独立于枢纽准入/计数逻辑，不改变 global/layer 数量界限，也不硬编码某一个枢纽。

- 纯材料能力谓词仍执行增强版原分类和材料端口替换。
- 显式方块、未知来源谓词仍执行增强版原逻辑。
- `energy.or(IO)` 中独立的 IO 谓词仍会替换。
- 单一 `abilities(IO, energy)` 混合谓词整体保留；无法删除其 IO 分支而不改变谓词语义。这是有意的保守边界。
- `TraceabilityPredicate` 拷贝和 OR 保留原 `SimplePredicate` 实例，来源标记随之保留。
- 可选增强版目标使用字符串、`@Pseudo` 和 `require = 0`，不存在时不建立强制依赖。

## 验证范围

回归测试使用依赖 JAR 内的真实算法字节码和生产注入方法，世界/注册表以隔离替身提供。它不是完整 Forge 游戏启动或实际存档成型测试。

- `EnhancedCoreNativeHatchesCompatibilityTest`：增强 3.8.9 / 4.1.7 / 4.6.5，各自验证修复前误删与修复后保留，以及正常材料替换；另检查真实 GTCEu 复制和可选类加载，共 8 项。
- `ShanhaiHubSlotCompatibilityTest`：17 项计数/准入/来源工厂回归，包含维护、能源、自定义和混合能力。
- 构建验证包括 Java/Kotlin 编译、重映射和 JAR 必要类完整性检查。Linux 本地验证仅临时把原有 Windows 绝对依赖路径映射到仓库 `libs` 内同名依赖，未改变正式构建配置。

手工验收建议：增强版默认启用原生机器增强，分别不装和安装山海；检查超级冶炼炉的预览、普通能源仓/维护仓实际成型，多能力枢纽与其他混合仓，以及正常材料仓替换和数量上限/下限。另测未安装 EnhancedCore 的启动。

2026-11-16 全套对照：基线 `fb46ae43` 为 2425 项、45 失败、3 跳过；修复后为 2433 项、45 失败、3 跳过。失败 testcase 集合完全一致，无新增失败。不能把全套测试表述为全部通过。
