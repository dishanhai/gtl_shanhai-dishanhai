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
        assertFalse(p.contains("COUNT_STEPS"), "數量改為清單行內編輯");
        assertTrue(p.contains("drawQuantityRow("), "行內步進共用按鈕反饋");
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
        assertTrue(picker().contains("modeChangedAtMs = System.currentTimeMillis()"), "切模式后网格重新淡入");
        assertTrue(extractBlock(read("MultiPickerScreen.java"),
                "private void stepQuantity(").contains("ShopUiSounds.step("),
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
        assertTrue(p.contains("this.font.split("), "清單行高由全部名稱換行計算");
    }

    private static String picker() throws Exception {
        return read("MultiPickerScreen.java");
    }

    @Test
    void threeColumnsUseKotlinLogicAndVersionedSortSnapshots() throws Exception {
        String source = picker();
        assertTrue(source.contains("MultiPickerLayout.geometry("));
        assertTrue(source.contains("new PickerSelection<>"));
        assertTrue(source.contains("PickerSort.order("));
        assertTrue(source.contains("List.copyOf(sortEntries)"));
        assertTrue(source.contains("sourceVersion") && source.contains("sortGeneration"));
        assertTrue(source.contains("ClientShopUiSettings.setPickerPreferences("));
        assertTrue(source.contains("tooltipCache.clear()"));
    }

    @Test
    void blurRequestsAndOneTopLayerAreRegisteredAfterAllPainting() throws Exception {
        String source = picker();
        String finish = extractBlock(source, "protected void renderTooltips(");
        assertTrue(finish.indexOf("ShopGaussBlur.clear()") < finish.indexOf("ShopGaussBlur.requestDirectional("));
        assertTrue(finish.indexOf("ShopGaussBlur.requestDirectional(") < finish.indexOf("ShopGaussBlur.requestExtra("));
        assertTrue(finish.contains("ShopGaussBlur.deferTopLayer(this"));
        assertTrue(source.contains("GlassGeometry.applyAffine(") && source.contains("GlassGeometry.toPixels("));
        assertTrue(source.contains("parent.render(g, -1, -1"));
        String removed = extractBlock(source, "public void removed()");
        assertTrue(removed.contains("ShopGaussBlur.clear()") && removed.contains("ShopGaussBlur.clearTopLayer()"));
        String client = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/ClientInit.java"));
        assertTrue(client.contains("ShopGaussBlur.allowScreen(") && client.contains("instanceof com.dishanhai.gt_shanhai.client.gui.shop.MultiPickerScreen"));
    }

    @Test
    void parentScreenRendersBehindPickerEvenWhenItHasAHighZForeground() throws Exception {
        String background = extractBlock(picker(), "public void renderBackground(");
        assertTrue(background.indexOf("g.pose().pushPose()") < background.indexOf("parent.render(g, -1, -1"));
        assertTrue(background.contains("g.pose().translate(0, 0, -500)"));
        assertTrue(background.indexOf("parent.render(g, -1, -1") < background.indexOf("g.pose().popPose()"));
    }

    @Test
    void draggingRepeatsAndExactLongEditingShareTheSelectionModel() throws Exception {
        String source = picker();
        assertTrue(source.contains("ui.holdRepeat(RepeatCurve.STEPPER"));
        assertTrue(source.contains("selection.move("));
        assertTrue(source.contains("dragVisited"));
        assertTrue(source.contains("countBox.setMaxLength(19)"));
        assertTrue(source.contains("selection.step("));
        assertTrue(source.contains("selection.setAll("));
        assertFalse(source.contains("lines.size() > 2"));
    }

    @Test
    void cancellingQuantityHoldDoesNotCancelUnrelatedScrollbarDrags() throws Exception {
        String cancel = extractBlock(picker(), "private void cancelHold(");
        assertTrue(cancel.contains("if (heldQuantity != null)"));
        String repeat = extractBlock(picker(), "private void startQuantityRepeat(");
        assertTrue(repeat.indexOf("setActive(entry)") < repeat.indexOf("ui.press("));
        assertTrue(repeat.indexOf("ui.press(") < repeat.indexOf("ui.holdRepeat("));
    }

    @Test
    void rightClickTogglesOnlyOnReleaseWhileRightDragRemovesItsOrigin() throws Exception {
        String source = picker();
        String click = extractBlock(source, "private boolean gridClicked(");
        assertFalse(click.contains("onGridPick(index, btn == 1)"));
        String release = extractBlock(source, "protected boolean universalMouseReleased(");
        assertTrue(release.contains("!dragStarted") && release.contains("onGridPick(gestureOrigin, true)"));
        String drag = extractBlock(source, "protected boolean universalMouseDragged(");
        assertTrue(drag.contains("findSelected(gestureOrigin)"));
        assertTrue(drag.contains("onGridPick(index, false)"), "拖動加入亦沿用 Shift 一組");
    }

    @Test
    void moduleAndOriginalLabelsAreMeasuredAndRenderedWithoutDroppingLines() throws Exception {
        String source = picker();
        assertFalse(source.contains(".getFirst()"), "原格與模組名也必須完整換行");
        assertTrue(source.contains("moduleStarts") && source.contains("moduleHeights"));
        assertTrue(source.contains("headerHeights"));
        String header = extractBlock(source, "private void drawGridHeader(");
        assertFalse(header.contains("filtered.stream()"), "分區項數不在每幀重新遍歷全量清單");
        assertTrue(header.contains("this.font.split("));
    }

    @Test
    void languageChangesRefreshNamesAndTooltipCachesBeforeSorting() throws Exception {
        String source = picker();
        String refresh = extractBlock(source, "private void refreshSourceNames(");
        assertTrue(refresh.contains("sourceLanguage") && refresh.contains("getHoverName()"));
        assertTrue(refresh.contains("ALL_ITEM_TOOLTIP = new String["));
        String rebuild = extractBlock(source, "private void rebuildSource(");
        assertTrue(rebuild.indexOf("refreshSourceNames()") < rebuild.indexOf("List<String> names = curNames()"));
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
        assertTrue(e.contains("ShopEntryEditLayout.replaceAll("), "确认后整份清单换掉这一排");
    }

    @Test
    void stageLimitEditorSlotsOpenTheShopPicker() throws Exception {
        String s = read("ShopStageEditScreen.java");
        assertFalse(s.contains("EditorWidgets.openItemPicker("));
        String open = extractBlock(s, "private void openItemReplace(");
        assertTrue(open.contains(".replacing()") && open.contains("preselect("));
        assertTrue(open.contains("ShopEntryEditLayout.replaceAll("), "精确数量跟着一起换");
    }

    @Test
    void noFtbLibPickerIsLeftAnywhere() throws Exception {
        String widgets = read("EditorWidgets.java");
        assertFalse(widgets.contains("SelectItemStackScreen"), "FTBLib 物品选取页已无调用方，代码一并移除");
        assertFalse(widgets.contains("SelectFluidScreen"), "流体选取页同理");
        assertTrue(widgets.contains("openItemPicker") == false && widgets.contains("openFluidPicker") == false);
    }

    // ===== 多选器：分区标题层级、侧栏数量列、快速填数；商店页签按下反馈 =====

    @Test
    void gridHeaderIsLiftedAboveItemIconsAndSidebarCountsAreRightAligned() throws Exception {
        String p = read("MultiPickerScreen.java");
        String header = extractBlock(p, "private void drawGridHeader(");
        assertTrue(header.contains("translate(0, 0, GRID_HEADER_Z)"), "物品图标在 z≈150，吸顶标题要抬得比它高");
        assertTrue(p.contains("GRID_HEADER_Z = 300f"), "低于 ScaledScreen 的 400 顶层");
        assertTrue(p.contains("sidebarX + sidebarW - 3 - this.font.width(countText)"), "数量按最右缘右对齐，五位数也不出栏");
        assertFalse(p.contains("sidebarW - 28"), "名称宽度由数量列宽现算，不再写死");
    }

    @Test
    void selectionRowsOfferQuickStepsFromOneToOneMillion() throws Exception {
        String p = read("MultiPickerScreen.java");
        assertTrue(p.contains("{1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L}"));
        assertTrue(extractBlock(p, "private boolean selListClicked(").contains("quickStep(entry, row == 0 ? -QUICK_STEPS[chip] : QUICK_STEPS[chip])"),
                "减排取负、加排取正，均走共用按钮命中");
        assertTrue(extractBlock(p, "private void quickStep(").contains("stepQuantity(entry, delta, 0)"),
                "沿用 PickerSelection.step 的下限 1 与上限钳制");
        assertTrue(p.contains("QUANTITY_ROW_H + QUICK_BLOCK_H"), "行高与拖拽判定区都计入快速填数两排");
    }

    @Test
    void shopTabStripsPlayThePressFeedbackBeforeSwitching() throws Exception {
        String s = read("ShopScreen.java");
        String strip = extractBlock(s, "private void drawTabStrip(");
        assertTrue(strip.contains("buttonFeedback.visual(pk, pk, true, now, reduceMotionFrame)"), "页签按几何 key 取压下态");
        assertTrue(strip.contains("tabPressScale("), "页签按下用放大的压下幅度，14px 高的页签 0.95 看不出");
        assertTrue(strip.contains("fb[i].flashMix()"), "按下与闪色都把格子亮成金色");
        assertTrue(extractBlock(s, "private boolean tabRowClicked(").contains("pressTab("),
                "点中页签即压下");
        assertTrue(extractBlock(s, "private void pressTab(").contains("buttonFeedback.flash("), "按下同时闪金色");
        assertFalse(extractBlock(s, "private void beginTabAction(").contains("performTabSwitch("),
                "切页等到松开，否则 init 会把刚压下的反馈归零");
    }

    @Test
    void replacePickersPrefillTheWholeRowAndReplaceItAsOne() throws Exception {
        String picker = read("MultiPickerScreen.java");
        assertTrue(picker.contains("public MultiPickerScreen focusPreselected("), "选择器能指出点中的是整排里的哪一格");
        for (String f : new String[] {"ShopEntryEditScreen.java", "ExchangeEditScreen.java", "ShopStageEditScreen.java"}) {
            String src = read(f);
            assertTrue(src.contains("focusPreselected("), f + " 点已有格要整排预填");
            assertFalse(src.contains("ShopEntryEditLayout.replaceAt("), f + " 确认后整排换，不再只换点中的那一格");
        }
    }
}
