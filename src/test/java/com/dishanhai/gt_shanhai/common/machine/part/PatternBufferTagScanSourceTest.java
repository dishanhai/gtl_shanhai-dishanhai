package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：標籤候選探索節流與一般庫存同步分離的接線守衛。 */
class PatternBufferTagScanSourceTest {

    private static final Path PART = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part");

    @Test
    void onlyTagCandidateDiscoveryUsesHundredTickCache() throws IOException {
        String pull = read("PatternBufferStockPull.java");
        String machine = read("RecipeTypePatternBufferPartMachine.java");
        String sync = method(machine, "protected void syncStockInput()", "private void refreshStockTagConfiguration");

        assertTrue(pull.contains("TAG_SCAN_INTERVAL_TICKS = 100L"),
                "只把標籤全網候選探索節流至 100 tick");
        assertTrue(pull.contains("cachedMatches"), "必須快取空結果在內的候選列表，而非只在命中時節流");
        assertTrue(sync.contains("stockItemHandler.syncStock(view)") && sync.contains("stockFluidHandler.syncStock(view)"),
                "已知 key 每次正常同步都應使用最新 KeyCounter 可用量");
        assertTrue(sync.contains("requestStockShortages()"), "可請求補貨仍須維持正常同步週期");
        assertFalse(sync.contains("TAG_SCAN_INTERVAL_TICKS"), "不得將整個補貨方法延遲五秒");
    }

    @Test
    void candidateCacheIsScopedToMachineGridFilterAndTagRevision() throws IOException {
        String pull = read("PatternBufferStockPull.java");

        assertTrue(pull.contains("cachedGrid != grid"), "切換 AE grid 後必須即時重掃，不能沿用別網候選");
        assertTrue(pull.contains("StellarTagFilter.revision()"), "標籤重載應令候選探索即時失效");
        assertTrue(pull.contains("Objects.equals(cachedWhite, white)")
                        && pull.contains("Objects.equals(cachedBlack, black)"),
                "白黑名單變更應即時重掃");
        assertFalse(pull.contains("static List<AEItemKey> cachedMatches"), "候選快取必須屬於每個機器實例");
    }

    @Test
    void tagsUpdatedEventInvalidatesAlreadyExistingFilterInstances() throws IOException {
        String filter = read("StellarTagFilter.java");

        assertTrue(filter.contains("@Mod.EventBusSubscriber"), "共用標籤過濾器必須訂閱 Forge 事件");
        assertTrue(filter.contains("@SubscribeEvent") && filter.contains("TagsUpdatedEvent"),
                "標籤重載事件必須推進失效版本");
        assertTrue(filter.contains("long revision()"), "候選探索與現存過濾器必須共用標籤失效版本");
        assertTrue(filter.contains("cache.clear()"), "現存 filter 不能永久沿用重載前的逐物品布林結果");
    }

    private static String read(String file) throws IOException {
        return Files.readString(PART.resolve(file)).replace("\r\n", "\n");
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        return source.substring(from, to);
    }
}
