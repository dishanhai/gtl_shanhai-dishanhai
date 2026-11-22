package com.shanhai.client.backstage;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.client.ShanhaiWindowTitle;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端初始化时的启动点（<b>mod 事件总线</b>）：把后台进程起来，并把日志出口接到游戏日志。
 *
 * <p>为什么还要这一条（{@link ShanhaiWindowTitle#tickFromLoader()} 已经更早）：
 * {@code FMLClientSetupEvent} 是 Forge 生命周期里一个<b>稳定</b>的点 —— 窗口一定已经创建、
 * mod 一定已经构造完。加载泵那条更早，但它依赖一个混入（{@code require = 0}，可能静默挂不上）；
 * 两条都调 {@link BackstageProcess#bootstrap}，幂等，谁先到谁负责起。
 *
 * <p>🔴 {@code value = Dist.CLIENT}：本类引用客户端类，专服必须靠 ASM 读注解直接跳过。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BackstageModEvents {

    private BackstageModEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        try {
            BackstageProcess.setLogger(ShanhaiMod.LOGGER::info);
            // 触发一次状态发布：它会 ensureStarted（起子进程 + 写状态文件）。
            // 这一刻窗口已存在，HWND 取得到（GLFW 主线程）。
            ShanhaiWindowTitle.publishState();
        } catch (Throwable ignored) {
            // 标题/计时是装饰：任何异常都不许影响客户端初始化
        }
    }
}
