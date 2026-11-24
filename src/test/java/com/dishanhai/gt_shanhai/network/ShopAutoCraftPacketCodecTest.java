package com.dishanhai.gt_shanhai.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
    void requestRoundTripPreservesUnlockTargetAndStagePath() {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopAutoCraftRequestPacket(ShopAutoCraftRequestPacket.Target.STAGE,
                    0L, -1L, 1L, "无限盘区/前期", true, 25).encode(encoded);
            new ShopAutoCraftRequestPacket(encoded).encode(decoded);
            assertEquals(ShopAutoCraftRequestPacket.Target.STAGE, decoded.readEnum(ShopAutoCraftRequestPacket.Target.class));
            assertEquals(0L, decoded.readLong());
            assertEquals(-1L, decoded.readLong());
            assertEquals(1L, decoded.readVarLong());
            assertEquals("无限盘区/前期", decoded.readUtf(256));
            assertTrue(decoded.readBoolean());
            assertEquals(25, decoded.readVarInt());
            assertFalse(decoded.isReadable());
        } finally {
            encoded.release();
            decoded.release();
        }
    }

    @Test
    void planRoundTripPreservesSectionsIncludingTranslatableNamesAndLongQuantities() {
        UUID id = UUID.randomUUID();
        Component output = Component.translatable("gt_shanhai.shop.confirmation.auto_craft.item_quantity",
                Component.translatable("item.minecraft.iron_ingot"), "7000000000");
        Component used = Component.translatable("item.minecraft.raw_iron");
        Component note = Component.translatable("gt_shanhai.shop.confirmation.auto_craft.no_pattern");
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopAutoCraftPlanPacket(id, List.of(output), List.of(used), List.of(note), 37, 2)
                    .encode(encoded);
            new ShopAutoCraftPlanPacket(encoded).encode(decoded);
            assertEquals(id, decoded.readUUID());
            assertEquals(1, decoded.readVarInt());
            assertEquals(output, decoded.readComponent());
            assertEquals(1, decoded.readVarInt());
            assertEquals(used, decoded.readComponent());
            assertEquals(1, decoded.readVarInt());
            assertEquals(note, decoded.readComponent());
            assertEquals(37, decoded.readVarInt());
            assertEquals(1, decoded.readVarInt());
            assertEquals(2, decoded.readVarInt());
            assertTrue(decoded.readBoolean(), "方案預覽仍會開確認窗");
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
            new ShopAutoCraftPlanPacket(id, List.of(), List.of(), Collections.nCopies(600, Component.literal("x".repeat(500))), 78, 600)
                    .encode(buffer);
            assertEquals(id, buffer.readUUID());
            assertEquals(0, buffer.readVarInt());
            assertEquals(0, buffer.readVarInt());
            assertEquals(256, buffer.readVarInt());
            for (int i = 0; i < 255; i++) {
                assertEquals(Component.translatable("gt_shanhai.shop.confirmation.auto_craft.long_line")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), buffer.readComponent());
            }
            assertEquals(Component.translatable("gt_shanhai.shop.confirmation.auto_craft.more_lines", "345")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), buffer.readComponent());
            assertEquals(78, buffer.readVarInt());
            assertEquals(0, buffer.readVarInt());
            assertEquals(600, buffer.readVarInt());
            assertTrue(buffer.readBoolean());
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
            buffer.writeVarInt(257);
            assertThrows(DecoderException.class, () -> new ShopAutoCraftPlanPacket(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void costRequestRoundTripPreservesTheFeedbackRequestId() {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShopAutoCraftRequestPacket(ShopAutoCraftRequestPacket.Target.COST, 9L, 42L, 7L, "", true, 123).encode(encoded);
            new ShopAutoCraftRequestPacket(encoded).encode(decoded);
            assertEquals(ShopAutoCraftRequestPacket.Target.COST, decoded.readEnum(ShopAutoCraftRequestPacket.Target.class));
            assertEquals(9L, decoded.readLong());
            assertEquals(42L, decoded.readLong());
            assertEquals(7L, decoded.readVarLong());
            assertEquals("", decoded.readUtf(256));
            assertTrue(decoded.readBoolean());
            assertEquals(123, decoded.readVarInt());
            assertFalse(encoded.isReadable());
            assertFalse(decoded.isReadable());
        } finally {
            encoded.release();
            decoded.release();
        }
    }

    @Test
    void immediateSuccessAndFailureOnlyResolveFeedbackWithoutOpeningConfirmation() {
        for (boolean success : new boolean[]{true, false}) {
            FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
            FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new ShopAutoCraftPlanPacket(78, success).encode(encoded);
                new ShopAutoCraftPlanPacket(encoded).encode(decoded);
                assertEquals(new UUID(0L, 0L), decoded.readUUID());
                assertEquals(0, decoded.readVarInt());
                assertEquals(0, decoded.readVarInt());
                assertEquals(0, decoded.readVarInt());
                assertEquals(78, decoded.readVarInt());
                assertEquals(success ? 1 : 0, decoded.readVarInt());
                assertEquals(1, decoded.readVarInt());
                assertFalse(decoded.readBoolean());
                assertFalse(decoded.isReadable());
            } finally {
                encoded.release();
                decoded.release();
            }
        }
    }
}
