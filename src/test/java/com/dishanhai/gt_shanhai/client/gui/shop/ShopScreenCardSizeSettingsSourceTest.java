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
}
