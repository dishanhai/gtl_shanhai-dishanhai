package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 山海無線終端的細節同步與遠端驗證 tick 邊界。 */
class ShanhaiStellarTerminalTickCadenceTest {

    @Test
    void metadataIsCalculatedImmediatelyThenEveryTwentyWorldTicks() throws Exception {
        Object cadence = cadence("ShanhaiPatternManagementMenu$MetadataSyncCadence");

        assertTrue(shouldRun(cadence, 73L));
        assertFalse(shouldRun(cadence, 73L), "同一 tick 的重複廣播不得重算細節");
        for (long tick = 74; tick < 93; tick++) {
            assertFalse(shouldRun(cadence, tick), "20 tick 間隔內不得重算細節");
        }
        assertTrue(shouldRun(cadence, 93L));
        assertFalse(shouldRun(cadence, 112L));
        assertTrue(shouldRun(cadence, 113L));
    }

    @Test
    void metadataClockRewindDoesNotLeaveDetailsStale() throws Exception {
        Object cadence = cadence("ShanhaiPatternManagementMenu$MetadataSyncCadence");

        assertTrue(shouldRun(cadence, 500L));
        assertTrue(shouldRun(cadence, 0L));
        assertFalse(shouldRun(cadence, 19L));
        assertTrue(shouldRun(cadence, 20L));
    }

    @Test
    void metadataCadenceDoesNotThrottleParentMenuOrToolbox() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item/terminal/"
                + "ShanhaiPatternManagementMenu.java"));
        String broadcast = source.substring(source.indexOf("public void broadcastChanges()"),
                source.indexOf("public boolean isWUT()"));

        int toolbox = broadcast.indexOf("toolboxMenu.tick();");
        int parent = broadcast.indexOf("super.broadcastChanges();");
        int cadence = broadcast.indexOf("metadataSyncCadence.shouldSync(");
        int sync = broadcast.indexOf("syncStellarContainerMetadata();");
        assertTrue(toolbox >= 0 && parent > toolbox && cadence > parent && sync > cadence,
                "只節流星律 metadata，AE2 與工具箱仍每次廣播執行");
        assertTrue(broadcast.contains("getPlayer().level().getGameTime()"),
                "細節節流應以世界 tick 計時，不以 broadcast 呼叫次數計時");
    }

    @Test
    void wirelessChecksRunOncePerTickAndRefreshAfterDimensionChange() throws Exception {
        Object cadence = cadence("ShanhaiStellarRemoteUIHolder$WirelessCheckCadence");
        Method shouldCheck = cadence.getClass().getDeclaredMethod("shouldCheck", Object.class, long.class);
        shouldCheck.setAccessible(true);
        Object level = new Object();
        Object otherLevel = new Object();

        assertTrue((boolean) shouldCheck.invoke(cadence, level, 100L));
        assertFalse((boolean) shouldCheck.invoke(cadence, level, 100L),
                "同 tick 重複 isInvalid 不可重複推進 AE2 扣電計數");
        assertTrue((boolean) shouldCheck.invoke(cadence, level, 101L),
                "新 tick 必須繼續推進 AE2 既有扣電計數");
        assertTrue((boolean) shouldCheck.invoke(cadence, otherLevel, 101L),
                "跨維度即使 gameTime 相同也必須刷新無線狀態");
        assertFalse((boolean) shouldCheck.invoke(cadence, otherLevel, 101L));
        assertTrue((boolean) shouldCheck.invoke(cadence, otherLevel, 0L));
    }

    private static Object cadence(String nestedName) {
        return assertDoesNotThrow(() -> {
            Class<?> type = Class.forName("com.dishanhai.gt_shanhai.common.item.terminal." + nestedName);
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }, "應提供可獨立驗證的終端 tick 節流狀態");
    }

    private static boolean shouldRun(Object cadence, long tick) throws Exception {
        Method method = cadence.getClass().getDeclaredMethod("shouldSync", long.class);
        method.setAccessible(true);
        return (boolean) method.invoke(cadence, tick);
    }
}
