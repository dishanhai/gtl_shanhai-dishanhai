package com.dishanhai.gt_shanhai.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.cgse.core.GraphCompiler;
import org.cgse.core.GraphJobRuntime;
import org.cgse.core.GraphPlan;
import org.cgse.core.GraphPlanner;
import org.cgse.core.GraphRecipe;
import org.cgse.core.PlanSummary;
import org.cgse.core.PlanningBudget;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GtlCoreGraphPresenceRecursionTest {

    private static final long RUNS = 8;
    private static final Map<String, Long> STARTUP = Map.of("virtual", 1L, "reagent", 1L);
    private static final GraphRecipe<String> MIX = new GraphRecipe<>("mix", "mix", List.of(
            new GraphRecipe.Slot<>("virtual", 1, 0, true, true),
            new GraphRecipe.Slot<>("reagent", 1, 1)), Map.of("intermediate", 1L, "virtual", 1L));
    private static final GraphRecipe<String> RECOVER = new GraphRecipe<>("recover", "recover",
            List.of(new GraphRecipe.Slot<>("intermediate", 1, 0)), Map.of("reagent", 1L, "product", 1L));

    @Test
    void recursivePreviewCountsTheVirtualSeedOnceAndReportsPhysicalProducts() throws Exception {
        GraphPlan<String> plan = plan(STARTUP);
        assertTrue(plan.feasible(), () -> plan.result() + " " + plan.missingExact());

        Map<String, PlanSummary.Entry<String>> rows = new HashMap<>();
        PlanSummary.entries(plan, Map.of()).forEach(row -> rows.put(row.key(), row));

        assertEquals(BigInteger.ONE, rows.get("virtual").stored());
        assertEquals(BigInteger.valueOf(RUNS), rows.get("reagent").crafted());
        assertEquals(BigInteger.valueOf(RUNS), rows.get("product").crafted());
        for (Class<?> hook : summaryHooks()) {
            assertEquals(Map.of("intermediate", 1L), summaryProducts(hook, plan.recipes().get("mix")));
            assertEquals(Map.of("reagent", 1L, "product", 1L), summaryProducts(hook, plan.recipes().get("recover")));
        }
    }

    @Test
    void summaryKeepsRealProductionThatSharesTheVirtualSeedKey() throws Exception {
        GraphRecipe<String> recipe = new GraphRecipe<>("grow", "grow",
                List.of(new GraphRecipe.Slot<>("virtual", 1, 0, true, true)),
                Map.of("virtual", 3L));

        for (Class<?> hook : summaryHooks()) {
            assertEquals(Map.of("virtual", 2L), summaryProducts(hook, recipe));
        }
    }

    @Test
    void recursiveExecutionStartsWithOneOfEachAndDispatchesOnlyRealInputs() {
        GraphPlan<String> plan = plan(STARTUP);
        assertTrue(plan.feasible(), () -> plan.result() + " " + plan.missingExact());
        assertEquals(STARTUP, plan.initial());
        assertEquals(Map.of("mix", RUNS, "recover", RUNS), plan.patternTimes());

        GraphJobRuntime<String> runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        Map<String, Long> returns = new HashMap<>();
        Map<String, Long> refunds = new HashMap<>();
        GraphJobRuntime.Adapter<String> adapter = new GraphJobRuntime.Adapter<>() {
            @Override
            public long capacity(GraphRecipe<String> recipe, long requested) {
                return requested;
            }

            @Override
            public GraphJobRuntime.Outcome push(GraphRecipe<String> recipe, long runs, Map<String, Long> inputs) {
                assertEquals(recipe.binding().equals("mix")
                        ? Map.of("reagent", runs) : Map.of("intermediate", runs), inputs);
                assertEquals(1L, runtime.held("virtual"));
                recipe.executionOutputs().forEach((key, amount) -> returns.merge(key, amount * runs, Long::sum));
                return GraphJobRuntime.Outcome.ACCEPTED;
            }

            @Override
            public long deliver(String key, long amount) {
                assertEquals("product", key);
                return amount;
            }

            @Override
            public long refund(String key, long amount) {
                refunds.merge(key, amount, Long::sum);
                return amount;
            }
        };
        for (int i = 0; i < 64 && !runtime.finished(); i++) {
            runtime.tick(adapter, RUNS, 16);
            assertEquals(0L, runtime.waiting("virtual"));
            returns.forEach((key, amount) -> assertEquals(amount.longValue(), runtime.accept(key, amount, false)));
            returns.clear();
        }
        assertEquals(GraphJobRuntime.State.COMPLETED, runtime.state(), runtime::reason);
        assertEquals(0L, runtime.remainingDelivery());
        assertEquals(STARTUP, refunds);
    }

    @Test
    void recursiveExecutionCannotBootstrapWithEitherStartupInputMissing() {
        assertFalse(plan(Map.of("virtual", 1L)).feasible());
        assertFalse(plan(Map.of("reagent", 1L)).feasible());
    }

    private static GraphPlan<String> plan(Map<String, Long> stock) {
        return new GraphPlanner<>(new GraphCompiler<>(List.of(MIX, RECOVER))).plan(
                "product", RUNS, stock, true, true, new PlanningBudget(0, 200_000, () -> false));
    }

    private static List<Class<?>> summaryHooks() {
        return List.of(GtlCoreGraphPresenceSummaryMixin.class, GtlCoreGraphPresenceSummaryMixin.Legacy.class);
    }

    private static Object summaryProducts(Class<?> hook, GraphRecipe<String> recipe) throws Exception {
        var method = hook.getDeclaredMethod("gtShanhai$physicalProducts", GraphRecipe.class, Operation.class);
        method.setAccessible(true);
        Operation<Map<String, Long>> original = ignored -> recipe.outputs();
        return method.invoke(null, recipe, original);
    }
}
