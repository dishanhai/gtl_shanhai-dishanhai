package com.shanhai.client.holo;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 山海重构 · 全息菜单的「<b>附近有没有外来实体在场</b>」信号源（纯客户端、只读、限流）。
 *
 * <h2>1. 为什么要有它</h2>
 * "面板打不开 / 打开了却看不见"这类问题里，有一个信号旧日志<b>从来没有</b>：
 * <b>那一刻附近有没有别的东西在场</b>（别的模组加的生物、效果实体、残留的特效替身 …）。
 * 没有这个信号，"是不是被某个模组在场时干扰的"就只能靠回忆和猜。
 * 本类把这件事变成<b>每一条点击日志里都带着的机器可判字段</b>。
 *
 * <h2>2. 判什么、怎么判</h2>
 * <pre>
 *   每 {@link #SCAN_PERIOD_TICKS}（20）tick，扫一次<b>客户端已加载</b>的实体：
 *     level.getEntitiesOfClass(Entity.class, 玩家包围盒.inflate(48), 谓词)
 *   谓词 = 该实体的 {@code EntityType} 的<b>注册名命名空间</b>不是 {@code minecraft}
 *          （口径在 {@link ShanhaiHoloViewMath#isForeignId}）
 *   ⇒ 记 {@code count / nearest / types}，并取其中<b>最近</b>的那一只记 {@code env_near / env_type / env_dist}
 * </pre>
 *
 * <h3>2.1 🔴 为什么按"命名空间是不是 minecraft"判，而不写具体类名/具体模组</h3>
 * 本类<b>不许认识任何具体模组</b>（用户口径：不许写针对某个 mod 的代码）。所以判据只能落在
 * 一个与模组无关的事实上："这条注册名的命名空间是游戏本体，还是别人"。
 * <ul>
 *   <li>不需要在编译期依赖任何第三方模组的任何类（本模组对它们<b>零编译依赖</b>，也不反射它们的字段）；</li>
 *   <li>那个模组换了实体、改了名字，本条判据<b>不用跟着改</b>；</li>
 *   <li>反过来：原版的猪/牛/僵尸<b>不算</b>外来（{@code minecraft:pig} ⇒ false，自检里有这条负对照）。</li>
 * </ul>
 *
 * <h3>2.2 为什么不会给服务端加负担</h3>
 * 全程在客户端：{@code ClientLevel} 的实体分片表是客户端从网络包里<b>已经收下来</b>的那份
 * （只含已加载区块）⇒ 这个查询<b>不发包、不问服务端</b>，也不做全实体表遍历。
 * 频率是 1 次 / 20 tick（≈ 1 次/秒），AABB 边长 96 格 ≈ 36 个区块的实体分片。
 *
 * <h3>2.3 两种触发（刻意不是一种）</h3>
 * <table border="1">
 *   <tr><th>入口</th><th>谁调</th><th>为什么</th></tr>
 *   <tr><td>{@link #tick}</td><td>{@code ShanhaiHoloMenuInput.onClientTick}</td>
 *       <td>持续维护"有没有外来实体"这个状态；<b>开↔关切换时</b>打一行 {@code env_near changed}。
 *           ⚠️ tick 在 {@code Minecraft.tick()} 的末尾（offset 828），比右键事件<b>晚</b>
 *           ⇒ 只靠它，点击那一行会用到最多 1 秒前的旧值。</td></tr>
 *   <tr><td>{@link #refreshNow}</td><td>{@code holo_click} 那一行日志之前（同一拍）</td>
 *       <td>点击那一行要的是<b>这一拍</b>的真相。它只是把"下一次扫描"提前到这一拍，不改变任何判定。</td></tr>
 * </table>
 *
 * <h2>3. 它【不】做什么（硬要求：纯观测、不改已验收行为）</h2>
 * <ul>
 *   <li>不参与任何动作分派 —— 投影开不开、面板开不开、点哪一格，<b>与它一个字节的关系都没有</b>；</li>
 *   <li>不改任何状态：只有本类自己的只读字段（{@code near/typeId/dist/count} + 附近注册名探针）
 *       与一个扫描游标 {@code lastScanTick}；</li>
 *   <li>拿不到 level/player 时<b>直接返回</b>（不刷日志、不重置），重置只走 {@link #clear}。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloEnvScan {

    private ShanhaiHoloEnvScan() {}

    /** 日志前缀（与 {@code ShanhaiHoloMenuInput} 同一个，便于一次 grep 全拿到）。 */
    public static final String PREFIX = ShanhaiHoloMenuInput.PREFIX;

    /** 扫描半径（格）：任务书点名 48。 */
    public static final double SCAN_RADIUS_BLOCKS = 48.0;

    /** 扫描周期（tick）：任务书点名 20（≈ 1 秒）。 */
    public static final int SCAN_PERIOD_TICKS = 20;

    /** {@code types=} 的条数上限（"当时有哪些东西在场"够看就行，不追求全量）。 */
    public static final int TYPE_MAX = 4;

    // ------------------------------------------------------------------ 只读状态（全工程唯一一份）

    /** 现在附近有没有外来（非原版命名空间）实体。 */
    private static boolean near;

    /** 最近那一个外来实体的注册名（没有 = {@code "none"}）。 */
    private static String typeId = "none";

    /** 到最近那一个外来实体的距离（格；没有 = {@code -1}）。 */
    private static double dist = -1.0;

    /** 上一次扫描时命中的外来实体数量（诊断用：>1 说明附近不止一个）。 */
    private static int count;

    /** 上一次扫描时出现过的注册名（去重、保序、最多 {@link #TYPE_MAX} 条）。 */
    private static final String[] TYPES = new String[TYPE_MAX];

    /** {@link #TYPES} 的有效条数。 */
    private static int typeCount;

    /** 上一次扫描的 tick（{@link Integer#MIN_VALUE} = 还没扫过）。 */
    private static int lastScanTick = Integer.MIN_VALUE;

    /** 累计扫描次数（诊断用：判"限流真的生效了"）。 */
    private static int scans;

    /** 附近注册名探针上限（够看清"这一片到底有哪些东西"就行）。 */
    private static final int NS_PROBE_MAX = 6;

    /**
     * 上一次扫描时、半径内<b>所有</b>实体的注册名（最多 {@link #NS_PROBE_MAX} 条）。
     * <p>为什么要有它：判据只看命名空间，所以"判成没有外来实体"有两种完全不同的原因 ——
     * ① 这一片真的只有原版生物；② 判据本身想错了。这个探针把**它真正看到的注册名**打出来，
     * 于是这两种原因<b>自己分开</b>，不用再猜第二轮。
     */
    private static final String[] NS_PROBE = new String[NS_PROBE_MAX];

    /** {@link #NS_PROBE} 的有效条数。 */
    private static int nsProbeCount;

    /** 探针打过几次（只在"点击那一拍一个外来实体都没扫到"时打一次）。 */
    private static int nsProbeLogged;

    // ==================================================================== 对外读数

    /** 现在附近有没有外来实体（{@code env_near=} 字段的唯一来源）。 */
    public static boolean anyForeign() {
        return near;
    }

    /** 最近那一个外来实体的注册名（没有 = {@code none}）。 */
    public static String nearestId() {
        return typeId;
    }

    /** 到最近那一个外来实体的距离（没有 = {@code -1}）。 */
    public static double nearestDist() {
        return dist;
    }

    /**
     * 上一次扫描时，半径内外来命名空间的实体数（遮挡探针的 {@code count=}）。
     * <p>⚠️ 它和 {@link #anyForeign()} 不是一件事：{@code anyForeign} 是"有没有"（布尔），这里是"一共几个"。
     */
    public static int foreignCount() {
        return count;
    }

    /** 其中最近的那一个到玩家的距离（格；没有 = {@code -1}）—— 遮挡探针的 {@code nearest=}。 */
    public static double foreignNearest() {
        return count > 0 ? dist : -1.0;
    }

    /**
     * 上一次扫描时出现过的注册名（去重、最多 {@link #TYPE_MAX} 条），形如
     * {@code examplemod:thing,example:other}；一条都没有 ⇒ {@code none}。
     * <p>用途：一眼看清"当时到底有哪些东西在场"。
     */
    public static String foreignTypesText() {
        if (typeCount <= 0) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < typeCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(TYPES[i]);
        }
        return sb.toString();
    }

    /**
     * 点击日志里那三个字段（<b>唯一</b>拼装点）：{@code env_near=true|false env_type=<id 或 none> env_dist=<n>}。
     */
    public static String line() {
        return "env_near=" + near + " env_type=" + ShanhaiHoloViewMath.idText(typeId)
                + " env_dist=" + ShanhaiHoloViewMath.fmt2(dist);
    }

    /** 距离上一次扫描过了几 tick（没扫过 = {@code -1}）。 */
    public static int ageTicks(LocalPlayer player) {
        if (player == null || lastScanTick == Integer.MIN_VALUE) {
            return -1;
        }
        return player.tickCount - lastScanTick;
    }

    /** 一行完整读数（给 {@code /shanhai menu dump} 与排障用）。 */
    public static String describe() {
        return "env_near=" + near + " type=" + ShanhaiHoloViewMath.idText(typeId)
                + " dist=" + ShanhaiHoloViewMath.fmt2(dist)
                + " count=" + count + " radius=" + ShanhaiHoloViewMath.fmt2(SCAN_RADIUS_BLOCKS)
                + " period_ticks=" + SCAN_PERIOD_TICKS + " scans=" + scans
                + " nearby=" + nsProbeText();
    }

    /** JSON 形态（进 {@code /shanhai menu dump} 的 visibility_dump）。 */
    public static String dumpJson() {
        return "{\"near\":" + near
                + ",\"type\":\"" + ShanhaiHoloViewMath.idText(typeId) + '"'
                + ",\"dist\":" + ShanhaiHoloViewMath.fmt2(dist)
                + ",\"count\":" + count
                + ",\"radius\":" + ShanhaiHoloViewMath.fmt2(SCAN_RADIUS_BLOCKS)
                + ",\"period_ticks\":" + SCAN_PERIOD_TICKS
                + ",\"scans\":" + scans
                + ",\"nearby\":\"" + ShanhaiHoloViewMath.jsonEsc(nsProbeText()) + '"'
                + ",\"rule\":\"namespace!=minecraft\"}";
    }

    // ==================================================================== 两个入口

    /**
     * 周期入口：每 {@link #SCAN_PERIOD_TICKS} tick 扫一次（<b>只在有玩家/有世界时跑</b>）。
     * <p>由 {@code ShanhaiHoloMenuInput.onClientTick} 调（那里已经做过 lp/level 的空判）。
     */
    public static void tick(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) {
            return;
        }
        final int now = player.tickCount;
        if (lastScanTick != Integer.MIN_VALUE && now >= lastScanTick
                && now - lastScanTick < SCAN_PERIOD_TICKS) {
            return;                                     // 限流：一只 tick 都不多扫
        }
        scan(level, player, "periodic");
    }

    /**
     * 立即入口：把"下一次扫描"提前到这一拍（<b>只给点击日志用</b>，见类注释 §2.3）。
     * <p>⚠️ 它<b>不</b>改变任何判定，只是让 {@code holo_click} 那一行的 {@code env_near} 是这一拍的真相。
     */
    public static void refreshNow(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) {
            return;
        }
        scan(level, player, "click");
    }

    /**
     * 退出世界 / 断线时清干净（{@code ShanhaiHoloMenuClient.onLoggingOut} 调）。
     * <p>不清的后果：下次进世界时 {@code holo_click} 会带着上一次世界的 {@code env_near=true} —— 一条会撒谎的读数。
     */
    public static void clear(String why) {
        final boolean wasNear = near;
        near = false;
        typeId = "none";
        dist = -1.0;
        count = 0;
        typeCount = 0;
        lastScanTick = Integer.MIN_VALUE;
        nsProbeCount = 0;
        nsProbeLogged = 0;
        if (wasNear) {
            ShanhaiMod.LOGGER.info("{} env_near changed now=false type=none dist=-1.00 why={}",
                    PREFIX, why);
        }
    }

    // ==================================================================== 扫描本体

    /**
     * 扫一次（{@link #tick} 与 {@link #refreshNow} 的<b>唯一</b>实现）。
     *
     * @param why 进日志：{@code periodic} / {@code click}（"这次切换是谁发现的"）
     */
    private static void scan(ClientLevel level, LocalPlayer player, String why) {
        lastScanTick = player.tickCount;
        scans++;
        // ① 只问【已加载】的实体：AABB 查询走客户端实体分片表，不做全表遍历、不发包
        final AABB box = player.getBoundingBox().inflate(SCAN_RADIUS_BLOCKS);
        final List<Entity> entities = level.getEntitiesOfClass(Entity.class, box, e -> e != player);
        // ①b 探针：这个半径里到底有哪些注册名（"判成没有"的两种原因靠它自己分开）
        nsProbeCount = 0;
        for (int i = 0; i < entities.size() && nsProbeCount < NS_PROBE_MAX; i++) {
            final Entity e = entities.get(i);
            if (e == null) {
                continue;
            }
            final String id = registryId(e.getType());
            boolean dup = false;
            for (int k = 0; k < nsProbeCount; k++) {
                if (NS_PROBE[k] != null && NS_PROBE[k].equals(id)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                NS_PROBE[nsProbeCount++] = id;
            }
        }
        // ② 取最近的一个外来实体（同距离取先出现的：顺序稳定，便于复现）
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        int countNow = 0;
        typeCount = 0;
        for (int i = 0; i < entities.size(); i++) {
            final Entity e = entities.get(i);
            if (e == null || e.isRemoved()) {
                continue;
            }
            final String id = registryId(e.getType());
            if (!ShanhaiHoloViewMath.isForeignId(id)) {
                continue;
            }
            final double d = player.distanceTo(e);
            countNow++;
            if (d < best) {
                best = d;
                nearest = e;
            }
            collectType(id);
        }
        // ③ 落状态（只此一处）
        final boolean wasNear = near;
        near = nearest != null;
        typeId = near ? registryId(nearest.getType()) : "none";
        dist = near ? best : -1.0;
        count = countNow;
        // ④ 🔴 状态变更日志：**只在 开↔关 切换时**打一行（任务书点名的那一行）
        if (near != wasNear) {
            ShanhaiMod.LOGGER.info("{} env_near changed now={} type={} dist={} why={} radius={}",
                    PREFIX, near, ShanhaiHoloViewMath.idText(typeId), ShanhaiHoloViewMath.fmt2(dist),
                    why, ShanhaiHoloViewMath.fmt2(SCAN_RADIUS_BLOCKS));
        }
        // ⑤ 探针：只在"点击那一拍一个外来实体都没扫到"时打一次 —— 这是"确实只有原版"的唯一可见痕迹。
        //    打出来的就是它真正看到的注册名（人类一眼能认出这一片都有什么）。
        if (!near && "click".equals(why) && nsProbeLogged == 0) {
            nsProbeLogged = 1;
            ShanhaiMod.LOGGER.info("{} env_scan ns_probe now=false radius={} ids={} "
                            + "（判据 = 注册名命名空间 != {}）",
                    PREFIX, ShanhaiHoloViewMath.fmt2(SCAN_RADIUS_BLOCKS),
                    nsProbeText(), ShanhaiHoloViewMath.VANILLA_NAMESPACE);
        }
    }

    /** 探针的文本形态：{@code [example:thing, minecraft:pig]} / {@code [none]}。 */
    public static String nsProbeText() {
        if (nsProbeCount <= 0) {
            return "[none]";
        }
        final StringBuilder sb = new StringBuilder(96);
        sb.append('[');
        for (int i = 0; i < nsProbeCount; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(NS_PROBE[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 记一条外来实体的注册名（去重、保序、最多 {@link #TYPE_MAX} 条）。
     * <p>满了就<b>静默丢弃</b>：这个字段的用途是"一眼看清当时有哪些东西在场"，
     * 不是审计全量（全量条数在 {@link #foreignCount()} 里，一条都不少）。
     */
    private static void collectType(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        for (int i = 0; i < typeCount; i++) {
            if (id.equals(TYPES[i])) {
                return;                                 // 去重：同一种只记一次
            }
        }
        if (typeCount < TYPE_MAX) {
            TYPES[typeCount++] = id;
        }
    }

    /**
     * 实体类型的注册名（{@code examplemod:thing} 这种形态）；拿不到给 {@code ""}（⇒ 不算外来）。
     * <p>真值来源（javap 取证过）：{@code EntityType.getKey(EntityType)} 是 <b>public static</b>，
     * 实现是 {@code BuiltInRegistries.ENTITY_TYPE.getKey(this)}；拿不到时退到
     * {@code toString()}（= {@code getDescriptionId()}，形如 {@code entity.<ns>.<path>}）。
     */
    private static String registryId(EntityType<?> type) {
        if (type == null) {
            return "";
        }
        final ResourceLocation key = EntityType.getKey(type);
        if (key != null) {
            return key.toString();
        }
        // 兜底：getDescriptionId 的 "entity.<ns>.<path>" 形态（只在注册键缺失时才会走到）
        final String desc = String.valueOf(type);
        final String ns = ShanhaiHoloViewMath.namespaceOfDescriptionId(desc);
        return ns.isEmpty() ? "" : ns + ":?";
    }
}
