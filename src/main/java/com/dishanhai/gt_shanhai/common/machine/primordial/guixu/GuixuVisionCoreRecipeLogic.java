package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gtladd.gtladditions.api.machine.IWirelessElectricMultiblockMachine;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import java.util.Objects;

/** 山海：在原生無線 EU/t 讀點減免，之後再由原生聚合並行數與耗時。 */
public class GuixuVisionCoreRecipeLogic extends PrimordialOmegaEngineRecipeLogic {
    private final GuixuVisionCoreMachine host;

    public GuixuVisionCoreRecipeLogic(GuixuVisionCoreMachine host) {
        super(host);
        this.host = host;
    }

    @Override
    protected long getWirelessRecipeEut(GTRecipe recipe) {
        if (recipe.recipeType == DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION) return RecipeHelper.getOutputEUt(recipe);
        // 父類 getTotalEuOfRecipe 恆回傳 0；buildFinalWirelessRecipe 實際讀的是此方法。
        return GuixuRecipeMath.reducedEnergyEut(super.getWirelessRecipeEut(recipe), host.moduleSlotBonus());
    }

    @Override
    protected boolean isEnergyConsumer() {
        return host.getRecipeType() != DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION;
    }

    @Override
    public IWirelessElectricMultiblockMachine getWirelessMachine() { return host; }

    @Override
    protected void doDamping() {
        // 發電已逐 tick 入倉，等待時不得倒退進度，否則同一批燃料會重產已給出的 EU。
        if (getLastRecipe() instanceof IWirelessGTRecipe wireless
                && GuixuEnergyTransfer.generatesEnergy(wireless.getWirelessEuTickInputs())) return;
        super.doDamping();
    }

    @Override
    public void setWaiting(Component reason) {
        // 原生 Mixin 在無線扣存失敗後統一寫「輸入能源不足」；只校正本次真實交易失敗。
        if (host != null && Objects.equals(reason, RecipeResult.FAIL_NO_ENOUGH_EU_IN.reason())) {
            reason = switch (host.getWirelessNetworkEnergyHandler().getLastFailure()) {
                case INPUT -> Component.literal("能源倉可用 EU 不足");
                case OUTPUT -> Component.literal("動力倉可用容量不足");
                case TRANSACTION -> Component.literal("能源倉未完成整筆結算，已回滾");
                case DISCONNECTED -> Component.literal("主機能源接口未就緒");
                default -> reason;
            };
        }
        super.setWaiting(reason);
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (host.getOffsetTimer() % 10 == 0) host.refreshOperationState();
    }
}
