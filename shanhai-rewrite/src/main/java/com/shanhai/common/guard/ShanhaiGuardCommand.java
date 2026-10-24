package com.shanhai.common.guard;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shanhai.ShanhaiMod;
import com.shanhai.api.PurgeRoute;
import com.shanhai.api.ShanhaiAdd;
import com.shanhai.api.ShanhaiAddHolder;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;

import java.util.function.IntSupplier;

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
 *   /shanhai purge                      🔴 主动触发：清除【全服所有实体】（跳过所有玩家）
 *                                       · 装了扩展且它肯接  ⇒ 扩展的逐级版（日志 purge_via=add）
 *                                       · 否则（没装/未实现/抛异常）⇒ 本体 kill @e（日志 purge_via=simple）
 *   /shanhai purge count                只数不抹（安全读数；用来先看"范围内 X 只"）
 *   /shanhai purge selfcheck            自检（本体这一半：拦/放行两条值 ＋ 通道 ＋ 隔离阈值 ＋ 四个开关）
 *   /shanhai guard                      看开关现在什么状态
 *   /shanhai guard ai on|off            AI 层（默认关）
 *   /shanhai guard attack on|off        攻击端（默认开）
 *   /shanhai guard death on|off         死而无损（默认开）
 *   /shanhai guard knockback on|off     🆕 反击退 / 反挤压（默认开；关掉后完全不动玩家）
 * </pre>
 *
 * <h2>🔴 本类只保留【本体那一半】（阶段 3b 拆命令）</h2>
 * <pre>
 *   留在这里：purge ／ purge count ／ purge selfcheck ／ guard ai|attack|death|knockback on|off
 *   搬去扩展：suppress list|clear（压制表整块搬走了）＋ 逐级版的 /shanhai_add purge
 * </pre>
 * 🔴 本体<b>不许</b>注册 {@code /shanhai_add} 的任何东西；扩展<b>不许</b>往 {@code /shanhai} 上挂任何东西
 * —— 否则"有没有装扩展"在命令树上分不出来。
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

    private ShanhaiGuardCommand() {}

    /** 命令分支（{@code /shanhai} 下面挂的几棵子树）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_PURGE)
                .executes(ctx -> purge(ctx, false))
                .then(Commands.literal("count").executes(ctx -> purge(ctx, true)))
                .then(Commands.literal("selfcheck").executes(ShanhaiGuardCommand::selfcheck))
                .then(guardBranch());
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
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} + /shanhai {} ; shanhai_children=[{}]",
                PREFIX, COMMAND_PURGE, COMMAND_GUARD, kids.toString().trim());
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

    /**
     * {@code /shanhai purge} 与 {@code /shanhai purge count}（<b>两条路，一个入口</b>）。
     *
     * <h2>手段选择（设计 §3.4）</h2>
     * <pre>
     *   装了扩展 ＆ 它返回 &gt;= 0   ⇒ 用扩展的逐级版（日志 purge_via=add，回显里 channels={add=N}）
     *   没装 ／ 未实现(-1) ／ 抛异常 ⇒ 本体 ShanhaiPurgeSimple：逐维度 kill @e（日志 purge_via=simple）
     *   只数不抹(count)            ⇒ 一律本体只读遍历（扩展接口这一版没有 dryRun 参数 ⇒ 不调它，免得真删）
     * </pre>
     * 🔴 命令名、门槛（模块 ／ 攻击端开关）、回显格式<b>一个字都不许变</b>：只换数量来源。
     * 判定本身在 {@link PurgeRoute#ask}（纯 JDK，可离线值表验证），这里只负责取值与执行。
     */
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
        // ① 先问扩展（唯一的跨 mod 调用点；阶段 2 它返回 -1 = 未实现）
        //    取值、吞异常、四条分支的判定全在 PurgeRoute（纯 JDK，可离线值表验证）
        final ShanhaiAdd bridge = ShanhaiAddHolder.optional();
        final IntSupplier callAdd = bridge == null ? null : () -> bridge.purgeAll(owner);
        final PurgeRoute.Decision route = PurgeRoute.ask(bridge != null, dryRun, callAdd);
        if (route.error != null) {
            // 扩展坏了不许把本体这条命令弄失败（"装了扩展反而更弱"是最不该出现的行为）
            ShanhaiMod.LOGGER.warn(PREFIX + "purge_via=add status=threw ⇒ 回退 kill@e（扩展异常，命令照常成功）："
                    + route.error);
        }
        // ② 按判定执行（两条路给出的读数形状完全一样 ⇒ 回显那段一个字都不用改）
        final ShanhaiPurgeSimple.Result r = route.usesAdd()
                ? ShanhaiPurgeSimple.fromBridge(server, route.removed)
                : ShanhaiPurgeSimple.killAll(server, owner, dryRun);
        ShanhaiMod.LOGGER.info("{} {} bridge_version={} levels={} removed={} skipped_players={} dry_run={}",
                PREFIX, route.logLine(), bridgeVersion(bridge),
                r.levels, r.removed, r.skippedPlayers, dryRun);
        // ③ 回显（格式与拆分前逐字一致：只换了数字的来源）
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
     * 扩展的版本号（只进日志）。
     *
     * <p>拿不到（没装 ／ 扩展实现里抛了）就 {@code "-"} / {@code "?"} —— <b>取不到版本号不许影响命令</b>。
     */
    private static String bridgeVersion(ShanhaiAdd bridge) {
        if (bridge == null) {
            return "-";
        }
        try {
            final String v = bridge.modVersion();
            return v == null ? "?" : v;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * {@code /shanhai purge selfcheck} —— 当场跑一遍纯判据自检（控制台可跑）。
     *
     * <p>🔴 只跑<b>本体这一半</b>：判据（拦 ／ 放行两条值）、通道表、隔离阈值、四个开关、AI 层、死而无损。
     * 压制表那一行（{@code suppress …}）与逐级阶梯的读数已经搬进扩展，走扩展自己的自检命令
     * —— 本体这里读不到它们（本体不许引用扩展的任何类）。
     */
    private static int selfcheck(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final ShanhaiGuardCore.Decision ok = ShanhaiGuardRemoval.refuseRemoval(
                true, true, ShanhaiGuardRemoval.R_DISCARDED, false, false);
        final ShanhaiGuardCore.Decision allow = ShanhaiGuardRemoval.refuseRemoval(
                true, true, ShanhaiGuardRemoval.R_CHANGED_DIMENSION, false, false);
        final String line = "guard_selftest 拦(DISCARDED)=" + ok.yes + " 放行(CHANGED_DIMENSION)=" + (!allow.yes)
                + " 通道=" + String.join("/", ShanhaiGuardRemoval.purgeChannels())
                + " 隔离阈值=" + ShanhaiGuardRemoval.ISOLATE_AFTER_REASSERTS
                + " ／ " + ShanhaiGuardSwitches.statusLine()
                + " ／ " + ShanhaiAiLayer.statusLine()
                + " ／ " + ShanhaiDeathlessRestore.statusLine();
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + line), false);
        return ok.yes && !allow.yes ? 1 : 0;
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
