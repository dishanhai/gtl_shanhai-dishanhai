package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：詳情入口的實際結果必須接到共用動效，不能只播放點擊成功。 */
class ShopDetailFeedbackSourceTest {
    private static final String ROOT = "src/main/java/com/dishanhai/gt_shanhai/";

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(ROOT + path));
    }

    @Test
    void highlightedActionsHaveStableKeysAndResultLabels() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        assertTrue(shop.contains("detailActionKey(") && shop.contains("detailActionLabel("));
        String clicked = extractBlock(shop, "protected boolean universalMouseClicked(");
        assertTrue(clicked.contains("buttonHit(mx, my, guideLinkX, guideLinkY, guideLinkW, guideLinkH)"));
        assertTrue(clicked.contains("requestCostAutoCraft()"));
        String bookmark = extractBlock(shop, "private void bookmarkMissingCosts()");
        assertTrue(bookmark.contains("ButtonFeedback.Tone.SUCCESS") && bookmark.contains("ButtonFeedback.Tone.FAILED"));
        assertTrue(bookmark.contains("case ALREADY") && bookmark.contains("case UNAVAILABLE"));
        assertTrue(extractBlock(shop, "private long detailActionKey(").contains("ClientShopCatalog.revision()"),
                "entryKey 是快照索引，必須隔離目錄重排後佔用同一索引的商品");
        assertTrue(extractBlock(shop, "private void requestCostAutoCraft()").contains("Long.MAX_VALUE"),
                "整輪 AE 計算不能被比服務端合法計算時間短的客戶端期限截斷");
    }

    @Test
    void autoCraftRequestAndResponseKeepTheRequestId() throws Exception {
        for (String path : new String[]{"network/ShopAutoCraftRequestPacket.java", "network/ShopAutoCraftPlanPacket.java"}) {
            String packet = read(path);
            assertTrue(packet.contains("requestId = buf.readVarInt()"), path);
            assertTrue(packet.contains("buf.writeVarInt(requestId)"), path);
        }
        String server = read("common/shop/ShopAutoCraft.java");
        assertTrue(server.contains("session.requestId = requestId"));
        assertTrue(server.contains("sendPlanFeedback(player, session.requestId, false)"),
                "計算逾時或網路離線也要結束等待");
        assertTrue(server.contains("session.requestId, willCraft.size()"),
                "以真正可提交的項目數判定成功或失敗");
    }

    @Test
    void tooltipAnimationWrapsTheOriginalRenderersAndAlwaysEndsTheFrame() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        String tip = extractBlock(shop, "private void drawTooltipLayer(");
        assertTrue(tip.contains("animateTooltip("));
        assertTrue(tip.contains("tooltipMotion.endFrame()"));
        assertTrue(tip.contains("g.renderTooltip(this.font, detailHoverStack, mx, my)"),
                "保留原版真實物品 tooltip 與擴充內容");
        String animate = extractBlock(shop, "private void animateTooltip(");
        assertTrue(animate.contains("ClientShopUiSettings.reduceMotion()"));
        assertTrue(animate.contains("GuiRenderUtil.withPanelMotion("));
    }

    @Test
    void guideClickHasVisibleFramesAndUsesTheRealOpenResult() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        String click = extractBlock(shop, "private void openDetailGuide()");
        assertTrue(click.contains("pendingGuideHit = guideLinkHit"));
        assertTrue(click.contains("pendingGuideAtMs = now +"), "先顯示點擊回饋再跳頁");
        String tick = extractBlock(shop, "public void tick()");
        assertTrue(tick.contains("ShopGuideLookup.tryOpen("));
        assertTrue(tick.contains("replayGuideSuccess = true"));
        assertTrue(extractBlock(shop, "protected void initScaled()").contains("replayGuideSuccess"),
                "成功閃色從返回商店才開始，不能在指南背後播完");
        assertTrue(extractBlock(read("client/shop/ShopGuideLookup.java"), "public static boolean tryOpen(")
                .contains("return guideme.internal.GuideMEProxy.instance().openGuide("),
                "GuidesCommon 的 void 包裝會吞掉失敗布林值");
    }
}
