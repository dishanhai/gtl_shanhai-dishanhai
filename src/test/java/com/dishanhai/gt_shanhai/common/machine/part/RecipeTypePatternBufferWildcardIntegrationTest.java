package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternBufferWildcardIntegrationTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path ACCESS = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "RecipeTypePatternSlotAccess.java");
    private static final Path SEARCH = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path BRIDGE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "WildcardPatternBridge.java");
    private static final Path PERSISTENCE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternWildcardPersistence.java");
    private static final Path ZH_CN = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang",
            "zh_cn.json");
    private static final Path EN_US = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang",
            "en_us.json");

    @Test
    void wildcardPatternsUseFiveMotherSlotsAndDynamicStockingSlots() throws IOException {
        String machine = Files.readString(MACHINE);
        String access = Files.readString(ACCESS);
        String search = Files.readString(SEARCH);
        String zhCn = Files.readString(ZH_CN);
        String enUs = Files.readString(EN_US);

        assertTrue(Files.exists(BRIDGE), "应存在可选通配符反射桥接");
        assertTrue(Files.exists(PERSISTENCE), "应存在动态槽持久化器");
        assertTrue(machine.contains("WILDCARD_PATTERN_SLOT_COUNT = 5"), "通配符母槽数量必须为 5");
        assertTrue(machine.contains("new SlotWidget(wildcardPatternInventory, slot, 34 + slot * 18, 14)"),
                "通配符配置页必须使用 5×1 横向布局");
        assertTrue(machine.contains("new WidgetGroup(0, 0, 160, 155)")
                        && machine.contains("new PatternCycleWidget(8, 71, 142, 36, () -> wildcardPatterns)"),
                "通配符配置页必须使用原版展开样板轮播预览");
        assertTrue(machine.contains("new StockingPatternBufferInternalSlot(globalSlot)"),
                "展开样板必须使用库存型内部槽");
        assertTrue(machine.contains("protected int getInternalSlotCount()")
                        && machine.contains("getSlotIndexForPattern(IPatternDetails pattern)"),
                "星律必须合并普通槽与通配符动态槽");
        assertTrue(machine.contains("class CombinedPatternTrait extends MEPatternTrait"),
                "动态槽必须接入 AE 配方缓存 trait");
        assertTrue(access.contains("gtShanhai$getPatternSlotCount()")
                        && access.contains("gtShanhai$getPatternStack(int slot)"),
                "首配搜索接口必须能遍历动态编码样板");
        assertTrue(search.contains("access.gtShanhai$getPatternSlotCount()")
                        && search.contains("access.gtShanhai$getPatternStack(slot)"),
                "首配搜索必须读取统一槽位接口");
        assertTrue(machine.contains(
                        "String assignedTypeId = PatternRecipeTypeHelper.readRecipeTypeId(wildcardStack)"),
                "每个通配符母槽必须读取自身分配的配方类型");
        assertTrue(machine.contains("WildcardPatternRecipeTypeBinding.findRecipe(pattern, assignedTypeId)"),
                "展开样板必须按母槽指定类型解析真实配方");
        assertTrue(machine.contains("if (!assignedTypeId.isEmpty() && recipe == null) continue"),
                "指定类型无法匹配时不得回退到其他配方类型");
        assertTrue(machine.contains("private int selectedWildcardMotherSlot"),
                "通配符 UI 必须记录当前选择的 1-5 号母槽");
        assertTrue(machine.contains("new DraggableScrollableWidgetGroup"),
                "主机配方类型列表必须支持滚动");
        assertTrue(machine.contains("setYScrollBarWidth(4).setYBarStyle"),
                "配方类型列表必须显示可拖动的纵向滚动条");
        assertTrue(machine.contains("WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(getControllers())"),
                "配方类型候选必须来自星律当前连接主机");
        assertTrue(machine.contains("assignWildcardRecipeType")
                        && machine.contains("clearWildcardRecipeType"),
                "当前母槽必须支持分配类型与恢复自动识别");
        assertTrue(zhCn.contains("gui.gt_shanhai.wildcard_pattern_recipe_type")
                        && zhCn.contains("gui.gt_shanhai.wildcard_pattern_auto")
                        && enUs.contains("gui.gt_shanhai.wildcard_pattern_recipe_type")
                        && enUs.contains("gui.gt_shanhai.wildcard_pattern_auto"),
                "通配符类型 UI 必须提供中英文文案");
    }
}
