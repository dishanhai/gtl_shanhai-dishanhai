package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadScheduler;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import java.util.*;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedCorePrimordialPolicyTest {
    @Test
    void finalNativeOutputReplacesTheSnapshotTakenBeforeDelayedSetup() {
        Map<String, Long> completion = new HashMap<>(Map.of("before-setup", 2L, "removed-output", 3L));
        EnhancedCorePrimordialContext.replaceCompletion(completion, Map.of("broadcast-output", 16L));
        assertEquals(Map.of("broadcast-output", 16L), completion);
        EnhancedCorePrimordialContext.replaceCompletion(completion, Map.of());
        assertTrue(completion.isEmpty(), "Output swallowed by the final native lifecycle must remain swallowed");
    }
    @Test
    void anUnsuccessfulNativeInputRollConsumesNothingAndNeverRollsReusableCatalysts() throws Exception {
        Content guaranteed = content("ordinary", 10000);
        Content chance = content("chance", 1000);
        Content reusable = content("catalyst", 0);
        Map<RecipeCapability<?>, List<Content>> contents = new HashMap<>();
        contents.put(null, List.of(guaranteed, chance, reusable));
        int[] rolls = {0};
        var failedChance = EnhancedCorePrimordialContext.rollConsumedInputs(contents, offered -> {
            rolls[0]++;
            assertEquals(List.of(guaranteed, chance), offered.get(null));
            assertEquals(1000, offered.get(null).get(1).chance);
            Map<RecipeCapability<?>, List<Content>> rolled = new HashMap<>();
            rolled.put(null, List.of(guaranteed));
            return rolled;
        });
        assertEquals(1, rolls[0]);
        assertEquals(List.of(guaranteed), failedChance.get(null));
        assertEquals(List.of(guaranteed, chance, reusable), contents.get(null));
    }
    @Test
    void cloudMustDrainPaidLocalBatchesBeforeTakingExecution() {
        assertEquals(EnhancedCorePrimordialPolicy.Owner.LOCAL,
                EnhancedCorePrimordialPolicy.owner(false, 3));
        assertEquals(EnhancedCorePrimordialPolicy.Owner.DRAIN_LOCAL,
                EnhancedCorePrimordialPolicy.owner(true, 3));
        assertEquals(EnhancedCorePrimordialPolicy.Owner.CLOUD,
                EnhancedCorePrimordialPolicy.owner(true, 0));
    }

    @Test
    void admissionSharesTheSameFiniteParallelAndThreadCapacity() {
        var scheduler = new PrimordialThreadScheduler<String>();
        assertEquals(4, scheduler.admissionShare(10, 3));
        scheduler.restore(0, 4, "paid-first");
        assertEquals(3, scheduler.admissionShare(10, 3));
        scheduler.restore(1, 3, "paid-second");
        scheduler.restore(2, 3, "paid-third");
        assertEquals(0, scheduler.admissionShare(10, 3));
        var full = new PrimordialThreadScheduler<String>();
        full.restore(0, 10, "capacity-reserved");
        assertEquals(0, full.admissionShare(10, 3));
        assertEquals(Long.MAX_VALUE, new PrimordialThreadScheduler<>().admissionShare(Long.MAX_VALUE, 1));
    }

    @Test
    void currentSelectionIncludingAnEmptySelectionIsAuthoritative() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var machine = (SelectionProbe) unsafe.allocateInstance(SelectionProbe.class);
        machine.selection = new GTRecipeType[2];
        assertSame(machine.selection, EnhancedCorePrimordialRecipeTypes.selected(machine, null));
        machine.selection = new GTRecipeType[0];
        assertSame(machine.selection, EnhancedCorePrimordialRecipeTypes.selected(machine, null));
        assertEquals(0, EnhancedCorePrimordialRecipeTypes.selected(machine, null).length);
    }

    @Test
    void aBroadCrossTypeRecipeGateCannotReadmitAQueuedDeselectedRecipe() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var machine = (SelectionProbe) unsafe.allocateInstance(SelectionProbe.class);
        var logic = (SelectionLogic) unsafe.allocateInstance(SelectionLogic.class);
        logic.owner = machine;
        GTRecipe recipe = (GTRecipe) unsafe.allocateInstance(GTRecipe.class);
        GTRecipeType type = (GTRecipeType) unsafe.allocateInstance(GTRecipeType.class);
        unsafe.putObject(recipe, unsafe.objectFieldOffset(GTRecipe.class.getDeclaredField("recipeType")), type);
        machine.selected = true;
        assertTrue(logic.isCloudRecipeSelected(recipe));
        assertTrue(logic.checkCloudCandidate(recipe));
        assertEquals(1, logic.nativeChecks);
        machine.selected = false;
        assertFalse(logic.isCloudRecipeSelected(recipe));
        assertFalse(logic.checkCloudCandidate(recipe));
        assertEquals(1, logic.nativeChecks, "Reject deselection before any broadened native gate or material check");
    }

    private static class SelectionProbe extends SelectableRecipeTypeSetMachine {
        GTRecipeType[] selection;
        boolean selected;
        SelectionProbe() { super(null, null); }
        @Override public GTRecipeType[] getSelectedRecipeTypes() { return selection; }
        @Override public boolean isRecipeTypeSelected(GTRecipeType type) { return selected; }
        @Override public GTRecipeType[] getAllSelectableRecipeTypes() {
            throw new AssertionError("Cloud allocation cannot broaden the player's selection");
        }
    }

    private static class SelectionLogic extends PrimordialThreadedRecipeLogic {
        SelectionProbe owner;
        int nativeChecks;
        SelectionLogic() { super(null); }
        @Override public SelectableRecipeTypeSetMachine getMachine() { return owner; }
        @Override protected long getUnreservedTotalParallelLimit() { return 1; }
        @Override protected boolean checkRecipe(GTRecipe recipe) { nativeChecks++; return true; }
    }

    private static Content content(String value, int chance) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        Content content = (Content) unsafe.allocateInstance(Content.class);
        unsafe.putObject(content, unsafe.objectFieldOffset(Content.class.getDeclaredField("content")), value);
        content.chance = chance;
        content.maxChance = 10000;
        content.tierChanceBoost = 0;
        return content;
    }
}
