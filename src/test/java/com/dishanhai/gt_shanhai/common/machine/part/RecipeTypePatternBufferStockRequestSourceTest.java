package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternBufferStockRequestSourceTest {

    @Test
    void stockPageKeepsFiveTabsAndAddsTagPullWithRequestLimits() throws Exception {
        String machine = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java"));
        String ui = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "machine", "part", "PatternBufferStockRequestConfigurator.java"));

        assertTrue(machine.contains("implements RecipeTypePatternSlotAccess, ICraftingRequester"));
        assertTrue(machine.contains("new PatternBufferStockRequestConfigurator(this)"));
        assertTrue(machine.contains("refreshStockTagConfiguration(cached)"));
        assertTrue(machine.contains("requestStockShortages()"));
        assertTrue(machine.contains("node.addService(ICraftingRequester.class"));
        assertTrue(machine.contains("storage.insert(what, amount, mode, this.actionSource)"));
        assertEquals(5, count(machine, "sideTabs.attachSubTab("));
        assertTrue(ui.contains("machine::setStockTagWhite"));
        assertTrue(ui.contains("machine.submitStockRequest"));
        assertTrue(ui.contains("stock_request.amount"));
        assertTrue(ui.contains("stock_request.batch"));
        assertTrue(machine.contains("STOCK_PAGE_MAX_HEIGHT"));
        assertTrue(machine.contains("selected, 1, STOCK_PAGE_MAX_HEIGHT"));
        String adapter = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "api", "gui", "configurators", "FancyConfiguratorSidebarPage.java"));
        assertTrue(adapter.contains("maxPageHeight > 0 && pageHeight > maxPageHeight"));
        assertTrue(adapter.contains("new DraggableScrollableWidgetGroup"));
    }

    private static int count(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
