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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/** 山海：在原生無線 EU/t 讀點減免，之後再由原生聚合並行數與耗時。 */
public class GuixuVisionCoreRecipeLogic extends PrimordialOmegaEngineRecipeLogic {
    private final GuixuVisionCoreMachine host;
    private int plannedTicks = 1;
    private int plannedBaseTicks = 1;
    private long plannedParallels;
    private boolean plannedConsumer = true;
    private boolean preferGeneration;

    public GuixuVisionCoreRecipeLogic(GuixuVisionCoreMachine host) {
        super(host);
        this.host = host;
    }

    /** 遏制場坍縮鎖定期間不開新批次。 */
    @Override
    public void findAndHandleRecipe() {
        if (host.isContainmentLocked()) {
            setWaiting(Component.literal("黑洞坍縮，鎖定剩餘 " + host.getContainmentLockSeconds() + " 秒"));
            return;
        }
        super.findAndHandleRecipe();
    }

    @Override
    protected long getTotalParallelLimit() {
        return host.getContainmentBonus().scaleParallel(super.getTotalParallelLimit());
    }

    /** 先規劃整批共享功率，原生 buildFinalWirelessRecipe 才真正扣料。 */
    @Override
    protected ParallelData calculateParallels() {
        var candidates = new ArrayList<>(lookupRecipeIterator());
        candidates.sort(Comparator.comparing(recipe -> recipe.id.toString()));
        // 原生一次只封裝一個 EU 方向；輪轉類型時不能把燃料發電與耗電配方混成一批。
        for (boolean consumer : new boolean[]{!preferGeneration, preferGeneration}) {
            plannedConsumer = consumer;
            ParallelData plan = planDirection(consumer, candidates);
            if (plan != null) {
                preferGeneration = consumer;
                return plan;
            }
        }
        invalidateLookupSetCache();
        return null;
    }

    private ParallelData planDirection(boolean consumer, java.util.List<GTRecipe> candidates) {
        BigInteger power = getDirectionalHatchPower(consumer);
        plannedParallels = 0L;
        if (power.signum() <= 0) return null;
        var recipes = new ArrayList<GTRecipe>();
        var costs = new ArrayList<BigInteger>();
        BigInteger referenceEut = BigInteger.ONE;
        int baseTicks = 1;
        // 穩定次序使剩餘預算分配不受 HashSet 迭代次序影響。
        for (GTRecipe recipe : candidates) {
            if (recipeConsumesEnergy(recipe) != consumer) continue;
            BigInteger cost = BigDecimal.valueOf((double) Math.max(1, recipe.duration) * getEuMultiplier())
                    .multiply(BigDecimal.valueOf(Math.max(0L, getWirelessRecipeEut(recipe))))
                    .setScale(0, RoundingMode.CEILING).toBigIntegerExact().max(BigInteger.ONE);
            BigInteger eut = GuixuPowerBudget.ceilDivide(cost, BigInteger.valueOf(Math.max(1, recipe.duration)));
            if (eut.compareTo(power) > 0 || getMaxParallel(recipe, 1L) <= 0) continue;
            recipes.add(recipe);
            costs.add(cost);
            referenceEut = referenceEut.max(eut);
            baseTicks = Math.max(baseTicks, recipe.duration);
        }
        if (recipes.isEmpty()) {
            invalidateLookupSetCache();
            return null;
        }
        plannedBaseTicks = baseTicks;
        plannedTicks = GuixuPowerBudget.duration(power, referenceEut, baseTicks, host.getLimitedDuration());
        BigInteger stored = consumer ? host.getWirelessNetworkEnergyHandler().getMaxAvailableEnergy() : null;
        BigInteger budget = power.multiply(BigInteger.valueOf(plannedTicks));
        if (stored != null) budget = budget.min(stored);
        long limit = GuixuPowerBudget.parallelLimit(power, referenceEut, baseTicks,
                host.getLimitedDuration(), getTotalParallelLimit());
        long[] wants = new long[recipes.size()];
        for (int i = 0; i < wants.length; i++) {
            long affordable = budget.divide(costs.get(i)).min(BigInteger.valueOf(limit)).longValueExact();
            wants[i] = affordable <= 0L ? 0L : getMaxParallel(recipes.get(i), affordable);
        }
        long[] parallels = GuixuPowerBudget.allocate(power, plannedTicks, costs.toArray(BigInteger[]::new),
                wants, stored, limit);
        var selected = new ArrayList<GTRecipe>();
        var amounts = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int i = 0; i < parallels.length; i++) {
            if (parallels[i] <= 0L) continue;
            selected.add(recipes.get(i));
            amounts.add(parallels[i]);
            plannedParallels += parallels[i]; // allocate 已限制總和不超過 long 上限。
        }
        return selected.isEmpty() ? null : new ParallelData(selected, amounts.toLongArray(), true, null);
    }

    /** 獨立世界邊界，方向規劃本身只處理精確功率。 */
    protected BigInteger getDirectionalHatchPower(boolean consumer) {
        return host.getGuixuHatchPower(consumer ? IO.IN : IO.OUT).power();
    }

    @Override
    protected WirelessGTRecipe buildWirelessRecipe(ObjectArrayList<Content> items, ObjectArrayList<Content> fluids,
                                                   BigInteger totalEu) {
        GuixuContainmentBonus bonus = host.getContainmentBonus();
        if (isEnergyConsumer() && bonus.shouldSwallow(java.util.concurrent.ThreadLocalRandom.current().nextDouble())) {
            items.clear();
            fluids.clear();
        }
        // 預算以未縮短的 plannedTicks 計算；耗時縮短只作用在最終批次上（總耗電不變）。
        int ticks = bonus.scaleDuration(plannedTicks);
        var recipe = RecipeCalculationHelper.INSTANCE.buildWirelessRecipe(items, fluids, ticks,
                GuixuPowerBudget.roundForTicks(totalEu, ticks), GTRecipeTypes.DUMMY_RECIPES);
        recipe.data.putInt("sh_guixu_base_ticks", plannedBaseTicks);
        recipe.data.putLong("sh_guixu_parallel_budget", plannedParallels);
        return recipe;
    }

    @Override
    protected long getWirelessRecipeEut(GTRecipe recipe) {
        if (recipe.recipeType == DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION) return RecipeHelper.getOutputEUt(recipe);
        // 父類 getTotalEuOfRecipe 恆回傳 0；buildFinalWirelessRecipe 實際讀的是此方法。
        return host.getContainmentBonus().scaleEnergy(
                GuixuRecipeMath.reducedEnergyEut(super.getWirelessRecipeEut(recipe), host.moduleSlotBonus()));
    }

    @Override
    protected boolean isEnergyConsumer() {
        return plannedConsumer;
    }

    private static boolean recipeConsumesEnergy(GTRecipe recipe) {
        return recipe.recipeType != DShanhaiRecipeTypes.GUIXU_ENERGY_EXTRACTION;
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
