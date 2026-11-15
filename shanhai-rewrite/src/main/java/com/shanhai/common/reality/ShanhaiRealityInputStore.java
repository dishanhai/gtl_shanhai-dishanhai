package com.shanhai.common.reality;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shanhai.ShanhaiMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 65 件 {@code mc_*} 事件物品的<b>登记簿</b>：{@code config/shanhai/mc_bottom_inputs.json}。
 *
 * <h2>一、文件结构（4 个字段：target / action / ts / owner）</h2>
 * <pre>
 * {
 *   "shanhai:mc_livingattackevent": { "target": "自己", "action": "取消", "ts": 1763000000000,
 *                                     "owner": "069a79f4-44e9-4726-a5be-fca90e38aaf5" },
 *   "shanhai:mc_livinghurtevent":   { "target": "@a",  "action": "记录", "ts": 1763000001234,
 *                                     "owner": "069a79f4-44e9-4726-a5be-fca90e38aaf5" }
 * }
 * </pre>
 * <ul>
 *   <li>顶层键 = <b>完整物品 id</b>（{@code shanhai:<path>}）。读的时候也接受裸 path（容错，见
 *       {@link #normalizeItemId}）——但<b>写出去的一律是完整 id</b>。</li>
 *   <li>{@code target}：{@code 自己} / {@code @a} / 玩家名 / 实体类型 id（{@code minecraft:zombie}
 *       或裸 {@code zombie}）；语义见 {@link #matchesTarget}。</li>
 *   <li>{@code action}：{@code 取消} / {@code 记录}（只要串里含"取消"就当取消，见 {@link #isCancelAction}）。</li>
 *   <li>{@code ts}：登记那一刻的 <b>epoch 毫秒</b>（{@code System.currentTimeMillis()}）。</li>
 *   <li>{@code owner}：<b>登记者本人的 UUID</b>（{@code UUID#toString} 那种带连字符的小写形式）。
 *       {@code target} 写 {@code 自己} 时靠它认人；<b>它必须落盘</b>，否则重启之后"自己"就谁也匹配不上了。</li>
 * </ul>
 *
 * <h2>二、「自己」= 登记者本人（owner 落盘，重启后仍然有效）</h2>
 * <ul>
 *   <li>同一会话内：{@code 自己} 精确匹配登记者本人的 UUID；</li>
 *   <li><b>重启服务器之后</b>：{@code owner} 从文件读回来 ⇒ {@code 自己} <b>照旧匹配</b>
 *       （判据：{@code temp\reality-items\exp\RestartSim.java} 走的就是本类真正在跑的那几个方法：
 *       {@link #toJson} → {@link #parseJson} → {@link #matchesTarget}，清空内存再读一遍 = 重启）；</li>
 *   <li>🔴 <b>没有 {@code owner} 的老文件/手写文件</b>：{@code 自己} 这一档<b>不匹配</b>（绝不猜，
 *       也绝不退化成"@a 全玩家"），并在读盘时<b>逐条打一行 WARN</b>
 *       （{@code entry_without_owner id=…}）；要恢复就重新登记一次。</li>
 *   <li>{@code @a} / 玩家名 / 实体类型 id 这 3 档<b>本来就不依赖 owner</b> ⇒ 重启后照常。</li>
 * </ul>
 *
 * <h2>三、写盘纪律：先写同目录 {@code .tmp}，再 {@code ATOMIC_MOVE} 覆盖</h2>
 * 照 {@code ShanhaiRealityItemTable#writeTable} 的既有做法（崩在写一半不会留下半截 JSON）；
 * {@code ATOMIC_MOVE} 不支持时退回普通 {@code REPLACE_EXISTING}。
 * <p>JSON 的<b>序列化 / 反序列化</b>与<b>目标匹配</b>都被抽成了不碰 Minecraft 类的纯函数
 * （{@link #toJson} / {@link #parseJson} / {@link #matchesTarget}）—— 目的是让它们能在
 * <b>不进游戏</b>的情况下被真跑一遍（离线「重启」对照就是这么来的）。
 *
 * <h2>四、🔴 这一批只有 5 件真的接线，其余 60 件<b>只登记</b></h2>
 * {@link #WIRED} 是全工程<b>唯一</b>一份"已接线"表；面板按它决定要不要多回一句
 * {@code （这件尚未接线，只做登记）}。判据 {@code WIRED.size() == 5} 写在 {@link #selfCheck()} 里，
 * 开机就有一行读数 —— 目的是让"悄悄多接了一件/少接了一件"变成可 grep 的失败。
 */
public final class ShanhaiRealityInputStore {

    /** 日志前缀（可 grep）。 */
    public static final String PREFIX = "[SHANHAI-REALITY-INPUT]";

    /** 登记文件（相对游戏目录；与 {@code creative_items.json} 同一处）。 */
    public static final String RELATIVE_PATH = "config/shanhai/mc_bottom_inputs.json";

    /** 目标 / 动作的字符上限（界面上不给无限长，落盘也夹一次）。 */
    public static final int MAX_TARGET_CHARS = 64;

    public static final int MAX_ACTION_CHARS = 32;

    /** 目标口径（与面板提示文本一致）。 */
    public static final String TARGET_SELF = "自己";
    public static final String TARGET_ALL_PLAYERS = "@a";

    /** 动作口径（与面板提示文本一致）。 */
    public static final String ACTION_CANCEL = "取消";
    public static final String ACTION_RECORD = "记录";

    /** 物品 id 的命名空间（{@code ShanhaiMod.MOD_ID} 的常量副本，避免本类被 <clinit> 顺序绑住）。 */
    public static final String NAMESPACE = "shanhai";

    /**
     * <b>已接线</b>的 5 件（唯一一份表）。
     *
     * <p>每件的判据（契约）：
     * <ol>
     *   <li>事件被触发时调 {@link #cancelNext};</li>
     *   <li>{@code action} 含"取消"且目标匹配 ⇒ 这一次事件被取消；</li>
     *   <li>取消成功后<b>立刻</b>把这条登记从内存与文件里都删掉（一次性，用完即清）；</li>
     *   <li>给登记者（在线时）报一行 {@code [创始现实修改模块] 已取消 <事件名>（目标 …）}。</li>
     * </ol>
     */
    private static final Set<String> WIRED = Set.of(
            NAMESPACE + ":mc_livingattackevent",
            NAMESPACE + ":mc_livinghurtevent",
            NAMESPACE + ":mc_livingdeathevent",
            NAMESPACE + ":mc_livingdropsevent",
            NAMESPACE + ":mc_criticalhitevent");

    /** 落盘口径（与工程其它 config 一致：缩进 + 不转义 HTML 字符）。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 物品 id 的字符集（与原版 {@code ResourceLocation} 一致；见 {@link #normalizeItemId} 的注释）。 */
    private static final java.util.regex.Pattern NAMESPACE_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+");

    private static final java.util.regex.Pattern PATH_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9/._-]+");

    /** 内存里的登记表（键 = 完整物品 id；值不可变）。🔴 归属人也在这张表里（{@code Entry#owner}），不另存一份。 */
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static volatile boolean loaded;

    private ShanhaiRealityInputStore() {}

    // ------------------------------------------------------------------ 数据形状

    /**
     * 一条登记（不可变）。
     *
     * @param owner 登记者 UUID 的字符串形式；<b>空串 = 老文件/手写文件没写 owner</b>
     *              （此时 {@code 自己} 这一档不匹配，见类注释 §二）。字段顺序与 JSON 里的顺序一致。
     */
    public record Entry(String target, String action, long ts, String owner) {

        public String describe() {
            return "target=" + this.target + " action=" + this.action + " ts=" + this.ts
                    + " owner=" + (this.owner == null || this.owner.isEmpty() ? "<none>" : this.owner);
        }

        /** 有没有归属人（{@code 自己} 这一档的前置条件）。 */
        public boolean hasOwner() {
            return this.owner != null && !this.owner.isEmpty();
        }
    }

    // ------------------------------------------------------------------ 路径 / 读

    /** {@code <gameDir>/config/shanhai/mc_bottom_inputs.json}。 */
    public static Path path() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve(RELATIVE_PATH).toAbsolutePath().normalize();
    }

    /** 惰性读一次（第一次被问到时读盘；之后只走内存）。 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (ShanhaiRealityInputStore.class) {
            if (loaded) {
                return;
            }
            reloadFromDisk();
            loaded = true;
        }
    }

    /** 强制重读磁盘（自检 / 手动改了文件时用）。 */
    public static synchronized void reloadFromDisk() {
        ENTRIES.clear();
        final Path target = path();
        if (!Files.isRegularFile(target)) {
            ShanhaiMod.LOGGER.info("{} store_absent path={}（没有登记 = 空表，不报错）", PREFIX, target);
            return;
        }
        try {
            final String json = Files.readString(target, StandardCharsets.UTF_8);
            final java.util.List<String> issues = new java.util.ArrayList<>();
            final Map<String, Entry> parsed = parseJson(json, issues);
            ENTRIES.putAll(parsed);
            // 🔴 老文件/手写文件没有 owner 的，逐条打出来（别静默）：这类条目的「自己」不生效
            for (String issue : issues) {
                ShanhaiMod.LOGGER.warn("{} {}", PREFIX, issue);
            }
            ShanhaiMod.LOGGER.info("{} store_loaded path={} entries={} without_owner={} issues={}",
                    PREFIX, target, parsed.size(), withoutOwner(parsed), issues.size());
        } catch (Throwable t) {
            // 坏 JSON ⇒ 整体忽略、保留上一份内存（与本工程其它台账同一条规矩）
            ShanhaiMod.LOGGER.error("{} store_read_failed path={} err={}", PREFIX, target, t.toString());
        }
    }

    // ------------------------------------------------------------------ 纯函数：JSON ↔ 表（不碰任何 Minecraft 类）

    /**
     * 整表 → JSON 文本（<b>纯函数</b>：只碰 Gson）。
     *
     * <p>为什么单拎出来：这条 + {@link #parseJson} + {@link #matchesTarget} 是"重启之后「自己」还认不认人"
     * 的全部逻辑，抽成纯函数之后就能在<b>不进游戏</b>的情况下被真跑一遍
     * （`temp\reality-items\exp\RestartSim.java`：写 → 清空内存 → 读 → 匹配）。
     *
     * <p>字段顺序固定 {@code target / action / ts / owner}（与类注释 §一 的文件样例逐字一致）。
     */
    static JsonObject toJson(Map<String, Entry> map) {
        final JsonObject root = new JsonObject();
        if (map == null) {
            return root;
        }
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            final Entry v = e.getValue();
            if (v == null) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("target", v.target() == null ? "" : v.target());
            o.addProperty("action", v.action() == null ? "" : v.action());
            o.addProperty("ts", v.ts());
            o.addProperty("owner", v.owner() == null ? "" : v.owner());
            root.add(e.getKey(), o);
        }
        return root;
    }

    /** 整表 → JSON 文本（给 {@link #writeMap} / 离线对照用）。 */
    public static String toJsonText(Map<String, Entry> map) {
        return GSON.toJson(toJson(map));
    }

    /**
     * JSON 文本 → 整表（<b>纯函数</b>：只碰 Gson；坏条目只计进 {@code issues}，不抛、不静默）。
     *
     * @param issues 收"这一条为什么被忽略 / 为什么「自己」不生效"的人话（调用方负责打日志）
     */
    static Map<String, Entry> parseJson(String json, java.util.List<String> issues) {
        final Map<String, Entry> out = new LinkedHashMap<>();
        final JsonElement el = JsonParser.parseString(json);
        if (el == null || !el.isJsonObject()) {
            if (issues != null) {
                issues.add("store_ignored reason=not_object");
            }
            return out;
        }
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            final String id = normalizeItemId(e.getKey());
            if (id == null || !e.getValue().isJsonObject()) {
                if (issues != null) {
                    issues.add("store_skipped reason=bad_entry key=" + e.getKey());
                }
                continue;
            }
            final JsonObject o = e.getValue().getAsJsonObject();
            final String t = cap(optString(o, "target"), MAX_TARGET_CHARS);
            final String a = cap(optString(o, "action"), MAX_ACTION_CHARS);
            final long ts = optLong(o, "ts");
            final String owner = optString(o, "owner").trim();
            out.put(id, new Entry(t, a, ts, owner));
            if (owner.isEmpty()) {
                // 别静默：这一类条目的「自己」不生效（其余 3 档照常）
                if (issues != null) {
                    issues.add("entry_without_owner id=" + id
                            + "（老文件/手写文件没写 owner ⇒ 『自己』这一档对这条不生效；"
                            + "要恢复请重新登记一次）");
                }
            } else if (parseOwner(owner) == null) {
                if (issues != null) {
                    issues.add("entry_owner_unparsable id=" + id + " owner=" + owner
                            + "（不是合法 UUID ⇒ 『自己』这一档对这条不生效）");
                }
            }
        }
        return out;
    }

    /** 表里有几条没有归属人（自检/日志读数）。 */
    public static int withoutOwner(Map<String, Entry> map) {
        if (map == null) {
            return 0;
        }
        int n = 0;
        for (Entry e : map.values()) {
            if (e != null && !e.hasOwner()) {
                n++;
            }
        }
        return n;
    }

    /** 这一件物品的登记（没有 = {@code null}）。 */
    public static Entry get(String fullId) {
        ensureLoaded();
        final String id = normalizeItemId(fullId);
        if (id == null) {
            return null;
        }
        synchronized (ShanhaiRealityInputStore.class) {
            return ENTRIES.get(id);
        }
    }

    /** 当前登记条数（自检/日志用）。 */
    public static int size() {
        ensureLoaded();
        synchronized (ShanhaiRealityInputStore.class) {
            return ENTRIES.size();
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 登记一件：写文件成功之后才提交到内存。
     *
     * @param owner 登记者的 UUID（<b>写进文件</b>的 {@code owner} 字段；{@code null} ⇒ 写空串，
     *              那时只有一个后果：{@code target=自己} 这条不生效 —— 面板那条路不会传 null）
     * @return {@code false} = 没写盘（内存也<b>没</b>改），调用方必须如实告诉玩家
     */
    public static boolean save(String fullId, String target, String action, UUID owner) {
        ensureLoaded();
        final String id = normalizeItemId(fullId);
        if (id == null) {
            ShanhaiMod.LOGGER.warn("{} save_rejected reason=bad_item_id id={}", PREFIX, fullId);
            return false;
        }
        final String t = cap(target, MAX_TARGET_CHARS).trim();
        final String a = cap(action, MAX_ACTION_CHARS).trim();
        if (t.isEmpty() || a.isEmpty()) {
            ShanhaiMod.LOGGER.warn("{} save_rejected reason=empty_field id={} target_len={} action_len={}",
                    PREFIX, id, t.length(), a.length());
            return false;
        }
        final String ownerText = owner == null ? "" : owner.toString();
        final Entry entry = new Entry(t, a, System.currentTimeMillis(), ownerText);
        synchronized (ShanhaiRealityInputStore.class) {
            final Map<String, Entry> next = new LinkedHashMap<>(ENTRIES);
            next.put(id, entry);
            if (!writeMap(next)) {
                return false;
            }
            ENTRIES.clear();
            ENTRIES.putAll(next);
        }
        ShanhaiMod.LOGGER.info("{} save_ok id={} {} wired={} entries={}", PREFIX, id, entry.describe(),
                isWired(id), size());
        return true;
    }

    /** 把整张表原子写盘（成功才由调用方提交到内存）。内容 = {@link #toJsonText}（唯一一条序列化路径）。 */
    private static boolean writeMap(Map<String, Entry> map) {
        final Path target = path();
        final String json = toJsonText(map);
        try {
            final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.createDirectories(target.getParent());
            try (java.io.Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(json);
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

    // ------------------------------------------------------------------ 取消下一次（5 件真接线的事件调这一条）

    /**
     * 「若这一件登记过『取消』且目标匹配，就消耗掉这一次（一次性），并给登记者报一行」。
     *
     * <p><b>唯一的消耗点</b>：5 个事件处理器各调一次，谁先匹配谁消耗 —— 之后这条登记在内存与文件里
     * 都<b>不存在</b>了（下次不会再触发）。
     *
     * <p>⚠️ 这个方法在<b>每一个</b>对应事件上都会被调（一场战斗里上百次）⇒ 第一行的空表快速失败
     * 是必需的：没有登记的世界一次字符串归一化都不做。
     *
     * @param fullId 哪一件（{@code shanhai:mc_livingattackevent} 这种）
     * @param entity 这次事件的主体（{@code LivingEvent#getEntity()} / {@code CriticalHitEvent#getEntity()}）
     * @return {@code true} = 这次该被取消（调用方去 {@code setCanceled(true)} / {@code setResult(DENY)}）
     */
    public static boolean cancelNext(String fullId, LivingEntity entity) {
        if (fullId == null || entity == null) {
            return false;
        }
        // 快速失败：没有登记的世界，一场战斗里上百次事件都只走这两行（热路径）
        if (!loaded) {
            ensureLoaded();
        }
        if (ENTRIES.isEmpty()) {
            return false;
        }
        // 5 个 handler 传进来的已经是规范化 id（shanhai:mc_xxx）⇒ 先按原串查，查不到才归一（省一次分配）
        String id = fullId;
        Entry e = ENTRIES.get(id);
        if (e == null) {
            id = normalizeItemId(fullId);
            if (id == null) {
                return false;
            }
            e = ENTRIES.get(id);
            if (e == null) {
                return false;
            }
        }
        // 🔴 第二道护栏（第一道是"只有那 5 个 handler 会调本方法"）：未接线的 60 件
        //    即使在别处被误接上一个事件，也【绝不】产生"已取消"—— 登记归登记，效果不假装。
        if (!WIRED.contains(id)) {
            return false;
        }
        if (!isCancelAction(e.action())) {
            return false;          // 「记录」= 只留档，不取消（不算错误，也不消耗）
        }
        // 🔴 归属人从【这条登记自己身上】拿（落盘的那个 owner 字段）——
        //    不再有"内存里的 OWNERS 表"这种东西：重启之后就没人记得谁是登记者了，那才是真 bug。
        final UUID owner = parseOwner(e.owner());
        if (!matches(e.target(), entity, owner)) {
            return false;          // 目标不匹配 ⇒ 不动这条登记（下次别人触发时还有机会）
        }
        final Entry hit;
        synchronized (ShanhaiRealityInputStore.class) {
            if (!ENTRIES.containsKey(id)) {
                return false;      // 竞态：别的线程刚消耗掉
            }
            // 🔴 用完即清：内存 + 文件都删（一次性）。写盘失败也清内存 ——
            //    因为这一次已经取消了，留着会让同一件物品再取消第二次（那就是另一个 bug 了）。
            final Map<String, Entry> next = new LinkedHashMap<>(ENTRIES);
            next.remove(id);
            final boolean written = writeMap(next);
            ENTRIES.clear();
            ENTRIES.putAll(next);
            hit = e;
            if (!written) {
                ShanhaiMod.LOGGER.error("{} cancel_cleared_but_write_failed id={}（内存已清，文件里可能还在）",
                        PREFIX, id);
            }
        }
        ShanhaiMod.LOGGER.info("{} cancel_once id={} target={} entity={}（一次性，已从内存与文件里清掉）",
                PREFIX, id, hit.target(), entityName(entity));
        messageOwner(owner, Component.translatable("shanhai.reality_input.chat.cancelled",
                displayName(id), hit.target()));
        return true;
    }

    /**
     * 目标匹配的<b>运行时入口</b>：只负责把 {@code LivingEntity} 拆成三样"纯数据"
     * （UUID / 玩家名 / 实体类型 id），判定全部交给 {@link #matchesTarget}。
     *
     * <p>为什么要拆：判定逻辑一旦碰 Minecraft 类就没法在<b>不进游戏</b>的情况下被跑一遍，
     * 而"重启之后「自己」还认不认人"恰恰是这个功能最容易出错的地方
     * （判据见 {@code temp\reality-items\exp\RestartSim.java}）。
     */
    public static boolean matches(String target, LivingEntity entity, UUID owner) {
        if (entity == null) {
            return false;
        }
        final String playerName = entity instanceof Player p && p.getGameProfile() != null
                ? p.getGameProfile().getName() : null;
        String entityTypeId = null;
        try {
            final ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            entityTypeId = type == null ? null : type.toString();
        } catch (Throwable ignored) {
            // 注册表没就绪 ⇒ 类型 id 这一档不参与（其它档照常），不抛
        }
        return matchesTarget(target, entity.getUUID(), playerName, entityTypeId, owner);
    }

    /**
     * 目标匹配（<b>纯函数</b>，5 个事件共用一套语义；不碰任何 Minecraft 类 ⇒ 可离线真跑）。
     *
     * <table border="1">
     *   <tr><th>登记里的 target</th><th>要什么</th><th>匹配谁</th></tr>
     *   <tr><td>{@code 自己}（也认 {@code self} / {@code @s}）</td><td>{@code owner} 非空</td>
     *       <td>UUID 等于 {@code owner} 的那个玩家（<b>owner 来自登记里落盘的那一栏，重启后照旧</b>）</td></tr>
     *   <tr><td>{@code @a}</td><td>—</td><td>任何玩家</td></tr>
     *   <tr><td>玩家名（先精确、再忽略大小写）</td><td>—</td><td>名字对得上的玩家</td></tr>
     *   <tr><td>实体类型 id（{@code minecraft:zombie} 或裸 {@code zombie}）</td><td>—</td>
     *       <td>该类型的实体（忽略大小写）</td></tr>
     *   <tr><td>其它 {@code @x} 选择器 / 空串</td><td>—</td><td><b>谁都不匹配</b>（不猜、不放宽）</td></tr>
     * </table>
     *
     * @param entityUuid   事件主体的 UUID
     * @param playerName   事件主体是玩家时的名字，否则 {@code null}（= "它不是玩家"）
     * @param entityTypeId 事件主体的实体类型 id（{@code minecraft:zombie} 这种），取不到就 {@code null}
     * @param owner        登记里的 owner 解析出来的 UUID；没有/非法 = {@code null} ⇒「自己」不匹配
     */
    public static boolean matchesTarget(String target, UUID entityUuid, String playerName,
                                        String entityTypeId, UUID owner) {
        if (target == null) {
            return false;
        }
        final String t = target.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.equals(TARGET_SELF) || t.equalsIgnoreCase("self") || t.equalsIgnoreCase("@s")) {
            return owner != null && entityUuid != null && playerName != null && owner.equals(entityUuid);
        }
        if (t.equalsIgnoreCase(TARGET_ALL_PLAYERS)) {
            return playerName != null;
        }
        if (t.startsWith("@")) {
            return false;      // 需求只定义了 @a / 自己 / 名字 / 类型 id；别的选择器一律不认
        }
        if (playerName != null && (playerName.equals(t) || playerName.equalsIgnoreCase(t))) {
            return true;
        }
        if (entityTypeId == null) {
            return false;
        }
        if (entityTypeId.equalsIgnoreCase(t)) {
            return true;
        }
        final int colon = entityTypeId.indexOf(':');
        final String pathOnly = colon >= 0 ? entityTypeId.substring(colon + 1) : entityTypeId;
        return pathOnly.equalsIgnoreCase(t);
    }

    /** {@code owner} 字符串 → UUID；空串/不是 UUID ⇒ {@code null}（不抛）。 */
    public static UUID parseOwner(String owner) {
        if (owner == null) {
            return null;
        }
        final String s = owner.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 已接线表

    /** 这一件是否已接线（60 件里的一件 ⇒ {@code false}）。 */
    public static boolean isWired(String fullId) {
        final String id = normalizeItemId(fullId);
        return id != null && WIRED.contains(id);
    }

    /** 已接线的 5 个 id（自检/审计用；只读副本）。 */
    public static Set<String> wiredIds() {
        return new LinkedHashSet<>(WIRED);
    }

    /** 已接线条数（判据用：必须恒等于 5）。 */
    public static int wiredCount() {
        return WIRED.size();
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把各种写法归一成 {@code shanhai:<path>}；非法返回 {@code null}。
     *
     * <p>接受：{@code shanhai:mc_x} / {@code mc_x}（补命名空间，<b>容错读旧/手写文件</b>）。
     *
     * <p>🔴 这里<b>故意不用</b> {@code ResourceLocation.tryParse}：它会把整条
     * {@code Codec} 静态初始化链拖进来（datafixerupper → fastutil …），
     * 于是"纯 JSON + 纯匹配"这条路就没法在<b>不进游戏</b>的 JVM 里被跑一遍
     * （{@code temp\reality-items\exp\RestartSim.java} 就是靠这条路做"重启"对照的）。
     * 字符集口径与原版一致：命名空间 {@code [a-z0-9_.-]+}，path {@code [a-z0-9/._-]+}；大小写敏感。
     */
    public static String normalizeItemId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.indexOf(':') < 0) {
            s = NAMESPACE + ":" + s;
        }
        final int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            return null;
        }
        final String namespace = s.substring(0, colon);
        final String path = s.substring(colon + 1);
        if (!NAMESPACE_PATTERN.matcher(namespace).matches() || !PATH_PATTERN.matcher(path).matches()) {
            return null;
        }
        return namespace + ":" + path;
    }

    /** {@code action} 里含"取消"（也认 {@code cancel}）= 要取消；空/其它 = 不取消。 */
    public static boolean isCancelAction(String action) {
        if (action == null) {
            return false;
        }
        return action.contains(ACTION_CANCEL) || action.toLowerCase(java.util.Locale.ROOT).contains("cancel");
    }

    /** 物品 id → 显示名（{@code Component}，按客户端语言渲染）。取不到就回退到 id 本身。 */
    public static Component displayName(String fullId) {
        final String id = normalizeItemId(fullId);
        if (id == null) {
            return Component.literal(String.valueOf(fullId));
        }
        try {
            final ResourceLocation rl = new ResourceLocation(id);
            final net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new net.minecraft.world.item.ItemStack(item).getHoverName();
            }
        } catch (Throwable ignored) {
            // 注册表没就绪/不是我们的物品 ⇒ 落到 id
        }
        return Component.literal(id);
    }

    /** 给某个玩家发一行聊天（服务端权威；不在线 = 只留日志）。 */
    private static void messageOwner(UUID owner, Component line) {
        if (owner == null) {
            return;
        }
        try {
            final MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            final ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) {
                p.displayClientMessage(line, false);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} message_owner_failed err={}", PREFIX, t.toString());
        }
    }

    private static String entityName(Entity e) {
        if (e == null) {
            return "?";
        }
        try {
            final ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            final String name = e instanceof Player p && p.getGameProfile() != null
                    ? p.getGameProfile().getName() : null;
            return (name == null ? "" : name + " ") + "(" + (type == null ? "?" : type.toString()) + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String optString(JsonObject o, String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long optLong(JsonObject o, String key) {
        try {
            final JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? 0L : e.getAsLong();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 夹长度（按字符，不按字节；中文一字一字符）。 */
    public static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        final String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 开机一行<b>机器可判</b>读数（{@code grep [SHANHAI-REALITY-INPUT]} 就能看）。
     *
     * <p>判据：① 已接线必须恰好 5 件；② 文件路径必须是需求里那一条；
     * ③ 清单里 5 个 id 必须都在 {@code shanhai:mc_} 段（写错命名空间 ⇒ 永远匹配不到）。
     */
    public static String selfCheck() {
        ensureLoaded();
        int pass = 0;
        final int total = 4;
        final StringBuilder bad = new StringBuilder();
        if (wiredCount() == 5) {
            pass++;
        } else {
            bad.append(" wired=").append(wiredCount());
        }
        if (RELATIVE_PATH.equals("config/shanhai/mc_bottom_inputs.json")) {
            pass++;
        } else {
            bad.append(" path=").append(RELATIVE_PATH);
        }
        int badIds = 0;
        for (String id : WIRED) {
            if (!id.startsWith(NAMESPACE + ":mc_")) {
                badIds++;
            }
        }
        if (badIds == 0) {
            pass++;
        } else {
            bad.append(" bad_ids=").append(badIds);
        }
        if (path().endsWith("config\\shanhai\\mc_bottom_inputs.json")
                || path().toString().endsWith("config/shanhai/mc_bottom_inputs.json")) {
            pass++;
        } else {
            bad.append(" resolved=").append(path());
        }
        final boolean ok = pass == total;
        final String line = "selfcheck " + pass + "/" + total + " PASS=" + ok + " wired=" + wiredCount()
                + " entries=" + size() + " without_owner=" + withoutOwner(ENTRIES)
                + " path=" + RELATIVE_PATH + " wired_ids=" + new ArrayList<>(WIRED)
                + (ok ? "" : " bad=" + bad);
        return line;
    }

    /** 日志里打印用的短清单。 */
    public static List<String> wiredList() {
        return new ArrayList<>(WIRED);
    }
}
