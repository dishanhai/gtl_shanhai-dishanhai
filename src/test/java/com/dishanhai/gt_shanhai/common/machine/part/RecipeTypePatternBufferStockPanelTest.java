package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternBufferStockPanelTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");

    @Test
    void stockInputsUseTheFirstDedicatedSideTabInsteadOfTheMainUi() throws IOException {
        String source = Files.readString(SOURCE);
        int attachStart = source.indexOf("public void attachSideTabs");
        assertTrue(attachStart >= 0, "库存拉取入口必须迁移到连续侧栏");
        int mainUiStart = source.indexOf("public @NotNull Widget createUIWidget()", attachStart);
        int fieldHolderStart = source.indexOf("public @NotNull ManagedFieldHolder getFieldHolder()", mainUiStart);
        String attachMethod = source.substring(attachStart, mainUiStart);
        String mainUiMethod = source.substring(mainUiStart, fieldHolderStart);

        int stockConfigurator = attachMethod.indexOf("sideTabs.attachSubTab(");
        int wildcardConfigurator = attachMethod.indexOf("new WildcardPatternConfigurator()", stockConfigurator);

        assertTrue(source.contains("class StockInputConfigurator implements IFancyConfigurator"),
                "库存拉取槽应继续由独立 Fancy 配置器承载");
        assertTrue(stockConfigurator >= 0 && stockConfigurator < wildcardConfigurator,
                "库存拉取页面必须位于连续侧栏第一项");
        assertTrue(source.contains("gt_shanhai:textures/gui/stock_input_panel.png"),
                "库存拉取按钮必须使用专用自绘图标");
        assertTrue(source.contains("new AEDualConfigWidget") && source.contains("countConfiguredStockSlots()"),
                "独立配置页必须复用原有库存槽和计数逻辑");
        assertFalse(mainUiMethod.contains("AEDualConfigWidget"),
                "样板主 UI 不应继续包含库存拉取槽");
        assertFalse(mainUiMethod.contains("stockLabelY") || mainUiMethod.contains("stockWidgetY"),
                "样板主 UI 不应保留库存区域布局高度");
    }
}
