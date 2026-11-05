package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：KubeJS 取消原版方法後，進度仍須涵蓋實際處理入口。 */
class ShanhaiLoadingProgressWiringTest {
    private static String source(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + file + ".java"));
    }

    @Test void recipeApplyWrapsCancelledBodiesAndCountsTheInputTraversal() throws Exception {
        String mixin = source("mixin/ShanhaiLoadingRecipeMixin");
        assertTrue(mixin.contains("original.call(ShanhaiLoadingClient.trackRecipes(recipes)"),
                "KubeJS 直接取消 apply，不能只計數原版 fromJson 成功回傳");
        assertTrue(mixin.contains("finally { ShanhaiLoadingClient.endRecipes()"));
    }

    @Test void syncCountsReplacementTraversalBeforeIndexAndJeiCallbacks() throws Exception {
        String mixin = source("mixin/ShanhaiLoadingSyncMixin");
        assertTrue(mixin.contains("ShanhaiLoadingClient.trackSyncedRecipes"),
                "同步不能只有 HEAD 0 與 RETURN 100，須包裝實際 replaceRecipes 輸入");
    }

    @Test void waitingFramesRefreshTerrainBeforeTakingSnapshot() throws Exception {
        String client = source("client/loading/ShanhaiLoadingClient");
        String waiting = client.substring(client.indexOf("public static void renderWhileWaiting"));
        int update = waiting.indexOf("updateTerrain(mc.screen)");
        assertTrue(update >= 0 && update < waiting.indexOf("WORLD.snapshot"),
                "managedBlock 補幀也須重新讀取區塊監聽器，不能反覆畫舊的 0%");
    }

    @Test void dynamicPackFiltersNamespaceBeforeCreatingResourceSuppliers() throws Exception {
        Path file = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin/ShanhaiLoadingDataPackMixin.java");
        assertTrue(Files.exists(file), "動態資料包必須修正跨 namespace 重複列舉");
        String mixin = Files.readString(file);
        assertTrue(mixin.contains("namespace.equals(id.getNamespace())"));
        assertTrue(mixin.contains("Ljava/util/stream/Stream;forEach"));
    }

    @Test void dynamicPackAlsoAdvertisesNamespacesPresentOnlyInGeneratedData() throws Exception {
        String mixin = source("mixin/ShanhaiLoadingDataPackMixin");
        assertTrue(mixin.contains("DATA.keySet()"),
                "篩選 namespace 前必須包含 DATA 裡額外產生的 namespace，避免漏掉自訂 ID");
        assertTrue(mixin.contains("getNamespaces"));
    }
}
