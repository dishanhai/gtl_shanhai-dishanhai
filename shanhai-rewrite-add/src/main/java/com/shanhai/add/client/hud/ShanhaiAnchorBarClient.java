package com.shanhai.add.client.hud;

import com.shanhai.ShanhaiMod;
import com.shanhai.add.ShanhaiAdd;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · 锚点条的<b>客户端生命周期监听</b>（退出世界/断线时把缓存清干净）。
 *
 * <h2>1. 为什么要单独一个类</h2>
 * {@link ShanhaiAnchorBarCache} 是纯逻辑核（零 MC 依赖，可离线验），
 * 所以"什么时候清"这种<b>必须碰游戏事件</b>的事放不到它身上 —— 就放这里。
 * 两者分工：本类只知道"该清了"，清的理由与判据全在缓存类注释 §3。
 *
 * <h2>2. 挂 {@code ClientPlayerNetworkEvent.LoggingOut} 而不是 ClientTickEvent</h2>
 * 前者是<b>一次性、有明确语义</b>的事件（这一局结束了），只在真正退出时来一次；
 * 后者每 tick 都跑，要么再判一个"刚刚才变 null"的边沿（多一份状态），
 * 要么每 tick 都去清缓存（把"收到一帧"和"清空"搅在一起，读数就不可数了）。
 *
 * <h2>3. 只在客户端跑</h2>
 * {@code @OnlyIn(Dist.CLIENT)} ＋ {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)}：
 * 专用服务端上 Forge 不会把本类注册进事件总线，常量池里的 {@code net.minecraft.client.*}
 * <b>永远不解析</b> ⇒ 服务端不可能 {@code NoClassDefFoundError}（与 {@link ShanhaiAnchorBarHud} 同款）。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiAnchorBarClient {

    private ShanhaiAnchorBarClient() {
    }

    /**
     * 退出世界/断线：清缓存。
     *
     * <p>🔴 不做这件事的后果是真机上看到的：回标题界面还挂着一条 60 秒的进度条，
     * 换到新存档后那条旧帧再也不会被服务端覆盖（新世界这一局没设过锚点）。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            ShanhaiAnchorBarCache.clear();
            ShanhaiMod.LOGGER.info("[SHANHAI-ANCHORBAR] client_cache_clear reason=logging_out {}", 
                    ShanhaiAnchorBarCache.summary());
        } catch (Throwable t) {
            // 退出路径上抛异常会打断客户端断线流程（比"没清缓存"严重得多）
            ShanhaiMod.LOGGER.error("[SHANHAI-ANCHORBAR] client_cache_clear_failed err={}", t.toString());
        }
    }
}
