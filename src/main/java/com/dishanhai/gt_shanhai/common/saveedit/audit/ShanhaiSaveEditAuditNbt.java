package com.dishanhai.gt_shanhai.common.saveedit.audit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * 「旁挂文件里的 NBT」<b>只读走一遍</b>（纯 JDK：自己的最小 NBT 读取器）。
 *
 * <h2>一、为什么不用现成的 {@code NbtIo}</h2>
 * 需要的东西比"读成一个 CompoundTag"多两点：
 * <ol>
 *   <li><b>每个值都要带位置</b>（{@code tagPath}）—— 判据回报里要写清"哪一份旁挂、在哪一条路径上"，
 *       否则用户拿到一条"命中了"却不知道去文件里哪一处看；</li>
 *   <li><b>任何畸形输入都不许抛</b> —— 旁挂文件是别人写的，可能被截断、可能根本不是 NBT。
 *       判据要求这类文件计入 {@code unreadable}，而不是让一次检测整个崩掉。</li>
 * </ol>
 * 于是这里自带一个最小读取器：未知 tag 类型 / 越界 / 递归过深 / 超长字符串 一律折成
 * {@link Walk#ok()} = {@code false} + 一句原因，<b>不抛</b>。
 *
 * <h2>二、认哪些"值"</h2>
 * <ul>
 *   <li>{@code String} tag ⇒ 当成文本扫 UUID（32/36 位、大小写都认）；</li>
 *   <li>{@code IntArray} 长度 4 ⇒ 按 NBT 里实体的存法拼成一个 UUID；长度 3 ⇒ 当成一组坐标；</li>
 *   <li>同一个 Compound 里 <b>连续的数值</b>（byte/short/int/long/float/double）三个一组 ⇒ 当成一组坐标；</li>
 *   <li>同一个 Compound 里相邻的 {@code UUIDMost}/{@code UUIDLeast}（旧写法）⇒ 拼成一个 UUID。</li>
 * </ul>
 * 🔴 这里刻意<b>不</b>认任何具体字段名（除了那两个旧 UUID 键这种 NBT 规范本身的东西）：
 * 判据立在"同一份数据"上，不立在"某个模组怎么命名"上。
 *
 * <h2>三、压缩外壳</h2>
 * 旁挂 {@code .dat} 常见三种外壳：裸 NBT、gzip、zlib（偶尔还有无头 deflate）。这里按
 * <b>内容魔数</b>依次试，谁先"完整读完"谁算数 —— 所以扩展名是什么都不影响判定。
 */
public final class ShanhaiSaveEditAuditNbt {

    /** 递归上限（旁挂文件里出现病态嵌套时不至于把栈打穿）。 */
    private static final int MAX_DEPTH = 64;
    /** 最多读多少个 tag。 */
    private static final int MAX_TAGS = 400_000;
    /** 单个字符串上限。 */
    private static final int MAX_STRING = 1 << 16;
    /** 单个数组/列表长度上限。 */
    private static final int MAX_LEN = 4_000_000;
    /** 解压后大小上限（防压缩炸弹）。 */
    private static final int MAX_INFLATED = 64 << 20;

    private static final int T_END = 0;
    private static final int T_BYTE = 1;
    private static final int T_SHORT = 2;
    private static final int T_INT = 3;
    private static final int T_LONG = 4;
    private static final int T_FLOAT = 5;
    private static final int T_DOUBLE = 6;
    private static final int T_BYTE_ARRAY = 7;
    private static final int T_STRING = 8;
    private static final int T_LIST = 9;
    private static final int T_COMPOUND = 10;
    private static final int T_INT_ARRAY = 11;
    private static final int T_LONG_ARRAY = 12;

    private ShanhaiSaveEditAuditNbt() {}

    /** 命中的一个 UUID（带它在 NBT 里的位置）。 */
    public record UuidRef(String uuid, String path) {
        public String line() {
            return "uuid=" + this.uuid + " at=" + this.path;
        }
    }

    /** 命中的一个坐标三元组（带位置）。 */
    public record TripleRef(double[] pos, String path) {
        public String line() {
            return "pos=" + ShanhaiSaveEditAuditUuid.posText(this.pos) + " at=" + this.path;
        }
    }

    /** 一次"走一遍"的读数。 */
    public record Walk(boolean ok, String error, String encoding, List<UuidRef> uuids,
                       List<TripleRef> triples, int tags, int strings, int numbers,
                       int consumed, int bytes) {
        public String line() {
            return "ok=" + this.ok
                    + " enc=" + this.encoding
                    + (this.ok ? "" : " error=" + this.error)
                    + " tags=" + this.tags + " strings=" + this.strings + " numbers=" + this.numbers
                    + " uuids=" + (this.uuids == null ? 0 : this.uuids.size())
                    + " triples=" + (this.triples == null ? 0 : this.triples.size())
                    + " consumed=" + this.consumed + "/" + this.bytes;
        }

        public static Walk fail(String error) {
            return new Walk(false, error == null ? "unreadable" : error, "-", List.of(), List.of(),
                    0, 0, 0, 0, 0);
        }
    }

    // ================================================================== 入口

    /**
     * 把一段文件字节当 NBT 走一遍（<b>只读</b>，任何畸形输入都折成 {@code ok=false}）。
     *
     * <p>顺序：裸 NBT → gzip → zlib → 无头 deflate；每种外壳再按"有名根 / 无名根"两种读法各试一次。
     * 只有<b>刚好读完整个输入</b>的那种才算成功（多出来的字节说明读法不对或文件坏了）。
     */
    public static Walk read(byte[] raw) {
        if (raw == null || raw.length < 2) {
            return Walk.fail("too_short(" + (raw == null ? 0 : raw.length) + ")");
        }
        String bestErr = null;
        int bestConsumed = -1;
        final List<String> encodings = new ArrayList<>();
        final List<byte[]> datas = new ArrayList<>();
        addCandidate(encodings, datas, "raw", raw);
        addCandidate(encodings, datas, "gzip", gunzip(raw));
        addCandidate(encodings, datas, "zlib", inflate(raw, false));
        addCandidate(encodings, datas, "deflate", inflate(raw, true));
        for (int c = 0; c < datas.size(); c++) {
            final byte[] data = datas.get(c);
            final String enc = encodings.get(c);
            for (boolean named : new boolean[] {true, false}) {
                final Walk w = attempt(data, named, enc);
                if (w.ok()) {
                    return w;
                }
                if (w.consumed() > bestConsumed) {
                    bestConsumed = w.consumed();
                    bestErr = (named ? "named/" : "unnamed/") + enc + ":" + w.error();
                }
            }
        }
        return Walk.fail(bestErr == null ? "unreadable" : bestErr);
    }

    private static void addCandidate(List<String> encodings, List<byte[]> datas, String enc, byte[] data) {
        if (data == null || data.length < 2) {
            return;
        }
        for (byte[] existing : datas) {
            if (existing == data) {
                return;
            }
        }
        encodings.add(enc);
        datas.add(data);
    }

    private static Walk attempt(byte[] data, boolean named, String encoding) {
        final Reader r = new Reader(data, encoding);
        try {
            final int type = r.u1();
            if (type != T_COMPOUND) {
                return new Walk(false, "root_not_compound(type=" + type + ")", encoding,
                        List.of(), List.of(), 0, 0, 0, r.pos, data.length);
            }
            if (named) {
                r.utf();            // 有名根的根名（通常为空串）
            }
            r.walkCompound("", 1);
            if (r.pos != data.length) {
                return new Walk(false, "trailing_bytes(" + (data.length - r.pos) + ")", encoding,
                        r.uuids, r.triples, r.tags, r.strings, r.numbers, r.pos, data.length);
            }
            return new Walk(true, "", encoding, r.uuids, r.triples, r.tags, r.strings, r.numbers,
                    r.pos, data.length);
        } catch (Throwable t) {
            return new Walk(false, shortErr(t), encoding, r.uuids, r.triples, r.tags, r.strings,
                    r.numbers, r.pos, data.length);
        }
    }

    private static String shortErr(Throwable t) {
        final String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ":" + m);
    }

    // ================================================================== 读取器

    /** 一个数值（同时保留 long 形态，供 UUIDMost/UUIDLeast 配对）。 */
    private record Num(String name, double value, Long asLong) {}

    private static final class Reader {
        private final byte[] d;
        private final String encoding;
        private int pos;
        private int tags;
        private int strings;
        private int numbers;
        private final List<UuidRef> uuids = new ArrayList<>();
        private final List<TripleRef> triples = new ArrayList<>();

        private Reader(byte[] d, String encoding) {
            this.d = d;
            this.encoding = encoding;
        }

        // ---------------------------------------------------------- 基础读

        private void need(long n) throws IOException {
            if (n < 0 || this.pos + n > this.d.length) {
                throw new IOException("truncated_at_" + this.pos + "_need_" + n + "_of_" + this.d.length);
            }
        }

        private int u1() throws IOException {
            need(1);
            return this.d[this.pos++] & 0xFF;
        }

        private int u2() throws IOException {
            need(2);
            final int v = ((this.d[this.pos] & 0xFF) << 8) | (this.d[this.pos + 1] & 0xFF);
            this.pos += 2;
            return v;
        }

        private int s4() throws IOException {
            need(4);
            final int v = ((this.d[this.pos] & 0xFF) << 24) | ((this.d[this.pos + 1] & 0xFF) << 16)
                    | ((this.d[this.pos + 2] & 0xFF) << 8) | (this.d[this.pos + 3] & 0xFF);
            this.pos += 4;
            return v;
        }

        private long s8() throws IOException {
            final long hi = s4() & 0xFFFFFFFFL;
            final long lo = s4() & 0xFFFFFFFFL;
            return (hi << 32) | lo;
        }

        private float f4() throws IOException {
            return Float.intBitsToFloat(s4());
        }

        private double f8() throws IOException {
            return Double.longBitsToDouble(s8());
        }

        private void skip(long n) throws IOException {
            need(n);
            this.pos += (int) n;
        }

        /** 长度字段（负数 / 超上限都算畸形）。 */
        private int len(int limit) throws IOException {
            final int n = s4();
            if (n < 0) {
                throw new IOException("negative_length(" + n + ")");
            }
            if (n > limit) {
                throw new IOException("length_too_big(" + n + ">" + limit + ")");
            }
            return n;
        }

        private String utf() throws IOException {
            final int n = u2();
            if (n > MAX_STRING) {
                throw new IOException("string_too_long(" + n + ")");
            }
            need(n);
            final String s = new String(this.d, this.pos, n, StandardCharsets.UTF_8);
            this.pos += n;
            return s;
        }

        private void countTag() throws IOException {
            if (++this.tags > MAX_TAGS) {
                throw new IOException("too_many_tags(>" + MAX_TAGS + ")");
            }
        }

        // ---------------------------------------------------------- 结构

        private void walkCompound(String path, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("too_deep(" + depth + ")");
            }
            final List<Num> nums = new ArrayList<>();
            while (true) {
                final int type = u1();
                if (type == T_END) {
                    break;
                }
                countTag();
                final String name = utf();
                final String child = path.isEmpty() ? name : path + "." + name;
                switch (type) {
                    case T_BYTE -> nums.add(new Num(name, (double) (byte) u1(), null));
                    case T_SHORT -> nums.add(new Num(name, (double) (short) u2(), null));
                    case T_INT -> nums.add(new Num(name, (double) s4(), null));
                    case T_LONG -> {
                        final long v = s8();
                        nums.add(new Num(name, (double) v, v));
                    }
                    case T_FLOAT -> nums.add(new Num(name, (double) f4(), null));
                    case T_DOUBLE -> nums.add(new Num(name, f8(), null));
                    case T_BYTE_ARRAY -> skip(len(MAX_LEN));
                    case T_STRING -> {
                        final String s = utf();
                        this.strings++;
                        addUuids(s, child);
                    }
                    case T_LIST -> walkList(child, depth + 1);
                    case T_COMPOUND -> walkCompound(child, depth + 1);
                    case T_INT_ARRAY -> intArray(child);
                    case T_LONG_ARRAY -> skip(8L * len(MAX_LEN));
                    default -> throw new IOException("unknown_tag_type(" + type + ") at " + child);
                }
            }
            flush(nums, path);
        }

        private void walkList(String path, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("list_too_deep(" + depth + ")");
            }
            final int elemType = u1();
            final int n = len(MAX_LEN);
            countTag();
            if (elemType == T_END) {
                if (n > 0) {
                    throw new IOException("empty_list_with_length(" + n + ")");
                }
                return;
            }
            final List<Num> nums = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String child = path + "[" + i + "]";
                switch (elemType) {
                    case T_BYTE -> nums.add(new Num("", (double) (byte) u1(), null));
                    case T_SHORT -> nums.add(new Num("", (double) (short) u2(), null));
                    case T_INT -> nums.add(new Num("", (double) s4(), null));
                    case T_LONG -> {
                        final long v = s8();
                        nums.add(new Num("", (double) v, v));
                    }
                    case T_FLOAT -> nums.add(new Num("", (double) f4(), null));
                    case T_DOUBLE -> nums.add(new Num("", f8(), null));
                    case T_BYTE_ARRAY -> skip(len(MAX_LEN));
                    case T_STRING -> {
                        final String s = utf();
                        this.strings++;
                        addUuids(s, child);
                    }
                    case T_LIST -> walkList(child, depth + 1);
                    case T_COMPOUND -> walkCompound(child, depth + 1);
                    case T_INT_ARRAY -> intArray(child);
                    case T_LONG_ARRAY -> skip(8L * len(MAX_LEN));
                    default -> throw new IOException("unknown_list_elem_type(" + elemType + ") at " + child);
                }
            }
            flush(nums, path);
        }

        private void intArray(String path) throws IOException {
            final int n = len(MAX_LEN);
            countTag();
            if (n == 4) {
                final int[] v = new int[4];
                for (int i = 0; i < 4; i++) {
                    v[i] = s4();
                }
                final String uuid = ShanhaiSaveEditAuditUuid.uuidOfInts(v);
                if (uuid != null) {
                    this.uuids.add(new UuidRef(uuid, path));
                }
                this.numbers += 4;
                return;
            }
            if (n == 3) {
                final double[] p = new double[] {s4(), s4(), s4()};
                this.triples.add(new TripleRef(p, path));
                this.numbers += 3;
                return;
            }
            skip(4L * n);
            this.numbers += n;
        }

        // ---------------------------------------------------------- 采集

        private void addUuids(String s, String path) {
            if (s == null || s.isEmpty()) {
                return;
            }
            for (String u : ShanhaiSaveEditAuditUuid.findUuids(s)) {
                this.uuids.add(new UuidRef(u, path));
            }
        }

        /** 一个容器读完：连续的数值三个一组当坐标；相邻的 Most/Least 当 UUID。 */
        private void flush(List<Num> nums, String path) {
            this.numbers += nums.size();
            for (int i = 0; i + 2 < nums.size(); i++) {
                final double[] p = new double[] {nums.get(i).value(), nums.get(i + 1).value(),
                        nums.get(i + 2).value()};
                if (!finite(p)) {
                    continue;
                }
                this.triples.add(new TripleRef(p, path + "#" + i + ".." + (i + 2)));
            }
            for (int i = 0; i + 1 < nums.size(); i++) {
                final Num a = nums.get(i);
                final Num b = nums.get(i + 1);
                if (a.asLong() == null || b.asLong() == null) {
                    continue;
                }
                final String an = a.name().toUpperCase(Locale.ROOT);
                final String bn = b.name().toUpperCase(Locale.ROOT);
                if (an.contains("UUIDMOST") && bn.contains("UUIDLEAST")) {
                    this.uuids.add(new UuidRef(
                            ShanhaiSaveEditAuditUuid.uuidOfLongs(a.asLong(), b.asLong()),
                            path + "." + a.name()));
                } else if (an.contains("UUIDLEAST") && bn.contains("UUIDMOST")) {
                    this.uuids.add(new UuidRef(
                            ShanhaiSaveEditAuditUuid.uuidOfLongs(b.asLong(), a.asLong()),
                            path + "." + b.name()));
                }
            }
        }

        private static boolean finite(double[] p) {
            for (double v : p) {
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    return false;
                }
            }
            return true;
        }
    }

    // ================================================================== 解压

    /** gzip 外壳（魔数 {@code 1F 8B}）；不是就返回 {@code null}。 */
    private static byte[] gunzip(byte[] raw) {
        if (raw.length < 3 || (raw[0] & 0xFF) != 0x1F || (raw[1] & 0xFF) != 0x8B) {
            return null;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return drain(in);
        } catch (Throwable t) {
            return null;
        }
    }

    /** zlib / 无头 deflate 外壳；解不开就返回 {@code null}。 */
    private static byte[] inflate(byte[] raw, boolean headerless) {
        if (raw.length < 3) {
            return null;
        }
        try (InflaterInputStream in = headerless
                ? new InflaterInputStream(new ByteArrayInputStream(raw), new Inflater(true))
                : new InflaterInputStream(new ByteArrayInputStream(raw))) {
            return drain(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] drain(java.io.InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_INFLATED) {
                throw new IOException("inflated_too_big(>" + MAX_INFLATED + ")");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditNbt[]";
    }
}
