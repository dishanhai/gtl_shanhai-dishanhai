package com.dishanhai.gt_shanhai.common.time;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.time.anchor.ShanhaiAnchorBarBroadcast;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayAnchor;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayAnchorRuntime;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayBudget;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayConst;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayCoverage;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayGate;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayJournal;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayModeCompose;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayRecorder;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplaySavedDataAudit;
import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayService;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-authoritative time controls under /shanhai time.
 * Mutations require the actual player's level-2 permission.
 */
public final class ShanhaiClockCommands {

    private ShanhaiClockCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("shanhai")
                .then(Commands.literal("time")
                        .then(Commands.literal("pause")
                                .requires(ShanhaiClockCommands::mayControl)
                                .executes(ctx -> changeMode(ctx, ShanhaiClockMode.PAUSED)))
                        .then(Commands.literal("resume")
                                .requires(ShanhaiClockCommands::mayControl)
                                .executes(ctx -> changeMode(ctx, ShanhaiClockMode.LIVE)))
                        .then(Commands.literal("record")
                                .requires(ShanhaiClockCommands::mayControl)
                                .executes(ShanhaiClockCommands::recordStatus)
                                .then(Commands.literal("start").executes(ShanhaiClockCommands::recordStart))
                                .then(Commands.literal("stop").executes(ShanhaiClockCommands::recordStop)))
                        .then(Commands.literal("rewind")
                                .requires(ShanhaiClockCommands::mayControl)
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
                                        .requires(ShanhaiClockCommands::mayControl)
                                        .executes(ShanhaiClockCommands::anchorSet))
                                .then(Commands.literal("status")
                                        .executes(ShanhaiClockCommands::anchorStatus))
                                .then(Commands.literal("clear")
                                        .requires(ShanhaiClockCommands::mayControl)
                                        .executes(ShanhaiClockCommands::anchorClear)))
                        .then(Commands.literal("status")
                                .executes(ShanhaiClockCommands::replayStatus))));
    }

    private static boolean mayControl(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return ReplayPermission.allowed(true, player.hasPermissions(2), source.hasPermission(2));
        }
        return ReplayPermission.allowed(false, false, source.hasPermission(2));
    }

    private static int recordStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.translatable(ReplayRecording.isEnabled()
                ? "gt_shanhai.time.record.active" : "gt_shanhai.time.record.inactive"), false);
        return 1;
    }

    private static int recordStart(CommandContext<CommandSourceStack> ctx) {
        if (ShanhaiReplayService.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.record.replaying"));
            return 0;
        }
        if (!ReplayRecording.isEnabled()) {
            var server = ctx.getSource().getServer();
            var oldAnchor = ShanhaiReplayRecorder.journal().anchor();
            if (oldAnchor != null) {
                int oldDimension = oldAnchor.dim();
                ShanhaiReplayAnchorRuntime.clear(server, null);
                ShanhaiAnchorBarBroadcast.onAnchorClear(server, oldDimension);
            }
            ShanhaiReplayService.onServerStarted(server);
            ReplayRecording.start();
            com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayBlockEntityRecorder.bootstrap(server);
            com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayCapture.endOfTick(server);
        }
        return recordStatus(ctx);
    }

    private static int recordStop(CommandContext<CommandSourceStack> ctx) {
        if (ShanhaiReplayService.isRunning()) {
            ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.record.replaying"));
            return 0;
        }
        ReplayRecording.stop();
        var server = ctx.getSource().getServer();
        var anchor = ShanhaiReplayRecorder.journal().anchor();
        if (anchor != null) {
            int dimension = anchor.dim();
            ShanhaiReplayAnchorRuntime.clear(server, null);
            ShanhaiAnchorBarBroadcast.onAnchorClear(server, dimension);
        }
        ShanhaiReplayService.onServerStarted(server);
        return recordStatus(ctx);
    }

    // ------------------------------------------------------------------ 子命令

    private static int changeMode(CommandContext<CommandSourceStack> ctx, ShanhaiClockMode mode) {
        if (ShanhaiReplayService.isFailed()) {
            ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.rewind.apply_failed"));
            return 0;
        }
        ShanhaiWorldClock clock = ShanhaiWorldClock.SERVER;
        // 🔴 倒流中切到任何别的模式 = 先走完整收尾（回 LIVE + 恢复写盘 + 清 ring），
        //    绝不允许"把 noSave 留在 true、把游标留在半截"就换模式（那是丢档路径）。
        if (ShanhaiReplayService.isActive() && mode != ShanhaiClockMode.REPLAY) {
            ShanhaiReplayService.stop(ctx.getSource().getServer(), "command_" + mode.name().toLowerCase(java.util.Locale.ROOT));
            if (ShanhaiReplayService.isFailed()) {
                ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.rewind.apply_failed"));
                return 0;
            }
            if (ShanhaiReplayService.isRunning()) {
                ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.stop.pending"));
                return 0;
            }
        }
        ShanhaiClockMode before = clock.getMode();
        noteTick(clock, ctx);
        boolean changed = clock.setMode(mode);
        ShanhaiClockMode after = clock.getMode();

        if (after != mode) {
            // setMode 拒绝只有一种情况：null（这里不可能）。留一条防御分支，避免"命令说成功但没变"。
            ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.mode.unchanged", after.name()));
            return 0;
        }
        Component msg = Component.translatable("gt_shanhai.time.mode.changed", before.name(), after.name());
        ctx.getSource().sendSuccess(() -> msg, true);

        // 🔴 判据 ③：`clock resume` 成功后必须打一行"记录闸开着"的日志（进游戏一眼可判，字符串判据见下一行）。
        //    记录闸 = (mode != REPLAY)，另一条线已经把它接进记录器 ⇒ "暂停之后重新打开时间" 记录照常继续；
        //    这里只负责把这件事打成证据（不是靠聊天栏，是靠日志）。
        if (mode == ShanhaiClockMode.LIVE) {
            final boolean recording = ShanhaiReplayModeCompose.recordsUnder(after);
            final String actor = actorName(ctx.getSource());
            if (recording) {
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] clock resume mode={} recording=true actor={} epoch={}",
                        after, actor, clock.getEpoch());
            } else {
                GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] clock resume mode={} recording=false actor={} epoch={}",
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
     * 时间倒流的<b>唯一</b>真入口（{@code /shanhai time rewind [秒数]}）。
     *
     * <p>🔴 2026-11-14 起 {@code /clock replay} <b>不再</b>走这里（它只回指引，见
     * {@link #clockReplayRefused}）：用户口径是「要倒流请用 /shanhai time rewind」，
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
            source.sendFailure(Component.translatable("gt_shanhai.time.no_world"));
            return 0;
        }
        if (level == null) {
            source.sendFailure(Component.translatable("gt_shanhai.time.no_world"));
            return 0;
        }
        // 🔴 2026-11-14：入口级语义闸（纯逻辑核 = ShanhaiReplayModeCompose，与自检 V 组同一份）
        //    PAUSED + /clock replay ⇒ 拒绝（不要"顺手开始倒流"，他要在暂停下继续记录）
        final ShanhaiClockMode mode = ShanhaiWorldClock.SERVER.getMode();
        final ShanhaiReplayModeCompose.Refusal refusal = ShanhaiReplayModeCompose.refuse(action, mode);
        if (refusal.refused()) {
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] replay_refused reason={} action={} mode={}",
                    refusal.code(), action.code(), mode);
            source.sendFailure(Component.translatable("gt_shanhai.time.rewind.refused", refusal.code()));
            return 0;
        }
        final ServerPlayer actor = source.getEntity() instanceof ServerPlayer p ? p : null;
        final boolean started = ShanhaiReplayService.start(source.getServer(), actor, seconds);
        if (!started) {
            source.sendFailure(Component.translatable("gt_shanhai.time.rewind.failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("gt_shanhai.time.rewind.started", ShanhaiReplayService.window()),
                true);
        return 1;
    }

    /**
     * {@code /shanhai clock replay [秒数]} —— <b>不再开始倒流</b>，只回一句明确指引。
     *
     * <p>用户口径（第二次澄清更准的版本，逐字）：
     * 「我说的暂停→继续记录指的是，暂停之后重新打开时间，可以继续记录，而不是输入
     * {@code /shanhai clock replay} 之后直接开始倒流了」
     * ⇒ 这个方法体里<b>不允许</b>出现 {@code startReplay} / {@code startAtAnchor} 这样的开工调用，
     * 它是"拒绝 + 指路"，不是"换个入口继续倒流"（判据 ①：本方法体内这两个名字命中 = 0）。
     *
     * <p>为什么不是直接删掉这个命令：老玩家手上还留着 {@code /clock replay}，
     * 让它回一句"该用哪条命令"比让 Brigadier 报"未知命令"更好排查；
     * 而且它能回一条可 grep 的日志 {@code clock_replay_refused reason=use_time_rewind}。
     */
    /** 立即中止倒流（走同一套收尾）。 */
    private static int stopReplay(CommandContext<CommandSourceStack> ctx) {
        if (ShanhaiReplayService.isFailed()) {
            ctx.getSource().sendFailure(Component.translatable("gt_shanhai.time.rewind.apply_failed"));
            return 0;
        }
        final boolean stopped = ShanhaiReplayService.stop(ctx.getSource().getServer(), "command_stop");
        if (!stopped) {
            ctx.getSource().sendFailure(Component.translatable(ShanhaiReplayService.isFailed()
                    ? "gt_shanhai.time.rewind.apply_failed" : "gt_shanhai.time.rewind.not_active"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(ShanhaiReplayService.isRunning()
                ? "gt_shanhai.time.stop.pending" : "gt_shanhai.time.stop.done"), true);
        return 1;
    }

    // ------------------------------------------------------------------ 锚点

    /**
     * {@code /shanhai time rewind}（<b>无参</b>）—— 回到锚点。
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
            GTDishanhaiMod.LOGGER.info("[SHANHAI-REPLAY] replay_refused reason={} action={} mode={}",
                    refusal.code(), ShanhaiReplayModeCompose.Action.REWIND.code(), mode);
            source.sendFailure(Component.translatable("gt_shanhai.time.rewind.refused", refusal.code()));
            return 0;
        }
        final ServerPlayer actor = source.getEntity() instanceof ServerPlayer p ? p : null;
        final boolean started = ShanhaiReplayService.startAtAnchor(source.getServer(), actor);
        if (!started) {
            source.sendFailure(Component.translatable("gt_shanhai.time.rewind.anchor_failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("gt_shanhai.time.rewind.anchor_started", ShanhaiReplayService.anchorTick(), ShanhaiReplayService.window()),
                true);
        return 1;
    }

    /** {@code /shanhai time anchor set} —— 在当前位置记下锚点（{@code LIVE} 与 {@code PAUSED} 都允许）。 */
    private static int anchorSet(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        if (!ReplayRecording.isEnabled()) {
            source.sendFailure(Component.translatable("gt_shanhai.time.record.required"));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("gt_shanhai.time.anchor.player_required"));
            return 0;
        }
        final ShanhaiReplayAnchor.Refusal refusal =
                ShanhaiReplayAnchorRuntime.set(source.getServer(), player);
        if (refusal.refused()) {
            source.sendFailure(Component.translatable("gt_shanhai.time.anchor.refused", refusal.code()));
            return 0;
        }
        // 锚点条：让客户端立刻出现那条 60 秒的条（不等下一次周期滴）
        ShanhaiAnchorBarBroadcast.onAnchorSet(source.getServer());
        final ShanhaiReplayJournal.Anchor anchor = ShanhaiReplayRecorder.journal().anchor();
        source.sendSuccess(() -> Component.translatable("gt_shanhai.time.anchor.set", anchor.tick()), true);
        return 1;
    }

    /** {@code /shanhai time anchor status} —— tick / 坐标 / 朝向 / 距现在多少 tick / 是否 stale。 */
    private static int anchorStatus(CommandContext<CommandSourceStack> ctx) {
        var anchor = ShanhaiReplayRecorder.journal().anchor();
        if (anchor == null) {
            ctx.getSource().sendSuccess(() -> Component.translatable("gt_shanhai.time.anchor.missing"), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.translatable("gt_shanhai.time.anchor.status", anchor.tick(), anchor.posText()), false);
        }
        return 1;
    }

    /** {@code /shanhai time anchor clear} —— 清掉锚点（之后无参 rewind 会拒绝 no_anchor）。 */
    private static int anchorClear(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final ServerPlayer actor = source.getEntity() instanceof ServerPlayer p ? p : null;
        // 🔴 维度要在清掉**之前**取：清完 journal.anchor() 就是 null，那一帧"条消失"会发给没人
        final ShanhaiReplayJournal.Anchor before = ShanhaiReplayRecorder.journal().anchor();
        final int dimBefore = before == null ? -1 : before.dim();
        if (!ShanhaiReplayAnchorRuntime.clear(source.getServer(), actor)) {
            source.sendFailure(Component.translatable("gt_shanhai.time.anchor.missing"));
            return 0;
        }
        // 锚点条：让客户端把条去掉（判据 ③：这一句没了，屏幕上会一直挂着旧锚点的条）
        ShanhaiAnchorBarBroadcast.onAnchorClear(source.getServer(), dimBefore);
        source.sendSuccess(() -> Component.translatable("gt_shanhai.time.anchor.cleared"), true);
        return 1;
    }

    /** 只看倒流那几行（{@code /shanhai time status}）。 */
    private static int replayStatus(CommandContext<CommandSourceStack> ctx) {
        var mode = ShanhaiWorldClock.SERVER.getMode().name();
        var recording = Component.translatable(ReplayRecording.isEnabled()
                ? "gt_shanhai.time.record.active" : "gt_shanhai.time.record.inactive");
        ctx.getSource().sendSuccess(() -> Component.translatable("gt_shanhai.time.status", mode, recording,
                ShanhaiReplayService.isRunning(), ShanhaiReplayService.remaining()), false);
        return 1;
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


}
