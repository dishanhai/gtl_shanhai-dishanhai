package com.shanhai.common.guard;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shanhai.ShanhaiMod;
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
 * 山海 · 创始现实修改模块的命令入口（<b>与既有命令同一种写法</b>）。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai purge                      🔴 主动触发：抹除【全服所有实体】（跳过所有玩家）
 *   /shanhai purge count                只数不抹（安全读数；用来先看"范围内 X 只"）
 *   /shanhai suppress                   看压制表（= suppress list）
 *   /shanhai suppress list              逐条列出"现在还在压制窗口里的类型 + 剩余秒数"
 *   /shanhai suppress clear             清空压制表（立刻停止周期抹除）
 *   /shanhai guard                      看开关现在什么状态
 *   /shanhai guard ai on|off            AI 层（默认关）
 *   /shanhai guard attack on|off        攻击端（默认开）
 *   /shanhai guard death on|off         死而无损（默认开）
 *   /shanhai guard knockback on|off     🆕 反击退 / 反挤压（默认开；关掉后完全不动玩家）
 * </pre>
 *
 * <h2>🔴 门槛（与既有命令同口径）</h2>
 * <b>玩家</b>执行时必须身上带着「创始现实修改模块」（{@link ShanhaiHoloMenuNetwork#moduleInInventory} ——
 * 与 {@code /shanhai items} ／ 全息命令输入框同一个纯函数口径）；
 * <b>控制台/命令方块不受限</b>（那是运维通道，不该被一个背包判定挡死）。
 *
 * <h2>为什么另注册一个同名 literal</h2>
 * 与 {@code ShanhaiCreativeItemsCommand} ／ {@code ShanhaiRecipeStats} 同族：
 * Brigadier 会把同名子节点合并 ⇒ 既有命令的 children 一个都不会丢。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiGuardCommand {

    public static final String PREFIX = ShanhaiGuardCore.LOG_PREFIX;

    /** 主动抹除那个子命令名。 */
    public static final String COMMAND_PURGE = "purge";
    /** 三个开关那个子命令名。 */
    public static final String COMMAND_GUARD = "guard";
    /** 🆕 压制表那个子命令名。 */
    public static final String COMMAND_SUPPRESS = "suppress";

    private ShanhaiGuardCommand() {}

    /** 命令分支（{@code /shanhai} 下面挂的几棵子树）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_PURGE)
                .executes(ctx -> purge(ctx, false))
                .then(Commands.literal("count").executes(ctx -> purge(ctx, true)))
                .then(Commands.literal("selfcheck").executes(ShanhaiGuardCommand::selfcheck))
                .then(guardBranch())
                .then(suppressBranch());
    }

    /** 🆕 {@code /shanhai suppress …}（门与 purge/guard 同一条：玩家身上要带模块）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> suppressBranch() {
        return Commands.literal(COMMAND_SUPPRESS)
                .executes(ShanhaiGuardCommand::suppressList)
                .then(Commands.literal("list").executes(ShanhaiGuardCommand::suppressList))
                .then(Commands.literal("clear").executes(ShanhaiGuardCommand::suppressClear));
    }

    /** {@code /shanhai guard …}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> guardBranch() {
        return Commands.literal(COMMAND_GUARD)
                .executes(ShanhaiGuardCommand::status)
                .then(Commands.literal(ShanhaiGuardSwitches.Switch.AI.arg)
                        .executes(ctx -> status(ctx))
                        .then(Commands.literal("on").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.AI, true)))
                        .then(Commands.literal("off").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.AI, false))))
                .then(Commands.literal(ShanhaiGuardSwitches.Switch.ATTACK.arg)
                        .executes(ctx -> status(ctx))
                        .then(Commands.literal("on").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.ATTACK, true)))
                        .then(Commands.literal("off").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.ATTACK, false))))
                .then(Commands.literal(ShanhaiGuardSwitches.Switch.DEATH.arg)
                        .executes(ctx -> status(ctx))
                        .then(Commands.literal("on").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.DEATH, true)))
                        .then(Commands.literal("off").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.DEATH, false))))
                // 🆕 2026-10-17：反击退 / 反挤压（与上面三个【同一套写法】：同一个 status / 同一个 setSwitch）
                .then(Commands.literal(ShanhaiGuardSwitches.Switch.KNOCKBACK.arg)
                        .executes(ctx -> status(ctx))
                        .then(Commands.literal("on").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.KNOCKBACK, true)))
                        .then(Commands.literal("off").executes(ctx -> setSwitch(ctx, ShanhaiGuardSwitches.Switch.KNOCKBACK, false))));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // ⚠️ 与 ShanhaiRecipeStats / ShanhaiRecipeEditorCommand / ShanhaiCreativeItemsCommand 各 register 一次
        //    literal("shanhai")：Brigadier 会合并同名子节点（既有命令的 children 一个都不会丢）。
        event.getDispatcher().register(Commands.literal("shanhai").then(commandBranch()));
        final var shanhaiNode = event.getDispatcher().getRoot().getChild("shanhai");
        final StringBuilder kids = new StringBuilder();
        if (shanhaiNode != null) {
            for (var c : shanhaiNode.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} + /shanhai {} + /shanhai {} ; shanhai_children=[{}]",
                PREFIX, COMMAND_PURGE, COMMAND_GUARD, COMMAND_SUPPRESS, kids.toString().trim());
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
        ShanhaiMod.LOGGER.info("{} cmd_rejected reason=no_module player={}",
                PREFIX, player.getName().getString());
        return "§c这条命令要身上带着「创始现实修改模块」";
    }

    // ═══════════════════════════════ /shanhai purge ═══════════════════════════════

    private static int purge(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean dryRun) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        if (!ShanhaiGuardSwitches.attackSide()) {
            source.sendFailure(Component.literal(
                    "§c攻击端开关是关的（/shanhai guard attack on 打开后才能抹除）"));
            ShanhaiMod.LOGGER.info("{} purge_rejected reason=attack_side_off", PREFIX);
            return 0;
        }
        final MinecraftServer server = source.getServer();
        final ServerPlayer owner = source.getPlayer();
        ShanhaiPurge.noteOwner(owner);   // 左下角播报给执行者本人
        final ShanhaiPurge.Result r = ShanhaiPurge.purgeAll(server, owner, dryRun);
        final String line = (dryRun ? "§b[勘数] §7范围内 §f" : "§a[已执行] §7已抹除 §f")
                + (dryRun ? r.scanned : r.removed)
                + (dryRun ? " §7只（未抹）" : " §7只")
                + " §7／跳过玩家 §f" + r.skippedPlayers + " §7个"
                + (r.unresolved > 0 ? " §c／这一轮没解决 " + r.unresolved + " 只（已进重申表）" : "")
                + " §8／逐级解决 " + r.channels;
        source.sendSuccess(() -> Component.literal(line), false);
        return r.removed;
    }

    /** {@code /shanhai purge selfcheck} —— 当场跑一遍纯判据自检（控制台可跑）。 */
    private static int selfcheck(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final ShanhaiGuardCore.Decision ok = ShanhaiGuardRemoval.refuseRemoval(
                true, true, ShanhaiGuardRemoval.R_DISCARDED, false, false);
        final ShanhaiGuardCore.Decision allow = ShanhaiGuardRemoval.refuseRemoval(
                true, true, ShanhaiGuardRemoval.R_CHANGED_DIMENSION, false, false);
        final String line = "guard_selftest 拦(DISCARDED)=" + ok.yes + " 放行(CHANGED_DIMENSION)=" + (!allow.yes)
                + " 通道=" + String.join("/", ShanhaiGuardRemoval.purgeChannels())
                + " 隔离阈值=" + ShanhaiGuardRemoval.ISOLATE_AFTER_REASSERTS
                + " ／ " + ShanhaiGuardSuppression.statusLine()
                + " ／ " + ShanhaiGuardSwitches.statusLine()
                + " ／ " + ShanhaiAiLayer.statusLine()
                + " ／ " + ShanhaiDeathlessRestore.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + line), false);
        return ok.yes && !allow.yes ? 1 : 0;
    }

    // ═══════════════════════════════ 🆕 /shanhai suppress ═══════════════════════════════

    /** {@code /shanhai suppress [list]} —— 逐条列出现在还在压制窗口里的类型。 */
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

    /** {@code /shanhai suppress clear} —— 清空压制表（立刻停止周期抹除）。 */
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

    // ═══════════════════════════════ /shanhai guard ═══════════════════════════════

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        for (final String line : new String[] {
                ShanhaiGuardSwitches.statusLine(),
                ShanhaiAiLayer.statusLine(),
                ShanhaiDeathlessRestore.statusLine(),
                ShanhaiGuardKnockback.statusLine(),   // 🆕 反击退（窗口/阈值/豁免/累计计数）
                "开启/关闭：/shanhai guard ai|attack|death|knockback on|off（写回 config/shanhai-common.toml）" }) {
            source.sendSuccess(() -> Component.literal("§7" + line), false);
        }
        return 1;
    }

    private static int setSwitch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                 ShanhaiGuardSwitches.Switch which, boolean on) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        final boolean ok = ShanhaiGuardSwitches.set(which, on);
        final String reading = which.zh + " = " + which.now()
                + (ok ? "" : "（写入失败，看日志 guard_switch_write_failed）");
        source.sendSuccess(() -> Component.literal((ok ? "§a" : "§c") + reading), true);
        // 关掉 AI 层 ⇒ 立刻恢复"我们冻过谁"（与"开→关"那一拍同一段代码，只是这里不必等 tick）
        if (which == ShanhaiGuardSwitches.Switch.AI && !on) {
            final MinecraftServer server = source.getServer();
            final int restored = ShanhaiAiLayer.restoreAll(server, "命令把 AI 层关掉了");
            source.sendSuccess(() -> Component.literal("§7AI 层已关：恢复 noAi=false 的生物 " + restored
                    + " 只（只碰带 shanhai_frozen 标记的）"), false);
        }
        return ok ? 1 : 0;
    }
}
