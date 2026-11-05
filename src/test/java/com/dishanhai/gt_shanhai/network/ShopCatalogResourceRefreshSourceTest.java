package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：FTBQ 首载和重载发生在普通商店初始化之后，必须延迟刷新索引。 */
class ShopCatalogResourceRefreshSourceTest {
    @Test
    void questCacheEventDefersAndCoalescesRefreshUntilServerTickEnd() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/event/ShopCatalogSyncEventHandler.java"));
        assertTrue(source.contains("ClearFileCacheEvent.EVENT.register"));
        assertTrue(source.contains("file instanceof dev.ftb.mods.ftbquests.quest.ServerQuestFile"));
        assertTrue(source.contains("ServerStartedEvent event"));
        assertTrue(source.contains("event.phase != net.minecraftforge.event.TickEvent.Phase.END"));
        int consume = source.indexOf("resourceIndexRefreshPending = false;", source.indexOf("void onServerTick"));
        int refresh = source.indexOf("ShopConfig.refreshResourceIndex()", consume);
        assertTrue(consume >= 0 && refresh > consume);
        String config = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopConfig.java"));
        assertTrue(config.contains("public static synchronized void refreshResourceIndex()"));
        assertTrue(config.contains("publish(current.entries())"));
    }
}
