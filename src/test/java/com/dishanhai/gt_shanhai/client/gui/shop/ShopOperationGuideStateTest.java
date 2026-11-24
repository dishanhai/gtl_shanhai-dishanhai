package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：操作引导可以跳过练习，模拟交易只取本次练习中的数量。 */
class ShopOperationGuideStateTest {
    @Test
    void screenUsesThePhysicalKeyGuardBeforeDispatchingNativeActivation() throws IOException {
        String screen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopOperationGuideScreen.java"));

        assertTrue(screen.contains("activationInput.reset("));
        assertTrue(screen.contains("activationInput.press(keyCode, enabled)"));
        assertTrue(screen.contains("activationInput.release(keyCode)"));
    }

    @Test
    void allFourStepsAreReachableWithoutCompletingExercises() {
        var guide = new ShopOperationGuideState();

        assertEquals(ShopOperationGuideState.Step.BROWSE, guide.step());
        assertFalse(guide.previous());
        assertTrue(guide.next());
        assertEquals(ShopOperationGuideState.Step.QUANTITY, guide.step());
        assertTrue(guide.next());
        assertEquals(ShopOperationGuideState.Step.CONFIRM, guide.step());
        assertTrue(guide.next());
        assertEquals(ShopOperationGuideState.Step.SHORTCUTS, guide.step());
        assertFalse(guide.next());
        assertTrue(guide.previous());
        assertEquals(ShopOperationGuideState.Step.CONFIRM, guide.step());
    }

    @Test
    void quantityUsesSamplePriceAndStopsAtBothBounds() {
        var guide = new ShopOperationGuideState();

        assertEquals(1, guide.quantity());
        assertEquals(3, guide.total());
        guide.decrease();
        assertEquals(1, guide.quantity());
        guide.increase();
        assertEquals(2, guide.quantity());
        assertEquals(6, guide.total());
        for (int i = 0; i < 100; i++) guide.increase();
        assertEquals(64, guide.quantity());
        assertEquals(192, guide.total());
        assertFalse(guide.canIncrease());
        for (int i = 0; i < 100; i++) guide.decrease();
        assertEquals(1, guide.quantity());
        assertFalse(guide.canDecrease());
    }

    @Test
    void selectionAndFeedbackAreLocalToTheGuideAndChangingQuantityResetsFeedback() {
        var guide = new ShopOperationGuideState();
        guide.selectExample();
        guide.next();
        guide.increase();
        guide.next();
        guide.confirmExample();
        guide.previous();

        assertTrue(guide.selected());
        assertTrue(guide.confirmed());
        guide.increase();
        assertFalse(guide.confirmed());
        assertEquals(3, guide.quantity());

        var reopened = new ShopOperationGuideState();
        assertFalse(reopened.selected());
        assertFalse(reopened.confirmed());
        assertEquals(1, reopened.quantity());
    }
}
