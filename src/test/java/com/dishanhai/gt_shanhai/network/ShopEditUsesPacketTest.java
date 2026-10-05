package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCost;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShopEditUsesPacketTest {

    private ShopEditPacket packet() {
        return new ShopEditPacket(ShopEditPacket.Action.EDIT,
                List.of(ShopEntry.GoodsStack.of(new ResourceLocation("minecraft", "stone"), 1, null)),
                ShopEntry.DEFAULT_CATEGORY, "",
                ShopCost.singleCoin(new ResourceLocation("minecraft", "diamond"), 1L),
                null, "", -1, 20L, List.of(), ShopEntry.RewardMode.NONE, List.of(), false,
                "", "", "", "", ShopEntry.RewardMode.RANDOM, ShopEntry.TradeMode.BOTH,
                -1L, -1L, "", 10L, 0L, 0, -1L, -1L, List.of());
    }

    private ShopEditPacket roundTrip(ShopEditPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            ShopEditPacket decoded = new ShopEditPacket(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    @Test
    void untouchedSaveFieldIsNotAnImplicitReset() {
        ShopEditPacket decoded = roundTrip(packet());
        assertEquals(20L, decoded.serverUses());
        assertNull(decoded.saveUses());
    }

    @Test
    void saveOverrideAndServerBaselineRoundTripIndependently() {
        ShopEditPacket decoded = roundTrip(packet().withSaveUses(3L));
        assertEquals(20L, decoded.serverUses());
        assertEquals(3L, decoded.saveUses().longValue());
    }

    @Test
    void unlimitedSaveOverrideIsDistinctFromAbsentField() {
        ShopEditPacket decoded = roundTrip(packet().withSaveUses(-1L));
        assertEquals(20L, decoded.serverUses());
        assertEquals(-1L, decoded.saveUses().longValue());
    }
}
