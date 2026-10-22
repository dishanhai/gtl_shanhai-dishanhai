package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多选选择器与两个编辑器接入商店交互核心的源码约束（山海署名）。 */
class ShopPickerFeedbackSourceTest {

    private static final Path SHOP = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "client", "gui", "shop");

    private static String read(String name) throws Exception {
        return Files.readString(SHOP.resolve(name));
    }

    // ===== MultiPickerScreen：按钮、滚动条、清单行 =====

    @Test
    void pickerButtonsUseTheSharedFeedbackInsteadOfItsOwnPainter() throws Exception {
        String p = read("MultiPickerScreen.java");
        assertTrue(p.contains("new ShopDialogUi("), "和商店弹窗同一套按下/回弹/点击声");
        assertFalse(p.contains("private void drawBtn("), "自绘的无反馈按钮已移除");
        assertTrue(p.contains("ui.draw(g, this.font, confirmX()"), "确认/取消走共用按钮");
        assertTrue(p.contains("ui.draw(g, this.font, modeX"), "浏览模式切换按钮也有反馈");
        assertTrue(p.contains("ui.draw(g, this.font, bx, countStepY(0)"), "三排数量步进按钮也有反馈");
        assertTrue(extractBlock(p, "protected void renderScaledBackground(").contains("ui.frame(mx, my)"),
                "每帧推进按下/闪色状态");
        assertTrue(extractBlock(p, "protected boolean universalMouseReleased(").contains("ui.release()"));
        assertTrue(extractBlock(p, "protected void initScaled(").contains("ui.cancel()"),
                "从选择器返回或重建后不卡在按下态");
        assertTrue(extractBlock(p, "public void removed()").contains("ui.cancel()"));
    }

    @Test
    void pickerClicksGoThroughHitSoTheyAnswerWithASound() throws Exception {
        String click = extractBlock(read("MultiPickerScreen.java"), "protected boolean universalMouseClicked(");
        assertTrue(click.contains("ui.hit(mx, my, confirmX()"), "确认按钮点中即压下并出声");
        assertTrue(click.contains("ui.hit(mx, my, cancelX()"));
        assertTrue(click.contains("modeChangedAtMs = System.currentTimeMillis()"), "切模式后网格重新淡入");
        assertTrue(extractBlock(read("MultiPickerScreen.java"),
                "private boolean countStepsClicked(").contains("ShopUiSounds.step("),
                "数量步进出货架音，跟商店购买页一致");
    }

    @Test
    void pickerScrollbarCanBeDraggedOrClickedOnTheTrack() throws Exception {
        String p = read("MultiPickerScreen.java");
        assertTrue(p.contains("ui.drawScrollbar("), "滚动条走共用件，hover 变亮");
        assertTrue(p.contains("ui.beginDrag("), "点轨道任意一处直接跳过去");
        assertTrue(extractBlock(p, "protected boolean universalMouseDragged(").contains("ui.drag("));
        assertFalse(p.contains("private boolean draggingScroll;"), "拖拽状态归共用件管");
    }

    @Test
    void newlyPickedEntriesFlashAndLongNamesWrap() throws Exception {
        String p = read("MultiPickerScreen.java");
        assertTrue(p.contains("private void markAdded(") && p.contains("private float addFlash("),
                "刚加入清单的那一项亮一下再淡掉");
        assertTrue(extractBlock(p, "private void addFromGrid(").contains("markAdded("),
                "从网格加入也要闪");
        assertTrue(extractBlock(p, "private void addFromGrid(").contains("ShopUiSounds.click()"));
        assertTrue(p.contains("private void drawListName("), "清单列名换行，不再截字");
        assertFalse(p.contains("GuiRenderUtil.trimText("), "清单里不再砍名字");
        assertTrue(extractBlock(p, "protected void renderTooltips(").contains("hoverSelNameFull"),
                "折行还放不下的悬停看全名");
    }

    @Test
    void pickerKeysConfirmWithEnterAndEscapeLeavesTheSearchBoxFirst() throws Exception {
        String p = read("MultiPickerScreen.java");
        String keys = extractBlock(p, "public boolean keyPressed(");
        assertTrue(keys.contains("ui.takeEnter()"), "按住 Enter 只确认一次");
        assertTrue(keys.contains("getFocused() instanceof EditBox"), "Esc 先退出搜索框，再按一次才关");
        assertTrue(extractBlock(p, "public boolean keyReleased(").contains("ui.enterReleased("));
        assertTrue(extractBlock(p, "protected void initScaled(").contains("ui.syncEnterHeld()"));
    }

    // ===== 两个编辑器：不再开 FTBLib 选取页 =====

    @Test
    void exchangeEditorSlotsOpenTheShopPicker() throws Exception {
        String e = read("ExchangeEditScreen.java");
        assertFalse(e.contains("EditorWidgets.openItemPicker("), "物品格不再开 FTBLib/FTBQ 选取页");
        assertFalse(e.contains("EditorWidgets.openFluidPicker("), "流体格也一样");
        assertTrue(extractBlock(e, "private void openItemReplace(").contains(".replacing()"));
        assertTrue(extractBlock(e, "private void openItemReplace(").contains("preselect("),
                "原物品与数量先放进已选清单");
        assertTrue(extractBlock(e, "private void openFluidReplace(").contains("preselect("));
        assertTrue(e.contains("ShopEntryEditLayout.replaceAt("), "确认后整份清单换掉这一格");
    }

    @Test
    void stageLimitEditorSlotsOpenTheShopPicker() throws Exception {
        String s = read("ShopStageEditScreen.java");
        assertFalse(s.contains("EditorWidgets.openItemPicker("));
        String open = extractBlock(s, "private void openItemReplace(");
        assertTrue(open.contains(".replacing()") && open.contains("preselect("));
        assertTrue(open.contains("ShopEntryEditLayout.replaceAt("), "精确数量跟着一起换");
    }

    @Test
    void noFtbLibPickerIsLeftAnywhere() throws Exception {
        String widgets = read("EditorWidgets.java");
        assertFalse(widgets.contains("SelectItemStackScreen"), "FTBLib 物品选取页已无调用方，代码一并移除");
        assertFalse(widgets.contains("SelectFluidScreen"), "流体选取页同理");
        assertTrue(widgets.contains("openItemPicker") == false && widgets.contains("openFluidPicker") == false);
    }
}
