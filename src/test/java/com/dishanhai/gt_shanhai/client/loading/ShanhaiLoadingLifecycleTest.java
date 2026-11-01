package com.dishanhai.gt_shanhai.client.loading;

import net.minecraft.client.gui.screens.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：登入的臨時 ProgressScreen 不能被視為取消或錯誤頁。 */
class ShanhaiLoadingLifecycleTest {
    @Test void localAndRemoteLoginKeepTheSessionAcrossNativeProgressScreens() {
        var state = new ShanhaiLoadingState();
        state.begin(0);
        state.complete(state.session(), "recipes", "配方", "loaded", 100);
        for (Class<?> screen : new Class<?>[]{GenericDirtMessageScreen.class, ProgressScreen.class,
                LevelLoadingScreen.class, ProgressScreen.class, ReceivingLevelScreen.class}) {
            if (!ShanhaiLoadingScreens.contains(screen)) state.reset();
        }
        assertTrue(state.active(), "合法的原生載入過渡不應清除會話");
        assertEquals(100, state.snapshot(10, false).tasks().get(0).current());
        assertFalse(ShanhaiLoadingScreens.contains(DisconnectedScreen.class));
        assertFalse(ShanhaiLoadingScreens.contains(TitleScreen.class));
    }
}
