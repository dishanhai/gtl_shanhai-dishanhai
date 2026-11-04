package com.dishanhai.gt_shanhai.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海商店启用状态：两端消息类型、禁用值与 OP 权限位的二进制往返。 */
class ShopAvailabilityPacketCodecTest {
    @Test
    void queryDoesNotClaimOpPermission() {
        assertPacket(ShopAvailabilityPacket.query(), ShopAvailabilityPacket.Action.QUERY, false, false);
    }

    @Test
    void updateCarriesBothDisableAndReenableChoices() {
        assertPacket(ShopAvailabilityPacket.update(true), ShopAvailabilityPacket.Action.UPDATE, true, false);
        assertPacket(ShopAvailabilityPacket.update(false), ShopAvailabilityPacket.Action.UPDATE, false, false);
    }

    @Test
    void stateAndUpdateResultRoundTripAllPermissionAndAvailabilityCombinations() {
        for (ShopAvailabilityPacket.Action action : new ShopAvailabilityPacket.Action[]{
                ShopAvailabilityPacket.Action.STATE, ShopAvailabilityPacket.Action.RESULT}) {
            for (boolean disabled : new boolean[]{false, true}) {
                for (boolean op : new boolean[]{false, true}) {
                    FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.buffer());
                    try {
                        input.writeEnum(action);
                        input.writeBoolean(disabled);
                        input.writeBoolean(op);
                        ShopAvailabilityPacket packet = new ShopAvailabilityPacket(input);
                        assertFalse(input.isReadable());
                        assertPacket(packet, action, disabled, op);
                    } finally {
                        input.release();
                    }
                }
            }
        }
    }

    private static void assertPacket(ShopAvailabilityPacket packet, ShopAvailabilityPacket.Action action,
                                     boolean disabled, boolean op) {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf decoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(encoded);
            new ShopAvailabilityPacket(encoded).encode(decoded);
            assertEquals(action, decoded.readEnum(ShopAvailabilityPacket.Action.class));
            assertEquals(disabled, decoded.readBoolean());
            assertEquals(op, decoded.readBoolean());
            assertFalse(encoded.isReadable());
            assertFalse(decoded.isReadable());
        } finally {
            encoded.release();
            decoded.release();
        }
    }
}
