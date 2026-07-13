package com.dishanhai.gt_shanhai.client.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientShopCatalogLifecycleSourceTest {

    @Test
    void clientDisconnectClearsServerSpecificCatalog() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/ClientInit.java"));
        assertTrue(source.contains("ClientPlayerNetworkEvent.LoggingOut"));
        assertTrue(source.contains("ClientShopCatalog.clear()"));
    }
}
