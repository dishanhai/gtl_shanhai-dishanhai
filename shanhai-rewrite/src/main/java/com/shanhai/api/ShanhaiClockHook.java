package com.shanhai.api;

/**
 * 本体侧<b>唯一</b>的「时钟渲染闸门」接缝（🔴 零 Minecraft / 零 Forge 依赖，别加 import）。
 *
 * <h2>为什么本体需要这个类（拆分设计 §1.4）</h2>
 * 世界时钟整块搬进扩展（{@code shanhai_add}）之后，本体的两个渲染器仍然需要问一句
 * "现在暂停了吗 / 这一帧的 α 该用多少"：
 * <ul>
 *   <li>{@code client\renderer\machine\AbstractRingRenderer}（原始终焉引擎的环/恒星动画）；</li>
 *   <li>{@code client\renderer\machine\PrimordialOmegaEngineRenderer}（中子星球的直调）。</li>
 * </ul>
 * 但它们<b>不许</b> import 扩展的任何类（只装本体时会 {@code NoClassDefFoundError}）。
 * 于是本体只认这个接口，扩展在自己的 {@code @Mod} 构造器里 {@link #install} 实现
 * —— 与 {@link ShanhaiAddHolder} 同一个套路，只是这里只谈"两个浮点数"。
 *
 * <h2>没装扩展时的行为：<b>原值透传</b></h2>
 * {@code impl == null} ⇒ {@link #smoothTick(float)} 返回入参、{@link #partialTick(float)} 返回入参。
 * 时钟在扩展里，没装扩展就永远不可能处于暂停 ⇒ 透传与"时钟恒 LIVE"逐字等价
 * （"未暂停时"那条链在两侧都是原值：{@code smoothTick} 返回 {@code liveTick}、
 *  {@code partialTick} 返回 {@code vanilla}）。
 *
 * <h2>语义（由扩展实现，本体不解释）</h2>
 * <pre>
 *   smoothTick(live)     暂停 ⇒ 返回"锁住的那一帧的值"（不是 0：0 会让动画跳回原点）；未暂停 ⇒ live
 *   partialTick(vanilla) 暂停 ⇒ 0；未暂停 ⇒ vanilla
 * </pre>
 * 🔴 这两个口子都很<b>窄</b>：只作用于我们自己的机器环/恒星特效，绝不改
 * {@code Minecraft#getFrameTime} / 全局 {@code Timer#partialTick}（用户红线：鼠标转视角必须正常）。
 *
 * <h2>三条硬约束（与 {@link ShanhaiAddHolder} 一致）</h2>
 * <ol>
 *   <li><b>任何方法都不抛异常</b>：缺装是正常状态，不是错误。</li>
 *   <li><b>必须 volatile</b>：install 发生在 mod 加载线程，读发生在渲染线程。</li>
 *   <li>实现自己出问题也不许把渲染带崩 —— 实现方负责 try/catch（见扩展侧的实现类）。</li>
 * </ol>
 */
public final class ShanhaiClockHook {

    /** 扩展提供的实现。{@code null} = 没装扩展 ⇒ 原值透传。 */
    private static volatile Impl impl;

    private ShanhaiClockHook() {}

    /**
     * 时钟语义的实现面。
     *
     * <p>两个方法都<b>必须是纯函数式的</b>（同一个入参可以每帧调多次）且必须自己能兜住异常。
     */
    public interface Impl {

        /**
         * 连续渲染时钟（gtlcore {@code GlobalRenderClock#getSmoothTick} 那条墙钟链）。
         *
         * @param live 本帧真实算出来的值
         * @return 应当交给渲染器的值（暂停 ⇒ 沿用锁住的那一帧；未暂停 ⇒ {@code live}）
         */
        float smoothTick(float live);

        /**
         * 世界动画用的渲染插值量 α。
         *
         * @param vanilla 原版这一帧的 α
         * @return 应当交给渲染器的值（暂停 ⇒ {@code 0}；未暂停 ⇒ {@code vanilla}）
         */
        float partialTick(float vanilla);
    }

    /**
     * 注册实现（由扩展的 {@code @Mod} 构造器调用；重复调用以最后一次为准）。
     *
     * @param i 实现；{@code null} 等价于 {@link #clear()}
     */
    public static void install(Impl i) {
        impl = i;
    }

    /** 断开（不抛异常，重复调用无副作用）。 */
    public static void clear() {
        impl = null;
    }

    /** {@code true} = 现在有位实现（只表示"装上了"，不表示"暂停了"）。 */
    public static boolean present() {
        return impl != null;
    }

    /**
     * 冻结连续渲染时钟。没装扩展时<b>原值透传</b>。
     *
     * @param live 本帧真实值
     * @return 扩展给的值；没装扩展时原样返回 {@code live}
     */
    public static float smoothTick(float live) {
        final Impl i = impl;
        return i == null ? live : i.smoothTick(live);
    }

    /**
     * 取"世界动画用的 α"。没装扩展时<b>原值透传</b>。
     *
     * @param vanilla 原版 α
     * @return 扩展给的值；没装扩展时原样返回 {@code vanilla}
     */
    public static float partialTick(float vanilla) {
        final Impl i = impl;
        return i == null ? vanilla : i.partialTick(vanilla);
    }
}
