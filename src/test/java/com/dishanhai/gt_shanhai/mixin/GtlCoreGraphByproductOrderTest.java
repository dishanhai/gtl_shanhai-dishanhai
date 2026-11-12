package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;
import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.cgse.core.GraphCompiler;
import org.cgse.core.GraphJobRuntime;
import org.cgse.core.GraphPlan;
import org.cgse.core.GraphPlanner;
import org.cgse.core.GraphRecipe;
import org.cgse.core.PlanningBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GtlCoreGraphByproductOrderTest {

    private static final Path MIXINS = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin");

    private static boolean registriesUnavailable;

    private static void requireVanillaRegistries() {
        assumeTrue(!registriesUnavailable, "Minecraft registries failed to initialize earlier in this test worker");
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (NoClassDefFoundError error) {
            // A test in this worker attempted to initialize ItemStack before bootstrapping Minecraft.
            if (!error.getMessage().contains("net.minecraft.core.registries.BuiltInRegistries")
                    || error.getCause() == null || !error.getCause().getMessage().contains("Not bootstrapped")) {
                throw error;
            }
            registriesUnavailable = true;
            assumeTrue(false, "Minecraft registries failed to initialize earlier in this test worker");
        } catch (ExceptionInInitializerError error) {
            // The plain Gradle test worker has no Forge EventBus transformer.
            // Vanilla registration has already completed when NetworkHooks is initialized.
            if (!(error.getCause() instanceof RuntimeException listenerFailure)
                    || !(listenerFailure.getCause() instanceof NoSuchMethodException missingConstructor)
                    || !missingConstructor.getMessage().equals("net.minecraftforge.network.NetworkEvent.<init>()")) {
                throw error;
            }
        }
    }

    @Test
    void graphUsesMarkedSourcesForExactFuzzyAndCandidateDiscovery() throws Exception {
        var config = Files.readString(Path.of("src/main/resources/gt_shanhai.gtlcore.mixin.json"));
        assertTrue(config.contains("\"GtlCoreGraphByproductOrderMixin\""),
                "GRAPH discards the AE2 registration for explicitly orderable secondary outputs");
        String mixin = Files.readString(MIXINS.resolve("GtlCoreGraphByproductOrderMixin.java"));
        assertTrue(mixin.contains("primarySources"));
        assertTrue(mixin.contains("fuzzySources"));
        assertTrue(mixin.contains("candidates"));
    }

    @Test
    void bothCircuitReencodingPathsKeepOrderAndRecipeMetadata() throws Exception {
        String mixin = Files.readString(MIXINS.resolve("MEBufferPatternByproductMixin.java"));
        assertTrue(mixin.contains("rebuildPatternCircuit"),
                "Changing the circuit re-encodes the inventory pattern and loses its order flag");
        assertTrue(mixin.contains("copyExtraTags"));
        assertTrue(mixin.contains("copyDefinitionTags"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void markedSecondaryCandidatesReachExactAndFuzzyLookups() throws Exception {
        requireVanillaRegistries();
        var water = AEFluidKey.of(Fluids.WATER);
        var lava = AEFluidKey.of(Fluids.LAVA);
        var tool = AEItemKey.of(Items.IRON_PICKAXE);
        var damage = new CompoundTag();
        damage.putInt("Damage", 5);
        var damagedTool = AEItemKey.of(Items.IRON_PICKAXE, damage);
        var marked = pattern(true, AEItemKey.of(Items.IRON_INGOT), water, damagedTool);
        var unmarked = pattern(false, AEItemKey.of(Items.PAPER), water, damagedTool, lava);
        var primary = pattern(false, water);
        var hook = new GtlCoreGraphByproductOrderMixin() {};
        var outputIndex = GtlCoreGraphByproductOrderMixin.class.getDeclaredField("outputs");
        outputIndex.setAccessible(true);
        outputIndex.set(hook, Map.of(water, List.of(unmarked, marked), lava, List.of(unmarked)));
        var familyIndex = GtlCoreGraphByproductOrderMixin.class.getDeclaredField("gtShanhai$allOutputFamilies");
        familyIndex.setAccessible(true);
        familyIndex.set(hook, Map.of(tool.getPrimaryKey(), List.of(unmarked, marked)));
        Operation<List<IPatternDetails>> ordered = args -> (List<IPatternDetails>) args[1];

        var exact = GtlCoreGraphByproductOrderMixin.class.getDeclaredMethod("gtShanhai$includeMarkedExactSources",
                Collection.class, List.class, Operation.class, AEKey.class);
        exact.setAccessible(true);
        assertEquals(List.of(primary, marked), exact.invoke(hook, List.of(primary), List.of(primary), ordered, water));
        assertEquals(List.of(), exact.invoke(hook, List.of(), List.of(), ordered, lava));

        var fuzzy = GtlCoreGraphByproductOrderMixin.class.getDeclaredMethod("gtShanhai$includeMarkedFuzzySources",
                Collection.class, List.class, Operation.class, AEKey.class, Collection.class, boolean.class);
        fuzzy.setAccessible(true);
        assertEquals(List.of(marked), fuzzy.invoke(hook, List.of(), List.of(), ordered, tool, List.of(), false));
        var globalSources = List.of(unmarked, marked);
        assertEquals(globalSources, fuzzy.invoke(hook, List.of(), globalSources, ordered, tool, List.of(), true));

        var candidates = GtlCoreGraphByproductOrderMixin.class.getDeclaredMethod("gtShanhai$includeMarkedCandidates",
                Stream.class, Predicate.class, Operation.class);
        candidates.setAccessible(true);
        Stream<Map.Entry<AEKey, Boolean>> keys = Stream.of(Map.entry(water, false), Map.entry(lava, false),
                Map.entry(tool, true));
        Predicate<Map.Entry<AEKey, Boolean>> primaryOnly = Map.Entry::getValue;
        Operation<Stream<Map.Entry<AEKey, Boolean>>> filter =
                args -> ((Stream<Map.Entry<AEKey, Boolean>>) args[0]).filter((Predicate<Map.Entry<AEKey, Boolean>>) args[1]);
        var selected = (Stream<Map.Entry<AEKey, Boolean>>) candidates.invoke(hook, keys, primaryOnly, filter);
        assertEquals(List.of(water, tool), selected.map(Map.Entry::getKey).toList());
    }

    @Test
    void circuitRebuildPreservesTagsWithoutRestoringOldSlots() {
        requireVanillaRegistries();
        var source = new ItemStack(Items.PAPER);
        source.getOrCreateTag().putBoolean(PatternByproductOrder.TAG, true);
        source.getOrCreateTag().putString("recipe", "first_light");
        source.getOrCreateTag().putString("in", "old inputs");
        source.getOrCreateTag().putString("out", "old outputs");
        var rebuilt = new ItemStack(Items.PAPER);
        rebuilt.getOrCreateTag().putString("in", "edited inputs");
        PatternByproductOrder.copyExtraTags(source.getTag(), rebuilt);
        assertTrue(PatternByproductOrder.isMarked(rebuilt));
        assertEquals("first_light", rebuilt.getOrCreateTag().getString("recipe"));
        assertEquals("edited inputs", rebuilt.getOrCreateTag().getString("in"));
        assertFalse(rebuilt.getOrCreateTag().contains("out"));
    }

    @Test
    void screenshotRecipeRoundsTheSecondaryFluidOrderUpAndKeepsBothOutputs() throws Exception {
        Path recipePath = Path.of("src/main/resources/data/gt_shanhai/recipes/photon_separation/destination/first_light.json");
        try (var reader = Files.newBufferedReader(recipePath)) {
            var recipeJson = JsonParser.parseReader(reader).getAsJsonObject();
            long reagent = recipeJson.getAsJsonObject("inputs").getAsJsonArray("fluid").get(0)
                    .getAsJsonObject().getAsJsonObject("content").get("amount").getAsLong();
            long light = recipeJson.getAsJsonObject("outputs").getAsJsonArray("fluid").get(0)
                    .getAsJsonObject().getAsJsonObject("content").get("amount").getAsLong();
            GraphRecipe<String> recipe = new GraphRecipe<>("first_light", "first_light",
                    List.of(new GraphRecipe.Slot<>("zero_point_energy", reagent, 0)),
                    Map.of("first_light", 2L, "light", light));

            long requested = 1_000_001L;
            long runs = Math.ceilDiv(requested, light);
            GraphPlan<String> plan = new GraphPlanner<>(new GraphCompiler<>(List.of(recipe))).plan(
                    "light", requested, Map.of("zero_point_energy", reagent * runs), true, true,
                    new PlanningBudget(0, 200_000, () -> false));

            assertTrue(plan.feasible(), () -> plan.result() + " " + plan.missingExact());
            assertEquals(Map.of("first_light", runs), plan.patternTimes());
            assertEquals(Map.of("zero_point_energy", reagent * runs), plan.initial());
            assertEquals(Map.of("first_light", 2L, "light", 550L), recipe.executionOutputs());
            verifyDelivery(plan, reagent, light, runs, requested);
        }
    }

    private static void verifyDelivery(GraphPlan<String> plan, long reagent, long light, long runs, long requested) {
        var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        Map<String, Long> incoming = new LinkedHashMap<>();
        Map<String, Long> refunds = new LinkedHashMap<>();
        List<Long> deliveries = new ArrayList<>();
        var adapter = new GraphJobRuntime.Adapter<String>() {
            @Override
            public long capacity(GraphRecipe<String> recipe, long requested) {
                return requested;
            }

            @Override
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long count, Map<String, Long> inputs) {
                assertEquals(Map.of("zero_point_energy", reagent * count), inputs);
                recipe.executionOutputs().forEach((key, amount) -> incoming.merge(key, amount * count, Long::sum));
                return GraphJobRuntime.Outcome.ACCEPTED;
            }

            @Override
            public long deliver(String key, long amount) {
                assertEquals("light", key);
                deliveries.add(amount);
                return amount;
            }

            @Override
            public long refund(String key, long amount) {
                refunds.merge(key, amount, Long::sum);
                return amount;
            }
        };
        for (int i = 0; i < 16 && !runtime.finished(); i++) {
            runtime.tick(adapter, runs, 16);
            incoming.forEach((key, amount) -> assertEquals(amount.longValue(), runtime.accept(key, amount, false)));
            incoming.clear();
        }
        assertEquals(GraphJobRuntime.State.COMPLETED, runtime.state(), runtime::reason);
        assertEquals(requested, deliveries.stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("first_light", runs * 2, "light", runs * light - requested), refunds);
    }

    private static IPatternDetails pattern(boolean marked, AEKey primary, AEKey... secondary) {
        var tag = new CompoundTag();
        tag.putBoolean(PatternByproductOrder.TAG, marked);
        AEItemKey definition = AEItemKey.of(Items.PAPER, tag);
        GenericStack[] outputs = Stream.concat(Stream.of(primary), Stream.of(secondary))
                .map(key -> new GenericStack(key, 1)).toArray(GenericStack[]::new);
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return definition;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public GenericStack[] getOutputs() {
                return outputs;
            }
        };
    }
}
