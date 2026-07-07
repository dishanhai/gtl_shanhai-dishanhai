package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.machine.DShanhaiBroadcastSourceMachine;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class TianjieNavigationTowerMachine extends DShanhaiBroadcastSourceMachine {
    private static final GTRecipeType[] RECIPE_TYPES = { DShanhaiRecipeTypes.TIANJIE_NAVIGATION };

    public TianjieNavigationTowerMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public int getMaxParallel() {
        return 32768;
    }

    @Override
    protected int getShanhaiBroadcastRadius() {
        return 500;
    }

    @Override
    protected int getShanhaiFixedOutputMultiplier() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        if (isFormed()) {
            textList.add(Component.literal("§b天界领航塔 §7· §e并行: §f" + getMaxParallel()));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        }
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block hassium = getBlock("gtceu:hassium_block");
        Block powerCore = getBlock("gtlcore:power_core");
        Block glass = getBlock("minecraft:light_blue_stained_glass");
        Block blackstone = getBlock("minecraft:polished_blackstone");
        Block seaLantern = getBlock("minecraft:sea_lantern");
        Block hatchCasing = getBlock("gtlcore:dimensionally_transcendent_casing");

        return DShanhaiMBParser.parseSequence(
                java.util.Collections.singletonList(new ResourceLocation(MOD_ID, "tianjie_navigation_tower")),
                c -> {
                    if (c == '~') return Predicates.controller(Predicates.blocks(definition.getBlock()));
                    if (c == 'A') return Predicates.blocks(hassium);
                    if (c == 'B') return Predicates.blocks(powerCore);
                    if (c == 'C') return Predicates.blocks(glass);
                    if (c == 'D') return Predicates.blocks(blackstone);
                    if (c == 'E') return Predicates.blocks(seaLantern);
                    if (c == 'H') return Predicates.blocks(hatchCasing)
                            .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                            .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1));
                    return null;
                },
                false,
                RelativeDirection.BACK,
                RelativeDirection.RIGHT,
                RelativeDirection.UP
        );
    }

    private static Block getBlock(String id) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
    }

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
                .multiblock("tianjie_navigation_tower", TianjieNavigationTowerMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(RECIPE_TYPES)
                .appearanceBlock(() -> getBlock("gtlcore:dimensionally_transcendent_casing"))
                .pattern(TianjieNavigationTowerMachine::createPattern)
                .workableCasingRenderer(
                        new ResourceLocation("gtlcore", "block/casings/dimensionally_transcendent_casing"),
                        new ResourceLocation(MOD_ID, "block/multiblock/nebula_siphon"))
                .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(DShanhaiTextUtil.createAuroraText("天界领航塔"));
            tips.add(Component.literal("§7以天界巨构为锚点，引导多维星穹零点聚合流程"));
            tips.add(Component.literal("§7结构来源: tianjie2.schem，oak_log 为主机占位"));
            tips.add(Component.literal("§b8 个维度超越机壳位置可替换为输入/输出/能源仓室"));
            tips.add(Component.literal("§a配方类型: 宇宙修改·天界领航"));
            tips.add(Component.literal("§e并行: 32768"));
            tips.add(Component.literal("§8隐藏效果: 周围 500 格机器配方产出倍率拉满"));
        });
        return def;
    }
}
