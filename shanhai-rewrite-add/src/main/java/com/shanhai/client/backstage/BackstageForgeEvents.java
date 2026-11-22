package com.shanhai.client.backstage;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.client.ShanhaiWindowTitle;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 游戏总线上的两个钩子（<b>只有两个</b>，别再加第三个）：
 *
 * <ol>
 *   <li>{@link #onScreenOpening} —— <b>开始加载存档</b>的最早信号：原版在
 *       {@code Minecraft#doWorldLoad} 里先 {@code new LevelLoadingScreen(...)} 再 {@code setScreen}，
 *       {@code LevelLoadingScreen} 一出现就说明「已经在加载这个存档了」。
 *       收到就调 {@link ShanhaiWindowTitle#hintLoadingStarted()}，把阶段起点定在这一刻。</li>
 *   <li>{@link #onGameShuttingDown} —— 关游戏：让后台进程看到 {@code phase=STOP} 自己退出。
 *       1.20.1 <b>没有</b> {@code ClientStoppingEvent}（那是更高版本 Forge 的类），本版本
 *       原版 {@code Minecraft#stop()} 里会 post 的关闭事件就是 {@code GameShuttingDownEvent}；
 *       另外 JVM shutdown hook 在 {@link BackstageProcess#bootstrap} 里已经挂好，双保险。</li>
 * </ol>
 *
 * <p>退出世界回主菜单<b>不在这里停</b>：那时下一次 tick 的 {@link ShanhaiWindowTitle#publishState()}
 * 会把 phase 判成 {@code IDLE}，后台进程继续活着（用户明确要求）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT)
public final class BackstageForgeEvents {

    private BackstageForgeEvents() {
    }

    /** 后台进程都起来之前不处理屏事件（避免无谓的类加载）。 */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        try {
            if (!BackstageProcess.isStarted()) {
                return;
            }
            final Screen screen = event.getNewScreen();
            if (screen instanceof LevelLoadingScreen || screen instanceof ReceivingLevelScreen) {
                ShanhaiWindowTitle.hintLoadingStarted();
            }
        } catch (Throwable ignored) {
            // 标题/计时是装饰
        }
    }

    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        try {
            BackstageProcess.setLogger(ShanhaiMod.LOGGER::info);
            // 先撤 sink（让本体不再往一个要死的进程提交标题），再让它退
            com.shanhai.add.backstage.AddBackstageTitle.stopNow("game_shutdown");
            BackstageProcess.requestStop();
        } catch (Throwable ignored) {
            // 正在关游戏：任何异常都不许往外冒
        }
    }
}
