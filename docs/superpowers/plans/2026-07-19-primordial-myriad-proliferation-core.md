# Primordial Myriad Proliferation Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增原初万象衍生核心，以空闲 10x、二级晋升 100x、一级晋升 1000x 的非叠加阶梯倍率，统一放大原始终焉引擎挂载模块的全部配方输出并强制满概率。

**Architecture:** 核心实现窄接口并从自身实际运行配方解析当前阶段；宿主按 tick 扫描已连接模块并缓存最高倍率；`PrimordialModuleRecipeLogic` 在输出容量预检和最终无线输出构建时分别创建一次放大副本。两个晋升配方类型由山海署名 KubeJS startup script 注册，Java 只按 `gtceu` ID 查表。

**Tech Stack:** Java 17、Forge 1.20.1、GTCEu 1.4.4、GTLCore 1.2.3.0-fix9、GTLAdditions、KubeJS Rhino、JUnit 5、Gradle 8.8。

---

## 文件结构

**新增：**

- `src/main/java/com/dishanhai/gt_shanhai/api/machine/primordial/IPrimordialOutputMultiplierModule.java`：功能模块倍率接口。
- `src/main/java/com/dishanhai/gt_shanhai/api/recipe/PrimordialMyriadRecipeTypes.java`：两个启动期配方类型的 ID 与严格查表入口。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialRecipeOutputAmplifier.java`：只负责输出倍率和概率满值的配方副本变换。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCore.java`：核心机器及阶段状态。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreLogic.java`：核心自身的双配方类型运行逻辑。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreStructure.java`：仅要求物品/流体输入能力的标准原初模块结构。
- `src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java`：阶段、非叠加与输出变换回归。
- `D:/minecraft/gtl/.minecraft/versions/八周目/.minecraft/versions/GTL九周目/.minecraft/versions/GTL九周目/kubejs/startup_scripts/山海的原初万象晋升配方类型.js`：Rhino/IIFE 启动期配方类型注册。

**修改：**

- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineMachine.java`：聚合并缓存最高挂载倍率。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineModuleBase.java`：向模块逻辑暴露宿主倍率。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialModuleRecipeLogic.java`：接入放大后的容量检查与输出构建。
- `src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java`：注册核心机器、结构、配方类型和 tooltip。
- `src/main/resources/assets/gt_shanhai/lang/zh_cn.json`：中文机器、模式和配方类型文本。
- `src/main/resources/assets/gt_shanhai/lang/en_us.json`：英文机器、模式和配方类型文本。

---

### Task 1: 注册两个启动期晋升配方类型

**Files:**
- Create: `D:/minecraft/gtl/.minecraft/versions/八周目/.minecraft/versions/GTL九周目/.minecraft/versions/GTL九周目/kubejs/startup_scripts/山海的原初万象晋升配方类型.js`
- Create: `src/main/java/com/dishanhai/gt_shanhai/api/recipe/PrimordialMyriadRecipeTypes.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java`

- [ ] **Step 1: 写配方类型契约失败测试**

在测试中读取 Java 查表类，固定两个完整 ID，并禁止 `GTRecipeTypes.register`：

```java
@Test
void ascensionRecipeTypesAreLookedUpWithoutJavaRegistration() throws Exception {
    String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/api/recipe/PrimordialMyriadRecipeTypes.java"));
    assertTrue(source.contains("gtceu:primordial_myriad_ascension_tier_2"));
    assertTrue(source.contains("gtceu:primordial_myriad_ascension_tier_1"));
    assertTrue(source.contains("GTRegistries.RECIPE_TYPES.get"));
    assertFalse(source.contains("GTRecipeTypes.register"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle.bat test --tests "com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialMyriadProliferationCoreTest" --no-daemon
```

Expected: FAIL，提示 `PrimordialMyriadRecipeTypes.java` 不存在。

- [ ] **Step 3: 新建 Rhino/IIFE startup script**

```javascript
(function () {
    'use strict';

    GTCEuStartupEvents.registry('gtceu:recipe_type', function (event) {
        function createAscensionType(id) {
            event.create(id, 'basic')
                .category('multiblock')
                .setMaxIOSize(4, 0, 4, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, true, GuiTextures.FLUID_SLOT)
                .setSound(GTSoundEntries.ARC);
        }

        createAscensionType('gtceu:primordial_myriad_ascension_tier_2');
        createAscensionType('gtceu:primordial_myriad_ascension_tier_1');
    });
})();
```

- [ ] **Step 4: 新建严格 Java 查表类**

```java
public final class PrimordialMyriadRecipeTypes {
    public static final ResourceLocation TIER_2_ID =
            new ResourceLocation("gtceu", "primordial_myriad_ascension_tier_2");
    public static final ResourceLocation TIER_1_ID =
            new ResourceLocation("gtceu", "primordial_myriad_ascension_tier_1");

    public static GTRecipeType requireTier2() {
        return require(TIER_2_ID);
    }

    public static GTRecipeType requireTier1() {
        return require(TIER_1_ID);
    }

    private static GTRecipeType require(ResourceLocation id) {
        GTRecipeType type = GTRegistries.RECIPE_TYPES.get(id);
        if (type == null) {
            throw new IllegalStateException("缺少启动期配方类型: " + id);
        }
        return type;
    }

    private PrimordialMyriadRecipeTypes() {}
}
```

- [ ] **Step 5: 验证脚本语法和 Java 测试**

Run:

```powershell
Select-String -LiteralPath "D:\minecraft\gtl\.minecraft\versions\八周目\.minecraft\versions\GTL九周目\.minecraft\versions\GTL九周目\kubejs\startup_scripts\山海的原初万象晋升配方类型.js" -Pattern "=>|\bconst\b|\blet\b|GTRecipeTypes.register"
.\gradle-install\gradle-8.8\bin\gradle.bat test --tests "com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialMyriadProliferationCoreTest" --no-daemon
```

Expected: `Select-String` 无输出；测试进入下一项失败而不是配方类型契约失败。

- [ ] **Step 6: 提交配方类型**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/api/recipe/PrimordialMyriadRecipeTypes.java src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java
git commit -m "feat(primordial): 添加万象衍生晋升配方类型引用"
```

游戏端 startup script 不属于模组仓库提交，但必须保留在九周目真实 KubeJS 路径。

### Task 2: 添加功能接口和宿主最高倍率聚合

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/api/machine/primordial/IPrimordialOutputMultiplierModule.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineMachine.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineModuleBase.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java`

- [ ] **Step 1: 写宿主聚合失败测试**

```java
@Test
void hostTakesHighestMultiplierWithoutStacking() throws Exception {
    String host = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineMachine.java"));
    assertTrue(host.contains("instanceof IPrimordialOutputMultiplierModule"));
    assertTrue(host.contains("Math.max(multiplier, outputModule.getCurrentOutputMultiplier())"));
    assertTrue(host.contains("if (multiplier >= 1000)"));
    assertFalse(host.contains("multiplier *= outputModule"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: Task 1 的定向 Gradle 命令。

Expected: FAIL，宿主尚未实现接口扫描。

- [ ] **Step 3: 新建窄接口**

```java
public interface IPrimordialOutputMultiplierModule {
    int getCurrentOutputMultiplier();
}
```

- [ ] **Step 4: 在宿主添加每 tick 最高值缓存**

在 `PrimordialOmegaEngineMachine` 添加：

```java
private long cachedOutputMultiplierTick = Long.MIN_VALUE;
private int cachedOutputMultiplier = 1;

public int getMountedOutputMultiplier() {
    long tick = getOffsetTimer();
    if (cachedOutputMultiplierTick == tick) return cachedOutputMultiplier;
    int multiplier = 1;
    for (var module : modules) {
        if (module instanceof IPrimordialOutputMultiplierModule outputModule) {
            multiplier = Math.max(multiplier, outputModule.getCurrentOutputMultiplier());
            if (multiplier >= 1000) break;
        }
    }
    cachedOutputMultiplierTick = tick;
    cachedOutputMultiplier = multiplier;
    return multiplier;
}

private void invalidateOutputMultiplierCache() {
    cachedOutputMultiplierTick = Long.MIN_VALUE;
    cachedOutputMultiplier = 1;
}
```

`addModule`、`removeModule`、`safeClearModules`、`onStructureInvalid` 调用失效方法。`addDisplayText` 在已形成状态增加：

```java
int outputMultiplier = getMountedOutputMultiplier();
if (outputMultiplier > 1) {
    textList.add(Component.literal("万象衍生倍率: " + outputMultiplier + "x")
            .withStyle(ChatFormatting.LIGHT_PURPLE));
}
```

- [ ] **Step 5: 在模块基类暴露宿主倍率**

```java
public int getHostOutputMultiplier() {
    PrimordialOmegaEngineMachine currentHost = getHost();
    if (currentHost == null || !currentHost.isFormed()) return 1;
    return currentHost.getMountedOutputMultiplier();
}
```

- [ ] **Step 6: 运行定向测试并提交**

Expected: 宿主聚合测试 PASS。

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/api/machine/primordial/IPrimordialOutputMultiplierModule.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineMachine.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineModuleBase.java src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java
git commit -m "feat(primordial): 聚合原初模块最高产出倍率"
```

### Task 3: 实现原初万象衍生核心状态机

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCore.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreLogic.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreStructure.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java`

- [ ] **Step 1: 写纯状态解析失败测试**

```java
@Test
void stageRequiresAnAttachedCoreAndActivelyRunningRecipe() {
    assertEquals(1, PrimordialMyriadProliferationCore.resolveOutputMultiplier(false, false, null));
    assertEquals(10, PrimordialMyriadProliferationCore.resolveOutputMultiplier(true, false, null));
    assertEquals(100, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
            true, true, PrimordialMyriadRecipeTypes.TIER_2_ID));
    assertEquals(1000, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
            true, true, PrimordialMyriadRecipeTypes.TIER_1_ID));
    assertEquals(10, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
            true, true, new ResourceLocation("gtceu", "electric_furnace")));
}
```

- [ ] **Step 2: 运行测试确认失败**

Expected: FAIL，核心类和解析方法不存在。

- [ ] **Step 3: 实现核心状态方法**

核心继承 `PrimordialOmegaEngineModuleBase` 并实现接口：

```java
static int resolveOutputMultiplier(boolean attached, boolean working, ResourceLocation recipeTypeId) {
    if (!attached) return 1;
    if (!working || recipeTypeId == null) return 10;
    if (PrimordialMyriadRecipeTypes.TIER_1_ID.equals(recipeTypeId)) return 1000;
    if (PrimordialMyriadRecipeTypes.TIER_2_ID.equals(recipeTypeId)) return 100;
    return 10;
}

@Override
public int getCurrentOutputMultiplier() {
    boolean attached = isFormed() && isHostConnected();
    var logic = getRecipeLogic();
    GTRecipe recipe = logic.getLastRecipe();
    ResourceLocation typeId = recipe != null && recipe.recipeType != null
            ? recipe.recipeType.registryName : null;
    return resolveOutputMultiplier(attached, logic.isWorking(), typeId);
}
```

`createRecipeLogic` 返回新的 `PrimordialMyriadProliferationCoreLogic`；该逻辑只调用 `super(machine)`，不修改并行、概率或配方查找。

- [ ] **Step 4: 实现只允许输入能力的结构**

复用 `MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST_MODULE()`。所有 casing 字符沿用青铜/工业蒸汽方块池；`B` 仅增加：

```java
.or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
.or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
```

禁止添加 `EXPORT_ITEMS` 或 `EXPORT_FLUIDS`。

- [ ] **Step 5: 添加核心显示状态**

`addDisplayText` 在形成时调用 `addHostStatusDisplay`、`addEnergyDisplay`、`addWorkingStatus`，并显示：

```java
int multiplier = getCurrentOutputMultiplier();
String stage = multiplier >= 1000 ? "一级晋升" : multiplier >= 100 ? "二级晋升" : "基础衍生";
textList.add(Component.literal("当前阶段: " + stage).withStyle(ChatFormatting.LIGHT_PURPLE));
textList.add(Component.literal("宿主配方产出: " + multiplier + "x").withStyle(ChatFormatting.AQUA));
```

- [ ] **Step 6: 运行状态测试并提交**

Expected: 五个状态断言全部 PASS。

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCore.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreLogic.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreStructure.java src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java
git commit -m "feat(primordial): 实现万象衍生核心晋升状态"
```

### Task 4: 统一放大输出并强制满概率

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialRecipeOutputAmplifier.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialModuleRecipeLogic.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java`

- [ ] **Step 1: 写输出变换契约失败测试**

```java
@Test
void amplificationCopiesOnlyOutputsAndForcesNormalAndTickChance() throws Exception {
    String amplifier = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialRecipeOutputAmplifier.java"));
    String logic = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialModuleRecipeLogic.java"));
    assertTrue(amplifier.contains("recipe.copy(ContentModifier.multiplier(multiplier), false)"));
    assertTrue(amplifier.contains("forceFullChance(copy.outputs)"));
    assertTrue(amplifier.contains("forceFullChance(copy.tickOutputs)"));
    assertTrue(amplifier.contains("content.chance = 10000"));
    assertTrue(amplifier.contains("content.maxChance = 10000"));
    assertTrue(logic.contains("amplifyForMountedCore(recipe)"));
    assertTrue(logic.contains("amplifyForMountedCore(processedRecipes.get(i))"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Expected: FAIL，输出变换类不存在。

- [ ] **Step 3: 实现幂等调用边界明确的变换工具**

```java
final class PrimordialRecipeOutputAmplifier {
    static GTRecipe apply(GTRecipe recipe, int multiplier) {
        if (recipe == null || multiplier <= 1) return recipe;
        GTRecipe copy = recipe.copy(ContentModifier.multiplier(multiplier), false);
        forceFullChance(copy.outputs);
        forceFullChance(copy.tickOutputs);
        return copy;
    }

    private static void forceFullChance(Map<?, List<Content>> contentsMap) {
        for (List<Content> contents : contentsMap.values()) {
            for (Content content : contents) {
                content.chance = 10000;
                content.maxChance = 10000;
            }
        }
    }

    private PrimordialRecipeOutputAmplifier() {}
}
```

- [ ] **Step 4: 在逻辑中添加唯一宿主变换入口**

```java
private GTRecipe amplifyForMountedCore(GTRecipe recipe) {
    MetaMachine machine = getMachine();
    if (!(machine instanceof PrimordialOmegaEngineModuleBase module)) return recipe;
    return PrimordialRecipeOutputAmplifier.apply(recipe, module.getHostOutputMultiplier());
}
```

修改 `getMaxParallel`：先对单份原配方创建容量预检副本，再交给 `IParallelLogic.getMaxParallel` 和 `matchRecipeOutput`。

```java
GTRecipe amplified = amplifyForMountedCore(recipe);
long max = IParallelLogic.getMaxParallel(getMachine(), amplified, limit);
return max > 0L && RecipeRunnerHelper.matchRecipeOutput(getMachine(), amplified) ? max : 0L;
```

修改 `buildFinalWirelessRecipe`：

- `shouldProcess` 分支将 `amplifyForMountedCore(recipe)` 传入 `findMatchableScaledRecipe`，再执行 `getRecipeOutputChance`。
- `processedRecipeList` 分支在 `collectOutputs` 前调用 `amplifyForMountedCore(processedRecipes.get(i))`。
- EU 计算始终继续使用未放大的 `recipe`。

- [ ] **Step 5: 运行定向测试与现有并行容量测试**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle.bat test --tests "com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialMyriadProliferationCoreTest" --tests "com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleParallelCapacitySourceTest" --no-daemon
```

Expected: 两个测试类全部 PASS；现有巨大流体并行回退不退化。

- [ ] **Step 6: 提交输出变换**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialRecipeOutputAmplifier.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialModuleRecipeLogic.java src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java
git commit -m "feat(primordial): 按晋升阶段放大挂载模块产出"
```

### Task 5: 注册机器和本地化文本

**Files:**
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java`
- Modify: `src/main/resources/assets/gt_shanhai/lang/zh_cn.json`
- Modify: `src/main/resources/assets/gt_shanhai/lang/en_us.json`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java`

- [ ] **Step 1: 写注册与 Lang 失败测试**

测试读取注册和 Lang JSON，断言：

```java
assertTrue(machines.contains("PRIMORDIAL_MYRIAD_PROLIFERATION_CORE"));
assertTrue(machines.contains("PrimordialMyriadRecipeTypes.requireTier2()"));
assertTrue(machines.contains("PrimordialMyriadRecipeTypes.requireTier1()"));
assertTrue(machines.contains("PrimordialMyriadProliferationCoreStructure::createPattern"));
assertEquals("原初万象衍生核心", zh.get("block.gt_shanhai.primordial_myriad_proliferation_core").getAsString());
```

- [ ] **Step 2: 运行测试确认失败**

Expected: FAIL，机器定义和 Lang 键不存在。

- [ ] **Step 3: 注册机器**

在 `DShanhaiMachines` 添加字段、imports 和定义：

```java
PRIMORDIAL_MYRIAD_PROLIFERATION_CORE = GTDishanhaiRegistration.REGISTRATE
        .multiblock("primordial_myriad_proliferation_core", PrimordialMyriadProliferationCore::new)
        .rotationState(RotationState.ALL)
        .recipeTypes(PrimordialMyriadRecipeTypes.requireTier2(), PrimordialMyriadRecipeTypes.requireTier1())
        .pattern(PrimordialMyriadProliferationCoreStructure::createPattern)
        .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing")))
        .workableCasingRenderer(
                new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
        .register();
```

Tooltip 明确写出：安装于引擎模块位、空闲 10x、二级 100x、一级 1000x、满概率、多个不叠加、配方 4 物品与 4 流体输入且无输出。

- [ ] **Step 4: 添加中英文 Lang**

至少增加：

```json
"block.gt_shanhai.primordial_myriad_proliferation_core": "原初万象衍生核心",
"gt_shanhai.machine.primordial_myriad_proliferation_core.name": "原初万象衍生核心",
"gtceu.recipe_type.primordial_myriad_ascension_tier_2": "原初万象衍生·二级晋升",
"gtceu.recipe_type.primordial_myriad_ascension_tier_1": "原初万象衍生·一级晋升"
```

英文对应 `Primordial Myriad Proliferation Core`、`Primordial Myriad Ascension Tier II`、`Primordial Myriad Ascension Tier I`。同时按现有兼容格式补齐 `gtceu.<id>`、`recipe_type.<id>` 键。

- [ ] **Step 5: 运行注册测试并提交**

Expected: 定向测试全部 PASS，两个 Lang JSON 可解析。

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java src/main/resources/assets/gt_shanhai/lang/zh_cn.json src/main/resources/assets/gt_shanhai/lang/en_us.json src/test/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/core/PrimordialMyriadProliferationCoreTest.java
git commit -m "feat(primordial): 注册原初万象衍生核心"
```

### Task 6: 完整验证和项目记录

**Files:**
- Modify: `C:/Users/dishanhai/Desktop/ide专属文件/.learnings/TODO.md`
- Modify after success: `C:/Users/dishanhai/Desktop/ide专属文件/.learnings/LEARNINGS.md`
- Modify on failure: `C:/Users/dishanhai/Desktop/ide专属文件/.learnings/ERRORS.md`

- [ ] **Step 1: 清理并运行定向测试**

```powershell
Remove-Item -LiteralPath ".\build" -Recurse -Force
.\gradle-install\gradle-8.8\bin\gradle.bat test --tests "com.dishanhai.gt_shanhai.common.machine.primordial.*" --no-daemon
```

Expected: 所有 primordial 测试 0 failures、0 errors。

- [ ] **Step 2: 运行完整构建**

```powershell
.\gradle-install\gradle-8.8\bin\gradle.bat build --rerun-tasks --no-daemon
```

Expected: `BUILD SUCCESSFUL`，全部测试 0 failures、0 errors。

- [ ] **Step 3: 检查 Jar 内容**

```powershell
jar tf build\libs\gt_shanhai.jar | Select-String "PrimordialMyriad|primordial_myriad"
Get-FileHash -Algorithm SHA256 build\libs\gt_shanhai.jar
```

Expected: 核心、逻辑、结构、接口、查表类和 Lang 均在 Jar；记录 SHA256。

- [ ] **Step 4: 启动期与实机验证**

启动九周目后检查 `latest.log` 无配方类型缺失、重复注册或 KubeJS startup 错误。依次验证：

1. 无核心时原模块 1x。
2. 核心空闲时 10x 且概率产物必出。
3. 二级配方实际推进时 100x，暂停后回到 10x。
4. 一级配方实际推进时 1000x，完成后回到 10x。
5. 多核心不同阶段只取最高值。
6. 直接 Tick 产电模块不受影响。

- [ ] **Step 5: 更新 Self-Improvement 与 TODO**

功能与完整构建成功后，在 `LEARNINGS.md` 记录“宿主按 tick 取最高功能模块倍率，输出变换在概率抽取前且容量预检使用独立副本”；将 `TODO-20260719` 的实现、测试、构建项标为完成。任何命令、构建或运行错误按唯一编号写入 `ERRORS.md`。

- [ ] **Step 6: 提交最终记录**

仅提交模组仓库内本功能尚未提交的文件；不得暂存当前工作树中 AE2/虚拟样板等并行改动。

```powershell
git status --short
git diff --cached --name-only
```

Expected: 暂存清单只包含本功能文件。

---

## 规格覆盖检查

- 阶梯倍率 1/10/100/1000：Task 2、Task 3。
- 多核心不叠加且取最高：Task 2。
- 两个 4 物品 + 4 流体输入、无输出类型：Task 1。
- 高倍率必须真实运行配方：Task 3。
- 物品、流体、持续输出满概率并放大：Task 4。
- 输入、EU、时长、并行不额外改变：Task 4。
- 放大后容量检查：Task 4。
- 标准原初模块结构且仅需要输入能力：Task 3。
- 中英文显示、tooltip、宿主状态：Task 2、Task 3、Task 5。
- 定向测试、全量构建、Jar 与实机验收：Task 6。
