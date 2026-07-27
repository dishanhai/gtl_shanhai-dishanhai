package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存输入 AE 查询降载守卫。
 * <p>
 * 父类 MEStockingPatternBufferPartMachine 的 syncStockInput / findStock*Key 对每个条目·每个原料
 * 做 extract(key, Long.MAX_VALUE, SIMULATE)：请求量永远凑不满，NetworkStorage 必然遍历全网全部
 * 挂载存储（性能监测实测单次 sync ~3ms → Jade 平均延迟 73µs）。改造后周期同步与配方模拟阶段
 * 走 StorageService.getCachedInventory() 的 O(1) 缓存；真扣料阶段（includeCatalyst=false）必须
 * 保持父类实时查询，守住"check 循环先拦截不足、不部分消耗"的防吞料保证。
 */
class RecipeTypePatternBufferStockCachedLookupSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");

    @Test
    void syncStockInputUsesCachedInventoryView() throws IOException {
        String source = Files.readString(MACHINE);
        int start = source.indexOf("protected void syncStockInput()");
        assertTrue(start >= 0, "syncStockInput 覆写必须存在，否则回退父类全网遍历热点");
        int end = source.indexOf("gtShanhai$cachedConfiguredItemAmount", start);
        assertTrue(end > start, "库存条目门控 helper 必须存在");
        String sync = source.substring(start, end);
        assertTrue(sync.contains("gtShanhai$cachedNetworkInventory()"),
                "周期同步必须走 StorageService 缓存，不得直连 getInventory() 做全网模拟提取");
        assertTrue(sync.contains("clearStocks()"), "断网时必须清空库存显示，对齐父类语义");
        assertTrue(sync.contains("new CachedNetworkAmountView("),
                "必须经缓存视图复用父类 syncStock 的槽位/stockMap 回写逻辑");
    }

    @Test
    void cachedViewOnlyAnswersSimulate() throws IOException {
        String source = Files.readString(MACHINE);
        int start = source.indexOf("private static final class CachedNetworkAmountView");
        assertTrue(start >= 0, "缓存视图类必须存在");
        int end = source.indexOf("gtShanhai$cachedNetworkInventory()", start);
        String view = source.substring(start, end);
        assertTrue(view.contains("mode != Actionable.SIMULATE"),
                "视图背后没有真实存储，必须拒绝 MODULATE，防止被误当可扣料库存");
    }

    @Test
    void realConsumePhaseStaysOnLiveQuery() throws IOException {
        String source = Files.readString(MACHINE);
        for (String method : new String[] { "findStockItemKey", "findStockFluidKey" }) {
            int start = source.indexOf("protected AEItemKey " + method);
            if (start < 0) start = source.indexOf("protected AEFluidKey " + method);
            assertTrue(start >= 0, method + " 覆写必须存在");
            int end = source.indexOf("return null;", start);
            String body = source.substring(start, end);
            assertTrue(body.contains("includeCatalyst ? gtShanhai$cachedNetworkInventory() : null"),
                    method + " 只允许模拟阶段（includeCatalyst=true）走缓存");
            assertTrue(body.contains("super." + method + "("),
                    method + " 真扣料阶段必须委托父类实时查询，守住防吞料保证");
        }
    }

    @Test
    void cachedAmountsAreGatedByConfiguredSlots() throws IOException {
        String source = Files.readString(MACHINE);
        for (String helper : new String[] {
                "gtShanhai$cachedConfiguredItemAmount", "gtShanhai$cachedConfiguredFluidAmount" }) {
            int start = source.indexOf("private long " + helper);
            assertTrue(start >= 0, helper + " 必须存在");
            int end = source.indexOf("return 0L;", start);
            String body = source.substring(start, end);
            assertTrue(body.contains("key.equals(config.what())"),
                    helper + " 必须先命中已配置条目才计入网络量（对齐父类 configList 门控）");
            assertTrue(body.contains("Math.max(0L, cached.get(key))"),
                    helper + " 必须钳制缓存计数的瞬时负值");
        }
    }
}
