package com.shanhai.common.itemedit;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>「改写任意已存在物品」的唯一一张表 + 唯一一份落盘</b>。
 *
 * <h2>1. 规格（用户拍板，逐条对应）</h2>
 * <pre>
 *   ① 表 = config/shanhai/item_overrides.json（与 creative_items.json / recipe_*.json 同一处）
 *   ② 一条记录 = 一个物品 id 的<b>覆盖项</b>：显示名 / 提示 / 堆叠上限；各字段可缺省（缺省 = 该项不改）
 *   ③ <b>每次读取现读</b>：内存里放 volatile 整表快照，按文件 <b>mtime + size</b> 变化重载
 *      ⇒ 改完立刻生效（不打 /reload、不重启）
 *   ④ <b>原子写盘</b>：先写同目录 .tmp，再 ATOMIC_MOVE 覆盖（照抄 ShanhaiRealityItemTable 的做法）
 *   ⑤ 坏 JSON ⇒ 打 ERROR、<b>整体忽略</b>（保留上一份好表），绝不半生效
 *   ⑥ 🔴 {@code shanhai:reality_item_*} 这 128 个壳物品<b>跳过覆盖</b>
 *      （它们自己有 RealityShellItem 的动态显示名，两个来源会打架）
 * </pre>
 *
 * <h2>2. 落盘格式</h2>
 * <pre>
 * {
 *   "schema_version": 1,
 *   "generated_by": "shanhai-0.1.0",
 *   "_note": "…",
 *   "updated_at": "2026-…Z",
 *   "entries": [
 *     { "id": "minecraft:apple", "name": "红苹果", "lore": "第一行\n第二行",
 *       "lore_mode": "append", "max_stack": 64, "ts": 1690000000000 }
 *   ]
 * }
 * </pre>
 * <b>只写「设过的」字段</b>：某个字段没设过，它就不会出现在文件里（读回来 = 该项不改）。
 * 这一条决定了「清掉一项」和「改一项」是两种不同的状态，手改文件也不会被兜底值污染。
 *
 * <h2>3. 🔴 为什么表在 common 包、而且"整表快照 + volatile 引用"</h2>
 * <ul>
 *   <li><b>两侧都要读</b>：服务端读它做命令校验，客户端读它渲染名字/tooltip
 *       ⇒ 不能放 {@code client} 包（专服会 {@code NoClassDefFoundError}，本工程老坑）；</li>
 *   <li><b>整表快照</b>：读侧拿到的永远是完整的一份（换引用），渲染线程不可能读到"改了一半"的表；</li>
 *   <li><b>mtime 节流</b>：{@code ItemStack#getMaxStackSize()} 是全游戏最热的路径之一，
 *       所以"查文件变没变"每 {@link #STAT_INTERVAL_MS} ms 最多做一次（一次 {@code stat} 系统调用），
 *       其余调用只是一次 {@code volatile long} 比较 ⇒ 开销可忽略，而人的手感仍是"立刻生效"。</li>
 * </ul>
 *
 * <h2>4. 线程模型</h2>
 * 写侧（命令/网络）与读侧（渲染）可能不同线程 ⇒ 所有写操作 {@code synchronized}，
 * 读侧只读 volatile 引用。快照 Map 是 {@link Collections#unmodifiableMap}，读侧不可能改到它。
 */
public final class ShanhaiItemOverrideTable {

    /** 日志前缀（可 grep）。用户点名要这个串。 */
    public static final String PREFIX = "[SHANHAI-ITEMEDIT]";

    /** 相对【游戏目录】的路径。 */
    public static final String RELATIVE_PATH = "config/shanhai/item_overrides.json";

    public static final int SCHEMA_VERSION = 1;
    public static final String GENERATED_BY = "shanhai-0.1.0";

    /** 名字字符上限（够用，且防止有人把整个 JS 塞进来）。 */
    public static final int MAX_NAME_CHARS = 128;
    /** 提示总字符上限。 */
    public static final int MAX_LORE_CHARS = 2048;

    /** 堆叠上下限（与 ShanhaiRealityItemTable 同一口径：1 … int 上限）。 */
    public static final int MIN_STACK = 1;
    public static final int MAX_STACK = Integer.MAX_VALUE;

    /** {@code lore_mode} 的两个合法值。 */
    public static final String MODE_APPEND = "append";
    public static final String MODE_REPLACE = "replace";

    /** 查文件变没变的最小间隔（毫秒）。见类注释 §3。 */
    public static final long STAT_INTERVAL_MS = 200L;

    /** 🔴 被这 128 个壳物品占用的路径前缀（它们跳过覆盖）。 */
    private static final String RESERVED_NS = "shanhai";
    private static final String RESERVED_PATH_PREFIX = "reality_item_";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String NOTE =
            "Runtime item overrides maintained by /shanhai item ... Every field is optional: a field that is "
                    + "absent means 'leave that aspect untouched'. 'lore' is a multi-line string; a line "
                    + "starting with [s] shows only while Shift is held, [c] shows only while Ctrl is held. "
                    + "'lore_mode' = append (default, adds lines below the vanilla tooltip) or replace (drops "
                    + "the item's own description lines, keeps the name line). 'ts' is the last edit time "
                    + "(epoch millis). The shanhai:reality_item_* shells are intentionally NOT overridable "
                    + "(they already have their own dynamic name source).";

    /**
     * 一条覆盖项（<b>不可变</b>）。
     *
     * @param id      物品 id（规范形式 {@code ns:path}）
     * @param name    显示名；{@code null}/空 = 不改名
     * @param lore    提示（已归一成真实换行）；{@code null}/空 = 不改提示
     * @param loreMode {@code append} / {@code replace}
     * @param maxStack 堆叠上限；{@code 0} = 不改
     * @param ts      最后修改时间（epoch millis）
     */
    public record Entry(String id, String name, String lore, String loreMode, int maxStack, long ts,
                        boolean hidden, boolean removed) {

        /** 兼容旧调用点（hidden/removed 缺省 false）。 */
        public Entry(String id, String name, String lore, String loreMode, int maxStack, long ts) {
            this(id, name, lore, loreMode, maxStack, ts, false, false);
        }

        /** 🔴 {@code removed=true} 时 {@code hidden} 视为 true（用户规格：藏 + 抹实例 + 摘配方产出）。 */
        public boolean hiddenEffective() {
            return hidden || removed;
        }

        public boolean hasName() {
            return name != null && !name.isBlank();
        }

        public boolean hasLore() {
            return lore != null && !lore.isBlank();
        }

        public boolean hasStack() {
            return maxStack > 0;
        }

        public boolean replaceMode() {
            return MODE_REPLACE.equals(loreMode);
        }

        /** 这份覆盖项到底改了点什么（全空 = 不该出现在表里）。🆕 hidden/removed 也算改动。 */
        public boolean any() {
            return hasName() || hasLore() || hasStack() || hidden || removed;
        }

        /** 一行签名（日志/对账用）。 */
        public String signature() {
            return "name=" + (hasName() ? '"' + name + '"' : "<none>")
                    + " lore=" + (hasLore() ? ('"' + oneLine(lore) + '"') : "<none>")
                    + " mode=" + loreMode
                    + " stack=" + (hasStack() ? Integer.toString(maxStack) : "<none>")
                    + " hidden=" + hidden
                    + " removed=" + removed
                    + " ts=" + ts;
        }

        private static String oneLine(String s) {
            return s == null ? "" : s.replace("\n", "\\n");
        }
    }

    /** 只含 id 的极简查询结果（读侧热路径用，不含其它开销）。 */
    private static volatile Map<ResourceLocation, Entry> byId = Collections.emptyMap();

    /** 文件 mtime（-1 = 文件不存在/没读过）。 */
    private static volatile long loadedMtime = -1L;
    /** 文件 size（-1 = 文件不存在/没读过）。 */
    private static volatile long loadedSize = -1L;
    /** 下一次允许 stat 的时刻（{@link System#currentTimeMillis()}）。 */
    private static volatile long nextStatAt;

    /** 是否已经尝试过第一次加载（开机日志只需要一次）。 */
    private static volatile boolean firstLoadDone;

    private ShanhaiItemOverrideTable() {}

    // ------------------------------------------------------------------ 路径与时间

    /** {@code <gameDir>/config/shanhai/item_overrides.json}。 */
    public static Path path() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    public static String nowTs() {
        return TS_FMT.format(Instant.now());
    }

    // ------------------------------------------------------------------ 读

    /**
     * 热路径入口：**最多** {@link #STAT_INTERVAL_MS} ms 做一次 {@code stat}，
     * 文件变了就重载。{@code ItemStack} 那四条拦截全都先过这里。
     */
    public static void refresh() {
        final long now = System.currentTimeMillis();
        if (now < nextStatAt) {
            return;
        }
        nextStatAt = now + STAT_INTERVAL_MS;
        statAndMaybeReload();
    }

    /** 强制对齐磁盘（命令改表前用：不吃节流，避免把别人手改的内容覆盖掉）。 */
    public static void refreshNow() {
        nextStatAt = System.currentTimeMillis() + STAT_INTERVAL_MS;
        statAndMaybeReload();
    }

    private static void statAndMaybeReload() {
        final Path p = path();
        try {
            if (!Files.isRegularFile(p)) {
                if (loadedMtime != -1L || !byId.isEmpty()) {
                    byId = Collections.emptyMap();
                    loadedMtime = -1L;
                    loadedSize = -1L;
                    ShanhaiMod.LOGGER.info("{} store_missing path={} -> 空表（全部物品都按原版显示）",
                            PREFIX, p);
                }
                loadedMtime = -1L;
                loadedSize = -1L;
                firstLoadDone = true;
                return;
            }
            final long m = Files.getLastModifiedTime(p).toMillis();
            final long s = Files.size(p);
            if (m == loadedMtime && s == loadedSize) {
                return;
            }
            reloadFromDisk(p, m, s);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} store_stat_failed path={} err={} -> 保留上一份表", PREFIX, p, t.toString());
        }
    }

    /** 惰性加载（开机 / 首次使用）。 */
    public static void ensureLoaded() {
        if (firstLoadDone) {
            return;
        }
        synchronized (ShanhaiItemOverrideTable.class) {
            if (firstLoadDone) {
                return;
            }
            refreshNow();
            firstLoadDone = true;
        }
    }

    /** 强制重读磁盘。坏 JSON ⇒ 打 ERROR 并<b>保留上一份</b>（绝不半生效）。 */
    public static void reloadFromDisk() {
        try {
            final Path p = path();
            if (!Files.isRegularFile(p)) {
                byId = Collections.emptyMap();
                loadedMtime = -1L;
                loadedSize = -1L;
                return;
            }
            reloadFromDisk(p, Files.getLastModifiedTime(p).toMillis(), Files.size(p));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} store_load_failed err={} -> 保留上一份表", PREFIX, t.toString());
        }
    }

    private static void reloadFromDisk(Path p, long mtime, long size) {
        final Map<ResourceLocation, Entry> parsed = new LinkedHashMap<>();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            final JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.error("{} store_not_object path={} -> 整体忽略（保留上一份表）", PREFIX, p);
                return;
            }
            final JsonObject root = el.getAsJsonObject();
            final JsonElement arrEl = root.get("entries");
            if (arrEl != null && arrEl.isJsonArray()) {
                int bad = 0;
                for (JsonElement e : arrEl.getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        bad++;
                        continue;
                    }
                    final Entry entry = parseEntry(e.getAsJsonObject());
                    if (entry == null) {
                        bad++;
                        continue;
                    }
                    final ResourceLocation key = ResourceLocation.tryParse(entry.id());
                    if (key != null) {
                        parsed.put(key, entry);
                    }
                }
                if (bad > 0) {
                    ShanhaiMod.LOGGER.warn("{} store_entries_skipped count={} reason=坏条目/保留字段/保留 id",
                            PREFIX, bad);
                }
            }
            // 全部读完才提交 ⇒ 解析中途抛异常时不会留下半张表
            byId = Collections.unmodifiableMap(parsed);
            loadedMtime = mtime;
            loadedSize = size;
            ShanhaiMod.LOGGER.info("{} store_loaded path={} entries={} mtime={} size={}",
                    PREFIX, p, parsed.size(), mtime, size);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} store_read_failed path={} err={} -> 整体忽略（保留上一份表）",
                    PREFIX, p, t.toString());
        }
    }

    /** 单条解析；返回 {@code null} = 这条要跳过（并已在日志里说明原因）。 */
    private static Entry parseEntry(JsonObject o) {
        final String rawId = str(o, "id", 128);
        if (rawId.isEmpty()) {
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            ShanhaiMod.LOGGER.warn("{} entry_skipped reason=bad_id id=\"{}\"", PREFIX, rawId);
            return null;
        }
        if (isReserved(id)) {
            ShanhaiMod.LOGGER.warn("{} entry_skipped reason=reserved_reality_shell id={} "
                    + "（这 128 个壳物品走 RealityShellItem 自己的动态名字，不吃覆盖）", PREFIX, id);
            return null;
        }
        final String name = cap(str(o, "name", MAX_NAME_CHARS), MAX_NAME_CHARS);
        final String lore = cap(normalizeNewlines(str(o, "lore", MAX_LORE_CHARS)), MAX_LORE_CHARS);
        final String mode = sanitizeMode(str(o, "lore_mode", 16));
        int stack = 0;
        if (o.has("max_stack") && o.get("max_stack").isJsonPrimitive()) {
            try {
                stack = clampStack(o.get("max_stack").getAsInt());
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} entry_skipped reason=bad_max_stack id={} raw={}",
                        PREFIX, id, o.get("max_stack"));
                return null;
            }
        }
        long ts = 0L;
        if (o.has("ts") && o.get("ts").isJsonPrimitive()) {
            try {
                ts = o.get("ts").getAsLong();
            } catch (Throwable ignored) {
                ts = 0L;
            }
        }
        // 🆕 两个布尔字段（缺省 false；removed=true 时 hidden 视为 true）
        final boolean removed = o.has("removed") && o.get("removed").isJsonPrimitive()
                && o.get("removed").getAsBoolean();
        final boolean hidden = removed || (o.has("hidden") && o.get("hidden").isJsonPrimitive()
                && o.get("hidden").getAsBoolean());
        final Entry entry = new Entry(id.toString(), name, lore, mode, stack, ts, hidden, removed);
        return entry.any() ? entry : null;
    }

    /** 读一个字符串键（缺键 = 空串；不 trim，因为 lore 可能有意义的行首空格）。 */
    private static String str(JsonObject o, String key, int max) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return "";
        }
        final String v = o.get(key).getAsString();
        if (v == null) {
            return "";
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** 手改 JSON 时最容易写的就是字面量 {@code \n} ⇒ 两种写法都认。 */
    public static String normalizeNewlines(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n").replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ------------------------------------------------------------------ 查询（内存）

    /** 表里有没有任何一条（热路径的第一道闸）。 */
    public static boolean active() {
        return !byId.isEmpty();
    }

    /** 这条 id 的覆盖项；没有 = {@code null}。 */
    public static Entry get(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        return byId.get(id);
    }

    /** 由「已注册物品」反查覆盖项（{@code null} = 没改）。 */
    public static Entry getFor(net.minecraft.world.item.Item item) {
        if (item == null || byId.isEmpty()) {
            return null;
        }
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : byId.get(id);
    }

    public static String nameOf(ResourceLocation id) {
        final Entry e = get(id);
        return e == null ? null : (e.hasName() ? e.name() : null);
    }

    /** 全部条目（按 id 字典序，稳定输出）。 */
    public static List<Entry> entries() {
        final List<Entry> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public static int size() {
        return byId.size();
    }

    // ------------------------------------------------------------------ 🆕 删除/抹除的查询面

    /** 🆕 当前「藏起来」生效的全部条目（{@code hidden || removed}），按 id 字典序。 */
    public static List<Entry> suppressed() {
        final List<Entry> out = new ArrayList<>();
        for (Entry e : byId.values()) {
            if (e.hiddenEffective()) {
                out.add(e);
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /** 🆕 这个 id 现在是否从 JEI/创造栏藏起来（= hidden 或 removed）。 */
    public static boolean isHidden(ResourceLocation id) {
        final Entry e = get(id);
        return e != null && e.hiddenEffective();
    }

    /** 🆕 这个物品的实例现在是否该被抹掉（= removed）。热路径：一次 volatile 读 + Map 查。 */
    public static boolean isRemoved(net.minecraft.world.item.Item item) {
        final Entry e = getFor(item);
        return e != null && e.removed();
    }

    /** 🆕 这个 id 是否 {@code removed}（按 id 判，不依赖注册表）。 */
    public static boolean isRemoved(ResourceLocation id) {
        final Entry e = get(id);
        return e != null && e.removed();
    }

    /** 一行读数（开机日志 + 命令回执都印它）。 */
    public static String statsLine() {
        return "path=" + RELATIVE_PATH + " entries=" + size() + " active=" + active()
                + " mtime=" + loadedMtime + " size=" + loadedSize
                + " ids=" + idsPreview();
    }

    private static String idsPreview() {
        if (byId.isEmpty()) {
            return "[]";
        }
        final StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (ResourceLocation id : byId.keySet()) {
            if (n++ > 0) {
                sb.append(", ");
            }
            if (n > 8) {
                sb.append("…");
                break;
            }
            sb.append(id);
        }
        return sb.append(']').toString();
    }

    /** 🔴 这 128 个壳物品（{@code shanhai:reality_item_1..128}）不吃覆盖。 */
    public static boolean isReserved(ResourceLocation id) {
        return id != null
                && RESERVED_NS.equals(id.getNamespace())
                && id.getPath().startsWith(RESERVED_PATH_PREFIX);
    }

    // ------------------------------------------------------------------ 写

    /** 设显示名。 */
    public static boolean setName(ResourceLocation id, String name) {
        return mutate(id, "name", cur -> new Entry(cur.id(),
                cap(name == null ? "" : name.trim(), MAX_NAME_CHARS),
                cur.lore(), cur.loreMode(), cur.maxStack(), System.currentTimeMillis(),
                cur.hidden(), cur.removed()));
    }

    /** 设提示（多行，已含真实换行）。{@code mode} 传 {@code null} = 保持原模式。 */
    public static boolean setLore(ResourceLocation id, String lore, String mode) {
        return mutate(id, "lore", cur -> new Entry(cur.id(),
                cur.name(),
                cap(normalizeNewlines(lore == null ? "" : lore), MAX_LORE_CHARS),
                mode == null ? cur.loreMode() : sanitizeMode(mode),
                cur.maxStack(), System.currentTimeMillis(), cur.hidden(), cur.removed()));
    }

    /** 只改 {@code lore_mode}。 */
    public static boolean setLoreMode(ResourceLocation id, String mode) {
        return mutate(id, "lore_mode", cur -> new Entry(cur.id(), cur.name(), cur.lore(),
                sanitizeMode(mode), cur.maxStack(), System.currentTimeMillis(), cur.hidden(), cur.removed()));
    }

    /** 设堆叠上限。 */
    public static boolean setMaxStack(ResourceLocation id, int n) {
        return mutate(id, "max_stack", cur -> new Entry(cur.id(), cur.name(), cur.lore(),
                cur.loreMode(), clampStack(n), System.currentTimeMillis(), cur.hidden(), cur.removed()));
    }

    // ------------------------------------------------------------------ 🆕 删除/抹除（hidden / removed）

    /**
     * 设 {@code hidden}（从 JEI/创造栏藏起来，实例还在）。可撤回。
     *
     * <p>⚠️ 若这条已 {@code removed=true}，{@code setHidden(false)} <b>不会</b>让实例重新出现
     * —— 因为 {@code removed} 仍然有效；要完全恢复请走 {@code restore}（{@link #setFlags}）。
     */
    public static boolean setHidden(ResourceLocation id, boolean hidden) {
        return mutate(id, "hidden", cur -> new Entry(cur.id(), cur.name(), cur.lore(), cur.loreMode(),
                cur.maxStack(), System.currentTimeMillis(),
                hidden || cur.removed(), cur.removed()));
    }

    /** 设 {@code removed}（藏 + 抹实例 + 摘配方产出）。撤回用 {@code restore}（{@link #setFlags}）。 */
    public static boolean setRemoved(ResourceLocation id, boolean removed) {
        return mutate(id, "removed", cur -> new Entry(cur.id(), cur.name(), cur.lore(), cur.loreMode(),
                cur.maxStack(), System.currentTimeMillis(),
                removed || cur.hidden(), removed));
    }

    /**
     * 一次设两个标志（{@code null} = 这一项不动）。
     *
     * <p>{@code restore} 走的就是 {@code setFlags(id, false, false)}：两个标志一起取消，
     * 别的覆盖项（名字/提示/堆叠）<b>一个字节都不动</b>。
     */
    public static boolean setFlags(ResourceLocation id, Boolean hidden, Boolean removed) {
        return mutate(id, "flags", cur -> {
            final boolean r = removed == null ? cur.removed() : removed;
            final boolean h = r || (hidden == null ? cur.hidden() : hidden);
            return new Entry(cur.id(), cur.name(), cur.lore(), cur.loreMode(),
                    cur.maxStack(), System.currentTimeMillis(), h, r);
        });
    }

    /** 删掉这条覆盖（= 立刻恢复原样）。 */
    public static boolean clear(ResourceLocation id) {        synchronized (ShanhaiItemOverrideTable.class) {
            refreshNow();
            if (id == null) {
                return false;
            }
            if (isReserved(id)) {
                ShanhaiMod.LOGGER.warn("{} clear_rejected reason=reserved_reality_shell id={}", PREFIX, id);
                return false;
            }
            final Entry cur = byId.get(id);
            if (cur == null) {
                ShanhaiMod.LOGGER.info("{} clear_noop id={} reason=原本就没有覆盖项", PREFIX, id);
                return true;
            }
            final Map<ResourceLocation, Entry> next = new LinkedHashMap<>(byId);
            next.remove(id);
            if (!writeTable(next)) {
                return false;
            }
            byId = Collections.unmodifiableMap(next);
            syncStat();
            ShanhaiMod.LOGGER.info("{} clear_ok id={} before={{{}}} -> after=<removed> entries={}",
                    PREFIX, id, cur.signature(), next.size());
            return true;
        }
    }

    /** 改一条（唯一入口）：**先落盘、成功才换内存**（与 RealityItemTable 同一纪律）。 */
    private static boolean mutate(ResourceLocation id, String what,
                                  java.util.function.UnaryOperator<Entry> op) {
        synchronized (ShanhaiItemOverrideTable.class) {
            refreshNow();
            if (id == null) {
                return false;
            }
            if (isReserved(id)) {
                ShanhaiMod.LOGGER.warn("{} edit_rejected reason=reserved_reality_shell id={} field={}",
                        PREFIX, id, what);
                return false;
            }
            final Entry cur = byId.getOrDefault(id, new Entry(id.toString(), "", "", MODE_APPEND, 0, 0L));
            final Entry after = op.apply(cur);
            final Map<ResourceLocation, Entry> next = new LinkedHashMap<>(byId);
            if (after.any()) {
                next.put(id, after);
            } else {
                next.remove(id);       // 全空 ⇒ 这一条不该留在文件里
            }
            if (!writeTable(next)) {
                return false;
            }
            byId = Collections.unmodifiableMap(next);
            syncStat();
            ShanhaiMod.LOGGER.info("{} edit_ok id={} field={} before={{{}}} -> after={{{}}} entries={}",
                    PREFIX, id, what, cur.signature(), after.signature(), next.size());
            return true;
        }
    }

    /** 写完把 mtime/size 对一次，避免刚写完就被节流器判成"文件变了"再读一遍。 */
    private static void syncStat() {
        try {
            final Path p = path();
            if (Files.isRegularFile(p)) {
                loadedMtime = Files.getLastModifiedTime(p).toMillis();
                loadedSize = Files.size(p);
            }
        } catch (Throwable ignored) {
            loadedMtime = -1L;
            loadedSize = -1L;
        }
    }

    /** 把一份表原子写盘。成功才由调用方提交到内存。 */
    private static boolean writeTable(Map<ResourceLocation, Entry> table) {
        final Path target = path();
        final JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("generated_by", GENERATED_BY);
        root.addProperty("_note", NOTE);
        root.addProperty("updated_at", nowTs());
        final JsonArray arr = new JsonArray();
        final List<Entry> sorted = new ArrayList<>(table.values());
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        for (Entry e : sorted) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            if (e.hasName()) {
                o.addProperty("name", e.name());
            }
            if (e.hasLore()) {
                o.addProperty("lore", e.lore());
                o.addProperty("lore_mode", sanitizeMode(e.loreMode()));
            }
            if (e.hasStack()) {
                o.addProperty("max_stack", clampStack(e.maxStack()));
            }
            // 🆕 只写"设过"的字段：hidden 缺省不写；removed=true 时一定同时写 hidden（自描述）
            if (e.hiddenEffective()) {
                o.addProperty("hidden", true);
            }
            if (e.removed()) {
                o.addProperty("removed", true);
            }
            o.addProperty("ts", e.ts() > 0 ? e.ts() : System.currentTimeMillis());
            arr.add(o);
        }
        root.add("entries", arr);
        try {
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
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

    // ------------------------------------------------------------------ 归一 / 自检

    public static int clampStack(int v) {
        return v < MIN_STACK ? MIN_STACK : Math.min(v, MAX_STACK);
    }

    public static String sanitizeMode(String raw) {
        if (raw == null) {
            return MODE_APPEND;
        }
        return MODE_REPLACE.equalsIgnoreCase(raw.trim()) ? MODE_REPLACE : MODE_APPEND;
    }

    /**
     * 自检（开机日志里跑；<b>纯算术、只读</b>）。
     *
     * <p>判据：1 条正对照 + 4 条负对照。没有负对照，"全绿"可能只是判据从来不响。
     */
    public static String selfCheck() {
        final StringBuilder bad = new StringBuilder();
        int pass = 0;
        final int total = 5;

        // ① 正对照：正常 id 不被判成保留
        if (!isReserved(new ResourceLocation("minecraft:apple"))
                && !isReserved(new ResourceLocation("shanhai:reality_module"))
                && isReserved(new ResourceLocation("shanhai:reality_item_1"))
                && isReserved(new ResourceLocation("shanhai:reality_item_128"))) {
            pass++;
        } else {
            bad.append("正对照失败（保留 id 判据不对）;");
        }
        // ② 负对照 A：别家的同名前缀不保留
        if (!isReserved(new ResourceLocation("othermod:reality_item_1"))) {
            pass++;
        } else {
            bad.append("负对照A没响（别人家的 reality_item_ 也被跳过了）;");
        }
        // ③ 负对照 B：堆叠夹紧
        if (clampStack(0) == 1 && clampStack(-5) == 1 && clampStack(16) == 16
                && clampStack(Integer.MAX_VALUE) == MAX_STACK) {
            pass++;
        } else {
            bad.append("负对照B没响（堆叠夹紧没生效）;");
        }
        // ④ 负对照 C：lore_mode 归一（认不出来 = append）
        if (MODE_REPLACE.equals(sanitizeMode("REPLACE")) && MODE_REPLACE.equals(sanitizeMode(" replace "))
                && MODE_APPEND.equals(sanitizeMode("nonsense")) && MODE_APPEND.equals(sanitizeMode(null))) {
            pass++;
        } else {
            bad.append("负对照C没响（lore_mode 归一没生效）;");
        }
        // ⑤ 负对照 D：全空 entry 不算"改了什么"
        final Entry empty = new Entry("minecraft:apple", "", "", MODE_APPEND, 0, 0L);
        final Entry named = new Entry("minecraft:apple", "红苹果", "", MODE_APPEND, 0, 0L);
        if (!empty.any() && named.any() && !empty.hasName() && named.hasName()) {
            pass++;
        } else {
            bad.append("负对照D没响（空 entry 被当成有改动）;");
        }

        final String line = "item_override_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + "（判据：保留 id／别人家前缀不误伤／堆叠夹紧／lore_mode 归一／空 entry 不算改动；"
                + "另 " + statsLine() + "）";
        if (bad.length() > 0) {
            ShanhaiMod.LOGGER.error("{} item_override_selftest FAILED: {}", PREFIX, bad);
        }
        return line;
    }
}
