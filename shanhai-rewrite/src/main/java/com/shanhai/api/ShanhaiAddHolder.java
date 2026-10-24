package com.shanhai.api;

/**
 * 「扩展在不在」的<b>唯一</b>持有处（{@code volatile} 静态引用）。
 *
 * <h2>三个方法就是全部形态</h2>
 * <pre>
 *   install(impl)  add 在 {@code @Mod} 构造器里注册实现（唯一装配点）
 *   clear()        add 卸载 / 想显式断开时用（缺省不需要调）
 *   optional()     本体取用：拿到 {@code null} = 没装 add
 * </pre>
 *
 * <h2>🔴 三条硬约束</h2>
 * <ol>
 *   <li><b>任何方法都不许抛异常</b>（"add 卸载/缺装"是正常状态，不是错误）：
 *       读写一个 {@code volatile} 引用不可能失败；{@code install(null)} 等价于 {@link #clear()}。</li>
 *   <li><b>必须 volatile</b>：add 的 {@code @Mod} 构造器跑在 mod 加载线程，本体的命令/事件在服务端线程，
 *       没有 {@code volatile} 就只是"碰巧能看见"。</li>
 *   <li><b>这里不缓存 {@code ModList.isLoaded} 之类的判据</b>：只有"实现对象在不在"这一个事实，
 *       避免两份状态互相打架。</li>
 * </ol>
 *
 * <p>本类<b>不写日志</b>：它要能被纯 JDK 值表直接驱动（零 MC、零 Forge、零 slf4j）。
 * 装配成功的机器可判痕迹有两条，都在调用方：
 * add 侧 {@code [SHANHAI-ADD] bridge installed version=…}；本体侧每次 purge 的
 * {@code purge_via=add|simple reason=…}。
 */
public final class ShanhaiAddHolder {

    /** 当前装着的实现；{@code null} = 没装（或已 clear）。 */
    private static volatile ShanhaiAdd impl;

    private ShanhaiAddHolder() {}

    /**
     * 注册实现（由 add 的 {@code @Mod} 构造器调用）。重复调用以最后一次为准。
     *
     * @param add 实现；{@code null} 等价于 {@link #clear()}
     */
    public static void install(ShanhaiAdd add) {
        impl = add;
    }

    /** 断开（add 卸载时用；不抛异常，重复调用无副作用）。 */
    public static void clear() {
        impl = null;
    }

    /**
     * 取当前实现。
     *
     * @return 实现对象；{@code null} = <b>没装 add</b>（调用方必须自己回退，不许 NPE）
     */
    public static ShanhaiAdd optional() {
        return impl;
    }

    /** {@code true} = 现在有实现可调（等价于 {@code optional() != null}）。 */
    public static boolean present() {
        return impl != null;
    }
}
