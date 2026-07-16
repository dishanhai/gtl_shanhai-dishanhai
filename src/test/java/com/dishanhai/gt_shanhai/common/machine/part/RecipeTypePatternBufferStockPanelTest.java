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
    void stockInputsUseTheFirstDedicatedConfiguratorInsteadOfTheMainUi() throws IOException {
        String source = Files.readString(SOURCE);
        int attachStart = source.indexOf("public void attachConfigurators");
        int mainUiStart = source.indexOf("public @NotNull Widget createUIWidget()", attachStart);
        int fieldHolderStart = source.indexOf("public @NotNull ManagedFieldHolder getFieldHolder()", mainUiStart);
        String attachMethod = source.substring(attachStart, mainUiStart);
        String mainUiMethod = source.substring(mainUiStart, fieldHolderStart);

        int stockConfigurator = attachMethod.indexOf("attachConfigurators(new StockInputConfigurator())");
        int parentConfigurators = attachMethod.indexOf("super.attachConfigurators(configuratorPanel)");

        assertTrue(source.contains("class StockInputConfigurator implements IFancyConfigurator"),
                "库存拉取槽应由独立 Fancy 配置器承载");
        assertTrue(stockConfigurator >= 0 && stockConfigurator < parentConfigurators,
                "库存拉取按钮必须先注册，才能位于左侧配置栏首位");
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
