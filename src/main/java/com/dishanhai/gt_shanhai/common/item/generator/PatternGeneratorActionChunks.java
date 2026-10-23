package com.dishanhai.gt_shanhai.common.item.generator;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.UUID;

/** 山海生成器預設動作分片：每個會話只保留一個有界、按序的傳輸。 */
public final class PatternGeneratorActionChunks {
    // 伺服端 custom payload 的 32767-byte 上限包含 SimpleChannel 與本封包欄位。
    public static final int MAX_PAYLOAD = 32 * 1024 - 128;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int TIMEOUT_TICKS = 20 * 10;
    public static final UUID SINGLE_TRANSFER = new UUID(0, 0);

    private UUID transferId;
    private int action, nextIndex, count, totalBytes, requestId;
    private long lastChunkTick;
    private ByteArrayOutputStream bytes;

    public static void validate(int action, UUID transferId, int index, int count, int totalBytes, int payloadLength) {
        Objects.requireNonNull(transferId, "Missing pattern generator transfer id");
        if (action < 101 || action > 119 || totalBytes < 0 || totalBytes > MAX_TOTAL_BYTES
                || payloadLength < 0 || payloadLength > MAX_PAYLOAD || count < 1)
            throw new IllegalArgumentException("Invalid pattern generator action chunk");
        int expectedCount = Math.max(1, (totalBytes + MAX_PAYLOAD - 1) / MAX_PAYLOAD);
        if (count != expectedCount || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid pattern generator chunk count/index");
        int expectedLength = index == count - 1 ? totalBytes - index * MAX_PAYLOAD : MAX_PAYLOAD;
        if (payloadLength != expectedLength)
            throw new IllegalArgumentException("Invalid pattern generator chunk length");
        if (count == 1) {
            if (!SINGLE_TRANSFER.equals(transferId))
                throw new IllegalArgumentException("Single action has a transfer id");
        } else if ((action != 114 && action != 115) || SINGLE_TRANSFER.equals(transferId)) {
            throw new IllegalArgumentException("Only preset import/inspection may use chunks");
        }
    }

    /** 未齊回傳 null；完成回傳副本；錯序、重複或不一致一律丟棄暫存。 */
    public byte[] accept(int action, UUID transferId, int index, int count, int totalBytes,
                         byte[] payload, long tick) {
        return accept(action, transferId, index, count, totalBytes, payload, tick, 0);
    }

    public byte[] accept(int action, UUID transferId, int index, int count, int totalBytes,
                         byte[] payload, long tick, int requestId) {
        try {
            if (requestId < 0) throw new IllegalArgumentException("Invalid pattern generator request id");
            validate(action, transferId, index, count, totalBytes, payload.length);
            expire(tick);
            if (count == 1) {
                clear();
                return payload.clone();
            }
            if (index == 0) {
                if (transferId.equals(this.transferId))
                    throw new IllegalArgumentException("Duplicate initial pattern generator chunk");
                clear();
                this.transferId = transferId;
                this.action = action;
                this.count = count;
                this.totalBytes = totalBytes;
                this.requestId = requestId;
                bytes = new ByteArrayOutputStream(totalBytes);
            }
            if (bytes == null || !transferId.equals(this.transferId) || action != this.action
                    || count != this.count || totalBytes != this.totalBytes || index != nextIndex
                    || requestId != this.requestId)
                throw new IllegalArgumentException("Out-of-order or inconsistent pattern generator chunk");
            bytes.write(payload, 0, payload.length);
            nextIndex++;
            lastChunkTick = tick;
            if (nextIndex != count) return null;
            byte[] complete = bytes.toByteArray();
            clear();
            return complete;
        } catch (RuntimeException invalid) {
            clear();
            throw invalid;
        }
    }

    public boolean pending() { return bytes != null; }

    public void expire(long tick) {
        if (pending() && tick - lastChunkTick >= TIMEOUT_TICKS) clear();
    }

    public void clear() {
        bytes = null;
        transferId = null;
        action = nextIndex = count = totalBytes = requestId = 0;
        lastChunkTick = 0;
    }
}

