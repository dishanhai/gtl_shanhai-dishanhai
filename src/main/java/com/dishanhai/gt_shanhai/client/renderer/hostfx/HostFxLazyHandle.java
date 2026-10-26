package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import java.util.function.Supplier;

/**
 * 归墟视界枢核 · <b>懒建资源的持有器</b>（"首帧建、资源重载后作废重建"的那点逻辑）。
 *
 * <h2>为什么它单独一个类（而且是<b>零 MC 依赖</b>）</h2>
 * 光柱的 {@code ShaderInstance} 是"第一次真要画的时候自己 new"的（见 {@link HostFxPillarBeam} 类注释 §注册方式）。
 * 这带来一个必须处理的后果：<b>{@code F3+T} 资源重载时没人替我们重建它</b>，于是改完 shader 不重启游戏看不到效果。
 * 处置 = 资源重载时把实例<b>作废</b>，下一次 {@link #get()} 重新建。
 *
 * <p>🔴 关键点：这套"作废 / 重建 / 身份变没变"的语义<b>不许活在带 GL 的类里</b> ——
 * 那样离线探针（无 GL、无 MC 资源）根本验不了，只能靠"看代码觉得对"。
 * 所以把它抽到这里（只依赖 {@link Supplier}），探针可以用<b>替身对象</b>把
 * 「不重载 ⇒ 身份不变」「重载 ⇒ 身份变了」逐条跑出来、贴两个 identity hash 当判据。
 *
 * <h2>三种返回值语义（调用方要分清）</h2>
 * <ul>
 *   <li>{@code get()} 返回非 null ⇒ 拿到实例（可能刚建、也可能是上一次那个）；</li>
 *   <li>{@code get()} 返回 null 且 {@link #failed()} = false ⇒ <b>还没就绪</b>（例如资源管理器还没起来），
 *       下次再试，<b>不算失败</b>；</li>
 *   <li>{@code get()} 返回 null 且 {@link #failed()} = true ⇒ 工厂抛过异常（编译不过 / 缺文件），
 *       <b>不再重试</b>（重试只会每帧抛一次）；{@link #invalidate()} 之后才会重新给一次机会。</li>
 * </ul>
 *
 * <p>⚠️ 本类<b>不</b>负责 close 旧实例：{@code ShaderInstance.close()} 是 GL 操作，
 * 必须留在渲染线程（{@link HostFxPillarBeam#onResourceReload()} 只作废、把旧实例交给下一次 draw 去 close）。
 *
 * <h2>线程</h2>
 * {@code F3+T} 的重载与渲染在 MC 里是同一条线程（客户端主线程 = 渲染线程），
 * 所以这里不做同步；但"作废"只置引用、close 延后到 draw，即使将来换线程也不会在错误线程碰 GL。
 */
public final class HostFxLazyHandle<T> {

    /** 真正建实例的工厂（生产代码里就是 {@code () -> new ShaderInstance(...)}）。 */
    private final Supplier<T> factory;

    /** 当前实例（null = 还没建 / 已被作废 / 建失败）。 */
    private T value;
    /** 工厂抛过异常 ⇒ 不再自动重试（防每帧抛一次）。 */
    private boolean failed;
    /** 最近一次工厂抛的异常（日志用）。 */
    private Throwable lastError;
    /** 成功建过几次（日志/判据：重载后应 +1）。 */
    private int builds;

    public HostFxLazyHandle(Supplier<T> factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory 不能为 null");
        }
        this.factory = factory;
    }

    /**
     * 取当前实例，必要时新建。<b>每帧都会调用</b> ⇒ 命中时只有一次判空 + 一次返回。
     *
     * @return null = 还没就绪（{@link #failed()} false）或建失败（{@link #failed()} true）
     */
    public T get() {
        if (value != null) {
            return value;
        }
        if (failed) {
            return null;
        }
        final T made;
        try {
            made = factory.get();
        } catch (Throwable t) {
            failed = true;
            lastError = t;
            return null;
        }
        if (made == null) {
            return null;                     // 还没就绪：不标记失败 ⇒ 下次再试
        }
        value = made;
        builds++;
        return value;
    }

    /**
     * <b>作废</b>当前实例（资源重载时调）。下一次 {@link #get()} 会重新建。
     *
     * @return 被作废的旧实例（null = 本来就没有）；<b>调用方负责在渲染线程 close 它</b>
     */
    public T invalidate() {
        final T old = value;
        value = null;
        failed = false;                      // 重载后给工厂一次重新失败/成功的机会
        lastError = null;
        return old;
    }

    /** 当前实例（不作废、不新建）。 */
    public T peek() {
        return value;
    }

    /** 成功建过几次（判据：重载后 +1）。 */
    public int builds() {
        return builds;
    }

    /** 工厂是否抛过异常（{@link #invalidate()} 后复位）。 */
    public boolean failed() {
        return failed;
    }

    /** 工厂最近一次抛的异常（null = 没抛过）。 */
    public Throwable lastError() {
        return lastError;
    }

    /** 当前实例的 identity hash（null ⇒ 0）。<b>探针的判据就是比这个数</b>。 */
    public int identityHash() {
        return value == null ? 0 : System.identityHashCode(value);
    }
}
