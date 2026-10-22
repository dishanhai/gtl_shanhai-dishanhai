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
    void slotRowsWrapAndOnlyTheWholePageScrolls() throws Exception {
        String s = src();
        for (String old : new String[]{"coinCostScroll", "itemCostScroll", "fluidCostScroll", "submissionScroll",
                "goodsRowScroll", "draggingCostRow", "costRow", "drawCostRowScrollbar", "slotRowAt("}) {
            assertFalse(s.contains(old), "移除横向滚动状态与分支: " + old);
        }
        assertTrue(s.contains("ShopEntryEditLayout.slotX(") && s.contains("ShopEntryEditLayout.slotY("));
        assertTrue(s.contains("ShopEntryEditLayout.slotIndexAt("));
        String measure = extractBlock(s, "private int measureRow(");
        assertTrue(measure.contains("slotRowsHeight(") && measure.contains("rewardRowsHeight()"), "行高随全部格子增加");
        assertTrue(extractBlock(s, "private int rewardMax()").contains("return MAX_REWARD_OPTIONS"), "奖励上限不被单行宽度截短");
        for (String method : new String[]{"private void drawGoodsRow(", "private void drawCostIngredientRow(", "private void drawFluidRow("}) {
            assertFalse(extractBlock(s, method).contains("Scissor"), "只保留整页裁切: " + method);
        }
        String slot = extractBlock(s, "private void drawSlot(");
        assertTrue(slot.contains("idx + 1") && slot.contains("translate(dx, dy"), "删除后的跨行补位同时移动 x/y");
    }

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

    // ===== E4 键盘与未保存确认 =====

    @Test
    void escapeClosesOneLayerAtATimeAndNeverDropsTheWholeShop() throws Exception {
        String s = src();
        String keys = extractBlock(s, "public boolean keyPressed(int keyCode, int scanCode, int modifiers)");
        int discard = keys.indexOf("discardConfirmOpen");
        int qty = keys.indexOf("qtyEditorOpen");
        int desc = keys.indexOf("descEditorOpen");
        int picker = keys.indexOf("catPickerOpen = false");
        int unfocus = keys.indexOf("setFocused(null)");
        int close = keys.indexOf("requestClose()");
        assertTrue(discard >= 0 && discard < qty && qty < desc && desc < picker && picker < unfocus && unfocus < close,
                "Esc 依次：放弃确认框 → 数量框 → 描述大框 → 分类下拉 → 输入框焦点 → 回商店");
        assertTrue(extractBlock(s, "public void onClose()").contains("requestClose()"), "原版 Esc 关闭也走同一条路，不会直接关掉整个商店");
    }

    @Test
    void unsavedEditsAskBeforeLeaving() throws Exception {
        String s = src();
        String close = extractBlock(s, "private void requestClose()");
        assertTrue(close.contains("capture()") && close.contains("draftFingerprint()") && close.contains("initialFingerprint"));
        assertTrue(close.contains("discardConfirmOpen = true"));
        assertTrue(extractBlock(s, "protected boolean universalMouseClicked(").contains("requestClose()"), "取消按钮也先问");
        String fp = extractBlock(s, "private String draftFingerprint()");
        for (String field : new String[]{"goodsList", "goodsFluids", "spark", "eu", "coinCounts", "itemCounts", "fluids",
                "submissionCounts", "displayIcons", "rewardPool", "ftbqTableId", "prerequisiteQuestId", "linkTo", "displayName"}) {
            assertTrue(fp.contains(field), "指纹要涵盖 " + field);
        }
    }

    @Test
    void ctrlShortcutsSaveAndSwitchTabs() throws Exception {
        String s = src();
        String keys = extractBlock(s, "public boolean keyPressed(int keyCode, int scanCode, int modifiers)");
        assertTrue(keys.contains("hasControlDown()"));
        assertTrue(keys.contains("ShopEntryEditLayout.tabForHotkey("), "Ctrl+1~5 切页");
        assertTrue(keys.contains("GLFW_KEY_TAB") && keys.contains("ShopEntryEditLayout.cycle("), "Ctrl+Tab 轮换页签");
        assertTrue(keys.contains("ui.takeEnter()"), "按住 Ctrl+Enter 只保存一次");
        assertTrue(extractBlock(s, "public boolean keyReleased(").contains("ui.enterReleased("));
        assertTrue(extractBlock(s, "protected void initScaled()").contains("ui.syncEnterHeld()"));
    }

    // ===== E5 表单内错误提示 =====

    @Test
    void saveProblemsShowInTheFormNotInChat() throws Exception {
        String s = src();
        String submit = extractBlock(s, "private void submit()");
        assertFalse(submit.contains("displayClientMessage"), "保存失败不再只发聊天消息，界面上看不出哪栏出错");
        assertTrue(submit.contains("problems()"));
        assertTrue(submit.contains("selectTab(") && submit.contains("ShopEntryEditLayout.scrollToShow("), "跳到出错那页并把那一行滚出来");
        assertTrue(submit.contains("ui.reject("), "保存按钮轻抖并出失败音");
        String problems = extractBlock(s, "private List<Problem> problems()");
        assertTrue(problems.contains("Tab.GOODS") && problems.contains("Tab.PRICE") && problems.contains("Tab.REWARD"));
        assertTrue(problems.contains("设为免费"), "成本为空时告诉玩家怎么做成免费商品");
    }

    @Test
    void problemTabsGetARedDotAndTheFooterSaysWhy() throws Exception {
        String s = src();
        assertTrue(extractBlock(s, "private void drawTabs(").contains("showProblems"));
        String footer = extractBlock(s, "private String footerText()");
        assertTrue(footer.contains("catalogSnapshotValid()") && footer.contains("problems()"));
        String ui = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopDialogUi.java"));
        assertTrue(extractBlock(ui, "void reject(").contains("ShopUiSounds.fail()"));
    }

    @Test
    void namesAndTitlesWrapInsteadOfBeingCut() throws Exception {
        String s = src();
        assertFalse(s.contains("trimText("), "商品名、FTBQ 表名、任务名、分类名都换行，不截断");
        assertTrue(s.contains("ShopDialogRows.fitFrom("), "分类下拉行高可变，按实际能放下几行翻页");
    }

    // ===== 槽位一律走商店自建选择器 =====

    private static String picker() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/MultiPickerScreen.java"));
    }

    @Test
    void slotClicksNeverOpenTheFtbLibPickers() throws Exception {
        String s = src();
        assertFalse(s.contains("EditorWidgets.openItemPicker("), "点已有物品格不再开 FTBLib/FTBQ 的选取页");
        assertFalse(s.contains("EditorWidgets.openFluidPicker("), "点流体格也一样");
        assertTrue(s.contains(".replacing()") && s.contains(".preselect("), "开自建多选器的替换模式，原来的东西先放进已选清单");
        assertTrue(s.contains("ShopEntryEditLayout.replaceAt("), "确认后整份清单换掉这一格");
    }

    @Test
    void emptyGoodsPlaceholderOpensTheShopPicker() throws Exception {
        String s = src();
        assertTrue(extractBlock(s, "private boolean goodsRowClicked(").contains("openGoodsReplacePicker("),
                "新商品的空占位格点了就开自建选择器");
        String settle = extractBlock(s, "private void settleGoods(");
        assertTrue(settle.contains("removeIf(ItemStack::isEmpty)"), "选好东西后空占位格让位");
        assertTrue(settle.contains("count ="), "首项换了，新首项自带的数量挪进「每份数量」");
        assertTrue(extractBlock(s, "private void openGoodsPicker()").contains("settleGoods("), "「+」加进来的也要顶掉空占位格");
    }

    @Test
    void coinSlotsAreReplacedOnlyFromConfiguredCurrencies() throws Exception {
        String open = extractBlock(src(), "private void openCostReplacePicker(");
        assertTrue(open.contains("CurrencyRateConfig.getCurrencies()"), "币种格只能换成已配置的货币");
        assertTrue(open.contains("preselect("), "原来的币种和精确数量先放进清单");
    }

    @Test
    void theShopPickerHasAReplaceMode() throws Exception {
        String p = picker();
        assertTrue(p.contains("public MultiPickerScreen replacing()"));
        assertTrue(p.contains("public MultiPickerScreen preselect(ItemStack"));
        assertTrue(p.contains("public MultiPickerScreen preselect(FluidStack"));
        assertTrue(p.contains("public MultiPickerScreen preselectTexture("), "贴图图标也能点开换");
        assertTrue(p.contains("确认替换") && p.contains("移除这一格"), "按钮写明确认后会发生什么");
    }

    // ===== 动效与交互细节 =====

    @Test
    void motionStateLivesInOnePlaceAndHonoursReducedMotion() throws Exception {
        String s = src();
        assertTrue(s.contains("new ShopEntryEditMotion()"));
        assertTrue(extractBlock(s, "protected void renderScaledBackground(")
                .contains("motion.setReduceMotion(ClientShopUiSettings.reduceMotion())"));
    }

    @Test
    void openingScalesInAndTabsSlideWithTheirInputs() throws Exception {
        String s = src();
        String bg = extractBlock(s, "protected void renderScaledBackground(");
        assertTrue(bg.contains("motion.openScale(") && bg.contains("motion.slideX("));
        assertTrue(extractBlock(s, "private void selectTab(").contains("motion.tabChanged("));
        assertTrue(extractBlock(s, "private void drawTabs(").contains("motion.underline("), "下划线滑到新页签");
        assertTrue(s.contains("new AnimatableEditBox("), "输入框跟着开场缩放和切页平移");
        assertTrue(s.contains("setSlideX(") && s.contains("setAnim("));
        assertFalse(s.contains("new EditBox(this.font, fx"), "页内输入框不再用不会动的原版框");
    }

    @Test
    void pageScrollGlidesWhileHitTestsUseTheShownValue() throws Exception {
        String s = src();
        assertTrue(s.contains("motion.scroll(") && s.contains("motion.snapScroll("));
        assertTrue(extractBlock(s, "protected boolean universalMouseScrolled(").contains("scrollTarget()"),
                "连滚几格按目标累加，不被动画拖慢");
        assertTrue(extractBlock(s, "private void layoutRows()").contains("setScroll(scrollTarget())"),
                "排版只夹目标，不把动画中途的值当新目标");
    }

    @Test
    void overlaysPopInAndFadeOut() throws Exception {
        String s = src();
        assertTrue(s.contains("GuiRenderUtil.withPanelMotion("));
        String fg = extractBlock(s, "protected void renderScaledForeground(");
        assertTrue(fg.contains("ShopEntryEditMotion.visible("), "关掉后还画着淡出");
        for (String overlay : new String[]{"discardConfirmOpen", "descEditorOpen", "qtyEditorOpen", "catPickerOpen"}) {
            assertTrue(s.contains("motion.panel(") && s.contains(overlay + ", now)"), overlay + " 走弹簧开合");
        }
    }

    @Test
    void slotsGlowPopSlideAndShake() throws Exception {
        String s = src();
        String slot = extractBlock(s, "private void drawSlot(");
        assertTrue(slot.contains("ui.hoverAt("), "悬停外圈金光渐亮");
        assertTrue(slot.contains("motion.slotScale(") && slot.contains("motion.slotShiftX("));
        assertTrue(slot.contains("ui.withMotion("), "被拒时轻抖");
        assertTrue(s.contains("motion.popSlots("), "从选择器回来，新放进的格子弹出来");
        assertTrue(s.contains("motion.slotRemoved("), "右键删掉后后面的格子补位");
        assertTrue(extractBlock(s, "private boolean goodsRowClicked(").contains("ui.reject("), "最后一件商品删不掉：轻抖并出失败音");
        assertTrue(s.contains("motion.breath("), "空占位格的「?」提示可以点");
        String ui = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopDialogUi.java"));
        assertTrue(ui.contains("float hoverAt(") && ui.contains("void withMotion("));
    }

    @Test
    void failedSaveFlashesTheRowAndNewRowsFadeIn() throws Exception {
        String s = src();
        assertTrue(extractBlock(s, "private void submit()").contains("motion.startFlash("), "出错那一行闪红");
        String change = extractBlock(s, "private void changeRows(");
        assertTrue(change.contains("motion.startReveal("), "免费/奖励模式切换后冒出来的行淡入");
        String click = extractBlock(s, "private boolean clickRow(");
        assertTrue(click.contains("changeRows(() -> setFreeGoods(") && click.contains("changeRows(this::cycleRewardMode)"));
    }

    @Test
    void hoveringASlotExplainsWhatClicksDo() throws Exception {
        String s = src();
        assertTrue(extractBlock(s, "protected void renderTooltips(").contains("hoverSlotTip"));
        assertTrue(s.contains("左键 替换") && s.contains("右键 删除"));
    }
}
