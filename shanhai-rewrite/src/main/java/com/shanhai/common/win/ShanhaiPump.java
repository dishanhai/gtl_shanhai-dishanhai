package com.shanhai.common.win;

/**
 * 山海重构 · <b>消息泵挂点</b>（纯 dist 中立：服务端也会加载本类，因此【绝不许】出现任何客户端类）。
 *
 * <h2>1. 它只做一件事</h2>
 * 持有"当前要跑的那件事"（一个 {@link Runnable}），由<b>必然高频经过</b>的插点在主线程上调
 * {@link #tick()} 去跑它。真正的实现（Win32 消息泵、JNA 接口）在客户端那个类里，
 * <b>本类不 import 它，也不引用 JNA 的任何类型（连一个 import 都没有）</b>：
 * <ul>
 *   <li>本类只认识 {@link Runnable} -> 专用服务端加载它时不会连带加载任何一个客户端类
 *       （这正是本工程血账里"Attempted to load class ... for invalid dist DEDICATED_SERVER"那一族坑）；</li>
 *   <li>JNA 在某个环境不可用时，失败面被关在客户端那个类的 try/catch 里，不会传染到这里。</li>
 * </ul>
 *
 * <h2>2. 为什么 tick() 必须吞掉一切异常</h2>
 * 两个插点都在 <b>Forge 自己的加载推进链</b>上（模组加载期的同步执行器）与 GT 的配方索引构建里。
 * 从这里往外抛，就不是"泵没跑"，而是<b>整局起不来 / 配方装不进去</b>。
 * 所以 {@link #tick()} 的契约只有一条：<b>要么不跑，要么跑完不抛</b>。
 *
 * <h2>3. 线程</h2>
 * 本类不做任何线程判断（它没有那个上下文）：线程判断在实现里做，绝大多数调用会在第一件事上就返回。
 *
 * <h2>4. 安装 / 卸载</h2>
 * <pre>
 *   安装：客户端 {@code FMLClientSetupEvent} 里 install(实现::onTick)（整段包 try/catch(Throwable)）
 *   卸载：实现自己判断"再试也没意义"时调 uninstall()（例如 JNA 加载失败）
 * </pre>
 * 安装是<b>覆盖</b>而不是追加：只允许一个泵（重复安装 = 用新的顶掉旧的，不会叠出两个泵）。
 */
public final class ShanhaiPump {

    /**
     * 当前要跑的那件事（{@code null} = 没装）。
     *
     * <p>{@code volatile}：写入发生在客户端 setup（主线程），读取发生在加载泵 / 配方索引（多数是主线程，
     * 但不保证）。这里只需要"看得见最新值"，不需要原子性。
     */
    private static volatile Runnable hook;

    private ShanhaiPump() {
    }

    /**
     * 装上"每个插点都要跑一次"的那件事（重复调用 = 覆盖）。
     *
     * <p>传 {@code null} 等价于 {@link #uninstall()}。
     */
    public static void install(final Runnable r) {
        hook = r;
    }

    /** 卸载（之后 {@link #tick()} 立刻变成空操作）。 */
    public static void uninstall() {
        hook = null;
    }

    /**
     * 跑一次挂点。<b>本方法保证不抛任何 Throwable</b>（包括 {@code NoClassDefFoundError} /
     * {@code UnsatisfiedLinkError} 这类 {@code Error}）。
     *
     * <p>没装 -> 一次 {@code volatile} 读 + 返回，代价可忽略（插点会被高频调用，这条路径必须极短）。
     */
    public static void tick() {
        final Runnable r = hook;
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (final Throwable ignored) {
            // 唯一正确的处理：吞掉。本类的调用方是 Forge 的加载循环与 GT 的配方索引构建，
            // 从这里抛出去会打断加载（见类 javadoc 第 2 节）。
        }
    }
}
