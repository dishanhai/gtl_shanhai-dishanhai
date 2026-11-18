// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayPermissionTest {
    @Test void elevatedCommandSourceDoesNotElevateAPlayer() {
        assertFalse(ReplayPermission.allowed(true, false, true));
        assertTrue(ReplayPermission.allowed(true, true, false));
        assertTrue(ReplayPermission.allowed(false, false, true));
        assertFalse(ReplayPermission.allowed(false, false, false));
    }
}
