package com.shanhai.backstage;

/**
 * 后台进程一侧的几个可注入出口（窗口探活 / 标题写入 / 时钟 / 状态源 / 父进程探活）。
 *
 * <p>全部抽成接口的动机是自证与降级：
 * <ul>
 *   <li>探针要用 <b>fake</b> 版本证明「卡顿不冻结 / 写标题次数 / 异常不杀线程」；</li>
 *   <li>生产环境里 JNA 缺失时必须能换成「只算不写」而不改核心逻辑；</li>
 *   <li>负对照要把时钟换成「主线程每 tick 累加的计数器」，换一个实现即可。</li>
 * </ul>
 * 这些接口都<b>只有 JDK 类型</b>（没有 JNA / 没有 Minecraft），所以整个核心能在零依赖下编译与自证。
 */
public final class BackstagePorts {

    private BackstagePorts() {
    }

    /** 时间来源：生产 = {@link System#currentTimeMillis()}。 */
    public interface BackstageClock {
        long currentTimeMillis();
    }

    /** 状态来源：生产 = 状态文件（变了才重读）；探针 = 可变字段。 */
    public interface StateSource {
        /** 当前状态；{@code null} = 还没读到（调用方保留上一份）。 */
        BackstageState current();
    }

    /**
     * 窗口探活：{@code isHung} = 「这一拍窗口所属线程不响应」。
     *
     * <p>生产实现 = Win32 {@code SendMessageTimeoutW(hwnd, WM_NULL, ..., SMTO_ABORTIFHUNG | SMTO_BLOCK, 100)}，
     * 返回 0 即不响应（这是 Windows 自己判「程序未响应」用的那条路）。
     */
    public interface WindowProbe {
        /** 句柄指向的窗口还在不在。 */
        boolean isWindow(long hwnd);

        /** 窗口是否卡住（超时 = 卡住）。任何异常都应当被实现吞掉并返回 {@code true}（宁可当卡住）。 */
        boolean isHung(long hwnd, int timeoutMs);
    }

    /**
     * 标题出口：把「这一拍该显示的标题」交给实现。<b>实现绝不允许抛异常</b>（核心兜不住原生崩溃）。
     *
     * @param title      要显示的完整标题
     * @param windowHung 这一拍窗口是否卡住（实现可据此决定"写还是不写"）
     */
    public interface TitleSink {
        void submit(String title, boolean windowHung);

        /** 卡住期间真正写成功过几次（{@code stop} / {@code hung} 日志用；fake 可不实现）。 */
        default int writesWhileHung() {
            return 0;
        }

        /**
         * <b>真正写成功</b>的次数：原生调用成功 + 外部读回一致（口径见 {@link Win32TitleSink}）。
         * 卡死期间它不涨，才是"没写进去"的证据；{@code submitted} 涨不能说明任何事。
         */
        default int completedCount() {
            return 0;
        }

        /** 最近一次写调用的耗时（毫秒）；{@code -1} = 还没写过。日志里的 {@code blocked_ms} 就是它。 */
        default long lastWriteMs() {
            return -1L;
        }
    }

    /** 父进程是否还活着（生产 = {@code ProcessHandle.of(pid).isAlive()}）。 */
    public interface ParentCheck {
        boolean isAlive();
    }
}
