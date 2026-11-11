package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopPersonalHidden;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPersonalHiddenPacketTest {
    @Test
    void syncRoundTripsOnlyTheRecipientsHiddenIds() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new ShopPersonalHiddenSyncPacket(Set.of("stable-a", "stable-b"));
            packet.encode(buffer);
            assertEquals(packet, new ShopPersonalHiddenSyncPacket(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsAnInvalidCountBeforeReadingIds() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(ShopPersonalHidden.MAX_ENTRIES + 1);
            assertThrows(IllegalArgumentException.class, () -> new ShopPersonalHiddenSyncPacket(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void editAccessSyncDoesNotReopenTheScreenOrResendTheCatalog() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        var copy = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ShopOpenPacket.editAccess(true, false).encode(buffer);
            new ShopOpenPacket(buffer).encode(copy);
            assertEquals(0, buffer.readableBytes());
            assertTrue(copy.readBoolean());
            assertFalse(copy.readBoolean());
            assertFalse(copy.readBoolean());
            assertEquals(0, copy.readableBytes());
        } finally {
            buffer.release();
            copy.release();
        }
    }
}
