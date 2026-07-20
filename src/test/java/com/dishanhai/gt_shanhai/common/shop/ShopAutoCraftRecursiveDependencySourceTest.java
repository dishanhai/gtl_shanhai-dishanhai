package com.dishanhai.gt_shanhai.common.shop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShopAutoCraftRecursiveDependencySourceTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopAutoCraft.java");

    @Test
    void autoCraftExpandsRecursiveShopGoodsDependencies() throws Exception {
        String source = Files.readString(SOURCE);
        String expand = extractBlock(source, "private static boolean expandShopDependencies(");

        assertAll(
                () -> assertTrue(source.contains("MAX_DEPENDENCY_EXPANSION_ROUNDS"),
                        "递归补单必须有轮次上限，避免商店商品链成环时无限计算"),
                () -> assertTrue(source.contains("MAX_PLAN_ITEMS"),
                        "递归补单必须有目标数量上限，避免一张巨大计划把服务端拖死"),
                () -> assertTrue(expand.contains("buildShopGoodsKeyIndex()"),
                        "只能把商店商品链上的中间物加入补单，不能把所有可合成基础材料都加入"),
                () -> assertTrue(expand.contains("pi.result.usedItems()"),
                        "必须从 AE 计划实际消耗的中间物里发现递归依赖"),
                () -> assertTrue(expand.contains("craftingService.isCraftable(key)"),
                        "递归加入前仍要确认 AE 有对应样板"),
                () -> assertTrue(expand.contains("session.plannedAmounts.getOrDefault(key, 0L)"),
                        "同一种中间物已计划数量足够时不能重复追加"),
                () -> assertTrue(expand.contains("session.expansionRound++"),
                        "追加递归项后必须重新进入下一轮计算"),
                () -> assertTrue(source.contains("CALCULATING.put(uuid, session);"),
                        "发现递归依赖后应回到计算阶段，而不是直接弹确认框"));
    }

    @Test
    void shopGoodsIndexIncludesItemAndFluidGoodsButOnlyBuyableEntries() throws Exception {
        String source = Files.readString(SOURCE);
        String index = extractBlock(source, "private static Map<AEKey, ShopEntry.GoodsStack> buildShopGoodsKeyIndex()");
        String goodsKey = extractBlock(source, "private static AEKey goodsKey(");

        assertAll(
                () -> assertTrue(index.contains("ShopConfig.getEntries()")),
                () -> assertTrue(index.contains("!entry.allowsBuy()")),
                () -> assertTrue(index.contains("!entry.isStructurallyValid()")),
                () -> assertTrue(index.contains("entry.getGoodsList()")),
                () -> assertTrue(index.contains("index.putIfAbsent(key, goods)"),
                        "同一商品有多条商店项时保留最前条目的显示名，符合商店列表优先级"),
                () -> assertTrue(goodsKey.contains("AEItemKey.of(stack)")),
                () -> assertTrue(goodsKey.contains("AEFluidKey.of(fluid)")));
    }

    @Test
    void confirmationSubmitsDependenciesBeforeConsumers() throws Exception {
        String source = Files.readString(SOURCE);
        String confirm = extractBlock(source, "public static void confirmPlan(");
        String ordered = extractBlock(source, "private static List<PlanItem> orderedSubmittableItems(");
        String dfs = extractBlock(source, "private static void appendDependenciesFirst(");

        assertAll(
                () -> assertTrue(confirm.contains("orderedSubmittableItems(session)"),
                        "确认提交必须使用依赖排序后的计划列表"),
                () -> assertTrue(ordered.contains("byKey.computeIfAbsent(submittable.get(i).key"),
                        "排序前要按目标 key 建索引，才能判断某计划是否依赖另一计划"),
                () -> assertTrue(dfs.contains("result.usedItems()"),
                        "依赖关系来自 AE 计划 usedItems：A 消耗 B，则 B 必须先提交"),
                () -> assertTrue(dfs.contains("appendDependenciesFirst(dependencyIndex"),
                        "排序必须递归地先加入底层依赖"),
                () -> assertTrue(dfs.contains("if (state[index] == 1)"),
                        "依赖图成环时必须有访问状态保护"));
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
