package com.dishanhai.gt_shanhai.client.loading;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.ProgressScreen;

/** 山海：只列出登入流程中的原生畫面，避免擋住錯誤與返回畫面。 */
final class ShanhaiLoadingScreens {
    private ShanhaiLoadingScreens() {}

    static boolean contains(Class<?> type) {
        return ConnectScreen.class.isAssignableFrom(type) || GenericDirtMessageScreen.class.isAssignableFrom(type)
                || LevelLoadingScreen.class.isAssignableFrom(type) || ReceivingLevelScreen.class.isAssignableFrom(type)
                || ProgressScreen.class.isAssignableFrom(type);
    }
}
