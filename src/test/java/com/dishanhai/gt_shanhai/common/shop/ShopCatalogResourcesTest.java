package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：服务端目录确实采集全部产出和成本，而非只测试手工构造的元数据。 */
class ShopCatalogResourcesTest {
    private ResourceLocation id(String value) { return new ResourceLocation(value); }

    @Test
    void goodsMetadataIncludesSecondaryFluidAndExcludesWirelessEnergy() {
        var goods = List.of(ShopEntry.GoodsStack.of(id("mod:iron"), 17, null),
                ShopEntry.GoodsStack.ofFluid(id("mod:water"), 8000),
                ShopEntry.GoodsStack.ofEnergy(BigInteger.TEN));
        var entry = new ShopEntry(goods, "test", new ShopCost(null, null, null), "", -1,
                List.of(), ShopEntry.RewardMode.NONE, List.of(), false, "", "", "", "");
        var resources = ShopCatalogSnapshot.goodsResources(entry);
        assertEquals(2, resources.size());
        assertEquals(new ShopResourceFilter.Resource(false, "mod:iron", "mod:iron\u0000"), resources.get(0));
        assertEquals(new ShopResourceFilter.Resource(true, "mod:water", ""), resources.get(1));
    }

    @Test
    void costsIncludeWalletCoinsPhysicalItemsAndFluidsButNotSparkOrEu() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("variant", "specific");
        var cost = new ShopCost(BigInteger.TEN, Map.of(id("mod:coin"), BigInteger.ONE),
                List.of(new ExchangeEntry.Ingredient(id("mod:disk"), false, 9000, nbt),
                        new ExchangeEntry.Ingredient(id("mod:water"), true, Long.MAX_VALUE)), BigInteger.TEN);
        var resources = ShopCatalogSnapshot.costResources(cost);
        assertEquals(3, resources.size());
        assertTrue(resources.contains(new ShopResourceFilter.Resource(false, "mod:coin", "")));
        assertTrue(resources.contains(new ShopResourceFilter.Resource(false, "mod:disk", ShopGoodsIdentity.key("mod:disk", nbt))));
        assertTrue(resources.contains(new ShopResourceFilter.Resource(true, "mod:water", "")));
    }

    @Test
    void layoutCarriesBothResourceListsToEveryChunkWithoutLosingTypes() {
        var item = new ShopResourceFilter.Resource(false, "mod:shared", "");
        var fluid = new ShopResourceFilter.Resource(true, "mod:shared", "");
        var descriptor = new ShopCatalogSnapshot.Descriptor("a/b", false, "", "", List.of(), 1,
                "stable", "", List.of(), List.of(item, fluid), List.of(fluid));
        var stub = ShopCatalogSnapshot.layout(List.of(descriptor), 1, 1).stubs().get(0);
        assertEquals(List.of(item, fluid), stub.goodsResources());
        assertEquals(List.of(fluid), stub.costResources());
        assertThrows(UnsupportedOperationException.class, () -> stub.goodsResources().clear());
    }

    @Test
    void unresolvedFtbqTableDoesNotIndexItsDisplayPlaceholderAsAProduct() {
        var entry = new ShopEntry(List.of(ShopEntry.GoodsStack.of(id("mod:placeholder"), 1, null)),
                "test", new ShopCost(null, null, null), "", -1, List.of(), ShopEntry.RewardMode.FTBQ,
                List.of(), false, "", "", "", "01");
        assertEquals(ShopEntry.RewardMode.FTBQ, entry.getRewardMode());
        // 第三方 FTBQ jar 在普通 JUnit 中仍为 SRG 名称；注入空表边界，测试真实分支而不启动 Forge。
        assertEquals(List.of(), ShopCatalogSnapshot.goodsResources(entry, List::of));
    }
}
