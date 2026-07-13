package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogEntryPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
