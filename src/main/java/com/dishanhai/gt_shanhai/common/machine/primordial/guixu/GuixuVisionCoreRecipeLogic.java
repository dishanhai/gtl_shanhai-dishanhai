package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

/** 山海：在原生無線 EU/t 讀點減免，之後再由原生聚合並行數與耗時。 */
public class GuixuVisionCoreRecipeLogic extends PrimordialOmegaEngineRecipeLogic {
    private final GuixuVisionCoreMachine host;

    public GuixuVisionCoreRecipeLogic(GuixuVisionCoreMachine host) {
        super(host);
        this.host = host;
    }

    @Override
    protected long getWirelessRecipeEut(GTRecipe recipe) {
        // 父類 getTotalEuOfRecipe 恆回傳 0；buildFinalWirelessRecipe 實際讀的是此方法。
        return GuixuRecipeMath.reducedEnergyEut(super.getWirelessRecipeEut(recipe), host.moduleSlotBonus());
    }
}
