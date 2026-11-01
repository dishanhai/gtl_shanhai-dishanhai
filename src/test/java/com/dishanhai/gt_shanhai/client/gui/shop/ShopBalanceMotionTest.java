package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：分筆顯示大整數增扣，動畫不改變帳戶餘額。 */
class ShopBalanceMotionTest {
    private static final ResourceLocation SPARK = new ResourceLocation("gt_shanhai", "spark");

    private ClientWalletAccount.BalanceChange change(BigInteger amount) {
        return new ClientWalletAccount.BalanceChange(SPARK, amount, 1000L);
    }

    @Test void rapidChangesAreStaggeredWithoutReplacingEarlierAmounts() {
        var motion = new ShopBalanceMotion();
        motion.addAll(List.of(change(BigInteger.TEN), change(BigInteger.ONE.negate())), 1000L);
        assertEquals(1, motion.visible(1100L, false).size());
        var views = motion.visible(1600L, false);
        assertEquals(2, views.size());
        assertEquals("+10", views.get(0).text());
        assertEquals("-1", views.get(1).text());
        assertNotEquals(views.get(0).lane(), views.get(1).lane());
        assertTrue(views.get(0).rise() > 0f);
    }

    @Test void lifetimeAndLaneSpacingAllowAtMostThreeVisibleChanges() {
        var motion = new ShopBalanceMotion();
        for (int i = 0; i < 80; i++) motion.addAll(List.of(change(BigInteger.ONE)), 1000L);
        assertTrue(motion.pendingCount() <= 32);
        for (long t = 1000; t < 20_000; t += 37) {
            var views = motion.visible(t, false);
            assertTrue(views.size() <= 3);
            assertEquals(views.size(), views.stream().map(ShopBalanceMotion.View::lane).distinct().count());
            assertTrue(views.stream().allMatch(v -> v.opacity() >= 0f && v.opacity() <= 1f));
        }
        assertTrue(motion.visible(60_000L, false).isEmpty());
    }

    @Test void reduceMotionStillShowsExactSignedAmountsWithoutMovement() {
        var motion = new ShopBalanceMotion();
        BigInteger huge = BigInteger.TEN.pow(90);
        motion.addAll(List.of(change(huge)), 1000L);
        var view = motion.visible(1000L, true).get(0);
        assertEquals("+" + huge, view.text());
        assertEquals(1f, view.opacity());
        assertEquals(0f, view.rise());
        motion.clear();
        assertTrue(motion.visible(1100L, true).isEmpty());
    }

    @Test void wrappedAmountsEnteringOrLeavingDoNotTeleportOtherLanes() {
        var motion = new ShopBalanceMotion();
        assertEquals(86f, motion.laneOffset(2, 39, 1000L, true));
        float before = motion.laneOffset(2, 39, 1100L, false);
        assertEquals(before, motion.laneOffset(2, 22, 1100L, false), 0.0001f);
        for (long t = 1110; t <= 1800; t += 10) motion.laneOffset(2, 22, t, false);
        assertEquals(52f, motion.laneOffset(2, 22, 1800L, false), 0.05f);
        assertEquals(86f, motion.laneOffset(2, 39, 1800L, true));
    }
}
