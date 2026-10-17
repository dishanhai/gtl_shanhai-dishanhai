package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopScreenOperationHintsSourceTest {

    @Test
    void shopScreenExplainsBuyingAndBatchOperations() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        assertTrue(screen.contains("怎么买 / 卖"), "未选商品时详情列应写出购买步骤");
        assertTrue(screen.contains("Ctrl+左键加入购物车，顶栏购物车一次结算"),
                "底部提示应说明购物车");
        assertTrue(screen.contains("空白处 Alt+右键清空批量名单"),
                "已有批量名单时应提示如何清空");
        assertTrue(screen.contains("左键打开详情 · Ctrl+左键加入购物车 · 右键菜单"),
                "商品悬停应带上操作提示");
        assertTrue(screen.contains("topButtonTip"), "顶栏按钮悬停应有操作说明");
        assertTrue(screen.contains("这个分类是空的"), "空分类应告诉玩家去换页签");
    }
}
