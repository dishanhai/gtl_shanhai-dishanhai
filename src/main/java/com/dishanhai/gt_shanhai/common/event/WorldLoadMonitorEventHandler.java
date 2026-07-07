package com.dishanhai.gt_shanhai.common.event;

import com.dishanhai.gt_shanhai.common.debug.WorldLoadMonitor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界加载监控事件监听器 - 使用 Forge 事件而非 Mixin
 * 
 * 注意：已禁用 @Mod.EventBusSubscriber，不会自动注册
 * 如需启用，取消下面一行的注释
 */
// @Mod.EventBusSubscriber(modid = "gt_shanhai", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WorldLoadMonitorEventHandler {
    
    // 延迟发送报告的时间（tick）
    private static final int REPORT_DELAY_TICKS = 100; // 5 秒后发送报告
    
    // 记录每个玩家的登录时间点，用于延迟发送报告
    private static final Map<UUID, Long> pendingReports = new ConcurrentHashMap<>();
    
    // 服务器 tick 计数器
    private static long serverTickCount = 0;
    
    /**
     * 玩家开始登录（最早触发点）
     */
    @SubscribeEvent
    public static void onPlayerLoggingIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!WorldLoadMonitor.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        
        WorldLoadMonitor.startTiming("Event.PlayerLoggedIn");
        
        // 记录登录时间点，延迟 5 秒后发送报告
        pendingReports.put(player.getUUID(), serverTickCount + REPORT_DELAY_TICKS);
        
        // 给玩家提示监控已开始
        player.sendSystemMessage(Component.literal("§7[世界加载监控] 正在记录加载性能数据..."));
        player.sendSystemMessage(Component.literal("§7[DEBUG] 监控已启动，事件监听已触发"));
    }
    
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!WorldLoadMonitor.isEnabled()) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            WorldLoadMonitor.endTiming("Event.PlayerLoggedIn");
        }
    }
    
    /**
     * 服务器 tick - 收集性能数据
     * 优先级设为 LOWEST，确保在所有其他监听器之后执行
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            WorldLoadMonitor.startTiming("Event.ServerTick");
            WorldLoadMonitor.startTiming("Event.ServerTick.Phase.START");
            serverTickCount++;
        }
    }
    
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            WorldLoadMonitor.endTiming("Event.ServerTick.Phase.START");
        } else if (event.phase == TickEvent.Phase.END) {
            WorldLoadMonitor.startTiming("Event.ServerTick.Phase.END");
            
            // 检查所有待发送的报告
            if (!pendingReports.isEmpty()) {
                pendingReports.entrySet().removeIf(entry -> {
                    UUID playerUUID = entry.getKey();
                    long reportTime = entry.getValue();
                    
                    if (serverTickCount >= reportTime) {
                        // 到时间了，发送报告
                        ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerUUID);
                        if (player != null) {
                            WorldLoadMonitor.printReport(player);
                            player.sendSystemMessage(Component.literal("§7提示：使用 §e/wlm report§7 可再次查看性能报告"));
                            player.sendSystemMessage(Component.literal("§7提示：使用 §e/wlm reset§7 可清空数据并重新监控"));
                            player.sendSystemMessage(Component.literal("§7提示：查看日志搜索 '慢速操作' 找到具体瓶颈"));
                        }
                        return true; // 移除已处理的条目
                    }
                    return false; // 保留未到时间的条目
                });
            }
            
            WorldLoadMonitor.endTiming("Event.ServerTick.Phase.END");
            WorldLoadMonitor.endTiming("Event.ServerTick");
        }
    }
    
    /**
     * 世界 tick
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (!WorldLoadMonitor.isEnabled()) return;
        if (event.side.isClient()) return; // 只监控服务端
        
        if (event.phase == TickEvent.Phase.START) {
            WorldLoadMonitor.startTiming("Event.LevelTick");
        } else {
            WorldLoadMonitor.endTiming("Event.LevelTick");
        }
    }
    
    /**
     * 区块加载
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!WorldLoadMonitor.isEnabled()) return;
        if (event.getLevel().isClientSide()) return; // 只监控服务端
        
        WorldLoadMonitor.startTiming("Event.ChunkLoad");
        WorldLoadMonitor.endTiming("Event.ChunkLoad"); // 立即结束（事件本身很快）
    }
    
    /**
     * 方块放置（代表世界修改）
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!WorldLoadMonitor.isEnabled()) return;
        if (event.getLevel().isClientSide()) return;
        
        WorldLoadMonitor.startTiming("Event.BlockPlace");
        WorldLoadMonitor.endTiming("Event.BlockPlace");
    }
}
