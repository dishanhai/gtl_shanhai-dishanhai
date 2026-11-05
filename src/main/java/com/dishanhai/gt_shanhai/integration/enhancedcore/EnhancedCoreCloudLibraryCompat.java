package com.dishanhai.gt_shanhai.integration.enhancedcore;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gtl.enhancedcore.common.cloud.CloudPatternBufferMachine;
import com.gtl.enhancedcore.common.cloud.CloudPatternMatching;
import com.gtl.enhancedcore.common.cloud.store.CloudPatternStore;
import com.gtl.enhancedcore.common.recipe.iv.IvBuffers;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 山海：母原件仍由 Enhancedcore 的交易與租約持有，子樣板只作執行描述。 */
public final class EnhancedCoreCloudLibraryCompat {
    public static final int DISPLAY_SCHEMA = 4;
    public static final String CHILD_RECIPE = "gt_shanhai_cloud_child_recipe";
    private static final ThreadLocal<String> DISPATCH_RECIPE = new ThreadLocal<>();
    // Probe is a value record: identity semantics prevent equal-IO recipes sharing a pin.
    private static final EnhancedCoreCloudLibraryRecipePins<CloudPatternMatching.Probe> PROBE_RECIPES =
            new EnhancedCoreCloudLibraryRecipePins<>();
    private static final Set<CloudPatternStore.Entry> PREPARED_DISPLAYS = Collections.newSetFromMap(new WeakHashMap<>());
    private static long recipeRevision;

    private EnhancedCoreCloudLibraryCompat() {}

    public static boolean mother(ItemStack stack) {
        return EnhancedCoreCloudCompat.isAvailable() && SelectedRecipePattern.isPattern(stack);
    }

    /** Validator-only decode. This is never registered as a global AE decoder. */
    public static IPatternDetails validationDetails(ItemStack stack, Level level) {
        if (!mother(stack)) return PatternDetailsHelper.decodePattern(stack, level);
        if (stack.getCount() != 1 || SelectedRecipePattern.read(stack.getTag()) == null) return null;
        var children = SelectedRecipePattern.expand(stack, level);
        return children.isEmpty() ? null : children.getFirst().pattern();
    }

    public static Set<ResourceLocation> types(ItemStack original, Level level) {
        var types = new TreeSet<ResourceLocation>();
        var contents = SelectedRecipePattern.read(original.getTag());
        if (contents == null || level == null) return types;
        for (String id : contents.recipes()) {
            var key = ResourceLocation.tryParse(id);
            if (key != null && level.getRecipeManager().byKey(key).orElse(null) instanceof GTRecipe recipe
                    && recipe.recipeType != null && recipe.recipeType.registryName != null)
                types.add(recipe.recipeType.registryName);
        }
        return types;
    }

    /** Build a fresh row even for persisted displaySchema=3; one row covers every child. */
    public static CompoundTag metadata(CloudPatternStore.Entry entry, ServerLevel level) {
        CompoundTag cached = entry.displayMetadata();
        // Persisted schema alone cannot prove this server's recipes are unchanged since the last load.
        if (PREPARED_DISPLAYS.contains(entry) && cached.getInt("displaySchema") == DISPLAY_SCHEMA)
            return registryIds(cached);
        ItemStack original = entry.original();
        var names = SelectedRecipePattern.searchNames(original, level);
        Component name = names.outputs().isEmpty() ? original.getHoverName() : Component.literal(String.join(", ", names.outputs()));
        CompoundTag row = new CompoundTag();
        row.putUUID("entry", entry.id()); row.putString("id", entry.id().toString());
        row.putString("name", name.getString()); row.putString("recipe", name.getString());
        row.putString("nameJson", Component.Serializer.toJson(name)); row.putString("recipeJson", Component.Serializer.toJson(name));
        row.put("icon", original.save(new CompoundTag()));
        var inputs = new LinkedHashSet<AEKey>(); var outputs = new LinkedHashSet<AEKey>();
        for (var child : SelectedRecipePattern.expand(original, level)) {
            for (var input : child.pattern().getInputs()) for (var possible : input.getPossibleInputs()) inputs.add(possible.what());
            for (var output : child.pattern().getOutputs()) outputs.add(output.what());
        }
        if (!outputs.isEmpty()) row.put("iconKey", outputs.iterator().next().toTagGeneric());
        putKeys(row, "inputItemKeys", inputs, true); putKeys(row, "inputFluidKeys", inputs, false);
        putKeys(row, "outputItemKeys", outputs, true); putKeys(row, "outputFluidKeys", outputs, false);
        var all = new LinkedHashSet<>(inputs); all.addAll(outputs);
        putKeys(row, "itemKeys", all, true); putKeys(row, "fluidKeys", all, false);
        row.putIntArray("itemIds", all.stream().filter(AEItemKey.class::isInstance).map(AEItemKey.class::cast)
                .mapToInt(key -> BuiltInRegistries.ITEM.getId(key.getItem())).distinct().toArray());
        row.putIntArray("fluidIds", all.stream().filter(AEFluidKey.class::isInstance).map(AEFluidKey.class::cast)
                .mapToInt(key -> BuiltInRegistries.FLUID.getId(key.getFluid())).distinct().toArray());
        ListTag typeTags = new ListTag(); var typeNames = new ArrayList<String>();
        for (var type : types(original, level)) {
            typeTags.add(StringTag.valueOf(type.toString()));
            typeNames.add(org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata.recipeTypeName(type).getString());
        }
        row.put("types", typeTags); row.putString("type", typeTags.isEmpty() ? "" : typeTags.getString(0));
        row.putString("typeName", typeNames.isEmpty() ? "" : typeNames.getFirst());
        row.putString("typeNames", String.join(", ", typeNames));
        org.gtlcore.gtlcore.integration.ae2.pattern.PatternEncoderMetadata.readEncoder(original).ifPresent(author -> {
            row.putString("author", author.displayName()); row.putString("authorId", author.id().toString());
        });
        var config = entry.configuration(); boolean circuit = config.contains("circuit", Tag.TAG_INT);
        row.putBoolean("hasCircuit", circuit); if (circuit) row.putInt("circuit", config.getInt("circuit"));
        row.putString("inputNames", String.join(" ", names.inputs()));
        row.putString("outputNames", String.join(" ", names.outputs()));
        ListTag recipeIds = new ListTag();
        var contents = SelectedRecipePattern.read(original.getTag());
        if (contents != null) contents.recipes().forEach(id -> recipeIds.add(StringTag.valueOf(id)));
        row.put("recipeIds", recipeIds);
        row.putInt("displaySchema", DISPLAY_SCHEMA);
        CompoundTag persisted = row.copy(); persisted.remove("itemIds"); persisted.remove("fluidIds");
        if (!persisted.equals(entry.displayMetadata())) entry.cacheDisplayMetadata(persisted);
        PREPARED_DISPLAYS.add(entry);
        return row;
    }

    private static CompoundTag registryIds(CompoundTag row) {
        row.putIntArray("itemIds", row.getList("itemKeys", Tag.TAG_STRING).stream().map(Tag::getAsString)
                .map(ResourceLocation::tryParse).filter(Objects::nonNull).filter(BuiltInRegistries.ITEM::containsKey)
                .mapToInt(id -> BuiltInRegistries.ITEM.getId(BuiltInRegistries.ITEM.get(id))).toArray());
        row.putIntArray("fluidIds", row.getList("fluidKeys", Tag.TAG_STRING).stream().map(Tag::getAsString)
                .map(ResourceLocation::tryParse).filter(Objects::nonNull).filter(BuiltInRegistries.FLUID::containsKey)
                .mapToInt(id -> BuiltInRegistries.FLUID.getId(BuiltInRegistries.FLUID.get(id))).toArray());
        return row;
    }

    private static void putKeys(CompoundTag row, String name, Set<AEKey> values, boolean items) {
        ListTag tags = new ListTag(); var ids = new TreeSet<String>();
        for (AEKey key : values) {
            if (items && key instanceof AEItemKey item) ids.add(BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
            if (!items && key instanceof AEFluidKey fluid) ids.add(BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString());
        }
        ids.forEach(id -> tags.add(StringTag.valueOf(id))); row.put(name, tags);
    }

    public static String dispatchRecipe() { return DISPATCH_RECIPE.get(); }
    public static String enterDispatch(IPatternDetails pattern) {
        String previous = DISPATCH_RECIPE.get(); var tag = pattern.getDefinition().getTag();
        String id = tag == null ? "" : tag.getCompound(IvBuffers.ROUTE_KEY).getString(CHILD_RECIPE);
        if (id.isEmpty()) DISPATCH_RECIPE.remove(); else DISPATCH_RECIPE.set(id);
        return previous;
    }
    public static void leaveDispatch(String previous) {
        if (previous == null) DISPATCH_RECIPE.remove(); else DISPATCH_RECIPE.set(previous);
    }

    public static IPatternDetails routed(CloudPatternBufferMachine buffer, int slot, ItemStack original,
                                         SelectedRecipePattern.Expanded child) {
        var processed = buffer.cloudRealPattern(slot, child.stack());
        if (processed == null) return null;
        ItemStack definition = processed.getDefinition().toStack(); var tag = definition.getOrCreateTag();
        var route = tag.getCompound(IvBuffers.ROUTE_KEY);
        route.put("original", original.save(new CompoundTag()));
        route.putString(CHILD_RECIPE, child.recipe().id.toString());
        tag.put(IvBuffers.ROUTE_KEY, route); tag.putString(SelectedRecipePattern.RECIPE_ID, child.recipe().id.toString());
        return PatternDetailsHelper.decodePattern(definition, buffer.getLevel());
    }

    public static List<IPatternDetails> publications(CloudPatternBufferMachine buffer, Set<UUID> validated) {
        var result = new ArrayList<IPatternDetails>(); var node = buffer.cloudNode();
        if (node == null) return result;
        WorkableElectricMultiblockMachine machine = IvBuffers.controller(buffer);
        if (machine == null) return result;
        for (var lease : node.leases()) {
            var entry = buffer.entry(lease.stableSlot());
            if (entry == null || !validated.contains(entry.id()) || !mother(entry.original())) continue;
            for (var child : SelectedRecipePattern.expand(entry.original(), buffer.getLevel())) {
                if (!Objects.equals(buffer.cloudMode(), child.recipe().recipeType.registryName)) continue;
                var pattern = routed(buffer, lease.stableSlot(), entry.original(), child);
                if (pattern == null) continue;
                var probe = CloudPatternMatching.probe(pattern, virtual(buffer, entry, buffer.getCircuitForRecipe(lease.stableSlot())));
                if (!CloudPatternMatching.matches(child.recipe(), machine, probe)) continue;
                if (pattern != null) result.add(pattern);
            }
        }
        return result;
    }

    public static Map<AEKey, Long> virtual(CloudPatternBufferMachine buffer, CloudPatternStore.Entry entry, ItemStack circuit) {
        var virtual = new LinkedHashMap<>(CloudPatternMatching.virtual(entry.configuration(), circuit));
        buffer.cloudCatalysts().forEach((key, amount) -> virtual.merge(key, amount,
                (left, right) -> left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right));
        com.gtl.enhancedcore.common.cloud.CloudLibraryService.catalysts(entry).forEach((key, amount) -> virtual.merge(key, amount,
                (left, right) -> left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right));
        return virtual;
    }

    public static CloudPatternMatching.Probe probe(CloudPatternBufferMachine buffer, CloudPatternStore.Entry entry, boolean keepByProduct) {
        var machine = IvBuffers.controller(buffer); if (machine == null) return null;
        for (var child : SelectedRecipePattern.expand(entry.original(), buffer.getLevel())) {
            if (!Objects.equals(buffer.cloudMode(), child.recipe().recipeType.registryName)) continue;
            var probe = childProbe(buffer, entry, child, keepByProduct);
            if (CloudPatternMatching.matches(child.recipe(), machine, probe)) {
                PROBE_RECIPES.put(probe, child.recipe().id.toString()); return probe;
            }
        }
        return null;
    }

    public static CloudPatternMatching.Probe childProbe(CloudPatternBufferMachine buffer, CloudPatternStore.Entry entry,
                                                       SelectedRecipePattern.Expanded child, boolean keepByProduct) {
        ItemStack[] circuit = {ItemStack.EMPTY};
        var processed = buffer.getRealPatternHelper().processPatternWithCircuit(child.stack(),
                number -> circuit[0] = IntCircuitBehaviour.stack(number), buffer.getLevel(), keepByProduct);
        if (entry.configuration().contains("circuit", Tag.TAG_INT))
            circuit[0] = IntCircuitBehaviour.stack(entry.configuration().getInt("circuit"));
        return CloudPatternMatching.probe(processed, virtual(buffer, entry, circuit[0]));
    }

    public static boolean matchesPinned(GTRecipe recipe, CloudPatternMatching.Probe probe) {
        String id = PROBE_RECIPES.get(probe); return id == null || recipe.id.toString().equals(id);
    }

    public static long recipeRevision() { return recipeRevision; }
    public static void clearProbes() { PROBE_RECIPES.clear(); PREPARED_DISPLAYS.clear(); recipeRevision++; }
}
