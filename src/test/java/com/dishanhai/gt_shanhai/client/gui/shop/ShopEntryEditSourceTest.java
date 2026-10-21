package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商品编辑器五页签重构的源码约束（山海署名）。 */
class ShopEntryEditSourceTest {

    private static String src() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopEntryEditScreen.java"));
    }

    // ===== E2 页签与排版 =====

    @Test
    void rowsAreStackedPerTabInsteadOfHardcodedOffsets() throws Exception {
        String s = src();
        assertFalse(s.contains("contentY(top + "), "不再手写每行的 y 偏移");
        assertTrue(s.contains("ShopEntryEditLayout.stack("));
        assertTrue(extractBlock(s, "private void layoutRows()").contains("measureRow("), "行高按内容现算");
    }

    @Test
    void switchingTabsKeepsInputAndDropsFocus() throws Exception {
        String sel = extractBlock(src(), "private void selectTab(");
        assertTrue(sel.contains("capture()"), "切页前先把输入框文字落回草稿");
        assertTrue(sel.contains("catPickerOpen = false"), "分类下拉只属于商品页");
        assertTrue(sel.contains("setFocused(null)"), "别的页的输入框不能还接着打字");
    }

    @Test
    void inputsOnlyShowOnTheirOwnTabAndFullyInsideTheViewport() throws Exception {
        String place = extractBlock(src(), "private void placeBox(");
        assertTrue(place.contains("rowShown(row)"));
        assertTrue(place.contains("viewTop()") && place.contains("viewBottom()"), "半截露在外面的框会盖住页签或页脚");
    }

    @Test
    void saveAndCancelLiveInTheFooter() throws Exception {
        String s = src();
        assertFalse(s.contains("cancelX(), top + 3"), "按钮不再挤在标题栏");
        assertTrue(s.contains("footerBtnY()"));
    }

    @Test
    void longHintsWrapInsteadOfRunningPastThePanel() throws Exception {
        String s = src();
        assertFalse(s.contains("periodHintX()"), "旧的行尾长说明会越出面板被裁掉");
        assertFalse(s.contains("discountHintX()"));
        assertTrue(s.contains("this.font.split("));
    }

    @Test
    void clicksOnlyReachRowsOfTheActiveTabInsideTheViewport() throws Exception {
        String click = extractBlock(src(), "protected boolean universalMouseClicked(");
        assertTrue(click.contains("ShopEntryEditLayout.rowsOf(tab)"));
        assertTrue(click.contains("inViewport(mx, my)"), "滚出可视区的行不能被点到");
    }

    // ===== E3 按钮反馈、滚动条、文字换行 =====

    @Test
    void buttonsAndTabsUseTheSharedDialogFeedback() throws Exception {
        String s = src();
        assertTrue(s.contains("new ShopDialogUi("), "和商店弹窗同一套按下/回弹/点击声");
        assertTrue(extractBlock(s, "protected void renderScaledBackground(").contains("ui.frame(mx, my)"));
        assertTrue(extractBlock(s, "protected boolean universalMouseReleased(").contains("ui.release()"));
        assertTrue(extractBlock(s, "public void removed()").contains("ui.cancel()"), "跳去选择器再回来不会卡在按下态");
        assertTrue(extractBlock(s, "protected void initScaled()").contains("ui.cancel()"));
        assertTrue(extractBlock(s, "private void drawTabs(").contains("ui.draw("), "页签也有按下反馈");
        assertFalse(s.contains("private void drawBtn("), "旧的无反馈按钮画法已移除");
        assertTrue(s.contains("ui.hit("));
    }

    @Test
    void slotClicksAnswerWithASound() throws Exception {
        assertTrue(extractBlock(src(), "private boolean slotClick(").contains("ui.select()"));
    }

    @Test
    void pageScrollbarCanBeDraggedOrClickedAnywhereOnTheTrack() throws Exception {
        String s = src();
        assertTrue(s.contains("ui.drawScrollbar(") && s.contains("ui.beginDrag(") && s.contains("ui.drag("));
        assertFalse(s.contains("draggingEditorScroll"), "拖动状态归共用件管，松开落在别处也能兜底");
    }

    @Test
    void namesAndTitlesWrapInsteadOfBeingCut() throws Exception {
        String s = src();
        assertFalse(s.contains("trimText("), "商品名、FTBQ 表名、任务名、分类名都换行，不截断");
        assertTrue(s.contains("ShopDialogRows.fitFrom("), "分类下拉行高可变，按实际能放下几行翻页");
    }
}
