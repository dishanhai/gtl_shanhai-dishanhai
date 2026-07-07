package com.dishanhai.gt_shanhai.common.machine.trait;

import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import java.util.Collections;
import java.util.List;

/**
 * 无限算力容器——受电压绕过开关控制。
 * handleRecipeInner 返回 null 表示"没有剩余需求"，即全部 CWU 已被满足。
 */
public class InfiniteCWUContainer extends NotifiableRecipeHandlerTrait<Integer> {

    public InfiniteCWUContainer(MetaMachine machine) {
        super(machine);
    }

    @Override
    public IO getHandlerIO() {
        return IO.IN;
    }

    @Override
    public RecipeCapability<Integer> getCapability() {
        return CWURecipeCapability.CAP;
    }

    @Override
    public List<Integer> handleRecipeInner(IO io, GTRecipe recipe, List<Integer> left,
                                            String slotName, boolean simulate) {
        // 电压绕过关闭时不提供无限算力
        if (getMachine() instanceof com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine hatch
                && !hatch.isVoltageBypassEnabled()) {
            return left;
        }
        return null;
    }

    @Override
    public List<Object> getContents() {
        return Collections.emptyList();
    }

    @Override
    public double getTotalContentAmount() {
        if (getMachine() instanceof com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine hatch
                && !hatch.isVoltageBypassEnabled()) {
            return 0;
        }
        return Integer.MAX_VALUE;
    }
}
