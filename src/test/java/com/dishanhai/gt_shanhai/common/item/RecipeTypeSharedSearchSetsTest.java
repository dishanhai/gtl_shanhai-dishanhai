package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypeSharedSearchSetsTest {

    @Test
    void parseGroupsSplitsEntryMembersOnCommaSemicolonAndWhitespace() {
        Map<String, Set<String>> groups = RecipeTypeSharedSearchSets.parseGroups(List.of(
                "gtceu:chemical_reactor, gtceu:large_chemical_reactor; gtceu:dummy_type"));

        assertEquals(Set.of("gtceu:chemical_reactor", "gtceu:large_chemical_reactor", "gtceu:dummy_type"),
                groups.get("gtceu:chemical_reactor"),
                "同一条目内的所有类型必须归入同一组");
        assertSame(groups.get("gtceu:chemical_reactor"), groups.get("gtceu:dummy_type"),
                "组内每个成员都必须指向同一个集合实例");
    }

    @Test
    void parseGroupsMergesEntriesSharingAMemberTransitively() {
        Map<String, Set<String>> groups = RecipeTypeSharedSearchSets.parseGroups(List.of(
                "a:x,a:y",
                "a:z,a:w",
                "a:y,a:z"));

        Set<String> merged = groups.get("a:x");
        assertEquals(Set.of("a:x", "a:y", "a:z", "a:w"), merged,
                "跨条目共享成员时必须传递合并为一组");
        assertSame(merged, groups.get("a:w"), "合并后所有成员都指向同一组");
    }

    @Test
    void parseGroupsIgnoresSingleMemberAndBlankEntries() {
        Map<String, Set<String>> groups = RecipeTypeSharedSearchSets.parseGroups(List.of(
                "gtceu:lonely_type",
                "   ",
                "gtceu:a,,gtceu:a"));

        assertTrue(groups.isEmpty(), "单成员组没有共享语义，必须忽略");
    }

    @Test
    void parseGroupsNormalizesCaseAndSurroundingWhitespace() {
        Map<String, Set<String>> groups = RecipeTypeSharedSearchSets.parseGroups(List.of(
                "  GTCEU:Chemical_Reactor ,gtceu:large_chemical_reactor"));

        Set<String> group = groups.get("gtceu:chemical_reactor");
        assertNotNull(group, "配置里的大小写/空白差异必须被归一化");
        assertTrue(group.contains("gtceu:large_chemical_reactor"));
    }

    @Test
    void parseGroupsOfNullOrEmptyListIsEmpty() {
        assertTrue(RecipeTypeSharedSearchSets.parseGroups(null).isEmpty());
        assertTrue(RecipeTypeSharedSearchSets.parseGroups(List.of()).isEmpty());
    }

    @Test
    void sameNonEmptyTypeIdIsAlwaysShared() {
        assertTrue(RecipeTypeSharedSearchSets.isShared("gtceu:chemical_reactor", "gtceu:chemical_reactor"),
                "同一类型自身恒为共享");
        assertFalse(RecipeTypeSharedSearchSets.isShared("", "gtceu:chemical_reactor"));
        assertFalse(RecipeTypeSharedSearchSets.isShared(null, "gtceu:chemical_reactor"));
        assertFalse(RecipeTypeSharedSearchSets.isShared("gtceu:chemical_reactor", ""));
    }

    @Test
    void defaultConfigShipsTheChemicalReactorPair() {
        List<? extends String> defaults = DShanhaiConfig.COMMON.recipeTypeSharedSearchSets.getDefault();

        assertEquals(List.of("gtceu:chemical_reactor,gtceu:large_chemical_reactor"), defaults,
                "默认配置必须自带化反/大型化反这组动机用例");
    }
}
