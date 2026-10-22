package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段提交限制的换行、像素滚动与精确数量表约束（山海署名）。 */
class ShopStageEditSourceTest {
    private static String src() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopStageEditScreen.java"));
    }

    @Test
    void stageSlotsShareWrappingGeometryAndUseShownPixelScroll() throws Exception {
        String s = src();
        assertTrue(s.contains("ShopEntryEditLayout.slotX(") && s.contains("ShopEntryEditLayout.slotY("));
        assertTrue(s.contains("ShopEntryEditLayout.slotIndexAt("));
        assertTrue(s.contains("new Spring(Spring.SCROLL)"));
        assertTrue(s.contains("ClientShopUiSettings.reduceMotion()") && s.contains("scrollMotion.snap("));
        assertTrue(extractBlock(s, "protected boolean universalMouseClicked(").contains("shownScroll"));
        assertTrue(extractBlock(s, "protected boolean universalMouseScrolled(").contains("SLOT_ROW_H"));
        assertFalse(s.contains("滚轮切换物品"));
    }

    @Test
    void stageScrollbarUsesSharedTrackAndDragHandling() throws Exception {
        String s = src();
        assertTrue(s.contains("ui.drawScrollbar(") && s.contains("ui.beginDrag(") && s.contains("ui.drag("));
        assertTrue(extractBlock(s, "protected boolean universalMouseReleased(").contains("ui.release()"));
        assertTrue(extractBlock(s, "protected void initScaled()").contains("ui.cancel()"));
        assertTrue(extractBlock(s, "public void removed()").contains("ui.cancel()"));
    }

    @Test
    void replacementPadsCountsBeforeChangingTheItemsLength() throws Exception {
        String open = extractBlock(src(), "private void openItemReplace(");
        int pad = open.indexOf("while (counts.size() < items.size())");
        int items = open.indexOf("ShopEntryEditLayout.replaceAt(items");
        int counts = open.indexOf("ShopEntryEditLayout.replaceAt(counts");
        assertTrue(pad >= 0 && pad < items && items < counts,
                "按旧长度补数量，之后两表一起换；一换多后再添加不会消费多余的1L尾巴");
    }
}
