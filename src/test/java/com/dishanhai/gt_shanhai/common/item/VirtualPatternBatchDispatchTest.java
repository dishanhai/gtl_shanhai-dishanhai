package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ListCraftingInventory;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.electronwill.nightconfig.core.CommentedConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraftforge.fml.loading.FMLPaths;

import org.cgse.core.GraphRecipe;
import org.cgse.core.CapturedRecipe;
import org.cgse.core.PlanningBudget;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualPatternBatchDispatchTest {

    @BeforeAll
    static void initializeConfigPaths() {
        FMLPaths.loadAbsolutePaths(Path.of("build", "test-game-dir"));
    }

    @Test
    void distributesACondensedBatchAcrossRepeatedSparseInputsInOrder() {
        AEKey first = new TestKey("first");
        AEKey second = new TestKey("second");
        GenericStack[] sparse = {
                new GenericStack(first, 2), new GenericStack(second, 1), new GenericStack(first, 3)
        };
        KeyCounter[] holders = { holder(first, 20), holder(second, 4) };
        List<GenericStack> sent = new ArrayList<>();

        VirtualPatternEncodingHelper.pushSparseInputsIncludingVirtual(sparse, holders,
                (key, amount) -> sent.add(new GenericStack(key, amount)));

        assertEquals(List.of(new GenericStack(first, 8), new GenericStack(second, 4),
                new GenericStack(first, 12)), sent);
        assertEquals(0L, holders[0].get(first));
        assertEquals(0L, holders[1].get(second));
    }

    @Test
    void infersBatchFromTheTotalOfRepeatedSparseInputs() {
        AEKey key = new TestKey("material");
        GenericStack[] sparse = { new GenericStack(key, 2), new GenericStack(key, 3) };
        KeyCounter[] holders = { holder(key, 20) };
        List<GenericStack> sent = new ArrayList<>();

        VirtualPatternEncodingHelper.pushSparseInputsIncludingVirtual(sparse, holders,
                (input, amount) -> sent.add(new GenericStack(input, amount)));

        assertEquals(List.of(new GenericStack(key, 8), new GenericStack(key, 12)), sent);
        assertEquals(0L, holders[0].get(key));
    }

    @Test
    void dispatchKeepsSameKeyPresenceSeparateFromConsumables() throws Exception {
        AEKey material = new TestKey("material");
        GenericStack[] sparse = { new GenericStack(material, 3) };
        IPatternDetails.IInput[] planning = {
                VirtualPatternEncodingHelper.createPlanningInputs(
                        new GenericStack[] { new GenericStack(material, 2) })[0],
                presenceInput(material, 1)
        };
        KeyCounter[] holders = { holder(material, 8), holder(material, 1) };
        List<GenericStack> sent = new ArrayList<>();

        VirtualPatternEncodingHelper.pushSparseInputsIncludingVirtual(sparse, null, holders,
                (key, amount) -> sent.add(new GenericStack(key, amount)), planning);

        assertEquals(List.of(new GenericStack(material, 8)), sent);
        assertEquals(0L, holders[0].get(material));
        assertEquals(1L, holders[1].get(material));
    }

    @Test
    void incompleteBatchLeavesAllRealInputsInTheirHolders() {
        AEKey first = new TestKey("first");
        AEKey second = new TestKey("second");
        GenericStack[] sparse = { new GenericStack(first, 2), new GenericStack(second, 3) };
        KeyCounter[] holders = { holder(first, 8), holder(second, 2) };
        List<GenericStack> sent = new ArrayList<>();

        assertThrows(IllegalArgumentException.class, () ->
                VirtualPatternEncodingHelper.pushSparseInputsIncludingVirtual(sparse, holders,
                        (key, amount) -> sent.add(new GenericStack(key, amount))));

        assertEquals(List.of(), sent);
        assertEquals(8L, holders[0].get(first));
        assertEquals(2L, holders[1].get(second));
    }

    @Test
    void graphCaptureModelsPresenceOnceAndKeepsARealSameKeyOutput() throws Exception {
        AEKey key = new TestKey("catalyst");
        AEKey product = new TestKey("product");
        IPatternDetails.IInput input = presenceInput(key, 2);
        var capture = new CapturedRecipe<>(
                List.of(new CapturedRecipe.Input<>(2, List.of(new CapturedRecipe.Candidate<>(
                        new CapturedRecipe.Amount<>(key, 1),
                        GraphPresenceDispatchBridge.physicalRemainder(input, key,
                                IPatternDetails.IInput::getRemainingKey),
                        true, true)))),
                List.of(new CapturedRecipe.Amount<>(product, 5), new CapturedRecipe.Amount<>(key, 1)),
                true, false);
        var resolved = capture.expand(new PlanningBudget(0, 10_000, () -> false)).next();
        GraphRecipe<AEKey> recipe = new GraphRecipe<>("recipe", "binding", resolved.slots(), resolved.outputs());

        assertEquals(Map.of(product, 5L, key, 3L), recipe.outputs());
        assertEquals(Map.of(product, 5L, key, 1L), recipe.executionOutputs());
        assertEquals(key, input.getRemainingKey(key), "普通树仍得到虚拟自返");
    }

    @Test
    void legacySubmissionPreservesTheActualFuzzyIngredientChosenByTheTree() throws Exception {
        AEKey presence = new TestKey("presence");
        TestKey template = new TestKey("template");
        AEKey chosen = new TestKey("substitute", template);
        KeyCounter used = holder(chosen, 3L);

        ListCraftingInventory cpu = submitLegacy(used, presence, ingredient(1L, new GenericStack(template, 3L)), 1L);

        assertEquals(3L, cpu.list.get(chosen));
        assertEquals(0L, cpu.list.get(presence));
    }

    @Test
    void legacySubmissionUsesFullTemplateUnitsForARealInputSharingThePresenceKey() throws Exception {
        AEKey shared = new TestKey("shared");
        KeyCounter used = holder(shared, 5L);

        ListCraftingInventory cpu = submitLegacy(used, shared, ingredient(1L, new GenericStack(shared, 2L)), 2L);

        assertEquals(4L, cpu.list.get(shared));
        assertTrue(VirtualCraftingPresenceState.hasPresence(cpu, shared, 1L));
    }

    @Test
    void legacySubmissionKeepsAnExactTaggedCatalystSeparateFromAnotherVariant() throws Exception {
        TestKey base = new TestKey("variants");
        AEKey catalyst = new TestKey("catalyst_variant", base);
        AEKey consumed = new TestKey("consumed_variant", base);
        KeyCounter used = holder(catalyst, 1L);
        used.add(consumed, 1L);

        var input = ingredient(1L, new GenericStack(consumed, 1L), consumed::equals);
        ListCraftingInventory cpu = submitLegacy(used, catalyst, input, 1L);

        assertEquals(1L, cpu.list.get(consumed));
        assertEquals(0L, cpu.list.get(catalyst));
    }

    @Test
    void legacySubmissionRechecksThePartialVirtualSeedBeforeTakingRealInputs() throws Exception {
        AEKey presence = new TestKey("presence_seed");
        AEKey real = new TestKey("physical_input");
        KeyCounter used = holder(presence, 1L);
        used.add(real, 1L);
        KeyCounter stock = holder(real, 1L);
        GenericStack output = new GenericStack(new TestKey("output"), 1L);
        var consumer = pattern(output, presenceInput(presence, 2L), ingredient(1L, new GenericStack(real, 1L)));
        var producer = pattern(new GenericStack(presence, 1L));
        var plan = new CraftingPlan(output, 32L, false, false, used, new KeyCounter(), new KeyCounter(),
                Map.of(consumer, 1L, producer, 1L));

        LegacySubmission submission = submitLegacy(plan, stock);

        assertEquals(new GenericStack(presence, 1L), submission.missing());
        assertEquals(1L, stock.get(real));
        assertTrue(submission.inventory().list.isEmpty());
    }

    @Test
    void legacySubmissionRechecksAReusableSeedOutsideTheUsedItemsCounter() throws Exception {
        AEKey presence = new TestKey("external_seed");
        AEKey real = new TestKey("physical_input");
        KeyCounter used = holder(real, 1L);
        KeyCounter stock = holder(real, 1L);

        LegacySubmission submission = submitLegacy(used, stock, presence, 1L,
                ingredient(1L, new GenericStack(real, 1L)), 1L);

        assertEquals(new GenericStack(presence, 1L), submission.missing());
        assertEquals(1L, stock.get(real));
    }

    @Test
    void legacySubmissionCanUsePresenceFromAPlannedProducerOrEmitter() throws Exception {
        AEKey presence = new TestKey("produced_seed");
        AEKey real = new TestKey("physical_input");
        GenericStack output = new GenericStack(new TestKey("output"), 1L);
        var consumer = pattern(output, presenceInput(presence, 1L), ingredient(1L, new GenericStack(real, 1L)));
        var producer = pattern(new GenericStack(presence, 1L));
        KeyCounter used = holder(real, 1L);
        var crafted = new CraftingPlan(output, 32L, false, false, used, new KeyCounter(), new KeyCounter(),
                Map.of(consumer, 1L, producer, 1L));
        var emitted = new CraftingPlan(output, 32L, false, false, used, holder(presence, 1L), new KeyCounter(),
                Map.of(consumer, 1L));

        assertNull(submitLegacy(crafted, holder(real, 1L)).missing());
        assertNull(submitLegacy(emitted, holder(real, 1L)).missing());
    }

    @Test
    void legacySubmissionChecksTheSeedOfAPlannedSelfProducer() throws Exception {
        AEKey presence = new TestKey("self_produced_seed");
        GenericStack seed = new GenericStack(presence, 1L);
        var producer = pattern(seed, presenceInput(presence, 1L));
        var unfunded = new CraftingPlan(seed, 32L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of(producer, 1L));

        GenericStack output = new GenericStack(new TestKey("output"), 1L);
        var consumer = pattern(output, presenceInput(presence, 2L));
        var emitted = new CraftingPlan(output, 32L, false, false,
                new KeyCounter(), holder(presence, 1L), new KeyCounter(), Map.of(producer, 1L, consumer, 1L));

        assertEquals(seed, submitLegacy(unfunded, new KeyCounter()).missing());
        assertNull(submitLegacy(emitted, new KeyCounter()).missing());
    }

    @Test
    void legacySubmissionChecksRealAvailabilityBeforeTakingPartialStock() throws Exception {
        AEKey presence = new TestKey("present_seed");
        AEKey real = new TestKey("physical_input");
        KeyCounter used = holder(real, 2L);
        KeyCounter stock = holder(real, 1L);
        stock.add(presence, 1L);

        LegacySubmission submission = submitLegacy(used, stock, presence, 1L,
                ingredient(2L, new GenericStack(real, 1L)), 1L);

        assertEquals(new GenericStack(real, 1L), submission.missing());
        assertEquals(1L, stock.get(real));
        assertTrue(submission.inventory().list.isEmpty());
    }

    private static ListCraftingInventory submitLegacy(KeyCounter used, AEKey presence, IPatternDetails.IInput input,
            long times) throws Exception {
        KeyCounter stock = new KeyCounter();
        stock.addAll(used);
        stock.add(presence, 1L);
        LegacySubmission submission = submitLegacy(used, stock, presence, 1L, input, times);
        assertNull(submission.missing());
        return submission.inventory();
    }

    private static LegacySubmission submitLegacy(KeyCounter used, KeyCounter stock, AEKey presence,
            long presenceAmount, IPatternDetails.IInput input, long times) throws Exception {
        GenericStack output = new GenericStack(new TestKey("output"), times);
        IPatternDetails details = pattern(output, presenceInput(presence, presenceAmount), input);
        var plan = new CraftingPlan(output, 32L, false, false, used, new KeyCounter(), new KeyCounter(),
                Map.of(details, times));
        return submitLegacy(plan, stock);
    }

    private static IPatternDetails pattern(GenericStack output, IPatternDetails.IInput... inputs) {
        return new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return null; }
            @Override public IInput[] getInputs() { return inputs; }
            @Override public GenericStack[] getOutputs() { return new GenericStack[] { output }; }
        };
    }

    private static LegacySubmission submitLegacy(CraftingPlan plan, KeyCounter stock) {
        MEStorage storage = new MEStorage() {
            @Override public Component getDescription() { return Component.empty(); }
            @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                long extracted = Math.min(amount, stock.get(key));
                if (mode == Actionable.MODULATE) stock.remove(key, extracted);
                return extracted;
            }
        };
        IStorageService storageService = proxyFor(IStorageService.class, "getInventory", storage);
        IGrid grid = proxyFor(IGrid.class, "getStorageService", storageService);
        ListCraftingInventory inventory = new ListCraftingInventory(ignored -> {});
        DShanhaiConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory());
        try {
            GenericStack missing = VirtualCraftingInitialItemExtractor.extract(plan, grid, inventory, IActionSource.empty());
            return new LegacySubmission(missing, inventory);
        } finally {
            DShanhaiConfig.COMMON_SPEC.setConfig(null);
        }
    }

    private record LegacySubmission(GenericStack missing, ListCraftingInventory inventory) {}

    private static IPatternDetails.IInput ingredient(long multiplier, GenericStack template) {
        return ingredient(multiplier, template, key -> true);
    }

    private static IPatternDetails.IInput ingredient(long multiplier, GenericStack template,
            java.util.function.Predicate<AEKey> valid) {
        return new IPatternDetails.IInput() {
            @Override public GenericStack[] getPossibleInputs() { return new GenericStack[] { template }; }
            @Override public long getMultiplier() { return multiplier; }
            @Override public boolean isValid(AEKey key, Level level) { return valid.test(key); }
            @Override public AEKey getRemainingKey(AEKey key) { return null; }
        };
    }

    private static <T> T proxyFor(Class<T> type, String methodName, Object result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    if (method.getName().equals(methodName)) return result;
                    if (type == IGrid.class && method.getName().equals("getPivot")) return null;
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    @Test
    void reducingRestoredPresenceKeepsRealStock() {
        AEKey key = new TestKey("same_key");
        Object2LongOpenHashMap<AEKey> inventory = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<AEKey> mirror = new Object2LongOpenHashMap<>();
        inventory.put(key, 13L);
        mirror.put(key, 5L);
        VirtualPatternBufferSlotState.addVirtualTarget(inventory, key, 5L);

        VirtualPatternBufferSlotState.registerVirtualTarget(inventory, key, 2L, mirror);

        assertEquals(10L, inventory.getLong(key));
        assertEquals(2L, mirror.getLong(key));
        VirtualPatternBufferSlotState.stripVirtualTargets(inventory);
        assertEquals(8L, inventory.getLong(key));
    }

    @Test
    void ordinaryProcessingInputKeepsNativeIdentityAndHasNoImplicitRemainder() {
        AEKey key = new TestKey("tool");
        IPatternDetails.IInput input = VirtualPatternEncodingHelper.createPlanningInputs(
                new GenericStack[] { new GenericStack(key, 3) }, null)[0];

        assertEquals("appeng.crafting.pattern.AEProcessingPattern$Input", input.getClass().getName());
        assertEquals(3L, input.getMultiplier());
        assertNull(input.getRemainingKey(key));
    }

    @Test
    void realConsumptionNeverSeesOrRemovesTheVirtualPortion() {
        AEKey key = new TestKey("same_key");
        Object2LongOpenHashMap<AEKey> inventory = new Object2LongOpenHashMap<>();
        inventory.put(key, 13L);
        VirtualPatternBufferSlotState.addVirtualTarget(inventory, key, 5L);

        VirtualPatternBufferSlotState.withPhysicalStock(inventory, () -> {
            assertEquals(8L, inventory.getLong(key));
            inventory.addTo(key, -3L);
            return true;
        });

        assertEquals(10L, inventory.getLong(key));
        assertEquals(5L, VirtualPatternBufferSlotState.getVirtualTargets(inventory).getLong(key));
    }

    @Test
    void missingSupplyReportsTheShortfallWithoutRequiringOtherPresenceSources() {
        AEKey supplyOnly = new TestKey("supply_only");
        Object2LongOpenHashMap<AEKey> requirements = new Object2LongOpenHashMap<>();
        requirements.put(supplyOnly, 5L);

        assertEquals(new GenericStack(supplyOnly, 3L),
                VirtualProviderSoftLocks.missingSupplyRequirement(requirements, key -> 2L));
        assertNull(VirtualProviderSoftLocks.missingSupplyRequirement(requirements, key -> 5L));
        assertNull(VirtualProviderSoftLocks.missingSupplyRequirement(null, key -> 0L));
    }

    @Test
    void separateIngredientsShareOnePhysicalStockBudgetWithoutMutatingInventory() {
        AEKey key = new TestKey("same_key");
        Object2LongOpenHashMap<AEKey> inventory = new Object2LongOpenHashMap<>();
        inventory.put(key, 9L);
        VirtualPatternBufferSlotState.addVirtualTarget(inventory, key, 5L);
        Object2LongOpenHashMap<String> demands = new Object2LongOpenHashMap<>();
        demands.put("tag_match", 3L);
        demands.put("exact_match", 3L);

        assertFalse(VirtualPatternBufferSlotState.canAllocatePhysicalStock(inventory, demands,
                (available, ingredient, needed) -> available.getLong(key) >= needed ? key : null));
        assertTrue(VirtualPatternBufferSlotState.canAllocatePhysicalStock(inventory, demands,
                (available, ingredient, needed) -> available.getLong(key) + 2L >= needed ? key : null));
        assertEquals(9L, inventory.getLong(key));
    }


    private static IPatternDetails.IInput presenceInput(AEKey key, long amount) throws Exception {
        var type = Class.forName(VirtualPatternEncodingHelper.class.getName() + "$PresenceInput");
        var constructor = type.getDeclaredConstructor(AEKey.class, long.class);
        constructor.setAccessible(true);
        return (IPatternDetails.IInput) constructor.newInstance(key, amount);
    }

    private static KeyCounter holder(AEKey key, long amount) {
        KeyCounter holder = new KeyCounter();
        holder.add(key, amount);
        return holder;
    }

    private static final class TestKey extends AEKey {
        private final String id;
        private final TestKey primary;

        private TestKey(String id) {
            this(id, null);
        }

        private TestKey(String id, TestKey primary) {
            this.id = id;
            this.primary = primary == null ? this : primary;
        }

        @Override public AEKeyType getType() { return null; }
        @Override public AEKey dropSecondary() { return primary; }
        @Override public CompoundTag toTag() { return new CompoundTag(); }
        @Override public Object getPrimaryKey() { return primary; }
        @Override public ResourceLocation getId() { return new ResourceLocation("gt_shanhai", id); }
        @Override public void writeToPacket(FriendlyByteBuf output) {}
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
    }
}
