# 原初临界加工模块设计

## 目标

新增原初模块系列多方块机器“原初临界加工模块”，将 GTLAdditions 枢纽卫星工厂 MK-1 至 MK-4 使用的全部基础加工配方类型集中到一个原初引擎模块中。

新模块沿用原初模块的既有能力：安装到原初终焉引擎模块位、按模块物品和数量提供并行、从无线电网取电、支持跨配方线程与配方类型选择，并通过 `PrimordialModuleRecipeLogic` 执行配方。

## 注册与命名

- 注册 ID：`gt_shanhai:primordial_critical_processing_module`
- Java 机器类：`PrimordialCriticalProcessingModule`
- Java 配方逻辑类：`PrimordialCriticalProcessingModuleLogic`
- 中文名：原初临界加工模块
- 英文名：Primordial Critical Processing Module
- 包路径：`com.dishanhai.gt_shanhai.common.machine.primordial.module.processing`

机器类直接继承 `PrimordialOmegaEngineModuleBase`，配方逻辑直接继承 `PrimordialModuleRecipeLogic`。不继承装配线模块，不修改或抽象现有模块类。

## 结构设计

新模块复用 `PrimordialAssemblyLineModuleStructure.createPattern()`，保持与现有原初加工模块相同的模块结构和输入输出仓室能力。

原初终焉引擎结构的 `J` 模块位白名单加入新模块控制器，使其能够安装并参与主机挂载、供电和并行体系。

## 配方类型

严格使用 GTLAdditions 枢纽卫星工厂 MK-1 至 MK-4 注册代码中的 35 种配方类型，不额外加入相近类型。

### MK-1：成型加工，共 10 种

- `GTRecipeTypes.LATHE_RECIPES`
- `GTRecipeTypes.BENDER_RECIPES`
- `GTRecipeTypes.COMPRESSOR_RECIPES`
- `GTRecipeTypes.FORGE_HAMMER_RECIPES`
- `GTRecipeTypes.CUTTER_RECIPES`
- `GTRecipeTypes.EXTRUDER_RECIPES`
- `GTRecipeTypes.MIXER_RECIPES`
- `GTRecipeTypes.WIREMILL_RECIPES`
- `GTRecipeTypes.FORMING_PRESS_RECIPES`
- `GTRecipeTypes.POLARIZER_RECIPES`

### MK-2：矿物处理，共 9 种

- `GTRecipeTypes.ROCK_BREAKER_RECIPES`
- `GTRecipeTypes.ORE_WASHER_RECIPES`
- `GTRecipeTypes.CENTRIFUGE_RECIPES`
- `GTRecipeTypes.ELECTROLYZER_RECIPES`
- `GTRecipeTypes.SIFTER_RECIPES`
- `GTRecipeTypes.MACERATOR_RECIPES`
- `GTLRecipeTypes.DEHYDRATOR_RECIPES`
- `GTRecipeTypes.THERMAL_CENTRIFUGE_RECIPES`
- `GTRecipeTypes.ELECTROMAGNETIC_SEPARATOR_RECIPES`

### MK-3：流体与化学加工，共 10 种

- `GTRecipeTypes.EVAPORATION_RECIPES`
- `GTRecipeTypes.AUTOCLAVE_RECIPES`
- `GTRecipeTypes.EXTRACTOR_RECIPES`
- `GTRecipeTypes.BREWING_RECIPES`
- `GTRecipeTypes.FERMENTING_RECIPES`
- `GTRecipeTypes.DISTILLERY_RECIPES`
- `GTRecipeTypes.DISTILLATION_RECIPES`
- `GTRecipeTypes.FLUID_HEATER_RECIPES`
- `GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES`
- `GTRecipeTypes.CHEMICAL_BATH_RECIPES`

### MK-4：装配与高能加工，共 6 种

- `GTRecipeTypes.CANNER_RECIPES`
- `GTRecipeTypes.ARC_FURNACE_RECIPES`
- `GTLRecipeTypes.LIGHTNING_PROCESSOR_RECIPES`
- `GTRecipeTypes.ASSEMBLER_RECIPES`
- `GTLRecipeTypes.PRECISION_ASSEMBLER_RECIPES`
- `GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES`

## 并行与运行行为

新模块复制原初装配线模块当前已经验证的模块槽和并行计算方式，但保持为独立机器实现：

1. 模块槽只接受既有原初并行模块物品。
2. 默认并行为 `64`。
3. 并行基础值由模块物品决定，并通过 `applyModuleCountParallelMultiplier()` 叠加物品数量倍率。
4. 跨配方线程、模块等级条件、无线能源消耗和多配方并行全部交由原初基类体系处理。
5. UI 显示已安装模块、并行上限、跨配方线程、工作状态和“临界加工”模式文本。

不复制卫星工厂的固定 `8192` 线程倍率、亚空间轨道条件或激光仓能力；这些属于卫星工厂本体，不属于原初模块体系。

## 文本与指南

新增中英文方块名、机器名、模式名和模块名语言键。Tooltip 按 MK-1 至 MK-4 分组概述 35 种配方类型，并明确说明需要安装在原初引擎模块位、按模块等级提供并行、直接从电网取电。

新增独立指南页，并在原初模块索引的页面清单和模块列表中加入新模块。

## 改动边界

- 只修改或新增 `gt_shanhai` 中山海署名的原初模块、机器注册和资源文件。
- 不修改 GTLAdditions 反编译源码。
- 不修改当前工作区中商店、钱包、网络或其他未提交功能。
- 不新增配方合成表；本次目标仅为机器与配方类型能力注册。
- 不部署 Jar 到游戏目录，除非用户后续明确要求。

## 验证标准

1. 新模块注册成功，并能被原初终焉引擎的 `J` 模块位结构识别。
2. 机器注册列表恰好包含卫星工厂 MK-1 至 MK-4 的 35 种配方类型。
3. 配方逻辑继承 `PrimordialModuleRecipeLogic`，机器继承 `PrimordialOmegaEngineModuleBase`。
4. 模块结构与原初装配线模块一致。
5. 中英文语言 JSON 可正常解析，指南入口和页面链接有效。
6. 清理旧 `build` 后执行 Gradle 完整构建，命令退出码为 `0`。
7. Git 差异不包含用户当前商店系统等无关改动。
