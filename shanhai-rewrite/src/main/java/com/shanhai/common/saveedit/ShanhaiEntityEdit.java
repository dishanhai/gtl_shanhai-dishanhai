package com.shanhai.common.saveedit;

import com.google.gson.JsonParser;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 「存档直改 · 第四期」：{@code entities/*.mca} 里的<b>实体列表</b>（列出 / 删除）。
 *
 * <h2>一、格式（用本机真实样本反解出来的，不是猜的）</h2>
 * <pre>
 *   entities/r.&lt;x&gt;.&lt;z&gt;.mca   与 region 完全同布局：8 KiB 头（1024×偏移 + 1024×时间戳）+ 扇区
 *   每个区块（槽位 = (chunkX&amp;31) + (chunkZ&amp;31)*32）是一个<b>无名根 CompoundTag</b>：
 *     DataVersion : Int          （真实样本 3465）
 *     Position    : IntArray[2]  （{@code [chunkX, chunkZ]}，注意<b>不是</b> xPos/zPos）
 *     Entities    : List&lt;CompoundTag&gt;
 *   实体条目 = 标准实体 NBT：{@code id} / {@code UUID}（IntArray[4]）/ {@code Pos}（List&lt;Double&gt;）
 *   / {@code Rotation} / 模组自己塞的键（真实样本里还有 {@code ForgeCaps}、{@code Lifespan}…）
 * </pre>
 * 取证：{@code temp\saveedit-probe} 的 {@code DumpEntityChunk} 直接解真实
 * {@code entities/r.-1.-1.mca}（2 个区块 / 各 1 个 {@code minecraft:item}）。
 *
 * <h2>二、🔴 只删目标实体，其余实体<b>原样保留</b></h2>
 * 本类<b>从不</b> {@code EntityType.create(...)} 再 {@code save(...)} —— 那会经一遍实体工厂与
 * 默认值，把模组塞的额外键洗掉。删除的做法是：
 * <ol>
 *   <li>读 {@code Entities} 这个 {@link ListTag}；</li>
 *   <li>把要删的那几条<b>摘掉</b>，其余条目<b>连对象都不换</b>（同一个 {@link CompoundTag} 引用）；</li>
 *   <li>把摘好的 list 放回<b>原 chunk 的 copy</b> 里，别的键（{@code Position}/{@code DataVersion}/
 *       以后可能加的键）一个都不碰。</li>
 * </ol>
 * ⇒ 判据 c10 的"同区块其它实体逐字段等价"在 NBT 层可验证（{@link #verifyDelete}）。
 *
 * <h2>三、🔴 其它区块的<b>压缩字节</b>一个都不动</h2>
 * 打包仍走 {@link ShanhaiRegionFile#withChunk}：clone 原文件，只覆盖被删到的那几个槽位。
 * 于是没被删到的 1000+ 个区块，其<b>压缩后字节的 sha256</b> 与写前逐槽相同（判据 c10 的第三条）。
 * 负对照：把这一步换成"全部解压再压"（{@link ShanhaiRegionFile#decompressAllThenRecompress}）
 * ⇒ 那条判据立刻变红。
 *
 * <h2>四、🔴 玩家实体不许动</h2>
 * {@code minecraft:player} 一律跳过，并在结果里如实报"跳过了几条"。真实存档的 {@code entities/}
 * 里不该有玩家（玩家在 {@code playerdata/}），但模组/异常存档可能有；宁可不动，也不许把玩家删掉。
 *
 * <h2>五、本类<b>不</b>判窗口</h2>
 * 与一~三期同一条红线：调用方必须先过 {@link ShanhaiSaveEditFlow#tryApply(String)}
 * （生产路径 = {@link ShanhaiSaveEditWindow} ← {@code ShanhaiSaveEditReload#applyOnIo}）。
 *
 * <h2>六、第五期：<b>只填维度</b>就能列 / 读 / 写 / 删（区坐标由本类自己扫出来）</h2>
 * 用户的诉求原话：「实体我就是要修改」「读取实体的数据不需要填 x/y/z，只需要维度」
 * 「可以点进去改」「允许搜索」。⇒ 新增四个公开入口（写法都是「世界目录 + 维度 + UUID」）：
 * <ol>
 *   <li>{@link #listDimension}：扫该维度 {@code entities/*.mca} 全部区块，按
 *       {@code id / uuid / CustomName} 子串过滤，<b>默认折叠"视觉残留"实体</b>；</li>
 *   <li>{@link #read}：一条实体的 <b>10 项</b>可改字段读数（键不存在就报"不存在"，<b>不</b>用默认值伪造）；</li>
 *   <li>{@link #writeFields}：只写 {@link Patch} 里给了值的项；</li>
 *   <li>{@link #deleteByUuid}：<b>只按 UUID</b> 删一条（不做"按类型无上限整批删"）。</li>
 * </ol>
 *
 * <h2>七、🔴 六条硬约束怎么落的（每条都能被离线判据打红）</h2>
 * <table border="1">
 *   <caption>硬约束 → 落点</caption>
 *   <tr><th>约束</th><th>落点</th></tr>
 *   <tr><td>{@code Pos} 必须与实体所在区块一致</td>
 *       <td>{@link #writeFields} → {@code applyPatch}：x 必须在
 *           {@code [chunkX*16, chunkX*16+16)}、z 同理，越界<b>直接抛</b>，一个字节都不写</td></tr>
 *   <tr><td>{@code Health} 的 NBT 宽度随类型不同（实测同存档 short 505 / float 947）</td>
 *       <td>{@code applyPatch} 按<b>现有 tag 的 {@code getId()}</b> 决定写 {@link ShortTag} 还是
 *           {@link FloatTag}；{@code Age} 不在 10 项里 ⇒ 本类<b>不写</b>；
 *           {@link #verifyFieldWrite} 要求写后类型与写前<b>逐位相同</b></td></tr>
 *   <tr><td>{@code Rotation} 是 {@code List<Float>[2]}、{@code Pos}/{@code Motion} 是 {@code List<Double>[3]}</td>
 *       <td>{@code applyPatch} 只用这一种形状写；{@link #verifyFieldWrite} 写后复查形状</td></tr>
 *   <tr><td>12 个键绝对不许动</td><td>{@link #PROTECTED}；{@link #verifyFieldWrite} 把 10 项<b>全部摘掉</b>
 *       之后逐字段比剩下的，任何一处不同就算失败</td></tr>
 *   <tr><td>写盘只能走 {@code writeAtomic}；只重写目标 chunk</td>
 *       <td>四个入口都只调 {@link ShanhaiSaveEditStorage#writeAtomic}；打包只用
 *           {@link ShanhaiRegionFile#withChunk}，写后用
 *           {@link #compareUntouchedChunks} 断言"其它区块压缩字节 sha256 逐槽相同"</td></tr>
 *   <tr><td>键名里可能带点号（实测有 {@code ..._DATA_COLOUR.X} 这种）</td>
 *       <td>全部用 {@link CompoundTag#get(String)} / {@code put(String,Tag)} 按<b>整串键名</b>取写，
 *           本类<b>没有</b>任何"按 {@code .} 切路径"的代码</td></tr>
 * </table>
 */
public final class ShanhaiEntityEdit {

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 实体列表的键名（真实样本证实）。 */
    public static final String ENTITIES = "Entities";
    /** 区块坐标键名（真实样本证实，IntArray[2] = [chunkX, chunkZ]）。 */
    public static final String POSITION = "Position";
    /** 数据版本键名（真实样本证实）。 */
    public static final String DATA_VERSION = "DataVersion";
    /** 实体的类型键名。 */
    public static final String ID = "id";
    /** 实体的 UUID 键名（IntArray[4]）。 */
    public static final String UUID_KEY = "UUID";
    /** 1.16 之前的旧 UUID 键（只用于"认得出来"，不写）。 */
    public static final String UUID_MOST = "UUIDMost";
    public static final String UUID_LEAST = "UUIDLeast";
    /** 实体位置键名。 */
    public static final String POS = "Pos";

    /** 玩家实体：一律跳过。 */
    public static final String PLAYER_TYPE = "minecraft:player";

    private ShanhaiEntityEdit() {}

    // ================================================================== 请求

    /**
     * 一次删除请求：<b>按类型</b>或<b>按 UUID</b>，二选一。
     *
     * @param dimension 维度（{@code minecraft:overworld} / {@code the_nether} / {@code the_end} / {@code ns:path}）
     * @param regionX   区坐标 X（文件 {@code r.<regionX>.<regionZ>.mca} 里的那个数，<b>不是</b>方块坐标）
     * @param regionZ   区坐标 Z
     * @param typeId    按类型删（如 {@code minecraft:item}）
     * @param uuid      按 UUID 删（标准 {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}）
     * @param anchor    🆕 2026-11-20：暂存时记下的退化匹配锚点（{@code null} = 没有，只能按 UUID 严格匹配）。
     *                  用途见 {@link Anchor}：停服写盘把 UUID 换了之后，按「同区块 ＋ 同 id ＋ 同 Pos」兜底。
     */
    public record Request(String dimension, int regionX, int regionZ, String typeId, String uuid,
                          Anchor anchor) {

        /**
         * 旧签名（没有锚点）—— 保留它，让既有调用点一个字都不用改。
         *
         * <p>⚠️ 走这个构造器的请求<b>只能</b>严格按 UUID 匹配（退化匹配需要锚点，没有就退不了）。
         */
        public Request(String dimension, int regionX, int regionZ, String typeId, String uuid) {
            this(dimension, regionX, regionZ, typeId, uuid, null);
        }

        /** 有没有指定类型。 */
        public boolean hasType() {
            return this.typeId != null && !this.typeId.trim().isEmpty();
        }

        /** 有没有指定 UUID。 */
        public boolean hasUuid() {
            return this.uuid != null && !this.uuid.trim().isEmpty();
        }

        /** 什么都没指定 ⇒ 这一页不参与。 */
        public boolean isEmpty() {
            return !hasType() && !hasUuid();
        }

        /** 归一化后的维度（不认识 ⇒ {@code null}）。 */
        public String dim() {
            return ShanhaiSaveEditDimension.normalize(this.dimension);
        }

        /** 一行读数。 */
        public String line() {
            return "dim=" + dim() + " region=(" + this.regionX + "," + this.regionZ + ")"
                    + " by=" + (hasUuid() ? "uuid:" + normalizedUuid() : "type:" + normalizeType(this.typeId));
        }

        /** UUID 归一化成小写标准形态（只做形状校验，非法 ⇒ 原样返回小写）。 */
        public String normalizedUuid() {
            return normalizeUuidInput(this.uuid);
        }

        /**
         * 校验（面板暂存前 + 落盘前各一次）。
         *
         * @throws IllegalArgumentException 说清楚哪里不对（中文；面板直接回显）
         */
        public void validate() {
            if (dim() == null) {
                throw new IllegalArgumentException("维度写法不认识：" + this.dimension);
            }
            if (!hasType() && !hasUuid()) {
                throw new IllegalArgumentException("要么填实体类型、要么填 UUID（两个都空 = 不删）");
            }
            if (hasType() && hasUuid()) {
                throw new IllegalArgumentException("只能按一种删：实体类型 或 UUID（现在两个都填了）");
            }
            if (hasType() && !isTypeId(normalizeType(this.typeId))) {
                throw new IllegalArgumentException("实体类型要写成 namespace:path（例如 minecraft:item），现在是「"
                        + this.typeId + "」");
            }
            if (hasUuid() && parseUuid(this.uuid) == null) {
                throw new IllegalArgumentException("UUID 要写成 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx，现在是「"
                        + this.uuid + "」");
            }
        }
    }

    /** 一条实体读数。 */
    public record Entry(int slot, int chunkX, int chunkZ, int index, String type, String uuid,
                        double x, double y, double z) {
        public String line() {
            return "chunk(" + this.chunkX + "," + this.chunkZ + ") #" + this.index
                    + " " + this.type + " @" + round(this.x) + "," + round(this.y) + "," + round(this.z)
                    + " uuid=" + this.uuid;
        }
    }

    /** 一次"列出"的读数。 */
    public record Listing(boolean ok, String error, Path path, int chunks, int total,
                          int playersSkipped, Map<String, Integer> byType, List<Entry> entries,
                          boolean truncated) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " file=" + (this.path == null ? "-" : this.path.getFileName())
                    + " chunks=" + this.chunks + " entities=" + this.total
                    + " types=" + (this.byType == null ? 0 : this.byType.size())
                    + " players_skipped=" + this.playersSkipped
                    + (this.truncated ? " (entries_truncated)" : "");
        }

        /** 面板明细的若干行（第 0 行是概况，后面是实体；最多 {@code max} 行）。 */
        public List<String> lines(int max) {
            final List<String> out = new ArrayList<>();
            if (!this.ok) {
                out.add("§c" + (this.error == null ? "列不出来" : this.error));
                return out;
            }
            out.add("§7区块 " + this.chunks + " 个 · 实体 " + this.total + " 个 · 类型 "
                    + (this.byType == null ? 0 : this.byType.size()) + " 种"
                    + (this.playersSkipped > 0 ? " · 玩家实体跳过 " + this.playersSkipped : ""));
            if (this.total == 0) {
                out.add("§8这个区域里没有实体（实体只在区块被写过之后才落盘）");
                return out;
            }
            int i = 0;
            for (Entry e : this.entries) {
                if (out.size() >= Math.max(1, max)) {
                    out.add("§8…还有 " + (this.total - i) + " 个没显示");
                    break;
                }
                out.add(shortType(e.type()) + " §8#" + e.index() + " §7" + e.uuid()
                        + " §8@" + round(e.x()) + "," + round(e.y()) + "," + round(e.z()));
                i++;
            }
            return out;
        }
    }

    /** 一次删除的结果。 */
    public record Result(boolean ok, String error, List<String> changed, List<String> notes,
                         ShanhaiSaveEditStorage.WriteResult write, String chunkLine) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " changed=" + this.changed
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + " chunks=[" + (this.chunkLine == null ? "" : this.chunkLine) + "]"
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    // ================================================================== 列出

    /** 列出某维度某区里的实体（<b>只读</b>；不建文件、不建目录）。 */
    public static Listing list(Path worldDir, String dimension, int regionX, int regionZ) {
        final Path file = ShanhaiSaveEditDimension.entitiesFile(worldDir, dimension, regionX, regionZ);
        if (file == null) {
            return new Listing(false, "维度写法不认识：" + dimension, null, 0, 0, 0, Map.of(), List.of(), false);
        }
        return listFile(file, regionX, regionZ, 400);
    }

    /** 列出一个 {@code entities/*.mca}（{@code regionX/regionZ} 只用于缺 {@code Position} 时兜底）。 */
    public static Listing listFile(Path file, int regionX, int regionZ, int maxEntries) {
        if (file == null || !Files.isRegularFile(file)) {
            return new Listing(false, "这个区还没有实体文件（区块没被写过就不会有）："
                    + (file == null ? "-" : file.getFileName()), file, 0, 0, 0, Map.of(), List.of(), false);
        }
        final ShanhaiRegionFile.Parse parse;
        try {
            parse = ShanhaiRegionFile.parse(Files.readAllBytes(file));
        } catch (Throwable t) {
            return new Listing(false, "读实体文件失败：" + t, file, 0, 0, 0, Map.of(), List.of(), false);
        }
        if (!parse.problems().isEmpty()) {
            return new Listing(false, "实体文件有 " + parse.problems().size() + " 处结构问题（不动它）："
                    + parse.problems().get(0), file, 0, 0, 0, Map.of(), List.of(), false);
        }
        final Map<String, Integer> byType = new TreeMap<>();
        final List<Entry> entries = new ArrayList<>();
        int total = 0;
        int players = 0;
        boolean truncated = false;
        for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
            final ShanhaiRegionFile.Chunk chunk = parse.chunk(slot);
            if (chunk == null) {
                continue;
            }
            final CompoundTag tag;
            try {
                tag = ShanhaiRegionEdit.readChunk(chunk);
            } catch (Throwable t) {
                return new Listing(false, "槽位 " + slot + " 解不开：" + t, file, 0, 0, 0, Map.of(), List.of(), false);
            }
            final int[] cxz = chunkCoords(tag, slot, regionX, regionZ);
            final ListTag list = tag.getList(ENTITIES, 10);
            for (int i = 0; i < list.size(); i++) {
                final CompoundTag e = list.getCompound(i);
                total++;
                final String type = entityType(e);
                byType.merge(shortType(type), 1, Integer::sum);
                if (PLAYER_TYPE.equals(type)) {
                    players++;
                }
                if (entries.size() < Math.max(0, maxEntries)) {
                    final double[] pos = entityPos(e);
                    entries.add(new Entry(slot, cxz[0], cxz[1], i, type, entityUuidText(e), pos[0], pos[1], pos[2]));
                } else {
                    truncated = true;
                }
            }
        }
        return new Listing(true, "", file, parse.chunkCount(), total, players, byType, entries, truncated);
    }

    // ================================================================== 删除

    /** 按世界目录 + 请求删（会写盘）。 */
    public static Result apply(Path worldDir, Request req, Path backupRoot, String stamp) {
        if (worldDir == null || req == null) {
            return fail("null_arg");
        }
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            return fail(iae.getMessage());
        }
        final Path file = ShanhaiSaveEditDimension.entitiesFile(worldDir, req.dim(),
                req.regionX(), req.regionZ());
        if (file == null) {
            return fail("算不出实体文件路径（维度 " + req.dimension() + "）");
        }
        if (!Files.isRegularFile(file)) {
            return fail("这个区还没有实体文件（区块没被写过就不会有）：" + file);
        }
        return applyToFile(file, req, backupRoot, stamp);
    }

    /** 直接对一个 {@code entities/*.mca} 做删除（离线判据走这条：用副本，不碰真存档）。 */
    public static Result applyToFile(Path file, Request req, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        if (file == null || req == null) {
            return fail("null_arg");
        }
        if (req.isEmpty()) {
            return fail("empty_request（既没给类型也没给 UUID）");
        }
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            return fail(iae.getMessage());
        }
        final boolean byUuid = req.hasUuid();
        final String wantUuid = byUuid ? req.normalizedUuid() : null;
        final String wantType = byUuid ? null : normalizeType(req.typeId());

        // ── ① 读盘 + 解析
        final byte[] beforeFile;
        final ShanhaiRegionFile.Parse beforeParse;
        final Map<Integer, CompoundTag> beforeChunks = new LinkedHashMap<>();
        try {
            beforeFile = Files.readAllBytes(file);
            beforeParse = ShanhaiRegionFile.parse(beforeFile);
            if (!beforeParse.problems().isEmpty()) {
                return fail("实体文件有 " + beforeParse.problems().size() + " 处结构问题（不动它）："
                        + beforeParse.problems().get(0));
            }
            for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
                if (beforeParse.chunk(slot) != null) {
                    beforeChunks.put(slot, ShanhaiRegionEdit.readChunk(beforeParse.chunk(slot)));
                }
            }
        } catch (Throwable t) {
            return fail("读实体文件失败：" + t);
        }
        if (beforeChunks.isEmpty()) {
            return fail("这个实体文件里一个区块都没有 ⇒ 没东西可删");
        }

        // ── ② 找出要删的（玩家实体一律跳过）
        final Map<Integer, List<Integer>> toRemove = new LinkedHashMap<>();  // slot -> 索引（升序）
        final Map<Integer, Set<String>> removedUuids = new LinkedHashMap<>();
        final List<String> skippedPlayers = new ArrayList<>();
        int matched = 0;
        for (Map.Entry<Integer, CompoundTag> en : beforeChunks.entrySet()) {
            final int slot = en.getKey();
            final ListTag list = en.getValue().getList(ENTITIES, 10);
            for (int i = 0; i < list.size(); i++) {
                final CompoundTag e = list.getCompound(i);
                final String type = entityType(e);
                final String uuid = entityUuidText(e);
                final boolean hit = byUuid ? uuid.equalsIgnoreCase(wantUuid) : type.equals(wantType);
                if (!hit) {
                    continue;
                }
                if (PLAYER_TYPE.equals(type)) {
                    skippedPlayers.add(uuid);
                    continue;
                }
                toRemove.computeIfAbsent(slot, k -> new ArrayList<>()).add(i);
                removedUuids.computeIfAbsent(slot, k -> new LinkedHashSet<>()).add(uuid.toUpperCase(Locale.ROOT));
                matched++;
            }
        }
        if (!skippedPlayers.isEmpty()) {
            notes.add("🔴 玩家实体一律不删：跳过 " + skippedPlayers.size() + " 条（" + skippedPlayers.get(0)
                    + (skippedPlayers.size() > 1 ? " …" : "") + "）");
        }
        // ── 🆕 2026-11-20：按 UUID 找不到 ⇒ 两档<b>退化匹配</b>（实机判据见 {@link Anchor}） ──
        //    ① 同区块 ＋ 实体 id 相同 ＋ Pos 三元组<b>逐位</b>全等
        //    ② 同区块 ＋ 实体 id 相同 ＋ 该 id 在该区块<b>只有 1 个</b>（唯一才删；不唯一就放弃）
        //    🔴 "不唯一"一律放弃，绝不随便挑一个删。
        String matchedHow = byUuid ? "uuid" : "type";
        String degradedWhy = "";
        if (matched == 0 && byUuid && req.anchor() != null) {
            final Fallback fb = fallbackMatch(beforeChunks, req);
            degradedWhy = fb.why();
            if (!fb.hits().isEmpty()) {
                matchedHow = fb.how();
                for (FallbackHit h : fb.hits()) {
                    toRemove.computeIfAbsent(h.slot(), k -> new ArrayList<>()).add(h.index());
                    removedUuids.computeIfAbsent(h.slot(), k -> new LinkedHashSet<>())
                            .add(h.uuid().toUpperCase(Locale.ROOT));
                    matched++;
                }
                notes.add("⚠ 按 UUID 找不到 ⇒ 退化匹配命中（matched=" + matchedHow + "）：" + req.anchor().describe()
                        + "；这些实体属于「退出世界时被重写过」的那一类（UUID 变了）");
            } else {
                notes.add("按 UUID 与两档退化判据都没命中：anchor=" + req.anchor().describe()
                        + " reason=" + degradedWhy);
            }
        }
        final String matchedLine = "matched=" + matchedHow;
        if (matched == 0) {
            // 🔴 "一条都没匹配上" = 这一页<b>没什么可删</b>（盘上本来就是这样）⇒ 一个字节都不会写
            //    ⇒ 必须落在 no_effective_change 这一族里，否则重开闸
            //    （ShanhaiSaveEditReopenGate#isNoEffectiveChange）认不出来 ⇒ 世界停服 + 重开，
            //    但盘上什么都没变。判据装置：SaveEditProbe 的判据 ⑮ P2。
            //    🆕 2026-11-20 文案：不再说"世界已经停了、回去重进"，改成诚实版 ——
            //    这次<b>根本没写入</b>，失败原因是在退出世界写盘时那条实体被重写了。
            return fail("no_effective_change：这个区域里没有匹配的实体（"
                    + (byUuid ? "uuid=" + wantUuid : "type=" + wantType)
                    + "；" + matchedLine + (degradedWhy.isEmpty() ? "" : " reason=" + degradedWhy)
                    + "）" + (skippedPlayers.isEmpty() ? "" : "；只有玩家实体匹配，而玩家实体不许删")
                    + "。没删掉：那条实体在你退出世界时被重写了（UUID 变了或已被移除）。"
                    + "请重新打开这个世界、重新列一次再删。存档一个字节都没改。");
        }
        notes.add("匹配到 " + matched + " 条实体（" + matchedLine + "），分布在 " + toRemove.size() + " 个区块里");

        // ── ③ 造出"删完之后"的 chunk（只摘 Entities，别的键一个不碰）
        final Map<Integer, CompoundTag> afterChunks = new LinkedHashMap<>();
        final Map<Integer, byte[]> payloads = new LinkedHashMap<>();
        boolean payloadFailed = false;
        for (Map.Entry<Integer, List<Integer>> en : toRemove.entrySet()) {
            final int slot = en.getKey();
            final CompoundTag before = beforeChunks.get(slot);
            final CompoundTag after = before.copy();
            final ListTag kept = new ListTag();
            final List<Integer> remove = en.getValue();
            final ListTag list = before.getList(ENTITIES, 10);
            for (int i = 0; i < list.size(); i++) {
                if (!remove.contains(i)) {
                    kept.add(list.getCompound(i));      // 🔴 同一个对象，不重建
                }
            }
            after.put(ENTITIES, kept);
            afterChunks.put(slot, after);
            try {
                payloads.put(slot, ShanhaiRegionFile.deflate(ShanhaiRegionEdit.encodeChunk(after)));
            } catch (Throwable t) {
                notes.add("槽位 " + slot + " 重新打包失败：" + t);
                payloadFailed = true;
            }
        }
        // ── ④ 写盘前先在内存里验（不通过 ⇒ 一个字节都不写）
        for (Map.Entry<Integer, CompoundTag> en : afterChunks.entrySet()) {
            final int slot = en.getKey();
            final Verify v = verifyDelete(beforeChunks.get(slot), en.getValue(),
                    removedUuids.getOrDefault(slot, Set.of()));
            if (!v.ok()) {
                return fail("删完的区块没通过回读校验（未写盘）slot=" + slot + "：" + v.line());
            }
            if (payloadFailed) {
                return fail("重新打包失败（未写盘）");
            }
            changed.addAll(v.changes());
        }

        // ── ⑤ 打包：只覆盖被删到的那几个槽位，其它区块的压缩字节原样搬
        byte[] newFile;
        try {
            newFile = beforeFile;
            long epoch = System.currentTimeMillis() / 1000L;
            for (Map.Entry<Integer, byte[]> en : payloads.entrySet()) {
                final ShanhaiRegionFile.Parse cur = ShanhaiRegionFile.parse(newFile);
                newFile = ShanhaiRegionFile.withChunk(cur, en.getKey(), en.getValue(), epoch, notes);
            }
            final ShanhaiRegionFile.Table table = ShanhaiRegionFile.validate(newFile);
            if (!table.ok()) {
                return fail("新文件的偏移表/长度不自洽（未写盘）：" + table.line());
            }
        } catch (Throwable t) {
            return fail("重新打包实体文件失败（未写盘）：" + t);
        }

        // ── ⑥ 备份 + 原子写
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(file, newFile, backupRoot, stamp);
        if (!write.ok()) {
            return new Result(false, write.error(), List.of(), notes, write, "");
        }

        // ── ⑦ 从盘上回读，逐条验
        try {
            final byte[] disk = Files.readAllBytes(file);
            final ShanhaiRegionFile.Parse afterParse = ShanhaiRegionFile.parse(disk);
            final ShanhaiRegionFile.Table diskTable = ShanhaiRegionFile.validate(disk);
            if (!diskTable.ok()) {
                restore(write, file);
                return fail("写完之后的表不自洽 ⇒ 已用备份盖回：" + diskTable.line());
            }
            for (Map.Entry<Integer, CompoundTag> en : afterChunks.entrySet()) {
                final int slot = en.getKey();
                final ShanhaiRegionFile.Chunk onDisk = afterParse.chunk(slot);
                if (onDisk == null) {
                    restore(write, file);
                    return fail("回读出问题了：槽位 " + slot + " 读不出来 ⇒ 已用备份盖回");
                }
                final CompoundTag diskTag = ShanhaiRegionEdit.readChunk(onDisk);
                final Verify v = verifyDelete(beforeChunks.get(slot), diskTag,
                        removedUuids.getOrDefault(slot, Set.of()));
                if (!v.ok()) {
                    restore(write, file);
                    return fail("回读不一致 slot=" + slot + "：" + v.line() + " ⇒ 已用备份盖回");
                }
            }
            // 没被删到的区块：压缩字节 sha256 必须逐槽相同
            final ChunkDiff others = compareUntouchedChunks(beforeParse, afterParse, toRemove.keySet());
            if (!others.ok()) {
                restore(write, file);
                return fail("其它区块被动过了 ⇒ 已用备份盖回：" + others.problems());
            }
            notes.add("其它 " + others.compared() + " 个区块的压缩字节 sha256 全部与写前相同");
            notes.add("文件 " + disk.length + " 字节；区块 " + afterParse.chunkCount() + " 个");
            return new Result(true, "", changed, notes, write, others.line() + " " + matchedLine);
        } catch (Throwable t) {
            restore(write, file);
            return fail("回读阶段抛了（已用备份盖回）：" + t);
        }
    }

    /** 回读失败时用备份盖回去（尽力而为）。 */
    private static void restore(ShanhaiSaveEditStorage.WriteResult write, Path target) {
        ShanhaiSaveEditStorage.restore(write == null ? null : write.backupPath(), target);
    }

    // ═══════════════════════ 🆕 2026-11-20：退化匹配（UUID 变了之后的唯一兜底） ═══════════════════════

    /** 退化匹配的一处结果（{@code slot} ＋ {@code Entities} 下标 ＋ 那条记录自己的身份）。 */
    private record FallbackHit(int slot, int index, String id, String uuid) {}

    /**
     * 一次退化匹配的读数。
     *
     * @param how  {@code pos} ／ {@code type_unique} ／ {@code none}
     * @param hits 命中的那几条（{@code how=none} 时为空）
     * @param why  没命中时的原因码（{@code none} 时空串）
     */
    private record Fallback(String how, List<FallbackHit> hits, String why) {}

    /**
     * 🔴 <b>按 UUID 找不到时的两档退化匹配</b>（用户在 2026-11-20 点名的判据）。
     *
     * <pre>
     *   ① 同区块 ＋ 实体 id 相同 ＋ Pos 三元组【逐位】全等           ⇒ matched=pos
     *   ② 同区块 ＋ 实体 id 相同 ＋ 该 id 在该区块【只有 1 个】      ⇒ matched=type_unique
     *   都不成立 ⇒ 放弃（reason=…），一个字节都不写
     * </pre>
     *
     * <p>🔴 "不唯一"一律放弃：那说明同区块里还有同类的第二只，随便挑一个删就是删错东西。
     *
     * <p>"同区块"怎么定：锚点里存的是暂存那一刻的 {@code Pos} 原值，区块坐标由它算出来
     * （{@code floor(x) >> 4}）—— 不另存一份区块号，免得两个来源对不上。
     */
    private static Fallback fallbackMatch(Map<Integer, CompoundTag> chunks, Request req) {
        final Anchor anchor = req.anchor();
        if (anchor == null) {
            return new Fallback("none", List.of(), "no_anchor");
        }
        final String wantType = normalizeType(anchor.typeId());
        if (!isTypeId(wantType)) {
            return new Fallback("none", List.of(), "anchor_type_unusable");
        }
        final int wantChunkX = ((int) Math.floor(anchor.x())) >> 4;
        final int wantChunkZ = ((int) Math.floor(anchor.z())) >> 4;
        final List<FallbackHit> byPos = new ArrayList<>();
        final List<FallbackHit> byType = new ArrayList<>();
        for (Map.Entry<Integer, CompoundTag> en : chunks.entrySet()) {
            final int slot = en.getKey();
            final CompoundTag tag = en.getValue();
            final int[] cxz = chunkCoords(tag, slot, req.regionX(), req.regionZ());
            if (cxz[0] != wantChunkX || cxz[1] != wantChunkZ) {
                continue;   // 🔴 只在【同区块】里退化：跨区块的同类撞车一律不碰
            }
            final ListTag list = tag.getList(ENTITIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                final CompoundTag e = list.getCompound(i);
                final String type = entityType(e);
                if (!wantType.equals(normalizeType(type)) || PLAYER_TYPE.equals(normalizeType(type))) {
                    continue;   // 类型不同、或者是玩家 ⇒ 不参与
                }
                final FallbackHit h = new FallbackHit(slot, i, type, entityUuidText(e));
                byType.add(h);
                final double[] p = entityPos(e);
                if (bitEqual(p[0], anchor.x()) && bitEqual(p[1], anchor.y()) && bitEqual(p[2], anchor.z())) {
                    byPos.add(h);
                }
            }
        }
        if (byPos.size() == 1) {
            return new Fallback("pos", byPos, "");
        }
        if (byPos.size() > 1) {
            return new Fallback("none", List.of(), "pos_ambiguous=" + byPos.size());
        }
        if (byType.size() == 1) {
            return new Fallback("type_unique", byType, "");
        }
        if (byType.isEmpty()) {
            return new Fallback("none", List.of(), "no_same_chunk_type_match");
        }
        return new Fallback("none", List.of(), "type_not_unique=" + byType.size());
    }

    /**
     * "double 逐位相等"（任务书写死的判据）。
     *
     * <p>用 {@link Double#doubleToLongBits} 而不是 {@code ==}：{@code -0.0 == 0.0} 为真但位不同，
     * 而这里要的是<b>逐位</b>；NaN 的两个形态也一并按位比（同一位型才算相等）。
     */
    private static boolean bitEqual(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    // ================================================================== 校验（判据的尺子）

    /** 一次"删除是否正确"的读数。 */
    public record Verify(boolean ok, List<String> problems, List<String> changes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.changes.isEmpty() ? "" : " changes=" + this.changes)
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems);
        }
    }

    /**
     * 🔴 <b>判据 c10 的核心尺子</b>：删完之后
     * <ol>
     *   <li>目标 UUID <b>不在了</b>；</li>
     *   <li>同区块其它实体<b>逐字段等价</b>（顺序也一致）；</li>
     *   <li>chunk 的其它键（{@code Position}/{@code DataVersion}/未来说不准多出来的键）逐条等价；</li>
     *   <li>{@code Entities} 只少了 {@code removed.size()} 条。</li>
     * </ol>
     */
    public static Verify verifyDelete(CompoundTag before, CompoundTag after, Set<String> removed) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (before == null || after == null) {
            return new Verify(false, List.of("chunk_null"), List.of());
        }
        final ListTag lb = before.getList(ENTITIES, 10);
        final ListTag la = after.getList(ENTITIES, 10);
        final Set<String> want = new LinkedHashSet<>();
        for (String s : removed == null ? Set.<String>of() : removed) {
            want.add(s == null ? "" : s.toUpperCase(Locale.ROOT));
        }
        // ① 目标不在了
        for (int i = 0; i < la.size(); i++) {
            final String uuid = entityUuidText(la.getCompound(i)).toUpperCase(Locale.ROOT);
            if (want.contains(uuid)) {
                problems.add("目标实体还在（uuid=" + uuid + "）");
            }
        }
        // ② 其余实体逐字段等价（按"过滤掉被删的之后"的顺序比）
        final List<CompoundTag> expected = new ArrayList<>();
        for (int i = 0; i < lb.size(); i++) {
            final CompoundTag e = lb.getCompound(i);
            if (!want.contains(entityUuidText(e).toUpperCase(Locale.ROOT))) {
                expected.add(e);
            }
        }
        if (expected.size() != la.size()) {
            problems.add("实体条数不对（期望 " + expected.size() + " 实际 " + la.size() + "）");
        }
        final int n = Math.min(expected.size(), la.size());
        for (int i = 0; i < n; i++) {
            final List<String> d = ShanhaiPlayerDataEditor.diffTags(expected.get(i), la.getCompound(i));
            if (!d.isEmpty()) {
                problems.add("第 " + i + " 条实体被改动了：" + d.subList(0, Math.min(3, d.size())));
            }
        }
        // ③ 其它键逐条等价（把 Entities 摘掉再比）
        final CompoundTag b = before.copy();
        final CompoundTag a = after.copy();
        b.remove(ENTITIES);
        a.remove(ENTITIES);
        final List<String> other = ShanhaiPlayerDataEditor.diffTags(b, a);
        if (!other.isEmpty()) {
            problems.add("Entities 以外的键被改动了：" + other.subList(0, Math.min(3, other.size())));
        }
        if (lb.size() - la.size() != want.size()) {
            problems.add("少掉的条数 " + (lb.size() - la.size()) + " ≠ 目标条数 " + want.size());
        }
        changes.add("Entities " + lb.size() + " -> " + la.size());
        return new Verify(problems.isEmpty(), problems, changes);
    }

    /** 两个解析结果里"除 {@code touched} 以外"的区块比对（与 {@link ShanhaiRegionEdit.ChunkDiff} 同口径）。 */
    public record ChunkDiff(boolean ok, int compared, List<String> problems) {
        public String line() {
            return "other_chunks=" + this.compared + " unchanged=" + this.ok
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems);
        }
    }

    /** 逐槽比对"没被删到的区块"：两边都要么都没有，要么压缩字节 sha256 相同（+ 表项 + 时间戳不变）。 */
    public static ChunkDiff compareUntouchedChunks(ShanhaiRegionFile.Parse before,
                                                   ShanhaiRegionFile.Parse after, Set<Integer> touched) {
        if (before == null || after == null) {
            return new ChunkDiff(false, 0, List.of("parse_null"));
        }
        final List<String> problems = new ArrayList<>();
        int bad = 0;
        int compared = 0;
        for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
            if (touched != null && touched.contains(slot)) {
                continue;
            }
            final ShanhaiRegionFile.Chunk a = before.chunk(slot);
            final ShanhaiRegionFile.Chunk b = after.chunk(slot);
            compared++;
            if (a == null && b == null) {
                continue;
            }
            final List<String> here = new ArrayList<>();
            if (a == null || b == null) {
                here.add("slot=" + slot + " 存在性变了");
            } else {
                final String sa = before.payloadSha(slot);
                final String sb = after.payloadSha(slot);
                if (sa == null || !sa.equals(sb)) {
                    here.add("slot=" + slot + " 压缩字节变了");
                }
                if (a.offsetSector() != b.offsetSector() || a.sectorCount() != b.sectorCount()) {
                    here.add("slot=" + slot + " 偏移表变了");
                }
                if (a.timestamp() != b.timestamp()) {
                    here.add("slot=" + slot + " 时间戳变了");
                }
            }
            for (String h : here) {
                bad++;
                if (problems.size() < 8) {
                    problems.add(h);
                }
            }
        }
        if (bad > problems.size()) {
            problems.add("…还有 " + (bad - problems.size()) + " 处不同");
        }
        return new ChunkDiff(bad == 0, compared, problems);
    }

    // ================================================================== 实体小工具

    /** 实体的类型 id（没有就是 {@code "?"}）。 */
    public static String entityType(CompoundTag entity) {
        if (entity == null) {
            return "?";
        }
        final String id = entity.getString(ID);
        return id == null || id.isEmpty() ? "?" : id;
    }

    /** 实体类型归一化（{@code item} ⇒ {@code minecraft:item}）。 */
    public static String normalizeType(String type) {
        final String s = type == null ? "" : type.trim();
        if (s.isEmpty()) {
            return "";
        }
        return s.indexOf(':') > 0 ? s : "minecraft:" + s;
    }

    /** 像不像一个合法的实体类型 id（只查形状）。 */
    public static boolean isTypeId(String type) {
        return ShanhaiPlayerDataEdit.isResourceLocation(normalizeType(type));
    }

    /** 短类型名（去掉 {@code minecraft:} 前缀，面板/日志用）。 */
    public static String shortType(String type) {
        if (type == null) {
            return "?";
        }
        return type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
    }

    /** 实体的 UUID 文本（认 {@code UUID} 的 IntArray[4]，也认 1.16 之前的 Most/Least）。 */
    public static String entityUuidText(CompoundTag entity) {
        if (entity == null) {
            return "?";
        }
        final Tag t = entity.get(UUID_KEY);
        if (t instanceof IntArrayTag arr && arr.getAsIntArray().length == 4) {
            final int[] v = arr.getAsIntArray();
            return new UUID(((long) v[0] << 32) | (v[1] & 0xFFFFFFFFL),
                    ((long) v[2] << 32) | (v[3] & 0xFFFFFFFFL)).toString();
        }
        if (entity.contains(UUID_MOST, 4) && entity.contains(UUID_LEAST, 4)) {
            return new UUID(entity.getLong(UUID_MOST), entity.getLong(UUID_LEAST)).toString();
        }
        return "?";
    }

    /** UUID 输入归一化（能解出来就小写标准形态；解不出来原样返回小写）。 */
    public static String normalizeUuidInput(String raw) {
        final UUID u = parseUuid(raw);
        return u == null ? (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) : u.toString();
    }

    /** 解析 UUID（非法 ⇒ {@code null}）。也认 Minecraft 的 {@code [I;a,b,c,d]} 写法。 */
    public static UUID parseUuid(String raw) {
        final String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (s.startsWith("[") && s.endsWith("]")) {
                final int semi = s.indexOf(';');
                final String body = semi >= 0 ? s.substring(semi + 1, s.length() - 1) : s.substring(1, s.length() - 1);
                final String[] parts = body.split(",");
                if (parts.length == 4) {
                    final long hi = ((long) Integer.parseInt(parts[0].trim()) << 32)
                            | (Integer.parseInt(parts[1].trim()) & 0xFFFFFFFFL);
                    final long lo = ((long) Integer.parseInt(parts[2].trim()) << 32)
                            | (Integer.parseInt(parts[3].trim()) & 0xFFFFFFFFL);
                    return new UUID(hi, lo);
                }
                return null;
            }
            // 允许没有连字符的 32 位写法
            if (s.length() == 32 && s.indexOf('-') < 0) {
                return new UUID(Long.parseUnsignedLong(s.substring(0, 16), 16),
                        Long.parseUnsignedLong(s.substring(16), 16));
            }
            return UUID.fromString(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 实体坐标（读不到 ⇒ 全 0）。 */
    public static double[] entityPos(CompoundTag entity) {
        if (entity == null) {
            return new double[3];
        }
        final ListTag list = entity.getList(POS, 6);
        if (list.size() < 3) {
            return new double[3];
        }
        return new double[] {list.getDouble(0), list.getDouble(1), list.getDouble(2)};
    }

    /** 区块坐标：优先读 {@code Position}（IntArray[2]），没有就按槽位 + 区坐标推。 */
    public static int[] chunkCoords(CompoundTag chunk, int slot, int regionX, int regionZ) {
        if (chunk != null && chunk.contains(POSITION, 11)) {
            final int[] p = chunk.getIntArray(POSITION);
            if (p.length >= 2) {
                return new int[] {p[0], p[1]};
            }
        }
        return new int[] {regionX * ShanhaiRegionFile.REGION_CHUNKS + ShanhaiRegionFile.slotChunkX(slot),
                regionZ * ShanhaiRegionFile.REGION_CHUNKS + ShanhaiRegionFile.slotChunkZ(slot)};
    }

    /** 一块区域按类型计数的读数（面板/日志用）。 */
    public static String countLine(Map<String, Integer> byType) {
        if (byType == null || byType.isEmpty()) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : byType.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append("×").append(e.getValue());
        }
        return sb.toString();
    }

    /** 一行空请求的字段默认值（面板用）。 */
    public static Map<String, String> emptyRequestFields() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("dimension", ShanhaiSaveEditDimension.OVERWORLD);
        return m;
    }

    /** 失败结果（不写盘）。 */
    public static Result fail(String why) {
        return new Result(false, why == null ? "failed" : why, List.of(), List.of(), null, "");
    }

    // ============================================================ 第五期：可写 10 项 / 保护 12 项

    /**
     * <b>可写的 10 项</b>（顺序 = 面板上的顺序；这是用户认可的清单，<b>不再增减</b>）。
     *
     * <p>{@code Item} 一项里含两个子键：{@code Item.id} 与 {@code Item.Count}（掉落物专用）。
     */
    public static final List<String> WRITABLE = List.of(
            "Pos", "Rotation", "Motion", "CustomName", "CustomNameVisible",
            "NoAI", "Invulnerable", "PersistenceRequired", "Health", "Item");

    /**
     * 🔴 <b>一律不动的键</b>（写盘前内存校验 + 写盘后回读校验都会把它们逐个比一遍）。
     *
     * <p>逐条为什么不动：
     * <table border="1">
     *   <caption>受保护键</caption>
     *   <tr><th>键</th><th>不动的理由</th></tr>
     *   <tr><td>{@code UUID}</td><td>身份键。改了 = 变成另一个实体；与别处撞车就是项目自己的
     *       {@code double_registration}（重复登记）那类病，掉落物还会失去合并/去重语义。</td></tr>
     *   <tr><td>{@code id}</td><td>换类型。各类型键集完全不同（掉落物 15 个键 vs 生物 40+ 个），
     *       换完旧的键变垃圾、缺的键取默认值 ⇒ 半残实体。</td></tr>
     *   <tr><td>{@code ForgeCaps}</td><td>模组能力表（实测 100% 实体都有，连掉落物都被塞了
     *       {@code yes_steve_model:vehicle_model_id}）。是模组自己的存档结构，我们只能原样透传。</td></tr>
     *   <tr><td>{@code ForgeData}</td><td>同上（模组私有数据；实测 933 条里 918 条是空 compound）。</td></tr>
     *   <tr><td>{@code Passengers}</td><td>骑手是<b>整条实体 NBT</b>（自带 Pos/Health/Brain/ForgeCaps）。
     *       手写漏键 ⇒ 骑手加载失败；而骑手同时又是"另一个位置的实体"⇒ 一举制造重复登记。
     *       ⇒ 本类不但不写它，<b>还不许绕过它</b>：目标是某条 {@code Passengers} 里的骑手时直接拒绝。</td></tr>
     *   <tr><td>{@code Brain}</td><td>AI 记忆体（{@code memories} = 记忆类型 → 带过期 tick 的数据）。
     *       结构写错 ⇒ 这个 AI 站着不动/永久发呆，而且很难从存档里看出是谁写坏的。</td></tr>
     *   <tr><td>{@code Attributes}</td><td>三元结构（Name + Base + Modifiers[]）：Name 写错 = 整条属性
     *       被静默忽略；Modifiers 的 UUID 重复 = 效果叠加；改了 max_health 之后 Health 不跟着变
     *       ⇒ 血量条越界。本类只<b>读</b>它的 {@code generic.max_health} 当上限，从不写。</td></tr>
     *   <tr><td>{@code HandItems}</td><td>定长 {@code List<Compound>[2]}，空槽是空 compound {@code {}}。
     *       长度/元素类型/空槽写法任一错 = 装备被丢。</td></tr>
     *   <tr><td>{@code ArmorItems}</td><td>同上（{@code [4]}）。</td></tr>
     *   <tr><td>{@code Inventory}</td><td>村民/矿车的物品栏（条目 {@code {Slot,id,Count,tag}}，槽位越界就被丢）。
     *       实体页不开物品栏编辑这条入口。</td></tr>
     *   <tr><td>{@code LootTable}</td><td>战利品表 id。写不存在的表 = 打开箱子空/报错（未实测）；
     *       与 {@code Items} 同时非空时行为未实测。</td></tr>
     *   <tr><td>{@code LootTableSeed}</td><td>与 {@code LootTable} 配套的随机种子，必须同生同灭。</td></tr>
     * </table>
     */
    public static final List<String> PROTECTED = List.of(
            "UUID", "id", "ForgeCaps", "ForgeData", "Passengers", "Brain", "Attributes",
            "HandItems", "ArmorItems", "Inventory", "LootTable", "LootTableSeed");

    /** 骑手列表的键名。 */
    public static final String PASSENGERS = "Passengers";

    /**
     * 「视觉残留」判据的键名后缀：实体 NBT <b>根上</b>存在以它结尾的 int 键、且值 ≤
     * {@link #RESIDUE_TIME_MAX}，并<b>没有</b>任何 {@link #LIVE_KEYS} ⇒ 归为残留。
     *
     * <p>🔴 判据<b>只看键的形状，不认任何模组名</b>：实测样本里这类实体共 3,162 / 4,871 =
     * 64.9%，全部 {@code DATA_TIME=10}（10 tick 后本该消失的爆炸/闪电/冲击波视觉效果）。
     * 它们占满列表且改了没有玩家可见意义，所以默认折叠。
     */
    public static final String RESIDUE_TIME_SUFFIX = "_DATA_TIME";

    /** 计时键的值 ≤ 它才算"快消失了"（实测全部 = 10）。 */
    public static final int RESIDUE_TIME_MAX = 20;

    /** 有这些键 ⇒ 是活物（不是残留）。 */
    public static final List<String> LIVE_KEYS = List.of("Health", "Attributes", "Brain", "ArmorItems");

    /** 实体区文件名规则（与 {@link ShanhaiRegionFile#fileName} 同一条）。 */
    private static final Pattern REGION_FILE_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    /** {@code Pos.y} 的兜底允许范围（本实现<b>不</b>按世界高度校验，只挡明显离谱的值）。 */
    public static final double MIN_Y = -4096.0D;
    public static final double MAX_Y = 4096.0D;

    /**
     * 原版各类型的常规血量上限（{@code Attributes} 里取不到 {@code generic.max_health} 时的回落）。
     *
     * <p>⚠️ 这张表是<b>按原版常识写的，没有逐个从字节码核对</b>（本机存档里只有 8 条实体写了
     * {@code generic.max_health}，其余 939 条都得走回落）⇒ 报告里标成"未实测"。
     * 取不到的一律回落 {@code 20}。
     */
    public static final Map<String, Integer> VANILLA_MAX_HEALTH = buildMaxHealthTable();

    private static Map<String, Integer> buildMaxHealthTable() {
        final Map<String, Integer> m = new LinkedHashMap<>();
        // 掉落物 / 经验球的 Health 是 short，语义是"耐久"（原版 5）
        m.put("minecraft:item", 5);
        m.put("minecraft:experience_orb", 5);
        m.put("minecraft:pig", 10);
        m.put("minecraft:cow", 10);
        m.put("minecraft:mooshroom", 10);
        m.put("minecraft:sheep", 8);
        m.put("minecraft:chicken", 4);
        m.put("minecraft:rabbit", 3);
        m.put("minecraft:wolf", 8);
        m.put("minecraft:cat", 10);
        m.put("minecraft:ocelot", 10);
        m.put("minecraft:fox", 10);
        m.put("minecraft:parrot", 6);
        m.put("minecraft:bat", 6);
        m.put("minecraft:horse", 15);
        m.put("minecraft:donkey", 15);
        m.put("minecraft:mule", 15);
        m.put("minecraft:skeleton_horse", 15);
        m.put("minecraft:zombie_horse", 15);
        m.put("minecraft:llama", 15);
        m.put("minecraft:trader_llama", 15);
        m.put("minecraft:villager", 20);
        m.put("minecraft:wandering_trader", 20);
        m.put("minecraft:zombie", 20);
        m.put("minecraft:husk", 20);
        m.put("minecraft:drowned", 20);
        m.put("minecraft:zombie_villager", 20);
        m.put("minecraft:skeleton", 20);
        m.put("minecraft:stray", 20);
        m.put("minecraft:wither_skeleton", 20);
        m.put("minecraft:creeper", 20);
        m.put("minecraft:spider", 16);
        m.put("minecraft:cave_spider", 12);
        m.put("minecraft:silverfish", 8);
        m.put("minecraft:endermite", 8);
        m.put("minecraft:enderman", 40);
        m.put("minecraft:blaze", 20);
        m.put("minecraft:ghast", 10);
        m.put("minecraft:magma_cube", 16);
        m.put("minecraft:slime", 16);
        m.put("minecraft:squid", 10);
        m.put("minecraft:glow_squid", 10);
        m.put("minecraft:cod", 3);
        m.put("minecraft:salmon", 3);
        m.put("minecraft:tropical_fish", 3);
        m.put("minecraft:pufferfish", 3);
        m.put("minecraft:turtle", 30);
        m.put("minecraft:dolphin", 10);
        m.put("minecraft:axolotl", 14);
        m.put("minecraft:bee", 10);
        m.put("minecraft:goat", 10);
        m.put("minecraft:iron_golem", 100);
        m.put("minecraft:snow_golem", 4);
        m.put("minecraft:polar_bear", 30);
        m.put("minecraft:panda", 20);
        m.put("minecraft:strider", 20);
        m.put("minecraft:hoglin", 40);
        m.put("minecraft:zoglin", 40);
        m.put("minecraft:piglin", 16);
        m.put("minecraft:piglin_brute", 50);
        m.put("minecraft:zombified_piglin", 20);
        m.put("minecraft:guardian", 30);
        m.put("minecraft:elder_guardian", 80);
        m.put("minecraft:shulker", 30);
        m.put("minecraft:phantom", 20);
        m.put("minecraft:pillager", 24);
        m.put("minecraft:vindicator", 24);
        m.put("minecraft:evoker", 24);
        m.put("minecraft:ravager", 100);
        m.put("minecraft:vex", 14);
        m.put("minecraft:illusioner", 32);
        m.put("minecraft:witch", 26);
        m.put("minecraft:allay", 20);
        m.put("minecraft:frog", 10);
        m.put("minecraft:tadpole", 3);
        m.put("minecraft:warden", 500);
        m.put("minecraft:camel", 32);
        m.put("minecraft:sniffer", 14);
        m.put("minecraft:wither", 300);
        m.put("minecraft:ender_dragon", 200);
        return Map.copyOf(m);
    }

    // ---------------------------------------------------------------- 读数记录

    /** 一行实体读数（列表用）。{@code uuid} 就是删除所需的定位信息。 */
    public record Row(String file, int regionX, int regionZ, int slot, int chunkX, int chunkZ, int index,
                      String uuid, String id, double x, double y, double z,
                      String customName, boolean residue, String summary) {
        public String line() {
            return this.id + " uuid=" + this.uuid + " @(" + round(this.x) + "," + round(this.y) + "," + round(this.z) + ")"
                    + " chunk(" + this.chunkX + "," + this.chunkZ + ") #" + this.index
                    + " file=" + this.file + " slot=" + this.slot
                    + (this.residue ? " [residue]" : "")
                    + " summary=" + this.summary;
        }
    }

    /** 一次「按维度列出」的读数。 */
    public record DimensionListing(boolean ok, String error, String dimension, Path entitiesDir,
                                   int files, int chunks, int total, int residueHidden, int matched,
                                   int shown, boolean truncated, boolean includeResidue, String filter,
                                   List<Row> rows, List<String> notes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " dim=" + this.dimension
                    + " files=" + this.files + " chunks=" + this.chunks
                    + " entities=" + this.total + " residue_hidden=" + this.residueHidden
                    + " matched=" + this.matched + " shown=" + this.shown
                    + " filter=" + (this.filter == null || this.filter.isEmpty() ? "-" : this.filter)
                    + " include_residue=" + this.includeResidue
                    + (this.truncated ? " (truncated)" : "")
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes);
        }

        /** 面板明细的若干行（第 0 行是概况）。 */
        public List<String> lines(int max) {
            final List<String> out = new ArrayList<>();
            if (!this.ok) {
                out.add("§c" + (this.error == null ? "列不出来" : this.error));
                return out;
            }
            out.add("§7文件 " + this.files + " 个 · 区块 " + this.chunks + " 个 · 实体 " + this.total
                    + " 个 · 匹配 " + this.matched
                    + (this.residueHidden > 0 ? " · 折叠残留 " + this.residueHidden : ""));
            int i = 0;
            for (Row r : this.rows) {
                if (out.size() >= Math.max(1, max)) {
                    out.add("§8…还有 " + (this.matched - i) + " 个没显示");
                    break;
                }
                out.add("§f" + r.summary() + " §8" + r.uuid());
                i++;
            }
            return out;
        }
    }

    /**
     * 一处命中（定位结果；{@code path} 是落盘文件，{@code index} 是 {@code Entities} 里的下标）。
     *
     * <p>🆕 2026-11-20：多了 {@code x/y/z}（实体 NBT 里 {@code Pos} 的三个 double 原值）——
     * 落盘阶段按 UUID 找不到时要退化匹配，"同区块 ＋ id 相同 ＋ Pos 三位全等"就是第一档判据
     * （见 {@link Anchor}）。这里存的是<b>读盘时的原值</b>，不做四舍五入。
     */
    public record Hit(String file, Path path, int regionX, int regionZ, int slot, int chunkX, int chunkZ,
                      int index, String id, String uuid, double x, double y, double z) {
        public String line() {
            return this.id + " uuid=" + this.uuid + " file=" + this.file + " slot=" + this.slot
                    + " chunk(" + this.chunkX + "," + this.chunkZ + ") #" + this.index
                    + " pos=(" + this.x + "," + this.y + "," + this.z + ")";
        }
    }

    /**
     * 🆕 2026-11-20：暂存时记下的<b>退化匹配锚点</b>（UUID 在停服写盘后变了的唯一兜底依据）。
     *
     * <h2>为什么需要它（实机判据）</h2>
     * <pre>
     *   20:23:47 panel_entity_open … uuid=073667a6-… file=r.-1.0.mca slot=157 chunk(-3,4) #0
     *   20:23:48 panel_entity_delete_staged … by=uuid:073667a6-…
     *   20:24:07 （用户退出世界 ⇒ 服务器停 ⇒ 内存实体写回 region）
     *   20:24:09 apply_failed entity_page_failed:no_effective_change（uuid 已经不存在）
     * </pre>
     * ⇒ 那一类实体是"重启会复活"型，停服写盘时<b>换了 UUID</b>。没有了 UUID，唯一还能认的是
     * 「<b>同一个区块 ＋ 同一个实体 id ＋ 同一个 Pos</b>」。三者都是暂存那一刻从盘上读到的原值。
     *
     * @param typeId 暂存时读到的实体 id（如 {@code minecraft:item}）
     * @param x      {@code Pos[0]} 原值
     * @param y      {@code Pos[1]} 原值
     * @param z      {@code Pos[2]} 原值
     */
    public record Anchor(String typeId, double x, double y, double z) {
        public String describe() {
            return "type=" + normalizeType(this.typeId) + " pos=(" + this.x + "," + this.y + "," + this.z + ")";
        }
    }

    /** 一次「按 UUID 在某个维度里找」的读数。 */
    public record Found(boolean ok, String error, String dimension, int files, List<Hit> hits,
                        List<String> passengers, List<String> notes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " dim=" + this.dimension + " files=" + this.files
                    + " hits=" + this.hits.size() + " passenger_of=" + this.passengers.size();
        }
    }

    /** 10 项里的一项读数。<b>{@code present=false} ⇒ 键不存在，{@code value} 一律 {@code "-"}</b>。 */
    public record Field(String key, String label, boolean present, int tagType, String typeName,
                        String value, String note) {
        public String line() {
            return pad(this.key, 20) + (this.present ? this.value : "不存在")
                    + (this.typeName == null || this.typeName.isEmpty() ? "" : " [" + this.typeName + "]")
                    + (this.note == null || this.note.isEmpty() ? "" : "  // " + this.note);
        }
    }

    /** 一条实体的 10 项读数 + 定位信息。 */
    public record Detail(boolean ok, String error, String dimension, Hit hit, String id, String uuid,
                         boolean residue, double healthMax, String healthMaxSource, boolean posInChunk,
                         List<Field> fields, List<String> notes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + (this.hit == null ? "" : " | " + this.hit.line())
                    + " fields=" + (this.fields == null ? 0 : this.fields.size())
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes);
        }

        /** 按 key 取一项（没有 ⇒ {@code null}）。 */
        public Field field(String key) {
            if (this.fields == null || key == null) {
                return null;
            }
            for (Field f : this.fields) {
                if (key.equals(f.key())) {
                    return f;
                }
            }
            return null;
        }

        /** 面板明细（10 行 + 定位行）。 */
        public List<String> lines() {
            final List<String> out = new ArrayList<>();
            if (!this.ok) {
                out.add("§c" + (this.error == null ? "读不出来" : this.error));
                return out;
            }
            out.add("§7" + this.id + " §8" + this.uuid + " §7" + (this.hit == null ? "" : "chunk("
                    + this.hit.chunkX() + "," + this.hit.chunkZ() + ") #" + this.hit.index()));
            if (this.fields != null) {
                for (Field f : this.fields) {
                    out.add((f.present() ? "§f" : "§8") + f.line());
                }
            }
            if (this.notes != null) {
                for (String n : this.notes) {
                    out.add("§8" + n);
                }
            }
            return out;
        }
    }

    /**
     * 一次「写回字段」的请求：<b>{@code null} = 这一项不动</b>。
     *
     * <p>{@code Item} 一项拆成两个入参（{@code itemId} / {@code itemCount}），各自可以为 {@code null}。
     */
    public record Patch(List<Double> pos, List<Float> rotation, List<Double> motion, String customName,
                        Boolean customNameVisible, Boolean noAi, Boolean invulnerable,
                        Boolean persistenceRequired, Double health, String itemId, Integer itemCount) {

        /** 什么都不改。 */
        public static Patch empty() {
            return new Patch(null, null, null, null, null, null, null, null, null, null, null);
        }

        /** 有没有任何要写的项。 */
        public boolean isEmpty() {
            return keys().isEmpty();
        }

        /** 这次点到了哪几项（按 {@link #WRITABLE} 的顺序）。 */
        public List<String> keys() {
            final List<String> out = new ArrayList<>();
            if (this.pos != null) {
                out.add("Pos");
            }
            if (this.rotation != null) {
                out.add("Rotation");
            }
            if (this.motion != null) {
                out.add("Motion");
            }
            if (this.customName != null) {
                out.add("CustomName");
            }
            if (this.customNameVisible != null) {
                out.add("CustomNameVisible");
            }
            if (this.noAi != null) {
                out.add("NoAI");
            }
            if (this.invulnerable != null) {
                out.add("Invulnerable");
            }
            if (this.persistenceRequired != null) {
                out.add("PersistenceRequired");
            }
            if (this.health != null) {
                out.add("Health");
            }
            if (this.itemId != null || this.itemCount != null) {
                out.add("Item");
            }
            return out;
        }

        /** 这次点到 {@code key} 了吗。 */
        public boolean touches(String key) {
            return key != null && keys().contains(key);
        }
    }

    /** 一次「写字段 / 删一条」的读数（维度就是调用方传进来的那个，不在这里重复回显）。 */
    public record WriteOutcome(boolean ok, String error, String action, String uuid,
                               String id, String file, int chunkX, int chunkZ, int slot, int index,
                               int chunkEntitiesBefore, int chunkEntitiesAfter,
                               List<String> changed, List<String> notes, List<String> dangers,
                               ShanhaiSaveEditStorage.WriteResult write, String chunkLine) {
        public String line() {
            return "action=" + this.action + " ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " " + (this.id == null ? "" : this.id + " ") + "uuid=" + this.uuid
                    + " file=" + this.file + " chunk(" + this.chunkX + "," + this.chunkZ + ") #" + this.index
                    + " chunk_entities=" + this.chunkEntitiesBefore + "->" + this.chunkEntitiesAfter
                    + " changed=" + this.changed
                    + (this.dangers == null || this.dangers.isEmpty() ? "" : " dangers=" + this.dangers)
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + " | chunks=[" + (this.chunkLine == null ? "" : this.chunkLine) + "]"
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    // ============================================================ ① 按维度列实体

    /**
     * 按维度列出实体（<b>只读</b>；不建文件、不建目录、不写一个字节）。
     *
     * @param worldDir       存档根（{@code saves/<世界>}）
     * @param dimension      维度（不填 = 主世界）
     * @param filter         过滤串：大小写不敏感，匹配 {@code id} / uuid / {@code CustomName} 任一子串
     * @param maxEntries     最多返回多少行（≤0 = 不限；硬上限 5000）
     * @param includeResidue {@code true} = 连"视觉残留"一起返回（默认折叠）
     */
    public static DimensionListing listDimension(Path worldDir, String dimension, String filter,
                                                 int maxEntries, boolean includeResidue) {
        final List<String> notes = new ArrayList<>();
        final String dim = ShanhaiSaveEditDimension.normalize(dimension);
        final String flt = filter == null ? "" : filter.trim();
        if (worldDir == null) {
            return new DimensionListing(false, "存档目录是 null（没拿到世界目录，不敢乱扫）", String.valueOf(dimension),
                    null, 0, 0, 0, 0, 0, 0, false, includeResidue, flt, List.of(), notes);
        }
        if (dim == null) {
            return new DimensionListing(false, "维度写法不认识：" + dimension, String.valueOf(dimension),
                    null, 0, 0, 0, 0, 0, 0, false, includeResidue, flt, List.of(), notes);
        }
        final Path dir = ShanhaiSaveEditDimension.entitiesDir(worldDir, dim);
        if (dir == null || !Files.isDirectory(dir)) {
            notes.add("这个维度还没有 entities 目录（区块没被写过就不会有）：" + dir);
            return new DimensionListing(true, "", dim, dir, 0, 0, 0, 0, 0, 0, false, includeResidue, flt,
                    List.of(), notes);
        }
        final List<Path> files = entityFiles(dir, notes);
        final List<Row> rows = new ArrayList<>();
        final int limit = maxEntries <= 0 ? Integer.MAX_VALUE : Math.min(5000, maxEntries);
        int chunks = 0;
        int total = 0;
        int residueHidden = 0;
        for (Path file : files) {
            final ShanhaiRegionFile.Parse parse;
            try {
                parse = ShanhaiRegionFile.parse(Files.readAllBytes(file));
            } catch (Throwable t) {
                notes.add(file.getFileName() + " 读不了（跳过）：" + t);
                continue;
            }
            if (!parse.problems().isEmpty()) {
                notes.add(file.getFileName() + " 有 " + parse.problems().size() + " 处结构问题（跳过，不动它）："
                        + parse.problems().get(0));
                continue;
            }
            final int[] rz = regionOf(file);
            for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
                final ShanhaiRegionFile.Chunk chunk = parse.chunk(slot);
                if (chunk == null) {
                    continue;
                }
                final CompoundTag tag;
                try {
                    tag = ShanhaiRegionEdit.readChunk(chunk);
                } catch (Throwable t) {
                    notes.add(file.getFileName() + " 槽位 " + slot + " 解不开（跳过）：" + t);
                    continue;
                }
                chunks++;
                final int[] cxz = chunkCoords(tag, slot, rz[0], rz[1]);
                final ListTag list = tag.getList(ENTITIES, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    final CompoundTag e = list.getCompound(i);
                    total++;
                    final boolean residue = isVisualResidue(e);
                    if (residue && !includeResidue) {
                        residueHidden++;
                        continue;
                    }
                    final Row row = rowOf(file.getFileName().toString(), rz[0], rz[1], slot,
                            cxz[0], cxz[1], i, e, residue);
                    if (!matches(row, flt)) {
                        continue;
                    }
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparing(Row::id).thenComparing(Row::uuid));
        final boolean truncated = rows.size() > limit;
        final List<Row> shown = truncated ? new ArrayList<>(rows.subList(0, limit)) : rows;
        if (truncated) {
            notes.add("匹配 " + rows.size() + " 条，只返回前 " + limit + " 条（上限可调）");
        }
        return new DimensionListing(true, "", dim, dir, files.size(), chunks, total, residueHidden,
                rows.size(), shown.size(), truncated, includeResidue, flt, shown, notes);
    }

    /** 一行 {@link Row}（不读盘，纯由 {@link CompoundTag} 造）。 */
    public static Row rowOf(String file, int regionX, int regionZ, int slot, int chunkX, int chunkZ,
                            int index, CompoundTag entity, boolean residue) {
        final double[] p = entityPos(entity);
        final String name = displayNameOf(entity.getString("CustomName"));
        final String summary = name != null && !name.isEmpty()
                ? name
                : shortType(entityType(entity)) + " @" + round(p[0]) + "," + round(p[1]) + "," + round(p[2]);
        return new Row(file, regionX, regionZ, slot, chunkX, chunkZ, index, entityUuidText(entity),
                entityType(entity), p[0], p[1], p[2],
                entity.contains("CustomName", Tag.TAG_STRING) ? entity.getString("CustomName") : null,
                residue, summary);
    }

    /** 过滤串匹配（大小写不敏感；uuid 允许省掉连字符）。 */
    public static boolean matches(Row row, String filter) {
        if (row == null) {
            return false;
        }
        final String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (f.isEmpty()) {
            return true;
        }
        if (row.id() != null && row.id().toLowerCase(Locale.ROOT).contains(f)) {
            return true;
        }
        final String u = row.uuid() == null ? "" : row.uuid().toLowerCase(Locale.ROOT);
        if (u.contains(f) || stripDashes(u).contains(stripDashes(f))) {
            return true;
        }
        return row.customName() != null && row.customName().toLowerCase(Locale.ROOT).contains(f);
    }

    /**
     * 🔴 「视觉残留」判据（<b>只按键的形状，不认任何模组名</b>）：
     * 根上存在以 {@link #RESIDUE_TIME_SUFFIX} 结尾的 int 键且值 ≤ {@link #RESIDUE_TIME_MAX}，
     * 且<b>没有</b>任何 {@link #LIVE_KEYS}。
     */
    public static boolean isVisualResidue(CompoundTag entity) {
        if (entity == null) {
            return false;
        }
        boolean timer = false;
        for (String key : entity.getAllKeys()) {
            if (key == null || !key.endsWith(RESIDUE_TIME_SUFFIX)) {
                continue;
            }
            final Tag t = entity.get(key);
            if (t != null && t.getId() == Tag.TAG_INT && entity.getInt(key) <= RESIDUE_TIME_MAX) {
                timer = true;
                break;
            }
        }
        if (!timer) {
            return false;
        }
        for (String live : LIVE_KEYS) {
            if (entity.contains(live)) {
                return false;
            }
        }
        return true;
    }

    /** 把一个 {@code {"text":"…"}} 的 JSON 名字展开成人能读的串（解析不了就原样返回）。 */
    public static String displayNameOf(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            final com.google.gson.JsonElement el = JsonParser.parseString(json);
            if (el.isJsonObject()) {
                final com.google.gson.JsonElement t = el.getAsJsonObject().get("text");
                if (t != null && t.isJsonPrimitive()) {
                    return t.getAsString();
                }
                return json;
            }
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
            return json;
        } catch (Throwable t) {
            return json;
        }
    }

    /** 列出某个 {@code entities} 目录下的实体区文件（按文件名排序；不合名字的记一条 note 后跳过）。 */
    public static List<Path> entityFiles(Path entitiesDir, List<String> notes) {
        final List<Path> out = new ArrayList<>();
        if (entitiesDir == null || !Files.isDirectory(entitiesDir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(entitiesDir)) {
            for (final Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                final Path p = it.next();
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (!REGION_FILE_NAME.matcher(p.getFileName().toString()).matches()) {
                    if (notes != null) {
                        notes.add("不是实体区文件名（跳过）：" + p.getFileName());
                    }
                    continue;
                }
                out.add(p);
            }
        } catch (Throwable t) {
            if (notes != null) {
                notes.add("列实体区目录失败：" + entitiesDir + " / " + t);
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** {@code r.<x>.<z>.mca} → {@code [x, z]}（不合规则 ⇒ {@code [0,0]}，调用方已经筛过）。 */
    public static int[] regionOf(Path file) {
        if (file == null || file.getFileName() == null) {
            return new int[] {0, 0};
        }
        final Matcher m = REGION_FILE_NAME.matcher(file.getFileName().toString());
        if (!m.matches()) {
            return new int[] {0, 0};
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (Throwable t) {
            return new int[] {0, 0};
        }
    }

    private static String stripDashes(String s) {
        return s == null ? "" : s.replace("-", "");
    }

    private static String pad(String s, int width) {
        final StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ============================================================ ② 按 UUID 定位 + ③ 读字段

    /**
     * 在一个维度里按 UUID 找实体（<b>只读</b>）。
     *
     * <p>同时收集"这个 UUID 是<b>谁的骑手</b>"（{@link #PASSENGERS} 里嵌套的实体 NBT）——
     * 骑手不是独立记录，读写删都要先看这一条。
     *
     * <p>🔴 只要有一个实体文件读不了/结构不对就<b>整次拒绝</b>（{@code ok=false}）：宁可不动，
     * 也不许在"看不全"的前提下下"这个 UUID 不存在"的结论（那会导致误删/误写）。
     */
    public static Found locate(Path worldDir, String dimension, String uuid) {
        final List<String> notes = new ArrayList<>();
        final List<Hit> hits = new ArrayList<>();
        final List<String> passengers = new ArrayList<>();
        if (worldDir == null) {
            return new Found(false, "存档目录是 null（没拿到世界目录，不敢乱扫）", String.valueOf(dimension), 0,
                    hits, passengers, notes);
        }
        final String dim = ShanhaiSaveEditDimension.normalize(dimension);
        if (dim == null) {
            return new Found(false, "维度写法不认识：" + dimension, String.valueOf(dimension), 0,
                    hits, passengers, notes);
        }
        final UUID want = parseUuid(uuid);
        if (want == null) {
            return new Found(false, "UUID 要写成 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx，现在是「" + uuid + "」",
                    dim, 0, hits, passengers, notes);
        }
        final String wantText = want.toString();
        final Path dir = ShanhaiSaveEditDimension.entitiesDir(worldDir, dim);
        if (dir == null || !Files.isDirectory(dir)) {
            notes.add("这个维度还没有 entities 目录（区块没被写过就不会有）：" + dir);
            return new Found(true, "", dim, 0, hits, passengers, notes);
        }
        final List<Path> files = entityFiles(dir, notes);
        int scanned = 0;
        for (Path file : files) {
            final ShanhaiRegionFile.Parse parse;
            try {
                parse = ShanhaiRegionFile.parse(Files.readAllBytes(file));
            } catch (Throwable t) {
                return new Found(false, "读实体文件失败 ⇒ 不敢下结论（一个字节都没动）："
                        + file.getFileName() + " / " + t, dim, scanned, hits, passengers, notes);
            }
            if (!parse.problems().isEmpty()) {
                return new Found(false, "实体文件有 " + parse.problems().size()
                        + " 处结构问题 ⇒ 不敢下结论（一个字节都没动）：" + file.getFileName()
                        + " / " + parse.problems().get(0), dim, scanned, hits, passengers, notes);
            }
            scanned++;
            final int[] rz = regionOf(file);
            for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
                final ShanhaiRegionFile.Chunk chunk = parse.chunk(slot);
                if (chunk == null) {
                    continue;
                }
                final CompoundTag tag;
                try {
                    tag = ShanhaiRegionEdit.readChunk(chunk);
                } catch (Throwable t) {
                    return new Found(false, "槽位解不开 ⇒ 不敢下结论（一个字节都没动）："
                            + file.getFileName() + " slot=" + slot + " / " + t,
                            dim, scanned, hits, passengers, notes);
                }
                final int[] cxz = chunkCoords(tag, slot, rz[0], rz[1]);
                final ListTag list = tag.getList(ENTITIES, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    final CompoundTag e = list.getCompound(i);
                    final String here = entityUuidText(e);
                    if (wantText.equalsIgnoreCase(here)) {
                        final double[] p = entityPos(e);
                        hits.add(new Hit(file.getFileName().toString(), file, rz[0], rz[1], slot,
                                cxz[0], cxz[1], i, entityType(e), wantText, p[0], p[1], p[2]));
                    }
                    collectPassenger(e, wantText, entityType(e) + " @chunk(" + cxz[0] + "," + cxz[1]
                            + ") #" + i + " uuid=" + here + " 文件=" + file.getFileName(), passengers, 0);
                }
            }
        }
        return new Found(true, "", dim, scanned, hits, passengers, notes);
    }

    /** 递归收集"这个 UUID 是谁的骑手"（只读；深度上限 4 层，防环）。 */
    private static void collectPassenger(CompoundTag parent, String wantText, String parentDesc,
                                         List<String> out, int depth) {
        if (parent == null || depth > 4) {
            return;
        }
        final ListTag pax = parent.getList(PASSENGERS, Tag.TAG_COMPOUND);
        for (int k = 0; k < pax.size(); k++) {
            final CompoundTag rider = pax.getCompound(k);
            if (wantText.equalsIgnoreCase(entityUuidText(rider))) {
                out.add(parentDesc + "（Passengers 第 " + k + " 个，类型 " + entityType(rider) + "）");
            }
            collectPassenger(rider, wantText, parentDesc, out, depth + 1);
        }
    }

    /** 一条实体的 10 项读数（<b>只读</b>）。 */
    public static Detail read(Path worldDir, String dimension, String uuid) {
        final Found f = locate(worldDir, dimension, uuid);
        if (!f.ok()) {
            return detailFail(f.error());
        }
        if (!f.passengers().isEmpty()) {
            return detailFail("这个 UUID 是一条骑手（在 " + f.passengers().get(0)
                    + " 的 Passengers 里）：它不是独立实体记录，不能单独读/写/删");
        }
        if (f.hits().isEmpty()) {
            return detailFail("not_found：这个维度里没有这个 UUID（实体文件扫了 " + f.files()
                    + " 个；提示：uuid 只在这个维度里找，换维度要重新点一次）");
        }
        if (f.hits().size() > 1) {
            return detailFail("重复登记：同一个 UUID 出现在 " + f.hits().size() + " 个区块里（"
                    + f.hits().get(0).line() + " …）⇒ 拒绝处理，需人工确认");
        }
        final Hit hit = f.hits().get(0);
        final CompoundTag entity;
        try {
            entity = entityAt(hit);
        } catch (Throwable t) {
            return detailFail("读实体失败：" + t);
        }
        return detailOf(f.dimension(), hit, entity);
    }

    /** 把一条实体 NBT 摊成 10 项读数（不读盘；{@code hit} 只用来填定位信息，可为 {@code null}）。 */
    public static Detail detailOf(String dimension, Hit hit, CompoundTag e) {
        final List<Field> fields = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final int cx = hit == null ? Integer.MIN_VALUE : hit.chunkX();
        final int cz = hit == null ? Integer.MIN_VALUE : hit.chunkZ();
        fields.add(fieldPos(e, cx, cz));
        fields.add(fieldRotation(e));
        fields.add(fieldMotion(e));
        fields.add(fieldString(e, "CustomName", "头顶名字（JSON 文本组件）"));
        fields.add(fieldFlag(e, "CustomNameVisible", "名字一直显示"));
        fields.add(fieldFlag(e, "NoAI", "关掉 AI（做成标本）"));
        fields.add(fieldFlag(e, "Invulnerable", "打不死"));
        fields.add(fieldFlag(e, "PersistenceRequired", "不会被刷掉"));
        final MaxHealth max = maxHealthOf(e);
        fields.add(fieldHealth(e, max));
        fields.add(fieldItem(e));
        final boolean inChunk = hit != null && posInChunkOf(e, cx, cz);
        if (hit != null && !inChunk) {
            notes.add("🔴 这条实体现在的 Pos 不在它自己的区块里（数据本身就不自洽）：写 Pos 时会按区块范围校验");
        }
        if (isVisualResidue(e)) {
            notes.add("这条是「视觉残留」实体（根上有以 " + RESIDUE_TIME_SUFFIX
                    + " 结尾、值 ≤ " + RESIDUE_TIME_MAX + " 的 int 键，且没有活物键）");
        }
        return new Detail(true, "", dimension, hit, entityType(e), entityUuidText(e),
                isVisualResidue(e), max.value(), max.source(), inChunk, fields, notes);
    }

    private static Detail detailFail(String why) {
        return new Detail(false, why, null, null, null, null, false, 0.0D, null, false,
                List.of(), List.of());
    }

    /** 读出 {@code hit} 指向的那条实体（属于 {@code Entities} 的那个对象，不是副本）。 */
    public static CompoundTag entityAt(Hit hit) throws java.io.IOException {
        if (hit == null || hit.path() == null) {
            throw new java.io.IOException("hit_null");
        }
        final ShanhaiRegionFile.Parse parse = ShanhaiRegionFile.parse(Files.readAllBytes(hit.path()));
        if (!parse.problems().isEmpty()) {
            throw new java.io.IOException("region_problem: " + parse.problems().get(0));
        }
        final ShanhaiRegionFile.Chunk chunk = parse.chunk(hit.slot());
        if (chunk == null) {
            throw new java.io.IOException("slot_missing: " + hit.slot());
        }
        final CompoundTag tag = ShanhaiRegionEdit.readChunk(chunk);
        final ListTag list = tag.getList(ENTITIES, Tag.TAG_COMPOUND);
        if (hit.index() < 0 || hit.index() >= list.size()) {
            throw new java.io.IOException("index_out_of_range: " + hit.index() + " / " + list.size());
        }
        return list.getCompound(hit.index());
    }

    private static Field fieldPos(CompoundTag e, int chunkX, int chunkZ) {
        final Tag t = e.get("Pos");
        if (t == null) {
            return new Field("Pos", "坐标 x/y/z", false, -1, "", "-", "键不存在 ⇒ 写入时新建（double[3]，形状没有歧义）");
        }
        final ListTag list = e.getList("Pos", Tag.TAG_DOUBLE);
        if (list.size() < 3) {
            return new Field("Pos", "坐标 x/y/z", true, t.getId(), typeName(t.getId()),
                    "-", "形状不对：不是 List<Double>[3]（实际 " + typeName(t.getId()) + "）");
        }
        final double x = list.getDouble(0);
        final double y = list.getDouble(1);
        final double z = list.getDouble(2);
        final String note = chunkX == Integer.MIN_VALUE ? ""
                : (posInChunk(x, z, chunkX, chunkZ)
                        ? "落在区块(" + chunkX + "," + chunkZ + ")内 ✔"
                        : "🔴 不在区块(" + chunkX + "," + chunkZ + ")内：x 允许 ["
                                + (chunkX * 16) + ", " + (chunkX * 16 + 16) + ")、z 允许 ["
                                + (chunkZ * 16) + ", " + (chunkZ * 16 + 16) + ")");
        return new Field("Pos", "坐标 x/y/z", true, t.getId(), "double[3]",
                "x=" + x + " y=" + y + " z=" + z, note);
    }

    private static Field fieldRotation(CompoundTag e) {
        final Tag t = e.get("Rotation");
        if (t == null) {
            return new Field("Rotation", "朝向 yaw/pitch", false, -1, "", "-", "键不存在 ⇒ 写入时新建（float[2]）");
        }
        final ListTag list = e.getList("Rotation", Tag.TAG_FLOAT);
        if (list.size() < 2) {
            return new Field("Rotation", "朝向 yaw/pitch", true, t.getId(), typeName(t.getId()),
                    "-", "形状不对：不是 List<Float>[2]");
        }
        return new Field("Rotation", "朝向 yaw/pitch", true, t.getId(), "float[2]",
                "yaw=" + list.getFloat(0) + " pitch=" + list.getFloat(1), "");
    }

    private static Field fieldMotion(CompoundTag e) {
        final Tag t = e.get("Motion");
        if (t == null) {
            return new Field("Motion", "速度 vx/vy/vz", false, -1, "", "-", "键不存在 ⇒ 写入时新建（double[3]，形状没有歧义）");
        }
        final ListTag list = e.getList("Motion", Tag.TAG_DOUBLE);
        if (list.size() < 3) {
            return new Field("Motion", "速度 vx/vy/vz", true, t.getId(), typeName(t.getId()),
                    "-", "形状不对：不是 List<Double>[3]");
        }
        return new Field("Motion", "速度 vx/vy/vz", true, t.getId(), "double[3]",
                "vx=" + list.getDouble(0) + " vy=" + list.getDouble(1) + " vz=" + list.getDouble(2), "");
    }

    private static Field fieldString(CompoundTag e, String key, String label) {
        final Tag t = e.get(key);
        if (t == null) {
            return new Field(key, label, false, -1, "", "-", "键不存在 ⇒ 写入时新建（string）");
        }
        if (t.getId() != Tag.TAG_STRING) {
            return new Field(key, label, true, t.getId(), typeName(t.getId()),
                    t.getAsString(), "⚠ 类型不是 string");
        }
        final String raw = e.getString(key);
        final String shown = displayNameOf(raw);
        return new Field(key, label, true, t.getId(), "string", raw,
                shown == null || shown.equals(raw) ? "" : "显示名：" + shown);
    }

    private static Field fieldFlag(CompoundTag e, String key, String label) {
        final Tag t = e.get(key);
        if (t == null) {
            return new Field(key, label, false, -1, "", "-", "键不存在 ⇒ 写入时新建（byte 0/1）");
        }
        if (t.getId() != Tag.TAG_BYTE) {
            return new Field(key, label, true, t.getId(), typeName(t.getId()),
                    String.valueOf(e.getInt(key)), "⚠ 类型不是 byte");
        }
        return new Field(key, label, true, t.getId(), "byte",
                (e.getBoolean(key) ? "1（是）" : "0（否）"), "");
    }

    private static Field fieldHealth(CompoundTag e, MaxHealth max) {
        final Tag t = e.get("Health");
        if (t == null) {
            return new Field("Health", "血量", false, -1, "", "-",
                    "键不存在 ⇒ 写入时拒绝（不知道该写 short 还是 float，不猜）");
        }
        final int id = t.getId();
        if (id != Tag.TAG_SHORT && id != Tag.TAG_FLOAT) {
            return new Field("Health", "血量", true, id, typeName(id),
                    String.valueOf(e.getDouble("Health")), "⚠ 既不是 short 也不是 float ⇒ 写入时拒绝");
        }
        final String note = "上限 " + max.value() + "（来源：" + max.source() + "）"
                + (id == Tag.TAG_SHORT ? "；NBT 宽度 = short" : "；NBT 宽度 = float");
        return new Field("Health", "血量", true, id, typeName(id),
                String.valueOf(e.getDouble("Health")), note);
    }

    private static Field fieldItem(CompoundTag e) {
        final Tag t = e.get("Item");
        if (t == null) {
            return new Field("Item", "掉落物（id + Count）", false, -1, "", "-",
                    "键不存在（Item 只用于掉落物 minecraft:item）⇒ 写入时拒绝新建");
        }
        if (!(t instanceof CompoundTag item)) {
            return new Field("Item", "掉落物（id + Count）", true, t.getId(), typeName(t.getId()),
                    "-", "⚠ 不是 compound ⇒ 写入时拒绝");
        }
        final String id = item.contains("id", Tag.TAG_STRING) ? item.getString("id") : "(无 id)";
        final String cnt = item.contains("Count") ? String.valueOf(item.getInt("Count")) : "(无 Count)";
        final String cntType = item.contains("Count") ? typeName(item.get("Count").getId()) : "";
        return new Field("Item", "掉落物（id + Count）", true, t.getId(), "compound",
                "id=" + id + " Count=" + cnt,
                cntType.isEmpty() ? "⚠ Item 里没有 Count" : "Count 的 NBT 宽度 = " + cntType);
    }

    /** 血量上限的读数（值 + 来源）。 */
    public record MaxHealth(double value, String source) {
        public String line() {
            return this.value + " (" + this.source + ")";
        }
    }

    /**
     * 血量上限：优先 {@code Attributes} 里的 {@code minecraft:generic.max_health}.Base；
     * 取不到就按类型查 {@link #VANILLA_MAX_HEALTH}；再取不到回落 {@code 20}。
     */
    public static MaxHealth maxHealthOf(CompoundTag entity) {
        if (entity != null) {
            final Tag attrs = entity.get("Attributes");
            if (attrs instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    final CompoundTag a = list.getCompound(i);
                    if (!"minecraft:generic.max_health".equals(a.getString("Name"))) {
                        continue;
                    }
                    final double base = a.getDouble("Base");
                    if (base > 0.0D && base < 1.0E6D) {
                        return new MaxHealth(base, "Attributes 里的 generic.max_health.Base");
                    }
                }
            }
            final Integer table = VANILLA_MAX_HEALTH.get(normalizeType(entityType(entity)));
            if (table != null) {
                return new MaxHealth(table, "该类型的常规上限（本机没写 max_health 属性 ⇒ 用类型表）");
            }
        }
        return new MaxHealth(20.0D, "取不到 ⇒ 回落原版默认 20");
    }

    /** 这块方块区间的 x/z 是否落在该区块里（左闭右开，与游戏的分块规则一致）。 */
    public static boolean posInChunk(double x, double z, int chunkX, int chunkZ) {
        return x >= chunkX * 16.0D && x < chunkX * 16.0D + 16.0D
                && z >= chunkZ * 16.0D && z < chunkZ * 16.0D + 16.0D;
    }

    private static boolean posInChunkOf(CompoundTag e, int chunkX, int chunkZ) {
        final double[] p = entityPos(e);
        return posInChunk(p[0], p[2], chunkX, chunkZ);
    }

    // ============================================================ ④ 写字段 / ⑤ 删一条

    /**
     * 写回指定字段（只写 {@link Patch} 里给了值的项；<b>会写盘</b>）。
     *
     * <p>🔴 调用方必须先过 {@link ShanhaiSaveEditFlow#tryApply(String)}（本方法不判窗口）。
     * 写盘只走 {@link ShanhaiSaveEditStorage#writeAtomic}（先备份 + 回读校验）。
     */
    public static WriteOutcome writeFields(Path worldDir, String dimension, String uuid, Patch patch,
                                           Path backupRoot, String stamp) {
        if (patch == null || patch.isEmpty()) {
            return failOutcome("write", "empty_patch：这次没有任何要写的项");
        }
        final Found f = locate(worldDir, dimension, uuid);
        if (!f.ok()) {
            return failOutcome("write", f.error());
        }
        if (!f.passengers().isEmpty()) {
            return failOutcome("write", "骑手不能单独写：这个 UUID 在 "
                    + f.passengers().get(0) + " 的 Passengers 里 ⇒ 那不是独立记录，改它要改父实体");
        }
        if (f.hits().isEmpty()) {
            return failOutcome("write", "no_effective_change：这个维度里找不到这个 UUID（实体文件扫了 "
                    + f.files() + " 个 ⇒ 盘上本来就没有，什么都没写）");
        }
        if (f.hits().size() > 1) {
            return failOutcome("write", "重复登记：同一个 UUID 出现在 " + f.hits().size()
                    + " 个区块里 ⇒ 拒绝（不动盘），需人工确认");
        }
        final Hit hit = f.hits().get(0);
        if (PLAYER_TYPE.equals(hit.id())) {
            return failOutcome("write", "玩家记录不在这里改：`playerdata/<uuid>.dat` 才是权威副本"
                    + "（实体区里的玩家记录改了也不生效）");
        }
        return editOne(hit, patch, backupRoot, stamp);
    }

    /**
     * 按维度 + UUID 删<b>一条</b>实体（<b>会写盘</b>）。
     *
     * <p>🔴 只按 UUID，<b>没有</b>"按类型无上限整批删"这个入口（那是危险面）；
     * 骑手不能单独删（要改父实体的 {@code Passengers}）⇒ 直接拒绝并说明是谁的骑手。
     */
    public static WriteOutcome deleteByUuid(Path worldDir, String dimension, String uuid,
                                            Path backupRoot, String stamp) {
        final Found f = locate(worldDir, dimension, uuid);
        if (!f.ok()) {
            return failOutcome("delete", f.error());
        }
        if (!f.passengers().isEmpty()) {
            return failOutcome("delete", "骑手不能单独删：这个 UUID 在 " + f.passengers().get(0)
                    + " 的 Passengers 里 ⇒ 骑手是嵌套在父实体 NBT 里的，删它要改父实体"
                    + "（找不到父实体同样拒绝）⇒ 一个字节都没写");
        }
        if (f.hits().isEmpty()) {
            return failOutcome("delete", "no_effective_change：这个维度里找不到这个 UUID（实体文件扫了 "
                    + f.files() + " 个 ⇒ 盘上本来就没有，什么都没写）");
        }
        if (f.hits().size() > 1) {
            return failOutcome("delete", "重复登记：同一个 UUID 出现在 " + f.hits().size()
                    + " 个区块里 ⇒ 拒绝（只删一处会留下另一半，删两处又超出「按 UUID 删一条」的语义）");
        }
        final Hit hit = f.hits().get(0);
        if (PLAYER_TYPE.equals(hit.id())) {
            return failOutcome("delete", "🔴 玩家实体一律不删（实体区里的玩家记录不是权威副本，"
                    + "删了也不会改变玩家：`playerdata/<uuid>.dat` 才是）");
        }
        return deleteOne(hit, backupRoot, stamp);
    }

    /** 一次读盘的结果（写路径共用）。 */
    private record Loaded(byte[] fileBytes, ShanhaiRegionFile.Parse parse, CompoundTag chunk,
                          ListTag entities, CompoundTag entity) {}

    private static Loaded loadAt(Hit hit) throws java.io.IOException {
        final byte[] bytes = Files.readAllBytes(hit.path());
        final ShanhaiRegionFile.Parse parse = ShanhaiRegionFile.parse(bytes);
        if (!parse.problems().isEmpty()) {
            throw new java.io.IOException("region_problem: " + parse.problems().get(0));
        }
        final ShanhaiRegionFile.Chunk c = parse.chunk(hit.slot());
        if (c == null) {
            throw new java.io.IOException("slot_missing: " + hit.slot());
        }
        final CompoundTag chunk = ShanhaiRegionEdit.readChunk(c);
        final ListTag list = chunk.getList(ENTITIES, Tag.TAG_COMPOUND);
        if (hit.index() < 0 || hit.index() >= list.size()) {
            throw new java.io.IOException("index_out_of_range: " + hit.index() + " / " + list.size());
        }
        final CompoundTag entity = list.getCompound(hit.index());
        if (!hit.uuid().equalsIgnoreCase(entityUuidText(entity))) {
            throw new java.io.IOException("uuid_changed: 盘上这个位置现在是 " + entityUuidText(entity)
                    + "，不是 " + hit.uuid() + "（列表可能过期了，重新列一次）");
        }
        return new Loaded(bytes, parse, chunk, list, entity);
    }

    private static WriteOutcome editOne(Hit hit, Patch patch, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        final List<String> dangers = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        final Loaded l;
        try {
            l = loadAt(hit);
        } catch (Throwable t) {
            return failOutcome("write", "读实体失败（未写盘）：" + t);
        }
        // ① 造出"改完之后"的实体（只碰 patch 点到的那几项）
        final CompoundTag entityAfter = l.entity().copy();
        try {
            applyPatch(entityAfter, patch, hit, changed, notes, dangers);
        } catch (IllegalArgumentException iae) {
            return failOutcome("write", iae.getMessage());
        }
        final List<String> diff = ShanhaiPlayerDataEditor.diffTags(l.entity(), entityAfter);
        if (diff.isEmpty()) {
            return failOutcome("write", "no_effective_change：给的值和盘上现在的一样，没东西可写（"
                    + patch.keys() + "）");
        }
        final Verify v = verifyFieldWrite(l.entity(), entityAfter, patch);
        if (!v.ok()) {
            return failOutcome("write", "写前自检没过（未写盘）：" + v.line());
        }
        notes.addAll(v.changes());
        // ② 造出"改完之后"的区块（只换 Entities 里那一条，其它条目同一个对象）
        final ListTag listAfter = new ListTag();
        for (int i = 0; i < l.entities().size(); i++) {
            listAfter.add(i == hit.index() ? entityAfter : l.entities().getCompound(i));
        }
        final CompoundTag chunkAfter = l.chunk().copy();
        chunkAfter.put(ENTITIES, listAfter);
        return commit(hit, l, chunkAfter, l.entities().size(), listAfter.size(),
                (before, after) -> verifyFieldWrite(entityOf(before, hit.index()),
                        entityOf(after, hit.index()), patch),
                "write", changed, notes, dangers, backupRoot, stamp);
    }

    private static WriteOutcome deleteOne(Hit hit, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        final List<String> dangers = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        final Loaded l;
        try {
            l = loadAt(hit);
        } catch (Throwable t) {
            return failOutcome("delete", "读实体失败（未写盘）：" + t);
        }
        final ListTag listAfter = new ListTag();
        for (int i = 0; i < l.entities().size(); i++) {
            if (i != hit.index()) {
                listAfter.add(l.entities().getCompound(i));   // 🔴 同一个对象，不重建
            }
        }
        final CompoundTag chunkAfter = l.chunk().copy();
        chunkAfter.put(ENTITIES, listAfter);
        final Set<String> removed = Set.of(hit.uuid());
        final Verify v = verifyDelete(l.chunk(), chunkAfter, removed);
        if (!v.ok()) {
            return failOutcome("delete", "删完的区块没通过内存校验（未写盘）：" + v.line());
        }
        changed.addAll(v.changes());
        changed.add("chunk(" + hit.chunkX() + "," + hit.chunkZ() + ") 的实体条数 "
                + l.entities().size() + " -> " + listAfter.size());
        return commit(hit, l, chunkAfter, l.entities().size(), listAfter.size(),
                (before, after) -> verifyDelete(before, after, removed),
                "delete", changed, notes, dangers, backupRoot, stamp);
    }

    /** 打包 → 写盘（{@code writeAtomic}）→ 从盘上回读并逐条验。 */
    private static WriteOutcome commit(Hit hit, Loaded l, CompoundTag chunkAfter,
                                       int countBefore, int countAfter,
                                       java.util.function.BiFunction<CompoundTag, CompoundTag, Verify> verifier,
                                       String action, List<String> changed, List<String> notes,
                                       List<String> dangers, Path backupRoot, String stamp) {
        byte[] payload;
        try {
            payload = ShanhaiRegionFile.deflate(ShanhaiRegionEdit.encodeChunk(chunkAfter));
        } catch (Throwable t) {
            return failOutcome(action, "重新打包失败（未写盘）：" + t);
        }
        byte[] newFile;
        try {
            newFile = ShanhaiRegionFile.withChunk(l.parse(), hit.slot(), payload,
                    System.currentTimeMillis() / 1000L, notes);
            final ShanhaiRegionFile.Table table = ShanhaiRegionFile.validate(newFile);
            if (!table.ok()) {
                return failOutcome(action, "新文件的偏移表/长度不自洽（未写盘）：" + table.line());
            }
        } catch (Throwable t) {
            return failOutcome(action, "重新打包实体文件失败（未写盘）：" + t);
        }
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(hit.path(), newFile, backupRoot, stamp);
        if (!write.ok()) {
            return new WriteOutcome(false, "写盘失败：" + write.error(), action, hit.uuid(),
                    hit.id(), hit.file(), hit.chunkX(), hit.chunkZ(), hit.slot(), hit.index(),
                    countBefore, countAfter, changed, notes, dangers, write, "");
        }
        try {
            final byte[] disk = Files.readAllBytes(hit.path());
            final ShanhaiRegionFile.Parse afterParse = ShanhaiRegionFile.parse(disk);
            final ShanhaiRegionFile.Table diskTable = ShanhaiRegionFile.validate(disk);
            if (!diskTable.ok()) {
                restore(write, hit.path());
                return failOutcome(action, "写完之后的表不自洽 ⇒ 已用备份盖回：" + diskTable.line());
            }
            final ShanhaiRegionFile.Chunk onDisk = afterParse.chunk(hit.slot());
            if (onDisk == null) {
                restore(write, hit.path());
                return failOutcome(action, "回读出问题了：槽位 " + hit.slot() + " 读不出来 ⇒ 已用备份盖回");
            }
            final CompoundTag diskChunk = ShanhaiRegionEdit.readChunk(onDisk);
            final Verify v = verifier.apply(l.chunk(), diskChunk);
            if (!v.ok()) {
                restore(write, hit.path());
                return failOutcome(action, "回读不一致 ⇒ 已用备份盖回：" + v.line());
            }
            final ChunkDiff others = compareUntouchedChunks(l.parse(), afterParse, Set.of(hit.slot()));
            if (!others.ok()) {
                restore(write, hit.path());
                return failOutcome(action, "其它区块被动过了 ⇒ 已用备份盖回：" + others.problems());
            }
            notes.add("其它 " + others.compared() + " 个区块的压缩字节 sha256 全部与写前相同");
            notes.add("文件 " + disk.length + " 字节；区块 " + afterParse.chunkCount() + " 个");
            return new WriteOutcome(true, "", action, hit.uuid(), hit.id(), hit.file(),
                    hit.chunkX(), hit.chunkZ(), hit.slot(), hit.index(), countBefore, countAfter,
                    changed, notes, dangers, write, others.line());
        } catch (Throwable t) {
            restore(write, hit.path());
            return failOutcome(action, "回读阶段抛了（已用备份盖回）：" + t);
        }
    }

    private static CompoundTag entityOf(CompoundTag chunk, int index) {
        if (chunk == null) {
            return null;
        }
        final ListTag list = chunk.getList(ENTITIES, Tag.TAG_COMPOUND);
        return index >= 0 && index < list.size() ? list.getCompound(index) : null;
    }

    private static WriteOutcome failOutcome(String action, String why) {
        return new WriteOutcome(false, why == null ? "failed" : why, action, null, null, null,
                0, 0, -1, -1, 0, 0, List.of(), List.of(), List.of(), null, "");
    }

    // ============================================================ 补丁怎么落到 NBT 上

    /**
     * 把 {@link Patch} 写进 {@code e}（{@code e} 必须是副本，本方法就地改它）。
     *
     * <p>🔴 全部按<b>整串键名</b>取写（{@link CompoundTag#put(String, Tag)}），
     * 没有任何"按 {@code .} 切路径"的写法 —— 实测存档里存在
     * {@code examplemod_Thing_DATA_COLOUR.X} 这种带点号的键名，切了就会碎。
     */
    public static void applyPatch(CompoundTag e, Patch p, Hit hit, List<String> changed,
                                  List<String> notes, List<String> dangers) {
        if (e == null || p == null) {
            throw new IllegalArgumentException("null_patch");
        }
        if (p.pos() != null) {
            requireSize(p.pos(), 3, "Pos");
            final double x = requireFinite(p.pos().get(0), "Pos.x");
            final double y = requireFinite(p.pos().get(1), "Pos.y");
            final double z = requireFinite(p.pos().get(2), "Pos.z");
            if (y < MIN_Y || y > MAX_Y) {
                throw new IllegalArgumentException("Pos.y=" + y + " 超出兜底范围 [" + MIN_Y + ", " + MAX_Y
                        + "]（本实现不按世界高度校验，只挡明显离谱的值）");
            }
            if (hit != null && !posInChunk(x, z, hit.chunkX(), hit.chunkZ())) {
                throw new IllegalArgumentException("Pos 必须与这条实体所在区块一致 ⇒ 拒绝写入（一个字节都没动）。"
                        + "这条实体在区块 (" + hit.chunkX() + "," + hit.chunkZ() + ")：x 允许 ["
                        + (hit.chunkX() * 16) + ", " + (hit.chunkX() * 16 + 16) + ")、z 允许 ["
                        + (hit.chunkZ() * 16) + ", " + (hit.chunkZ() * 16 + 16) + ")，现在给的是 ("
                        + x + ", " + z + ")。跨区块改会让区块根 Position 与实体坐标自相矛盾");
            }
            final String old = oldText(e, "Pos");
            final ListTag list = new ListTag();
            list.add(DoubleTag.valueOf(x));
            list.add(DoubleTag.valueOf(y));
            list.add(DoubleTag.valueOf(z));
            e.put("Pos", list);
            changed.add("Pos " + old + " -> x=" + x + " y=" + y + " z=" + z);
        }
        if (p.rotation() != null) {
            requireSize(p.rotation(), 2, "Rotation");
            final float yaw = (float) requireFinite(p.rotation().get(0), "Rotation.yaw");
            final float pitch = (float) requireFinite(p.rotation().get(1), "Rotation.pitch");
            if (pitch < -90.0F || pitch > 90.0F) {
                throw new IllegalArgumentException("Rotation.pitch 要在 [-90, 90] 之间（游戏就是这么钳的），"
                        + "现在给的是 " + pitch + " ⇒ 拒绝写入（不悄悄改你的值）");
            }
            final String old = oldText(e, "Rotation");
            final ListTag list = new ListTag();
            list.add(FloatTag.valueOf(yaw));
            list.add(FloatTag.valueOf(pitch));
            e.put("Rotation", list);
            changed.add("Rotation " + old + " -> yaw=" + yaw + " pitch=" + pitch);
        }
        if (p.motion() != null) {
            requireSize(p.motion(), 3, "Motion");
            final double vx = requireFinite(p.motion().get(0), "Motion.vx");
            final double vy = requireFinite(p.motion().get(1), "Motion.vy");
            final double vz = requireFinite(p.motion().get(2), "Motion.vz");
            final String old = oldText(e, "Motion");
            final ListTag list = new ListTag();
            list.add(DoubleTag.valueOf(vx));
            list.add(DoubleTag.valueOf(vy));
            list.add(DoubleTag.valueOf(vz));
            e.put("Motion", list);
            changed.add("Motion " + old + " -> vx=" + vx + " vy=" + vy + " vz=" + vz);
        }
        if (p.customName() != null) {
            final String s = p.customName().trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("CustomName 不能写成空串：要去掉名字得删键，而 10 项里只有"
                        + "「写」没有「删」⇒ 拒绝（不替你猜）");
            }
            if (s.length() > 1024) {
                throw new IllegalArgumentException("CustomName 太长了（" + s.length() + " 字符 > 1024）");
            }
            if (!isJsonText(s)) {
                throw new IllegalArgumentException("CustomName 必须是合法 JSON 文本组件（例如 {\"text\":\"名字\"}），"
                        + "现在这个解析不了：" + s);
            }
            final String old = oldText(e, "CustomName");
            e.put("CustomName", StringTag.valueOf(s));
            changed.add("CustomName " + old + " -> " + s);
        }
        putFlag(e, "CustomNameVisible", p.customNameVisible(), changed);
        putFlag(e, "NoAI", p.noAi(), changed);
        putFlag(e, "Invulnerable", p.invulnerable(), changed);
        putFlag(e, "PersistenceRequired", p.persistenceRequired(), changed);
        if (p.health() != null) {
            final Tag t = e.get("Health");
            if (t == null) {
                throw new IllegalArgumentException("这个实体没有 Health 键（本类型是非活物，例如掉落中的方块）"
                        + "⇒ 不知道该写 short 还是 float，拒绝写入（不做默认值）");
            }
            final int id = t.getId();
            if (id != Tag.TAG_SHORT && id != Tag.TAG_FLOAT) {
                throw new IllegalArgumentException("Health 的实际 NBT 类型是 " + typeName(id)
                        + "（既不是 short 也不是 float）⇒ 拒绝写入");
            }
            final MaxHealth max = maxHealthOf(e);
            // 🔴 short 的物理上限是 32767：上限本身大于它时先按类型钳（写错宽度会溢出成负数）
            final double upper = id == Tag.TAG_SHORT ? Math.min(max.value(), 32767.0D) : max.value();
            final double wanted = requireFinite(p.health(), "Health");
            final double clamped = Math.max(0.0D, Math.min(upper, wanted));
            if (clamped != wanted) {
                notes.add("Health 从 " + wanted + " 钳到 " + clamped + "（上限 " + upper + "，来源："
                        + max.source() + "）");
            }
            final String old = oldText(e, "Health");
            if (id == Tag.TAG_SHORT) {
                e.put("Health", ShortTag.valueOf((short) Math.round(clamped)));
            } else {
                e.put("Health", FloatTag.valueOf((float) clamped));
            }
            changed.add("Health " + old + " -> " + clamped + "（" + typeName(id) + "，上限 " + upper + "）");
        }
        if (p.itemId() != null || p.itemCount() != null) {
            final Tag t = e.get("Item");
            if (!(t instanceof CompoundTag item)) {
                throw new IllegalArgumentException("这个实体没有 Item 键（Item 只用于掉落物 minecraft:item）"
                        + "⇒ 拒绝新建");
            }
            if (p.itemId() != null) {
                final String id = normalizeType(p.itemId());
                if (!isTypeId(id)) {
                    throw new IllegalArgumentException("Item.id 要写成 namespace:path（例如 minecraft:beef），"
                            + "现在是「" + p.itemId() + "」");
                }
                final String old = item.contains("id", Tag.TAG_STRING) ? item.getString("id") : "(不存在)";
                item.put("id", StringTag.valueOf(id));
                changed.add("Item.id " + old + " -> " + id);
                // 🔴 不在离线路径上偷偷查注册表就放行：这条危险必须原样交给 UI 标出来
                dangers.add("Item.id 改成了 " + id + "：如果注册表里没有这个物品，这条实体可能被丢弃（未实测）");
            }
            if (p.itemCount() != null) {
                final Tag c = item.get("Count");
                if (c == null) {
                    throw new IllegalArgumentException("Item 里没有 Count 键 ⇒ 不知道该写 byte 还是 int，"
                            + "拒绝新建（不猜宽度）");
                }
                final int cid = c.getId();
                final int upper = switch (cid) {
                    case Tag.TAG_BYTE -> 127;
                    case Tag.TAG_SHORT -> 32767;
                    case Tag.TAG_INT -> Integer.MAX_VALUE;
                    default -> -1;
                };
                if (upper < 0) {
                    throw new IllegalArgumentException("Item.Count 的实际 NBT 类型是 " + typeName(cid)
                            + "（不是 byte/short/int）⇒ 拒绝写入");
                }
                final int n = p.itemCount();
                if (n <= 0) {
                    throw new IllegalArgumentException("Item.Count 必须 ≥ 1（用 Count=0 表示空格是错的："
                            + "空掉落物就是没了这条实体）");
                }
                if (n > upper) {
                    throw new IllegalArgumentException("Item.Count=" + n + " 超出 " + typeName(cid)
                            + " 的范围（≤ " + upper + "）⇒ 拒绝写入（这么写会把数量写成负数）");
                }
                if (n > 64) {
                    dangers.add("Item.Count=" + n + " 超过原版堆叠上限 64：游戏可能钳回或丢弃（未实测）");
                }
                final String old = String.valueOf(item.getInt("Count"));
                if (cid == Tag.TAG_BYTE) {
                    item.put("Count", ByteTag.valueOf((byte) n));
                } else if (cid == Tag.TAG_SHORT) {
                    item.put("Count", ShortTag.valueOf((short) n));
                } else {
                    item.put("Count", IntTag.valueOf(n));
                }
                changed.add("Item.Count " + old + " -> " + n + "（" + typeName(cid) + "）");
            }
        }
    }

    private static void putFlag(CompoundTag e, String key, Boolean value, List<String> changed) {
        if (value == null) {
            return;
        }
        final String old = oldText(e, key);
        e.put(key, ByteTag.valueOf(value));
        changed.add(key + " " + old + " -> " + (value ? "1" : "0"));
    }

    /** 某一项现在的读数（写之前取，只为了在"做了什么"里写清 old -> new）。 */
    public static String oldText(CompoundTag e, String key) {
        if (e == null || key == null) {
            return "(?)";
        }
        final Tag t = e.get(key);
        if (t == null) {
            return "(不存在)";
        }
        switch (key) {
            case "Pos":
            case "Motion":
                return String.valueOf(e.getList(key, Tag.TAG_DOUBLE).getAsString());
            case "Rotation":
                return String.valueOf(e.getList(key, Tag.TAG_FLOAT).getAsString());
            case "Item":
                final Tag it = e.get("Item");
                if (it instanceof CompoundTag ic) {
                    return "id=" + ic.getString("id") + " Count=" + ic.getInt("Count");
                }
                return t.getAsString();
            case "Health":
            case "CustomNameVisible":
            case "NoAI":
            case "Invulnerable":
            case "PersistenceRequired":
                return t.getAsString() + "（" + typeName(t.getId()) + "）";
            default:
                return t.getAsString();
        }
    }

    // ============================================================ 校验尺子（判据）

    /**
     * 🔴 <b>写字段的核心尺子</b>：写完之后
     * <ol>
     *   <li>10 项<b>以外</b>的键逐字段等价（把 10 项全摘掉再比 ⇒ 12 个受保护键自然被覆盖）；</li>
     *   <li>patch <b>没点到</b>的项与写前逐字段等价；</li>
     *   <li>每一项的<b>形状</b>对：{@code Pos}/{@code Motion} = {@code List<Double>[3]}、
     *       {@code Rotation} = {@code List<Float>[2]}、4 个开关 = byte、{@code CustomName} = string；</li>
     *   <li>🔴 {@code Health} / {@code Item.Count} 的 NBT <b>宽度</b>与写前<b>逐位相同</b>
     *       （写前是 short 就还是 short，是 float 就还是 float）；</li>
     *   <li>{@code Item} 里除了 {@code id}/{@code Count} 之外的子键一个不动。</li>
     * </ol>
     */
    public static Verify verifyFieldWrite(CompoundTag before, CompoundTag after, Patch patch) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (before == null || after == null) {
            return new Verify(false, List.of("entity_null"), List.of());
        }
        // ① 10 项以外逐字段等价（摘掉整串键名，不切点号）
        final CompoundTag b = before.copy();
        final CompoundTag a = after.copy();
        for (String k : WRITABLE) {
            b.remove(k);
            a.remove(k);
        }
        final List<String> rest = ShanhaiPlayerDataEditor.diffTags(b, a);
        if (!rest.isEmpty()) {
            problems.add("10 项以外的键被改动了：" + rest.subList(0, Math.min(4, rest.size())));
        }
        // ② patch 没点到的项必须一字不差（逐键整串取，不切点号）
        for (String k : WRITABLE) {
            if (patch != null && patch.touches(k)) {
                continue;
            }
            final List<String> d = diffKey(before, after, k);
            if (!d.isEmpty()) {
                problems.add(k + " 没在 patch 里却被改了：" + d.subList(0, Math.min(3, d.size())));
            }
        }
        // ③④⑤ 形状 + 宽度 + Item 子键
        for (String k : WRITABLE) {
            checkWritableShape(before, after, k, problems);
        }
        for (String k : (patch == null ? List.<String>of() : patch.keys())) {
            changes.add(k + (k.equals("Item") ? "(id/Count)" : "") + " 已写");
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    private static List<String> diffKey(CompoundTag before, CompoundTag after, String key) {
        final CompoundTag x = new CompoundTag();
        final CompoundTag y = new CompoundTag();
        final Tag tb = before.get(key);
        final Tag ta = after.get(key);
        if (tb != null) {
            x.put(key, tb.copy());
        }
        if (ta != null) {
            y.put(key, ta.copy());
        }
        return ShanhaiPlayerDataEditor.diffTags(x, y);
    }

    private static void checkWritableShape(CompoundTag before, CompoundTag after, String key,
                                           List<String> problems) {
        final Tag ta = after.get(key);
        final Tag tb = before.get(key);
        switch (key) {
            case "Pos":
            case "Motion": {
                if (ta == null) {
                    if (tb != null) {
                        problems.add(key + " 被删掉了");
                    }
                    return;
                }
                if (!(ta instanceof ListTag raw) || raw.size() != 3) {
                    problems.add(key + " 形状不对：要 List<Double>[3]，现在是 " + typeName(ta.getId())
                            + " size=" + (ta instanceof ListTag l2 ? l2.size() : -1));
                    return;
                }
                for (int i = 0; i < 3; i++) {
                    if (raw.get(i).getId() != Tag.TAG_DOUBLE) {
                        problems.add(key + "[" + i + "] 的元素不是 double（是 "
                                + typeName(raw.get(i).getId()) + "）");
                    }
                }
                return;
            }
            case "Rotation": {
                if (ta == null) {
                    if (tb != null) {
                        problems.add("Rotation 被删掉了");
                    }
                    return;
                }
                if (!(ta instanceof ListTag raw) || raw.size() != 2) {
                    problems.add("Rotation 形状不对：要 List<Float>[2]，现在是 " + typeName(ta.getId())
                            + " size=" + (ta instanceof ListTag l3 ? l3.size() : -1));
                    return;
                }
                for (int i = 0; i < 2; i++) {
                    if (raw.get(i).getId() != Tag.TAG_FLOAT) {
                        problems.add("Rotation[" + i + "] 的元素不是 float（是 "
                                + typeName(raw.get(i).getId()) + "）");
                    }
                }
                return;
            }
            case "CustomName":
                if (ta != null && ta.getId() != Tag.TAG_STRING) {
                    problems.add("CustomName 要是 string，现在是 " + typeName(ta.getId()));
                }
                return;
            case "CustomNameVisible":
            case "NoAI":
            case "Invulnerable":
            case "PersistenceRequired":
                if (ta != null && ta.getId() != Tag.TAG_BYTE) {
                    problems.add(key + " 要是 byte，现在是 " + typeName(ta.getId()));
                }
                return;
            case "Health": {
                if (tb == null) {
                    if (ta != null) {
                        problems.add("Health 写前不存在却被新建了（不允许：不知道该写 short 还是 float）");
                    }
                    return;
                }
                if (ta == null) {
                    problems.add("Health 被删掉了");
                    return;
                }
                if (tb.getId() != ta.getId()) {
                    problems.add("🔴 Health 的 NBT 宽度变了（" + typeName(tb.getId()) + " -> "
                            + typeName(ta.getId()) + "）：同一条实体必须按原宽度写");
                }
                return;
            }
            case "Item": {
                if (tb == null) {
                    if (ta != null) {
                        problems.add("Item 写前不存在却被新建了（Item 只用于掉落物）");
                    }
                    return;
                }
                if (ta == null) {
                    problems.add("Item 被删掉了");
                    return;
                }
                if (!(tb instanceof CompoundTag cb) || !(ta instanceof CompoundTag ca)) {
                    problems.add("Item 不是 compound");
                    return;
                }
                final Tag cbTag = cb.get("Count");
                final Tag caTag = ca.get("Count");
                if (cbTag != null && caTag == null) {
                    problems.add("Item.Count 被删掉了");
                } else if (cbTag != null && caTag != null && cbTag.getId() != caTag.getId()) {
                    problems.add("🔴 Item.Count 的 NBT 宽度变了（" + typeName(cbTag.getId()) + " -> "
                            + typeName(caTag.getId()) + "）");
                }
                // Item 里除 id / Count 之外的子键一个不动（整串键名取写，不切点号）
                final CompoundTag x = cb.copy();
                final CompoundTag y = ca.copy();
                x.remove("id");
                x.remove("Count");
                y.remove("id");
                y.remove("Count");
                final List<String> extra = ShanhaiPlayerDataEditor.diffTags(x, y);
                if (!extra.isEmpty()) {
                    problems.add("Item 里除 id/Count 以外的子键被改动了："
                            + extra.subList(0, Math.min(3, extra.size())));
                }
                return;
            }
            default:
        }
    }

    private static double requireFinite(Double v) {
        return requireFinite(v, "value");
    }

    private static double requireFinite(Double v, String what) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            throw new IllegalArgumentException(what + " 必须是有限数字（现在给的是 " + v + "）");
        }
        return v;
    }

    private static double requireFinite(Float v, String what) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            throw new IllegalArgumentException(what + " 必须是有限数字（现在给的是 " + v + "）");
        }
        return v.doubleValue();
    }

    private static void requireSize(List<?> list, int size, String what) {
        if (list == null) {
            throw new IllegalArgumentException(what + " 不能是 null");
        }
        if (list.size() != size) {
            throw new IllegalArgumentException(what + " 要给 " + size + " 个数，现在给了 " + list.size() + " 个");
        }
    }

    /** 是不是合法 JSON（文本组件的最小形状判据；只判"能不能解析"，不判语义）。 */
    public static boolean isJsonText(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            JsonParser.parseString(s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // NBT 类型 id → 人能读的名字。
    public static String typeName(int id) {
        return switch (id) {
            case 0 -> "end";
            case 1 -> "byte";
            case 2 -> "short";
            case 3 -> "int";
            case 4 -> "long";
            case 5 -> "float";
            case 6 -> "double";
            case 7 -> "byte[]";
            case 8 -> "string";
            case 9 -> "list";
            case 10 -> "compound";
            case 11 -> "int[]";
            case 12 -> "long[]";
            default -> "tag" + id;
        };
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 10.0D) / 10.0D);
    }
}
