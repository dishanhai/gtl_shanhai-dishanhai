package com.dishanhai.gt_shanhai.common.saveedit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiSaveEditAccessTest {
    @Test void onlyRealAuthorizedSingleplayerOwnerMayUseCommands() {
        assertTrue(ShanhaiSaveEditAccess.allowed(true, true, true, true, false));
        assertFalse(ShanhaiSaveEditAccess.allowed(true, false, false, false, false));
        assertFalse(ShanhaiSaveEditAccess.allowed(true, true, false, true, false));
        assertFalse(ShanhaiSaveEditAccess.allowed(true, true, true, false, false));
        assertFalse(ShanhaiSaveEditAccess.allowed(true, true, true, true, true));
        assertFalse(ShanhaiSaveEditAccess.allowed(false, true, true, true, false));
    }
}
