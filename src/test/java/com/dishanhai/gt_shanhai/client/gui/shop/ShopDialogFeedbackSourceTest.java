package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopDialogFeedbackSourceTest {

    private static final String DIR = "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/";
    private static final String[] DIALOGS = {
            "ShopAutoCraftConfirmScreen.java", "RewardChoiceScreen.java", "FtbqRewardChoiceScreen.java"};

    private static String src(String name) throws Exception {
        return Files.readString(Path.of(DIR + name));
    }

    @Test
    void sharedDialogKitReusesTheShopFeedbackCore() throws Exception {
        String ui = src("ShopDialogUi.java");
        assertTrue(ui.contains("new ButtonFeedback(hover)"), "hover 与按下共用一张表，观感和商店主界面一致");
        assertTrue(ui.contains("ShopUiSounds.click()") && ui.contains("ShopUiSounds.limit()"),
                "可用按钮出点击声，不可用的轻抖低响");
        assertTrue(ui.contains("GuiRenderUtil.withButtonMotion(") && ui.contains("ButtonFeedback.shade("));
        assertTrue(ui.contains("ClientShopUiSettings.reduceMotion()"), "跟随减少动效开关");
        assertTrue(ui.contains("mouseHandler.isLeftPressed()"), "松开落在别处时每帧兜底");
    }

    @Test
    void everyDialogDrivesTheKitAndNoLongerTrimsText() throws Exception {
        for (String name : DIALOGS) {
            String s = src(name);
            assertTrue(s.contains("new ShopDialogUi("), name + " 接入共用按钮反馈");
            assertTrue(extractBlock(s, "protected void renderScaledBackground(").contains("ui.frame(mx, my)"), name);
            assertTrue(extractBlock(s, "protected boolean universalMouseReleased(").contains("ui.release()"), name);
            assertTrue(extractBlock(s, "public void removed()").contains("ui.cancel()"), name);
            assertTrue(s.contains("ui.hit(") && s.contains("ui.draw("), name + " 按钮经 hit/draw");
            assertFalse(s.contains("private void drawBtn("), name + " 旧的无反馈按钮画法已移除");
            assertFalse(s.contains("trimText("), name + " 文字换行，不截断");
            assertTrue(s.contains("this.font.split("), name + " 长文字换行");
            String keys = extractBlock(s, "public boolean keyPressed(int keyCode, int scanCode, int modifiers)");
            assertTrue(keys.contains("GLFW_KEY_ENTER") && keys.contains("GLFW_KEY_KP_ENTER"), name + " Enter 确认");
            assertTrue(keys.contains("ui.press("), name + " 键盘确认也有按下反馈");
        }
    }

    @Test
    void pagerLineSitsAboveTheButtonsInsteadOfUnderThem() throws Exception {
        for (String name : DIALOGS) {
            String s = src(name);
            assertTrue(s.contains("FOOTER_H"), name + " 列表底部给页码行和按钮各留位置");
            assertFalse(s.contains("listH = panelHeight - 26 - 30;"), name + " 旧算法会让页码压到按钮上");
            assertTrue(s.contains("ui.drawScrollbar("), name + " 溢出时有可拖动的滚动条");
            assertTrue(s.contains("ui.beginDrag(") && s.contains("ui.drag("), name);
        }
    }

    @Test
    void rewardChoicesGoBackToTheShopAndCarryARequestId() throws Exception {
        for (String name : new String[]{"RewardChoiceScreen.java", "FtbqRewardChoiceScreen.java"}) {
            String s = src(name);
            String confirm = extractBlock(s, "private void confirm()");
            assertTrue(confirm.contains("parent.submitRewardChoice("), name + " 经商店发包，确认按钮接着显示等待与结果");
            assertFalse(confirm.contains("new ShopActionPacket("), name);
            assertTrue(extractBlock(s, "public void onClose()").contains("setScreen(parent)"), name + " Esc 回商店而不是关掉整个界面");
            String keys = extractBlock(s, "public boolean keyPressed(int keyCode, int scanCode, int modifiers)");
            assertTrue(keys.contains("GLFW_KEY_UP") && keys.contains("GLFW_KEY_DOWN"), name + " 上下键选奖励");
            assertTrue(s.contains("ShopDialogRows.scrollToShow("), name + " 键盘选中的行滚进视野");
        }
        String shop = src("ShopScreen.java");
        String submit = extractBlock(shop, "boolean submitRewardChoice(");
        assertTrue(submit.contains("buttonFeedback.tryBegin(") && submit.contains("ClientShopActionResults.nextRequestId()"));
        assertTrue(submit.contains("buttonFeedback.pending(") && submit.contains("confirmActionKey()"));
    }

    @Test
    void autoCraftConfirmKeepsItsOneShotProtocol() throws Exception {
        String s = src("ShopAutoCraftConfirmScreen.java");
        String clicked = extractBlock(s, "protected boolean universalMouseClicked(");
        assertTrue(clicked.contains("btn != 0"));
        assertTrue(s.contains("new ShopAutoCraftConfirmPacket(planId, true)"));
        assertTrue(s.contains("new ShopAutoCraftConfirmPacket(planId, false)"));
        String keys = extractBlock(s, "public boolean keyPressed(int keyCode, int scanCode, int modifiers)");
        assertTrue(keys.contains("GLFW_KEY_PAGE_DOWN") && keys.contains("GLFW_KEY_END"), "长清单可用键盘翻页");
    }
}
