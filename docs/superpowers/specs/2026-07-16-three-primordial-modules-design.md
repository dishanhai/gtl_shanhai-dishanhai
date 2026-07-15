# 三个原初加工模块设计

## 目标

新增三个可安装到原初终焉引擎 `J` 位的原初加工模块，并新增 `gtceu:primordial_stellar_reaction` 配方类型。三个模块继续使用 `PrimordialModuleRecipeLogic` 的选配方、多配方并行、模块等级、无线供电与线程倍率体系。

## 共用机器基类

新增只供这三个模块使用的共用机器基类，直接继承 `PrimordialOmegaEngineModuleBase`。基类集中实现与现有原初加工模块一致的内容：

- 物质并行模块槽和既有模块倍率表。
- 默认并行 `64`，模块数量倍率使用 `applyModuleCountParallelMultiplier()`。
- 每 3 tick 刷新并行模块。
- 线程倍率槽持久化、并行显示、无线能源和工作状态显示。
- 子类只提供机器名称键、模式键和各自的配方逻辑。

不重构现有原初装配线、临界加工或聚爆模块，避免扩大改动范围。

## 原初超临界物质生成核心

- 注册 ID：`gt_shanhai:primordial_supercritical_matter_generation_core`
- 英文名：`Primordial Supercritical Matter Generation Core`
- 配方类型：
  - `GTLRecipeTypes.SPS_CRAFTING_RECIPES`，对应 `gtceu:sps_crafting`。
  - `GTLRecipeTypes.MATTER_FABRICATOR_RECIPES`，忽略来源署名前缀，使用当前依赖中的 `matter_fabricator` 类型。
  - `GTLRecipeTypes.MASS_FABRICATOR_RECIPES`，对应 `gtceu:mass_fabricator`。

## 原初宇宙反应炉

- 注册 ID：`gt_shanhai:primordial_cosmic_reactor`
- 英文名：`Primordial Cosmic Reactor`
- 配方类型：
  - `GTLRecipeTypes.SUPER_PARTICLE_COLLIDER_RECIPES`。
  - `GTRecipeTypes.FUSION_RECIPES`。
  - `DShanhaiRecipeTypes.PRIMORDIAL_STELLAR_REACTION`。

新增配方类型 `PRIMORDIAL_STELLAR_REACTION`：

- 注册路径：`gtceu:primordial_stellar_reaction`。
- 类型：`multiblock`。
- 最大物品输入/输出：`5 / 3`。
- 最大流体输入/输出：`5 / 3`。
- 能源方向：输入。
- 使用聚变进度条、从左到右填充、聚变音效，并开启聚变类型的电压文本偏移。

该类型由 `gt_shanhai` 项目维护，但遵循 GTCEu 配方类型的默认 `gtceu` 命名空间；直接在 `DShanhaiRecipeTypes.init()` 中调用现有 `GTRecipeTypes.register("primordial_stellar_reaction", "multiblock")`，再配置上述 IO 和 UI 属性。

本次只创建配方类型入口，不添加具体原初恒星反应配方。

## 原初分子裂隙核心

- 注册 ID：`gt_shanhai:primordial_molecular_rift_core`
- 英文名：`Primordial Molecular Rift Core`
- 配方类型：
  - `GTLRecipeTypes.DISTORT_RECIPES`。
  - `GTRecipeTypes.LARGE_CHEMICAL_RECIPES`。
  - `GTRecipeTypes.CHEMICAL_BATH_RECIPES`。

## 结构与资源

- 三台机器均复用 `PrimordialAssemblyLineModuleStructure.createPattern()`。
- 三台控制器均加入 `PrimordialOmegaEngineStructure` 的 `J` 位白名单。
- 每台机器新增独立中英文名称、模式文本、Tooltip 和指南页面。
- 原初模块索引追加三台机器，但必须保留用户当前未提交的索引改动。
- 新配方类型新增 `gtceu.*`、`gtceu.recipe_type.*` 与 `recipe_type.*` 中文和英文显示键。

## 改动边界

- 不修改 GTLCore、GTCEu 或其它模组反编译源码。
- 不修改或提交当前样板缓存、库存面板材质和相关测试改动。
- 不新增机器合成配方，不部署 Jar 到游戏目录。
- 只修改山海署名文件和本次新增文件。

## 验证标准

1. 三个机器类使用共用原初加工基类，三个逻辑类直接继承 `PrimordialModuleRecipeLogic`。
2. 三台机器分别恰好注册 `3 / 3 / 3` 个指定配方类型，无重复或串用。
3. `PRIMORDIAL_STELLAR_REACTION` 的 IO 为物品 `5/3`、流体 `5/3`，并使用聚变显示与音效。
4. 三个控制器均被原初引擎 `J` 位识别。
5. 中英文 JSON 可解析，指南位置不重复，索引链接存在。
6. 安全清理项目 `build` 后完整 Gradle 构建退出码为 `0`。
7. 本功能提交不包含用户当前样板缓存和库存面板相关改动。
