package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 按展开后的编码样板身份保存动态槽，避免五个母槽重排后按旧索引恢复。 */
final class RecipeTypePatternWildcardPersistence {

    private static final String ROOT_KEY = "gtShanhaiWildcardDynamicSlots";
    private static final String PATTERN_KEY = "pattern";
    private static final String SLOT_KEY = "slot";
    private static final String RECIPE_ID_KEY = "recipeId";

    private final List<PendingSlot> pendingSlots = new ArrayList<>();

    void save(CompoundTag root, int baseSlot, List<ItemStack> patternStacks,
            List<CompoundTag> internalSlotData,
            Int2ReferenceMap<GTRecipe> recipeCache) {
        ListTag entries = new ListTag();
        int size = Math.min(patternStacks.size(), internalSlotData.size());
        for (int localSlot = 0; localSlot < size; localSlot++) {
            ItemStack pattern = patternStacks.get(localSlot);
            GTRecipe recipe = recipeCache.get(baseSlot + localSlot);
            CompoundTag slotData = internalSlotData.get(localSlot);
            boolean hasSlotData = slotData != null && !slotData.isEmpty();
            boolean hasRecipe = recipe != null && recipe.id != null;
            if (pattern.isEmpty() || (!hasSlotData && !hasRecipe)) continue;

            CompoundTag entry = new CompoundTag();
            entry.put(PATTERN_KEY, pattern.serializeNBT());
            if (hasSlotData) entry.put(SLOT_KEY, slotData);
            if (hasRecipe) entry.putString(RECIPE_ID_KEY, recipe.id.toString());
            entries.add(entry);
        }
        if (!entries.isEmpty()) root.put(ROOT_KEY, entries);
    }

    void load(CompoundTag root) {
        pendingSlots.clear();
        ListTag entries = root.getList(ROOT_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            ItemStack pattern = ItemStack.of(entry.getCompound(PATTERN_KEY));
            if (pattern.isEmpty()) continue;
            CompoundTag slotData = entry.contains(SLOT_KEY, Tag.TAG_COMPOUND)
                    ? entry.getCompound(SLOT_KEY) : null;
            ResourceLocation recipeId = parseRecipeId(entry.getString(RECIPE_ID_KEY));
            if (slotData != null || recipeId != null) {
                pendingSlots.add(new PendingSlot(pattern, slotData, recipeId));
            }
        }
    }

    void restore(int baseSlot, List<ItemStack> patternStacks,
            int internalSlotCount, BiConsumer<Integer, CompoundTag> restoreSlotData,
            List<GTRecipe> resolvedRecipes, Int2ReferenceMap<GTRecipe> recipeCache,
            Consumer<List<CompoundTag>> refundDiscardedSlots) {
        if (pendingSlots.isEmpty()) return;
        boolean[] used = new boolean[patternStacks.size()];
        List<CompoundTag> discarded = new ArrayList<>();
        for (PendingSlot pending : pendingSlots) {
            int localSlot = findMatchingPattern(patternStacks, pending.pattern(), used);
            if (localSlot < 0 || localSlot >= internalSlotCount) {
                if (pending.slotData() != null) discarded.add(pending.slotData());
                continue;
            }
            used[localSlot] = true;
            if (pending.slotData() != null) {
                restoreSlotData.accept(localSlot, pending.slotData());
            }
            if (pending.recipeId() != null && localSlot < resolvedRecipes.size()) {
                GTRecipe recipe = resolvedRecipes.get(localSlot);
                if (recipe != null && pending.recipeId().equals(recipe.id)) {
                    recipeCache.put(baseSlot + localSlot, recipe);
                }
            }
        }
        pendingSlots.clear();
        if (!discarded.isEmpty()) refundDiscardedSlots.accept(discarded);
    }

    void clearPending() {
        pendingSlots.clear();
    }

    private static int findMatchingPattern(List<ItemStack> patterns, ItemStack expected, boolean[] used) {
        for (int i = 0; i < patterns.size(); i++) {
            if (!used[i] && ItemStack.isSameItemSameTags(patterns.get(i), expected)) return i;
        }
        return -1;
    }

    private static ResourceLocation parseRecipeId(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new ResourceLocation(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record PendingSlot(ItemStack pattern, CompoundTag slotData, ResourceLocation recipeId) {}
}
