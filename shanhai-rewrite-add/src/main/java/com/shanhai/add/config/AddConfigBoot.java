package com.shanhai.add.config;

import com.mojang.logging.LogUtils;
import com.shanhai.add.ShanhaiAdd;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * 启动时把 {@code config/shanhai_add-common.toml} 里<b>每一个键</b>实际读到的值打一遍。
 *
 * <h2>为什么要专门一个类</h2>
 * 用户 2026-11-22 报的是"重开游戏配置重置为默认"。判这一条不需要读代码，只需要一行日志：
 * <pre>
 *   [SHANHAI-ADD] cfg_load key=hotfix.auto value=false source=file default=true file_raw=false
 * </pre>
 * {@code source=file} = 这个值来自文件；{@code source=default} = 文件里没有这一行（回落默认）。
 * ⇒ 玩家下次报"又被重置了"，直接 grep {@code cfg_load} 就知道是没落盘、还是落了但读错。
 *
 * <h2>为什么挂在 {@code FMLCommonSetupEvent}</h2>
 * 那一刻 COMMON 配置<b>已经加载完</b>（{@code ConfigValue#get()} 可用；mod 构造器里还不行 —— 会抛
 * {@code IllegalStateException}），而世界/服务端还没起 ⇒ 一行在世界之前，判读时不会和游戏内日志混在一起。
 * 这个事件每个游戏进程只出一次（单机 = 一次；专用服务端 = 一次）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AddConfigBoot {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AddConfigBoot() {}

    /** 配置加载完成后，把 9 个键的实读值各打一行（{@code cfg_load}）。 */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                AddConfigFiles.logLoadAll();
            } catch (Throwable t) {
                // 读数失败绝不许影响启动（配置本身仍然照常生效）
                LOGGER.warn("{} cfg_load_dump failed err={}", AddConfigFiles.PREFIX, t.toString());
            }
        });
    }
}
