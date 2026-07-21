package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.crafting.pattern.AEProcessingPattern;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Rewrites GT non-consumable item inputs in AE processing patterns to virtual item providers.
 */
public final class VirtualPatternEncodingHelper {

    private static final Logger LOG = LoggerFactory.getLogger("VirtualPatternEncodingHelper");
    private static final long VIRTUAL_FLUID_MARKER_AMOUNT = 1L;
    private static final int PATTERN_ANALYSIS_CACHE_LIMIT = 4096;
    private static final Map<PatternKey, PatternAnalysis> PATTERN_ANALYSIS_CACHE = new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PatternKey, PatternAnalysis> eldest) {
            return size() > PATTERN_ANALYSIS_CACHE_LIMIT;
        }
    };
    private static RecipeOutputIndexes recipeOutputIndexes;

    public static GenericStack[] rewriteInputsForVirtualProviders(GenericStack[] inputs, GenericStack[] outputs) {
        if (inputs == null || outputs == null) {
            return inputs;
        }
        if (PatternRecipeTypeHelper.currentAuthoritativeEncodingRecipe() != null) {
            return inputs;
        }

        PatternEncodeOverride override = PatternEncodeOverride.current();
        PatternEncodeOverride.WrapMode mode = override == null ? PatternEncodeOverride.WrapMode.AUTO : override.mode;

        if (mode == PatternEncodeOverride.WrapMode.FORCE_NO_WRAP) {
            PatternEncodeOverride.setLastDiagnostic("wrap forced OFF, pattern encoded raw");
            return inputs;
        }

        GenericStack[] result;
        String autoDiagnostic = null;
        if (mode == PatternEncodeOverride.WrapMode.FORCE_WRAP) {
            result = inputs.clone();
            autoDiagnostic = "auto-match skipped (force-wrap mode)";
        } else {
            GTRecipe recipe = findMatchingRecipeForEncoding(inputs, outputs);
            if (recipe == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] no unique recipe matched for outputs={}, wrap skipped, inputs left raw",
                            StackBag.of(outputs));
                }
                result = inputs;
                autoDiagnostic = "no unique recipe matched";
            } else {
                GenericStack[] rewritten = rewriteInputsPreservingSelections(inputs, recipe);
                if (rewritten == inputs) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[VirtualPatternEncoding] recipe={} matched but rewrite was rejected, inputs left raw",
                                recipe.getId());
                    }
                    autoDiagnostic = "recipe " + recipe.getId() + " matched but wrap rejected";
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("[VirtualPatternEncoding] recipe={} matched, inputs rewritten with virtual providers",
                                recipe.getId());
                    }
                    autoDiagnostic = "recipe " + recipe.getId() + " matched, wrapped";
                }
                result = rewritten;
            }
        }

        Set<Integer> forcedSlots = override == null ? null : override.forcedSlots;
        int manuallyWrapped = 0;
        if (forcedSlots != null && !forcedSlots.isEmpty()) {
            for (Integer slot : forcedSlots) {
                if (slot == null || slot < 0 || slot >= inputs.length) continue;
                GenericStack raw = inputs[slot];
                if (raw == null || !(raw.what() instanceof AEItemKey key)) continue;
                ItemStack rawStack = key.toStack((int) Math.min(Integer.MAX_VALUE, Math.max(1L, raw.amount())));
                if (VirtualItemProviderHelper.isProviderItem(rawStack) || VirtualItemProviderHelper.isAutoWrapExcluded(rawStack)) continue;
                ItemStack provider = VirtualItemProviderHelper.createBoundProvider(rawStack);
                if (provider.isEmpty()) continue;
                if (result == inputs) result = inputs.clone();
                result[slot] = new GenericStack(AEItemKey.of(provider), 1);
                manuallyWrapped++;
            }
        }

        String diagnostic = autoDiagnostic == null ? "" : autoDiagnostic;
        if (manuallyWrapped > 0) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[VirtualPatternEncoding] {} slot(s) force-wrapped by manual mark", manuallyWrapped);
            }
            diagnostic = diagnostic.isEmpty() ? (manuallyWrapped + " slot(s) manually marked, wrapped")
                    : diagnostic + "; " + manuallyWrapped + " manually marked slot(s) also wrapped";
        }
        if (!diagnostic.isEmpty()) {
            PatternEncodeOverride.setLastDiagnostic(diagnostic);
        }
        return result;
    }

    public static boolean containsVirtualProviderInput(GenericStack[] inputs) {
        return containsVirtualProviderInput(inputs, null);
    }

    public static boolean containsVirtualProviderInput(GenericStack[] inputs, GenericStack[] outputs) {
        return containsVirtualInput(inputs, outputs);
    }

    public static boolean containsVirtualProviderPattern(IPatternDetails details) {
        if (details == null || details.getInputs() == null) return false;
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (isPresenceInput(input)) return true;
        }
        return false;
    }

    public static boolean isPresenceInput(IPatternDetails.IInput input) {
        return input instanceof PresenceInput;
    }

    public static boolean containsPresenceInputs(ICraftingPlan plan) {
        if (plan == null || plan.patternTimes() == null) return false;
        for (IPatternDetails details : plan.patternTimes().keySet()) {
            if (containsVirtualProviderPattern(details)) return true;
        }
        return false;
    }

    /**
     * Presence inputs are reusable within a plan. Their initial ownership is therefore the
     * largest amount required by any pattern, not the sum of every planned execution.
     */
    public static Object2LongMap<AEKey> collectPresenceRequirements(ICraftingPlan plan) {
        Object2LongOpenHashMap<AEKey> requirements = new Object2LongOpenHashMap<>();
        requirements.defaultReturnValue(0L);
        if (plan == null || plan.patternTimes() == null) return requirements;
        for (IPatternDetails details : plan.patternTimes().keySet()) {
            if (details == null || details.getInputs() == null) continue;
            for (IPatternDetails.IInput input : details.getInputs()) {
                if (!isPresenceInput(input)) continue;
                GenericStack[] possibleInputs = input.getPossibleInputs();
                if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) continue;
                AEKey key = possibleInputs[0].what();
                long amount = Math.max(1L, input.getMultiplier());
                if (amount > requirements.getLong(key)) requirements.put(key, amount);
            }
        }
        return requirements;
    }

    /**
     * Upper bounds for ordinary consumable inputs in the plan. AE may merge a reusable presence
     * requirement with an ordinary requirement of the same key in {@code usedItems()}, so the
     * initial extractor must identify the ordinary portion from the actual pattern tasks instead
     * of subtracting the presence amount from the already-merged value.
     */
    public static Object2LongMap<AEKey> collectConsumableRequirements(ICraftingPlan plan) {
        Object2LongOpenHashMap<AEKey> requirements = new Object2LongOpenHashMap<>();
        requirements.defaultReturnValue(0L);
        if (plan == null || plan.patternTimes() == null) return requirements;
        for (Map.Entry<IPatternDetails, Long> task : plan.patternTimes().entrySet()) {
            IPatternDetails details = task.getKey();
            long times = task.getValue() == null ? 0L : Math.max(0L, task.getValue());
            if (details == null || details.getInputs() == null || times <= 0L) continue;
            for (IPatternDetails.IInput input : details.getInputs()) {
                if (isPresenceInput(input)) continue;
                GenericStack[] possibleInputs = input.getPossibleInputs();
                if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) continue;
                long perPattern = Math.max(0L, input.getMultiplier());
                long total = NumberUtils.saturatedMultiply(perPattern, times);
                if (total <= 0L) continue;
                AEKey key = possibleInputs[0].what();
                requirements.put(key, NumberUtils.saturatedAdd(requirements.getLong(key), total));
            }
        }
        return requirements;
    }

    public static IPatternDetails.IInput[] createPlanningInputs(GenericStack[] sparseInputs) {
        return createPlanningInputs(sparseInputs, null);
    }

    public static IPatternDetails.IInput[] createPlanningInputs(GenericStack[] sparseInputs, GenericStack[] sparseOutputs) {
        Map<AEKey, Long> condensed = new LinkedHashMap<>();
        Map<AEKey, Long> presenceTargets = new LinkedHashMap<>();
        PatternAnalysis analysis = getPatternAnalysis(sparseInputs, sparseOutputs);
        if (sparseInputs != null) {
            for (GenericStack input : sparseInputs) {
                if (input == null || input.what() == null || input.amount() <= 0) continue;
                AEKey key = input.what();
                long amount = input.amount();
                GenericStack presenceTarget = analysis.targetFor(input);
                if (presenceTarget != null) {
                    presenceTargets.merge(presenceTarget.what(), Math.max(1L, presenceTarget.amount()), Math::max);
                    continue;
                }
                Long old = condensed.get(key);
                condensed.put(key, old == null ? amount : old + amount);
            }
        }
        IPatternDetails.IInput[] result = new IPatternDetails.IInput[condensed.size() + presenceTargets.size()];
        int index = 0;
        for (Map.Entry<AEKey, Long> entry : condensed.entrySet()) {
            result[index++] = new PlanningInput(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<AEKey, Long> entry : presenceTargets.entrySet()) {
            result[index++] = new PresenceInput(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink) {
        pushSparseInputsIncludingVirtual(sparseInputs, null, inputHolder, inputSink, null);
    }

    public static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, GenericStack[] sparseOutputs,
            KeyCounter[] inputHolder, IPatternDetails.PatternInputSink inputSink) {
        pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink, null);
    }

    private static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, GenericStack[] sparseOutputs, KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink, BiConsumer<AEKey, Long> virtualTargetSink) {
        KeyCounter allInputs = new KeyCounter();
        if (inputHolder != null) {
            for (KeyCounter counter : inputHolder) {
                if (counter != null) {
                    allInputs.addAll(counter);
                }
            }
        }

        if (sparseInputs == null) return;
        PatternAnalysis analysis = getPatternAnalysis(sparseInputs, sparseOutputs);
        long patternMultiplier = countPatternMultiplier(allInputs, sparseInputs, analysis);
        for (GenericStack input : sparseInputs) {
            if (input == null || input.what() == null || input.amount() <= 0) continue;
            AEKey key = input.what();
            long amount = input.amount();
            GenericStack virtualTarget = analysis.targetFor(input);
            if (virtualTarget != null) {
                if (virtualTargetSink != null) {
                    virtualTargetSink.accept(virtualTarget.what(), Math.max(1L, virtualTarget.amount()));
                }
                continue;
            }

            long available = allInputs.get(key);
            long scaledAmount = multiplyAmount(amount, patternMultiplier);
            if (available < scaledAmount) {
                throw new RuntimeException(String.format(
                        "Expected at least %d of %s when pushing pattern, but only %d available",
                        scaledAmount, key, available));
            }
            inputSink.pushInput(key, scaledAmount);
            consumeInputHolder(inputHolder, key, scaledAmount);
            allInputs.remove(key, scaledAmount);
        }
    }

    private static long multiplyAmount(long amount, long multiplier) {
        if (amount <= 0 || multiplier <= 0) return 0L;
        if (amount > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return amount * multiplier;
    }

    private static long countPatternMultiplier(KeyCounter inputs, GenericStack[] sparseInputs, PatternAnalysis analysis) {
        if (inputs == null || sparseInputs == null) return 1L;
        long multiplier = Long.MAX_VALUE;
        for (GenericStack input : sparseInputs) {
            if (input == null || input.what() == null || input.amount() <= 0 || analysis.targetFor(input) != null) continue;
            long available = inputs.get(input.what());
            multiplier = Math.min(multiplier, available / input.amount());
        }
        if (multiplier == Long.MAX_VALUE || multiplier <= 0) return 1L;
        return multiplier;
    }

    private static void consumeInputHolder(KeyCounter[] inputHolder, AEKey key, long amount) {
        if (inputHolder == null || key == null || amount <= 0) return;
        long remaining = amount;
        for (KeyCounter counter : inputHolder) {
            if (counter == null) continue;
            long available = counter.get(key);
            long extracted = Math.min(available, remaining);
            if (extracted <= 0) continue;
            counter.remove(key, extracted);
            remaining -= extracted;
            if (remaining <= 0) {
                return;
            }
        }
    }

    public static boolean pushPatternInputsIncludingVirtual(IPatternDetails details, KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink) {
        return pushPatternInputsIncludingVirtual(details, inputHolder, inputSink, null);
    }

    public static boolean pushPatternInputsIncludingVirtual(IPatternDetails details, KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink, BiConsumer<AEKey, Long> virtualTargetSink) {
        GenericStack[] sparseInputs = getSparseInputs(details);
        GenericStack[] sparseOutputs = getSparseOutputs(details);
        if (!containsVirtualInput(sparseInputs, sparseOutputs)) {
            return false;
        }
        pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink, virtualTargetSink);
        return true;
    }

    public static GTRecipe findMatchingRecipeForPattern(GenericStack[] inputs, GenericStack[] outputs) {
        return findMatchingRecipe(inputs, outputs, getAllRecipeOutputIndex(), null, StackBag.EMPTY);
    }

    public static GTRecipe findMatchingRecipeForPattern(GenericStack[] inputs, GenericStack[] outputs,
            GenericStack[] availableCatalystInputs) {
        return findMatchingRecipe(inputs, outputs, getAllRecipeOutputIndex(), null,
                StackBag.of(availableCatalystInputs));
    }

    public static GTRecipe findMatchingRecipeForPattern(GenericStack[] inputs, GenericStack[] outputs, String recipeTypeId) {
        return findMatchingRecipeForPattern(inputs, outputs, recipeTypeId, null);
    }

    public static GTRecipe findMatchingRecipeForPattern(GenericStack[] inputs, GenericStack[] outputs,
            String recipeTypeId, GenericStack[] availableCatalystInputs) {
        GTRecipeType recipeType = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
        if (recipeType == null || recipeType.getLookup() == null || recipeType.getLookup().getLookup() == null) {
            return findMatchingRecipe(inputs, outputs, getAllRecipeOutputIndex(), null,
                    StackBag.of(availableCatalystInputs));
        }
        return findMatchingRecipe(inputs, outputs, getAllRecipeOutputIndex(), recipeType,
                StackBag.of(availableCatalystInputs));
    }

    public static int detectPatternOutputMultiplier(IPatternDetails pattern, String recipeTypeId) {
        if (!(pattern instanceof AEProcessingPattern processingPattern)) return 0;
        GenericStack[] inputs = processingPattern.getSparseInputs();
        GenericStack[] outputs = processingPattern.getSparseOutputs();
        GTRecipe recipe = findMatchingRecipeForPattern(inputs, outputs, recipeTypeId);
        if (recipe == null) return 0;
        long multiplier = detectRecipeOutputMultiplier(recipe, StackBag.of(outputs));
        return multiplier <= 0L ? 0 : (int) Math.min(1000L, multiplier);
    }

    public static IPatternDetails rewritePatternOutputMultiplier(IPatternDetails pattern, Level level,
            String recipeTypeId, int requestedMultiplier) {
        if (!(pattern instanceof AEProcessingPattern processingPattern) || level == null) return pattern;
        GenericStack[] inputs = processingPattern.getSparseInputs();
        GenericStack[] outputs = processingPattern.getSparseOutputs();
        GTRecipe recipe = findMatchingRecipeForPattern(inputs, outputs, recipeTypeId);
        if (recipe == null) {
            return rewritePatternOutputMultiplierDirect(processingPattern, level, requestedMultiplier);
        }

        long currentMultiplier = detectRecipeOutputMultiplier(recipe, StackBag.of(outputs));
        if (currentMultiplier <= 0L) {
            return rewritePatternOutputMultiplierDirect(processingPattern, level, requestedMultiplier);
        }
        int targetMultiplier = Math.max(1, Math.min(1000, requestedMultiplier));
        GenericStack[] rewrittenInputs = rewriteCycleContainerInputs(
                inputs, currentMultiplier, targetMultiplier);
        if (rewrittenInputs == null) return pattern;
        GenericStack[] rewrittenOutputs = createScaledRecipeOutputs(recipe, targetMultiplier);
        IPatternDetails rewritten = PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeProcessingPattern(rewrittenInputs, rewrittenOutputs), level);
        return rewritten == null ? pattern : rewritten;
    }

    private static IPatternDetails rewritePatternOutputMultiplierDirect(AEProcessingPattern pattern, Level level,
            int requestedMultiplier) {
        int targetMultiplier = Math.max(1, Math.min(1000, requestedMultiplier));
        if (targetMultiplier <= 1) return pattern;
        GenericStack[] rewrittenInputs = multiplyCycleContainerStacks(pattern.getSparseInputs(), targetMultiplier, true);
        GenericStack[] rewrittenOutputs = multiplyCycleContainerStacks(pattern.getSparseOutputs(), targetMultiplier, false);
        IPatternDetails rewritten = PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeProcessingPattern(rewrittenInputs, rewrittenOutputs), level);
        return rewritten == null ? pattern : rewritten;
    }

    private static GenericStack[] multiplyCycleContainerStacks(GenericStack[] stacks,
            long multiplier, boolean inputs) {
        if (stacks == null) return new GenericStack[0];
        List<GenericStack> rewritten = new ArrayList<>();
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null) continue;
            boolean keepAmount = inputs != isRecipeCycleContainerKey(stack.what());
            long amount = keepAmount ? stack.amount() : NumberUtils.saturatedMultiply(stack.amount(), multiplier);
            rewritten.add(new GenericStack(stack.what(), amount));
        }
        return rewritten.toArray(new GenericStack[0]);
    }

    private static GenericStack[] rewriteCycleContainerInputs(GenericStack[] inputs,
            long currentMultiplier, long targetMultiplier) {
        if (inputs == null) return null;
        GenericStack[] rewritten = new GenericStack[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            GenericStack input = inputs[i];
            if (input == null || !isRecipeCycleContainerKey(input.what())) {
                rewritten[i] = input;
                continue;
            }
            if (input.amount() <= 0L || input.amount() % currentMultiplier != 0L) return null;
            long baseAmount = input.amount() / currentMultiplier;
            rewritten[i] = new GenericStack(input.what(), NumberUtils.saturatedMultiply(baseAmount, targetMultiplier));
        }
        return rewritten;
    }

    private static GenericStack[] createScaledRecipeOutputs(GTRecipe recipe, long targetMultiplier) {
        List<GenericStack> outputs = createRecipeOutputs(recipe);
        GenericStack[] rewritten = new GenericStack[outputs.size()];
        for (int i = 0; i < outputs.size(); i++) {
            GenericStack output = outputs.get(i);
            long amount = isRecipeCycleContainerKey(output.what())
                    ? output.amount() : NumberUtils.saturatedMultiply(output.amount(), targetMultiplier);
            rewritten[i] = new GenericStack(output.what(), amount);
        }
        return rewritten;
    }

    private static GenericStack[] getSparseInputs(IPatternDetails details) {
        if (details instanceof AEProcessingPattern pattern) {
            return pattern.getSparseInputs();
        }
        return null;
    }

    private static GenericStack[] getSparseOutputs(IPatternDetails details) {
        if (details instanceof AEProcessingPattern pattern) {
            return pattern.getSparseOutputs();
        }
        return null;
    }

    private static boolean containsVirtualInput(GenericStack[] stacks, GenericStack[] outputs) {
        return getPatternAnalysis(stacks, outputs).containsVirtualInput();
    }

    private static boolean isVirtualInput(GenericStack stack, GenericStack[] inputs, GenericStack[] outputs) {
        return getVirtualTarget(stack, inputs, outputs) != null;
    }

    private static boolean isVirtualProvider(GenericStack stack) {
        return getVirtualProviderTarget(stack) != null;
    }

    private static GenericStack getVirtualTarget(GenericStack stack, GenericStack[] inputs, GenericStack[] outputs) {
        return getPatternAnalysis(inputs, outputs).targetFor(stack);
    }

    private static GenericStack getVirtualProviderTarget(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey key)) return null;
        ItemStack provider = key.toStack();
        if (!VirtualItemProviderHelper.isProviderItem(provider)) return null;
        ItemStack target = VirtualItemProviderHelper.getTarget(provider);
        if (target.isEmpty()) return null;
        return new GenericStack(AEItemKey.of(target), Math.max(1L, target.getCount()));
    }

    // 裸编程电路(未被包裹的集成电路)识别为"自映射虚拟目标"：目标就是电路自身、数量恒为 1。
    // 不创建 virtual provider 物品，交给样板总成的虚拟电路机制处理（见 analyzePattern 注释）。
    private static GenericStack getIntegratedCircuitTarget(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey key)) return null;
        if (!IntCircuitBehaviour.isIntegratedCircuit(key.toStack())) return null;
        return new GenericStack(stack.what(), 1);
    }

    private static boolean isVirtualFluidInput(GenericStack stack, GenericStack[] inputs, GenericStack[] outputs) {
        return getVirtualFluidTarget(stack, inputs, outputs) != null;
    }

    private static GenericStack getVirtualFluidTarget(GenericStack stack, GenericStack[] inputs, GenericStack[] outputs) {
        if (stack == null || !(stack.what() instanceof AEFluidKey) || inputs == null || outputs == null) return null;
        return getPatternAnalysis(inputs, outputs).targetFor(stack);
    }

    private static PatternAnalysis getPatternAnalysis(GenericStack[] inputs, GenericStack[] outputs) {
        PatternKey key = new PatternKey(StackBag.of(inputs), StackBag.of(outputs), DShanhaiRecipeModifierAPI.getPatternCacheRevision());
        synchronized (PATTERN_ANALYSIS_CACHE) {
            PatternAnalysis cached = PATTERN_ANALYSIS_CACHE.get(key);
            if (cached != null) return cached;
        }
        PatternAnalysis analysis = analyzePattern(inputs, outputs);
        synchronized (PATTERN_ANALYSIS_CACHE) {
            PATTERN_ANALYSIS_CACHE.put(key, analysis);
        }
        return analysis;
    }

    private static PatternAnalysis analyzePattern(GenericStack[] inputs, GenericStack[] outputs) {
        Map<Entry, GenericStack> targets = new LinkedHashMap<>();
        boolean hasFluidCandidate = false;
        GTRecipe matchingRecipe = null;
        boolean matchingRecipeResolved = false;
        if (inputs != null) {
            for (GenericStack input : inputs) {
                GenericStack providerTarget = getVirtualProviderTarget(input);
                if (providerTarget != null) {
                    targets.put(new Entry(input.what(), input.amount()), providerTarget);
                    continue;
                }
                GenericStack circuitTarget = getIntegratedCircuitTarget(input);
                if (circuitTarget != null) {
                    // 编程电路(集成电路)是 GT 非消耗配置物，本就该交给样板总成的虚拟电路机制，
                    // 而不是包裹成 virtual provider。识别为"自映射虚拟目标"后下游全自动闭环：
                    // 求解层走 PresenceInput，被 CraftingTreeNodeVirtualPresenceMixin 封顶到
                    // multiplier(=1)、不再按合成批次放大；总成层 pushPattern 命中虚拟目标路径，
                    // 由 gtShanhai$cacheVirtualCircuit 把电路 config 交给 SlotCacheManager，
                    // 执行时总成用缓存 config 提供电路。电路保持自身身份，config NBT 精确保留。
                    targets.put(new Entry(input.what(), input.amount()), circuitTarget);
                    continue;
                }
                if (input != null && input.what() instanceof AEFluidKey && outputs != null) {
                    hasFluidCandidate = true;
                }
            }
        }
        if (hasFluidCandidate) {
            matchingRecipe = findMatchingRecipe(inputs, outputs);
            matchingRecipeResolved = true;
            if (matchingRecipe != null) {
                for (GenericStack input : inputs) {
                    if (input == null || !(input.what() instanceof AEFluidKey)) continue;
                    GenericStack virtualFluidTarget = getNonConsumableFluidTarget(matchingRecipe, input);
                    if (virtualFluidTarget != null) {
                        targets.put(new Entry(input.what(), input.amount()), virtualFluidTarget);
                    }
                }
            }
        }
        return new PatternAnalysis(targets, matchingRecipe, matchingRecipeResolved);
    }

    private static GTRecipe findMatchingRecipe(GenericStack[] inputs, GenericStack[] outputs) {
        return findMatchingRecipe(inputs, outputs, getRecipeOutputIndex());
    }

    private static GTRecipe findMatchingRecipe(GenericStack[] inputs, GenericStack[] outputs, RecipeOutputIndex index) {
        return findMatchingRecipe(inputs, outputs, index, null, StackBag.EMPTY);
    }

    private static GTRecipe findMatchingRecipeForEncoding(GenericStack[] inputs, GenericStack[] outputs) {
        String encodingRecipeTypeId = PatternRecipeTypeHelper.currentEncodingRecipeTypeId();
        GTRecipeType encodingRecipeType = PatternRecipeTypeHelper.resolveRecipeType(encodingRecipeTypeId);
        if (!encodingRecipeTypeId.isEmpty() && encodingRecipeType == null) return null;
        return findMatchingRecipe(inputs, outputs, getRecipeOutputIndex(), encodingRecipeType,
                StackBag.EMPTY, true);
    }

    private static GTRecipe findMatchingRecipe(GenericStack[] inputs, GenericStack[] outputs,
            RecipeOutputIndex index, GTRecipeType requiredType, StackBag availableCatalystInputs) {
        return findMatchingRecipe(inputs, outputs, index, requiredType, availableCatalystInputs, false);
    }

    private static GTRecipe findMatchingRecipe(GenericStack[] inputs, GenericStack[] outputs,
            RecipeOutputIndex index, GTRecipeType requiredType, StackBag availableCatalystInputs,
            boolean allowOmittedNonConsumables) {
        StackBag inputBag = StackBag.of(inputs);
        StackBag outputBag = StackBag.of(outputs);
        if ((!allowOmittedNonConsumables && inputBag.isEmpty()) || outputBag.isEmpty()) {
            return null;
        }

        RecipeSelection exactSelection = selectRecipeCandidate(
                index.candidates(outputBag), inputs, outputs, inputBag, outputBag,
                availableCatalystInputs, requiredType, false, allowOmittedNonConsumables);
        if (exactSelection.recipe != null) {
            return exactSelection.recipe;
        }
        if (exactSelection.ambiguous) {
            return null;
        }

        RecipeSelection scaledSelection = selectRecipeCandidate(
                index.scaledCandidates(outputBag), inputs, outputs, inputBag, outputBag,
                availableCatalystInputs, requiredType, true, allowOmittedNonConsumables);
        if (scaledSelection.recipe != null) {
            return scaledSelection.recipe;
        }
        if (scaledSelection.ambiguous) {
            return null;
        }

        if (requiredType != null) {
            RecipeSelection partialSelection = selectPartialTypeScopedCandidate(
                    index.partialCandidates(outputBag), inputs, inputBag, outputBag,
                    availableCatalystInputs, requiredType, allowOmittedNonConsumables);
            return partialSelection.ambiguous ? null : partialSelection.recipe;
        }
        return null;
    }

    private static RecipeSelection selectRecipeCandidate(List<GTRecipe> candidates,
            GenericStack[] patternInputs, GenericStack[] patternOutputs, StackBag inputBag, StackBag outputBag,
            StackBag availableCatalystInputs, GTRecipeType requiredType, boolean scaledOnly,
            boolean allowOmittedNonConsumables) {
        List<RecipeCandidate> matches = new ArrayList<>();
        for (GTRecipe recipe : candidates) {
            if (recipe == null || (requiredType != null && (recipe.recipeType == null
                    || !Objects.equals(recipe.recipeType.registryName, requiredType.registryName)))) {
                continue;
            }
            long multiplier = detectRecipeOutputMultiplier(recipe, outputBag);
            if ((scaledOnly && multiplier <= 1L) || (!scaledOnly && multiplier != 1L)) continue;
            if (!matchesRecipeInputsAtMultiplier(
                    recipe, patternInputs, inputBag, multiplier, availableCatalystInputs,
                    allowOmittedNonConsumables)) continue;
            matches.add(new RecipeCandidate(recipe, multiplier));
        }
        if (matches.isEmpty()) {
            return RecipeSelection.NONE;
        }
        if (matches.size() == 1) {
            return new RecipeSelection(matches.get(0).recipe, false);
        }
        if (requiredType != null) {
            GTRecipe reference = matches.get(0).recipe;
            for (int i = 1; i < matches.size(); i++) {
                if (!haveEquivalentRecipeInputsAndOutputs(reference, matches.get(i).recipe)) {
                    return RecipeSelection.AMBIGUOUS;
                }
            }
            return selectCanonicalTypeScopedCandidate(matches);
        }

        if (haveMultipleRecipeTypes(matches)) {
            logAmbiguousRecipeTypes(outputBag, matches);
            return RecipeSelection.AMBIGUOUS;
        }

        GTRecipe orderedMatch = null;
        for (RecipeCandidate candidate : matches) {
            if (!matchesOrderedRecipeOutputs(candidate.recipe, patternOutputs, candidate.multiplier)) continue;
            if (orderedMatch != null) {
                logAmbiguousOutput(outputBag, orderedMatch, candidate.recipe, requiredType);
                return RecipeSelection.AMBIGUOUS;
            }
            orderedMatch = candidate.recipe;
        }
        if (orderedMatch != null) return new RecipeSelection(orderedMatch, false);

        logAmbiguousOutput(outputBag, matches.get(0).recipe, matches.get(1).recipe, requiredType);
        return RecipeSelection.AMBIGUOUS;
    }

    private static RecipeSelection selectCanonicalTypeScopedCandidate(List<RecipeCandidate> matches) {
        RecipeCandidate canonical = matches.get(0);
        String canonicalId = canonical.recipe.getId().toString();
        for (int i = 1; i < matches.size(); i++) {
            RecipeCandidate candidate = matches.get(i);
            String candidateId = candidate.recipe.getId().toString();
            if (candidateId.compareTo(canonicalId) < 0) {
                canonical = candidate;
                canonicalId = candidateId;
            }
        }
        return new RecipeSelection(canonical.recipe, false);
    }

    private static RecipeSelection selectPartialTypeScopedCandidate(List<GTRecipe> candidates,
            GenericStack[] patternInputs, StackBag inputBag, StackBag outputBag,
            StackBag availableCatalystInputs, GTRecipeType requiredType,
            boolean allowOmittedNonConsumables) {
        List<RecipeCandidate> matches = new ArrayList<>();
        for (GTRecipe recipe : candidates) {
            if (recipe == null || recipe.recipeType == null
                    || !Objects.equals(recipe.recipeType.registryName, requiredType.registryName)) {
                continue;
            }
            long multiplier = detectPartialRecipeOutputMultiplier(recipe, outputBag);
            if (multiplier <= 0L) continue;
            if (!matchesRecipeInputsAtMultiplier(
                    recipe, patternInputs, inputBag, multiplier, availableCatalystInputs,
                    allowOmittedNonConsumables)) continue;
            matches.add(new RecipeCandidate(recipe, multiplier));
        }
        if (matches.isEmpty()) {
            return RecipeSelection.NONE;
        }

        GTRecipe reference = matches.get(0).recipe;
        for (int i = 1; i < matches.size(); i++) {
            if (!haveEquivalentRecipeInputsAndOutputs(reference, matches.get(i).recipe)) {
                return RecipeSelection.AMBIGUOUS;
            }
        }
        return selectCanonicalTypeScopedCandidate(matches);
    }

    private static void logAmbiguousOutput(StackBag outputBag, GTRecipe first, GTRecipe second,
            GTRecipeType requiredType) {
        if (!LOG.isDebugEnabled()) return;
        LOG.debug("[VirtualPatternEncoding] recipeType={} outputs={} matched multiple recipes (at least {} and {}), "
                        + "ordered outputs could not identify one candidate",
                requiredType == null ? "*" : requiredType.registryName, outputBag, first.getId(), second.getId());
    }

    private static boolean haveMultipleRecipeTypes(List<RecipeCandidate> matches) {
        String firstType = null;
        for (RecipeCandidate candidate : matches) {
            GTRecipeType recipeType = candidate.recipe.recipeType;
            String typeId = recipeType == null || recipeType.registryName == null
                    ? "" : recipeType.registryName.toString();
            if (firstType == null) {
                firstType = typeId;
            } else if (!Objects.equals(firstType, typeId)) {
                return true;
            }
        }
        return false;
    }

    private static void logAmbiguousRecipeTypes(StackBag outputBag, List<RecipeCandidate> matches) {
        if (!LOG.isDebugEnabled() || matches.isEmpty()) return;
        LOG.debug("[VirtualPatternEncoding] outputs={} matched multiple recipe types, encoding type context required",
                outputBag);
    }

    private static long detectRecipeOutputMultiplier(GTRecipe recipe, StackBag patternOutputs) {
        StackBag baseOutputs = StackBag.of(createRecipeOutputs(recipe));
        Set<AEKey> unscaledKeys = new HashSet<>();
        for (AEKey key : baseOutputs.keys()) {
            if (isRecipeCycleContainerKey(key)) unscaledKeys.add(key);
        }
        return UniformOutputMultiplier.detect(baseOutputs.amounts(), patternOutputs.amounts(), unscaledKeys);
    }

    private static long detectPartialRecipeOutputMultiplier(GTRecipe recipe, StackBag patternOutputs) {
        Map<AEKey, Long> recipeAmounts = StackBag.of(createRecipeOutputs(recipe)).amounts();
        Map<AEKey, Long> patternAmounts = patternOutputs.amounts();
        if (recipeAmounts.isEmpty() || patternAmounts.isEmpty()) return 0L;

        Map<AEKey, Long> matchingRecipeAmounts = new HashMap<>();
        Set<AEKey> unscaledKeys = new HashSet<>();
        for (AEKey key : patternAmounts.keySet()) {
            Long amount = recipeAmounts.get(key);
            if (amount == null || amount.longValue() <= 0L) return 0L;
            matchingRecipeAmounts.put(key, amount);
            if (isRecipeCycleContainerKey(key)) unscaledKeys.add(key);
        }
        return UniformOutputMultiplier.detect(matchingRecipeAmounts, patternAmounts, unscaledKeys);
    }

    private static boolean haveEquivalentRecipeInputsAndOutputs(GTRecipe first, GTRecipe second) {
        return first != null && second != null
                && StackBag.of(createRecipeInputs(first)).equals(StackBag.of(createRecipeInputs(second)))
                && StackBag.of(createRecipeOutputs(first)).equals(StackBag.of(createRecipeOutputs(second)));
    }

    private static boolean matchesRecipeInputsAtMultiplier(GTRecipe recipe, GenericStack[] patternInputs,
            StackBag inputBag, long multiplier, StackBag availableCatalystInputs,
            boolean allowOmittedNonConsumables) {
        if (matchesIndexedRecipe(recipe, patternInputs, inputBag, availableCatalystInputs,
                allowOmittedNonConsumables)) return true;
        if (multiplier <= 1L || patternInputs == null) return false;

        GenericStack[] normalized = new GenericStack[patternInputs.length];
        for (int i = 0; i < patternInputs.length; i++) {
            GenericStack input = patternInputs[i];
            if (input == null || !isRecipeCycleContainerKey(input.what())) {
                normalized[i] = input;
                continue;
            }
            if (input.amount() <= 0L || input.amount() % multiplier != 0L) return false;
            normalized[i] = new GenericStack(input.what(), input.amount() / multiplier);
        }
        return patternInputsMatchRecipe(recipe, normalized, availableCatalystInputs,
                allowOmittedNonConsumables);
    }

    private static boolean matchesOrderedRecipeOutputs(GTRecipe recipe, GenericStack[] patternOutputs,
            long multiplier) {
        List<GenericStack> base = createRecipeOutputs(recipe);
        List<GenericStack> pattern = compactStacks(patternOutputs);
        if (base.size() != pattern.size()) return false;

        for (int i = 0; i < base.size(); i++) {
            GenericStack baseStack = base.get(i);
            GenericStack patternStack = pattern.get(i);
            if (!Objects.equals(baseStack.what(), patternStack.what())) return false;
            long expected = isRecipeCycleContainerKey(baseStack.what())
                    ? baseStack.amount() : NumberUtils.saturatedMultiply(baseStack.amount(), multiplier);
            if (patternStack.amount() != expected) return false;
        }
        return true;
    }

    private static boolean isRecipeCycleContainerKey(AEKey key) {
        return key instanceof AEItemKey itemKey
                && RecipeCalculationHelper.INSTANCE.isRecipeCycleContainerItem(itemKey.getItem());
    }

    private static RecipeOutputIndexes getRecipeOutputIndexes() {
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        RecipeOutputIndexes indexes = recipeOutputIndexes;
        if (indexes != null && indexes.revision == revision) return indexes;
        synchronized (VirtualPatternEncodingHelper.class) {
            indexes = recipeOutputIndexes;
            if (indexes == null || indexes.revision != revision) {
                indexes = buildRecipeOutputIndexes(revision);
                recipeOutputIndexes = indexes;
            }
        }
        return indexes;
    }

    private static RecipeOutputIndex getAllRecipeOutputIndex() {
        return getRecipeOutputIndexes().allRecipes;
    }

    private static RecipeOutputIndex getRecipeOutputIndex() {
        return getRecipeOutputIndexes().nonConsumableRecipes;
    }

    private static RecipeOutputIndexes buildRecipeOutputIndexes(long revision) {
        Map<StackBag, List<GTRecipe>> allByOutput = new HashMap<>();
        Map<StackBag, List<GTRecipe>> nonConsumableByOutput = new HashMap<>();
        Map<Set<AEKey>, List<GTRecipe>> allByOutputShape = new HashMap<>();
        Map<Set<AEKey>, List<GTRecipe>> nonConsumableByOutputShape = new HashMap<>();
        Map<AEKey, List<GTRecipe>> allByOutputKey = new HashMap<>();
        Map<AEKey, List<GTRecipe>> nonConsumableByOutputKey = new HashMap<>();
        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) continue;
            Iterable<GTRecipe> recipes = type.getLookup().getLookup().getRecipes(true)::iterator;
            for (GTRecipe recipe : recipes) {
                if (recipe == null) continue;
                StackBag outputBag = StackBag.of(createRecipeOutputs(recipe));
                if (outputBag.isEmpty()) continue;
                allByOutput.computeIfAbsent(outputBag, key -> new ArrayList<>()).add(recipe);
                allByOutputShape.computeIfAbsent(outputBag.keys(), key -> new ArrayList<>()).add(recipe);
                for (AEKey outputKey : outputBag.keys()) {
                    allByOutputKey.computeIfAbsent(outputKey, key -> new ArrayList<>()).add(recipe);
                }
                if (hasNonConsumableInput(recipe)) {
                    nonConsumableByOutput.computeIfAbsent(outputBag, key -> new ArrayList<>()).add(recipe);
                    nonConsumableByOutputShape.computeIfAbsent(outputBag.keys(), key -> new ArrayList<>()).add(recipe);
                    for (AEKey outputKey : outputBag.keys()) {
                        nonConsumableByOutputKey.computeIfAbsent(outputKey, key -> new ArrayList<>()).add(recipe);
                    }
                }
            }
        }
        return new RecipeOutputIndexes(revision,
                new RecipeOutputIndex(allByOutput, allByOutputShape, allByOutputKey),
                new RecipeOutputIndex(nonConsumableByOutput, nonConsumableByOutputShape,
                        nonConsumableByOutputKey));
    }

    private static boolean matchesIndexedRecipe(GTRecipe recipe, GenericStack[] patternInputs, StackBag inputs,
            StackBag availableCatalystInputs, boolean allowOmittedNonConsumables) {
        return StackBag.of(createRecipeInputs(recipe)).equals(inputs)
                || StackBag.of(createVirtualInputs(recipe)).equals(inputs)
                || patternInputsMatchRecipe(recipe, patternInputs, availableCatalystInputs,
                        allowOmittedNonConsumables);
    }

    private static boolean patternInputsMatchRecipe(GTRecipe recipe, GenericStack[] patternInputs) {
        return patternInputsMatchRecipe(recipe, patternInputs, StackBag.EMPTY);
    }

    private static boolean patternInputsMatchRecipe(GTRecipe recipe, GenericStack[] patternInputs,
            StackBag availableCatalystInputs) {
        return patternInputsMatchRecipe(recipe, patternInputs, availableCatalystInputs, false);
    }

    private static boolean patternInputsMatchRecipe(GTRecipe recipe, GenericStack[] patternInputs,
            StackBag availableCatalystInputs, boolean allowOmittedNonConsumables) {
        List<GenericStack> inputs = compactStacks(patternInputs);
        int requiredCount = countRequiredPatternInputs(
                recipe, availableCatalystInputs, allowOmittedNonConsumables);
        int totalCount = countRecipeInputs(recipe);
        if (inputs.size() < requiredCount || inputs.size() > totalCount) return false;

        boolean[] used = new boolean[inputs.size()];
        if (!matchItemContents(recipe.getInputContents(ItemRecipeCapability.CAP), inputs, used,
                availableCatalystInputs, allowOmittedNonConsumables)) return false;
        if (!matchFluidContents(recipe.getInputContents(FluidRecipeCapability.CAP), inputs, used,
                availableCatalystInputs, allowOmittedNonConsumables)) return false;
        for (boolean matched : used) {
            if (!matched) return false;
        }
        return true;
    }

    private static int countRequiredPatternInputs(GTRecipe recipe, StackBag availableCatalystInputs,
            boolean allowOmittedNonConsumables) {
        int count = 0;
        List<Content> itemContents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (itemContents != null) {
            for (Content content : itemContents) {
                Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null && !ingredient.isEmpty()
                        && !canOmitItemPatternInput(
                                content, availableCatalystInputs, allowOmittedNonConsumables)) {
                    count++;
                }
            }
        }
        List<Content> fluidContents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (fluidContents != null) {
            for (Content content : fluidContents) {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null && !ingredient.isEmpty()
                        && !canOmitFluidPatternInput(
                                content, availableCatalystInputs, allowOmittedNonConsumables)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<GenericStack> compactStacks(GenericStack[] stacks) {
        List<GenericStack> result = new ArrayList<>();
        if (stacks == null) return result;
        for (GenericStack stack : stacks) {
            if (stack != null && stack.what() != null && stack.amount() > 0) {
                result.add(stack);
            }
        }
        return result;
    }

    private static int countRecipeInputs(GTRecipe recipe) {
        int count = 0;
        List<Content> itemContents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (itemContents != null) {
            for (Content content : itemContents) {
                if (!ItemRecipeCapability.CAP.of(content.getContent()).isEmpty()) count++;
            }
        }
        List<Content> fluidContents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (fluidContents != null) {
            for (Content content : fluidContents) {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null && !ingredient.isEmpty()) count++;
            }
        }
        return count;
    }

    private static boolean matchItemContents(List<Content> contents, List<GenericStack> inputs, boolean[] used,
            StackBag availableCatalystInputs, boolean allowOmittedNonConsumables) {
        if (contents == null || contents.isEmpty()) return true;
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            long amount = getItemAmount(content, firstItemStack(content));
            boolean optional = isOmittablePatternCatalyst(content);
            boolean matched = false;
            for (int i = 0; i < inputs.size(); i++) {
                GenericStack input = inputs.get(i);
                if (used[i] || !(input.what() instanceof AEItemKey key)) continue;
                if (itemInputMatchesIngredient(input, key, ingredient, amount, optional)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched && !canOmitItemPatternInput(
                    content, availableCatalystInputs, allowOmittedNonConsumables)) return false;
        }
        return true;
    }

    private static boolean itemInputMatchesIngredient(GenericStack input, AEItemKey key,
            Ingredient ingredient, long expectedAmount, boolean optional) {
        ItemStack stack = key.toStack();
        if (input.amount() == expectedAmount && ingredient.test(stack)) return true;
        if (!optional || input.amount() != 1L || !VirtualItemProviderHelper.isProviderItem(stack)) return false;
        ItemStack target = VirtualItemProviderHelper.getTarget(stack);
        if (target.isEmpty() || !ingredient.test(target)) return false;
        long encodedAmount = Math.max(1L, target.getCount());
        return encodedAmount == Math.min((long) Integer.MAX_VALUE, expectedAmount);
    }

    private static boolean matchFluidContents(List<Content> contents, List<GenericStack> inputs, boolean[] used,
            StackBag availableCatalystInputs, boolean allowOmittedNonConsumables) {
        if (contents == null || contents.isEmpty()) return true;
        for (Content content : contents) {
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
            long amount = sample == null ? 0 : sample.getAmount();
            boolean optional = isNonConsumable(content);
            boolean matched = false;
            for (int i = 0; i < inputs.size(); i++) {
                GenericStack input = inputs.get(i);
                if (used[i] || !(input.what() instanceof AEFluidKey key)
                        || input.amount() != amount && !(optional && input.amount() == VIRTUAL_FLUID_MARKER_AMOUNT)) {
                    continue;
                }
                Fluid fluid = (Fluid) key.getPrimaryKey();
                CompoundTag tag = key.toTag();
                com.lowdragmc.lowdraglib.side.fluid.FluidStack stack = com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                        fluid,
                        amount,
                        tag.contains("tag", 10) ? tag.getCompound("tag") : null);
                if (ingredient.test(stack)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched && !canOmitFluidPatternInput(
                    content, availableCatalystInputs, allowOmittedNonConsumables)) return false;
        }
        return true;
    }

    private static boolean canOmitItemPatternInput(Content content, StackBag availableCatalystInputs,
            boolean allowOmittedNonConsumables) {
        if (allowOmittedNonConsumables && isNonConsumable(content)) return true;
        return isOmittablePatternCatalyst(content)
                && availableInputMatchesItemCatalyst(content, availableCatalystInputs);
    }

    private static boolean canOmitFluidPatternInput(Content content, StackBag availableCatalystInputs,
            boolean allowOmittedNonConsumables) {
        if (allowOmittedNonConsumables && isNonConsumable(content)) return true;
        return isNonConsumable(content)
                && availableInputMatchesFluidCatalyst(content, availableCatalystInputs);
    }

    private static boolean availableInputMatchesItemCatalyst(Content content, StackBag availableCatalystInputs) {
        if (!isOmittablePatternCatalyst(content) || availableCatalystInputs == null
                || availableCatalystInputs.isEmpty()) return false;
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        ItemStack sample = firstItemStack(content);
        long expectedAmount = getItemAmount(content, sample);
        for (Map.Entry<AEKey, Long> entry : availableCatalystInputs.amounts().entrySet()) {
            if (entry.getValue() < expectedAmount || !(entry.getKey() instanceof AEItemKey key)) continue;
            if (ingredient.test(key.toStack())) return true;
        }
        return false;
    }

    private static boolean availableInputMatchesFluidCatalyst(Content content, StackBag availableCatalystInputs) {
        if (!isNonConsumable(content) || availableCatalystInputs == null
                || availableCatalystInputs.isEmpty()) return false;
        FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
        com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
        long expectedAmount = sample == null ? 0L : sample.getAmount();
        for (Map.Entry<AEKey, Long> entry : availableCatalystInputs.amounts().entrySet()) {
            if (entry.getValue() < expectedAmount || !(entry.getKey() instanceof AEFluidKey key)) continue;
            CompoundTag keyTag = key.toTag();
            com.lowdragmc.lowdraglib.side.fluid.FluidStack stack =
                    com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                            key.getFluid(), expectedAmount,
                            keyTag.contains("tag", 10) ? keyTag.getCompound("tag") : null);
            if (ingredient.test(stack)) return true;
        }
        return false;
    }

    private static boolean isOmittablePatternCatalyst(Content content) {
        if (!isNonConsumable(content)) return false;
        ItemStack stack = firstItemStack(content);
        return !stack.isEmpty() && !IntCircuitBehaviour.isIntegratedCircuit(stack);
    }

    private static boolean hasNonConsumableInput(GTRecipe recipe) {
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents != null) {
            for (Content content : contents) {
                if (isNonConsumable(content) && !firstItemStack(content).isEmpty()) {
                    return true;
                }
            }
        }
        List<Content> fluidContents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (fluidContents != null) {
            for (Content content : fluidContents) {
                if (isNonConsumable(content) && firstFluidStack(content) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GenericStack getNonConsumableFluidTarget(GTRecipe recipe, GenericStack stack) {
        if (recipe == null || stack == null || !(stack.what() instanceof AEFluidKey key)) return null;
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return null;
        for (Content content : contents) {
            if (!isNonConsumable(content)) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack fluidStack = firstFluidStack(content);
            if (fluidStack == null || fluidStack.isEmpty()) continue;
            AEFluidKey contentKey = fluidKeyOf(fluidStack);
            if (contentKey.equals(key)) {
                return new GenericStack(contentKey, Math.max(1L, fluidStack.getAmount()));
            }
        }
        return null;
    }

    private static GenericStack[] rewriteInputsPreservingSelections(GenericStack[] inputs, GTRecipe recipe) {
        List<GenericStack> original = compactStacks(inputs);
        List<GenericStack> rewritten = new ArrayList<>(original);
        boolean[] used = new boolean[original.size()];
        if (!rewriteItemInputsPreservingSelections(
                recipe.getInputContents(ItemRecipeCapability.CAP), original, rewritten, used)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[VirtualPatternEncoding] recipe={} item ingredient match failed, wrap skipped", recipe.getId());
            }
            return inputs;
        }
        if (!rewriteFluidInputsPreservingSelections(
                recipe.getInputContents(FluidRecipeCapability.CAP), original, rewritten, used)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[VirtualPatternEncoding] recipe={} fluid ingredient match failed, wrap skipped", recipe.getId());
            }
            return inputs;
        }
        for (boolean matched : used) {
            if (!matched) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] recipe={} has unmatched original pattern input, wrap skipped",
                            recipe.getId());
                }
                return inputs;
            }
        }
        return rewritten.toArray(new GenericStack[0]);
    }

    private static boolean rewriteItemInputsPreservingSelections(List<Content> contents,
            List<GenericStack> original, List<GenericStack> rewritten, boolean[] used) {
        if (contents == null || contents.isEmpty()) return true;
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            long amount = getItemAmount(content, firstItemStack(content));
            int matchedIndex = -1;
            ItemStack selected = ItemStack.EMPTY;
            for (int i = 0; i < original.size(); i++) {
                GenericStack input = original.get(i);
                if (used[i] || !(input.what() instanceof AEItemKey key)) continue;
                ItemStack stack = key.toStack();
                if (itemInputMatchesIngredient(
                        input, key, ingredient, amount, isOmittablePatternCatalyst(content))) {
                    matchedIndex = i;
                    selected = stack;
                    break;
                }
            }
            if (matchedIndex < 0 && !isNonConsumable(content)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] no pattern slot matches recipe item ingredient (amount={})", amount);
                }
                return false;
            }
            if (matchedIndex < 0) {
                ItemStack sample = firstItemStack(content);
                GenericStack missingInput = createVirtualItemInput(sample, amount);
                if (missingInput == null) return false;
                rewritten.add(missingInput);
                continue;
            }
            used[matchedIndex] = true;
            if (!isNonConsumable(content)) continue;
            if (VirtualItemProviderHelper.isProviderItem(selected)) continue;

            selected.setCount((int) Math.min(Integer.MAX_VALUE, amount));
            GenericStack virtualInput = createVirtualItemInput(selected, amount);
            if (virtualInput == null) return false;
            rewritten.set(matchedIndex, virtualInput);
        }
        return true;
    }

    private static GenericStack createVirtualItemInput(ItemStack sample, long amount) {
        if (sample == null || sample.isEmpty()) return null;
        sample = sample.copy();
        sample.setCount((int) Math.min(Integer.MAX_VALUE, amount));
        if (IntCircuitBehaviour.isIntegratedCircuit(sample)
                || VirtualItemProviderHelper.isAutoWrapExcluded(sample)) {
            return new GenericStack(AEItemKey.of(sample), amount);
        }
        ItemStack provider = VirtualItemProviderHelper.createBoundProvider(sample.copy());
        if (provider.isEmpty()) {
            LOG.warn("[VirtualPatternEncoding] item={} createBoundProvider returned EMPTY", sample.getItem());
            return null;
        }
        return new GenericStack(AEItemKey.of(provider), 1);
    }

    private static boolean rewriteFluidInputsPreservingSelections(List<Content> contents,
            List<GenericStack> original, List<GenericStack> rewritten, boolean[] used) {
        if (contents == null || contents.isEmpty()) return true;
        for (Content content : contents) {
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
            if (sample == null) return false;
            long amount = sample.getAmount();
            int matchedIndex = -1;
            AEFluidKey selected = null;
            for (int i = 0; i < original.size(); i++) {
                GenericStack input = original.get(i);
                if (used[i] || !(input.what() instanceof AEFluidKey key)
                        || input.amount() != amount && !(isNonConsumable(content)
                        && input.amount() == VIRTUAL_FLUID_MARKER_AMOUNT)) continue;
                Fluid fluid = (Fluid) key.getPrimaryKey();
                CompoundTag tag = key.toTag();
                com.lowdragmc.lowdraglib.side.fluid.FluidStack stack =
                        com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                                fluid, amount, tag.contains("tag", 10) ? tag.getCompound("tag") : null);
                if (ingredient.test(stack)) {
                    matchedIndex = i;
                    selected = key;
                    break;
                }
            }
            if (matchedIndex < 0 && !isNonConsumable(content)) return false;
            if (matchedIndex < 0) {
                rewritten.add(new GenericStack(fluidKeyOf(sample), VIRTUAL_FLUID_MARKER_AMOUNT));
                continue;
            }
            used[matchedIndex] = true;
            if (isNonConsumable(content)) {
                rewritten.set(matchedIndex, new GenericStack(selected, VIRTUAL_FLUID_MARKER_AMOUNT));
            }
        }
        return true;
    }

    private static List<GenericStack> createVirtualInputs(GTRecipe recipe) {
        List<GenericStack> stacks = new ArrayList<>();
        List<Content> itemInputs = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (itemInputs != null) {
            for (Content content : itemInputs) {
                ItemStack stack = firstItemStack(content);
                if (stack.isEmpty()) continue;

                if (isNonConsumable(content)) {
                    if (VirtualItemProviderHelper.isAutoWrapExcluded(stack)) {
                        stacks.add(new GenericStack(AEItemKey.of(stack), getItemAmount(content, stack)));
                        continue;
                    }
                    ItemStack provider = VirtualItemProviderHelper.createBoundProvider(stack);
                    if (!provider.isEmpty()) {
                        stacks.add(new GenericStack(AEItemKey.of(provider), 1));
                    }
                } else {
                    stacks.add(new GenericStack(AEItemKey.of(stack), getItemAmount(content, stack)));
                }
            }
        }

        appendFluidStacks(recipe.getInputContents(FluidRecipeCapability.CAP), stacks, true);
        return stacks;
    }

    private static List<GenericStack> createRecipeInputs(GTRecipe recipe) {
        List<GenericStack> stacks = new ArrayList<>();
        appendItemStacks(recipe.getInputContents(ItemRecipeCapability.CAP), stacks);
        appendFluidStacks(recipe.getInputContents(FluidRecipeCapability.CAP), stacks, false);
        return stacks;
    }

    private static List<GenericStack> createRecipeOutputs(GTRecipe recipe) {
        List<GenericStack> stacks = new ArrayList<>();
        appendItemStacks(recipe.getOutputContents(ItemRecipeCapability.CAP), stacks);
        appendFluidStacks(recipe.getOutputContents(FluidRecipeCapability.CAP), stacks, false);
        return stacks;
    }

    private static void appendItemStacks(List<Content> contents, List<GenericStack> stacks) {
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            ItemStack stack = firstItemStack(content);
            if (!stack.isEmpty()) {
                stacks.add(new GenericStack(AEItemKey.of(stack), getItemAmount(content, stack)));
            }
        }
    }

    private static void appendFluidStacks(List<Content> contents, List<GenericStack> stacks, boolean virtualizeNonConsumable) {
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack stack = firstFluidStack(content);
            if (stack == null || stack.isEmpty()) continue;
            AEFluidKey key = fluidKeyOf(stack);
            long amount = virtualizeNonConsumable && isNonConsumable(content)
                    ? VIRTUAL_FLUID_MARKER_AMOUNT
                    : Math.max(1L, stack.getAmount());
            stacks.add(new GenericStack(key, amount));
        }
    }

    private static boolean isNonConsumable(Content content) {
        return content != null && content.chance <= 0;
    }

    private static com.lowdragmc.lowdraglib.side.fluid.FluidStack firstFluidStack(Content content) {
        if (content == null || content.getContent() == null) return null;
        FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null || ingredient.isEmpty()) return null;
        com.lowdragmc.lowdraglib.side.fluid.FluidStack[] fluidStacks = ingredient.getStacks();
        if (fluidStacks == null || fluidStacks.length == 0 || fluidStacks[0].isEmpty()) return null;
        return fluidStacks[0];
    }

    private static AEFluidKey fluidKeyOf(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && !tag.isEmpty()
                ? AEFluidKey.of(stack.getFluid(), tag)
                : AEFluidKey.of(stack.getFluid());
    }

    private static ItemStack firstItemStack(Content content) {
        if (content == null || content.getContent() == null) return ItemStack.EMPTY;
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stacks[0].copy();
        result.setCount((int) Math.min(Integer.MAX_VALUE, getItemAmount(content, result)));
        return result;
    }

    private static long getItemAmount(Content content, ItemStack fallback) {
        Object raw = content.getContent();
        if (raw instanceof LongIngredient ingredient) {
            return Math.max(1L, ingredient.getActualAmount());
        }
        if (raw instanceof SizedIngredient ingredient) {
            return Math.max(1L, ingredient.getAmount());
        }
        return Math.max(1L, fallback.getCount());
    }

    private VirtualPatternEncodingHelper() {}

    private static final class PatternAnalysis {
        private final Map<Entry, GenericStack> targets;
        private GTRecipe matchingRecipe;
        private boolean matchingRecipeResolved;

        private PatternAnalysis(Map<Entry, GenericStack> targets, GTRecipe matchingRecipe,
                boolean matchingRecipeResolved) {
            this.targets = targets;
            this.matchingRecipe = matchingRecipe;
            this.matchingRecipeResolved = matchingRecipeResolved;
        }

        private boolean containsVirtualInput() {
            return !targets.isEmpty();
        }

        private GenericStack targetFor(GenericStack stack) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) return null;
            return targets.get(new Entry(stack.what(), stack.amount()));
        }

        private synchronized GTRecipe matchingRecipe(GenericStack[] inputs, GenericStack[] outputs) {
            if (!matchingRecipeResolved) {
                matchingRecipe = findMatchingRecipe(inputs, outputs);
                matchingRecipeResolved = true;
            }
            return matchingRecipe;
        }
    }

    private static final class RecipeOutputIndexes {
        private final long revision;
        private final RecipeOutputIndex allRecipes;
        private final RecipeOutputIndex nonConsumableRecipes;

        private RecipeOutputIndexes(long revision, RecipeOutputIndex allRecipes,
                RecipeOutputIndex nonConsumableRecipes) {
            this.revision = revision;
            this.allRecipes = allRecipes;
            this.nonConsumableRecipes = nonConsumableRecipes;
        }
    }

    private static final class RecipeOutputIndex {
        private final Map<StackBag, List<GTRecipe>> byOutput;
        private final Map<Set<AEKey>, List<GTRecipe>> byOutputShape;
        private final Map<AEKey, List<GTRecipe>> byOutputKey;

        private RecipeOutputIndex(Map<StackBag, List<GTRecipe>> byOutput,
                Map<Set<AEKey>, List<GTRecipe>> byOutputShape,
                Map<AEKey, List<GTRecipe>> byOutputKey) {
            this.byOutput = byOutput;
            this.byOutputShape = byOutputShape;
            this.byOutputKey = byOutputKey;
        }

        private List<GTRecipe> candidates(StackBag outputBag) {
            List<GTRecipe> candidates = byOutput.get(outputBag);
            return candidates == null ? List.of() : candidates;
        }

        private List<GTRecipe> scaledCandidates(StackBag outputBag) {
            List<GTRecipe> candidates = byOutputShape.get(outputBag.keys());
            return candidates == null ? List.of() : candidates;
        }

        private List<GTRecipe> partialCandidates(StackBag outputBag) {
            LinkedHashSet<GTRecipe> candidates = new LinkedHashSet<>();
            for (AEKey outputKey : outputBag.keys()) {
                List<GTRecipe> keyed = byOutputKey.get(outputKey);
                if (keyed != null) candidates.addAll(keyed);
            }
            return candidates.isEmpty() ? List.of() : new ArrayList<>(candidates);
        }
    }

    private record RecipeCandidate(GTRecipe recipe, long multiplier) {}

    private static final class RecipeSelection {
        private static final RecipeSelection NONE = new RecipeSelection(null, false);
        private static final RecipeSelection AMBIGUOUS = new RecipeSelection(null, true);

        private final GTRecipe recipe;
        private final boolean ambiguous;

        private RecipeSelection(GTRecipe recipe, boolean ambiguous) {
            this.recipe = recipe;
            this.ambiguous = ambiguous;
        }
    }

    private static final class PatternKey {
        private final StackBag inputs;
        private final StackBag outputs;
        private final long revision;

        private PatternKey(StackBag inputs, StackBag outputs, long revision) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.revision = revision;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof PatternKey other)) return false;
            return revision == other.revision
                    && Objects.equals(inputs, other.inputs)
                    && Objects.equals(outputs, other.outputs);
        }

        @Override
        public int hashCode() {
            int result = inputs.hashCode();
            result = 31 * result + outputs.hashCode();
            return 31 * result + Long.hashCode(revision);
        }
    }

    private static class PlanningInput implements IPatternDetails.IInput {
        protected final GenericStack[] template;
        private final long multiplier;

        private PlanningInput(AEKey key, long multiplier) {
            this.template = new GenericStack[] { new GenericStack(key, 1) };
            this.multiplier = multiplier;
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return template;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return input != null && input.matches(template[0]);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private static final class PresenceInput extends PlanningInput {

        private PresenceInput(AEKey key, long multiplier) {
            super(key, multiplier);
        }

        @Override
        public AEKey getRemainingKey(AEKey ignored) {
            return template[0].what();
        }
    }

    private static final class StackBag {
        private static final StackBag EMPTY = new StackBag(Map.of());
        private final Map<Entry, Integer> entries;

        private StackBag(Map<Entry, Integer> entries) {
            this.entries = entries;
        }

        static StackBag of(GenericStack[] stacks) {
            Map<Entry, Integer> entries = new HashMap<>();
            if (stacks != null) {
                for (GenericStack stack : stacks) {
                    add(entries, stack);
                }
            }
            return new StackBag(entries);
        }

        static StackBag of(List<GenericStack> stacks) {
            Map<Entry, Integer> entries = new HashMap<>();
            if (stacks != null) {
                for (GenericStack stack : stacks) {
                    add(entries, stack);
                }
            }
            return new StackBag(entries);
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        Set<AEKey> keys() {
            Set<AEKey> keys = new HashSet<>();
            for (Entry entry : entries.keySet()) {
                keys.add(entry.key);
            }
            return Set.copyOf(keys);
        }

        Map<AEKey, Long> amounts() {
            Map<AEKey, Long> amounts = new HashMap<>();
            for (Map.Entry<Entry, Integer> entry : entries.entrySet()) {
                long total = NumberUtils.saturatedMultiply(entry.getKey().amount, entry.getValue().longValue());
                amounts.merge(entry.getKey().key, total, NumberUtils::saturatedAdd);
            }
            return amounts;
        }

        private static void add(Map<Entry, Integer> entries, GenericStack stack) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) return;
            Entry entry = new Entry(stack.what(), stack.amount());
            entries.put(entry, entries.getOrDefault(entry, 0) + 1);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof StackBag other)) return false;
            return entries.equals(other.entries);
        }

        @Override
        public int hashCode() {
            return entries.hashCode();
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }

    private static final class Entry {
        private final AEKey key;
        private final long amount;

        private Entry(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Entry other)) return false;
            return amount == other.amount && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return 31 * key.hashCode() + Long.hashCode(amount);
        }
    }
}
