package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogEntryPayload;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 山海商店目录网络格式与解码硬上限；本类只搬运纯字符串/数字，不创建 Minecraft 物品对象。 */
public final class ShopCatalogCodecs {

    public static final int MAX_STUBS = 100_000;
    public static final int MAX_CHUNK_ENTRIES = 16;
    public static final int MAX_ENTRY_JSON_CHARS = 1_048_576;
    public static final int MAX_CHUNK_JSON_CHARS = 2_097_152;
    public static final int MAX_GOODS_PER_STUB = 64;
    public static final int MAX_CATEGORY_CHARS = 256;
    public static final int MAX_LINK_KEY_CHARS = 256;
    public static final int MAX_DISPLAY_NAME_CHARS = 1024;
    public static final int MAX_RESOURCE_ID_CHARS = 256;

    private ShopCatalogCodecs() {}

    public static void writeManifest(FriendlyByteBuf buf, ShopCatalogManifest manifest) {
        List<ShopCatalogManifest.Stub> stubs = manifest == null ? List.of() : manifest.stubs();
        requireEncodeSize("manifest stubs", stubs.size(), MAX_STUBS);
        buf.writeLong(manifest == null ? 0L : manifest.revision());
        buf.writeBoolean(manifest != null && manifest.ready());
        buf.writeVarInt(stubs.size());
        for (ShopCatalogManifest.Stub stub : stubs) {
            if (stub.entryKey() < 0L || stub.chunkId() < 0) {
                throw new EncoderException("invalid shop stub identity");
            }
            buf.writeVarLong(stub.entryKey());
            buf.writeUtf(stub.top(), MAX_CATEGORY_CHARS);
            buf.writeUtf(stub.sub(), MAX_CATEGORY_CHARS);
            buf.writeBoolean(stub.hidden());
            buf.writeVarInt(stub.chunkId());
            buf.writeUtf(stub.linkKey(), MAX_LINK_KEY_CHARS);
            buf.writeUtf(stub.displayName(), MAX_DISPLAY_NAME_CHARS);
            requireEncodeSize("stub goods", stub.goodsIds().size(), MAX_GOODS_PER_STUB);
            buf.writeVarInt(stub.goodsIds().size());
            for (String goodsId : stub.goodsIds()) {
                buf.writeUtf(goodsId == null ? "" : goodsId, MAX_RESOURCE_ID_CHARS);
            }
        }
    }

    public static ShopCatalogManifest readManifest(FriendlyByteBuf buf) {
        long revision = buf.readLong();
        boolean ready = buf.readBoolean();
        int count = readBoundedCount(buf, "manifest stubs", MAX_STUBS);
        List<ShopCatalogManifest.Stub> stubs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long entryKey = buf.readVarLong();
            String top = buf.readUtf(MAX_CATEGORY_CHARS);
            String sub = buf.readUtf(MAX_CATEGORY_CHARS);
            boolean hidden = buf.readBoolean();
            int chunkId = buf.readVarInt();
            String linkKey = buf.readUtf(MAX_LINK_KEY_CHARS);
            String displayName = buf.readUtf(MAX_DISPLAY_NAME_CHARS);
            if (entryKey < 0L || chunkId < 0) throw new DecoderException("invalid shop stub identity");
            int goodsCount = readBoundedCount(buf, "stub goods", MAX_GOODS_PER_STUB);
            List<String> goodsIds = new ArrayList<>(goodsCount);
            for (int g = 0; g < goodsCount; g++) goodsIds.add(buf.readUtf(MAX_RESOURCE_ID_CHARS));
            stubs.add(new ShopCatalogManifest.Stub(
                    entryKey, top, sub, hidden, chunkId, linkKey, displayName, goodsIds));
        }
        return new ShopCatalogManifest(revision, ready, stubs);
    }

    public static void writePayloads(FriendlyByteBuf buf, List<ShopCatalogEntryPayload> payloads) {
        List<ShopCatalogEntryPayload> safe = payloads == null ? List.of() : payloads;
        requireEncodeSize("chunk entries", safe.size(), MAX_CHUNK_ENTRIES);
        int totalChars = 0;
        buf.writeVarInt(safe.size());
        for (ShopCatalogEntryPayload payload : safe) {
            if (payload.entryKey() < 0L) throw new EncoderException("negative shop entry key");
            if (payload.json().length() > MAX_ENTRY_JSON_CHARS) {
                throw new EncoderException("shop entry JSON exceeds limit");
            }
            totalChars += payload.json().length();
            if (totalChars > MAX_CHUNK_JSON_CHARS) {
                throw new EncoderException("shop chunk JSON exceeds limit");
            }
            buf.writeVarLong(payload.entryKey());
            buf.writeUtf(payload.json(), MAX_ENTRY_JSON_CHARS);
        }
    }

    public static List<ShopCatalogEntryPayload> readPayloads(FriendlyByteBuf buf) {
        int count = readBoundedCount(buf, "chunk entries", MAX_CHUNK_ENTRIES);
        List<ShopCatalogEntryPayload> payloads = new ArrayList<>(count);
        int totalChars = 0;
        for (int i = 0; i < count; i++) {
            long entryKey = buf.readVarLong();
            if (entryKey < 0L) throw new DecoderException("negative shop entry key");
            String json = buf.readUtf(MAX_ENTRY_JSON_CHARS);
            totalChars += json.length();
            if (totalChars > MAX_CHUNK_JSON_CHARS) {
                throw new DecoderException("shop chunk JSON exceeds limit");
            }
            payloads.add(new ShopCatalogEntryPayload(entryKey, json));
        }
        return List.copyOf(payloads);
    }

    private static int readBoundedCount(FriendlyByteBuf buf, String label, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new DecoderException(label + " count out of range: " + count);
        }
        return count;
    }

    private static void requireEncodeSize(String label, int count, int max) {
        if (count < 0 || count > max) {
            throw new EncoderException(label + " count out of range: " + count);
        }
    }
}
