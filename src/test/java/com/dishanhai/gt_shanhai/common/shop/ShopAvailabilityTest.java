package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海商店：个人启用不能覆盖全局禁用。 */
class ShopAvailabilityTest {
    @Test
    void onlyAllowsShopWhenNeitherSideDisablesIt() {
        assertFalse(ShopAvailability.isDisabled(false, false));
        assertTrue(ShopAvailability.isDisabled(false, true));
        assertTrue(ShopAvailability.isDisabled(true, false));
        assertTrue(ShopAvailability.isDisabled(true, true));
    }
}
