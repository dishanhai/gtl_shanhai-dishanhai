package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.heat.ShanhaiHeatGate;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentBonus;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
import com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.IAdvancedContentModifier;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeCacheStrategy;
import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

public abstract class PrimordialModuleRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {

    /** 山海：附在最終批次上，AR 只讀成功處理的並行，不讀預分配或並行上限。 */
    public static final String ACTUAL_PARALLEL_KEY = "sh_primordial_actual_parallel";
    private static final long ACTIVE_LOOKUP_CACHE_TICKS = 200L;
    private long stellarLastAllocatedRecipes;

    // checkModuleCondition 缓存：避免每次都查静态注册表和遍历 conditions。
    // true / false 均为短期缓存（各带 TTL），过期后重新检查，避免一次瞬时结果被永久固化。
    // 模块等级变化时可主动调用 onModuleLevelChanged() 立即清空。
    // 键用 IdentityHashMap 按 recipe 引用本身——曾用 identityHashCode 当 Integer 键，
    // 两个不同配方哈希碰撞时会串判定结果。配方重载后的旧引用由既有失效点清理。
    private final java.util.IdentityHashMap<GTRecipe, Long> moduleConditionTrueCache = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<GTRecipe, CachedModuleConditionFailure> moduleConditionFalseCache = new java.util.IdentityHashMap<>();
    private static final long MODULE_CONDITION_TRUE_TTL = 20L;
    private static final long MODULE_CONDITION_FALSE_TTL = 20L;
    private Set<GTRecipe> cachedModuleConditionSource;
    private Set<GTRecipe> cachedModuleConditionRecipes;
    private String cachedModuleConditionError;
    private List<ShanhaiHeatGate.SlotContent> cachedGateMountContents;
    private String cachedModuleItemId;
    private int cachedModuleCount = Integer.MIN_VALUE;
    private int cachedEffectiveModuleLevel = Integer.MIN_VALUE;
    private int cachedHostCountMultiplier = Integer.MIN_VALUE;
    private final java.util.IdentityHashMap<GTRecipe, AmplifiedRecipeCacheEntry> amplifiedRecipeCache =
            new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<GTRecipe, MatchableScaledRecipe> matchableScaledRecipeCache =
            new java.util.IdentityHashMap<>();
    // 仅作二分起点提示，错了只多几次探测，不影响结果；设上限避免持有已失效配方引用。
    private static final int PARALLEL_HINT_LIMIT = 256;
    private final java.util.IdentityHashMap<GTRecipe, Long> parallelHints = new java.util.IdentityHashMap<>();

    public PrimordialModuleRecipeLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine) {
        super((SelectableRecipeTypeSetMachine) machine);
    }

    public PrimordialModuleRecipeLogic(GTLAddWirelessWorkableElectricMultipleRecipesMachine machine,
                                       java.util.function.Predicate<com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine> beforeWorking) {
        super((SelectableRecipeTypeSetMachine) machine, beforeWorking);
    }

    @Override
    protected long getLogicThreadMultiplier() {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            return mod.getRecipeLogicThreadMultiplier();
        }
        return super.getLogicThreadMultiplier();
    }

    @Override
    protected GTRecipeType[] getLookupRecipeTypes(SelectableRecipeTypeSetMachine machine) {
        if (machine instanceof PrimordialOmegaEngineModuleBase module && module.isCrossTypeParallelActive()) {
            return module.getAllSelectableRecipeTypes();
        }
        return super.getLookupRecipeTypes(machine);
    }

    @Override
    protected long getLookupCacheTicks() {
        return ACTIVE_LOOKUP_CACHE_TICKS;
    }

    @Override
    protected long getMaxLookupCacheTicks() {
        return ACTIVE_LOOKUP_CACHE_TICKS;
    }

    @Override
    protected boolean shouldInvalidateLookupCacheWhenNoRunnableRecipe() {
        return false;
    }

    /**
     * 空闲模块的空结果退避窗口：spark 实测空转模块每 5 tick 一次的全量 getRecipeIterator
     * 是最大稳态开销。窗口内不重搜；選擇器變化、選擇集變化、模塊等級變化會立刻解除。
     * 連續的樣板倉數量通知不解除退避。安靜超過 1 tick 後的第一次通知仍會解除，補料可以開工。
     */
    @Override
    protected long getEmptyLookupBackoffTicks() {
        return 20L;
    }

    /**
     * 樣板倉數量通知不再每 tick 清掉非空候選。電路和 1 格選擇槽仍立刻失效。
     */
    @Override
    protected boolean protectLookupCacheFromContentNoise() {
        return true;
    }

    @Override
    public void onRecipeTypeSelectionChanged() {
        // 先失效基类配方集合缓存（选中类型变了，候选集合必须立即重算，否则被取消选中的类型
        // 可能在缓存窗口内仍被执行——控死风险）。再清模块自己的等级门控缓存。
        // 不打断正在运行的配方（interruptRecipe 会让已扣输入蒸发），新选择下一轮 lookup 立即生效。
        super.onRecipeTypeSelectionChanged();
        invalidateModuleConditionCaches();
        amplifiedRecipeCache.clear();
        matchableScaledRecipeCache.clear();
        parallelHints.clear();
    }

    /**
     * 模块等级变化时调用此方法清空等级门控缓存
     * 应该在模块升级/降级后调用，确保条件检查使用最新的模块等级
     */
    public void onModuleLevelChanged() {
        invalidateModuleConditionCaches();
        invalidateLookupSetCache();
        amplifiedRecipeCache.clear();
        matchableScaledRecipeCache.clear();
    }

    @Override
    public GTRecipe getGTRecipe() {
        // 山海：在候選查找、配方縮放與扣料之前阻擋未由歸墟接管的高階模組。
        if (getMachine() instanceof PrimordialOmegaEngineModuleBase mod && mod.isGuixuHostBlocked()) return null;
        // 歸墟遏制場坍縮鎖定：不開新批次（提示由 addHostStatusDisplay 顯示）。
        if (containmentBonus().locked()) return null;
        // 模块等级门控已在 lookupRecipeIterator 按真实候选配方逐一过滤，这里只叠加额外挂载效果。
        return applyExtraMountRecipeEffects(super.getGTRecipe());
    }

    /** 主機是歸墟時讀遏制場增益；其他主機一律 NONE（行為逐位不變）。 */
    protected final GuixuContainmentBonus containmentBonus() {
        return getMachine() instanceof PrimordialOmegaEngineModuleBase mod
                ? GuixuVisionCoreMachine.containmentBonusOf(mod.getHost())
                : GuixuContainmentBonus.NONE;
    }

    private GTRecipe applyExtraMountRecipeEffects(GTRecipe recipe) {
        if (recipe == null) return null;
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase mod)) return recipe;
        if (!mod.hasAnnihilationCoreMounted()) return recipe;

        GTRecipe copy = recipe.copy();
        copy.duration = Math.max(1, (int) Math.ceil(copy.duration * mod.getExtraMountDurationMultiplier()));

        double lossChance = mod.getAnnihilationOutputLossChance();
        if (lossChance > 0.0D && Math.random() < lossChance) {
            copy.outputs.clear();
            copy.tickOutputs.clear();
        }
        return copy;
    }

    @Override
    protected boolean checkRecipe(GTRecipe recipe) {
        return recipe != null
                && recipe.recipeType != null
                && isSelectedRecipeType(recipe.recipeType)
                && matchRecipeInputHandlePartCache(recipe)
                && RecipeRunnerHelper.matchRecipeOutput(getMachine(), recipe)
                && shanhai$extraMountGateAllows(recipe)
                && shanhai$conditionsPass(recipe);
    }

    /**
     * 原初模块的"高级产出"不做基类那种"总池按 recipes.size() 均分再水位填补"的公平分配——
     * 同时选中多个配方类型时，每个配方各自独立吃满自己的 getMaxParallel(recipe, totalParallel)
     * 上限（超限模式下即 Long.MAX_VALUE 级），互不挤占，而不是从一个共享池里分蛋糕。
     */
    @Override
    @Nullable
    protected ParallelData calculateParallels() {
        stellarLastAllocatedRecipes = 0L;
        matchableScaledRecipeCache.clear();
        Set<GTRecipe> recipes = lookupRecipeIterator();
        if (recipes.isEmpty()) {
            // 与父类 calculateParallels 同款语义：是否清缓存必须先问 shouldInvalidateLookupCacheWhenNoRunnableRecipe()。
            // 此前三处无条件 invalidate 把模块的 200-tick 稳态缓存和该开关整体架空——空闲模块每 5 tick
            // 全量重跑 getRecipeIterator（AE 内容物化+全子树枚举）后清空全部缓存，正是 322µs/tick 的主源。
            invalidateLookupSetCacheIfConfigured();
            return null;
        }
        long totalParallel = getTotalParallelLimit();
        if (totalParallel <= 0L) {
            invalidateLookupSetCacheIfConfigured();
            return null;
        }
        long[] parallels = new long[recipes.size()];
        int index = 0;
        ObjectArrayList<GTRecipe> recipeList = new ObjectArrayList<>(recipes.size());
        long crossTypeThreads = getMachine() instanceof PrimordialOmegaEngineModuleBase module
                && module.isCrossTypeParallelActive() ? module.getRecipeLogicThreadMultiplier() : Long.MAX_VALUE;
        for (GTRecipe recipe : recipes) {
            if (recipeList.size() >= crossTypeThreads) break;
            MatchableScaledRecipe matchable = findMaxMatchableScaledRecipe(recipe, totalParallel);
            if (matchable == null) {
                continue;
            }
            recipeList.add(recipe);
            parallels[index] = matchable.parallel;
            matchableScaledRecipeCache.put(recipe, matchable);
            index++;
        }
        if (recipeList.isEmpty()) {
            invalidateLookupSetCacheIfConfigured();
            return null;
        }
        // 原初模組已在上方逐配方完成輸入/輸出可行性計算，這裡必須標記為可處理，
        // 讓 buildFinalWirelessRecipe 進入真實扣料分支。GTLAdd 的 helper 在空餘配額為
        // 0 時固定回傳 shouldProcess=false；直接使用該結果會只收集輸出而跳過扣料。
        stellarLastAllocatedRecipes = recipeList.size();
        return new ParallelData((List<GTRecipe>) recipeList, parallels, true, null);
    }

    /** 山海星律：本邏輯逐配方獨立並行，真正競爭的是同時容納幾個配方。 */
    @Override
    public long gtShanhai$getStuckWatchParallelLimit() {
        return getMachine() instanceof PrimordialOmegaEngineModuleBase module && module.isCrossTypeParallelActive()
                ? Math.max(1L, module.getRecipeLogicThreadMultiplier()) : Long.MAX_VALUE;
    }

    @Override
    public long gtShanhai$getLastAllocatedParallel() {
        return stellarLastAllocatedRecipes;
    }

    @Override
    protected long getTotalParallelLimit() {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod && mod.hasParallelOverdriver()) {
            // 超限器的契約是每個配方獨立 Long.MAX_VALUE 並行；不能依賴
            // getAdditionalThread() 的 int 轉型或 GTLAdd 的固定執行緒上限。
            return Long.MAX_VALUE;
        }
        return containmentBonus().scaleParallel(super.getTotalParallelLimit());
    }

    private void invalidateLookupSetCacheIfConfigured() {
        if (shouldInvalidateLookupCacheWhenNoRunnableRecipe()) {
            invalidateLookupSetCache();
        }
    }

    @Override
    public long getMaxParallel(GTRecipe recipe, long limit) {
        MatchableScaledRecipe matchable = findMaxMatchableScaledRecipe(recipe, limit);
        return matchable == null ? 0L : matchable.parallel;
    }

    private MatchableScaledRecipe findMaxMatchableScaledRecipe(GTRecipe recipe, long limit) {
        if (recipe == null || limit <= 0L) return null;
        GTRecipe amplified = amplifyForMountedCore(recipe);
        long inputMax = IParallelLogic.getMaxParallel(getMachine(), amplified, limit);
        if (inputMax <= 0L) return null;
        // GTLCore 的 getMinParallel() 內部用 double ContentModifier 做輸出容量二分；
        // Long.MAX_VALUE 超限並行與大批量輸出會在 2^53 後失去整數精度。
        // 直接以精確 long 縮放配方測試輸出容量，讓二分與最終扣料使用同一條路徑。
        return findMatchableScaledRecipe(amplified, inputMax);
    }

    protected boolean allowsEmptyRecipeOutputs() {
        return false;
    }

    @Override
    protected WirelessGTRecipe buildFinalWirelessRecipe(ParallelData parallelData, IWirelessNetworkEnergyHandler wirelessTrait) {
        if (parallelData == null || wirelessTrait == null || !wirelessTrait.isOnline()) {
            matchableScaledRecipeCache.clear();
            return null;
        }

        try {
            IRecipeCapabilityHolder machine = getMachine();
            BigInteger maxTotalEu = wirelessTrait.getMaxAvailableEnergy();
            boolean energyConsumer = isEnergyConsumer();
            double euMultiplier = getEuMultiplier() * containmentBonus().energyFactor(energyConsumer);

            int size = parallelData.getOriginRecipeList().size();
            // 预分配容量，减少 ArrayList 扩容开销（假设每个配方平均 2 个输出）
            ObjectArrayList<Content> itemOutputs = new ObjectArrayList<>(size * 2);
            ObjectArrayList<Content> fluidOutputs = new ObjectArrayList<>(size);
            BigInteger accumulatedEu = BigInteger.ZERO;
            boolean processedRecipe = false;
            long actualParallel = 0L;

            for (int i = 0; i < size; i++) {
                GTRecipe recipe = parallelData.getOriginRecipeList().get(i);
                long parallel = parallelData.getParallels()[i];

                if (parallelData.getShouldProcess()) {
                    MatchableScaledRecipe matchable = matchableScaledRecipeCache.remove(recipe);
                    if (matchable == null || matchable.parallel != parallel) {
                        GTRecipe amplifiedRecipe = amplifyForMountedCore(recipe);
                        matchable = findMatchableScaledRecipe(amplifiedRecipe, parallel);
                    }
                    if (matchable == null) {
                        continue;
                    }
                    GTRecipe scaledRecipe = matchable.scaledRecipe;
                    parallel = matchable.parallel;
                    BigInteger nextEu = accumulatedEu.add(calculateRecipeEu(recipe, parallel, euMultiplier));
                    if (energyConsumer && nextEu.compareTo(maxTotalEu) > 0) {
                        if (accumulatedEu.signum() == 0) {
                            RecipeResult.of(getMachine(), RecipeResult.FAIL_NO_ENOUGH_EU_IN);
                        }
                        continue;
                    }
                    GTRecipe processed = IParallelLogic.getRecipeOutputChance(machine, scaledRecipe);
                    if (matchRecipeInputHandlePartCache(processed)
                            && handleRecipeInputHandlePartCache(processed)) {
                        accumulatedEu = nextEu;
                        processedRecipe = true;
                        actualParallel = parallel > Long.MAX_VALUE - actualParallel
                                ? Long.MAX_VALUE : actualParallel + parallel;
                        RecipeCalculationHelper.INSTANCE.collectOutputs(processed,
                                (List<Content>) itemOutputs,
                                (List<Content>) fluidOutputs);
                    }
                } else {
                    BigInteger nextEu = accumulatedEu.add(calculateRecipeEu(recipe, parallel, euMultiplier));
                    accumulatedEu = nextEu;
                    processedRecipe = true;
                    actualParallel = parallel > Long.MAX_VALUE - actualParallel
                            ? Long.MAX_VALUE : actualParallel + parallel;
                    GTRecipe amplifiedOutputRecipe = amplifyForMountedCore(recipe);
                    GTRecipe scaledOutputRecipe = scaleRecipePrecisely(amplifiedOutputRecipe, parallel);
                    RecipeCalculationHelper.INSTANCE.collectOutputs(scaledOutputRecipe,
                            (List<Content>) itemOutputs,
                            (List<Content>) fluidOutputs);
                }
            }

            BigInteger totalEu = energyConsumer ? accumulatedEu : accumulatedEu.negate();
            if (energyConsumer && !processedRecipe) {
                if (getRecipeStatus() == null || getRecipeStatus().isSuccess()) {
                    RecipeResult.of(getMachine(), RecipeResult.FAIL_FIND);
                }
                return null;
            }
            if (energyConsumer && !allowsEmptyRecipeOutputs()
                    && !RecipeCalculationHelper.INSTANCE.hasOutputs(itemOutputs, fluidOutputs)) {
                if (getRecipeStatus() == null || getRecipeStatus().isSuccess()) {
                    RecipeResult.of(getMachine(), RecipeResult.FAIL_FIND);
                }
                return null;
            }
            WirelessGTRecipe result = buildWirelessRecipe(itemOutputs, fluidOutputs, totalEu);
            if (result != null) result.data.putLong(ACTUAL_PARALLEL_KEY, actualParallel);
            return result;
        } finally {
            matchableScaledRecipeCache.clear();
        }
    }

    /**
     * 歸墟遏制場：縮短批次最終耗時（下限 1 tick）並按機率吞掉產出。
     * 沒有影響時走父類，與原本逐位一致；父類同樣用 DUMMY 配方類型與 limitedDuration。
     */
    @Override
    protected WirelessGTRecipe buildWirelessRecipe(ObjectArrayList<Content> itemOutputs,
                                                   ObjectArrayList<Content> fluidOutputs, BigInteger totalEu) {
        GuixuContainmentBonus bonus = containmentBonus();
        if (!bonus.affectsRecipes()) {
            return super.buildWirelessRecipe(itemOutputs, fluidOutputs, totalEu);
        }
        if (isEnergyConsumer() && bonus.shouldSwallow(java.util.concurrent.ThreadLocalRandom.current().nextDouble())) {
            itemOutputs.clear();
            fluidOutputs.clear();
        }
        int ticks = bonus.scaleDuration(getLimited().getLimitedDuration());
        return RecipeCalculationHelper.INSTANCE.buildWirelessRecipe(itemOutputs, fluidOutputs, ticks, totalEu,
                GTRecipeTypes.DUMMY_RECIPES);
    }

    private GTRecipe amplifyForMountedCore(GTRecipe recipe) {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase module)) {
            return recipe;
        }
        int multiplier = module.getHostOutputMultiplier();
        if (multiplier <= 1) {
            return recipe;
        }
        AmplifiedRecipeCacheEntry cached = amplifiedRecipeCache.get(recipe);
        if (cached != null && cached.multiplier == multiplier) {
            return cached.recipe;
        }
        GTRecipe amplified = PrimordialRecipeOutputAmplifier.apply(recipe, multiplier);
        amplifiedRecipeCache.put(recipe, new AmplifiedRecipeCacheEntry(multiplier, amplified));
        return amplified;
    }

    private MatchableScaledRecipe findMatchableScaledRecipe(GTRecipe recipe, long requestedParallel) {
        ScaledRecipeMatchContext context = new ScaledRecipeMatchContext(recipe);
        Long hint = parallelHints.get(recipe);
        long parallel = findHighestMatchableParallel(requestedParallel, hint == null ? 0L : hint, context);
        if (parallel <= 0L) {
            parallelHints.remove(recipe);
            return null;
        }
        if (parallelHints.size() >= PARALLEL_HINT_LIMIT) {
            parallelHints.clear();
        }
        parallelHints.put(recipe, parallel);
        if (context.matchedParallel == parallel && context.matchedRecipe != null) {
            return new MatchableScaledRecipe(parallel, context.matchedRecipe);
        }
        return new MatchableScaledRecipe(parallel, scaleRecipePrecisely(recipe, parallel));
    }

    private static final class MatchableScaledRecipe {
        private final long parallel;
        private final GTRecipe scaledRecipe;

        private MatchableScaledRecipe(long parallel, GTRecipe scaledRecipe) {
            this.parallel = parallel;
            this.scaledRecipe = scaledRecipe;
        }
    }

    private static final class AmplifiedRecipeCacheEntry {
        private final int multiplier;
        private final GTRecipe recipe;

        private AmplifiedRecipeCacheEntry(int multiplier, GTRecipe recipe) {
            this.multiplier = multiplier;
            this.recipe = recipe;
        }
    }

    private final class ScaledRecipeMatchContext implements LongPredicate {
        private final GTRecipe recipe;
        private long matchedParallel;
        private GTRecipe matchedRecipe;

        private ScaledRecipeMatchContext(GTRecipe recipe) {
            this.recipe = recipe;
        }

        @Override
        public boolean test(long candidate) {
            GTRecipe scaledRecipe = scaleRecipePrecisely(recipe, candidate);
            if (matchRecipeInputHandlePartCache(scaledRecipe)
                    && RecipeRunnerHelper.matchRecipeOutput(getMachine(), scaledRecipe)) {
                matchedParallel = candidate;
                matchedRecipe = scaledRecipe;
                return true;
            }
            return false;
        }
    }

    /**
     * 原初配方的并行缩放必须保留 long 数量精度。
     * GTLAdd 的 multipleRecipe(long) 会先把倍率转成 double，超过 2^53 后会改变物品/流体数量。
     */
    private static GTRecipe scaleRecipePrecisely(GTRecipe recipe, long parallel) {
        if (parallel <= 1L) {
            return recipe;
        }
        GTRecipe scaled = recipe.copy(IAdvancedContentModifier.preciseMultiplier(parallel), false);
        RecipeExtensionCopier.copy(recipe, scaled);
        IGTRecipe.of(scaled).setRealParallels(parallel);
        return scaled;
    }

    static long findHighestMatchableParallel(long requestedParallel, LongPredicate canMatch) {
        return findHighestMatchableParallel(requestedParallel, 0L, canMatch);
    }

    /**
     * hintParallel 是同一配方上次的可匹配并行。输出受限的稳态下每轮结果几乎不变，
     * 先测提示值再向两侧倍增划出区间，探测约 2–4 次；提示无效（≤1 或 ≥ 上界）时退化为原二分。
     * 与二分一样依赖「可匹配性随并行单调」；最后一次通过的探测恒为最终结果，调用方的缓存命中依赖这一点。
     */
    static long findHighestMatchableParallel(long requestedParallel, long hintParallel, LongPredicate canMatch) {
        if (requestedParallel <= 0L || canMatch == null) {
            return 0L;
        }
        if (canMatch.test(requestedParallel)) {
            return requestedParallel;
        }
        if (requestedParallel == 1L) {
            return 0L;
        }

        long oneLess = requestedParallel - 1L;
        if (canMatch.test(oneLess)) {
            return oneLess;
        }
        if (oneLess == 1L) {
            return 0L;
        }

        // 并行 1 都过不了（输出倉滿、输入被别的配方吃光）= 整段单调区间全部失败，直接返回，
        // 不必再二分。这是「堵塞状态每 5 tick 重试一轮」的主要开销：超限器下 log2(上限) 可达 60 轮，
        // 每轮都复制一份缩放配方并做输入、输出两次 handler 匹配。
        if (!canMatch.test(1L)) {
            return 0L;
        }

        // lo 已知可过，hi 已知不可过。
        long lo = 1L;
        long hi = oneLess;
        if (hintParallel > lo && hintParallel < hi) {
            long step = 1L;
            if (canMatch.test(hintParallel)) {
                lo = hintParallel;
                while (true) {
                    long probe = lo + step;
                    if (probe >= hi || probe < 0L) break;
                    if (canMatch.test(probe)) {
                        lo = probe;
                        step <<= 1;
                    } else {
                        hi = probe;
                        break;
                    }
                }
            } else {
                hi = hintParallel;
                while (true) {
                    long probe = hi - step;
                    if (probe <= lo) break;
                    if (canMatch.test(probe)) {
                        lo = probe;
                        break;
                    }
                    hi = probe;
                    step <<= 1;
                }
            }
        }
        while (hi - lo > 1L) {
            long middle = lo + ((hi - lo) >>> 1);
            if (canMatch.test(middle)) {
                lo = middle;
            } else {
                hi = middle;
            }
        }
        return lo;
    }

    private BigInteger calculateRecipeEu(GTRecipe recipe, long parallel, double euMultiplier) {
        BigInteger parallelEu = BigInteger.valueOf(getWirelessRecipeEut(recipe));
        if (parallel != 1L) {
            parallelEu = parallelEu.multiply(BigInteger.valueOf(parallel));
        }
        if (euMultiplier == 1.0D) {
            return parallelEu.multiply(BigInteger.valueOf(recipe.duration));
        }
        return BigDecimal.valueOf(recipe.duration)
                .multiply(BigDecimal.valueOf(euMultiplier))
                .multiply(new BigDecimal(parallelEu))
                .toBigInteger();
    }

    /**
     * 在基类候选上再过两道门：模块等级，以及额外挂载（含线圈炉温）。
     * 样板总成并入的配方不走 checkRecipe，只在这里补上挂载判定。
     * 挂载槽变化时丢掉基类候选缓存，避免温度绕过把旧候选留在窗口里。
     */
    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        boolean crossType = getMachine() instanceof PrimordialOmegaEngineModuleBase module && module.isCrossTypeParallelActive();
        if (crossType != lastCrossType) {
            lastCrossType = crossType;
            invalidateLookupSetCache();
            cachedModuleConditionSource = null;
        }
        refreshModuleConditionContext();
        List<ShanhaiHeatGate.SlotContent> mounts = currentGateMountContents();
        if (mounts != cachedGateMountContents) {
            cachedGateMountContents = mounts;
            cachedModuleConditionSource = null;
            cachedModuleConditionRecipes = null;
            cachedModuleConditionError = null;
            invalidateLookupSetCache();
        }
        Set<GTRecipe> base = super.lookupRecipeIterator();
        if (base.isEmpty()) {
            cachedModuleConditionError = readConditionError();
            if (cachedModuleConditionError == null) {
                clearConditionError();
            }
            return base;
        }
        if (base == cachedModuleConditionSource && cachedModuleConditionRecipes != null) {
            applyCachedModuleConditionError();
            return cachedModuleConditionRecipes;
        }
        Set<GTRecipe> result = crossType ? new java.util.LinkedHashSet<>() : new ObjectOpenHashSet<>(base.size());
        boolean moduleConditionFailed = false;
        String firstConditionError = null;
        for (GTRecipe recipe : base) {
            if (!checkModuleCondition(recipe)) {
                moduleConditionFailed = true;
                if (firstConditionError == null) {
                    firstConditionError = readConditionError();
                }
                continue;
            }
            if (!shanhai$extraMountGateAllows(recipe) || !shanhai$conditionsPass(recipe)) {
                moduleConditionFailed = true;
                if (firstConditionError == null) {
                    firstConditionError = readConditionError();
                }
                continue;
            }
            result.add(recipe);
        }
        cachedModuleConditionError = firstConditionError;
        if (!moduleConditionFailed) {
            clearConditionError();
        } else if (firstConditionError != null) {
            setConditionError(firstConditionError);
        }
        cachedModuleConditionSource = base;
        if (crossType) {
            var groups = new java.util.LinkedHashMap<GTRecipeType, java.util.List<GTRecipe>>();
            for (GTRecipeType type : getMachine().getRecipeTypes()) groups.put(type, new java.util.ArrayList<>());
            result.stream().sorted(java.util.Comparator.comparing(recipe -> recipe.id.toString()))
                    .forEach(recipe -> groups.computeIfAbsent(recipe.recipeType, ignored -> new java.util.ArrayList<>()).add(recipe));
            result = com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuRecipeRotation.roundRobin(
                    new java.util.ArrayList<>(groups.values()));
        }
        cachedModuleConditionRecipes = result;
        return result;
    }

    private boolean lastCrossType;

    private ShanhaiHeatGate.Outcome shanhai$gateOutcome;

    private boolean shanhai$extraMountGateAllows(GTRecipe recipe) {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase module)) {
            return true;
        }

        boolean unrestrictedMode = DShanhaiConfig.COMMON.primordialModuleUnrestrictedMode.get();
        List<ShanhaiHeatGate.Requirement> needs = new ArrayList<>();
        if (recipe.conditions != null) {
            for (RecipeCondition condition : recipe.conditions) {
                if (condition == null || condition.isReverse()) {
                    continue;
                }
                ShanhaiHeatGate.Requirement requirement = shanhai$requirementOf(condition);
                if (requirement == null) {
                    continue;
                }
                if (!ShanhaiHeatGate.isRequirementEnforced(requirement.kind, unrestrictedMode)) {
                    continue;
                }
                if (!condition.test(recipe, this)) {
                    needs.add(requirement);
                }
            }
        }

        GTRecipeType type = recipe.recipeType;
        boolean heatReachable = type != null
                && type.registryName != null
                && ShanhaiHeatGate.isGated(type.registryName.toString())
                && module.canUseExtraMountAsHeatSource();
        if (!unrestrictedMode && heatReachable && recipe.data != null) {
            int requiredTemperature = readRecipeInt(recipe.data, ShanhaiHeatGate.KEY_EBF_TEMP);
            int requiredContainment = readRecipeInt(recipe.data, ShanhaiHeatGate.KEY_SC_TIER);
            if (requiredTemperature > 0) {
                needs.add(ShanhaiHeatGate.Requirement.heatTemp(requiredTemperature));
            }
            if (requiredContainment > 0) {
                needs.add(ShanhaiHeatGate.Requirement.scTier(requiredContainment));
            }
        }

        ShanhaiHeatGate.Outcome outcome =
                ShanhaiHeatGate.evaluate(needs, module.getExtraMountContents());
        shanhai$gateOutcome = outcome;
        if (outcome.allowed) {
            return true;
        }
        module.setModuleConditionError(shanhai$extraMountFailure(outcome, module));
        return false;
    }

    private boolean shanhai$conditionsPass(GTRecipe recipe) {
        ShanhaiHeatGate.Outcome outcome = shanhai$gateOutcome;
        if (outcome == null || outcome.satisfied.isEmpty()) {
            if (!shanhai$hasIgnoredCondition(recipe)) {
                return recipe.checkConditions(this).isSuccess();
            }
        }

        Map<RecipeConditionType<?>, Boolean> orGroupAllFail = new LinkedHashMap<>();
        for (RecipeCondition condition : recipe.conditions) {
            ShanhaiHeatGate.Requirement requirement = shanhai$requirementOf(condition);
            boolean passed = (!condition.isReverse() && shanhai$conditionIgnored(requirement))
                    || condition.test(recipe, this) != condition.isReverse();
            if (!passed) {
                passed = outcome != null && requirement != null && outcome.satisfied.contains(requirement);
            }
            if (condition.isOr()) {
                orGroupAllFail.merge(condition.getType(), !passed, (left, right) -> left && right);
            } else if (!passed) {
                return false;
            }
        }
        for (Boolean allFail : orGroupAllFail.values()) {
            if (Boolean.TRUE.equals(allFail)) {
                return false;
            }
        }
        return true;
    }

    private boolean shanhai$hasIgnoredCondition(GTRecipe recipe) {
        if (recipe.conditions == null) {
            return false;
        }
        for (RecipeCondition condition : recipe.conditions) {
            if (!condition.isReverse()
                    && shanhai$conditionIgnored(shanhai$requirementOf(condition))) {
                return true;
            }
        }
        return false;
    }

    private boolean shanhai$conditionIgnored(ShanhaiHeatGate.Requirement requirement) {
        return requirement != null
                && ShanhaiHeatGate.isRequirementIgnored(
                        requirement.kind,
                        DShanhaiConfig.COMMON.primordialModuleUnrestrictedMode.get());
    }

    @Nullable
    private ShanhaiHeatGate.Requirement shanhai$requirementOf(RecipeCondition condition) {
        if (condition instanceof CleanroomCondition cleanroom) {
            CleanroomType type = cleanroom.getCleanroom();
            int tier = ShanhaiHeatGate.cleanroomTierOfName(type == null ? null : type.getName());
            return tier == ShanhaiHeatGate.CLEANROOM_NONE
                    ? null : ShanhaiHeatGate.Requirement.cleanroom(tier);
        }
        if (condition instanceof DimensionCondition dimension) {
            ResourceLocation id = dimension.getDimension();
            return id == null ? null : ShanhaiHeatGate.Requirement.dimension(id.toString());
        }
        if (condition instanceof ResearchCondition) {
            return ShanhaiHeatGate.Requirement.research();
        }
        if (condition instanceof GravityCondition) {
            return ShanhaiHeatGate.Requirement.gravity();
        }
        return null;
    }

    private List<ShanhaiHeatGate.SlotContent> currentGateMountContents() {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase module)) {
            return null;
        }
        return module.getExtraMountContents();
    }

    /**
     * 不走 {@link CompoundTag#getInt(String)}。温度绕过会把 ebf_temp 的 getInt 改成 0，
     * 额外挂载闸门若跟着读到 0，就会把 3000K 配方当成没有炉温要求。
     */
    private static int readRecipeInt(CompoundTag data, String key) {
        if (data == null || key == null || !data.contains(key)) {
            return 0;
        }
        net.minecraft.nbt.Tag tag = data.get(key);
        if (tag instanceof net.minecraft.nbt.NumericTag numeric) {
            return numeric.getAsInt();
        }
        return 0;
    }

    private String shanhai$extraMountFailure(ShanhaiHeatGate.Outcome outcome,
                                             PrimordialOmegaEngineModuleBase module) {
        ShanhaiHeatGate.Requirement requirement = outcome.blocked;
        if (requirement == null) {
            return "额外挂载条件未满足";
        }
        String needed = requirement.describe();
        return switch (outcome.deny) {
            case SLOT_EMPTY -> "这个配方需要【" + needed + "】，但" + ShanhaiHeatGate.SLOT_COUNT + "个额外挂载槽全是空的";
            case WRONG_ITEM -> "这个配方需要【" + needed + "】，但额外挂载槽里放的是【"
                    + shanhai$extraActualNames(module) + "】";
            case NOT_FULL -> "这个配方需要放满 " + ShanhaiHeatGate.REQUIRED_COUNT + " 个【"
                    + needed + "】才生效，槽里只有 " + shanhai$extraCountOfKind(requirement.kind, outcome) + " 个";
            case CLEANROOM_TIER -> "这个配方需要【" + needed + "】，但槽里的维护仓档位只有 "
                    + outcome.have;
            case HEAT_TEMP -> "这个配方需要 " + requirement.number + "K 炉温，但槽里的线圈只有 "
                    + outcome.have + "K";
            case SC_TIER -> "这个配方需要 " + requirement.number + " 级恒星热力容器，但槽里只有 "
                    + outcome.have + " 级";
            default -> "额外挂载条件未满足";
        };
    }

    private String shanhai$extraActualNames(PrimordialOmegaEngineModuleBase module) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ShanhaiHeatGate.SLOT_COUNT; i++) {
            ItemStack stack = module.getExtraMountStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append("、");
            }
            result.append(stack.getHoverName().getString());
        }
        return result.length() == 0 ? "其它物品" : result.toString();
    }

    private static int shanhai$extraCountOfKind(ShanhaiHeatGate.Kind kind,
                                                ShanhaiHeatGate.Outcome outcome) {
        int best = 0;
        for (ShanhaiHeatGate.SlotContent slot : outcome.slots) {
            boolean relevant = switch (kind) {
                case HEAT_TEMP -> slot.coilTemperature > 0;
                case SC_TIER -> slot.containmentTier > 0;
                default -> true;
            };
            if (relevant) {
                best = Math.max(best, slot.count);
            }
        }
        return best;
    }

    private void refreshModuleConditionContext() {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase mod)) {
            return;
        }
        String moduleItemId = mod.getModuleItemId();
        int moduleCount = mod.getModuleCount();
        int moduleLevel = mod.getModuleLevel();
        // 歸墟遏制場會倍增主機的物質模塊數量；倍率一變，舊的條件判定就不能再用。
        int hostCountMultiplier = mod.getHost() == null ? 1 : mod.getHost().moduleCountMultiplier();
        if (java.util.Objects.equals(cachedModuleItemId, moduleItemId) && cachedModuleCount == moduleCount
                && cachedEffectiveModuleLevel == moduleLevel && cachedHostCountMultiplier == hostCountMultiplier) {
            return;
        }
        invalidateModuleConditionCaches();
        invalidateLookupSetCache();
        cachedModuleItemId = moduleItemId;
        cachedModuleCount = moduleCount;
        cachedEffectiveModuleLevel = moduleLevel;
        cachedHostCountMultiplier = hostCountMultiplier;
    }

    private void invalidateModuleConditionCaches() {
        moduleConditionTrueCache.clear();
        moduleConditionFalseCache.clear();
        cachedModuleConditionSource = null;
        cachedModuleConditionRecipes = null;
        cachedModuleConditionError = null;
        clearConditionError();
    }

    private boolean matchRecipeInputHandlePartCache(GTRecipe recipe) {
        return recipe != null && (recipe.inputs.isEmpty()
                || RecipeRunnerHelper.handleRecipe(IO.IN, getMachine(), recipe.inputs,
                        java.util.Collections.emptyMap(), false, recipe, true,
                        RecipeCacheStrategy.HANDLE_PART_CACHE_ONLY).isSuccess());
    }

    private boolean handleRecipeInputHandlePartCache(GTRecipe recipe) {
        return recipe != null && (recipe.inputs.isEmpty()
                || RecipeRunnerHelper.handleRecipe(IO.IN, getMachine(), recipe.inputs,
                        getMachine().getRecipeLogic().getChanceCaches(), true, recipe, false,
                        RecipeCacheStrategy.HANDLE_PART_CACHE_ONLY).isSuccess());
    }

    /** 手动检查模块条件——优先从静态注册表读（绕过 KubeJS 序列化类型丢失） */
    private boolean checkModuleCondition(GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }

        long now = getMachine().getOffsetTimer();
        Long trueUntil = moduleConditionTrueCache.get(recipe);
        if (trueUntil != null) {
            if (now < trueUntil) {
                return true;
            }
            moduleConditionTrueCache.remove(recipe);
        }
        CachedModuleConditionFailure cachedFailure = moduleConditionFalseCache.get(recipe);
        if (cachedFailure != null) {
            if (now < cachedFailure.expiresAt) {
                setConditionError(cachedFailure.message);
                return false;
            }
            moduleConditionFalseCache.remove(recipe);
        }

        MetaMachine machine = getMachine();
        String recipeId = recipe.getId() != null ? recipe.getId().toString() : "";

        // 1. 优先从本轮重载的静态注册表按完整配方 ID 精确查询
        java.util.List<com.dishanhai.gt_shanhai.api.ModuleLevelCondition> staticReqs =
                com.dishanhai.gt_shanhai.api.ModuleLevelCondition.getRequirements(recipeId);
        if (staticReqs != null && !staticReqs.isEmpty()) {
            for (var mlc : staticReqs) {
                if (!mlc.checkModuleLevel(machine)) {
                    String error = setError(machine, mlc.getFailTooltip().getString(), mlc.moduleId, mlc.requiredLevel);
                    cacheModuleConditionFalse(recipe, error);
                    return false;
                }
            }
            cacheModuleConditionTrue(recipe);
            return true;
        }

        // 2. 回退：recipe.conditions
        if (recipe.conditions == null || recipe.conditions.isEmpty()) {
            cacheModuleConditionTrue(recipe);
            return true;
        }
        for (var cond : recipe.conditions) {
            if (cond instanceof com.dishanhai.gt_shanhai.api.ModuleLevelCondition mlc) {
                if (!mlc.checkModuleLevel(machine)) {
                    String error = setError(machine, mlc.getFailTooltip().getString(), mlc.moduleId, mlc.requiredLevel);
                    cacheModuleConditionFalse(recipe, error);
                    return false;
                }
                continue;
            }
            var type = cond.getType();
            if (type == com.dishanhai.gt_shanhai.api.ModuleLevelCondition.TYPE && cond instanceof com.dishanhai.gt_shanhai.api.ModuleLevelCondition mlc2) {
                if (!mlc2.checkModuleLevel(machine)) {
                    String error = setError(machine, mlc2.getFailTooltip().getString(), mlc2.moduleId, mlc2.requiredLevel);
                    cacheModuleConditionFalse(recipe, error);
                    return false;
                }
            }
        }
        cacheModuleConditionTrue(recipe);
        return true;
    }

    private void cacheModuleConditionTrue(GTRecipe recipe) {
        moduleConditionFalseCache.remove(recipe);
        moduleConditionTrueCache.put(recipe, getMachine().getOffsetTimer() + MODULE_CONDITION_TRUE_TTL);
    }

    private void cacheModuleConditionFalse(GTRecipe recipe, String message) {
        moduleConditionTrueCache.remove(recipe);
        moduleConditionFalseCache.put(recipe, new CachedModuleConditionFailure(
                getMachine().getOffsetTimer() + MODULE_CONDITION_FALSE_TTL, message));
    }

    private static final class CachedModuleConditionFailure {
        private final long expiresAt;
        private final String message;

        private CachedModuleConditionFailure(long expiresAt, String message) {
            this.expiresAt = expiresAt;
            this.message = message;
        }
    }

    private String setError(MetaMachine machine, String msg, String moduleId, int requiredLevel) {
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            // 模块侧用详细诊断替代通用错误信息
            String diag = mod.getModuleConditionDiagnosis(moduleId, requiredLevel);
            String error = diag != null ? diag : msg;
            mod.setModuleConditionError(error);
            return error;
        }
        return msg;
    }

    private String readConditionError() {
        MetaMachine machine = getMachine();
        return machine instanceof PrimordialOmegaEngineModuleBase mod
                ? mod.getModuleConditionError() : null;
    }

    private void setConditionError(String message) {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            mod.setModuleConditionError(message);
        }
    }

    private void applyCachedModuleConditionError() {
        if (cachedModuleConditionError == null) {
            clearConditionError();
        } else {
            setConditionError(cachedModuleConditionError);
        }
    }

    private void clearConditionError() {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            mod.setModuleConditionError(null);
        }
    }

    private boolean isSelectedRecipeType(GTRecipeType type) {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod && mod.isCrossTypeParallelActive()) {
            return true;
        }
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            // 星律共享搜索集：与任一选中类型同组的配方类型视为选中（精确判断先行，共享集兜底）
            return mod.isRecipeTypeSelected(type)
                    || RecipeTypeSharedSearchSets.isSharedWithAny(type, mod.getSelectedRecipeTypes());
        }
        return true;
    }
}
