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
}
