package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PrimordialThreadEntrySourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine");

    @Test void modulesAndHostsExposeTheSameItemDrivenThreadEntry() throws Exception {
        String module = Files.readString(ROOT.resolve("primordial/PrimordialOmegaEngineModuleBase.java"));
        String host = Files.readString(ROOT.resolve("primordial/PrimordialOmegaEngineMachine.java"));
        for (String source : new String[]{module, host}) {
            assertTrue(source.contains("public long getParallelRecipeThreadCount()"));
            assertTrue(source.contains("onThreadInputsChanged()"));
            assertTrue(source.contains("多线程并行"));
            assertTrue(source.contains("logic.saveRecipeThreads(tag)"));
            assertTrue(source.contains("logic.loadRecipeThreads(tag)"));
        }
        assertTrue(host.contains("@Persisted\n    private final NotifiableItemStackHandler recipeThreadSlot;")
                || host.contains("@Persisted\r\n    private final NotifiableItemStackHandler recipeThreadSlot;"));
        assertTrue(host.contains("clearInventory(recipeThreadSlot.storage)"));
        assertTrue(host.contains("getThreadBoostForStack(recipeThreadSlot.storage.getStackInSlot(0))"));
        assertTrue(host.contains("size.height - 88"), "避開歸墟等級覆寫槽");
        assertTrue(module.contains("page.getSize().height - 88"), "特殊發電模組仍有線程入口");
    }

    @Test void acceptedStellarPushNotifiesOnlyItsControllersBeforeOrderTracking() throws Exception {
        String source = Files.readString(ROOT.resolve("part/RecipeTypePatternBufferPartMachine.java"));
        int push = source.indexOf("public boolean pushPattern(");
        int accepted = source.indexOf("boolean accepted = super.pushPattern", push);
        int wake = source.indexOf("notifyParallelRecipeThreads();", accepted);
        int tracked = source.indexOf("StellarPatternStuckWatch.schedule(", accepted);
        assertTrue(wake > accepted && wake < tracked);
        assertTrue(source.substring(accepted, wake).contains("if (accepted && !isRemote())"));
        int helper = source.indexOf("private void notifyParallelRecipeThreads()");
        assertTrue(helper > 0);
        String body = source.substring(helper, source.indexOf("private final class StellarPatternBufferInternalSlot", helper));
        assertTrue(body.contains("getControllers()"));
        assertTrue(body.contains("controller.self() instanceof IRecipeLogicMachine host"));
        assertTrue(body.contains("instanceof PrimordialThreadedRecipeLogic logic"));
        assertTrue(body.contains("logic.onThreadInputsChanged()"));
        assertFalse(body.contains("getAllLevels"));
    }

    @Test void overdriverTooltipsDescribeTheSharedMachineBudget() throws Exception {
        String module = Files.readString(ROOT.resolve("primordial/PrimordialOmegaEngineModuleBase.java"));
        assertFalse(module.contains("每个配方独立 Long.MAX_VALUE 并行"));
        assertEquals(2, module.split("整机共享 Long.MAX_VALUE 并行", -1).length - 1);
    }
}
