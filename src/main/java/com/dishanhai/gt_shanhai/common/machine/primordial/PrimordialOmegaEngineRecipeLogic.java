package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentBonus;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuRecipeMath;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.chat.Component;

import java.math.BigInteger;

import java.util.Set;

public class PrimordialOmegaEngineRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {

    private static final long ACTIVE_LOOKUP_CACHE_TICKS = 100L;

    private final AmplifiedCandidateCache<GTRecipe> amplifiedCandidates = new AmplifiedCandidateCache<>();

    public PrimordialOmegaEngineRecipeLogic(SelectableRecipeTypeSetMachine machine) {
        super(machine);
    }

    @Override
    protected long getLookupCacheTicks() {
        return ACTIVE_LOOKUP_CACHE_TICKS;
    }

    @Override
    public int getMultipleThreads() {
        return Integer.MAX_VALUE;
    }

    /** 歸墟自身由子類套用；此處只處理被鏈接的普通引擎，不能讓增益重算兩次。 */
    private GuixuVisionCoreMachine linkedGuixu() {
        return getMachine() instanceof PrimordialOmegaEngineMachine engine ? engine.getLinkedGuixuHost() : null;
    }

    private GuixuContainmentBonus linkedContainmentBonus() {
        var upstream = linkedGuixu();
        return upstream == null ? GuixuContainmentBonus.NONE : upstream.getContainmentBonus();
    }

    @Override
    public void findAndHandleRecipe() {
        if (pauseForLinkedCollapse()) return;
        super.findAndHandleRecipe();
    }

    @Override
    public void handleRecipeWorking() {
        if (pauseForLinkedCollapse()) return;
        super.handleRecipeWorking();
    }

    private boolean pauseForLinkedCollapse() {
        if (!linkedContainmentBonus().locked()) return false;
        setWaiting(Component.translatable("gt_shanhai.machine.primordial_myriad_proliferation_core.link_locked"));
        return true;
    }

    @Override
    protected long getTotalParallelLimit() {
        return linkedContainmentBonus().scaleParallel(super.getTotalParallelLimit());
    }

    @Override
    protected long getWirelessRecipeEut(GTRecipe recipe) {
        long original = super.getWirelessRecipeEut(recipe);
        var upstream = linkedGuixu();
        return upstream == null || !isEnergyConsumer() ? original : upstream.getContainmentBonus().scaleEnergy(
                GuixuRecipeMath.reducedEnergyEut(original, upstream.moduleSlotBonus()));
    }

    @Override
    protected WirelessGTRecipe buildWirelessRecipe(ObjectArrayList<Content> items, ObjectArrayList<Content> fluids,
                                                   BigInteger totalEu) {
        var upstream = linkedGuixu();
        if (upstream == null) return super.buildWirelessRecipe(items, fluids, totalEu);
        var bonus = upstream.getContainmentBonus();
        if (isEnergyConsumer() && bonus.shouldSwallow(java.util.concurrent.ThreadLocalRandom.current().nextDouble())) {
            items.clear();
            fluids.clear();
        }
        int ticks = bonus.scaleDuration(getLimited().getLimitedDuration());
        return RecipeCalculationHelper.INSTANCE.buildWirelessRecipe(items, fluids, ticks, totalEu, GTRecipeTypes.DUMMY_RECIPES);
    }

    /**
     * 主機也必須吃到已安裝模組提供的產出倍率。
     *
     * <p>倍率在候選配方階段套用，父類後續的並行上限、輸出容量檢查和無線配方建構
     * 才會全部以放大後的輸出為準；若在最終無線配方建立後才複製，會丟失
     * {@code WirelessGTRecipe} 的能源封裝。</p>
     *
     * <p>放大是整集 copy，不能每次查找都重做：基類在快取窗口內回傳同一個候選集實例，
     * 實例與倍率都沒變時直接沿用上次的放大結果。</p>
     */
    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        Set<GTRecipe> recipes = super.lookupRecipeIterator();
        if (recipes.isEmpty() || !(getMachine() instanceof PrimordialOmegaEngineMachine engine)) {
            return recipes;
        }
        int multiplier = engine.getRecipeOutputMultiplier();
        if (multiplier <= 1) {
            return recipes;
        }
        return amplifiedCandidates.get(recipes, multiplier, PrimordialRecipeOutputAmplifier::apply);
    }
}
