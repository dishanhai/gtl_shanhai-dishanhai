package com.dishanhai.gt_shanhai.common.util;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import org.gtlcore.gtlcore.api.recipe.IGTRecipe;

import java.util.List;
import java.util.Map;

public final class HubMachineHelper {

    private HubMachineHelper() {}

    /**
     * 主机上是否装有终焉聚合枢纽，且配置未禁用枢纽功能。
     * <p>
     * 配置检查收在这里而不是各调用点：此前这段逻辑在 4 个 mixin 里各抄了一份，
     * 结果 {@code maintenanceHatchEnabled} 只补进了其中一部分，管理员把开关设为 false 后
     * 星阵压缩、太虚锻炉锁定等绕过仍然生效。所有调用点统一走本方法即可自动继承开关。
     * <p>
     * 注意：本方法要完整遍历 {@code getParts()}，大型多方块上部件数以百计，
     * 调用点若位于每 tick 路径必须先做节流或缓存。
     */
    public static boolean hasHub(IMultiController controller) {
        try {
            if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) {
                return false;
            }
            if (controller == null || !controller.isFormed()) {
                return false;
            }
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean hasChanceBypass(IMultiController controller) {
        try {
            if (controller == null || !controller.isFormed()) {
                return false;
            }
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine hatch
                        && hatch.isChanceBypassEnabled()) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 在并行逻辑折算概率产出前提升概率。必须复制配方，避免污染注册表中的原始配方。
     */
    public static GTRecipe forceFullOutputChance(GTRecipe recipe) {
        GTRecipe copy = recipe.copy();
        forceFullChance(copy.outputs);
        forceFullChance(copy.tickOutputs);
        copy.ocTier = recipe.ocTier;
        if (recipe instanceof IGTRecipe source && copy instanceof IGTRecipe target) {
            target.setRealParallels(source.getRealParallels());
        }
        return copy;
    }

    static void forceFullChance(Map<?, List<Content>> contents) {
        for (List<Content> list : contents.values()) {
            if (list == null) continue;
            for (Content content : list) {
                if (content != null) {
                    content.chance = content.maxChance;
                }
            }
        }
    }
}
