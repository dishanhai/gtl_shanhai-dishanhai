package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** 山海：Integrate 歸墟視界樞核，沿用 master 的機器註冊階段。 */
public final class GuixuVisionCoreMachines {
    public static final String ID = "guixu_vision_core";
    private static MultiblockMachineDefinition definition;
    public static final String CONTAINMENT_ID = "guixu_penrose_containment_field";
    private static MultiblockMachineDefinition containmentDefinition;

    private GuixuVisionCoreMachines() {}

    public static void init() {
        if (definition != null) return;
        GuixuHostGeometry.verify();
        GuixuVisionCoreMachine.assertFieldHolderComplete();
        var casing = new ResourceLocation("gtlcore", "block/casings/dimension_injection_casing");
        var overlay = GTCEu.id("block/multiblock/fusion_reactor");
        definition = GTDishanhaiRegistration.REGISTRATE
                .multiblock(ID, GuixuVisionCoreMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(DShanhaiRecipeTypes.GUIXU_MATTER_DECOMPRESSION,
                        DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION,
                        DShanhaiRecipeTypes.GUIXU_PARADIGM_SIMPLIFICATION)
                .pattern(GuixuVisionCoreStructure::createPattern)
                .workableCasingRenderer(casing, overlay)
                .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuHostFxRenderer(casing, overlay))
                .hasTESR(true)
                .tooltips(
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.0"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.1"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.2"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.3"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.4"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.5"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.6"),
                        Component.translatable("gt_shanhai.multiblock.guixu_vision_core.tooltip.7"))
                .appearanceBlock(GuixuVisionCoreStructure::appearanceBlock)
                .register();
        if (FMLEnvironment.dist.isClient()) {
            var renderer = definition.getRenderer();
            if (!renderer.getClass().getName().equals(
                    "com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuHostFxRenderer")) {
                throw new IllegalStateException("[SHANHAI-HOST] 动态特效渲染器未挂上：" + renderer.getClass().getName());
            }
        }
        GTDishanhaiMod.LOGGER.info("[SHANHAI-HOST] 已注册 {}", ID);
        registerContainment(overlay);
    }

    /** 歸墟彭羅斯黑洞遏制場：被動模組，掛 DUMMY 配方類型只為滿足多方塊註冊。 */
    private static void registerContainment(ResourceLocation overlay) {
        containmentDefinition = GTDishanhaiRegistration.REGISTRATE
                .multiblock(CONTAINMENT_ID, GuixuPenroseContainmentModule::new)
                .rotationState(RotationState.ALL)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .pattern(GuixuPenroseContainmentStructure::createPattern)
                .appearanceBlock(GuixuPenroseContainmentStructure::appearanceBlock)
                .workableCasingRenderer(new ResourceLocation("kubejs", "block/hollow_casing"), overlay)
                .register();
        containmentDefinition.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§7需安装在归墟视界枢核的模组位"));
            tooltips.add(Component.literal("§7投入黑洞种子开启；每秒消耗时空流体维持稳定"));
            tooltips.add(Component.literal("§d遏制中：整台归墟并行 ×4、耗时 ×0.5、增产 +5、耗电 ×0.5、物质模块数量 ×2"));
            tooltips.add(Component.literal("§d稳定度低于 50／20 时，并行与速度各再 ×2"));
            tooltips.add(Component.literal("§c失稳会吞噬产出；坍缩时整台归墟停机 60 秒"));
            tooltips.add(Component.literal("§7投入黑洞坍缩器可安全关闭"));
        });
        GTDishanhaiMod.LOGGER.info("[SHANHAI-CONTAINMENT] 已注册 {}", CONTAINMENT_ID);
    }

    public static MultiblockMachineDefinition guixuPenroseContainment() {
        return containmentDefinition;
    }

    public static MultiblockMachineDefinition guixuVisionCore() {
        return definition;
    }
}
