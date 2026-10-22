package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧式跳转称呼 → stableId 的一次性迁移规则（山海署名）。 */
class ShopLinkMigrationTest {

    private static Map<Integer, String> run(List<String> ids, List<String> keys, List<String> tos) {
        return ShopLinkMigration.rewrites(ids, keys, tos);
    }

    @Test
    void legacyAliasIsRewrittenToTheTargetsStableId() {
        assertEquals(Map.of(0, "b"), run(List.of("a", "b"), List.of("", "ore"), List.of("ore", "")));
    }

    @Test
    void firstEntryWinsWhenTwoShareAnAlias() {
        assertEquals(Map.of(2, "a"), run(List.of("a", "b", "c"), List.of("dup", "dup", ""), List.of("", "", "dup")),
                "与客户端 putIfAbsent 一致：先出现的认领称呼");
    }

    @Test
    void alreadyStableIdIsLeftAlone() {
        assertTrue(run(List.of("a", "b"), List.of("", ""), List.of("b", "")).isEmpty());
    }

    @Test
    void stableIdWinsWhenAnotherEntryClaimsItAsAnAlias() {
        assertTrue(run(List.of("a", "b", "c"), List.of("b", "", ""), List.of("", "", "b")).isEmpty(),
                "跳转值已经是稳定身份时，不被另一条目的同名称呼抢走");
    }

    @Test
    void unresolvedAliasIsKeptForTheClientToReport() {
        assertTrue(run(List.of("a"), List.of(""), List.of("ghost")).isEmpty(), "解析不到的原样保留，客户端显示「找不到目标」");
    }

    @Test
    void whitespaceAndBlanksAreIgnored() {
        assertEquals(Map.of(1, "a"), run(List.of("a", "b"), List.of(" ore ", ""), List.of("", "  ore ")));
        assertTrue(run(List.of("a"), List.of("ore"), List.of("   ")).isEmpty());
    }

    @Test
    void unicodeWhitespaceDoesNotMergeDistinctAliases() {
        assertEquals(Map.of(2, "b"), run(List.of("a", "b", "c"), List.of("\u3000ore\u3000", "ore", ""),
                List.of("", "", "ore")), "山海：沿用 Java trim 身份口径，全形空白称呼与普通称呼不同");
    }

    @Test
    void unicodeWhitespaceInTheTargetStillFindsItsExactAlias() {
        assertEquals(Map.of(2, "a"), run(List.of("a", "b", "c"), List.of("\u3000ore\u3000", "ore", ""),
                List.of("", "", "\u3000ore\u3000")), "山海：目标保留全形空白，仍引用相同称呼的商品");
    }

    @Test
    void entriesWithoutStableIdDoNotClaimAliases() {
        assertTrue(run(Arrays.asList("", "b"), List.of("ore", ""), List.of("", "ore")).isEmpty());
    }

    @Test
    void nullFieldsAreTreatedAsBlank() {
        assertEquals(Map.of(1, "a"), run(Arrays.asList("a", null, "b"), Arrays.asList("ore", "missing", null),
                Arrays.asList(null, "  ore ", "missing")));
    }

    @Test
    void mismatchedListsUseTheShortestLength() {
        assertEquals(Map.of(0, "b"), run(List.of("a", "b"), List.of("", "ore"), List.of("ore", "", "ore")));
    }
}
