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
 * 山海扩展 · <b>逐级抹除 / 压制表 / 钩子自检的命令入口</b>（{@code /shanhai_add} 下面的三棵子树）。
 *
 * <h2>命令树（设计 §3.2）</h2>
 * <pre>
 *   /shanhai_add purge                    🔴 安全清场（HurtOnly：写 0.1 血 ＋ 原版 hurt；默认通道）
 *   /shanhai_add purge count              只数不抹（安全读数）
 *   /shanhai_add purge legacy             🔴 旧阶梯（L1→L6，可能造成第三方崩；只给显式排查用）
 *   /shanhai_add purge legacy count       旧阶梯只数不抹
 *   /shanhai_add purge selfcheck          自检（通道表 ＋ 隔离阈值 ＋ 压制表读数 ＋ 三个开关 ＋ 新通道常量）
 *   /shanhai_add suppress                 看压制表（= suppress list）
 *   /shanhai_add suppress list            逐条列出"现在还在压制窗口里的类型 ＋ 剩余秒数"
 *   /shanhai_add suppress clear           清空压制表（立刻停止周期抹除）
 *   /shanhai_add hooks                    🆕 只读：立刻跑一轮"我们的注入还在不在"自检（存在性 ＋ 两条探针 ＋ 是否降级）
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
 *   [SHANHAI-GUARD] add_purge_via=hurt dry_run=&lt;b&gt; swept=&lt;n&gt; killed=&lt;n&gt; stuck=&lt;n&gt; skipped_not_living=&lt;n&gt; …
 *   [SHANHAI-GUARD] purge_kill window_ms=5000 swept=&lt;n&gt; killed=&lt;n&gt; stuck=&lt;n&gt; skipped=&lt;n&gt; reason=… ｜ killed：类型×N
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
    /** 🆕 钩子自检那个子命令名（只读：立刻跑一轮"我们的注入还在不在"；不设模块门槛）。 */
    public static final String COMMAND_HOOKS = "hooks";

    private ShanhaiAddGuardCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 🔴 2026-11-07 修：purge 与 suppress 必须是【兄弟】（以前 suppress 被 `.then(...)` 挂成 purge 的子节点
        //    ⇒ 真实路径 `/shanhai_add purge suppress …`，`/shanhai_add suppress` 不存在）。
        event.getDispatcher().register(Commands.literal(ROOT)
                .then(commandBranch())
                .then(suppressBranch())
                .then(hooksBranch()));
        final var node = event.getDispatcher().getRoot().getChild(ROOT);
        final StringBuilder kids = new StringBuilder();
        if (node != null) {
            for (var c : node.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        // 这一行同时是"扩展的命令树真的挂上了"的唯一运行时证据（modid 写错就会被 Forge 静默跳过）
        // 🔴 2026-11-07：改成回读命令树给 present=，不再只打印"打算注册什么"。
        final boolean purgePresent = node != null && node.getChild(COMMAND_PURGE) != null;
        final boolean suppressPresent = node != null && node.getChild(COMMAND_SUPPRESS) != null;
        ShanhaiMod.LOGGER.info("{} add_command_registered /{}/{} present={} + /{}/{} present={} ; {}_children=[{}]",
                PREFIX, ROOT, COMMAND_PURGE, purgePresent, ROOT, COMMAND_SUPPRESS, suppressPresent,
                ROOT, kids.toString().trim());
    }

    /** {@code /shanhai_add} 下面的 purge 子树（suppress 由 register 那一层并排挂上去）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_PURGE)
                .executes(ctx -> purge(ctx, false, false))
                .then(Commands.literal("count").executes(ctx -> purge(ctx, true, false)))
                .then(Commands.literal("legacy")
                        .executes(ctx -> purge(ctx, false, true))
                        .then(Commands.literal("count").executes(ctx -> purge(ctx, true, true))))
                .then(Commands.literal("selfcheck").executes(ShanhaiAddGuardCommand::selfcheck));
    }

    /** {@code /shanhai_add suppress …}（门与 purge 同一条：玩家身上要带模块）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> suppressBranch() {
        return Commands.literal(COMMAND_SUPPRESS)
                .executes(ShanhaiAddGuardCommand::suppressList)
                .then(Commands.literal("list").executes(ShanhaiAddGuardCommand::suppressList))
                .then(Commands.literal("clear").executes(ShanhaiAddGuardCommand::suppressClear));
    }

    /**
     * 🆕 {@code /shanhai_add hooks} —— <b>只读</b>：立刻跑一轮"我们的注入还在不在"自检
     * （存在性 ＋ 两条行为探针，判据全在 {@link com.shanhai.add.guard.selfcheck.ShanhaiHookSelfCheck}）。
     *
     * <p>挂在根上作 {@code purge} ／ {@code suppress} 的<b>兄弟</b>；<b>不设</b>模块门槛
     * （只读、不改世界；控制台也算成功）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> hooksBranch() {
        return Commands.literal(COMMAND_HOOKS).executes(ShanhaiAddGuardCommand::hooks);
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
     * {@code /shanhai_add purge}、{@code … purge count}、{@code … purge legacy}、{@code … purge legacy count}。
     *
     * <p>🆕 默认走 <b>安全清场通道（HurtOnly）</b> {@link ShanhaiPurge#purgeAll}：先写 0.1 血骗过
     * {@code isDeadOrDying} 前置，再用原版 {@code hurt} 打一记必杀；<b>永不</b> setRemoved/discard/改 Level 容器
     * （旧的 L1→L6 阶梯对"替身 setRemoved 空实现"的实体会把对方内部表搞成不一致 ⇒ 崩服）。
     * {@code legacy} 子命令是<b>显式</b>退回旧阶梯（日志会写 {@code purge_legacy_removal … 可能造成第三方崩}）。
     *
     * @param dryRun true = 只数不抹（{@code … purge count}）
     * @param legacy true = 旧阶梯（{@code … purge legacy}）
     */
    private static int purge(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                             boolean dryRun, boolean legacy) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        // 🔴 2026-11-20（任务 1）：命令路径【不判 attackSide】（用户原话：「我打那个抹除的指令都不生效了」）。
        //    attack_side 只关自动反制两样：① 受击后自动抹除攻击者 ② 持续压制（enroll ＋ 每 5 tick 复查）。
        //    这里只把它的当前值当背景读数打进日志，绝不拿它拦命令。
        final boolean attackSideNow = ShanhaiGuardSwitches.attackSide();
        final MinecraftServer server = source.getServer();
        final ServerPlayer owner = source.getPlayer();
        if (server == null) {
            // 命令来源没有服务端 = 不该发生；但绝不许回一句"已抹除 0 只"假装做完了
            source.sendFailure(Component.literal("§c拿不到服务端实例，这一次什么都没做"));
            ShanhaiMod.LOGGER.warn("{} add_purge_rejected reason=no_server", PREFIX);
            return 0;
        }
        final ShanhaiPurge.Result r = legacy
                ? ShanhaiPurge.purgeLegacy(server, owner, dryRun)
                : ShanhaiPurge.purgeAll(server, owner, dryRun);
        // 维度数不在这里再数一遍：purgeAll 自己那行 `purge_start … levels=<n>` 才是权威读数
        // （在这里另数一次就成了"同一件事两个来源"，早晚会不一致）。
        // 🔴 2026-11-20 任务 1 的判读行：`purge via=command` = "命令路径没被 attackSide 挡住"的证据。
        ShanhaiMod.LOGGER.info("{} purge via=command count={} dry_run={} legacy={} attack_side={}"
                        + "（命令路径不判 attackSide）",
                PREFIX, "legacy".equals(r.mode) ? r.removed : r.killed, dryRun, legacy, attackSideNow);
        ShanhaiMod.LOGGER.info("{} add_purge_via={} dry_run={} swept={} killed={} stuck={}"
                        + " skipped_not_living={} skipped_players={} removed={} unresolved={} channels={}",
                PREFIX, r.mode, dryRun, r.scanned, r.killed, r.stuck,
                r.notLivingSkipped, r.skippedPlayers, r.removed, r.unresolved, r.channels);
        // 回显：默认通道报 killed/stuck/skipped；legacy 通道保留旧那套（逐级解决 + 重申表）
        final String line;
        if (legacy) {
            line = (dryRun ? "§b[勘数·旧阶梯] §7范围内 §f" : "§a[已执行·旧阶梯] §7已抹除 §f")
                    + (dryRun ? r.scanned : r.removed)
                    + (dryRun ? " §7只（未抹）" : " §7只")
                    + " §7／跳过玩家 §f" + r.skippedPlayers + " §7个"
                    + (r.unresolved > 0 ? " §c／这一轮没解决 " + r.unresolved + " 只（已进重申表）" : "")
                    + " §8／逐级解决 " + r.channels
                    + " §c／旧阶梯可能造成第三方崩";
        } else {
            line = (dryRun ? "§b[勘数] §7可杀候选 §f" : "§a[已执行] §7已抹除 §f")
                    + (dryRun ? r.scanned : r.killed)
                    + (dryRun ? " §7只（未抹）" : " §7只")
                    + " §8／删不掉 §f" + (dryRun ? 0 : r.stuck) + " §7只（留在原地）"
                    + " §8／非生物跳过 §f" + r.notLivingSkipped
                    + " §7／跳过玩家 §f" + r.skippedPlayers + " §7个";
        }
        source.sendSuccess(() -> Component.literal(line), false);
        return "legacy".equals(r.mode) ? r.removed : r.killed;
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
                + " ／ 默认通道=hurt(prime=" + ShanhaiPurgeAdmission.PRIME_HEALTH
                + " damage_max=" + ShanhaiPurgeAdmission.SAFE_DAMAGE_MAX
                + " headroom=" + ShanhaiPurgeAdmission.DAMAGE_HEADROOM + ")"
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

    // ═══════════════════════════════ /shanhai_add hooks ═══════════════════════════════

    /**
     * 🆕 {@code /shanhai_add hooks} —— <b>只读</b>：立刻跑一轮"我们的注入还在不在"自检。
     *
     * <p>回显规格那行（逐字）：
     * <pre>
     *   [SHANHAI-GUARD] hooks status=ok|missing data_only=&lt;bool&gt; found=&lt;0..4&gt; missing=&lt;名单&gt; probe=ok|health|remove
     * </pre>
     * 判据、探针、降级标志全在 {@link com.shanhai.add.guard.selfcheck.ShanhaiHookSelfCheck}；
     * 控制台另落两行：同一行 ＋ {@code hooks_found …}（<b>实际跑在类里的方法名</b>，带 {@code handler$<token>$} 前缀，
     * 对账用）。
     *
     * <p>🔴 这一条也<b>不过</b> {@link #gate}：只读、不改世界、不动真玩家（探针零实体），
     * 控制台（{@code player == null}）同样算成功 ⇒ 永远返回 1。
     * 自检发现钩子缺失时，状态本身会在那行里如实写 {@code status=missing}（不靠返回值表达）。
     */
    private static int hooks(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final com.shanhai.add.guard.selfcheck.ShanhaiHookSelfCheck.Result result =
                com.shanhai.add.guard.selfcheck.ShanhaiHookSelfCheck.runForCommand();
        source.sendSuccess(() -> Component.literal("§a" + result.line), false);
        ShanhaiMod.LOGGER.info(result.line);
        ShanhaiMod.LOGGER.info("{}hooks_found {}", PREFIX, result.foundDetail());
        return 1;
    }
}
