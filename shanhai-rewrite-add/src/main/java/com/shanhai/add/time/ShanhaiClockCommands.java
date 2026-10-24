package com.shanhai.add.time;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 山海重构 · {@code /shanhai_add clock …} 指令。
 *
 * <h2>用法</h2>
 * <pre>
 *   /shanhai_add clock pause     冻结世界（连光影/光照一起停）
 *   /shanhai_add clock resume    恢复
 *   /shanhai_add clock status    打印 mode/tick/客户端三处时钟/光影 hook/拦截计数
 *   /shanhai_add clock rate &lt;x&gt;  设倍率（本轮只影响 status 与下一轮的加速；PAUSED 下不生效）
 *   /shanhai_add clock replay    占位：本轮不实现（回：未实现）
 * </pre>
 *
 * <p>权限：{@code pause}/{@code resume}/{@code rate} 需要权限等级 2；{@code status} 谁都能看。
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
                        .then(Commands.literal("replay")
                                .requires(source -> source.hasPermission(2))
                                .executes(ShanhaiClockCommands::replayNotImplemented))
                        .then(Commands.literal("status")
                                .executes(ShanhaiClockCommands::status))
                        .then(Commands.literal("rate")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(ShanhaiWorldClock.MIN_RATE,
                                                ShanhaiWorldClock.MAX_RATE))
                                        .executes(ShanhaiClockCommands::setRate)))));
    }

    // ------------------------------------------------------------------ 子命令

    private static int changeMode(CommandContext<CommandSourceStack> ctx, ShanhaiClockMode mode) {
        ShanhaiWorldClock clock = ShanhaiWorldClock.SERVER;
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

        // 立刻把新状态推给所有客户端（不等下一个外层 tick，避免"服务端冻了、客户端还在跑"的一瞬）
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            ShanhaiClockNetwork.sendStateTo(player);
        }
        return 1;
    }

    private static int replayNotImplemented(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal(
                "[山海时钟] REPLAY（时间倒流）本轮未实现。骨架与状态已留好（ShanhaiClockMode.REPLAY），"
                        + "倒流需要「增量日志 + 播放头」，不在本轮的暂停范围内。"));
        return 0;
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
                + " anchorTick=" + clock.getAnchorTick());
        send(source, String.format(java.util.Locale.ROOT,
                "rate=%.3f effective=%.3f clientMsPerTick=%.2f",
                clock.getRate(), clock.effectiveRate(), clock.getClientMsPerTick()));
        send(source, "服务端: ServerLevel#tick=" + (clock.isPaused() ? "已跳过" : "运行中")
                + " 世界写入硬网=" + (ShanhaiClockHooks.denyWorldWrite() ? "已拦截(仅服务端)" : "放行")
                // 2026-10-18 新增判据：暂停期间【客户端】的表现写（藏环/恢复环）被放行的次数。
                // 修好按侧分流后它应该在涨；若它恒为 0 而 blocked=world_write 在涨 ⇒ 还在误拦客户端。
                + " client_write_allowed=" + ShanhaiClockHooks.clientWriteAllowedCount());
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

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
