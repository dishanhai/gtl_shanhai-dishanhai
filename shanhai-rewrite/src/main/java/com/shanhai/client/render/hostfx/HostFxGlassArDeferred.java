package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;

import org.joml.Matrix4f;

/**
 * 归墟视界枢核 · <b>玻璃 AR 面板 + 灰色遮罩的"延迟提交"队列</b>（2026-10-25 第二十二改）。
 *
 * <h2>1. 为什么需要它（用户 2026-10-25 原话）</h2>
 * <blockquote>
 * 「引力透镜……还会影响前面的方块」「<b>我们的 AR 会被黑洞影响，这就不对</b>」
 * 「<b>这个遮罩没有把黑洞遮上</b>」
 * </blockquote>
 * <h2>2. 取证：AR 与透镜谁先画（决定"被弯折"这件事是必然还是偶然）</h2>
 * <pre>
 *   GameRenderer.renderLevel
 *     └─ LevelRenderer.renderLevel(...)          ← 整层世界都在这里
 *          ├─ 方块层（solid/cutout/translucent）
 *          ├─ renderBlockEntities(...)           ← ★ AR 面板 + 遮罩就在这一档（TESR）
 *          │     GuixuHostFxRenderer.render → HostFxGlassArText.render → 画几何 + 字
 *          ├─ 粒子 / 天气
 *          └─ RenderLevelStageEvent.AFTER_WEATHER ← 透镜<b>采样</b>点（BlackHoleLensRenderer）
 *     └─ ShanhaiBlackHoleGameRendererMixin（LevelRenderer.renderLevel 返回之后、
 *        RenderSystem.clear(DEPTH) 之前）        ← 透镜<b>真正画</b>的点（drawAfterComposite）
 *     └─ RenderSystem.clear(DEPTH) ─ renderItemInHand ─ GUI
 * </pre>
 * ⇒ <b>AR 在透镜之前画</b>，于是它成了透镜"从主渲染目标里拷出来的那张背景"的一部分 ⇒
 * 必然被引力透镜弯折，而且透镜画在它上面 ⇒ 灰色遮罩永远盖不住黑洞。
 * <p>这不是时序抖动，是<b>定义性</b>的：TESR 永远在 {@code LevelRenderer.renderLevel} 内部，
 * 而透镜永远在它返回之后。所以修法只能是"把 AR 挪到透镜之后画"。
 *
 * <h2>3. 做法：TESR 里只<b>采样</b>，透镜画完再<b>提交</b></h2>
 * <ul>
 *   <li>TESR 里 {@link HostFxGlassArText#render} 照旧算完模型（布局/读数/文字串都不变），
 *       然后把这一帧要用的三样东西<b>拷一份</b>存进本类：
 *       ①{@code PoseStack} 的当帧矩阵、②{@code RenderSystem.getModelViewMatrix()}、
 *       ③模型 + 枢轴 + 朝向；</li>
 *   <li>透镜画完之后（{@code BlackHoleLensRenderer.drawAfterComposite} 的 {@code finally}）
 *       调 {@link #flushAfterLens()}，用<b>同一份矩阵</b>把同一批顶点再提一遍 ——
 *       因为同帧同相机，画出来的位置与"没挪过"逐像素相同，只是<b>时机</b>晚了一步。</li>
 * </ul>
 *
 * <h2>🔴 4. 深度遮挡：<b>不丢</b>（这是选这条路的核心理由）</h2>
 * 透镜的绘制点是 {@code GameRenderer.renderLevel} 里 {@code RenderSystem.clear(IZ)V}
 * 的<b>前面</b>（vanilla offset 616 之前，锚点见 {@code ShanhaiBlackHoleGameRendererMixin}）
 * ⇒ <b>深度缓冲此刻仍是本帧世界的真实深度</b>，而透镜自己 {@code depthMask(false)} 从不写深度。
 * <p>本类提交的两条层沿用了 {@link HostFxGlassArDraw} 的既有状态：
 * {@code DepthTestStateShard(LEQUAL)} + {@code WriteMaskStateShard(true, false)}。
 * 于是"AR 面板贴在玻璃墙上、前面的墙挡住它"这条遮挡关系<b>照旧由硬件深度测试给出</b>，
 * <b>不需要</b>自己再做一次 {@code world.getBlockState} 射线判定，也不需要第二份深度拷贝。
 * <p>⚠️ 唯一会退化的是<b>兜底入口</b>（{@code @At("TAIL")}，vanilla 已经清过深度）：
 * 那里 {@code depthPreserved=false}，AR 会失去方块深度遮挡。这与透镜自己在同一入口上的取舍一致
 * （同一个注入点、同一份深度状态），不是新引入的问题；正常装机不会走到那条路
 * （日志里 {@code stage=GameRenderer@renderLevel:BEFORE_RenderSystem.clear} 就是证据）。
 *
 * <h2>5. 兜底：flush 点不止一个</h2>
 * <ol>
 *   <li>{@code drawAfterComposite} 的 {@code finally}（常态；mixin 的三条注入共用它）；</li>
 *   <li>{@code BlackHoleLensRenderer.onRenderLevelStage} 的退路（mixin 没装上时的
 *       {@code path=lens-after-weather}，在那里画完透镜立刻 flush）。</li>
 * </ol>
 * ⇒ 只要世界还在渲染，本帧就一定有一次 flush。<b>绝不会</b>出现"挪到透镜之后 ⇒ AR 整块消失"。
 * <p>另外还有两道保险：①同一帧捕获的读数<b>只提交一次</b>（提交后立刻清空）；
 * ②超过 {@link #STALE_NANOS} 没提交的读数<b>丢弃并计数</b>（暂停/切屏时不会拿旧相机画）。
 */
public final class HostFxGlassArDeferred {

    private HostFxGlassArDeferred() {}

    /** 读数的新鲜度上限（纳秒）。1 帧在 20 fps 下是 50 ms，这里给 4 帧余量。 */
    public static final long STALE_NANOS = 200_000_000L;

    /** 待提交的模型（{@code null} = 没有）。 */
    private static HostFxGlassArModel.Model model;
    /** 当帧 {@code PoseStack.last().pose()} 的拷贝（相机相对的世界矩阵）。 */
    private static final Matrix4f POSE = new Matrix4f();
    /** 当帧 {@code RenderSystem.getModelViewMatrix()} 的拷贝（顶点着色器还要再乘它）。 */
    private static final Matrix4f MODELVIEW = new Matrix4f();
    private static float cx;
    private static float cy;
    private static float cz;
    private static Direction facing;
    private static long stampNanos;

    /** 统计（进 {@code [SHANHAI-HOSTFX] draw} 那一行）。 */
    private static long captured;
    private static long flushed;
    private static long dropped;
    private static long lastQuads;
    private static long lastTexts;
    /** 丢弃日志只打一次（同一条原因不刷屏）。 */
    private static boolean staleWarned;

    /**
     * TESR 里调用：把这一帧的 AR 面板<b>存起来</b>，等透镜画完再提交。
     *
     * @param poseStack TESR 的顶点栈（取 {@code last().pose()} 拷一份）
     * @param m         {@link HostFxGlassArModel#build} 算出的模型（含灰色遮罩底，它是第一个图元）
     * @param ccx,ccy,ccz 环心相对控制器的坐标（与三组烘焙同一个枢轴）
     * @param f         主机朝向
     */
    public static void capture(PoseStack poseStack, HostFxGlassArModel.Model m,
                               float ccx, float ccy, float ccz, Direction f) {
        if (poseStack == null || m == null || f == null) {
            return;
        }
        model = m;
        POSE.set(poseStack.last().pose());
        MODELVIEW.set(RenderSystem.getModelViewMatrix());
        cx = ccx;
        cy = ccy;
        cz = ccz;
        facing = f;
        stampNanos = System.nanoTime();
        captured++;
    }

    /** 有没有待提交的东西（日志/判据用）。 */
    public static boolean pending() {
        return model != null;
    }

    /** 换维度/复位：把待提交的读数丢掉（不留跨维度的残渣）。 */
    public static void reset() {
        model = null;
        stampNanos = 0L;
        staleWarned = false;
    }

    /**
     * 透镜画完之后调：把当前帧捕获的 AR 面板 + 遮罩提交上去。
     *
     * <p>提交顺序与 TESR 里完全一致：<b>灰色遮罩底（alpha 层） → 加性发光件 → 文字</b>
     * ⇒ 遮罩在透镜之上（能盖住黑洞）、文字在遮罩之上（不会被自己糊掉）。
     *
     * <p>幂等：提交后清空，同一帧的第二次调用是空操作。
     * 线程：只在渲染线程（调用方都是渲染路径）。
     *
     * @return 是否真的提交了一份
     */
    public static boolean flushAfterLens() {
        final HostFxGlassArModel.Model m = model;
        if (m == null) {
            return false;
        }
        if (System.nanoTime() - stampNanos > STALE_NANOS) {
            model = null;
            dropped++;
            if (!staleWarned) {
                staleWarned = true;
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] glass_ar_stale drop=1 why=读数超过 {}ms 没等到透镜的 flush"
                        + "（暂停/切屏/渲染被截断时会这样；AR 本帧不画，下一帧照常）", STALE_NANOS / 1_000_000L);
            }
            return false;
        }
        model = null;                                   // 先取走：就算下面抛异常也不会重复画
        final Minecraft mc = Minecraft.getInstance();
        final Font font = mc == null ? null : mc.font;
        if (font == null) {
            return false;
        }
        // 🔴 modelview 必须与 TESR 那一帧【同一份】：顶点是按当帧矩阵算出来的，
        //    而 RenderType 的 setup 用的是 RenderSystem 的 modelview。两者不一致 ⇒ 整块飞掉。
        //    在本调用前后成对 push/pop，绝不把 Lens 之后的全局状态带歪。
        final PoseStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushPose();
        try {
            mvStack.last().pose().set(MODELVIEW);
            RenderSystem.applyModelViewMatrix();

            final PoseStack ps = new PoseStack();
            ps.last().pose().set(POSE);

            final int quads = HostFxGlassArDraw.render(ps, cx, cy, cz, facing, m);
            final int texts = HostFxGlassArText.drawTexts(ps, font, m, cx, cy, cz, facing);

            lastQuads = quads;
            lastTexts = texts;
            flushed++;
            HostFxGlassArText.noteDeferredFlush(quads, texts);
        } finally {
            mvStack.popPose();
            RenderSystem.applyModelViewMatrix();
        }
        return true;
    }

    /** 一行摘要（进渲染器每 5 秒那条 {@code draw} 日志）。 */
    public static String stats() {
        return "AR延迟 捕获=" + captured + " 提交=" + flushed + " 丢弃=" + dropped
                + " 待提交=" + (model != null ? "是" : "否")
                + " 四边形=" + lastQuads + " 文字=" + lastTexts;
    }
}
