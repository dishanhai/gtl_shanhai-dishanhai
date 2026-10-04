package com.shanhai.client.render.halo;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shanhai.ShanhaiMod;
import com.shanhai.client.config.ShanhaiClientConfig;
import com.shanhai.client.config.ShanhaiClientConfig.PoseMode;
import com.shanhai.item.ShanhaiItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * 终末之环的头顶渲染 —— 一个渲染体（{@link #renderHaloParts}），两个入口、两套位姿。
 *
 * <h2>入口 A：Curios 路径（{@link #render}）</h2>
 * 由 Curios 的 {@code CuriosLayer} 调起（挂在原版 {@code PlayerRenderer} 的图层上）。
 * 这条路上，姿势栈<b>已经含原版渲染前奏</b>（{@code setupRotations} 的 yaw → {@code scale(-1,-1,1)}
 * → {@code translate(0,-1.501,0)}），所以「什么都不加」就对了 —— 这正是启示录原版的行为。
 * 默认参数（{@code [halo_end.model_pose]}）= 全 0 / 1.0 ⇒ 与启示录逐值等价。
 *
 * <h2>入口 B：YSM 自算姿势路径（{@link #renderSelfPose}）</h2>
 * 由 {@code HaloYsmFallback}（{@code RenderLivingEvent.Post}）调起。
 * 这条路的姿势栈<b>只是「实体位置」</b>：Forge 的 {@code RenderLivingEvent.Post} 在
 * {@code LivingEntityRenderer.render} 的 {@code popPose()} <b>之后</b> post
 * （取证：Forge 1.20.1-47.2.20 的 {@code LivingEntityRenderer.java} 第 135 行 {@code popPose}、
 * 第 137 行 post；{@code EntityRenderDispatcher.render} 只做 {@code translate(x,y,z)}，见其第 138~140 行）
 * ⇒ 头部的姿态、身高偏移、朝向<b>都不在</b>姿势栈里，必须自己补。
 * 补法逐句抄自参考实现（用户 2026-09-21 在 YSM 下实机验收通过）：
 * {@code ysm_render_compat 1.0.6} 的 {@code GoetyLayerRenderer.renderEffects} 第 359~371 行
 * + {@code HaloOffsets.applyTo} 第 100~106 行。
 *
 * <h2>渲染体本身：逐句抄 {@code OdamaneHaloLayer.render}</h2>
 * 见 {@link #renderHaloParts} 的注释（含"为什么放大缩小/光照恒全亮/渲染类型用 cutout"）。
 */
@OnlyIn(Dist.CLIENT)
public final class HaloEndRenderer implements ICurioRenderer {

    /**
     * 我们自己的环贴图（64×64）。内容 = <b>逐字节</b>拷贝启示录原图
     * （{@code [Forge]RevelationFix-1.20.1-4.4.jar!assets/revelationfix/textures/entity/player/halo_the_end.png}，
     * 819 B，sha256 {@code 816296D2496A078D6C8A17BD2615D6E392C6BFDDF926E8C4ABC0DC13CA7B59F5}）。
     */
    public static final ResourceLocation HALO_TEX =
            new ResourceLocation("shanhai", "textures/entity/halo_end.png");

    /** 与原文一致：环不受世界光照影响（15728880 = 0xF000F0）。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final String PREFIX = "[SHANHAI-HALO]";

    /** 每条路径各打一次的可判据日志（"路径真的被调到了" vs "没报错"要能区分）。 */
    private static boolean loggedCuriosPath = false;
    private static boolean loggedSelfPath = false;

    private static HaloEndModel model;

    private HaloEndRenderer() {}

    // ------------------------------------------------------------------ 注册（客户端）

    public static void register() {
        CuriosRendererRegistry.register(ShanhaiItems.HALO_END.get(), HaloEndRenderer::new);
    }

    /** 客户端模型层只在渲染线程被 bake，这里用懒加载（避免在 ClientSetup 期提前 bake）。 */
    private static HaloEndModel model() {
        try {
            if (model == null) {
                model = new HaloEndModel(
                        Minecraft.getInstance().getEntityModels().bakeLayer(HaloEndModel.LAYER_LOCATION), false);
            }
            return model;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} 光环模型 bake 失败，本次不渲染", PREFIX, t);
            return null;
        }
    }

    /** 当前配置下，Curios 路径该不该画（见 {@link PoseMode}）。 */
    private static boolean curiosPathActive() {
        return switch (ShanhaiClientConfig.poseMode()) {
            case SELF_POSE -> false;      // 自算路径独占，避免双画
            case AUTO, MODEL_POSE -> true;
        };
    }

    /** 当前配置下，自算姿势路径该不该画（见 {@link PoseMode}）。 */
    public static boolean selfPoseActive(boolean rendererIsVanillaPlayerRenderer) {
        return switch (ShanhaiClientConfig.poseMode()) {
            case SELF_POSE -> true;
            case MODEL_POSE -> false;
            case AUTO -> !rendererIsVanillaPlayerRenderer;
        };
    }

    // ------------------------------------------------------------------ 入口 A：Curios 路径

    /** 与 Curios 5.14.1 的 {@code ICurioRenderer.render} 逐字匹配。 */
    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(ItemStack stack, SlotContext slotContext,
                                                                          PoseStack poseStack,
                                                                          RenderLayerParent<T, M> renderLayerParent,
                                                                          MultiBufferSource bufferSource,
                                                                          int packedLight, float limbSwing,
                                                                          float limbSwingAmount, float partialTick,
                                                                          float ageInTicks, float netHeadYaw,
                                                                          float headPitch) {
        if (!curiosPathActive() || !ShanhaiClientConfig.haloEnabled()) {
            return;
        }
        if (!loggedCuriosPath) {
            loggedCuriosPath = true;
            ShanhaiMod.LOGGER.info("{} 渲染路径命中【A 原版/Curios】：renderer={} 位姿={}（姿势栈含原版渲染前奏，"
                            + "故默认全 0 = 与启示录逐值等价）",
                    PREFIX, renderLayerParent.getModel().getClass().getName(),
                    HaloPose.of(ShanhaiClientConfig.modelPose()).describe());
        }
        HaloPose pose = HaloPose.of(ShanhaiClientConfig.modelPose());
        poseStack.pushPose();
        try {
            pose.applyTo(poseStack);
            renderHaloParts(slotContext.entity(), poseStack, bufferSource);
        } finally {
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------ 入口 B：YSM 自算姿势

    /**
     * 由 {@code HaloYsmFallback} 在 {@code RenderLivingEvent.Post} 里调用。
     *
     * <p>🔴 本方法<b>不使用调用方的任何模型</b>（YSM 下拿到的不是 {@code PlayerModel}，
     * 就算拿得到，YSM 自己那套骨骼的 {@code head} 部件也未必处在原版语义上）。
     * 全部位姿由「玩家自身数据 + 配置常量」算出：{@code bodyYaw}（含 partialTick 插值）、
     * 蹲下/幼年修正，再加配置里的头顶偏移。
     *
     * @param bodyYaw 已插值的身体朝向（度），调用方用 {@code Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)} 算好
     */
    public static void renderSelfPose(AbstractClientPlayer player, PoseStack poseStack, MultiBufferSource bufferSource,
                                      float bodyYaw, boolean crouching, boolean passenger, boolean baby) {
        if (!ShanhaiClientConfig.haloEnabled()) {
            return;
        }
        HaloPose pose = HaloPose.of(ShanhaiClientConfig.ysmPose());
        if (!loggedSelfPath) {
            loggedSelfPath = true;
            ShanhaiMod.LOGGER.info("{} 渲染路径命中【B 自算姿势/YSM】：player={} poseMode={} 位姿={}",
                    PREFIX, player.getGameProfile().getName(), ShanhaiClientConfig.poseMode(), pose.describe());
        }

        poseStack.pushPose();
        try {
            // ① 原版 LivingEntityRenderer.setupRotations 的第一句（yaw）。
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - bodyYaw));
            // ② 原版 PlayerRenderer.setupRotations 的蹲下修正。
            if (crouching && !passenger) {
                poseStack.translate(0.0D, 0.125D, 0.0D);
            }
            // ③ 原版 LivingEntityRenderer.render 的幼年修正。
            if (baby) {
                poseStack.scale(0.5F, 0.5F, 0.5F);
                poseStack.translate(0.0D, 1.0D, 0.0D);
            }
            // ④ 用户可调位姿（位移 → 旋转 → 缩放）。
            pose.applyTo(poseStack);
            // ⑤ 渲染体本身。
            renderHaloParts(player, poseStack, bufferSource);
        } finally {
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------ 渲染体（两条路径共用，逐句抄原版）

    /**
     * 逐句抄自 {@code OdamaneHaloLayer.render}（字节码偏移 183~428 的那一支）：
     * <pre>
     * angle = (Mth.cos((float) 玩家名长度) * 3201123.0F + (float) Blaze3D.getTime() * 90.0F) * 0.017453292F;
     * packedLight = 15728880;                       // 原文覆盖成全亮
     * poseStack.pushPose();
     * model.head.translateAndRotate(poseStack);      // f_102808_
     * model.Halo.translateAndRotate(poseStack);
     * poseStack.translate(0.0, -0.0, -0.5);
     * model.bone2.zRot =  angle;  model.bone3.zRot =  angle;
     * model.bone4.zRot = -angle;  model.bone5.zRot = -angle;
     * model.bone.y     = Mth.cos(angle) + 1.0F;
     * model.Halo.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
     * poseStack.popPose();
     * </pre>
     *
     * <h3>🔴 与原文相同、但容易被当成 bug 的三处，先说明白</h3>
     * <ol>
     *   <li><b>放大两遍 {@code Halo} 的自身位移</b>：原文<b>既</b>显式调了
     *       {@code Halo.translateAndRotate(poseStack)}，<b>又</b>调了 {@code Halo.render(...)}；
     *       而 {@code ModelPart.render} 自己会 {@code pushPose → translateAndRotate} 一次
     *       （取证：Forge 1.20.1-47.2.20 的 {@code ModelPart.java} 第 113~117 行）。
     *       所以 {@code Halo} 的 {@code PartPose.offset(0,-5,10)} 实际被施加了<b>两次</b>。
     *       这是原版的既有行为 —— 参考实现在同一基础上调出的配置值也建立在这个前提上，
     *       所以这里<b>照抄不动</b>（改了就会同时破坏两条路径的已验收数值）。</li>
     *   <li><b>不调用 {@code setupAnim}</b>：原文从不调用它自己的光环模型 {@code setupAnim}
     *       （全内层 jar 内只有 {@code OdamaneHaloModel} 自己引用该方法）⇒ 它的
     *       {@code head} 部件旋转恒为 0。这里同样不调 ⇒ 两条路径的姿势与原文一致。
     *       （副作用：环不随头部俯仰摆动，只随身体朝向 —— 这是原版行为，不是 bug。）</li>
     *   <li><b>光照恒为全亮</b>：原文在两条分支里都把入参 {@code packedLight} 覆盖成 15728880。</li>
     * </ol>
     *
     * <h3>渲染类型</h3>
     * 原文"普通"分支用的是 {@code model.renderType(HALO_TEX)}，即
     * {@code EntityModel.renderType} ⇒ {@code RenderType.entityCutoutNoCull(...)}。
     * 我们照抄这一条（不用 {@code entityTranslucent}）—— 本贴图的 alpha 只有 0 与 255 两种
     * （实测 64×64 共 4096 px：alpha=0 有 3859 px、alpha=255 有 237 px），
     * cutout 与 translucent 在这种图上是同一结果，而 cutout 与原版逐值一致。
     */
    private static void renderHaloParts(LivingEntity wearer, PoseStack poseStack, MultiBufferSource bufferSource) {
        HaloEndModel m = model();
        if (m == null) {
            return;
        }
        final float angle = (Mth.cos((float) wearer.getName().getString().length()) * 3201123.0F
                + (float) Blaze3D.getTime() * 90.0F) * 0.017453292F;

        poseStack.pushPose();
        try {
            m.head.translateAndRotate(poseStack);
            m.Halo.translateAndRotate(poseStack);
            poseStack.translate(0.0D, -0.0D, -0.5D);

            m.bone2.zRot = angle;
            m.bone3.zRot = angle;
            m.bone4.zRot = -angle;
            m.bone5.zRot = -angle;
            m.bone.y = Mth.cos(angle) + 1.0F;

            VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(HALO_TEX));
            m.Halo.render(poseStack, consumer, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
        }
    }
}
