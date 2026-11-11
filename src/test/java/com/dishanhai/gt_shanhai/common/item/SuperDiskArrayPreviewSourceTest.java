package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperDiskArrayPreviewSourceTest {
    @Test
    void sharedPreviewUsesSdaAdapterWithoutRegisteringAnotherTooltipListener() throws IOException {
        String adapter = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/misc",
                "SuperDiskArrayTooltipHandler.java"));
        assertTrue(adapter.contains("ItemContentPreview.register("));
        assertFalse(adapter.contains("ItemTooltipEvent"));
        assertTrue(adapter.contains("readInlineEntries("));
        assertTrue(adapter.contains("readCellBigAmounts("));
        assertTrue(adapter.contains("DShanhaiSdaContentStore.restore("));
        assertTrue(adapter.contains("BigInteger"));
    }
}
