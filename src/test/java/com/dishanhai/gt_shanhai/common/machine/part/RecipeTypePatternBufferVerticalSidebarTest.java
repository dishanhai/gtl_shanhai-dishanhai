package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternBufferVerticalSidebarTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path ADAPTER = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "api", "gui", "configurators", "FancyConfiguratorSidebarPage.java");

    @Test
    void patternBufferUsesFiveSideTabsAndKeepsRefundAsOnlyFloatingControl() throws IOException {
        String machine = Files.readString(MACHINE);
        int configuratorsStart = machine.indexOf("public void attachConfigurators");
        int sideTabsStart = machine.indexOf("public void attachSideTabs", configuratorsStart);
        String configurators = machine.substring(configuratorsStart, sideTabsStart);

        assertTrue(machine.contains("public void attachSideTabs"),
                "星律必须通过 VerticalTabsWidget 的 attachSideTabs 注册配置入口");
        assertEquals(5, countOccurrences(machine, "sideTabs.attachSubTab("),
                "新增产出倍率页后，主页面之外必须固定注册五个连续侧栏页面");
        assertTrue(machine.contains("new OutputMultiplierConfigurator()"),
                "星律必须提供独立的样板产出倍率侧栏页");
        assertTrue(machine.contains("collectParentConfigurators()"),
                "父类配置器必须先收集后迁移，不能复制私有配置逻辑");
        assertFalse(configurators.contains("super.attachConfigurators"),
                "星律真实 ConfiguratorPanel 不得直接恢复全部父类悬浮按钮");
        assertEquals(1, countOccurrences(configurators, "configuratorPanel.attachConfigurators("),
                "星律真实 ConfiguratorPanel 只能保留返还共享库存这一项");
        assertTrue(configurators.contains("parentConfigurators.get(PARENT_REFUND)"),
                "返还共享库存必须保持独立悬浮入口");
        assertTrue(machine.contains("createStockInputSidebarPage(parentConfigurators)"),
                "高级拉取设置必须与库存输入配置器合并到同一侧栏页");
        assertTrue(machine.contains("selected.add(configurators.get(PARENT_ADVANCED_ME))"));
        assertFalse(machine.contains("\"gui.gt_shanhai.pattern_buffer_me_operations\""),
                "合并后不得继续注册独立 ME 操作侧栏页");
    }

    @Test
    void sidebarAdapterPreservesConfiguratorSyncAndButtonBehavior() throws IOException {
        assertTrue(Files.exists(ADAPTER), "必须提供 IFancyConfigurator 到侧栏页面的适配器");
        String adapter = Files.readString(ADAPTER);

        assertTrue(adapter.contains("configurator instanceof IFancyConfiguratorButton"),
                "按钮配置器必须在调用 createConfigurator 之前单独处理");
        assertTrue(adapter.contains("configurator.writeInitialData(buffer)"));
        assertTrue(adapter.contains("configurator.readInitialData(buffer)"));
        assertTrue(adapter.contains("configurator.detectAndSendChange"));
        assertTrue(adapter.contains("configurator.readUpdateInfo"));
        assertTrue(adapter.contains("button.onClick(clickData)"),
                "迁移后的返还与开关按钮必须继续调用原配置器动作");
    }

    @Test
    void sidebarLabelsUseSupplierConstructorForServerSynchronization() throws IOException {
        String adapter = Files.readString(ADAPTER);

        assertFalse(adapter.contains("new LabelWidget(PAGE_PADDING, 3, configurator.getTitle())"),
                "LDLib 的 Component 构造器不会初始化 textSupplier，服务端同步会 NPE");
        assertTrue(adapter.contains("() -> configurator.getTitle().getString()"),
                "同步标题必须通过 Supplier 构造器创建 LabelWidget");
    }

    @Test
    void sharedInputsUseTwoColumnFirstRowAndKeepOtherPagesSingleColumn() throws IOException {
        String machine = Files.readString(MACHINE);
        String adapter = Files.readString(ADAPTER);

        assertTrue(adapter.contains("private final int firstRowColumns;"));
        assertTrue(adapter.contains("this(title, icon, tooltips, configurators, 1);"),
                "适配器默认构造器必须保持现有单列行为");
        assertTrue(adapter.contains("int firstRowHeight = 0;"),
                "双列首行必须按两个配置器的最大高度换行");
        assertTrue(adapter.contains("Math.max(firstRowHeight, section.getSize().height)"));
        assertTrue(machine.contains("\"gui.gt_shanhai.pattern_buffer_shared_inputs\", 2,"),
                "只有共享输入页应启用首行双列");
        assertEquals(1, countOccurrences(machine, "pattern_buffer_shared_inputs\", 2,"));
    }

    @Test
    void remoteCatalystScreenBindsPlayerInventoryBelowCatalystSlots() throws IOException {
        String machine = Files.readString(MACHINE);
        String method = extractBlock(machine, "public ModularUI createRemoteSlotCatalystUI(");

        assertTrue(method.contains("UITemplate.bindPlayerInventory(player.getInventory(), GuiTextures.SLOT, 7, 184, true)"),
                "远程催化剂槽界面必须显示玩家背包，否则无法把手里物品放进催化剂槽");
        assertTrue(method.contains("new ModularUI(176, 266, holder, player)"),
                "窗口高度必须容纳上方催化剂面板和下方玩家背包");
        assertTrue(method.contains("new WidgetGroup(0, 0, 176, 180)"),
                "催化剂面板原尺寸保持不变，避免影响槽位坐标");
        assertTrue(method.contains("layoutRemoteCatalystContainers(manager)"),
                "远程页面必须把流体催化剂容器移到物品容器右侧");
    }

    @Test
    void remoteCatalystContainersArePlacedSideBySideAndManagerBoundsAreUpdated() throws IOException {
        String machine = Files.readString(MACHINE);
        String method = extractBlock(machine, "private static void layoutRemoteCatalystContainers(");

        assertTrue(method.contains("manager.widgets.size() < 3"));
        assertTrue(method.contains("Widget itemContainer = manager.widgets.get(1)"));
        assertTrue(method.contains("Widget fluidContainer = manager.widgets.get(2)"));
        assertTrue(method.contains("fluidContainer.setSelfPosition("));
        assertTrue(method.contains("itemContainer.getPositionX() + itemContainer.getSizeWidth() + 4"));
        assertTrue(method.contains("itemContainer.getPositionY()"));
        assertTrue(method.contains("manager.setSize("),
                "移动子控件后必须同步收紧管理器命中边界");
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
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
