package com.shanhai.add.guard.state;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.guard.stack.ShanhaiViolationProbe;
import com.shanhai.add.guard.stack.ShanhaiViolationStack;
import com.shanhai.add.hotfix.HotfixTriageTap;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai_add momentum …} —— <b>「玩家状态独占权威」的开关入口</b>。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai_add momentum                 打印读数（三个开关 ＋ 计数 ＋ 跟踪人数）
 *   /shanhai_add momentum on|off          动量那一项（默认开）
 *   /shanhai_add momentum pos on|off      位置那一项（默认开；只在窗口内生效）
 *   /shanhai_add momentum inv on|off      物品栏那一项（默认开；只认"一次少掉一大片"）
 *   /shanhai_add momentum reset           清空跟踪状态（换世界/重来用；不动配置）
 * </pre>
 *
 * <h2>为什么命令根又叫一次 {@code /shanhai_add}</h2>
 * 本模块里 {@code ShanhaiAddGuardCommand}（purge/suppress）、{@code ShanhaiGravityCommand}（gravity）
 * 已经各自 {@code register} 过一次同名根 —— Brigadier 的 {@code CommandNode#addChild} 对<b>同名节点会合并</b>
 * （{@code javap -c com.mojang.brigadier.tree.CommandNode} 已证：同名的走
 * "把新节点的 children 递归 addChild 进旧节点"，命令执行器只在<b>非空时覆盖</b>）。
 * ⇒ 所以本类<b>绝不给 {@code /shanhai_add} 这个根挂 executor</b>（挂了就会把别人的覆盖掉）。
 *
 * <h2>门槛</h2>
 * 与同模块 purge/suppress 一模一样：玩家身上要带「创始现实修改模块」，控制台/命令方块放行。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiStateCommand {

    /** 与守卫同一套前缀。 */
    public static final String PREFIX = ShanhaiStateLog.PREFIX;

    /** 命令根（与本模块其它命令同一个根）。 */
    public static final String ROOT = "shanhai_add";

    /** 本命令那一段（用户点名要的 `momentum on|off`）。 */
    public static final String COMMAND_ARG = "momentum";

    private ShanhaiStateCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .executes(ShanhaiStateCommand::status)                       // 只读：所有人可看
                .then(Commands.literal("on").executes(ctx -> set(ctx, ShanhaiStateSwitches.Switch.MOMENTUM, true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx, ShanhaiStateSwitches.Switch.MOMENTUM, false)))
                .then(fieldBranch(ShanhaiStateSwitches.Switch.POS))
                .then(fieldBranch(ShanhaiStateSwitches.Switch.INV))
                .then(Commands.literal("reset").executes(ShanhaiStateCommand::reset));
    }

    /** {@code pos on|off} / {@code inv on|off} 这两棵小子树（写法与上面那一对逐字同款）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> fieldBranch(ShanhaiStateSwitches.Switch which) {
        return Commands.literal(which.arg)
                .executes(ShanhaiStateCommand::status)
                .then(Commands.literal("on").executes(ctx -> set(ctx, which, true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx, which, false)));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 日志出口只在这里接一次（进世界才有玩家，才可能有"状态被改写"）
        ShanhaiStateLog.bind(text -> {
            try {
                ShanhaiMod.LOGGER.info(text);
            } catch (Throwable ignored) {
                // 日志出口坏了不许影响任何判定
            }
        });
        // 🆕 2026-11-08：把"违规现场抓到的帧"接到热改的 strike 通道（HotfixTriageTap）。
        //   为什么在这里接：① 这是本模块唯一的装配点（配置/命令都在这条路上）；
        //   ② tap 自己可能还没装上（默认 auto 关）—— submitViolation 会先记住，
        //      等 tap 装上一个 solved=false 就能配上，不需要第二条通道。
        try {
            ShanhaiViolationStack.bind(hit -> HotfixTriageTap.submitViolation(
                    hit.where, hit.target, hit.frames, hit.atMillis));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} violation_strike_channel=false err={}", PREFIX, t.toString());
        }
        event.getDispatcher().register(Commands.literal(ROOT).then(commandBranch()));

        // 🔴 回读命令树：只报【真的挂上了没有】，不报"我打算注册什么"
        final var node = event.getDispatcher().getRoot().getChild(ROOT);
        final var mine = node == null ? null : node.getChild(COMMAND_ARG);
        final boolean present = mine != null;
        final boolean onPresent = present && mine.getChild("on") != null;
        final boolean offPresent = present && mine.getChild("off") != null;
        final boolean posPresent = present && mine.getChild("pos") != null;
        final boolean invPresent = present && mine.getChild("inv") != null;
        final StringBuilder kids = new StringBuilder();
        if (node != null) {
            for (var c : node.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} add_command_registered /{}/{} present={} on={} off={} pos={} inv={}"
                        + " ; children_at_register=[{}]",
                PREFIX, ROOT, COMMAND_ARG, present, onPresent, offPresent, posPresent, invPresent,
                kids.toString().trim());
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiStateAuthority.armedLine());
    }

    // ═══════════════════════════════ 子命令 ═══════════════════════════════

    private static int status(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§7" + ShanhaiStateAuthority.statusLine()), false);
        source.sendSuccess(() -> Component.literal("§7" + ShanhaiViolationProbe.statusLine()), false);
        source.sendSuccess(() -> Component.literal(
                "§8开启/关闭：§f/shanhai_add momentum on|off§8 ／ §f… momentum pos on|off§8 ／ "
                        + "§f… momentum inv on|off§8（写回 config/shanhai_add-common.toml）"), false);
        source.sendSuccess(() -> Component.literal(
                "§8判据：动量=归属（是不是玩家自己的操作，见下）／位置=归属+锚点预算／物品栏=一次少掉 ≥30%"
                        + " §8· 拦截日志 §f[SHANHAI-GUARD] 拦截 状态被改写§8 · 抓栈 §fviolation_stack"), false);
        source.sendSuccess(() -> Component.literal(
                "§8归属标记：§fself_input§8（玩家自己的移动包 handleMovePlayer）／"
                        + "§fself_guard§8（我们自己的复活·复位·清击退）⇒ 这两类一律采纳，不再按 tick 猜"), false);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiStateAuthority.statusLine());
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, ShanhaiStateSwitches.Switch which, boolean on) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        final boolean ok = ShanhaiStateSwitches.set(which, on);
        final String reading = which.zh + " = " + which.now()
                + (ok ? "" : "（写入失败，看日志 state_switch_write_failed）");
        source.sendSuccess(() -> Component.literal((ok ? "§a" : "§c") + reading), true);
        return ok ? 1 : 0;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final String denied = gate(source);
        if (denied != null) {
            source.sendFailure(Component.literal(denied));
            return 0;
        }
        ShanhaiStateAuthority.reset("command");
        source.sendSuccess(() -> Component.literal("§a已清空跟踪状态（配置与开关一个都没动）"), true);
        return 1;
    }

    /** 门槛：玩家必须身上带模块；控制台／命令方块放行（与同模块 purge/suppress 同一条）。 */
    private static String gate(CommandSourceStack source) {
        final ServerPlayer player = source.getPlayer();
        if (player == null) {
            return null;
        }
        if (ShanhaiHoloMenuNetwork.moduleInInventory(player)) {
            return null;
        }
        ShanhaiMod.LOGGER.info("{} state_cmd_rejected reason=no_module player={}",
                PREFIX, player.getName().getString());
        return "§c这条命令要身上带着「创始现实修改模块」";
    }
}
