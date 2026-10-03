package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EaepInfinityCellUuidGuardSourceTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "EaepInfinityCellUuidGuardMixin.java");

    @Test
    void guardRunsBeforeEaepPersistAndOnlyRepairsNonEmptyCells() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory"));
        assertTrue(source.contains("method = \"persist\""));
        assertTrue(source.contains("BigInteger total = totalAEKey2Amounts;"));
        assertTrue(source.contains("InfinityConstants.INFINITY_CELL_UUID"));
        assertTrue(source.contains("hasUUID(InfinityConstants.INFINITY_CELL_UUID)"));
        assertTrue(source.contains("putUUID"));
        assertTrue(Files.readString(CONFIG).contains("EaepInfinityCellUuidGuardMixin"));
    }

    @Test
    void eaepRetainsOwnershipOfStorageTotalsAndPersistence() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("@Redirect(method = \"insert\""));
        assertFalse(source.contains("@Redirect(method = \"extract\""));
        assertFalse(source.contains("gtShanhai$markChanged"));
        assertFalse(source.contains("@Overwrite"),
                "EAEP 1.5.4.1 已增量维护总量，不能再覆盖其存取与持久化逻辑");
    }

    @Test
    void compatibleWithEaepStorageFieldRename() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("AEKey2AmountsMap"),
                "EAEP 1.5.5 已移除该私有字段，mixin 不能 shadow 具体字段名");
        assertFalse(source.contains("abstract BigInteger getTotalAEKey2Amounts"),
                "EAEP 1.5.5 将该方法改为 private，persist 注入应直接复用稳定字段");
        assertFalse(source.contains("abstract boolean hasUUID"),
                "EAEP 1.5.5 将该方法改为 private，UUID 判定应直接读取 ItemStack NBT");
        assertFalse(source.contains("getCellStoredMap"),
                "不可依赖 EAEP 1.5.3 的内存映射实现");
    }

    @Test
    void diskLoadUsesSavedDataBeforeEaepFirstWorldTick() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("method = \"initData\""));
        assertTrue(source.contains("server.isSameThread()"));
        assertTrue(source.contains("InfinityStorageManager.getInstance(server)"));
        assertTrue(source.contains("ExtendedAEPlus.STORAGE_INSTANCE ="));
        assertFalse(source.contains("method = \"getStorageManagerInstance\""),
                "EAEP 1.5.3 与 1.5.4.1 的方法静态性不同");
    }
}
