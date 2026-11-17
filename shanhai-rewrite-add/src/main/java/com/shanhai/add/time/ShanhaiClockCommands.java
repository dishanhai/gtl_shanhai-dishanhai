package com.shanhai.add.time;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.time.anchor.ShanhaiAnchorBarBroadcast;
import com.shanhai.add.time.replay.ShanhaiReplayAnchor;
import com.shanhai.add.time.replay.ShanhaiReplayAnchorRuntime;
import com.shanhai.add.time.replay.ShanhaiReplayBudget;
import com.shanhai.add.time.replay.ShanhaiReplayConst;
import com.shanhai.add.time.replay.ShanhaiReplayCoverage;
import com.shanhai.add.time.replay.ShanhaiReplayGate;
import com.shanhai.add.time.replay.ShanhaiReplayJournal;
import com.shanhai.add.time.replay.ShanhaiReplayModeCompose;
import com.shanhai.add.time.replay.ShanhaiReplayRecorder;
import com.shanhai.add.time.replay.ShanhaiReplaySavedDataAudit;
import com.shanhai.add.time.replay.ShanhaiReplayService;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 山海重构 · {@code /shanhai_add clock …} 与 {@code /shanhai_add time …} 指令。
 *
 * <h2>用法</h2>
 * <pre>
 *   /shanhai_add clock pause          冻结世界（连光影/光照一起停）
 *   /shanhai_add clock resume         重新打开时间，<b>记录照常继续</b>（倒流中敲它 = 立即收尾并回到 LIVE）
 *   /shanhai_add clock status         打印 mode/tick/客户端三处时钟/光影 hook/拦截计数/倒流进度
 *   /shanhai_add clock rate &lt;x&gt;       设倍率（本轮只影响 status 与下一轮的加速；PAUSED 下不生效）
 *   /shanhai_add clock replay         ⚠️ <b>不再开始倒流</b>（只回一句指引，零倒流动作；真入口在下面 time rewind）
 *
 *   /shanhai_add time rewind            回到<b>锚点</b>（无参 = 锚点语义；没设过就明确提示先设锚点）
 *                                       🔴 PAUSED 下也允许（"暂停 = 停在某一刻，倒流 = 从这一刻往回走"）
 *   /shanhai_add time rewind &lt;秒数&gt;  旧语义：把最近 N 秒 1:1 退回去（默认窗口 1200 tick）
 *   /shanhai_add time rewind stop      立即中止倒流（走同一套收尾：回"进来之前的模式" + 恢复写盘 + 清 ring）
 *   /shanhai_add time anchor set       在当前位置/朝向记一个锚点（LIVE 与 PAUSED 都允许）
 *   /shanhai_add time anchor status    tick / 坐标 / 朝向 / 距现在多少 tick / 是否 stale
 *   /shanhai_add time anchor clear     清掉锚点（之后无参 rewind 会拒绝 no_anchor）
 *   /shanhai_add time status           只看倒流那几行（journal/游标/写入计数/记录器/锚点）
 * </pre>
 *
 * <p>权限：{@code pause}/{@code resume}/{@code rate}/{@code rewind}/{@code anchor set|clear} 需要权限等级 2；
 * {@code status} 与 {@code anchor status} 谁都能看。
 */
public final class ShanhaiClockCommands {

    private ShanhaiClockCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shanhai_add")
                .then(Commands.literal("clock")
                        .then(Commands.literal("pause")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> changeMode(ctx, ShanhaiClockMode.PAUSED)))
                        .then(Commands.literal("resume")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> changeMode(ctx, ShanhaiClockMode.LIVE)))
                        // 🔴 2026-11-14：`clock replay` <b>不再是倒流入口</b>。用户两次澄清后的口径（逐字）：
                        //    ① 「我设置锚点之后，暂停时间，再 /shanhai_add clock replay，此时直接开始倒流了，而它应该继续记录才对」
                        //    ② 「我说的暂停→继续记录指的是，暂停之后重新打开时间，可以继续记录，
                        //        而不是输入 /shanhai_add clock replay 之后直接开始倒流了」
                        //    ⇒ 这一格只回指引，<b>一个倒流动作都不做</b>
                        //      （判据 ①：本分支的执行方法体内 startReplay/startAtAnchor 命中 = 0）。
                        //      真倒流入口只剩 /shanhai_add time rewind（无参=回锚点 / <秒>=回退 N 秒 / stop=中止）。
                        .then(Commands.literal("replay")
                                .requires(source -> source.hasPermission(2))
                                .executes(ShanhaiClockCommands::clockReplayRefused)
                                // 老玩家手上有 `clock replay <秒数>`：同样只回指引，不按这个秒数开工
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3_600))
                                        .executes(ShanhaiClockCommands::clockReplayRefused))
                                // stop 保留「停倒流」的能力：它只会收尾，没有倒流时什么都不启动
                                .then(Commands.literal("stop")
                                        .executes(ShanhaiClockCommands::stopReplay)))
                        .then(Commands.literal("status")
                                .executes(ShanhaiClockCommands::status))
                        .then(Commands.literal("rate")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(ShanhaiWorldClock.MIN_RATE,
                                                ShanhaiWorldClock.MAX_RATE))
                                        .executes(ShanhaiClockCommands::setRate))))
                // 倒流的新入口（用户口径：先设锚点 ⇒ /shanhai_add time rewind 回到锚点）
                .then(Commands.literal("time")
                        .then(Commands.literal("rewind")
                                .requires(source -> source.hasPermission(2))
                                // 🔴 无参 = 回到锚点（用户口述："先要玩家设置锚点，基于此再进行倒流"）
                                .executes(ShanhaiClockCommands::rewindToAnchor)
                                .then(Commands.literal("stop")
                                        .executes(ShanhaiClockCommands::stopReplay))
                                // 带秒数 = 旧语义（回退 N 秒），一个字都不改（判据 ⑤）
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3_600))
                                        .executes(ctx -> startReplay(ctx,
                                                IntegerArgumentType.getInteger(ctx, "seconds"),
                                                ShanhaiReplayModeCompose.Action.REWIND))))
                        .then(Commands.literal("anchor")
                                .then(Commands.literal("set")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(ShanhaiClockCommands::anchorSet))
                                .then(Commands.literal("status")
                                        .executes(ShanhaiClockCommands::anchorStatus))
                                .then(Commands.literal("clear")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(ShanhaiClockCommands::anchorClear)))
                        .then(Commands.literal("status")
                                .executes(ShanhaiClockCommands::replayStatus))
                        // 2026-11 新增：覆盖矩阵（类别 / 层级 / 判据 / 已知漏点）+ 预算 + SavedData 名单
                        .then(Commands.literal("coverage")
                                .executes(ShanhaiClockCommands::coverage))));
    }

    // ------------------------------------------------------------------ 子命令

    private static int changeMode(CommandContext<CommandSourceStack> ctx, ShanhaiClockMode mode) {
        ShanhaiWorldClock clock = ShanhaiWorldClock.SERVER;
        // 🔴 倒流中切到任何别的模式 = 先走完整收尾（回 LIVE + 恢复写盘 + 清 ring），
        //    绝不允许"把 noSave 留在 true、把游标留在半截"就换模式（那是丢档路径）。
        if (ShanhaiReplayService.isActive() && mode != ShanhaiClockMode.REPLAY) {
            ShanhaiReplayService.stop(ctx.getSource().getServer(), "command_" + mode.name().toLowerCase(java.util.Locale.ROOT));
        }
        ShanhaiClockMode before = clock.getMode();
        noteTick(clock, ctx);
        boolean changed = clock.setMode(mode);
        ShanhaiClockMode after = clock.getMode();

        if (after != mode) {
            // setMode 拒绝只有一种情况：null（这里不可能）。留一条防御分支，避免"命令说成功但没变"。
            ctx.getSource().sendFailure(Component.literal("时钟模式未改变（当前 " + after + "）"));
            return 0;
        }
        Component msg = Component.literal("[山海时钟] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(before + " → " + after).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("（epoch " + clock.getEpoch() + (changed ? "，已生效" : "，本来就是该状态") + "）")
                        .withStyle(ChatFormatting.GRAY));
        ctx.getSource().sendSuccess(() -> msg, true);

        // 🔴 判据 ③：`clock resume` 成功后必须打一行"记录闸开着"的日志（进游戏一眼可判，字符串判据见下一行）。
        //    记录闸 = (mode != REPLAY)，另一条线已经把它接进记录器 ⇒ "暂停之后重新打开时间" 记录照常继续；
        //    这里只负责把这件事打成证据（不是靠聊天栏，是靠日志）。
        if (mode == ShanhaiClockMode.LIVE) {
            final boolean recording = ShanhaiReplayModeCompose.recordsUnder(after);
            final String actor = actorName(ctx.getSource());
            if (recording) {
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] clock resume mode={} recording=true actor={} epoch={}",
                        after, actor, clock.getEpoch());
            } else {
                ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] clock resume mode={} recording=false actor={} epoch={}",
                        after, actor, clock.getEpoch());
            }
        }

        // 立刻把新状态推给所有客户端（不等下一个外层 tick，避免"服务端冻了、客户端还在跑"的一瞬）
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            ShanhaiClockNetwork.sendStateTo(player);
        }
        return 1;
    }

    /**
     * 时间倒流的<b>唯一</b>真入口（{@code /shanhai_add time rewind [秒数]}）。
     *
     * <p>🔴 2026-11-14 起 {@code /clock replay} <b>不再</b>走这里（它只回指引，见
     * {@link #clockReplayRefused}）：用户口径是「要倒流请用 /shanhai_add time rewind」，
     * 两个入口共用同一段实现的时代结束了 —— 现在只有一段入口、一套语义。
     *
     * @param seconds {@code <= 0} = 把整个窗口倒完
     */
    private static int startReplay(CommandContext<CommandSourceStack> ctx, int seconds,
                                   ShanhaiReplayModeCompose.Action action) {
        final CommandSourceStack source = ctx.getSource();
        final ServerLevel level;
        try {
            level = source.getLevel();
        } catch (Throwable t) {
            source.sendFailure(Component.literal("[山海时钟] 这条指令需要在一个世界里执行"));
            return 0;
        }
        if (level == null) {
            source.sendFailure(Component.literal("[山海时钟] 没有世界（先在游戏里执行）"));
            return 0;
        }
        // 🔴 2026-11-14：入口级语义闸（纯逻辑核 = ShanhaiReplayModeCompose，与自检 V 组同一份）
        //    PAUSED + /clock replay ⇒ 拒绝（不要"顺手开始倒流"，他要在暂停下继续记录）
        final ShanhaiClockMode mode = ShanhaiWorldClock.SERVER.getMode();
        final ShanhaiReplayModeCompose.Refusal refusal = ShanhaiReplayModeCompose.refuse(action, mode);
        if (refusal.refused()) {
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] replay_refused reason={} action={} mode={}",
                    refusal.code(), action.code(), mode);
            source.sendFailure(Component.literal("[山海时钟] 没有开始倒流（" + refusal.code() + "）："
                    + refusal.text()));
            return 0;
        }
        final ServerPlayer actor = source.getEntity() instanceof ServerPlayer p ? p : null;
        final boolean started = ShanhaiReplayService.start(source.getServer(), actor, seconds);
        if (!started) {
            source.sendFailure(Component.literal("[山海时钟] 倒流没有开始（见聊天上一句/服务器日志 [SHANHAI-REPLAY] start_refused）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[山海时钟] 倒流已开始：" + ShanhaiReplayService.window()
                        + " tick（≈" + (ShanhaiReplayService.window() / ShanhaiReplayConst.TICKS_PER_SECOND)
                        + " 秒）；世界 tick 已冻结、存档暂停写入；/shanhai_add time rewind stop 可中止"),
                true);
        return 1;
    }

    /**
     * {@code /shanhai_add clock replay [秒数]} —— <b>不再开始倒流</b>，只回一句明确指引。
     *
     * <p>用户口径（第二次澄清更准的版本，逐字）：
     * 「我说的暂停→继续记录指的是，暂停之后重新打开时间，可以继续记录，而不是输入
     * {@code /shanhai_add clock replay} 之后直接开始倒流了」
     * ⇒ 这个方法体里<b>不允许</b>出现 {@code startReplay} / {@code startAtAnchor} 这样的开工调用，
     * 它是"拒绝 + 指路"，不是"换个入口继续倒流"（判据 ①：本方法体内这两个名字命中 = 0）。
     *
     * <p>为什么不是直接删掉这个命令：老玩家手上还留着 {@code /clock replay}，
     * 让它回一句"该用哪条命令"比让 Brigadier 报"未知命令"更好排查；
     * 而且它能回一条可 grep 的日志 {@code clock_replay_refused reason=use_time_rewind}。
     */
    private static int clockReplayRefused(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final ShanhaiClockMode mode = ShanhaiWorldClock.SERVER.getMode();
        // 判据 ②：这一行是给游戏外 grep 的证据（进游戏怎么一眼判，见交付报告）
        ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] clock_replay_refused reason=use_time_rewind actor={} mode={}",
                actorName(source), mode);
        final Component msg = Component.literal("[山海时钟] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("这个子命令不再开始倒流；要倒流请用 "
                                + "/shanhai_add time rewind [秒数|锚点回退]；要看状态用 /shanhai_add time status")
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("（mode=" + mode
                                + " 记录闸=" + (ShanhaiReplayModeCompose.recordsUnder(mode) ? "记录中" : "暂停记录") + "）")
                        .withStyle(ChatFormatting.GRAY));
        // 成功路径（=1），但"成功"指的是**把话说清楚**，不是"开工"：一行倒流动作都没执行
        source.sendSuccess(() -> msg, false);
        return 1;
    }

    /** 立即中止倒流（走同一套收尾）。 */
    private static int stopReplay(CommandContext<CommandSourceStack> ctx) {
        final boolean stopped = ShanhaiReplayService.stop(ctx.getSource().getServer(), "command_stop");
        if (!stopped) {
            ctx.getSource().sendFailure(Component.literal("[山海时钟] 现在没有在倒流"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[山海时钟] 倒流已中止，回到 LIVE（存档已恢复写入）"), true);
        return 1;
    }

    // ------------------------------------------------------------------ 锚点

    /**
     * {@code /shanhai_add time rewind}（<b>无参</b>）—— 回到锚点。
     *
     * <p>无锚点时<b>明确</b>提示"先设锚点"（判据 ②：{@code no_anchor}），
     * 不做"退而求其次倒整个窗口"这种静默降级。
     */
    private static int rewindToAnchor(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        // 🔴 2026-11-14：PAUSED 下允许回锚点（"暂停 = 停在某一刻，倒流 = 从这一刻往回走"）
        final ShanhaiClockMode mode = ShanhaiWorldClock.SERVER.getMode();
        final ShanhaiReplayModeCompose.Refusal refusal =
                ShanhaiReplayModeCompose.refuse(ShanhaiReplayModeCompose.Action.REWIND, mode);
        if (refusal.refused()) {
            ShanhaiMod.LOGGER.info("[SHANHAI-REPLAY] replay_refused reason={} action={} mode={}",
                    refusal.code(), ShanhaiReplayModeCompose.Action.REWIND.code(), mode);
            source.sendFailure(Component.literal("[山海时钟] 没有开始倒流（" + refusal.code() + "）："
                    + refusal.text()));
            return 0;
        }
        final ServerPlayer actor = source.getEntity() instanceof ServerPlayer p ? p : null;
        final boolean started = ShanhaiReplayService.startAtAnchor(source.getServer(), actor);
        if (!started) {
            source.sendFailure(Component.literal("[山海时钟] 没有开始倒流（见上一句；"
                    + "无锚点就先 /shanhai_add time anchor set）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[山海时钟] 正在回到锚点：tick="
                        + ShanhaiReplayService.anchorTick()
                        + "，回退 " + ShanhaiReplayService.window() + " tick（≈"
                        + (ShanhaiReplayService.window() / ShanhaiReplayConst.TICKS_PER_SECOND)
                        + " 秒）"),
                true);
        return 1;
    }

    /** {@code /shanhai_add time anchor set} —— 在当前位置记下锚点（{@code LIVE} 与 {@code PAUSED} 都允许）。 */
    private static int anchorSet(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[山海时钟] 锚点记的是「你的位置」，必须在游戏里由玩家执行"));
            return 0;
        }
        final ShanhaiReplayAnchor.Refusal refusal =
                ShanhaiReplayAnchorRuntime.set(source.getServer(), player);
        if (refusal.refused()) {
            source.sendFailure(Component.literal("[山海时钟] 设锚点被拒绝（" + refusal.code() + "）：" + refusal.text()));
            return 0;
        }
        // 锚点条：让客户端立刻出现那条 60 秒的条（不等下一次周期滴）
        ShanhaiAnchorBarBroadcast.onAnchorSet(source.getServer());
        final ShanhaiReplayJournal.Anchor anchor = ShanhaiReplayRecorder.journal().anchor();
        source.sendSuccess(() -> Component.literal("[山海时钟] 锚点已记下：" + ShanhaiReplayAnchor.setLine(anchor)
                + "\n之后执行 /shanhai_add time rewind（不带秒数）就回到这一刻"), true);
        return 1;
    }

    /** {@code /shanhai_add time anchor status} —— tick / 坐标 / 朝向 / 距现在多少 tick / 是否 stale。 */
    private static int anchorStatus(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        send(source, ShanhaiReplayAnchorRuntime.statusLine(source.getServer()));
        return 1;
    }

    /** {@code /shanhai_add time anchor clear} —— 清掉锚点（之后无参 rewind 会拒绝 no_anchor）。 */
    private static int anchorClear(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final ServerPlayer actor = source.getEntity() instanceof ServerPlayer p ? p : null;
        // 🔴 维度要在清掉**之前**取：清完 journal.anchor() 就是 null，那一帧"条消失"会发给没人
        final ShanhaiReplayJournal.Anchor before = ShanhaiReplayRecorder.journal().anchor();
        final int dimBefore = before == null ? -1 : before.dim();
        if (!ShanhaiReplayAnchorRuntime.clear(source.getServer(), actor)) {
            source.sendFailure(Component.literal("[山海时钟] 本来就没有锚点"));
            return 0;
        }
        // 锚点条：让客户端把条去掉（判据 ③：这一句没了，屏幕上会一直挂着旧锚点的条）
        ShanhaiAnchorBarBroadcast.onAnchorClear(source.getServer(), dimBefore);
        source.sendSuccess(() -> Component.literal("[山海时钟] 锚点已清除"), true);
        return 1;
    }

    /** 只看倒流那几行（{@code /shanhai_add time status}）。 */
    private static int replayStatus(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        send(source, "倒流: " + ShanhaiReplayService.statusLine());
        send(source, ShanhaiReplayAnchorRuntime.statusLine(source.getServer()));
        send(source, "到期: " + ShanhaiReplayAnchorRuntime.expiryStatusLine());
        send(source, "记录器: " + ShanhaiReplayService.recorderLine());
        send(source, "闸门: " + ShanhaiReplayGate.describe(ShanhaiWorldClock.SERVER.getMode()));
        return 1;
    }

    /**
     * {@code /shanhai_add time coverage} —— 倒流覆盖矩阵 + 预算 + SavedData 名单。
     *
     * <p>🔴 这张表是<b>交付物</b>（用户口径："我要覆盖整个游戏内所有的内容都覆盖到，但是可能会漏"）：
     * 逐行 = 类别 / 覆盖层级 / 判据 / 已知漏点，全部由纯逻辑核
     * {@link ShanhaiReplayCoverage} 与 {@link ShanhaiReplayBudget} 生成（离线可断言，不是手写文案）。
     */
    private static int coverage(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        for (String line : ShanhaiReplayCoverage.lines()) {
            send(source, line);
        }
        for (String line : ShanhaiReplayBudget.lines()) {
            send(source, line);
        }
        for (String line : ShanhaiReplaySavedDataAudit.lines(source.getServer())) {
            send(source, line);
        }
        for (String line : ShanhaiReplayCoverage.gapLines()) {
            send(source, "已知漏点: " + line);
        }
        return 1;
    }

    private static int setRate(CommandContext<CommandSourceStack> ctx) {
        double value = DoubleArgumentType.getDouble(ctx, "value");
        ShanhaiWorldClock clock = ShanhaiWorldClock.SERVER;
        boolean changed = clock.setRate(value);
        if (!changed && Math.abs(clock.getRate() - value) > 1.0E-9D) {
            ctx.getSource().sendFailure(Component.literal(
                    "[山海时钟] 倍率被拒绝（允许范围 " + ShanhaiWorldClock.MIN_RATE + " … "
                            + ShanhaiWorldClock.MAX_RATE + "，当前 " + clock.getRate() + "）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                        "[山海时钟] rate=%.3f 生效值=%.3f（PAUSED/REPLAY 下生效值恒为 0）",
                        clock.getRate(), clock.effectiveRate())), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ShanhaiWorldClock clock = ShanhaiWorldClock.SERVER;

        long tick = 0L;
        try {
            ServerLevel level = source.getLevel();
            tick = level == null ? 0L : level.getGameTime();
        } catch (Throwable ignored) {
            // 控制台在没有世界时也可能敲 status
        }

        int ack = 0;
        boolean hasPlayer = false;
        if (source.getEntity() instanceof ServerPlayer player) {
            hasPlayer = true;
            ack = ShanhaiClockNetwork.ackOf(player);
        }

        String clientLine;
        if (!hasPlayer) {
            clientLine = "客户端时钟=<无客户端，控制台看不到>";
        } else if (ack == 0) {
            clientLine = "客户端时钟=<客户端尚未回报>（多客户端时看执行者那份）";
        } else {
            clientLine = ackText(ack);
        }

        String oculus = !hasPlayer || ack == 0 ? "unknown" : ((ack & (1 << 7)) != 0 ? "on" : "off");

        send(source, "mode=" + clock.getMode() + " tick=" + tick + " epoch=" + clock.getEpoch()
                + " anchorTick=" + clock.getAnchorTick()
                + " 冻结=" + (ShanhaiClockHooks.freezeServerLevelTick() ? "是" : "否"));
        send(source, String.format(java.util.Locale.ROOT,
                "rate=%.3f effective=%.3f clientMsPerTick=%.2f",
                clock.getRate(), clock.effectiveRate(), clock.getClientMsPerTick()));
        // 🔴 2026-11：判据从 clock.isPaused() 换成 freezeServerLevelTick()（冻结态的唯一真值），
        //    并把外层 tick 预算一起打出来（它就是"该跑几次世界 tick"，以前这个返回值被丢掉了）。
        send(source, "服务端: ServerLevel#tick=" + (ShanhaiClockHooks.freezeServerLevelTick() ? "已跳过" : "运行中")
                + " 外层tick预算=" + ShanhaiClockInit.lastWantedTicks()
                + " 世界写入硬网=" + (ShanhaiClockHooks.denyWorldWrite() ? "已拦截(仅服务端)" : "放行")
                // 2026-10-18 新增判据：暂停期间【客户端】的表现写（藏环/恢复环）被放行的次数。
                // 修好按侧分流后它应该在涨；若它恒为 0 而 blocked=world_write 在涨 ⇒ 还在误拦客户端。
                + " client_write_allowed=" + ShanhaiClockHooks.clientWriteAllowedCount());
        send(source, "倒流: " + ShanhaiReplayService.statusLine());
        send(source, "记录器: " + ShanhaiReplayService.recorderLine());
        send(source, clientLine);
        send(source, "光影hook=" + oculus);
        send(source, ShanhaiClockGuard.summary());
        return 1;
    }

    /** 客户端 ack 位掩码 → 人类可读（不依赖客户端类，服务端也能打印）。 */
    private static String ackText(int mask) {
        StringBuilder sb = new StringBuilder("客户端时钟:");
        sb.append("ticks=").append(bit(mask, 0, "已钉住"));
        sb.append(" tickTime=").append(bit(mask, 1, "已钉住"));
        sb.append(" texture=").append(bit(mask, 2, "已钉住"));
        sb.append(" particles=").append(bit(mask, 3, "已钉住"));
        sb.append(" entities=").append(bit(mask, 4, "已钉住"));
        sb.append(" blockEntities=").append(bit(mask, 5, "已钉住"));
        sb.append(" partialTick=").append(bit(mask, 6, "已钉住(非默认)"));
        sb.append(" posePinned=").append(bit(mask, 9, "已对齐"));
        sb.append(" smoothTick=").append(bit(mask, 10, "已冻结"));
        sb.append(" sound=").append(bit(mask, 8, "已暂停"));
        return sb.toString();
    }

    private static String bit(int mask, int index, String on) {
        return (mask & (1 << index)) != 0 ? on : "未触发";
    }

    private static void noteTick(ShanhaiWorldClock clock, CommandContext<CommandSourceStack> ctx) {
        try {
            ServerLevel level = ctx.getSource().getLevel();
            if (level != null) {
                clock.noteAnchorTick(level.getGameTime());
            }
        } catch (Throwable ignored) {
            // 没世界就算了（anchorTick 只是诊断字段）
        }
    }

    /**
     * 执行者名字（日志用）：玩家 ⇒ 玩家名；控制台/命令方块 ⇒ {@code console}。
     *
     * <p>{@code actor=<玩家名>} 是用户点名要的字段（方便他从日志里认出是谁敲的）。
     */
    private static String actorName(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p ? p.getName().getString() : "console";
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
