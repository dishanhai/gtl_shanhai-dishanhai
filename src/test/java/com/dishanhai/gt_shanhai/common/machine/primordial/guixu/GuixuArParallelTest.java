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

    @Test void readingAModuleUsesAvailableCapacityEvenWhenIdleOrAnOldBatchIsPresent() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var module = (SnapshotModule) unsafe.allocateInstance(SnapshotModule.class);
        module.logic = (SnapshotLogic) unsafe.allocateInstance(SnapshotLogic.class);
        module.logic.recipe = recipe();
        module.logic.recipe.data.putLong("sh_primordial_actual_parallel", 9_000_000_000L);
        module.capacity = 64L;
        var idle = GuixuVisionCoreMachine.readModule(module);
        assertEquals(64L, idle.parallel, "閒置仍須顯示預設並行");
        assertEquals(0, idle.progressPct);
        module.logic.working = true;
        module.capacity = 128L;
        var reading = GuixuVisionCoreMachine.readModule(module);
        assertEquals(128L, reading.parallel, "主機加成更新後不可沿用舊批次並行");
        assertEquals(50, reading.progressPct);
        assertEquals(1, reading.threads);
        module.capacity = Long.MAX_VALUE;
        assertEquals(Long.MAX_VALUE, GuixuVisionCoreMachine.readModule(module).parallel);
    }

    private static class SnapshotModule extends com.dishanhai.gt_shanhai.common.machine.primordial.module.matter.PrimordialSupercriticalMatterGenerationCore {
        SnapshotLogic logic;
        long capacity;
        SnapshotModule() { super(null); }
        @Override public SnapshotLogic getRecipeLogic() { return logic; }
        @Override public long getRecipeLogicThreadMultiplier() { return 1L; }
        public long getAvailableRecipeParallel() { return capacity; }
    }

    private static class SnapshotLogic extends com.dishanhai.gt_shanhai.common.machine.primordial.module.matter.PrimordialSupercriticalMatterGenerationCoreLogic {
        GTRecipe recipe;
        boolean working;
        SnapshotLogic() { super(null); }
        @Override public boolean isWorking() { return working; }
        @Override public int getProgress() { return working ? 10 : 0; }
        @Override public int getMaxProgress() { return 20; }
        @Override public GTRecipe getLastRecipe() { return recipe; }
    }
}
