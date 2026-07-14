package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogEntryPayload;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogPacketCodecTest {

    @Test
    void chunkRequestRoundTripsIdentity() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.network.ShopCatalogChunkRequestPacket");
        Object source = type.getConstructor(long.class, long.class, int.class)
                .newInstance(9L, 12L, 3);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        type.getMethod("encode", FriendlyByteBuf.class).invoke(source, buffer);
        Object decoded = type.getConstructor(FriendlyByteBuf.class).newInstance(buffer);

        assertEquals(9L, type.getMethod("revision").invoke(decoded));
        assertEquals(12L, type.getMethod("requestId").invoke(decoded));
        assertEquals(3, type.getMethod("chunkId").invoke(decoded));
    }

    @Test
    void chunkResponseRoundTripsPureJsonPayloads() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.network.ShopCatalogChunkPacket");
        Constructor<?> constructor = type.getConstructor(
                long.class, long.class, int.class, List.class);
        Object source = constructor.newInstance(9L, 12L, 3,
                List.of(new ShopCatalogEntryPayload(4L, "{\"goods\":\"minecraft:stone\"}")));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        Method encode = type.getMethod("encode", FriendlyByteBuf.class);
        encode.invoke(source, buffer);
        Object decoded = type.getConstructor(FriendlyByteBuf.class).newInstance(buffer);

        assertEquals(9L, type.getMethod("revision").invoke(decoded));
        assertEquals(12L, type.getMethod("requestId").invoke(decoded));
        assertEquals(3, type.getMethod("chunkId").invoke(decoded));
        assertEquals(1, ((List<?>) type.getMethod("entries").invoke(decoded)).size());
    }

    @Test
    void manifestRoundTripsCatalogStructure() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.network.ShopCatalogManifestPacket");
        ShopCatalogManifest manifest = new ShopCatalogManifest(15L, true, List.of(
                new ShopCatalogManifest.Stub(7L, "材料", "矿物", false,
                        2, "iron", "铁锭", List.of("minecraft:iron_ingot"), "stable-7")));
        Object source = type.getConstructor(ShopCatalogManifest.class).newInstance(manifest);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        type.getMethod("encode", FriendlyByteBuf.class).invoke(source, buffer);
        Object decoded = type.getConstructor(FriendlyByteBuf.class).newInstance(buffer);

        ShopCatalogManifest result = (ShopCatalogManifest) type.getMethod("manifest").invoke(decoded);
        assertEquals(15L, result.revision());
        assertTrue(result.ready());
        assertEquals(1, result.stubs().size());
        assertEquals(7L, result.stubs().get(0).entryKey());
        assertEquals("矿物", result.stubs().get(0).sub());
    }

    @Test
    void permanentRemainingStateRoundTripsAbsoluteValue() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.network.ShopCatalogStatePacket");
        Object source = type.getConstructor(long.class, long.class, long.class)
                .newInstance(15L, 7L, 23L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        type.getMethod("encode", FriendlyByteBuf.class).invoke(source, buffer);
        Object decoded = type.getConstructor(FriendlyByteBuf.class).newInstance(buffer);

        assertEquals(15L, type.getMethod("revision").invoke(decoded));
        assertEquals(7L, type.getMethod("entryKey").invoke(decoded));
        assertEquals(23L, type.getMethod("remainingUses").invoke(decoded));
    }
}
