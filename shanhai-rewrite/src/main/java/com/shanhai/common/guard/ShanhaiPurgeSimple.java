package com.shanhai.common.guard;

import java.util.LinkedHashMap;
import java.util.Map;

import com.shanhai.ShanhaiMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/**
 * 山海 · <b>本体自带的"清除实体"</b>：逐维度执行原版 {@code kill @e[type=!player]}。
 *
 * <p>用户口径原话（2026-10-18）：「<b>清除实体就是一个 kill@e</b>」—— 本体不写自己的遍历/逐级逻辑，
 * 把"怎么删"完全交给原版指令；装了扩展（{@code shanhai_add}）且它愿意接的时候，
 * 才由扩展的逐级版接管（判定见 {@code com.shanhai.api.PurgeRoute}）。
 *
 * <h2>🔴 为什么必须<b>逐维度</b>跑</h2>
 * {@code kill @e[…]} 只在<b>执行者所在的维度</b>生效（选择器的实体表来自 {@code source.getLevel()}）。
 * 只跑一次就只覆盖约 1/3 的实体，而且控制台来源的维度栈绑在 overworld —— 表现为
 * "主世界清干净了，下界/末地还有一堆"。所以
 * {@code for (ServerLevel lv : server.getAllLevels())} 每层各跑一次，返回值累加。
 *
 * <h2>🔴 为什么用原版指令而不是自己遍历</h2>
 * <ol>
 *   <li>原版 {@code /kill} 自己走 {@code Entity#kill() → discard() → 区域广播}，客户端表现一致；</li>
 *   <li>自己遍历就要处理"边遍历边删"（快照抄法），而那正是<b>逐级逻辑</b>的入口 —— 那是扩展的活；</li>
 *   <li>{@code @e[type=!player]} 一行就把"跳过玩家"写清楚了，比手写 instanceof 更难写错。</li>
 * </ol>
 *
 * <h2>🔴 为什么内层来源用"无实体的服务端来源"（{@code server.createCommandSourceStack()}）</h2>
 * 而不是 {@code actor.createCommandSourceStack()}（设计 §3.3 伪代码那种写法）—— 两个实打实的理由：
 * <ol>
 *   <li><b>玩家来源会被本仓自己的时钟守卫拦掉</b>：{@code ShanhaiClockGuard#onCommand} 在时钟暂停时
 *       对<b>玩家发起</b>的世界写指令做黑名单取消，{@code "kill"} 就在名单里
 *       （{@code WORLD_CHANGING_COMMANDS}）。玩家带着实体来源跑 {@code kill} ⇒ 被取消。</li>
 *   <li>🔴 <b>被取消的指令，Forge 的 {@code Commands#performCommand} 返回 1，不是 0</b>
 *       （反编译实测：事件被 post 返回 true 时走 {@code iconst_1; ireturn}）。
 *       如果直接拿返回值当"抹掉了几只"，冻结期间玩家点一次「清除实体」就会印
 *       「已抹除 1 只」而<b>一只都没删</b> —— 正是本工程"活界面不许假数据"那条红线。</li>
 * </ol>
 * 无实体来源同时满足：① 时钟守卫的判据第一句就是 {@code if (source.getEntity() == null) return}
 * （控制台/命令方块是运维通道）；② 与"执行者是谁"解耦 ⇒ 玩家点板、控制台敲命令、命令方块
 * 触发，三条入口的数字口径完全一致；③ 权限由 {@link #PERMISSION} 固定，不受执行者 OP 等级影响。
 * <b>{@code actor} 只用于日志/播报，不参与"删谁"</b>。
 *
 * <h2>安全默认</h2>
 * <ul>
 *   <li><b>玩家一律不删</b>（选择器 {@code type=!player}；{@link Result#skippedPlayers} 只用于回显/日志）；</li>
 *   <li>某维度抛异常 ⇒ <b>只记日志、这一维度按 0 计，其余维度继续</b>，绝不把异常抛回命令调用方；</li>
 *   <li>只数不抹（{@code /shanhai purge count}）走 <b>纯只读遍历</b>，一个实体都不删。</li>
 * </ul>
 *
 * <h2>日志判据（设计 §3.3 的三条 ＋ 逐维度明细）</h2>
 * <pre>
 *   ① [SHANHAI-GUARD] purge_scope=all_levels dry_run=&lt;b&gt; levels=&lt;n&gt; … removed=&lt;n&gt; skipped_players=&lt;n&gt;
 *   ② 回显数字 == 各维度返回值之和（同一个 removed 字段，不另算一份）
 *   ③ skipped_players == 执行时在线玩家数（口径：各维度 players() 之和；玩家不参与 {@code @e[type=!player]}）
 *   ④ 逐维度明细：purge_simple_level level=&lt;dim&gt; entities_seen=&lt;n&gt; killed=&lt;n&gt; players=&lt;n&gt;
 *      ⇒ "回显数字与现场不符"时看这一行：能立刻分清是"没实体"、"被别的 mod 取消了"还是"命令没执行"
 * </pre>
 */
public final class ShanhaiPurgeSimple {

    /** 日志前缀（与控制台其它 guard 行同一条前缀，grep 一次就能拿到全部）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /**
     * 真正发出去的那条原版命令。<b>逐字</b>就是用户口径里的 {@code kill@e}，只补了"排除玩家"。
     *
     * <p>{@code @e} 本身是含玩家的（{@code /kill @e} 会把自己也杀了），所以 {@code type=!player} 不能省。
     */
    public static final String COMMAND = "kill @e[type=!player]";

    /** {@link Result#channels} 里"本体简单版"的通道名（回显尾段 {@code ／逐级解决 {kill@e=N}}）。 */
    public static final String CHANNEL_SIMPLE = "kill@e";

    /** {@link Result#channels} 里"扩展给的数"的通道名（回显尾段 {@code ／逐级解决 {add=N}}）。 */
    public static final String CHANNEL_ADD = "add";

    /**
     * 内层 {@code /kill} 用的权限等级。
     *
     * <p>原版 {@code /kill} 只要 2 级；这里给 4 级，与本仓"/shanhai purge 的运维口径"一致
     * （命令行玩家已经过了"身上要带创始现实修改模块"那道门，见 {@code ShanhaiGuardCommand#gate}），
     * 否则非 OP 玩家点全息板第 1 格会因为"内层命令权限不足"而 0 只、还不报错。
     */
    private static final int PERMISSION = 4;

    private ShanhaiPurgeSimple() {}

    /** 一次"清除实体"的读数（形状与扩展那份逐级读数对齐 ⇒ 命令的回显那段一个字都不用改）。 */
    public static final class Result {

        /** 这次是只数不抹吗。 */
        public boolean dryRun;
        /** 跑了几个维度。 */
        public int levels;
        /** 扫到多少只（不含玩家）。⚠️ 非 dryRun 时由 {@link #removed} 回填（原版 kill 一次到底，没有"扫到没删掉"的中间态）。 */
        public int scanned;
        /** 抹掉多少只（= 各维度 {@code performPrefixedCommand} 的返回值之和）。 */
        public int removed;
        /** 跳过多少个玩家（只用于回显/日志）。 */
        public int skippedPlayers;
        /** 没解决的只数（本体简单版恒 0：原版 kill 要么删掉、要么本来就不在）。 */
        public int unresolved;
        /** 抛异常、按 0 计的维度数（> 0 说明这一轮不完整，日志里有逐维度 WARN）。 */
        public int failedLevels;
        /** 执行前"非玩家实体"总数（只用于日志对账：`entities_seen` 与 `removed` 一比就知道有没有被取消）。 */
        public int entitiesSeen;
        /** 通道 ⇒ 只数（简单版是 {@code {kill@e=N}}；扩展接管时是 {@code {add=N}}）。 */
        public final Map<String, Integer> channels = new LinkedHashMap<>();

        /** 一行机器可判读数（日志用；键名与扩展那份逐级读数同族）。 */
        public String line() {
            return "purge_scope=all_levels dry_run=" + dryRun + " levels=" + levels + " scanned=" + scanned
                    + " removed=" + removed + " skipped_players=" + skippedPlayers
                    + " entities_seen=" + entitiesSeen
                    + " unresolved=" + unresolved + " failed_levels=" + failedLevels
                    + " channels=" + channels;
        }
    }

    /**
     * 🔴 <b>逐维度执行 {@value #COMMAND}</b>（本体自己的清除实体）。
     *
     * <p>⚠️ 内层来源<b>不用</b> {@code actor} 的实体来源，理由见类注释（时钟守卫会取消玩家发起的
     * {@code kill}，而被取消的指令返回 1 ⇒ 会印假数字）。
     *
     * @param server 服务端（{@code null} ⇒ 直接返回空读数，不抛异常）
     * @param actor  执行者（{@code null} = 控制台/命令方块；<b>只进日志/播报，不参与"删谁"</b>）
     * @param dryRun true = 只数不抹（{@code /shanhai purge count}）
     * @return 读数（永远非 {@code null}；异常已被就地吞掉并记日志）
     */
    public static Result killAll(MinecraftServer server, ServerPlayer actor, boolean dryRun) {
        final Result r = new Result();
        r.dryRun = dryRun;
        if (server == null) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge_simple 没有服务器实例 ⇒ 什么都不做（removed=0）");
            return r;
        }
        // 无实体来源：时钟守卫跳过它、权限固定、与执行者解耦（见类注释）
        final CommandSourceStack base = server.createCommandSourceStack().withPermission(PERMISSION);
        for (final ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            r.levels++;
            try {
                // 玩家不参与删除，但"跳过几个"要能回显 ⇒ 逐维度数一下（跨维度不会重复计：一个玩家只在一个维度）
                final int players = level.players().size();
                r.skippedPlayers += players;
                final int seen = countNonPlayers(level);
                r.entitiesSeen += seen;
                if (dryRun) {
                    r.scanned += seen;
                    continue;
                }
                // 🔴 必须 withLevel(level)：选择器的实体表来自来源所在维度（别人怎么改都不影响这个语义）
                final int killed = server.getCommands().performPrefixedCommand(
                        base.withLevel(level).withSuppressedOutput(), COMMAND);
                r.removed += Math.max(killed, 0);
                // 逐维度明细：`entities_seen` 与 `killed` 一比就知道这一维度是真删了还是被取消了
                // （被 CommandEvent 取消时 Forge 返回 1 —— 见类注释，所以"killed=1 而 seen 很大"是可疑形态）
                ShanhaiMod.LOGGER.info(PREFIX + "purge_simple_level level={} dry_run=false entities_seen={}"
                        + " killed={} players={}", dimensionName(level), seen, killed, players);
            } catch (Throwable t) {
                // 一个维度失败不许把整条命令弄失败（更不许抛给命令调用方）
                r.failedLevels++;
                ShanhaiMod.LOGGER.warn(PREFIX + "purge_simple 维度执行失败（这一维度按 0 计，其余维度继续）"
                        + " level=" + dimensionName(level) + " cmd=" + COMMAND + " : " + t);
            }
        }
        if (!dryRun) {
            // 原版 kill 一次到底：删掉的数就是"这一次处理了几只"，没有中途失败的中间态
            r.scanned = r.removed;
            if (r.removed > 0) {
                r.channels.put(CHANNEL_SIMPLE, r.removed);
            }
        }
        ShanhaiMod.LOGGER.info(PREFIX + "purge_simple " + r.line() + " actor=" + actorName(actor)
                + " cmd=" + COMMAND);
        return r;
    }

    /**
     * 把<b>扩展</b>报的"抹掉几只"补齐成同一种回显读数（<b>只读，一只实体都不碰</b>）。
     *
     * <p>为什么要这一步：命令的回显格式不许变（设计 §3.4），而
     * {@code com.shanhai.api.ShanhaiAdd#purgeAll} 这一版只回来一个 {@code int}。
     * 于是 {@code skipped_players} 由本体自己数（纯读）、{@code channels} 记成 {@code {add=N}}
     * （通道明细在扩展自己的日志里）。
     *
     * @param removed 扩展的返回值（{@code >= 0}；调用方已确保不是 {@code NOT_IMPLEMENTED}）
     */
    public static Result fromBridge(MinecraftServer server, int removed) {
        final Result r = new Result();
        r.removed = Math.max(removed, 0);
        r.scanned = r.removed;
        if (r.removed > 0) {
            r.channels.put(CHANNEL_ADD, r.removed);
        }
        if (server != null) {
            try {
                for (final ServerLevel level : server.getAllLevels()) {
                    if (level != null) {
                        r.levels++;
                        r.skippedPlayers += level.players().size();
                    }
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn(PREFIX + "purge_bridge 统计读数时抛异常（回显按已读到的部分）：" + t);
            }
        }
        ShanhaiMod.LOGGER.info(PREFIX + "purge_bridge " + r.line() + "（数量来自扩展；通道明细见扩展自己的日志）");
        return r;
    }

    // ═══════════════════════════════ 工具 ═══════════════════════════════

    /**
     * 一个维度里"非玩家实体"的只数（<b>只读</b>）。
     *
     * <p>两个用途：① {@code /shanhai purge count} 的读数；② 真抹之前先量一次 ⇒ 日志里的
     * {@code entities_seen} 与 {@code killed} 一对，就能判出"这一维度到底是真删了还是被取消了"。
     */
    private static int countNonPlayers(ServerLevel level) {
        int n = 0;
        try {
            for (final Entity e : level.getAllEntities()) {
                if (e != null && !isPlayerLike(e)) {
                    n++;
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(PREFIX + "purge_simple 数实体时抛异常（这一维度按已数到的部分）：" + t);
        }
        return n;
    }

    /** 与扩展那份逐级抹除同口径：玩家 ／ 玩家类型 一律不算"要删的实体"。 */
    private static boolean isPlayerLike(Entity entity) {
        return entity instanceof Player || entity.getType() == EntityType.PLAYER;
    }

    /** 维度名（日志用；取不到就 {@code ?} —— 诊断行自己不许再抛异常）。 */
    private static String dimensionName(ServerLevel level) {
        try {
            return String.valueOf(level.dimension().location());
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 执行者名字（只进日志；{@code null} = 控制台/命令方块）。 */
    private static String actorName(ServerPlayer actor) {
        if (actor == null) {
            return "console";
        }
        try {
            return actor.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
