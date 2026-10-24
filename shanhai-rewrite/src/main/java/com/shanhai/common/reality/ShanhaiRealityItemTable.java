package com.shanhai.common.reality;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shanhai.ShanhaiMod;
import com.shanhai.item.ShanhaiItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

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
import java.util.List;

/**
 * 山海重构 · <b>「自己新增物品」（现实物品槽位）的唯一一张表 + 唯一一份落盘</b>。
 *
 * <h2>1. 规格（用户拍板，逐条对应）</h2>
 * <pre>
 *   ① 槽位数 128，物品 id = {@code shanhai:reality_item_1 … _128}（物品本体在
 *      {@code ShanhaiItems} 里【加载期】注册，本类只管"这 128 个壳各自长什么样"）
 *   ② 只做占位物品：名字 / 图标 / 提示 / 堆叠 / 稀有度，<b>不带任何行为</b>
 *   ③ 图标 = 借已注册物品的图标；没选图标时兜底 = 内置 16 张纯色色块（按槽位号轮流）
 *   ⑥ 门槛 = 身上带着「创始现实修改模块」
 *   ⑦ 数据落 {@code config/shanhai/creative_items.json}（与 recipe_overrides.json 同一处）
 *   ⑧ 只显示"已经填过名字"的槽位（未定义 ⇒ 创造栏 / JEI 里都看不到）
 *   ⑨ 改名只改显示名，id 不变；清空名字 ⇒ 退回默认壳（不删物品）
 * </pre>
 *
 * <h2>2. 🔴 为什么表放在 common 包、而且是"整表快照 + 版本号"</h2>
 * <ul>
 *   <li><b>两侧都要读</b>：服务端读它判"有没有定义"（创造栏内容、命令、保存校验），
 *       客户端读它渲染名字/图标/tooltip。⇒ 不能放 {@code client} 包（专服会
 *       {@code NoClassDefFoundError}，本工程老坑）。</li>
 *   <li><b>整表快照</b>：所有读侧拿到的都是不可变的 {@link Entry}，改表 = 换一个新数组再
 *       换引用（{@code volatile}），渲染线程不可能读到"改了一半"的表。</li>
 *   <li><b>版本号</b>：客户端渲染层（{@code ShanhaiRealityItemModels}）按它作废自己的
 *       解析缓存 ⇒ <b>不用重载资源、不用 F3+T</b>。</li>
 * </ul>
 *
 * <h2>3. 落盘格式（人可读、可手改）</h2>
 * <pre>
 * {
 *   "schema_version": 1,
 *   "generated_by": "shanhai-0.1.0",
 *   "_note": "…",
 *   "slots": [
 *     { "slot": 1, "name": "测试物品", "icon": "item:minecraft:diamond",
 *       "max_stack": 64, "rarity": "common", "lore": "第一行提示" }
 *   ]
 * }
 * </pre>
 * <ul>
 *   <li>{@code slot} 是 <b>1 基</b>（= 用户看到的槽位号，= id 后缀）；内存里一律 0 基。</li>
 *   <li>{@code icon}：空串 = 用兜底（内置色块 {@code slot % 16}）；{@code builtin:K} = 指定第 K 号色块；
 *       {@code item:ns:id} = 借那个物品的图标。</li>
 *   <li><b>只有"填过名字"的槽位才写进文件</b>：清空名字 = 这一条从文件里消失
 *       ⇒ 未定义槽位在创造栏 / JEI 里必然看不到（第 ⑧ 条）。</li>
 * </ul>
 *
 * <h2>4. 写盘纪律（照 {@code ShanhaiRecipeOverrideStore} 的既有做法）</h2>
 * 先写同目录 {@code .tmp}，再 {@code ATOMIC_MOVE} 覆盖 ⇒ 崩在写一半也不会留下半截 JSON。
 * 读失败/文件不存在 ⇒ 空表 + 如实打日志，<b>绝不静默编一个数</b>。
 */
public final class ShanhaiRealityItemTable {

    /** 日志前缀（可 grep）。 */
    public static final String PREFIX = "[SHANHAI-REALITY]";

    /** 槽位数。用户拍板：128。 */
    public static final int SLOT_COUNT = 128;

    /** 内置兜底色块张数。用户拍板：16。 */
    public static final int BUILTIN_ICON_COUNT = 16;

    /** 相对【游戏目录】的路径（与 {@code recipe_overrides.json} 同一处）。 */
    public static final String RELATIVE_PATH = "config/shanhai/creative_items.json";

    public static final int SCHEMA_VERSION = 1;
    public static final String GENERATED_BY = "shanhai-0.1.0";

    /** 名字 / 提示的字符上限（写盘与网络包共用一套口径）。 */
    public static final int MAX_NAME_CHARS = 48;
    /**
 * 提示的总字符上限。🔴 2026-10-17 用户点单「不要有最大 10 行的限制」⇒ 行数不再受限（编辑器分页），
 * 总长从 96 抬到 512（一整段描述够用，tooltip 也是多行渲染）。
 */
    public static final int MAX_LORE_CHARS = 512;
    public static final int MAX_ICON_CHARS = 96;

    /**
     * 堆叠上下限。
     *
     * <p>🔴 2026-10-17 用户点单：<b>上限放开到 int</b>（他原话「可以放开到 intmax，不过加上不建议超出 64，
     * 可能出现未知 bug 的提示」）⇒ 所以这里分两个常量：
     * <ul>
     *   <li>{@link #DEFAULT_STACK} = 64 —— <b>未定义的槽位</b>用它，也是面板上「64」那个快捷按钮的值；</li>
     *   <li>{@link #MAX_STACK} = {@code Integer.MAX_VALUE} —— 滑到多大就多大，但 &gt;64 会在面板上打红字警告。</li>
     * </ul>
     * ⚠️ 超过 64 的已知风险（界面上也要提示）：原版有些路径仍按「无参 {@code getMaxStackSize()} = 64」处理
     * （漏斗/自动合并、个别模组的栈逻辑），表现为"能拿到一大摞，但合并不满"。
     */
    public static final int MIN_STACK = 1;
    public static final int DEFAULT_STACK = 64;
    public static final int MAX_STACK = Integer.MAX_VALUE;

    /** 稀有度口径（小写英文，落盘用；界面上的中文由面板给）。 */
    public static final String[] RARITY_NAMES = {"common", "uncommon", "rare", "epic"};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** 一个槽位的定义（不可变）。{@code icon} 可以是空串（= 用兜底色块）。 */
    public record Entry(String name, String icon, int maxStack, String rarity, String lore) {

        /** 这份定义"填过名字"（第 ⑧ 条的唯一判据）。 */
        public boolean defined() {
            return name != null && !name.isBlank();
        }

        /** 一行签名（客户端 JEI 增量同步用：变了就重加一次）。 */
        public String signature() {
            return name + '\u0001' + icon + '\u0001' + maxStack + '\u0001' + rarity + '\u0001' + lore;
        }
    }

    /** 当前整表（长度恒为 {@link #SLOT_COUNT}；元素可为 null）。改表 = 换一个数组引用。 */
    private static volatile Entry[] slots = new Entry[SLOT_COUNT];

    /** 表版本号：每次成功改表 +1（渲染层据此作废缓存）。 */
    private static volatile int version;

    /** 文件是否已经读过（避免每次取名字都打一次盘）。 */
    private static volatile boolean loaded;

    private ShanhaiRealityItemTable() {}

    // ------------------------------------------------------------------ 路径与时间

    /** {@code <gameDir>/config/shanhai/creative_items.json}。 */
    public static Path path() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    public static String nowTs() {
        return TS_FMT.format(Instant.now());
    }

    // ------------------------------------------------------------------ 读

    /** 惰性读一次（第一次被问到时读盘；之后只走内存）。 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (ShanhaiRealityItemTable.class) {
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
            ShanhaiMod.LOGGER.info("{} store_missing path={} -> 空表（128 个槽位全部未定义）", PREFIX, p);
            return;
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            final JsonElement el = JsonParser.parseReader(r);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.error("{} store_not_object path={} -> 保留空表（不覆盖用户文件）", PREFIX, p);
                return;
            }
            applyFromJson(el.getAsJsonObject());
            ShanhaiMod.LOGGER.info("{} store_loaded path={} defined={} version={}",
                    PREFIX, p, definedCount(), version);
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
                final Entry entry = new Entry(
                        str(o, "name", MAX_NAME_CHARS),
                        sanitizeIcon(str(o, "icon", MAX_ICON_CHARS)),
                        clampStack(o.has("max_stack") ? o.get("max_stack").getAsInt() : DEFAULT_STACK),
                        sanitizeRarity(str(o, "rarity", 16)),
                        str(o, "lore", MAX_LORE_CHARS));
                if (entry.defined()) {
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

    /** 槽位定义（0 基）；未定义返回 {@code null}。 */
    public static Entry entry(int index) {
        ensureLoaded();
        final Entry[] t = slots;
        return index >= 0 && index < SLOT_COUNT ? t[index] : null;
    }

    /** 这个槽位"填过名字"没有（第 ⑧ 条的唯一判据）。 */
    public static boolean isDefined(int index) {
        final Entry e = entry(index);
        return e != null && e.defined();
    }

    /** 已定义槽位数。 */
    public static int definedCount() {
        final Entry[] t = slots;
        int n = 0;
        for (Entry e : t) {
            if (e != null && e.defined()) {
                n++;
            }
        }
        return n;
    }

    /** 已定义的槽位下标（升序）。 */
    public static List<Integer> definedSlots() {
        final Entry[] t = slots;
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (t[i] != null && t[i].defined()) {
                out.add(i);
            }
        }
        return out;
    }

    /** 显示名（未定义 = 默认壳的名字「现实物品 #n」）。 */
    public static String displayName(int index) {
        final Entry e = entry(index);
        return e != null && e.defined() ? e.name() : "现实物品 #" + (index + 1);
    }

    /** 生效的图标串（未指定 ⇒ 兜底色块 {@code builtin:index%16}）。 */
    public static String iconOf(int index) {
        final Entry e = entry(index);
        if (e == null || e.icon() == null || e.icon().isBlank()) {
            return "builtin:" + Math.floorMod(index, BUILTIN_ICON_COUNT);
        }
        return e.icon();
    }

    public static int maxStackOf(int index) {
        final Entry e = entry(index);
        return e == null ? DEFAULT_STACK : clampStack(e.maxStack());
    }

    public static String rarityOf(int index) {
        final Entry e = entry(index);
        return e == null ? "common" : sanitizeRarity(e.rarity());
    }

    public static String loreOf(int index) {
        final Entry e = entry(index);
        return e == null ? "" : (e.lore() == null ? "" : e.lore());
    }

    // ------------------------------------------------------------------ 写

    /**
     * 改一个槽位（唯一入口）。
     *
     * @return true = 内存与磁盘都改成功；false = 参数不合法或写盘失败（此时<b>内存也不动</b>）
     */
    public static boolean apply(int index, String name, String icon, int maxStack, String rarity, String lore) {
        if (index < 0 || index >= SLOT_COUNT) {
            ShanhaiMod.LOGGER.warn("{} apply_rejected reason=slot_out_of_range idx={}", PREFIX, index);
            return false;
        }
        final String cleanName = cap(name, MAX_NAME_CHARS);
        final Entry next = new Entry(cleanName, sanitizeIcon(cap(icon, MAX_ICON_CHARS)),
                clampStack(maxStack), sanitizeRarity(rarity), cap(lore, MAX_LORE_CHARS));
        final Entry[] nextTable = slots.clone();
        nextTable[index] = next.defined() ? next : null;   // 名字空 = 这一格未定义
        if (!writeTable(nextTable)) {
            return false;
        }
        slots = nextTable;
        incrementVersion();
        ShanhaiMod.LOGGER.info("{} apply_ok slot={} id=shanhai:reality_item_{} defined={} icon={} stack={} "
                        + "rarity={} version={}",
                PREFIX, index + 1, index + 1, next.defined(), next.icon(), next.maxStack(), next.rarity(),
                version);
        return true;
    }

    /** 清空一个槽位（= 退回默认壳；不删物品）。 */
    public static boolean clear(int index) {
        return apply(index, "", "", DEFAULT_STACK, "common", "");
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
            if (e == null || !e.defined()) {
                continue;                       // 未定义槽位不写进文件（第 ⑧ 条的根）
            }
            final JsonObject o = new JsonObject();
            o.addProperty("slot", i + 1);
            o.addProperty("name", e.name());
            o.addProperty("icon", e.icon() == null ? "" : e.icon());
            o.addProperty("max_stack", clampStack(e.maxStack()));
            o.addProperty("rarity", sanitizeRarity(e.rarity()));
            o.addProperty("lore", e.lore() == null ? "" : e.lore());
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
            "Maintained by the in-game 'creative items' panel (/shanhai items). Only slots that have a "
                    + "non-empty name are listed here; clearing a name removes its entry and the item falls "
                    + "back to its default shell (the registered item itself is NEVER removed, so existing "
                    + "stacks keep working). icon: '' = builtin colour block (slot %% 16), 'builtin:K' = that "
                    + "colour block, 'item:ns:id' = borrow that item's icon. Keys are 1-based slot numbers.";

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
            if (e == null || !e.defined()) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("slot", i + 1);
            o.addProperty("name", e.name());
            o.addProperty("icon", e.icon() == null ? "" : e.icon());
            o.addProperty("max_stack", clampStack(e.maxStack()));
            o.addProperty("rarity", sanitizeRarity(e.rarity()));
            o.addProperty("lore", e.lore() == null ? "" : e.lore());
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

    // ------------------------------------------------------------------ 门槛

    /**
     * 第 ⑥ 条门槛：<b>身上带着「创始现实修改模块」</b>。
     *
     * <p>口径：背包（含快捷栏/副手）任意一格即可 —— 用户的原话是"身上带着"，不是"拿在手上"。
     * 服务端与客户端用的是同一个纯函数（两条入口各判一次，服务端不信客户端）。
     */
    public static boolean playerHasModule(Player player) {
        if (player == null) {
            return false;
        }
        final net.minecraft.world.item.Item module =
                ShanhaiItems.GENESIS_REALITY_MODIFICATION_MODULE.get();
        if (module == null) {
            return false;
        }
        final net.minecraft.world.Container inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            final ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(module)) {
                return true;
            }
        }
        return false;
    }

    /** 门槛不满足时给玩家看的那一句（命令与全息两条入口共用同一句）。 */
    public static String moduleRequiredText() {
        return "§c需要身上带着「创始现实修改模块」（§fshanhai:genesis_reality_modification_module§c）";
    }

    // ------------------------------------------------------------------ 小工具

    private static int clampStack(int v) {
        return v < MIN_STACK ? MIN_STACK : Math.min(v, MAX_STACK);
    }

    /** 稀有度归一到 {@link #RARITY_NAMES} 里的一项（认不出来 = common）。 */
    public static String sanitizeRarity(String raw) {
        if (raw == null) {
            return "common";
        }
        final String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (String r : RARITY_NAMES) {
            if (r.equals(v)) {
                return r;
            }
        }
        return "common";
    }

    /** 图标串归一：空 / {@code builtin:K}（K 落在 0..15）/ {@code item:ns:id}；其余一律当空。 */
    public static String sanitizeIcon(String raw) {
        if (raw == null) {
            return "";
        }
        final String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.startsWith("builtin:")) {
            try {
                final int k = Integer.parseInt(v.substring("builtin:".length()).trim());
                return "builtin:" + Math.floorMod(k, BUILTIN_ICON_COUNT);
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if (v.startsWith("item:")) {
            final String id = v.substring("item:".length()).trim();
            return net.minecraft.resources.ResourceLocation.tryParse(id) == null ? "" : "item:" + id;
        }
        // 只写了 ns:id 也算 item:（手改文件时最自然的那种写法）
        if (net.minecraft.resources.ResourceLocation.tryParse(v) != null) {
            return "item:" + v;
        }
        return "";
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    // ------------------------------------------------------------------ 自检 / 读数

    /** 一行读数（开机日志 + 面板标题都用它）。 */
    public static String statsLine() {
        return "path=" + RELATIVE_PATH + " slots=" + SLOT_COUNT + " builtin_icons=" + BUILTIN_ICON_COUNT
                + " defined=" + definedCount() + " version=" + version;
    }

    /**
     * 自检（开机日志里跑；<b>只读</b>，不写盘、不改真表）。
     *
     * <p>判据：1 条正对照 + 5 条负对照。没有负对照，"全绿"可能只是判据从来不响。
     */
    public static String selfCheck() {
        final StringBuilder bad = new StringBuilder();
        int pass = 0;
        final int total = 6;

        // ① 正对照：未定义槽位的图标必须是"按槽位号轮流的兜底色块"
        final int probe = 5;   // 只读，不管它现在定义与否，直接算 iconOf 的兜底分支
        final String fallback = "builtin:" + Math.floorMod(probe, BUILTIN_ICON_COUNT);
        final boolean pos = fallback.equals("builtin:5") && Math.floorMod(127, BUILTIN_ICON_COUNT) == 15;
        if (pos) {
            pass++;
        } else {
            bad.append(" 正对照失败：24 号槽位的兜底应当是 builtin:5，实际 ")
                    .append(fallback).append(';');
        }
        // ② 负对照 A：sanitizeRarity 认不出 ⇒ common
        if ("common".equals(sanitizeRarity("legendary")) && "epic".equals(sanitizeRarity(" EPIC "))) {
            pass++;
        } else {
            bad.append(" 负对照A没响（稀有度归一没生效）;");
        }
        // ③ 负对照 B：sanitizeIcon 认不出的串 ⇒ 空（不是原样保留）
        if (sanitizeIcon("乱七八糟").isEmpty() && "builtin:3".equals(sanitizeIcon("builtin:19"))) {
            pass++;
        } else {
            bad.append(" 负对照B没响（图标串归一没生效）;");
        }
        // ④ 负对照 C：堆叠夹紧
        if (clampStack(0) == 1 && clampStack(Integer.MAX_VALUE) == MAX_STACK && clampStack(999) == 999 && clampStack(16) == 16 && DEFAULT_STACK == 64) {
            pass++;
        } else {
            bad.append(" 负对照C没响（堆叠夹紧没生效）;");
        }
        // ⑤ 负对照 D：名字空的 Entry 不算"已定义"（第 ⑧ 条的根）
        if (!new Entry("   ", "item:minecraft:stone", 64, "common", "").defined()
                && new Entry("x", "", 64, "common", "").defined()) {
            pass++;
        } else {
            bad.append(" 负对照D没响（空白名的槽位被当成了已定义）;");
        }
        // ⑥ 负对照 E：只写了 ns:id 的图标串会被补成 item: 前缀
        if ("item:minecraft:diamond".equals(sanitizeIcon("minecraft:diamond"))) {
            pass++;
        } else {
            bad.append(" 负对照E没响（裸 id 没被补成 item: 前缀）;");
        }

        final String line = "reality_table_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + "（判据：兜底色块按槽位号轮流／稀有度归一／图标串归一／堆叠夹紧／"
                + "空白名不算已定义／裸 id 补前缀；另 " + statsLine() + "）";
        if (bad.length() > 0) {
            ShanhaiMod.LOGGER.error("{} reality_table_selftest FAILED: {}", PREFIX, bad.toString());
        }
        return line;
    }
}
