package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.util.CraftingRecipeUtil;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;

import java.util.ArrayList;
import java.util.List;

/** Encodes GT recipes with gt_shanhai virtual inputs and authoritative recipe-type metadata. */
public final class ShanhaiPatternEncoder {

    public static Ae2GtmProcessingPattern encode(GTRecipe recipe, ServerPlayer player,
                                                  boolean respectAutoWrapExclusions) {
        return encode(recipe, player, null, respectAutoWrapExclusions);
    }

    /** Encode with the live pattern terminal network so candidate ingredients follow AE2 priorities. */
    public static Ae2GtmProcessingPattern encode(GTRecipe recipe, ServerPlayer player,
                                                  PatternEncodingTermMenu menu,
                                                  boolean respectAutoWrapExclusions) {
        if (recipe == null || player == null) return null;

        List<GenericStack> inputs = new ArrayList<>();
        List<GenericStack> outputs = new ArrayList<>();
        CandidatePriority priority = CandidatePriority.from(menu);
        appendItemInputs(recipe, inputs, priority, respectAutoWrapExclusions);
        appendFluidInputs(recipe, inputs, priority);
        appendItemOutputs(recipe, outputs);
        appendFluidOutputs(recipe, outputs);
        if (outputs.isEmpty()) return null;

        ItemStack patternStack;
        PatternRecipeTypeHelper.pushAuthoritativeEncodingRecipe(recipe);
        try {
            patternStack = PatternDetailsHelper.encodeProcessingPattern(
                    inputs.toArray(new GenericStack[0]),
                    outputs.toArray(new GenericStack[0]));
        } finally {
            PatternRecipeTypeHelper.popAuthoritativeEncodingRecipe();
        }
        if (patternStack == null || patternStack.isEmpty()) return null;

        // GTLCore 的目標搜尋只讀這個 metadata；山海自有標記不能取代它。
        if (recipe.recipeType != null && recipe.recipeType.registryName != null) {
            PatternQuickUploadMetadata.writeRecipeTypeId(patternStack, recipe.recipeType.registryName);
        }
        PatternRecipeTypeHelper.writeAuthoritativeRecipeType(patternStack, recipe);
        return new Ae2GtmProcessingPattern(patternStack, player, recipe);
    }

    /** Encode a vanilla crafting recipe as AE2's native crafting pattern. */
    public static ItemStack encodeCrafting(CraftingRecipe recipe, ServerPlayer player) {
        return encodeCrafting(recipe, player, null);
    }

    /** Encode a vanilla crafting recipe using the pattern terminal's AE2 candidate priorities. */
    public static ItemStack encodeCrafting(CraftingRecipe recipe, ServerPlayer player,
                                            PatternEncodingTermMenu menu) {
        if (recipe == null || player == null) return ItemStack.EMPTY;

        List<Ingredient> matrix = CraftingRecipeUtil.ensure3by3CraftingMatrix(recipe);
        if (matrix == null || matrix.isEmpty()) return ItemStack.EMPTY;

        ItemStack[] inputs = new ItemStack[matrix.size()];
        boolean hasInput = false;
        CandidatePriority priority = CandidatePriority.from(menu);
        for (int slot = 0; slot < matrix.size(); slot++) {
            Ingredient ingredient = matrix.get(slot);
            ItemStack input = bestIngredientStack(ingredient, priority, false);
            inputs[slot] = input;
            hasInput |= !input.isEmpty();
        }
        ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
        if (!hasInput || output.isEmpty()) return ItemStack.EMPTY;

        return PatternDetailsHelper.encodeCraftingPattern(recipe, inputs, output, false, false);
    }

    private static void appendItemInputs(GTRecipe recipe, List<GenericStack> inputs,
                                         CandidatePriority priority,
                                         boolean respectAutoWrapExclusions) {
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            ItemStack stack = bestItemStack(content, priority);
            if (stack.isEmpty()) continue;

            // Programmed circuits are recipe selectors, not virtual inventory requirements.
            if (IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                inputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
                continue;
            }

            if (isNonConsumable(content) && !VirtualItemProviderHelper.isProviderItem(stack)) {
                boolean excluded = respectAutoWrapExclusions
                        && VirtualItemProviderHelper.isAutoWrapExcluded(stack);
                if (!respectAutoWrapExclusions || !excluded) {
                    ItemStack provider = VirtualItemProviderHelper.createBoundProvider(stack);
                    if (!provider.isEmpty() && VirtualItemProviderHelper.isBoundProvider(provider)) {
                        inputs.add(new GenericStack(AEItemKey.of(provider), 1));
                        continue;
                    }
                }
            }

            inputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
        }
    }

    private static void appendFluidInputs(GTRecipe recipe, List<GenericStack> inputs,
                                          CandidatePriority priority) {
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks = ingredient.getStacks();
            com.lowdragmc.lowdraglib.side.fluid.FluidStack stack =
                    bestFluidStack(stacks, priority);
            if (stack == null || stack.isEmpty()) continue;
            GenericStack target = new GenericStack(fluidKeyOf(stack), Math.max(1L, stack.getAmount()));
            if (isNonConsumable(content)) {
                ItemStack provider = VirtualItemProviderHelper.createBoundProvider(target);
                if (provider.isEmpty()) {
                    throw new IllegalStateException("Cannot wrap non-consumable fluid for " + recipe.id);
                }
                inputs.add(new GenericStack(AEItemKey.of(provider), 1L));
            } else {
                inputs.add(target);
            }
        }
    }

    private static void appendItemOutputs(GTRecipe recipe, List<GenericStack> outputs) {
        List<Content> contents = recipe.getOutputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            ItemStack stack = firstItemStack(content);
            if (!stack.isEmpty()) {
                outputs.add(new GenericStack(AEItemKey.of(stack.copy()), getItemAmount(content, stack)));
            }
        }
    }

    private static void appendFluidOutputs(GTRecipe recipe, List<GenericStack> outputs) {
        List<Content> contents = recipe.getOutputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        for (Content content : contents) {
            if (content == null) continue;
            FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
            if (ingredient == null || ingredient.isEmpty()) continue;
            com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks = ingredient.getStacks();
            if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) continue;
            outputs.add(new GenericStack(fluidKeyOf(stacks[0]), stacks[0].getAmount()));
        }
    }

    private static boolean isNonConsumable(Content content) {
        return content != null && content.chance <= 0;
    }

    private static AEFluidKey fluidKeyOf(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && !tag.isEmpty()
                ? AEFluidKey.of(stack.getFluid(), tag)
                : AEFluidKey.of(stack.getFluid());
    }

    private static ItemStack firstItemStack(Content content) {
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0 || stacks[0].isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stacks[0].copy();
        result.setCount(getItemAmount(content, result));
        return result;
    }

    private static ItemStack bestItemStack(Content content, CandidatePriority priority) {
        Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
        return bestIngredientStack(ingredient, priority, true);
    }

    private static ItemStack bestIngredientStack(Ingredient ingredient, CandidatePriority priority,
                                                boolean preferUniversalCircuit) {
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0) return ItemStack.EMPTY;
        ItemStack best = ItemStack.EMPTY;
        CandidateScore bestScore = null;
        for (int index = 0; index < stacks.length; index++) {
            ItemStack candidate = stacks[index];
            if (candidate == null || candidate.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(candidate);
            CandidateScore score = priority.score(key, index, preferUniversalCircuit
                    && isUniversalCircuit(candidate));
            if (bestScore == null || score.isBetterThan(bestScore)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best.isEmpty() ? ItemStack.EMPTY : best.copy();
    }

    private static com.lowdragmc.lowdraglib.side.fluid.FluidStack bestFluidStack(
            com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks, CandidatePriority priority) {
        if (stacks == null || stacks.length == 0) return null;
        com.lowdragmc.lowdraglib.side.fluid.FluidStack best = null;
        CandidateScore bestScore = null;
        for (int index = 0; index < stacks.length; index++) {
            com.lowdragmc.lowdraglib.side.fluid.FluidStack candidate = stacks[index];
            if (candidate == null || candidate.isEmpty()) continue;
            CandidateScore score = priority.score(fluidKeyOf(candidate), index, false);
            if (bestScore == null || score.isBetterThan(bestScore)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? null : best.copy();
    }

    private static int getItemAmount(Content content, ItemStack fallback) {
        Object raw = content.getContent();
        if (raw instanceof SizedIngredient sized) {
            return Math.max(1, sized.getAmount());
        }
        if (raw instanceof LongIngredient ingredient) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ingredient.getActualAmount()));
        }
        return Math.max(1, fallback.getCount());
    }

    private static boolean isUniversalCircuit(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("universal_circuit");
    }

    private static final class CandidatePriority {
        private final IGrid grid;
        private final KeyCounter availableStacks;

        private CandidatePriority(IGrid grid, KeyCounter availableStacks) {
            this.grid = grid;
            this.availableStacks = availableStacks;
        }

        private static CandidatePriority from(PatternEncodingTermMenu menu) {
            if (menu == null) return new CandidatePriority(null, null);
            IGridNode node = menu.getNetworkNode();
            IGrid grid = node == null ? null : node.getGrid();
            KeyCounter availableStacks = null;
            if (menu.getHost() != null && menu.getHost().getInventory() != null) {
                availableStacks = menu.getHost().getInventory().getAvailableStacks();
            }
            return new CandidatePriority(grid, availableStacks);
        }

        private CandidateScore score(AEKey key, int index, boolean universalCircuit) {
            boolean craftable = grid != null && grid.getCraftingService().isCraftable(key);
            boolean undamaged = !(key instanceof AEItemKey itemKey) || !itemKey.isDamaged();
            long stored = availableStacks == null ? 0L : availableStacks.get(key);
            return new CandidateScore(universalCircuit, craftable, undamaged, stored, index);
        }
    }

    private static final class CandidateScore {
        private final boolean universalCircuit;
        private final boolean craftable;
        private final boolean undamaged;
        private final long stored;
        private final int index;

        private CandidateScore(boolean universalCircuit, boolean craftable, boolean undamaged,
                               long stored, int index) {
            this.universalCircuit = universalCircuit;
            this.craftable = craftable;
            this.undamaged = undamaged;
            this.stored = stored;
            this.index = index;
        }

        private boolean isBetterThan(CandidateScore other) {
            if (universalCircuit != other.universalCircuit) return universalCircuit;
            if (craftable != other.craftable) return craftable;
            if (undamaged != other.undamaged) return undamaged;
            if (stored != other.stored) return stored > other.stored;
            return index < other.index;
        }
    }

    private ShanhaiPatternEncoder() {}
}
