package com.dishanhai.gt_shanhai.common.item.generator;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/** 设置写在手里的工具上，不写在单例行为里。 */
public final class PatternGeneratorSettings {
    public static final String TAG = "ShanhaiPatternSettings";
    public static final int FILTER_SLOTS = 7;

    public ItemStack machine = ItemStack.EMPTY;
    public String recipeType = "";
    public int circuit = PatternGeneratorFilter.ANY_CIRCUIT;
    public String inputWhite = "";
    public String outputWhite = "";
    public final GenericStack[] inputInclude = new GenericStack[FILTER_SLOTS];
    public final GenericStack[] outputInclude = new GenericStack[FILTER_SLOTS];
    public final String[] inputTag = emptyTags();
    public final String[] outputTag = emptyTags();
    public final String[] inputBlackTag = emptyTags();
    public final String[] outputBlackTag = emptyTags();
    public int previewColumns = PatternGeneratorLayout.DEFAULT_COLUMNS;
    public final GenericStack[] inputBlack = new GenericStack[FILTER_SLOTS];
    public final GenericStack[] outputBlack = new GenericStack[FILTER_SLOTS];
    public final BitSet inputNamedItems = new BitSet();
    public final BitSet inputNamedFluids = new BitSet();
    public final BitSet outputNamedItems = new BitSet();
    public final BitSet outputNamedFluids = new BitSet();
    public int scale = 1;
    public int outputScale = 1;
    public int minTier = PatternGeneratorFilter.ANY_TIER;
    public int maxTier = PatternGeneratorFilter.ANY_TIER;
    public int sort = PatternGeneratorFilter.SORT_ID;
    public boolean skipOwned;
    public String recipeQuery = "";
    public final List<String> pending = new ArrayList<>();
    public final List<String> transferRecipes = new ArrayList<>();
    public CompoundTag transferConfiguration = new CompoundTag();
    public UUID generatorId;
    public boolean transferArmed;

    public static PatternGeneratorSettings load(ItemStack tool) {
        var result = new PatternGeneratorSettings();
        CompoundTag root = tool.getTag();
        if (root != null && root.contains(TAG, Tag.TAG_COMPOUND)) result.read(root.getCompound(TAG));
        return result;
    }

    public void read(CompoundTag tag) {
        machine = ItemStack.of(tag.getCompound("machine"));
        if (!machine.isEmpty()) machine.setCount(1);
        recipeType = tag.getString("recipeType");
        circuit = tag.contains("circuit") ? tag.getInt("circuit") : PatternGeneratorFilter.ANY_CIRCUIT;
        circuit = Math.max(PatternGeneratorFilter.ANY_CIRCUIT, Math.min(32, circuit));
        inputWhite = limit(tag.getString("inputsWhite"));
        outputWhite = limit(tag.getString("outputsWhite"));
        readSlots(tag.getList("inputIncludes", Tag.TAG_COMPOUND), inputInclude);
        readSlots(tag.getList("outputIncludes", Tag.TAG_COMPOUND), outputInclude);
        readTags(tag, "inputTags", inputInclude, inputTag);
        readTags(tag, "outputTags", outputInclude, outputTag);
        previewColumns = tag.contains("previewColumns")
                ? PatternGeneratorLayout.columns(tag.getInt("previewColumns"))
                : PatternGeneratorLayout.DEFAULT_COLUMNS;
        readSlots(tag.getList("inputGhosts", Tag.TAG_COMPOUND), inputBlack);
        readSlots(tag.getList("outputGhosts", Tag.TAG_COMPOUND), outputBlack);
        readTags(tag, "inputBlackTags", inputBlack, inputBlackTag);
        readTags(tag, "outputBlackTags", outputBlack, outputBlackTag);
        scale = PatternGeneratorScale.clamp(tag.contains("scale") ? tag.getInt("scale") : 1);
        outputScale = PatternGeneratorScale.clamp(tag.contains("outputScale") ? tag.getInt("outputScale") : 1);
        minTier = tier(tag.contains("minTier") ? tag.getInt("minTier") : PatternGeneratorFilter.ANY_TIER);
        maxTier = tier(tag.contains("maxTier") ? tag.getInt("maxTier") : PatternGeneratorFilter.ANY_TIER);
        sort = PatternGeneratorFilter.sortMode(tag.getInt("sort"));
        skipOwned = tag.getBoolean("skipOwned");
        recipeQuery = limit(tag.getString("recipeQuery"));
        transferRecipes.clear();
        for (Tag entry : tag.getList("transferRecipes", Tag.TAG_STRING)) {
            if (transferRecipes.size() >= PatternGeneratorPresetFiles.MAX_RECIPES) break;
            if (ResourceLocation.tryParse(entry.getAsString()) != null) transferRecipes.add(entry.getAsString());
        }
        transferConfiguration = tag.getCompound("transferConfiguration").copy();
        pending.clear();
        for (Tag entry : tag.getList("pending", Tag.TAG_STRING)) pending.add(entry.getAsString());
        generatorId = tag.hasUUID("generatorId") ? tag.getUUID("generatorId") : null;
        transferArmed = tag.getBoolean("transferArmed");
    }

    public CompoundTag write() {
        var tag = new CompoundTag();
        tag.putInt("schema", 1);
        tag.put("machine", machine.save(new CompoundTag()));
        tag.putString("recipeType", recipeType);
        tag.putInt("circuit", circuit);
        tag.putString("inputsWhite", inputWhite);
        tag.putString("outputsWhite", outputWhite);
        tag.put("inputIncludes", writeSlots(inputInclude));
        tag.put("outputIncludes", writeSlots(outputInclude));
        tag.put("inputTags", writeTags(inputTag));
        tag.put("outputTags", writeTags(outputTag));
        tag.put("inputBlackTags", writeTags(inputBlackTag));
        tag.put("outputBlackTags", writeTags(outputBlackTag));
        tag.putInt("previewColumns", previewColumns);
        tag.put("inputGhosts", writeSlots(inputBlack));
        tag.put("outputGhosts", writeSlots(outputBlack));
        tag.putInt("scale", scale);
        tag.putInt("outputScale", outputScale);
        tag.putInt("minTier", minTier);
        tag.putInt("maxTier", maxTier);
        tag.putInt("sort", sort);
        tag.putBoolean("skipOwned", skipOwned);
        tag.putString("recipeQuery", recipeQuery);
        var transfers = new ListTag();
        transferRecipes.forEach(id -> transfers.add(StringTag.valueOf(id)));
        tag.put("transferRecipes", transfers);
        if (!transferRecipes.isEmpty()) tag.put("transferConfiguration", transferConfiguration.copy());
        var queue = new ListTag();
        pending.forEach(id -> queue.add(StringTag.valueOf(id)));
        tag.put("pending", queue);
        if (generatorId != null) tag.putUUID("generatorId", generatorId);
        tag.putBoolean("transferArmed", transferArmed);
        return tag;
    }

    public int filterCount() {
        int count = (inputWhite.isBlank() ? 0 : 1) + (outputWhite.isBlank() ? 0 : 1);
        for (GenericStack stack : inputInclude) if (stack != null) count++;
        for (GenericStack stack : outputInclude) if (stack != null) count++;
        for (GenericStack stack : inputBlack) if (stack != null) count++;
        for (GenericStack stack : outputBlack) if (stack != null) count++;
        return count;
    }

    public boolean extensionsActive() {
        return scale != 1 || outputScale != 1 || minTier >= 0 || maxTier >= 0 || sort != 0
                || skipOwned || !recipeQuery.isBlank();
    }

    public void clearFilters() {
        inputWhite = outputWhite = "";
        Arrays.fill(inputInclude, null);
        Arrays.fill(outputInclude, null);
        for (String[] tags : List.of(inputTag, outputTag, inputBlackTag, outputBlackTag)) Arrays.fill(tags, "");
        Arrays.fill(inputBlack, null);
        Arrays.fill(outputBlack, null);
        inputNamedItems.clear();
        inputNamedFluids.clear();
        outputNamedItems.clear();
        outputNamedFluids.clear();
    }

    public void clearExtensions() {
        scale = 1;
        outputScale = 1;
        minTier = PatternGeneratorFilter.ANY_TIER;
        maxTier = PatternGeneratorFilter.ANY_TIER;
        sort = PatternGeneratorFilter.SORT_ID;
        skipOwned = false;
        recipeQuery = "";
    }

    public GenericStack[] filterSlots(boolean input, boolean exclude) {
        return exclude ? (input ? inputBlack : outputBlack) : (input ? inputInclude : outputInclude);
    }

    public String[] filterTags(boolean input, boolean exclude) {
        return exclude ? (input ? inputBlackTag : outputBlackTag) : (input ? inputTag : outputTag);
    }

    /** 包含组命中任意一格即可；排除组命中任意一格即拒绝。 */
    public boolean allowsGhosts(List<PatternGeneratorFilter.Material> materials, boolean input) {
        GenericStack[] included = filterSlots(input, false);
        GenericStack[] excluded = filterSlots(input, true);
        String[] includeTags = filterTags(input, false);
        String[] excludeTags = filterTags(input, true);
        boolean unrestricted = true;
        boolean matches = false;
        for (int i = 0; i < FILTER_SLOTS; i++) {
            if (excluded[i] != null && includes(materials, excluded[i], excludeTags[i])) return false;
            if (included[i] != null) {
                unrestricted = false;
                matches |= includes(materials, included[i], includeTags[i]);
            }
        }
        return unrestricted || matches;
    }

    public CompoundTag configuration() {
        CompoundTag tag = write();
        tag.remove("pending");
        tag.remove("generatorId");
        tag.remove("transferArmed");
        tag.remove("transferRecipes");
        tag.remove("transferConfiguration");
        return tag;
    }

    public void applyConfiguration(CompoundTag config) {
        var safe = config.copy();
        safe.remove("pending");
        safe.remove("generatorId");
        safe.remove("transferArmed");
        safe.remove("transferRecipes");
        safe.remove("transferConfiguration");
        UUID identity = generatorId;
        read(safe);
        generatorId = identity;
        transferArmed = false;
        pending.clear();
        transferRecipes.clear();
        transferConfiguration = new CompoundTag();
    }

    public static String limit(String value) {
        return value == null ? "" : value.substring(0, Math.min(256, value.length()));
    }

    public static int tier(int value) {
        if (value < 0) return PatternGeneratorFilter.ANY_TIER;
        return Math.min(14, value);
    }

    public static List<String> tags(GenericStack ghost) {
        if (ghost == null) return List.of();
        if (ghost.what() instanceof AEItemKey item) return item.getItem().builtInRegistryHolder().tags()
                .map(key -> key.location().toString()).sorted().toList();
        if (ghost.what() instanceof AEFluidKey fluid) return fluid.getFluid().builtInRegistryHolder().tags()
                .map(key -> key.location().toString()).sorted().toList();
        return List.of();
    }

    public static String validTag(GenericStack ghost, String tag) {
        return !tag.isEmpty() && tags(ghost).contains(tag) ? tag : "";
    }

    public static boolean includes(List<PatternGeneratorFilter.Material> materials, GenericStack ghost, String tag) {
        if (ghost == null) return true;
        boolean fluid = ghost.what() instanceof AEFluidKey;
        ResourceLocation tagId = ResourceLocation.tryParse(tag);
        return materials.stream().anyMatch(material -> {
            if (material.fluid() != fluid) return false;
            if (tagId == null) return material.id().equals(ghost.what().getId().toString());
            ResourceLocation id = ResourceLocation.tryParse(material.id());
            if (id == null) return false;
            return fluid
                    ? BuiltInRegistries.FLUID.get(id).builtInRegistryHolder().is(TagKey.create(Registries.FLUID, tagId))
                    : BuiltInRegistries.ITEM.get(id).builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tagId));
        });
    }

    private static String[] emptyTags() {
        String[] result = new String[FILTER_SLOTS];
        Arrays.fill(result, "");
        return result;
    }

    private static ListTag writeTags(String[] tags) {
        var result = new ListTag();
        for (String tag : tags) result.add(StringTag.valueOf(tag));
        return result;
    }

    private static void readTags(CompoundTag tag, String key, GenericStack[] ghosts, String[] target) {
        Arrays.fill(target, "");
        ListTag values = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < Math.min(values.size(), FILTER_SLOTS); i++)
            target[i] = validTag(ghosts[i], limit(values.getString(i)));
    }

    private static GenericStack readGhost(CompoundTag tag) {
        GenericStack value = GenericStack.readTag(tag);
        return value != null && (value.what() instanceof AEItemKey || value.what() instanceof AEFluidKey)
                ? new GenericStack(value.what(), 1) : null;
    }

    private static ListTag writeSlots(GenericStack[] slots) {
        var list = new ListTag();
        for (GenericStack slot : slots) list.add(slot == null ? new CompoundTag() : GenericStack.writeTag(slot));
        return list;
    }

    private static void readSlots(ListTag tags, GenericStack[] slots) {
        Arrays.fill(slots, null);
        for (int i = 0; i < Math.min(tags.size(), slots.length); i++) {
            GenericStack stack = readGhost(tags.getCompound(i));
            if (stack != null) slots[i] = new GenericStack(stack.what(), 1);
        }
    }
}
