package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：原生按钮适配保留防连发、裁切命中与键盘显露。 */
class ShopControlInputTest {
    private Object input() throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.gui.shop.ShopControlInput")
                .getDeclaredConstructor().newInstance();
    }

    private boolean press(Object input, int key, boolean enabled) throws Exception {
        return (boolean) input.getClass().getDeclaredMethod("press", int.class, boolean.class)
                .invoke(input, key, enabled);
    }

    private void release(Object input, int key) throws Exception {
        input.getClass().getDeclaredMethod("release", int.class).invoke(input, key);
    }

    @Test
    void heldActivationCannotRepeatOrTransferToAnotherButton() throws Exception {
        Object input = input();
        assertTrue(press(input, 257, true));
        assertFalse(press(input, 257, true));
        assertFalse(press(input, 32, true));
        release(input, 257);
        assertFalse(press(input, 32, true));
        release(input, 32);
        assertTrue(press(input, 32, true));
    }

    @Test
    void blockedPressMustBeReleasedBeforeBecomingActive() throws Exception {
        Object input = input();
        assertFalse(press(input, 335, false));
        assertFalse(press(input, 335, true));
        release(input, 335);
        assertTrue(press(input, 335, true));
        assertFalse(press(input, 65, true));
    }

    @Test
    void clippedButtonRejectsCoveredAndOutsidePixels() throws Exception {
        var method = Class.forName("com.dishanhai.gt_shanhai.client.gui.shop.ShopControlInput")
                .getDeclaredMethod("hitClipped", double.class, double.class,
                        int.class, int.class, int.class, int.class, int.class, int.class);
        assertFalse((boolean) method.invoke(null, 25d, 12d, 20, 10, 50, 18, 30, 60));
        assertTrue((boolean) method.invoke(null, 30d, 10d, 20, 10, 50, 18, 30, 60));
        assertTrue((boolean) method.invoke(null, 59.9d, 27.9d, 20, 10, 50, 18, 30, 60));
        assertFalse((boolean) method.invoke(null, 60d, 12d, 20, 10, 50, 18, 30, 60));
        assertFalse((boolean) method.invoke(null, 40d, 28d, 20, 10, 50, 18, 30, 60));
    }

    @Test
    void keyboardRevealKeepsButtonInsideViewportAndClampsScroll() throws Exception {
        var method = Class.forName("com.dishanhai.gt_shanhai.client.gui.shop.ShopControlInput")
                .getDeclaredMethod("revealScroll", int.class, int.class, int.class,
                        int.class, int.class, int.class);
        assertEquals(0, method.invoke(null, 15, 40, 20, 150, 5, 200));
        assertEquals(80, method.invoke(null, 140, 40, 20, 150, 50, 200));
        assertEquals(50, method.invoke(null, 30, 40, 20, 150, 50, 200));
        assertEquals(200, method.invoke(null, 140, 90, 20, 150, 195, 200));
    }
}
