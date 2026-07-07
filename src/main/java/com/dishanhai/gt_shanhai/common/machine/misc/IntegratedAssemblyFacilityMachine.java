package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;

import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.data.GCyMRecipeTypes;

import org.gtlcore.gtlcore.common.data.GTLRecipeTypes;

import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class IntegratedAssemblyFacilityMachine extends GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine {

    private static final GTRecipeType[] RECIPE_TYPES = {
        GTRecipeTypes.ASSEMBLER_RECIPES,
        GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES,
        GTRecipeTypes.ASSEMBLY_LINE_RECIPES,
        GTLRecipeTypes.CIRCUIT_ASSEMBLY_LINE_RECIPES,
        GTLRecipeTypes.COMPONENT_ASSEMBLY_LINE_RECIPES,
        GTLRecipeTypes.PRECISION_ASSEMBLER_RECIPES,
        GTLRecipeTypes.SUPRACHRONAL_ASSEMBLY_LINE_RECIPES,
        GCyMRecipeTypes.ALLOY_BLAST_RECIPES
    };

    private int cachedTier = 1;

    public IntegratedAssemblyFacilityMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, GTRecipeTypes.ASSEMBLER_RECIPES, args);
    }

    @Override
    public GTRecipeType[] getRecipeTypes() {
        return RECIPE_TYPES;
    }

    @Override
    public GTRecipeType getRecipeType() {
        return GTRecipeTypes.ASSEMBLER_RECIPES;
    }

    // ==================== 结构等级检测 ====================

    private void detectStructureTier() {
        if (!isFormed() || getLevel() == null) {
            cachedTier = 1;
            return;
        }
        int found = 0;
        BlockPos center = getPos();
        Block tier2Block = DShanhaiBlocks.CASING_TRANSCENDENT.get();
        Block tier3Block = DShanhaiBlocks.CASING_RHENIUM.get();

        BlockPos[] checkPositions = {
            center.offset(2, 2, 2), center.offset(-2, 2, 2),
            center.offset(2, -2, 2), center.offset(-2, -2, 2),
            center.offset(2, 2, -2), center.offset(-2, 2, -2),
            center.offset(2, -2, -2), center.offset(-2, -2, -2)
        };

        for (BlockPos pos : checkPositions) {
            Block block = getLevel().getBlockState(pos).getBlock();
            if (block.equals(tier3Block)) found += 2;
            else if (block.equals(tier2Block)) found += 1;
        }

        if (found >= 12) cachedTier = 3;
        else if (found >= 6) cachedTier = 2;
        else cachedTier = 1;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        detectStructureTier();
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
        return switch (cachedTier) {
            case 3 -> 512;
            case 2 -> 128;
            default -> 32;
        };
    }

    // ==================== Jade 显示 ====================

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            textList.add(Component.literal("§e结构等级: §fTier " + cachedTier
                + " §7· §b并行: §f" + getMaxParallel()));
            textList.add(Component.literal("§7综合8种装配/装配线配方类型"));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.literal("§6综合组装车间 §7- GTNL 移植").withStyle(ChatFormatting.GOLD));
    }

    // ==================== 结构定义 ====================

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return DShanhaiMBParser.parseSequence(
            java.util.Collections.singletonList(new ResourceLocation(MOD_ID, "integrated_assembly_facility")),
            c -> {
                if (c == '~') return Predicates.controller(Predicates.blocks(definition.getBlock()));
                // A = 中子素管道外壳 → kubejs:neutronium_pipe_casing
                if (c == 'A') return Predicates.blocks(getBlock("kubejs", "neutronium_pipe_casing"))
                    .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1));
                // B = 部件装配线外壳 → gtlcore:component_assembly_line_casing_uhv
                if (c == 'B') return Predicates.blocks(getBlock("gtlcore", "component_assembly_line_casing_uhv"));
                // C = 强化镀铱机械方块 → gtceu:high_power_casing
                if (c == 'C') return Predicates.blocks(getBlock("gtceu", "high_power_casing"));
                // D = 处理器机械方块 → gtceu:computer_casing
                if (c == 'D') return Predicates.blocks(getBlock("gtceu", "computer_casing"));
                // E = 高级过滤外壳 → gtceu:sterilizing_filter_casing
                if (c == 'E') return Predicates.blocks(getBlock("gtceu", "sterilizing_filter_casing"));
                // F = 增强光刻框架 → gtceu:advanced_computer_casing
                if (c == 'F') return Predicates.blocks(getBlock("gtceu", "advanced_computer_casing"));
                // G = 中子素框架（山海注册）
                if (c == 'G') return Predicates.blocks(DShanhaiBlocks.NEUTRONIUM_FRAME.get());
                // H = 极端密度外壳 → kubejs:high_strength_concrete
                if (c == 'H') return Predicates.blocks(getBlock("kubejs", "high_strength_concrete"));
                // I = 屏蔽加速器外壳（山海注册，Lanth 原版材质）
                if (c == 'I') return Predicates.blocks(DShanhaiBlocks.SHIELDED_ACCELERATOR_CASING.get());
                // J = 铌腔体外壳（山海注册，Lanth 原版材质）
                if (c == 'J') return Predicates.blocks(DShanhaiBlocks.NIOBIUM_CAVITY_CASING.get());
                // K = 装配线外壳 → gtceu:assembly_line_casing
                if (c == 'K') return Predicates.blocks(getBlock("gtceu", "assembly_line_casing"));
                // L = 冷却剂输送外壳（山海注册，Lanth 原版材质）
                if (c == 'L') return Predicates.blocks(DShanhaiBlocks.COOLANT_DELIVERY_CASING.get());
                // M = 分子装配器外壳 → gtlcore:molecular_casing
                if (c == 'M') return Predicates.blocks(getBlock("gtlcore", "molecular_casing"));
                // N = 玻璃链 → gtceu:fusion_glass
                if (c == 'N') return Predicates.blocks(getBlock("gtceu", "fusion_glass"));
                // O = 发光方块 → gtceu:heat_vent
                if (c == 'O') return Predicates.blocks(getBlock("gtceu", "heat_vent"));
                // P = Hermetic IX → gtceu:uhv_hermetic_casing
                if (c == 'P') return Predicates.blocks(getBlock("gtceu", "uhv_hermetic_casing"));
                // Q = 中子素齿轮箱（山海注册）
                if (c == 'Q') return Predicates.blocks(DShanhaiBlocks.NEUTRONIUM_GEARBOX.get());
                // R = 宇宙中子素框架（山海注册）
                if (c == 'R') return Predicates.blocks(DShanhaiBlocks.COSMIC_NEUTRONIUM_FRAME.get());
                return null;
            },
            false,
            RelativeDirection.FRONT, RelativeDirection.UP, RelativeDirection.RIGHT
        );
    }

    private static Block getBlock(String modId, String name) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(modId, name));
    }

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
            .multiblock("integrated_assembly_facility", IntegratedAssemblyFacilityMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeTypes(RECIPE_TYPES)
            .appearanceBlock(DShanhaiBlocks.CASING_ASSEMBLY::get)
            .pattern(IntegratedAssemblyFacilityMachine::createPattern)
            .workableCasingRenderer(
                new ResourceLocation(MOD_ID, "block/casing_assembly"),
                new ResourceLocation(MOD_ID, "block/multiblock/integrated_assembly_facility"))
            .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§6§l综合组装车间 §r§7- GTNL 移植"));
            tips.add(Component.literal(""));
            tips.add(Component.literal("§7集成8种装配与装配线类配方"));
            tips.add(Component.literal("§b综合: 组装/电路组装/装配线/精密组装/超时空装配/合金高炉"));
            tips.add(Component.literal("§e结构等级 Tier 1-3: 并行 32/128/512"));
            tips.add(Component.literal("§d量子玻璃装饰，静音运行"));
        });
        return def;
    }
}
