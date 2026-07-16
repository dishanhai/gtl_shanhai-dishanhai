package com.dishanhai.gt_shanhai.common.util;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import org.gtlcore.gtlcore.api.recipe.IGTRecipe;

import java.util.List;
import java.util.Map;

public final class HubMachineHelper {

    private HubMachineHelper() {}

    public static boolean hasHub(IMultiController controller) {
        try {
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
