package com.dishanhai.gt_shanhai.client.gui.generator;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import java.util.UUID;

/** 山海：僅客戶端的 Screen 入口，伺服器經 DistExecutor 延遲連結。 */
public final class PatternGeneratorScreenOpener {
    private static PatternGeneratorScreen active;
    private PatternGeneratorScreenOpener() {}
    public static void open(InteractionHand hand,PatternGeneratorState state) {
        var screen=new PatternGeneratorScreen(hand,state);
        Minecraft.getInstance().setScreen(screen); active=screen;
    }
    public static void update(PatternGeneratorState state) {
        if (active!=null && active.sessionId().equals(state.sessionId())) active.receive(state);
    }
    public static void preset(UUID sessionId,int kind,byte[] payload) {
        if (active!=null && active.sessionId().equals(sessionId)) active.receivePreset(kind,payload);
    }
    public static void close(UUID sessionId) {
        if (active!=null && active.sessionId().equals(sessionId)) active.serverClosed();
    }
    static void forget(PatternGeneratorScreen screen) { if (active==screen) active=null; }
}
