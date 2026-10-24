package com.dishanhai.gt_shanhai.client.gui.terminal;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** 山海：僅客戶端的 Screen 入口，行為類經 DistExecutor 延遲連結。 */
public final class UltimateTerminalScreenOpener {
    private UltimateTerminalScreenOpener() {}

    public static void open(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.setScreen(new UltimateTerminalScreen(hand));
    }
}
