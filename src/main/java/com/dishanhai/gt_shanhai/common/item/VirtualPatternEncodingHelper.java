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

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;
import com.dishanhai.gt_shanhai.common.machine.output.ForgeOutputMultiplier;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Rewrites GT non-consumable item and fluid inputs to self-describing virtual providers.
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
    private static volatile TypeScopedRecipeCache typeScopedRecipeCache;
    private static volatile RecipeOutputSnapshotCache recipeOutputSnapshotCache;

    public static GenericStack[] rewriteInputsForVirtualProviders(GenericStack[] inputs, GenericStack[] outputs) {
        if (inputs == null || outputs == null) {
            return inputs;
        }
        if (!PatternEncodePreferences.writeVirtualProvider()) {
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
            GTRecipe selectedRecipe = PatternRecipeTypeHelper.currentSelectedEncodingRecipe();
            GenericStack[] anchored = selectedRecipe == null ? null
                    : rewriteInputsAnchoredToKnownRecipe(inputs, outputs, selectedRecipe);
            if (anchored != null) {
                result = anchored;
                autoDiagnostic = "recipe " + selectedRecipe.getId() + " anchored by id, wrapped";
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] recipe={} anchored by id, inputs rewritten from that recipe",
                            selectedRecipe.getId());
                }
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
        }

        Set<Integer> forcedSlots = override == null ? null : override.forcedSlots;
        int manuallyWrapped = 0;
        if (forcedSlots != null && !forcedSlots.isEmpty()) {
            for (Integer slot : forcedSlots) {
                if (slot == null || slot < 0 || slot >= inputs.length) continue;
                GenericStack raw = inputs[slot];
                if (raw == null || raw.amount() <= 0L) continue;
                if (raw.what() instanceof AEItemKey key) {
                    ItemStack rawStack = key.toStack();
                    if (VirtualItemProviderHelper.isProviderItem(rawStack)
                            || VirtualItemProviderHelper.isAutoWrapExcluded(rawStack)) continue;
                }
                ItemStack provider = VirtualItemProviderHelper.createBoundProvider(raw);
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

    public static ItemStack encodeProcessingPatternRecoveringVirtualInputs(GenericStack[] inputs, GenericStack[] outputs) {
        if (inputs == null || outputs == null || outputs.length == 0 || outputs[0] == null) {
            return ItemStack.EMPTY;
        }
        GenericStack[] rewritten = rewriteInputsForVirtualProviders(inputs, outputs);
        if (!hasAnyInput(rewritten)) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(rewritten, outputs);
            return encoded == null ? ItemStack.EMPTY : encoded;
        } catch (IllegalArgumentException ignored) {
            return ItemStack.EMPTY;
        } catch (NullPointerException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean hasAnyInput(GenericStack[] inputs) {
        if (inputs == null) return false;
        for (GenericStack input : inputs) {
            if (input != null && input.what() != null && input.amount() > 0L) {
                return true;
            }
        }
        return false;
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

    public static boolean containsPresenceInput(IPatternDetails.IInput[] inputs) {
        if (inputs == null) return false;
        for (int i = 0; i < inputs.length; i++) {
            if (isPresenceInput(inputs[i])) return true;
        }
        return false;
    }

    /** 在场输入的目标键。推送和发配用它跳过按批次放大的实料抽取。 */
    public static Set<AEKey> presenceKeys(IPatternDetails.IInput[] inputs) {
        Set<AEKey> keys = new HashSet<>();
        if (inputs == null) return keys;
        for (int i = 0; i < inputs.length; i++) {
            if (!isPresenceInput(inputs[i])) continue;
            GenericStack[] possible = inputs[i].getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null || possible[0].what() == null) continue;
            keys.add(possible[0].what());
        }
        return keys;
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

    /**
     * 反查配方里 chance &lt;= 0 的输入。在场数量取配方需求与样板编码量的较小值，
     * 这样被下单次数放大的编码量不会变成要检查的份数。
     */
    private static Map<AEKey, Long> collectRecipeNonConsumableTargets(GenericStack[] inputs,
            GenericStack[] outputs, String recipeTypeId) {
        Map<AEKey, Long> targets = new LinkedHashMap<>();
        if (inputs == null || outputs == null) return targets;
        GTRecipe recipe;
        try {
            recipe = findMatchingRecipeForPattern(inputs, outputs, recipeTypeId);
        } catch (RuntimeException exception) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[VirtualPatternEncoding] byproduct non-consumable lookup failed", exception);
            }
            return targets;
        }
        if (recipe == null) return targets;
        InputMatchPlan plan = findInputMatchPlan(recipe, inputs, StackBag.EMPTY, true);
        List<GenericStack> original = compactStacks(inputs);
        if (plan != null) {
            addMatchedNonConsumableTargets(recipe.getInputContents(ItemRecipeCapability.CAP), original, plan, targets, true);
            addMatchedNonConsumableTargets(recipe.getInputContents(FluidRecipeCapability.CAP), original, plan, targets, false);
            return targets;
        }
        addIdentityNonConsumableTargets(recipe.getInputContents(ItemRecipeCapability.CAP), original, targets, true);
        addIdentityNonConsumableTargets(recipe.getInputContents(FluidRecipeCapability.CAP), original, targets, false);
        return targets;
    }

    private static void addMatchedNonConsumableTargets(List<Content> contents, List<GenericStack> inputs,
            InputMatchPlan plan, Map<AEKey, Long> targets, boolean items) {
        if (contents == null || contents.isEmpty() || plan == null) return;
        for (int i = 0; i < contents.size(); i++) {
            Content content = contents.get(i);
            if (!isNonConsumable(content)) continue;
            Integer index = plan.indexFor(content);
            if (index == null || index < 0 || index >= inputs.size()) continue;
            putNonConsumableTarget(inputs.get(index), content, targets, items);
        }
    }

    private static void addIdentityNonConsumableTargets(List<Content> contents, List<GenericStack> inputs,
            Map<AEKey, Long> targets, boolean items) {
        if (contents == null || contents.isEmpty()) return;
        for (int i = 0; i < inputs.size(); i++) {
            GenericStack input = inputs.get(i);
            if (input == null || input.what() == null) continue;
            if (alreadyVirtualInput(input)) continue;
            Content matched = null;
            boolean consumed = false;
            for (int c = 0; c < contents.size(); c++) {
                Content content = contents.get(c);
                if (!inputMatchesContent(input, content, items)) continue;
                if (isNonConsumable(content)) {
                    if (matched == null) matched = content;
                } else {
                    consumed = true;
                }
            }
            if (matched != null && !consumed) {
                putNonConsumableTarget(input, matched, targets, items);
            }
        }
    }

    private static boolean inputMatchesContent(GenericStack input, Content content, boolean items) {
        if (input == null || content == null) return false;
        if (items) {
            if (!(input.what() instanceof AEItemKey key)) return false;
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            return ingredient != null && !ingredient.isEmpty() && ingredient.test(key.toStack());
        }
        if (!(input.what() instanceof AEFluidKey key)) return false;
        com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
        if (sample == null || sample.isEmpty()) return false;
        AEFluidKey sampleKey = fluidKeyOf(sample);
        return key.equals(sampleKey);
    }

    private static void putNonConsumableTarget(GenericStack input, Content content,
            Map<AEKey, Long> targets, boolean items) {
        if (input == null || input.what() == null || alreadyVirtualInput(input)) return;
        long recipeAmount = 1L;
        if (items) {
            recipeAmount = Math.max(1L, getItemAmount(content, firstItemStack(content)));
        } else {
            com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
            if (sample != null && !sample.isEmpty()) recipeAmount = Math.max(1L, sample.getAmount());
        }
        long encoded = Math.max(1L, input.amount());
        long presence = Math.min(recipeAmount, encoded);
        Long old = targets.get(input.what());
        targets.put(input.what(), old == null ? presence : Math.max(old, presence));
    }

    private static boolean alreadyVirtualInput(GenericStack input) {
        return getVirtualProviderTarget(input) != null || getIntegratedCircuitTarget(input) != null;
    }

    /**
     * 副产物下单保留全部产出，可以反查到不消耗输入。
     * 这些输入只检查一份在场，不按合成次数放大。
     */
    public static IPatternDetails.IInput[] createByproductOrderPlanningInputs(GenericStack[] sparseInputs,
            GenericStack[] sparseOutputs, String recipeTypeId) {
        Map<AEKey, Long> once = collectRecipeNonConsumableTargets(sparseInputs, sparseOutputs, recipeTypeId);
        IPatternDetails.IInput[] base = createPlanningInputs(sparseInputs, sparseOutputs);
        if (once.isEmpty()) return base;
        List<IPatternDetails.IInput> rebuilt = new ArrayList<>();
        for (int i = 0; i < base.length; i++) {
            IPatternDetails.IInput input = base[i];
            if (input == null || isPresenceInput(input)) {
                if (input != null) rebuilt.add(input);
                continue;
            }
            GenericStack[] possible = input.getPossibleInputs();
            AEKey key = possible == null || possible.length == 0 || possible[0] == null ? null : possible[0].what();
            Long presence = key == null ? null : once.get(key);
            if (presence == null) {
                rebuilt.add(input);
                continue;
            }
            long consumed = input.getMultiplier() - presence;
            if (consumed > 0L) {
                rebuilt.add(new PlanningInput(key, consumed));
            }
            rebuilt.add(new PresenceInput(key, presence));
        }
        return rebuilt.toArray(new IPatternDetails.IInput[0]);
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
        pushSparseInputsIncludingVirtual(sparseInputs, null, inputHolder, inputSink, null, null);
    }

    public static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, GenericStack[] sparseOutputs,
            KeyCounter[] inputHolder, IPatternDetails.PatternInputSink inputSink) {
        pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink, null, null);
    }

    public static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, GenericStack[] sparseOutputs,
            KeyCounter[] inputHolder, IPatternDetails.PatternInputSink inputSink, Set<AEKey> presenceKeys) {
        pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink, null, presenceKeys);
    }

    private static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, GenericStack[] sparseOutputs, KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink, BiConsumer<AEKey, Long> virtualTargetSink) {
        pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink, virtualTargetSink, null);
    }

    private static void pushSparseInputsIncludingVirtual(GenericStack[] sparseInputs, GenericStack[] sparseOutputs, KeyCounter[] inputHolder,
            IPatternDetails.PatternInputSink inputSink, BiConsumer<AEKey, Long> virtualTargetSink,
            Set<AEKey> presenceKeys) {
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
        long patternMultiplier = countPatternMultiplier(allInputs, sparseInputs, analysis, presenceKeys);
        for (GenericStack input : sparseInputs) {
            if (input == null || input.what() == null || input.amount() <= 0) continue;
            AEKey key = input.what();
            long amount = input.amount();
            GenericStack virtualTarget = analysis.targetFor(input);
            if (virtualTarget != null || (presenceKeys != null && presenceKeys.contains(key))) {
                if (virtualTargetSink != null) {
                    AEKey target = virtualTarget == null ? key : virtualTarget.what();
                    long once = virtualTarget == null ? Math.max(1L, amount) : Math.max(1L, virtualTarget.amount());
                    virtualTargetSink.accept(target, once);
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

    private static long countPatternMultiplier(KeyCounter inputs, GenericStack[] sparseInputs, PatternAnalysis analysis,
            Set<AEKey> presenceKeys) {
        if (inputs == null || sparseInputs == null) return 1L;
        long multiplier = Long.MAX_VALUE;
        for (GenericStack input : sparseInputs) {
            if (input == null || input.what() == null || input.amount() <= 0 || analysis.targetFor(input) != null) continue;
            if (presenceKeys != null && presenceKeys.contains(input.what())) continue;
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
        Set<AEKey> presence = presenceKeys(details.getInputs());
        if (presence.isEmpty() && !containsVirtualInput(sparseInputs, sparseOutputs)) {
            return false;
        }
        pushSparseInputsIncludingVirtual(sparseInputs, sparseOutputs, inputHolder, inputSink, virtualTargetSink, presence);
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

    public static long detectPatternOutputMultiplier(IPatternDetails pattern, String recipeTypeId) {
        if (!(pattern instanceof AEProcessingPattern processingPattern)) return 0L;
        GenericStack[] inputs = processingPattern.getSparseInputs();
        GenericStack[] outputs = processingPattern.getSparseOutputs();
        GTRecipe recipe = findMatchingRecipeForPattern(inputs, outputs, recipeTypeId);
        if (recipe == null) return 0L;
        long multiplier = detectRecipeOutputMultiplier(recipe, StackBag.of(outputs));
        return multiplier <= 0L ? 0L : multiplier;
    }

    public static boolean productOutputsReflectMultiplier(IPatternDetails pattern, long multiplier) {
        if (multiplier <= 1L) return true;
        GenericStack[] outputs = pattern instanceof AEProcessingPattern processing
                ? processing.getSparseOutputs() : pattern == null ? null : pattern.getOutputs();
        if (outputs == null) return false;
        boolean sawProduct = false;
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0L) continue;
            if (isRecipeCycleContainerKey(output.what())) continue;
            sawProduct = true;
            if (output.amount() % multiplier != 0L) return false;
        }
        return sawProduct;
    }

    public static IPatternDetails rewritePatternOutputMultiplier(IPatternDetails pattern, Level level,
            String recipeTypeId, long requestedMultiplier) {
        return rewritePatternOutputMultiplier(pattern, level, recipeTypeId, requestedMultiplier, 0L);
    }

    /**
     * @param forgeMultiplier 伪神自身对该配方类型的量化倍率（{@code ForgeOutputMultiplier.quantize}）；0 表示没有伪神来源。
     *                        有伪神来源时按原版 {@code ForgeOfTheAntichrist.copyAndModifyRecipe} 的口径改写：
     *                        容器输入只按伪神倍率放大（枢纽/线程/引力波只放大产出），容器身份取配方内容的
     *                        {@code recipe_cycle_container} 槽名，三条 stellar_forge 配方追加 {@code (倍率-1)} 个空容器。
     */
    public static IPatternDetails rewritePatternOutputMultiplier(IPatternDetails pattern, Level level,
            String recipeTypeId, long requestedMultiplier, long forgeMultiplier) {
        if (!(pattern instanceof AEProcessingPattern processingPattern) || level == null) return pattern;
        long targetMultiplier = Math.max(1L, requestedMultiplier);
        if (targetMultiplier <= 1L) return pattern;
        if (productOutputsReflectMultiplier(processingPattern, targetMultiplier)) return processingPattern;
        boolean forgeContext = forgeMultiplier > 0L;
        long containerMultiplier = forgeContext ? forgeMultiplier : targetMultiplier;

        GenericStack[] inputs = processingPattern.getSparseInputs();
        GenericStack[] outputs = processingPattern.getSparseOutputs();
        GTRecipe recipe = findMatchingRecipeForPattern(inputs, outputs, recipeTypeId);
        long currentMultiplier = recipe == null ? 0L
                : detectRecipeOutputMultiplier(recipe, StackBag.of(outputs));
        if (recipe != null && currentMultiplier > 0L) {
            Predicate<AEKey> inputContainers = forgeContext
                    ? taggedContainerKeys(recipe.getInputContents(ItemRecipeCapability.CAP))
                    : VirtualPatternEncodingHelper::isRecipeCycleContainerKey;
            Predicate<AEKey> outputContainers = forgeContext
                    ? taggedContainerKeys(recipe.getOutputContents(ItemRecipeCapability.CAP))
                    : VirtualPatternEncodingHelper::isRecipeCycleContainerKey;
            GenericStack[] rewrittenInputs = rewriteCycleContainerInputs(
                    inputs, currentMultiplier, containerMultiplier, inputContainers);
            // 循環容器除不盡時不能放棄整張樣板，否則 AE 會繼續按 1x 產出下單。
            if (rewrittenInputs == null) rewrittenInputs = inputs;
            rewrittenInputs = appendForgeSpecialInput(rewrittenInputs, recipe, forgeMultiplier);
            IPatternDetails rewritten = encodeScaledPattern(processingPattern, rewrittenInputs,
                    createScaledRecipeOutputs(recipe, targetMultiplier, outputContainers), level);
            if (rewritten != null && keepsByproductOrder(processingPattern, rewritten)
                    && productOutputsReflectMultiplier(rewritten, targetMultiplier)
                    && coversPatternProducts(rewritten, outputs, currentMultiplier, targetMultiplier)) {
                return rewritten;
            }
        }
        IPatternDetails direct = rewritePatternOutputMultiplierDirect(processingPattern, level,
                requestedMultiplier, containerMultiplier);
        if (direct != null && direct != processingPattern
                && keepsByproductOrder(processingPattern, direct)
                && productOutputsReflectMultiplier(direct, targetMultiplier)) {
            return direct;
        }
        return scaledOutputView(processingPattern, targetMultiplier);
    }

    private static IPatternDetails rewritePatternOutputMultiplierDirect(AEProcessingPattern pattern, Level level,
            long requestedMultiplier, long containerMultiplier) {
        long targetMultiplier = Math.max(1L, requestedMultiplier);
        if (targetMultiplier <= 1) return pattern;
        GenericStack[] rewrittenInputs = multiplyCycleContainerStacks(pattern.getSparseInputs(), containerMultiplier, true);
        GenericStack[] rewrittenOutputs = multiplyCycleContainerStacks(pattern.getSparseOutputs(), targetMultiplier, false);
        IPatternDetails rewritten = encodeScaledPattern(pattern, rewrittenInputs, rewrittenOutputs, level);
        return rewritten == null ? pattern : rewritten;
    }

    private static IPatternDetails encodeScaledPattern(AEProcessingPattern source, GenericStack[] inputs,
            GenericStack[] outputs, Level level) {
        if (inputs == null || outputs == null || outputs.length == 0 || level == null) return null;
        try {
            ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
            if (encoded == null || encoded.isEmpty()) return null;
            // 重编码只写 in/out。副产物下单靠根标签挂进 AE 可合成索引，丢掉后终端无法请求副产物。
            PatternByproductOrder.copyDefinitionTags(source, encoded);
            return PatternDetailsHelper.decodePattern(encoded, level);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean keepsByproductOrder(IPatternDetails source, IPatternDetails rewritten) {
        return !PatternByproductOrder.isMarked(source) || PatternByproductOrder.isMarked(rewritten);
    }

    private static boolean coversPatternProducts(IPatternDetails rewritten, GenericStack[] patternOutputs,
            long currentMultiplier, long targetMultiplier) {
        if (rewritten == null || patternOutputs == null) return false;
        GenericStack[] rewrittenOutputs = rewritten instanceof AEProcessingPattern processing
                ? processing.getSparseOutputs() : rewritten.getOutputs();
        for (GenericStack output : patternOutputs) {
            if (output == null || output.what() == null || output.amount() <= 0L) continue;
            if (isRecipeCycleContainerKey(output.what())) continue;
            long baseAmount = currentMultiplier > 1L && output.amount() % currentMultiplier == 0L
                    ? output.amount() / currentMultiplier : output.amount();
            long expected = NumberUtils.saturatedMultiply(baseAmount, targetMultiplier);
            long actual = 0L;
            if (rewrittenOutputs != null) {
                for (GenericStack candidate : rewrittenOutputs) {
                    if (candidate != null && output.what().equals(candidate.what())) {
                        actual = NumberUtils.saturatedAdd(actual, candidate.amount());
                    }
                }
            }
            if (actual < expected) return false;
        }
        return true;
    }

    private static IPatternDetails scaledOutputView(AEProcessingPattern pattern, long multiplier) {
        GenericStack[] outputs = pattern.getOutputs();
        if (outputs == null || outputs.length == 0) return pattern;
        GenericStack[] scaled = new GenericStack[outputs.length];
        for (int i = 0; i < outputs.length; i++) {
            GenericStack output = outputs[i];
            if (output == null || output.what() == null) {
                scaled[i] = output;
                continue;
            }
            long amount = isRecipeCycleContainerKey(output.what())
                    ? output.amount() : NumberUtils.saturatedMultiply(output.amount(), multiplier);
            scaled[i] = new GenericStack(output.what(), amount);
        }
        return new ScaledOutputPatternView(pattern, scaled);
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
            long currentMultiplier, long targetMultiplier, Predicate<AEKey> isContainer) {
        if (inputs == null) return null;
        GenericStack[] rewritten = new GenericStack[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            GenericStack input = inputs[i];
            if (input == null || !isContainer.test(input.what())) {
                rewritten[i] = input;
                continue;
            }
            if (input.amount() <= 0L || input.amount() % currentMultiplier != 0L) return null;
            long baseAmount = input.amount() / currentMultiplier;
            rewritten[i] = new GenericStack(input.what(), NumberUtils.saturatedMultiply(baseAmount, targetMultiplier));
        }
        return rewritten;
    }

    private static GenericStack[] createScaledRecipeOutputs(GTRecipe recipe, long targetMultiplier,
            Predicate<AEKey> isContainer) {
        List<GenericStack> outputs = createRecipeOutputs(recipe);
        GenericStack[] rewritten = new GenericStack[outputs.size()];
        for (int i = 0; i < outputs.size(); i++) {
            GenericStack output = outputs.get(i);
            long amount = isContainer.test(output.what())
                    ? output.amount() : NumberUtils.saturatedMultiply(output.amount(), targetMultiplier);
            rewritten[i] = new GenericStack(output.what(), amount);
        }
        return rewritten;
    }

    /**
     * 原版 FOTC 靠配方内容的 {@code recipe_cycle_container} 槽名认容器（RecipesModify 只给 dimensionally_transcendent_plasma_forge
     * 与 stellar_forge 两类配方打标）。没打标的内容——ultimate_material_forge、各子模块的配方——一律按普通物品放大。
     */
    private static Predicate<AEKey> taggedContainerKeys(List<Content> contents) {
        if (contents == null || contents.isEmpty()) return key -> false;
        Set<AEKey> keys = new HashSet<>();
        for (Content content : contents) {
            if (content == null || !RecipeCalculationHelper.INSTANCE.isRecipeCycleContainerContent(content)) continue;
            ItemStack stack = firstItemStack(content);
            if (!stack.isEmpty()) keys.add(AEItemKey.of(stack));
        }
        return keys::contains;
    }

    /**
     * 对应 {@code RecipeCalculationHelper.appendSpecificInput}：三条 stellar_forge 配方在伪神倍率 ≥ 2 时
     * 追加 {@code 单位量 × (倍率 - 1)} 个空容器，否则 N 倍产物没有 N 个容器可装，机器会因缺罐子卡死。
     */
    private static GenericStack[] appendForgeSpecialInput(GenericStack[] inputs, GTRecipe recipe,
            long forgeMultiplier) {
        if (inputs == null || recipe == null || forgeMultiplier < 2L) return inputs;
        GenericStack extraInput = forgeSpecialInput(recipe, forgeMultiplier);
        if (extraInput == null) return inputs;
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] == null) {
                GenericStack[] filled = inputs.clone();
                filled[i] = extraInput;
                return filled;
            }
        }
        if (inputs.length >= AEProcessingPattern.MAX_INPUT_SLOTS) return inputs;
        GenericStack[] extended = Arrays.copyOf(inputs, inputs.length + 1);
        extended[inputs.length] = extraInput;
        return extended;
    }

    private static GenericStack forgeSpecialInput(GTRecipe recipe, long forgeMultiplier) {
        if (recipe.getId() == null) return null;
        Content rule;
        try {
            rule = RecipeCalculationHelper.INSTANCE.getFORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES().get(recipe.getId());
        } catch (RuntimeException ignored) {
            // 规则表懒加载依赖 kubejs 物品注册；缺失时退回不追加，行为同改动前
            return null;
        }
        if (rule == null) return null;
        List<GenericStack> unit = new ArrayList<>(1);
        appendItemStacks(List.of(rule), unit);
        if (unit.isEmpty()) return null;
        long extra = ForgeOutputMultiplier.specialInputExtra(unit.get(0).amount(), forgeMultiplier);
        return extra <= 0L ? null : new GenericStack(unit.get(0).what(), extra);
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

    public static GenericStack resolveVirtualTargetForPatternInput(GenericStack input, GTRecipe recipe) {
        if (input == null || input.what() == null || input.amount() <= 0) return null;
        GenericStack providerTarget = getVirtualProviderTarget(input);
        if (providerTarget != null) return providerTarget;
        GenericStack circuitTarget = getIntegratedCircuitTarget(input);
        if (circuitTarget != null) return circuitTarget;
        return recipe == null ? null : getNonConsumableFluidTarget(recipe, input);
    }

    private static GenericStack getVirtualProviderTarget(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey key)) return null;
        ItemStack provider = key.toStack();
        if (!VirtualItemProviderHelper.isProviderItem(provider)) return null;
        ItemStack target = VirtualItemProviderHelper.getTarget(provider);
        if (target.isEmpty()) return null;
        GenericStack resolved = GenericStack.fromItemStack(target);
        return resolved == null || resolved.amount() <= 0L ? null : resolved;
    }

    // 裸编程电路(未被包裹的集成电路)识别为"自映射虚拟目标"：目标就是电路自身、数量恒为 1。
    // 不创建 virtual provider 物品，交给样板总成的虚拟电路机制处理（见 analyzePattern 注释）。
    private static GenericStack getIntegratedCircuitTarget(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey key)) return null;
        if (!IntCircuitBehaviour.isIntegratedCircuit(key.toStack())) return null;
        return new GenericStack(stack.what(), 1);
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
                List<GenericStack> original = compactStacks(inputs);
                InputMatchPlan plan = findInputMatchPlan(matchingRecipe, inputs, StackBag.EMPTY, false);
                if (plan != null) {
                    for (Content content : matchingRecipe.getInputContents(FluidRecipeCapability.CAP)) {
                        if (!isNonConsumable(content)) continue;
                        Integer index = plan.indexFor(content);
                        if (index == null) continue;
                        GenericStack input = original.get(index);
                        if (!(input.what() instanceof AEFluidKey)
                                || input.amount() != VIRTUAL_FLUID_MARKER_AMOUNT) continue;
                        com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
                        if (sample != null && !sample.isEmpty()) {
                            targets.put(new Entry(input.what(), input.amount()),
                                    new GenericStack(input.what(), Math.max(1L, sample.getAmount())));
                        }
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

    static GTRecipe findMatchingRecipeForEncoding(GenericStack[] inputs, GenericStack[] outputs) {
        GTRecipe selectedRecipe = PatternRecipeTypeHelper.currentSelectedEncodingRecipe();
        if (selectedRecipe != null) {
            StackBag outputBag = StackBag.of(outputs);
            long multiplier = detectRecipeOutputMultiplier(selectedRecipe, outputBag);
            if (multiplier > 0L && matchesRecipeInputsAtMultiplier(selectedRecipe, inputs,
                    StackBag.of(inputs), multiplier, StackBag.EMPTY, true)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] using JEI-selected recipe {} after exact input/output validation",
                            selectedRecipe.id);
                }
                return selectedRecipe;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("[VirtualPatternEncoding] JEI-selected recipe {} no longer matches current pattern, "
                                + "falling back to unscoped exact lookup", selectedRecipe.id);
            }
            // 选择上下文已经失配时，不能继续沿用旧 recipe type；重新按完整输入/输出做唯一反查。
            GTRecipe unscoped = findMatchingRecipe(inputs, outputs, getRecipeOutputIndex(), null,
                    StackBag.EMPTY, true);
            return unscoped;
        }
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
            if (partialSelection.ambiguous || partialSelection.recipe != null) {
                return partialSelection.ambiguous ? null : partialSelection.recipe;
            }
            return findMatchingRecipeInCurrentTypeLookup(inputs, outputs, inputBag, outputBag,
                    availableCatalystInputs, requiredType, allowOmittedNonConsumables);
        }
        return null;
    }

    private static GTRecipe findMatchingRecipeInCurrentTypeLookup(GenericStack[] inputs, GenericStack[] outputs,
            StackBag inputBag, StackBag outputBag, StackBag availableCatalystInputs,
            GTRecipeType requiredType, boolean allowOmittedNonConsumables) {
        if (requiredType.getLookup() == null || requiredType.getLookup().getLookup() == null) return null;
        // 索引 miss 後仍要看該類型的當前 lookup，但同一 revision 只按產出鍵建一次索引。
        // 否則每張樣板都會對該類型全部不消耗配方重跑 detectRecipeOutputMultiplier。
        List<GTRecipe> currentRecipes = nonConsumableRecipesSharingOutputs(requiredType, outputBag.keys());

        RecipeSelection selection = selectRecipeCandidate(currentRecipes, inputs, outputs, inputBag, outputBag,
                availableCatalystInputs, requiredType, false, allowOmittedNonConsumables);
        if (selection.recipe == null && !selection.ambiguous) {
            selection = selectRecipeCandidate(currentRecipes, inputs, outputs, inputBag, outputBag,
                    availableCatalystInputs, requiredType, true, allowOmittedNonConsumables);
        }
        if (selection.recipe == null && !selection.ambiguous) {
            selection = selectPartialTypeScopedCandidate(currentRecipes, inputs, inputBag, outputBag,
                    availableCatalystInputs, requiredType, allowOmittedNonConsumables);
        }
        if (selection.ambiguous || selection.recipe == null) return null;
        if (LOG.isDebugEnabled()) {
            LOG.debug("[VirtualPatternEncoding] type-scoped live lookup matched recipe={}",
                    selection.recipe.getId());
        }
        return selection.recipe;
    }

    /**
     * 类型域兜底会展开整棵 lookup。同一 revision 内配方表不变，按类型只展开一次。
     */
    private static TypeScopedRecipeCache typeScopedRecipeCache() {
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        TypeScopedRecipeCache cache = typeScopedRecipeCache;
        if (cache != null && cache.revision == revision) return cache;
        synchronized (VirtualPatternEncodingHelper.class) {
            cache = typeScopedRecipeCache;
            if (cache == null || cache.revision != revision) {
                cache = new TypeScopedRecipeCache(revision);
                typeScopedRecipeCache = cache;
            }
            return cache;
        }
    }

    private static List<GTRecipe> nonConsumableRecipesSharingOutputs(GTRecipeType requiredType, Set<AEKey> outputKeys) {
        if (requiredType.getLookup() == null || requiredType.getLookup().getLookup() == null) return List.of();
        return typeScopedRecipeCache().sharingOutputKeys(requiredType, outputKeys);
    }

    private static RecipeOutputSnapshot recipeOutputSnapshot(GTRecipe recipe) {
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        RecipeOutputSnapshotCache cache = recipeOutputSnapshotCache;
        if (cache == null || cache.revision != revision) {
            synchronized (VirtualPatternEncodingHelper.class) {
                cache = recipeOutputSnapshotCache;
                if (cache == null || cache.revision != revision) {
                    cache = new RecipeOutputSnapshotCache(revision);
                    recipeOutputSnapshotCache = cache;
                }
            }
        }
        return cache.get(recipe);
    }

    private static List<GTRecipe> collectNonConsumableRecipes(GTRecipeType requiredType) {
        if (requiredType.getLookup() == null || requiredType.getLookup().getLookup() == null) return List.of();
        List<GTRecipe> currentRecipes = new ArrayList<>();
        Iterable<GTRecipe> recipes = requiredType.getLookup().getLookup().getRecipes(true)::iterator;
        for (GTRecipe recipe : recipes) {
            if (recipe != null && hasNonConsumableInput(recipe)) currentRecipes.add(recipe);
        }
        return currentRecipes.isEmpty() ? List.of() : List.copyOf(currentRecipes);
    }

    private static RecipeSelection selectRecipeCandidate(List<GTRecipe> candidates,
            GenericStack[] patternInputs, GenericStack[] patternOutputs, StackBag inputBag, StackBag outputBag,
            StackBag availableCatalystInputs, GTRecipeType requiredType, boolean scaledOnly,
            boolean allowOmittedNonConsumables) {
        List<RecipeCandidate> matches = new ArrayList<>();
        Map<AEKey, Long> patternAmounts = outputBag.amounts();
        for (GTRecipe recipe : candidates) {
            if (recipe == null || (requiredType != null && (recipe.recipeType == null
                    || !Objects.equals(recipe.recipeType.registryName, requiredType.registryName)))) {
                continue;
            }
            long multiplier = detectRecipeOutputMultiplier(recipe, patternAmounts);
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
        Map<AEKey, Long> patternAmounts = outputBag.amounts();
        for (GTRecipe recipe : candidates) {
            if (recipe == null || recipe.recipeType == null
                    || !Objects.equals(recipe.recipeType.registryName, requiredType.registryName)) {
                continue;
            }
            long multiplier = detectPartialRecipeOutputMultiplier(recipe, patternAmounts);
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
        return detectRecipeOutputMultiplier(recipe, patternOutputs.amounts());
    }

    private static long detectRecipeOutputMultiplier(GTRecipe recipe, Map<AEKey, Long> patternAmounts) {
        RecipeOutputSnapshot baseOutputs = recipeOutputSnapshot(recipe);
        return UniformOutputMultiplier.detect(baseOutputs.amounts, patternAmounts, baseOutputs.unscaledKeys);
    }

    private static long detectPartialRecipeOutputMultiplier(GTRecipe recipe, Map<AEKey, Long> patternAmounts) {
        RecipeOutputSnapshot snapshot = recipeOutputSnapshot(recipe);
        Map<AEKey, Long> recipeAmounts = snapshot.amounts;
        if (recipeAmounts.isEmpty() || patternAmounts.isEmpty()) return 0L;

        Map<AEKey, Long> matchingRecipeAmounts = new HashMap<>();
        Set<AEKey> unscaledKeys = new HashSet<>();
        for (AEKey key : patternAmounts.keySet()) {
            Long amount = recipeAmounts.get(key);
            if (amount == null || amount.longValue() <= 0L) return 0L;
            matchingRecipeAmounts.put(key, amount);
            if (snapshot.unscaledKeys.contains(key)) unscaledKeys.add(key);
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

    private static final AtomicBoolean INDEX_PREWARM_RUNNING = new AtomicBoolean(false);

    /**
     * 后台预热配方输出索引。buildRecipeOutputIndexes 是类锁内的全量配方扫描（GTL 体量下可达秒级），
     * 若等到第一次样板编码 / AE 规划时才惰性构建，会卡住主线程或合成计算线程。
     * 服务器启动完成后（延迟 tick 里）调用一次即可；revision 未变时重复调用是空操作，无害。
     */
    public static void prewarmRecipeOutputIndexesAsync(String reason) {
        if (!INDEX_PREWARM_RUNNING.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                getRecipeOutputIndexes();
                // 走 lifecycle 日志通道：编码器自己的 LOG 按契约不写 INFO（热路径防刷屏）
                GTDishanhaiMod.LOGGER.info("[VirtualPatternEncodingIndex] 配方输出索引预热完成，耗时 {} ms ({})",
                        System.currentTimeMillis() - start, reason);
            } catch (Throwable t) {
                GTDishanhaiMod.LOGGER.warn("[VirtualPatternEncodingIndex] 配方输出索引预热失败 ({})", reason, t);
            } finally {
                INDEX_PREWARM_RUNNING.set(false);
            }
        }, "gtshanhai-recipe-index-prewarm");
        thread.setDaemon(true);
        thread.start();
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
                || findInputMatchPlan(recipe, patternInputs, availableCatalystInputs,
                        allowOmittedNonConsumables) != null;
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
        return findInputMatchPlan(recipe, patternInputs, availableCatalystInputs,
                allowOmittedNonConsumables) != null;
    }

    /**
     * 为一条配方建立输入内容到样板槽的一对一匹配计划。
     *
     * <p>旧实现按配方内容顺序取第一个命中槽位。Ingredient/tag 重叠时，前一个宽匹配
     * 会抢走后一个只能精确匹配的槽位，导致本来可用的配方被判定为不匹配，或者重写阶段
     * 把不消耗物品包到错误的槽位。这里先按候选数量和必需性排序，再对候选槽位回溯，
     * 同一套计划算法同时服务反查和编码重写。</p>
     */
    private static InputMatchPlan findInputMatchPlan(GTRecipe recipe, GenericStack[] patternInputs,
            StackBag availableCatalystInputs, boolean allowOmittedNonConsumables) {
        return findInputMatchPlan(recipe, patternInputs, availableCatalystInputs,
                allowOmittedNonConsumables, false);
    }

    private static InputMatchPlan findInputMatchPlan(GTRecipe recipe, GenericStack[] patternInputs,
            StackBag availableCatalystInputs, boolean allowOmittedNonConsumables,
            boolean allowPartialPattern) {
        List<GenericStack> inputs = compactStacks(patternInputs);
        int requiredCount = countRequiredPatternInputs(recipe, availableCatalystInputs,
                allowOmittedNonConsumables);
        int totalCount = countRecipeInputs(recipe);
        if (!allowPartialPattern && (inputs.size() < requiredCount || inputs.size() > totalCount)) return null;

        boolean[] used = new boolean[inputs.size()];
        InputMatchPlan plan = new InputMatchPlan();
        if (!assignItemContents(recipe.getInputContents(ItemRecipeCapability.CAP), inputs, used,
                availableCatalystInputs, allowOmittedNonConsumables, allowPartialPattern, plan)) return null;
        if (!assignFluidContents(recipe.getInputContents(FluidRecipeCapability.CAP), inputs, used,
                availableCatalystInputs, allowOmittedNonConsumables, allowPartialPattern, plan)) return null;
        if (!allowPartialPattern) {
            for (boolean matched : used) {
                if (!matched) return null;
            }
            return plan;
        }
        if (inputs.isEmpty()) return plan;
        for (boolean matched : used) {
            if (matched) return plan;
        }
        return null;
    }

    private static boolean assignItemContents(List<Content> contents, List<GenericStack> inputs,
            boolean[] used, StackBag availableCatalystInputs, boolean allowOmittedNonConsumables,
            boolean allowAnyOmission, InputMatchPlan plan) {
        List<InputMatchSpec> specs = new ArrayList<>();
        if (contents != null) {
            for (Content content : contents) {
                Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
                if (ingredient == null || ingredient.isEmpty()) continue;
                long amount = getItemAmount(content, firstItemStack(content));
                List<InputMatchCandidate> candidates = new ArrayList<>();
                for (int i = 0; i < inputs.size(); i++) {
                    GenericStack input = inputs.get(i);
                    if (!(input.what() instanceof AEItemKey key)) continue;
                    int score = itemInputMatchScore(input, key, ingredient, amount,
                            isOmittablePatternCatalyst(content));
                    if (score > 0) candidates.add(new InputMatchCandidate(i, score));
                }
                candidates.sort(InputMatchCandidate.ORDER);
                specs.add(new InputMatchSpec(content, candidates,
                        allowAnyOmission || canOmitItemPatternInput(content, availableCatalystInputs,
                                allowOmittedNonConsumables)));
            }
        }
        return assignContentSpecs(specs, 0, used, plan);
    }

    private static boolean assignFluidContents(List<Content> contents, List<GenericStack> inputs,
            boolean[] used, StackBag availableCatalystInputs, boolean allowOmittedNonConsumables,
            boolean allowAnyOmission, InputMatchPlan plan) {
        List<InputMatchSpec> specs = new ArrayList<>();
        if (contents != null) {
            for (Content content : contents) {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
                if (ingredient == null || ingredient.isEmpty()) continue;
                com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
                long amount = sample == null ? 0L : sample.getAmount();
                List<InputMatchCandidate> candidates = new ArrayList<>();
                for (int i = 0; i < inputs.size(); i++) {
                    GenericStack input = inputs.get(i);
                    int score;
                    if (input.what() instanceof AEFluidKey key) {
                        score = fluidInputMatchScore(input, key, ingredient, sample, amount, content);
                    } else {
                        GenericStack target = getVirtualProviderTarget(input);
                        if (!isNonConsumable(content) || input.amount() != 1L || target == null
                                || !(target.what() instanceof AEFluidKey key) || target.amount() != amount) continue;
                        score = fluidInputMatchScore(target, key, ingredient, sample, amount, content) > 0 ? 100 : 0;
                    }
                    if (score > 0) candidates.add(new InputMatchCandidate(i, score));
                }
                candidates.sort(InputMatchCandidate.ORDER);
                specs.add(new InputMatchSpec(content, candidates,
                        allowAnyOmission || canOmitFluidPatternInput(content, availableCatalystInputs,
                                allowOmittedNonConsumables)));
            }
        }
        return assignContentSpecs(specs, 0, used, plan);
    }

    private static boolean assignContentSpecs(List<InputMatchSpec> specs, int offset,
            boolean[] used, InputMatchPlan plan) {
        if (offset >= specs.size()) return true;
        if (offset == 0) {
            specs.sort((left, right) -> {
                int required = Boolean.compare(left.canOmit, right.canOmit);
                return required != 0 ? required
                        : Integer.compare(left.candidates.size(), right.candidates.size());
            });
        }
        InputMatchSpec spec = specs.get(offset);
        for (InputMatchCandidate candidate : spec.candidates) {
            if (used[candidate.inputIndex]) continue;
            used[candidate.inputIndex] = true;
            plan.bind(spec.content, candidate.inputIndex);
            if (assignContentSpecs(specs, offset + 1, used, plan)) return true;
            plan.unbind(spec.content);
            used[candidate.inputIndex] = false;
        }
        if (spec.canOmit) {
            plan.omit(spec.content);
            if (assignContentSpecs(specs, offset + 1, used, plan)) return true;
            plan.unomit(spec.content);
        }
        return false;
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

    private static int itemInputMatchScore(GenericStack input, AEItemKey key,
            Ingredient ingredient, long expectedAmount, boolean optional) {
        if (input == null || key == null || ingredient == null || input.amount() <= 0L) return 0;
        ItemStack stack = key.toStack();
        if (input.amount() == expectedAmount && ingredient.test(stack)) {
            ItemStack sample = ingredient.getItems().length == 0 ? ItemStack.EMPTY : ingredient.getItems()[0];
            return !sample.isEmpty() && key.equals(AEItemKey.of(sample)) ? 300 : 200;
        }
        if (!optional || input.amount() != 1L || !VirtualItemProviderHelper.isProviderItem(stack)) return 0;
        ItemStack target = VirtualItemProviderHelper.getTarget(stack);
        if (target.isEmpty() || !ingredient.test(target)) return 0;
        long encodedAmount = Math.max(1L, target.getCount());
        return encodedAmount == Math.min((long) Integer.MAX_VALUE, expectedAmount) ? 100 : 0;
    }

    private static int fluidInputMatchScore(GenericStack input, AEFluidKey key,
            FluidIngredient ingredient, com.lowdragmc.lowdraglib.side.fluid.FluidStack sample,
            long expectedAmount, Content content) {
        if (input == null || key == null || ingredient == null || sample == null || sample.isEmpty()) return 0;
        if (input.amount() != expectedAmount
                && !(isNonConsumable(content)
                        && input.amount() == VIRTUAL_FLUID_MARKER_AMOUNT)) return 0;
        Fluid fluid = (Fluid) key.getPrimaryKey();
        CompoundTag tag = key.toTag();
        com.lowdragmc.lowdraglib.side.fluid.FluidStack stack =
                com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(
                        fluid, expectedAmount, tag.contains("tag", 10) ? tag.getCompound("tag") : null);
        if (!ingredient.test(stack)) return 0;
        if (key.equals(fluidKeyOf(sample)) && input.amount() == expectedAmount) return 300;
        return input.amount() == expectedAmount ? 200 : 100;
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
        if (recipe == null || stack == null || stack.amount() != VIRTUAL_FLUID_MARKER_AMOUNT
                || !(stack.what() instanceof AEFluidKey key)) return null;
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return null;
        for (Content content : contents) {
            if (!isNonConsumable(content)) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack fluidStack = firstFluidStack(content);
            if (fluidStack == null || fluidStack.isEmpty()) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (fluidInputMatchScore(stack, key, ingredient, fluidStack, fluidStack.getAmount(), content) > 0) {
                return new GenericStack(key, Math.max(1L, fluidStack.getAmount()));
            }
        }
        return null;
    }

    /**
     * 編碼當下已經有配方 ID 時，用這條配方的 chance 決定哪些輸入要包。
     * 玩家刪掉若干輸入、拿掉副產物或改了整數倍率後，唯一反查會失敗，這裡仍按已對上的槽位包裹。
     * 產出和這條配方沒有任何交集，或輸入一個都對不上時，退回唯一反查，避免舊 ID 套到另一張配方。
     * 格子裡多了不屬於這條配方的輸入時，只包對得上的不消耗槽，不把刪掉的催化劑補回來。
     */
    private static GenericStack[] rewriteInputsAnchoredToKnownRecipe(GenericStack[] inputs,
            GenericStack[] outputs, GTRecipe recipe) {
        long multiplier = anchoredOutputMultiplier(recipe, outputs);
        if (multiplier <= 0L) return null;
        GenericStack[] scoredInputs = multiplier > 1L ? scaleDivisibleInputs(inputs, multiplier) : inputs;
        InputMatchPlan plan = findInputMatchPlan(recipe, scoredInputs, StackBag.EMPTY, true, false);
        if (plan == null) {
            plan = findInputMatchPlan(recipe, scoredInputs, StackBag.EMPTY, true, true);
        }
        if (plan == null) return null;
        if (hasUnmatchedPatternInput(scoredInputs, plan)) {
            plan.clearOmissions();
        }
        return applyInputMatchPlan(inputs, recipe, plan);
    }

    private static long anchoredOutputMultiplier(GTRecipe recipe, GenericStack[] outputs) {
        RecipeOutputSnapshot snapshot = recipeOutputSnapshot(recipe);
        Map<AEKey, Long> patternAmounts = StackBag.of(outputs).amounts();
        if (snapshot.amounts.isEmpty() || patternAmounts.isEmpty()) return 0L;
        Map<AEKey, Long> overlapRecipe = new HashMap<>();
        Map<AEKey, Long> overlapPattern = new HashMap<>();
        Set<AEKey> unscaled = new HashSet<>();
        for (Map.Entry<AEKey, Long> entry : patternAmounts.entrySet()) {
            Long base = snapshot.amounts.get(entry.getKey());
            if (base == null || base.longValue() <= 0L) continue;
            overlapRecipe.put(entry.getKey(), base);
            overlapPattern.put(entry.getKey(), entry.getValue());
            if (snapshot.unscaledKeys.contains(entry.getKey())) unscaled.add(entry.getKey());
        }
        if (overlapRecipe.isEmpty()) return 0L;
        long multiplier = UniformOutputMultiplier.detect(overlapRecipe, overlapPattern, unscaled);
        return multiplier > 0L ? multiplier : 1L;
    }

    private static GenericStack[] scaleDivisibleInputs(GenericStack[] inputs, long multiplier) {
        GenericStack[] scaled = new GenericStack[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            GenericStack input = inputs[i];
            if (input == null || input.what() == null || input.amount() <= 0L
                    || input.amount() % multiplier != 0L) {
                scaled[i] = input;
                continue;
            }
            long scaledAmount = input.amount() / multiplier;
            scaled[i] = scaledAmount == input.amount()
                    ? input : new GenericStack(input.what(), scaledAmount);
        }
        return scaled;
    }

    private static boolean hasUnmatchedPatternInput(GenericStack[] patternInputs, InputMatchPlan plan) {
        int count = compactStacks(patternInputs).size();
        for (int i = 0; i < count; i++) {
            if (!plan.binds(i)) return true;
        }
        return false;
    }

    private static GenericStack[] rewriteInputsPreservingSelections(GenericStack[] inputs, GTRecipe recipe) {
        InputMatchPlan plan = findInputMatchPlan(recipe, inputs, StackBag.EMPTY, true);
        if (plan == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[VirtualPatternEncoding] recipe={} exact input assignment failed, wrap skipped",
                        recipe.getId());
            }
            return inputs;
        }
        return applyInputMatchPlan(inputs, recipe, plan);
    }

    private static GenericStack[] applyInputMatchPlan(GenericStack[] inputs, GTRecipe recipe, InputMatchPlan plan) {
        List<GenericStack> original = compactStacks(inputs);
        List<GenericStack> rewritten = new ArrayList<>(original);
        rewriteItemInputsFromPlan(recipe.getInputContents(ItemRecipeCapability.CAP), original, rewritten, plan);
        rewriteFluidInputsFromPlan(recipe.getInputContents(FluidRecipeCapability.CAP), original, rewritten, plan);
        return rewritten.toArray(new GenericStack[0]);
    }

    private static void rewriteItemInputsFromPlan(List<Content> contents,
            List<GenericStack> original, List<GenericStack> rewritten, InputMatchPlan plan) {
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            long amount = getItemAmount(content, firstItemStack(content));
            Integer matchedIndex = plan.indexFor(content);
            if (matchedIndex == null) {
                if (!plan.wasOmitted(content) || !isNonConsumable(content)) continue;
                if (!DShanhaiConfig.COMMON.virtualProviderForceWrapOmittedNonConsumables.get()) {
                    continue;
                }
                ItemStack sample = firstItemStack(content);
                GenericStack missingInput = createVirtualItemInput(sample, amount);
                if (missingInput != null) rewritten.add(missingInput);
                continue;
            }
            if (!isNonConsumable(content)) continue;
            ItemStack selected = original.get(matchedIndex).what() instanceof AEItemKey key
                    ? key.toStack() : ItemStack.EMPTY;
            if (VirtualItemProviderHelper.isProviderItem(selected)) continue;

            selected.setCount((int) Math.min(Integer.MAX_VALUE, amount));
            GenericStack virtualInput = createVirtualItemInput(selected, amount);
            if (virtualInput == null) continue;
            rewritten.set(matchedIndex, virtualInput);
        }
    }

    private static void rewriteFluidInputsFromPlan(List<Content> contents,
            List<GenericStack> original, List<GenericStack> rewritten, InputMatchPlan plan) {
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
            if (sample == null) continue;
            Integer matchedIndex = plan.indexFor(content);
            if (matchedIndex == null) {
                if (!plan.wasOmitted(content) || !isNonConsumable(content)) continue;
                if (!DShanhaiConfig.COMMON.virtualProviderForceWrapOmittedNonConsumables.get()) continue;
                GenericStack missingInput = createVirtualFluidInput(fluidKeyOf(sample), sample.getAmount());
                if (missingInput != null) rewritten.add(missingInput);
                continue;
            }
            if (isNonConsumable(content)) {
                GenericStack selected = original.get(matchedIndex);
                if (!(selected.what() instanceof AEFluidKey key)) continue;
                GenericStack virtualInput = createVirtualFluidInput(key, sample.getAmount());
                if (virtualInput != null) rewritten.set(matchedIndex, virtualInput);
            }
        }
    }

    private static GenericStack createVirtualFluidInput(AEFluidKey key, long amount) {
        if (key == null || amount <= 0L) return null;
        if (!PatternEncodePreferences.writeVirtualProvider()) {
            return new GenericStack(key, amount);
        }
        ItemStack provider = VirtualItemProviderHelper.createBoundProvider(new GenericStack(key, amount));
        return provider.isEmpty() ? null : new GenericStack(AEItemKey.of(provider), 1L);
    }

    private static GenericStack createVirtualItemInput(ItemStack sample, long amount) {
        if (sample == null || sample.isEmpty()) return null;
        sample = sample.copy();
        sample.setCount((int) Math.min(Integer.MAX_VALUE, amount));
        if (IntCircuitBehaviour.isIntegratedCircuit(sample)
                || VirtualItemProviderHelper.isAutoWrapExcluded(sample)
                || !PatternEncodePreferences.writeVirtualProvider()) {
            return new GenericStack(AEItemKey.of(sample), amount);
        }
        ItemStack provider = VirtualItemProviderHelper.createBoundProvider(sample.copy());
        if (provider.isEmpty()) {
            LOG.warn("[VirtualPatternEncoding] item={} createBoundProvider returned EMPTY", sample.getItem());
            return null;
        }
        return new GenericStack(AEItemKey.of(provider), 1);
    }

    private static List<GenericStack> createVirtualInputs(GTRecipe recipe) {
        List<GenericStack> stacks = new ArrayList<>();
        List<Content> itemInputs = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (itemInputs != null) {
            for (Content content : itemInputs) {
                ItemStack stack = firstItemStack(content);
                if (stack.isEmpty()) continue;

                if (isNonConsumable(content)) {
                    if (VirtualItemProviderHelper.isAutoWrapExcluded(stack)
                            || !PatternEncodePreferences.writeVirtualProvider()) {
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
            if (virtualizeNonConsumable && isNonConsumable(content)) {
                GenericStack virtualInput = createVirtualFluidInput(key, stack.getAmount());
                if (virtualInput != null) stacks.add(virtualInput);
            } else {
                stacks.add(new GenericStack(key, Math.max(1L, stack.getAmount())));
            }
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

    private static final class ScaledOutputPatternView implements IPatternDetails {
        private final AEProcessingPattern base;
        private final GenericStack[] outputs;

        private ScaledOutputPatternView(AEProcessingPattern base, GenericStack[] outputs) {
            this.base = base;
            this.outputs = outputs;
        }

        @Override
        public AEItemKey getDefinition() {
            return base.getDefinition();
        }

        @Override
        public IPatternDetails.IInput[] getInputs() {
            return base.getInputs();
        }

        @Override
        public GenericStack[] getOutputs() {
            return outputs;
        }

        @Override
        public void pushInputsToExternalInventory(KeyCounter[] inputHolder, IPatternDetails.PatternInputSink inputSink) {
            base.pushInputsToExternalInventory(inputHolder, inputSink);
        }
    }

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

    private static final class TypeScopedRecipeCache {
        private final long revision;
        private final ConcurrentHashMap<GTRecipeType, List<GTRecipe>> byType = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<GTRecipeType, Map<AEKey, List<GTRecipe>>> byOutputKey = new ConcurrentHashMap<>();

        private TypeScopedRecipeCache(long revision) {
            this.revision = revision;
        }

        private List<GTRecipe> recipesFor(GTRecipeType type) {
            List<GTRecipe> cached = byType.get(type);
            if (cached != null) return cached;
            List<GTRecipe> built = collectNonConsumableRecipes(type);
            List<GTRecipe> raced = byType.putIfAbsent(type, built);
            return raced == null ? built : raced;
        }

        private List<GTRecipe> sharingOutputKeys(GTRecipeType type, Set<AEKey> outputKeys) {
            if (outputKeys == null || outputKeys.isEmpty()) return List.of();
            Map<AEKey, List<GTRecipe>> index = outputKeyIndex(type);
            LinkedHashSet<GTRecipe> found = new LinkedHashSet<>();
            for (AEKey key : outputKeys) {
                List<GTRecipe> keyed = index.get(key);
                if (keyed != null) found.addAll(keyed);
            }
            return found.isEmpty() ? List.of() : new ArrayList<>(found);
        }

        private Map<AEKey, List<GTRecipe>> outputKeyIndex(GTRecipeType type) {
            Map<AEKey, List<GTRecipe>> cached = byOutputKey.get(type);
            if (cached != null) return cached;
            Map<AEKey, List<GTRecipe>> built = new HashMap<>();
            for (GTRecipe recipe : recipesFor(type)) {
                for (AEKey key : recipeOutputSnapshot(recipe).outputKeys) {
                    built.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
                }
            }
            Map<AEKey, List<GTRecipe>> raced = byOutputKey.putIfAbsent(type, built);
            return raced == null ? built : raced;
        }
    }

    private static final class RecipeOutputSnapshot {
        private final Map<AEKey, Long> amounts;
        private final Set<AEKey> unscaledKeys;
        private final Set<AEKey> outputKeys;

        private RecipeOutputSnapshot(Map<AEKey, Long> amounts, Set<AEKey> unscaledKeys, Set<AEKey> outputKeys) {
            this.amounts = amounts;
            this.unscaledKeys = unscaledKeys;
            this.outputKeys = outputKeys;
        }

        private static RecipeOutputSnapshot capture(GTRecipe recipe) {
            StackBag bag = StackBag.of(createRecipeOutputs(recipe));
            Set<AEKey> outputKeys = bag.keys();
            Set<AEKey> unscaledKeys = new HashSet<>();
            for (AEKey key : outputKeys) {
                if (isRecipeCycleContainerKey(key)) unscaledKeys.add(key);
            }
            return new RecipeOutputSnapshot(Map.copyOf(bag.amounts()), Set.copyOf(unscaledKeys), outputKeys);
        }
    }

    private static final class RecipeOutputSnapshotCache {
        private final long revision;
        private final IdentityHashMap<GTRecipe, RecipeOutputSnapshot> byRecipe = new IdentityHashMap<>();

        private RecipeOutputSnapshotCache(long revision) {
            this.revision = revision;
        }

        private RecipeOutputSnapshot get(GTRecipe recipe) {
            synchronized (this) {
                RecipeOutputSnapshot cached = byRecipe.get(recipe);
                if (cached != null) return cached;
                cached = RecipeOutputSnapshot.capture(recipe);
                byRecipe.put(recipe, cached);
                return cached;
            }
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

    private static final class InputMatchCandidate {
        private static final Comparator<InputMatchCandidate> ORDER = (left, right) -> {
            int score = Integer.compare(right.score, left.score);
            return score != 0 ? score : Integer.compare(left.inputIndex, right.inputIndex);
        };

        private final int inputIndex;
        private final int score;

        private InputMatchCandidate(int inputIndex, int score) {
            this.inputIndex = inputIndex;
            this.score = score;
        }
    }

    private static final class InputMatchSpec {
        private final Content content;
        private final List<InputMatchCandidate> candidates;
        private final boolean canOmit;

        private InputMatchSpec(Content content, List<InputMatchCandidate> candidates, boolean canOmit) {
            this.content = content;
            this.candidates = candidates;
            this.canOmit = canOmit;
        }
    }

    private static final class InputMatchPlan {
        private final Map<Content, Integer> matches = new IdentityHashMap<>();
        private final Map<Content, Boolean> omitted = new IdentityHashMap<>();

        private void bind(Content content, int inputIndex) {
            omitted.remove(content);
            matches.put(content, inputIndex);
        }

        private void unbind(Content content) {
            matches.remove(content);
        }

        private void omit(Content content) {
            matches.remove(content);
            omitted.put(content, Boolean.TRUE);
        }

        private void unomit(Content content) {
            omitted.remove(content);
        }

        private Integer indexFor(Content content) {
            return matches.get(content);
        }

        private boolean wasOmitted(Content content) {
            return omitted.containsKey(content);
        }

        private boolean binds(int inputIndex) {
            for (Integer matched : matches.values()) {
                if (matched != null && matched.intValue() == inputIndex) return true;
            }
            return false;
        }

        private void clearOmissions() {
            omitted.clear();
        }
    }

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
