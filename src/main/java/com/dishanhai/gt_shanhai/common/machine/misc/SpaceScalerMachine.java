package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.api.gui.configurators.SpaceScalerModeConfigurator;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;

import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import org.gtlcore.gtlcore.common.data.GTLRecipeTypes;

import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class SpaceScalerMachine extends GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine {

    private static final String[] MODE_NAMES = {
        "§6空间压缩", "§b空间提取", "§d超粒子对撞", "§c电力聚爆压缩", "§5中子态素压缩"
    };

    private static final GTRecipeType[] MODE_RECIPE_TYPES = {
        GTRecipeTypes.COMPRESSOR_RECIPES,
        GTRecipeTypes.EXTRACTOR_RECIPES,
        GTLRecipeTypes.SUPER_PARTICLE_COLLIDER_RECIPES,
        GTLRecipeTypes.ELECTRIC_IMPLOSION_COMPRESSOR_RECIPES,
        DShanhaiRecipeTypes.BLACK_HOLE_NEUTRONIUM_COMPRESSOR
    };

    @Persisted
    private int currentMode = 0;

    private int cachedTier = 1;

    public SpaceScalerMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, MODE_RECIPE_TYPES[0], args);
    }

    public int getCurrentMode() { return currentMode; }

    public void setCurrentMode(int mode) {
        if (mode >= 0 && mode < 5 && mode != this.currentMode) {
            this.currentMode = mode;
            var logic = getRecipeLogic();
            if (logic != null) logic.resetRecipeLogic();
            if (getLevel() != null && !getLevel().isClientSide) {
                notifyBlockUpdate();
            }
        }
    }

    public String getModeName() {
        return currentMode >= 0 && currentMode < MODE_NAMES.length ? MODE_NAMES[currentMode] : "未知";
    }

    @Override
    public GTRecipeType[] getRecipeTypes() {
        return new GTRecipeType[] { MODE_RECIPE_TYPES[currentMode] };
    }

    @Override
    public GTRecipeType getRecipeType() {
        return MODE_RECIPE_TYPES[currentMode];
    }

    public static GTRecipeType[] getAllRecipeTypes() {
        return MODE_RECIPE_TYPES;
    }

    // ==================== 力场发生器等级检测 ====================

    private void detectFieldGeneratorTier() {
        if (!isFormed() || getLevel() == null) {
            cachedTier = 1;
            return;
        }
        int found = 0;
        BlockPos center = getPos();
        Block fieldGen = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("kubejs", "containment_field_generator"));
        Block highField = ForgeRegistries.BLOCKS.getValue(
                new ResourceLocation("kubejs", "restraint_device"));

        BlockPos[] checkPositions = {
            center.offset(0, 4, 0), center.offset(0, -4, 0),
            center.offset(0, 0, 4), center.offset(0, 0, -4),
            center.offset(4, 0, 0), center.offset(-4, 0, 0),
            center.offset(0, 8, 0), center.offset(0, -8, 0),
        };

        for (BlockPos pos : checkPositions) {
            Block block = getLevel().getBlockState(pos).getBlock();
            if (block.equals(highField)) {
                found += 2;
            } else if (block.equals(fieldGen)) {
                found += 1;
            }
        }

        if (found >= 12) cachedTier = 3;
        else if (found >= 4) cachedTier = 2;
        else cachedTier = 1;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        detectFieldGeneratorTier();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        cachedTier = 1;
    }

    // ==================== 并行 ====================

    @Override
    public int getMaxParallel() {
        if (!isFormed()) return 1;
        int tier = cachedTier;
        return switch (tier) {
            case 3 -> 64;
            case 2 -> 16;
            default -> 4;
        };
    }

    // ==================== UI 模式切换 ====================

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        super.attachSideTabs(tabsWidget);
        tabsWidget.attachSubTab(new SpaceScalerModeConfigurator(
            MODE_NAMES, () -> currentMode, m -> setCurrentMode(m), 5));
    }

    // ==================== Jade 显示 ====================

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            textList.add(Component.literal("§6模式: " + getModeName() + " §7[" + currentMode + "/4]"));
            textList.add(Component.literal("§e力场等级: §fTier " + cachedTier
                + " §7· §b并行: §f" + getMaxParallel()));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.literal("§5空间缩放仪 §7- TST 移植").withStyle(ChatFormatting.DARK_PURPLE));
    }

    // ==================== 结构定义 ====================

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return DShanhaiMBParser.parse(
            new ResourceLocation(MOD_ID, "space_scaler"),
            c -> {
                if (c == '~') return Predicates.controller(Predicates.blocks(definition.getBlock()));
                if (c == ' ') return Predicates.any();
                if (c == 'A') return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get())
                    .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1));
                if (c == 'C' || c == 'E')
                    return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get())
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1));
                if (c == 'B') return Predicates.blocks(DShanhaiBlocks.CASING_TRANSCENDENT.get());
                if (c == 'D') return Predicates.blocks(DShanhaiBlocks.CASING_QUANTUM_GLASS.get());
                if (c == 'G') {
                    Block fieldGen = ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("kubejs", "containment_field_generator"));
                    Block highField = ForgeRegistries.BLOCKS.getValue(
                        new ResourceLocation("kubejs", "restraint_device"));
                    if (fieldGen != null && highField != null) {
                        return Predicates.blocks(fieldGen).or(Predicates.blocks(highField));
                    }
                    if (fieldGen != null) return Predicates.blocks(fieldGen);
                    if (highField != null) return Predicates.blocks(highField);
                    return Predicates.blocks(DShanhaiBlocks.CASING_MOLECULAR.get());
                }
                return null;
            }
        );
    }

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
            .multiblock("space_scaler", SpaceScalerMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeTypes(getAllRecipeTypes())
            .appearanceBlock(DShanhaiBlocks.CASING_MOLECULAR::get)
            .pattern(SpaceScalerMachine::createPattern)
            .workableCasingRenderer(
                new ResourceLocation(MOD_ID, "block/casing_molecular"),
                new ResourceLocation(MOD_ID, "block/multiblock/space_scaler"))
            .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§5§l空间缩放仪 §r§7- TST 移植"));
            tips.add(Component.literal(""));
            tips.add(Component.translatable("gt_shanhai.machine.space_scaler.tooltip.0"));
            tips.add(Component.translatable("gt_shanhai.machine.space_scaler.tooltip.1"));
            tips.add(Component.translatable("gt_shanhai.machine.space_scaler.tooltip.2"));
            tips.add(Component.translatable("gt_shanhai.machine.space_scaler.tooltip.3"));
            tips.add(Component.literal(""));
            tips.add(Component.literal("§a模式: 空间压缩/空间提取/超粒子对撞/电力聚爆压缩/中子态素压缩"));
            tips.add(Component.literal("§e力场发生器 Tier 1-3: 并行 4/16/64"));
            tips.add(Component.literal("§d31×32×31 原版 TST 结构"));
        });
        return def;
    }
}
