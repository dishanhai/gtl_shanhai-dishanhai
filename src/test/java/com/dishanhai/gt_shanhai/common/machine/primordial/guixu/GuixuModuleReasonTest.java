package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class GuixuModuleReasonTest {
    private byte reason(boolean working, boolean waiting, String key) throws Exception {
        Method method = assertDoesNotThrow(() -> GuixuVisionCoreMachine.class.getDeclaredMethod(
                "classifyReason", boolean.class, boolean.class, String.class));
        method.setAccessible(true);
        return (byte) method.invoke(null, working, waiting, key);
    }

    @Test void idleAndSuspendedIgnoreStaleFailureWhileWorkingWins() throws Exception {
        assertEquals(0, reason(false, false, "gtceu.recipe.fail.no.enough.eu.in"));
        assertEquals(1, reason(true, false, "gtceu.recipe.fail.Output"));
    }

    @Test void waitingUsesPublishedFailureAndUnknownRemainsBlocked() throws Exception {
        assertEquals(2, reason(false, true, "gtceu.recipe.fail.no.enough.eu.in"));
        assertEquals(3, reason(false, true, "gtceu.recipe.fail.Output"));
        assertEquals(4, reason(false, true, "other"));
        assertEquals(4, reason(false, true, null));
    }
}
