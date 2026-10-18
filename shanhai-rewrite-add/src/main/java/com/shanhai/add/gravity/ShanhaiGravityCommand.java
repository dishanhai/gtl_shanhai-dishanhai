package com.shanhai.add.gravity;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.config.ShanhaiAddConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai_add gravity …} —— 全量调制重力的入口（<b>扩展自己的命令根</b>）。
 *
 * <p>🔴 2026-10-18 拆分：它原来挂在 {@code /shanhai} 这个根上（与 {@code guard|items|statistics|edit}
 * 同根，靠 Brigadier 合并子节点，既有命令一个字节都不动）；时钟与重力整块搬进扩展后，
 * 本子树改挂<b>独立根 {@code /shanhai_add}</b>（设计 §3.2），本体那棵树一个字节都没动。
 * 两个<b>不同</b>的根不会冲突 —— 同名根才会合并。
 *
 * <h2>命令树</h2>
 * <pre>
 *   /shanhai_add gravity                 打印当前倍率（含"这条值得从哪来"）
 *   /shanhai_add gravity &lt;倍率&gt;          设倍率：0=关重力、1=原版、负数=反重力、小数=半重力（范围 -100 ~ 100）
 *   /shanhai_add gravity reset           回到 1（原版手感）
 *   /shanhai_add gravity selfcheck       当场跑一遍纯算术自检（含负对照，控制台可跑）
 * </pre>
 *
 * <h2>🔴 写入链路（服务端权威）</h2>
 * <pre>
 *   指令 → ShanhaiGravity.set(G)「进程内权威值」
 *        → ShanhaiAddConfig.COMMON.gravityMultiplier.set(G) + COMMON_SPEC.save()「落盘，重启还在」
 *        → ShanhaiGravityNetwork.syncToAll(server)「通知所有客户端，避免橡皮筋」
 *        → 两行日志：set_mult=… / gravity_state …
 * </pre>
 *
 * <h2>门槛</h2>
 * <b>不加</b>「创始现实修改模块」那道门（那是 {@code /shanhai guard|items} 的路数）：重力是世界级物理，
 * 用户要的是"一句指令就能试"。若以后要收，只需在 {@link #apply} 开头加一句
 * {@code if (player != null && !ShanhaiHoloMenuNetwork.moduleInInventory(player)) return 0;}。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiGravityCommand {

    public static final String COMMAND_ARG = "gravity";

    private ShanhaiGravityCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                // 只读（打印当前倍率）对所有人放行；改世界参数一律要 OP 等级 2（与 /shanhai item … 一致）
                .executes(ShanhaiGravityCommand::print)
                .then(Commands.literal("reset").requires(ShanhaiGravityCommand::canEdit)
                        .executes(ShanhaiGravityCommand::reset))
                .then(Commands.literal("selfcheck").requires(ShanhaiGravityCommand::canEdit)
                        .executes(ShanhaiGravityCommand::selfcheck))
                .then(Commands.argument("mult",
                                DoubleArgumentType.doubleArg(ShanhaiGravity.MIN_MULT, ShanhaiGravity.MAX_MULT))
                        .requires(ShanhaiGravityCommand::canEdit)
                        .executes(ShanhaiGravityCommand::set));
    }

    /** 改倍率的权限门禁：OP 等级 2。 */
    private static boolean canEdit(CommandSourceStack src) {
        return src.hasPermission(2);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai_add").then(commandBranch()));
        final var shanhaiNode = event.getDispatcher().getRoot().getChild("shanhai_add");
        final StringBuilder kids = new StringBuilder();
        if (shanhaiNode != null) {
            for (var c : shanhaiNode.getChildren()) {
                kids.append(c.getName()).append(' ');
            }
        }
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai_add {} ; shanhai_children=[{}]",
                ShanhaiGravity.PREFIX, COMMAND_ARG, kids.toString().trim());
    }

    // ------------------------------------------------------------------ 子命令

    private static int print(CommandContext<CommandSourceStack> ctx) {
        final String line = "§b重力倍率 = §f" + ShanhaiGravity.fmt(ShanhaiGravity.get())
                + " §8(" + describe(ShanhaiGravity.get()) + ")"
                + " §8· 作用范围 = 全部实体（生物 + 非生物，17 个施加点）"
                + " §8· 改法 §f/shanhai_add gravity <倍率>§8 ／ §f/shanhai_add gravity reset";
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiGravity.PREFIX,
                ShanhaiGravity.stateLine(ShanhaiGravity.SITE_BASES.length));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        return apply(ctx, ShanhaiGravity.VANILLA, "reset");
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        return apply(ctx, DoubleArgumentType.getDouble(ctx, "mult"), "cmd");
    }

    private static int selfcheck(CommandContext<CommandSourceStack> ctx) {
        final int failPositive = ShanhaiGravity.runSelfCheck(ShanhaiGravity::scaleFor, false);
        final int failMutant = ShanhaiGravity.runSelfCheck(ShanhaiGravity::mutantNoScale, false);
        final boolean ok = failPositive == 0 && failMutant > 0;
        final String line = "SELFCHECK positive=" + (failPositive == 0) + " negativeCaught=" + (failMutant > 0)
                + " positiveFailures=" + failPositive + " mutantFailures=" + failMutant + " PASS=" + ok;
        ctx.getSource().sendSuccess(() -> Component.literal((ok ? "§a" : "§c") + line), false);
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiGravity.PREFIX, line);
        return ok ? 1 : 0;
    }

    /** 真正的写入 + 落盘 + 广播 + 日志（reset 与 set 共用）。 */
    private static int apply(CommandContext<CommandSourceStack> ctx, double want, String how) {
        final CommandSourceStack source = ctx.getSource();
        final String by = source.getTextName();
        final double got = ShanhaiGravity.set(want);

        // ① 落盘（COMMON 配置：单机两侧读同一份；专用服务端读服务端那份）
        boolean saved = false;
        if (ShanhaiAddConfig.isLoaded()) {
            try {
                ShanhaiAddConfig.COMMON.gravityMultiplier.set(got);
                ShanhaiAddConfig.COMMON_SPEC.save();
                saved = true;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} config_write_failed mult={} err={}",
                        ShanhaiGravity.PREFIX, ShanhaiGravity.fmt(got), t.toString());
            }
        } else {
            ShanhaiMod.LOGGER.warn("{} config_not_loaded（本次倍率只在内存里生效，重启会丢）",
                    ShanhaiGravity.PREFIX);
        }

        // ② 两端同步（服务端权威 → 所有客户端；不广播的话本地玩家预测会分叉 = 橡皮筋）
        final MinecraftServer server = source.getServer();
        ShanhaiGravityNetwork.syncToAll(server);

        // ③ 日志（主代理按这两串判）
        ShanhaiMod.LOGGER.info("{} set mult={} by={} asked={} how={} saved={}",
                ShanhaiGravity.PREFIX, ShanhaiGravity.fmt(got), by,
                ShanhaiGravity.fmt(want), how, saved);
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiGravity.PREFIX,
                ShanhaiGravity.stateLine(ShanhaiGravity.SITE_BASES.length));

        // ④ 回执（含"这条倍率意味着什么"，免得用户对着数字猜）
        final String msg = "§a重力倍率 = §f" + ShanhaiGravity.fmt(got)
                + " §8(" + describe(got) + ")"
                + (saved ? " §8· 已写进 config/shanhai-common.toml" : " §c· 配置未加载，只在内存里");
        source.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    /** 把数字翻译成人话（回显用）。 */
    private static String describe(double g) {
        if (g == 1.0D) {
            return "原版";
        }
        if (g == 0.0D) {
            return "关掉重力";
        }
        if (g < 0.0D) {
            return "反重力";
        }
        if (g < 1.0D) {
            return "半重力";
        }
        return "加重";
    }
}
