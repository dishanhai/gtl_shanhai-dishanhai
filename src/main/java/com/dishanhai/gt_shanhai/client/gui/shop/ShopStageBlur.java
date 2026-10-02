package com.dishanhai.gt_shanhai.client.gui.shop;

/** 阶段锁定状态只由商店 UI 自己绘制局部遮罩，不再接管游戏主画面的后处理。 */
final class ShopStageBlur {
    private static boolean active;

    private ShopStageBlur() {}

    static void setActive(boolean enabled) {
        active = enabled;
    }

    static void clear() {
        active = false;
    }
}
