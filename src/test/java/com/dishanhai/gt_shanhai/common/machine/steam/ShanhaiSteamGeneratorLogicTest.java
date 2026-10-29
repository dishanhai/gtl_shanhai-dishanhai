package com.dishanhai.gt_shanhai.common.machine.steam;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：執行真正的完成流程；用邊界替身代替 Forge 世界與流體輸出。 */
class ShanhaiSteamGeneratorLogicTest {
    @Test
    void emptyModuleSlotUsesPrimordialDefaultTimesFour() throws Exception {
        ShanhaiSteamGeneratorMachine host = (ShanhaiSteamGeneratorMachine)
                unsafe().allocateInstance(ShanhaiSteamGeneratorMachine.class);
        assertEquals(256L, ShanhaiSteamGeneratorMachine.class
                .getMethod("getParallelLimit").invoke(host));
        assertEquals(256, host.getMaxParallel(), "原生顯示與實際模組限制應一致");
    }

    @Test
    void failedOutputRetainsCompletedBatchAndRetriesExactlyOnceAfterRecovery() throws Exception {
        Unsafe unsafe = unsafe();
        TestMachine host = (TestMachine) unsafe.allocateInstance(TestMachine.class);
        TestLogic logic = (TestLogic) unsafe.allocateInstance(TestLogic.class);
        Field machineField = RecipeLogic.class.getDeclaredField("machine");
        unsafe.putObject(logic, unsafe.objectFieldOffset(machineField), host);
        GTRecipe recipe = new GTRecipe(null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), new CompoundTag(), 20, false) {
            @Override
            public void postWorking(IRecipeCapabilityHolder holder) {}
        };
        logic.startCompletedBatch(recipe);

        logic.onRecipeFinish();
        logic.onRecipeFinish();
        assertSame(recipe, logic.getLastRecipe(), "輸出失敗不可清除已扣水的批次");
        assertEquals(20, logic.getProgress());
        assertEquals(20, logic.getDuration());
        assertEquals(RecipeLogic.Status.WAITING, logic.getStatus());
        assertEquals(0, logic.newBatchSearches, "等待輸出時不可再次尋配方扣水");
        assertEquals(0, host.finishes);

        logic.allowOutput = true;
        logic.onRecipeFinish();
        logic.onRecipeFinish();
        assertNull(logic.getLastRecipe());
        assertEquals(0, logic.getProgress());
        assertEquals(0, logic.getDuration());
        assertEquals(1, logic.successfulOutputs);
        assertEquals(1, host.finishes);
        assertEquals(1, logic.newBatchSearches);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static class TestMachine extends ShanhaiSteamGeneratorMachine {
        int finishes;

        private TestMachine() { super(null); }

        @Override
        public void afterWorking() { finishes++; }

        @Override
        public void onWaiting() {}

        @Override
        public void notifyStatusChanged(RecipeLogic.Status previous, RecipeLogic.Status next) {}
    }

    private static class TestLogic extends ShanhaiSteamGeneratorMachine.SteamGenerationLogic {
        boolean allowOutput;
        int successfulOutputs;
        int newBatchSearches;

        private TestLogic() { super(null); }

        void startCompletedBatch(GTRecipe recipe) {
            lastRecipe = recipe;
            progress = duration = recipe.duration;
            ocResult = new OCResult();
            setStatus(Status.WORKING);
        }

        @Override
        public void updateTickSubscription() {}

        @Override
        public void findAndHandleRecipe() { newBatchSearches++; }

        @Override
        protected boolean tryOutput(GTRecipe recipe) {
            if (allowOutput) successfulOutputs++;
            return allowOutput;
        }
    }
}
