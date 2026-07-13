package com.dishanhai.gt_shanhai.common.machine;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.client.renderer.machine.MachineRenderer;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.common.data.GCyMRecipeTypes;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import org.gtlcore.gtlcore.common.data.GTLRecipeTypes;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.caster.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.matrix.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.furnace.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.core.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.collector.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.assembly.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.engraving.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.cutter.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.forge.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.taixu.*;
import com.dishanhai.gt_shanhai.common.machine.wave.*;
import com.dishanhai.gt_shanhai.common.machine.nebula.*;
import com.dishanhai.gt_shanhai.common.machine.worldline_cracking.WorldlineCrackingHubMachine;
import com.dishanhai.gt_shanhai.common.machine.worldline_cracking.WorldlineCrackingHubStructure;
import com.dishanhai.gt_shanhai.common.machine.spacetime.SpacetimeWaveMatrixMachine;
import com.dishanhai.gt_shanhai.common.machine.spacetime.SpacetimeWaveMatrixStructure;
import com.dishanhai.gt_shanhai.common.machine.stripping.*;
import com.dishanhai.gt_shanhai.common.machine.misc.*;
import com.dishanhai.gt_shanhai.common.machine.misc.workshop.*;
import com.dishanhai.gt_shanhai.common.machine.mass_fabricator.*;
import com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes;
import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.GTValues;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.common.item.DShanhaiMaintenanceHatchItem;
import com.dishanhai.gt_shanhai.common.item.SeventyTwoChangesItem;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiDivergenceEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiOverclockHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.part.LogicalComputeHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.MEDiskHatchPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.ReliableMEAsyncOutputPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferProxyPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.StarRailMEOutputMatrixPartMachine;
import com.dishanhai.gt_shanhai.common.machine.structure.STRStructure;
import com.dishanhai.gt_shanhai.client.renderer.machine.PrimordialOmegaEngineRenderer;
import com.dishanhai.gt_shanhai.client.renderer.machine.SpacetimeWaveMatrixRenderer;
import com.gtladd.gtladditions.utils.CommonUtils;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class DShanhaiMachines {

    // GTNL 移植机器
    public static MultiblockMachineDefinition ADVANCED_MASS_FABRICATOR;
    
    public static MultiblockMachineDefinition SPACETIME_WAVE_MATRIX;
    public static MultiblockMachineDefinition PRIMORDIAL_OMEGA_ENGINE;
    public static MultiblockMachineDefinition PRIMORDIAL_VOID_INDUCTION_ARMATURE;
    public static MultiblockMachineDefinition PRIMORDIAL_BIOLOGICAL_CORE;
    public static MultiblockMachineDefinition PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE;
    public static MultiblockMachineDefinition WORLD_LINE_STRIPPING_OSCILLATION_GENERATOR;
    public static MultiblockMachineDefinition PRIMORDIAL_MATTER_RECOMBINATOR_CORE;
    public static MultiblockMachineDefinition PRIMORDIAL_CAUSAL_WEAVING_MATRIX;
    public static MultiblockMachineDefinition PRIMORDIAL_SINGULARITY_INVERSION_CORE;
    public static MultiblockMachineDefinition GRAVITATIONAL_WAVE_ANTENNA_TRANSMITTER;
    public static MultiblockMachineDefinition PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR;
    public static MultiblockMachineDefinition PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE;
    public static MultiblockMachineDefinition PRIMORDIAL_DIVERGENCE_GENERATOR;
    public static MultiblockMachineDefinition PRIMORDIAL_MATTER_CASTER;
    public static MultiblockMachineDefinition PRIMORDIAL_ASSEMBLY_LINE_MODULE;
    public static MultiblockMachineDefinition PRIMORDIAL_ENGRAVING_MODULE;
    public static MultiblockMachineDefinition PRIMORDIAL_REALITY_ANCHOR_MODULE;
    public static MultiblockMachineDefinition PRIMORDIAL_COIN_FORGE;

    public static MachineDefinition BIG_TAG_FILTER_STOCK_BUS;
    public static MachineDefinition ME_REQUESTABLE_INPUT_BUS;
    public static MachineDefinition ME_REQUESTABLE_INPUT_HATCH;
    public static MachineDefinition INPUT_DUAL_HATCH;
    public static MachineDefinition MAINTENANCE_HATCH;
    public static MachineDefinition[] PROGRAMMABLE_HATCH = new MachineDefinition[GTValues.MAX + 1];
    public static MachineDefinition[] OVERCLOCK_HATCH = new MachineDefinition[GTValues.MAX + 1];
    public static MachineDefinition LOGICAL_COMPUTE_HATCH;
    public static MachineDefinition DIVERGENCE_ENGINE;
    public static MachineDefinition SUPER_PARALLEL_CORE;
    public static MachineDefinition SEVENTY_TWO_CHANGES;
    public static MachineDefinition ME_DISK_HATCH;
    public static MachineDefinition RECIPE_TYPE_PATTERN_BUFFER;
    public static MachineDefinition RECIPE_TYPE_PATTERN_BUFFER_PROXY;
    public static MachineDefinition RELIABLE_ME_ASYNC_OUTPUT_BUFFER;
    public static MachineDefinition ME_STARRAIL_OUTPUT_MATRIX;
    public static MachineDefinition VIRTUAL_ITEM_SUPPLY_MACHINE;
    public static MachineDefinition FTBQ_AE_SUBMITTER;
    public static MachineDefinition SHOP_TERMINAL;
    public static MultiblockMachineDefinition NEBULA_SIPHON;
    public static MultiblockMachineDefinition TAIXU_SMELTING_FURNACE;
    public static MultiblockMachineDefinition WORLDLINE_CRACKING_HUB;
    public static MultiblockMachineDefinition PRIMORDIAL_WORLDLINE_CUTTING_CORE;
    public static MultiblockMachineDefinition ZERO_PHOTON_CONDENSER;
    public static MultiblockMachineDefinition SHANHAI_NINE_INDUSTRIAL;  // 大明科技
    public static MultiblockMachineDefinition BLACK_HOLE_CONTAINMENT;   // 亚稳态黑洞遏制场
    public static MultiblockMachineDefinition ETERNAL_GREGTECH_WORKSHOP; // 永恒格雷工坊
    public static MultiblockMachineDefinition ETERNAL_GREGTECH_WORKSHOP_FUSION_MODULE;
    public static MultiblockMachineDefinition ETERNAL_GREGTECH_WORKSHOP_EYE_OF_HARMONY_MODULE;
    public static MultiblockMachineDefinition ETERNAL_GREGTECH_WORKSHOP_EXTRA_MODULE;
    public static MultiblockMachineDefinition SPACE_SCALER;
    public static MultiblockMachineDefinition INTEGRATED_ASSEMBLY_MATRIX;
    public static MultiblockMachineDefinition INTEGRATED_ASSEMBLY_FACILITY;
    public static MultiblockMachineDefinition PROXY_EXECUTOR;
    public static MultiblockMachineDefinition SINGULARITY_DATA_HUB;
    public static MultiblockMachineDefinition ABSOLUTE_QUANTUM_PERFECT_PURIFICATION_UNIT;
    public static MultiblockMachineDefinition BOX_SYSTEM_CENTRAL_CONTROLLER;
    public static MultiblockMachineDefinition TIANJIE_NAVIGATION_TOWER;
    public static MultiblockMachineDefinition MATTER_COPIER;

    // ===== 基础单方块机器 (ULV~ZPM, 索引=电压等级) =====
    public static MachineDefinition[] ZERO_POINT_CONVERSION = new MachineDefinition[3];
    public static MachineDefinition[] PHOTON_SIPHON = new MachineDefinition[3];

    public static void init() {
        // ========== GTNL 移植机器 ==========
        
        // 进阶质量发生器
        ADVANCED_MASS_FABRICATOR = GTDishanhaiRegistration.REGISTRATE
                .multiblock("advanced_mass_fabricator", AdvancedMassFabricatorMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(GTLRecipeTypes.MASS_FABRICATOR_RECIPES)
                .pattern(AdvancedMassFabricatorStructure::createPattern)
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/solid/machine_casing_solid_steel"),
                        new ResourceLocation("gtceu", "block/multiblock/mass_fabricator"))
                .register();
        
        ADVANCED_MASS_FABRICATOR.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§d进阶质量发生器"));
            tooltips.add(Component.literal("§7比相同电压的机器快 §a400%"));
            tooltips.add(Component.literal("§7只需要使用配方要求功率的 §a60%"));
            tooltips.add(Component.literal("§7"));
            tooltips.add(Component.literal("§7在控制舱内放入并行控制核心并进行控制核心"));
            tooltips.add(Component.literal("§7并行控制核心额外提供 §e16倍§7 的并行数"));
            tooltips.add(Component.literal("§7加速由加算改为乘算，每级提供 §a75%§7 的加速"));
            tooltips.add(Component.literal("§7机器可进行 §e无限超频"));
            tooltips.add(Component.literal("§7"));
            tooltips.add(Component.literal("§7安装无线升级格额外提供 §e500%§7 加速和 §e20§7 倍耗电减免"));
            tooltips.add(Component.literal("§7不安装能源仓且安装无线升级后自动进入无线模式"));
            tooltips.add(Component.literal("§7无线模式下电压为并行控制核心等级 §e+ 1§7，电流为 §e4§7 ^ 等级 - §e2§7"));
            tooltips.add(Component.literal("§7激光仓会被无视！"));
        });
        
        // ========== 原有机器 ==========
        
        Block casing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtlcore", "dimensionally_transcendent_casing"));
        Block bronzeCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing"));

        SPACETIME_WAVE_MATRIX = GTDishanhaiRegistration.REGISTRATE
                .multiblock("spacetime_wave_matrix", SpacetimeWaveMatrixMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(
                        GTRecipeTypes.ASSEMBLER_RECIPES,
                        GTLRecipeTypes.DISTORT_RECIPES,
                        GTLRecipeTypes.QFT_RECIPES,
                        GTRecipeTypes.LARGE_CHEMICAL_RECIPES,
                        DShanhaiRecipeTypes.SPACETIME_DISTORTION,
                        DShanhaiRecipeTypes.KU_MING_YUAN_YANG)
                .pattern(SpacetimeWaveMatrixStructure::createPattern)
                .renderer(() -> new SpacetimeWaveMatrixRenderer(
                        new ResourceLocation("gtladditions", "block/casings/god_forge_support_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/spacetime_wave_matrix")))
                .appearanceBlock(() -> casing)
                .hasTESR(true)
                .register();

        SPACETIME_WAVE_MATRIX.setTooltipBuilder(DShanhaiMachines::buildTooltip);

        PRIMORDIAL_OMEGA_ENGINE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_omega_engine", PrimordialOmegaEngineMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(
                        GTRecipeTypes.FURNACE_RECIPES,
                        GTRecipeTypes.ASSEMBLER_RECIPES,
                        GTRecipeTypes.ALLOY_SMELTER_RECIPES,
                        GTRecipeTypes.FORGE_HAMMER_RECIPES,
                        GTRecipeTypes.MACERATOR_RECIPES)
                .pattern(PrimordialOmegaEngineStructure::createPattern)
                .renderer(() -> new PrimordialOmegaEngineRenderer(
                        new ResourceLocation("gtceu", "block/casings/solid/machine_casing_bronze_plated_bricks"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_omega_engine")))
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "steam_machine_casing")))
                .hasTESR(true)
                .register();

        PRIMORDIAL_OMEGA_ENGINE.setTooltipBuilder(DShanhaiMachines::buildTooltipPrimordial);

        PRIMORDIAL_VOID_INDUCTION_ARMATURE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_void_induction_armature", PrimordialOmegaVoidInductionArmature::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(DShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR)
                .pattern(PrimordialOmegaVoidInductionArmatureStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_void_induction_armature"))
                .register();

        PRIMORDIAL_VOID_INDUCTION_ARMATURE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("从真空量子涨落中提取零点能"));
            tooltips.add(Component.literal("§b卡西米尔力在纳米间隙中呼吸，每一对虚粒子对的湮灭都是能量的脉搏"));
            tooltips.add(Component.literal("§b将这微弱的量子泡沫能量汇聚成足以扭曲时空的洪流"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：原初发电协议"));
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("并行与线程随电路幕级增长"));
        });

        PRIMORDIAL_BIOLOGICAL_CORE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_biological_core", PrimordialBiologicalCore::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        DShanhaiRecipeTypes.PRIMORDIAL_BIOLOGICAL_CORE,
                        GTLAddRecipesTypes.INSTANCE.getBIOLOGICAL_SIMULATION(),
                        GTLRecipeTypes.GREENHOUSE_RECIPES,
                        GTLRecipeTypes.INCUBATOR_RECIPES,
                        GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES)
                .pattern(PrimordialBiologicalCoreStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_biological_core"))
                .register();

        PRIMORDIAL_BIOLOGICAL_CORE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("以最原始的生命律动驱动永恒引擎"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§b孕育于原初汤海的第一个自我复制的闭环，亿万年演化浓缩为一握"));
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("跨越碳基与硅基的界限，将生命本身视为一种可设计的算法"));
            tooltips.add(Component.literal("§c五合一配方大类：原初生物演化协议"));
            tooltips.add(Component.literal("§7配方类型：原初生物演化协议/生物模拟/温室/培养缸/浮游选矿"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f并行，直接从电网取电")));
        });

        PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_chaotic_ephemeral_deconstruction_crystallization_furnace",
                        PrimordialChaoticEphemeralDeconstructionCrystallizationFurnace::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES,
                        GTLRecipeTypes.ISA_MILL_RECIPES,
                        GTLAddRecipesTypes.INSTANCE.getSPACE_ORE_PROCESSOR(),
                        GTLRecipeTypes.INTEGRATED_ORE_PROCESSOR,
                        GTLRecipeTypes.LARGE_VOID_MINER_RECIPES,
                        GTLRecipeTypes.RANDOM_ORE_RECIPES,
                        GTRecipeTypes.ORE_WASHER_RECIPES,
                        GTLRecipeTypes.MINER_MODULE_RECIPES)
                .pattern(PrimordialChaoticEphemeralDeconstructionCrystallizationFurnaceStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_chaotic_ephemeral_deconstruction_crystallization_furnace"))
                .register();

        PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("蜉蝣朝生暮死，矿物亿万年沉淀，皆在此炉中解构与重生"));
            tooltips.add(Component.literal("§b来自混沌的蜉蝣在晶格中凝固，每一粒原子都刻写着宇宙的矿脉图谱"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：蜉蝣选矿，湿法研磨，天基矿石处理，集成矿石处理，虚空采矿，虚空矿脉洗矿，太空采矿"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f并行，直接从电网取电")));
        });

        // ── I级 原初物质重组核心 ──────────────────────────────────────────
        PRIMORDIAL_MATTER_RECOMBINATOR_CORE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_matter_recombinator_core",
                        PrimordialMatterRecombinatorCore::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(DShanhaiRecipeTypes.PRIMORDIAL_MATTER_RECOMBINATION)
                .pattern(PrimordialMatterRecombinatorCoreStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

        PRIMORDIAL_MATTER_RECOMBINATOR_CORE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createFireText("从一块石头中剥离出它本不该含有的黄金——物质的界限只是懒惰的共识"));
            tooltips.add(Component.literal("§b将原子拆解为亚粒子流，按预设模式重新排列，实现最基础的现实修改"));
            tooltips.add(Component.literal("§a铁可变铜，石可变金——物理定律在这里不再是约束，而是参考意见"));
            tooltips.add(Component.literal("§7这是踏入原初科技的第一步，也是改写宇宙规则的第一笔"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：原初物质重组"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── II级 原初因果编织矩阵 ──────────────────────────────────────────
        PRIMORDIAL_CAUSAL_WEAVING_MATRIX = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_causal_weaving_matrix",
                        PrimordialCausalWeavingMatrix::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(DShanhaiRecipeTypes.PRIMORDIAL_CAUSAL_WEAVING)
                .pattern(PrimordialCausalWeavingMatrixStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_causal_weaving_matrix"))
                .register();

        PRIMORDIAL_CAUSAL_WEAVING_MATRIX.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("将因与果从线性暴政中解放——不再追问「为什么」，因为每一个「如果」都已折叠进必然"));
            tooltips.add(Component.literal("§b时间之矢在此转向自身，像一条蛇咬住尾巴——循环不是重复，是自由"));
            tooltips.add(Component.literal("§a将两条不相关的生产链编织在一起——消耗 A 产生 B，中间的一切都是多余的"));
            tooltips.add(Component.literal("§a允许因果逆转：先得产品，后偿原料——代价由能量网承担"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：原初因果编织"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── III级 原初奇点反演核心 ────────────────────────────────────────
        PRIMORDIAL_SINGULARITY_INVERSION_CORE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_singularity_inversion_core",
                        PrimordialSingularityInversionCore::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(DShanhaiRecipeTypes.PRIMORDIAL_SINGULARITY_INVERSION)
                .pattern(PrimordialSingularityInversionCoreStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_singularity_inversion_core"))
                .register();

        PRIMORDIAL_SINGULARITY_INVERSION_CORE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("在奇点的另一侧，能量与物质不再区分，因果与概率同时沉默"));
            tooltips.add(Component.literal("§b反演展开的那一刻，你看见的不是生产——是创世"));
            tooltips.add(Component.literal("§a将整个生产体系坍缩为奇点，再从反演中重新展开——输出倍率指数级提升"));
            tooltips.add(Component.literal("§a模块运行时局部宇宙常数被临时覆写，允许从虚空中凝聚超重元素"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：原初奇点反演"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── 原初世界碎片采集器 ──────────────────────────────────────────
        PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_world_fragments_collector",
                        PrimordialWorldFragmentsCollector::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(GTLRecipeTypes.FRAGMENT_WORLD_COLLECTION)
                .pattern(PrimordialWorldFragmentsCollectorStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

           PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createFireText("将散落在时空裂隙中的世界碎片聚拢——每一片都承载着一个坍缩的宇宙"));
            tooltips.add(DShanhaiTextUtil.createElectricText("通过原初引擎的至高伟力，大幅提升碎片采集效率"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("从主世界到末地，从月球到创始维度——万物皆可采集"));
            tooltips.add(DShanhaiTextUtil.createMagicText("碎片并非死物，它们是被斩断的世线末端，仍在低声回放曾经的历史"));
            tooltips.add(DShanhaiTextUtil.createWaterText("采集器以原初谐振频率发出引力波脉冲，让碎片自主跃迁至接收矩阵"));
            tooltips.add(DShanhaiTextUtil.createNatureText("每聚拢一片，原初引擎的因果权重便增加一分——更多分支将被解锁"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：世界碎片采集"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── 原初反熵冷凝核心 ──────────────────────────────────────────
        PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_anti_entropy_condensation_core",
                        PrimordialAntiEntropyCondensationCore::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        GTLAddRecipesTypes.INSTANCE.getANTIENTROPY_CONDENSATION(),
                        GTRecipeTypes.VACUUM_RECIPES,
                        GTLRecipeTypes.PLASMA_CONDENSER_RECIPES)
                .pattern(PrimordialAntiEntropyCondensationCoreStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

        PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createIceText("温度不是分子的运动速率，而是混沌的计量单位"));
            tooltips.add(DShanhaiTextUtil.createFireText("反熵冷凝核心逆向运行热力学箭头——从高熵态中抽取无序，冻结为有序的静止"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("核心剥离热振动，直至分子骨架停止战栗——输出绝对零度的冷凝体"));
            tooltips.add(DShanhaiTextUtil.createMagicText("每单位热量的移除伴随附近区域的熵减脉冲；冷凝体在常温下不会回温"));
            tooltips.add(DShanhaiTextUtil.createWaterText("并行等级提升时，核心可同时冻结多条世线中的同一批物质"));
            tooltips.add(DShanhaiTextUtil.createNatureText("它们的温度是负开尔文，但逻辑上依然存在"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：反熵冷凝/真空冷冻/等离子冷凝"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── 原初分歧发生器 ──────────────────────────────────────────
        PRIMORDIAL_DIVERGENCE_GENERATOR = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_divergence_generator",
                        PrimordialDivergenceGenerator::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        DShanhaiRecipeTypes.WORLDLINE_OSCILLATION_COLLECTION,
                        DShanhaiRecipeTypes.INTERSTELLAR_MATTER_ABSORPTION,
                        DShanhaiRecipeTypes.PRIMORDIAL_ENERGY_ABSORPTION)
                .pattern(PrimordialDivergenceGeneratorStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

        PRIMORDIAL_DIVERGENCE_GENERATOR.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("从世线的震荡中捕捉分歧的种子——每一次振荡都诞生一个新可能"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("星际物质在核心中凝聚，锻造出通往分歧之路的物质基石"));
            tooltips.add(DShanhaiTextUtil.createWaterText("分歧不是混乱，而是未被探索的秩序。"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：世线震荡收集/星际物质吸取/原初能量吸取"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── 原初物质铸造机 ──────────────────────────────────────────
        PRIMORDIAL_MATTER_CASTER = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_matter_caster",
                        PrimordialMatterCaster::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        DShanhaiRecipeTypes.PHOTON_SEPARATION,
                        DShanhaiRecipeTypes.MATTER_MODULE_CASTING,
                        DShanhaiRecipeTypes.MATTER_FLOW_CONDENSATION,
                        DShanhaiRecipeTypes.MATTER_FORGING)
                .pattern(PrimordialMatterCasterStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

        PRIMORDIAL_MATTER_CASTER.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createFireText("光子分离——将光与物质解构，提取最纯粹的能量形态"));
            tooltips.add(DShanhaiTextUtil.createMagicText("物质模块铸造——将精炼的物质注入模具，铸成实体"));
            tooltips.add(DShanhaiTextUtil.createNatureText("铸造即创造，分离即理解。"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：光子分离/物质模块铸造/物质流凝结/物质锻造"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级提供并行处理能力"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ── 原初装配线模块 ──────────────────────────────────────────
        PRIMORDIAL_ASSEMBLY_LINE_MODULE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_assembly_line_module",
                        PrimordialAssemblyLineModule::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        GTRecipeTypes.ASSEMBLY_LINE_RECIPES,
                        GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES,
                        GTLRecipeTypes.COMPONENT_ASSEMBLY_LINE_RECIPES,
                        DShanhaiRecipeTypes.WL_BOARD_CIRCUIT_ASSEMBLY,
                        DShanhaiRecipeTypes.WL_BOARD_WAFER_ETCHING)
                .pattern(PrimordialAssemblyLineModuleStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

        PRIMORDIAL_ASSEMBLY_LINE_MODULE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("将装配线纳入原初引擎的并行体系——批量生产不再是梦"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("从普通装配到电路装配到部件装配——万物皆可流水线"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：装配线 / 电路装配线 / 部件装配线"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级并行提供"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // 原初激光蚀刻模块
        PRIMORDIAL_ENGRAVING_MODULE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_engraving_module",
                        PrimordialEngravingModule::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        GTRecipeTypes.LASER_ENGRAVER_RECIPES,
                        GTLRecipeTypes.PRECISION_LASER_ENGRAVER_RECIPES,
                        GTLRecipeTypes.DIMENSIONAL_FOCUS_ENGRAVING_ARRAY_RECIPES,
                        GTLAddRecipesTypes.INSTANCE.getPHOTON_MATRIX_ETCH(),
                        DShanhaiRecipeTypes.WL_BOARD_WAFER_ETCHING)
                .pattern(PrimordialEngravingModuleStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "steam_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/primordial_matter_recombinator_core"))
                .register();

        PRIMORDIAL_ENGRAVING_MODULE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("世线集成电路的制造核心——光刻精度决定文明等级"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("从激光蚀刻到光子矩阵蚀刻，从精密蚀刻到维度聚焦阵列"));
            tooltips.add(DShanhaiTextUtil.createWaterText("最终——在世线的晶圆上蚀刻出超越物理极限的电路路径"));
            tooltips.add(Component.literal("§7世线板晶圆蚀刻：将世线板材蚀刻成精密电路的基础工艺"));
            tooltips.add(Component.literal("§7需安装在引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：激光蚀刻 / 精密蚀刻 / 维度聚焦蚀刻 / 光子矩阵蚀刻 / 世线板晶圆蚀刻"));
        });

        // ========== 原初铸币工厂 ==========
        PRIMORDIAL_COIN_FORGE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("primordial_coin_forge",
                        PrimordialCoinForgeModule::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(DShanhaiRecipeTypes.COIN_FORGE)
                .pattern(PrimordialCoinForgeStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "bronze_machine_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/machine/primordial_coin_forge"))
                .register();

        PRIMORDIAL_COIN_FORGE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("原初铸币工厂"));
            tooltips.add(Component.literal(""));
            tooltips.add(DShanhaiTextUtil.createElectricText("从原初物质中铸造GT币的专用工厂"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("物质模块控制并行上限，线程增强槽扩展跨配方线程"));
            tooltips.add(Component.literal("§7需安装在原初奥米伽引擎模块位"));
            tooltips.add(Component.literal("§7配方类型：铸币专用（coin_forge）"));
        });
        var wlsRecipeTypes = new java.util.ArrayList<com.gregtechceu.gtceu.api.recipe.GTRecipeType>();
        wlsRecipeTypes.add(GTLRecipeTypes.COSMOS_SIMULATION_RECIPES);
        wlsRecipeTypes.add(GTLRecipeTypes.ELEMENT_COPYING_RECIPES);
        wlsRecipeTypes.add(GTLAddRecipesTypes.INSTANCE.getSTAR_CORE_STRIPPER());
        if (net.minecraftforge.fml.ModList.get().isLoaded("gtl_extend")) {
            // 全量扫描 GTRegistries.RECIPE_TYPES 寻找 horizon_matter_decompression
            for (var t : GTRegistries.RECIPE_TYPES) {
                if ("gtlcore:horizon_matter_decompression".equals(t.registryName.toString())) {
                    wlsRecipeTypes.add(t);
                    break;
                }
            }
        }

        WORLD_LINE_STRIPPING_OSCILLATION_GENERATOR = GTDishanhaiRegistration.REGISTRATE
                .multiblock("world_line_stripping_oscillation_generator",
                        WorldLineStrippingOscillationGenerator::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(wlsRecipeTypes.toArray(new com.gregtechceu.gtceu.api.recipe.GTRecipeType[0]))
                .pattern(WorldLineStrippingOscillationGeneratorStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtlcore", "dimensionally_transcendent_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtladditions", "block/casings/god_forge_support_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/world_line_stripping_oscillation_generator"))
                .register();

        WORLD_LINE_STRIPPING_OSCILLATION_GENERATOR.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("在世线的震荡中剥离冗余分支，将概率云坍缩为确定的能量"));
            tooltips.add(DShanhaiTextUtil.createGoldenText("从宇宙模拟的虚数空间与星核内部的极端环境中提取世线震荡物质"));
            tooltips.add(DShanhaiTextUtil.createWaterText("终焉创始现实修改矩阵附属模块"));
            var recipeTypes = new StringBuilder("§7配方类型：宇宙模拟，元素复制，星核剥离");
            if (net.minecraftforge.fml.ModList.get().isLoaded("gtl_extend")) {
                recipeTypes.append("，视界物质剥离");
            }
            tooltips.add(Component.literal(recipeTypes.toString()));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                    .append(Component.literal("§f并行，直接从电网取电")));
        });

        // ── 山海特大号标签过滤总线 ──────────────────────────────────────
        BIG_TAG_FILTER_STOCK_BUS = GTDishanhaiRegistration.REGISTRATE
                .machine("big_tag_filter_stock_bus", DShanhaiBigTagFilterStockBusMachine::new)
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/big_tag_filter_stock_bus_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/big_tag_filter_stock_bus")))
                .register();

        BIG_TAG_FILTER_STOCK_BUS.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("山海特大号标签过滤库存总线"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7基于标签匹配自动从 ME 网络拉取物品"));
            tooltips.add(Component.literal("§7支持白名单/黑名单标签过滤，支持通配符"));
            tooltips.add(Component.literal("§a白名单: forge:ores/* @gtceu  §c黑名单: *raw*"));
            tooltips.add(Component.literal(""));
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("拉取种类与UI槽位可在配置文件中调整"));
        });

        // ── ME可请求输入总线 ──────────────────────────────────────────
        ME_REQUESTABLE_INPUT_BUS = GTDishanhaiRegistration.REGISTRATE
                .machine("me_requestable_input_bus", DShanhaiMERequestableInputBusMachine::new)
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/me_requestable_input_bus_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/me_requestable_input_bus")))
                .register();

        ME_REQUESTABLE_INPUT_BUS.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("ME可请求输入总线"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7仓室类型: §e物品输入总线 §8(IMPORT_ITEMS)"));
            tooltips.add(Component.literal("§7可作为多方块输入仓室使用，机器会从本总线读取物品"));
            tooltips.add(Component.literal("§7将物品从 ME 网络中提取出来，放入机器中进行加工"));
            tooltips.add(Component.literal("§b每个槽位按配置的目标物品与数量补齐库存"));
            tooltips.add(Component.literal("§b优先抽取 ME 库存；库存不足时提交缺口合成请求"));
            tooltips.add(Component.literal("§e需要在线 ME 网络、可用合成 CPU、样板与原料"));
            tooltips.add(Component.literal("§c仅处理物品，不处理流体；流体请使用输入仓"));
            tooltips.add(Component.literal("§d在机器 GUI 中收藏网络后，按 Shift 放置可自动连接收藏的网络"));
            tooltips.add(Component.literal("§6可共享：§c✕"));
        });

        // ── ME可请求输入仓 ────────────────────────────────────────────
        ME_REQUESTABLE_INPUT_HATCH = GTDishanhaiRegistration.REGISTRATE
                .machine("me_requestable_input_hatch", DShanhaiMERequestableInputHatchMachine::new)
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/me_requestable_input_hatch_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/me_requestable_input_hatch")))
                .register();

        ME_REQUESTABLE_INPUT_HATCH.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("ME可请求输入仓"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7仓室类型: §e流体输入仓室 §8(IMPORT_FLUIDS)"));
            tooltips.add(Component.literal("§7可作为多方块输入仓室使用，机器会从本仓读取流体"));
            tooltips.add(Component.literal("§7将流体从 ME 网络中提取出来，放入机器中进行加工"));
            tooltips.add(Component.literal("§b每个槽位按配置的目标流体与数量补齐库存"));
            tooltips.add(Component.literal("§b优先抽取 ME 库存；库存不足时提交缺口合成请求"));
            tooltips.add(Component.literal("§e需要在线 ME 网络、可用合成 CPU、样板与原料"));
            tooltips.add(Component.literal("§c仅处理流体，不处理物品；物品请使用输入总线"));
            tooltips.add(Component.literal("§d在机器 GUI 中收藏网络后，按 Shift 放置可自动连接收藏的网络"));
            tooltips.add(Component.literal("§6可共享：§c✕"));
        });

        // ── 可请求输入总成 ────────────────────────────────────────────
        INPUT_DUAL_HATCH = GTDishanhaiRegistration.REGISTRATE
                .machine("input_dual_hatch", DShanhaiInputDualHatchMachine::new)
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/input_dual_hatch_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/input_dual_hatch")))
                .register();

        INPUT_DUAL_HATCH.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("可请求输入总成"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7仓室类型: §eME可请求物品输入总线 + ME可请求流体输入仓"));
            tooltips.add(Component.literal("§7一个方块同时提供 §bIMPORT_ITEMS §7与 §bIMPORT_FLUIDS"));
            tooltips.add(Component.literal("§7适合同时吃物品和流体配方的多方块机器"));
            tooltips.add(Component.literal("§f物品槽位: §e4 §7个"));
            tooltips.add(Component.literal("§f流体槽位: §e2 §7个"));
            tooltips.add(Component.literal("§b按配置的目标物品/流体与数量补齐库存"));
            tooltips.add(Component.literal("§b优先抽取 ME 库存；库存不足时提交缺口合成请求"));
            tooltips.add(Component.literal("§e需要在线 ME 网络、可用合成 CPU、样板与原料"));
            tooltips.add(Component.literal("§d在机器 GUI 中收藏网络后，按 Shift 放置可自动连接收藏的网络"));
            tooltips.add(Component.literal("§6可共享：§c✕"));
        });

        // ========== 山海维护仓 ==========
        MAINTENANCE_HATCH = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "maintenance_hatch",
                MachineDefinition::createDefinition,
                DShanhaiMaintenanceHatchMachine::new,
                MetaMachineBlock::new,
                DShanhaiMaintenanceHatchItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/hub_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/hub_overlay")))
                .register();

        MAINTENANCE_HATCH.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createRainbowText("终焉聚合枢纽——空想至高造物"));
            tooltips.add(Component.literal(""));
            tooltips.add(ShanhaiTextAPI.inline("{ultimateRainbow}蓝星空想时代造物{/}{body_silver}，{/}{sakura}投放于多世线的重影，无垠的终极衍生{/}"));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{ice}光学{/}、{ice}算力{/}、{ice}电源{/}、{ice}维护{/}、{ice}天球分歧{/}、{ice}并行{/}——"));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_silver}所有仓室的职能在此{/}{ice}汇聚{/}{body_silver}为{/}{ice}单一节点{/}{body_silver}。{/}"));
            tooltips.add(Component.literal(""));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_silver}它不是替代，是{/}{water}统合{/}{body_silver}；不是妥协，是{/}{golden}超越{/}{body_silver}。{/}"));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_silver}安装至多方块后，现实规则被{/}{ice}暂时挂起{/}{body_silver}，{/}"));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_silver}如同被请出{/}{ice}会议室{/}{body_silver}的旁观者。{/}"));
            tooltips.add(Component.literal(""));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_fire}维护的不只是机器，是{/}{fire}宇宙对蓝星文明的让步{/}{body_fire}；{/}"));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_electric}运算的不只是数据，是{/}{electric}因果对指令的沉默{/}{body_electric}。{/}"));
            tooltips.add(Component.literal(""));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_silver}每一束{/}{aurora}星光{/}{body_silver}、每一个{/}{golden}线程{/}{body_silver}、每一条{/}{magic}世线分歧{/}{body_silver}，{/}"));
            tooltips.add(ShanhaiTextAPI.inline(
                    "{body_silver}都从这里{/}{water}流出又流回{/}{body_silver}——{/}"));
            tooltips.add(DShanhaiTextUtil.createRainbowText(
                    "枢纽不决策，枢纽只是让决策变得毫无阻力。"));
        });

        // ========== 可编程仓（虚拟不消耗物品槽） ==========
        for (int tier = GTValues.ULV; tier <= GTValues.MAX; tier++) {
            int finalTier = tier;
            String suffix = GTValues.VN[tier].toLowerCase();
            String id = tier == GTValues.MAX ? "programmable_hatch" : suffix + "_programmable_hatch";

            PROGRAMMABLE_HATCH[tier] = MachineBuilder.create(
                    GTDishanhaiRegistration.REGISTRATE,
                    id,
                    MachineDefinition::createDefinition,
                    holder -> new ProgrammableHatchPartMachine(holder, finalTier),
                    MetaMachineBlock::new,
                    MetaMachineItem::new,
                    MetaMachineBlockEntity::createBlockEntity
            )
                    .tier(tier)
                    .rotationState(RotationState.ALL)
                    .renderer(() -> new WorkableCasingMachineRenderer(
                            new ResourceLocation("gtceu", "block/casings/voltage/" + suffix + "/side"),
                            new ResourceLocation(MOD_ID, "block/machine/part/programmable_hatch")))
                    .register();

            PROGRAMMABLE_HATCH[tier].setTooltipBuilder((stack, tooltips) -> {
                tooltips.add(DShanhaiTextUtil.createRainbowText(GTValues.VN[finalTier] + "可编程仓"));
                tooltips.add(Component.literal(""));
                tooltips.add(DShanhaiTextUtil.createElectricText("可选择多方块当前搜索的配方类型"));
                tooltips.add(DShanhaiTextUtil.createMagicText("虚拟物品/流体仅满足 chance=0 输入，不会被消耗"));
                tooltips.add(Component.literal("§7物品槽: " + Math.max(1, finalTier * finalTier) + " | 流体槽: " + Math.max(1, finalTier)));
                tooltips.add(Component.literal("§7普通物品/流体仍作为输入仓参与消耗配方"));
            });
        }

        // ========== 超频仓（先注册，后续接入机器超频逻辑） ==========
        for (int tier = GTValues.LV; tier <= GTValues.MAX; tier++) {
            int finalTier = tier;
            String suffix = GTValues.VN[tier].toLowerCase();
            String id = tier == GTValues.MAX ? "overclock_hatch" : suffix + "_overclock_hatch";

            OVERCLOCK_HATCH[tier] = MachineBuilder.create(
                    GTDishanhaiRegistration.REGISTRATE,
                    id,
                    MachineDefinition::createDefinition,
                    holder -> new DShanhaiOverclockHatchMachine(holder, finalTier),
                    MetaMachineBlock::new,
                    MetaMachineItem::new,
                    MetaMachineBlockEntity::createBlockEntity
            )
                    .tier(tier)
                    .rotationState(RotationState.ALL)
                    .abilities(
                            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS,
                            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS)
                    .renderer(() -> new WorkableCasingMachineRenderer(
                            new ResourceLocation("gtceu", "block/casings/voltage/" + suffix + "/side"),
                            new ResourceLocation(MOD_ID, "block/machine/part/overclock_hatch")))
                    .register();

            OVERCLOCK_HATCH[tier].setTooltipBuilder((stack, tooltips) -> {
                long maxDivisor = DShanhaiOverclockHatchMachine.getMaxDivisorForTier(finalTier);
                tooltips.add(DShanhaiTextUtil.createElectricText(GTValues.VN[finalTier] + "超频仓"));
                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§7可放入标准输入仓位，为运行配方提供可调耗时除数"));
                tooltips.add(Component.literal("§f可调除数: §e2 §7- §e" + maxDivisor));
                tooltips.add(Component.literal("§f最大耗时倍率: §b1/" + maxDivisor));
            });
        }

        // ========== ME 磁盘仓室（AE2 存储元件挂载点） ==========
        ME_DISK_HATCH = MEDiskHatchPartMachine.register();

        // ========== 星律样板总成 ==========
        RECIPE_TYPE_PATTERN_BUFFER = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "recipe_type_pattern_buffer",
                MachineDefinition::createDefinition,
                holder -> new RecipeTypePatternBufferPartMachine(holder,
                        DShanhaiConfig.COMMON.recipeTypePatternsPerRow.get(),
                        DShanhaiConfig.COMMON.recipeTypeRowsPerPage.get(),
                        DShanhaiConfig.COMMON.recipeTypeMaxPages.get()),
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS,
                        PartAbility.EXPORT_ITEMS, PartAbility.EXPORT_FLUIDS)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gt_shanhai", "block/casings/recipe_type_pattern_buffer_casing"),
                        new ResourceLocation("gt_shanhai", "block/machine/part/recipe_type_pattern_buffer")))
                .register();

        RECIPE_TYPE_PATTERN_BUFFER.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("星律样板总成"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7基于 GTLCore ME 样板总成，保留分页样板槽与输入能力"));
            tooltips.add(Component.literal("§a当前保留基础样板总成行为，不追加跨配方类型虚拟执行链"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§6继承能力:"));
            tooltips.add(Component.literal("§7· 各样板槽位库存§f真·完全隔离§7，互不串料"));
            tooltips.add(Component.literal("§7· §f中键催化剂槽§7：为多个样板共享同一组催化剂/共享电路"));
            tooltips.add(Component.literal("§7· 样板可写入§f虚拟电路§7，下单不消耗电路，可覆盖共享电路"));
            tooltips.add(Component.literal("§7· §f配方缓存§7：单槽默认缓存一种配方，可在 UI 中调整"));
            tooltips.add(Component.literal("§7· 产物§f直接写入 ME 网络§7，无需额外输出总成(我更建议你用星轨输出矩阵)"));
            tooltips.add(Component.literal("§7· §f槽位行列数/页数§7可在配置文件中自定义（修改后重新放置生效）"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§6用法:"));
            tooltips.add(Component.literal("§7· 作为普通超级样板仓室装入宿主机器"));
            tooltips.add(Component.literal("§7· 按宿主原生支持的配方类型执行样板"));
            tooltips.add(Component.literal("§7· 可搭配§f星律样板代理§7扩展样板容量"));
            tooltips.add(Component.literal("§8无线供电宿主需先绑定无线网络（放置即绑定放置者，或数据棒右键）"));
        });

        RECIPE_TYPE_PATTERN_BUFFER_PROXY = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "recipe_type_pattern_buffer_proxy",
                MachineDefinition::createDefinition,
                RecipeTypePatternBufferProxyPartMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS,
                        PartAbility.EXPORT_ITEMS, PartAbility.EXPORT_FLUIDS)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gt_shanhai", "block/casings/recipe_type_pattern_buffer_proxy_casing"),
                        new ResourceLocation("gt_shanhai", "block/machine/part/recipe_type_pattern_buffer_proxy")))
                .register();

        RECIPE_TYPE_PATTERN_BUFFER_PROXY.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("星律样板代理"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7只能绑定星律样板总成"));
            tooltips.add(Component.literal("§b用于把同一组超级样板输入能力延伸到结构其他位置"));
        });

        // ========== ME 相位输出矩阵（先落盘 buffer，再批量写入 ME） ==========
        RELIABLE_ME_ASYNC_OUTPUT_BUFFER = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "reliable_me_async_output_buffer",
                MachineDefinition::createDefinition,
                ReliableMEAsyncOutputPartMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/reliable_me_async_output_buffer_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/reliable_me_async_output_buffer")))
                .register();

        RELIABLE_ME_ASYNC_OUTPUT_BUFFER.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("ME 相位输出矩阵"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7直接将产物存入本机持久化缓冲，再批量写入 ME 网络"));
            tooltips.add(Component.literal("§b保留增广输出总成的优先级与过滤配置"));
            tooltips.add(Component.literal("§a避免 GTLCore 异步输出总成的非持久化累计窗口吞物品"));
            tooltips.add(Component.literal("§e适合跨配方机器；比原异步略慢，但以可靠性优先"));
        });

        // ========== ME 星轨输出矩阵（持久化确认 + 多轮星轨刷写） ==========
        ME_STARRAIL_OUTPUT_MATRIX = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "me_starrail_output_matrix",
                MachineDefinition::createDefinition,
                StarRailMEOutputMatrixPartMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/me_starrail_output_matrix_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/me_starrail_output_matrix")))
                .register();

        ME_STARRAIL_OUTPUT_MATRIX.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createRainbowText("ME 星轨输出矩阵").copy().withStyle(s -> s.withBold(true)));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7产物先进入本机持久化缓冲，再沿星轨批量写入 ME 网络"));
            tooltips.add(Component.literal("§b每次 AE tick 执行多轮刷写，吞吐高于 ME 相位输出矩阵"));
            tooltips.add(Component.literal("§a网络满载或断线时，未写入内容仍保留在本机缓冲"));
            tooltips.add(Component.literal("§e螺丝刀右键切换输出模式: 均衡 / 物品优先 / 流体优先 / 小堆优先"));
            tooltips.add(Component.literal("§7物品/流体优先会先处理目标类型，剩余预算继续均衡输出"));
            tooltips.add(Component.literal("§7小堆优先会在扫描窗口内先清理最小堆，减少缓存条目"));
            tooltips.add(Component.literal("§bJade 可查看刷写预算、失败 key 冷却、全网冷却与 AE 缓存状态"));
            tooltips.add(Component.literal("§d适合终局跨配方机器与高并行产线的稳定输出"));
        });

        // ========== 虚拟物品供应机（AE2 虚拟不消耗物品挂载点） ==========
        VIRTUAL_ITEM_SUPPLY_MACHINE = VirtualItemSupplyMachine.register();

        // ========== FTBQ AE 自动提交器 ==========
        FTBQ_AE_SUBMITTER = FtbqAeSubmitterMachine.register();

        // ========== 山海商店终端（独立于提交器给商店供 AE 网络） ==========
        SHOP_TERMINAL = ShopTerminalMachine.register();

        // ========== 逻辑算力仓 ==========
        LOGICAL_COMPUTE_HATCH = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "logical_compute_hatch",
                MachineDefinition::createDefinition,
                LogicalComputeHatchMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.NON_Y_AXIS)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gtlcore", "block/casings/dimensionally_transcendent_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/logical_compute_hatch")))
                .register();

        LOGICAL_COMPUTE_HATCH.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("逻辑算力仓").copy().withStyle(s -> s.withBold(true)));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§b门与门堆叠，晶振无声跳动。"));
            tooltips.add(Component.literal("§b它不懂因果，只会逐条执行。"));
            tooltips.add(Component.literal("§b每一个\"真\"与\"假\"在这里反复撞击，直到答案浮出暗涌。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7提供无限算力(CWU)，绕过配方算力需求"));
            tooltips.add(Component.literal("§7可作为算力源仓/靶仓、光学源仓/靶仓"));
        });

        // ========== 太初分歧引擎 ==========
        DIVERGENCE_ENGINE = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "divergence_engine",
                MachineDefinition::createDefinition,
                DShanhaiDivergenceEngineMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gtlcore", "block/casings/graviton_field_constraint_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/divergence_engine")))
                .register();

        DIVERGENCE_ENGINE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createNatureText("太初分歧引擎").copy().withStyle(s -> s.withBold(true)));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7混沌尚未退场，秩序还未成形。"));
            tooltips.add(Component.literal("§7太初之际，第一条世线从虚无中探出头来。"));
            tooltips.add(Component.literal("§7它不知道哪条分支更优，只负责制造分岔。"));
            tooltips.add(Component.literal("§7天球还未被命名，但分歧已经发生。"));
            tooltips.add(Component.literal("§7因果尚未学会走路，每一个方向都是第一次。"));
            tooltips.add(Component.literal("§7没有一条路径被标记为\"正确\"——"));
            tooltips.add(DShanhaiTextUtil.createGoldenText("因为正确本身就是分岔的产物。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7槽位1: 放入太初并行子 → +32并行/个"));
            tooltips.add(Component.literal("§7槽位2: 放入太初世线之种 → +2线程/个"));
            tooltips.add(Component.literal("§7UI可开关并行/线程并调整数量"));
        });

        // ========== 山海超级并行核心（分子操纵者并行 MAX_VALUE） ==========
        SUPER_PARALLEL_CORE = com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "super_parallel_core",
                com.gregtechceu.gtceu.api.machine.MachineDefinition::createDefinition,
                com.dishanhai.gt_shanhai.common.machine.part.DShanhaiSuperParallelCoreMachine::new,
                com.gregtechceu.gtceu.api.block.MetaMachineBlock::new,
                com.gregtechceu.gtceu.api.item.MetaMachineItem::new,
                com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(com.gregtechceu.gtceu.api.data.RotationState.ALL)
                .abilities(org.gtlcore.gtlcore.api.machine.multiblock.GTLPartAbility.MOLECULAR_ASSEMBLER_MATRIX)
                .renderer(() -> new com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer(
                        new net.minecraft.resources.ResourceLocation(MOD_ID, "block/casings/super_parallel_core"),
                        new net.minecraft.resources.ResourceLocation(MOD_ID, "block/machine/part/super_parallel_core")))
                .register();

        // ========== 引力波天线发射器 ==========
        Block gwsCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "lv_machine_casing"));

        GRAVITATIONAL_WAVE_ANTENNA_TRANSMITTER = GTDishanhaiRegistration.REGISTRATE
                .multiblock("gravitational_wave_antenna_transmitter",
                        GravitationalWaveAntennaTransmitter::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(DShanhaiRecipeTypes.GRAVITATIONAL_WAVE_PRODUCTION, DShanhaiRecipeTypes.GRAVITATIONAL_WAVE_CONSUMPTION)
                .pattern(STRStructure::createPattern)
                .appearanceBlock(() -> gwsCasing)
                .workableCasingRenderer(
                        new ResourceLocation("gtlcore", "block/molecular_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/gravitational_wave_antenna_transmitter"))
                .register();

        GRAVITATIONAL_WAVE_ANTENNA_TRANSMITTER.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createFireText("引力波天线发射器").copy().withStyle(s -> s.withBold(true)));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7悬垂于大地之上，如同一枚倒悬的审判之钉。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7它不是武器，却能毁灭两个世界；"));
            tooltips.add(Component.literal("§7它不是信号塔，却能让全宇宙听见你的沉默。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7它的振动弦由简并态物质构成，"));
            tooltips.add(Component.literal("§7每一根都承载着黑暗森林的诅咒——"));
            tooltips.add(Component.literal("§7广播一经发出，坐标暴露，打击降临，文明湮灭。"));
            tooltips.add(Component.literal(""));
            tooltips.add(DShanhaiTextUtil.createLavaText("按下按钮的那一刻，你不再是执剑人而是两个文明的死神。"));
            tooltips.add(DShanhaiTextUtil.createCrimsonText("威慑在此建立，恐惧在此安家;"));
            tooltips.add(DShanhaiTextUtil.createCrimsonText("但逐光者无畏，终有一天[泛文明命运共同体]会到来。"));
            tooltips.add(DShanhaiTextUtil.createCrimsonText("为范围内的机器增加倍率产出,提供完美超频,提高生产效率。"));
        });

        // ========== 七十二变（精选通用配方类型，避免冲突） ==========
        SEVENTY_TWO_CHANGES = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "seventy_two_changes",
                MachineDefinition::createDefinition,
                SeventyTwoChangesMachine::new,
                MetaMachineBlock::new,
                SeventyTwoChangesItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(
                        GTRecipeTypes.FURNACE_RECIPES,
                        GTRecipeTypes.ALLOY_SMELTER_RECIPES,
                        GTRecipeTypes.ASSEMBLER_RECIPES,
                        GTRecipeTypes.CHEMICAL_RECIPES,
                        GTRecipeTypes.LARGE_CHEMICAL_RECIPES,
                        GTRecipeTypes.MIXER_RECIPES,
                        GTRecipeTypes.CENTRIFUGE_RECIPES,
                        GTRecipeTypes.ELECTROLYZER_RECIPES,
                        GTRecipeTypes.EXTRACTOR_RECIPES,
                        GTRecipeTypes.COMPRESSOR_RECIPES,
                        GTRecipeTypes.MACERATOR_RECIPES,
                        GTRecipeTypes.FORGE_HAMMER_RECIPES,
                        GTRecipeTypes.BENDER_RECIPES,
                        GTRecipeTypes.LATHE_RECIPES,
                        GTRecipeTypes.WIREMILL_RECIPES,
                        GTRecipeTypes.FORMING_PRESS_RECIPES,
                        GTRecipeTypes.EXTRUDER_RECIPES,
                        GTRecipeTypes.PACKER_RECIPES,
                        GTRecipeTypes.CANNER_RECIPES,
                        GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES,
                        GTRecipeTypes.AUTOCLAVE_RECIPES,
                        GTRecipeTypes.THERMAL_CENTRIFUGE_RECIPES,
                        GTRecipeTypes.ELECTROMAGNETIC_SEPARATOR_RECIPES,
                        GTRecipeTypes.SIFTER_RECIPES,
                        GTRecipeTypes.LASER_ENGRAVER_RECIPES,
                        GTRecipeTypes.POLARIZER_RECIPES,
                        GTRecipeTypes.CUTTER_RECIPES,
                        GTRecipeTypes.ORE_WASHER_RECIPES,
                        GTRecipeTypes.ARC_FURNACE_RECIPES,
                        GTRecipeTypes.FLUID_HEATER_RECIPES,
                        GTRecipeTypes.DISTILLERY_RECIPES,
                        GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES,
                        GTRecipeTypes.ROCK_BREAKER_RECIPES,
                        GTRecipeTypes.SCANNER_RECIPES,
                        GTRecipeTypes.GAS_COLLECTOR_RECIPES,
                        GTRecipeTypes.BREWING_RECIPES,
                        GTRecipeTypes.FERMENTING_RECIPES,
                        GTRecipeTypes.CHEMICAL_BATH_RECIPES,
                        GTRecipeTypes.AIR_SCRUBBER_RECIPES,
                        // — gtlcore 通用处理 —
                        GTLRecipeTypes.GREENHOUSE_RECIPES,
                        GTLRecipeTypes.INCUBATOR_RECIPES,
                        GTLRecipeTypes.FISHING_GROUND_RECIPES,
                        GTLRecipeTypes.DEHYDRATOR_RECIPES,
                        GTLRecipeTypes.VACUUM_DRYING_RECIPES,
                        GTLRecipeTypes.HEAT_EXCHANGER_RECIPES,
                        GTLRecipeTypes.AGGREGATION_DEVICE_RECIPES,
                        GTLRecipeTypes.LARGE_RECYCLER_RECIPES,
                        GTLRecipeTypes.DISASSEMBLY_RECIPES,
                        GTLRecipeTypes.NEUTRON_ACTIVATOR_RECIPES,
                        GTLRecipeTypes.LIGHTNING_PROCESSOR_RECIPES,
                        GTLRecipeTypes.DECAY_HASTENER_RECIPES,
                        GTLRecipeTypes.ELEMENT_COPYING_RECIPES,
                        GTLRecipeTypes.RARE_EARTH_CENTRIFUGAL_RECIPES,
                        GTLRecipeTypes.MASS_FABRICATOR_RECIPES,
                        GTLRecipeTypes.MATTER_FABRICATOR_RECIPES,
                        GTLRecipeTypes.SUPER_PARTICLE_COLLIDER_RECIPES,
                        GTLRecipeTypes.LAVA_FURNACE_RECIPES,
                        GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES,
                        GTLRecipeTypes.PLASMA_CONDENSER_RECIPES,
                        GTLRecipeTypes.ATOMIC_ENERGY_EXCITATION_RECIPES,
                        GTLRecipeTypes.ISA_MILL_RECIPES,
                        GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES,
                        GTLRecipeTypes.MAGIC_MANUFACTURER_RECIPES,
                        GTLRecipeTypes.SPS_CRAFTING_RECIPES,
                        GTLRecipeTypes.FUEL_REFINING_RECIPES,
                        GTLRecipeTypes.PETROCHEMICAL_PLANT_RECIPES,
                        GTLRecipeTypes.DISSOLUTION_TREATMENT,
                        GTLRecipeTypes.DIGESTION_TREATMENT,
                        // — gtladditions 通用处理 —
                        GTLAddRecipesTypes.INSTANCE.getSPACE_ORE_PROCESSOR(),
                        GTLAddRecipesTypes.INSTANCE.getBIOLOGICAL_SIMULATION(),
                        DShanhaiRecipeTypes.SEVENTY_TWO_CHANGES,
                        // — 真·混沌合成：万物皆可为万物 —
                        DShanhaiRecipeTypes.CHAOS_CRAFTING)
                .workableCasingRenderer(
                        new ResourceLocation(MOD_ID, "block/machine/seventy_two_changes/overlay_front_active_emissive"),
                        new ResourceLocation(MOD_ID, "block/machine/seventy_two_changes"))
                .register();

        SEVENTY_TWO_CHANGES.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createNeonText("一台基于量子叠加态的物质重编器。"));
            tooltips.add(Component.literal(""));
            tooltips.add(DShanhaiTextUtil.createElectricText("内置七十二种高维物质态序列。"));
            tooltips.add(Component.literal("§7输入任意原料，矩阵将随机坍缩至七十二种可能产出的其中一种——"));
            tooltips.add(DShanhaiTextUtil.createGoldenText("不是无序，而是你尚未理解的更高阶秩序。"));
            tooltips.add(Component.literal(""));
            tooltips.add(DShanhaiTextUtil.createAuroraText("每一次处理都是一次多元宇宙采样："));
            tooltips.add(Component.literal("§7你拿到的结果，只是无数平行现实中恰好撞入本世线的那个。"));
            tooltips.add(Component.literal("§7概率不是随机的玩笑，是多世界在帮你选礼物。"));
            tooltips.add(Component.literal(""));
            tooltips.add(DShanhaiTextUtil.createLavaText("警告：持续使用可能导致你对\"因果\"产生不健康的怀疑。"));
        });

        // ===== 天界星云零点虹吸枢纽 =====
        Block nsAppearance = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "iv_machine_casing"));

        NEBULA_SIPHON = GTDishanhaiRegistration.REGISTRATE.multiblock("nebula_siphon",
                        NebulaSiphonMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(DShanhaiRecipeTypes.NEBULA_SIPHONING)
                .pattern(HarpoonSatelliteStructure::createPattern)
                .appearanceBlock(() -> nsAppearance)
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/voltage/iv/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/harpoon_satellite"))
                .register();

                // ===== 太虚熔炼炉 =====
        Block taixuCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing"));

        TAIXU_SMELTING_FURNACE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("taixu_smelting_furnace", PrimordialTaixuCosmicForgeModule::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        GTRecipeTypes.FURNACE_RECIPES,
                        GTRecipeTypes.ALLOY_SMELTER_RECIPES,
                        GTRecipeTypes.BLAST_RECIPES,
                        GCyMRecipeTypes.ALLOY_BLAST_RECIPES,
                        DShanhaiRecipeTypes.TAIXU_SMELTING)
                .pattern(TaixuSmeltingFurnaceStructure::createPattern)
                .appearanceBlock(() -> taixuCasing)
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/taixu_smelting_furnace"))
                .register();

        TAIXU_SMELTING_FURNACE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createNeonText("原初太虚宇宙锻炉"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7以太初为名，以虚空为薪。"));
            tooltips.add(DShanhaiTextUtil.createNeonText("必须安装在原初引擎上才能工作"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7太虚只是起点，不是终点。"));
            tooltips.add(Component.literal("§7它处理物质，而非可能性。"));
            tooltips.add(Component.literal("§7当你想触碰太虚之上——"));
            tooltips.add(Component.literal("§e空想重构器 §7在那里等你。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§a配方类型: 太虚熔炼/电炉/合金冶炼/电力高炉/合金冶炼炉"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("按模块等级并行提供"))
                    .append(Component.literal("§f，直接从电网取电")));
        });

        // ========== 世线裂解枢纽 ==========
        WORLDLINE_CRACKING_HUB = GTDishanhaiRegistration.REGISTRATE
                .multiblock("worldline_cracking_hub", WorldlineCrackingHubMachine::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(
                        DShanhaiRecipeTypes.WORLDLINE_PROBABILITY_CRACKING,
                        DShanhaiRecipeTypes.WORLDLINE_MATTER_RECURRENCE,
                        DShanhaiRecipeTypes.WORLDLINE_SAMPLING)
                .pattern(WorldlineCrackingHubStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtceu", "stress_proof_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/solid/machine_casing_stress_proof"),
                        new ResourceLocation(MOD_ID, "block/multiblock/worldline_cracking_hub"))
                .register();

        WORLDLINE_CRACKING_HUB.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("世线裂解枢纽"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("确定性的暴政在此终结。"));
            tooltips.add(Component.literal("投入原料，枢纽将其分解为未被观测的概率云——"));
            tooltips.add(Component.literal("所有世线中该原料的潜在形态同时浮现。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("裂解不是毁灭，而是释放物质内部被冻结的'如果'。"));
            tooltips.add(Component.literal("每一粒产出的碎片，都是一条世线在向你招手。"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("配方类型: 概率裂解 / 物质复现 / 世线采样"));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("并行超过阈值时同时交付所有可能结果")));
        });

        NEBULA_SIPHON.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createAuroraText("天界星云零点虹吸枢纽"));
            tooltips.add(DShanhaiTextUtil.createNeonText("以多维星穹为引，从宇宙真空中聚合零点能驱动基础物质合成"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7消耗特定流体提升并行处理能力："));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createWaterText("真空零点能"))
                    .append(Component.literal(" §7→ ").append(DShanhaiTextUtil.createElectricText("1,024")).append(Component.literal(" §7并行"))));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createNatureText("初级物质流"))
                    .append(Component.literal(" §7→ ").append(DShanhaiTextUtil.createElectricText("2,048")).append(Component.literal(" §7并行"))));
            tooltips.add(Component.literal("")
                    .append(DShanhaiTextUtil.createLavaText("高级物质流"))
                    .append(Component.literal(" §7→ ").append(DShanhaiTextUtil.createUltimateRainbow("10,240")).append(Component.literal(" §7并行"))));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7直接从电网取电，无需 EU 输入仓"));
            tooltips.add(DShanhaiTextUtil.createAuroraText("配方类型：多维星穹零点聚合"));
        });

        // ===== 基础单方块机器 (ULV~ZPM) =====

            for (int tier = 0; tier <= 2; tier++) {
            int finalTier = tier;
            String suffix = GTValues.VN[tier].toLowerCase();

            ZERO_POINT_CONVERSION[tier] = GTDishanhaiRegistration.REGISTRATE
                    .machine(suffix + "_zero_point_conversion",
                            holder -> new DShanhaiWirelessBasicMachine(holder, finalTier))
                    .tier(tier)
                    .rotationState(RotationState.ALL)
                    .recipeTypes(DShanhaiRecipeTypes.ZERO_POINT_CONVERSION)
                    .editableUI(SimpleTieredMachine.EDITABLE_UI_CREATOR.apply(
                            GTCEu.id(suffix + "_zero_point_conversion"),
                            DShanhaiRecipeTypes.ZERO_POINT_CONVERSION))
                    .workableCasingRenderer(
                            new ResourceLocation("gtceu", "block/casings/voltage/" + suffix + "/side"),
                            new ResourceLocation(MOD_ID, "block/machine/zero_point_conversion"))
                    .register();

            PHOTON_SIPHON[tier] = GTDishanhaiRegistration.REGISTRATE
                    .machine(suffix + "_photon_siphon",
                            holder -> new DShanhaiBasicMachine(holder, finalTier))
                    .tier(tier)
                    .rotationState(RotationState.ALL)
                    .recipeTypes(DShanhaiRecipeTypes.PHOTON_SIPHON)
                    .editableUI(SimpleTieredMachine.EDITABLE_UI_CREATOR.apply(
                            GTCEu.id(suffix + "_photon_siphon"),
                            DShanhaiRecipeTypes.PHOTON_SIPHON))
                    .workableCasingRenderer(
                            new ResourceLocation("gtceu", "block/casings/voltage/" + suffix + "/side"),
                            new ResourceLocation(MOD_ID, "block/machine/photon_siphon"))
                    .register();
        }

            // ========== 零点光子转换器（HV 多方块） ==========
            Block zpCasing = ForgeRegistries.BLOCKS.getValue(
                    new ResourceLocation("gtceu", "hv_machine_casing"));

            ZERO_PHOTON_CONDENSER = GTDishanhaiRegistration.REGISTRATE
                    .multiblock("zero_photon_condenser",
                            ZeroPhotonCondenserMachine::new)
                    .rotationState(RotationState.NON_Y_AXIS)
                    .recipeTypes(DShanhaiRecipeTypes.ZERO_POINT_CONVERSION,
                            DShanhaiRecipeTypes.PHOTON_SIPHON)
                    .pattern(ZeroPhotonCondenserMachine::createPattern)
                    .appearanceBlock(() -> zpCasing)
                    .workableCasingRenderer(
                            new ResourceLocation("gtceu", "block/casings/hv_machine_casing"),
                            new ResourceLocation(MOD_ID, "block/multiblock/zero_photon_condenser"))
                    .register();

            ZERO_PHOTON_CONDENSER.setTooltipBuilder((stack, tooltips) -> {
                tooltips.add(Component.literal("§6零点光子转换器").copy().withStyle(s -> s.withBold(true)));
                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§7在真空的子宫里，光与虚无完成第一次交易。"));
                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§7● 处理零点转换与光子虹吸两类配方"));
                tooltips.add(Component.literal("§7● 输入：物品 + 流体 ｜ 输出：物品 + 流体"));
                tooltips.add(Component.literal("§7● 支持并行仓与分歧引擎扩展"));
                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§73×3×3 紧凑结构，HV机壳搭建"));
                tooltips.add(Component.literal("§7"));
                tooltips.add(Component.literal("§8「每一个光子都是从虚无中索取的能量。」"));
            });

        // ═══════════════════════════════════════════════════════════════
        // 大明科技 — 108模式万能配方处理机
        // 原出处: GTnotleisure-0.2.5 (GTNH)
        // ═══════════════════════════════════════════════════════════════
        SHANHAI_NINE_INDUSTRIAL = ShanhaiNineIndustrialMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 亚稳态黑洞遏制场 (EHCl)
        // 原出处: GTNH GregTech 5u (DregTech)
        // ═══════════════════════════════════════════════════════════════
        BLACK_HOLE_CONTAINMENT = ShanhaiBlackHoleContainmentMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 永恒格雷工坊 — 主机本体
        // 原出处: GTnotleisure / Eternal GregTech Workshop (GTNH) + GTO
        // ═══════════════════════════════════════════════════════════════
        ETERNAL_GREGTECH_WORKSHOP = GTDishanhaiRegistration.REGISTRATE
                .multiblock("eternal_gregtech_workshop", EternalGregTechWorkshopMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .pattern(EternalGregTechWorkshopStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtladditions", "god_forge_support_casing")))
                .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.machine.EternalGregTechWorkshopRenderer(
                        new ResourceLocation("gtladditions", "block/casings/god_forge_support_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/spacetime_wave_matrix")))
                .hasTESR(true)
                .register();

        ETERNAL_GREGTECH_WORKSHOP.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("永恒格雷工坊"));
            tooltips.add(Component.literal("§7GTNL+GTO 永恒格雷工坊主机本体移植"));
            tooltips.add(Component.literal("§7主结构、专用渲染、模块绑定、燃料统计与实际增益汇总已接入"));
            tooltips.add(Component.literal("§7可连接聚变模块、创世之眼模块与额外模块"));
            tooltips.add(Component.literal("§7主机 UI 可调整等级、模块等级、燃料因子、燃料类型与渲染开关"));
        });

        ETERNAL_GREGTECH_WORKSHOP_FUSION_MODULE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("eternal_gregtech_workshop_fusion_module",
                        EternalGregTechWorkshopFusionModuleMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(GTRecipeTypes.FUSION_RECIPES)
                .pattern(EternalGregTechWorkshopModuleStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtladditions", "god_forge_support_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtladditions", "block/casings/god_forge_support_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/spacetime_wave_matrix"))
                .register();

        ETERNAL_GREGTECH_WORKSHOP_FUSION_MODULE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("永恒格雷工坊·聚变模块"));
            tooltips.add(Component.literal("§7使用永恒格雷工坊数据模块绑定到已成型主机"));
            tooltips.add(Component.literal("§7绑定后按主机汇总增益修正配方EU、时长与并行"));
            tooltips.add(Component.literal("§7配方入口: 聚变"));
            tooltips.add(Component.literal("§8结构来源: GTNL 原版 module.mb"));
        });

        ETERNAL_GREGTECH_WORKSHOP_EYE_OF_HARMONY_MODULE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("eternal_gregtech_workshop_eye_of_harmony_module",
                        EternalGregTechWorkshopEyeOfHarmonyModuleMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(
                        GTLRecipeTypes.STELLAR_FORGE_RECIPES,
                        GTLRecipeTypes.COSMOS_SIMULATION_RECIPES,
                        GTLRecipeTypes.DIMENSIONALLY_TRANSCENDENT_PLASMA_FORGE_RECIPES,
                        DShanhaiRecipeTypes.CHAOS_CRAFTING)
                .pattern(EternalGregTechWorkshopModuleStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtladditions", "god_forge_support_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtladditions", "block/casings/god_forge_support_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/spacetime_wave_matrix"))
                .register();

        ETERNAL_GREGTECH_WORKSHOP_EYE_OF_HARMONY_MODULE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("永恒格雷工坊·创世之眼模块"));
            tooltips.add(Component.literal("§7使用永恒格雷工坊数据模块绑定到已成型主机"));
            tooltips.add(Component.literal("§7绑定后按主机汇总增益修正配方EU、时长与并行"));
            tooltips.add(Component.literal("§7配方入口: 恒星锻炉 / 宇宙模拟 / 超维度熔炼 / 混沌"));
            tooltips.add(Component.literal("§8结构来源: GTNL 原版 module.mb"));
        });

        ETERNAL_GREGTECH_WORKSHOP_EXTRA_MODULE = GTDishanhaiRegistration.REGISTRATE
                .multiblock("eternal_gregtech_workshop_extra_module",
                        EternalGregTechWorkshopExtraModuleMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(
                        GTRecipeTypes.ASSEMBLY_LINE_RECIPES,
                        GTLRecipeTypes.SUPRACHRONAL_ASSEMBLY_LINE_RECIPES,
                        GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES,
                        GTLRecipeTypes.COMPONENT_ASSEMBLY_LINE_RECIPES,
                        DShanhaiRecipeTypes.WL_BOARD_CIRCUIT_ASSEMBLY,
                        DShanhaiRecipeTypes.WL_BOARD_WAFER_ETCHING)
                .pattern(EternalGregTechWorkshopModuleStructure::createPattern)
                .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("gtladditions", "god_forge_support_casing")))
                .workableCasingRenderer(
                        new ResourceLocation("gtladditions", "block/casings/god_forge_support_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/spacetime_wave_matrix"))
                .register();

        ETERNAL_GREGTECH_WORKSHOP_EXTRA_MODULE.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createUltimateRainbow("永恒格雷工坊·额外模块"));
            tooltips.add(Component.literal("§7使用永恒格雷工坊数据模块绑定到已成型主机"));
            tooltips.add(Component.literal("§7绑定后作为第三模块槽参与主机实际增益汇总"));
            tooltips.add(Component.literal("§7配方入口: 装配线 / 超时空装配 / 电路装配 / 部件装配"));
            tooltips.add(Component.literal("§8结构来源: GTNL 原版 module.mb"));
        });

        // ═══════════════════════════════════════════════════════════════
        // 空间缩放仪 — TST 移植
        // ═══════════════════════════════════════════════════════════════
        SPACE_SCALER = com.dishanhai.gt_shanhai.common.machine.misc.SpaceScalerMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 量子计算机 — GTNL 移植
        // ═══════════════════════════════════════════════════════════════
        // quantum_computer is registered as an AE multiblock core in DShanhaiAE2Blocks.

        // ═══════════════════════════════════════════════════════════════
        // 集成装配矩阵 — TST 移植
        // ═══════════════════════════════════════════════════════════════
        INTEGRATED_ASSEMBLY_MATRIX = com.dishanhai.gt_shanhai.common.machine.misc.IntegratedAssemblyMatrixMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 物质定增 — NXY Matter Copier 移植
        // ═══════════════════════════════════════════════════════════════
        MATTER_COPIER = com.dishanhai.gt_shanhai.common.machine.misc.MatterCopierMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 综合组装车间 — GTNL 移植 (IntegratedAssemblyFacility)
        // ═══════════════════════════════════════════════════════════════
        INTEGRATED_ASSEMBLY_FACILITY = com.dishanhai.gt_shanhai.common.machine.misc.IntegratedAssemblyFacilityMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 盒系统中央控制器 — BoxPlusPlus 行为移植第一阶段
        // ═══════════════════════════════════════════════════════════════
        BOX_SYSTEM_CENTRAL_CONTROLLER = com.dishanhai.gt_shanhai.common.machine.misc.BoxSystemCentralControllerMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 代理执行者 — NXY Proxy 行为移植第一阶段
        // ═══════════════════════════════════════════════════════════════
        PROXY_EXECUTOR = com.dishanhai.gt_shanhai.common.machine.misc.ProxyExecutorMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 奇点数据中枢 — GTNL 移植 (SingularityDataHub, ID 21170)
        // ═══════════════════════════════════════════════════════════════
        SINGULARITY_DATA_HUB = com.dishanhai.gt_shanhai.common.machine.misc.SingularityDataHubMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 绝对量子完美净化单元 — GTNH 移植
        // ═══════════════════════════════════════════════════════════════
        ABSOLUTE_QUANTUM_PERFECT_PURIFICATION_UNIT =
                com.dishanhai.gt_shanhai.common.machine.misc.AbsoluteQuantumPerfectPurificationUnitMachine.register();

        // ═══════════════════════════════════════════════════════════════
        // 天界领航塔 — tianjie2.schem 导入结构
        // ═══════════════════════════════════════════════════════════════
        TIANJIE_NAVIGATION_TOWER = com.dishanhai.gt_shanhai.common.machine.misc.TianjieNavigationTowerMachine.register();

    }


    /**
     * 在方块注册完成后调用，注册 PartAbility（否则不被识别为有效输入/输出仓）
     */
    public static void registerPartAbilities() {
        if (BIG_TAG_FILTER_STOCK_BUS != null) {
            var busBlock = BIG_TAG_FILTER_STOCK_BUS.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, busBlock);
        }
        if (ME_REQUESTABLE_INPUT_BUS != null) {
            var requestableBusBlock = ME_REQUESTABLE_INPUT_BUS.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, requestableBusBlock);
        }
        if (ME_REQUESTABLE_INPUT_HATCH != null) {
            var requestableHatchBlock = ME_REQUESTABLE_INPUT_HATCH.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS.register(0, requestableHatchBlock);
        }
        if (INPUT_DUAL_HATCH != null) {
            var inputDualHatchBlock = INPUT_DUAL_HATCH.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, inputDualHatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS.register(0, inputDualHatchBlock);
        }
        if (LOGICAL_COMPUTE_HATCH != null) {
            var computeBlock = LOGICAL_COMPUTE_HATCH.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.COMPUTATION_DATA_RECEPTION.register(0, computeBlock);
        }
        if (DIVERGENCE_ENGINE != null) {
            var divBlock = DIVERGENCE_ENGINE.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.PARALLEL_HATCH.register(0, divBlock);
            com.gtladd.gtladditions.api.machine.GTLAddPartAbility.INSTANCE.getTHREAD_MODIFIER().register(0, divBlock);
        }
        if (MAINTENANCE_HATCH != null) {
            var hatchBlock = MAINTENANCE_HATCH.getBlock();
            // 枢纽映射所有标准 PartAbility，可替换任意仓室
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.MAINTENANCE.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.PARALLEL_HATCH.register(0, hatchBlock);
            com.gtladd.gtladditions.api.machine.GTLAddPartAbility.INSTANCE.getTHREAD_MODIFIER().register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.COMPUTATION_DATA_RECEPTION.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.COMPUTATION_DATA_TRANSMISSION.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.OPTICAL_DATA_RECEPTION.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.OPTICAL_DATA_TRANSMISSION.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.INPUT_ENERGY.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.SUBSTATION_INPUT_ENERGY.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.OUTPUT_ENERGY.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.SUBSTATION_OUTPUT_ENERGY.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.INPUT_LASER.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.OUTPUT_LASER.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_ITEMS.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS.register(0, hatchBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_FLUIDS.register(0, hatchBlock);
            // 结构检测工具过滤由 StructureDetectFilterMixin 处理
        }
        for (int tier = GTValues.ULV; tier <= GTValues.MAX; tier++) {
            if (PROGRAMMABLE_HATCH[tier] != null) {
                com.dishanhai.gt_shanhai.common.machine.ShanhaiPartAbility.PROGRAMMABLE_HATCH.register(tier, PROGRAMMABLE_HATCH[tier].getBlock());
                com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(tier, PROGRAMMABLE_HATCH[tier].getBlock());
            }
        }
        for (int tier = GTValues.LV; tier <= GTValues.MAX; tier++) {
            if (OVERCLOCK_HATCH[tier] != null) {
                Block overclockBlock = OVERCLOCK_HATCH[tier].getBlock();
                com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(tier, overclockBlock);
                com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS.register(tier, overclockBlock);
            }
        }
        if (ME_DISK_HATCH != null) {
            com.dishanhai.gt_shanhai.common.machine.ShanhaiPartAbility.ME_DISK_HATCH.register(0, ME_DISK_HATCH.getBlock());
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, ME_DISK_HATCH.getBlock());
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_ITEMS.register(0, ME_DISK_HATCH.getBlock());
        }
        if (RECIPE_TYPE_PATTERN_BUFFER != null) {
            var patternBufferBlock = RECIPE_TYPE_PATTERN_BUFFER.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, patternBufferBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS.register(0, patternBufferBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_ITEMS.register(0, patternBufferBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_FLUIDS.register(0, patternBufferBlock);
        }
        if (RECIPE_TYPE_PATTERN_BUFFER_PROXY != null) {
            var patternBufferProxyBlock = RECIPE_TYPE_PATTERN_BUFFER_PROXY.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_ITEMS.register(0, patternBufferProxyBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.IMPORT_FLUIDS.register(0, patternBufferProxyBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_ITEMS.register(0, patternBufferProxyBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_FLUIDS.register(0, patternBufferProxyBlock);
        }
        if (RELIABLE_ME_ASYNC_OUTPUT_BUFFER != null) {
            var reliableOutputBlock = RELIABLE_ME_ASYNC_OUTPUT_BUFFER.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_ITEMS.register(0, reliableOutputBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_FLUIDS.register(0, reliableOutputBlock);
        }
        if (ME_STARRAIL_OUTPUT_MATRIX != null) {
            var starRailOutputBlock = ME_STARRAIL_OUTPUT_MATRIX.getBlock();
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_ITEMS.register(0, starRailOutputBlock);
            com.gregtechceu.gtceu.api.machine.multiblock.PartAbility.EXPORT_FLUIDS.register(0, starRailOutputBlock);
        }
        if (SUPER_PARALLEL_CORE != null) {
            var coreBlock = SUPER_PARALLEL_CORE.getBlock();
            if (coreBlock != null) {
                org.gtlcore.gtlcore.api.machine.multiblock.GTLPartAbility.MOLECULAR_ASSEMBLER_MATRIX.register(0, coreBlock);
            }
        }
        // ========== 引力波天线发射器 — 能力映射已移至结构模式 STRStructure.java 中的 'U' 字符 ==========
    }

    private static void buildTooltip(ItemStack stack, List<Component> tooltips) {
        tooltips.add(DShanhaiTextUtil.createMagicText("现实的底层规则在此被覆写，现实织线随矩阵脉冲重新编织"));
        tooltips.add(DShanhaiTextUtil.createGoldenText("——万物皆可为输入，万象皆可为输出。"));
        tooltips.add(Component.literal("§b无需追问因果是否仍旧成立，"
                + "只需明白：改写常数，重构维度，"));
        tooltips.add(Component.literal("§9让\"不可能\"从辞典中蒸发。"));
        tooltips.add(DShanhaiTextUtil.createAuroraText("取消旧宇宙的只读属性，保存为属于你的新现实；"));
        tooltips.add(DShanhaiTextUtil.createUltimateRainbow("大反冲降临此世，永恒由我们重写"));
        tooltips.add(DShanhaiTextUtil.createUltimateRainbow("跨越世线的终极巨构，以世线波动干涉技术修改现实位面"));
        tooltips.add(Component.literal("§7配方类型：组装机/深度化学扭曲仪/量子操纵者/大型化学反应釜/量子化现实重构/苦命鸳鸯"));
        tooltips.add(Component.literal("§5搭载模块：世线剥离震荡发生器（提供配方：宇宙模拟 · 星核剥离 · 视界物质剥离）"));
        tooltips.add(Component.literal(""));
        tooltips.add(Component.literal("§a默认15倍配方输出，无视配方概率"));
        tooltips.add(Component.literal("§f拥有")
                .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                .append(Component.literal("§f并行与"))
                .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                .append(Component.literal("§f线程，直接从电网取电")));
    }

    private static void buildTooltipPrimordial(ItemStack stack, List<Component> tooltips) {
        tooltips.add(DShanhaiTextUtil.createGoldenText("原初的力量在此苏醒，蒸汽与齿轮的低语交织成亘古的轰鸣"));
        tooltips.add(DShanhaiTextUtil.createElectricText("以最原始的机械诠释最极致的伟力。"));
        tooltips.add(Component.literal("§7青铜砌骨，蒸汽为脉，烈火锻魂；"));
        tooltips.add(Component.literal("§7焦炉砖上烙印着最初文明的印记。"));
        tooltips.add(Component.literal(""));
        tooltips.add(Component.literal("§7配方类型：电炉/组装机/合金炉/锻造锤/打粉机"));
        tooltips.add(Component.literal("§f拥有")
                .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                .append(Component.literal("§f并行与"))
                .append(DShanhaiTextUtil.createUltimateRainbow("无限"))
                .append(Component.literal("§f线程，直接从电网取电")));
    }

    private DShanhaiMachines() {}
}
