package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import com.gtladd.gtladditions.common.modify.GTLAddSoundEntries;

import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;

public class DShanhaiRecipeTypes {

    public static GTRecipeType SPACETIME_DISTORTION;
    public static GTRecipeType KU_MING_YUAN_YANG;
    public static GTRecipeType PRIMORDIAL_POWER_GENERATOR;
    public static GTRecipeType PRIMORDIAL_BIOLOGICAL_CORE;
    public static GTRecipeType PRIMORDIAL_MATTER_RECOMBINATION;
    public static GTRecipeType PRIMORDIAL_CAUSAL_WEAVING;
    public static GTRecipeType PRIMORDIAL_SINGULARITY_INVERSION;
    public static GTRecipeType GRAVITATIONAL_WAVE_PRODUCTION;
    public static GTRecipeType GRAVITATIONAL_WAVE_CONSUMPTION;
    public static GTRecipeType SEVENTY_TWO_CHANGES;
    public static GTRecipeType CHAOS_CRAFTING;
    public static GTRecipeType NEBULA_SIPHONING;
    public static GTRecipeType TIANJIE_NAVIGATION;
    public static GTRecipeType TAIXU_SMELTING;
    public static GTRecipeType MATTER_AGGREGATION;
    public static GTRecipeType ZERO_POINT_CONVERSION;
    public static GTRecipeType PHOTON_SIPHON;
    public static GTRecipeType WORLDLINE_OSCILLATION_COLLECTION;
    public static GTRecipeType INTERSTELLAR_MATTER_ABSORPTION;
    public static GTRecipeType MATTER_FLOW_CONDENSATION;
    public static GTRecipeType PHOTON_SEPARATION;
    public static GTRecipeType MATTER_MODULE_CASTING;
    public static GTRecipeType MATTER_FORGING;
    public static GTRecipeType WL_BOARD_CIRCUIT_ASSEMBLY;
    public static GTRecipeType WL_BOARD_WAFER_ETCHING;
    public static GTRecipeType PRIMORDIAL_ENERGY_ABSORPTION;
    public static GTRecipeType WORLDLINE_PROBABILITY_CRACKING;
    public static GTRecipeType WORLDLINE_MATTER_RECURRENCE;
    public static GTRecipeType WORLDLINE_SAMPLING;
    public static GTRecipeType WORLDLINE_CUTTING;
    public static GTRecipeType HIGH_DIMENSIONAL_FRAGMENT_CUTTING;
    public static GTRecipeType NINE_INDUSTRIAL;  // 大明科技聚合类型
    public static GTRecipeType BLACK_HOLE_COMPRESSOR;           // 黑洞引力压缩
    public static GTRecipeType BLACK_HOLE_NEUTRONIUM_COMPRESSOR; // 黑洞中子态素压缩
    public static GTRecipeType BLACK_HOLE_EVENT_HORIZON_BLAST;   // 事件视界爆破
    public static GTRecipeType COIN_FORGE;                       // 原初铸币工厂
    public static GTRecipeType PROXY_EXECUTION;                   // 代理执行占位类型
    public static final GTRecipeType[] NINE_INDUSTRIAL_MODES = new GTRecipeType[36]; // 36 水浒传模式显示类型

    private DShanhaiRecipeTypes() {}

    public static void init() {
        SPACETIME_DISTORTION = GTRecipeTypes.register("spacetime_distortion", "multiblock")
                .setMaxIOSize(9, 6, 6, 5)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        KU_MING_YUAN_YANG = GTRecipeTypes.register("kmyy", "multiblock")
                .setMaxIOSize(2, 1, 0, 0)
                .setEUIO(IO.OUT)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PRIMORDIAL_POWER_GENERATOR = GTRecipeTypes.register("primordial_power_generator", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.OUT)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PRIMORDIAL_BIOLOGICAL_CORE = GTRecipeTypes.register("primordial_biological_core", "multiblock")
                .setMaxIOSize(6, 3, 3, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PRIMORDIAL_MATTER_RECOMBINATION = GTRecipeTypes.register("primordial_matter_recombination", "multiblock")
                .setMaxIOSize(12, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PRIMORDIAL_CAUSAL_WEAVING = GTRecipeTypes.register("primordial_causal_weaving", "multiblock")
                .setMaxIOSize(12, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PRIMORDIAL_SINGULARITY_INVERSION = GTRecipeTypes.register("primordial_singularity_inversion", "multiblock")
                .setMaxIOSize(12, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        GRAVITATIONAL_WAVE_PRODUCTION = GTRecipeTypes.register("gravitational_wave_production", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        SEVENTY_TWO_CHANGES = GTRecipeTypes.register("seventy_two_changes", "single")
                .setMaxIOSize(1, 1, 0, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        CHAOS_CRAFTING = GTRecipeTypes.register("chaos_crafting", "multiblock")
                .setMaxIOSize(24, 24, 12, 12)
                .setEUIO(IO.BOTH)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        NEBULA_SIPHONING = GTRecipeTypes.register("nebula_siphoning", "multiblock")
                .setMaxIOSize(6, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        TIANJIE_NAVIGATION = GTRecipeTypes.register("tianjie_navigation", "multiblock")
                .setMaxIOSize(6, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        GRAVITATIONAL_WAVE_CONSUMPTION = GTRecipeTypes.register("gravitational_wave_consumption", "multiblock")
                .setMaxIOSize(1, 0, 1, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        TAIXU_SMELTING = GTRecipeTypes.register("taixu_smelting", "multiblock")
                .setMaxIOSize(2, 2, 1, 1)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        MATTER_AGGREGATION = GTRecipeTypes.register("matter_aggregation", "single")
                .setMaxIOSize(2, 2, 0, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        ZERO_POINT_CONVERSION = GTRecipeTypes.register("zero_point_conversion", "single")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PHOTON_SIPHON = GTRecipeTypes.register("photon_siphon", "single")
                .setMaxIOSize(4, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        WORLDLINE_OSCILLATION_COLLECTION = GTRecipeTypes.register("worldline_oscillation_collection", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        INTERSTELLAR_MATTER_ABSORPTION = GTRecipeTypes.register("interstellar_matter_absorption", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        MATTER_FLOW_CONDENSATION = GTRecipeTypes.register("matter_flow_condensation", "multiblock")
                .setMaxIOSize(4, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PRIMORDIAL_ENERGY_ABSORPTION = GTRecipeTypes.register("primordial_energy_absorption", "multiblock")
                .setMaxIOSize(1, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PHOTON_SEPARATION = GTRecipeTypes.register("photon_separation", "multiblock")
                .setMaxIOSize(2, 4, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        MATTER_MODULE_CASTING = GTRecipeTypes.register("matter_module_casting", "multiblock")
                .setMaxIOSize(15, 6, 6, 6)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setHasResearchSlot(true)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        MATTER_FORGING = GTRecipeTypes.register("matter_forging", "multiblock")
                .setMaxIOSize(4, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        WL_BOARD_CIRCUIT_ASSEMBLY = GTRecipeTypes.register("wl_board_circuit_assembly", "multiblock")
                .setMaxIOSize(9, 3, 6, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_CIRCUIT, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.CIRCUIT_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.CIRCUIT_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        WL_BOARD_WAFER_ETCHING = GTRecipeTypes.register("wl_board_wafer_etching", "multiblock")
                .setMaxIOSize(6, 3, 4, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_CIRCUIT, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.CIRCUIT_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        // ========== 世线裂解枢纽 ==========
        WORLDLINE_PROBABILITY_CRACKING = GTRecipeTypes.register("worldline_probability_cracking", "multiblock")
                .setMaxIOSize(6, 9, 4, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        WORLDLINE_MATTER_RECURRENCE = GTRecipeTypes.register("worldline_matter_recurrence", "multiblock")
                .setMaxIOSize(9, 6, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        WORLDLINE_SAMPLING = GTRecipeTypes.register("worldline_sampling", "multiblock")
                .setMaxIOSize(3, 12, 3, 6)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        // ========== 原初世线切割核心 ==========
        WORLDLINE_CUTTING = GTRecipeTypes.register("worldline_cutting", "multiblock")
                .setMaxIOSize(6, 6, 4, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        HIGH_DIMENSIONAL_FRAGMENT_CUTTING = GTRecipeTypes.register("high_dimensional_fragment_cutting", "multiblock")
                .setMaxIOSize(4, 9, 2, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        BLACK_HOLE_COMPRESSOR = GTRecipeTypes.register("black_hole_compressor", "multiblock")
                .setMaxIOSize(9, 6, 6, 5)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_COMPRESS, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        BLACK_HOLE_NEUTRONIUM_COMPRESSOR = GTRecipeTypes.register("black_hole_neutronium_compressor", "multiblock")
                .setMaxIOSize(9, 6, 6, 5)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_COMPRESS, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        BLACK_HOLE_EVENT_HORIZON_BLAST = GTRecipeTypes.register("black_hole_event_horizon_blast", "multiblock")
                .setMaxIOSize(3, 9, 3, 6)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_FUSION, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        NINE_INDUSTRIAL = GTRecipeTypes.register("nine_industrial", "multiblock")
                .setMaxIOSize(24, 24, 12, 12)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        // 36 水浒传模式显示类型 — 仅用于 Jade 机器模式展示，翻译名在 zh_cn.json
        for (int i = 0; i < 36; i++) {
            NINE_INDUSTRIAL_MODES[i] = GTRecipeTypes.register("nine_industrial_mode_" + i, "multiblock")
                    .setMaxIOSize(1, 1, 0, 0)
                    .setEUIO(IO.IN)
                    .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                    .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());
        }

        // ========== 原初铸币工厂 ==========
        COIN_FORGE = GTRecipeTypes.register("coin_forge", "multiblock")
                .setMaxIOSize(9, 6, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());

        PROXY_EXECUTION = GTRecipeTypes.register("proxy_execution", "multiblock")
                .setMaxIOSize(0, 0, 0, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(1)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST());
    }
}
