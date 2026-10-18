package com.shanhai.add.gravity;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.config.ShanhaiAddConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 重力倍率的<b>服务端生命周期</b>（FORGE 总线）。
 *
 * <h2>两个时机</h2>
 * <ol>
 *   <li><b>世界/服务器起来了</b>（{@link ServerStartedEvent}）：从 {@code config/shanhai_add-common.toml}
 *       把上次的倍率读进 {@link ShanhaiGravity}，并打两行读数。
 *       —— 这就是"重启游戏 ⇒ 值还是上次那个"的那一步。</li>
 *   <li><b>玩家登录</b>（{@link PlayerEvent.PlayerLoggedInEvent}）：把当前倍率补发给这个玩家。
 *       —— 晚进服的人也要拿到，否则他的客户端按 1 预测 ⇒ 橡皮筋。</li>
 * </ol>
 *
 * <h2>为什么用 {@code ServerStartedEvent} 而不是 {@code FMLCommonSetupEvent}</h2>
 * 配置项在 COMMON 配置<b>加载完成</b>之后才有值（{@code ConfigValue.get()} 在未加载时抛
 * {@code IllegalStateException}）；而且"当前这个世界的倍率"本来就属于服务器生命周期。
 * 单机里它由集成服务器触发，客户端同 JVM ⇒ 客户端侧的值同步可见（不需要再来一次配置读取）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiGravityEvents {

    private ShanhaiGravityEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 旧配置迁移的【第二个】调用点（`runOnce` 幂等；common setup 那一刻若配置还没加载，
        // 它不会置"已跑"闩，这里补一次）。放在读值之前 —— 迁来的值必须这一次就生效。
        com.shanhai.add.config.AddGravityConfigMigration.runOnce();
        double fromConfig = ShanhaiGravity.VANILLA;
        if (ShanhaiAddConfig.isLoaded()) {
            try {
                fromConfig = ShanhaiAddConfig.COMMON.gravityMultiplier.get();
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} config_read_failed err={}（按 1 处理）",
                        ShanhaiGravity.PREFIX, t.toString());
            }
        } else {
            ShanhaiMod.LOGGER.warn("{} config_not_loaded（按 1 处理）", ShanhaiGravity.PREFIX);
        }
        final double applied = ShanhaiGravity.set(fromConfig);
        ShanhaiMod.LOGGER.info("{} loaded mult={} from_config={} clamped={}",
                ShanhaiGravity.PREFIX, ShanhaiGravity.fmt(applied),
                ShanhaiGravity.fmt(fromConfig), applied != fromConfig);
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiGravity.PREFIX,
                ShanhaiGravity.stateLine(ShanhaiGravity.SITE_BASES.length));
        // 世界重载（退出到标题再进来）时也要让在线客户端对齐：单人没有在线玩家，这条是空转
        ShanhaiGravityNetwork.syncToAll(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ShanhaiGravityNetwork.syncTo(player);
            ShanhaiMod.LOGGER.info("{} login_sync player={} mult={}",
                    ShanhaiGravity.PREFIX, player.getName().getString(),
                    ShanhaiGravity.fmt(ShanhaiGravity.get()));
        }
    }
}
