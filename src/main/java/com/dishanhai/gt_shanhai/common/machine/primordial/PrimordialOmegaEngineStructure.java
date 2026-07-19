package com.dishanhai.gt_shanhai.common.machine.primordial;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;

import com.gtladd.gtladditions.common.machine.multiblock.structure.MultiBlockStructure;

public class PrimordialOmegaEngineStructure {

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        // 蒸汽时代方块映射 — 原始、朴素、机械美
        Block bronzeMachineCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_machine_casing"));
        Block steamMachineCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "steam_machine_casing"));
        Block bronzePipeCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_pipe_casing"));
        Block firebricks = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "firebricks"));
        Block industrialSteamCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "industrial_steam_casing"));
        Block cokeOvenBricks = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "coke_oven_bricks"));
        Block bronzeBrickCasing = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gtceu", "bronze_brick_casing"));
        Block voidInductionArmature = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_void_induction_armature"));
        Block massEnergyCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_mass_energy_core"));
        Block biologicalCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_biological_core"));
        Block chaoticEphemeralFurnace = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_chaotic_ephemeral_deconstruction_crystallization_furnace"));
        Block matterRecombinatorCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_matter_recombinator_core"));
        Block causalWeavingMatrix = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_causal_weaving_matrix"));
        Block singularityInversionCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_singularity_inversion_core"));
        Block worldFragmentsCollector = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_world_fragments_collector"));
        Block assemblyLineModule = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_assembly_line_module"));
        Block criticalProcessingModule = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_critical_processing_module"));
        Block multidimensionalImplosionCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_multidimensional_implosion_core"));
        Block supercriticalMatterGenerationCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_supercritical_matter_generation_core"));
        Block cosmicReactor = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_cosmic_reactor"));
        Block molecularRiftCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_molecular_rift_core"));
        Block tianqiongAssemblyCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_tianqiong_assembly_core"));
        Block eternalSmeltingFurnace = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_eternal_smelting_furnace"));
        Block worldlineTraversalMatrix = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_worldline_traversal_matrix"));
        Block quantumDistortionMatrix = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_quantum_distortion_matrix"));
        Block shaoguangAggregationCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_shaoguang_aggregation_core"));
        Block weiyangReconstructionModule = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_weiyang_reconstruction_module"));
        Block engravingModule = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_engraving_module"));
        Block taixuSmeltingFurnace = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "taixu_smelting_furnace"));
        Block antiEntropyCondensationCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_anti_entropy_condensation_core"));
        Block divergenceGenerator = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_divergence_generator"));
        Block matterCaster = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_matter_caster"));
        Block coinForge = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_coin_forge"));
        Block flameCrackingKiln = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_flame_cracking_kiln"));
        Block abyssalRefinery = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_abyssal_refinery"));
        Block cosmicOriginCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_cosmic_origin_core"));
        Block sixfoldResourceCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_sixfold_resource_core"));
        Block myriadProliferationCore = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "primordial_myriad_proliferation_core"));
        // 山海多合一仓（不注册到标准 PartAbility 避免污染 JEI 预览）
        Block maintenanceHatch = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("gt_shanhai", "maintenance_hatch"));

        // A 点：蒸汽机器外壳 + 全仓室挂载（对应原版 FOTC 的仓室贴附点）
        var aPred = Predicates.blocks(steamMachineCasing)
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                .or(maintenanceHatch != null ? Predicates.blocks(maintenanceHatch) : Predicates.air());
        // B 点：青铜机器外壳（装饰，无仓室）
        var bPred = Predicates.blocks(bronzeMachineCasing);

        return MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST()
                // A: 外圈外壳 — 蒸汽机器外壳 + 全仓室能力
                .where('A', aPred)
                // B: 外圈装饰 — 青铜机器外壳（纯装饰，无仓室）
                .where('B', bPred)
                // C: 核心圈内层 — 青铜管道外壳（唯一占用 bronzePipeCasing）
                .where('C', Predicates.blocks(bronzePipeCasing))
                // D: 背面结构 — 耐火砖（唯一占用 firebricks）
                .where('D', Predicates.blocks(firebricks))
                // E: 侧面 —— 工业蒸汽外壳（唯一占用 industrialSteamCasing）
                .where('E', Predicates.blocks(industrialSteamCasing))
                // F: 核心圈外圈 — 焦炉砖（不挂载仓室）
                .where('F', Predicates.blocks(cokeOvenBricks))
                // G: 内部填充 — 青铜管道外壳（替代用途）
                .where('G', Predicates.blocks(bronzePipeCasing))
                // H: 内部填充 — 耐火砖（替代用途）
                .where('H', Predicates.blocks(firebricks))
                // I: 内部填充 — 青铜管道外壳（与 C 不重叠，C 是核心圈、I 是内部）
                .where('I', Predicates.blocks(bronzePipeCasing))
                // J: 16顶点模块位 — 首选 steam_assembly_block，模块控制器为候选
                .where('J', Predicates.blocks(ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("kubejs", "steam_assembly_block")))
                        .or(Predicates.blocks(voidInductionArmature))
                        .or(Predicates.blocks(biologicalCore))
                        .or(Predicates.blocks(chaoticEphemeralFurnace))
                        .or(Predicates.blocks(matterRecombinatorCore))
                        .or(Predicates.blocks(causalWeavingMatrix))
                        .or(Predicates.blocks(singularityInversionCore))
                        .or(Predicates.blocks(worldFragmentsCollector))
                        .or(Predicates.blocks(assemblyLineModule))
                        .or(Predicates.blocks(criticalProcessingModule))
                        .or(Predicates.blocks(multidimensionalImplosionCore))
                        .or(Predicates.blocks(supercriticalMatterGenerationCore))
                        .or(Predicates.blocks(cosmicReactor))
                        .or(Predicates.blocks(molecularRiftCore))
                        .or(Predicates.blocks(tianqiongAssemblyCore))
                        .or(Predicates.blocks(eternalSmeltingFurnace))
                        .or(Predicates.blocks(worldlineTraversalMatrix))
                        .or(Predicates.blocks(quantumDistortionMatrix))
                        .or(Predicates.blocks(shaoguangAggregationCore))
                        .or(Predicates.blocks(weiyangReconstructionModule))
                        .or(Predicates.blocks(taixuSmeltingFurnace))
                        .or(Predicates.blocks(antiEntropyCondensationCore))
                        .or(Predicates.blocks(divergenceGenerator))
                        .or(Predicates.blocks(matterCaster))
                        .or(Predicates.blocks(engravingModule))
                        .or(Predicates.blocks(coinForge))
                        .or(Predicates.blocks(massEnergyCore))
                        .or(Predicates.blocks(flameCrackingKiln))
                        .or(Predicates.blocks(abyssalRefinery))
                        .or(Predicates.blocks(cosmicOriginCore))
                        .or(Predicates.blocks(sixfoldResourceCore))
                        .or(Predicates.blocks(myriadProliferationCore)))
                // K: 内部填充 — 青铜砖块（不再与 A 冲突）
                .where('K', Predicates.blocks(bronzeBrickCasing))
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where(' ', Predicates.any())
                .build();
    }

    private PrimordialOmegaEngineStructure() {}
}
