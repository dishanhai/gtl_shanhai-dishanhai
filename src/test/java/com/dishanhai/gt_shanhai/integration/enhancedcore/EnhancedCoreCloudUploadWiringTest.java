package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 山海：上傳必須經原生來源交易，且中央庫嘗試先於星律寫入。 */
class EnhancedCoreCloudUploadWiringTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void quickEncodeTriesOptionalLibraryBeforeAnyStellarMutation() throws Exception {
        String source = Files.readString(ROOT.resolve("common/item/JeiPatternQuickEncodeService.java"));
        int cloud = source.indexOf("tryUploadToCloudLibrary(player, menu, patterns)");
        assertTrue(cloud > 0);
        assertTrue(cloud < source.indexOf("List<UploadedPattern> uploaded = new ArrayList<>(patterns.size())"));
        assertTrue(source.contains("EnhancedCoreCloudCompat.isAvailable()"));
    }

    @Test
    void bridgeReusesValidatedNativeUploadAndNeverWritesStoreDirectly() throws Exception {
        Path bridge = ROOT.resolve("integration/enhancedcore/EnhancedCoreCloudUpload.java");
        assertTrue(Files.exists(bridge));
        String source = Files.readString(bridge);
        assertTrue(source.contains("cloud$upload()"));
        assertTrue(source.contains("getEncodedPatternInv()"));
        assertTrue(source.contains("containsOriginal(pattern)"));
        assertTrue(source.contains("logic.saveChanges()"));
        assertFalse(source.contains("store.upload("));
        assertFalse(source.contains(".acknowledge("));
    }
}
