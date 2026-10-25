package com.shanhai.client.render.hostfx;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 归墟视界枢核 · <b>光柱</b>—— <b>伪神之锻炉主光柱的 1:1 复刻</b>。
 *
 * <p>本类现在画<b>两批</b>、但走的是<b>同一个</b> {@code drawBeam(...)}：
 * <ol>
 *   <li>{@link #draw} —— <b>中央上下 2 根</b>竖主光柱（第五改起）；</li>
 *   <li>{@link #drawMdBeams} —— <b>4 座物质解压机的喷口主光柱</b>（第七改新增，
 *       用户原话：「<b>还有 4 个物质解压机的主光束也换成伪神同款的啊</b>」）。</li>
 * </ol>
 * 两批的 shader / 贴图 / 顶点格式 / uniform / 两遍 Intensity 完全一致，差别只有
 * 端点（竖轴 vs 喷口→洞面）、半径（6.20→3.80 vs 1.25→1.55）和可配颜色（两个独立开关）。
 *
 * <h2>复刻对象（取证：gtladditions-3.2.8Custom-fix2.jar 内 · 2026-10-25 从 jar 里直接取出）</h2>
 * <pre>
 *   类      com/gtladd/gtladditions/client/render/machine/antichrist/AntichristBeamRenderer
 *           （入口 {@code render(...)}；我们照抄的是它的通用直线版 {@code renderLinear(...)}）
 *   着色    assets/gtladditions/shaders/core/gtladditions_antichrist_beam.{vsh,fsh}
 *   程序声明 assets/gtladditions/shaders/core/gtladditions_antichrist_beam.json
 *   贴图    assets/gtladditions/textures/block/multiblock/forge_of_antichrist/space_layer.png
 *           （8192×4096，文件头是 JFIF ⇒ MC 用 stb 解，扩展名仍叫 .png）
 *   注册    AntichristShaders.register(RegisterShadersEvent) →
 *           new ShaderInstance(ResourceProvider, id, BEAM_VERTEX_FORMAT)
 * </pre>
 *
 * <h2>逐项对照（左 = 伪神，右 = 我们）</h2>
 * <table border="1">
 *   <tr><th>项</th><th>伪神之锻炉</th><th>本类</th></tr>
 *   <tr><td>GLSL</td><td>{@code gtladditions_antichrist_beam.vsh/.fsh}</td>
 *       <td>{@code shanhai_host_beam.vsh/.fsh}（<b>字节级相同</b>，sha256 一致）</td></tr>
 *   <tr><td>顶点格式</td><td>{@code Position + UV0 + UV1}（UV1 = 2×short，装 alpha×10000）</td>
 *       <td>{@link #BEAM_VERTEX_FORMAT}，同一份 {@code ImmutableMap}</td></tr>
 *   <tr><td>贴图</td><td>{@code space_layer.png}</td>
 *       <td>{@code assets/shanhai/textures/block/host/space_layer.png}（<b>字节级相同</b>）</td></tr>
 *   <tr><td>uniform</td><td>{@code CameraPosition(vec3)} / {@code BeamColor(vec3)} / {@code Intensity(float)} / {@code Sampler0}</td>
 *       <td>同名同类型（JSON 逐字复制，只改了 shader id 的命名空间）</td></tr>
 *   <tr><td>两遍</td><td>soft {@code Intensity=2.0}；intense {@code Intensity=4.0} 且 {@code BeamColor=(1,1,1)}</td>
 *       <td>同（{@link #drawBeam}）</td></tr>
 *   <tr><td>几何</td><td>沿轴两端点 → {@code 16} 个四边形（{@code Mode.TRIANGLES}，每片 6 顶点）、
 *       起始角加 {@code cameraAngle−π/2}</td><td>同（{@link #writeBeamVertices}）</td></tr>
 *   <tr><td>UV</td><td>{@code u=offset/256+tick/240}，{@code v=angle/2π+u/3+tick/240}</td>
 *       <td>同（就是"贴图沿轴流动"的来源）</td></tr>
 *   <tr><td>混合</td><td>JSON {@code add / srcalpha / 1-srcalpha}（{@code BlendMode.apply()} 在
 *       {@code shader.apply()} 里生效）＋ 显式 {@code blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)}</td><td>同</td></tr>
 *   <tr><td>深度</td><td>{@code enableDepthTest()} + {@code depthMask(false)}</td><td>同</td></tr>
 *   <tr><td>剔除</td><td>{@code disableCull()} 后恢复</td><td>同</td></tr>
 *   <tr><td>提交</td><td>{@code VertexBuffer(Usage.DYNAMIC)} + {@code drawWithShader(pose, proj, shader)}</td>
 *       <td>同（不能走 RenderType：那样顶点会被 PoseStack 预乘，{@code Position.xy} 就不是"光束本地横截面"了，
 *       fsh 的 {@code localPosition} 会算错）</td></tr>
 * </table>
 *
 * <h2>⚠️ 我们没有照抄的两处（都只为状态卫生，不影响像素）</h2>
 * <ol>
 *   <li>{@code writeBeamVertices} 在原实现里对 {@code segmentId 0..9} 都发顶点，而 {@code renderLinear}
 *       只写了 <b>2 个端点</b>（+ 一次 {@code repeatLastEndpoint}）⇒ 后面 9 段取到端点表外 →
 *       <b>半径 0 / 偏移 0 的退化三角形</b>。我们只发<b>有效的那一段</b>（16 片 × 6 顶点 = 96 顶点）；
 *       像素结果相同，顶点数 960 → 96。</li>
 *   <li>原实现结束后只 {@code enableCull + depthMask(true) + disableBlend}，不动 blend func。
 *       我们额外补一句 {@code defaultBlendFunc()}（MC 默认就是它，等价）。</li>
 * </ol>
 *
 * <h2>注册方式（与伪神的差异）</h2>
 * 伪神走 {@code RegisterShadersEvent} 里 {@code event.registerShader(...)}。本类<b>不许碰注册类</b>，
 * 改成<b>首帧懒建</b>：{@code new ShaderInstance(Minecraft.getInstance().getResourceManager(), id, format)}
 * （全类唯一一处，在 {@code createShader()} 工厂里；持有器是 {@link HostFxLazyHandle}）。
 * 失败（缺 json/编译不过/没有 GL）⇒ 返回 {@code null}，由 {@link HostFxDraw} 回落到上一版的
 * 自绘管壳（{@link HostFxGeometry#buildPillarQuads}），<b>绝不出现"主光柱整根不见了"</b>。
 *
 * <p><b>资源重载</b>（{@code F3+T}）由 {@link HostFxPillarBeamReloadHook} 监听 {@code RegisterShadersEvent} →
 * {@link #onResourceReload()}：只作废实例 + 打判据行 {@code pillar_shader_reload rebuilt=1}，
 * GL 侧的 {@code close()} 留到渲染线程下一次 {@link #shader()}。
 */
public final class HostFxPillarBeam {

    private HostFxPillarBeam() {}

    /** 着色器 id（{@code assets/shanhai/shaders/core/shanhai_host_beam.json}）。 */
    public static final ResourceLocation SHADER_ID = new ResourceLocation("shanhai", "shanhai_host_beam");

    /** 贴图（伪神那张 {@code space_layer} 的字节级副本）。 */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation("shanhai", "textures/block/host/space_layer.png");

    /**
     * 顶点格式 —— 与 {@code AntichristShaders.BEAM_VERTEX_FORMAT} <b>逐项相同</b>：
     * {@code Position(FLOAT×3) + UV0(FLOAT×2) + UV1(SHORT×2)}。
     *
     * <p>为什么必须是 UV1 而不是 UV2：fsh 里 {@code in ivec2 UV1} 读的就是它（alpha×10000）。
     */
    public static final VertexFormat BEAM_VERTEX_FORMAT = new VertexFormat(ImmutableMap.of(
            "Position", DefaultVertexFormat.ELEMENT_POSITION,
            "UV0", DefaultVertexFormat.ELEMENT_UV0,
            "UV1", DefaultVertexFormat.ELEMENT_UV1));

    /** 沿圆周几等分（伪神写死 16）—— 数值来源是 {@link HostFxGeometry#PILLAR_QUADS}（同一口径）。 */
    public static final int QUADS = HostFxGeometry.PILLAR_QUADS;
    /** alpha 打包比例（伪神 {@code ALPHA_PACK_SCALE = 10000}）。 */
    public static final float ALPHA_PACK_SCALE = 10000.0F;
    /** soft 遍的 Intensity（伪神写死 2.0）。 */
    public static final float INTENSITY_SOFT = 2.0F;
    /** intense 遍的 Intensity（伪神写死 4.0）。 */
    public static final float INTENSITY_INTENSE = 4.0F;

    /**
     * 着色器的<b>懒建持有器</b>（"首帧建 + 资源重载后作废重建"的语义全在 {@link HostFxLazyHandle} 里，
     * 那边零 MC 依赖 ⇒ 离线探针能拿替身对象把"身份变没变"跑出来）。
     *
     * <p>🔴 本类里 {@code new ShaderInstance(...)} <b>只准出现一次</b>，就在这个工厂 lambda 里
     * （静态判据：`HostFxPillarBeam.java` 里 `new ShaderInstance(` 计数 == 1）。
     */
    private static final HostFxLazyHandle<ShaderInstance> HANDLE =
            new HostFxLazyHandle<>(HostFxPillarBeam::createShader);

    /**
     * 资源重载时<b>待 close 的旧实例</b>。
     *
     * <p>为什么要延后：{@link #onResourceReload()} 由 {@code RegisterShadersEvent} 触发，
     * 而 {@code ShaderInstance.close()} 是 <b>GL</b> 操作 —— 只在这里记一笔，真正的 close 放到
     * 渲染线程的下一次 {@link #shader()}（即使将来事件换线程也不会在错误线程碰 GL）。
     */
    private static ShaderInstance pendingClose;

    private static VertexBuffer buffer;
    /** 上一次成功画的遍数（日志用；正常 = 中央每根 2 遍 × 2 根 = 4）。 */
    private static int lastPasses;
    /** 上一次画 4 座解压机主柱的遍数（正常 = 每根 2 遍 × 4 根 = <b>8</b>；0 = 回落）。 */
    private static int lastMdPasses;
    /** 上一次打过 {@code pillar_shader_ok} 的 build 序号（同一个实例只打一次）。 */
    private static int loggedBuilds = -1;
    /** 上一次打过 {@code pillar_shader_failed} 的 build 序号（失败也只打一次，重载后可再打一次）。 */
    private static int warnedBuilds = -1;

    /** 上一帧提交的遍数（0 = 没画；4 = 上下各 soft+intense）。 */
    public static int passes() {
        return lastPasses;
    }

    /** 上一帧 4 条解压机主柱提交的遍数（0 = shader 没起来；8 = 4 根各 soft+intense）。 */
    public static int mdPasses() {
        return lastMdPasses;
    }

    /**
     * 记一笔"这一帧<b>故意</b>没画 4 条解压机主柱"（降级态整段跳过时由 {@code HostFxDraw} 调）。
     *
     * <p>为什么需要它：{@link #drawMdBeams} 的开头才会把 {@code lastMdPasses} 清零。
     * 降级态我们<b>不调</b>那个方法，读数就会停在上一次正常帧的 <b>8</b> ⇒
     * {@code HostFxDraw.stats()} 与 {@code draw} 日志会一起谎报"8 根都画了"。
     * 这个方法是那条"宁可不画、也不撒谎"的最小补丁。
     */
    public static void markMdBeamsSkipped() {
        lastMdPasses = 0;
    }

    /** 着色器是否已经就绪（日志/自检用）。 */
    public static boolean ready() {
        return shader() != null;
    }

    /**
     * 资源重载信号（由 {@code HostFxPillarBeamReloadHook} 在 {@code RegisterShadersEvent} 里调）。
     *
     * <p>做三件事：① 作废当前实例（下一次 {@link #shader()} 重建）；
     * ② 把旧实例交给渲染线程 close；③ 打<b>判据行</b>
     * {@code [SHANHAI-HOSTFX] pillar_shader_reload rebuilt=1 …}（每次资源重载恰好一条）。
     *
     * @return 是否真的作废了一个旧实例（true = 重载前有一个在用的实例）
     */
    public static boolean onResourceReload() {
        final ShaderInstance old = HANDLE.invalidate();
        if (old != null) {
            pendingClose = old;
        }
        loggedBuilds = -1;                    // 重建后要重新打一条 pillar_shader_ok（带新 identity）
        warnedBuilds = -1;
        ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] pillar_shader_reload rebuilt=1 oldIdentity={} builds={} "
                        + "hadOld={}（下一次 draw 重建 + close 旧实例；判据：F3+T 后光柱跟着变）",
                old == null ? "none" : Integer.toHexString(System.identityHashCode(old)),
                HANDLE.builds(), old != null);
        return old != null;
    }

    /** 一行摘要（渲染器 {@code draw} 日志里打这一行）。 */
    public static String stats() {
        return "主光柱 伪神同款 " + lastPasses + "/4 遍（中央）+ " + lastMdPasses + "/8 遍（4 座解压机）"
                + "（shader=" + (SHADER_ID.getPath()) + "，贴图 " + TEXTURE.getPath()
                + "，Intensity " + INTENSITY_SOFT + "/" + INTENSITY_INTENSE
                + "，几何 16 片三角形/遍，shader 实例 identity="
                + Integer.toHexString(HANDLE.identityHash()) + " 建过 " + HANDLE.builds() + " 次）";
    }

    /** 工厂：<b>全类唯一的</b> {@code new ShaderInstance(...)}（资源管理器没起来时返回 null = 下次再试）。 */
    private static ShaderInstance createShader() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) {
            return null;                                  // 还没就绪：不标记失败
        }
        try {
            return new ShaderInstance(mc.getResourceManager(), SHADER_ID, BEAM_VERTEX_FORMAT);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ShaderInstance 建不起来：" + SHADER_ID
                    + "（缺 assets/shanhai/shaders/core/shanhai_host_beam.json？）", e);
        }
    }

    /** 每帧取一次实例（命中时只是一次判空）；顺带把"重载后待 close 的旧实例"关掉、把两条探针日志打掉。 */
    private static ShaderInstance shader() {
        // ① 重载后延后到渲染线程的 GL 回收
        final ShaderInstance dead = pendingClose;
        if (dead != null) {
            pendingClose = null;
            dead.close();
        }
        // ② 取实例（必要时重建）
        final ShaderInstance s = HANDLE.get();
        // ③ 探针日志：每个实例只打一次
        if (s != null && loggedBuilds != HANDLE.builds()) {
            loggedBuilds = HANDLE.builds();
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] pillar_shader_ok id={} 顶点格式={}（伪神同款）"
                            + " identity={} 第 {} 次建",
                    SHADER_ID, BEAM_VERTEX_FORMAT,
                    Integer.toHexString(System.identityHashCode(s)), HANDLE.builds());
        }
        if (s == null && HANDLE.failed() && warnedBuilds != HANDLE.builds()) {
            warnedBuilds = HANDLE.builds();
            ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] pillar_shader_failed id=" + SHADER_ID
                    + "（回落到自绘管壳，主光柱不会消失）", HANDLE.lastError());
        }
        return s;
    }

    /** 换维度时丢弃（GL 侧一并 close；shader 也作废，下次进世界重建）。 */
    public static void reset() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        final ShaderInstance old = HANDLE.invalidate();
        if (old != null) {
            old.close();
        }
        pendingClose = null;
        loggedBuilds = -1;
        warnedBuilds = -1;
        lastPasses = 0;
        lastMdPasses = 0;
    }

    private static VertexBuffer buffer() {
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        return buffer;
    }

    /**
     * 画<b>上下两根</b>中央主光柱（每根 soft + intense 两遍）。
     *
     * <p>⚠️ 调用方必须已经套好<b>环心枢轴</b>、且<b>不要</b>套环自转：
     * 光柱在竖轴上，自转只会让贴图跟着转（伪神那边贴图是"沿轴流"，不该有角向漂移）。
     *
     * @param tipR         黑洞表面半径（格）—— 光柱从这里长出去
     * @param seconds      世界时间（秒）；{@code tick = seconds × 20} 喂给 UV 滚动（伪神用 tick/240）
     * @param camLocalX/Y/Z 相机在<b>环心本地坐标</b>里的位置（{@link HostFxGeometry#cameraInRingLocal}）
     * @param glowRgb      外层发光颜色（伪神 = {@code AntichristRenderProfile.colorR/G/B}，
     *                     我们 = {@link HostFxGeometry#jetGlowRgb()}，可配）
     * @return 成功提交的遍数（0 = 着色器没起来，调用方应回落）
     */
    public static int draw(PoseStack poseStack, float tipR, float seconds,
                           float camLocalX, float camLocalY, float camLocalZ, float[] glowRgb) {
        lastPasses = 0;
        final ShaderInstance s = shader();
        if (s == null) {
            return 0;
        }
        final float tick = seconds * 20.0F;
        for (int sign = -1; sign <= 1; sign += 2) {
            final float y0 = sign * tipR;
            final float y1 = sign * (tipR + HostFxGeometry.PILLAR_LENGTH);
            if (!drawBeam(poseStack, s, 0.0F, y0, 0.0F, 0.0F, y1, 0.0F,
                    HostFxGeometry.PILLAR_OUTER_R0, HostFxGeometry.PILLAR_OUTER_R1,
                    HostFxGeometry.PILLAR_INNER_SCALE, glowRgb,
                    HostFxGeometry.PILLAR_OUTER_ALPHA, HostFxGeometry.PILLAR_INNER_ALPHA,
                    tick, camLocalX, camLocalY, camLocalZ)) {
                lastPasses = 0;
                return 0;
            }
            lastPasses += 2;
        }
        return lastPasses;
    }

    /**
     * 画<b>4 座物质解压机的喷口主光柱</b>（每根 soft + intense 两遍）—— 🔴 2026-10-25 第七改新增。
     *
     * <p>用户原话：「<b>还有 4 个物质解压机的主光束也换成伪神同款的啊</b>」⇒ 本方法复用的就是
     * 上面 {@link #draw} 用的那<b>同一个</b> {@code drawBeam}：同一份 shader、同一张 space_layer、
     * 同一套顶点格式/uniform、同样两遍 Intensity 2.0/4.0。
     * 差别只有三个数：
     * <pre>
     *   端点   中央：(0,±tipR,0) → (0,±(tipR+18),0)         解压机：喷口 mdBeamEndpoints(k) → 洞面
     *   半径   中央：{@link HostFxGeometry#PILLAR_OUTER_R0 6.20} → 3.80      解压机：{@link HostFxGeometry#MD_BEAM_OUTER_R0 1.25} → 1.55（更细）
     *   颜色   中央：{@link HostFxGeometry#jetGlowRgb()}        解压机：{@link HostFxGeometry#mdBeamGlowRgb()}（另一个可配开关）
     * </pre>
     *
     * <p>⚠️ 调用方必须已经套好<b>环心枢轴 + 环自转</b>（这 4 根与解压机一起转），并且
     * {@code camLocalX/Y/Z} 必须是 <b>同一帧（自转后）</b>的相机坐标 —— 否则 shader 里的
     * {@code CameraPosition} 会差一个自转角，光柱的可见宽度会随自转"呼吸"。
     *
     * @param tipR         黑洞表面半径（格）
     * @param seconds      世界时间（秒）；{@code tick = seconds × 20} 喂给 UV 滚动
     * @param camLocalX/Y/Z 相机在<b>自转后的环心本地帧</b>里的位置
     * @param glowRgb      外层发光颜色（{@link HostFxGeometry#mdBeamGlowRgb()}，可配）
     * @return 成功提交的遍数（0 = 着色器没起来，调用方应回落到 {@link HostFxGeometry#buildMdBeamQuads}）
     */
    public static int drawMdBeams(PoseStack poseStack, float tipR, float seconds,
                                  float camLocalX, float camLocalY, float camLocalZ, float[] glowRgb) {
        lastMdPasses = 0;
        final ShaderInstance s = shader();
        if (s == null) {
            return 0;
        }
        final float tick = seconds * 20.0F;
        for (int k = 0; k < HostFxGeometry.MD_COUNT; k++) {
            final float[] e = HostFxGeometry.mdBeamEndpoints(k, tipR);
            if (!drawBeam(poseStack, s, e[0], e[1], e[2], e[3], e[4], e[5],
                    HostFxGeometry.MD_BEAM_OUTER_R0, HostFxGeometry.MD_BEAM_OUTER_R1,
                    HostFxGeometry.MD_BEAM_INNER_SCALE, glowRgb,
                    HostFxGeometry.MD_BEAM_OUTER_ALPHA, HostFxGeometry.MD_BEAM_INNER_ALPHA,
                    tick, camLocalX, camLocalY, camLocalZ)) {
                lastMdPasses = 0;
                return 0;
            }
            lastMdPasses += 2;
        }
        return lastMdPasses;
    }

    /**
     * 一根光柱的<b>一整次</b> {@code renderCurrentSegments}（soft + intense 两遍）。
     *
     * @param innerScale 内芯半径倍数（伪神 {@code LightHunterSpaceStationBeamRenderer.INNER_RADIUS_SCALE = 0.45}）
     */
    private static boolean drawBeam(PoseStack poseStack, ShaderInstance shader,
                                    float ax, float ay, float az, float bx, float by, float bz,
                                    float outerR0, float outerR1, float innerScale, float[] color,
                                    float outerAlpha, float innerAlpha,
                                    float tick, float camX, float camY, float camZ) {
        final float dx = bx - ax;
        final float dy = by - ay;
        final float dz = bz - az;
        final float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-3F) {
            return true;                                  // 退化：什么也不画，但不算失败
        }
        final Quaternionf rotation = new Quaternionf()
                .rotationTo(0.0F, 0.0F, 1.0F, dx / length, dy / length, dz / length);
        // 相机换算到"这根光束的本地帧"（= 伪神 updateCameraPositionInLocalBeamSpace）：
        //   relative = cam − from；relative = conj(rotation) × relative
        final Vector3f camLocal = new Vector3f(camX - ax, camY - ay, camZ - az);
        new Quaternionf(rotation).conjugate().transform(camLocal);
        final float cameraAngle = (float) Math.atan2(camLocal.y, camLocal.x);

        poseStack.pushPose();
        try {
            poseStack.translate(ax, ay, az);
            poseStack.mulPose(rotation);
            final Matrix4f model = poseStack.last().pose();

            // ---------- 状态（逐行照抄 AntichristBeamRenderer.renderCurrentSegments）----------
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShaderTexture(0, TEXTURE);
            try {
                // ① soft 遍：外层发光，颜色 = 伪神的 BeamColor
                setUniform(shader, "CameraPosition", camLocal.x, camLocal.y, camLocal.z);
                setUniform(shader, "BeamColor", color[0], color[1], color[2]);
                setUniform(shader, "Intensity", INTENSITY_SOFT);
                upload(shader, outerR0, 0.0F, outerAlpha, outerR1, length, outerAlpha,
                        cameraAngle, tick);
                buffer().bind();
                buffer().drawWithShader(model, RenderSystem.getProjectionMatrix(), shader);

                // ② intense 遍：内芯，半径 ×innerScale，颜色固定白（伪神传 1,1,1）
                setUniform(shader, "BeamColor", 1.0F, 1.0F, 1.0F);
                setUniform(shader, "Intensity", INTENSITY_INTENSE);
                upload(shader, outerR0 * innerScale, 0.0F, innerAlpha,
                        outerR1 * innerScale, length, innerAlpha, cameraAngle, tick);
                buffer().bind();
                buffer().drawWithShader(model, RenderSystem.getProjectionMatrix(), shader);
            } finally {
                VertexBuffer.unbind();
                RenderSystem.enableCull();
                RenderSystem.depthMask(true);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }
        } finally {
            poseStack.popPose();
        }
        return true;
    }

    private static void setUniform(ShaderInstance shader, String name, float... values) {
        final Uniform u = shader.getUniform(name);
        if (u == null) {
            return;                                        // 被 GLSL 优化掉 / 名字写错：不炸，但日志里能看出来
        }
        switch (values.length) {
            case 1 -> u.set(values[0]);
            case 2 -> u.set(values[0], values[1]);
            case 3 -> u.set(values[0], values[1], values[2]);
            default -> u.set(values[0], values[1], values[2], values[3]);
        }
    }

    /** 建顶点并上传（= 伪神 {@code uploadBeamBuffer}）。 */
    private static void upload(ShaderInstance shader, float r0, float z0, float a0,
                               float r1, float z1, float a1, float cameraAngle, float tick) {
        final BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(Mode.TRIANGLES, BEAM_VERTEX_FORMAT);
        writeBeamVertices(builder, r0, z0, a0, r1, z1, a1, cameraAngle, tick);
        buffer().bind();
        buffer().upload(builder.end());
    }

    /**
     * 16 个四边形（{@code Mode.TRIANGLES}，每片 6 个顶点）—— 逐行对应伪神
     * {@code writeBeamVertices} 的 {@code segmentId == 0} 那一轮。
     *
     * <p>顶点顺序（原实现）：{@code (end=1,local 0), (end=0,1), (end=0,2), (end=0,3),
     * (end=1,4), (end=1,5)} ⇒ 两个三角形拼成"角 0 → 角 1、段 0 → 段 1"的一片。
     */
    private static void writeBeamVertices(BufferBuilder builder,
                                          float r0, float z0, float a0,
                                          float r1, float z1, float a1,
                                          float cameraAngle, float tick) {
        for (int quadId = 0; quadId < QUADS; quadId++) {
            addBeamVertex(builder, r1, z1, a1, quadId, 0, cameraAngle, tick);
            addBeamVertex(builder, r0, z0, a0, quadId, 1, cameraAngle, tick);
            addBeamVertex(builder, r0, z0, a0, quadId, 2, cameraAngle, tick);
            addBeamVertex(builder, r0, z0, a0, quadId, 3, cameraAngle, tick);
            addBeamVertex(builder, r1, z1, a1, quadId, 4, cameraAngle, tick);
            addBeamVertex(builder, r1, z1, a1, quadId, 5, cameraAngle, tick);
        }
    }

    /** 一个顶点（= 伪神 {@code addBeamVertex}，连 UV 公式都逐字相同）。 */
    private static void addBeamVertex(BufferBuilder builder, float radius, float offset, float alpha,
                                      int quadId, int localId, float cameraAngle, float tick) {
        final float angle = getVertexAngle(quadId, localId, cameraAngle);
        final float u = offset / 256.0F + tick / 240.0F;
        final float v = angle / (float) (Math.PI * 2.0D) + u / 3.0F + tick / 240.0F;
        builder.vertex(Math.cos(angle) * radius, Math.sin(angle) * radius, offset)
                .uv(u, v)
                .overlayCoords(packAlpha(alpha))
                .endVertex();
    }

    /** {@code clamp(round(alpha × 10000), -32768, 32767) & 0xFFFF}（伪神 {@code packAlpha}）。 */
    private static int packAlpha(float alpha) {
        final int v = Math.round(alpha * ALPHA_PACK_SCALE);
        return Math.max(-32768, Math.min(32767, v)) & 0xFFFF;
    }

    /** 每片的起始角 —— 加 {@code cameraAngle − π/2} ⇒ 第 0 片永远朝向相机（伪神 {@code getVertexAngle}）。 */
    private static float getVertexAngle(int quadId, int localId, float cameraAngle) {
        return (float) Math.PI
                * (quadId + ((localId > 1 && localId < 5) ? 0 : 1)) / 16.0F
                + (cameraAngle - (float) (Math.PI / 2.0D));
    }
}
