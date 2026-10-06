package com.shanhai.machine.module;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.common.data.GCyMRecipeTypes;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.shanhai.common.compat.GtlAddCompat;
import com.shanhai.common.heat.ShanhaiHeatGate;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.common.recipe.ShanhaiRecipeTypes;
import com.shanhai.common.thread.ShanhaiParallelBudget;
import com.shanhai.ShanhaiMod;
import com.shanhai.machine.MachineTooltips;
import com.shanhai.registry.ShanhaiRegistration;

import org.gtlcore.gtlcore.common.data.GTLRecipeTypes;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import org.gtlcore.gtlcore.api.recipe.RecipeMultiplierTracker;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 原初模块的注册入口 + 全模块方块句柄表。
 *
 * <h2>初始化链（冻结，由 {@code ShanhaiRegistry.init()} 第 ③ 段调用）</h2>
 * <pre>
 * ShanhaiMod（@Mod 构造器）
 *   └─ ShanhaiRegistration.register(bus)   // 全工程唯一的 Registrate
 *   └─ ShanhaiRegistry.init()
 *        ├─ ① ShanhaiItems.init()
 *        ├─ ② ShanhaiMachines.init()      // 主机（其 J 谓词按 id 字符串解析本模块方块，是惰性的）
 *        └─ ③ ModuleRegistry.init()       // ← 本类
 * </pre>
 * 本类<b>不</b>自己 {@code GTRegistrate.create(...)}：{@link ShanhaiRegistration#REGISTRATE} 的类注释已说明，
 * 第二个 GTRegistrate 实例的事件监听器永远挂不上，它注册的东西一个都不会进注册表<b>且不抛异常</b>
 * —— 那是最典型的「编译通过、游戏里静默失效」。
 *
 * <h2>可扩展性（按「未来 24 个模块都会挂上来」设计）</h2>
 * 新增一个模块 = ① 写一个 {@link PrimordialModuleMachine} 子类；② 写它的结构类；
 * ③ 在 {@link #SPECS} 常量表里加<b>一行</b>；④ 在句柄区加一个 {@code public static} 字段并
 * 在 {@link #init()} 里 require 一次。注册循环、fail-fast、句柄表都不用动 —— 24 个模块共用同一套。
 *
 * <h2>🔴 fail-fast（{@link #init()} 末尾）</h2>
 * 句柄为 null 绝不能流进主机的 {@code pattern()}：主机装配 {@code J} 谓词时若拿到 null，
 * 结果要么是 NPE 出现在离病根极远的地方，要么是「谓词恒 false ⇒ 结构永不成型且日志无异常」。
 * 因此这里在加载期就用<b>带 id 的异常</b>炸掉。
 */
public final class ModuleRegistry {

    // ═════════════════════════════ 常量表（id 字符串只此一处，改名 = 改一行） ═════════════════════════════

    /** 本阶段唯一模块的注册 id（规格 §5.2 冻结）。命名空间是 {@code shanhai}，不是 {@code dishanhai}/{@code gt_shanhai}。 */
    public static final String ID_PRIMORDIAL_MATTER_RECOMBINATOR_CORE =
            "shanhai:primordial_matter_recombinator_core";

    /** 模块机器的电压等级（阶段 1 不要求数值平衡；上游同档位给的是高等级 + 高电压）。 */
    public static final int MODULE_MACHINE_TIER = 9;

    /**
     * 本阶段模块挂的 <b>GTCEu 原版</b>配方类型（规格 §6.2 方案 A）。
     *
     * <p>为什么不挂 {@code gtceu:primordial_matter_recombination}：那 103 条老配方全部由 KubeJS +
     * {@code module_level} 自定义条件写成，本阶段不做 KubeJS ⇒ 注册了类型也<b>没有任何配方可跑</b>，
     * 验收 A5（模块能跑配方）直接判不了。原版类型的配方由宿主自带，零数据成本。
     */
    public static final GTRecipeType[] RECIPE_TYPES_MATTER_RECOMBINATOR_CORE = {
            GTRecipeTypes.MACERATOR_RECIPES,     // 研磨（矿石→粉），输入 1 格即可演示
            GTRecipeTypes.FORGE_HAMMER_RECIPES,  // 锻造锤（锭→板）
    };

    // ───────── 2026-09 新增 5 个模块的配方类型 ─────────
    // 全部是 **GTCEu 原版** 常量（已用 javap 逐个核对 24 个名字在 gtceu-1.20.1-1.4.4 里都存在），
    // 宿主自带配方 ⇒ 每个新模块都能"放进去就跑一条配方"，验收不用碰 KubeJS。

    /** 原初临界加工模块：上游整合卫星工厂的基础加工线（这里取 8 种原版加工类型）。 */
    public static final GTRecipeType[] RECIPE_TYPES_CRITICAL_PROCESSING = {
            GTRecipeTypes.LATHE_RECIPES,
            GTRecipeTypes.BENDER_RECIPES,
            GTRecipeTypes.COMPRESSOR_RECIPES,
            GTRecipeTypes.EXTRUDER_RECIPES,
            GTRecipeTypes.WIREMILL_RECIPES,
            GTRecipeTypes.FORMING_PRESS_RECIPES,
            GTRecipeTypes.CUTTER_RECIPES,
            GTRecipeTypes.MIXER_RECIPES,
    };

    /** 原初宇宙反应炉：高温/辐射类原版类型。 */
    public static final GTRecipeType[] RECIPE_TYPES_COSMIC_REACTOR = {
            GTRecipeTypes.ARC_FURNACE_RECIPES,
            GTRecipeTypes.AUTOCLAVE_RECIPES,
            GTRecipeTypes.POLARIZER_RECIPES,
            GTRecipeTypes.LASER_ENGRAVER_RECIPES,
    };

    /** 原初分子裂隙核心：化学/分离类原版类型。 */
    public static final GTRecipeType[] RECIPE_TYPES_MOLECULAR_RIFT = {
            GTRecipeTypes.CHEMICAL_BATH_RECIPES,
            GTRecipeTypes.MIXER_RECIPES,
            GTRecipeTypes.CENTRIFUGE_RECIPES,
    };

    /** 原初超临界物质生成核心：物质分离/提纯类原版类型。 */
    public static final GTRecipeType[] RECIPE_TYPES_SUPERCRITICAL_MATTER = {
            GTRecipeTypes.CENTRIFUGE_RECIPES,
            GTRecipeTypes.ELECTROLYZER_RECIPES,
            GTRecipeTypes.SIFTER_RECIPES,
            GTRecipeTypes.ORE_WASHER_RECIPES,
    };

    /** 原初永恒熔炼炉：熔炼/合金类原版类型。 */
    public static final GTRecipeType[] RECIPE_TYPES_ETERNAL_SMELTING = {
            GTRecipeTypes.BLAST_RECIPES,
            GTRecipeTypes.ALLOY_SMELTER_RECIPES,
            GTRecipeTypes.MACERATOR_RECIPES,
    };

    // ───────── 2026-09 新增 18 个模块的配方类型（按主题分组的 11 个**共享**数组） ─────────
    // 🔴 全部是 **GTCEu 原版** 常量：这 40+ 个名字逐个用
    //    `javap -cp libs/gtceu-1.20.1-1.4.4.jar com.gregtechceu.gtceu.common.data.GTRecipeTypes`
    //    实读过（原始输出见交付报告 §4）。
    // 分组口径（对每台机器都是"读上游、不发明"）：
    //   ① 上游 zh_cn.json 里的机器中文名；
    //   ② 上游 guides/.../machine/primordial_index.md 里同一台机器的一句话简介；
    //   ③ DShanhaiMachines.java 里同一台机器 .recipeTypes(...) 实际挂的类型（能直接对上的就直接照抄，
    //      例：taixu_smelting_furnace 上游 L1736-1738 就是 FURNACE/ALLOY_SMELTER/BLAST）。
    // 18 台机器共用这 11 个数组，**不逐台各写一份**；上游那批 primordial_* 私有配方类型依赖 KubeJS，
    // 本阶段不挂（挂了就是"有类型没配方"，验收时机器放进去跑不动）。

    /** 能量/真空抽取组：原始真空零点能发生器、原初分歧发生器（真空涨落取能 / 震荡收集与能量吸取）。 */
    public static final GTRecipeType[] RECIPE_TYPES_ENERGY_EXTRACTION = {
            GTRecipeTypes.VACUUM_RECIPES,
            GTRecipeTypes.GAS_COLLECTOR_RECIPES,
            GTRecipeTypes.AIR_SCRUBBER_RECIPES,
            GTRecipeTypes.ARC_FURNACE_RECIPES,
    };

    /** 生物/生命组：原初生物核心（生命模拟 / 温室 / 培养缸 / 浮游选矿）。 */
    public static final GTRecipeType[] RECIPE_TYPES_BIO = {
            GTRecipeTypes.BREWING_RECIPES,
            GTRecipeTypes.FERMENTING_RECIPES,
            GTRecipeTypes.EXTRACTOR_RECIPES,
            GTRecipeTypes.CHEMICAL_BATH_RECIPES,
            GTRecipeTypes.SIFTER_RECIPES,
    };

    /** 选矿/采矿组：原初混沌蜉蝣解构结晶炉（选矿 / 研磨 / 采矿 / 洗矿）。 */
    public static final GTRecipeType[] RECIPE_TYPES_ORE_PROCESSING = {
            GTRecipeTypes.ROCK_BREAKER_RECIPES,
            GTRecipeTypes.MACERATOR_RECIPES,
            GTRecipeTypes.ORE_WASHER_RECIPES,
            GTRecipeTypes.SIFTER_RECIPES,
            GTRecipeTypes.THERMAL_CENTRIFUGE_RECIPES,
            GTRecipeTypes.ELECTROMAGNETIC_SEPARATOR_RECIPES,
    };

    /** 采集/探测组：原初世界碎片采集器、原初世线穿刺矩阵（碎片收集 / 宇宙探测 / 基岩采掘）。 */
    public static final GTRecipeType[] RECIPE_TYPES_COLLECTION = {
            GTRecipeTypes.ROCK_BREAKER_RECIPES,
            GTRecipeTypes.SCANNER_RECIPES,
            GTRecipeTypes.GAS_COLLECTOR_RECIPES,
            GTRecipeTypes.AIR_SCRUBBER_RECIPES,
    };

    /** 组装组：原初因果编织矩阵、原初装配线模块、原初天琼组装核心（生产链衔接 / 装配线 / 太空组装）。 */
    public static final GTRecipeType[] RECIPE_TYPES_ASSEMBLY = {
            GTRecipeTypes.ASSEMBLER_RECIPES,
            GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES,
            GTRecipeTypes.ASSEMBLY_LINE_RECIPES,
            GTRecipeTypes.PACKER_RECIPES,
            GTRecipeTypes.CANNER_RECIPES,
    };

    /** 高能组：原初奇点反演核心、原初多维聚爆核心（奇点反演 / 电力聚爆）。 */
    public static final GTRecipeType[] RECIPE_TYPES_HIGH_ENERGY = {
            GTRecipeTypes.IMPLOSION_RECIPES,
            GTRecipeTypes.FUSION_RECIPES,
            GTRecipeTypes.ARC_FURNACE_RECIPES,
    };

    /** 蒸馏/真空组：原初反熵冷凝核心（反熵冷凝 / 真空冷冻 / 等离子冷凝）。 */
    public static final GTRecipeType[] RECIPE_TYPES_DISTILLATION_VACUUM = {
            GTRecipeTypes.DISTILLERY_RECIPES,
            GTRecipeTypes.DISTILLATION_RECIPES,
            GTRecipeTypes.VACUUM_RECIPES,
            GTRecipeTypes.EVAPORATION_RECIPES,
            GTRecipeTypes.FLUID_HEATER_RECIPES,
            // ⚠️ 常量名是 GTCEu 自己的拼写 FLUID_SOLIDFICATION（少一个 I），jar 里**没有**
            //    FLUID_SOLIDIFICATION_RECIPES；上游 DShanhaiMachines.java:764/1638 用的也是这个拼写。
            //    （本次实测：写成 -TION 时 compileJava 直接报"找不到符号 变量 FLUID_SOLIDIFICATION_RECIPES"。）
            GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES,
    };

    /** 铸造/锻造组：原初物质铸造机（物质分离 / 铸造 / 凝结 / 锻造）。 */
    public static final GTRecipeType[] RECIPE_TYPES_CASTING = {
            GTRecipeTypes.FURNACE_RECIPES,
            GTRecipeTypes.ALLOY_SMELTER_RECIPES,
            // ⚠️ 同上的 GTCEu 特有拼写 FLUID_SOLIDFICATION（不是 FLUID_SOLIDIFICATION）。
            GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES,
            GTRecipeTypes.FORMING_PRESS_RECIPES,
            GTRecipeTypes.FORGE_HAMMER_RECIPES,
    };

    /** 蚀刻/精密加工组：原初量子扭曲矩阵、原初世线蚀刻核心（纳米制造 / 精密加工 / 电路蚀刻）。 */
    public static final GTRecipeType[] RECIPE_TYPES_ENGRAVING = {
            GTRecipeTypes.LASER_ENGRAVER_RECIPES,
            GTRecipeTypes.CUTTER_RECIPES,
            GTRecipeTypes.POLARIZER_RECIPES,
            GTRecipeTypes.AUTOCLAVE_RECIPES,
            GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES,
    };

    /** 聚合/化学组：原初韶光聚合核心、原初未央重构模块（聚合 / 衍射合成 / 物质异化）。 */
    public static final GTRecipeType[] RECIPE_TYPES_AGGREGATION = {
            GTRecipeTypes.CHEMICAL_RECIPES,
            GTRecipeTypes.LARGE_CHEMICAL_RECIPES,
            GTRecipeTypes.MIXER_RECIPES,
            GTRecipeTypes.CENTRIFUGE_RECIPES,
            GTRecipeTypes.ELECTROLYZER_RECIPES,
    };

    /** 熔炼/合金组：原初太虚宇宙锻炉（上游同一台机器挂的正是电炉 + 合金炉 + 电力高炉）。 */
    public static final GTRecipeType[] RECIPE_TYPES_SMELTING = {
            GTRecipeTypes.FURNACE_RECIPES,
            GTRecipeTypes.ALLOY_SMELTER_RECIPES,
            GTRecipeTypes.BLAST_RECIPES,
            GTRecipeTypes.PRIMITIVE_BLAST_FURNACE_RECIPES,
    };

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // 🔴 批二（2026-09-22）：下面 24 个是【逐台独立】的数组，照旧私货 DShanhaiMachines.java 里
    //    同一台机器（id 逐字相同）的 .recipeTypes(...) 原文逐条抄写。
    //
    //    为什么必须逐台各写一份：上面那 11 个数组里，有 6 个被 2~3 台机器**共用**
    //    （ENERGY_EXTRACTION / ASSEMBLY / HIGH_ENERGY / COLLECTION / AGGREGATION / DISTILLATION_VACUUM），
    //    而原版这 12 台的列表**彼此完全不同** ⇒ 原地改数组会一次改坏 2~3 台机器
    //    （这正是本项目"整段替换丢定义"那类事故的形态）。上面那些数组**一律作废、原文保留**，
    //    下面一张表是唯一被 SPECS 引用的来源。
    //
    //    上游出处（文件均为 …/common/machine/DShanhaiMachines.java）：
    //      ① DShanhaiRecipeTypes.X        → 本工程 ShanhaiRecipeTypes.X（同名同值）
    //      ② GTLRecipeTypes.X             → 直接引用（gtlcore 的 api 侧，且已逐个 javap 验在）
    //      ③ GTLAddRecipesTypes.X / .INSTANCE.getX() → 🔴 一律经 GtlAddCompat 转发
    //         （那是 gtladditions 的 `common.recipe` 非 API 包，本工程有明写的隔离墙）
    //      ④ GCyMRecipeTypes.ALLOY_BLAST_RECIPES（在 gtceu jar 内）
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** 原初物质重组核心 —— 上游 :513。 */
    public static final GTRecipeType[] RECIPE_MATTER_RECOMBINATOR_CORE = {
            ShanhaiRecipeTypes.PRIMORDIAL_MATTER_RECOMBINATION,
    };

    /** 原初临界加工模块 —— 上游 :736（36 条，全工程最长的一张）。 */
    public static final GTRecipeType[] RECIPE_CRITICAL_PROCESSING = {
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
            // ⚠️ GTCEu 拼写是 FLUID_SOLIDFICATION（少一个 I），jar 里没有 -TION 版本
            GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES,
            GTRecipeTypes.CHEMICAL_BATH_RECIPES,
            GTRecipeTypes.CANNER_RECIPES,
            GTRecipeTypes.ARC_FURNACE_RECIPES,
            GTLRecipeTypes.LIGHTNING_PROCESSOR_RECIPES,
            GTRecipeTypes.ASSEMBLER_RECIPES,
            GTLRecipeTypes.PRECISION_ASSEMBLER_RECIPES,
            GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES,
            GTRecipeTypes.PACKER_RECIPES,
            // 🆕 2026-10-01（用户点单）：原初物质定型 = 压模器 + 流体固化器全部配方，模头/模具 → 编程电路。
            //    它挂在这一台上是用户点名的（"为机器原初临界加工模块添加全新的配方种类"）；
            //    本来这台就已经挂着压模器与流体固化器两个原类型（见上面两行），所以是"同台再加一个改写版"。
            ShanhaiRecipeTypes.PRIMORDIAL_MATTER_FORMING,
    };

    /** 原初宇宙反应炉 —— 上游 :845。 */
    public static final GTRecipeType[] RECIPE_COSMIC_REACTOR = {
            GTLRecipeTypes.SUPER_PARTICLE_COLLIDER_RECIPES,
            GTRecipeTypes.FUSION_RECIPES,
            ShanhaiRecipeTypes.PRIMORDIAL_STELLAR_REACTION,
    };

    /** 原初分子裂隙核心 —— 上游 :864。 */
    public static final GTRecipeType[] RECIPE_MOLECULAR_RIFT = {
            GTLRecipeTypes.DISTORT_RECIPES,
            GTRecipeTypes.LARGE_CHEMICAL_RECIPES,
            GTRecipeTypes.CHEMICAL_BATH_RECIPES,
    };

    /** 原初超临界物质生成核心 —— 上游 :826。 */
    public static final GTRecipeType[] RECIPE_SUPERCRITICAL_MATTER = {
            GTLRecipeTypes.SPS_CRAFTING_RECIPES,
            GTLRecipeTypes.MATTER_FABRICATOR_RECIPES,
            GTLRecipeTypes.MASS_FABRICATOR_RECIPES,
    };

    /** 原初永恒熔炼炉 —— 上游 :902（3×GTL + 3×GTLAdd，GTLAdd 全部经 GtlAddCompat）。 */
    public static final GTRecipeType[] RECIPE_ETERNAL_SMELTING = {
            GTLRecipeTypes.DIMENSIONALLY_TRANSCENDENT_PLASMA_FORGE_RECIPES,
            GTLRecipeTypes.STELLAR_FORGE_RECIPES,
            GTLRecipeTypes.ULTIMATE_MATERIAL_FORGE_RECIPES,
            GtlAddCompat.molecularDeconstruction(),
            GtlAddCompat.chaoticAlchemy(),
            GtlAddCompat.stellarIgnition(),
    };

    /** 原始真空零点能发生器 —— 上游 :277（全 24 台里最短的一张，只有 1 条）。 */
    public static final GTRecipeType[] RECIPE_VOID_INDUCTION_ARMATURE = {
            ShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR,
    };

    /** 原初生物核心 —— 上游 :321。 */
    public static final GTRecipeType[] RECIPE_BIO = {
            ShanhaiRecipeTypes.PRIMORDIAL_BIOLOGICAL_CORE,
            GtlAddCompat.biologicalSimulation(),
            GTLRecipeTypes.GREENHOUSE_RECIPES,
            GTLRecipeTypes.INCUBATOR_RECIPES,
            GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES,
            // 🔴 2026-10-03 用户点单（逐字）：「然后给原初生物核心添加渔场的配方」。
            //    ⇒ 【只在末尾追加这一条】，原有 5 条一个字节都没动、下标不变
            //      （下标 = GUI 里 activeRecipeType 的口径，位移会让玩家已保存的机器换配方类型）。
            //    id = gtceu:fishing_ground（`javap -c org.gtlcore.gtlcore.common.data.GTLRecipeTypes`
            //      的 <clinit>：偏移 2210 ldc_w #921 // String fishing_ground →
            //      2213 ldc_w #498 // String multiblock → 2220 invokestatic GTRecipeTypes.register
            //      → 2252 putstatic #923 // Field FISHING_GROUND_RECIPES ⇒ 字段与 id 一一对应）。
            //    中文名「渔场」出处：gtlcore jar 的 assets/gtceu/lang/zh_cn.json 的 "gtceu.fishing_ground"。
            GTLRecipeTypes.FISHING_GROUND_RECIPES,
    };

    /** 原初混沌蜉蝣解构结晶炉 —— 上游 :351。 */
    public static final GTRecipeType[] RECIPE_ORE_PROCESSING = {
            GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES,
            GTLRecipeTypes.ISA_MILL_RECIPES,
            GtlAddCompat.spaceOreProcessor(),
            GTLRecipeTypes.INTEGRATED_ORE_PROCESSOR,
            GTLRecipeTypes.LARGE_VOID_MINER_RECIPES,
            GTLRecipeTypes.RANDOM_ORE_RECIPES,
            GTRecipeTypes.ORE_WASHER_RECIPES,
            GTLRecipeTypes.MINER_MODULE_RECIPES,
            // 🔴 2026-09-26（用户点单「给原初混沌蜉蝣解构结晶炉添加一个新的配方种类，名字叫原初物质解构」）：
            //    新增类型【追加在末尾】，原有 8 条一条未动、下标不变（activeRecipeType 口径不位移）
            //    —— 与上方 RECIPE_DIVERGENCE_GENERATOR 追加 PHOTON_SIPHON 那次同款做法。
            //    类型 id 经【已部署 jar 的字节码】实证为 "primordial_matter_deconstruction"
            //    （ShanhaiRecipeTypes.init() 的 register 首参）；中文名 = lang 键
            //    gtceu.primordial_matter_deconstruction（"原初物质解构"）。
            //    该类型专属模板 assets/gtceu/ui/recipe_type/primordial_matter_deconstruction.rtui
            //    画的是 1 物品入 / 20 物品出 / 1 流体入 / 10 流体出。
            ShanhaiRecipeTypes.PRIMORDIAL_MATTER_DECONSTRUCTION,
    };

    /** 原初因果编织矩阵 —— 上游 :539。 */
    public static final GTRecipeType[] RECIPE_CAUSAL_WEAVING_MATRIX = {
            ShanhaiRecipeTypes.PRIMORDIAL_CAUSAL_WEAVING,
    };

    /** 原初奇点反演核心 —— 上游 :565。 */
    public static final GTRecipeType[] RECIPE_SINGULARITY_INVERSION = {
            ShanhaiRecipeTypes.PRIMORDIAL_SINGULARITY_INVERSION,
    };

    /** 原初世界碎片采集器 —— 上游 :591。 */
    public static final GTRecipeType[] RECIPE_WORLD_FRAGMENTS_COLLECTOR = {
            GTLRecipeTypes.FRAGMENT_WORLD_COLLECTION,
    };

    /** 原初反熵冷凝核心 —— 上游 :619。 */
    public static final GTRecipeType[] RECIPE_ANTI_ENTROPY = {
            GtlAddCompat.antientropyCondensation(),
            GTRecipeTypes.VACUUM_RECIPES,
            GTLRecipeTypes.PLASMA_CONDENSER_RECIPES,
    };

    /** 原初分歧发生器 —— 上游 :650。 */
    public static final GTRecipeType[] RECIPE_DIVERGENCE_GENERATOR = {
            ShanhaiRecipeTypes.WORLDLINE_OSCILLATION_COLLECTION,
            ShanhaiRecipeTypes.INTERSTELLAR_MATTER_ABSORPTION,
            ShanhaiRecipeTypes.PRIMORDIAL_ENERGY_ABSORPTION,
            // 2026-09-25（用户点单「给原初分歧发生器加上光子虹吸的配方种类」）：
            // 光子虹吸【追加】在末尾，原有 3 条一条未动、下标不变（activeRecipeType 口径不位移）。
            // 类型 id 经 jar 字节码实证为 "photon_siphon"（ShanhaiRecipeTypes.init() 的 register 首参）；
            // 中文名 = lang 键 gtceu.photon_siphon（"光子虹吸"）。
            // 该类型此前只挂在 primordial_debug_module 上；挂到这里后，它的配方在这台也跑得了。
            ShanhaiRecipeTypes.PHOTON_SIPHON,
    };

    /** 原初物质铸造机 —— 上游 :678。 */
    public static final GTRecipeType[] RECIPE_CASTING = {
            ShanhaiRecipeTypes.PHOTON_SEPARATION,
            ShanhaiRecipeTypes.MATTER_MODULE_CASTING,
            ShanhaiRecipeTypes.MATTER_FLOW_CONDENSATION,
            ShanhaiRecipeTypes.MATTER_FORGING,
    };

    /** 原初装配线模块 —— 上游 :707。 */
    public static final GTRecipeType[] RECIPE_ASSEMBLY_LINE_MODULE = {
            GTRecipeTypes.ASSEMBLY_LINE_RECIPES,
            GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES,
            GTLRecipeTypes.COMPONENT_ASSEMBLY_LINE_RECIPES,
            ShanhaiRecipeTypes.WL_BOARD_CIRCUIT_ASSEMBLY,
            ShanhaiRecipeTypes.WL_BOARD_WAFER_ETCHING,
    };

    /** 原初多维聚爆核心 —— 上游 :800。 */
    public static final GTRecipeType[] RECIPE_MULTIDIMENSIONAL_IMPLOSION = {
            GTLRecipeTypes.ELECTRIC_IMPLOSION_COMPRESSOR_RECIPES,
            GTRecipeTypes.IMPLOSION_RECIPES,
            GTLRecipeTypes.GRAVITATION_SHOCKBURST_RECIPES,
    };

    /** 原初天琼组装核心 —— 上游 :883。 */
    public static final GTRecipeType[] RECIPE_TIANQIONG_ASSEMBLY = {
            GTLRecipeTypes.ASSEMBLER_MODULE_RECIPES,
            GTLRecipeTypes.SUPRACHRONAL_ASSEMBLY_LINE_RECIPES,
    };

    /** 原初世线穿刺矩阵 —— 上游 :927。 */
    public static final GTRecipeType[] RECIPE_WORLDLINE_TRAVERSAL = {
            GTLRecipeTypes.MAGIC_MANUFACTURER_RECIPES,
            GTLRecipeTypes.SPACE_COSMIC_PROBE_RECEIVERS_RECIPES,
            GTLRecipeTypes.DECAY_HASTENER_RECIPES,
            GTLRecipeTypes.NEUTRON_ACTIVATOR_RECIPES,
            GTLRecipeTypes.BEDROCK_DRILLING_RIG_RECIPES,
    };

    /** 原初量子扭曲矩阵 —— 上游 :951。 */
    public static final GTRecipeType[] RECIPE_QUANTUM_DISTORTION = {
            GTLRecipeTypes.QFT_RECIPES,
            GTLRecipeTypes.NEUTRON_COMPRESSOR_RECIPES,
            GTLRecipeTypes.DIMENSIONALLY_TRANSCENDENT_MIXER_RECIPES,
            GTLRecipeTypes.NANO_FORGE_RECIPES,
            GTLRecipeTypes.PCB_FACTORY_RECIPES,
            GTLRecipeTypes.FUEL_REFINING_RECIPES,
            // 🆕 2026-09-30 用户点单：模板 = 纳米蜂群工厂（同数组里那条 NANO_FORGE_RECIPES），透镜全换成电路
            ShanhaiRecipeTypes.PRIMORDIAL_SWARM_CASTING,
    };

    /** 原初韶光聚合核心 —— 上游 :976。 */
    public static final GTRecipeType[] RECIPE_SHAOGUANG_AGGREGATION = {
            GTLRecipeTypes.AGGREGATION_DEVICE_RECIPES,
            GtlAddCompat.leylineCrystallize(),
            GTLRecipeTypes.CREATE_AGGREGATION_RECIPES,
    };

    /** 原初未央重构模块 —— 上游 :997（5 条全部是 GTLAdd，全部经 GtlAddCompat）。 */
    public static final GTRecipeType[] RECIPE_WEIYANG_RECONSTRUCTION = {
            GtlAddCompat.matterExotic(),
            GtlAddCompat.nightmareCrafting(),
            GtlAddCompat.emResonanceConversionField(),
            GtlAddCompat.tectonicFaultGenerator(),
            GtlAddCompat.voidfluxReaction(),
    };

    /** 原初世线蚀刻核心 —— 上游 :1023。 */
    public static final GTRecipeType[] RECIPE_ENGRAVING = {
            GTRecipeTypes.LASER_ENGRAVER_RECIPES,
            GTLRecipeTypes.PRECISION_LASER_ENGRAVER_RECIPES,
            GTLRecipeTypes.DIMENSIONAL_FOCUS_ENGRAVING_ARRAY_RECIPES,
            GtlAddCompat.photonMatrixEtch(),
            ShanhaiRecipeTypes.WL_BOARD_WAFER_ETCHING,
            // 🆕 2026-09-30 用户点单：模板 = 光子晶阵蚀刻（同数组里那条 GtlAddCompat.photonMatrixEtch()），透镜全换成电路
            ShanhaiRecipeTypes.PRIMORDIAL_LASER_ETCHING,
    };

    /** 原初太虚宇宙锻炉 —— 上游 :1735。 */
    public static final GTRecipeType[] RECIPE_SMELTING = {
            GTRecipeTypes.FURNACE_RECIPES,
            GTRecipeTypes.ALLOY_SMELTER_RECIPES,
            GTRecipeTypes.BLAST_RECIPES,
            GCyMRecipeTypes.ALLOY_BLAST_RECIPES,
            ShanhaiRecipeTypes.TAIXU_SMELTING,
    };

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // 2026-09-26 新增：原初山海调试模块（第 25 台模块）—— 本工程【唯一一台"只为看配方而存在"】的模块
    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    //
    // 用户交办原话（逐字）：
    //   「你帮我添加一个新机器，叫原初山海调试模块，这个机器是原始终焉引擎的一个模块，
    //     它可以运行所有山海的新增配方类型，作用是让我在jei右键它就可以看到所有山海的额外配方种类，
    //     及其下面的配方，便于我写配方用」
    //
    // 🔴 为什么"能跑"与"JEI 里右键看得到"是【同一件事】（已用 javap 实证，不是推断）：
    //   `com.gregtechceu.gtceu.integration.jei.GTRecipeTypeCategory#registerRecipeCatalysts(
    //    IRecipeCatalystRegistration)`（静态方法，字节码偏移 0–154）双重遍历
    //   `GTRegistries.RECIPE_TYPES × GTRegistries.MACHINES`，用 `if_acmpne`（**引用相等**）比对
    //   `MachineDefinition.getRecipeTypes()[i] == recipeType`，命中就把 `def.asStack()` 登记成
    //   该 JEI 分类的 catalyst（偏移 137 `addRecipeCatalyst(ItemStack, RecipeType[])`）。
    //   ⇒ **机器挂了哪些类型，JEI 右键就能看到哪些分类**；`GTRecipeType` 与 `MachineDefinition`
    //   都**没有** `getRecipeCatalysts()` / `addRecipeCatalyst`（javap 全签名 grep 零命中）。
    //   ⇒ 本模块**不需要、也不许**自写 JEI 插件；只要这份数组挂对，环 3 自动完成。
    //
    // 🔴 本数组【只归本模块所有】—— 不许拿它去喂别的机器，也不许改它去凑别的机器。
    //   背景（本工程已知坑）：上面那批 `RECIPE_TYPES_*`（批一）里有 6 个数组被 2~3 台机器共用
    //   （`ENERGY_EXTRACTION / ASSEMBLY / HIGH_ENERGY / COLLECTION / AGGREGATION /
    //   DISTILLATION_VACUUM`，见本文件 L252-256 的留档），原地改一个会一次改坏 2~3 台。
    //   实测：那 11 个数组现在**已不被 SPECS 引用**（L627-866 区间 grep `RECIPE_TYPES_` 命中 0），
    //   但纪律仍然照旧 —— 新增机器一律开自己的数组，一个既有数组都不碰。
    //
    // 🔴 挂几条？—— ✅ <b>2026-09-26 用户点单"40 条"已落地</b>：挂**全部真类型**。
    //   （历史：本轮先按"只挂已注册的 16 条"落地过一版；用户随后点单扩到 40 条 ⇒ 已扩。）
    //   🔴 同日再 <b>40 → 41</b>：用户点单新增「原初物质解构」
    //   （{@code primordial_matter_deconstruction}）⇒ 本数组同步补成 41 条。
    //   41 = 本工程 {@code ShanhaiRecipeTypes.REAL_TYPE_COUNT}。**不许**把没注册的类型塞进来：
    //   没注册的字段是 `null`，而 `WorkableMultiblockMachine#getRecipeType()` 是
    //   `recipeTypes[activeRecipeType]`（javap：`getfield recipeTypes` → `getfield activeRecipeType`
    //   → `aaload`）⇒ null 会被当成"当前配方类型"用出去。下面的
    //   {@link #buildDebugModuleRecipeTypes()} 在**注册期**就把这条变成响亮异常。
    //
    // ⚠️ 恢复那 24 条时已按用户 2026-09-26 的先后顺序执行：
    //    ① 先在 `ShanhaiRecipeTypes` 里按作废块原文恢复注册（它自带 fail-fast，`REAL_TYPE_COUNT` 16→40）；
    //    ② ✅ **复核 `getRecipeUI()` 非 null —— 已做，静态证明（不依赖运行）**：
    //       `GTRecipeType.<init>` 的字节码偏移 `40: new GTRecipeTypeUI` / `45: invokespecial
    //       GTRecipeTypeUI."<init>":(GTRecipeType;)V` / `48: putfield recipeUI` ——
    //       **无条件赋值、前面没有任何分支**；`getRecipeUI()` 只是 `getfield recipeUI`（偏移 1），
    //       没有 lazy、没有判空；`setRecipeUI` 我们一次都没调用。
    //       ⇒ **经 `GTRecipeTypes.register(...)` 造出来的类型，UI 恒非 null**。
    //       因此 gtlcore 那条加固（`org.gtlcore.gtlcore.mixin.gtm.GTJEIPluginMixin` 用 `@Redirect` 把
    //       `registerCategories` 里的 `GTRecipeTypeUI;isXEIVisible()Z` 换成
    //       `gtlcore$hideRecipeTypesWithoutUi(GTRecipeTypeUI)` —— UI 为 null 就当不可见）
    //       **对这 40 条里任何一条都不成立** ⇒ 不会被藏掉。
    //       （该 mixin **只改 registerCategories**，没碰 registerRecipeCatalysts。）
    //    ③ lang：**24 条已全部补齐**（40 条 `gtceu.<id>` 键齐了）。其中 21 条照抄旧私货 lang 的**值**、
    //       但**键名一律写成 `gtceu.<id>`**（旧私货写的 `gtceu.recipe_type.<id>` 是**读不到的诱饵键** ——
    //       GTCEu 的 JEI 分类标题只读 `gtceu.<id>`，见 {@code GTRecipeTypeCategory#getTitle()}）；
    //       余 3 条（`matter_aggregation` / `worldline_cutting` / `high_dimensional_fragment_cutting`）
    //       上游两处都没有名字 ⇒ **用户 2026-09-26 裁决：用他 2026-09-22 的旧定名**
    //       「原初物质凝集」/「原初世线切割」/「高维碎片裁切」（原话「三条名字用前者」）。
    //       ⚠️ 队长先前给过另一套（物质聚合 / 世线切割 / 高维碎片裁切）—— **未被采用**，别写进去。
    //    ④ 测试配方（KubeJS）：见 {@code kubejs/server_scripts/[server_scripts]shanhai_test_recipes.js}。
    //    ⚠️ 用户若将来又要 **C 方案**（再加 36 条 `nine_industrial_mode_0..35` 显示类型），额外代价：
    //       那 36 条的现存 lang 键是 `gtceu.recipe_type.nine_industrial_mode_N`（36/36 有），
    //       而 `gtceu.nine_industrial_mode_N` 实测 **0/36** ⇒ 不补就是 36 行裸键；且那批来自
    //       **GTNH**（原 jar guide 逐字：「原出处：GTnotleisure-0.2.5.jar (GTNH mod)
    //       com.science.gtnl.common.machine.multiblock.wireless.NineIndustrialMultiMachine」），
    //       与用户 2026-09-22「那个 GTNH 是我重制版不会添加的」的裁决相抵 ⇒ 丙方案不问用户。
    //
    // 📌 并行表：`ParallelTable.STANDARD` —— 与 24 台里的 21 台同口径（空槽基础 64 +
    //    按物质模块槽那张 17 项表提升）。用户 2026-09-26 原话已把"无表档"整档删除，本台照办。
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>原初山海调试模块</b>挂的山海自有配方类型（**本模块独有**）。
     *
     * <p>顺序 = {@code ShanhaiRecipeTypes.init()} 里的注册顺序（也就是逐条照抄旧私货
     * {@code DShanhaiRecipeTypes.java} 的原顺序），便于与那一份逐行对账。
     *
     * <p>为什么是这些条：它们是本工程**当前实际注册**的全部真类型
     * （{@code ShanhaiRecipeTypes.REAL_TYPE_COUNT}，2026-09-26 由 16 恢复为 40、同日再增为 41）。
     * 另有 36 条 GTNH 显示类型（{@code nine_industrial_mode_0..35}）**按用户 2026-09-22 裁决不恢复**。
     * 用户 2026-09-26 裁决："山海新增配方类型"口径 = **全部真类型** ⇒ 本数组就是全部真类型。
     *
     * <p>⚠️ <b>40 条不等于 JEI 里有 40 个"有内容"的分类</b>：
     * <ul>
     *   <li>{@code proxy_execution} 的 {@code setMaxIOSize(0,0,0,0)}（物品输入=0、输出=0）
     *       ⇒ <b>写不出任何配方</b> ⇒ 它的 JEI 分类**永远是空的**；</li>
     *   <li>{@code primordial_myriad_ascension_tier_1} / {@code _tier_2}（{@code 4,0,4,0}）与
     *       {@code gravitational_wave_consumption}（{@code 1,0,1,0}）**物品输出槽 = 0**
     *       ⇒ 写不出「原石→钻石」，只能用"只消耗"形态；</li>
     *   <li>⇒ 实际能出内容的是 <b>39</b> 个分类（36 普通 + 3 只消耗）。</li>
     * </ul>
     */
    public static final GTRecipeType[] RECIPE_DEBUG_MODULE = buildDebugModuleRecipeTypes();

    /**
     * 🟢 2026-09-26 用户点单新增：<b>世线裂解枢纽</b>（{@code shanhai:worldline_cracking_hub}）。
     *
     * <p>用户原话：「然后可以添加世线裂解枢纽作为新模块，贴图和之前的模块一样，<b>配方就世线采样</b>」。
     * ⇒ 当时<b>只挂 {@link ShanhaiRecipeTypes#WORLDLINE_SAMPLING}（世线采样）这一个</b>。
     *
     * <p>🟢 <b>2026-09-29 用户点单追加</b>：再挂 {@link ShanhaiRecipeTypes#WORLDLINE_CUTTING}（原初世线切割）
     *   ⇒ 现在<b>挂 2 个</b>：世线采样 ＋ 原初世线切割。
     *   <p>⚠️ 加之前已实证：{@code worldline_cutting} <b>不是</b>"原版零挂载"的那种悬空类型 ——
     *   {@code ShanhaiRecipeTypes} 里 {@code GTRecipeTypes.register("worldline_cutting", "multiblock")}
     *   真在跑（非注释块），且在 {@link #RECIPE_DEBUG_MODULE} 的 41 条大表里已有一条；
     *   <b>不存在第二个模块独占它</b>（全工程只有调试模块这张 catch-all 表引用过它）⇒ 不会撞车。
     *
     * <p>🔴 <b>仍未挂另外两个</b>（{@code worldline_matter_recurrence} / {@code worldline_probability_cracking}）
     *   —— 源码注释说那三个都归它，但用户至今没点这两个 ⇒ <b>不自作主张</b>，等用户明确。
     *
     * <p>⚠️ 这个数组读的是 {@link ShanhaiRecipeTypes} 的静态字段；时机已实证是对的
     *   （见上方 {@link #RECIPE_DEBUG_MODULE} 的长注释：GTRecipeTypes.init() 先于 GTMachines.init()）。
     */
    public static final GTRecipeType[] RECIPE_WORLDLINE_CRACKING_HUB = {
            ShanhaiRecipeTypes.WORLDLINE_SAMPLING,
            // 🟢 2026-09-29 追加（原初世线切割）
            ShanhaiRecipeTypes.WORLDLINE_CUTTING,
    };

    /**
     * 🟢 2026-10-03 用户点单新增第 27 台：<b>原初碳基解构核心</b>
     * （{@code shanhai:primordial_carbon_deconstruction_core}）。
     *
     * <p><b>用户原话（逐字）</b>：
     * <blockquote>
     * 「新增机器：原初有机分解模块（有机分解你可以帮我取个科幻一点的名字），拥有配方类型：
     * 太素衍化，石化工厂，木化工厂，其余和其他模块一样」<br>
     * 「再给原初碳基解构核心加上热解炉，裂化机，蒸馏塔，脱硫的配方」
     * </blockquote>
     * （机器名由用户在候选里选定 = <b>「原初碳基解构核心」</b>。）
     *
     * <p>⇒ 本条数组 = 两句话的并集，<b>7 个类型</b>，顺序 = 用户点名的先后顺序。
     * <p>🔴 <b>7 个类型全部是宿主（gtceu / gtlcore / gtladditions）已有的，本工程一个新类型都没注册</b>
     * ⇒ {@code ShanhaiRecipeTypes.java} <b>一个字节都没动</b>（该文件本轮按任务书禁止改动）。
     *
     * <h3>逐条 id 实证（全部由本轮的 jar 字节码 + 语言文件反查得到，不是转抄）</h3>
     * <pre>
     *   下标  中文名      本数组里的字段                                    注册 id（register 的首参）
     *   ───  ──────────  ────────────────────────────────────────────────  ─────────────────────────────
     *   0    太素衍化    GtlAddCompat.evolutionOfPrimordial()              gtceu:evolution_of_primordial
     *   1    石化工厂    GTLRecipeTypes.PETROCHEMICAL_PLANT_RECIPES        gtceu:petrochemical_plant
     *   2    木化工厂    GTLRecipeTypes.WOOD_DISTILLATION_RECIPES          gtceu:wood_distillation
     *   3    热解炉      GTRecipeTypes.PYROLYSE_RECIPES                    gtceu:pyrolyse_oven
     *   4    裂化机      GTRecipeTypes.CRACKING_RECIPES                    gtceu:cracker
     *   5    蒸馏塔      GTRecipeTypes.DISTILLATION_RECIPES                gtceu:distillation_tower
     *   6    脱硫        GTLRecipeTypes.DESULFURIZER_RECIPES               gtceu:desulfurizer
     * </pre>
     * ⚠️ 任务书里把第 0 条写成 {@code gtladditions:evolution_of_primordial}；<b>命名空间那一节是错的</b>
     * —— 实测是 {@code gtceu:}。三条判据写在本数组第 0 行与
     * {@link GtlAddCompat#evolutionOfPrimordial()} 的 javadoc 里（走 gtceu 自己的
     * {@code GTRecipeTypes.register} ⇒ {@code GTCEu.id(name)} 恒返回 {@code gtceu:<id>}）。
     * 中文名反查出处：{@code handoff/outbound/类型名自动反查.md:514}（太素衍化）／
     * {@code :539}（木化工厂）／{@code :553}（渔场）等，键名一律 {@code gtceu.<id>}。
     *
     * <p>⚠️ 与 {@link #RECIPE_ORE_PROCESSING} 那类"多条 GTL/GT 混合"的数组同型：数组内容全是
     * {@code GTRecipeType} 对象，命名空间只在**取证/报告**里出现，代码侧不拼字符串
     * —— 拼字符串会绕开 {@code gtceu:} 与 {@code gtladditions:} 的歧义（正是上面那条订正要治的病）。
     */
    public static final GTRecipeType[] RECIPE_CARBON_DECONSTRUCTION_CORE = {
            GtlAddCompat.evolutionOfPrimordial(),       // 太素衍化   gtceu:evolution_of_primordial
            GTLRecipeTypes.PETROCHEMICAL_PLANT_RECIPES, // 石化工厂   gtceu:petrochemical_plant
            GTLRecipeTypes.WOOD_DISTILLATION_RECIPES,   // 木化工厂   gtceu:wood_distillation
            GTRecipeTypes.PYROLYSE_RECIPES,             // 热解炉     gtceu:pyrolyse_oven
            GTRecipeTypes.CRACKING_RECIPES,             // 裂化机     gtceu:cracker
            GTRecipeTypes.DISTILLATION_RECIPES,         // 蒸馏塔     gtceu:distillation_tower
            GTLRecipeTypes.DESULFURIZER_RECIPES,        // 脱硫       gtceu:desulfurizer
    };

    /**
     * 🟢 2026-10-03 用户点单新增<b>第 28 台</b>：<b>原初引力干涉阵列</b>
     * （{@code shanhai:primordial_gravitational_interference_array}）。
     *
     * <p><b>用户原话（逐字）</b>：
     * <blockquote>
     * 「你可以设计一台新机器，拥有引力波宏观干涉和引力波广域广播的配方，名字你去我来选」
     * </blockquote>
     * （机器名由<b>用户</b>在候选里选定 = <b>「原初引力干涉阵列」</b>；英文注册路径 = 队长给的
     * {@code primordial_gravitational_interference_array}。）
     *
     * <p>⇒ 本条数组 = 用户点名的<b>两个</b>类型，顺序 = 用户点名的先后顺序：
     * <pre>
     *   下标  中文名          本数组里的字段                                        注册 id
     *   ───  ──────────────  ────────────────────────────────────────────────────  ───────────────────────────────────
     *   0    引力波宏观干涉  ShanhaiRecipeTypes.GRAVITATIONAL_WAVE_PRODUCTION      gtceu:gravitational_wave_production
     *   1    引力波广域广播  ShanhaiRecipeTypes.GRAVITATIONAL_WAVE_CONSUMPTION     gtceu:gravitational_wave_consumption
     * </pre>
     * （两条 id 的取证 = 2026-10-03 客户端 latest.log 里那行
     *  {@code [SHANHAI-JEI] 模块催化剂展示槽已挂：45/45 …} 的「已挂」列表，逐字含这两个 id。）
     *
     * <p>🔴 <b>语义（别搞反）</b>：{@code gravitational_wave_production} = 宏观干涉 = <b>接收 / 测量端</b>
     * （IO = 2 物品入 / 2 物品出 / 2 流体入 / 2 流体出 ⇒ 产物从那一步出）；
     * {@code gravitational_wave_consumption} = 广域广播 = <b>发射端</b>
     * （IO = 1 物品入 / <b>0 物品出</b> / 1 流体入，用户已定「引力子碎片在这里当广播消耗品被吃掉」）
     * ⇒ <b>本机不改这两个类型的 IO / 中文名，一个字都不许动</b>。
     *
     * <p>🔴 <b>本轮之前，这两个类型【没有任何机器挂载】</b>：它们只出现在
     * {@link #RECIPE_DEBUG_MODULE} 那张 catch-all 表里（玩家在正常机器上跑不到）
     * ⇒ 本轮就是给它们一个家。
     *
     * <p>⚠️ 两条都是<b>山海自有</b>真类型（{@code ShanhaiRecipeTypes} 里 register 过、
     * 在 {@code REAL_TYPE_COUNT} = 45 之内）⇒ 本机落地后"有没有配方可跑"取决于配方数据侧
     * （用户已自行写配方）；<b>本轮一个配方都不写</b>（任务书硬要求）。
     *
     * <p>⚠️ 写法照抄 {@link #RECIPE_WORLDLINE_CRACKING_HUB}：直接读 {@code ShanhaiRecipeTypes}
     * 的静态字段、不做 {@code buildXxx()} 那种带名字表的 fail-fast —— 只挂 2 条的数组抄那套
     * 是多余结构；时机正确性由 {@link #RECIPE_DEBUG_MODULE} 的长注释（GTRecipeTypes.init()
     * 先于 GTMachines.init()）与那 24 台的既有事实共同保证。
     */
    public static final GTRecipeType[] RECIPE_GRAVITATIONAL_INTERFERENCE_ARRAY = {
            ShanhaiRecipeTypes.GRAVITATIONAL_WAVE_PRODUCTION,   // 引力波宏观干涉  gtceu:gravitational_wave_production
            ShanhaiRecipeTypes.GRAVITATIONAL_WAVE_CONSUMPTION,  // 引力波广域广播  gtceu:gravitational_wave_consumption
    };

    /**
     * 🟢 2026-10-03 用户点单新增<b>第 29 台</b>：<b>原初深空汲取核心</b>
     * （{@code shanhai:primordial_deep_space_extraction_core}）。
     *
     * <p><b>用户原话（逐字）</b>：
     * <blockquote>
     * 「我想新增一个机器，它可以执行以下配方：集气室，大型集气室，虚空聚流反应，虚空流体钻机，
     * 名字你取我来选」
     * </blockquote>
     * （机器名由<b>用户</b>在候选里选定 = <b>「原初深空汲取核心」</b>；形态 = <b>模块</b>（贴主机）。）
     *
     * <p>⇒ 本条数组 = 用户点名的<b>四个</b>类型，<b>顺序 = 用户点名的先后</b>
     * （「集气室 → 大型集气室 → 虚空聚流反应 → 虚空流体钻机」逐字照用户那句话的先后）：
     * <pre>
     *   下标  中文名        本数组里的字段                                  注册 id（register 的首参）
     *   ───  ────────────  ──────────────────────────────────────────────  ─────────────────────────────────
     *   0    集气室        GTRecipeTypes.GAS_COLLECTOR_RECIPES             gtceu:gas_collector
     *   1    大型集气室    GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES     gtceu:large_gas_collector
     *   2    虚空聚流反应  GtlAddCompat.voidfluxReaction()                gtceu:voidflux_reaction
     *   3    虚空流体钻机  GTLRecipeTypes.VOID_FLUID_DRILLING_RIG_RECIPES gtceu:void_fluid_drilling_rig
     *   4 🆕 原初深空汲取  ShanhaiRecipeTypes.PRIMORDIAL_DEEP_SPACE_EXTRACTION  gtceu:primordial_deep_space_extraction
     * </pre>
     * ⚠️ 上面这张表与数组体<b>必须同序</b>（下标一一对应）：数组给机器用、表给人看，
     * 错位就会变成"注释说是 A、机器挂的是 B"，那正是本工程最忌讳的一种假数据。
     * <p>🆕 <b>2026-10-04 用户点单追加下标 4</b>（原初深空汲取）：用户原话
     * 「<b>为原初深空汲取核心新增配方类型：原初深空汲取</b>」⇒ 本条数组由 4 条变 5 条，
     * <b>其余四条的字段与顺序一字未动</b>（也没有改任何别的机器）。
     * 第 4 条是<b>山海自有</b>真类型（前面四条都是宿主已有类型）——
     * 它由 {@code ShanhaiRecipeTypes.init()} 在 {@code GTRecipeTypes.init()} 的注册回调里赋值，
     * 而本类的类加载由 {@code GTMachines.init()} 触发（见 {@link #buildDeepSpaceExtractionCoreRecipeTypes()}
     * 的时机链），<b>严格更晚</b> ⇒ 取到的必然不是 null；这一条同样受
     * {@link #requireNoNullRecipeTypes} 的 fail-fast 保护。
     *
     * <h3>取证出处（2026-10-03 本轮实跑 javap 得到；原始输出见交付报告）</h3>
     * <ul>
     *   <li>{@code gtceu:gas_collector}：{@code javap -p -c -cp libs/gtceu-1.20.1-1.4.4.jar
     *       com.gregtechceu.gtceu.common.data.GTRecipeTypes} 的 {@code <clinit>}
     *       偏移 <b>2360</b> = {@code ldc_w // String gas_collector}、
     *       偏移 <b>2424</b> = {@code putstatic // Field GAS_COLLECTOR_RECIPES}。</li>
     *   <li>{@code gtceu:large_gas_collector}：{@code javap -p -c -cp libs/gtlcore-1.2.3.2-fix3.jar
     *       org.gtlcore.gtlcore.common.data.GTLRecipeTypes} 的 {@code <clinit>}
     *       偏移 <b>2432</b> = {@code ldc_w // String large_gas_collector}、
     *       偏移 <b>2473</b> = {@code putstatic // Field LARGE_GAS_COLLECTOR_RECIPES}。</li>
     *   <li>{@code gtceu:void_fluid_drilling_rig}：同一份 {@code GTLRecipeTypes} 的 {@code <clinit>}
     *       偏移 <b>1358</b> = {@code ldc_w // String void_fluid_drilling_rig}、
     *       偏移 <b>1399</b> = {@code putstatic // Field VOID_FLUID_DRILLING_RIG_RECIPES}。</li>
     *   <li>{@code gtceu:voidflux_reaction}：{@code javap -p -c -cp libs/gtladditions-3.2.8Custom-fix2.jar
     *       com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes} 的 {@code <clinit>}
     *       偏移 <b>192</b> = {@code ldc_w // String voidflux_reaction}、
     *       偏移 <b>233</b> = {@code putstatic // Field VOIDFLUX_REACTION}。
     *       本工程<b>一律经</b> {@link GtlAddCompat#voidfluxReaction()} 转发
     *       （项目铁律：禁止直接 import {@code GTLAddRecipesTypes}）。</li>
     *   <li>🔴 <b>命名空间为什么四条全是 {@code gtceu:}</b>：四条注册的字节码里
     *       {@code invokestatic} 的目标<b>都是</b>
     *       {@code com/gregtechceu/gtceu/common/data/GTRecipeTypes.register:(String,String,[Lnet/minecraft/world/item/crafting/RecipeType;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;}
     *       —— gtlcore 与 gtladditions <b>都没有自己造注册器</b>，它们是调 gtceu 自己那个方法；
     *       而它的第一句是 {@code new GTRecipeType(GTCEu.id(name), …)}，{@code GTCEu.id(String)} =
     *       {@code new ResourceLocation("gtceu", toLowerCaseUnder(name))}
     *       ⇒ 谁调它都落 {@code gtceu:}。<b>不存在</b> {@code gtlcore:…} / {@code gtladditions:…}
     *       形态的 id（本机不去拼字符串，只引用对象 —— 拼字符串正是这条歧义的来源）。</li>
     * </ul>
     *
     * <h3>🔴 语义：这四个类型【一个物品输出槽都没有】</h3>
     * 四条的 {@code GTRecipeType#setMaxIOSize(物品入, 物品出, 流体入, 流体出)} 逐条实读
     * （同一份 javap 的 {@code <clinit>}）：
     * <pre>
     *   gtceu:gas_collector            (1, 0, 0, 1)
     *   gtceu:large_gas_collector      (2, 0, 0, 1)
     *   gtceu:voidflux_reaction        (3, 0, 0, 1)
     *   gtceu:void_fluid_drilling_rig  (2, 0, 0, 1)
     * </pre>
     * ⇒ 这四个类型<b>只有 1 个流体输出槽</b>，<b>物品输出槽全是 0</b>（任务书里点名的那三个如此，
     * 第 4 个 {@code voidflux_reaction} 实测<b>同形</b>）。对本机的含义：
     * <ul>
     *   <li>这四类配方<b>只出流体</b> ⇒ 玩家要接的是<b>流体输出仓</b>，这台机器<b>不可能</b>产出物品
     *       —— 那是类型自己的 IO 口径，不是本机的 bug，也不是本工程能改的（见下一条红线）；</li>
     *   <li>它们<b>也没有流体输入槽</b>：{@code voidflux_reaction} 里那句"流体调节器 ＋ XX数据"
     *       中的两样<b>都是物品</b>（配方侧 {@code chance: 0} 不消耗 = 催化剂形态）
     *       ⇒ 任务书里"只吃电"这句话是<b>成立的</b>；</li>
     *   <li>⇒ 在 JEI 里右键本机时，这四类分类的右侧只会出现<b>流体</b>产物。</li>
     * </ul>
     *
     * <p>🔴 <b>本机不改这四个类型的 IO / 中文名 / 注册，一个字都不许动</b>：四条都是宿主
     * （gtceu / gtlcore / gtladditions）已有的类型，本工程只做<b>只读引用</b>；
     * {@code ShanhaiRecipeTypes.java} 与三个宿主 jar 本轮<b>一个字节都没改</b>。
     *
     * <p>⚠️ <b>本轮一个配方都不写</b>（任务书硬要求）：本机只是"给这四个类型一个家"。
     * 落地后"有没有配方可跑"取决于宿主自带的数据（集气室 / 大型集气室 / 虚空流体钻机 /
     * 虚空聚流反应 的配方都在宿主 jar 内，本工程不介入）。已知：集气室的配方带 {@code dimension}
     * 配方条件 —— 那条通道由「额外挂载槽 ×3」+ 维度碎片承担（见
     * {@code PrimordialModuleRecipeLogic#shanhai$extraMountGateAllows}，与配方类型无关）。
     *
     * <p>⚠️ 写法照抄 {@link #RECIPE_GRAVITATIONAL_INTERFERENCE_ARRAY}（第 28 台：直接读静态字段
     * 与 {@link GtlAddCompat} 转发口，不做多余结构）。<b>唯一多出来的一步</b>：任务书要求把
     * "某条没注册上 ⇒ 静默挂上 null" 变成<b>注册期响亮失败</b>，故本条不写裸数组字面量，而是走
     * {@link #buildDeepSpaceExtractionCoreRecipeTypes()} → {@link #requireNoNullRecipeTypes}。
     * 为什么必须有这一步：本工程唯一的 null 兜底在
     * {@code MachineTooltips#recipeTypesList}，而它<b>是"跳过 null"</b>
     * （原文 {@code if (type == null || type.registryName == null) continue;}，
     * 理由见该处注释"宁缺毋滥"）⇒ <b>裸数组里出现 null 的后果是"这台机器少一条类型"、
     * 日志里一个字都没有</b>，与"它本来就只有三条"完全同形。
     */
    public static final GTRecipeType[] RECIPE_DEEP_SPACE_EXTRACTION_CORE =
            buildDeepSpaceExtractionCoreRecipeTypes();

    /**
     * 造 {@link #RECIPE_DEEP_SPACE_EXTRACTION_CORE} 并<b>在注册期做 fail-fast</b>
     * （第 29 台，2026-10-03）。
     *
     * <p>🔴 为什么必须单独写成方法而不是一个裸数组字面量：本数组读的是
     * <b>三个不同来源</b>的类型 ——
     * <ol>
     *   <li>{@code gtceu} 的 {@code GTRecipeTypes} 静态字段（{@code <clinit>} 里赋值）；</li>
     *   <li>{@code gtlcore} 的 {@code GTLRecipeTypes} 静态字段（同一个包的 {@code <clinit>}）；</li>
     *   <li>{@code gtladditions} 的 {@code GTLAddRecipesTypes} Kotlin object getter
     *       （{@code GTLAddCompat.voidfluxReaction()}；它读 {@code INSTANCE}，
     *       <b>会触发那个 object 的类加载</b>）。</li>
     * </ol>
     * 任一处早于我们，或晚于我们，取到的就是 {@code null}；而 {@code null} 的**默认后果是静默**
     * （见 {@link #RECIPE_DEEP_SPACE_EXTRACTION_CORE} 注释末段）。
     *
     * <h3>时机为什么本来就成立（本轮复核，不是照抄）</h3>
     * <pre>
     * ShanhaiMod（@Mod 构造器）→ ShanhaiRegistry.init()   ← 只入列物品/流体/创造栏，不注册机器
     *   └─ GTCEu CommonProxy.init()
     *        ├─ 偏移 43  GTRecipeConditions.init()
     *        ├─ 偏移 94  GTRecipeTypes.init()   ← 内部在偏移 122 post 出
     *        │                 GTCEuAPI.RegisterEvent&lt;ResourceLocation, GTRecipeType&gt;
     *        │                 （gtlcore / gtladditions / 本工程都在这个回调里注册类型），
     *        │                 偏移 128 才 freeze()
     *        └─ 偏移 97  GTMachines.init()      ← post 出 MachineDefinition RegisterEvent
     *                        └─ ShanhaiRegistry.onMachineRegister → ShanhaiMachines.init()
     *                             └─ ModuleRegistry.init() → 本类【类加载】
     *                                 （RECIPE_* 数组就是在这里被求值的）
     * </pre>
     * ⇒ 本类的类加载<b>严格晚于</b> gtceu/gtlcore/gtladditions 三条注册链，
     * 三条来源此刻都已被赋值。既有旁证（同一时机、已在盘上跑了很久）：
     * {@link #RECIPE_ETERNAL_SMELTING}（3 条 {@link GtlAddCompat}）、
     * {@link #RECIPE_BIO}（1 条）、{@link #RECIPE_CARBON_DECONSTRUCTION_CORE}（第 27 台，1 条）、
     * 以及 {@link #RECIPE_CRITICAL_PROCESSING} 起的 30+ 处 {@code GTLRecipeTypes.X} 直接引用。
     * <p>⚠️ 但"本来就成立"与"静默拿到 null"在日志上<b>长得一模一样</b> ⇒ 本方法把它变成异常。
     *
     * <p>⚠️ 检查器本体 = {@link #requireNoNullRecipeTypes}，与调试模块那张 45 条表<b>共用同一处</b>
     * （任务书："已有同类检查就沿用，不要发明第二套"）。本方法只负责<b>提供名字表</b>
     * —— 异常里要能指名"是哪一条"，只报下标不够用。
     */
    private static GTRecipeType[] buildDeepSpaceExtractionCoreRecipeTypes() {
        final String path = "primordial_deep_space_extraction_core";
        // 🔴 名字表与类型表**必须同长 / 同序**（长度错位由检查器当场抛）：
        //    名字里同时写了"从哪个类拿的"，因为这台机器的四个来源分属三个 jar，
        //    只知道"第几条 null"不足以定位病根。
        final String[] names = {
                "GTRecipeTypes.GAS_COLLECTOR_RECIPES (gtceu:gas_collector)",
                "GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES (gtceu:large_gas_collector)",
                "GtlAddCompat.voidfluxReaction() (gtceu:voidflux_reaction)",
                "GTLRecipeTypes.VOID_FLUID_DRILLING_RIG_RECIPES (gtceu:void_fluid_drilling_rig)",
                // 🆕 2026-10-04 用户点单新增第 5 条：原初深空汲取（山海自有真类型，
                //    由 ShanhaiRecipeTypes.init() 在 GTRecipeTypes.init() 的注册回调里赋值；
                //    本类（ModuleRegistry）的类加载由 GTMachines.init() 触发 ⇒ 严格更晚 ⇒ 必已赋值）。
                "ShanhaiRecipeTypes.PRIMORDIAL_DEEP_SPACE_EXTRACTION (gtceu:primordial_deep_space_extraction)",
        };
        // 顺序 = 用户点名的先后（见上面那条数组注释里的下标表，两处必须一致）；
        // 🆕 第 5 条（原初深空汲取）追加在末尾。
        GTRecipeType[] types = {
                GTRecipeTypes.GAS_COLLECTOR_RECIPES,               // 集气室        gtceu:gas_collector
                GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES,        // 大型集气室    gtceu:large_gas_collector
                GtlAddCompat.voidfluxReaction(),                   // 虚空聚流反应  gtceu:voidflux_reaction
                GTLRecipeTypes.VOID_FLUID_DRILLING_RIG_RECIPES,    // 虚空流体钻机  gtceu:void_fluid_drilling_rig
                ShanhaiRecipeTypes.PRIMORDIAL_DEEP_SPACE_EXTRACTION, // 原初深空汲取  gtceu:primordial_deep_space_extraction
        };
        return requireNoNullRecipeTypes(path, names, types,
                "这五个类型里，前三个来自 gtceu/gtlcore 的静态字段、第四个来自 gtladditions 的 "
                        + "GTLAddRecipesTypes 这个 Kotlin object 的 getter、第五个（2026-10-04 追加）来自 "
                        + "ShanhaiRecipeTypes 这个【本工程自有】的静态字段 ⇒ 说明有一条注册链没跑到本类类加载之前。"
                        + "请先确认 CommonProxy#init() 里 GTRecipeTypes.init()(字节码偏移 94) 仍在 "
                        + "GTMachines.init()(偏移 97) 之前（本类的类加载由后者触发），"
                        + "且 gtlcore / gtladditions / 本工程的类型注册回调没有被挪到 freeze 之后。");
    }

    /**
     * 🔴 <b>「配方类型数组里不许有 null」的唯一检查器</b>（全工程只此一处，不许在别处再写第二份）。
     *
     * <p>本方法的由来：2026-10-03 落地第 29 台时，任务书点名要"若数组里出现 null 就抛异常、
     * 并在异常里点名是哪一个下标/类型 id"。工程里<b>已有</b>一份同类检查
     * （{@link #buildDebugModuleRecipeTypes} 里那两段：名字表错位 + null 计数），
     * 但它与那张 45 条表写死在一起、别人用不上 ⇒ 本轮把它<b>原地提取</b>成这个方法，
     * 调试模块与本台<b>走同一个判定核</b>，而不是复制一份出来。
     *
     * <p>🔴 为什么必须有它（默认为什么是静默的）：{@code register(ModuleSpec)} 拿到数组后
     * 只做两件事 —— 交给 {@code MultiblockMachineBuilder.recipeTypes(...)} 与
     * {@code MachineTooltips.forModule(...)}。后者对 {@code null} 的处理是
     * <b>跳过</b>（"宁缺毋滥：显示可以退化，加载不可以"，见
     * {@code MachineTooltips#recipeTypesList} 注释），于是
     * <b>数组里一个 null 的后果 = 这台机器少一条类型，日志里一个字都没有</b>，
     * 与"它本来就没有那一条"完全同形。本方法把这件事变成<b>带下标与字段名的加载期异常</b>。
     *
     * <p>⚠️ 与 {@code require(String)}（句柄非 null）是同一条纪律的两个落点：
     * 那个管"整台机器没注册上"，这个管"某一台的一条配方类型没拿到"。
     *
     * @param path  机器注册路径（只用于异常文案，不参与判定）
     * @param names 与 {@code types} <b>同长同序</b>的名字表（异常里指名的来源）
     * @param types 待检查的数组
     * @param hint  追加在异常里的排障提示（可空，各调用点写各自最该看的那条链）
     * @return 入参 {@code types} 原样返回（便于当字段初始化器用）
     */
    private static GTRecipeType[] requireNoNullRecipeTypes(String path, String[] names,
                                                           GTRecipeType[] types, String hint) {
        // 🔴 名字表与类型表**必须同长**：异常里要指名"是哪一条"，指错名比不指更坏。
        //    下面这句把"两张表错位"变成注册期异常，避免出现"报的是 A、坏的是 B"。
        if (names.length != types.length) {
            throw new IllegalStateException("[SHANHAI-RECIPE-TYPES] 模块 " + path
                    + " 的名字表与类型表错位：names.length=" + names.length
                    + " != types.length=" + types.length
                    + "。两张表必须一一对应，否则异常里会指错名字（比不报名字更坏）。");
        }
        StringBuilder missing = new StringBuilder();
        int nulls = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == null) {
                nulls++;
                if (missing.length() > 0) {
                    missing.append(" / ");
                }
                missing.append("[").append(i).append("] ").append(names[i]);
            }
        }
        if (nulls > 0) {
            throw new IllegalStateException("[SHANHAI-RECIPE-TYPES] 模块 " + path + " 的配方类型里有 "
                    + nulls + " / " + types.length + " 个是 null（下标从 0 起）。"
                    + " 已拿到 " + (types.length - nulls) + " 条。"
                    + (hint == null || hint.isEmpty() ? "" : " " + hint)
                    + " 具体缺的是：" + missing
                    + " ⇒ 绝不能把 null 放进 recipeTypes：WorkableMultiblockMachine#getRecipeType() ="
                    + " recipeTypes[activeRecipeType]，会在离病根极远的地方炸；"
                    + "而本工程唯一的 null 兜底（MachineTooltips#recipeTypesList）是【跳过】它，"
                    + "即「静默少一条」。");
        }
        return types;
    }

    /**
     * 造 {@link #RECIPE_DEBUG_MODULE} 并<b>在注册期做 fail-fast</b>。
     *
     * <p>🔴 为什么单独写成方法而不是一个裸数组字面量：这份数组读的是
     * {@code ShanhaiRecipeTypes} 的 <b>静态字段</b>，而那些字段是在 GTCEu 的
     * {@code RegisterEvent<ResourceLocation, GTRecipeType>} 里才被赋值的。时机**已实证是对的**
     * ——{@code com.gregtechceu.gtceu.common.CommonProxy#init()} 的字节码偏移 94 是
     * {@code GTRecipeTypes.init()}、偏移 97 才是 {@code GTMachines.init()}，
     * 且 {@code GTRecipeTypes.init()} 里的 {@code postEvent}（偏移 122）发生在
     * {@code RECIPE_TYPES.freeze()}`（偏移 128）之前 ⇒ **类型先于机器注册**。
     * 上面那 24 台用的也是同一套顺序，所以这条链本来就是通的。
     *
     * <p>但"通过"与"静默拿到 null"在日志上长得一模一样（本工程的老毛病），
     * 所以这里把"某条没注册上"变成<b>带 id 的加载期异常</b>：
     * null 流进 {@code WorkableMultiblockMachine#getRecipeType()} 会在**离病根很远的地方**炸。
     */
    private static GTRecipeType[] buildDebugModuleRecipeTypes() {
        final String path = "primordial_debug_module";
        // 🔴 名字表与类型表**必须同长**：异常里要指名"是哪一条"，指错名比不指更坏。
        //    下面那句 assert 把"两张表错位"变成注册期异常，避免出现"报的是 A、坏的是 B"。
        final String[] names = {
                "PRIMORDIAL_POWER_GENERATOR",
                "PRIMORDIAL_STELLAR_REACTION",
                "PRIMORDIAL_BIOLOGICAL_CORE",
                "PRIMORDIAL_MATTER_RECOMBINATION",
                "PRIMORDIAL_CAUSAL_WEAVING",
                "PRIMORDIAL_SINGULARITY_INVERSION",
                "TAIXU_SMELTING",
                "WORLDLINE_OSCILLATION_COLLECTION",
                "INTERSTELLAR_MATTER_ABSORPTION",
                "MATTER_FLOW_CONDENSATION",
                "PRIMORDIAL_ENERGY_ABSORPTION",
                "PHOTON_SEPARATION",
                "MATTER_MODULE_CASTING",
                "MATTER_FORGING",
                "WL_BOARD_CIRCUIT_ASSEMBLY",
                "WL_BOARD_WAFER_ETCHING",
                // ───── 2026-09-26 恢复的 24 条（用户点单"40 条"）─────
                "PROXY_EXECUTION",
                "COIN_FORGE",
                "NINE_INDUSTRIAL",
                "BLACK_HOLE_EVENT_HORIZON_BLAST",
                "BLACK_HOLE_NEUTRONIUM_COMPRESSOR",
                "BLACK_HOLE_COMPRESSOR",
                "HIGH_DIMENSIONAL_FRAGMENT_CUTTING",
                "WORLDLINE_CUTTING",
                "WORLDLINE_SAMPLING",
                "WORLDLINE_MATTER_RECURRENCE",
                "WORLDLINE_PROBABILITY_CRACKING",
                "PHOTON_SIPHON",
                "ZERO_POINT_CONVERSION",
                "MATTER_AGGREGATION",
                "GRAVITATIONAL_WAVE_CONSUMPTION",
                "TIANJIE_NAVIGATION",
                "NEBULA_SIPHONING",
                "CHAOS_CRAFTING",
                "SEVENTY_TWO_CHANGES",
                "GRAVITATIONAL_WAVE_PRODUCTION",
                "PRIMORDIAL_MYRIAD_ASCENSION_TIER_1",
                "PRIMORDIAL_MYRIAD_ASCENSION_TIER_2",
                "KU_MING_YUAN_YANG",
                "SPACETIME_DISTORTION",
                // ───── 2026-09-26 新增的第 41 条（用户点单「原初物质解构」）─────
                "PRIMORDIAL_MATTER_DECONSTRUCTION",
                // ───── 🆕 2026-09-30 新增的第 42／43 条（用户点单「原初激光蚀刻」＋「原初蜂群铸造」）─────
                "PRIMORDIAL_LASER_ETCHING",
                "PRIMORDIAL_SWARM_CASTING",
                // ───── 🆕 2026-10-01 新增的第 44 条（用户点单「原初物质定型」）─────
                "PRIMORDIAL_MATTER_FORMING",
                // ───── 🆕 2026-10-03 新增的第 45 条（用户点单「原初山海调试」）─────
                "PRIMORDIAL_DEBUG",
        };
        GTRecipeType[] types = {
                ShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR,
                ShanhaiRecipeTypes.PRIMORDIAL_STELLAR_REACTION,
                ShanhaiRecipeTypes.PRIMORDIAL_BIOLOGICAL_CORE,
                ShanhaiRecipeTypes.PRIMORDIAL_MATTER_RECOMBINATION,
                ShanhaiRecipeTypes.PRIMORDIAL_CAUSAL_WEAVING,
                ShanhaiRecipeTypes.PRIMORDIAL_SINGULARITY_INVERSION,
                ShanhaiRecipeTypes.TAIXU_SMELTING,
                ShanhaiRecipeTypes.WORLDLINE_OSCILLATION_COLLECTION,
                ShanhaiRecipeTypes.INTERSTELLAR_MATTER_ABSORPTION,
                ShanhaiRecipeTypes.MATTER_FLOW_CONDENSATION,
                ShanhaiRecipeTypes.PRIMORDIAL_ENERGY_ABSORPTION,
                ShanhaiRecipeTypes.PHOTON_SEPARATION,
                ShanhaiRecipeTypes.MATTER_MODULE_CASTING,
                ShanhaiRecipeTypes.MATTER_FORGING,
                ShanhaiRecipeTypes.WL_BOARD_CIRCUIT_ASSEMBLY,
                ShanhaiRecipeTypes.WL_BOARD_WAFER_ETCHING,
                // ───── 2026-09-26 恢复的 24 条（用户点单"40 条"）─────
                ShanhaiRecipeTypes.PROXY_EXECUTION,
                ShanhaiRecipeTypes.COIN_FORGE,
                ShanhaiRecipeTypes.NINE_INDUSTRIAL,
                ShanhaiRecipeTypes.BLACK_HOLE_EVENT_HORIZON_BLAST,
                ShanhaiRecipeTypes.BLACK_HOLE_NEUTRONIUM_COMPRESSOR,
                ShanhaiRecipeTypes.BLACK_HOLE_COMPRESSOR,
                ShanhaiRecipeTypes.HIGH_DIMENSIONAL_FRAGMENT_CUTTING,
                ShanhaiRecipeTypes.WORLDLINE_CUTTING,
                ShanhaiRecipeTypes.WORLDLINE_SAMPLING,
                ShanhaiRecipeTypes.WORLDLINE_MATTER_RECURRENCE,
                ShanhaiRecipeTypes.WORLDLINE_PROBABILITY_CRACKING,
                ShanhaiRecipeTypes.PHOTON_SIPHON,
                ShanhaiRecipeTypes.ZERO_POINT_CONVERSION,
                ShanhaiRecipeTypes.MATTER_AGGREGATION,
                ShanhaiRecipeTypes.GRAVITATIONAL_WAVE_CONSUMPTION,
                ShanhaiRecipeTypes.TIANJIE_NAVIGATION,
                ShanhaiRecipeTypes.NEBULA_SIPHONING,
                ShanhaiRecipeTypes.CHAOS_CRAFTING,
                ShanhaiRecipeTypes.SEVENTY_TWO_CHANGES,
                ShanhaiRecipeTypes.GRAVITATIONAL_WAVE_PRODUCTION,
                ShanhaiRecipeTypes.PRIMORDIAL_MYRIAD_ASCENSION_TIER_1,
                ShanhaiRecipeTypes.PRIMORDIAL_MYRIAD_ASCENSION_TIER_2,
                ShanhaiRecipeTypes.KU_MING_YUAN_YANG,
                ShanhaiRecipeTypes.SPACETIME_DISTORTION,
                // ───── 2026-09-26 新增的第 41 条（用户点单「原初物质解构」）─────
                ShanhaiRecipeTypes.PRIMORDIAL_MATTER_DECONSTRUCTION,
                // ───── 🆕 2026-09-30 新增的第 42／43 条（用户点单「原初激光蚀刻」＋「原初蜂群铸造」）─────
                ShanhaiRecipeTypes.PRIMORDIAL_LASER_ETCHING,
                ShanhaiRecipeTypes.PRIMORDIAL_SWARM_CASTING,
                // ───── 🆕 2026-10-01 新增的第 44 条（用户点单「原初物质定型」）─────
                ShanhaiRecipeTypes.PRIMORDIAL_MATTER_FORMING,
                // ───── 🆕 2026-10-03 新增的第 45 条（用户点单「原初山海调试」）─────
                ShanhaiRecipeTypes.PRIMORDIAL_DEBUG,
        };
        // 🔴 2026-10-03：原先这里有两段内联检查（名字表错位 + null 计数）；
        //    落地第 29 台时按任务书要求"沿用现成的同类检查、不要发明第二套"，
        //    把它**原地提取**成 requireNoNullRecipeTypes(...) —— 本表与第 29 台那张 4 条表
        //    现在走**同一个判定核**。判定语义与异常里带的信息逐条未变（只把缺项文案从
        //    "ShanhaiRecipeTypes.NAME" 补成 "[下标] ShanhaiRecipeTypes.NAME"，
        //    因为本表有 45 条，只报字段名定位不到"第几条"）。
        return requireNoNullRecipeTypes(path, names, types,
                "说明 ShanhaiRecipeTypes 少注册了类型"
                        + "（ShanhaiRecipeTypes.REAL_TYPE_COUNT = " + ShanhaiRecipeTypes.REAL_TYPE_COUNT + "）。"
                        + "⇒ 请先确认 CommonProxy#init() 里 GTRecipeTypes.init()(字节码偏移 94) 仍在 "
                        + "GTMachines.init()(偏移 97) 之前，且 GTRecipeType 的 RegisterEvent 监听器"
                        + "（ShanhaiRegistry::onRecipeTypeRegister）已挂上。");
    }

    /**
     * <b>正面对照</b>：证明 {@link #requireNoNullRecipeTypes} 对"已知为坏的输入"<b>真的会抛</b>。
     *
     * <p>🔴 为什么必须有这一步（本项目的血账）：<b>"永远不抛的检查器"与"永远通过的检查器"
     * 在日志上长得一模一样</b> —— 它会给出一片虚假的安心。工程里已有同款先例：
     * {@code PrimordialModuleMachine#selfTestParallelTableChecker()}（由 {@link #init()} 调用）。
     *
     * <p>判据可 grep：日志里必须有
     * {@code [SHANHAI-DEEPSPACE] 配方类型检查器正面对照通过}；没有这一行就说明它被删了。
     */
    private static void selfTestRecipeTypeChecker() {
        boolean threw = false;
        try {
            requireNoNullRecipeTypes("self-test-on-purpose",
                    new String[]{"（正面对照用的假名字）"},
                    new GTRecipeType[]{null},
                    "（正面对照：这条异常是应该发生的）");
        } catch (IllegalStateException expected) {
            threw = true;
        }
        if (!threw) {
            throw new IllegalStateException("[SHANHAI-RECIPE-TYPES] 正面对照失败："
                    + "把一个明知为 null 的数组喂给 requireNoNullRecipeTypes，它竟然没有抛 —— "
                    + "这种检查器与「永远通过」的检查器在日志上完全同形，采信它就等于没有检查。"
                    + "在修好检查器之前，本次启动直接失败（宁可不启动，也不要一份假的安心）。");
        }
    }

    // ═════════════════════════════ 句柄表（java-core 直接用，禁止字符串查表） ═════════════════════════════

    /** 物质重组核心的机器定义句柄。{@link #init()} 返回时保证非 null。 */
    public static MultiblockMachineDefinition PRIMORDIAL_MATTER_RECOMBINATOR_CORE;

    // ───── 2026-09 新增的 5 个模块句柄（同样由 {@link #init()} 保证非 null） ─────
    /** 原初临界加工模块。 */
    public static MultiblockMachineDefinition PRIMORDIAL_CRITICAL_PROCESSING_MODULE;
    /** 原初宇宙反应炉。 */
    public static MultiblockMachineDefinition PRIMORDIAL_COSMIC_REACTOR;
    /** 原初分子裂隙核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_MOLECULAR_RIFT_CORE;
    /** 原初超临界物质生成核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_SUPERCRITICAL_MATTER_GENERATION_CORE;
    /** 原初永恒熔炼炉。 */
    public static MultiblockMachineDefinition PRIMORDIAL_ETERNAL_SMELTING_FURNACE;

    // ───── 2026-09 新增的 18 个模块句柄（补齐 guide 权威清单里除铸币厂以外的全部模块） ─────
    // 全部由 {@link #init()} 的 require(...) 保证返回时非 null（为 null 直接抛带 id 的异常）。
    /** 原始真空零点能发生器。 */
    public static MultiblockMachineDefinition PRIMORDIAL_VOID_INDUCTION_ARMATURE;
    /** 原初生物核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_BIOLOGICAL_CORE;
    /** 原初混沌蜉蝣解构结晶炉。 */
    public static MultiblockMachineDefinition PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE;
    /** 原初因果编织矩阵。 */
    public static MultiblockMachineDefinition PRIMORDIAL_CAUSAL_WEAVING_MATRIX;
    /** 原初奇点反演核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_SINGULARITY_INVERSION_CORE;
    /** 原初世界碎片采集器。 */
    public static MultiblockMachineDefinition PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR;
    /** 原初反熵冷凝核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE;
    /** 原初分歧发生器。 */
    public static MultiblockMachineDefinition PRIMORDIAL_DIVERGENCE_GENERATOR;
    /** 原初物质铸造机。 */
    public static MultiblockMachineDefinition PRIMORDIAL_MATTER_CASTER;
    /** 原初装配线模块。 */
    public static MultiblockMachineDefinition PRIMORDIAL_ASSEMBLY_LINE_MODULE;
    /** 原初多维聚爆核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_MULTIDIMENSIONAL_IMPLOSION_CORE;
    /** 原初天琼组装核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_TIANQIONG_ASSEMBLY_CORE;
    /** 原初世线穿刺矩阵。 */
    public static MultiblockMachineDefinition PRIMORDIAL_WORLDLINE_TRAVERSAL_MATRIX;
    /** 原初量子扭曲矩阵。 */
    public static MultiblockMachineDefinition PRIMORDIAL_QUANTUM_DISTORTION_MATRIX;
    /** 原初韶光聚合核心。 */
    public static MultiblockMachineDefinition PRIMORDIAL_SHAOGUANG_AGGREGATION_CORE;
    /** 原初未央重构模块。 */
    public static MultiblockMachineDefinition PRIMORDIAL_WEIYANG_RECONSTRUCTION_MODULE;
    /** 原初世线蚀刻核心（注册路径仍是上游的 {@code primordial_engraving_module}）。 */
    public static MultiblockMachineDefinition PRIMORDIAL_ENGRAVING_MODULE;
    /**
     * 原初太虚宇宙锻炉。
     *
     * <p>⚠️ 注册路径<b>没有</b> {@code primordial_} 前缀 —— 上游 id 就是 {@code taixu_smelting_furnace}
     * （证据：guide 的 {@code item_ids} 里写的是 {@code gt_shanhai:taixu_smelting_furnace}；
     * 注册行是 {@code DShanhaiMachines.java:1733 .multiblock("taixu_smelting_furnace", …)}），
     * 只有<b>中文显示名</b>叫「原初太虚宇宙锻炉」（上游 {@code zh_cn.json:311} 的
     * {@code block.gt_shanhai.taixu_smelting_furnace}）。句柄名同理不带前缀。
     */
    public static MultiblockMachineDefinition TAIXU_SMELTING_FURNACE;

    // ───── 2026-09-26 新增的第 25 台（原初山海调试模块；同样由 {@link #init()} 保证非 null） ─────
    /**
     * 原初山海调试模块的机器定义句柄。
     *
     * <p>它的<b>唯一职责</b>是"把山海自有配方类型全挂在自己身上" ⇒ 于是
     * {@code GTRecipeTypeCategory#registerRecipeCatalysts} 会把这台机器的物品自动登记成
     * 那些 JEI 分类的 catalyst ⇒ <b>在 JEI 里右键它即可看到全部分类及其配方</b>。
     * 同时因为它真的挂了这些类型，{@code WorkableMultiblockMachine} 允许在 GUI 里切到其中
     * 任意一条 ⇒ 它<b>真的能跑</b>这些配方（不是纯展示物品）。详见 {@link #RECIPE_DEBUG_MODULE}。
     */
    public static MultiblockMachineDefinition PRIMORDIAL_DEBUG_MODULE;

    /**
     * 🟢 2026-09-26 新增第 26 台：<b>世线裂解枢纽</b>（{@code shanhai:worldline_cracking_hub}）。
     *
     * <p>用户原话：「然后可以添加世线裂解枢纽作为新模块，贴图和之前的模块一样，配方就世线采样」。
     * <p>⚠️ 贴图本轮<b>未改</b>（仍走 {@code register()} 里那套统一的 bronze 外壳 + steam_grinder 控制器渲染）。
     *   物品本身早就有自己的模型/贴图（{@code ShanhaiItems.WORLDLINE_CRACKING_HUB}），且已在创造栏里。
     */
    public static MultiblockMachineDefinition WORLDLINE_CRACKING_HUB;

    /**
     * 🟢 2026-10-03 新增第 27 台：<b>原初碳基解构核心</b>
     * （{@code shanhai:primordial_carbon_deconstruction_core}）。
     *
     * <p>中文名由用户在候选里亲定（原话里的临时名是「原初有机分解模块」）。
     * <p>配方类型 = {@link #RECIPE_CARBON_DECONSTRUCTION_CORE}（7 条，全是宿主已有类型）。
     * <p>⚠️ 贴图本轮<b>未改</b> —— 与其余 24 台一样走 {@code register()} 里那套统一的
     *   bronze 外壳 + steam_grinder 控制器渲染，<b>没有</b>新增任何 {@code assets/} 下的
     *   模型/贴图文件（这也是本台能"零资产"落地的原因）。
     */
    public static MultiblockMachineDefinition PRIMORDIAL_CARBON_DECONSTRUCTION_CORE;

    /**
     * 🟢 2026-10-03 新增<b>第 28 台</b>：<b>原初引力干涉阵列</b>
     * （{@code shanhai:primordial_gravitational_interference_array}）。
     *
     * <p>中文名由用户在候选里亲定；配方类型 = {@link #RECIPE_GRAVITATIONAL_INTERFERENCE_ARRAY}
     * （引力波宏观干涉 ＋ 引力波广域广播，两条<b>山海自有</b>类型）。
     * <p>⚠️ 贴图本轮<b>未改</b> —— 与其余 26 台一样走 {@code register()} 里那套统一的
     *   bronze 外壳 + steam_grinder 控制器渲染（{@code workableCasingRenderer} 动态出模型），
     *   <b>没有</b>新增任何 {@code assets/} 下的模型/贴图文件（这也是本台能"零资产"落地的原因）。
     */
    public static MultiblockMachineDefinition PRIMORDIAL_GRAVITATIONAL_INTERFERENCE_ARRAY;

    /**
     * 🟢 2026-10-03 新增<b>第 29 台</b>：<b>原初深空汲取核心</b>
     * （{@code shanhai:primordial_deep_space_extraction_core}）。
     *
     * <p>中文名由用户在候选里亲定；配方类型 = {@link #RECIPE_DEEP_SPACE_EXTRACTION_CORE}
     * （集气室 ／ 大型集气室 ／ 虚空聚流反应 ／ 虚空流体钻机，四条全是<b>宿主已有</b>类型，
     * 顺序 = 用户点名的先后）。
     * <p>⚠️ 贴图本轮<b>未改</b> —— 与其余 27 台一样走 {@code register()} 里那套统一的
     *   bronze 外壳 + steam_grinder 控制器渲染（{@code workableCasingRenderer} 动态出模型），
     *   <b>没有</b>新增任何 {@code assets/} 下的模型/贴图文件（这也是本台能"零资产"落地的原因）。
     */
    public static MultiblockMachineDefinition PRIMORDIAL_DEEP_SPACE_EXTRACTION_CORE;

    // ═════════════════════════════ 模块规格表 ═════════════════════════════

    /**
     * 一条模块的完整注册描述。**新增模块 = 这里加一行。**
     *
     * @param handleName 句柄字段名（与 {@link #require(String)} 的键一致）
     * @param path       注册路径（最终 id = {@code shanhai:<path>}）
     * @param langValue  英文显示名（中文名归 java-assets 的 lang 文件）
     * @param factory    机器构造工厂（{@code (holder, args...) -> new XxxModule(holder, args)}）
     * @param pattern    结构图案工厂
     * @param recipeTypes 该模块挂的配方类型
     * @param parallelTable 该模块用哪张「物质模块 → 并行」值表
     *        （{@link PrimordialModuleMachine.ParallelTable}）。**逐条声明**。
     *
     *        <h3>🔴 2026-09-25 路线 ① 改判（用户实机推翻旧口径，历史留档）</h3>
     *        <b>旧字段名 = {@code usesParallelModifier}（boolean），旧口径 =
     *        「只有物质重组核心为 true，其余 23 个走
     *        {@link #applyModuleRecipeModifierWithoutParallel}（跳过并行那一步）」。</b>
     *        那条口径的<b>后果</b>是：另外 23 台在游戏里并行恒为 1。用户悬停「原初世线蚀刻核心」时
     *        看到「最大并行数: 1」并当场指出「<b>不加任何物质模块它本身就有 64 的并行数</b>」。
     *        <p>逐台上游核对（12 台直读源码）后确认用户是对的：上游 <b>23/24 台</b>都有自己的并行槽
     *        （{@code DEFAULT_PARALLEL = 64L} + 一张 17 项表），唯一例外是那台<b>发电模块</b>
     *        （它按编程电路算并行：{@code PrimordialOmegaVoidInductionArmature:305-317}）。
     *        ⇒ 现在 24 台全部走带并行的同一条链，本字段改为「声明用哪张表」。
     *        并行槽本体的实现在 {@link PrimordialModuleMachine}（2026-09-25 上移）。
     *
     *        <h3>🔴 2026-09-26：上面那句"唯一例外"<b>已作废</b>（用户裁决；旧句照留）</h3>
     *        ⛔ <b>旧口径（作废）</b>：照抄上游，给发电模块单开一个"没有分档表"的档位
     *        （{@code ParallelTable.BASE}）⇒ 它的并行<b>恒为 64、不随物质模块提升</b>，
     *        与其余 23 台口径不同。（更早的旧实现是给它一段"按编程电路算并行"的上游逻辑对照。）
     *        <p><b>用户 2026-09-26 原话（逐字）</b>：
     *        <blockquote>「老板的山海那个零点能反应堆是根据电路决定并行的，我们不这样做，
     *        你把它设计和其他模块一样，基础并行是64，根据物质模块提升并行」</blockquote>
     *        ⇒ 发电模块（{@code PRIMORDIAL_VOID_INDUCTION_ARMATURE}）的 {@code parallelTable}
     *        <b>改为 {@link PrimordialModuleMachine.ParallelTable#STANDARD}</b>，
     *        而 {@code BASE} 档<b>已从枚举中删除</b>（旧档位的完整留档与删档理由见
     *        {@link PrimordialModuleMachine.ParallelTable}）。
     *        ⇒ 24 台归属 = <b>ENHANCED 3 / STANDARD 21 / 无表档 0</b>。
     *        <p>⚠️ 顺带修掉一条"活的假数据"：旧档位下，那台机器的物品 tooltip 一直写着
     *        「在物质模块槽放入物质模块可提高最大并行数，每 16 个翻一倍」，
     *        而它的并行<b>永远不会涨</b>（{@code BASE} 分支恒返回 64）。
     *        改完之后这句话对它才<b>第一次是真话</b>。
     * @param generator 是否为<b>发电</b>模块（2026-09-24 新增，本轮任务 B）。
     *        真值 ⇒ {@code register()} 链上补 {@code .generator(true)}，
     *        使 {@code MultiblockMachineDefinition.isGenerator()} 为真，
     *        {@code WorkableElectricMultiblockMachine#getMaxVoltage()} 走「输出压/输出安」分支。
     *        <b>全 24 台里只有原始真空零点能发生器为 true</b>——只有它挂了发电型配方类型
     *        {@code ShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR}（{@code setEUIO(IO.OUT)}，
     *        见 {@link #RECIPE_VOID_INDUCTION_ARMATURE}）。
     *        依据：GTCEu 原生发电多方块 {@code GTMachines.LARGE_COMBUSTION_ENGINE}
     *        同时具备 {@code .generator(true)} 与一格 {@code PartAbility.OUTPUT_ENERGY}。
     *        ⚠️ 已知边界（未实测）：发电模块在<b>没有能源输出仓</b>时
     *        {@code energyContainer} 为空 ⇒ {@code getOutputVoltage()==0}，
     *        此时 {@code tier}/{@code getMaxVoltage()} 的取值行为需实机确认。
     */
    private record ModuleSpec(String handleName,
                              String path,
                              String langValue,
                              Function<IMachineBlockEntity, ? extends MultiblockControllerMachine> factory,
                              Function<MultiblockMachineDefinition, BlockPattern> pattern,
                              Supplier<GTRecipeType[]> recipeTypes,
                              PrimordialModuleMachine.ParallelTable parallelTable,
                              boolean generator) {}

    /**
     * 全部要注册的模块（2026-09 起 <b>24 个</b>：物质重组核心 + 用户裁定的 23 个纯标准模块）。
     *
     * <p>总数对账：guide 权威清单（{@code originals/upstream/.../guide/machine/primordial_index.md} 的
     * {@code item_ids}）列出 26 个 id = 1 台主机（{@code primordial_omega_engine}）+ 25 个模块；
     * 其中铸币厂 {@code primordial_coin_forge} 已被用户删除 ⇒ 应做 24 个，本表已全部注册。
     *
     * <p>预留位的命名规则：旧机器名（如 {@code primordial_causal_weaving_matrix}）→ 大写下划线句柄名。
     * 预留表见 {@code originals/analysis/java-module/MODULE-FRAMEWORK-INTERFACE.md} §3。
     *
     * <p><b>取名与配方依据</b>（全部来自只读取证，见交付报告）：
     * 上游 25 个模块<b>共用同一份</b> {@code forge_of_the_antichrist_module.bin}（293 B，22 处调用点，
     * 结构谓词逐字符相同）⇒ 结构类零新增；它们的配方类型在宿主里解析后<b>全部落在 {@code gtceu:} 命名空间</b>。
     * 但其中相当一部分（{@code primordial_*} / {@code wl_board_*} 等）是私货自己注册、<b>配方由 KubeJS 写</b>的，
     * 本阶段没有 KubeJS ⇒ 挂上去"有类型没配方"。故 23 个纯标准模块沿用 §6.2 方案 A 的口径：
     * <b>挂 GTCEu 原版类型</b>（宿主自带配方，零数据成本，每个模块都保证"放进去就能跑一条配方"）；
     * 中文名与 id 逐一取上游 {@code gt_shanhai} 的 {@code lang/zh_cn.json} 与注册行原文。
     *
     * <p>🔴 <b>2026-09-26 订正（旧句「24 个」原样保留在上面，此处只追加事实）</b>：
     * 本表现为 <b>25 条</b> —— 新增第 25 台「原初山海调试模块」({@code shanhai:primordial_debug_module})。
     * 它的 id 与中文名是<b>用户亲定</b>（不在上游 25 个模块的清单里，是用户的调试用机器），
     * 因此它<b>不</b>属于上面那句"上游 25 个模块"的对账范围。其余 24 台的 id / 配方类型 /
     * 并行表 / 行为<b>本条改动一个字都没动</b>。
     */
    private static final List<ModuleSpec> SPECS = List.of(
            new ModuleSpec(
                    "PRIMORDIAL_MATTER_RECOMBINATOR_CORE",
                    "primordial_matter_recombinator_core",
                    "Primordial Matter Recombinator Core",
                    PrimordialMatterRecombinatorCore::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_MATTER_RECOMBINATOR_CORE,
                    PrimordialModuleMachine.ParallelTable.ENHANCED,
                    false),
            // ───── 2026-09 新增的 5 个（用户裁定「挑改动最小的几个」；上游同名机器的 id 与语义保持不变） ─────
            new ModuleSpec(
                    "PRIMORDIAL_CRITICAL_PROCESSING_MODULE",
                    "primordial_critical_processing_module",
                    "Primordial Critical Processing Module",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_CRITICAL_PROCESSING,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_COSMIC_REACTOR",
                    "primordial_cosmic_reactor",
                    "Primordial Cosmic Reactor",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_COSMIC_REACTOR,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_MOLECULAR_RIFT_CORE",
                    "primordial_molecular_rift_core",
                    "Primordial Molecular Rift Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_MOLECULAR_RIFT,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_SUPERCRITICAL_MATTER_GENERATION_CORE",
                    "primordial_supercritical_matter_generation_core",
                    "Primordial Supercritical Matter Generation Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_SUPERCRITICAL_MATTER,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_ETERNAL_SMELTING_FURNACE",
                    "primordial_eternal_smelting_furnace",
                    "Primordial Eternal Smelting Furnace",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_ETERNAL_SMELTING,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            // ═════════ 2026-09 补齐的 18 个模块（guide 权威清单 25 个 − 铸币厂 − 已做 6 个 = 18） ═════════
            // 每条一行；机器类全部复用 StandardPrimordialModule（纯标准模块，类体只有构造器），
            // 结构图案全部复用 PrimordialMatterRecombinatorCoreStructure::createPattern
            // （上游 25 个原初模块共用同一份 forge_of_the_antichrist_module.bin 结构）。
            // 中文名与上游 id 的对应依据见交付报告 §2 表；英文名 = langValue，与 en_us.json 逐字一致。
            new ModuleSpec(
                    "PRIMORDIAL_VOID_INDUCTION_ARMATURE",
                    "primordial_void_induction_armature",
                    "Primordial Void Induction Armature",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_VOID_INDUCTION_ARMATURE,
                    // 🔴🔴 2026-09-26：这里<b>曾经是</b> ParallelTable.BASE（"没有分档表 ⇒ 并行恒 64"）。
                    //   用户原话「老板的山海那个零点能反应堆是根据电路决定并行的，我们不这样做，
                    //   你把它设计和其他模块一样，基础并行是64，根据物质模块提升并行」
                    //   ⇒ 改成 STANDARD：与其余模块同口径（空槽 64 ＋ 按物质模块表提升）。
                    //   ⛔ 不要改回"无表档"——该档位已从枚举里删除，写不出来（编译期就挡）。
                    //   完整留档见 PrimordialModuleMachine.ParallelTable 与本文件 ModuleSpec 的注释。
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    // 🔴 全 24 台里唯一的发电模块（任务 B，2026-09-24）：它挂的是
                    //    ShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR（setEUIO(IO.OUT)），
                    //    必须 .generator(true) 才能走「输出压/输出安」的 getMaxVoltage() 分支，
                    //    且其结构必须允许 PartAbility.OUTPUT_ENERGY 仓（见 createPattern 的「有意偏离 3」）。
                    //    ⚠️ 与上面那条【并行来源】是两件互不影响的事：本参只管"电往哪走"，
                    //    parallelTable 只管"并行从哪来"。
                    true),
            new ModuleSpec(
                    "PRIMORDIAL_BIOLOGICAL_CORE",
                    "primordial_biological_core",
                    "Primordial Biological Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_BIO,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE",
                    "primordial_chaotic_ephemeral_deconstruction_crystallization_furnace",
                    "Primordial Chaotic Ephemeral Deconstruction Crystallization Furnace",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_ORE_PROCESSING,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_CAUSAL_WEAVING_MATRIX",
                    "primordial_causal_weaving_matrix",
                    "Primordial Causal Weaving Matrix",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_CAUSAL_WEAVING_MATRIX,
                    PrimordialModuleMachine.ParallelTable.ENHANCED,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_SINGULARITY_INVERSION_CORE",
                    "primordial_singularity_inversion_core",
                    "Primordial Singularity Inversion Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_SINGULARITY_INVERSION,
                    PrimordialModuleMachine.ParallelTable.ENHANCED,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR",
                    "primordial_world_fragments_collector",
                    "Primordial World Fragments Collector",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_WORLD_FRAGMENTS_COLLECTOR,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE",
                    "primordial_anti_entropy_condensation_core",
                    "Primordial Anti-Entropy Condensation Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_ANTI_ENTROPY,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_DIVERGENCE_GENERATOR",
                    "primordial_divergence_generator",
                    "Primordial Divergence Generator",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_DIVERGENCE_GENERATOR,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_MATTER_CASTER",
                    "primordial_matter_caster",
                    "Primordial Matter Caster",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_CASTING,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_ASSEMBLY_LINE_MODULE",
                    "primordial_assembly_line_module",
                    "Primordial Assembly Line Module",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_ASSEMBLY_LINE_MODULE,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_MULTIDIMENSIONAL_IMPLOSION_CORE",
                    "primordial_multidimensional_implosion_core",
                    "Primordial Multidimensional Implosion Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_MULTIDIMENSIONAL_IMPLOSION,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_TIANQIONG_ASSEMBLY_CORE",
                    "primordial_tianqiong_assembly_core",
                    "Primordial Tianqiong Assembly Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_TIANQIONG_ASSEMBLY,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_WORLDLINE_TRAVERSAL_MATRIX",
                    "primordial_worldline_traversal_matrix",
                    "Primordial Worldline Traversal Matrix",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_WORLDLINE_TRAVERSAL,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_QUANTUM_DISTORTION_MATRIX",
                    "primordial_quantum_distortion_matrix",
                    "Primordial Quantum Distortion Matrix",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_QUANTUM_DISTORTION,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_SHAOGUANG_AGGREGATION_CORE",
                    "primordial_shaoguang_aggregation_core",
                    "Primordial Shaoguang Aggregation Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_SHAOGUANG_AGGREGATION,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_WEIYANG_RECONSTRUCTION_MODULE",
                    "primordial_weiyang_reconstruction_module",
                    "Primordial Weiyang Reconstruction Module",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_WEIYANG_RECONSTRUCTION,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    "PRIMORDIAL_ENGRAVING_MODULE",
                    "primordial_engraving_module",
                    "Primordial Engraving Module",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_ENGRAVING,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            new ModuleSpec(
                    // ⚠️ 上游 id 无 primordial_ 前缀（见句柄区注释）；中文名仍是「原初太虚宇宙锻炉」。
                    "TAIXU_SMELTING_FURNACE",
                    "taixu_smelting_furnace",
                    "Taixu Cosmic Forge",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_SMELTING,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            // ═════════ 2026-09-26 新增第 25 台：原初山海调试模块 ═════════
            // 中文名与 id 均由用户/队长亲定；英文名照其余 24 台的 .langValue() 格式。
            // 结构图案复用同一份 createPattern（与 24 台一致：上游所有原初模块共用同一份
            // forge_of_the_antichrist_module.bin 结构）。
            // recipeTypes 指向**本模块独有**的 RECIPE_DEBUG_MODULE（见上方长注释：不许共用）。
            new ModuleSpec(
                    "PRIMORDIAL_DEBUG_MODULE",
                    "primordial_debug_module",
                    "Primordial Debug Module",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_DEBUG_MODULE,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            // 🟢 2026-09-26 新增第 26 台（用户点单：世线裂解枢纽）。2026-09-29 追加第二个类型
            // ⇒ 现在挂【世线采样 ＋ 原初世线切割】两个（见上方 RECIPE_WORLDLINE_CRACKING_HUB 的注释）。
            new ModuleSpec(
                    "WORLDLINE_CRACKING_HUB",
                    "worldline_cracking_hub",
                    "Worldline Cracking Hub",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_WORLDLINE_CRACKING_HUB,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            // 🟢 2026-10-03 新增第 27 台（用户点单：原初碳基解构核心）。
            //   机器类 / 结构图案 / 并行表 / generator 四项**全部照抄第 26 台**
            //   （StandardPrimordialModule + createPattern + STANDARD + false）
            //   ⇒ 与用户原话「其余和其他模块一样」逐项对齐；唯一不同的只有 recipeTypes 那一个数组。
            new ModuleSpec(
                    "PRIMORDIAL_CARBON_DECONSTRUCTION_CORE",
                    "primordial_carbon_deconstruction_core",
                    "Primordial Carbon Deconstruction Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_CARBON_DECONSTRUCTION_CORE,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            // 🟢 2026-10-03 新增第 28 台（用户点单：原初引力干涉阵列）。
            //   机器类 / 结构图案 / 并行表 / generator 四项同样**全部照抄第 26、27 台**
            //   （StandardPrimordialModule + createPattern + STANDARD + false）；
            //   唯一不同的只有 recipeTypes 那一个数组（挂 [引力波宏观干涉 + 引力波广域广播]）。
            //   🔴 并行表选 STANDARD 的理由（不是随手填的）：
            //     ① 本台**没有上游同族原型**（上游 25 个模块里没有它，它是用户点单的新机器）
            //        ⇒ "照同族选"只能落到最近两次新增（第 26/27 台）的既有口径 = STANDARD；
            //     ② ENHANCED 这张表（表#1）在上游只有【物质重组核心 / 奇点反演核心 / 因果编织矩阵】
            //        三台用，本台不属于那三台中的任何一类语义 ⇒ 选 ENHANCED 等于**擅自给它加数值**
            //        （同一档物质模块下并行更高：如等级1 入门模块 ENHANCED 256 vs STANDARD 128）；
            //     ③ 它与其余 25 台模块同型（同类/同结构/同默认并行 64/非发电），没有任何一处
            //        需要走另一张表。见 PrimordialModuleMachine.ParallelTable 的档位说明。
            new ModuleSpec(
                    "PRIMORDIAL_GRAVITATIONAL_INTERFERENCE_ARRAY",
                    "primordial_gravitational_interference_array",
                    "Primordial Gravitational Interference Array",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_GRAVITATIONAL_INTERFERENCE_ARRAY,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false),
            // 🟢 2026-10-03 新增第 29 台（用户点单：原初深空汲取核心）。
            //   机器类 / 结构图案 / 并行表 / generator 四项同样**全部照抄第 26、27、28 台**
            //   （StandardPrimordialModule + createPattern + STANDARD + false）；
            //   唯一不同的只有 recipeTypes 那一个数组
            //   （挂 [集气室 + 大型集气室 + 虚空聚流反应 + 虚空流体钻机]，顺序 = 用户点名的先后）。
            //   🔴 并行表选 STANDARD 的理由（不是随手填的，与第 28 台同款）：
            //     ① 本台**没有上游同族原型**（上游 25 个模块里没有它，它是用户点单的新机器）
            //        ⇒ "照同族选"只能落到最近三次新增（第 26/27/28 台）的既有口径 = STANDARD；
            //     ② ENHANCED 这张表（表#1）在上游只有【物质重组核心 / 奇点反演核心 / 因果编织矩阵】
            //        三台用，本台的四个类型（集气/钻探/虚空聚流）不属于那三台中的任何一类语义
            //        ⇒ 选 ENHANCED 等于**擅自给它加数值**
            //        （同一档物质模块下并行更高：如等级1 入门模块 ENHANCED 256 vs STANDARD 128）；
            //     ③ 它与其余 26 台模块同型（同类/同结构/同默认并行 64/非发电），没有任何一处
            //        需要走另一张表。见 PrimordialModuleMachine.ParallelTable 的档位说明。
            new ModuleSpec(
                    "PRIMORDIAL_DEEP_SPACE_EXTRACTION_CORE",
                    "primordial_deep_space_extraction_core",
                    "Primordial Deep Space Extraction Core",
                    StandardPrimordialModule::new,
                    PrimordialMatterRecombinatorCoreStructure::createPattern,
                    () -> RECIPE_DEEP_SPACE_EXTRACTION_CORE,
                    PrimordialModuleMachine.ParallelTable.STANDARD,
                    false)
            // 0 个待后续阶段：guide 权威清单的 25 个模块里，铸币厂（primordial_coin_forge）已被用户删除，
            // 其余 24 个（物质重组核心 + 23 个）全部在本表注册。加一个模块仍然只需"这里加一行 + 句柄区加一个字段
            // + init() 末尾 require 一次"，注册循环与主机侧都不用动。
            //
            // 🟢 2026-10-03 再 +1 ⇒ 本表【27 条】（第 27 台 = 原初碳基解构核心，见上面那条）。
            //    ⚠️ 台数的**唯一可读真源**是 SPECS.size()：init() 里那条日志与并行表归属统计
            //       早就改成由 SPECS.size() 提供（2026-09-26 那次订正，见上面"硬编码数字一定会再次过期"）。
            //        ⇒ 本注释里的数字只作考古，**不要**在日志/断言里再抄一份。
            // 🟢 2026-10-03 同日再 +1 ⇒ 本表【28 条】（第 28 台 = 原初引力干涉阵列，
            //    挂 [引力波宏观干涉 + 引力波广域广播]，并行表 STANDARD，见上面那条）。
            // 🟢 2026-10-03 同日再 +1 ⇒ 本表【29 条】（第 29 台 = 原初深空汲取核心，
            //    挂 [集气室 + 大型集气室 + 虚空聚流反应 + 虚空流体钻机]，并行表 STANDARD，
            //    见上面那条；它的类型数组走 buildDeepSpaceExtractionCoreRecipeTypes() 的 fail-fast）。
            //    ⇒ 并行表归属随之由「28 台 = ENHANCED 3 / STANDARD 25」变成
            //      「29 台 = ENHANCED 3 / STANDARD 26」（那条日志由 SPECS.size() 现算，不用手改）。
    );

    /** 句柄名 → 已注册的机器定义。 */
    private static final Map<String, MultiblockMachineDefinition> BY_HANDLE = new LinkedHashMap<>();

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private ModuleRegistry() {}

    // ═════════════════════════════ 注册 ═════════════════════════════

    /** 注册全部原初模块。幂等：重复调用直接返回（初始化链只会调一次，多调也不会重复注册）。 */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        for (ModuleSpec spec : SPECS) {
            BY_HANDLE.put(spec.handleName(), register(spec));
        }
        // ───── 2026-09-25 路线 ①：并行槽 fail-fast + 一条可机器核对的日志 ─────
        // 🔴 为什么放在这里：两张并行表是 17 个字符串 id 写的，拼错一个字的后果是【静默退回基础 64】。
        //    本调用把它变成加载期抛异常；日志行（字面量 [SHANHAI-SPEC] 并行槽自检）可在
        //    无头专服冒烟的 latest.log 里直接 grep 到 ⇒ 不需要人眼看。
        PrimordialModuleMachine.assertParallelTablesConsistent();
        {
            int enhanced = 0;
            int standard = 0;
            for (ModuleSpec spec : SPECS) {
                switch (spec.parallelTable()) {
                    case ENHANCED -> enhanced++;
                    case STANDARD -> standard++;
                }
                // 🔴 2026-09-26 新增 fail-fast：**逐台**核它声明的表真的可用。
                //   为什么不能只靠 assertParallelTablesConsistent()：那个查的是"表本身"（两张表各 17 项），
                //   查不到"某一台指向了哪张表"。2026-09-26 之前这里有过一个"无表档"（BASE），
                //   它的后果是那台机器并行恒为 64、而 tooltip 上却写着"放入物质模块可提高"（活的假数据）。
                //   现在档位删了 ⇒ 表永远存在；本循环把"将来多出一台无表机器 / 一张空表"
                //   变成【注册期异常，并且指名是哪一台】。
                PrimordialModuleMachine.assertParallelTableUsable(spec.path(), spec.parallelTable());
            }
            if (enhanced + standard != SPECS.size()) {
                throw new IllegalStateException("[SHANHAI-PARALLEL] 并行表归属漏了机器："
                        + enhanced + "+" + standard + " != " + SPECS.size()
                        + "（ParallelTable 里只有这两档；若新增档位，必须在这里一并统计，"
                        + "否则会有一台模块静默不入账）");
            }
            // 🔴 正面对照：先证明检查器【对已知为坏的输入真的会抛】，再采信它在真实输入上的"通过"。
            //   本项目踩过约 10 次"检查器自己错了"（永远不抛的检查器与永远通过的检查器日志上长得一样）。
            PrimordialModuleMachine.selfTestParallelTableChecker();
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] 并行槽自检通过：表#1(ENHANCED) {} 项 / 表#2-3(STANDARD) {} 项；"
                            // 🔴 2026-09-26：这里原本是硬编码的「24 台归属」。新增第 25 台（调试模块）后，
                            //    那句话会打印成「24 台归属 = ENHANCED 3 / STANDARD 22」——三数自相矛盾，
                            //    是【活的假数据】。改为由 SPECS.size() 提供，跟机器数量自动一致。
                            //    （台数变化时这句话不许再手改：硬编码数字一定会再次过期。）
                            + "{} 台归属 = ENHANCED {} / STANDARD {} / 无表档 0（BASE 档已于 2026-09-26 删除："
                            + "发电模块也走 STANDARD）；基础并行 {}（空槽）",
                    PrimordialModuleMachine.parallelTableSize(PrimordialModuleMachine.ParallelTable.ENHANCED),
                    PrimordialModuleMachine.parallelTableSize(PrimordialModuleMachine.ParallelTable.STANDARD),
                    SPECS.size(), enhanced, standard, PrimordialModuleMachine.DEFAULT_PARALLEL);
        }
        // ───── 🆕 2026-09-30：热力白名单自检（**注册期硬拦**，不是装饰） ─────
        //
        //  背景：用户选择题答案（逐字）**「B. 只留那三台」** ⇒ 哪几台的槽能充当【热力源】
        //  由一张 **id 白名单**（ShanhaiHeatGate.HEAT_SLOT_MACHINE_IDS）决定。
        //  🔴 按 id 决定行为是一条**会静默失效**的写法：白名单里写错一个字 / 那台机器被改名，
        //     结果是玩家**看不到热力效果**（线圈/容器放进去像空气），而日志里一个字都不会有
        //     —— 与"这台本来就不吃热力"完全同形。
        //  ⇒ 所以这里把"三个 id 必须逐个能在已注册的模块里找到"变成**注册期异常**：
        //     漏了会【报错】，不会【默默失效】。
        //
        //  🔴 2026-10-03 文案订正（**只改字：下面的判据、调用、实参一字未动**）：
        //     旧文案写的是「{} 台会显示恒星热力槽」—— 那个**单独的**恒星热力槽已按用户同日
        //     规格②删除，槽位合并进【每一台模块都有的】额外挂载槽 ×3
        //     （见 ShanhaiHeatGate 类注释 §1、PrimordialModuleMachine#extraMountSlots、
        //      以及 ShanhaiHeatGate 里 HEAT_SLOT_MACHINE_IDS 的现行语义注释）。
        //     ⇒ 现在**每一台**模块都看得见那一格，白名单管的不再是"这一格显不显示"，
        //       而是"这一格里的线圈 / 恒星热力容器算不算热力源"。
        //     旧文案在新机制下是**活的假数据**（它会让读日志的人以为只有 3 台有那一格），故改。
        //
        //  时机：本方法 = ShanhaiMod(@Mod 构造器) → ShanhaiRegistration.register(bus) → ShanhaiRegistry.init()
        //        的第 ③ 段（见本类类注释的初始化链）。此刻 SPECS 已可读、机器即将注册
        //        ⇒ 是"能最早发现、且一定先于任何玩家进游戏"的那个点。
        //  判据：已注册 id 的取值口径 = "shanhai:" + SPECS.path()，与
        //        PrimordialModuleMachine#shanhai$machineId()（方块注册表键）同源
        //        —— 旁证：方块 lang 键全部是 block.shanhai.<path>。
        {
            final List<String> registeredIds = new ArrayList<>(SPECS.size());
            for (ModuleSpec spec : SPECS) {
                registeredIds.add("shanhai:" + spec.path());
            }
            final String heatSlotProblem = ShanhaiHeatGate.verifyMachineIds(registeredIds);
            if (heatSlotProblem != null) {
                throw new IllegalStateException(heatSlotProblem);
            }
            ShanhaiMod.LOGGER.info("[SHANHAI-HEATSLOT] 热力白名单自检通过：{} 台模块里有 {} 台的【额外挂载槽】"
                            + "可充当热力源（线圈 / 恒星热力容器，同一格要放满 {} 个）= {}"
                            + "（用户 2026-09-30 选择题答案「B. 只留那三台」；2026-10-03 起那一格 = 额外挂载槽 ×3，"
                            + "单独的恒星热力槽已删除、其判定已改读 extraMountSlots）；"
                            + "其余 {} 台的额外挂载槽不充当热力源（那一格它们照样有，只是不吃热力）。",
                    registeredIds.size(),
                    ShanhaiHeatGate.HEAT_SLOT_MACHINE_IDS.size(),
                    ShanhaiHeatGate.REQUIRED_COUNT,
                    ShanhaiHeatGate.HEAT_SLOT_MACHINE_IDS,
                    registeredIds.size() - ShanhaiHeatGate.HEAT_SLOT_MACHINE_IDS.size());
        }
        // ───── 🆕 2026-10-03：第 29 台（原初深空汲取核心）的【配方类型自检 + 正面对照】 ─────
        //
        //  背景：本台挂的 4 个类型里，3 个读的是 gtceu / gtlcore 的**静态字段**、
        //  1 个读的是 gtladditions 的 Kotlin object getter（GtlAddCompat.voidfluxReaction()）。
        //  数组在**类加载期**就填好了（见 RECIPE_DEEP_SPACE_EXTRACTION_CORE 的长注释），
        //  而"某一条没拿到 ⇒ null"在日志里与"拿到了"长得一模一样 —— 本工程最忌讳这类失败。
        //
        //  🔴 检查分两拍，缺一拍都会退化成装饰：
        //    ① 【异常】由 requireNoNullRecipeTypes 在 buildDeepSpaceExtractionCoreRecipeTypes()
        //       里当场抛（null 永远进不了那个字段）；
        //    ② 【读数 + 正面对照】就是本段 —— 光有①的话，日志里没有任何"它真的查过"的痕迹，
        //       别人删掉①你也不会发现。故这里既打印实际读到的 4 条 id，
        //       又跑一次 selfTestRecipeTypeChecker() 证明检查器**对已知为坏的输入真的会抛**。
        {
            final GTRecipeType[] deepSpace = RECIPE_DEEP_SPACE_EXTRACTION_CORE;
            final StringBuilder readIds = new StringBuilder();
            for (int i = 0; i < deepSpace.length; i++) {
                if (i > 0) {
                    readIds.append(" / ");
                }
                readIds.append(i).append("=")
                        .append(deepSpace[i] == null ? "null" : deepSpace[i].registryName);
            }
            selfTestRecipeTypeChecker();
            ShanhaiMod.LOGGER.info("[SHANHAI-DEEPSPACE] 配方类型检查器正面对照通过（对已知为坏的输入确实抛异常）；"
                            + "本机配方类型自检：{} 条全部非 null = {}",
                    deepSpace.length, readIds);
        }
        // ───── fail-fast：句柄为 null 绝不允许流进主机的 pattern() ─────
        PRIMORDIAL_MATTER_RECOMBINATOR_CORE = require("PRIMORDIAL_MATTER_RECOMBINATOR_CORE");
        PRIMORDIAL_CRITICAL_PROCESSING_MODULE = require("PRIMORDIAL_CRITICAL_PROCESSING_MODULE");
        PRIMORDIAL_COSMIC_REACTOR = require("PRIMORDIAL_COSMIC_REACTOR");
        PRIMORDIAL_MOLECULAR_RIFT_CORE = require("PRIMORDIAL_MOLECULAR_RIFT_CORE");
        PRIMORDIAL_SUPERCRITICAL_MATTER_GENERATION_CORE =
                require("PRIMORDIAL_SUPERCRITICAL_MATTER_GENERATION_CORE");
        PRIMORDIAL_ETERNAL_SMELTING_FURNACE = require("PRIMORDIAL_ETERNAL_SMELTING_FURNACE");
        // ───── 2026-09 补齐的 18 个模块句柄（顺序 = SPECS 顺序，缺一个就在加载期带 id 炸掉） ─────
        PRIMORDIAL_VOID_INDUCTION_ARMATURE = require("PRIMORDIAL_VOID_INDUCTION_ARMATURE");
        PRIMORDIAL_BIOLOGICAL_CORE = require("PRIMORDIAL_BIOLOGICAL_CORE");
        PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE =
                require("PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE");
        PRIMORDIAL_CAUSAL_WEAVING_MATRIX = require("PRIMORDIAL_CAUSAL_WEAVING_MATRIX");
        PRIMORDIAL_SINGULARITY_INVERSION_CORE = require("PRIMORDIAL_SINGULARITY_INVERSION_CORE");
        PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR = require("PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR");
        PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE = require("PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE");
        PRIMORDIAL_DIVERGENCE_GENERATOR = require("PRIMORDIAL_DIVERGENCE_GENERATOR");
        PRIMORDIAL_MATTER_CASTER = require("PRIMORDIAL_MATTER_CASTER");
        PRIMORDIAL_ASSEMBLY_LINE_MODULE = require("PRIMORDIAL_ASSEMBLY_LINE_MODULE");
        PRIMORDIAL_MULTIDIMENSIONAL_IMPLOSION_CORE = require("PRIMORDIAL_MULTIDIMENSIONAL_IMPLOSION_CORE");
        PRIMORDIAL_TIANQIONG_ASSEMBLY_CORE = require("PRIMORDIAL_TIANQIONG_ASSEMBLY_CORE");
        PRIMORDIAL_WORLDLINE_TRAVERSAL_MATRIX = require("PRIMORDIAL_WORLDLINE_TRAVERSAL_MATRIX");
        PRIMORDIAL_QUANTUM_DISTORTION_MATRIX = require("PRIMORDIAL_QUANTUM_DISTORTION_MATRIX");
        PRIMORDIAL_SHAOGUANG_AGGREGATION_CORE = require("PRIMORDIAL_SHAOGUANG_AGGREGATION_CORE");
        PRIMORDIAL_WEIYANG_RECONSTRUCTION_MODULE = require("PRIMORDIAL_WEIYANG_RECONSTRUCTION_MODULE");
        PRIMORDIAL_ENGRAVING_MODULE = require("PRIMORDIAL_ENGRAVING_MODULE");
        TAIXU_SMELTING_FURNACE = require("TAIXU_SMELTING_FURNACE");
        // ───── 2026-09-26 新增第 25 台（原初山海调试模块） ─────
        PRIMORDIAL_DEBUG_MODULE = require("PRIMORDIAL_DEBUG_MODULE");
        // ───── 🟢 2026-09-26 新增第 26 台（世线裂解枢纽）─────
        WORLDLINE_CRACKING_HUB = require("WORLDLINE_CRACKING_HUB");
        // ───── 🟢 2026-10-03 新增第 27 台（原初碳基解构核心）─────
        PRIMORDIAL_CARBON_DECONSTRUCTION_CORE = require("PRIMORDIAL_CARBON_DECONSTRUCTION_CORE");
        // ───── 🟢 2026-10-03 新增第 28 台（原初引力干涉阵列）─────
        PRIMORDIAL_GRAVITATIONAL_INTERFERENCE_ARRAY =
                require("PRIMORDIAL_GRAVITATIONAL_INTERFERENCE_ARRAY");
        // ───── 🟢 2026-10-03 新增第 29 台（原初深空汲取核心）─────
        PRIMORDIAL_DEEP_SPACE_EXTRACTION_CORE =
                require("PRIMORDIAL_DEEP_SPACE_EXTRACTION_CORE");
    }

    private static MultiblockMachineDefinition register(ModuleSpec spec) {
        // 🔴 2026-09-25（任务 A/B）：配方类型数组只取【一次】，同时喂给 .recipeTypes(...) 与 tooltip。
        //    两处各自 spec.recipeTypes().get() 会调用两次 Supplier —— 而其中几条是
        //    GtlAddCompat.xxx()（转发到 gtladditions 的 Kotlin object getter），
        //    取一次比取两次安全，也保证"tooltip 里列的类型"与"机器真正挂的类型"是同一个数组。
        final GTRecipeType[] recipeTypes = spec.recipeTypes().get();
        return ShanhaiRegistration.REGISTRATE
                .multiblock(spec.path(), spec.factory())
                // 朝向：ALL（6 面全开）。2026-xx 用户拍板放开 Y 轴 —— 主机 16 个模块位里有 8 个
                //   （row=0 / row=28 那两圈）只有「模块朝 DOWN / 朝 UP」才对得上，NON_Y_AXIS 会让这 8 个槽位装不上。
                // ⚠️ 放开朝向只解决「能放下」，不解决「放下了会不会成型」：竖直摆放的图案轴解算走
                //   BlockPattern 的 UP/DOWN 分支（用 upwardsFacing 而非 frontFacing），风险见类注释与交付报告。
                // builder 上只有 rotationState(RotationState) 这一个朝向 API（没有 allRotation()/nonYAxisRotation()），
                //   RotationState 的四个常量 ALL/NONE/Y_AXIS/NON_Y_AXIS 已用 javap 复核。
                .rotationState(com.gregtechceu.gtceu.api.data.RotationState.ALL)
                .tier(MODULE_MACHINE_TIER)
                .recipeTypes(recipeTypes)
                // 🔴🔴 2026-09-25 路线 ①：**这里曾经是「64 变 1」的第一处病根，历史留档不许再犯**
                //   ⛔ 旧原文（作废）：
                //       .recipeModifier(spec.usesParallelModifier()
                //               ? ModuleRegistry::applyModuleRecipeModifierWithParallel
                //               : ModuleRegistry::applyModuleRecipeModifierWithoutParallel)
                //   ⛔ 它配合下面 step ① 的 `instanceof PrimordialMatterRecombinatorCore` 守卫，
                //      让另外 23 台**整段跳过并行** ⇒ GT 侧并行恒为 1。
                //      用户在游戏里看到「最大并行数: 1」并当场指出「不加任何物质模块它本身就有 64 的并行数」。
                //   ✅ 现在 24 台**全部**走带并行的同一条链；每台用哪张表由 spec.parallelTable() 决定
                //      （在 PrimordialModuleMachine#scanMatterSlot 里读取），
                //      **不再用 instanceof 去判断"是哪一台"** —— 那正是当年漏掉 23 台的写法。
                //   组合方法内部顺序写死为：并行 → 产出倍率(N3) → 耗时减免(N5) → 下限夹取(N6) → 耗能减免(N5)。
                //   不传 null：builder 对 null 的行为没有实据，宁可给一个真实的修饰器。
                .recipeModifier(ModuleRegistry::applyModuleRecipeModifierWithParallel)
                // 🔴 2026-09-24（任务 B）：发电模块（全 24 台里只有原始真空零点能发生器）必须
                //    显式声明 .generator(true)，否则 MultiblockMachineDefinition.isGenerator() 为 false，
                //    WorkableElectricMultiblockMachine#getMaxVoltage() 会去读「输入最高压」——
                //    而一台只挂输出仓的发电模块输入侧为空 ⇒ 电压口径为 0。
                //    范本：GTCEu GTMachines.LARGE_COMBUSTION_ENGINE（.generator(true) ＋ 一格 OUTPUT_ENERGY）。
                //    ⚠️ 非发电模块【必须】传 false：isGenerator() 为真会改变 getMaxVoltage()/tier 的取值分支。
                .generator(spec.generator())
                .appearanceBlock(GTBlocks.BRONZE_HULL)
                .pattern(spec.pattern())
                // 贴图借用原版现成资产（bronze 外壳 + steam_grinder 控制器模型）。
                // 阶段 2 有自有资产后换成 shanhai:block/multiblock/... —— 改了只影响外观，不影响结构与闭环。
                // 用 GTCEu.id(...) 而不是 new ResourceLocation("gtceu", ...)：后者在 Forge 47.4 里已是
                // 「deprecated for removal」（见 javac -Xlint:removal）。
                .workableCasingRenderer(
                        GTCEu.id("block/casings/steam/bronze/side"),
                        GTCEu.id("block/multiblock/steam_grinder"))
                .langValue(spec.langValue())
                // 🔴 2026-09-25（任务 A + B）：物品 tooltip 的属性行 —— **24 台模块统一走这一处**
                //   （唯一实现在 com.shanhai.machine.MachineTooltips，不许在别处再写第二份）。
                //   机制照上游 gtladditions：注册期把 Component 交给 MultiblockMachineBuilder.tooltips(...)
                //   （证据见 MachineTooltips 类注释：GTLAddMultiBlockMachineBuilder 的 tooltipText* 系列
                //    全部只是 super.tooltips(...) 的薄包装）。
                //   行序（用户 2026-09-25 选的「候选 A + 候选 C」）=
                //     描述行 → 最大并行数(基础值) → 「谁能提高它」一行
                //     → 跨配方线程数 → 「谁能提高它」一行 → 可用配方类型；
                //   GTCEu 自己会在【这些行之后】追加 id 与 mod 名，所以"mod 名永远在最后"这一条天然成立。
                //   ⚠️ 取值口径（🔴 2026-09-25 路线 ① 后全部是【真实值】）：
                //     · 最大并行数 = 每台自己的基础值（空槽 = PrimordialModuleMachine.DEFAULT_PARALLEL
                //       = 64，上游 30 台同值）。静态物品 tooltip 画不出"槽里现在放的是哪个模块"，
                //       所以如实标注为「基础值」，并单起一行说明**谁能让它涨**（照图2 的形态）。
                //     · 跨配方线程数 = 1（用户原话：「现在模块是没有跨配方线程，后续我会加，
                //       你现在可以写跨配方线程数为 1」）。单一来源 = CROSS_RECIPE_THREADS；
                //       同样单起一行说明谁能让它涨、并标明本阶段尚未生效（不编数字）。
                .tooltips(MachineTooltips.forModule(
                        spec.path(),
                        String.valueOf(PrimordialModuleMachine.DEFAULT_PARALLEL),
                        String.valueOf(PrimordialModuleMachine.CROSS_RECIPE_THREADS),
                        recipeTypes))
                .register();
    }

    // ═════════════════════════════ 模块侧的 recipeModifier 组合 ═════════════════════════════

    /**
     * <b>模块侧唯一的配方修饰入口（24 台全走这条）</b>。
     *
     * <p>{@link com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifier} 是<b>单函数接口</b>
     * （{@code javap} 只有一个抽象方法 {@code apply(MetaMachine, GTRecipe, OCParams, OCResult)}），
     * 框架<b>没有 chain 契约</b>，所以「先并行 → 后倍率」的组合必须自己写 —— 就是本方法。
     *
     * <h2>⛔ 2026-09-25 路线 ①：另一个入口 {@code …WithoutParallel} 已【删除】，不是改名</h2>
     * 旧代码里有两个入口：
     * <pre>
     *   applyModuleRecipeModifierWithParallel(…)    → …, true     // 只有物质重组核心
     *   applyModuleRecipeModifierWithoutParallel(…) → …, false    // 另外 23 台 ⇒ 并行恒 1
     * </pre>
     * 那个 {@code WithoutParallel} 就是「64 变 1」的入口 —— <b>把它留着，下次还会有人顺手用它</b>。
     * 所以连同 {@code applyModuleRecipeModifier} 的第 5 个参数 {@code boolean withParallel} 一起删掉：
     * 现在<b>不存在</b>"一条不带并行的模块配方链"这个概念。
     */
    public static GTRecipe applyModuleRecipeModifierWithParallel(MetaMachine machine, GTRecipe recipe,
                                                                 OCParams params, OCResult result) {
        return applyModuleRecipeModifier(machine, recipe, params, result);
    }

    /**
     * <b>模块侧唯一的配方修饰链</b>（顺序写死，不许换）：
     * <pre>
     *   ① 并行      24 台全部（上限来自每台自己的表；2026-09-25 路线 ① 之前只有物质重组核心这一步）
     *   ② 产出倍率  N3 —— 倍率源 = **主机的**门控等级 moduleSlotBonus()
     *   ③ 耗时减免  N5 —— f = 1 − 0.95^(17/等级)（= 1 − 减免比例），等级 ≤ 0 已在 reductionFactor 里短路成 1.0
     *   ④ 保底+天花板 N6 —— min(duration, max(1, round(duration × f)))  ⇒ ⛔ 下限项已删（2026-09-26 用户裁决）
     *   ⑤ 耗能减免  N5 —— 与耗时**独立**的第二个旋钮，乘 f 并钳 ≥ 1 EU/t
     * </pre>
     *
     * <h2>🔴 主机 vs 模块的不对称（2026-09 队长裁决；改动任一侧都要同步改另一处的注释）</h2>
     * <pre>
     *   主机（生产路径 = {@code PrimordialEngineRecipeLogic#buildFinalNormalRecipe}；本行即 A 口径）：
     *       时长【下限】recipe.duration = max(1, dx, getLimitedDuration())，dx = 整批总能量 ÷ 最大电压 —— 不吃 N5 时长减免
     *       耗电【吃】N5 减免：EUt ×= f
     *   模块（本方法）：
     *       时长 = min(原时长, max(1, round(duration × f)))            —— 吃 N5 时长减免；⛔ 下限项已删
     *       耗电【吃】N5 减免：EUt ×= f（钳 ≥ 1 EUt）
     * </pre>
     * ⛔ <b>2026-09-26 订正（用户裁决「连功能一起删」）：下面这段"模块侧必须保留下限语义"已作废，
     * 原文保留不改。</b>旧理由说的是「否则 N5 的耗时旋钮会被抹平，玩家那个『配方最短耗时』旋钮在
     * 模块上失效」—— 而<b>那个旋钮 2026-09-22 就从模块侧摘掉了</b>，用户 2026-09-26 追加确认
     * 「我们当时确实把模块的最小配方耗时给去除了」⇒ <b>被托住的一头不存在，下限随之删除</b>。
     * 模块侧现行只剩「≥1 tick 保底」+「永不变长天花板」。
     * <p>⛔ 原文（作废）：模块侧<b>必须</b>保留下限语义：模块侧的「配方最短耗时」是<b>它自己那一份</b>
     * （{@code LimitedDurationConfigurator} 已挂到模块 GUI，见
     * {@code PrimordialModuleMachine#attachConfigurators}），语义就是那个<b>下限</b>。
     * 若模块也改成恒等，N5 的耗时旋钮会连同 N6 一起被抹平，玩家直接失去「时长」这一个旋钮。
     * <p><b>可实机对账的判据（2026-09-26 更新）</b>：同一台主机 + 同一配方，专属槽【空】vs【满 64 个同种模块】
     * ⇒ 主机与模块的<b>耗电都应差 f 倍</b>（Lv.1 ⇒ f = 1 − 0.95^17 ≈ 0.5819，即约 0.5819 倍；
     * Lv.17 ⇒ f = 0.05）；
     * <b>主机的耗时不吃 f</b>（= N6 的 A 下限 max(1, dx, 下限)，会随批量/原时长变），模块的耗时则按 {@code min(原时长, max(1, round(d0 × f)))} 变
     * —— <b>不再有"至少 20 tick"</b>，所以<b>短配方会真的变快</b>（这正是本次删下限的直接后果）。
     *
     * <h2>为什么倍率/减免都取「主机的」门控等级</h2>
     * 用户原话是「<b>主机的</b>每级物质模块提供…减免」，随后澄清「耗时减免是对于模块的，对主机没有影响，
     * 因为它已经是无限了」⇒ 队长的解读（本实现照此）：<b>主机专属槽里那个模块的等级决定一个系数，
     * 作用于主机上的所有模块</b>。注意后半句在 2026-09 被用户更完整的口径覆盖了一部分：
     * <b>主机也吃这个系数的「耗电」半边</b>（用户原话「主机耗电吃N5减免」），只是不吃「时长」那半边。
     * <p>⇒ 同一台主机下的多台模块<b>必然</b>共用同一个系数（倍率各乘一次、<b>不叠乘</b>），
     * 因此不存在「同主机上多个模块系数不一」的冲突。
     * <p>模块自己的那个物质模块槽（{@code PrimordialModuleMachine#matterModuleSlot}）管的是
     * <b>它自己的并行档位</b>，是另一个旋钮，两者不打架。
     * <p>主机不在线（{@code getHost() == null}）⇒ 门控等级按 0 处理（倍率 ×1、系数 1.0）：
     * <b>宁可不放大，也不猜一个数</b>。
     * <p>⚠️ 注意 <b>N6 下限已于 2026-09-26 整体删除</b>（用户裁决「连功能一起删」）——
     * 原文（作废）写的是「2026-09-21 起下限取模块自己的 {@code getLimitedDuration()}（模块侧栏那个旋钮），
     * 与主机是否在线无关」；现在那个字段与旋钮都已不存在，本段只作考古。
     */
    private static GTRecipe applyModuleRecipeModifier(MetaMachine machine, GTRecipe recipe,
                                                      OCParams params, OCResult result) {
        if (!(machine instanceof PrimordialModuleMachine module)) {
            // 防御：这个修饰器只挂在模块定义上；落到别的机器就原样放行。
            return recipe;
        }
        PrimordialOmegaEngineMachine host = module.getHost();
        GTRecipe modified = recipe;

        // ① 并行
        // 🔴🔴 2026-09-25 路线 ①：**这里曾经是「64 变 1」的第二处（也是最后一处）病根**
        //   ⛔ 旧原文（作废）：
        //       // ① 并行（仅物质重组核心；上限来自它自己的「物质模块 → 并行」表）
        //       if (withParallel && module instanceof PrimordialMatterRecombinatorCore core) {
        //           modified = PrimordialRecipeEffects.applyParallel(modified, core, core.parallelCap());
        //       }
        //   ⛔ 后果：非核心模块这一条件**恒不成立** ⇒ 整段并行被跳过 ⇒ GT 侧 recipe.parallels 保持 1
        //      （用户在游戏里量到的就是「最大并行数: 1」；上游那 23 台本该是"空槽 64 + 自己那张表"）。
        //   ✅ 现在：`withParallel` 这个开关已随旧字段一起删除，**24 台一律走这里**；
        //      每台的上限由它自己的表算出（基类 PrimordialModuleMachine#parallelCap()，
        //      表的选择在 spec.parallelTable()）。**不要再用 instanceof 去挑机器** ——
        //      那正是当年漏掉 23 台的写法。
        //   🔴 2026-09-26：上限实参由 int 的 parallelCap() 改成 long 的
        //      PrimordialModuleMachine#getRecipeLogicMaxParallel()（= Math.max(1L, getCurrentParallel())，
        //      与老山海逐字同源）。这就是任务书点名的那个"int 卡点"：
        //      applyParallel(…, int limit) 是 gtceu 原版签名，装不下并行表末三档的
        //      4.6e18 / 6.9e18 / Long.MAX ⇒ 现在改走同名的 long 重载
        //      （PrimordialRecipeEffects，≤ 21 亿 时逐字转交 int 版 ⇒ 前 14 档行为不变）。
        //
        //   🔴🔴 2026-09-30：实参再改成【并行预算 = 表值 × 跨配方线程数】—— 用户报的
        //      「零点能反应堆不吃跨配方并行，那个发电量都没加」的根因就在这一行。
        //   · 病根：那台「原始真空零点能发生器」是全 24 台里【唯一】被 shanhai$resolveRouting()
        //     判定为 isGenerator() 的机器，它 setUseMultipleRecipes(false) ⇒ 【退回原生链】；
        //     而引擎路径那一边（PrimordialModuleRecipeLogic#calculateParallels()）用的是
        //     totalParallelLimitFor(getCurrentParallel(), getMultipleThreads()) = 表值 × 线程数。
        //     ⇒ 两条路对"跨配方线程"的态度相反：23 台吃，唯一那台发电的不吃。
        //   · 证据：用户两张图同一台机器，线程槽 1 个 → 121 个（30× 世线残片·共鸣），
        //     产能恒为 92.23E EU/t = 2^31 × 2147483647 ÷ 0.05 —— 那个 2147483647 就是
        //     「永恒物质模块」的表值本身，里面没有线程因子的任何痕迹。
        //   · 恒等保证（为什么这个改动对"没放残片"的场合逐值不变）：
        //     ShanhaiParallelBudget.totalParallelLimitFor(x, 1) == max(1L, x) == 改动前的实参，
        //     对任意 x 成立（加载期自检 [SHANHAI-PARALLEL-BUDGET] 逐档断言它）。
        //   · 影响面：本行属于【原生链】的 @RecipeModifier，而原生链只有 isGenerator() 那台走
        //     （bytecode：MutableRecipesLogic.findAndHandleRecipe 在 useMultipleRecipes 为真时走
        //      findAndHandleMultipleRecipe，其常量池里没有任何 RecipeModifier / fullModifyRecipe 引用）
        //     ⇒ 另外 23 台一个字节的行为都不变。
        //   🔴 2026-09-30（同日第二轮）：把预算【先算进一个局部变量】再喂 applyParallel ——
        //      因为下面 ④ 之后的「并行进 long 档 ⇒ 时长下限 10 tick」必须用**同一个数**判定。
        //      【为什么用"预算"而不是"实际吃到的并行"】后者被输入量钳位（箱里有多少料 ÷ 每份用量），
        //      随箱子剩多少跳变；用它会让下限时灵时不灵。预算才是"这台机器的并行档位"。
        // 🔴 2026-10-02 第三轮（用户裁决：换成【总量】口径）：本行调的公式已从
        //    min(本机上限 × T, 电力上限 × T) 改成 min(本机上限, 电力上限 ÷ T)。
        //    ⚠️ 本行属于【原生链】（全 24 台里只有被判定为发电的那台走），而这条路
        //    【没有第二处再乘 T】⇒ 改后发电机拿到的并行不再随线程数放大：
        //    T = 121、没有电力上限时 旧 = 2147483647 × 121 = 259845521287 → 新 = 2147483647。
        //    这是本轮最大的【真实数值变更】，正是 2026-09-30「零点能反应堆吃跨配方并行」
        //    那条已验收修复所依赖的那个乘积 ⇒ 已显式上报待裁（交付报告 §11.4），不擅自回改。
        //
        // 🔴🔴 2026-10-02 第五轮（用户裁决 ②「保留本机上限 × T，不动」）：
        //    【结论】上面那条"改成 ÷T"的写法已经改回来了 —— 本行现在**不是** parallelBudget
        //    （那个函数在第四轮起返回"每线程上限"，本行要的是**总量**），而是
        //    totalParallelLimitFor(本机上限, T) = 本机上限 × T。
        //
        //    【为什么这里【故意不统一口径】—— 别再"顺手统一"掉，那会弄坏一条已验收的修复】
        //    ① 消费方不同：本行喂的是 PrimordialRecipeEffects.applyParallel(recipe, module, limit)，
        //       那个实参语义是【这台机器这一轮一共允许多少份并行】（= 总量口径），
        //       与引擎父类 MutableRecipesLogic.calculateParallels() 里的
        //       `(long) getMaxParallel() * getMultipleThreads()` 是同一个东西；
        //       而接口层 ParallelOverrideMachine#applyEnergyCap 输出的是【每个跨配方线程】的上限。
        //       两者【差一个 × T】，形状相同而含义不同 —— 这正是"统一"会出事的地方。
        //    ② 这条乘积是 2026-09-30 已验收修复的全部内容：用户报「零点能反应堆不吃跨配方并行，
        //       那个发电量都没加」的根因就是本行当时【没有】乘 T；修好之后 30 枚共鸣残片
        //       （T = 121）才真的把并行从 2147483647 抬到 259845521287。
        //       用户在 2026-10-02 第五轮明确裁决：**保留这个乘积**。
        //    ③ 恒等保证（数值上可离线断言，见判据 B 段）：
        //       · 电力自动【关着】时（= 9/30 验收时的状态，也是发电那台的常态：它 setUseMultipleRecipes(false)
        //         ⇒ 根本不会走 calculateParallels() ⇒ energyParallel 恒为 ENERGY_CAP_NONE）
        //         getCurrentParallel() 不做任何钳制 ⇒ 本行 = 表值 × T，与验收时逐位相同；
        //       · 🔴🔴 2026-10-02 第六轮（用户裁决 ①）：上面那句"energyParallel 恒为 ENERGY_CAP_NONE"
        //         是一条【推断】，用户明确要求<b>不依赖它</b> ——「不管'它电上限是否恒为 0'那个推断对不对，
        //         都让它不受电上限钳制」。⇒ 已给发电那台加了<b>结构性豁免</b>：
        //         {@code PrimordialModuleMachine#isEnergyCapExempt() = getDefinition().isGenerator()}
        //         ⇒ {@code ParallelOverrideMachine#applyEnergyCap} 第一句就返回 base（电上限视为 ∞）
        //         ⇒ 本行 = 表值 × T 这件事【不再依赖任何推断】，写入口怎么写都改不了它。
        //         判据（离线、可复跑）见 handoff/outbound/自动并行-判据5 的 D 段「新增 · 豁免」那两条。
        //       · 线程槽空（T = 1）时 totalParallelLimitFor(x, 1) == max(1, x) 对任意 x 成立
        //         （加载期自检 [SHANHAI-PARALLEL-BUDGET] 逐档断言）。
        //    ④ ⛔ 已被本轮改掉的那一版（第四轮，作废，原文逐字留档）：
        //         final long parallelBudget = ShanhaiParallelBudget.parallelBudget(
        //                 module.getCurrentParallel(), module.getEnergyParallel(), module.getCrossRecipeThreads());
        //       它给出的是【每线程】口径 ⇒ 发电那台在 T = 121 时从 259845521287 掉到 2147483647，
        //       正好把 9/30 那条修复弄坏。
        final long parallelBudget = PrimordialModuleMachine.totalParallelLimitFor(
                module.getCurrentParallel(), module.getCrossRecipeThreads());
        modified = PrimordialRecipeEffects.applyParallel(modified, module, parallelBudget);

        int gateBonus = host == null ? 0 : host.moduleSlotBonus();

        // ② 产出倍率（N3，只动产出：outputs / tickOutputs 两张表，概率拉满）
        modified = PrimordialRecipeEffects.multiplyOutputs(modified,
                PrimordialRecipeEffects.outputMultiplier(gateBonus));

        // ③ 耗时减免（N5 旋钮一；🔴 主机侧没有这一步 —— 主机时长由 N6 的 A 下限决定，不吃 f）
        //    🔴 2026-09-22：必须在乘 f **之前**留一份【原时长快照】—— 它是第 ④ 步的天花板。
        //       没有它，"1 tick 的配方"就会被第 ④ 步的下限拖到 20 tick（队长算出来的那个病）。
        final int durationAtEntry = modified.duration;
        double factor = PrimordialRecipeEffects.reductionFactor(gateBonus);
        modified = PrimordialRecipeEffects.applyDurationReduction(modified, factor);

        // ④ 模块侧 N6（⛔ 2026-09-26 起 = 保底 + 天花板；【下限项已按用户裁决删除】）
        //    现行公式：duration = min(原时长, max(1, round(原时长 × f)))
        //    ⛔ 历史（作废，要点留档，勿再往回加）：本条曾经有两版，都以【模块自己的】为下限
        //       （2026-09-21 起不再读主机的）：
        //       · 纯下限（旧名 applyDurationFloorForModule）：max(下限, round(d × f)) —— 只保证"不比下限快"
        //       · 下限 + 天花板：min(原时长, max(下限, round(d × f))) —— 另加"不比原来慢"
        //      第二版当初是为了治"1 tick 配方被下限拖到 20 tick"这个病；现在下限整条不存在了，
        //      那个病连同病因一起去掉。
        //    🔴 2026-09-26 用户裁决原话逐字「连功能一起删」⇒ 本行的
        //       `module.getLimitedDuration()` 与 `applyModuleDuration(GTRecipe,int,int)` 的那个形参
        //       【都已删除】。依据：那条下限原本要"托住"的对象 —— 模块侧栏「配方最短耗时」旋钮 ——
        //       2026-09-22 就按用户裁决摘掉了，用户 2026-09-26 追加确认
        //       「我们当时确实把模块的最小配方耗时给去除了」。
        //    ⚠️ 保底 `max(1, …)` 保留（在 applyModuleDuration 内部）—— 那是「至少 1 tick」，
        //       与「下限 20」是两件事；f 极小 + 原时长极短时由它兜住 duration=0。
        //    ⚠️ 外层 `min(原时长, …)` 也保留（"时长永不变长"红线；f ≤ 1 时是恒等操作）。
        modified = PrimordialRecipeEffects.applyModuleDuration(modified, durationAtEntry);

        // ④-b 🔴 2026-09-30（同日第二轮）用户拍板：「这个配方加到 long 之后可以加一个最小配方时长为 10 tick，
        //      然后 jade 写一下提示，这样不会引起误解」。原话逐字留档，见
        //      {@code PrimordialRecipeEffects#LONG_SCALE_MIN_DURATION} 的 javadoc。
        //   · 它治的是哪件事：并行进 long 档之后，输入钳位把并行放大到「整箱 ÷ 每份用量」——
        //     对【原始真空零点能发生器】+【创始现实修改模块】+【创造模式输入仓】这一组，
        //     一次装配就把整箱 9,223,372,036,854,775,807 mB 扣到只剩 807 mB，而配方时长被上面
        //     ③④ 压到 **1 tick** ⇒ 下一 tick 就没料 ⇒ 抬头在「在跑」与「未找到配方」之间横跳
        //     （创造模式输入仓每 5 tick 才回填一次）。抬到 10 tick 后一个料周期跨 2 次回填 ⇒ 不再挨饿。
        //   · 判据 = 【并行预算 > 2147483647】（原原生链 int 天花板）⇒ 该值就是"进了 long 档"的
        //     唯一真源定义（{@code ShanhaiParallelBudget.NATIVE_INT_CEILING}）。
        //     ⚠️ 恒等保证：预算 ≤ 21 亿时本行**逐值不变**（函数第一句就返回入参实例），
        //        而本行所在的原生链只有 isGenerator() 那台走 ⇒ 另外 23 台连这个方法都不会进。
        //   · ⛔ 旧口径（作废）：实际生效值 = min(10, 配方原时长) —— 原时长 < 10 的配方不被拖长。
        //     它撞的是旧红线「任何情况下配方时长都不许超过定义时长」。
        //   · ✅ 现行口径（用户 2026-09-30 拍板「B. 破一次红线，让那 25 台也抬到 10」）：
        //     **判据成立 ⇒ 时长下限恒为 10 tick（绝对 10，与"原时长"无关）**。
        //     🔴 新红线措辞（逐字，见 ShanhaiDurationFloor 类注释）：
        //        「只有「进了 long 档」（并行预算 > 2,147,483,647）时，配方时长才允许被抬到 10 tick；
        //          其余一切情形，配方时长仍不许超过配方定义的原时长。」
        //     ⚠️ 代价（用户明知并接受）：原时长 < 10 的配方在 long 档下比原版慢，最坏 1→10（10 倍）。
        //     ⚠️ 「绝对 10」= 下限，**不是**"强制等于 10"：本行只抬不砍（60 tick 的配方仍是 60）。
        //
        //   ④-c 🔴 2026-09-30（同日第三轮）：用户报「它没有到 10 tick」。定位结论 = 「原时长」这个
        //      基准取错了源 —— 上面 `durationAtEntry` 取的是【链上第 ② 步之后】读到的
        //      `modified.duration`，它是**中间产物**；只要链上任何一步让它变成 1，
        //      旧公式 `min(10, 原时长)` 就退化成 1、本行整条静默失效（而且因为"目标 == 现值"
        //      连生效日志都不打 ⇒ 看起来像"功能没写"）。
        //   · ④-c 现在不再承担"取对基准"的职责（新公式根本不读原时长）⇒ 它只做两件事：
        //     ① 读【配方定义实例】`logic.getLastOriginRecipe()`（GTCEu/gtlcore 自己保留的匹配到的
        //        原始配方，见 PrimordialModuleMachine 第 1707 行同一个取法）当**诊断值**；
        //     ② 把「预算 / 链上原时长 / 定义值 / 当前时长 / 目标值」交给一次性现场探针。
        //     ⇒ 这样即使将来"链上原时长被污染"再发生，时长照抬不误，而探针会把它**记下来**。
        final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic floorLogic = module.getRecipeLogic();
        final GTRecipe floorOrigin = floorLogic == null ? null : floorLogic.getLastOriginRecipe();
        final int floorOriginDuration = (floorOrigin != null && floorOrigin.duration > 0)
                ? floorOrigin.duration
                : durationAtEntry;
        PrimordialRecipeEffects.shanhai$probeDurationFloor(module.getDefinition().isGenerator(),
                parallelBudget, durationAtEntry, floorOriginDuration, modified.duration);
        modified = PrimordialRecipeEffects.applyLongScaleDurationFloor(
                modified, floorOriginDuration, parallelBudget);

        // ⑤ 耗能减免（N5 旋钮二，与耗时独立；钳 ≥ 1 EUt。🔴 主机侧同样吃这一步）
        modified = PrimordialRecipeEffects.reduceEnergy(modified, factor);

        // ───────────── ⑥ C（2026-09-21）：把"真实因子"交给 gtlcore 的抬头追踪器 ─────────────
        // 🔴 只写【显示用】的 tracker（WeakHashMap），**不改配方任何字段**。
        // 🔴 因子传 1.0/1.0（口径"甲"）：captureReduction 内部是累乘，且它在 context 路径下会
        //    自己算 calculate(baseRecipe, 链尾) —— 再传 f 就是把 f 乘第二次 = 假数据。
        // 🔴 传的是【链尾】那个 modified，且 machine 必须是外层 begin() 的同一个对象。
        // 模块画的都是**真实结果**：耗能 = f（÷ realParallels 后）／耗时 = max(下限, d×f)/d。
        // 🔴 诚实边界：耗时因子可能 > 1（原时长比模块下限还短时，时长被下限抬长了），如实画。
        //
        // ⛔ 2026-09-26 订正（旧句全部保留在上面，不改写；依据 = 取证代理的取证 + 本文的字节码复核）：
        //    ① 【本行在引擎路径上根本不会执行 = 死代码】。模块走的是引擎路径
        //       （PrimordialModuleRecipeLogic extends gtladditions MutableRecipesLogic），
        //       而引擎路径不经过 RecipeModifierList.apply ⇒ 作为 RecipeModifier 注册的这一份不会被调用。
        //       依据（取证代理给出，本次未复跑）：MutableRecipesLogic 的常量池里没有任何 RecipeModifier 引用。
        //       ⇒ 这一句**既写不坏、也写不好**：抬头两行的真值改在
        //       PrimordialModuleRecipeLogic#shanhai$captureModuleReduction 里写（2026-09-26 新增）。
        //    ② 【上面"内部是累乘"这句措辞不准确】。captureReduction 的 null 分支（MULTIPLIERS 这张表
        //       —— 正是 provider 读的那一张）是 `put(machine, multiply(DEFAULT, e, d))`，
        //       即**【以 DEFAULT(1.0,1.0) 为底】，不是累乘到旧值**（旧表项被整个覆盖）。
        //       ⇒ 所以"传 1.0/1.0"的后果不是"乘 1 无事"，而是**把这张表项写成 (1.0,1.0)**
        //         ⇒ provider 读到 100%/100%（模块抬头恒 100% 的第二个成因，与"缺条目时的兜底"同值）。
        //       context 分支的描述与旧文一致（那句仍然成立：context 支由 calculate(baseRecipe, 链尾) 自算）。
        //    ③ 结论不变：本行**保留**（它维护的是"原生修饰链那条路"的语义），
        //       但**不要**再把它当成模块抬头两行的数据源。
        RecipeMultiplierTracker.captureReduction(machine, modified, 1.0D, 1.0D);

        return modified;
    }

    /**
     * 取句柄，拿不到就抛带 id 的异常（fail-fast，绝不返回 null）。
     *
     * <p>这条异常是本阶段最重要的「响亮失败」：静默 null 的后果是主机结构永不成型且日志无异常。
     */
    private static MultiblockMachineDefinition require(String handleName) {
        MultiblockMachineDefinition definition = BY_HANDLE.get(handleName);
        if (definition == null) {
            throw new IllegalStateException("[SHANHAI-MODULE] 模块句柄注册失败：" + handleName
                    + " == null（模块 id 见 ModuleRegistry.SPECS）。"
                    + "句柄为 null 会让主机的 J 谓词变成恒 false/空指针，且失败点离病根极远，"
                    + "因此在加载期直接失败。请检查 ShanhaiRegistration.REGISTRATE 是否已在 mod 构造器里"
                    + "registerEventListeners，以及本类是否被 ShanhaiRegistry.init() 第 ③ 段调用。");
        }
        return definition;
    }

    // ═════════════════════════════ 供主机/结构使用的查询 API ═════════════════════════════

    /** 全部已注册模块的机器定义（顺序 = {@link #SPECS} 顺序）。未 init 时为空列表。 */
    public static List<MultiblockMachineDefinition> allModules() {
        return Collections.unmodifiableList(new ArrayList<>(BY_HANDLE.values()));
    }

    /**
     * 已注册模块的<b>条数</b>。
     *
     * <p>🔴 <b>注册期（GTCEu 的 MachineDefinition RegisterEvent）里的 fail-fast 只能用这个，
     * 不能用 {@link #allModuleBlocks()}。</b> 理由：{@code allModuleBlocks()} 会取
     * {@code definition.getBlock()} → {@code RegistryEntry.get()}，而 Registrate 的条目在
     * <b>对应的 Forge RegisterEvent 之前</b>必然抛
     * {@code NullPointerException: Registry entry not present: <id>}
     * （证据：{@code Registrate-MC1.20-1.3.3/RegistryEntry.get()} 走
     * {@code Objects.requireNonNull(getUnchecked(), …)}；{@code RegistryObject} 的 value
     * 只在 {@code updateReference(RegisterEvent)} 里被填）。而 GTCEu 的机器注册事件
     * <b>早于</b> Forge 的方块 RegisterEvent（GTCEu 自己在 {@code CommonProxy.init()} 末尾才
     * {@code GTRegistration.REGISTRATE.registerRegistrate()}）。本方法只读句柄表，无此问题。
     */
    public static int registeredModuleCount() {
        return BY_HANDLE.size();
    }

    /**
     * 全部已注册模块的控制器方块 —— 主机的 16 个模块位谓词直接用它。
     *
     * <p>🔴 <b>只能在 Forge 的方块 RegisterEvent 之后调用</b>（实际调用点是主机 pattern 的惰性求值，
     * 那时全部注册表早已冻结）。在注册期调用会 NPE，原因见 {@link #registeredModuleCount()}。
     */
    public static List<Block> allModuleBlocks() {
        List<Block> blocks = new ArrayList<>(BY_HANDLE.size());
        for (MultiblockMachineDefinition definition : BY_HANDLE.values()) {
            blocks.add(definition.getBlock());
        }
        return Collections.unmodifiableList(blocks);
    }

    /** 按句柄名取定义；未知句柄返回 null（只有 {@link #require(String)} 会抛）。 */
    @Nullable
    public static MultiblockMachineDefinition byHandleName(String handleName) {
        return BY_HANDLE.get(handleName);
    }
}
