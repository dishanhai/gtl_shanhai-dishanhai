package com.shanhai.common.reality;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 山海重构 · <b>「现实流体」32 个占位流体的唯一一张表 ＋ 唯一一份落盘</b>。
 *
 * <h2>1. 规格（用户拍板，逐条对应）</h2>
 * <pre>
 *   ① 槽位 32，流体 id = {@code shanhai:reality_fluid_1 … _32}（流体本体在
 *      {@code com.shanhai.fluid.ShanhaiRealityFluids} 里【加载期】注册，本类只管"这 32 个壳各自长什么样"）
 *   ② 每个流体可改三项：中文名 / 颜色 tint / still（+ flowing）贴图 —— 三项都<b>热生效</b>
 *   ③ 只做占位：不带桶、不带流体方块、不进 JEI 独立条目、不生成到世界里（第一版）
 *   ④ 数据落 {@code config/shanhai/creative_fluids.json}（与 128 个物品那两份同一个 config 目录）
 *   ⑤ <b>老文件必须还能读</b>：本表是<b>独立的一份</b>，物品表 {@code creative_items.json} 的格式
 *      一个字节都没动（新增文件，不改既有文件）
 * </pre>
 *
 * <h2>2. 🔴 读侧在本类的三个方法上（这就是"热生效"的全部机制）</h2>
 * <ul>
 *   <li>{@link #displayName(int)} —— {@code ShanhaiRealityFluidType#getDescription()} 每次现读；</li>
 *   <li>{@link #tint(int)} —— {@code IClientFluidTypeExtensions#getTintColor()} 每次现读；</li>
 *   <li>{@link #stillTexture(int)} / {@link #flowTexture(int)} —— 同上。</li>
 * </ul>
 * 所有读侧拿到的都是不可变 {@link Entry} 的全新数组快照（改表 = 换一个数组引用，{@code volatile}），
 * 渲染线程不可能读到"改了一半"的表；{@link #version()} 每次成功改表 +1，面板据此刷新格子。
 *
 * <h2>3. 落盘格式（人可读、可手改；与物品表同一套原子写法）</h2>
 * <pre>
 * {
 *   "schema_version": 1,
 *   "generated_by": "shanhai-0.1.0",
 *   "_note": "…",
 *   "updated_at": "2026-11-22T10:39:15Z",
 *   "slots": [
 *     { "slot": 1, "name": "测试流体", "tint": "#FF8800",
 *       "still": "shanhai:block/fluid/matter_fluid_entry",
 *       "flow":  "shanhai:block/fluid/matter_fluid_entry_flow" }
 *   ]
 * }
 * </pre>
 * <ul>
 *   <li>{@code slot} 是 <b>1 基</b>（= 用户看到的槽位号，= id 后缀）；内存里一律 0 基。</li>
 *   <li>{@code tint}：{@code "#RRGGBB"}（alpha 恒 FF）—— 手改最自然，且不会因为十进制整数看不懂而改错。</li>
 *   <li>{@code still}/{@code flow}：<b>贴图路径</b>（不带 {@code textures/} 前缀、不带 {@code .png}），
 *       与 {@code IClientFluidTypeExtensions#getStillTexture()} 同一个形状。</li>
 *   <li><b>只有"动过"的槽位才写进文件</b>（名字非空 / 颜色非纯白 / 贴图非默认水）——
 *       没动过的槽位在文件里不存在，读回来就是默认（水贴图 + 纯白 tint）。</li>
 * </ul>
 *
 * <h2>4. 写盘纪律</h2>
 * 先写同目录 {@code .tmp}，再 {@code ATOMIC_MOVE} 覆盖（与 {@code ShanhaiRealityItemTable} 逐条同款）。
 * 读失败/文件不存在 ⇒ 全部默认 + 如实打日志，<b>绝不静默编一个数</b>。
 */
public final class ShanhaiRealityFluidTable {

    /** 日志前缀（可 grep）。 */
    public static final String PREFIX = "[SHANHAI-REALITY-FLUID]";

    /** 槽位数（用户拍板：32）。 */
    public static final int SLOT_COUNT = 32;

    /** 流体 id 前缀（对外的 id = {@code shanhai:reality_fluid_<n>}，1 基）。 */
    public static final String ID_PREFIX = "reality_fluid_";

    /** 相对【游戏目录】的路径（与 {@code creative_items.json} 同一处，但是<b>另一个文件</b>）。 */
    public static final String RELATIVE_PATH = "config/shanhai/creative_fluids.json";

    public static final int SCHEMA_VERSION = 1;
    public static final String GENERATED_BY = "shanhai-0.1.0";

    /**
     * 名字的<b>可见</b>字符上限（与 128 个物品同一个口径：48 个看得见的字）。
     * 见 {@link ShanhaiNameCaps} —— 这里按码点算，格式码不占位。
     */
    public static final int MAX_NAME_CHARS = 48;
    /** 名字的原始 char 上限（防止"48 可见字 + 一堆码"把网络包/JSON 撑爆）。 */
    public static final int MAX_NAME_RAW_CHARS = 192;

    /** 贴图路径串上限（够长，且防止塞进一整段文本）。 */
    public static final int MAX_TEXTURE_CHARS = 96;

    /** 没选贴图时的默认 still（原版水，任何环境都存在）。 */
    public static final String DEFAULT_STILL = "minecraft:block/water_still";
    /** 没选贴图时的默认 flow。 */
    public static final String DEFAULT_FLOW = "minecraft:block/water_flow";
    /** 没设颜色时的默认 tint（纯白 = 原样显示贴图自己的颜色）。 */
    public static final int DEFAULT_TINT = 0xFFFFFFFF;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /**
     * 一个槽位的定义（不可变）。
     *
     * @param name  显示名（<b>空串</b> = 未命名 ⇒ 显示 id + 标记，见 {@link #displayName(int)}）
     * @param tint  ARGB（alpha 恒 {@code 0xFF}）
     * @param still still 贴图路径（{@code ns:path}，不带 {@code textures/} 与 {@code .png}）
     * @param flow  flowing 贴图路径（同上）
     */
    public record Entry(String name, int tint, String still, String flow) {

        /** 这一格是不是"动过"（动过才写进文件）。 */
        public boolean customized() {
            return (name != null && !name.isBlank())
                    || (tint & 0xFFFFFF) != (DEFAULT_TINT & 0xFFFFFF)
                    || (still != null && !DEFAULT_STILL.equals(still))
                    || (flow != null && !DEFAULT_FLOW.equals(flow));
        }

        /** 一行签名（日志/对账用）。 */
        public String signature() {
            return "name=" + (name == null || name.isBlank() ? "<none>" : '"' + name + '"')
                    + " tint=" + tintHex(tint) + " still=" + still + " flow=" + flow;
        }
    }

    /** 当前整表（长度恒为 {@link #SLOT_COUNT}；元素可为 null）。改表 = 换一个数组引用。 */
    private static volatile Entry[] slots = new Entry[SLOT_COUNT];

    /** 表版本号：每次成功改表 +1（面板据此刷新格子）。 */
    private static volatile int version;

    /** 文件是否已经读过（避免每次取名字都打一次盘）。 */
    private static volatile boolean loaded;

    /**
     * 测试用的数据根目录覆盖（{@code null} = 用真实 {@code GAMEDIR}）。
     *
     * <p>🔴 只为<b>离线探针</b>存在：探针不启动游戏，也不许碰 {@code FMLPaths}。
     * 生产路径永远是 {@code null}，行为与"没有这个字段"逐字节一致。
     */
    private static volatile Path dataRootOverride;

    private ShanhaiRealityFluidTable() {}

    // ------------------------------------------------------------------ 路径 / 时间

    /** {@code <gameDir>/config/shanhai/creative_fluids.json}。 */
    public static Path path() {
        final Path root = dataRootOverride;
        return (root != null ? root : GameDir.ROOT())
                .resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    /**
     * 只给离线探针用的数据根目录（{@code null} = 恢复真实 GAMEDIR）。
     *
     * <p>为什么需要有这个口子：验收要求"经 {@code apply()} + 落盘 + 读回逐字符相同"，
     * 而那条断言不许启动游戏。{@code FMLPaths.GAMEDIR.get()} 在裸 JVM 里不可用，
     * 所以给探针一个显式的根目录。<b>生产代码从不准调用它</b>（开机日志里会印
     * {@code data_root=game} 或 {@code data_root=override} 以便一眼看出）。
     */
    public static void useDataRootForProbe(Path root) {
        dataRootOverride = root;
        loaded = false;
    }

    /** 现在是不是走"探针根目录"（开机日志与自检打印这一行）。 */
    public static String dataRootWord() {
        return dataRootOverride == null ? "game" : "override";
    }

    public static String nowTs() {
        return TS_FMT.format(Instant.now());
    }

    /** 槽位号（1 基）⇒ 注册 id。 */
    public static ResourceLocation fluidId(int index) {
        return new ResourceLocation(ShanhaiMod.MOD_ID, ID_PREFIX + (index + 1));
    }

    // ------------------------------------------------------------------ 读

    /** 惰性读一次（第一次被问到时读盘；之后只走内存）。 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (ShanhaiRealityFluidTable.class) {
            if (loaded) {
                return;
            }
            reloadFromDisk();
            loaded = true;
        }
    }

    /** 强制重读磁盘（命令 / 自检 / 外部手改了文件时用）。 */
    public static void reloadFromDisk() {
        final Path p = path();
        if (!Files.isRegularFile(p)) {
            slots = new Entry[SLOT_COUNT];
            incrementVersion();
            ShanhaiMod.LOGGER.info("{} store_missing path={} -> 空表（32 个流体全部用默认名字/颜色/贴图）",
                    PREFIX, p);
            return;
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            final JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.error("{} store_not_object path={} -> 保留空表（不覆盖用户文件）", PREFIX, p);
                return;
            }
            applyFromJson(el.getAsJsonObject());
            ShanhaiMod.LOGGER.info("{} store_loaded path={} customized={} version={}",
                    PREFIX, p, customizedCount(), version);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} store_read_failed path={} err={} -> 保留空表（不覆盖用户文件）",
                    PREFIX, p, t.toString());
        }
    }

    /** 把一份 root JSON 套进内存（读盘与网络同步共用）。 */
    public static void applyFromJson(JsonObject root) {
        final Entry[] next = new Entry[SLOT_COUNT];
        final JsonElement s = root.get("slots");
        if (s != null && s.isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                final JsonObject o = e.getAsJsonObject();
                final int oneBased = o.has("slot") ? o.get("slot").getAsInt() : 0;
                final int idx = oneBased - 1;
                if (idx < 0 || idx >= SLOT_COUNT) {
                    continue;
                }
                final String name = ShanhaiNameCaps.cap(str(o, "name", MAX_NAME_RAW_CHARS),
                        MAX_NAME_CHARS, MAX_NAME_RAW_CHARS);
                final int tint = parseTint(str(o, "tint", 16));
                final String still = sanitizeTexture(str(o, "still", MAX_TEXTURE_CHARS), DEFAULT_STILL);
                final String flow = sanitizeTexture(str(o, "flow", MAX_TEXTURE_CHARS), still);
                final Entry entry = new Entry(name, tint, still, flow);
                if (entry.customized()) {
                    next[idx] = entry;
                }
            }
        }
        slots = next;
        incrementVersion();
    }

    /** 读一个字符串键（截断 + 去首尾空白；缺键 = 空串）。 */
    private static String str(JsonObject o, String key, int max) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return "";
        }
        String v = o.get(key).getAsString();
        if (v == null) {
            return "";
        }
        v = v.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    // ------------------------------------------------------------------ 查询（内存）

    public static int version() {
        return version;
    }

    private static void incrementVersion() {
        version = version + 1;
    }

    /** 槽位定义（0 基）；没动过返回 {@code null}。 */
    public static Entry entry(int index) {
        ensureLoaded();
        final Entry[] t = slots;
        return index >= 0 && index < SLOT_COUNT ? t[index] : null;
    }

    /** 这一格有没有名字（≠ "流体存不存在" —— 32 个流体永远都注册着）。 */
    public static boolean isNamed(int index) {
        final Entry e = entry(index);
        return e != null && e.name() != null && !e.name().isBlank();
    }

    /** 动过的槽位数。 */
    public static int customizedCount() {
        final Entry[] t = slots;
        int n = 0;
        for (Entry e : t) {
            if (e != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 显示名 —— <b>{@code FluidType#getDescription()} 每次调都走这里</b>（这就是"改名字热生效"）。
     *
     * <p>没填名字 ⇒ <b>显示 id ＋ 标记</b>（用户要求："查不到名字则显示 id 并标记"）。
     */
    public static String displayName(int index) {
        final Entry e = entry(index);
        if (e != null && e.name() != null && !e.name().isBlank()) {
            return e.name();
        }
        return ShanhaiMod.MOD_ID + ":" + ID_PREFIX + (index + 1) + " §7（未命名）";
    }

    /** 名字原文（空串 = 未命名）；面板编辑框用它，<b>不要</b>用 {@link #displayName(int)}。 */
    public static String nameOf(int index) {
        final Entry e = entry(index);
        return e == null || e.name() == null ? "" : e.name();
    }

    /** tint（ARGB；默认纯白 = 不染色）。 */
    public static int tint(int index) {
        final Entry e = entry(index);
        return e == null ? DEFAULT_TINT : e.tint();
    }

    /** still 贴图路径串。 */
    public static String stillOf(int index) {
        final Entry e = entry(index);
        return e == null || e.still() == null ? DEFAULT_STILL : e.still();
    }

    /** flowing 贴图路径串。 */
    public static String flowOf(int index) {
        final Entry e = entry(index);
        return e == null || e.flow() == null ? DEFAULT_FLOW : e.flow();
    }

    /** still 贴图的 {@link ResourceLocation}（渲染侧用；解析不了 ⇒ 默认水）。 */
    public static ResourceLocation stillTexture(int index) {
        return toLocation(stillOf(index), DEFAULT_STILL);
    }

    /** flowing 贴图的 {@link ResourceLocation}（解析不了 ⇒ 默认水流动贴图）。 */
    public static ResourceLocation flowTexture(int index) {
        return toLocation(flowOf(index), DEFAULT_FLOW);
    }

    private static ResourceLocation toLocation(String raw, String fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(raw == null ? "" : raw);
        if (rl == null) {
            rl = ResourceLocation.tryParse(fallback);
        }
        return rl;
    }

    /** {@code "#RRGGBB"} 形式的读数（面板/日志用）。 */
    public static String tintHex(int tint) {
        return String.format("#%06X", tint & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 改一个槽位（唯一入口）。
     *
     * @return true = 内存与磁盘都改成功；false = 参数不合法或写盘失败（此时<b>内存也不动</b>）
     */
    public static boolean apply(int index, String name, String still, String flow, int tint) {
        if (index < 0 || index >= SLOT_COUNT) {
            ShanhaiMod.LOGGER.warn("{} apply_rejected reason=slot_out_of_range idx={}", PREFIX, index);
            return false;
        }
        final String cleanName = ShanhaiNameCaps.cap(name, MAX_NAME_CHARS, MAX_NAME_RAW_CHARS);
        final String cleanStill = sanitizeTexture(still, DEFAULT_STILL);
        final String cleanFlow = sanitizeTexture(flow, cleanStill);
        final Entry next = new Entry(cleanName, normalizeTint(tint), cleanStill, cleanFlow);
        final Entry[] nextTable = slots.clone();
        nextTable[index] = next.customized() ? next : null;   // 完全没动 ⇒ 这一格不写进文件
        if (!writeTable(nextTable)) {
            return false;
        }
        slots = nextTable;
        incrementVersion();
        ShanhaiMod.LOGGER.info("{} apply_ok slot={} id={} name_len={} name_visible={} tint={} still={} "
                        + "flow={} customized={} version={}",
                PREFIX, index + 1, fluidId(index), cleanName.length(),
                ShanhaiNameCaps.visibleLength(cleanName), tintHex(next.tint()),
                cleanStill, cleanFlow, next.customized(), version);
        return true;
    }

    /** 清空一个槽位（= 退回默认：无名字 + 纯白 + 水贴图）。流体本身还在。 */
    public static boolean clear(int index) {
        return apply(index, "", DEFAULT_STILL, DEFAULT_FLOW, DEFAULT_TINT);
    }

    /** 把一份表原子写盘；成功才由调用方提交到内存。 */
    private static boolean writeTable(Entry[] table) {
        final Path target = path();
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("generated_by", GENERATED_BY);
        root.addProperty("_note", NOTE);
        root.addProperty("updated_at", nowTs());
        final JsonArray arr = new JsonArray();
        for (int i = 0; i < SLOT_COUNT; i++) {
            final Entry e = table[i];
            if (e == null || !e.customized()) {
                continue;                       // 没动过的槽位不写进文件
            }
            final JsonObject o = new JsonObject();
            o.addProperty("slot", i + 1);
            o.addProperty("name", e.name() == null ? "" : e.name());
            o.addProperty("tint", tintHex(e.tint()));
            o.addProperty("still", e.still() == null ? DEFAULT_STILL : e.still());
            o.addProperty("flow", e.flow() == null ? DEFAULT_FLOW : e.flow());
            arr.add(o);
        }
        root.add("slots", arr);
        try {
            final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.createDirectories(target.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} store_write_failed path={} err={}", PREFIX, target, t.toString());
            return false;
        }
    }

    /** 文件里的说明（手改这个文件的人看的）。 */
    private static final String NOTE =
            "Runtime placeholder fluids (shanhai:reality_fluid_1..32) maintained by the in-game "
                    + "'reality items' panel / fluid tab. Only slots that were actually customised are "
                    + "listed here. 'tint' is '#RRGGBB' (alpha forced to FF); 'still'/'flow' are texture "
                    + "paths without the 'textures/' prefix and without '.png' - that is exactly the path "
                    + "shape the fluid renderer asks for. Keys are 1-based slot numbers. The fluid, its "
                    + "FluidType and its source/flowing states are registered at load time and are NEVER "
                    + "removed, so existing fluid stacks keep working even if this file is deleted.";

    // ------------------------------------------------------------------ 整表同步（多人）

    /** 整表序列化（网络同步用）。 */
    public static String toJsonString() {
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("generated_by", GENERATED_BY);
        final JsonArray arr = new JsonArray();
        final Entry[] t = slots;
        for (int i = 0; i < SLOT_COUNT; i++) {
            final Entry e = t[i];
            if (e == null || !e.customized()) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("slot", i + 1);
            o.addProperty("name", e.name() == null ? "" : e.name());
            o.addProperty("tint", tintHex(e.tint()));
            o.addProperty("still", e.still() == null ? DEFAULT_STILL : e.still());
            o.addProperty("flow", e.flow() == null ? DEFAULT_FLOW : e.flow());
            arr.add(o);
        }
        root.add("slots", arr);
        return GSON.toJson(root);
    }

    /** 收一份整表（客户端收到 S2C 之后调；<b>不落盘</b>）。 */
    public static void applyFromJsonString(String json) {
        try {
            final JsonElement el = JsonParser.parseString(json);
            if (el != null && el.isJsonObject()) {
                applyFromJson(el.getAsJsonObject());
                loaded = true;
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} sync_apply_failed err={}", PREFIX, t.toString());
        }
    }

    // ------------------------------------------------------------------ 归一

    /**
     * 颜色串 ⇒ ARGB。
     *
     * <p>认：{@code #RRGGBB} / {@code RRGGBB} / {@code #AARRGGBB} / {@code 0xRRGGBB} / 十进制整数。
     * 认不出来 ⇒ {@link #DEFAULT_TINT}（= 不染色），<b>不猜</b>。
     */
    public static int parseTint(String raw) {
        if (raw == null) {
            return DEFAULT_TINT;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return DEFAULT_TINT;
        }
        if (v.startsWith("0x") || v.startsWith("0X")) {
            v = v.substring(2);
        }
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        try {
            final long parsed = Long.parseLong(v, 16);
            if (v.length() == 6) {
                return normalizeTint((int) (0xFF000000L | parsed));
            }
            if (v.length() == 8) {
                return normalizeTint((int) parsed);
            }
            if (v.length() <= 6) {
                return normalizeTint((int) (0xFF000000L | parsed));
            }
        } catch (NumberFormatException ignored) {
            // 落到下面：十进制整数也收（手改文件时可能直接写 4,294,967,295）
        }
        try {
            return normalizeTint((int) Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TINT;
        }
    }

    /** alpha 一律拉满（流体 tint 的半透明没有意义，且会把贴图越叠越淡）。 */
    public static int normalizeTint(int argb) {
        return 0xFF000000 | (argb & 0xFFFFFF);
    }

    /**
     * 贴图路径归一：{@code ns:path}；允许误写成 {@code shanhai:textures/block/x.png} 或
     * 全路径带 {@code .png}（手改文件时最常见）—— 这两种会被<b>修回</b>标准形状。
     * 认不出来 ⇒ {@code fallback}。
     */
    public static String sanitizeTexture(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return fallback;
        }
        if (v.startsWith("textures/")) {
            v = v.substring("textures/".length());
        }
        if (v.endsWith(".png")) {
            v = v.substring(0, v.length() - ".png".length());
        }
        final int colon = v.indexOf(':');
        if (colon > 0) {
            final String ns = v.substring(0, colon);
            String pathPart = v.substring(colon + 1);
            if (pathPart.startsWith("textures/")) {
                pathPart = pathPart.substring("textures/".length());
            }
            if (pathPart.endsWith(".png")) {
                pathPart = pathPart.substring(0, pathPart.length() - ".png".length());
            }
            final ResourceLocation rl = ResourceLocation.tryParse(ns + ":" + pathPart);
            return rl == null ? fallback : rl.toString();
        }
        // 只写了 path（没写命名空间）⇒ 补 minecraft:
        final ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + v);
        return rl == null ? fallback : rl.toString();
    }

    // ------------------------------------------------------------------ 自检 / 读数

    /** 一行读数（开机日志 + 面板标题都用它）。 */
    public static String statsLine() {
        return "path=" + RELATIVE_PATH + " slots=" + SLOT_COUNT + " customized=" + customizedCount()
                + " version=" + version + " data_root=" + dataRootWord();
    }

    /**
     * 自检（开机日志里跑；<b>只读</b>，不写盘、不改真表）。
     *
     * <p>判据：2 条正对照 + 4 条负对照。没有负对照，"全绿"可能只是判据从来不响。
     */
    public static String selfCheck() {
        final StringBuilder bad = new StringBuilder();
        int pass = 0;
        final int total = 6;

        // ① 正对照：默认值（没动过的槽位 = 水贴图 + 纯白）
        final boolean pos1 = DEFAULT_STILL.equals(stillOf(0)) && tint(0) == DEFAULT_TINT
                && displayName(SLOT_COUNT - 1).contains(ID_PREFIX + SLOT_COUNT);
        if (pos1) {
            pass++;
        } else {
            bad.append(" 正对照A失败：未动过的槽位应当回落默认（水贴图 + 纯白 + id 名单）；");
        }
        // ② 正对照：颜色解析（#RRGGBB ⇒ FF 前缀）
        final boolean pos2 = parseTint("#FF8800") == 0xFFFF8800 && parseTint("#ff8800") == 0xFFFF8800
                && tintHex(0xFFFF8800).equals("#FF8800");
        if (pos2) {
            pass++;
        } else {
            bad.append(" 正对照B失败：颜色解析/格式化没对上；");
        }
        // ③ 负对照 A：认不出的颜色 ⇒ 默认纯白（不是 0、不是瞎猜）
        if (parseTint("乱七八糟") == DEFAULT_TINT && parseTint("") == DEFAULT_TINT
                && parseTint(null) == DEFAULT_TINT) {
            pass++;
        } else {
            bad.append(" 负对照A没响（非法颜色没回落到默认）；");
        }
        // ④ 负对照 B：贴图串归一（写错的形状被修回；纯垃圾 ⇒ 回落）
        if ("shanhai:block/fluid/x".equals(sanitizeTexture("shanhai:textures/block/fluid/x.png", DEFAULT_STILL))
                && DEFAULT_STILL.equals(sanitizeTexture("不是一个 id", DEFAULT_STILL))
                && "minecraft:block/water_still".equals(sanitizeTexture("block/water_still", DEFAULT_STILL))) {
            pass++;
        } else {
            bad.append(" 负对照B没响（贴图串归一没生效）；");
        }
        // ⑤ 负对照 C：可见长度口径（§ 与 &$…- 不占可见长度）
        if (ShanhaiNameCaps.visibleLength("§f红色") == 2
                && ShanhaiNameCaps.visibleLength("&$neon-红色") == 2
                && ShanhaiNameCaps.visibleLength("&$neon-红&r色") == 2) {
            pass++;
        } else {
            bad.append(" 负对照C没响（可见长度口径不对）；");
        }
        // ⑥ 负对照 D：槽位号越界 ⇒ 不写、不崩
        if (!apply(-1, "x", DEFAULT_STILL, DEFAULT_FLOW, DEFAULT_TINT)
                && !apply(SLOT_COUNT, "x", DEFAULT_STILL, DEFAULT_FLOW, DEFAULT_TINT)) {
            pass++;
        } else {
            bad.append(" 负对照D没响（越界槽位被接受了）；");
        }

        final String line = "reality_fluid_table_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + "（判据：默认回落／#RRGGBB 解析／非法颜色回落／贴图串归一／可见长度口径／越界拒绝；另 "
                + statsLine() + "）";
        if (bad.length() > 0) {
            ShanhaiMod.LOGGER.error("{} reality_fluid_table_selftest FAILED: {}", PREFIX, bad.toString());
        }
        return line;
    }

    /**
     * 只有这里碰 {@code FMLPaths}：放在<b>独立嵌套类</b>里 ⇒ 离线探针（用
     * {@link #useDataRootForProbe} 设了根目录）<b>永远不会加载它</b>，
     * 于是"不启动游戏也能跑 apply+落盘+读回"这条验收成立。
     */
    private static final class GameDir {

        private GameDir() {}

        static Path ROOT() {
            return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
        }
    }
}
