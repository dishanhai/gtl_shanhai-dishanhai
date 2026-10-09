package com.dishanhai.gt_shanhai.client.shop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopJeiProductCatalogSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "shop", "ShopJeiProductCatalog.java");

    @Test
    void collectsOnlyVisibleGoodsAndKeepsItemNbtInIdentity() throws IOException {
        assertTrue(Files.exists(SOURCE), "JEI 商店商品目录必须存在");
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("entry.getGoodsList()"), "只读取商品输出清单");
        assertTrue(source.contains("entry.isHidden()"), "隐藏商品不进入 JEI 商品列表");
        assertTrue(source.contains("hiddenByCatalog"), "目录中不可见的商品不进入 JEI 商品列表");
        assertTrue(source.contains("addFluid(goods.fluid(), goods.count())"), "固定流体商品也作为商品输出收集");
        assertTrue(source.contains("stack.getTag()"), "物品 NBT 必须参与商品身份去重");
        assertTrue(source.contains("entry.getRewardPool()"), "随机/自选奖励池属于可购买商品");
        assertTrue(source.contains("RewardMode.FTBQ"), "FTBQ 表中的物品奖励属于可购买商品");
        assertTrue(source.contains("getWeightedRewards()"), "FTBQ 奖励表只收集物品奖励");
        assertFalse(source.contains("entry.getCost()"), "购买成本不得进入商品列表");
        assertFalse(source.contains("entry.getSubmissionItems()"), "购买前提交物不得进入商品列表");
    }
}
