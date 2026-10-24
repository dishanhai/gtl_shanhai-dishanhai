package com.shanhai.client.render.blackhole;

import net.minecraftforge.fml.ModList;

/**
 * 山海重构 · 黑洞渲染件的 <b>Oculus / Iris 状态探测</b>（纯反射，零编译期依赖、零 GL）。
 *
 * <h2>0. 🔴 为什么必须重写（2026-10-24 傍晚用户实测暴露的真 bug）</h2>
 * 旧实现（{@code BlackHoleLensRenderer#irisShaderpackActive}）只做了一件事：
 * <pre>
 *   Iris.getPipelineManager().getPipeline()  →  Optional.isPresent()  ?  true  :  false
 * </pre>
 * 用户原话：「<b>他说已经关掉光影，日志仍报 true</b>」。日志证据（{@code latest.log}，12055 行处起）：
 * <pre>
 *   [SHANHAI-BH] boot … shaderpack=true
 *   [SHANHAI-BH] fallback=2 reason=iris_shaderpack_active
 * </pre>
 * 而同一时刻的同机配置文件 {@code config/oculus.properties} 写的是
 * {@code enableShaders=false}（{@code shaderPack=iterationT 3.2.0.zip} 只是"上次选过的包名"）。
 *
 * <h2>1. 根因（逐层字节码取证，不是推断）</h2>
 * 对 {@code oculus-mc1.20.1-1.8.0.jar}（sha256 见报告）反编译 / {@code javap -p -c}：
 * <ol>
 *   <li>{@code PipelineManager.getPipeline()} 的实现是
 *       <pre>0: aload_0 / 1: getfield pipeline / 4: invokestatic Optional.ofNullable / 7: areturn</pre>
 *       ⇒ <b>就是 {@code Optional.ofNullable(pipeline)}，没有任何类型判断</b>。</li>
 *   <li>{@code Iris.createPipeline(NamespacedId)}：
 *       <pre>0: getstatic currentPack / 3: ifnonnull 14
 *        6: new VanillaRenderingPipeline / … / 13: areturn      ← currentPack == null 时
 *       14: new IrisRenderingPipeline(programSet) / 30: areturn  ← 真跑光影时</pre>
 *       ⇒ <b>光影关掉时 {@code pipeline} 仍然非 null</b>（是个 {@code VanillaRenderingPipeline}）。</li>
 * </ol>
 * ⇒ {@code getPipeline().isPresent()} 恒为 <b>true</b>（只要进过一次世界），
 * 与"光影到底开没开"<b>完全无关</b>。这就是那行骗人的 {@code shaderpack=true}。
 *
 * <h2>2. 正确的判据（Oculus 1.8.0 的公开 API，逐条对上）</h2>
 * <table border="1">
 *   <caption>三个能问到的量</caption>
 *   <tr><th>问题</th><th>API</th><th>实现（字节码原文）</th></tr>
 *   <tr><td>装了 Oculus 吗</td><td>{@code ModList.isLoaded("oculus")}</td><td>—</td></tr>
 *   <tr><td><b>光影此刻真的在生效吗</b>（= 世界被画进 Iris 自己的缓冲）</td>
 *       <td>{@code IrisApi.getInstance().isShaderPackInUse()}</td>
 *       <td>{@code getPipelineNullable()} 非 null <b>且不是</b> {@code VanillaRenderingPipeline}</td></tr>
 *   <tr><td>用户开关是 on 还是 off</td>
 *       <td>{@code IrisApi.getInstance().getConfig().areShadersEnabled()}</td>
 *       <td>{@code Iris.getIrisConfig().areShadersEnabled()} ⇒ 读 {@code oculus.properties}</td></tr>
 * </table>
 * <p>等价写法（少一层 API 接口，多一层内部类依赖）：
 * {@code Iris.isPackInUseQuick()} 的字节码是
 * {@code getPipelineNullable() instanceof IrisRenderingPipeline}，与上表第二行<b>同一个语义</b>。
 *
 * <p>本类把三条都读出来，但<b>行为只由 {@link #pipelineActive()} 决定</b>
 * （它的白话就是"这时主渲染目标里没有本帧世界"）。{@link #shadersEnabled()} 只进日志，
 * 用来回答"是没开光影、还是开了但没生效"。
 *
 * <h2>3. 为什么全是反射</h2>
 * Oculus 在 {@code libs/} 里只是 {@code implementation fileTree}（编译期可见），
 * 但游戏里它<b>可以被卸掉</b>。一处 {@code import net.irisshaders…} 就会在没装 Oculus 的
 * 整合包里变成 {@code NoClassDefFoundError}（而且是在渲染线程上）。
 * 所以这里全部 {@code Class.forName} + 缓存，找不到就当"没有光影"。
 */
public final class BlackHoleIris {

    private BlackHoleIris() {}

    /** 上一次探测的结论（只在渲染线程写）。 */
    private static boolean modLoaded;
    private static boolean shadersEnabled;
    private static boolean pipelineActive;
    /** 哪一条 API 给出了结论（日志用；{@code none} = 一个都没问到）。 */
    private static String source = "none";
    /** 上一次探测的帧号（{@value #RECHECK_FRAMES} 帧重查一次：玩家可能在游戏里按 L 切光影）。 */
    private static int lastCheckFrame = -1000;
    /** 重查间隔（帧）：约 0.33 s @60fps，足够跟手，又不至于每帧反射。 */
    public static final int RECHECK_FRAMES = 20;

    private static boolean probedOnce;
    private static boolean reflectionBroken;

    /** 装了 Oculus / Iris 吗。 */
    public static boolean modLoaded() {
        return modLoaded;
    }

    /** 用户开关（{@code config/oculus.properties} 的 {@code enableShaders}）。 */
    public static boolean shadersEnabled() {
        return shadersEnabled;
    }

    /**
     * <b>光影此刻真的在生效</b>：世界被 Iris 画进它自己的缓冲，主渲染目标里没有本帧世界。
     *
     * <p>⇒ 这个量为 true 时，深度缓冲也不可信（见 {@code BlackHoleLensRenderer} 的 {@code depth=} 口径）。
     */
    public static boolean pipelineActive() {
        return pipelineActive;
    }

    /** 结论来源（{@code irisapi} / {@code irisquick} / {@code pipeline} / {@code none}）。 */
    public static String source() {
        return source;
    }

    /** 反射整条断掉了吗（那种情况下上表三个量都是 false，日志里要能看出来）。 */
    public static boolean reflectionBroken() {
        return reflectionBroken;
    }

    /**
     * 每帧调（内部按 {@link #RECHECK_FRAMES} 节流）；返回 {@link #pipelineActive()}。
     *
     * @param frame 渲染帧计数（调用方的自增计数，只用来判"该不该重查"）
     */
    public static boolean update(int frame) {
        if (!probedOnce || frame - lastCheckFrame >= RECHECK_FRAMES || frame < lastCheckFrame) {
            lastCheckFrame = frame;
            probedOnce = true;
            probe();
        }
        return pipelineActive;
    }

    /** 无条件重探（资源重载 / {@code /shanhai bh reload} 时用）。 */
    public static void invalidate() {
        probedOnce = false;
        lastCheckFrame = -1000;
    }

    private static void probe() {
        try {
            modLoaded = ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
        } catch (Throwable t) {
            modLoaded = false;
        }
        if (!modLoaded) {
            shadersEnabled = false;
            pipelineActive = false;
            source = "none";
            return;
        }
        // ① 首选公开 API：IrisApi.getInstance().isShaderPackInUse() / getConfig().areShadersEnabled()
        if (readViaIrisApi()) {
            return;
        }
        // ② 退到 Iris 自己的静态方法（同一语义，Oculus 内部用）
        if (readViaIrisQuick()) {
            return;
        }
        // ③ 最后才用"旧的那条"——但这次<b>做了类型判断</b>，不再被 VanillaRenderingPipeline 骗
        if (readViaPipelineType()) {
            return;
        }
        reflectionBroken = true;
        shadersEnabled = false;
        pipelineActive = false;
        source = "none";
    }

    /** ① {@code net.irisshaders.iris.api.v0.IrisApi}（Oculus 承诺稳定的对外接口）。 */
    private static boolean readViaIrisApi() {
        try {
            final Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            final Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return false;
            }
            final Object inUse = apiClass.getMethod("isShaderPackInUse").invoke(api);
            pipelineActive = inUse instanceof Boolean b && b;
            // 配置接口：areShadersEnabled()
            try {
                final Object cfg = apiClass.getMethod("getConfig").invoke(api);
                if (cfg != null) {
                    final Object en = cfg.getClass().getMethod("areShadersEnabled").invoke(cfg);
                    shadersEnabled = en instanceof Boolean b2 && b2;
                }
            } catch (Throwable ignored) {
                // 老版本没有 getConfig：保留 pipelineActive 的结论
                shadersEnabled = pipelineActive;
            }
            source = "irisapi";
            reflectionBroken = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ② {@code Iris.isPackInUseQuick()} = {@code getPipelineNullable() instanceof IrisRenderingPipeline}。 */
    private static boolean readViaIrisQuick() {
        try {
            final Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            final Object quick = iris.getMethod("isPackInUseQuick").invoke(null);
            pipelineActive = quick instanceof Boolean b && b;
            shadersEnabled = pipelineActive || readShadersEnabledFromConfig(iris);
            source = "irisquick";
            reflectionBroken = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ③ 拿 pipeline 对象<b>判类名</b>（旧路径 + 类型判断；等价于 ②，但语义自解释）。 */
    private static boolean readViaPipelineType() {
        try {
            final Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            final Object mgr = iris.getMethod("getPipelineManager").invoke(null);
            if (mgr == null) {
                return false;
            }
            final Object pipeline = mgr.getClass().getMethod("getPipelineNullable").invoke(mgr);
            pipelineActive = pipeline != null
                    && !"VanillaRenderingPipeline".equals(pipeline.getClass().getSimpleName());
            shadersEnabled = pipelineActive || readShadersEnabledFromConfig(iris);
            source = "pipeline";
            reflectionBroken = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code Iris.getIrisConfig().areShadersEnabled()}（{@code IrisConfig} 的公开方法）。 */
    private static boolean readShadersEnabledFromConfig(Class<?> irisClass) {
        try {
            final Object cfg = irisClass.getMethod("getIrisConfig").invoke(null);
            if (cfg == null) {
                return false;
            }
            final Object en = cfg.getClass().getMethod("areShadersEnabled").invoke(cfg);
            return en instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一行读数（{@code boot}/切换时打；不是每帧）。 */
    public static String describe() {
        return "mod=" + modLoaded + " shadersEnabled=" + shadersEnabled
                + " pipelineActive=" + pipelineActive + " src=" + source
                + (reflectionBroken ? " reflectionBroken=true" : "");
    }
}
