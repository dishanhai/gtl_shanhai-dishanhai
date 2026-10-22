package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** 排序不改来源，平手稳定，模组分区独立于网格排序（山海署名）。 */
class PickerSortTest {
    private final List<PickerSortEntry> entries = List.of(
            new PickerSortEntry(40, "Beta", "z", "Zulu", "z:beta", 2),
            new PickerSortEntry(7, "Alpha", "minecraft", "Minecraft", "minecraft:alpha", 9),
            new PickerSortEntry(99, "Alpha", "a", "Alpha Mod", "a:alpha", 9),
            new PickerSortEntry(3, "Gamma", "a", "Alpha Mod", "a:gamma", (long) Integer.MAX_VALUE + 1));

    @Test
    void defaultAndUnknownSortReturnSourceIndicesInInputOrder() {
        assertEquals(List.of(40, 7, 99, 3), PickerSort.order(entries, "default", true, "en_us"));
        assertEquals(List.of(40, 7, 99, 3), PickerSort.order(entries, "unknown", true, "en_us"));
    }

    @Test
    void nameSortKeepsEqualNamesInSourceOrder() {
        assertEquals(List.of(7, 99, 40, 3), PickerSort.order(entries, "name", true, "en_us"));
    }

    @Test
    void modSortPutsMinecraftFirstThenDisplayNamesAndKeepsInternalSourceOrder() {
        assertEquals(List.of(7, 99, 3, 40), PickerSort.order(entries, "mod", false, "en_us"));
    }

    @Test
    void idSortUsesFullNamespacedIds() {
        assertEquals(List.of(99, 3, 7, 40), PickerSort.order(entries, "id", false, "en_us"));
    }

    @Test
    void countSortIsDescendingAndOnlyAppliesToInventory() {
        assertEquals(List.of(3, 7, 99, 40), PickerSort.order(entries, "count", true, "en_us"));
        assertEquals(List.of(40, 7, 99, 3), PickerSort.order(entries, "count", false, "zh_tw"));
    }

    @Test
    void countComparisonHandlesLongExtremesWithoutSubtractionOverflow() {
        List<PickerSortEntry> extremes = List.of(
                new PickerSortEntry(50, "low", "a", "A", "a:low", Long.MIN_VALUE),
                new PickerSortEntry(2, "high", "a", "A", "a:high", Long.MAX_VALUE),
                new PickerSortEntry(9, "zero", "a", "A", "a:zero", 0),
                new PickerSortEntry(1, "high again", "a", "A", "a:high_again", Long.MAX_VALUE));

        assertEquals(List.of(2, 1, 9, 50), PickerSort.order(extremes, "count", true, "en_us"));
    }

    @Test
    void equalSortKeysAlwaysKeepInputOrderRatherThanIndexOrIdOrder() {
        List<PickerSortEntry> ties = List.of(
                new PickerSortEntry(30, "Same", "z", "Same Mod", "same:item", 9),
                new PickerSortEntry(2, "Same", "a", "Same Mod", "same:item", 9),
                new PickerSortEntry(15, "Same", "b", "Same Mod", "same:item", 9));

        for (String sort : List.of("default", "name", "mod", "id", "count")) {
            assertEquals(List.of(30, 2, 15), PickerSort.order(ties, sort, true, "en_us"), sort);
        }
    }

    @Test
    void modulesReturnUniqueNamespacesWithMinecraftFirst() {
        assertEquals(List.of("minecraft", "a", "z"), PickerSort.modules(entries, "en_us"));
    }

    @Test
    void modulesUseDisplayNamesAndFallBackToNamespaceForMissingNames() {
        List<PickerSortEntry> modules = List.of(
                new PickerSortEntry(0, "item", "alpha_id", "Zulu", "alpha_id:item", 1),
                new PickerSortEntry(1, "item", "zulu_id", "Alpha", "zulu_id:item", 1),
                new PickerSortEntry(2, "item", "beta", "", "beta:item", 1),
                new PickerSortEntry(3, "item", "minecraft", "ZZZ", "minecraft:item", 1),
                new PickerSortEntry(4, "item", "charlie", "   ", "charlie:item", 1));

        assertEquals(List.of("minecraft", "zulu_id", "beta", "charlie", "alpha_id"),
                PickerSort.modules(modules, "en_us"));
        assertEquals(List.of(3, 1, 2, 4, 0), PickerSort.order(modules, "mod", false, "en_us"));
    }

    @Test
    void equalModuleDisplayNamesKeepFirstOccurrenceOrder() {
        List<PickerSortEntry> modules = List.of(
                new PickerSortEntry(30, "item", "z", "Same Mod", "z:item", 1),
                new PickerSortEntry(2, "item", "a", "Same Mod", "a:item", 1),
                new PickerSortEntry(15, "item", "z", "Same Mod", "z:second", 1));

        assertEquals(List.of("z", "a"), PickerSort.modules(modules, "en_us"));
    }

    @Test
    void chineseLanguagesUseChinaCollationAndOtherLanguagesUseRoot() {
        List<PickerSortEntry> localized = localizedEntries();

        assertEquals(List.of(30, 20, 10), PickerSort.order(localized, "name", false, "zh_cn"));
        assertEquals(List.of(30, 20, 10), PickerSort.order(localized, "name", false, "zh_tw"));
        assertEquals(List.of(10, 20, 30), PickerSort.order(localized, "name", false, "en_us"));
        assertEquals(List.of(10, 20, 30), PickerSort.order(localized, "name", false, "ja_jp"));
        assertEquals(List.of("alpha", "beta", "gamma"), PickerSort.modules(localized, "zh_tw"));
        assertEquals(List.of("gamma", "beta", "alpha"), PickerSort.modules(localized, "en_us"));
    }

    @Test
    void concurrentJobsKeepTheirOwnLanguageCollation() throws Exception {
        List<PickerSortEntry> localized = localizedEntries();
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int jobIndex = 0; jobIndex < 12; jobIndex++) {
            boolean chinese = jobIndex % 2 == 0;
            jobs.add(() -> {
                String language = chinese ? "zh_tw" : "en_us";
                List<Integer> expectedOrder = chinese ? List.of(30, 20, 10) : List.of(10, 20, 30);
                List<String> expectedModules = chinese
                        ? List.of("alpha", "beta", "gamma") : List.of("gamma", "beta", "alpha");
                for (int repeat = 0; repeat < 20; repeat++) {
                    assertEquals(expectedOrder, PickerSort.order(localized, "name", false, language));
                    assertEquals(expectedModules, PickerSort.modules(localized, language));
                }
                return null;
            });
        }

        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(jobs)) {
                result.get();
            }
        }
    }

    @Test
    void sortAndModulesNeverMutateSourceEntries() {
        List<PickerSortEntry> source = new ArrayList<>(entries);
        for (String sort : List.of("default", "name", "mod", "id", "count")) {
            PickerSort.order(source, sort, true, "zh_tw");
            assertEquals(entries, source);
            assertSame(entries.get(0), source.get(0));
        }
        PickerSort.modules(source, "en_us");
        assertEquals(entries, source);
    }

    @Test
    void emptySourcesHaveNoIndicesOrModules() {
        for (String sort : List.of("default", "name", "mod", "id", "count")) {
            assertEquals(List.of(), PickerSort.order(List.of(), sort, true, "zh_tw"));
            assertEquals(List.of(), PickerSort.order(List.of(), sort, false, "en_us"));
        }
        assertEquals(List.of(), PickerSort.modules(List.of(), "en_us"));
    }

    private static List<PickerSortEntry> localizedEntries() {
        return List.of(
                new PickerSortEntry(30, "阿", "alpha", "阿", "alpha:item", 1),
                new PickerSortEntry(10, "中", "gamma", "中", "gamma:item", 1),
                new PickerSortEntry(20, "波", "beta", "波", "beta:item", 1));
    }
}
