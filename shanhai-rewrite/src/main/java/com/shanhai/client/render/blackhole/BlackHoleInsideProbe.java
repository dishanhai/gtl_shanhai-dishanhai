package com.shanhai.client.render.blackhole;

import com.shanhai.ShanhaiMod;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 山海重构 · 黑洞【洞内】这条链的<b>分阶段一次性探针</b>（任务书第 1 条）。
 *
 * <h2>为什么要有它（2026-10-25 实机的教训）</h2>
 * 上一轮日志里洞内帧只有一行判据：
 * <pre>
 *   [SHANHAI-BH] path=inside-overlay … inside=true … parts=inside draw=yes level=1 reason=none
 * </pre>
 * 那一行说"画了"，屏幕上却是空的。真相是 <b>洞内着色器在"必需 uniform"检查那一步就被判死</b>
 * （{@code fallback=2 reason=inside_uniform_missing:uScreenSize}），而那一行
 * 把 {@code drewInside} 写成了<b>字面量 {@code true}</b> ⇒ 日志与画面彻底脱钩。
 *
 * <h2>这一版怎么做到"一进洞就能唯一确定断点"</h2>
 * 洞内那条链被切成 5 段，<b>每段一行、每会话只打一次</b>（{@link #logOnce}），
 * 并且顺序是固定的：{@code program → uniform → copy → quad → draw}。
 * 任何一段失败都会立刻 {@link #markBreak} 记下<b>第一个</b>断点，
 * 之后每秒那行读数末尾会带 {@code inside_break=<词>}、{@code inside_reached=<段>}，
 * 所以<b>不需要翻上下文</b>：看一行就知道断在哪。
 *
 * <h2>固定词表（日志可断言）</h2>
 * <pre>
 *   inside_probe stage=program id=&lt;n&gt; link=ok|fail fmt=… vert=… frag=…
 *   inside_probe stage=uniform n=&lt;present&gt;/&lt;total&gt; missing=[a,b] present=[…]
 *   inside_probe stage=copy src=[x,y wxh] tex=&lt;id&gt; side=&lt;n&gt; fb_r=&lt;n&gt; fb_d=&lt;n&gt; result=ok|fail gl=&lt;n&gt;
 *   inside_probe stage=quad declare=… actual=… vtx=4 endbatch=ok|fail
 *   inside_probe stage=draw gl=&lt;n&gt; result=ok|fail
 *   inside_break at=&lt;program|uniform|copy|quad|draw_gl|shader&gt; detail=…
 * </pre>
 *
 * <p>⚠️ 本类<b>只读</b>：不改任何渲染状态、不碰 GL、不吞异常。
 * ⚠️ 线程：只在渲染线程（== 客户端主线程）调用；{@code LOGGED} 仍然加锁，
 * 因为 {@code /shanhai bh reload} 会在命令线程上清它。
 */
public final class BlackHoleInsideProbe {

    // ---- 5 个阶段名（与任务书 1..5 逐条对应）----
    /** ① 全屏四边形的 RenderType / blend / 深度状态（声明值 + 设状态那一刻的实际 GL 值）。 */
    public static final String STAGE_QUAD = "quad";
    /** ② 背景拷贝（源矩形 / 目标纹理 / 绑定中的 FBO / glGetError）。 */
    public static final String STAGE_COPY = "copy";
    /** ③ inside shader 编译+链接结果（成功也打，带 program id）。 */
    public static final String STAGE_PROGRAM = "program";
    /** ④ 必需 uniform 是否全部取到（缺哪个点名）。 */
    public static final String STAGE_UNIFORM = "uniform";
    /** ⑤ 绘制后的 glGetError。 */
    public static final String STAGE_DRAW = "draw";

    // ---- 断点词表（进读数行的 inside_break=）----
    /** 没断（洞内这一帧五段全过）。 */
    public static final String BREAK_NONE = "none";
    /** 着色器 new/链接/编译失败 ⇒ 连 program 都没有。 */
    public static final String BREAK_PROGRAM = "program";
    /** 必需 uniform 一个都取不到（现在不是致命，只记一次；见 {@code setInsideUniforms}）。 */
    public static final String BREAK_UNIFORM = "uniform";
    /** 背景拷贝失败 / 主渲染目标尺寸对不上 / 纹理分配失败 ⇒ 没有可采样的源。 */
    public static final String BREAK_COPY = "copy";
    /** 四边形提交那段抛异常（`endBatch` 没跑完）。 */
    public static final String BREAK_QUAD = "quad";
    /** 提交了但 GL 报错 / 绘制后立刻异常。 */
    public static final String BREAK_DRAW = "draw_gl";
    /** 洞内绘制抛过异常（本会话不再试，见 {@code insideBroken}）。 */
    public static final String BREAK_SHADER = "shader";

    /** 已经打过的阶段（key 可以是阶段名，也可以是细分名，如 {@code quad_setup}）。 */
    private static final Set<String> LOGGED = new LinkedHashSet<>();

    /** 本会话<b>第一个</b>断点（一旦置上就不再改 —— 后面的失败都是它的后果）。 */
    private static volatile String breakAt = BREAK_NONE;
    private static volatile String breakDetail = "";
    /** 这一帧真正走到的<b>最远</b>阶段（每帧重置，读数行里就是它）。 */
    private static volatile String reached = BREAK_NONE;

    private BlackHoleInsideProbe() {}

    /**
     * 打一行阶段日志，<b>同一个 key 每会话只打一次</b>（这就是"节流"）。
     *
     * @param key    阶段名或细分名（固定词表，日志可断言）
     * @param detail 该阶段的实测数据（{@code k=v} 用空格分隔；不要换行）
     * @return 这次是否真的打出去了
     */
    public static boolean logOnce(String key, String detail) {
        synchronized (LOGGED) {
            if (!LOGGED.add(key)) {
                return false;
            }
        }
        ShanhaiMod.LOGGER.info("{} inside_probe stage={} {} note=一次性（每阶段只打一行，看这一行就能定位断点）",
                BlackHoleFallbackGuard.PREFIX, key, detail == null ? "" : detail);
        return true;
    }

    /** 标记"这一帧走到了这一段"（只在比已记录的更靠后时更新）。 */
    public static void reach(String stage) {
        reached = stage;
    }

    /** 这个 key 是否已经打过一行（给"要不要再做一次实测"的判断用）。 */
    public static boolean isLogged(String key) {
        synchronized (LOGGED) {
            return LOGGED.contains(key);
        }
    }

    /**
     * 记下断点（<b>第一个说了算</b>，后面的不再覆盖）。
     *
     * <p>为什么只认第一个：一旦 program 拿不到，后面的 copy/draw 必然也失败，
     * 若允许覆盖，读数行最后会显示"最后一个失败"而不是"真正的源头"。
     */
    public static void markBreak(String stage, String detail) {
        if (!BREAK_NONE.equals(breakAt)) {
            return;
        }
        breakAt = stage;
        breakDetail = detail == null ? "" : BlackHoleFallbackGuard.sanitize(detail);
        ShanhaiMod.LOGGER.warn("{} inside_break at={} detail={} reached={} "
                        + "note=本会话第一个断点（后面的失败都是它的后果，不要被覆盖）",
                BlackHoleFallbackGuard.PREFIX, breakAt, breakDetail, reached);
    }

    /** 读数行里的 {@code inside_break=}（{@link #BREAK_NONE} = 五段全过）。 */
    public static String breakToken() {
        return breakAt;
    }

    /** 读数行里的 {@code inside_reached=}（这一帧最远走到哪一段）。 */
    public static String reachedToken() {
        return reached;
    }

    /** 断点细节（命令回话用；没断过是空串）。 */
    public static String breakDetail() {
        return breakDetail;
    }

    /** 给 {@code /shanhai bh status} 的一行读数。 */
    public static String statusLine() {
        final String[] logged;
        synchronized (LOGGED) {
            logged = LOGGED.toArray(new String[0]);
        }
        return "inside_probe break=" + breakAt
                + " reached=" + reached
                + " detail=" + (breakDetail.isEmpty() ? "none" : breakDetail)
                + " logged=" + java.util.Arrays.toString(logged);
    }

    /**
     * 清空（{@code /shanhai bh reload} / 资源重载时调）。
     *
     * <p>为什么要清：重载之后着色器会重新加载，如果旧断点还留着，
     * 读数行会一直显示上一轮的病灶 —— 而"这一轮到底断没断"才是要看的。
     */
    public static void reset() {
        synchronized (LOGGED) {
            LOGGED.clear();
        }
        breakAt = BREAK_NONE;
        breakDetail = "";
        reached = BREAK_NONE;
    }
}
