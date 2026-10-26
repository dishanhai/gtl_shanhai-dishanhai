package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 归墟视界枢核 · <b>中央主光柱着色器的资源重载挂钩</b>（只有一件事：把 {@link HostFxPillarBeam} 的懒建实例作废）。
 *
 * <h2>为什么需要它</h2>
 * 主光柱的 {@code ShaderInstance} 是"第一次真要画的时候自己 new"的（理由见 {@link HostFxPillarBeam} 类注释
 * §注册方式：本件不许碰注册类）。代价是它<b>不在原版 shader 列表里</b>，{@code F3+T} 资源重载时
 * <b>没人会替我们重建它</b> ⇒ 改完 shader 不重启游戏看不到效果。
 * 这里只把"重载发生了"这个信号接出来，作废缓存；下一帧 {@code draw} 会走"首次加载"那条路重新读
 * {@code assets/shanhai/shaders/core/shanhai_host_beam.*}。
 *
 * <h2>与 {@code client/render/blackhole/BlackHoleReloadHook} 是同一套写法</h2>
 * 两个包各有一份、互不引用（黑洞那个不归本任务改；本类只碰 hostfx 自己的东西）。
 *
 * <h2>为什么用 {@link RegisterShadersEvent}</h2>
 * 它是"原版 shader 刚注册完"的唯一官方时机（{@code F3+T} 与启动各来一次），
 * javadoc 原文："This event is fired on the mod-specific event bus" ⇒ {@code bus = Bus.MOD}。
 *
 * <p>⚠️ 就算这条事件因为任何原因没跑到，后果也只是"shader 不重载"，<b>不会崩、不会黑块</b>
 * —— 它是增强项，不是正确性依赖（真正的正确性判据在 {@link HostFxPillarBeam#onResourceReload()} 打的那行
 * {@code pillar_shader_reload rebuilt=1}）。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HostFxPillarBeamReloadHook {

    private HostFxPillarBeamReloadHook() {}

    /** 资源重载（含启动时那一次）⇒ 作废懒建的主光柱 shader，下一次 draw 重建。 */
    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        HostFxPillarBeam.onResourceReload();
        // 🔴 2026-10-25 第八改：内外环之间那圈【光柱环】用的是另一份着色器
        //    （shanhai_host_beam_ring，多一个 Normal 属性），同样不在原版列表里 ⇒ 同样要作废。
        //    两件事写在同一行位置，避免"只重载了主光柱、环还是旧 shader"这种半新半旧。
        HostFxBeamRing.onResourceReload();
    }
}
