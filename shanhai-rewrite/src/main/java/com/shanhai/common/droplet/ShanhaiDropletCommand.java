package com.shanhai.common.droplet;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shanhai.ShanhaiMod;
import com.shanhai.item.ShanhaiItems;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /shanhai droplet ...} —— 水滴的三条可点击指令的<b>落点</b>（规格 1 / 10 / 11）。
 *
 * <h2>一、不新建命令根</h2>
 * 与 {@code ShanhaiSaveEditCommand} / {@code ShanhaiGuardCommand} / {@code ShanhaiWhyCommand} 同一种写法：
 * 另注册一次 {@code Commands.literal("shanhai").then(commandBranch())}，
 * Brigadier 会把同名子节点合并（既有命令的 children 一个都不丢）⇒ 本类只往 {@code /shanhai} 下面挂
 * {@code droplet} 这一个子节点。<b>本类不注册任何新的根命令。</b>
 *
 * <h2>二、命令树</h2>
 * <pre>
 *   /shanhai droplet mode              切换攻击模式 1 → 2 → 3 → 1（并把三条提示重发一遍）
 *   /shanhai droplet explode           切换爆炸地形破坏（开 / 关）
 *   /shanhai droplet recall            收回本人名下所有水滴，按数量归还物品（背包满则掉脚下）
 *   /shanhai droplet status            只读：打印当前模式 / 爆炸开关 / 在场水滴数 / 第一个水滴的目标
 *   /shanhai droplet distance [1~10]   跟随距离（格）：不带参 = 打印当前值；带参 = 写入（默认 2.5）
 *   /shanhai droplet speed [10~200]    最大飞行速度（格/秒）：不带参 = 打印当前值；带参 = 写入（默认 40）
 * </pre>
 *
 * <h2>三、为什么要求"必须是玩家"</h2>
 * 规格 11：模式与爆炸开关存在<b>拥有者玩家</b>的 {@code getPersistentData()} 上。
 * 控制台没有 persistentData ⇒ 这些子命令对控制台没有语义，{@code requires} 直接挡掉，
 * 并在被挡掉时回一条明确的 {@code droplet.shanhai.cmd.player_only}（不静默失败）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletCommand {

    /** 子命令名（{@code /shanhai droplet}）。 */
    public static final String COMMAND_ARG = "droplet";

    /** 日志前缀（与水滴实体/命中同一串）。 */
    public static final String PREFIX = ShanhaiDropletCombat.PREFIX;

    // ------------------------------------------------------------------ 命令反馈的语言键

    public static final String KEY_CMD_PLAYER_ONLY = "droplet.shanhai.cmd.player_only";
    public static final String KEY_CMD_MODE = "droplet.shanhai.cmd.mode";
    public static final String KEY_CMD_EXPLODE = "droplet.shanhai.cmd.explode";
    public static final String KEY_CMD_RECALL_NONE = "droplet.shanhai.cmd.recall_none";
    public static final String KEY_CMD_RECALL_DONE = "droplet.shanhai.cmd.recall_done";
    public static final String KEY_CMD_RECALL_DROPPED = "droplet.shanhai.cmd.recall_dropped";
    public static final String KEY_CMD_STATUS = "droplet.shanhai.cmd.status";
    public static final String KEY_CMD_STATUS_NO_TARGET = "droplet.shanhai.cmd.status.no_target";

    private ShanhaiDropletCommand() {}

    /** 子命令树（挂到既有的 {@code /shanhai} 下）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal(COMMAND_ARG)
                .requires(source -> source.getPlayer() != null)
                .then(Commands.literal("mode").executes(ShanhaiDropletCommand::cycleMode))
                .then(Commands.literal("explode").executes(ShanhaiDropletCommand::toggleExplosion))
                .then(Commands.literal("recall").executes(ShanhaiDropletCommand::recall))
                .then(Commands.literal("status").executes(ShanhaiDropletCommand::status))
                // 跟随距离：不带参 = 打印当前值；带参 = 写入（1.00 ~ 10.00，留 2 位小数）
                .then(Commands.literal("distance")
                        .executes(ShanhaiDropletCommand::queryDistance)
                        .then(Commands.argument("value",
                                        DoubleArgumentType.doubleArg(
                                                ShanhaiDropletSettings.FOLLOW_DISTANCE_MIN,
                                                ShanhaiDropletSettings.FOLLOW_DISTANCE_MAX))
                                .executes(ShanhaiDropletCommand::setDistance)))
                // 最大飞行速度：不带参 = 打印当前值；带参 = 写入（10 ~ 200 格/秒）
                .then(Commands.literal("speed")
                        .executes(ShanhaiDropletCommand::querySpeed)
                        .then(Commands.argument("value",
                                        IntegerArgumentType.integer(
                                                (int) ShanhaiDropletSettings.MAX_SPEED_MIN,
                                                (int) ShanhaiDropletSettings.MAX_SPEED_MAX))
                                .executes(ShanhaiDropletCommand::setSpeed)))
                // 驾驶：自动驾驶开关（开启后忽略 W/S，Shift 仍可下车；无目标原地静止）
                .then(Commands.literal("autopilot")
                        .executes(ShanhaiDropletCommand::queryAutopilot)
                        .then(Commands.literal("on").executes(context -> setAutopilot(context, true)))
                        .then(Commands.literal("off").executes(context -> setAutopilot(context, false))))
                // 驾驶：瞄准辅助开关 / 判定角度（1~45 度，默认 5）
                .then(Commands.literal("assist")
                        .executes(ShanhaiDropletCommand::queryAssist)
                        .then(Commands.literal("on").executes(context -> setAssist(context, true)))
                        .then(Commands.literal("off").executes(context -> setAssist(context, false)))
                        .then(Commands.literal("angle")
                                .executes(ShanhaiDropletCommand::queryAssistAngle)
                                .then(Commands.argument("degrees",
                                                DoubleArgumentType.doubleArg(
                                                        ShanhaiDropletSettings.ASSIST_ANGLE_MIN,
                                                        ShanhaiDropletSettings.ASSIST_ANGLE_MAX))
                                        .executes(ShanhaiDropletCommand::setAssistAngle))));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("shanhai").then(commandBranch()));
        // 机器可判：回读命令树，没挂上就是 false（照 ShanhaiSaveEditCommand 的做法）
        final var shanhaiNode = event.getDispatcher().getRoot().getChild("shanhai");
        final boolean present = shanhaiNode != null && shanhaiNode.getChild(COMMAND_ARG) != null;
        ShanhaiMod.LOGGER.info("{} command_registered /shanhai {} present={}", PREFIX, COMMAND_ARG, present);
    }

    // ------------------------------------------------------------------ 子命令

    /** 第 1 条：切换攻击模式（1 → 2 → 3 → 1），并重发三条提示（"当前：…"要跟着变）。 */
    private static int cycleMode(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final int next = ShanhaiDropletSettings.nextMode(ShanhaiDropletSettings.mode(player));
        ShanhaiDropletSettings.setMode(player, next);
        context.getSource().sendSuccess(
                () -> Component.translatable(KEY_CMD_MODE, ShanhaiDropletSettings.modeName(next)), false);
        ShanhaiDropletSettings.sendControlPanel(player);
        ShanhaiMod.LOGGER.info("{} cmd mode owner={} -> {}", PREFIX, player.getUUID(), next);
        return 1;
    }

    /** 第 2 条：切换爆炸地形破坏。 */
    private static int toggleExplosion(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final boolean enabled = ShanhaiDropletSettings.toggleExplosion(player);
        context.getSource().sendSuccess(
                () -> Component.translatable(KEY_CMD_EXPLODE, ShanhaiDropletSettings.explosionName(enabled)), false);
        ShanhaiDropletSettings.sendControlPanel(player);
        ShanhaiMod.LOGGER.info("{} cmd explosion owner={} -> {}", PREFIX, player.getUUID(), enabled);
        return 1;
    }

    /**
     * 第 3 条：收回水滴（规格 10）。
     *
     * <p>清掉该玩家名下<b>所有</b>水滴（含跨维度），然后<b>按数量</b>还物品：先塞背包
     * （{@code Inventory#add}），塞不进去的直接 {@code drop} 在脚下。
     */
    private static int recall(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final int removed = ShanhaiDropletEntity.discardOwnedBy(player);
        if (removed == 0) {
            context.getSource().sendSuccess(() -> Component.translatable(KEY_CMD_RECALL_NONE), false);
            ShanhaiMod.LOGGER.info("{} cmd recall owner={} removed=0", PREFIX, player.getUUID());
            return 0;
        }
        int dropped = 0;
        for (int i = 0; i < removed; i++) {
            final ItemStack stack = new ItemStack(ShanhaiItems.STRONG_INTERACTION_DROPLET.get());
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
                dropped++;
            }
        }
        final int given = removed - dropped;
        final int droppedCount = dropped;
        context.getSource().sendSuccess(
                () -> Component.translatable(KEY_CMD_RECALL_DONE, Integer.valueOf(removed), Integer.valueOf(given)),
                false);
        if (droppedCount > 0) {
            context.getSource().sendSuccess(
                    () -> Component.translatable(KEY_CMD_RECALL_DROPPED, Integer.valueOf(droppedCount)), false);
        }
        ShanhaiMod.LOGGER.info("{} cmd recall owner={} removed={} given={} dropped={}",
                PREFIX, player.getUUID(), removed, given, droppedCount);
        return removed;
    }

    /** 只读状态：模式 / 爆炸开关 / 在场水滴数 / 第一个水滴的目标。 */
    private static int status(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final int mode = ShanhaiDropletSettings.mode(player);
        final boolean explosion = ShanhaiDropletSettings.explosion(player);
        final List<ShanhaiDropletEntity> droplets = ShanhaiDropletEntity.collectOwnedBy(player);
        final String target = droplets.isEmpty() || !droplets.get(0).hasTarget()
                ? Component.translatable(KEY_CMD_STATUS_NO_TARGET).getString()
                : droplets.get(0).targetName();
        context.getSource().sendSuccess(() -> Component.translatable(KEY_CMD_STATUS,
                ShanhaiDropletSettings.modeName(mode),
                ShanhaiDropletSettings.explosionName(explosion),
                Integer.valueOf(droplets.size()),
                target), false);
        ShanhaiMod.LOGGER.info("{} cmd status owner={} mode={} explosion={} droplets={} target={} health_field={}",
                PREFIX, player.getUUID(), mode, explosion, droplets.size(), target,
                ShanhaiDropletCombat.healthAccessorVia());
        // 驾驶态单独一行（不改既有 lang 键的参数个数，避免与另一条线的文案撞）
        if (!droplets.isEmpty()) {
            final ShanhaiDropletEntity first = droplets.get(0);
            context.getSource().sendSuccess(
                    () -> Component.literal("§7驾驶：§f" + first.pilotStatsLine()
                            + "§7 · 限流丢弃：§f" + ShanhaiDropletPilotNetwork.IntentStore.droppedCount()
                            + "§7 · 免伤：§f" + ShanhaiDropletPilotEvents.statsLine()), false);
        }
        return droplets.size();
    }

    /** 控制台/命令方块走到这里 ⇒ 明确拒绝（这些设置只存在玩家身上）。 */
    private static int playerOnly(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.translatable(KEY_CMD_PLAYER_ONLY));
        return 0;
    }

    // ------------------------------------------------------------------ 跟随距离 / 最大速度

    /** {@code /shanhai droplet distance} —— 不带参：打印当前值。 */
    private static int queryDistance(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final double distance = ShanhaiDropletSettings.followDistance(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.distanceFeedback(distance, false), false);
        ShanhaiMod.LOGGER.info("{} cmd distance owner={} query={}", PREFIX, player.getUUID(),
                Double.valueOf(distance));
        return 1;
    }

    /** {@code /shanhai droplet distance <1~10>} —— 写入跟随距离（夹到 1~10、保留 2 位小数）。 */
    private static int setDistance(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final double requested = DoubleArgumentType.getDouble(context, "value");
        ShanhaiDropletSettings.setFollowDistance(player, requested);
        final double applied = ShanhaiDropletSettings.followDistance(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.distanceFeedback(applied, true), false);
        ShanhaiMod.LOGGER.info("{} cmd distance owner={} requested={} applied={}",
                PREFIX, player.getUUID(), Double.valueOf(requested), Double.valueOf(applied));
        return 1;
    }

    /** {@code /shanhai droplet speed} —— 不带参：打印当前值。 */
    private static int querySpeed(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final double speed = ShanhaiDropletSettings.maxSpeedPerSec(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.speedFeedback(speed, false), false);
        ShanhaiMod.LOGGER.info("{} cmd speed owner={} query={}per_sec per_tick={}", PREFIX,
                player.getUUID(), Double.valueOf(speed), Double.valueOf(speed / 20.0D));
        return 1;
    }

    /** {@code /shanhai droplet speed <10~200>} —— 写入最大飞行速度（格/秒）。 */
    private static int setSpeed(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final int requested = IntegerArgumentType.getInteger(context, "value");
        ShanhaiDropletSettings.setMaxSpeedPerSec(player, requested);
        final double applied = ShanhaiDropletSettings.maxSpeedPerSec(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.speedFeedback(applied, true), false);
        ShanhaiMod.LOGGER.info("{} cmd speed owner={} requested={}per_sec applied={}per_sec per_tick={}",
                PREFIX, player.getUUID(), Integer.valueOf(requested), Double.valueOf(applied),
                Double.valueOf(applied / 20.0D));
        return 1;
    }

    // ------------------------------------------------------------------ 驾驶：自动驾驶 / 瞄准辅助

    /** {@code /shanhai droplet autopilot} —— 不带参：打印当前值。 */
    private static int queryAutopilot(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final boolean enabled = ShanhaiDropletSettings.autopilot(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.autopilotFeedback(enabled, false), false);
        ShanhaiMod.LOGGER.info("{} cmd autopilot owner={} query={}", PREFIX, player.getUUID(),
                Boolean.valueOf(enabled));
        return 1;
    }

    /** {@code /shanhai droplet autopilot on|off} —— 写入（随拥有者存档走，立刻对所有在场水滴生效）。 */
    private static int setAutopilot(CommandContext<CommandSourceStack> context, boolean enabled) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        ShanhaiDropletSettings.setAutopilot(player, enabled);
        final boolean applied = ShanhaiDropletSettings.autopilot(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.autopilotFeedback(applied, true), false);
        // 与第 1/2 条同款行为：切换后把整段提示重发一遍（"当前：…"要跟着变）
        ShanhaiDropletSettings.sendControlPanel(player);
        ShanhaiMod.LOGGER.info("{} cmd autopilot owner={} requested={} applied={}", PREFIX, player.getUUID(),
                Boolean.valueOf(enabled), Boolean.valueOf(applied));
        return 1;
    }

    /** {@code /shanhai droplet assist} —— 不带参：打印当前开关与角度。 */
    private static int queryAssist(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final boolean enabled = ShanhaiDropletSettings.assist(player);
        final double angle = ShanhaiDropletSettings.assistAngle(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.assistFeedback(enabled, angle, false), false);
        ShanhaiMod.LOGGER.info("{} cmd assist owner={} query={} angle={}", PREFIX, player.getUUID(),
                Boolean.valueOf(enabled), Double.valueOf(angle));
        return 1;
    }

    /** {@code /shanhai droplet assist on|off} —— 写入瞄准辅助开关。 */
    private static int setAssist(CommandContext<CommandSourceStack> context, boolean enabled) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        ShanhaiDropletSettings.setAssist(player, enabled);
        final boolean applied = ShanhaiDropletSettings.assist(player);
        final double angle = ShanhaiDropletSettings.assistAngle(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.assistFeedback(applied, angle, true), false);
        // 切换后重发五行提示（第 5 条的"当前：开/关，x.x 度"要跟着变）
        ShanhaiDropletSettings.sendControlPanel(player);
        ShanhaiMod.LOGGER.info("{} cmd assist owner={} requested={} applied={} angle={}", PREFIX,
                player.getUUID(), Boolean.valueOf(enabled), Boolean.valueOf(applied), Double.valueOf(angle));
        return 1;
    }

    /** {@code /shanhai droplet assist angle} —— 不带参：打印当前角度。 */
    private static int queryAssistAngle(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final double angle = ShanhaiDropletSettings.assistAngle(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.assistAngleFeedback(angle), false);
        ShanhaiMod.LOGGER.info("{} cmd assist_angle owner={} query={}", PREFIX, player.getUUID(),
                Double.valueOf(angle));
        return 1;
    }

    /** {@code /shanhai droplet assist angle <1~45>} —— 写入判定角度（夹到 1~45，保留 1 位小数）。 */
    private static int setAssistAngle(CommandContext<CommandSourceStack> context) {
        final ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return playerOnly(context);
        }
        final double requested = DoubleArgumentType.getDouble(context, "degrees");
        ShanhaiDropletSettings.setAssistAngle(player, requested);
        final double applied = ShanhaiDropletSettings.assistAngle(player);
        context.getSource().sendSuccess(
                () -> ShanhaiDropletSettings.assistAngleFeedback(applied), false);
        // 角度变了 ⇒ 第 5 条里那个"x.x 度"必须跟着变
        ShanhaiDropletSettings.sendControlPanel(player);
        ShanhaiMod.LOGGER.info("{} cmd assist_angle owner={} requested={} applied={}", PREFIX, player.getUUID(),
                Double.valueOf(requested), Double.valueOf(applied));
        return 1;
    }
}
