# 三个原初加工模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增原初超临界物质生成核心、原初宇宙反应炉、原初分子裂隙核心，并创建 `gtceu:primordial_stellar_reaction` 配方类型。

**Architecture:** 新增一个直接继承 `PrimordialOmegaEngineModuleBase` 的共用加工模块基类，集中既有并行槽、模块倍率、线程槽和显示逻辑。三台机器分别拥有独立机器类与直接继承 `PrimordialModuleRecipeLogic` 的逻辑类，复用装配线模块结构；机器定义集中声明配方类型。

**Tech Stack:** Java 17、Forge 1.20.1、GTCEu、GTLCore、GTLAdditions、Gradle 8.8、GuideME Markdown。

---

### Task 1: 共用原初加工模块基类

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/PrimordialParallelProcessingModuleBase.java`

- [ ] **Step 1: 建立失败基线**

Run:

```powershell
if (Test-Path 'src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/PrimordialParallelProcessingModuleBase.java') { throw '基类已存在' }
```

Expected: 退出码 `0`。

- [ ] **Step 2: 实现基类**

基类直接继承 `PrimordialOmegaEngineModuleBase`，从现有原初加工模块提取并保持以下完整接口：

```java
public abstract class PrimordialParallelProcessingModuleBase extends PrimordialOmegaEngineModuleBase {
    private static final Map<Item, Long> ITEM_PARALLEL_MAP = new HashMap<>();
    private static final long DEFAULT_PARALLEL = 64L;
    private long currentParallel = DEFAULT_PARALLEL;
    private TickableSubscription parallelScanSubs;
    private final NotifiableItemStackHandler machineStorage;

    protected PrimordialParallelProcessingModuleBase(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) setUuid(UUID.randomUUID());
        initItems();
        machineStorage = createMachineStorage();
    }

    protected abstract String getMachineNameKey();
    protected abstract String getMachineModeKey();
}
```

`initItems()` 必须包含现有 17 个物质模块与完全相同的倍率表；`getPersistedStorages()` 返回 `{ machineStorage, threadBoostSlot }`；`onStructureFormed()` 每 3 tick 刷新；`getMaxParallel()` 使用 `Ints.saturatedCast`；`createUIWidget()` 添加并行槽；`addDisplayText()` 通过两个抽象语言键显示名称和模式。

- [ ] **Step 3: 编译基类**

Run: `.\gradle-install\gradle-8.8\bin\gradle.bat compileJava --no-daemon`

Expected: `BUILD SUCCESSFUL`。

### Task 2: 三台独立机器与逻辑

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/matter/PrimordialSupercriticalMatterGenerationCore.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/matter/PrimordialSupercriticalMatterGenerationCoreLogic.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/reactor/PrimordialCosmicReactor.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/reactor/PrimordialCosmicReactorLogic.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/rift/PrimordialMolecularRiftCore.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/rift/PrimordialMolecularRiftCoreLogic.java`

- [ ] **Step 1: 创建三个直接继承原初逻辑的类**

每个逻辑类使用相同形式，类名和包名分别对应机器：

```java
public class PrimordialCosmicReactorLogic extends PrimordialModuleRecipeLogic {
    public PrimordialCosmicReactorLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }
}
```

- [ ] **Step 2: 创建三个机器类**

每个机器类继承 `PrimordialParallelProcessingModuleBase`，并实现构造器、配方逻辑与语言键。宇宙反应炉的完整形式为：

```java
public class PrimordialCosmicReactor extends PrimordialParallelProcessingModuleBase {
    public PrimordialCosmicReactor(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialCosmicReactorLogic createRecipeLogic(Object... args) {
        return new PrimordialCosmicReactorLogic(this);
    }

    @Override
    public PrimordialCosmicReactorLogic getRecipeLogic() {
        return (PrimordialCosmicReactorLogic) recipeLogic;
    }

    @Override
    protected String getMachineNameKey() {
        return "gt_shanhai.machine.primordial_cosmic_reactor.name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gt_shanhai.machine.primordial_cosmic_reactor.mode";
    }
}
```

另外两台机器使用相同接口，语言键分别为：

```text
gt_shanhai.machine.primordial_supercritical_matter_generation_core.name/mode
gt_shanhai.machine.primordial_molecular_rift_core.name/mode
```

- [ ] **Step 3: 验证继承关系**

Run:

```powershell
rg -n "extends PrimordialParallelProcessingModuleBase|extends PrimordialModuleRecipeLogic" src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module
```

Expected: 三个机器类与三个逻辑类全部命中。

### Task 3: 新配方类型、机器注册与结构白名单

**Files:**
- Modify: `src/main/java/com/dishanhai/gt_shanhai/api/recipe/DShanhaiRecipeTypes.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineStructure.java`

- [ ] **Step 1: 注册原初恒星反应类型**

新增字段：

```java
public static GTRecipeType PRIMORDIAL_STELLAR_REACTION;
```

在 `init()` 中注册：

```java
PRIMORDIAL_STELLAR_REACTION = GTRecipeTypes.register("primordial_stellar_reaction", "multiblock")
        .setMaxIOSize(5, 3, 5, 3)
        .setEUIO(IO.IN)
        .setMaxTooltips(4)
        .setProgressBar(GuiTextures.PROGRESS_BAR_FUSION, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
        .setSound(GTSoundEntries.ARC)
        .setOffsetVoltageText(true);
```

- [ ] **Step 2: 注册三台机器**

三台机器均使用 `PrimordialAssemblyLineModuleStructure::createPattern` 和现有青铜外观/原初渲染器，配方类型列表必须严格为：

```java
// 原初超临界物质生成核心
GTLRecipeTypes.SPS_CRAFTING_RECIPES,
GTLRecipeTypes.MATTER_FABRICATOR_RECIPES,
GTLRecipeTypes.MASS_FABRICATOR_RECIPES

// 原初宇宙反应炉
GTLRecipeTypes.SUPER_PARTICLE_COLLIDER_RECIPES,
GTRecipeTypes.FUSION_RECIPES,
DShanhaiRecipeTypes.PRIMORDIAL_STELLAR_REACTION

// 原初分子裂隙核心
GTLRecipeTypes.DISTORT_RECIPES,
GTRecipeTypes.LARGE_CHEMICAL_RECIPES,
GTRecipeTypes.CHEMICAL_BATH_RECIPES
```

Tooltip 分别说明物质生成、宇宙反应与分子裂隙定位，并保留“安装在引擎模块位、按模块等级并行、无线取电”提示。

- [ ] **Step 3: 加入 `J` 位白名单**

读取三个控制器方块，并在 `J` 位候选链加入：

```java
.or(Predicates.blocks(supercriticalMatterGenerationCore))
.or(Predicates.blocks(cosmicReactor))
.or(Predicates.blocks(molecularRiftCore))
```

- [ ] **Step 4: 静态检查注册集合**

Run PowerShell 正则分别提取三个 `.multiblock(...)` 注册块，断言每块类型数量为 `3`、无重复，并与任务列表完全相等；同时断言新配方类型包含 `.setMaxIOSize(5, 3, 5, 3)`。

Expected: 三组集合均精确匹配。

### Task 4: 语言与指南

**Files:**
- Modify: `src/main/resources/assets/gt_shanhai/lang/zh_cn.json`
- Modify: `src/main/resources/assets/gt_shanhai/lang/en_us.json`
- Modify: `src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_index.md`
- Create: `src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_supercritical_matter_generation_core.md`
- Create: `src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_cosmic_reactor.md`
- Create: `src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_molecular_rift_core.md`

- [ ] **Step 1: 添加机器语言键**

每台机器添加 `block.*`、`gt_shanhai.machine.*.name`、`gt_shanhai.machine.*.mode`、`module.*.name` 四个键；中英文名称对应规格。

- [ ] **Step 2: 添加新配方类型语言键**

```json
"gtceu.primordial_stellar_reaction": "原初恒星反应",
"gtceu.recipe_type.primordial_stellar_reaction": "原初恒星反应",
"recipe_type.primordial_stellar_reaction": "原初恒星反应"
```

英文值统一为 `Primordial Stellar Reaction`。

- [ ] **Step 3: 创建指南并更新索引**

三个页面使用未占用的连续导航位置，说明各自配方类型、并行槽与引擎挂载规则。在 `primordial_index.md` 的 `item_ids`、专用产线和产线模块三处追加链接；应用补丁前先读取当前文件，保留用户未提交内容。

- [ ] **Step 4: 验证资源**

Run:

```powershell
Get-Content -Raw src/main/resources/assets/gt_shanhai/lang/zh_cn.json | ConvertFrom-Json | Out-Null
Get-Content -Raw src/main/resources/assets/gt_shanhai/lang/en_us.json | ConvertFrom-Json | Out-Null
```

Expected: JSON 解析成功，指南导航位置无重复。

### Task 5: 构建、审查和经验记录

**Files:**
- Modify: `C:/Users/dishanhai/Desktop/ide专属文件/.learnings/LEARNINGS.md`

- [ ] **Step 1: 安全清理并完整构建**

确认解析后的删除目标严格等于项目根目录下 `build`，再执行：

```powershell
.\gradle-install\gradle-8.8\bin\gradle.bat build --no-daemon
```

Expected: `BUILD SUCCESSFUL`，退出码 `0`。

- [ ] **Step 2: 审计差异边界**

只提交本计划列出的文件，不提交 `RecipeTypePatternBufferPartMachine.java`、库存面板图片或相关测试。运行 `git diff --check`，核对三组配方类型和新类型 IO。

- [ ] **Step 3: 记录经验**

记录 `gtceu:primordial_stellar_reaction` 虽由 gt_shanhai 项目新增，但遵循 `GTRecipeTypes.register` 的默认 `gtceu` 命名空间；三个新模块通过共用基类复用并行槽实现。
