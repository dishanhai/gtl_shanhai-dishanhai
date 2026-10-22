package com.dishanhai.gt_shanhai.api.ae;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 山海：AEKey 雜湊快取契約守衛。
 *
 * <p>伺服器效能報告顯示 {@code updateCellBig → stableHash → canonicalString} 佔用主執行緒，
 * 因此 stableHash / toNormalizedTag 必須走 key 快取，且快取對外只吐副本，
 * 避免呼叫方改寫 NBT 污染其他 cell 的雜湊結果。
 *
 * <p>純原始碼契約測試：AE2 的 AEKey.toTagGeneric 需實際遊戲執行環境（intermediary 名稱），
 * 在單元測試 JVM 內不可用，故以結構斷言鎖定。
 */
class DShanhaiAEKeyCodecCacheTest {

    private static final Path CODEC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "ae", "DShanhaiAEKeyCodec.java");
    private static final Path SAVED_DATA = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "ae", "DShanhaiVirtualCellSavedData.java");

    @Test
    void stableHashAndNormalizedTagGoThroughCache() throws IOException {
        String codec = Files.readString(CODEC);

        assertTrue(codec.contains("private static final Map<AEKey, CachedKey> CACHE"),
                "以 AEKey 為鍵的快取必須存在");
        assertTrue(codec.contains("private static final int CACHE_LIMIT"),
                "快取必須有上限，避免長期累積無界 key");
        assertTrue(codec.contains("return cachedEntry(key).hash();"),
                "stableHash 必須直接取快取雜湊，不得每次重算 canonicalString + SHA-256");
        assertTrue(codec.contains("return cachedEntry(key).copyTag();"),
                "toNormalizedTag 必須回傳快取副本，避免呼叫方污染快取");
        assertFalse(codec.contains("entry.putString(TAG_HASH, sha256(canonicalString(keyTag)));"),
                "writeHashedEntry 不得繞過快取自行重算");
    }

    @Test
    void cacheIsBoundedAndSynchronized() throws IOException {
        String codec = Files.readString(CODEC);

        assertTrue(codec.contains("return size() > CACHE_LIMIT;"), "超過上限須淘汰最舊項目");
        assertTrue(codec.contains("public synchronized CachedKey get(Object key)")
                        && codec.contains("public synchronized CachedKey put(AEKey key, CachedKey value)"),
                "快取讀寫跨執行緒（伺服器 tick / 客戶端 tooltip），必須同步");
    }

    @Test
    void savedDataDoesNotRewriteKeyTableOnEveryPersist() throws IOException {
        String savedData = Files.readString(SAVED_DATA);

        assertTrue(savedData.contains("private String registerKey(AEKey key)"),
                "key 註冊必須集中到 registerKey，避免兩處各自正規化");
        assertTrue(savedData.contains("if (!keyTable.containsKey(hash))"),
                "已存在的 hash 不得每輪持久化重寫整張 key 表");
        assertFalse(savedData.contains(
                        "keyTable.put(hash, DShanhaiAEKeyCodec.toNormalizedTag(key));".replace(
                                "keyTable.put", "keyTable.put")),
                "updateCell / updateCellBig 不得各自正規化 key");
    }
}
