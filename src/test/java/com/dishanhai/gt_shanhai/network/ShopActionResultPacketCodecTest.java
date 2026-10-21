package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopActionOutcome;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 详情页买卖结构化回执与请求号的二进制往返。 */
class ShopActionResultPacketCodecTest {

    @Test
    void resultRoundTripKeepsEveryField() {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopActionResultPacket(42, ShopActionPacket.Action.SELL, 7L, 10L, 3L, "已售 3 份，商品不足")
                    .encode(encoded);
            new ShopActionResultPacket(encoded).encode(decoded);
            assertEquals(42, decoded.readVarInt());
            assertEquals(ShopActionPacket.Action.SELL, decoded.readEnum(ShopActionPacket.Action.class));
            assertEquals(7L, decoded.readLong());
            assertEquals(10L, decoded.readVarLong());
            assertEquals(3L, decoded.readVarLong());
            assertEquals(ShopActionOutcome.PARTIAL, decoded.readEnum(ShopActionOutcome.class));
            assertEquals("已售 3 份，商品不足", decoded.readUtf(256));
            assertFalse(decoded.isReadable());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
            decoded.release();
        }
    }

    @Test
    void overlongReasonsStillEncode() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            String reason = "前".repeat(400);
            new ShopActionResultPacket(1, ShopActionPacket.Action.BUY, 0L, 1L, 0L, reason).encode(buf);
            ShopActionResultPacket back = new ShopActionResultPacket(buf);
            assertEquals(256, back.reason().length());
            assertEquals(ShopActionOutcome.FAILED, back.outcome());
        } finally {
            buf.release();
        }
    }

    @Test
    void actionPacketCarriesTheRequestIdAsItsLastField() {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopActionPacket(ShopActionPacket.Action.BUY, 9L, 3L, 5L, true, false, -1, false, 123)
                    .encode(encoded);
            new ShopActionPacket(encoded).encode(decoded);
            assertEquals(ShopActionPacket.Action.BUY, decoded.readEnum(ShopActionPacket.Action.class));
            assertEquals(9L, decoded.readLong());
            assertEquals(3L, decoded.readLong());
            assertEquals(5L, decoded.readVarLong());
            assertTrue(decoded.readBoolean());
            assertFalse(decoded.readBoolean());
            assertEquals(-1, decoded.readInt());
            assertFalse(decoded.readBoolean());
            assertEquals(123, decoded.readVarInt());
            assertFalse(decoded.isReadable());
        } finally {
            encoded.release();
            decoded.release();
        }
    }

    @Test
    void olderConstructorsAskForNoResult() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopActionPacket(ShopActionPacket.Action.BUY, 1L, 2L, 3L, false, false, -1, true).encode(buf);
            ShopActionPacket back = new ShopActionPacket(buf);
            assertEquals(0, back.requestId(), "购物车等旧入口不要结构化回执");
        } finally {
            buf.release();
        }
    }
}
