package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopButtonFeedbackSourceTest {

    private static final String SHOP = "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java";
    private static final String UTIL = "src/main/java/com/dishanhai/gt_shanhai/client/gui/scaled/GuiRenderUtil.java";

    @Test
    void everyButtonHitPressesTheButtonAndReleaseLiftsIt() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        assertTrue(s.contains("private final ButtonFeedback buttonFeedback = new ButtonFeedback(hoverButtons);"),
                "按下态与 hover 共用同一张 hoverButtons 表");
        String hit = extractBlock(s, "private boolean buttonHit(");
        assertTrue(hit.contains("buttonFeedback.press(") && hit.contains("pressKey(x, y, w, h)"),
                "所有按钮点击都经 buttonHit，压下 key 只由几何决定");

        String released = extractBlock(s, "protected boolean universalMouseReleased(");
        int release = released.indexOf("buttonFeedback.release(");
        int tabDrag = released.indexOf("if (dragTabLevel != -1)");
        assertTrue(release >= 0 && release < tabDrag, "松开先结束按下态，再走拖拽分支");
        assertFalse(released.contains("return buttonFeedback.release"), "松开按钮不能吞掉拖拽的松开");
    }

    @Test
    void clickSoundPlaysEvenWhenMotionIsReduced() throws Exception {
        String wave = extractBlock(Files.readString(Path.of(SHOP)), "private void armClickWave(");
        int sound = wave.indexOf("ShopUiSounds.click()");
        int reduce = wave.indexOf("ClientShopUiSettings.reduceMotion()");
        int arm = wave.indexOf("clickWaveAtMs =");
        assertTrue(sound >= 0 && sound < reduce && reduce < arm, "先出声，减少动效时再跳过波纹");
    }

    @Test
    void buttonsDrawThroughTheFeedbackVisual() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String draw = extractBlock(s, "private void drawButton(");
        assertTrue(draw.contains("hoverButtons.follow("), "hover 弹簧照旧");
        assertTrue(draw.contains("my - frameMy"), "滚动区按内容坐标画，要换回屏幕坐标对上按下 key");
        assertTrue(draw.contains("buttonFeedback.visual(") && draw.contains("GuiRenderUtil.withButtonMotion("));
        String render = extractBlock(s, "int border, int textColor, long actionKey)");
        assertTrue(render.contains("buttonFeedback.visual(") && render.contains("GuiRenderUtil.withButtonMotion("));
    }

    @Test
    void frameDrivesPointerAndLifecycleCancelsStuckPresses() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String bg = extractBlock(s, "protected void renderScaledBackground(");
        assertTrue(bg.contains("frameMy = my;"));
        assertTrue(bg.contains("buttonFeedback.pointer(mx, my, nowMs)") && bg.contains("buttonFeedback.tick(nowMs)"));
        assertTrue(bg.contains("mouseHandler.isLeftPressed()"), "松开落到别的界面时由每帧兜底补松开");
        assertTrue(extractBlock(s, "protected void initScaled()").contains("buttonFeedback.cancel("));
        assertTrue(extractBlock(s, "public void removed()").contains("buttonFeedback.cancel("));
    }

    @Test
    void detailSteppersRepeatWhileHeldButCartSteppersDoNot() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String hold = extractBlock(s, "private void beginStepHold(");
        assertTrue(hold.contains("buttonFeedback.holdRepeat(") && hold.contains("RepeatCurve.STEPPER"));
        assertTrue(hold.contains("if (btn != 0) return;"), "只有左键按住才连发");
        assertTrue(hold.contains("ShopUiSounds.step(") && hold.contains("ShopUiSounds.limit()"));
        assertTrue(hold.contains("buttonFeedback.nudge("), "到边界轻抖一下");
        String clicked = extractBlock(s, "protected boolean universalMouseClicked(");
        assertTrue(clicked.contains("beginStepHold(btn, pressKey(bx, dy + 82, bw, 12), steps[i])"));
        assertTrue(clicked.contains("beginStepHold(btn, pressKey(bx, dy + 96, bw, 12), -steps[i])"));
        // 购物车 ± 每次都发 ShopCartEditPacket，连发会刷包
        assertFalse(extractBlock(s, "private boolean handleCartOverlayClick(").contains("holdRepeat"));
    }

    @Test
    void confirmTradeWaitsForTheServerAndFlashesTheResult() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        assertTrue(extractBlock(s, "protected boolean universalMouseClicked(").contains("performConfirmTrade(false)"));
        String trade = extractBlock(s, "private void performConfirmTrade(boolean viaKeyboard, boolean singleUseWarned)");
        assertTrue(trade.contains("buttonFeedback.tryBegin(") && trade.contains("ShopUiSounds.busy()"),
                "等待中或 300ms 内再点只提示忙，不重复下单");
        assertTrue(trade.contains("ClientShopActionResults.nextRequestId()") && trade.contains("buttonFeedback.pending("));
        assertTrue(trade.contains("buttonFeedback.flash(") && trade.contains("ShopUiSounds.fail()"), "本地预检失败就地红闪");
        assertFalse(trade.contains("canTrade"), "红框只是预告，余额与前置以服务端为准，照发");
        for (String msg : new String[]{
                "该商品引用的物品缺失（对应模组可能未安装），无法购买",
                "该商品仅允许出售，不能购买",
                "免费商品不能出售",
                "该商品仅允许购买，不能出售"}) {
            assertTrue(trade.contains(msg), "本地预检文案保留：" + msg);
        }
        assertTrue(trade.contains("new RewardChoiceScreen(") && trade.contains("new FtbqRewardChoiceScreen("),
                "自选奖励照旧先弹选择界面");

        assertTrue(extractBlock(s, "protected void renderScaledBackground(").contains("ClientShopActionResults.drain("));
        String result = extractBlock(s, "private void onActionResult(");
        assertTrue(result.contains("buttonFeedback.resolve(") && result.contains("ShopUiSounds.success("));

        String label = extractBlock(s, "private String confirmLabel(");
        for (String text : new String[]{"处理中", "购买成功", "出售成功", "部分成交", "未成交"}) {
            assertTrue(label.contains(text), "确认按钮要显示：" + text);
        }
        assertTrue(extractBlock(s, "private void drawDetail(").contains("confirmActionKey()"));
    }

    @Test
    void enterConfirmsOnceAndEscapeLeavesTextFieldsFirst() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String keys = extractBlock(s, "public boolean keyPressed(int keyCode, int scanCode, int modifiers)");
        assertTrue(keys.contains("GLFW_KEY_ENTER") && keys.contains("GLFW_KEY_KP_ENTER"));
        assertTrue(keys.contains("performConfirmTrade(true)"));
        assertTrue(keys.contains("enterHeld"), "按住 Enter 的系统重复只算一次");
        assertTrue(keys.contains("searchBox.isFocused()"), "在搜索框里按 Enter 不下单");
        assertTrue(keys.contains("modalLayerOpen()"), "浮层或阶段锁盖着时不下单");
        int lastOverlay = keys.indexOf("closeGuideOverlay(); return true;");
        int focus = keys.indexOf("clearTextFocus()");
        assertTrue(lastOverlay >= 0 && focus > lastOverlay, "Esc 先关浮层，再退出输入框，最后才关店");
        assertTrue(extractBlock(s, "private boolean clearTextFocus()").contains("setFocused(null)"));

        String released = extractBlock(s, "public boolean keyReleased(int keyCode, int scanCode, int modifiers)");
        assertTrue(released.contains("enterHeld = false"));
        assertTrue(released.contains("return super.keyReleased("), "修饰键要交还原版，输入法才能切换");
    }

    @Test
    void detailButtonsExplainThemselvesWithWrappedTooltips() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String tip = extractBlock(s, "private List<net.minecraft.util.FormattedCharSequence> detailButtonTip(");
        assertTrue(tip.contains("this.font.split("), "提示换行，不截断");
        assertTrue(tip.contains("左键或 Enter 确认；处理中再点不会重复下单"));
        assertTrue(tip.contains("按住连续调整，越按越快"));
        assertTrue(extractBlock(s, "private void drawTooltipLayer(").contains("detailButtonTip("));
    }

    @Test
    void buttonMotionRestoresThePoseStack() throws Exception {
        String util = Files.readString(Path.of(UTIL));
        String motion = extractBlock(util, "public static void withButtonMotion(");
        assertTrue(motion.contains("finally") && motion.contains("popPose()"));
        assertTrue(util.contains("public static void drawPendingSweep("));
    }

    static String extractBlock(String source, String declaration) {
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
