package com.shanhai.common.saveedit.audit;

import com.shanhai.common.saveedit.ShanhaiEntityEdit;
import com.shanhai.common.saveedit.ShanhaiRegionEdit;
import com.shanhai.common.saveedit.ShanhaiRegionFile;
import com.shanhai.common.saveedit.ShanhaiSaveEditDimension;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「双份登记 · 审计」的<b>只读检测核</b>。
 *
 * <h2>一、判据是什么（只立在世界数据本身）</h2>
 * 同一个实体在<b>两个地方</b>各被记了一份：
 * <ol>
 *   <li>一份在<b>原版区块文件</b>里 —— {@code entities/*.mca}（主世界）、{@code DIM-1/entities}、
 *       {@code DIM1/entities}、{@code dimensions/<ns>/<path>/entities} 下的区块 NBT 里的实体列表；</li>
 *   <li>另一份在<b>非原版布局的普通文件</b>里 —— 世界根或维度目录下、白名单之外的一切普通文件
 *       （不管它是 {@code .dat}、{@code .txt}、{@code .json}、没有扩展名，还是某个目录里的一堆文件）。</li>
 * </ol>
 * 命中条件 = 那两个位置出现<b>同一个 UUID</b> 或 <b>同一组坐标</b>（容差见
 * {@link ShanhaiSaveEditAuditUuid#POS_TOLERANCE}）。
 *
 * <h2>二、🔴 为什么判据里没有"某个模组"的影子</h2>
 * 本类<b>不</b>读任何具体类型名 / 文件名 / 字段名来做判定：它只回答"这个 UUID（或这组坐标）
 * 是不是同时出现在两个地方"。换一个名字、换一个扩展名、甚至没有扩展名，只要它还是"在原版布局之外
 * 又登记了一份同一个实体"，就照样命中；反过来，一个单纯的旁挂文件（只登记坐标、区块里没有对应实体）
 * 不会命中 —— 那不是"双份"。
 *
 * <h2>三、白名单（除这些之外的一切都算旁挂文件）</h2>
 * 世界根：{@code level.dat}、{@code level.dat_old}、{@code session.lock}、{@code icon.png}、
 * {@code region/}、{@code entities/}、{@code poi/}、{@code data/}、{@code datapacks/}、
 * {@code playerdata/}、{@code advancements/}、{@code stats/}、{@code DIM-1/}、{@code DIM1/}、
 * {@code dimensions/}、{@code server-resources/}。
 * <p>这些<b>只作排除</b>（不解析、不修改）：{@code region/} 等原版布局目录整棵跳过；
 * {@code DIM-1/}、{@code DIM1/}、{@code dimensions/<ns>/<path>/} 是<b>维度根</b>，
 * 在它们里面<b>再套一次同一张白名单</b>（维度目录下同样应该有 {@code region/entities/data/…}），
 * 于是"维度目录里塞了一份非原版文件"这种情况也在检测范围内。
 * {@code server-resources/} 与 {@code session.lock} 明确不碰。
 *
 * <h2>四、四种命中形态（都要，缺一个就有盲区）</h2>
 * <ul>
 *   <li>文本文件里的 UUID：32 位无连字符 / 36 位带连字符 / 大写 / 夹在别的文本里；</li>
 *   <li>文本文件里的坐标：{@code x,y,z} 长小数，容差 0.001 命中、差 0.01 不命中；</li>
 *   <li>二进制/NBT 文件里的 UUID：String tag、{@code IntArray[4]}、{@code UUIDMost/UUIDLeast}；</li>
 *   <li>二进制/NBT 文件里的坐标：同一个 Compound 里连续三个数值、或 {@code IntArray[3]}。</li>
 * </ul>
 *
 * <h2>五、不崩的四条</h2>
 * 坏区块文件 / 解不开的槽位 / 读不了的旁挂文件 / 畸形 NBT 一律<b>计数后跳过</b>
 * （{@code entity_files_bad}、{@code unreadable}、{@code oversize}），绝不让一次检测抛出去。
 * 各类上限见 {@link Options}（防的是"旁挂目录里躺着几十万个小文件"这种现场）。
 */
public final class ShanhaiSaveEditAuditScan {

    /** 命中：同一个实体在两个地方各一份。 */
    public static final String KIND_DOUBLE = "double_registration";
    /**
     * 命中：旁挂文件里还登记着这个 UUID，但区块文件里<b>已经没有</b>这条实体了。
     *
     * <p>它是"只做了一半"的残迹：{@link #KIND_DOUBLE} 那份删掉了、旁挂那份还在。
     * 判据里的负对照就靠它：只删区块那一份、不删旁挂那一行 ⇒ 再扫一次仍报这一条。
     * 只有调用方显式给出"对照集"（{@link Options#priorEntityUuids()}）时才会产出，
     * 所以正常审计（不带对照集）不会因为它刷屏。
     */
    public static final String KIND_SIDECAR_ONLY = "sidecar_only";

    /** 文本旁挂文件的大小上限（判据口径：≤ 8 MiB）。 */
    public static final long TEXT_MAX_BYTES = 8L << 20;
    /** 二进制/NBT 旁挂文件的大小上限（判据口径：≤ 16 MiB）。 */
    public static final long BINARY_MAX_BYTES = 16L << 20;

    /** 世界根上的原版布局文件名（只排除）。 */
    public static final Set<String> ROOT_FILES = Set.of("level.dat", "level.dat_old", "session.lock", "icon.png");

    /** 世界根 / 维度根上的原版布局目录名（只排除）。 */
    public static final Set<String> VANILLA_DIRS = Set.of("region", "entities", "poi", "data", "datapacks",
            "playerdata", "advancements", "stats");

    /** 明确不碰的目录（可能很大，且与本判据无关）。 */
    public static final String SERVER_RESOURCES = "server-resources";

    private static final String ENTITIES_DIR = ShanhaiSaveEditDimension.ENTITIES_DIR;
    private static final String DIMENSIONS_DIR = ShanhaiSaveEditDimension.DIMENSIONS_DIR;

    private ShanhaiSaveEditAuditScan() {}

    // ================================================================== 数据模型

    /** 区块文件里的一条实体。 */
    public record EntityRef(String uuid, String uuid32, String type, double x, double y, double z,
                            String dim, String file, int chunkX, int chunkZ, int slot, int index) {

        public double[] pos() {
            return new double[] {this.x, this.y, this.z};
        }

        public String posText() {
            return ShanhaiSaveEditAuditUuid.posText(pos());
        }

        public String posShort() {
            return ShanhaiSaveEditAuditUuid.posShort(pos());
        }

        /** 去掉 {@code minecraft:} 的类型名（面板清单用，省字）。 */
        public String shortType() {
            final String t = this.type == null ? "?" : this.type;
            return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
        }

        public String chunkText() {
            return "(" + this.chunkX + "," + this.chunkZ + ")";
        }

        public String line() {
            return "type=" + this.type + " uuid=" + this.uuid + " pos=" + posText()
                    + " dim=" + this.dim + " file=" + this.file + " chunk=" + chunkText()
                    + " slot=" + this.slot + " #" + this.index;
        }
    }

    /**
     * 旁挂侧的一条证据。
     *
     * @param file    相对世界根的路径（{@code /} 分隔）
     * @param line    文本文件里的行号（1 起；二进制恒 0）
     * @param tagPath 二进制/NBT 里的位置（文本恒空串）
     * @param match   {@code uuid} 或 {@code pos}
     * @param binary  是不是二进制/NBT 文件（这类<b>只报不改</b>）
     */
    public record SidecarRef(String file, int line, String tagPath, String match, boolean binary) {

        /** 面板/日志里的定位写法：文本 {@code file:line}，二进制 {@code file#tagPath}。 */
        public String where() {
            return this.binary ? this.file + "#" + (this.tagPath == null ? "?" : this.tagPath)
                    : this.file + ":" + this.line;
        }
    }

    /**
     * 一条 finding。
     *
     * <p>{@code entity == null} 只出现在 {@link #KIND_SIDECAR_ONLY}（原版侧那份已经没了）。
     */
    public record Finding(String kind, String uuid, EntityRef entity, SidecarRef sidecar) {

        public boolean doubleRegistration() {
            return KIND_DOUBLE.equals(this.kind);
        }

        /** 能不能"删区块里那份"（实体还在区块文件里才谈得上）。 */
        public boolean canDropEntity() {
            return this.entity != null;
        }

        /** 能不能"删旁挂那行"（文本旁挂才谈得上；二进制只报不改）。 */
        public boolean canDropSidecar() {
            return this.sidecar != null && !this.sidecar.binary();
        }

        /** 一条 finding 一行（日志用；字段齐全，便于对账）。 */
        public String logLine() {
            final StringBuilder sb = new StringBuilder();
            sb.append("kind=").append(this.kind);
            if (this.entity != null) {
                sb.append(" entity=").append(this.entity.line());
            } else {
                sb.append(" uuid=").append(this.uuid).append(" entity=<区块里已经没有>");
            }
            sb.append(" | sidecar=").append(this.sidecar == null ? "-" : this.sidecar.where())
                    .append(" match=").append(this.sidecar == null ? "-" : this.sidecar.match())
                    .append(this.sidecar != null && this.sidecar.binary() ? " binary=true" : "");
            return sb.toString();
        }

        /** 一条 finding 一行（玩家聊天栏用）。 */
        public String chatLine() {
            final String type = this.entity == null ? "§8残迹" : "§f" + this.entity.shortType();
            final String pos = this.entity == null ? "§8?" : "§7" + this.entity.posShort();
            final String where = this.sidecar == null ? "§8-" : "§e" + this.sidecar.where();
            return "§c双份§8· " + type + " " + pos + " §8← " + where
                    + (this.sidecar != null && this.sidecar.binary() ? " §8(二进制只报不改)" : "");
        }

        /** 🔴 <b>面板清单</b>的一行：{@code 类型 坐标 旁挂文件:行}。 */
        public String panelLine() {
            final String type = this.entity == null ? "残迹" : this.entity.shortType();
            final String pos = this.entity == null ? "?" : this.entity.posShort();
            return type + " " + pos + " " + (this.sidecar == null ? "-" : this.sidecar.where());
        }
    }

    /** 各类计数（全部是"扫出来看见了什么"，不是调用方自述）。 */
    public record Stats(int entityFiles, int entityFilesBad, int entityFilesEmpty, int entities, int chunks, int badChunks,
                        int sidecarFiles, int sidecarText, int sidecarBinary, int unreadable,
                        int oversize, int truncatedFiles, long millis) {

        public String line() {
            return "entities=" + this.entities + " entity_files=" + this.entityFiles
                    + " entity_files_bad=" + this.entityFilesBad
                    + " entity_files_empty=" + this.entityFilesEmpty
                    + " chunks=" + this.chunks
                    + " bad_chunks=" + this.badChunks
                    + " sidecar_files=" + this.sidecarFiles + " text=" + this.sidecarText
                    + " binary=" + this.sidecarBinary + " unreadable=" + this.unreadable
                    + " oversize=" + this.oversize + " truncated_files=" + this.truncatedFiles
                    + " millis=" + this.millis;
        }
    }

    /** 一次检测的全部结果。 */
    public record Report(Path worldDir, List<Finding> findings, Stats stats) {

        public int doubleRegistrations() {
            return count(KIND_DOUBLE);
        }

        public int sidecarOnly() {
            return count(KIND_SIDECAR_ONLY);
        }

        public int textSidecars() {
            int n = 0;
            for (Finding f : this.findings) {
                if (f.sidecar() != null && !f.sidecar().binary()) {
                    n++;
                }
            }
            return n;
        }

        public int binarySidecars() {
            int n = 0;
            for (Finding f : this.findings) {
                if (f.sidecar() != null && f.sidecar().binary()) {
                    n++;
                }
            }
            return n;
        }

        private int count(String kind) {
            int n = 0;
            for (Finding f : this.findings) {
                if (kind.equals(f.kind())) {
                    n++;
                }
            }
            return n;
        }

        /** 一行摘要（日志/命令用；每个 token 都能被离线判据直接 grep）。 */
        public String summaryLine() {
            return "audit findings=" + this.findings.size()
                    + " double=" + doubleRegistrations()
                    + " sidecar_only=" + sidecarOnly()
                    + " sidecar_binary_skipped=" + binarySidecars()
                    + " " + this.stats.line();
        }

        /** 🔴 <b>面板顶部那一行</b>：{@code 共 N 条 · 可删实体 K · 纯文本旁挂 L · 二进制旁挂 M}。 */
        public String panelSummary() {
            return "共 " + this.findings.size() + " 条 · 可删实体 " + doubleRegistrations()
                    + " · 纯文本旁挂 " + textSidecars() + " · 二进制旁挂 " + binarySidecars();
        }

        /** 逐条一行（最多 {@code max} 条 + 一行 {@code ...共 N 条}）。 */
        public List<String> logLines(int max) {
            return render(max, false);
        }

        /** 逐条一行（玩家聊天栏形态）。 */
        public List<String> chatLines(int max) {
            return render(max, true);
        }

        /**
         * 🔴 <b>面板清单区</b>的若干行：每行 {@code 类型 坐标 旁挂文件:行}（超出补一行 {@code …还有 N 条}）。
         */
        public List<String> panelLines(int max) {
            final List<String> out = new ArrayList<>();
            final int cap = Math.max(0, max);
            int shown = 0;
            for (Finding f : this.findings) {
                if (shown >= cap) {
                    break;
                }
                out.add(f.panelLine());
                shown++;
            }
            if (this.findings.size() > cap) {
                out.add("§8…还有 " + (this.findings.size() - cap) + " 条（共 " + this.findings.size() + " 条）");
            }
            return out;
        }

        private List<String> render(int max, boolean chat) {
            final List<String> out = new ArrayList<>();
            final int cap = Math.max(0, max);
            int shown = 0;
            for (Finding f : this.findings) {
                if (shown >= cap) {
                    break;
                }
                out.add(chat ? f.chatLine() : f.logLine());
                shown++;
            }
            if (this.findings.size() > cap) {
                out.add(chat ? "§8...共 " + this.findings.size() + " 条"
                        : "...共 " + this.findings.size() + " 条");
            }
            return out;
        }

        public boolean empty() {
            return this.findings.isEmpty();
        }
    }

    /** 扫描上限 + 进度回调 + 对照集（面板/探针都能改）。 */
    public record Options(int maxEntities, int maxSidecarFiles, long maxTextBytes, long maxBinaryBytes,
                          int maxFindings, Set<String> priorEntityUuids, Progress progress) {

        /** 进度回调（面板画进度条用；离线判据传 {@code null}）。 */
        public interface Progress {
            void onProgress(String phase, int done, int total);
        }

        public Options {
            priorEntityUuids = priorEntityUuids == null ? Set.of() : Set.copyOf(priorEntityUuids);
        }

        public static Options defaults() {
            return new Options(200_000, 4_000, TEXT_MAX_BYTES, BINARY_MAX_BYTES, 2_000, Set.of(), null);
        }

        public Options withProgress(Progress p) {
            return new Options(this.maxEntities, this.maxSidecarFiles, this.maxTextBytes, this.maxBinaryBytes,
                    this.maxFindings, this.priorEntityUuids, p);
        }

        /** 对照集：只有带上它，{@link #KIND_SIDECAR_ONLY} 才会产出（见该常量的说明）。 */
        public Options withPrior(Set<String> uuids) {
            return new Options(this.maxEntities, this.maxSidecarFiles, this.maxTextBytes, this.maxBinaryBytes,
                    this.maxFindings, uuids, this.progress);
        }

        public Options withMaxSidecarFiles(int n) {
            return new Options(this.maxEntities, n, this.maxTextBytes, this.maxBinaryBytes,
                    this.maxFindings, this.priorEntityUuids, this.progress);
        }
    }

    // ================================================================== 入口

    /** 默认上限扫一遍（只读）。 */
    public static Report scan(Path worldDir) {
        return scan(worldDir, Options.defaults());
    }

    /** 扫一遍（只读；返回全部 finding + 计数，<b>不写任何文件</b>）。 */
    public static Report scan(Path worldDir, Options opts) {
        final long t0 = System.currentTimeMillis();
        final Options o = opts == null ? Options.defaults() : opts;
        final List<Finding> found = new ArrayList<>();
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return new Report(worldDir, found, new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        final EntityIndex index = indexEntities(worldDir, o);
        final Map<String, EntityRef> byUuid = new HashMap<>();
        final PosIndex posIndex = new PosIndex();
        for (EntityRef e : index.entities()) {
            if (e.uuid() != null) {
                byUuid.putIfAbsent(e.uuid(), e);
            }
            posIndex.add(e);
        }
        final Map<String, Finding> out = new LinkedHashMap<>();
        final Counters c = new Counters();
        final List<Path> files = sidecarFiles(worldDir);
        for (Path f : files) {
            if (c.files >= o.maxSidecarFiles()) {
                c.truncatedFiles++;
                break;
            }
            if (out.size() >= o.maxFindings()) {
                c.truncatedFiles++;
                break;
            }
            c.files++;
            scanOneFile(worldDir, f, byUuid, posIndex, o, out, c);
            if (o.progress() != null) {
                o.progress().onProgress("sidecar", c.files, Math.min(files.size(), o.maxSidecarFiles()));
            }
        }
        found.addAll(out.values());
        final Stats stats = new Stats(index.files(), index.badFiles(), index.emptyFiles(), index.entities().size(),
                index.chunks(), index.badChunks(), c.files, c.text, c.binary, c.unreadable, c.oversize,
                c.truncatedFiles, System.currentTimeMillis() - t0);
        return new Report(worldDir, found, stats);
    }

    // ================================================================== 实体侧（区块 NBT）

    /** 一次实体索引的读数。 */
    public record EntityIndex(List<EntityRef> entities, int files, int badFiles, int emptyFiles, int chunks,
                                  int badChunks) {}

    /** 一个维度根（{@code dim} 是归一后的维度 id，{@code root} 是该维度的目录）。 */
    private record DimRoot(String dim, Path root) {}

    /** 把四个位置（主世界 / 下界 / 末地 / 每个模组维度）的 {@code entities/*.mca} 全读一遍。 */
    public static EntityIndex indexEntities(Path worldDir) {
        return indexEntities(worldDir, Options.defaults());
    }

    /** 同上（受 {@link Options#maxEntities()} 限制）。 */
    public static EntityIndex indexEntities(Path worldDir, Options opts) {
        final Options o = opts == null ? Options.defaults() : opts;
        final List<EntityRef> out = new ArrayList<>();
        int files = 0;
        int badFiles = 0;
        int emptyFiles = 0;
        int chunks = 0;
        int badChunks = 0;
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return new EntityIndex(out, 0, 0, 0, 0, 0);
        }
        for (DimRoot d : dimRoots(worldDir)) {
            final Path dir = d.root().resolve(ENTITIES_DIR);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (Path f : list(dir)) {
                if (!f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mca")) {
                    continue;
                }
                if (out.size() >= o.maxEntities()) {
                    return new EntityIndex(out, files, badFiles, emptyFiles, chunks, badChunks);
                }
                files++;
                final byte[] bytes;
                try {
                    bytes = Files.readAllBytes(f);
                } catch (Throwable t) {
                    badFiles++;
                    continue;
                }
                if (bytes.length < ShanhaiRegionFile.HEADER_BYTES) {
                    // 0 字节 / 空壳文件：里面没有区块可读 ⇒ 记"空"而不记"坏"
                    // （真实存档里这种文件是常态：区块没被写过，文件先被建出来了）
                    emptyFiles++;
                    continue;
                }
                final ShanhaiRegionFile.Parse parse;
                try {
                    parse = ShanhaiRegionFile.parse(bytes);
                } catch (Throwable t) {
                    badFiles++;
                    continue;
                }
                if (!parse.problems().isEmpty()) {
                    // 结构有问题 ⇒ 整份跳过，不猜
                    badFiles++;
                    continue;
                }
                if (parse.chunkCount() == 0) {
                    emptyFiles++;
                    continue;
                }
                chunks += parse.chunkCount();
                final int[] rz = regionCoordsOf(f);
                final String rel = relative(worldDir, f);
                for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
                    final ShanhaiRegionFile.Chunk chunk = parse.chunk(slot);
                    if (chunk == null) {
                        continue;
                    }
                    final CompoundTag tag;
                    try {
                        tag = ShanhaiRegionEdit.readChunk(chunk);
                    } catch (Throwable t) {
                        badChunks++;
                        continue;
                    }
                    final int[] cxz = ShanhaiEntityEdit.chunkCoords(tag, slot, rz[0], rz[1]);
                    final ListTag list = tag.getList(ShanhaiEntityEdit.ENTITIES, 10);
                    for (int i = 0; i < list.size(); i++) {
                        if (out.size() >= o.maxEntities()) {
                            return new EntityIndex(out, files, badFiles, emptyFiles, chunks, badChunks);
                        }
                        final CompoundTag e = list.getCompound(i);
                        final String uuid = ShanhaiSaveEditAuditUuid.canonical(
                                ShanhaiEntityEdit.entityUuidText(e));
                        final double[] p = ShanhaiEntityEdit.entityPos(e);
                        out.add(new EntityRef(uuid, uuid == null ? null : ShanhaiSaveEditAuditUuid.hex32(uuid),
                                ShanhaiEntityEdit.entityType(e), p[0], p[1], p[2], d.dim(), rel,
                                cxz[0], cxz[1], slot, i));
                    }
                }
            }
        }
        return new EntityIndex(out, files, badFiles, emptyFiles, chunks, badChunks);
    }

    /** 按 UUID 在世界里找那一条实体（找不到 ⇒ {@code null}；只读）。 */
    public static EntityRef findEntity(Path worldDir, String canonicalUuid) {
        final String want = ShanhaiSaveEditAuditUuid.canonical(canonicalUuid);
        if (want == null) {
            return null;
        }
        for (EntityRef e : indexEntities(worldDir).entities()) {
            if (want.equals(e.uuid())) {
                return e;
            }
        }
        return null;
    }

    /** 四个维度的目录（主世界 = 存档根；下界/末地 = 原版硬编码目录名；模组维度 = {@code dimensions/<ns>/<path>}）。 */
    private static List<DimRoot> dimRoots(Path worldDir) {
        final List<DimRoot> out = new ArrayList<>();
        out.add(new DimRoot(ShanhaiSaveEditDimension.OVERWORLD, worldDir));
        final Path nether = worldDir.resolve(ShanhaiSaveEditDimension.NETHER_DIR);
        if (Files.isDirectory(nether)) {
            out.add(new DimRoot(ShanhaiSaveEditDimension.NETHER, nether));
        }
        final Path end = worldDir.resolve(ShanhaiSaveEditDimension.END_DIR);
        if (Files.isDirectory(end)) {
            out.add(new DimRoot(ShanhaiSaveEditDimension.END, end));
        }
        final Path dims = worldDir.resolve(DIMENSIONS_DIR);
        if (Files.isDirectory(dims)) {
            for (Path ns : list(dims)) {
                if (!Files.isDirectory(ns, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                for (Path d : list(ns)) {
                    if (Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                        out.add(new DimRoot(name(ns) + ":" + name(d), d));
                    }
                }
            }
        }
        return out;
    }

    /** {@code r.<x>.<z>.mca} → {@code [x,z]}（读不出来 ⇒ {@code [0,0]}，随后按 {@code Position} 兜底）。 */
    private static int[] regionCoordsOf(Path file) {
        final String n = file.getFileName().toString();
        try {
            if (n.startsWith("r.") && n.endsWith(".mca")) {
                final String[] parts = n.substring(2, n.length() - 4).split("\\.");
                if (parts.length == 2) {
                    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的 [0,0]
        }
        return new int[] {0, 0};
    }

    // ================================================================== 旁挂侧（白名单之外的所有普通文件）

    /**
     * 白名单之外的<b>全部普通文件</b>（递归；{@code server-resources/} 与 {@code session.lock} 不碰）。
     *
     * <p>顺序稳定（目录内按名字排序），所以两次检测的清单顺序一致 —— 面板选中项不会跳。
     */
    public static List<Path> sidecarFiles(Path worldDir) {
        final List<Path> out = new ArrayList<>();
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return out;
        }
        collectWorldRoot(worldDir, out, 0);
        return out;
    }

    private static void collectWorldRoot(Path worldDir, List<Path> out, int depth) {
        for (Path p : list(worldDir)) {
            final String n = name(p);
            if (ROOT_FILES.contains(n) || SERVER_RESOURCES.equals(n)) {
                continue;
            }
            if (ShanhaiSaveEditDimension.NETHER_DIR.equals(n) || ShanhaiSaveEditDimension.END_DIR.equals(n)) {
                collectDimRoot(p, out, depth + 1);
                continue;
            }
            if (DIMENSIONS_DIR.equals(n)) {
                collectModdedDims(p, out, depth + 1);
                continue;
            }
            if (VANILLA_DIRS.contains(n)) {
                continue;
            }
            collectDeep(p, out, depth + 1);
        }
    }

    private static void collectDimRoot(Path dimRoot, List<Path> out, int depth) {
        for (Path p : list(dimRoot)) {
            final String n = name(p);
            if (ROOT_FILES.contains(n) || SERVER_RESOURCES.equals(n) || VANILLA_DIRS.contains(n)) {
                continue;
            }
            collectDeep(p, out, depth + 1);
        }
    }

    private static void collectModdedDims(Path dimsRoot, List<Path> out, int depth) {
        for (Path ns : list(dimsRoot)) {
            if (!Files.isDirectory(ns, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            for (Path d : list(ns)) {
                if (Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                    collectDimRoot(d, out, depth + 1);
                }
            }
        }
    }

    private static void collectDeep(Path p, List<Path> out, int depth) {
        if (depth > 64) {
            return;
        }
        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            out.add(p);
            return;
        }
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        for (Path c : list(p)) {
            collectDeep(c, out, depth + 1);
        }
    }

    // ================================================================== 单文件扫描

    private static final class Counters {
        int files;
        int text;
        int binary;
        int unreadable;
        int oversize;
        int truncatedFiles;
    }

    private static void scanOneFile(Path worldDir, Path file, Map<String, EntityRef> byUuid,
                                    PosIndex index, Options opts, Map<String, Finding> out, Counters c) {
        final String rel = relative(worldDir, file);
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (Throwable t) {
            c.unreadable++;
            return;
        }
        if (bytes.length <= opts.maxTextBytes()) {
            final String text = decodeText(bytes);
            if (text != null) {
                c.text++;
                scanText(rel, text, byUuid, index, opts, out);
                return;
            }
        }
        if (bytes.length <= opts.maxBinaryBytes()) {
            c.binary++;
            final ShanhaiSaveEditAuditNbt.Walk w = ShanhaiSaveEditAuditNbt.read(bytes);
            if (!w.ok()) {
                c.unreadable++;
                return;
            }
            // 同一份文件里，同一个实体只报一条：UUID 命中了就不再按坐标重复计
            final Set<String> uuidSeen = new LinkedHashSet<>();
            for (ShanhaiSaveEditAuditNbt.UuidRef u : w.uuids()) {
                uuidSeen.add(u.uuid());
                matchUuid(u.uuid(), new SidecarRef(rel, 0, u.path(), "uuid", true), byUuid, opts, out);
            }
            for (ShanhaiSaveEditAuditNbt.TripleRef t : w.triples()) {
                for (EntityRef e : index.near(t.pos())) {
                    if (e.uuid() != null && uuidSeen.contains(e.uuid())) {
                        continue;
                    }
                    add(out, KIND_DOUBLE, e.uuid(), e, new SidecarRef(rel, 0, t.path(), "pos", true));
                }
            }
            return;
        }
        c.oversize++;
    }

    private static void scanText(String rel, String text, Map<String, EntityRef> byUuid, PosIndex index,
                                 Options opts, Map<String, Finding> out) {
        int lineNo = 0;
        int start = 0;
        final int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                lineNo++;
                if (i > start) {
                    scanLine(rel, lineNo, text.substring(start, i), byUuid, index, opts, out);
                }
                start = i + 1;
            }
        }
    }

    private static void scanLine(String rel, int lineNo, String line, Map<String, EntityRef> byUuid,
                                 PosIndex index, Options opts, Map<String, Finding> out) {
        boolean uuidHit = false;
        for (String u : ShanhaiSaveEditAuditUuid.findUuids(line)) {
            final SidecarRef ref = new SidecarRef(rel, lineNo, "", "uuid", false);
            final EntityRef e = byUuid.get(u);
            if (e != null) {
                add(out, KIND_DOUBLE, u, e, ref);
                uuidHit = true;
            } else if (opts.priorEntityUuids().contains(u)) {
                add(out, KIND_SIDECAR_ONLY, u, null, ref);
                uuidHit = true;
            }
        }
        if (uuidHit) {
            // 一行已经以 UUID 命中 ⇒ 这条旁挂证据只报一次（不再按坐标重复计一条）
            return;
        }
        for (double[] t : ShanhaiSaveEditAuditUuid.findTriples(line)) {
            matchPos(t, new SidecarRef(rel, lineNo, "", "pos", false), index, out);
        }
    }

    private static void matchUuid(String uuid, SidecarRef ref, Map<String, EntityRef> byUuid, Options opts,
                                  Map<String, Finding> out) {
        if (uuid == null) {
            return;
        }
        final EntityRef e = byUuid.get(uuid);
        if (e != null) {
            add(out, KIND_DOUBLE, uuid, e, ref);
            return;
        }
        if (opts.priorEntityUuids().contains(uuid)) {
            add(out, KIND_SIDECAR_ONLY, uuid, null, ref);
        }
    }

    private static void matchPos(double[] pos, SidecarRef ref, PosIndex index, Map<String, Finding> out) {
        if (pos == null || index == null) {
            return;
        }
        for (EntityRef e : index.near(pos)) {
            add(out, KIND_DOUBLE, e.uuid(), e, ref);
        }
    }

    private static void add(Map<String, Finding> out, String kind, String uuid, EntityRef e, SidecarRef ref) {
        final String key = kind + "|" + (uuid == null ? "?" : uuid) + "|" + ref.file() + "|" + ref.where();
        out.putIfAbsent(key, new Finding(kind, uuid, e, ref));
    }

    // ================================================================== 文本判定 / 行匹配

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

    /** 这一段文本里"含该 UUID 或匹配坐标"的行号（1 起）。 */
    public static List<Integer> matchingLines(String text, String canonicalUuid, double[] pos) {
        final List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int lineNo = 0;
        int start = 0;
        final int n = text.length();
        for (int i = 0; i <= n; i++) {
            if (i == n || text.charAt(i) == '\n') {
                lineNo++;
                if (i > start && lineMatches(text.substring(start, i), canonicalUuid, pos)) {
                    out.add(lineNo);
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** 这一行是不是"含该 UUID（任一种写法）或匹配坐标"。 */
    public static boolean lineMatches(String line, String canonicalUuid, double[] pos) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (canonicalUuid != null) {
            for (String u : ShanhaiSaveEditAuditUuid.findUuids(line)) {
                if (canonicalUuid.equals(u)) {
                    return true;
                }
            }
        }
        if (pos != null) {
            for (double[] t : ShanhaiSaveEditAuditUuid.findTriples(line)) {
                if (ShanhaiSaveEditAuditUuid.posWithin(t, pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================== 旁挂命中清单（给"删旁挂那行"用）

    /**
     * 一条旁挂命中：哪个文件、是文本还是二进制、命中在第几行 / 哪条 tagPath。
     *
     * @param match {@code uuid} 或 {@code pos}
     */
    public record SidecarHit(Path file, String rel, boolean binary, List<Integer> lines,
                             List<String> tagPaths, String match) {

        public boolean text() {
            return !this.binary;
        }
    }

    /** 在世界里找"含这个 UUID 或匹配坐标"的旁挂文件（只读）。 */
    public static List<SidecarHit> sidecarHits(Path worldDir, String canonicalUuid, double[] pos) {
        final List<SidecarHit> out = new ArrayList<>();
        final String want = ShanhaiSaveEditAuditUuid.canonical(canonicalUuid);
        if (worldDir == null || (want == null && pos == null)) {
            return out;
        }
        for (Path f : sidecarFiles(worldDir)) {
            final String rel = relative(worldDir, f);
            final byte[] bytes;
            try {
                if (Files.size(f) > BINARY_MAX_BYTES) {
                    continue;                       // 超过二进制上限：这份旁挂不改（也不猜）
                }
                bytes = Files.readAllBytes(f);
            } catch (Throwable t) {
                continue;
            }
            final String text = decodeText(bytes);
            if (text != null) {
                if (bytes.length > TEXT_MAX_BYTES) {
                    continue;                       // 超过文本上限：不整行改（判据口径 8 MiB）
                }
                final List<Integer> lines = matchingLines(text, want, pos);
                if (!lines.isEmpty()) {
                    out.add(new SidecarHit(f, rel, false, lines, List.of(), matchOf(text, want, pos)));
                }
                continue;
            }
            if (bytes.length > BINARY_MAX_BYTES) {
                continue;
            }
            final ShanhaiSaveEditAuditNbt.Walk w = ShanhaiSaveEditAuditNbt.read(bytes);
            if (!w.ok()) {
                continue;
            }
            final List<String> tags = new ArrayList<>();
            boolean uuidHit = false;
            for (ShanhaiSaveEditAuditNbt.UuidRef u : w.uuids()) {
                if (want != null && want.equals(u.uuid())) {
                    tags.add(u.path());
                    uuidHit = true;
                }
            }
            if (!uuidHit && pos != null) {
                for (ShanhaiSaveEditAuditNbt.TripleRef t : w.triples()) {
                    if (ShanhaiSaveEditAuditUuid.posWithin(t.pos(), pos)) {
                        tags.add(t.path());
                    }
                }
            }
            if (!tags.isEmpty()) {
                out.add(new SidecarHit(f, rel, true, List.of(), tags, uuidHit ? "uuid" : "pos"));
            }
        }
        return out;
    }

    private static String matchOf(String text, String want, double[] pos) {
        if (want != null && text != null) {
            for (String u : ShanhaiSaveEditAuditUuid.findUuids(text)) {
                if (want.equals(u)) {
                    return "uuid";
                }
            }
        }
        return pos == null ? "uuid" : "pos";
    }

    // ================================================================== 坐标索引

    /**
     * 坐标三级索引：把坐标按 0.001 网格散列，查询时看 27 个邻格。
     *
     * <p>容差正好是 0.001，所以"同一个点"最多落在相邻格里；散列冲突只会多给候选，
     * 命中与否仍由 {@link ShanhaiSaveEditAuditUuid#posWithin} 逐分量判定。
     */
    private static final class PosIndex {

        private static final double CELL = 1000.0D;
        private static final double WILD = 1.0e9D;

        private final Map<Long, List<EntityRef>> cells = new HashMap<>();
        private final List<EntityRef> wild = new ArrayList<>();

        void add(EntityRef e) {
            if (e == null) {
                return;
            }
            if (!finite(e.x()) || !finite(e.y()) || !finite(e.z())
                    || Math.abs(e.x()) > WILD || Math.abs(e.y()) > WILD || Math.abs(e.z()) > WILD) {
                this.wild.add(e);
                return;
            }
            this.cells.computeIfAbsent(key(cell(e.x()), cell(e.y()), cell(e.z())),
                    k -> new ArrayList<>()).add(e);
        }

        List<EntityRef> near(double[] p) {
            final List<EntityRef> out = new ArrayList<>();
            if (p == null || p.length < 3 || !finite(p[0]) || !finite(p[1]) || !finite(p[2])) {
                return out;
            }
            final LinkedHashSet<EntityRef> seen = new LinkedHashSet<>();
            final long cx = cell(p[0]);
            final long cy = cell(p[1]);
            final long cz = cell(p[2]);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    for (long dz = -1; dz <= 1; dz++) {
                        final List<EntityRef> bucket = this.cells.get(key(cx + dx, cy + dy, cz + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (EntityRef e : bucket) {
                            if (ShanhaiSaveEditAuditUuid.posWithin(p, e.pos())) {
                                seen.add(e);
                            }
                        }
                    }
                }
            }
            for (EntityRef e : this.wild) {
                if (ShanhaiSaveEditAuditUuid.posWithin(p, e.pos())) {
                    seen.add(e);
                }
            }
            out.addAll(seen);
            return out;
        }

        private static long cell(double v) {
            return (long) Math.floor(v * CELL);
        }

        private static long key(long x, long y, long z) {
            return x * 73856093L ^ y * 19349663L ^ z * 83492791L;
        }

        private static boolean finite(double v) {
            return !Double.isNaN(v) && !Double.isInfinite(v);
        }
    }

    // ================================================================== 小工具

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

    /** 相对世界根的路径（{@code /} 分隔；给日志、面板、以及"按相对路径找文件"用）。 */
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
        return "ShanhaiSaveEditAuditScan[whitelist=" + ROOT_FILES.size() + "+" + VANILLA_DIRS.size() + "]";
    }
}
