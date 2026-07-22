package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.RecipeCacheStrategy;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

public abstract class PrimordialModuleRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {

    private static final long ACTIVE_LOOKUP_CACHE_TICKS = 200L;

    // checkModuleCondition 缓存：避免每次都查静态注册表和遍历 conditions。
    // true / false 均为短期缓存（各带 TTL），过期后重新检查，避免一次瞬时结果被永久固化。
    // 模块等级变化时可主动调用 onModuleLevelChanged() 立即清空。
    // 优化：使用 System.identityHashCode(recipe) 作为快速键，减少 HashMap 对象装箱开销。
    private final java.util.Map<Integer, Long> moduleConditionTrueCache = new java.util.HashMap<>();
    private final java.util.Map<Integer, CachedModuleConditionFailure> moduleConditionFalseCache = new java.util.HashMap<>();
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
                && recipe.checkConditions(this).isSuccess();
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
            invalidateLookupSetCache();
            return null;
        }
        long totalParallel = getTotalParallelLimit();
        if (totalParallel <= 0L) {
            invalidateLookupSetCache();
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
            invalidateLookupSetCache();
            return null;
        }
        return RecipeCalculationHelper.INSTANCE.getFinalParallelData(
                0L, parallels, new LongArrayList(), new IntArrayList(), (ObjectList<GTRecipe>) recipeList);
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
        long outputMax = IParallelLogic.getMinParallel(getMachine(), amplified, inputMax);
        if (outputMax <= 0L) return null;
        return findMatchableScaledRecipe(amplified, outputMax);
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
                    GTRecipe scaledOutputRecipe = RecipeCalculationHelper.INSTANCE.multipleRecipe(
                            amplifiedOutputRecipe, parallel);
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
        final long[] matchedParallel = new long[1];
        final GTRecipe[] matchedRecipe = new GTRecipe[1];
        long parallel = findHighestMatchableParallel(requestedParallel, candidate -> {
            GTRecipe scaledRecipe = RecipeCalculationHelper.INSTANCE.multipleRecipe(recipe, candidate);
            if (matchRecipeInputHandlePartCache(scaledRecipe)
                    && RecipeRunnerHelper.matchRecipeOutput(getMachine(), scaledRecipe)) {
                matchedParallel[0] = candidate;
                matchedRecipe[0] = scaledRecipe;
                return true;
            }
            return false;
        });
        if (parallel <= 0L) {
            return null;
        }
        if (matchedParallel[0] == parallel && matchedRecipe[0] != null) {
            return new MatchableScaledRecipe(parallel, matchedRecipe[0]);
        }
        return new MatchableScaledRecipe(parallel, RecipeCalculationHelper.INSTANCE.multipleRecipe(recipe, parallel));
    }

    private boolean matchesScaledRecipe(GTRecipe recipe, long parallel) {
        GTRecipe scaledRecipe = RecipeCalculationHelper.INSTANCE.multipleRecipe(recipe, parallel);
        return matchRecipeInputHandlePartCache(scaledRecipe)
                && RecipeRunnerHelper.matchRecipeOutput(getMachine(), scaledRecipe);
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

        int recipeHash = System.identityHashCode(recipe);
        long now = getMachine().getOffsetTimer();
        Long trueUntil = moduleConditionTrueCache.get(recipeHash);
        if (trueUntil != null) {
            if (now < trueUntil) {
                return true;
            }
            moduleConditionTrueCache.remove(recipeHash);
        }
        CachedModuleConditionFailure cachedFailure = moduleConditionFalseCache.get(recipeHash);
        if (cachedFailure != null) {
            if (now < cachedFailure.expiresAt) {
                setConditionError(cachedFailure.message);
                return false;
            }
            moduleConditionFalseCache.remove(recipeHash);
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
        int recipeHash = System.identityHashCode(recipe);
        moduleConditionFalseCache.remove(recipeHash);
        moduleConditionTrueCache.put(recipeHash, getMachine().getOffsetTimer() + MODULE_CONDITION_TRUE_TTL);
    }

    private void cacheModuleConditionFalse(GTRecipe recipe, String message) {
        int recipeHash = System.identityHashCode(recipe);
        moduleConditionTrueCache.remove(recipeHash);
        moduleConditionFalseCache.put(recipeHash, new CachedModuleConditionFailure(
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
            return mod.isRecipeTypeSelected(type);
        }
        return true;
    }
}
