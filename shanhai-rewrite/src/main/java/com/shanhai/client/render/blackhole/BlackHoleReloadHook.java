package com.shanhai.client.render.blackhole;

import com.shanhai.ShanhaiMod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · 黑洞渲染件的<b>资源重载挂钩</b>（只有一件事：把 {@link BlackHoleLensRenderer} 的 shader 缓存作废）。
 *
 * <h2>为什么需要它</h2>
 * 本件的 shader 是"第一次真要画的时候自己 {@code new ShaderInstance(...)}"（理由见
 * {@link BlackHoleLensRenderer} 类注释 §5）。代价是它<b>不在原版的 shader 列表里</b>，
 * 所以 {@code F3+T} 资源重载时没人会替我们重建它。作废缓存后，下一帧会走"首次加载"那条路
 * 重新读 {@code assets/shanhai/shaders/core/blackhole.*} ⇒ 改了着色器不用重启游戏。
 *
 * <h2>为什么用 {@link RegisterShadersEvent}</h2>
 * 它是"原版 shader 刚注册完"的唯一官方时机，javadoc 原文：
 * "This event is fired on the mod-specific event bus"（{@code RegisterShadersEvent.java} 第 18 行）
 * ⇒ 用 {@code bus = Bus.MOD} 订阅。
 *
 * <p>⚠️ 就算这条事件因为任何原因没跑到，后果也只是"shader 不重载"，<b>不会崩、不会黑块</b>
 * —— 它是增强项，不是正确性依赖。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BlackHoleReloadHook {

    private BlackHoleReloadHook() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        // 不需要 event 的内容：我们只用它当"重载发生了"的信号。
        BlackHoleLensRenderer.onResourceReload();
    }
}
