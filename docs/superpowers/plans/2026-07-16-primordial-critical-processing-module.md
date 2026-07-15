# 原初临界加工模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增可挂载到原初终焉引擎、集中处理卫星工厂 MK-1 至 MK-4 全部 35 种基础加工配方的“原初临界加工模块”。

**Architecture:** 新机器直接继承 `PrimordialOmegaEngineModuleBase`，新逻辑直接继承 `PrimordialModuleRecipeLogic`，并复用 `PrimordialAssemblyLineModuleStructure`。机器注册负责声明 35 种配方类型，原初引擎结构白名单负责允许模块挂载，资源文件负责名称、Tooltip 和指南入口。

**Tech Stack:** Java 17、Forge 1.20.1、GTCEu、GTLCore、GTLAdditions、Gradle 8.8、Markdown GuideME 资源。

---

### Task 1: 建立失败基线与新增模块逻辑

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModuleLogic.java`

- [ ] **Step 1: 验证新模块尚不存在**

Run:

```powershell
$logic = 'src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModuleLogic.java'
$machine = 'src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModule.java'
if ((Test-Path -LiteralPath $logic) -or (Test-Path -LiteralPath $machine)) { throw '新模块文件已存在，需先检查冲突' }
```

Expected: 命令退出码为 `0`，两个目标文件均不存在。

- [ ] **Step 2: 创建直接继承原初配方逻辑的类**

```java
package com.dishanhai.gt_shanhai.common.machine.primordial.module.processing;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

public class PrimordialCriticalProcessingModuleLogic extends PrimordialModuleRecipeLogic {

    public PrimordialCriticalProcessingModuleLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super(machine);
    }
}
```

- [ ] **Step 3: 验证继承关系文本**

Run:

```powershell
rg -n "class PrimordialCriticalProcessingModuleLogic extends PrimordialModuleRecipeLogic" src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModuleLogic.java
```

Expected: 恰好输出一条匹配。

### Task 2: 实现原初模块机器本体

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModule.java`

- [ ] **Step 1: 创建独立模块机器类**

实现内容必须包含以下完整职责，不修改现有装配线模块：

```java
package com.dishanhai.gt_shanhai.common.machine.primordial.module.processing;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PrimordialCriticalProcessingModule extends PrimordialOmegaEngineModuleBase {

    private static final Map<Item, Long> ITEM_PARALLEL_MAP = new HashMap<>();
    private static final long DEFAULT_PARALLEL = 64L;

    private static Item ITEM_WZRM;
    private static Item ITEM_WZJC;
    private static Item ITEM_WZCZ1;
    private static Item ITEM_WZSB;
    private static Item ITEM_WZCZ2;
    private static Item ITEM_WZQS;
    private static Item ITEM_WZGL;
    private static Item ITEM_WZSW;
    private static Item ITEM_WZCX;
    private static Item ITEM_WZYH;
    private static Item ITEM_WZCZ3;
    private static Item ITEM_CREATE_MK;
    private static Item ITEM_WZAX;
    private static Item ITEM_WZXC;
    private static Item ITEM_WZHY;
    private static Item ITEM_WZDF;
    private static Item ITEM_REALITY_ANCHOR;

    private long currentParallel = DEFAULT_PARALLEL;
    private TickableSubscription parallelScanSubs;
    private final NotifiableItemStackHandler machineStorage;

    public PrimordialCriticalProcessingModule(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
        initItems();
        machineStorage = createMachineStorage();
    }

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[] { machineStorage, threadBoostSlot };
    }

    private NotifiableItemStackHandler createMachineStorage() {
        var handler = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        handler.setFilter(stack -> {
            if (stack == null || stack.isEmpty()) {
                return true;
            }
            return ITEM_PARALLEL_MAP.containsKey(stack.getItem());
        });
        return handler;
    }

    private static void initItems() {
        if (ITEM_WZRM != null) {
            return;
        }
        ITEM_WZRM = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzrm"));
        ITEM_WZJC = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzjc"));
        ITEM_WZCZ1 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz1"));
        ITEM_WZSB = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzsb"));
        ITEM_WZCZ2 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz2"));
        ITEM_WZQS = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzqs"));
        ITEM_WZGL = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzgl"));
        ITEM_WZSW = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzsw"));
        ITEM_WZCX = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcx"));
        ITEM_WZYH = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzyh"));
        ITEM_WZCZ3 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz3"));
        ITEM_CREATE_MK = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "create_mk"));
        ITEM_WZAX = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzax"));
        ITEM_WZXC = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzxc"));
        ITEM_WZHY = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzhy"));
        ITEM_WZDF = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzdf"));
        ITEM_REALITY_ANCHOR = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("dishanhai", "reality_anchor_module"));

        ITEM_PARALLEL_MAP.put(ITEM_WZRM, 128L);
        ITEM_PARALLEL_MAP.put(ITEM_WZJC, 256L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ1, 512L);
        ITEM_PARALLEL_MAP.put(ITEM_WZSB, 2048L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ2, 16384L);
        ITEM_PARALLEL_MAP.put(ITEM_WZQS, 65536L);
        ITEM_PARALLEL_MAP.put(ITEM_WZGL, 524288L);
        ITEM_PARALLEL_MAP.put(ITEM_WZSW, 2097152L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCX, 268435456L);
        ITEM_PARALLEL_MAP.put(ITEM_WZYH, 2147483647L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ3, 4611686018427387903L);
        ITEM_PARALLEL_MAP.put(ITEM_CREATE_MK, Long.MAX_VALUE);
        ITEM_PARALLEL_MAP.put(ITEM_REALITY_ANCHOR, 6917529027641081855L);
        ITEM_PARALLEL_MAP.put(ITEM_WZAX, 4096L);
        ITEM_PARALLEL_MAP.put(ITEM_WZXC, 1024L);
        ITEM_PARALLEL_MAP.put(ITEM_WZHY, 1048576L);
        ITEM_PARALLEL_MAP.put(ITEM_WZDF, 536870912L);
    }

    private void scanBoostItem() {
        var stack = machineStorage.storage.getStackInSlot(0);
        long base = ITEM_PARALLEL_MAP.getOrDefault(stack.getItem(), DEFAULT_PARALLEL);
        currentParallel = applyModuleCountParallelMultiplier(base, stack.getCount());
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        scanBoostItem();
        parallelScanSubs = subscribeServerTick(parallelScanSubs, () -> {
            if (getOffsetTimer() % 3 == 0) {
                scanBoostItem();
            }
        });
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (parallelScanSubs != null) {
            parallelScanSubs.unsubscribe();
            parallelScanSubs = null;
        }
    }

    @Override
    public PrimordialCriticalProcessingModuleLogic createRecipeLogic(Object... args) {
        return new PrimordialCriticalProcessingModuleLogic(this);
    }

    @Override
    public PrimordialCriticalProcessingModuleLogic getRecipeLogic() {
        return (PrimordialCriticalProcessingModuleLogic) recipeLogic;
    }

    @Override
    public int getMaxParallel() {
        return Ints.saturatedCast(currentParallel);
    }

    public long getCurrentParallel() {
        return currentParallel;
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getTier() {
        return 9;
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            var slot = new SlotWidget(machineStorage.storage, 0,
                    size.width - 30, size.height - 30, true, true);
            configureParallelModuleSlot(slot);
            group.addWidget(slot);
        }
        return widget;
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var stack = machineStorage.storage.getStackInSlot(0);
        var itemName = stack.isEmpty()
                ? Component.translatable("gt_shanhai.machine.module_slot.empty").withStyle(ChatFormatting.GRAY)
                : stack.getHoverName().copy().withStyle(ChatFormatting.AQUA);

        textList.add(Component.literal("")
                .append(DShanhaiTextUtil.createElectricText("已安装: "))
                .append(itemName));

        long parallel = getCurrentParallel();
        boolean isInfinite = parallel >= Long.MAX_VALUE / 2;
        var parallelText = isInfinite
                ? DShanhaiTextUtil.createUltimateRainbow("∞ 无限")
                : DShanhaiTextUtil.createAuroraText(String.format("%,d", parallel));

        textList.add(Component.literal("")
                .append(DShanhaiTextUtil.createElectricText("并行上限: "))
                .append(parallelText));
        addThreadBoostDisplay(textList);
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            if (!canModuleWork()) {
                textList.add(Component.translatable(
                        "gt_shanhai.machine.primordial_critical_processing_module.name")
                        .withStyle(ChatFormatting.GOLD));
                return;
            }
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            textList.add(Component.translatable(
                    "gt_shanhai.machine.primordial_critical_processing_module.mode")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable(
                "gt_shanhai.machine.primordial_critical_processing_module.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
```

- [ ] **Step 2: 编译新增 Java 类**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle.bat compileJava --no-daemon
```

Expected: `BUILD SUCCESSFUL`。此时机器尚未注册，但两个新类必须可编译。

### Task 3: 注册 35 种配方类型并允许引擎挂载

**Files:**
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineStructure.java`

- [ ] **Step 1: 接入 processing 包和静态机器字段**

在 `DShanhaiMachines.java` 的原初模块导入区加入：

```java
import com.dishanhai.gt_shanhai.common.machine.primordial.module.processing.*;
```

在 `PRIMORDIAL_ASSEMBLY_LINE_MODULE` 与 `PRIMORDIAL_ENGRAVING_MODULE` 附近加入：

```java
public static MultiblockMachineDefinition PRIMORDIAL_CRITICAL_PROCESSING_MODULE;
```

- [ ] **Step 2: 注册新机器和全部 35 种类型**

在原初装配线模块注册之后加入：

```java
PRIMORDIAL_CRITICAL_PROCESSING_MODULE = GTDishanhaiRegistration.REGISTRATE
        .multiblock("primordial_critical_processing_module",
                PrimordialCriticalProcessingModule::new)
        .rotationState(RotationState.ALL)
        .recipeTypes(
                GTRecipeTypes.LATHE_RECIPES,
                GTRecipeTypes.BENDER_RECIPES,
                GTRecipeTypes.COMPRESSOR_RECIPES,
                GTRecipeTypes.FORGE_HAMMER_RECIPES,
                GTRecipeTypes.CUTTER_RECIPES,
                GTRecipeTypes.EXTRUDER_RECIPES,
                GTRecipeTypes.MIXER_RECIPES,
                GTRecipeTypes.WIREMILL_RECIPES,
                GTRecipeTypes.FORMING_PRESS_RECIPES,
                GTRecipeTypes.POLARIZER_RECIPES,
                GTRecipeTypes.ROCK_BREAKER_RECIPES,
                GTRecipeTypes.ORE_WASHER_RECIPES,
                GTRecipeTypes.CENTRIFUGE_RECIPES,
                GTRecipeTypes.ELECTROLYZER_RECIPES,
                GTRecipeTypes.SIFTER_RECIPES,
                GTRecipeTypes.MACERATOR_RECIPES,
                GTLRecipeTypes.DEHYDRATOR_RECIPES,
                GTRecipeTypes.THERMAL_CENTRIFUGE_RECIPES,
                GTRecipeTypes.ELECTROMAGNETIC_SEPARATOR_RECIPES,
                GTRecipeTypes.EVAPORATION_RECIPES,
                GTRecipeTypes.AUTOCLAVE_RECIPES,
                GTRecipeTypes.EXTRACTOR_RECIPES,
                GTRecipeTypes.BREWING_RECIPES,
                GTRecipeTypes.FERMENTING_RECIPES,
                GTRecipeTypes.DISTILLERY_RECIPES,
                GTRecipeTypes.DISTILLATION_RECIPES,
                GTRecipeTypes.FLUID_HEATER_RECIPES,
                GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES,
                GTRecipeTypes.CHEMICAL_BATH_RECIPES,
                GTRecipeTypes.CANNER_RECIPES,
                GTRecipeTypes.ARC_FURNACE_RECIPES,
                GTLRecipeTypes.LIGHTNING_PROCESSOR_RECIPES,
                GTRecipeTypes.ASSEMBLER_RECIPES,
                GTLRecipeTypes.PRECISION_ASSEMBLER_RECIPES,
                GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES)
        .pattern(PrimordialAssemblyLineModuleStructure::createPattern)
        .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing")))
        .workableCasingRenderer(
                new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
        .register();

PRIMORDIAL_CRITICAL_PROCESSING_MODULE.setTooltipBuilder((stack, tooltips) -> {
    tooltips.add(DShanhaiTextUtil.createFireText("临界整合——将四级卫星工厂的基础加工能力压缩为一台原初模块"));
    tooltips.add(Component.literal("§7MK-1：车床 / 卷板 / 压缩 / 锻造锤 / 切割 / 压模 / 搅拌 / 线材轧制 / 冲压 / 极化"));
    tooltips.add(Component.literal("§7MK-2：碎岩 / 洗矿 / 离心 / 电解 / 筛选 / 研磨 / 脱水 / 热力离心 / 电磁选矿"));
    tooltips.add(Component.literal("§7MK-3：蒸发 / 高压釜 / 提取 / 酿造 / 发酵 / 蒸馏室 / 蒸馏塔 / 流体加热 / 流体固化 / 化学浸洗"));
    tooltips.add(Component.literal("§7MK-4：装罐 / 电弧炉 / 闪电处理 / 组装 / 精密组装 / 电路组装"));
    tooltips.add(Component.literal("§7需安装在引擎模块位"));
    tooltips.add(Component.literal("")
            .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级并行提供"))
            .append(Component.literal("§f，直接从电网取电")));
});
```

- [ ] **Step 3: 加入原初引擎模块位白名单**

在 `PrimordialOmegaEngineStructure.createPattern()` 中读取新方块：

```java
Block criticalProcessingModule = ForgeRegistries.BLOCKS.getValue(
        new ResourceLocation("gt_shanhai", "primordial_critical_processing_module"));
```

在 `J` 位候选链中加入：

```java
.or(Predicates.blocks(criticalProcessingModule))
```

- [ ] **Step 4: 静态核对配方数量和结构接入**

Run:

```powershell
$text = Get-Content -Raw 'src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java'
$block = [regex]::Match($text, '(?s)\.multiblock\("primordial_critical_processing_module".*?\.pattern\(').Value
$types = [regex]::Matches($block, '(?:GTRecipeTypes|GTLRecipeTypes)\.[A-Z_]+') | ForEach-Object Value
if ($types.Count -ne 35) { throw "配方类型数量不是 35：$($types.Count)" }
if (($types | Sort-Object -Unique).Count -ne 35) { throw '配方类型存在重复' }
rg -n "criticalProcessingModule|primordial_critical_processing_module" src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineStructure.java
```

Expected: 35 个互不重复的类型；引擎结构输出方块读取和候选链两处匹配。

### Task 4: 添加语言与指南资源

**Files:**
- Modify: `src/main/resources/assets/gt_shanhai/lang/zh_cn.json`
- Modify: `src/main/resources/assets/gt_shanhai/lang/en_us.json`
- Create: `src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_critical_processing_module.md`
- Modify: `src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_index.md`

- [ ] **Step 1: 添加中英文语言键**

中文：

```json
"block.gt_shanhai.primordial_critical_processing_module": "原初临界加工模块",
"gt_shanhai.machine.primordial_critical_processing_module.name": "原初临界加工模块",
"gt_shanhai.machine.primordial_critical_processing_module.mode": "§a临界加工中",
"module.primordial_critical_processing_module.name": "原初临界加工模块"
```

英文：

```json
"block.gt_shanhai.primordial_critical_processing_module": "Primordial Critical Processing Module",
"gt_shanhai.machine.primordial_critical_processing_module.name": "Primordial Critical Processing Module",
"gt_shanhai.machine.primordial_critical_processing_module.mode": "§aCritical Processing",
"module.primordial_critical_processing_module.name": "Primordial Critical Processing Module"
```

- [ ] **Step 2: 创建独立指南页面**

```markdown
---
navigation:
  title: 原初临界加工模块
  parent: machine/primordial_index.md
  position: 11
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_critical_processing_module
---

# 原初临界加工模块

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_critical_processing_module" />\
> 把枢纽卫星工厂 MK-1 至 MK-4 的基础加工能力集中到一个原初引擎模块中。\
> 一台机器覆盖成型、矿物处理、流体化工和装配加工，共计 35 种配方类型。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* MK-1：车床、卷板、压缩、锻造锤、切割、压模、搅拌、线材轧制、冲压、极化。
* MK-2：碎岩、洗矿、离心、电解、筛选、研磨、脱水、热力离心、电磁选矿。
* MK-3：蒸发、高压釜、提取、酿造、发酵、蒸馏室、蒸馏塔、流体加热、流体固化、化学浸洗。
* MK-4：装罐、电弧炉、闪电处理、组装、精密组装、电路组装。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 空槽默认并行是 `64`。\
> 放入山海物质模块后，基础并行按模块等级提升，并继续叠加同类模块数量倍率。\
> 线程倍率、无线供电和额外挂载效果沿用原初引擎模块体系。

</Column>

<Column gap="2" fullWidth={true}>

### 使用规则

* 必须安装到原初终焉引擎的模块位，或在配置允许时独立运行。
* 通过机器界面的配方类型选择器控制当前允许处理的工序。
* 本模块不继承卫星工厂的亚空间轨道条件、固定线程倍率或激光仓能力。

</Column>
```

- [ ] **Step 3: 更新原初索引两组入口**

在 `item_ids`、`专用产线` 和 `产线模块` 三处分别加入：

```markdown
- gt_shanhai:primordial_critical_processing_module
```

```markdown
* [<ItemLink id="gt_shanhai:primordial_critical_processing_module" />](primordial_critical_processing_module.md)：整合卫星工厂 MK-1 至 MK-4 的 35 种基础加工配方。
```

- [ ] **Step 4: 验证 JSON 与指南链接**

Run:

```powershell
Get-Content -Raw 'src/main/resources/assets/gt_shanhai/lang/zh_cn.json' | ConvertFrom-Json | Out-Null
Get-Content -Raw 'src/main/resources/assets/gt_shanhai/lang/en_us.json' | ConvertFrom-Json | Out-Null
$guide = 'src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_critical_processing_module.md'
if (-not (Test-Path -LiteralPath $guide)) { throw '指南文件缺失' }
rg -n "primordial_critical_processing_module" src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_index.md $guide
```

Expected: 两个 JSON 均成功解析；索引和独立页面均输出匹配。

### Task 5: 完整构建、差异审计与经验记录

**Files:**
- Modify: `C:/Users/dishanhai/Desktop/ide专属文件/.learnings/ERRORS.md`
- Modify: `C:/Users/dishanhai/Desktop/ide专属文件/.learnings/LEARNINGS.md`

- [ ] **Step 1: 安全清理构建目录**

Run:

```powershell
$root = (Resolve-Path '.').Path
$build = Join-Path $root 'build'
if (Test-Path -LiteralPath $build) {
    $resolvedBuild = (Resolve-Path -LiteralPath $build).Path
    if ($resolvedBuild -ne $build -or -not $resolvedBuild.StartsWith($root + [IO.Path]::DirectorySeparatorChar)) {
        throw "拒绝删除非项目 build 路径：$resolvedBuild"
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}
```

Expected: 仅删除 `C:/Users/dishanhai/Desktop/gt_shanhai/build`。

- [ ] **Step 2: 执行完整构建**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle.bat build --no-daemon
```

Expected: `BUILD SUCCESSFUL`，退出码为 `0`。

- [ ] **Step 3: 审计改动边界**

Run:

```powershell
git status --short
git diff -- src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineStructure.java src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing src/main/resources/assets/gt_shanhai/lang/zh_cn.json src/main/resources/assets/gt_shanhai/lang/en_us.json src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_index.md src/main/resources/assets/gt_shanhai/guides/gt_shanhai/guide/machine/primordial_critical_processing_module.md
```

Expected: 本任务差异只涉及计划列出的原初模块、注册、语言和指南文件；用户已有商店系统改动保持原样。

- [ ] **Step 4: 记录命令失败和实现经验**

在 `.learnings/ERRORS.md` 记录最初误用不存在的 `Desktop/ide专属文件/gt_shanhai` 路径，以及通过项目索引确认真实根目录为 `Desktop/gt_shanhai` 的纠正方式。

在 `.learnings/LEARNINGS.md` 记录：原初多配方模块应直接使用 `PrimordialOmegaEngineModuleBase` + `PrimordialModuleRecipeLogic`，机器定义集中声明配方类型，并同步加入原初引擎 `J` 位白名单。

- [ ] **Step 5: 最终需求核对**

Run:

```powershell
$machineFile = 'src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModule.java'
$logicFile = 'src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/module/processing/PrimordialCriticalProcessingModuleLogic.java'
rg -n "extends PrimordialOmegaEngineModuleBase" $machineFile
rg -n "extends PrimordialModuleRecipeLogic" $logicFile
rg -n "PrimordialAssemblyLineModuleStructure::createPattern" src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java
```

Expected: 机器、逻辑和结构复用三项要求全部命中。
