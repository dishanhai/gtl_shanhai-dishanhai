package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopCatalogCodecsTest {

    @Test
    void manifestRoundTripsWithoutMaterializingItems() throws Exception {
        Class<?> codecs = Class.forName("com.dishanhai.gt_shanhai.network.ShopCatalogCodecs");
        Method write = codecs.getMethod("writeManifest", FriendlyByteBuf.class, ShopCatalogManifest.class);
        Method read = codecs.getMethod("readManifest", FriendlyByteBuf.class);
        ShopCatalogManifest source = new ShopCatalogManifest(42L, true, List.of(
                new ShopCatalogManifest.Stub(7L, "无限盘区", "前期", "", "", false,
                        3, "disk", "超级磁盘", List.of("mod:disk"), "stable-7")), java.util.Map.of());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        write.invoke(null, buffer, source);
        ShopCatalogManifest decoded = (ShopCatalogManifest) read.invoke(null, buffer);

        assertEquals(42L, decoded.revision());
        assertEquals(1, decoded.stubs().size());
        assertEquals("mod:disk", decoded.stubs().get(0).goodsIds().get(0));
    }

    @Test
    void payloadDecoderRejectsTooManyEntriesBeforeAllocation() throws Exception {
        Class<?> codecs = Class.forName("com.dishanhai.gt_shanhai.network.ShopCatalogCodecs");
        Method read = codecs.getMethod("readPayloads", FriendlyByteBuf.class);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(17);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> read.invoke(null, buffer));
        assertInstanceOf(DecoderException.class, thrown.getCause());
    }
}
