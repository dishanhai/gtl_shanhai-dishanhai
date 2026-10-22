package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 跳转显示名：填了称呼用称呼，留空用商品名称（山海署名）。 */
class ShopLinkNamesTest {

    @Test
    void aliasWinsWhenFilled() {
        assertEquals("矿石入口", ShopLinkNames.label("矿石入口", "钻石"));
        assertEquals("别名", ShopLinkNames.label("  别名 ", "钻石"), "前后空白不算");
    }

    @Test
    void blankAliasFallsBackToTheGoodsName() {
        assertEquals("钻石", ShopLinkNames.label("", "钻石"));
        assertEquals("钻石", ShopLinkNames.label(null, "钻石"));
        assertEquals("钻石", ShopLinkNames.label("   ", " 钻石 "));
    }

    @Test
    void bothBlankSaysUnnamed() {
        assertEquals(ShopLinkNames.UNNAMED, ShopLinkNames.label("", ""));
        assertEquals("(未命名商品)", ShopLinkNames.label(null, null));
    }
}
