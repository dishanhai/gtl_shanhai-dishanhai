package com.dishanhai.gt_shanhai.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensRenderer;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · <b>客户端 {@code GameRenderer} 混入</b>：把黑洞的屏幕空间透镜挪到
 * <b>Iris/Oculus 合成之后</b>再画。
 *
 * <h2>0. 为什么（用户 2026-10-24 傍晚的第 0 优先级决定）</h2>
 * 用户原话：「<b>你能不能不管开不开光影，直接注入 iris</b>」。
 * 要的是「<b>无论光影开/关，黑洞都走一级真透镜</b>」，但<b>不是</b>去改 Iris 的 shader 源码，
 * 也不是把我们的代码塞进 Iris 的 pipeline —— 只是把<b>自己的绘制时机</b>挪到合成之后。
 *
 * <h2>1. 取证：合成到底发生在哪一步（对 {@code oculus-mc1.20.1-1.8.0.jar} 反编译）</h2>
 * <pre>
 * net/irisshaders/iris/mixin/MixinLevelRenderer.java（反编译第 122-147 行）
 *   &#64;Inject(method = LevelRenderer.renderLevel(&hellip;)V, at = &#64;At(value = "RETURN", shift = Shift.BEFORE))
 *   private void iris$endLevelRender(&hellip;) {
 *       HandRenderer.INSTANCE.renderTranslucent(&hellip;);
 *       Minecraft.getInstance().getProfiler().push("iris_final");
 *       this.pipeline.finalizeLevelRendering();          // ← 合成在这里
 *       this.pipeline = null;
 *   }
 *
 * net/irisshaders/iris/pipeline/IrisRenderingPipeline.java（反编译第 1122-1130 行）
 *   public void finalizeLevelRendering() {
 *       this.isRenderingWorld = false;
 *       this.compositeRenderer.renderAll();
 *       this.finalPassRenderer.renderFinalPass();        // ← 把结果写回主渲染目标
 *   }
 *   public void finalizeGameRendering() {                // ← 之后才做色彩空间转换
 *       this.colorSpaceConverter.process(Minecraft.getInstance().getMainRenderTarget().getColorTextureId());
 *   }
 *
 * net/irisshaders/iris/mixin/MixinGameRenderer.java（反编译第 486-492 行）
 *   &#64;Inject(method = GameRenderer.renderLevel(&hellip;)V, at = &#64;At("TAIL"))
 *   private void iris$runColorSpace(&hellip;) {
 *       Iris.getPipelineManager().getPipeline().ifPresent(WorldRenderingPipeline::finalizeGameRendering);
 *   }
 * </pre>
 * ⇒ <b>合成 = {@code LevelRenderer.renderLevel} 的 RETURN</b>；
 * <b>色彩空间转换 = {@code GameRenderer.renderLevel} 的 TAIL</b>。
 * <p>而 {@code GameRenderer.renderLevel} 在同一个方法里<b>调用</b>了前者（不是另一层回调）。
 * 字节码证据（{@code client.jar} 里 obf 名 {@code fjq} = GameRenderer、{@code fjv} = LevelRenderer，
 * {@code javap -p -c fjq} 的 {@code public void a(float, long, eij)}）：
 * <pre>
 *   585: invokevirtual #1737  // Method fjv.a:(Leij;FJZLemz;Lfjq;Lfjw;Lorg/joml/Matrix4f;)V
 *   592: invokevirtual #943   // Method enn.aG:()Lban;           ← profiler "hand"
 *   616: invokestatic  #1399  // Method RenderSystem.clear:(IZ)V ← 只清深度
 *   625: invokevirtual #1741  // Method a:(Leij;Lemz;F)V         ← renderItemInHand
 * </pre>
 *
 * <h2>2. 所以我们挂在 {@code 585} 的<b>后面</b>（{@code Shift.AFTER}），不是 TAIL</h2>
 * 这个点同时满足四件事：
 * <ol>
 *   <li><b>已经在合成之后</b>（{@code finalizeLevelRendering} 在 {@code LevelRenderer.renderLevel}
 *       的 RETURN 里跑完了）⇒ 主渲染目标里是<b>本帧</b>的世界，光影开着也读得到正确背景；</li>
 *   <li><b>还没 {@code renderItemInHand}</b> ⇒ 手持物不会被透镜扭曲（TAIL 那个点已经画过了）；</li>
 *   <li><b>还没 {@code finalizeGameRendering}</b> ⇒ 我们写进去的像素会跟着 Iris 的色彩空间转换
 *       一起处理，不会出现"黑洞比周围亮/暗一个 gamma"；</li>
 *   <li><b>主渲染目标仍是当前写目标</b>（{@code unbindWrite} 在更外层）。
 *       就算它被 Iris 换过，{@code BlackHoleLensRenderer} 拷背景前会再 {@code bindWrite(false)} 一次。</li>
 * </ol>
 * <p>⚠️ 为什么<b>不</b>写 {@code at = @At("TAIL")}：Mixin 不保证两条 TAIL 注入的先后，
 * 而 Iris 自己也有一条 TAIL 注入（{@code iris$runColorSpace}）——挂 TAIL 会让
 * "我们在色彩空间转换之前还是之后"变成不确定的，同一个 jar 在不同装机顺序下表现不同。
 * <p>⚠️ 为什么<b>不</b>挂在 {@code LevelRenderer.renderLevel} 的 RETURN 上：我们和 Iris 会抢同一个点，
 * 谁先谁后由 mixin 优先级决定；挂在"调用方"这一层则是<b>定义上</b>的"在它返回之后"。
 *
 * <h2>3. {@code require = 0, remap = false}：装不上也不许崩</h2>
 * 注入点被别的 mod 改掉 / 签名对不上时，{@code require = 1} 会让<b>整个 mixin 配置加载失败</b>
 * （最坏是启动崩）。这里要的是"退化而不是崩"：{@code require = 0, remap = false}，然后由
 * {@link BlackHoleLensRenderer#onRenderLevelStage} 的
 * {@code path=lens-after-weather} 退路继续画，并在 40 帧后打一行 {@code mixin_missing} WARN。
 * <p>代价是"注入失败"不会在启动期报错 —— 所以那条 WARN 是必需的，不能省。
 *
 * <h2>4. 与 Iris 的关系：只读取证，不注入</h2>
 * 本类<b>不引用</b>任何 {@code net.irisshaders.*} 类型，也不改 Iris 的任何状态；
 * 它只是把我们的画放到了 Iris 已经画完之后。光影是否生效由
 * {@code BlackHoleIris} 只用<b>公开 API</b> 读出来（{@code IrisApi.getInstance().isShaderPackInUse()}），
 * 结果只进日志与 {@code depth=} 口径。
 */
@Mixin(value = GameRenderer.class, remap = false)
public class ShanhaiBlackHoleGameRendererMixin {

    /** 山海：master 無 refmap，Minecraft 注入使用 SRG 名。 */
    @Inject(
            method = "m_109089_",
            at = @At(
                    value = "INVOKE",
                    // 🔴 用【SRG 名 + remap = false】：refmap 只翻译了 renderLevel 这个方法名
                    //    （实测生成的 refmap 里只有 "renderLevel": "...m_109089_(FJ...)V"），
                    //    @At 的 target 字符串<b>不会</b>被注解处理器重映射 ⇒ 写开发名
                    //    （LevelRenderer;renderLevel(...)）在生产 jar 里永远匹配不上（那里叫 m_109599_）。
                    //    这个锚点在 1.20.1 + Forge 47.4.16 + parchment 2023.09.03 下是常量；
                    //    万一哪天变了，下面那个 @At("TAIL") 会顶上（两个入口用同一个"取走 pending"
                    //    的契约，所以同一帧只会画一次）。
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;m_109599_(Lcom/mojang/blaze3d/vertex/PoseStack;FJLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 0, remap = false
    )
    private void shanhai$blackHoleAfterComposite(float partialTick, long finishNanoTime,
                                                 PoseStack poseStack, CallbackInfo ci) {
        shanhai$drawAfterComposite(BlackHoleLensRenderer.STAGE_GAME_RENDERER, true);
    }

    /**
     * <b>首选入口</b>：{@code GameRenderer.renderLevel} 里 {@code RenderSystem.clear(IZ)V} 的<b>前面</b>。
     *
     * <h2>🔴 为什么还要多这一个（2026-10-24 深夜，用户第二条「为什么有方块挡着还能看到透镜效果」）</h2>
     * vanilla 在 {@code LevelRenderer.renderLevel} 返回之后、{@code renderItemInHand} 之前会调一次
     * {@code RenderSystem.clear(256)}（= 清 DEPTH；obf {@code fjq.a(FJeij)V} 的 offset <b>616</b>，
     * 而调 {@code LevelRenderer.renderLevel} 在 offset <b>585</b>）。
     * ⇒ 只要注入点落在 616 之后，<b>深度缓冲已经是 1.0</b>，`lequal` 恒通过 = 没有遮挡
     * （哪怕 {@code shaderpack=false}）。
     * <p>所以真正"既在合成之后、又还能用深度"的窗口是 <b>585 与 616 之间</b>。
     * 上一轮实测日志里 {@code stage=…:TAIL} ⇒ 说明上面那个 INVOKE 锚点没命中、退到了 TAIL ⇒ 正好落在窗口外。
     * <h2>为什么这个锚点稳</h2>
     * {@code com/mojang/blaze3d/**} <b>不参与混淆</b>（只有 {@code net.minecraft.*} 会），
     * 所以这个字符串在生产 jar 里就是它本身 —— Oculus 自己也用同一个锚点：
     * {@code MixinLevelRenderer} 的 {@code CLEAR = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"} +
     * {@code @At(value="INVOKE", target=CLEAR, shift=AFTER, remap=false)}。
     * <p>用 {@code Shift.BEFORE} ⇒ 深度还没被清，遮挡成立。
     */
    @Inject(
            method = "m_109089_",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
                    shift = At.Shift.BEFORE,
                    remap = false
            ),
            require = 0, remap = false
    )
    private void shanhai$blackHoleBeforeDepthClear(float partialTick, long finishNanoTime,
                                                   PoseStack poseStack, CallbackInfo ci) {
        shanhai$drawAfterComposite(BlackHoleLensRenderer.STAGE_GAME_RENDERER_BEFORE_DEPTH_CLEAR, true);
    }

    /**
     * 兜底入口：{@code GameRenderer.renderLevel} 的 {@code TAIL}（<b>不需要任何 target 字符串</b>）。
     *
     * <p>为什么不只留上面那个：{@code @At} 的 target 是 SRG 字面量（见上），一旦上游改了签名，
     * 上面那条会静默失败（{@code require = 0, remap = false}），而本条仍然生效。
     * <p>为什么两条不会画两遍：{@link BlackHoleLensRenderer#drawAfterComposite} 会
     * <b>取走并清空</b>这一帧的读数（{@code pending}），第二次调用拿到 null 直接返回 false。
     * <p>TAIL 与上面那个点的差别只有两处（都可接受）：①手持物已经画过了（光影开时本来就在合成里）；
     * ②Iris 的 {@code finalizeGameRendering}（色彩空间）可能已经跑过 —— 我们的输出是"输入的几何重采样"，
     * 在两种色彩空间里都自洽。
     */
    @Inject(method = "m_109089_", at = @At("TAIL"), require = 0, remap = false)
    private void shanhai$blackHoleAfterLevelTail(float partialTick, long finishNanoTime,
                                                 PoseStack poseStack, CallbackInfo ci) {
        // ⚠️ TAIL 在 vanilla 清深度（offset 616）之后 ⇒ 这里 depthPreserved=false，
        //    黑洞会变成"无遮挡版"（前面的方块挡不住它）。只在上面两个锚点都失灵时才会走到。
        shanhai$drawAfterComposite(BlackHoleLensRenderer.STAGE_GAME_RENDERER_TAIL, false);
    }

    /** 三个入口共用：调渲染件并吞掉一切异常（渲染线程上抛异常 = 直接崩游戏）。 */
    private static void shanhai$drawAfterComposite(String stage, boolean depthPreserved) {
        try {
            BlackHoleLensRenderer.drawAfterComposite(stage, depthPreserved);
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-BH] after_composite_exception stage={} path={} err={}",
                    stage, BlackHoleLensRenderer.lastPath(), t.toString());
        }
    }
}
