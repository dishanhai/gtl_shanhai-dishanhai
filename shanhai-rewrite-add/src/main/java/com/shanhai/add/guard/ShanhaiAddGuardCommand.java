package com.shanhai.add.guard;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardRemoval;
import com.shanhai.common.guard.ShanhaiGuardSwitches;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海扩展 · <b>逐级抹除 / 压制表的命令入口</b>（{@code /shanhai_add} 下面的两棵子树）。
 *
 * <h2>命令树（设计 §3.2）</h2>
 * <pre>
 *   /shanhai_add purge                    🔴 逐级版清除实体（L1→L6，日志 purge_channel=L1|L3|L4|L5|L6）
 *   /shanhai_add purge count              只数不抹（安全读数）
 *   /shanhai_add purge selfcheck          自检（通道表 ＋ 隔离阈值 ＋ 压制表读数 ＋ 三个开关）
 *   /shanhai_add suppress                 看压制表（= suppress list）
 *   /shanhai_add suppress list            逐条列出"现在还在压制窗口里的类型 ＋ 剩余秒数"
 *   /shanhai_add suppress clear           清空压制表（立刻停止周期抹除）
 * </pre>
 *
 * <h2>🔴 两条命令树的边界（硬约束，grep 可判）</h2>
 * <ul>
 *   <li>本类<b>只</b>注册 {@code /shanhai_add}；<b>绝不</b>往 {@code /shanhai} 上挂东西
 *       —— 否则"有没有装扩展"在命令树上分不出来。</li>
 *   <li>本体（{@code shanhai}）只保留 {@code /shanhai purge}（kill @e ／ 装了扩展时委托给
 *       {@code com.shanhai.api.ShanhaiAdd#purgeAll}）、{@code /shanhai guard …}、{@code /shanhai purge selfcheck}
 *       的<b>本体那一半</b>；{@code suppress} 整棵子树在阶段 3b 搬到这里。</li>
 * </ul>
 *
 * <h2>为什么门槛／回显与本体逐字一致</h2>
 * 同一个动作在两个入口上不该有第二种口径：玩家执行时身上要带「创始现实修改模块」
 * （{@link ShanhaiHoloMenuNetwork#moduleInInventory} 这个纯函数，与 {@code /shanhai items} ／
 * 全息命令输入框同一套），控制台／命令方块放行；回显那句与本体 {@code /shanhai purge} 同格式。
 *
 * <h2>日志标记（机器可判）</h2>
 * <pre>
 *   [SHANHAI-GUARD] add_command_registered /shanhai_add purge + suppress ; shanhai_add_children=[…]
 *   [SHANHAI-GUARD] add_purge_via=ladder dry_run=&lt;b&gt; removed=&lt;n&gt; scanned=&lt;n&gt; skipped_players=&lt;n&gt; unresolved=&lt;n&gt; channels={L1=…}
 *   [SHANHAI-GUARD] suppress_cleared items=&lt;n&gt;
 * </pre>
 * 🔴 {@code add_purge_via=ladder} 只在<b>本类</b>打；本体走桥那次打的是 {@code purge_via=add}。
 * 两条痕迹不同 ⇒ 冒烟里能分辨"谁真的执行了抹除"。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiAddGuardCommand {

    /** 日志前缀：沿用守卫那一套（历史日志判据不失效；设计 §5.1 的"前缀不换"口径）。 */
    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 本扩展的命令根。 */
    public static final String ROOT = "shanhai_add";
    /** 逐级抹除那个子命令名（与本体同名，便于两边对照）。 */
    public static final String COMMAND_PURGE = "purge";
    /** 压制表那个子命令名（阶段 3b 从本体整棵搬来）。 */
    public static final String COMMAND_SUPPRESS = "suppress";

    private ShanhaiAddGuardCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT).then(commandBranch()));
        final var node = event.getDispatcher().getRoot().getChild(ROOT);
        final StringBuilder kids = new StringBuilder();
        if (node != null) {
            for (var c : node.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        // 这一行同时是"扩展的命令树真的挂上了"的唯一运行时证据（modid 写错就会被 Forge 静默跳过）
        ShanhaiMod.LOGGER.info("{} add_command_registered /{}/{} + /{}/{} ; {}_children=[{}]",
                PREFIX, ROOT, COMMAND_PURGE, ROOT, COMMAND_SUPPRESS, ROOT, kids.toString().trim());
    }

    /** {@code /shanhai_add} 下面挂的两棵子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_PURGE)
                .executes(ctx -> purge(ctx, false))
                .then(Commands.literal("count").executes(ctx -> purge(ctx, true)))
                .then(Commands.literal("selfcheck").executes(ShanhaiAddGuardCommand::selfcheck))
                .then(suppressBranch());
    }

    /** {@code /shanhai_add suppress …}（门与 purge 同一条：玩家身上要带模块）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> suppressBranch() {
        return Commands.literal(COMMAND_SUPPRESS)
                .executes(ShanhaiAddGuardCommand::suppressList)
                .then(Commands.literal("list").executes(ShanhaiAddGuardCommand::suppressList))
                .then(Commands.literal("clear").executes(ShanhaiAddGuardCommand::suppressClear));
    }

    // ═══════════════════════════════ 门槛 ═══════════════════════════════

    /**
     * 门槛：玩家必须身上带模块；控制台／命令方块放行（运维通道）。
     *
     * @return {@code null} = 放行；否则是"为什么被拒"的中文（调用方直接回显）
     */
    private static String gate(CommandSourceStack source) {
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            return null;   // 控制台/命令方块：放行
        }
        if (ShanhaiHoloMenuNetwork.moduleInInventory(player)) {
            return null;
        }
        ShanhaiMod.LOGGER.info("{} add_cmd_rejected reason=no_module player={}",
                PREFIX, player.getName().getString());
        return "§c这条命令要身上带着「创始现实修改模块」";
    }

    // ═══════════════════════════════ /shanhai_add purge ═══════════════════════════════

    /**
     * {@code /shanhai_add purge} 与 {@code /shanhai_add purge count}。
     *
     * <p>与本体 {@code /shanhai purge} 的区别<b>只有手段</b>：这里直接走 {@link ShanhaiPurge#purgeAll}
     * 的逐级阶梯（L1→L6，含 L5 反射摘账与 L6 隔离），而本体没装扩展时是逐维度 {@code kill @e}。
     * 门槛、跳过玩家、回显格式三者与本体一致。
     *
     * @param dryRun true = 只数不抹（{@code /shanhai_add purge count}）
     */
    private static int purge(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                             boolean dryRun) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        if (!ShanhaiGuardSwitches.attackSide()) {
            source.sendFailure(Component.literal(
                    "§c攻击端开关是关的（/shanhai guard attack on 打开后才能抹除）"));
            ShanhaiMod.LOGGER.info("{} add_purge_rejected reason=attack_side_off", PREFIX);
            return 0;
        }
        final MinecraftServer server = source.getServer();
        final ServerPlayer owner = source.getPlayer();
        if (server == null) {
            // 命令来源没有服务端 = 不该发生；但绝不许回一句"已抹除 0 只"假装做完了
            source.sendFailure(Component.literal("§c拿不到服务端实例，这一次什么都没做"));
            ShanhaiMod.LOGGER.warn("{} add_purge_rejected reason=no_server", PREFIX);
            return 0;
        }
        final ShanhaiPurge.Result r = ShanhaiPurge.purgeAll(server, owner, dryRun);
        // 维度数不在这里再数一遍：purgeAll 自己那行 `purge_start scope=all_levels levels=<n>` 才是权威读数
        // （在这里另数一次就成了"同一件事两个来源"，早晚会不一致）。
        ShanhaiMod.LOGGER.info("{} add_purge_via=ladder dry_run={} removed={} scanned={}"
                        + " skipped_players={} unresolved={} channels={}",
                PREFIX, dryRun, r.removed, r.scanned, r.skippedPlayers, r.unresolved, r.channels);
        // 回显与本体 /shanhai purge 同格式（只换数字来源，运维不必记两套）
        final String line = (dryRun ? "§b[勘数] §7范围内 §f" : "§a[已执行] §7已抹除 §f")
                + (dryRun ? r.scanned : r.removed)
                + (dryRun ? " §7只（未抹）" : " §7只")
                + " §7／跳过玩家 §f" + r.skippedPlayers + " §7个"
                + (r.unresolved > 0 ? " §c／这一轮没解决 " + r.unresolved + " 只（已进重申表）" : "")
                + " §8／逐级解决 " + r.channels;
        source.sendSuccess(() -> Component.literal(line), false);
        return r.removed;
    }

    /**
     * {@code /shanhai_add purge selfcheck} —— 逐级抹除＋压制表这一半的纯判据自检（控制台可跑）。
     *
     * <p>本体那一半（{@code /shanhai purge selfcheck}）不读压制表 —— 那个类已经搬到这里了。
     * 两边各报自己读得懂的东西，合起来是完整的 {@code guard_selftest}。
     */
    private static int selfcheck(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final ShanhaiGuardCore.Decision ok = ShanhaiGuardRemoval.refuseRemoval(
                true, true, ShanhaiGuardRemoval.R_DISCARDED, false, false);
        final ShanhaiGuardCore.Decision allow = ShanhaiGuardRemoval.refuseRemoval(
                true, true, ShanhaiGuardRemoval.R_CHANGED_DIMENSION, false, false);
        final String line = "add_guard_selftest 拦(DISCARDED)=" + ok.yes
                + " 放行(CHANGED_DIMENSION)=" + (!allow.yes)
                + " 逐级通道=" + String.join("/", ShanhaiGuardRemoval.purgeChannels())
                + " 隔离阈值=" + ShanhaiGuardRemoval.ISOLATE_AFTER_REASSERTS
                + " ／ " + ShanhaiGuardSuppression.statusLine()
                + " ／ " + ShanhaiGuardSwitches.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + line), false);
        return ok.yes && !allow.yes ? 1 : 0;
    }

    // ═══════════════════════════════ /shanhai_add suppress ═══════════════════════════════

    /** {@code /shanhai_add suppress [list]} —— 逐条列出现在还在压制窗口里的类型。 */
    private static int suppressList(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        final java.util.List<String> lines = ShanhaiGuardSuppression.listLines(System.currentTimeMillis());
        for (final String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** {@code /shanhai_add suppress clear} —— 清空压制表（立刻停止周期抹除）。 */
    private static int suppressClear(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        final int n = ShanhaiGuardSuppression.clear();
        final String line = "§a已清空压制表：§f" + n + " §7项（这些类型不再被每 "
                + ShanhaiGuardSuppressWindow.SCAN_EVERY_TICKS + " tick 复查抹除）";
        source.sendSuccess(() -> Component.literal(line), true);
        ShanhaiMod.LOGGER.info("{} suppress_cleared items={}", PREFIX, n);
        return n;
    }
}
