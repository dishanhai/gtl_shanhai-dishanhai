package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GuixuArParallelTest {
    private GTRecipe recipe() {
        return new GTRecipe(null, new ResourceLocation("gt_shanhai", "ar_test"),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), new CompoundTag(), 20, false);
    }

    private long parallel(GTRecipe recipe, boolean working) throws Exception {
        var method = GuixuVisionCoreMachine.class.getDeclaredMethod("snapshotModuleParallel", GTRecipe.class, boolean.class);
        method.setAccessible(true);
        return (long) method.invoke(null, recipe, working);
    }

    @Test void idleIsZeroAndUnrecordedRunningBatchesRemainUnknown() throws Exception {
        assertEquals(0L, parallel(null, false));
        assertEquals(0L, parallel(recipe(), false));
        assertEquals(-1L, parallel(null, true));
        assertEquals(-1L, parallel(recipe(), true));
    }

    @Test void actualBatchParallelSurvivesLargeCountsAndRecipeCopy() throws Exception {
        var recipe = recipe();
        recipe.data.putLong("sh_primordial_actual_parallel", 9_000_000_000L);
        assertEquals(9_000_000_000L, parallel(recipe.copy(), true));
        recipe.data.putLong("sh_primordial_actual_parallel", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, parallel(recipe, true));
    }

    @Test void malformedCountsAreUnknown() throws Exception {
        var recipe = recipe();
        recipe.data.putLong("sh_primordial_actual_parallel", -3L);
        assertEquals(-1L, parallel(recipe, true));
    }

    @Test void remoteSlotsHaveSpaceForParallelAndOutputSnapshots() {
        assertEquals(40, GuixuVisionCoreMachine.SNAP_SLOTS);
        int size = GuixuVisionCoreMachine.SNAP_SLOTS;
        var reading = new GuixuVisionCoreMachine.ModuleReading();
        reading.parallel = 9_000_000_000L;
        long[] parallel = new long[size];
        GuixuVisionCoreMachine.applyReading(39, reading, new byte[size], new long[size], parallel,
                new int[size], new int[size], new int[size], new int[size * 2], new int[size * 2]);
        assertEquals(9_000_000_000L, parallel[39]);
    }

    @Test void remoteModuleIdentityIsSyncedWithoutClientWorldLookups() throws Exception {
        var field = GuixuVisionCoreMachine.class.getDeclaredField("hostModMachineItems");
        assertNotNull(field.getAnnotation(com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced.class));
        assertEquals(int[].class, field.getType());
    }

    @Test void materialProductionSyncUsesTheFullServerAggregate() throws Exception {
        var field = GuixuVisionCoreMachine.class.getDeclaredField("hostProductionMultiplier");
        assertNotNull(field.getAnnotation(com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced.class));
        assertEquals(int.class, field.getType());
        String host = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
        assertTrue(host.contains("final int production = getRecipeOutputMultiplier();"));
        assertTrue(host.contains("production != hostProductionMultiplier"), "增益變化必須觸發快照同步");
        assertTrue(host.contains("hostProductionMultiplier = production;"));
        String client = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx/HostFxGlassArText.java"));
        assertTrue(client.contains("STATE.productionMultiplier = safeInt(host::hostProductionMultiplier);"));
        assertFalse(client.contains("STATE.productionMultiplier = safeInt(host::productionMultiplier);"));
    }

    @Test void readingAModuleKeepsTheActualBatchParallel() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var module = (SnapshotModule) unsafe.allocateInstance(SnapshotModule.class);
        module.logic = (SnapshotLogic) unsafe.allocateInstance(SnapshotLogic.class);
        module.logic.recipe = recipe();
        module.logic.recipe.data.putLong("sh_primordial_actual_parallel", 9_000_000_000L);
        var reading = GuixuVisionCoreMachine.readModule(module);
        assertEquals(9_000_000_000L, reading.parallel);
        assertEquals(50, reading.progressPct);
        assertEquals(1, reading.threads);
    }

    private static class SnapshotModule extends com.dishanhai.gt_shanhai.common.machine.primordial.module.matter.PrimordialSupercriticalMatterGenerationCore {
        SnapshotLogic logic;
        SnapshotModule() { super(null); }
        @Override public SnapshotLogic getRecipeLogic() { return logic; }
        @Override public long getRecipeLogicThreadMultiplier() { return 1L; }
    }

    private static class SnapshotLogic extends com.dishanhai.gt_shanhai.common.machine.primordial.module.matter.PrimordialSupercriticalMatterGenerationCoreLogic {
        GTRecipe recipe;
        SnapshotLogic() { super(null); }
        @Override public boolean isWorking() { return true; }
        @Override public int getProgress() { return 10; }
        @Override public int getMaxProgress() { return 20; }
        @Override public GTRecipe getLastRecipe() { return recipe; }
    }
}
