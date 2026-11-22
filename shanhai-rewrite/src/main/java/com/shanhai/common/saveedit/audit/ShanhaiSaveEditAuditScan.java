package com.shanhai.common.saveedit.audit;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「存档体检」的<b>只读检测核</b>：<b>白名单之外的一切 = 异常数据</b>。
 *
 * <h2>一、判据（用户 2026-11-15 拍板的那句）</h2>
 * 「若不是白名单中的数据，全部列为异常数据，并且允许玩家删除」⇒ 判据只有<b>一条</b>：
 * <b>这条相对路径在不在原版世界布局里</b>。在里面 ⇒ 不列；不在 ⇒ 列一条，可删。
 * <p>曾经那套「双份登记（同 UUID / 同坐标在两个地方各登记一份）」<b>已经整段删掉</b>：
 * 它要读区块 NBT、要认 UUID 写法、要算坐标容差，而结论仍然只是"旁挂文件可疑"。
 * 现在的判据不读任何内容，只看<b>世界数据布局</b>。
 *
 * <h2>二、🔴 判据里没有"某个模组"的影子</h2>
 * 本类<b>不认</b>任何模组名 / 实体 id / 文件名 / 字段名（用户红线「不要写针对某个 mod 的代码」）。
 * 白名单是<b>原版自己的目录名</b>（{@link #ROOT_FILES} / {@link #VANILLA_DIRS}），
 * 所以 {@code examplemod/sidecar.bin}、{@code somemod/data.bin}、{@code weird_dir/}
 * 一律同等对待 —— 换名字、换扩展名、没有扩展名都不影响结论。
 *
 * <h2>三、白名单（命中就不列）</h2>
 * <ul>
 *   <li>根下文件：{@code level.dat}、{@code level.dat_old}、{@code session.lock}、{@code icon.png}；</li>
 *   <li>根下目录（<b>含其全部子树</b>）：{@code region}、{@code entities}、{@code poi}、{@code data}、
 *       {@code datapacks}、{@code playerdata}、{@code advancements}、{@code stats}、
 *       {@code server-resources}；</li>
 *   <li>维度目录：{@code DIM-1}、{@code DIM1}、{@code dimensions/<命名空间>/<维度>}
 *       —— 这三个是<b>维度根</b>，进到里面<b>再套一次同一张目录白名单</b>
 *       （于是 {@code DIM-1/region/…}、{@code dimensions/kubejs/void/entities/…} 都不列，
 *       而 {@code DIM-1/foo.bin} 照样列）。</li>
 * </ul>
 *
 * <h2>四、目录怎么列（用户口径：目录只列它自己一条）</h2>
 * 白名单外的目录<b>只列一条</b>（{@code kind=dir}），子项不逐个重复列 —— 它自己就是异常根。
 * 展开/删除时按<b>整棵子树</b>算（{@link #Item#bytes()} 是子树字节和，{@link ShanhaiSaveEditAuditFix}
 * 递归删）。
 *
 * <h2>五、不崩的三条</h2>
 * 读不了的文件（权限/占用）记一条 {@code text=false} 的条目而不是抛；符号链接/特殊文件记一条
 * {@code deletable=false}；条目数超过 {@link Options#maxItems()} 就停，并在报表里标
 * {@code truncated=true}（面板/命令都显示「已截断」）。
 */
public final class ShanhaiSaveEditAuditScan {

    /** 条目数上限（沿用 {@link Options}；超了标「已截断」）。 */
    public static final int DEFAULT_MAX_ITEMS = 200_000;

    /** 文本判定的读取上限（超过就不读，按二进制记 —— 不猜）。 */
    public static final long TEXT_MAX_BYTES = 8L << 20;

    /** 预览取首行多少个字。 */
    public static final int PREVIEW_MAX_CHARS = 80;

    /** 世界根上的原版布局文件名（只排除）。 */
    public static final Set<String> ROOT_FILES = Set.of("level.dat", "level.dat_old", "session.lock", "icon.png");

    /** 世界根 / 维度根上的原版布局目录名（<b>含其全部子树</b>，只排除）。 */
    public static final Set<String> VANILLA_DIRS = Set.of("region", "entities", "poi", "data", "datapacks",
            "playerdata", "advancements", "stats");

    /** 原版两个维度目录名（各自是一个<b>维度根</b>：里面再套一次目录白名单）。 */
    public static final Set<String> DIM_DIRS = Set.of("DIM-1", "DIM1");

    /** 模组维度目录名（里面是 {@code <命名空间>/<维度>}，每个维度目录是一个维度根）。 */
    public static final String DIMENSIONS_DIR = "dimensions";

    /** 明确不碰的目录（可能很大，且与本判据无关）；在根与维度根上都算白名单。 */
    public static final String SERVER_RESOURCES = "server-resources";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ShanhaiSaveEditAuditScan() {}

    // ================================================================== 数据模型

    /**
     * 一条异常数据（白名单之外的一个文件或目录）。
     *
     * @param rel      相对存档根、{@code /} 分隔（面板/命令/删除请求都以它为准）
     * @param directory 目录（true）还是文件（false）
     * @param bytes    文件 = 自己的字节数；目录 = <b>整棵子树</b>的字节和
     * @param modified 最后修改时间（epoch millis；读不到 = 0）
     * @param text     能不能按 UTF-8 严格解码（目录恒 false）
     * @param preview  文本文件的首行（≤{@link #PREVIEW_MAX_CHARS} 字）；二进制/目录留空
     * @param deletable 能不能删（普通文件/真目录 = true；符号链接/特殊文件 = false）
     */
    public record Item(String rel, boolean directory, long bytes, long modified, boolean text, String preview,
                       boolean deletable) {

        /** 「文件」/「目录」。 */
        public String kindText() {
            return this.directory ? "目录" : "文件";
        }

        /** 「文本」/「二进制」/「目录」。 */
        public String dataText() {
            return this.directory ? "目录" : (this.text ? "文本" : "二进制");
        }

        public String sizeText() {
            return bytesText(this.bytes);
        }

        public String timeText() {
            return this.modified <= 0L ? "-"
                    : WHEN.format(Instant.ofEpochMilli(this.modified).atZone(ZoneId.systemDefault()));
        }

        public String previewText() {
            return this.preview == null ? "" : this.preview;
        }

        /** 面板/命令的一行：{@code 序号. 文件 相对路径 · 大小 · 文本|二进制}。 */
        public String rowText(int index) {
            return (index + 1) + ". " + kindText() + " " + this.rel + " · " + sizeText() + " · " + dataText();
        }

        /** 选中那一行的全部字段（用户口径：相对路径 / 文件或目录 / 字节 / 修改时间 / 文本或二进制 / 预览 / 可删）。 */
        public String detailText(int index, int total) {
            return "第 " + (index + 1) + "/" + total + " 条 · " + kindText() + " · " + this.rel
                    + " · " + sizeText() + " · " + timeText() + " · " + dataText()
                    + " · 预览=" + (previewText().isEmpty() ? "-" : previewText())
                    + " · 可删=" + (this.deletable ? "是" : "否");
        }

        /** 一行机器可判的读数（日志/探针）。 */
        public String logLine() {
            return "audit_item rel=" + this.rel + " kind=" + (this.directory ? "dir" : "file")
                    + " bytes=" + this.bytes + " mtime=" + this.modified + " when=" + timeText()
                    + " text=" + this.text + " preview=\"" + previewText() + "\" deletable=" + this.deletable;
        }

        /** 玩家聊天栏的一行。 */
        public String chatLine() {
            return "§e" + kindText() + " §f" + this.rel + " §7· " + sizeText() + " · " + dataText()
                    + (this.deletable ? "" : " §c(不可删)");
        }
    }

    /** 一次扫描的计数（每一位都是"扫出来看见了什么"）。 */
    public record Stats(int files, int dirs, int text, int binary, int whitelisted, long bytes, boolean truncated,
                        long millis) {

        public String line() {
            return "files=" + this.files + " dirs=" + this.dirs + " text=" + this.text + " binary=" + this.binary
                    + " bytes=" + this.bytes + " whitelisted=" + this.whitelisted
                    + " truncated=" + this.truncated + " millis=" + this.millis;
        }
    }

    /** 一次体检的全部结果（{@code items} = 白名单之外的一切）。 */
    public record Report(Path worldDir, List<Item> items, Stats stats) {

        public int size() {
            return this.items == null ? 0 : this.items.size();
        }

        public boolean empty() {
            return size() == 0;
        }

        /** 第 {@code i} 条（越界 ⇒ {@code null}）。 */
        public Item item(int i) {
            return this.items != null && i >= 0 && i < this.items.size() ? this.items.get(i) : null;
        }

        /** 按子串过滤（大小写不敏感；空串 = 全过）。面板搜索框就用它。 */
        public List<Item> filter(String needle) {
            final List<Item> out = new ArrayList<>();
            if (this.items == null) {
                return out;
            }
            final String want = needle == null ? "" : needle.trim().toLowerCase(Locale.ROOT);
            for (Item it : this.items) {
                if (want.isEmpty() || it.rel().toLowerCase(Locale.ROOT).contains(want)) {
                    out.add(it);
                }
            }
            return out;
        }

        /** 一行摘要（日志/命令；每个 token 都能被离线判据直接 grep）。 */
        public String summaryLine() {
            return "audit items=" + size() + " " + this.stats.line();
        }

        /** 🔴 <b>面板顶部那一行</b>：{@code 共 N 条 · 文件 a · 目录 b · 文本 c · 二进制 d}（截断时补「已截断」）。 */
        public String panelSummary() {
            return "共 " + size() + " 条 · 文件 " + this.stats.files() + " · 目录 " + this.stats.dirs()
                    + " · 文本 " + this.stats.text() + " · 二进制 " + this.stats.binary()
                    + (this.stats.truncated() ? " · 已截断" : "");
        }

        /** 逐条一行（最多 {@code max} 条 + 一行 {@code ...共 N 条}）。 */
        public List<String> logLines(int max) {
            return render(max, false);
        }

        /** 逐条一行（玩家聊天栏形态）。 */
        public List<String> chatLines(int max) {
            return render(max, true);
        }

        private List<String> render(int max, boolean chat) {
            final List<String> out = new ArrayList<>();
            final int cap = Math.max(0, max);
            int shown = 0;
            if (this.items != null) {
                for (Item it : this.items) {
                    if (shown >= cap) {
                        break;
                    }
                    out.add(chat ? it.chatLine() : it.logLine());
                    shown++;
                }
            }
            if (size() > cap) {
                out.add((chat ? "§8" : "") + "...共 " + size() + " 条");
            }
            return out;
        }
    }

    /** 扫描上限 + 进度回调（面板/命令都能改）。 */
    public record Options(int maxItems, long textMaxBytes, Progress progress) {

        /** 进度回调（面板画进度用；离线判据传 {@code null}）。 */
        public interface Progress {
            void onProgress(String phase, int done, int total);
        }

        public static Options defaults() {
            return new Options(DEFAULT_MAX_ITEMS, TEXT_MAX_BYTES, null);
        }

        public Options withProgress(Progress p) {
            return new Options(this.maxItems, this.textMaxBytes, p);
        }
    }

    // ================================================================== 入口

    /** 默认上限扫一遍（只读）。 */
    public static Report scan(Path worldDir) {
        return scan(worldDir, Options.defaults());
    }

    /** 扫一遍（只读；返回白名单之外的全部条目 + 计数，<b>不写任何文件</b>）。 */
    public static Report scan(Path worldDir, Options opts) {
        final long t0 = System.currentTimeMillis();
        final Options o = opts == null ? Options.defaults() : opts;
        final Ctx ctx = new Ctx(o);
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return new Report(worldDir, List.copyOf(ctx.items),
                    new Stats(0, 0, 0, 0, 0, 0L, false, System.currentTimeMillis() - t0));
        }
        walkRootLike(worldDir, "", false, ctx);
        final Stats stats = new Stats(ctx.files, ctx.dirs, ctx.text, ctx.binary, ctx.whitelisted, ctx.bytes,
                ctx.truncated, System.currentTimeMillis() - t0);
        return new Report(worldDir, List.copyOf(ctx.items), stats);
    }

    private static final class Ctx {
        /** 条目数上限（超了标「已截断」）。 */
        final int itemCap;
        /**
         * 一次扫描最多<b>摸</b>多少个文件/目录（防"几十万个小文件"把一次扫描拖死）。
         *
         * <p>它<b>不</b>等于 {@link Options#maxItems()}：条目上限管的是"清单能有多长"，
         * 而摸过多少个东西（含白名单里的）是另一件事 —— 两者绑在一起会让
         * "上限设小一点"变成"扫不到东西"（实测：maxItems=3 时 visited 立刻打满，只扫出 1 条）。
         */
        final int walkCap;
        final Options opts;
        final List<Item> items = new ArrayList<>();
        int files;
        int dirs;
        int text;
        int binary;
        int whitelisted;
        long bytes;
        boolean truncated;
        /** 摸过的文件/目录总数。 */
        int visited;

        Ctx(Options opts) {
            this.opts = opts;
            this.itemCap = Math.max(0, opts.maxItems());
            final long scaled = (long) this.itemCap * 8L;
            this.walkCap = (int) Math.min(Integer.MAX_VALUE, Math.max(200_000L, scaled));
        }

        boolean full() {
            return this.items.size() >= this.itemCap || this.visited >= this.walkCap;
        }

        void progress(String phase, int done, int total) {
            if (this.opts.progress() != null) {
                this.opts.progress().onProgress(phase, done, total);
            }
        }
    }

    // ================================================================== 走目录

    /**
     * 走一个"像世界根一样"的目录（世界根 / 维度根用<b>同一张</b>目录白名单）。
     *
     * @param prefix  相对存档根的路径前缀（{@code ""} = 世界根本身）
     * @param dimRoot true = 这是一个维度根（里面不该再出现 {@code DIM-1}/{@code DIM1}/{@code dimensions}）
     */
    private static void walkRootLike(Path dir, String prefix, boolean dimRoot, Ctx ctx) {
        for (Path p : list(dir)) {
            if (ctx.full()) {
                ctx.truncated = true;
                return;
            }
            final String name = name(p);
            final String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            final boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
            final boolean isFile = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
            ctx.visited++;
            ctx.progress("walk", ctx.visited, ctx.walkCap);
            if (!dimRoot && isDir && DIMENSIONS_DIR.equals(name)) {
                walkDimensions(p, rel, ctx);
                continue;
            }
            if (!dimRoot && isDir && DIM_DIRS.contains(name)) {
                walkRootLike(p, rel, true, ctx);
                continue;
            }
            if (isDir && (VANILLA_DIRS.contains(name) || SERVER_RESOURCES.equals(name))) {
                ctx.whitelisted++;                       // 原版布局目录：整棵子树白名单，不往下走
                continue;
            }
            if (isFile && ROOT_FILES.contains(name)) {
                ctx.whitelisted++;                       // 原版布局文件
                continue;
            }
            addItem(p, rel, isDir, isFile, ctx);         // 白名单之外 ⇒ 一条异常
        }
    }

    /** 走 {@code dimensions/<命名空间>/<维度>}（两段都是<b>自由名</b>，所以只能靠"是不是目录"判）。 */
    private static void walkDimensions(Path dimsRoot, String prefix, Ctx ctx) {
        for (Path ns : list(dimsRoot)) {
            if (ctx.full()) {
                ctx.truncated = true;
                return;
            }
            final String nsRel = prefix + "/" + name(ns);
            final boolean nsDir = Files.isDirectory(ns, LinkOption.NOFOLLOW_LINKS);
            final boolean nsFile = Files.isRegularFile(ns, LinkOption.NOFOLLOW_LINKS);
            ctx.visited++;
            if (!nsDir) {
                addItem(ns, nsRel, false, nsFile, ctx);   // dimensions 下直接躺了个文件
                continue;
            }
            for (Path d : list(ns)) {
                if (ctx.full()) {
                    ctx.truncated = true;
                    return;
                }
                final String dRel = nsRel + "/" + name(d);
                final boolean dDir = Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS);
                final boolean dFile = Files.isRegularFile(d, LinkOption.NOFOLLOW_LINKS);
                ctx.visited++;
                if (dDir) {
                    walkRootLike(d, dRel, true, ctx);     // 维度根：再套一次目录白名单
                } else {
                    addItem(d, dRel, false, dFile, ctx);  // 命名空间下直接躺了个文件
                }
            }
        }
    }

    /** 记一条异常（目录只记它自己一条；字节数 = 子树和）。 */
    private static void addItem(Path p, String rel, boolean isDir, boolean isFile, Ctx ctx) {
        if (ctx.items.size() >= ctx.itemCap) {
            ctx.truncated = true;
            return;
        }
        final long modified = mtimeOf(p);
        if (isDir) {
            final long[] sum = new long[] {0L};
            final boolean[] cut = new boolean[] {false};
            sumTree(p, sum, cut, ctx, 0);
            ctx.dirs++;
            ctx.bytes += sum[0];
            ctx.items.add(new Item(rel, true, sum[0], modified, false, "", true));
            if (cut[0]) {
                ctx.truncated = true;
            }
            return;
        }
        if (!isFile) {
            // 符号链接 / 特殊文件：列出来、但标"不可删"（不跟链接、不动手）
            ctx.files++;
            ctx.binary++;
            ctx.items.add(new Item(rel, false, 0L, modified, false, "", false));
            return;
        }
        final long size = Math.max(0L, sizeOf(p));
        boolean text = false;
        String preview = "";
        if (size <= ctx.opts.textMaxBytes()) {
            final byte[] bytes = readAll(p);
            if (bytes != null) {
                final String decoded = decodeText(bytes);
                if (decoded != null) {
                    text = true;
                    preview = firstLine(decoded);
                }
            }
        }
        ctx.files++;
        if (text) {
            ctx.text++;
        } else {
            ctx.binary++;
        }
        ctx.bytes += size;
        ctx.items.add(new Item(rel, false, size, modified, text, preview, true));
    }

    /** 子树字节和（有上限；被截断时把 {@code cut[0]} 置真）。 */
    private static void sumTree(Path dir, long[] sum, boolean[] cut, Ctx ctx, int depth) {
        if (cut[0] || depth > 64) {
            return;
        }
        for (Path c : list(dir)) {
            if (ctx.visited >= ctx.walkCap) {
                cut[0] = true;
                return;
            }
            final boolean isDir = Files.isDirectory(c, LinkOption.NOFOLLOW_LINKS);
            if (isDir) {
                sumTree(c, sum, cut, ctx, depth + 1);
                continue;
            }
            ctx.visited++;
            sum[0] += Math.max(0L, sizeOf(c));
        }
    }

    // ================================================================== 白名单判据（删除动作的唯一闸门）

    /**
     * 🔴 这条相对路径在不在<b>原版世界布局</b>里（= 白名单）。
     *
     * <p>删除动作就是靠它拒绝"删白名单内的东西"的（负对照：{@code region/r.0.0.mca} 必须被拒）。
     * 判据与扫描<b>同一张表</b>，两处不可能漂移。
     */
    public static boolean whitelisted(String relativePath) {
        final String[] seg = segments(relativePath);
        if (seg.length == 0) {
            return false;
        }
        if (seg.length == 1) {
            // 世界根上的一条：原版文件、原版目录、三个维度目录、server-resources 都算白名单
            return ROOT_FILES.contains(seg[0]) || VANILLA_DIRS.contains(seg[0])
                    || DIM_DIRS.contains(seg[0]) || DIMENSIONS_DIR.equals(seg[0])
                    || SERVER_RESOURCES.equals(seg[0]);
        }
        if (VANILLA_DIRS.contains(seg[0]) || SERVER_RESOURCES.equals(seg[0])) {
            return true;                                  // 原版目录的整棵子树
        }
        if (DIMENSIONS_DIR.equals(seg[0])) {
            if (seg.length <= 2) {
                return true;                              // dimensions/<命名空间> 本身不是条目
            }
            // segments = dimensions / <命名空间> / <维度> / <维度根里的相对路径…>
            return whitelistedInDimRoot(seg, 3);
        }
        if (DIM_DIRS.contains(seg[0])) {
            return whitelistedInDimRoot(seg, 1);
        }
        return false;
    }

    /** 维度根内部的判据（从 {@code from} 这一段起算）。 */
    private static boolean whitelistedInDimRoot(String[] seg, int from) {
        if (from >= seg.length) {
            return true;                                  // 维度根自己
        }
        if (from == seg.length - 1) {
            return ROOT_FILES.contains(seg[from]) || VANILLA_DIRS.contains(seg[from])
                    || SERVER_RESOURCES.equals(seg[from]);
        }
        return VANILLA_DIRS.contains(seg[from]) || SERVER_RESOURCES.equals(seg[from]);
    }

    /** 把相对路径切成段（{@code /} 与 {@code \} 都认；{@code .} / 空段丢掉；{@code ..} 保留原样）。 */
    public static String[] segments(String relativePath) {
        if (relativePath == null) {
            return new String[0];
        }
        final String s = relativePath.trim().replace('\\', '/');
        final List<String> out = new ArrayList<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            out.add(part);
        }
        return out.toArray(new String[0]);
    }

    // ================================================================== 文本判定

    /**
     * 按 UTF-8 <b>严格</b>解码：能解码且没有 NUL 才算文本（否则当二进制）。
     *
     * @return 文本内容；不是文本 ⇒ {@code null}
     */
    public static String decodeText(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** 首行、去首尾空白、最多 {@link #PREVIEW_MAX_CHARS} 字。 */
    public static String firstLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        final String line = text.substring(0, end).trim();
        return line.length() <= PREVIEW_MAX_CHARS ? line : line.substring(0, PREVIEW_MAX_CHARS);
    }

    // ================================================================== 读数小工具

    /** 字节数的中文读数（{@code 0 字节} / {@code 812 字节} / {@code 1.2 KiB} / {@code 3.4 MiB}）。 */
    public static String bytesText(long bytes) {
        if (bytes < 1024L) {
            return bytes + " 字节";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB(%d 字节)", bytes / 1024.0D, bytes);
        }
        return String.format(Locale.ROOT, "%.1f MiB(%d 字节)", bytes / (1024.0D * 1024.0D), bytes);
    }

    private static List<Path> list(Path dir) {
        final List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (Throwable t) {
            return out;
        }
        out.sort(Comparator.comparing(p -> p.getFileName() == null ? "" : p.getFileName().toString()));
        return out;
    }

    private static String name(Path p) {
        return p == null || p.getFileName() == null ? "" : p.getFileName().toString();
    }

    private static byte[] readAll(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long mtimeOf(Path p) {
        try {
            final FileTime t = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
            return t == null ? 0L : t.toMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 相对存档根的路径（{@code /} 分隔；给日志、面板、以及"按相对路径找文件"用）。 */
    public static String relative(Path worldDir, Path file) {
        if (worldDir == null || file == null) {
            return "-";
        }
        try {
            return worldDir.relativize(file).toString().replace('\\', '/');
        } catch (Throwable t) {
            return file.toString().replace('\\', '/');
        }
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditScan[root_files=" + ROOT_FILES.size() + " vanilla_dirs=" + VANILLA_DIRS.size()
                + " dim_dirs=" + DIM_DIRS.size() + "]";
    }
}
