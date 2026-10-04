package com.dishanhai.gt_shanhai.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Binary round trips for exact shop plan identity (author: dishanhai). */
class ShopAutoCraftPacketCodecTest {
    @Test
    void confirmationAndCancellationKeepTheExactPlanId() {
        UUID id = UUID.randomUUID();
        for (boolean confirm : new boolean[]{true, false}) {
            FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
            FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new ShopAutoCraftConfirmPacket(id, confirm).encode(encoded);
                new ShopAutoCraftConfirmPacket(encoded).encode(decoded);
                assertEquals(id, decoded.readUUID());
                assertEquals(confirm, decoded.readBoolean());
                assertFalse(decoded.isReadable());
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
                decoded.release();
            }
        }
    }

    @Test
    void planRoundTripPreservesItsIdentityAndTargetQuantities() {
        UUID id = UUID.randomUUID();
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopAutoCraftPlanPacket(id, true, List.of("wafer x7000000000"), List.of("missing pattern"))
                    .encode(encoded);
            new ShopAutoCraftPlanPacket(encoded).encode(decoded);
            assertEquals(id, decoded.readUUID());
            assertTrue(decoded.readBoolean());
            assertEquals(1, decoded.readVarInt());
            assertEquals("wafer x7000000000", decoded.readUtf(256));
            assertEquals(1, decoded.readVarInt());
            assertEquals("missing pattern", decoded.readUtf(256));
            assertFalse(decoded.isReadable());
        } finally {
            encoded.release();
            decoded.release();
        }
    }

    @Test
    void hugePreviewIsBoundedWithoutLosingThePlanId() {
        UUID id = UUID.randomUUID();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopAutoCraftPlanPacket(id, false, Collections.nCopies(600, "x".repeat(300)), List.of())
                    .encode(buffer);
            assertEquals(id, buffer.readUUID());
            assertFalse(buffer.readBoolean());
            assertEquals(512, buffer.readVarInt());
            for (int i = 0; i < 512; i++) assertEquals(256, buffer.readUtf(256).length());
            assertEquals(0, buffer.readVarInt());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidPreviewLineCountIsRejectedAfterReadingIdentity() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(UUID.randomUUID());
            buffer.writeBoolean(false);
            buffer.writeVarInt(513);
            assertThrows(DecoderException.class, () -> new ShopAutoCraftPlanPacket(buffer));
        } finally {
            buffer.release();
        }
    }
}
