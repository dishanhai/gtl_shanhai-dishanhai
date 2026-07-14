package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.crafting.IPatternDetails;
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

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.nbt.CompoundTag;

import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static RecipeOutputIndex recipeOutputIndex;
    private static RecipeOutputIndex allRecipeOutputIndex;

    public static GenericStack[] rewriteInputsForVirtualProviders(GenericStack[] inputs, GenericStack[] outputs) {
        if (inputs == null || outputs == null || containsVirtualInput(inputs, outputs)) {
            return inputs;
        }

        GTRecipe recipe = findMatchingRecipe(inputs, outputs);
        if (recipe == null) {
            return inputs;
        }

        return rewriteInputsPreservingSelections(inputs, recipe);
    }

    public static boolean containsVirtualProviderInput(GenericStack[] inputs) {
        return containsVirtualProviderInput(inputs, null);
    }

    public static boolean containsVirtualProviderInput(GenericStack[] inputs, GenericStack[] outputs) {
        return containsVirtualInput(inputs, outputs);
    }

    public static boolean containsVirtualProviderPattern(IPatternDetails details) {
        return containsVirtualInput(getSparseInputs(details), getSparseOutputs(details));
    }

    public static List<GenericStack> getPresenceCheckTargets(ICraftingPlan plan) {
        Map<AEKey, GenericStack> targets = new LinkedHashMap<>();
        if (plan == null || plan.patternTimes() == null || plan.patternTimes().isEmpty()) {
            return List.of();
        }
        for (IPatternDetails details : plan.patternTimes().keySet()) {
            GenericStack[] sparseInputs = getSparseInputs(details);
            if (sparseInputs == null) continue;
            PatternAnalysis analysis = getPatternAnalysis(sparseInputs, getSparseOutputs(details));
            for (GenericStack input : sparseInputs) {
                GenericStack target = analysis.targetFor(input);
                if (target != null) {
                    targets.putIfAbsent(target.what(), target);
                }
            }
        }
        return new ArrayList<>(targets.values());
    }

    public static IPatternDetails.IInput[] createPlanningInputs(GenericStack[] sparseInputs) {
        return createPlanningInputs(sparseInputs, null);
    }

    public static IPatternDetails.IInput[] createPlanningInputs(GenericStack[] sparseInputs, GenericStack[] sparseOutputs) {
        Map<AEKey, Long> condensed = new LinkedHashMap<>();
        PatternAnalysis analysis = getPatternAnalysis(sparseInputs, sparseOutputs);
        if (sparseInputs != null) {
            for (GenericStack input : sparseInputs) {
                if (input == null || input.what() == null || input.amount() <= 0) continue;
                AEKey key = input.what();
                long amount = input.amount();
                if (analysis.targetFor(input) != null) {
                    continue;
                }
                Long old = condensed.get(key);
                condensed.put(key, old == null ? amount : old + amount);
            }
        }
        IPatternDetails.IInput[] result = new IPatternDetails.IInput[condensed.size()];
        int index = 0;
        for (Map.Entry<AEKey, Long> entry : condensed.entrySet()) {
            result[index++] = new PlanningInput(entry.getKey(), entry.getValue());
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
                    virtualTargetSink.accept(virtualTarget.what(), multiplyAmount(Math.max(1L, virtualTarget.amount()), patternMultiplier));
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
        return findMatchingRecipe(inputs, outputs, getAllRecipeOutputIndex());
    }

    public static GTRecipe findMatchingRecipeForPattern(GenericStack[] inputs, GenericStack[] outputs, String recipeTypeId) {
        GTRecipeType recipeType = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
        if (recipeType == null || recipeType.getLookup() == null || recipeType.getLookup().getLookup() == null) {
            return findMatchingRecipeForPattern(inputs, outputs);
        }
        StackBag inputBag = StackBag.of(inputs);
        StackBag outputBag = StackBag.of(outputs);
        if (inputBag.isEmpty() || outputBag.isEmpty()) {
            return null;
        }
        GTRecipe matched = null;
        Iterable<GTRecipe> recipes = recipeType.getLookup().getLookup().getRecipes(true)::iterator;
        for (GTRecipe recipe : recipes) {
            if (recipe == null || !matchesRecipe(recipe, inputs, inputBag, outputBag)) {
                continue;
            }
            if (matched != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] 配方类型 {} 内输出 {} 匹配到多个配方（至少 {} 与 {}），"
                            + "无法唯一识别，放弃虚拟供料改写/配方类型识别",
                            recipeTypeId, outputBag, matched.getId(), recipe.getId());
                }
                return null;
            }
            matched = recipe;
        }
        return matched;
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
        if (!VirtualItemProviderHelper.isBoundProvider(provider)) return null;
        ItemStack target = VirtualItemProviderHelper.getTarget(provider);
        if (target.isEmpty()) return null;
        return new GenericStack(AEItemKey.of(target), Math.max(1L, target.getCount()));
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
        if (inputs != null) {
            for (GenericStack input : inputs) {
                GenericStack providerTarget = getVirtualProviderTarget(input);
                if (providerTarget != null) {
                    targets.put(new Entry(input.what(), input.amount()), providerTarget);
                    continue;
                }
                if (input != null && input.what() instanceof AEFluidKey && outputs != null) {
                    hasFluidCandidate = true;
                }
            }
        }
        if (hasFluidCandidate) {
            GTRecipe recipe = findMatchingRecipe(inputs, outputs);
            if (recipe != null) {
                for (GenericStack input : inputs) {
                    if (input == null || !(input.what() instanceof AEFluidKey)) continue;
                    GenericStack virtualFluidTarget = getNonConsumableFluidTarget(recipe, input);
                    if (virtualFluidTarget != null) {
                        targets.put(new Entry(input.what(), input.amount()), virtualFluidTarget);
                    }
                }
            }
        }
        return new PatternAnalysis(targets);
    }

    private static GTRecipe findMatchingRecipe(GenericStack[] inputs, GenericStack[] outputs) {
        return findMatchingRecipe(inputs, outputs, getRecipeOutputIndex());
    }

    private static GTRecipe findMatchingRecipe(GenericStack[] inputs, GenericStack[] outputs, RecipeOutputIndex index) {
        StackBag inputBag = StackBag.of(inputs);
        StackBag outputBag = StackBag.of(outputs);
        if (inputBag.isEmpty() || outputBag.isEmpty()) {
            return null;
        }

        GTRecipe matched = null;
        List<GTRecipe> candidates = index.candidates(outputBag);
        for (GTRecipe recipe : candidates) {
            if (!matchesRecipe(recipe, inputs, inputBag, outputBag)) continue;
            if (matched != null) {
                // 多个配方输入输出完全相同（仅电压等属性不同，GT 里不罕见）：无法唯一识别，放弃改写/
                // 放弃识别配方类型。之前完全静默，玩家只会看到"这张样板不认/不省料"却毫无线索
                // （ERR-20260714-009）。补一条 debug 日志，方便现场定位是不是撞上了这个歧义分支。
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[VirtualPatternEncoding] 输出 {} 匹配到多个配方（至少 {} 与 {}），"
                            + "无法唯一识别，放弃虚拟供料改写/配方类型识别", outputBag, matched.getId(), recipe.getId());
                }
                return null;
            }
            matched = recipe;
        }
        return matched;
    }

    private static RecipeOutputIndex getAllRecipeOutputIndex() {
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        RecipeOutputIndex index = allRecipeOutputIndex;
        if (index != null && index.revision == revision) return index;
        synchronized (VirtualPatternEncodingHelper.class) {
            index = allRecipeOutputIndex;
            if (index == null || index.revision != revision) {
                index = buildRecipeOutputIndex(revision, false);
                allRecipeOutputIndex = index;
            }
        }
        return index;
    }

    private static RecipeOutputIndex getRecipeOutputIndex() {
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        RecipeOutputIndex index = recipeOutputIndex;
        if (index != null && index.revision == revision) return index;
        synchronized (VirtualPatternEncodingHelper.class) {
            index = recipeOutputIndex;
            if (index == null || index.revision != revision) {
                index = buildRecipeOutputIndex(revision, true);
                recipeOutputIndex = index;
            }
        }
        return index;
    }

    private static RecipeOutputIndex buildRecipeOutputIndex(long revision, boolean requireNonConsumableInput) {
        Map<StackBag, List<GTRecipe>> byOutput = new HashMap<>();
        for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) continue;
            Iterable<GTRecipe> recipes = type.getLookup().getLookup().getRecipes(true)::iterator;
            for (GTRecipe recipe : recipes) {
                if (recipe == null || (requireNonConsumableInput && !hasNonConsumableInput(recipe))) continue;
                StackBag outputBag = StackBag.of(createRecipeOutputs(recipe));
                if (outputBag.isEmpty()) continue;
                byOutput.computeIfAbsent(outputBag, key -> new ArrayList<>()).add(recipe);
            }
        }
        return new RecipeOutputIndex(revision, byOutput);
    }

    private static boolean matchesRecipe(GTRecipe recipe, GenericStack[] patternInputs, StackBag inputs, StackBag outputs) {
        if (!StackBag.of(createRecipeOutputs(recipe)).equals(outputs)) return false;
        return StackBag.of(createRecipeInputs(recipe)).equals(inputs)
                || StackBag.of(createVirtualInputs(recipe)).equals(inputs)
                || patternInputsMatchRecipe(recipe, patternInputs);
    }

    private static boolean patternInputsMatchRecipe(GTRecipe recipe, GenericStack[] patternInputs) {
        List<GenericStack> inputs = compactStacks(patternInputs);
        int contentCount = countRecipeInputs(recipe);
        if (inputs.size() != contentCount) return false;

        boolean[] used = new boolean[inputs.size()];
        if (!matchItemContents(recipe.getInputContents(ItemRecipeCapability.CAP), inputs, used)) return false;
        if (!matchFluidContents(recipe.getInputContents(FluidRecipeCapability.CAP), inputs, used)) return false;
        for (boolean matched : used) {
            if (!matched) return false;
        }
        return true;
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

    private static boolean matchItemContents(List<Content> contents, List<GenericStack> inputs, boolean[] used) {
        if (contents == null || contents.isEmpty()) return true;
        for (Content content : contents) {
            Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            long amount = getItemAmount(content, firstItemStack(content));
            boolean matched = false;
            for (int i = 0; i < inputs.size(); i++) {
                GenericStack input = inputs.get(i);
                if (used[i] || input.amount() != amount || !(input.what() instanceof AEItemKey key)) continue;
                ItemStack stack = key.toStack();
                if (ingredient.test(stack)) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static boolean matchFluidContents(List<Content> contents, List<GenericStack> inputs, boolean[] used) {
        if (contents == null || contents.isEmpty()) return true;
        for (Content content : contents) {
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack sample = firstFluidStack(content);
            long amount = sample == null ? 0 : sample.getAmount();
            boolean matched = false;
            for (int i = 0; i < inputs.size(); i++) {
                GenericStack input = inputs.get(i);
                if (used[i] || input.amount() != amount || !(input.what() instanceof AEFluidKey key)) continue;
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
            if (!matched) return false;
        }
        return true;
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
        if (original.size() != countRecipeInputs(recipe)) return inputs;

        List<GenericStack> rewritten = new ArrayList<>(original);
        boolean[] used = new boolean[original.size()];
        if (!rewriteItemInputsPreservingSelections(
                recipe.getInputContents(ItemRecipeCapability.CAP), original, rewritten, used)) {
            return inputs;
        }
        if (!rewriteFluidInputsPreservingSelections(
                recipe.getInputContents(FluidRecipeCapability.CAP), original, rewritten, used)) {
            return inputs;
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
                if (used[i] || input.amount() != amount || !(input.what() instanceof AEItemKey key)) continue;
                ItemStack stack = key.toStack();
                if (ingredient.test(stack)) {
                    matchedIndex = i;
                    selected = stack;
                    break;
                }
            }
            if (matchedIndex < 0) return false;
            used[matchedIndex] = true;
            if (!isNonConsumable(content)) continue;

            selected.setCount((int) Math.min(Integer.MAX_VALUE, amount));
            if (VirtualItemProviderHelper.isAutoWrapExcluded(selected)) {
                rewritten.set(matchedIndex, new GenericStack(AEItemKey.of(selected), amount));
                continue;
            }
            ItemStack provider = VirtualItemProviderHelper.createBoundProvider(selected);
            if (provider.isEmpty()) return false;
            rewritten.set(matchedIndex, new GenericStack(AEItemKey.of(provider), 1));
        }
        return true;
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
                if (used[i] || input.amount() != amount || !(input.what() instanceof AEFluidKey key)) continue;
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
            if (matchedIndex < 0) return false;
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

        private PatternAnalysis(Map<Entry, GenericStack> targets) {
            this.targets = targets;
        }

        private boolean containsVirtualInput() {
            return !targets.isEmpty();
        }

        private GenericStack targetFor(GenericStack stack) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) return null;
            return targets.get(new Entry(stack.what(), stack.amount()));
        }
    }

    private static final class RecipeOutputIndex {
        private final long revision;
        private final Map<StackBag, List<GTRecipe>> byOutput;

        private RecipeOutputIndex(long revision, Map<StackBag, List<GTRecipe>> byOutput) {
            this.revision = revision;
            this.byOutput = byOutput;
        }

        private List<GTRecipe> candidates(StackBag outputBag) {
            List<GTRecipe> candidates = byOutput.get(outputBag);
            return candidates == null ? List.of() : candidates;
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
            return Objects.hash(inputs, outputs, revision);
        }
    }

    private static final class PlanningInput implements IPatternDetails.IInput {
        private final GenericStack[] template;
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

    private static final class StackBag {
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
            return Objects.hash(key, amount);
        }
    }
}
