package com.shanhai.common.holo;

import com.google.common.collect.Maps;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.shanhai.ShanhaiMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.command.CommandHelper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海重构 · 让<b>拿着「创始现实修改模块」的玩家（包括没有 OP 的）在客户端拿到【完整指令树】</b>。
 *
 * <h2>1. 为什么需要它（一句话）</h2>
 * 全息命令输入框的候选表复用原版 {@code CommandSuggestions}，数据源就是客户端手上那一份指令树；
 * 而那份树是服务端<b>按玩家真实权限剪枝</b>后才发下来的
 * （原版 {@code Commands#sendCommands} 里的 {@code canUse(source)} 那一道门）
 * ⇒ 没有 OP 的玩家，客户端不知道 {@code /gamemode} 这些节点的存在 ⇒ 候选表里当然没有。
 * <b>执行</b>那条路早就通了（{@code ShanhaiHoloMenuNetwork#onCommandRequest} 以
 * {@code withPermission(4)} 跑）；差的就是<b>把树补全</b>。
 *
 * <h2>2. 怎么发（照抄原版调用点，只换一个源栈）</h2>
 * 原版 {@code Commands#sendCommands(ServerPlayer)} 在 1.20.1-47.4.16 里长这样
 * （javap 证据：{@code temp\holo-cmdperm\r1-javap-Commands-code.txt} L622-654，
 * 源码证据：{@code r1-src-net_minecraft_commands_Commands.java} L309-316）：
 * <pre>
 *   Map&lt;CommandNode&lt;CommandSourceStack&gt;, CommandNode&lt;SharedSuggestionProvider&gt;&gt; map = Maps.newHashMap();
 *   RootCommandNode&lt;SharedSuggestionProvider&gt; root = new RootCommandNode&lt;&gt;();
 *   map.put(this.dispatcher.getRoot(), root);
 *   CommandHelper.mergeCommandNode(this.dispatcher.getRoot(), root, map,
 *           player.createCommandSourceStack(),            // ← 唯一要改的就是这一个参数
 *           ctx -&gt; 0,
 *           suggest -&gt; SuggestionProviders.safelySwap((SuggestionProvider&lt;SharedSuggestionProvider&gt;)
 *                                                     (SuggestionProvider&lt;?&gt;) suggest));
 *   player.connection.send(new ClientboundCommandsPacket(root));
 * </pre>
 * {@link #sendFullLevel4Tree} 与它<b>逐字同形，只把源栈换成
 * {@code createCommandSourceStack().withPermission(4)}</b> ⇒
 * {@code mergeCommandNode} 里 {@code child.canUse(sourceStack)} 那一道门对等级 4 放行
 * ⇒ 客户端拿到完整树；而重建出来的节点走的是 Brigadier {@code ArgumentBuilder} 的默认
 * {@code requires == s -&gt; true}（javap：{@code r1-javap3-ArgumentBuilder-code.txt} 的
 * {@code lambda$new$0} 直接 {@code iconst_1/ireturn}）⇒ 客户端本地解析时不会再被权限挡掉
 * ⇒ 候选表里就会出现 OP 指令。
 *
 * <h2>3. 🔴 红线：等级 4 的树【只】发给拿着模块的人</h2>
 * 判据在 {@link ShanhaiHoloCommandTreePolicy}（纯逻辑核，可离线自证）；本类只负责
 * ①观测（复用现成的 {@code ShanhaiHoloMenuNetwork#moduleInInventory}）、②发、③记。
 * 丢掉模块 ⇒ 立刻用<b>原版自己的方法</b> {@code Commands#sendCommands(player)} 还原成
 * "按他真实权限剪枝"的那一份。
 *
 * <h2>4. 什么时候触发（为什么不是每 tick 扫）</h2>
 * <pre>
 *   登录   PlayerLoggedInEvent            ⇒ forced（原版在 PlayerList.placeNewPlayer 里刚发过一份剪枝的）
 *   重生   PlayerRespawnEvent             ⇒ forced（原版 PlayerList.respawn 里又发了一份剪枝的）
 *   换维度 PlayerChangedDimensionEvent    ⇒ 只"重新核一遍"（原版不重发、客户端那份还在、背包跟着走）
 *   退出   PlayerLoggedOutEvent           ⇒ 忘掉这个玩家（不留垃圾）
 *   每 10 tick ServerTickEvent(END)       ⇒ 兜底核一遍（背包变化）+ 消化上面那几个"强制重发"
 * </pre>
 * 为什么兜底是"每 10 tick 核一遍"而不是一堆事件：<b>背包变动没有单一事件能覆盖</b>
 * （捡起/合成/漏斗/物品栏里拖动/丢弃/死亡掉落/{@code /give}/创造模式拿取……），
 * 而且物品栏 GUI 里的拖动<b>一个</b>玩家事件都不发。代价：每个玩家每 0.5 秒 41 次
 * {@code ItemStack.is(...)} 比较，可以忽略；<b>不是每 tick</b>。
 * <p>为什么"强制重发"要<b>延到 tick 末</b>（{@code ServerTickEvent.Phase.END}）再发：
 * 登录/重生时原版自己也在同一 tick 里发一份剪枝的，谁后发谁赢；延到 tick 末就保证我们
 * <b>一定后发</b>（顺序问题从设计上消失，不必去猜事件与原版那两行的先后）。
 *
 * <h2>5. op / deop 怎么办</h2>
 * 原版 {@code PlayerList.op/deop} 也会重发一份（按新权限剪枝）⇒ 那一下会把我们发过的全树顶掉。
 * Forge 1.20.1 没有 op/deop 事件，所以靠每 10 tick 那一遍里比对<b>权限等级有没有变</b>
 * （{@code server.getProfilePermissions(profile)}，与 {@code ServerPlayer#getPermissionLevel()} 同一个来源）
 * ⇒ 变了就按 {@code forced} 处理，最迟 0.5 秒后补回完整树。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiHoloCommandTreeSync {

    public static final String PREFIX = ShanhaiHoloMenuNetwork.PREFIX;

    /** 兜底核查间隔（tick）。0.5 秒 —— 见类注释 §4。 */
    public static final int RECHECK_INTERVAL_TICKS = 10;

    /** 发失败之后的冷却（tick）：别每 0.5 秒重试一次刷日志。 */
    public static final int FAIL_BACKOFF_TICKS = 200;

    /** 每个玩家（按 UUID，跨重生/换维度不变）的"我们最后发了哪一份树"。 */
    private static final Map<UUID, ShanhaiHoloCommandTreePolicy.State> STATES = new ConcurrentHashMap<>();

    /** 需要"强制重发"的玩家（登录/重生：原版刚覆盖过）。 */
    private static final Set<UUID> FORCED = ConcurrentHashMap.newKeySet();

    /** 需要"重新核一遍"的玩家（换维度：不强制重发，只复查状态）。 */
    private static final Set<UUID> RECHECK = ConcurrentHashMap.newKeySet();

    /** 发失败的玩家 ⇒ 冷却到期之前不再试（tick）。 */
    private static final Map<UUID, Long> FAILED_UNTIL = new ConcurrentHashMap<>();

    private static long tick;
    private static int sentFull;
    private static int sentActual;
    private static int sendFailed;

    private ShanhaiHoloCommandTreeSync() {}

    // ------------------------------------------------------------------ 触发

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        mark(event.getEntity(), true);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        mark(event.getEntity(), true);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        mark(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            final UUID id = player.getUUID();
            STATES.remove(id);
            FORCED.remove(id);
            RECHECK.remove(id);
            FAILED_UNTIL.remove(id);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, statsLine());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        final boolean interval = (tick % RECHECK_INTERVAL_TICKS) == 0;
        if (!interval && FORCED.isEmpty() && RECHECK.isEmpty()) {
            return;                                  // 绝大多数 tick 在这里就返回了
        }
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            final UUID id = player.getUUID();
            // 延一 tick 发：保证晚于原版在登录/重生里发的那一份（谁后发谁赢）
            final boolean forced = FORCED.remove(id);
            RECHECK.remove(id);
            if (!interval && !forced) {
                continue;
            }
            pass(player, forced);
        }
    }

    private static void mark(net.minecraft.world.entity.player.Player player, boolean forced) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final UUID id = serverPlayer.getUUID();
        if (forced) {
            FORCED.add(id);
        } else {
            RECHECK.add(id);
        }
    }

    // ------------------------------------------------------------------ 一遍判定 + 发

    private static void pass(ServerPlayer player, boolean forced) {
        if (player == null) {
            return;
        }
        try {
            final UUID id = player.getUUID();
            if (tick < FAILED_UNTIL.getOrDefault(id, 0L)) {
                return;                              // 上一次发失败了，冷却中（见 FAIL_BACKOFF_TICKS）
            }
            final ShanhaiHoloCommandTreePolicy.State state =
                    STATES.computeIfAbsent(id, k -> ShanhaiHoloCommandTreePolicy.State.fresh());
            final boolean connected = player.connection != null && !player.hasDisconnected();
            final ShanhaiHoloCommandTreePolicy.Carrier carrier = connected
                    ? ShanhaiHoloCommandTreePolicy.Carrier.of(
                            ShanhaiHoloMenuNetwork.moduleInInventory(player), permissionLevelOf(player))
                    : ShanhaiHoloCommandTreePolicy.Carrier.absent();
            final ShanhaiHoloCommandTreePolicy.Action action =
                    ShanhaiHoloCommandTreePolicy.decide(state, carrier, forced);
            switch (action) {
                case SEND_FULL_TREE:
                    if (!trySendFull(player)) {
                        // 全树没发出去 ⇒ ①账上要记成"客户端手上不是全树"（否则再也不会重试）
                        //            ②退回"按他真实权限剪枝"的那一份（= 本轮改动之前的行为）
                        //            ③记一笔冷却，别每 0.5 秒重试刷日志
                        state.fullTreeSent = false;
                        noteFailure(player);
                        trySendActual(player);
                    }
                    break;
                case SEND_ACTUAL_PERMISSION_TREE:
                    if (!trySendActual(player)) {
                        // 还原没发出去 ⇒ 客户端手上【还是】那份全树，账上就得照实记（下次再来还原）
                        state.fullTreeSent = true;
                        noteFailure(player);
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            // 一个玩家的判定/发包出问题绝不能让服务端 tick 挂掉
            ShanhaiMod.LOGGER.error("{} holo_cmd_tree_pass_failed player={} err={}",
                    PREFIX, name(player), t.toString(), t);
        }
    }

    private static boolean trySendFull(ServerPlayer player) {
        try {
            return sendFullLevel4Tree(player);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} holo_cmd_tree_full_failed player={} perm={} err={}",
                    PREFIX, name(player), permissionLevelOf(player), t.toString(), t);
            return false;
        }
    }

    /** 还原那一份：走原版自己的方法；失败也只记一行（退化 = 本轮改动之前的行为）。 */
    private static boolean trySendActual(ServerPlayer player) {
        try {
            return sendActualPermissionTree(player);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} holo_cmd_tree_actual_failed player={} err={}",
                    PREFIX, name(player), t.toString(), t);
            return false;
        }
    }

    /**
     * 发【完整】指令树：以等级 {@link ShanhaiHoloCommandTreePolicy#TREE_PERMISSION_LEVEL} 的源栈
     * 让 Forge 那套合并逻辑把树铺出来，再照原版那样发给这个玩家。
     * <p>🔴 只有 {@link ShanhaiHoloCommandTreePolicy} 判定"这个人拿着模块"时才会调到这里。
     *
     * @return true = 真的发出去了（false ⇒ 调用方要把"已发全树"从账上撤掉）
     */
    @SuppressWarnings("unchecked")
    private static boolean sendFullLevel4Tree(ServerPlayer player) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd_tree_full_skipped player={} reason=no_server",
                    PREFIX, name(player));
            return false;
        }
        final Commands commands = server.getCommands();
        final CommandSourceStack source4 = player.createCommandSourceStack()
                .withPermission(ShanhaiHoloCommandTreePolicy.TREE_PERMISSION_LEVEL);
        final Map<CommandNode<CommandSourceStack>, CommandNode<SharedSuggestionProvider>> map = Maps.newHashMap();
        final RootCommandNode<SharedSuggestionProvider> root = new RootCommandNode<>();
        map.put(commands.getDispatcher().getRoot(), root);
        // 与 Commands#sendCommands 逐字同形，只换了源栈（等级 4）
        CommandHelper.mergeCommandNode(commands.getDispatcher().getRoot(), root, map, source4,
                ctx -> 0,
                suggest -> SuggestionProviders.safelySwap(
                        (SuggestionProvider<SharedSuggestionProvider>) (SuggestionProvider<?>) suggest));
        player.connection.send(new ClientboundCommandsPacket(root));
        sentFull++;
        ShanhaiMod.LOGGER.info("{} holo_cmd_tree_sent player={} kind=FULL_LEVEL4 perm={} op={} module=true",
                PREFIX, name(player), permissionLevelOf(player), player.hasPermissions(2));
        return true;
    }

    /**
     * 发【按他真实权限剪枝】的那一份：直接叫原版自己发
     * （{@code PlayerList#sendPlayerPermissionLevel} 就是这么调的）—— 用来还原。
     *
     * @return true = 真的发出去了（false ⇒ 客户端手上还是那份全树，账上要照实记）
     */
    private static boolean sendActualPermissionTree(ServerPlayer player) {
        final MinecraftServer server = player.getServer();
        if (server == null) {
            ShanhaiMod.LOGGER.warn("{} holo_cmd_tree_actual_skipped player={} reason=no_server",
                    PREFIX, name(player));
            return false;
        }
        server.getCommands().sendCommands(player);
        sentActual++;
        ShanhaiMod.LOGGER.info("{} holo_cmd_tree_sent player={} kind=ACTUAL_PERMISSION perm={} op={} module=false",
                PREFIX, name(player), permissionLevelOf(player), player.hasPermissions(2));
        return true;
    }

    /**
     * 玩家真实的权限等级 —— 与 {@code ServerPlayer#getPermissionLevel()} 同一个来源
     * （javap：{@code r1-javap2-ServerPlayer-code-createCSS.txt} L3563-3570：
     * {@code server.getProfilePermissions(this.getGameProfile())}）。
     * <p>它有两个用处：① 判断 op/deop 有没有发生（变了就补发全树）；
     * ② 日志读数。带兜底：拿不到就当 0（宁可少发，不可多发）。
     */
    public static int permissionLevelOf(ServerPlayer player) {
        try {
            final MinecraftServer server = player == null ? null : player.getServer();
            if (server == null) {
                return 0;
            }
            return server.getProfilePermissions(player.getGameProfile());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String name(ServerPlayer player) {
        try {
            return player == null ? "null" : player.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 装机/运行读数（一行，机器可判）。 */
    public static String statsLine() {
        return "holo_cmd_tree_sync ready=true interval=" + RECHECK_INTERVAL_TICKS + "t"
                + " level=" + ShanhaiHoloCommandTreePolicy.TREE_PERMISSION_LEVEL
                + " triggers=[login,respawn,dimension,logout,servertick]"
                + " full_sent=" + sentFull + " actual_sent=" + sentActual
                + " failed=" + sendFailed + " tracked=" + STATES.size();
    }

    /** 发失败时的冷却登记（日志里那一次失败也记一笔）。 */
    private static void noteFailure(ServerPlayer player) {
        sendFailed++;
        if (player != null) {
            FAILED_UNTIL.put(player.getUUID(), tick + FAIL_BACKOFF_TICKS);
        }
    }
}
