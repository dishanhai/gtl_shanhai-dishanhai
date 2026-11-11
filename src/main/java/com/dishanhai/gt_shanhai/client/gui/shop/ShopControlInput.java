package com.dishanhai.gt_shanhai.client.gui.shop;

/** 山海：原生控件在虚拟画布上的激活防重与可视范围适配。 */
final class ShopControlInput {
    private final java.util.Set<Integer> held = new java.util.HashSet<>();

    static boolean activationKey(int key) {
        return key == 257 || key == 335 || key == 32;
    }

    boolean press(int key, boolean enabled) {
        if (!activationKey(key)) return false;
        boolean wasHeld = !held.isEmpty();
        held.add(key);
        return enabled && !wasHeld;
    }

    void release(int key) {
        held.remove(key);
    }

    void reset(boolean enterDown, boolean keypadEnterDown, boolean spaceDown) {
        held.clear();
        if (enterDown) held.add(257);
        if (keypadEnterDown) held.add(335);
        if (spaceDown) held.add(32);
    }

    static boolean hitClipped(double mx, double my, int x, int y, int width, int height,
                              int clipLeft, int clipRight) {
        return mx >= Math.max(x, clipLeft) && mx < Math.min(x + width, clipRight)
                && my >= y && my < y + height;
    }

    static int revealScroll(int x, int width, int viewLeft, int viewRight, int scroll, int maxScroll) {
        int target = scroll;
        if (x < viewLeft) target += x - viewLeft;
        else if (x + width > viewRight) target += x + width - viewRight;
        return Math.max(0, Math.min(maxScroll, target));
    }
}
