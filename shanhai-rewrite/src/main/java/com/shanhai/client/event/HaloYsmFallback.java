package com.shanhai.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shanhai.ShanhaiMod;
import com.shanhai.client.render.halo.HaloEndRenderer;
import com.shanhai.item.ShanhaiItems;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.util.LazyOptional;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * <b>YSM（是，史蒂夫模型）兜底渲染路径</b> —— 写进我们自己的 jar，不依赖任何外部兼容 mod。
 *
 * <h2>为什么需要它</h2>
 * YSM 用 Mixin 在 {@code EntityRenderDispatcher.render()} 层<b>拦截并跳过</b>原版
 * {@code entityrenderer.render(...)} 调用。
 * <p>取证（YSM 2.6.5，即目标实例 {@code mods\[是，史蒂夫模型] ysm-2.6.5-forge+mc1.20.1-release.jar}）：
 * {@code com/elfmcys/yesstevemodel/mixin/client/EntityRenderDispatcherMixin} 上挂的是
 * <b>{@code com.llamalad7.mixinextras.injector.v2.WrapWithCondition}</b>，
 * 目标 {@code Lnet/minecraft/client/renderer/entity/EntityRenderer;render(...)V}，
 * 处理函数返回 {@code boolean}（{@code javap -v} 原文见报告）。
 * {@code WrapWithCondition} 的语义是「返回 false ⇒ 跳过被包裹的调用」，
 * 而该函数在"YSM 真的画了这个模型"那一支返回 {@code false}、在"没画，交给原版"那一支返回 {@code true}
 * ⇒ <b>YSM 模型接管时，挂在该玩家渲染器上的 {@code CuriosLayer} 根本不会被执行</b>。
 * （参考项目 {@code ysm终末之环/README.md} 原文："YSM 通过 Mixin 在
 * EntityRenderDispatcher.render() 级别拦截玩家渲染，绕过原版 PlayerRenderer…
 * YSM 会跳过这些图层，导致效果不显示或显示在错误位置"。）
 *
 * <h2>🔴 自算姿势：本路径【不读调用方的任何模型】</h2>
 * 任务书要求处理「拿不到 {@code PlayerModel} 的 {@code head}」这一支。这里的做法比"退化处理"更彻底：
 * <b>本路径从一开始就不依赖调用方模型</b> —— 全部位姿由「玩家自身数据 + 配置常量」算出
 * （{@code bodyYaw} 含 partialTick 插值、蹲下/幼年修正、配置里的头顶偏移），
 * 再用<b>我们自己的</b> {@code HaloEndModel} 画环。
 * 因此"模型不是原版 {@code PlayerModel}"这件事对位置<b>没有任何影响</b>，不存在"静默画在错误位置"的可能。
 * 具体位姿公式见 {@link HaloEndRenderer#renderSelfPose}。
 *
 * <h2>姿势栈前奏的来源（不是猜的）</h2>
 * Forge 的 {@code RenderLivingEvent.Post} 在 {@code LivingEntityRenderer.render} 的
 * {@code poseStack.popPose()} <b>之后</b> post。
 * 取证（Forge 1.20.1-47.2.20 源码 {@code LivingEntityRenderer.java}）：
 * 第 135 行 {@code p_115311_.popPose();} → 第 136 行 {@code super.render(...)} → 第 137 行
 * {@code MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post<>(...))}；
 * 而 {@code EntityRenderDispatcher.render} 第 138~140 行只做 {@code pushPose / translate(d2,d3,d0) / renderer.render}
 * ⇒ 事件里拿到的是<b>「实体位置」这种裸姿势栈</b>：没有 yaw、没有 {@code scale(-1,-1,1)}、
 * 没有 {@code translate(0,-1.501,0)}。
 * 所以下面补的 yaw / 蹲下 / 幼年三项 + 配置位姿，与参考实现
 * （{@code GoetyLayerRenderer.renderEffects} 第 359~371 行 + {@code HaloOffsets.applyTo} 第 100~106 行）
 * <b>逐句一致</b>——那是同一环境下用户实机验收通过的做法。
 */
@OnlyIn(Dist.CLIENT)
public final class HaloYsmFallback {

    private static final String PREFIX = "[SHANHAI-HALO]";

    /** 只打一次的"谁在画这个玩家"日志 —— 下一次游戏运行就能凭它判定走的是哪条路径。 */
    private static boolean loggedRenderer = false;

    private HaloYsmFallback() {}

    /** 注册方：{@code ShanhaiHaloClientSetup.onClientSetup}（Curios 在场时挂）。 */
    public static void onRenderLivingPost(RenderLivingEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        final boolean vanillaPlayerRenderer = event.getRenderer() instanceof PlayerRenderer;

        if (!loggedRenderer) {
            loggedRenderer = true;
            ShanhaiMod.LOGGER.info("{} 首次进入 RenderLivingEvent.Post：player={} renderer={} "
                            + "isPlayerRenderer={}（此值决定 AUTO 模式走哪条位姿路径）",
                    PREFIX, player.getGameProfile().getName(),
                    event.getRenderer().getClass().getName(), vanillaPlayerRenderer);
        }

        if (!HaloEndRenderer.selfPoseActive(vanillaPlayerRenderer)) {
            // AUTO + 原版 PlayerRenderer ⇒ CuriosLayer 已经在画，这里不重复画（避免双环）。
            return;
        }

        LazyOptional<ICuriosItemHandler> curios = CuriosApi.getCuriosInventory(player);
        ICuriosItemHandler handler = curios.orElse(null);
        if (handler == null || handler.findFirstCurio(ShanhaiItems.HALO_END.get()).isEmpty()) {
            return;   // 只有真的戴着我们这枚环才画
        }

        final float partialTick = event.getPartialTick();
        final PoseStack poseStack = event.getPoseStack();
        final MultiBufferSource buffers = event.getMultiBufferSource();

        // 与 Curios 路径同源的参数（参考项目 GoetyLayerRenderer 第 340~351 行同款推导）。
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);

        HaloEndRenderer.renderSelfPose(player, poseStack, buffers, bodyYaw,
                player.isCrouching(), player.isPassenger(), player.isBaby());
    }
}
