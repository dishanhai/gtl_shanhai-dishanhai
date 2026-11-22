package com.shanhai.client.event;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.render.droplet.ShanhaiDropletDebugRenderer;
import com.shanhai.common.crash.ShanhaiCrashBoltEntity;
import com.shanhai.registry.ShanhaiEntities;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 实体的<b>客户端</b>渲染器注册点（只跑在客户端；专用服务端不会加载本类）。
 *
 * <h2>为什么不能写在 {@link ShanhaiEntities} 里</h2>
 * 2026-11-16 冒烟实锤：原本用 Registrate 的 {@code EntityBuilder#renderer(() -> ctx -> new ThrownItemRenderer<>(ctx))}，
 * 而 {@code ThrownItemRenderer} 是<b>客户端类</b>。专用服务端在 {@code CONSTRUCT} 阶段校验那段 lambda 时
 * 就要解析它（及其父类 {@code EntityRenderer}）⇒
 * {@code java.lang.RuntimeException: Attempted to load class net/minecraft/client/renderer/entity/EntityRenderer
 * for invalid dist DEDICATED_SERVER} ⇒ <b>整个专服起不来</b>。
 *
 * <p>所以：<b>注册点搬到 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} 的类里</b>。
 * 带 {@code value = Dist.CLIENT} 的订阅器在专用服务端上根本不会被加载，
 * 客户端类也就永远不会出现在服务端的类加载路径上（与本工程 {@code ShanhaiHaloClientSetup} 同一条口径）。
 *
 * <h2>为什么渲染器选原版 {@code ThrownItemRenderer}</h2>
 * 它把 {@code ItemSupplier#getItem()} 返回的 stack 平躺着画成 GROUND 物品 ⇒ 客户端看到的就是那件异常物品的
 * 64×64 贴图（连动态贴图都会动，因为走的是同一套 {@code ItemRenderer}）⇒ 零自写渲染代码。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiEntityRenderers {

    public static final String PREFIX = "[SHANHAI-RENDER]";

    private ShanhaiEntityRenderers() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
                ShanhaiEntities.CRASH_BOLT.get(),
                context -> new ThrownItemRenderer<ShanhaiCrashBoltEntity>(context));
        // 强相互作用水滴：占位调试渲染器（只有线框；外观另有一条线，审过之后才做真实渲染）
        event.registerEntityRenderer(ShanhaiEntities.DROPLET.get(), ShanhaiDropletDebugRenderer::new);
        ShanhaiMod.LOGGER.info("{} renderer_registered id={}:crash_bolt renderer=vanilla:ThrownItemRenderer",
                PREFIX, ShanhaiMod.MOD_ID);
        ShanhaiMod.LOGGER.info("{} renderer_registered id={}:strong_interaction_droplet renderer=client:debug_lines",
                PREFIX, ShanhaiMod.MOD_ID);
    }
}
