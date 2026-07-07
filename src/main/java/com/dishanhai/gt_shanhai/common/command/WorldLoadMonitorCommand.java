package com.dishanhai.gt_shanhai.common.command;

import com.dishanhai.gt_shanhai.common.debug.WorldLoadMonitor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 世界加载监控命令
 * /worldloadmonitor report - 显示性能报告
 * /worldloadmonitor reset - 清空统计数据
 * /worldloadmonitor enable <true|false> - 启用/禁用监控
 */
public class WorldLoadMonitorCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("worldloadmonitor")
                        .requires(source -> source.hasPermission(2)) // 需要 OP 权限
                        .then(Commands.literal("report")
                                .executes(WorldLoadMonitorCommand::executeReport))
                        .then(Commands.literal("reset")
                                .executes(WorldLoadMonitorCommand::executeReset))
                        .then(Commands.literal("enable")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(WorldLoadMonitorCommand::executeEnable)))
        );
        
        // 简短别名
        dispatcher.register(
                Commands.literal("wlm")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("report")
                                .executes(WorldLoadMonitorCommand::executeReport))
                        .then(Commands.literal("reset")
                                .executes(WorldLoadMonitorCommand::executeReset))
                        .then(Commands.literal("enable")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(WorldLoadMonitorCommand::executeEnable)))
        );
    }
    
    private static int executeReport(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = null;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception e) {
            // 控制台执行，没有玩家
        }
        
        WorldLoadMonitor.printReport(player);
        
        if (player == null) {
            context.getSource().sendSuccess(() -> Component.literal("§a性能报告已输出到日志"), false);
        }
        
        return Command.SINGLE_SUCCESS;
    }
    
    private static int executeReset(CommandContext<CommandSourceStack> context) {
        WorldLoadMonitor.reset();
        context.getSource().sendSuccess(() -> Component.literal("§a世界加载监控数据已清空"), true);
        return Command.SINGLE_SUCCESS;
    }
    
    private static int executeEnable(CommandContext<CommandSourceStack> context) {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        WorldLoadMonitor.setEnabled(enabled);
        
        String status = enabled ? "§a启用" : "§c禁用";
        context.getSource().sendSuccess(() -> Component.literal("§e世界加载监控已" + status), true);
        return Command.SINGLE_SUCCESS;
    }
}
