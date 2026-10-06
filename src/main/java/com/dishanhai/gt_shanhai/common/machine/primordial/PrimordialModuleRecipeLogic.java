package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.heat.ShanhaiHeatGate;
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

    private static final long ACTIVE_LOOKUP_CACHE_TICKS = 200L;

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
    private String cachedModuleItemId;
    private int cachedModuleCount = Integer.MIN_VALUE;
    private final java.util.IdentityHashMap<GTRecipe, AmplifiedRecipeCacheEntry> amplifiedRecipeCache =
            new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<GTRecipe, MatchableScaledRecipe> matchableScaledRecipeCache =
            new java.util.IdentityHashMap<>();

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
     * 是最大稳态开销。窗口内不重搜；任何仓室内容变化/选择集变化/模块等级变化立即解除退避
     * （见基类 updateTickSubscription 与 invalidateLookupSetCache），补料开工不受影响。
     */
    @Override
    protected long getEmptyLookupBackoffTicks() {
        return 20L;
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
        // 模块等级门控已在 lookupRecipeIterator 按真实候选配方逐一过滤，这里只叠加额外挂载效果。
        return applyExtraMountRecipeEffects(super.getGTRecipe());
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
        for (GTRecipe recipe : recipes) {
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
        return new ParallelData((List<GTRecipe>) recipeList, parallels, true, null);
    }

    @Override
    protected long getTotalParallelLimit() {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod && mod.hasParallelOverdriver()) {
            // 超限器的契約是每個配方獨立 Long.MAX_VALUE 並行；不能依賴
            // getAdditionalThread() 的 int 轉型或 GTLAdd 的固定執行緒上限。
            return Long.MAX_VALUE;
        }
        return super.getTotalParallelLimit();
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
            double euMultiplier = getEuMultiplier();
            boolean energyConsumer = isEnergyConsumer();

            int size = parallelData.getOriginRecipeList().size();
            // 预分配容量，减少 ArrayList 扩容开销（假设每个配方平均 2 个输出）
            ObjectArrayList<Content> itemOutputs = new ObjectArrayList<>(size * 2);
            ObjectArrayList<Content> fluidOutputs = new ObjectArrayList<>(size);
            BigInteger accumulatedEu = BigInteger.ZERO;
            boolean processedRecipe = false;

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
                        RecipeCalculationHelper.INSTANCE.collectOutputs(processed,
                                (List<Content>) itemOutputs,
                                (List<Content>) fluidOutputs);
                    }
                } else {
                    BigInteger nextEu = accumulatedEu.add(calculateRecipeEu(recipe, parallel, euMultiplier));
                    accumulatedEu = nextEu;
                    processedRecipe = true;
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
            return buildWirelessRecipe(itemOutputs, fluidOutputs, totalEu);
        } finally {
            matchableScaledRecipeCache.clear();
        }
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
        long parallel = findHighestMatchableParallel(requestedParallel, context);
        if (parallel <= 0L) {
            return null;
        }
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

        long low = 1L;
        long high = requestedParallel - 2L;
        long best = 0L;
        while (low <= high) {
            long middle = low + ((high - low) >>> 1);
            if (canMatch.test(middle)) {
                best = middle;
                low = middle + 1L;
            } else {
                high = middle - 1L;
            }
        }
        return best;
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
     * 在基类"遍历选中类型原生查找"结果上，按模块等级门控逐一过滤——这是模块相对主机唯一的额外约束。
     * 样板总成发配槽的发现/执行已由 GTLCore 原生接管（见基类说明），本类不再自行扫描/合并样板配方。
     * 同一份基类候选和同一模块物品/数量下复用过滤集合；并行数与库存数量仍由 calculateParallels
     * 每轮实算。模块槽变化、选择集变化和候选集合刷新都会立即使本缓存失效。
     */
    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        refreshModuleConditionContext();
        Set<GTRecipe> base = super.lookupRecipeIterator();
        if (base.isEmpty()) {
            cachedModuleConditionError = null;
            clearConditionError();
            return base;
        }
        if (base == cachedModuleConditionSource && cachedModuleConditionRecipes != null) {
            applyCachedModuleConditionError();
            return cachedModuleConditionRecipes;
        }
        Set<GTRecipe> result = new ObjectOpenHashSet<>(base.size());
        boolean moduleConditionFailed = false;
        String firstConditionError = null;
        for (GTRecipe recipe : base) {
            if (checkModuleCondition(recipe)) {
                result.add(recipe);
            } else {
                moduleConditionFailed = true;
                if (firstConditionError == null) {
                    firstConditionError = readConditionError();
                }
            }
        }
        cachedModuleConditionError = firstConditionError;
        if (!moduleConditionFailed) {
            clearConditionError();
        } else if (firstConditionError != null) {
            setConditionError(firstConditionError);
        }
        cachedModuleConditionSource = base;
        cachedModuleConditionRecipes = result;
        return result;
    }

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

    private static int readRecipeInt(CompoundTag data, String key) {
        return data.contains(key) ? data.getInt(key) : 0;
    }

    private String shanhai$extraMountFailure(ShanhaiHeatGate.Outcome outcome,
                                             PrimordialOmegaEngineModuleBase module) {
        ShanhaiHeatGate.Requirement requirement = outcome.blocked;
        if (requirement == null) {
            return "额外挂载条件未满足";
        }
        String needed = requirement.describe();
        return switch (outcome.deny) {
            case SLOT_EMPTY -> "这个配方需要【" + needed + "】，但三个额外挂载槽全是空的";
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
        if (java.util.Objects.equals(cachedModuleItemId, moduleItemId) && cachedModuleCount == moduleCount) {
            return;
        }
        invalidateModuleConditionCaches();
        invalidateLookupSetCache();
        cachedModuleItemId = moduleItemId;
        cachedModuleCount = moduleCount;
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
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            // 星律共享搜索集：与任一选中类型同组的配方类型视为选中（精确判断先行，共享集兜底）
            return mod.isRecipeTypeSelected(type)
                    || RecipeTypeSharedSearchSets.isSharedWithAny(type, mod.getSelectedRecipeTypes());
        }
        return true;
    }
}
