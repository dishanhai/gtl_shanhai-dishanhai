package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopScreenCardSizeSettingsSourceTest {

    @Test
    void shopGridCardSizeComesFromClientUiSettings() throws Exception {
        Path settingsPath = Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "client", "shop", "ClientShopUiSettings.java");
        assertTrue(Files.exists(settingsPath), "商品卡片大小必须有独立的客户端 UI 设置存储");

        String screen = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        String settingsScreen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopSettingsScreen.java"));
        String uiSettings = Files.readString(settingsPath);

        assertTrue(screen.contains("import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;"));
        assertTrue(screen.contains("Math.min(ClientShopUiSettings.cardWidth(), cardWidthLimitForTenColumns())"),
                "卡片宽度设置必须作为基准值，但低分辨率下要按 10 列可用宽度自动收缩");
        assertTrue(screen.contains("Math.min(ClientShopUiSettings.cardHeight(), cardHeightLimitForTargetRows())"),
                "卡片高度设置必须作为基准值，但低分辨率下要按目标行数自动收缩");
        assertTrue(screen.contains("DEFAULT_GRID_COLS * (cellW() + GRID_GAP) + 2"),
                "默认网格宽度必须继续按每行 10 格计算");
        assertFalse(screen.contains("private static final int CELL_W ="),
                "商品格宽不能继续硬编码成固定常量");
        assertFalse(screen.contains("private static final int CELL_H ="),
                "商品格高不能继续硬编码成固定常量");

        assertTrue(settingsScreen.contains("cardWidthBox") && settingsScreen.contains("cardHeightBox"),
                "商店设置页必须暴露商品卡片宽/高输入");
        assertTrue(settingsScreen.contains("商品卡片大小"));
        assertTrue(settingsScreen.contains("ClientShopUiSettings.setCardSize"),
                "卡片大小是客户端显示偏好，保存时不能发到服务端全局设置包");

        assertTrue(uiSettings.contains("DEFAULT_CARD_WIDTH = 70"));
        assertTrue(uiSettings.contains("DEFAULT_CARD_HEIGHT = 36"));
        assertTrue(uiSettings.contains("shop_client_ui.json"));
    }

    @Test
    void shopSettingsScrollsAndStoresRewardPreviewMode() throws Exception {
        String settingsScreen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopSettingsScreen.java"));
        String screen = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        String uiSettings = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "client", "shop", "ClientShopUiSettings.java"));

        assertTrue(settingsScreen.contains("settingsScroll"), "商店设置页内容超出视口时要能滚动");
        assertTrue(settingsScreen.contains("universalMouseScrolled"), "滚轮要滚设置页，而不是把事件交给输入框");
        assertTrue(settingsScreen.contains("获取预览模式"), "商店设置页要能切换获取预览模式");
        assertTrue(settingsScreen.contains("ClientShopUiSettings.setRewardPreviewBar"),
                "获取预览模式是客户端显示偏好，不能写进服务端设置包");
        assertTrue(uiSettings.contains("rewardPreview"));
        assertTrue(uiSettings.contains("PREVIEW_GRID"));
        assertTrue(screen.contains("drawGoodsBars"), "普通商品的条形预览要单独画数量，不能复用概率列");
        assertTrue(screen.contains("drawCostBars"), "花费预览的条形也要画数量，并跟获取预览模式走同一个开关");
        assertTrue(screen.contains("drawRewardOddsGrid"), "方格模式仍要画出奖励表概率");
        assertTrue(screen.contains("ClientShopUiSettings.rewardPreviewBar()"));
    }
}
