package com.dishanhai.gt_shanhai.common.saveedit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 最小 {@code .mca}（Anvil region）读写器 —— <b>纯 JDK</b>（{@code java.nio} + {@code java.util.zip}），
 * 不引用任何 Minecraft 类型。
 *
 * <h2>一、格式（本类实现的就是这几行）</h2>
 * <pre>
 *   0x0000  4096 B  偏移表：1024 个 4 字节大端 = (起始扇区 &lt;&lt; 8) | 扇区数
 *   0x1000  4096 B  时间戳表：1024 个 4 字节大端（秒）
 *   0x2000  …       区块：每块 = 4 B 长度(BE) + 1 B 压缩类型 + 数据
 *                   压缩类型：1=gzip / 2=zlib / 3=raw(无头 deflate)
 *                   长度 = 1 + 数据长度（含那 1 字节类型）
 *   扇区 = 4096 B；槽位 = (chunkX &amp; 31) + (chunkZ &amp; 31) * 32
 *   偏移 = 0 ⇒ 这个区块<b>还没生成</b>
 * </pre>
 *
 * <h2>二、🔴 只重写目标区块，其余区块的字节原样搬</h2>
 * {@link #withChunk(Parse, int, byte[], long, List)} 的做法是 <b>clone 原文件</b>，然后只覆盖
 * 目标槽位那一段：
 * <ul>
 *   <li>新数据装得进原分配扇区 ⇒ 原地写，扇区数不变（多出来的字节清零）；</li>
 *   <li>装不下 ⇒ 在文件末尾追加新扇区，只改目标槽位那 4 字节偏移表项。</li>
 * </ul>
 * ⇒ 其它 1023 个槽位的表项与<b>压缩后的字节</b>一个都没动过。判据 ④ 的负对照正是把这一步换成
 * "全部解压再压"（{@code decompressAllThenRecompress}），那样"其它区块哈希不变"立刻变红。
 *
 * <h2>三、时间戳表</h2>
 * 只有目标槽位被更新（{@code epochSeconds}），其余原样。
 */
public final class ShanhaiRegionFile {

    /** 一个扇区。 */
    public static final int SECTOR_BYTES = 4096;
    /** 两个表：偏移表 + 时间戳表。 */
    public static final int HEADER_BYTES = 8192;
    /** 每个 region 32×32 个区块。 */
    public static final int REGION_CHUNKS = 32;
    /** 槽位数。 */
    public static final int SLOT_COUNT = REGION_CHUNKS * REGION_CHUNKS;
    /** 单区块最多 255 个扇区（偏移表里的扇区数是 1 字节）。 */
    public static final int MAX_SECTORS_PER_CHUNK = 255;

    /** 压缩类型。 */
    public static final int COMPRESSION_GZIP = 1;
    public static final int COMPRESSION_ZLIB = 2;
    public static final int COMPRESSION_RAW = 3;

    private ShanhaiRegionFile() {}

    // ================================================================== 坐标 / 槽位

    /** {@code (chunkX, chunkZ)} → 槽位。 */
    public static int slotOf(int chunkX, int chunkZ) {
        return (chunkX & (REGION_CHUNKS - 1)) + (chunkZ & (REGION_CHUNKS - 1)) * REGION_CHUNKS;
    }

    /** 槽位 → 区块 x（region 内 0..31）。 */
    public static int slotChunkX(int slot) {
        return slot & (REGION_CHUNKS - 1);
    }

    /** 槽位 → 区块 z（region 内 0..31）。 */
    public static int slotChunkZ(int slot) {
        return (slot / REGION_CHUNKS) & (REGION_CHUNKS - 1);
    }

    /** 区块坐标 → region 文件坐标（floorDiv：负坐标也对）。 */
    public static int regionCoord(int chunkCoord) {
        return Math.floorDiv(chunkCoord, REGION_CHUNKS);
    }

    /** region 坐标 → 文件名 {@code r.<x>.<z>.mca}。 */
    public static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /** 一个区块在文件里的读数（{@code payload} = 压缩类型字节 + 压缩后的数据）。 */
    public record Chunk(int slot, int offsetSector, int sectorCount, long timestamp,
                        int compression, byte[] payload) {
        /** 压缩数据的长度（= 4 字节长度字段的值 − 1）。 */
        public int dataLength() {
            return this.payload.length - 1;
        }

        /** 占用的字节数（含 4 字节长度头）。 */
        public int allocatedBytes() {
            return this.sectorCount * SECTOR_BYTES;
        }
    }

    /** 一次解析的结果（坏掉的槽位折成 {@link #problems}，不抛）。 */
    public record Parse(byte[] file, int[] offsets, int[] counts, long[] timestamps,
                        Map<Integer, Chunk> chunks, List<String> problems) {

        /** 有数据的区块数。 */
        public int chunkCount() {
            return this.chunks.size();
        }

        /** 空槽位数（= 未生成）。 */
        public int emptyCount() {
            return SLOT_COUNT - this.chunks.size();
        }

        /** 有没有结构性问题（解析阶段发现的）。 */
        public boolean clean() {
            return this.problems.isEmpty();
        }

        /** 目标槽位的区块（未生成 ⇒ {@code null}）。 */
        public Chunk chunk(int slot) {
            return this.chunks.get(slot);
        }

        /** 目标槽位的压缩数据 sha256（"其它区块原样"的判据就是逐个比它）。 */
        public String payloadSha(int slot) {
            final Chunk c = this.chunks.get(slot);
            return c == null ? null : sha256(c.payload());
        }

        /** 一行读数。 */
        public String line() {
            return "file_bytes=" + this.file.length
                    + " chunks=" + chunkCount() + " empty=" + emptyCount()
                    + " problems=" + this.problems.size()
                    + (this.problems.isEmpty() ? "" : " first=" + this.problems.get(0));
        }
    }

    // ================================================================== 解析

    /** 从盘上读一个 region 文件（不存在 ⇒ 抛 {@link IOException}）。 */
    public static byte[] readAll(java.nio.file.Path path) throws IOException {
        if (path == null || !java.nio.file.Files.isRegularFile(path)) {
            throw new IOException("region_file_missing: " + path);
        }
        return java.nio.file.Files.readAllBytes(path);
    }

    /**
     * 解析整个 region 文件（<b>只读</b>；坏槽位折成 problems，不抛）。
     *
     * <p>🔴 <b>0 字节 = 干净的空区文件</b>：游戏自己会留这种文件（本机 7 个存档的
     * {@code entities/} 里 12/16 个 {@code .mca} 都是 0 字节），它表示"这个区一个区块都没写过"，
     * <b>不是</b>结构问题 ⇒ 返回 {@code clean()} 且 {@code chunkCount()==0}。
     *
     * <p>为什么这条会要人命：{@link ShanhaiEntityEdit#locate} 按文件名<b>升序</b>扫，撞到第一个
     * 有 problems 的文件就<b>整次拒绝</b>（"不敢下结论"）。而升序第一个恰好是 0 字节的
     * {@code r.-2.-1.mca}，真正含实体的 {@code r.-1.0.mca} 排在它后面 ⇒ 详情页永远读不出来、
     * 删除永远报"还没选中"。
     *
     * <p>真截断（字节数大于 0 但小于 8192）仍然算 problems —— 那是半个头部，不是空区。
     */
    public static Parse parse(byte[] file) {
        final List<String> problems = new ArrayList<>();
        final int[] offsets = new int[SLOT_COUNT];
        final int[] counts = new int[SLOT_COUNT];
        final long[] timestamps = new long[SLOT_COUNT];
        final Map<Integer, Chunk> chunks = new LinkedHashMap<>();
        if (file == null) {
            problems.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cc829be70f5c1789"));
            return new Parse(new byte[0], offsets, counts, timestamps, chunks, problems);
        }
        if (file.length == 0) {
            // 0 字节 ⇒ 空且 clean（0 槽位、无 problems）；下面 for 循环对 0 字节没有意义，直接返回
            return new Parse(file, offsets, counts, timestamps, chunks, problems);
        }
        if (file.length < HEADER_BYTES) {
            problems.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.00a38d2decb7eec6") + file.length + "）");
            return new Parse(file, offsets, counts, timestamps, chunks, problems);
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            final int raw = readInt(file, slot * 4);
            final int off = (raw >>> 8) & 0xFFFFFF;
            final int cnt = raw & 0xFF;
            offsets[slot] = off;
            counts[slot] = cnt;
            timestamps[slot] = readInt(file, SECTOR_BYTES + slot * 4) & 0xFFFFFFFFL;
            if (off == 0) {
                if (cnt != 0) {
                    problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b3eb3b85e819f436") + cnt);
                }
                continue;
            }
            if (cnt == 0) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.31f555012a45826b") + off + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0331ea2dfa0cddf1"));
                continue;
            }
            final long start = (long) off * SECTOR_BYTES;
            if (start + 4 > file.length) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.52acf1371901b9dc") + start + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f86232d3d4918cb9") + file.length);
                continue;
            }
            final int length = readInt(file, (int) start);
            if (length <= 0) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4e4168efa3ee00e4") + length + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.64b52f0ab3d2bd26"));
                continue;
            }
            if (start + 4 + length > file.length) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bfbc04e721d8f6fe") + length + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d7b811667a93f164") + start + "）");
                continue;
            }
            if (4L + length > (long) cnt * SECTOR_BYTES) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bfbc04e721d8f6fe") + length + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.03d0cdc487d10f6f") + cnt + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1b885d579f05dfac"));
                continue;
            }
            final byte[] payload = new byte[length];
            System.arraycopy(file, (int) start + 4, payload, 0, length);
            final int compression = payload[0] & 0xFF;
            if (compression != COMPRESSION_GZIP && compression != COMPRESSION_ZLIB
                    && compression != COMPRESSION_RAW) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a7530d69081fc86d") + compression + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dc56084f0a9d529e"));
                continue;
            }
            chunks.put(slot, new Chunk(slot, off, cnt, timestamps[slot], compression, payload));
        }
        return new Parse(file, offsets, counts, timestamps, chunks, problems);
    }

    /** 解压一个区块的 NBT 字节（压缩类型 1/2/3 <b>都</b>支持）。 */
    public static byte[] inflate(Chunk chunk) throws IOException {
        if (chunk == null) {
            throw new IOException("chunk_missing");
        }
        final byte[] data = new byte[chunk.payload().length - 1];
        System.arraycopy(chunk.payload(), 1, data, 0, data.length);
        switch (chunk.compression()) {
            case COMPRESSION_GZIP:
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    return in.readAllBytes();
                }
            case COMPRESSION_ZLIB:
                try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
                    return in.readAllBytes();
                }
            case COMPRESSION_RAW:
                try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data),
                        new Inflater(true))) {
                    return in.readAllBytes();
                }
            default:
                throw new IOException("unknown_compression:" + chunk.compression());
        }
    }

    /**
     * 压出一个区域区块的 payload（压缩类型字节 2 + zlib）。
     *
     * <p>用原版同一套：{@code Deflater(DEFAULT_COMPRESSION)}，与
     * {@code RegionFile#write(ChunkPos, CompoundTag)} 一致。
     */
    public static byte[] deflate(byte[] nbt) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(Math.max(64, nbt.length / 2));
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream out = new DeflaterOutputStream(raw, deflater)) {
            out.write(nbt);
        } finally {
            deflater.end();
        }
        final byte[] compressed = raw.toByteArray();
        final byte[] payload = new byte[compressed.length + 1];
        payload[0] = (byte) COMPRESSION_ZLIB;
        System.arraycopy(compressed, 0, payload, 1, compressed.length);
        return payload;
    }

    // ================================================================== 写（只动一个槽位）

    /**
     * 用一个新 payload 覆盖目标槽位，返回<b>新文件字节</b>（纯内存；落盘交给 Storage）。
     *
     * @param epochSeconds 目标槽位的新时间戳（原版是 {@code Util.getMillis() / 1000}）
     * @param notes        会被塞进"做了什么"的说明（装不下时追加扇区那条）
     * @throws IOException 装不下（&gt;255 扇区）或参数非法
     */
    public static byte[] withChunk(Parse parse, int slot, byte[] payload, long epochSeconds,
                                   List<String> notes) throws IOException {
        if (parse == null || parse.file() == null) {
            throw new IOException("no_parse");
        }
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IOException("bad_slot:" + slot);
        }
        if (payload == null || payload.length == 0) {
            throw new IOException("empty_payload");
        }
        final int total = 4 + payload.length;
        final int need = (total + SECTOR_BYTES - 1) / SECTOR_BYTES;
        if (need > MAX_SECTORS_PER_CHUNK) {
            throw new IOException("chunk_too_big:" + total + "B needs " + need + " sectors");
        }
        byte[] out = parse.file().clone();
        if (out.length < HEADER_BYTES) {
            // 🔴 0 字节的空区文件（{@link #parse} 把它判成"干净的空区"）：clone 出来只有 0 字节，
            // 下面的 off = (0+4095)/4096 = 0 会把区块写进偏移表里、Arrays.fill 还会越界。
            // 所以先把头部补齐到 8192 ⇒ 首个可用扇区是 2，与真正的存档文件同一套布局。
            out = java.util.Arrays.copyOf(out, HEADER_BYTES);
        }
        final Chunk old = parse.chunk(slot);
        final int off;
        final int cnt;
        if (old != null && old.sectorCount() >= need) {
            off = old.offsetSector();
            cnt = old.sectorCount();            // 原地写、扇区数不变（多出来的字节清零）
        } else {
            off = (out.length + SECTOR_BYTES - 1) / SECTOR_BYTES;
            cnt = need;
            final long needBytes = (long) (off + cnt) * SECTOR_BYTES;
            if (needBytes > out.length) {
                out = java.util.Arrays.copyOf(out, (int) needBytes);
            }
            if (notes != null) {
                notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.46ad4d880160a8fd") + need + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.646dbe4915dcb7c3")
                        + (old == null ? 0 : old.sectorCount()) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.26b0e001161022dd")
                        + off + ".." + (off + cnt - 1) + "）");
            }
        }
        // 把这一段先清零，再写"长度 + 类型 + 数据"
        java.util.Arrays.fill(out, off * SECTOR_BYTES, (off + cnt) * SECTOR_BYTES, (byte) 0);
        writeInt(out, off * SECTOR_BYTES, payload.length + 1);
        System.arraycopy(payload, 0, out, off * SECTOR_BYTES + 4, payload.length);
        // 偏移表 & 时间戳表：只动目标槽位
        writeInt(out, slot * 4, (off << 8) | (cnt & 0xFF));
        writeInt(out, SECTOR_BYTES + slot * 4, (int) epochSeconds);
        return out;
    }

    /**
     * 🔴 <b>负对照专用</b>：把所有区块解压再压（表项全部重排）。
     *
     * <p>它<b>不是</b>生产路径（生产用 {@link #withChunk} 原样搬）。判据 ④ 用它证明
     * "其它区块压缩字节不变"这条检查真能看见区别。
     */
    public static byte[] decompressAllThenRecompress(Parse parse, int targetSlot, byte[] targetPayload,
                                                     long epochSeconds) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(parse.file().length + SECTOR_BYTES);
        final byte[] header = new byte[HEADER_BYTES];
        System.arraycopy(parse.file(), 0, header, 0, HEADER_BYTES);
        out.write(header, 0, HEADER_BYTES);
        int sector = HEADER_BYTES / SECTOR_BYTES;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            final Chunk c = parse.chunk(slot);
            if (c == null && slot != targetSlot) {
                continue;
            }
            byte[] payload;
            if (slot == targetSlot) {
                payload = targetPayload;
            } else {
                // 解压 → 用 BEST_SPEED 重压（与原始 DEFAULT_COMPRESSION 的输出必然不同）⇒ 字节变了
                final byte[] nbt = inflate(c);
                final ByteArrayOutputStream raw = new ByteArrayOutputStream(nbt.length / 2 + 64);
                final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                try (DeflaterOutputStream dos = new DeflaterOutputStream(raw, deflater)) {
                    dos.write(nbt);
                } finally {
                    deflater.end();
                }
                final byte[] compressed = raw.toByteArray();
                payload = new byte[compressed.length + 1];
                payload[0] = (byte) COMPRESSION_ZLIB;
                System.arraycopy(compressed, 0, payload, 1, compressed.length);
            }
            final int need = (4 + payload.length + SECTOR_BYTES - 1) / SECTOR_BYTES;
            final byte[] lenBuf = new byte[4];
            lenBuf[0] = (byte) ((payload.length + 1) >>> 24);
            lenBuf[1] = (byte) ((payload.length + 1) >>> 16);
            lenBuf[2] = (byte) ((payload.length + 1) >>> 8);
            lenBuf[3] = (byte) (payload.length + 1);
            out.write(lenBuf, 0, 4);
            out.write(payload, 0, payload.length);
            final int used = 4 + payload.length;
            final int pad = need * SECTOR_BYTES - used;
            if (pad > 0) {
                out.write(new byte[pad], 0, pad);
            }
            final int rawEntry = (sector << 8) | (need & 0xFF);
            writeInt(header, slot * 4, rawEntry);
            if (slot == targetSlot) {
                writeInt(header, SECTOR_BYTES + slot * 4, (int) epochSeconds);
            }
            sector += need;
        }
        // 头部整块盖回去：没动过的表项就是原文件里的值（header 一开始就是原头部的副本）
        final byte[] result = out.toByteArray();
        System.arraycopy(header, 0, result, 0, HEADER_BYTES);
        return result;
    }

    // ================================================================== 表校验（判据 ②）

    /** 表校验读数。 */
    public record Table(boolean ok, int chunks, int empty, long maxEnd, List<String> problems) {
        public String line() {
            return "ok=" + this.ok + " chunks=" + this.chunks + " empty=" + this.empty
                    + " max_end=" + this.maxEnd
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems.size()
                    + " first=" + this.problems.get(0));
        }
    }

    /**
     * <b>判据 ②</b>：偏移表 / 时间戳表 / 每个区块长度必须自洽。
     *
     * <p>逐条：
     * <ol>
     *   <li>文件 ≥ 8 KiB；</li>
     *   <li>每个非零表项：扇区数 1..255、起始 ≥ 8192、<b>起始 &lt; 文件大小</b>；</li>
     *   <li>长度字段 &gt; 0、{@code 4+长度 ≤ 分配扇区数×4096}、{@code 起始+4+长度 ≤ 文件大小}；</li>
     *   <li>压缩类型 ∈ {1,2,3}；</li>
     *   <li>任意两个区块的字节区间<b>不重叠</b>（重叠会让第二个被杀掉）；</li>
     *   <li>有数据的槽位时间戳 &gt; 0。</li>
     * </ol>
     */
    public static Table validate(byte[] file) {
        final List<String> problems = new ArrayList<>();
        int chunks = 0;
        int empty = 0;
        long maxEnd = 0L;
        if (file == null || file.length < HEADER_BYTES) {
            return new Table(false, 0, SLOT_COUNT, 0L, List.of(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.314db75104c10bb7")));
        }
        final List<long[]> ranges = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            final int raw = readInt(file, slot * 4);
            final int off = (raw >>> 8) & 0xFFFFFF;
            final int cnt = raw & 0xFF;
            final long ts = readInt(file, SECTOR_BYTES + slot * 4) & 0xFFFFFFFFL;
            if (off == 0) {
                empty++;
                if (cnt != 0) {
                    problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b3eb3b85e819f436") + cnt);
                }
                continue;
            }
            chunks++;
            if (cnt < 1 || cnt > MAX_SECTORS_PER_CHUNK) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.fa65430381db5786") + cnt + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.87c98448b8433abd"));
                continue;
            }
            final long start = (long) off * SECTOR_BYTES;
            if (start < HEADER_BYTES) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.52acf1371901b9dc") + start + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7caf9da77dc0324d"));
                continue;
            }
            if (start >= file.length) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.52acf1371901b9dc") + start + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.76758cffd0f346fb") + file.length);
                continue;
            }
            final int length = readInt(file, (int) start);
            if (length <= 0) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bfbc04e721d8f6fe") + length);
                continue;
            }
            if (4L + length > (long) cnt * SECTOR_BYTES) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bfbc04e721d8f6fe") + length + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4a3add3860552676") + cnt + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.626b36a14cef23ab"));
                continue;
            }
            if (start + 4L + length > file.length) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.53caf3059ae12286") + (start + 4L + length) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.76758cffd0f346fb") + file.length);
                continue;
            }
            final int compression = file[(int) start + 4] & 0xFF;
            if (compression != COMPRESSION_GZIP && compression != COMPRESSION_ZLIB
                    && compression != COMPRESSION_RAW) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a7530d69081fc86d") + compression);
            }
            if (ts <= 0) {
                problems.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.851f843e3ee251ea"));
            }
            ranges.add(new long[] {start, start + 4L + length, slot});
            maxEnd = Math.max(maxEnd, start + 4L + length);
        }
        ranges.sort((a, b) -> Long.compare(a[0], b[0]));
        for (int i = 1; i < ranges.size(); i++) {
            if (ranges.get(i)[0] < ranges.get(i - 1)[1]) {
                problems.add("slot=" + (int) ranges.get(i - 1)[2] + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ffeb176e9d1cacf0") + (int) ranges.get(i)[2]
                        + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.30b56d7f4b31443f"));
            }
        }
        return new Table(problems.isEmpty(), chunks, empty, maxEnd, problems);
    }

    // ================================================================== 小工具

    /** 大端读 4 字节（越界返回 0；解析阶段不想抛）。 */
    public static int readInt(byte[] b, int at) {
        if (b == null || at < 0 || at + 4 > b.length) {
            return 0;
        }
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** 大端写 4 字节。 */
    public static void writeInt(byte[] b, int at, int value) {
        b[at] = (byte) (value >>> 24);
        b[at + 1] = (byte) (value >>> 16);
        b[at + 2] = (byte) (value >>> 8);
        b[at + 3] = (byte) value;
    }

    /** 字节数组的 sha256（十六进制小写；与 Storage 同一个口径，但这里独立实现，免得判据互相借用）。 */
    public static String sha256(byte[] content) {
        if (content == null) {
            return null;
        }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** UTF-8 字节（只给异常信息用，避免平台默认编码差异）。 */
    static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
