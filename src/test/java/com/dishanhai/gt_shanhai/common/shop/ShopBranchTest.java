package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopBranchTest {

    private static final ResourceLocation COIN = new ResourceLocation("minecraft", "diamond");

    private static ShopEntry entry(String goods, String category, long price) {
        return new ShopEntry(new ResourceLocation("minecraft", goods), 1, category, null,
                ShopCost.singleCoin(COIN, price), "", -1L);
    }

    private static List<ShopEntry> inCategory(List<ShopEntry> all, String category) {
        List<ShopEntry> out = new ArrayList<>();
        for (ShopEntry e : all) if (category.equals(e.getCategory())) out.add(e);
        return out;
    }

    @Test
    void pathHelpersHandleSubtreesAndDepth() {
        assertTrue(ShopBranch.inBranch("无限盘区/前期", "无限盘区/前期"));
        assertTrue(ShopBranch.inBranch("无限盘区/前期/元件", "无限盘区/前期"));
        assertEquals(false, ShopBranch.inBranch("无限盘区/前期2", "无限盘区/前期"));
        assertEquals("元件", ShopBranch.lastSegment("无限盘区/前期/元件"));
        assertEquals(3, ShopBranch.depth("a/b/c"));
        assertEquals("无限盘区/中期/元件/细分",
                ShopBranch.relocate("无限盘区/前期/元件/细分", "无限盘区/前期/元件", "无限盘区/中期/元件"));
    }

    @Test
    void copiesWholeSubtreeUnderEachStageAndLinksToMaster() {
        ShopEntry a = entry("stone", "无限盘区/前期/元件", 5);
        ShopEntry b = entry("dirt", "无限盘区/前期/元件/细分", 7);
        ShopEntry other = entry("gold_ingot", "无限盘区/前期/别的", 9);
        ShopBranch.CopyResult r = ShopBranch.copy(List.of(a, b, other), "无限盘区/前期/元件",
                List.of("无限盘区/中期", "无限盘区/后期"));
        assertEquals(4, r.created());
        assertEquals(7, r.entries().size());
        List<ShopEntry> mid = inCategory(r.entries(), "无限盘区/中期/元件");
        assertEquals(1, mid.size());
        assertEquals(a.getStableId(), mid.get(0).getBranchSource());
        assertNotEquals(a.getStableId(), mid.get(0).getStableId());
        assertEquals(1, inCategory(r.entries(), "无限盘区/后期/元件/细分").size());
        assertEquals(BigInteger.valueOf(5), mid.get(0).getCost().coins.get(COIN)); // 成本起步与主支一致
        assertTrue(inCategory(r.entries(), "无限盘区/中期").isEmpty(), "别的分支不被带走");
    }

    @Test
    void propagateTakesContentFromMasterButKeepsStageFieldsOfTheCopy() {
        ShopEntry master = entry("stone", "A/前期/元件", 5);
        ShopEntry copy = ShopBranch.copy(List.of(master), "A/前期/元件", List.of("A/中期")).entries().get(1);
        // 副本单独把成本改成 50
        ShopEntry pricedCopy = new ShopEntry(copy.getGoodsList(), copy.getCategory(),
                ShopCost.singleCoin(COIN, 50L), "", -1L, null, ShopEntry.RewardMode.NONE, null, false, "", "", "",
                "", ShopEntry.RewardMode.RANDOM, ShopEntry.TradeMode.BOTH, -1L, -1L, "", copy.getStableId())
                .withBranchSource(master.getStableId());
        // 主支改了商品、名称、描述、交易方向（沿用同一个 stableId）
        ShopEntry newMaster = new ShopEntry(
                List.of(ShopEntry.GoodsStack.of(new ResourceLocation("minecraft", "emerald"), 1, null)),
                master.getCategory(), master.getCost(), "新描述", -1L, null, ShopEntry.RewardMode.NONE, null,
                false, "", "", "新名字", "", ShopEntry.RewardMode.RANDOM, ShopEntry.TradeMode.BUY_ONLY,
                -1L, -1L, "", master.getStableId());
        List<ShopEntry> all = new ArrayList<>(List.of(newMaster, pricedCopy));
        int changed = ShopBranch.propagate(all, newMaster);
        ShopEntry synced = all.get(1);
        assertEquals(1, changed);
        assertEquals("emerald", synced.getGoodsId().getPath(), "商品内容跟随主支");
        assertEquals("新名字", synced.getDisplayName());
        assertEquals("新描述", synced.getDescription());
        assertEquals(ShopEntry.TradeMode.BUY_ONLY, synced.getTradeMode());
        assertEquals(BigInteger.valueOf(50), synced.getCost().coins.get(COIN), "副本自己的成本不被覆盖");
        assertEquals("A/中期/元件", synced.getCategory(), "副本留在自己的阶段");
        assertEquals(copy.getStableId(), synced.getStableId());
        assertEquals(master.getStableId(), synced.getBranchSource());
    }

    @Test
    void copyingAgainResyncsInsteadOfDuplicating() {
        ShopEntry master = entry("stone", "A/前期/元件", 5);
        List<ShopEntry> first = ShopBranch.copy(List.of(master), "A/前期/元件", List.of("A/中期")).entries();
        ShopBranch.CopyResult again = ShopBranch.copy(first, "A/前期/元件", List.of("A/中期"));
        assertEquals(0, again.created());
        assertEquals(1, again.synced());
        assertEquals(2, again.entries().size());
        // 主支后来新增了商品：再复制一次只补新增的
        List<ShopEntry> grown = new ArrayList<>(again.entries());
        grown.add(entry("dirt", "A/前期/元件", 3));
        ShopBranch.CopyResult third = ShopBranch.copy(grown, "A/前期/元件", List.of("A/中期"));
        assertEquals(1, third.created());
        assertEquals(1, third.synced());
    }

    @Test
    void skipsTargetsInsideTheSourceAndTooDeepEntries() {
        ShopEntry deep = entry("stone", "a/b/c/d", 1);
        ShopBranch.CopyResult r = ShopBranch.copy(List.of(deep), "a/b", List.of("a/b/c", "x/y/z"));
        // 目标在源子树内：直接忽略；x/y/z/b/c/d 共 6 层：跳过
        assertEquals(0, r.created());
        assertEquals(1, r.skipped());
        assertEquals(1, r.entries().size());
    }

    @Test
    void copyOfACopyFollowsTheRootMasterNotTheChain() {
        ShopEntry master = entry("stone", "A/前期/元件", 5);
        List<ShopEntry> step1 = ShopBranch.copy(List.of(master), "A/前期/元件", List.of("A/中期")).entries();
        List<ShopEntry> step2 = ShopBranch.copy(step1, "A/中期/元件", List.of("A/后期")).entries();
        ShopEntry last = inCategory(step2, "A/后期/元件").get(0);
        assertEquals(master.getStableId(), last.getBranchSource());
    }

    @Test
    void singleEntryBranchesIntoDifferentGroupsAndStaysLinked() {
        ShopEntry a = entry("stone", "无限盘区/前期", 5);
        ShopEntry other = entry("dirt", "无限盘区/前期", 7);
        ShopBranch.CopyResult r = ShopBranch.copyEntries(List.of(a, other), List.of(a.getStableId()),
                List.of("无限盘区/中期", "无限盘区/后期/细分"));
        assertEquals(2, r.created());
        assertEquals(4, r.entries().size());
        ShopEntry mid = inCategory(r.entries(), "无限盘区/中期").get(0);
        assertEquals("stone", mid.getGoodsId().getPath());
        assertEquals(a.getStableId(), mid.getBranchSource());
        assertEquals(1, inCategory(r.entries(), "无限盘区/后期/细分").size());
        assertEquals(1, inCategory(r.entries(), "无限盘区/前期").stream()
                .filter(e -> e.getGoodsId().getPath().equals("dirt")).count(), "没选的商品不动");
    }

    @Test
    void entryBranchSkipsItsOwnGroupAndResyncsOnRepeat() {
        ShopEntry a = entry("stone", "A/前期", 5);
        ShopBranch.CopyResult same = ShopBranch.copyEntries(List.of(a), List.of(a.getStableId()), List.of("A/前期"));
        assertEquals(0, same.created());
        assertEquals(1, same.skipped());
        List<ShopEntry> first = ShopBranch.copyEntries(List.of(a), List.of(a.getStableId()), List.of("A/中期")).entries();
        ShopBranch.CopyResult again = ShopBranch.copyEntries(first, List.of(a.getStableId()), List.of("A/中期"));
        assertEquals(0, again.created());
        assertEquals(1, again.synced());
        assertEquals(2, again.entries().size());
    }
}
