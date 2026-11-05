package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：仅使用目录元数据验证筛选，不需要加载商品分块或注册表。 */
class ShopResourceFilterTest {
    private Class<?> filter() {
        return assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.common.shop.ShopResourceFilter"), "缺少资源筛选策略");
    }

    private Object resource(boolean fluid, String id, String identity) throws Exception {
        filter();
        return Class.forName("com.dishanhai.gt_shanhai.common.shop.ShopResourceFilter$Resource")
                .getConstructor(boolean.class, String.class, String.class).newInstance(fluid, id, identity);
    }

    private boolean matches(List<?> selected, List<?> candidates) throws Exception {
        return (boolean) filter().getMethod("matches", List.class, List.class)
                .invoke(null, selected, candidates);
    }

    private ShopCatalogManifest.Stub stub(long key, boolean hidden, List<?> goods, List<?> costs) throws Exception {
        return assertDoesNotThrow(() -> ShopCatalogManifest.Stub.class.getConstructor(
                long.class, String.class, String.class, String.class, String.class, boolean.class,
                int.class, String.class, String.class, List.class, String.class, String.class, List.class,
                List.class, List.class).newInstance(key, "category", "", "", "", hidden,
                0, "", "", List.of(), "stable-" + key, "", List.of(), goods, costs));
    }

    @SuppressWarnings("unchecked")
    private List<Long> keys(List<ShopCatalogManifest.Stub> stubs, List<?> selected, boolean costs) throws Exception {
        return (List<Long>) filter().getMethod("keys", List.class, List.class, boolean.class)
                .invoke(null, stubs, selected, costs);
    }

    @Test
    void sameRegistryIdNeverConfusesAnItemWithAFluid() throws Exception {
        Object item = resource(false, "minecraft:water", "");
        Object fluid = resource(true, "minecraft:water", "");
        assertFalse(matches(List.of(item), List.of(fluid)));
        assertFalse(matches(List.of(fluid), List.of(item)));
        assertTrue(matches(List.of(fluid), List.of(fluid)));
    }

    @Test
    void multipleSelectionsUseAnyMatchAndEmptySelectionDoesNotRestrict() throws Exception {
        Object iron = resource(false, "minecraft:iron_ingot", "");
        Object water = resource(true, "minecraft:water", "");
        assertTrue(matches(List.of(iron, water), List.of(water)));
        assertTrue(matches(List.of(), List.of()));
        assertFalse(matches(List.of(iron), List.of()));
    }

    @Test
    void plainItemSelectionMatchesVariantsButTaggedSelectionKeepsStableIdentity() throws Exception {
        Object plain = resource(false, "mod:disk", "");
        Object first = resource(false, "mod:disk", "mod:disk\u0000first");
        Object second = resource(false, "mod:disk", "mod:disk\u0000second");
        assertTrue(matches(List.of(plain), List.of(first)));
        assertTrue(matches(List.of(first), List.of(first)));
        assertFalse(matches(List.of(first), List.of(second)));
        assertTrue(matches(List.of(first), List.of(plain)), "未限制NBT的购买材料应接受带NBT的物品");
    }

    @Test
    void modeSwitchUsesSeparateMetadataAndExcludesHiddenEntriesWithoutLoadingChunks() throws Exception {
        Object iron = resource(false, "minecraft:iron_ingot", "");
        Object water = resource(true, "minecraft:water", "");
        var first = stub(9, false, List.of(iron), List.of(water));
        var second = stub(3, false, List.of(water), List.of(iron));
        var hidden = stub(7, true, List.of(iron), List.of(iron));
        assertEquals(List.of(9L), keys(List.of(first, second, hidden), List.of(iron), false));
        assertEquals(List.of(3L), keys(List.of(first, second, hidden), List.of(iron), true));
        assertEquals(List.of(9L, 3L), keys(List.of(first, second, hidden), List.of(), false));
        assertEquals(List.of(), keys(List.of(second), List.of(iron), false));
    }

    @Test
    void resourceListsSurviveManifestWireRoundTrip() throws Exception {
        Object iron = resource(false, "minecraft:iron_ingot", "minecraft:iron_ingot\u0000abc");
        Object water = resource(true, "minecraft:water", "");
        var source = new ShopCatalogManifest(55, true,
                List.of(stub(9, false, List.of(iron, water), List.of(water))), java.util.Map.of());
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            Class<?> codec = Class.forName("com.dishanhai.gt_shanhai.network.ShopCatalogCodecs");
            codec.getMethod("writeManifest", net.minecraft.network.FriendlyByteBuf.class, ShopCatalogManifest.class)
                    .invoke(null, buffer, source);
            var decoded = (ShopCatalogManifest) codec.getMethod("readManifest", net.minecraft.network.FriendlyByteBuf.class)
                    .invoke(null, buffer);
            assertEquals(source.stubs(), decoded.stubs());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
