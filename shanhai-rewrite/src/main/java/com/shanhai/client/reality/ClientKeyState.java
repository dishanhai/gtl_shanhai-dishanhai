package com.shanhai.client.reality;

import net.minecraft.client.gui.screens.Screen;

/**
 * 客户端"按住键"状态（给 tooltip 的 Shift / Ctrl 条件用）。
 *
 * <p>🔴 为什么单独一个类：{@code Screen} 是<b>纯客户端</b>类，专用服务端上没有。
 * 公共侧的代码绝不能直接引用它 ⇒ 一律经
 * {@code DistExecutor.unsafeRunForDist(() -> ClientKeyState::shift, () -> () -> false)} 间接调用，
 * 这样服务端那一侧<b>根本不会加载本类</b>。
 */
public final class ClientKeyState {

    private ClientKeyState() {
    }

    /** 是否按住 Shift。 */
    public static boolean shift() {
        return Screen.hasShiftDown();
    }

    /** 是否按住 Ctrl。 */
    public static boolean ctrl() {
        return Screen.hasControlDown();
    }
}
