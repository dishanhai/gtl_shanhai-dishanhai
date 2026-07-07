package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.RecipeCacheStrategy;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public abstract class PrimordialModuleRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {

    private static final long LOOKUP_CACHE_TICKS = 20L; // 延长到 20 tick（1秒）
    private static final org.slf4j.Logger DIAG_LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai.PrimordialDiag");

    private long cachedLookupTick = Long.MIN_VALUE;
    private GTRecipeType cachedLookupForcedType;
    private Set<GTRecipe> cachedLookupRecipes;
    private int cachedLookupRecipesHash = 0; // 配方集合指纹
    private Set<GTRecipe> cachedNormalRecipes;
    private int cachedNormalRecipesHash = 0; // normal 配方集合指纹
    private final StableFilteredSetCache<GTRecipe> cachedNormalRecipeCandidates = new StableFilteredSetCache<>();
    
    // checkModuleCondition 缓存：避免每次都查静态注册表和遍历 conditions
    // 注意：模块等级变化时需手动调用 onModuleLevelChanged() 清空此缓存
    // true 永久缓存（靠 onModuleLevelChanged / 重选配方类型清空）；
    // false 仅短期缓存 MODULE_CONDITION_FALSE_TTL tick，防止一次瞬时 false
    //（host 未连接 / 模块槽未就绪 / 模块集空）被永久固化，导致配方永久待机、必须重选才恢复。
    private final java.util.Map<GTRecipe, Boolean> moduleConditionCache = new java.util.HashMap<>();
    private final java.util.Map<GTRecipe, Long> moduleConditionFalseCache = new java.util.HashMap<>();
    private static final long MODULE_CONDITION_FALSE_TTL = 20L;

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

    public void onRecipeTypeSelectionChanged() {
        cachedLookupTick = Long.MIN_VALUE;
        cachedLookupForcedType = null;
        cachedLookupRecipes = null;
        cachedLookupRecipesHash = 0;
        cachedNormalRecipes = null;
        cachedNormalRecipesHash = 0;
        cachedNormalRecipeCandidates.clear();
        moduleConditionCache.clear();
        moduleConditionFalseCache.clear();
        invalidateLookupCache(); // 同时失效父类缓存
        interruptRecipe();
    }
    
    /**
     * 模块等级变化时调用此方法清空缓存
     * 应该在模块升级/降级后调用，确保条件检查使用最新的模块等级
     */
    public void onModuleLevelChanged() {
        moduleConditionCache.clear();
        moduleConditionFalseCache.clear();
        // 清空配方缓存，因为条件检查结果变化可能导致配方集合变化
        cachedLookupRecipes = null;
        cachedLookupRecipesHash = 0;
        cachedNormalRecipes = null;
        cachedNormalRecipesHash = 0;
        cachedNormalRecipeCandidates.clear();
        invalidateLookupCache(); // 同时失效父类缓存
    }

    @Override
    public GTRecipe getGTRecipe() {
        GTRecipe recipe = super.getGTRecipe();
        if (recipe != null && !checkModuleCondition(recipe)) {
            return null;
        }
        return applyExtraMountRecipeEffects(recipe);
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
                && IGTRecipe.of(recipe).getEuTier() <= getMachine().getTier()
                && recipe.checkConditions(this).isSuccess();
    }

    @Override
    public long getMaxParallel(GTRecipe recipe, long limit) {
        if (recipe == null || limit <= 0L) return 0L;
        long max = IParallelLogic.getMaxParallel(getMachine(), recipe, limit);
        if (max <= 0L) return 0L;
        return RecipeRunnerHelper.matchRecipeOutput(getMachine(), recipe) ? max : 0L;
    }

    @Override
    protected WirelessGTRecipe buildFinalWirelessRecipe(ParallelData parallelData, IWirelessNetworkEnergyHandler wirelessTrait) {
        if (parallelData == null || wirelessTrait == null || !wirelessTrait.isOnline()) return null;

        IRecipeCapabilityHolder machine = getMachine();
        BigInteger maxTotalEu = wirelessTrait.getMaxAvailableEnergy();
        double euMultiplier = getEuMultiplier();
        boolean energyConsumer = isEnergyConsumer();
        
        int size = parallelData.getOriginRecipeList().size();
        // 预分配容量，减少 ArrayList 扩容开销（假设每个配方平均 2 个输出）
        ObjectArrayList<Content> itemOutputs = new ObjectArrayList<>(size * 2);
        ObjectArrayList<Content> fluidOutputs = new ObjectArrayList<>(size);
        BigInteger accumulatedEu = BigInteger.ZERO;
        
        // 预计算常量，避免循环内重复创建 BigDecimal
        BigDecimal euMultiplierBD = BigDecimal.valueOf(euMultiplier);

        for (int i = 0; i < size; i++) {
            GTRecipe recipe = parallelData.getOriginRecipeList().get(i);
            long parallel = parallelData.getParallels()[i];
            
            // 优化：尽量用 long 运算，减少 BigInteger 创建
            long recipeEUt = getWirelessRecipeEut(recipe);
            long totalEUt = recipeEUt * parallel; // long 乘法，溢出风险低（EU 值通常不超过 Long.MAX_VALUE）
            
            // 计算 EU：duration * euMultiplier * totalEUt
            BigDecimal nextEuDelta = BigDecimal.valueOf(recipe.duration)
                    .multiply(euMultiplierBD)
                    .multiply(BigDecimal.valueOf(totalEUt));
            BigInteger nextEu = accumulatedEu.add(nextEuDelta.toBigInteger());

            if (parallelData.getShouldProcess()) {
                if (energyConsumer && nextEu.compareTo(maxTotalEu) > 0) {
                    if (accumulatedEu.signum() == 0) {
                        RecipeResult.of(getMachine(), RecipeResult.FAIL_NO_ENOUGH_EU_IN);
                    }
                    continue;
                }
                GTRecipe processed = IParallelLogic.getRecipeOutputChance(machine,
                        RecipeCalculationHelper.INSTANCE.multipleRecipe(recipe, parallel));
                if (matchRecipeInputHandlePartCache(processed)
                        && handleRecipeInputHandlePartCache(processed)) {
                    accumulatedEu = nextEu;
                    RecipeCalculationHelper.INSTANCE.collectOutputs(processed,
                            (List<Content>) itemOutputs,
                            (List<Content>) fluidOutputs);
                }
            } else {
                accumulatedEu = nextEu;
                List<GTRecipe> processedRecipes = parallelData.getProcessedRecipeList();
                if (processedRecipes != null) {
                    RecipeCalculationHelper.INSTANCE.collectOutputs(processedRecipes.get(i),
                            (List<Content>) itemOutputs,
                            (List<Content>) fluidOutputs);
                }
            }
        }

        BigInteger totalEu = energyConsumer ? accumulatedEu : accumulatedEu.negate();
        if (isEnergyConsumer() && !RecipeCalculationHelper.INSTANCE.hasOutputs(itemOutputs, fluidOutputs)) {
            if (getRecipeStatus() == null || getRecipeStatus().isSuccess()) {
                RecipeResult.of(getMachine(), RecipeResult.FAIL_FIND);
            }
            return null;
        }
        return buildWirelessRecipe(itemOutputs, fluidOutputs, totalEu);
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        MetaMachine metaMachine = getMachine();
        GTRecipeType[] selectedTypes = null;
        if (metaMachine instanceof SelectableRecipeTypeSetMachine selectable) {
            selectedTypes = selectable.getRecipeTypes();
            if (selectedTypes.length == 0) {
                return java.util.Collections.emptySet();
            }
        }
        if (selectedTypes == null) {
            selectedTypes = getMachine().getRecipeTypes();
        }
        if (selectedTypes == null || selectedTypes.length == 0) {
            return java.util.Collections.emptySet();
        }
        long tick = getMachine().getOffsetTimer();
        GTRecipeType forcedType = ProgrammableHatchPartMachine.getSelectedRecipeTypeFor(getMachine());
        if (cachedLookupTick == tick && cachedLookupForcedType == forcedType && cachedLookupRecipes != null) {
            return cachedLookupRecipes;
        }
        if (forcedType == null && cachedLookupForcedType == null && cachedLookupRecipes != null
                && tick - cachedLookupTick > 0 && tick - cachedLookupTick < LOOKUP_CACHE_TICKS) {
            return cachedLookupRecipes;
        }

        Set<GTRecipe> result = lookupRecipeIteratorUncached(forcedType, selectedTypes);
        if (result.isEmpty() && tick % 40 == 0) {
            gtShanhai$diagEmptyLookup(selectedTypes);
        }
        cachedLookupTick = tick;
        cachedLookupForcedType = forcedType;
        cachedLookupRecipes = result;
        cachedLookupRecipesHash = result.isEmpty() ? 0 : System.identityHashCode(result);
        return result;
    }

    /** 诊断：配方查找返回空时，逐项打印每个选中配方类型卡在哪个检查 */
    private void gtShanhai$diagEmptyLookup(GTRecipeType[] selectedTypes) {
        MetaMachine m = getMachine();
        DIAG_LOG.warn("[空查找] 机器={} tier={} 选中类型数={}",
                m.getClass().getSimpleName(), getMachine().getTier(),
                selectedTypes == null ? 0 : selectedTypes.length);
        if (selectedTypes == null) return;
        for (GTRecipeType type : selectedTypes) {
            if (type == null) continue;
            String tn = type.registryName != null ? type.registryName.toString() : "null";
            if (!isSelectedRecipeType(type)) { DIAG_LOG.warn("  [{}] 未选中", tn); continue; }
            GTRecipe raw = type.getLookup().find(getMachine(), r -> true);
            if (raw == null) { DIAG_LOG.warn("  [{}] find(全通过)=null 该类型无任何配方", tn); continue; }
            String rid = raw.getId() != null ? raw.getId().toString() : "?";
            boolean inMatch = matchRecipeInputHandlePartCache(raw);
            boolean outMatch = RecipeRunnerHelper.matchRecipeOutput(getMachine(), raw);
            boolean euOk = IGTRecipe.of(raw).getEuTier() <= getMachine().getTier();
            boolean condOk = raw.checkConditions(this).isSuccess();
            boolean modOk = checkModuleCondition(raw);
            DIAG_LOG.warn("  [{}] recipe={} 输入料匹配={} 输出空间={} EU层级={}(需{}<=机{}) gt条件={} 模块条件={}",
                    tn, rid, inMatch, outMatch, euOk,
                    IGTRecipe.of(raw).getEuTier(), getMachine().getTier(), condOk, modOk);
        }
    }

    private Set<GTRecipe> lookupRecipeIteratorUncached(GTRecipeType forcedType, GTRecipeType[] selectedTypes) {
        Set<GTRecipe> result = new ObjectOpenHashSet<>();
        if (forcedType != null) {
            if (!isSelectedRecipeType(forcedType)) return result;
            GTRecipe recipe = forcedType.getLookup().find(getMachine(), this::checkRecipeInKnownSelectedType);
            if (recipe != null && checkModuleCondition(recipe)) {
                result.add(recipe);
            }
            return result;
        }

        Set<GTRecipe> cachedNormal = lookupCachedNormalRecipes();
        if (cachedNormal != null) {
            return cachedNormal;
        }

        boolean conditionsPassed = true;
        for (GTRecipeType type : selectedTypes) {
            if (type == null) continue;
            GTRecipe recipe = type.getLookup().find(getMachine(), this::checkRecipeInKnownSelectedType);
            if (recipe == null) continue;
            if (checkModuleCondition(recipe)) {
                result.add(recipe);
            } else {
                conditionsPassed = false;
            }
        }
        cachedNormalRecipes = result.isEmpty() ? null : new ObjectOpenHashSet<>(result);
        if (cachedNormalRecipes == null) {
            cachedNormalRecipeCandidates.clear();
            cachedNormalRecipesHash = 0;
        } else {
            cachedNormalRecipeCandidates.set(cachedNormalRecipes);
            cachedNormalRecipesHash = System.identityHashCode(cachedNormalRecipes);
        }
        if (conditionsPassed) mergePatternRecipes(result);
        return result;
    }

    private Set<GTRecipe> lookupCachedNormalRecipes() {
        Set<GTRecipe> recipes = cachedNormalRecipeCandidates.get();
        if (recipes == null || recipes.isEmpty()) return null;
        Set<GTRecipe> stableRecipes = cachedNormalRecipeCandidates.retainIf(this::isCachedNormalRecipeStillCandidate);
        if (stableRecipes == null || stableRecipes.isEmpty()) {
            cachedNormalRecipes = null;
            cachedNormalRecipesHash = 0;
            return null;
        }
        cachedNormalRecipes = stableRecipes;
        cachedNormalRecipesHash = System.identityHashCode(stableRecipes);
        if (!hasDynamicPatternRecipeSources()) {
            return stableRecipes;
        }
        Set<GTRecipe> merged = new ObjectOpenHashSet<>(stableRecipes);
        mergePatternRecipes(merged);
        return merged;
    }

    private boolean isCachedNormalRecipeStillCandidate(GTRecipe recipe) {
        return recipe != null
                && recipe.recipeType != null
                && isSelectedRecipeType(recipe.recipeType)
                && IGTRecipe.of(recipe).getEuTier() <= getMachine().getTier()
                && recipe.checkConditions(this).isSuccess()
                && checkModuleCondition(recipe);
    }

    private boolean hasDynamicPatternRecipeSources() {
        MetaMachine machine = getMachine();
        if (machine instanceof IRecipeCapabilityMachine recipeMachine
                && !recipeMachine.getMEPatternRecipeHandleParts().isEmpty()) {
            return true;
        }
        return hasHostPatternBuffers();
    }

    private boolean checkRecipeInKnownSelectedType(GTRecipe recipe) {
        return recipe != null
                && recipe.recipeType != null
                && matchRecipeInputHandlePartCache(recipe)
                && RecipeRunnerHelper.matchRecipeOutput(getMachine(), recipe)
                && IGTRecipe.of(recipe).getEuTier() <= getMachine().getTier()
                && recipe.checkConditions(this).isSuccess();
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

    private boolean mergeRegisteredPatternHandleRecipes(Set<GTRecipe> result) {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase mod)) return false;
        if (!(machine instanceof IRecipeCapabilityMachine recipeMachine)) return false;

        boolean added = false;
        for (MEPatternRecipeHandlePart part : recipeMachine.getMEPatternRecipeHandleParts()) {
            if (part != null) {
                added |= addCachedPatternRecipes(mod, result, part.getCachedGTRecipe());
            }
        }
        return added;
    }

    private boolean hasHostPatternBuffers() {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase mod)) return false;
        PrimordialOmegaEngineMachine host = mod.getHost();
        if (host == null || !host.isFormed()) return false;
        return !mod.getHostPatternBuffers().isEmpty();
    }

    /** 手动检查模块条件——优先从静态注册表读（绕过 KubeJS 序列化类型丢失） */
    private boolean checkModuleCondition(GTRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        
        // 缓存命中：true 永久有效（靠 onModuleLevelChanged / 重选配方类型清空）
        Boolean cached = moduleConditionCache.get(recipe);
        if (Boolean.TRUE.equals(cached)) {
            clearConditionError();
            return true;
        }
        // false 仅短期有效：过期后重新检查，避免一次瞬时 false 被永久固化
        Long falseUntil = moduleConditionFalseCache.get(recipe);
        if (falseUntil != null) {
            if (getMachine().getOffsetTimer() < falseUntil) {
                updateConditionErrorFromCache(recipe);
                return false;
            }
            moduleConditionFalseCache.remove(recipe);
        }
        
        MetaMachine machine = getMachine();
        clearConditionError(); // 每 tick 重置，防止状态残留
        String recipeId = recipe.getId() != null ? recipe.getId().toString() : "";

        // 1. 优先从静态注册表查（模糊匹配 ID）
        java.util.List<com.dishanhai.gt_shanhai.api.ModuleLevelCondition> staticReqs =
                com.dishanhai.gt_shanhai.api.ModuleLevelCondition.getRequirements(recipeId);
        if (staticReqs != null && !staticReqs.isEmpty()) {
            for (var mlc : staticReqs) {
                if (!mlc.checkModuleLevel(machine)) {
                    setError(machine, mlc.getFailTooltip().getString(), mlc.moduleId, mlc.requiredLevel);
                    cacheModuleConditionFalse(recipe);
                    return false;
                }
                setError(machine, mlc.getPassTooltip().getString(), mlc.moduleId, mlc.requiredLevel);
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
                    setError(machine, mlc.getFailTooltip().getString(), mlc.moduleId, mlc.requiredLevel);
                    cacheModuleConditionFalse(recipe);
                    return false;
                }
                setError(machine, mlc.getPassTooltip().getString(), mlc.moduleId, mlc.requiredLevel);
                continue;
            }
            var type = cond.getType();
            if (type == com.dishanhai.gt_shanhai.api.ModuleLevelCondition.TYPE && cond instanceof com.dishanhai.gt_shanhai.api.ModuleLevelCondition mlc2) {
                if (!mlc2.checkModuleLevel(machine)) {
                    setError(machine, mlc2.getFailTooltip().getString(), mlc2.moduleId, mlc2.requiredLevel);
                    cacheModuleConditionFalse(recipe);
                    return false;
                }
                setError(machine, mlc2.getPassTooltip().getString(), mlc2.moduleId, mlc2.requiredLevel);
            }
        }
        cacheModuleConditionTrue(recipe);
        return true;
    }

    private void cacheModuleConditionTrue(GTRecipe recipe) {
        moduleConditionFalseCache.remove(recipe);
        moduleConditionCache.put(recipe, Boolean.TRUE);
    }

    private void cacheModuleConditionFalse(GTRecipe recipe) {
        moduleConditionCache.remove(recipe);
        moduleConditionFalseCache.put(recipe, getMachine().getOffsetTimer() + MODULE_CONDITION_FALSE_TTL);
    }
    
    private void updateConditionErrorFromCache(GTRecipe recipe) {
        // 简化版：缓存失败时不重新查询详细错误信息
        // 如需完整错误信息可保留原逻辑，但会降低缓存效果
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            mod.setModuleConditionError("配方条件未满足");
        }
    }

    private void setError(MetaMachine machine, String msg, String moduleId, int requiredLevel) {
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            // 模块侧用详细诊断替代通用错误信息
            String diag = mod.getModuleConditionDiagnosis(moduleId, requiredLevel);
            mod.setModuleConditionError(diag != null ? diag : msg);
        }
    }

    private void clearConditionError() {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            mod.setModuleConditionError(null);
        }
    }

    private boolean mergePatternRecipes(Set<GTRecipe> result) {
        MetaMachine machine = getMachine();
        if (!(machine instanceof PrimordialOmegaEngineModuleBase mod)) return false;
        boolean added = false;
        added |= mergeRegisteredPatternHandleRecipes(result);
        added |= addVirtualPatternRecipes(mod, result, RecipeTypePatternSearchHelper.collectMarkedPatternRecipes(getMachine()));

        PrimordialOmegaEngineMachine host = mod.getHost();
        if (host == null || !host.isFormed()) return added;
        if (added) return true;

        for (var buf : mod.getHostPatternBuffers()) {
            var trait = buf.getMETrait();
            if (trait != null) {
                added |= addCachedPatternRecipes(mod, result, trait.getCachedGTRecipe());
            }
            added |= addVirtualPatternRecipes(mod, result, RecipeTypePatternSearchHelper.collectMarkedPatternRecipesFromBuffer(getMachine(), buf));
        }
        return added;
    }

    private boolean addVirtualPatternRecipes(PrimordialOmegaEngineModuleBase mod, Set<GTRecipe> result, Collection<GTRecipe> recipes) {
        if (recipes == null || recipes.isEmpty()) return false;
        Set<String> ownTypeNames = mod.getRecipeTypeNameSet();
        boolean added = false;
        for (GTRecipe recipe : recipes) {
            if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) continue;
            if (!ownTypeNames.contains(recipe.recipeType.registryName.toString())) continue;
            if (!matchRecipeInputHandlePartCache(recipe)) continue;
            if (!RecipeRunnerHelper.matchRecipeOutput(getMachine(), recipe)) continue;
            if (IGTRecipe.of(recipe).getEuTier() > getMachine().getTier()) continue;
            if (!recipe.checkConditions(this).isSuccess()) continue;
            if (!checkModuleCondition(recipe)) continue;
            if (result.add(recipe)) {
                added = true;
            }
        }
        return added;
    }

    private boolean addCachedPatternRecipes(PrimordialOmegaEngineModuleBase mod, Set<GTRecipe> result, Collection<GTRecipe> cached) {
        if (cached == null || cached.isEmpty()) return false;
        Set<String> targetTypeNames = getTargetRecipeTypeNames(mod, cached);
        if (targetTypeNames.isEmpty()) return false;
        boolean added = false;
        for (GTRecipe r : cached) {
            if (r == null || r.recipeType == null || r.recipeType.registryName == null) continue;
            if (!targetTypeNames.contains(r.recipeType.registryName.toString())) continue;
            if (!checkModuleCondition(r)) continue;
            if (result.add(r)) {
                added = true;
            }
        }
        if (added) tryAutoMatch(mod, cached);
        return added;
    }

    private Set<String> getTargetRecipeTypeNames(PrimordialOmegaEngineModuleBase mod, Collection<GTRecipe> cached) {
        Set<String> ownTypeNames = mod.getRecipeTypeNameSet();
        Set<String> selectedTypeNames = getSelectedRecipeTypeNames(mod);
        Set<String> matches = new ObjectOpenHashSet<>();

        for (GTRecipe r : cached) {
            if (r == null || r.recipeType == null || r.recipeType.registryName == null) continue;
            String name = r.recipeType.registryName.toString();
            if (!ownTypeNames.contains(name)) continue;
            if (selectedTypeNames.contains(name)) {
                matches.add(name);
            }
        }
        return matches;
    }

    /** 旧版自动切换入口保留为空；配方类型选择集由玩家显式控制。 */
    private void tryAutoMatch(PrimordialOmegaEngineModuleBase mod,
                              Collection<GTRecipe> cached) {
    }

    private boolean isSelectedRecipeType(GTRecipeType type) {
        MetaMachine machine = getMachine();
        if (machine instanceof PrimordialOmegaEngineModuleBase mod) {
            return mod.isRecipeTypeSelected(type);
        }
        return true;
    }

    private Set<String> getSelectedRecipeTypeNames(PrimordialOmegaEngineModuleBase mod) {
        Set<String> names = new ObjectOpenHashSet<>();
        for (GTRecipeType type : mod.getSelectedRecipeTypes()) {
            if (type != null && type.registryName != null) {
                names.add(type.registryName.toString());
            }
        }
        return names;
    }
}
