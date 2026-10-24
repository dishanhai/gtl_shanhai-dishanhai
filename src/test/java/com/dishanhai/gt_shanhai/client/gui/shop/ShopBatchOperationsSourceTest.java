package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopBatchOperationsSourceTest {

    private static String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai", relativePath));
    }

    @Test
    void altClickUsesStableIdsAndDoesNotFallThroughToOtherCardActions() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("Screen.hasAltDown()"),
                "商品卡片应识别 Alt + 左键");
        assertTrue(screen.contains("batchSelectionStableIds"),
                "批量名单应使用稳定 ID 保存");
        assertTrue(screen.contains("renderBatchSelectionOutline"),
                "批量选中卡片应绘制独立外框");
    }

    @Test
    void selectedContextMenuUsesOneBatchPacketForManagementActions() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        String packet = source("network/ShopBatchManagePacket.java");
        assertTrue(screen.contains("ShopBatchManagePacket"),
                "批量管理动作应走专用封包");
        assertTrue(packet.contains("enum Action"),
                "批量封包应声明动作枚举");
        assertTrue(packet.contains("stableIds"),
                "批量封包应传输稳定 ID 集合");
        assertTrue(screen.contains("批量"),
                "批量菜单应明确显示批量语义");
    }

    @Test
    void batchContextMenuCanSetTradeDirectionForAllSelectedEntries() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        String packet = source("network/ShopBatchManagePacket.java");
        String config = source("common/shop/ShopConfig.java");
        assertTrue(screen.contains("批量设为仅购买"),
                "批量菜单应提供仅购买交易方向");
        assertTrue(screen.contains("批量设为仅出售"),
                "批量菜单应提供仅出售交易方向");
        assertTrue(screen.contains("批量设为不限"),
                "批量菜单应提供不限交易方向");
        assertTrue(packet.contains("SET_TRADE_MODE"),
                "批量封包应声明交易方向动作");
        assertTrue(packet.contains("ShopEntry.TradeMode"),
                "批量封包应传输既有交易方向枚举");
        assertTrue(config.contains("batchSetTradeMode"),
                "服务端配置应一次更新整批商品交易方向");
    }

    @Test
    void altRightClickOnEmptyAreaClearsBatchCandidates() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("Screen.hasAltDown() && btn == 1"),
                "空白区重置应识别 Alt + 右键");
        assertTrue(screen.contains("clearBatchSelection()"),
                "空白区重置应清空批量名单");
        assertTrue(screen.contains("批量名单已清空"),
                "空白区重置应给出明确提示");
    }

    @Test
    void batchDeleteKeepsSecondConfirmationAndServerBatchProcessing() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        String config = source("common/shop/ShopConfig.java");
        assertTrue(screen.contains("pendingDeleteBatchIds"),
                "批量删除应保留待确认状态");
        assertTrue(screen.contains("DELETE_CONFIRM_WINDOW_MS"),
                "批量删除应沿用二次确认窗口");
        assertTrue(config.contains("removeEntries"),
                "服务端应一次处理批量删除");
    }

    @Test
    void hoverTooltipIncludesTradeAndLimitDetails() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("交易方向"),
                "商品悬停详情应展示交易方向");
        assertTrue(screen.contains("出售回收"),
                "商品悬停详情应展示出售回收信息");
        assertTrue(screen.contains("周期限购"),
                "商品悬停详情应展示周期限购");
        assertTrue(screen.contains("奖励模式"),
                "商品悬停详情应展示奖励模式");
    }

    @Test
    void periodLimitDetailShowsUsedAndRemainingCounts() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        String packet = source("network/WalletAccountSyncPacket.java");
        String client = source("client/shop/ClientWalletAccount.java");
        assertTrue(screen.contains("本周期已购") && screen.contains("ClientWalletAccount.getPeriodUsed(key)"),
                "详情页应显示周期限购的已购/剩余具体次数");
        assertTrue(packet.contains("periodUsed") && client.contains("getPeriodUsed"),
                "钱包同步应带上周期已用次数");
    }

    @Test
    void detailPanelFoldsLongTextBehindManualExpand() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("drawFoldedBlock(g, \"need\", \"§7预计消耗: \""),
                "预计消耗超长应折叠，手按[展开]才显示全文");
        assertTrue(screen.contains("drawFoldedBlock(g, \"cost\", costLabel"),
                "成本超长应折叠");
        assertTrue(screen.contains("drawWrappedBlock(g, \"§8含实物成本"),
                "实物成本提示应换行显示全文");
        assertTrue(screen.contains("§b[展开]") && screen.contains("§b[收起]") && screen.contains("expandedTexts"),
                "折叠文字应有展开/收起按钮并只在玩家点击后展开");
        assertTrue(screen.contains("wrapFcs(") && screen.contains("placeFcsToken("),
                "折叠/换行必须走字符串路径并把 &$ 内联码插回，不能用 FormattedCharSequence");
        assertTrue(screen.contains("drawBarName("),
                "花费/获得预览条形的名称应走折叠而不是省略号截断");
    }

    @Test
    void scrollingPastEdgeBouncesGridAndDetail() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("gridBounceSpring") && screen.contains("detailBounceSpring"),
                "网格与详情页都应有触底反弹弹簧");
        assertTrue(screen.contains("advanceBounce(") && screen.contains("ClientShopUiSettings.reduceMotion()"),
                "触底反弹应在减少动效时关闭");
    }

    @Test
    void shopThemeSwitchesBetweenClassicAndGeneratorGlass() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        String theme = source("client/gui/shop/ShopTheme.java");
        String settings = source("client/gui/shop/ShopSettingsScreen.java");
        String prefs = source("client/shop/ClientShopUiSettings.java");
        assertTrue(theme.contains("GlassPalette.ACCENT") && theme.contains("GlassPalette.LIT") && theme.contains("GlassBlur.backdrop("),
                "玻璃主题应沿用样板生成器的 GlassPalette 与受光亮边");
        assertTrue(screen.contains("applyTheme(ShopTheme.current())") && screen.contains("theme.backdrop(g, left, top, panelWidth, panelHeight)"),
                "商店每帧按主题回填结构色，玻璃主题先实时糊掉面板后面的世界");
        assertTrue(settings.contains("界面主题") && settings.contains("setShopTheme("),
                "商店设置应能切换并保存界面主题");
        assertTrue(prefs.contains("\"shopTheme\"") && prefs.contains("THEME_CLASSIC"),
                "主题只存客户端，默认经典");
    }

    @Test
    void glassPopupsBlurTheirInteriorInsteadOfTheRing() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("private void glassPopupBackdrop(") && screen.contains("glassPopupBackdrop(g, p, hole)"),
                "玻璃主题的二级界面应在面板内部做毛玻璃");
        assertTrue(screen.contains("if (!theme.glass) requestContentGauss("),
                "玻璃主题不再糊二级界面的外圈");
    }

    @Test
    void glassButtonsAndRegionsGetTheirOwnDarkerBlur() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        String theme = source("client/gui/shop/ShopTheme.java");
        String style = source("client/gui/shop/ShopGlassStyle.java");
        String blur = source("client/gui/shop/ShopGaussBlur.java");
        assertTrue(style.contains("CONTROL = new ShopGlassStyle(") && style.contains("REGION = new ShopGlassStyle("),
                "按钮与区域应各有一套压暗去色的玻璃样式");
        assertTrue(theme.contains("ShopGlassStyle.CONTROL") && theme.contains("ShopGlassStyle.REGION"),
                "主题应提供按钮/区域的毛玻璃入口");
        assertTrue(screen.contains("theme.controlBackdrop(g, x, y, w, h);")
                        && screen.contains("theme.controlBackdrop(g, xs[idx], y, ws[idx], h);"),
                "按钮与页签的底盒之前先糊背后");
        assertTrue(screen.contains("theme.regionBackdrop(g, dx, dy, dw, dh);")
                        && screen.contains("theme.regionBackdrop(g, gx, gy, gw, gh);"),
                "详情区与网格区应各自做区域毛玻璃");
        assertTrue(blur.contains("clipToScissor(") && blur.contains("RenderSystem.enableScissor(box[0], box[1], box[2], box[3])"),
                "滚动区里的按钮模糊不能糊出裁剪框，也不能把调用方的 scissor 关掉");
    }

    @Test
    void shopSubScreensFollowTheGlassTheme() throws Exception {
        for (String name : new String[]{"CurrencyAtmScreen", "ExchangeScreen", "ShopMembershipScreen"}) {
            String screen = source("client/gui/shop/" + name + ".java");
            assertTrue(screen.contains("theme = ShopTheme.current();") && screen.contains("theme.rootPanel(g, left, top, panelWidth, panelHeight,"),
                    name + " 根板应跟随商店主题");
            assertTrue(screen.contains("theme.region(g, ") && screen.contains("theme.button(g, this.font, "),
                    name + " 区域与按钮应走主题的毛玻璃");
            assertTrue(screen.contains("renderBackdropDim("), name + " 玻璃主题下不能再盖原版黑幕");
        }
        for (String name : new String[]{"ShopSettingsScreen", "ShopAutoCraftConfirmScreen", "RewardChoiceScreen",
                "FtbqRewardChoiceScreen", "ShopSingleUseWarnScreen"}) {
            assertTrue(source("client/gui/shop/" + name + ".java").contains(".rootPanel(g, left, top, panelWidth, panelHeight,"),
                    name + " 根板应跟随商店主题");
        }
        assertTrue(source("client/gui/shop/ShopDialogUi.java").contains("t.button(g, font, x, y, w, h, label, b, f, 0xFFFFFFFF)"),
                "弹窗按钮应走主题按钮（玻璃下先糊背后）");
    }

    @Test
    void glassThemeOwnsTooltipAndCardFlightLook() throws Exception {
        String tip = source("client/gui/shop/ShopGlassTooltip.java");
        String init = source("client/ClientInit.java");
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(tip.contains("event.setCanceled(true)") && tip.contains("ShopTheme.GLASS.tooltipPanel("),
                "玻璃主题下 tooltip 应由我们自己画成毛玻璃，不用原版黑底紫边");
        assertTrue(init.contains("ShopGlassTooltip::onTooltipPre"), "tooltip 接管必须挂到 Forge 总线");
        assertTrue(screen.contains("theme.controlBackdrop(g, rx, ry, rw, rh, fade);"),
                "玻璃主题的飞行卡片要先糊掉下面再画实一点的底，否则整块透明");
        assertTrue(screen.contains("// 不登记请求"),
                "玻璃主题不再给详情列叠后处理折射");
    }

    @Test
    void firstTimeShopOpenShowsSetupWizardOnce() throws Exception {
        String opener = source("client/gui/shop/ShopScreenOpener.java");
        String wizard = source("client/gui/shop/ShopWelcomeScreen.java");
        String prefs = source("client/shop/ClientShopUiSettings.java");
        String settings = source("client/gui/shop/ShopSettingsScreen.java");
        assertTrue(opener.contains("ClientShopUiSettings.setupDone()") && opener.contains("new ShopWelcomeScreen("),
                "首次打开商店应先弹初始化向导");
        assertTrue(prefs.contains("\"setupDone\"") && prefs.contains("!root.has(\"setupDone\")"),
                "已完成标记要落盘；旧配置文件没有这个键视为老玩家，不打扰");
        assertTrue(wizard.contains("ClientShopUiSettings.setSetupDone(true)") && wizard.contains("public void onClose()"),
                "完成、跳过、ESC 都要记下已完成，否则每次开店都弹");
        assertTrue(wizard.contains("setShopTheme(") && wizard.contains("setFeedbackPrefs(") && wizard.contains("setRememberShopView("),
                "向导的偏好页应写入界面主题、动效音效和记住选取");
        assertTrue(wizard.contains("PAGE_ITEMS") && wizard.contains("货币中心") && wizard.contains("AE 模式"),
                "向导应有功能介绍页");
        assertTrue(settings.contains("new ShopWelcomeScreen(") && settings.contains("新手引导"),
                "商店设置里应能重看向导");
    }

    @Test
    void wizardTestCommandPreviewsWithoutMarkingDone() throws Exception {
        String cmd = source("client/gui/shop/ShopWizardCommand.java");
        String wizard = source("client/gui/shop/ShopWelcomeScreen.java");
        assertTrue(cmd.contains("RegisterClientCommandsEvent") && cmd.contains("\"shopwizard\"") && cmd.contains("\"reset\""),
                "应有纯客户端的 /shanhai shopwizard 与 reset");
        assertTrue(cmd.contains("mc.tell(") && cmd.contains("new ShopWelcomeScreen(() -> null, true)"),
                "命令要延后一轮再开界面（聊天框回车后会 setScreen(null)），且走预览模式");
        assertTrue(wizard.contains("if (!preview) ClientShopUiSettings.setSetupDone(true)"),
                "预览模式不能写已完成标记");
    }

    @Test
    void dragOnlyWorksInEditModeAndNeedsADeliberateMove() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("return canEdit && catalogEditUnlocked;"),
                "拖拽须同时有编辑权并已开启编辑模式");
        assertTrue(screen.contains("!dragTabMoving && dragEnabled()") && screen.contains("btn == 0 && dragEnabled()"),
                "页签拖拽与商品拖拽都要走 dragEnabled");
        assertTrue(screen.contains("DRAG_TAB_THRESHOLD = 14.0") && screen.contains("DRAG_CARD_THRESHOLD = 28.0"),
                "拖拽阈值调高，降低灵敏度");
    }
}
