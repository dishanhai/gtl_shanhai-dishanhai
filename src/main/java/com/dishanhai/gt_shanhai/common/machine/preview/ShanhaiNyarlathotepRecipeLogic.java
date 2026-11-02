package com.dishanhai.gt_shanhai.common.machine.preview;

import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.forge.PrimordialCoinForgeLogic;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuPowerBudget;
import com.gtladd.gtladditions.common.data.ParallelData;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;

/** 山海：沿用原初／鑄幣門檻，以自身倉口限制並行，廣播必須實際扣料成功。 */
public final class ShanhaiNyarlathotepRecipeLogic extends PrimordialCoinForgeLogic {
    private static final String BROADCAST_KEY = "sh_nyarlathotep_broadcast";
    private boolean buildingBroadcast;

    public ShanhaiNyarlathotepRecipeLogic(ShanhaiNyarlathotepPreviewMachine machine) { super(machine); }

    @Override protected boolean checkRecipe(GTRecipe recipe) {
        return recipe != null && !(recipe.recipeType == DShanhaiRecipeTypes.COIN_FORGE
                && DShanhaiConfig.isCoinForgeDisabled()) && super.checkRecipe(recipe);
    }
    @Override protected Set<GTRecipe> lookupRecipeIterator() {
        Set<GTRecipe> candidates = super.lookupRecipeIterator();
        if (!DShanhaiConfig.isCoinForgeDisabled()) return candidates;
        Set<GTRecipe> allowed = new LinkedHashSet<>(candidates);
        allowed.removeIf(recipe -> recipe.recipeType == DShanhaiRecipeTypes.COIN_FORGE);
        return allowed;
    }
    private int batchTicks() { return Math.max(1, containmentBonus().scaleDuration(getLimited().getLimitedDuration())); }
    private BigInteger localBudget() {
        return getMachine().getWirelessNetworkEnergyHandler().getMaxAvailableEnergy().multiply(BigInteger.valueOf(batchTicks()));
    }

    @Override protected long getTotalParallelLimit() {
        long coinCapacity = super.getTotalParallelLimit();
        if (((ShanhaiNyarlathotepPreviewMachine) getMachine()).hasParallelOverdriver()) return coinCapacity;
        for (var type : getLookupRecipeTypes((ShanhaiNyarlathotepPreviewMachine) getMachine())) {
            if (type != DShanhaiRecipeTypes.COIN_FORGE)
                return coinCapacity > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : coinCapacity * 4;
        }
        return coinCapacity;
    }

    @Override public long getMaxParallel(GTRecipe recipe, long limit) {
        if (recipe == null || limit <= 0) return 0;
        // 混選時 UI／總池顯示普通容量，鑄幣仍受同一1/4上限約束。
        if (recipe.recipeType == DShanhaiRecipeTypes.COIN_FORGE)
            limit = Math.min(limit, super.getTotalParallelLimit());
        double factor = getEuMultiplier() * containmentBonus().energyFactor(true);
        BigInteger cost = BigDecimal.valueOf(Math.max(0L, getWirelessRecipeEut(recipe)))
                .multiply(BigDecimal.valueOf(Math.max(1, recipe.duration)))
                .multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.CEILING).toBigInteger();
        if (cost.signum() > 0) limit = Math.min(limit, localBudget().divide(cost).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
        return super.getMaxParallel(recipe, limit);
    }

    @Override protected void onBatchRecipeProcessed(GTRecipe recipe, long parallel) {
        if (parallel > 0 && recipe.recipeType == DShanhaiRecipeTypes.GRAVITATIONAL_WAVE_CONSUMPTION)
            buildingBroadcast = true;
    }
    @Override protected boolean allowsEmptyRecipeOutputs() { return buildingBroadcast; }

    @Override protected WirelessGTRecipe buildFinalWirelessRecipe(ParallelData data, IWirelessNetworkEnergyHandler local) {
        buildingBroadcast = false;
        // 既有演算法比較整批 EU，但實際由 handler 每 tick 扣本機能源。預算與批次 tick 同量綱。
        IWirelessNetworkEnergyHandler budget = new IWirelessNetworkEnergyHandler() {
            @Override public boolean isOnline() { return local.isOnline(); }
            @Override public BigInteger getMaxAvailableEnergy() { return localBudget(); }
            @Override public boolean consumeEnergy(int eu) { return local.consumeEnergy(eu); }
            @Override public boolean consumeEnergy(long eu) { return local.consumeEnergy(eu); }
            @Override public boolean consumeEnergy(BigInteger eu) { return local.consumeEnergy(eu); }
        };
        try {
            WirelessGTRecipe result = super.buildFinalWirelessRecipe(data, budget);
            if (result != null) result.data.putBoolean(BROADCAST_KEY, buildingBroadcast);
            return result;
        } finally { buildingBroadcast = false; }
    }
    @Override protected WirelessGTRecipe buildWirelessRecipe(ObjectArrayList<Content> items,
                                                            ObjectArrayList<Content> fluids, BigInteger totalEu) {
        // 既有 helper 以整數除法分攤，低耗能配方不可被截成每 tick 0 EU。
        return super.buildWirelessRecipe(items, fluids, GuixuPowerBudget.roundForTicks(totalEu, batchTicks()));
    }
    public boolean isBroadcastBatch() {
        GTRecipe recipe = getLastRecipe();
        return recipe != null && recipe.data.getBoolean(BROADCAST_KEY);
    }

    @Override public void handleRecipeWorking() {
        super.handleRecipeWorking();
        ((ShanhaiNyarlathotepPreviewMachine) getMachine()).updateBroadcast();
    }

    @Override public boolean handleWirelessTickInput(IWirelessGTRecipe recipe) {
        return ((ShanhaiLocalEnergyHandler) getMachine().getWirelessNetworkEnergyHandler()).consumeRecipeTick(recipe);
    }
}
