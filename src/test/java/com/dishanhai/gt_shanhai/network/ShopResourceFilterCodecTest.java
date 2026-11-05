package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.common.shop.ShopResourceFilter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：大型奖励表可同步，越界资源数量在分配前拒绝。 */
class ShopResourceFilterCodecTest {
    private ShopCatalogManifest manifest(List<ShopResourceFilter.Resource> resources) {
        return new ShopCatalogManifest(1, true, List.of(new ShopCatalogManifest.Stub(
                0, "test", "", "", "", false, 0, "", "", List.of(), "stable", "", List.of(),
                resources, List.of())), Map.of());
    }

    @Test
    void rewardTableWithMoreThan256DistinctResourcesRoundTrips() {
        var resources = IntStream.range(0, 300).mapToObj(i ->
                new ShopResourceFilter.Resource(false, "mod:reward_" + i, "mod:reward_" + i + "\u0000")).toList();
        var source = manifest(resources);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ShopCatalogCodecs.writeManifest(buffer, source);
            assertTrue(buffer.readableBytes() < 64 * 1024);
            assertEquals(source.stubs(), ShopCatalogCodecs.readManifest(buffer).stubs());
        } finally { buffer.release(); }
    }

    @Test
    void encoderRejectsResourceCountOverTheHardLimit() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(EncoderException.class, () -> ShopCatalogCodecs.writeManifest(buffer,
                    manifest(java.util.Collections.nCopies(65_537,
                            new ShopResourceFilter.Resource(true, "mod:water", "")))));
        } finally { buffer.release(); }
    }

    @Test
    void decoderRejectsNegativeOrOversizedResourceCountsBeforeAllocation() {
        for (int count : new int[]{-1, 65_537}) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeLong(1);
                buffer.writeBoolean(true);
                buffer.writeVarInt(1).writeVarLong(0);
                buffer.writeUtf("test").writeUtf("").writeUtf("").writeUtf("");
                buffer.writeBoolean(false);
                buffer.writeVarInt(0).writeUtf("").writeUtf("").writeVarInt(0);
                buffer.writeUtf("stable").writeUtf("").writeVarInt(0).writeVarInt(count);
                assertThrows(DecoderException.class, () -> ShopCatalogCodecs.readManifest(buffer));
            } finally { buffer.release(); }
        }
    }
}
