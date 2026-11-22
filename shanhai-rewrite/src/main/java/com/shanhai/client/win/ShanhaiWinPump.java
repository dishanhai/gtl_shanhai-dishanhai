package com.shanhai.client.win;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.win.ShanhaiPump;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.Locale;

/**
 * 山海重构 · <b>节流消息泵</b>：在窗口线程上周期性抽一次 Windows 消息，让"进世界/加载"那 5 秒以上
 * 不处理消息的空窗不再触发系统"未响应"（用户 Alt+Tab 切不回来、外部进程改标题阻塞十几秒）。
 *
 * <h2>1. 治的是什么（已实测的现象，不是推断）</h2>
 * 单机进世界/加载期间，窗口线程（渲染线程 = 主线程）长时间不 {@code PeekMessage}，
 * Windows 就把窗口标成"未响应"；同一个窗口上的跨线程调用（例如别的进程的
 * {@code SetWindowTextW}）会一直挂起到目标线程下一次抽消息为止。
 * 实测：只要窗口线程周期性抽一次消息，挂起的调用立刻完成（2701 ms 降到抽到那一刻；连续抽时 16 ms）。
 * 所以本类<b>不是</b>在改标题，只是<b>替窗口线程抽消息</b>：把系统本来就会在下一帧做的那件事提前做一点。
 *
 * <h2>2. 挂在哪、为什么这么挂</h2>
 * 本类自己不定时（没有新线程、没有定时器）：它由 {@link ShanhaiPump} 的插点驱动，
 * 插点插在两个"加载期必然高频经过"的位置（FML 主线程同步执行器、GT 配方索引入口）。
 * 自己开线程抽消息是<b>错</b>的：{@code PeekMessage}/{@code DispatchMessage} 必须由<b>窗口所属线程</b>执行。
 * 所以每一拍先做几次纯内存比较（停用标志 / 100 ms 节流），真的要去碰原生调用之前才确认
 * "我在不在窗口线程上"，不在就立刻返回（绝大多数调用走的就是这条）。
 * <p>2026-11-21 又加了一个<b>通用插点</b>（{@link ShanhaiLogPump}）：在 log4j 的 root logger 上挂一个
 * appender，<b>凡有日志就打一次泵</b>。原因是上面那两个插点实测 {@code calls=1}（等于没挂上），
 * 而"加载卡住"的现场恰恰都是日志密集的 —— 用"日志"当插点最不容易漏，也不再需要为每个新卡点猜位置。
 *
 * <h2>3. 为什么 JNA 只写最小面、且不在类签名里出现</h2>
 * 不用任何现成的 User32 封装，自己声明四个函数（{@code PeekMessageW} / {@code TranslateMessage} /
 * {@code DispatchMessageW} / {@code GetWindowThreadProcessId}）与一个 {@code GetCurrentThreadId}。
 * <p>更要紧的是：<b>JNA 的类型只出现在字段与嵌套接口里，不出现在本类的任何方法签名上</b>。
 * 这样 {@code @Mod.EventBusSubscriber} 的事件总线反射扫描（它会读本类的 {@code public} 方法签名）
 * 不需要解析 JNA；只有真的执行到泵那一步才会去碰 {@code Native.load}。
 * 万一 JNA 在某个环境里不可用，失败面就被下面的 {@code catch (Throwable)} 关在这里。
 * <p>调用约定：user32 的函数在 32 位 Windows 上是 stdcall、在 x64 上与 cdecl 等同；
 * 1.20.1 只跑 64 位 JVM，所以用 {@link Library} 的默认约定即可（不再引 jna-platform 的封装）。
 *
 * <h2>4. 节流 / 预算 / 读数</h2>
 * <pre>
 *   节流：距上次泵不到 100 ms 就返回（抽查频率上限约 10 次/秒）
 *   预算：一次泵最多派发 3 ms 的消息（之后剩下的留给下一帧，绝不长时间占用主线程）
 *   日志：每 5 秒一行（限流），格式固定：
 *         [SHANHAI-PUMP] on_thread=true calls=N dispatches=M max_gap_ms=K hwnd=0x... tid=...
 *         calls      = 本进程进过本方法的次数（含非窗口线程的快速返回）
 *         dispatches = 真正派发出去的消息条数
 *         max_gap_ms = 两次"真的泵"之间的最大间隔（判"加载期有没有长时间没抽到"就看它）
 * </pre>
 *
 * <h2>5. 失败模式（全部只打一行日志，绝不影响加载）</h2>
 * <ul>
 *   <li>安装：{@code FMLClientSetupEvent} 里整段 {@code try/catch(Throwable)}（含 {@code NoClassDefFoundError}）
 *       -> 只打 {@code [SHANHAI-PUMP] init_failed reason=<类名>}，事件照常返回；</li>
 *   <li>取不到窗口（{@code mc=null} / 句柄 0）：<b>不永久放弃</b>，每 250 ms 再试一次，日志 5 秒限流；</li>
 *   <li>JNA / 原生库加载不到：不可恢复 -> 打一行 {@code disabled reason=<类名>} 并 {@link ShanhaiPump#uninstall()}；</li>
 *   <li>运行期任何 Throwable：同上（一行 warn 后停用），<b>绝不允许</b>把异常抛回 Forge 的加载循环。</li>
 * </ul>
 *
 * <h2>6. 已知风险（如实写在这里，不藏着）</h2>
 * <ul>
 *   <li>泵把消息交给 GLFW 自己的窗口过程（{@code DispatchMessageW}），等价于"这一帧的窗口消息提前处理掉"。
 *       加载期因此也会处理输入/尺寸类消息，与正常帧里处理它们没有区别，但确实把"加载期不处理消息"这个前提改掉了。</li>
 *   <li>取消息用的是 {@code hWnd = NULL}（= 本线程全部窗口与线程消息）。其中 {@code WM_QUIT} 不在窗口过程里、
 *       是一条约定的线程消息，抽到它等于把它从队列里取走。正常情况下退出走的是关窗（{@code WM_CLOSE}，
 *       仍会经窗口过程交给 GLFW）或直接结束进程，所以这条只影响"别的进程给我投递退出消息"的边缘路径。</li>
 *   <li>句柄只在首次初始化时取一次。窗口线程若重建窗口，线程 id 不变 -> 泵照常工作（泵是按线程抽的），
 *       只有日志里的 {@code hwnd=} 会变旧。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "shanhai", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiWinPump {

    /** 日志前缀（判据行，格式见类 javadoc 第 4 节；grep 这一个词即可）。 */
    private static final String PREFIX = "[SHANHAI-PUMP]";

    /** 两次泵之间的最小间隔：100 ms（约 10 次/秒）。 */
    private static final long PUMP_MIN_GAP_NANOS = 100_000_000L;

    /** 一次泵的时间预算：3 ms。 */
    private static final long PUMP_BUDGET_NANOS = 3_000_000L;

    /** 取不到窗口时的重试间隔（毫秒）：加载早期 {@code mc} 可能还是 null。 */
    private static final long INIT_RETRY_MS = 250L;

    /** 初始化失败日志的限流间隔（毫秒）。 */
    private static final long INIT_FAIL_LOG_PERIOD_MS = 5_000L;

    /** 读数日志的周期（毫秒）。 */
    private static final long LOG_PERIOD_MS = 5_000L;

    /** {@code PeekMessageW} 的 {@code PM_REMOVE}：取走消息。 */
    private static final int PM_REMOVE = 0x0001;

    /**
     * {@code MSG} 结构要的字节数。
     *
     * <p>本类<b>不解析</b>这个结构的内容（只把它当不透明缓冲区交给三个 Win32 函数），
     * 64 字节在 32 位与 64 位上都不小于真实的 {@code MSG}（x64 为 48 字节）。
     */
    private static final long MSG_BYTES = 64L;

    // ----------------------------- 原生库（只有这里出现 JNA 类型） -----------------------------

    /** user32 句柄（懒加载；null = 还没加载）。 */
    private static PumpUser32 user32;

    /** kernel32 句柄（懒加载；null = 还没加载）。 */
    private static PumpKernel32 kernel32;

    /** user32 的最小面：只声明本类用到的四个函数。 */
    public interface PumpUser32 extends Library {

        /** {@code BOOL PeekMessageW(LPMSG, HWND, UINT, UINT, UINT)}。 */
        int PeekMessageW(Pointer lpMsg, Pointer hWnd, int min, int max, int remove);

        /** {@code BOOL TranslateMessage(const MSG*)}。 */
        int TranslateMessage(Pointer lpMsg);

        /** {@code LRESULT DispatchMessageW(const MSG*)}（返回值不解析，只按机器字长收下）。 */
        Pointer DispatchMessageW(Pointer lpMsg);

        /** {@code DWORD GetWindowThreadProcessId(HWND, LPDWORD)}。 */
        int GetWindowThreadProcessId(Pointer hWnd, IntByReference pid);
    }

    /** kernel32 的最小面。 */
    public interface PumpKernel32 extends Library {

        /** {@code DWORD GetCurrentThreadId(void)}。 */
        int GetCurrentThreadId();
    }

    // ------------------------------------- 状态 -------------------------------------

    /** 永久降级标志（JNA 不可用 / 运行期抛过 Throwable -> 不再尝试）。 */
    private static volatile boolean disabled;

    /** 窗口句柄与窗口线程 id 是否已经取到。 */
    private static boolean initialized;

    /** 窗口句柄（HWND；只用于取线程 id 与日志）。 */
    private static long hwnd;

    /** 窗口所属线程 id（= 唯一允许抽消息的那个线程）。 */
    private static int windowThreadId;

    /** 上一次真的泵的时间戳（{@code System.nanoTime()}；0 = 还没泵过）。 */
    private static long lastPumpNanos;

    /** 上一次尝试初始化窗口的时间戳（毫秒；0 = 还没试过）。 */
    private static long lastInitAttemptMs;

    /** 上一次打初始化失败日志的时间戳（毫秒；限流用）。 */
    private static long lastInitFailLogMs;

    /** 上一次打读数日志的时间戳（毫秒；限流用）。 */
    private static long lastLogMs;

    /** 防重入（只有窗口线程会把它置 true）。 */
    private static boolean inPump;

    /** 读数：进过本方法的次数（含所有线程的快速返回）。 */
    private static long calls;

    /** 读数：真的派发出去的消息条数。 */
    private static long dispatches;

    /** 读数：两次泵之间的最大间隔（毫秒）。 */
    private static long maxGapMs;

    /**
     * 本轮是否启用泵（2026-11-22）。
     *
     * <p>🔴 默认 <b>false</b>：装上泵的那一版实机连续两次被 FTB Essentials 的 NPE 踢出存档，
     * 回滚后立刻能进 ⇒ 先只交付不受牵连的部分（活动采样器 + 标题"正在加载"新段），
     * 泵等因果查清后再单独一版验证。
     *
     * <p>恢复方式：改回 {@code true}（代码、两条 mixin 配置、日志钩一个都没删）。
     */
    private static final boolean ENABLED = false;

    private ShanhaiWinPump() {
    }

    // ------------------------------------- 安装 -------------------------------------

    /**
     * 客户端 setup 那一拍把泵挂到 {@link ShanhaiPump} 上。
     *
     * <p>本方法的时间点早于游戏窗口存在，所以真正的初始化<b>不在</b>这里做（懒加载，见 {@link #initWindow()}）：
     * 这里只做"把 Runnable 装上去"这一件事。
     *
     * <p>整段 {@code try/catch(Throwable)}（含 {@code NoClassDefFoundError}）：JNA 或
     * {@code GLFWNativeWin32} 在某个环境不可用时，只许打一行日志，<b>绝不许让本事件抛异常</b>
     * （那会把启动打崩）。
     */
    @SubscribeEvent
    public static void onClientSetup(final FMLClientSetupEvent event) {
        try {
            if (!isWindows()) {
                info("skipped reason=not_windows os=" + osName());
                return;
            }
            // 🔴 2026-11-22 本轮【先不启用泵】。依据：装上泵的那一版（512B67FA）在 test 上连续两次
            // 被 FTB Essentials 的 NPE 踢出存档（FTBEWorldData.instance 为 null），回滚到 B932A648
            // 后立刻能进 ⇒ A/B 指向泵那版。因果查清之前只交付「采样器 + 标题新段」；
            // 泵的代码全部保留，把 ENABLED 改回 true 即可恢复（两条 mixin + 日志钩一起回来）。
            if (!ENABLED) {
                info("skipped reason=pump_disabled_by_flag note=本版只上采样器与标题新段");
                return;
            }
            ShanhaiPump.install(ShanhaiWinPump::onTick);
            // 通用日志钩子（2026-11-21）：凡有日志就打一次泵。两条 mixin 实测 calls=1（等于没挂上），
            // 而"加载卡住"的现场恰恰都是日志密集的，所以把"日志"本身当成插点最不容易漏。
            // install() 自己整段吞异常，失败只打一行 log_hook=failed；这里不接它的返回值。
            ShanhaiLogPump.install();
            info("installed min_gap_ms=" + (PUMP_MIN_GAP_NANOS / 1_000_000L)
                    + " budget_ms=" + (PUMP_BUDGET_NANOS / 1_000_000L)
                    + " log_hook=" + (ShanhaiLogPump.installed() ? "on" : "off")
                    + "（只在窗口线程抽消息；其它线程第一件事就返回）");
        } catch (final Throwable t) {
            // 硬要求：安装失败只许打一行，绝不许让 FMLClientSetupEvent 抛出去（会把启动打崩）。
            info("init_failed reason=" + className(t));
        }
    }

    // ------------------------------------- 每个插点都调它 -------------------------------------

    /**
     * 一个插点的入口。由 {@link ShanhaiPump#tick()} 调，所以<b>本方法也必须不抛</b>。
     *
     * <p>顺序（前两条是绝大多数调用会走的路，都只是几次内存比较）：
     * <ol>
     *   <li>停用标志 + 节流：距上次泵不到 100 ms 就直接返回（插入点会被每秒调用上万次）；</li>
     *   <li>原生库：只在第一次真的需要时加载（加载不到 = 不可恢复）；</li>
     *   <li>窗口懒初始化：每 250 ms 最多试一次（取不到不是错误，等窗口出生）；</li>
     *   <li>线程：不在窗口线程就直接返回（这一步之后才会去调原生函数）；</li>
     *   <li>防重入：窗口过程里如果又走到插点，不会套娃；</li>
     *   <li>泵 3 ms。</li>
     * </ol>
     */
    public static void onTick() {
        if (disabled) {
            return;
        }
        try {
            step();
        } catch (final Throwable t) {
            fatal(t);
        }
    }

    private static void step() {
        ++calls;

        // (1) 节流：100 ms 内的重复调用全部在这里丢掉（把它们排在线程判断前面，是为了不在热路径上做原生调用）。
        final long now = System.nanoTime();
        if (lastPumpNanos != 0L && (now - lastPumpNanos) < PUMP_MIN_GAP_NANOS) {
            return;
        }

        // (2) 原生库（只加载一次；加载不到 = 不可恢复，直接停用）。
        if (!ensureLibraries()) {
            return;
        }

        // (3) 懒初始化窗口（每 250 ms 最多试一次；取不到不是错误，等窗口出生）。
        if (!initialized) {
            final long nowMs = System.currentTimeMillis();
            if (lastInitAttemptMs != 0L && (nowMs - lastInitAttemptMs) < INIT_RETRY_MS) {
                return;
            }
            lastInitAttemptMs = nowMs;
            if (!initWindow()) {
                return;
            }
        }

        // (4) 只在窗口线程上跑。
        final int threadId = kernel32.GetCurrentThreadId();
        if (threadId != windowThreadId) {
            return;
        }

        // (5) 防重入。
        if (inPump) {
            return;
        }

        // (6) 记间隔 + 泵。
        final long start = System.nanoTime();
        if (lastPumpNanos != 0L) {
            final long gapMs = (start - lastPumpNanos) / 1_000_000L;
            if (gapMs > maxGapMs) {
                maxGapMs = gapMs;
            }
        }
        inPump = true;
        lastPumpNanos = start;
        try {
            pump();
        } finally {
            inPump = false;
        }
        logPeriodic();
    }

    // ------------------------------------- 初始化 -------------------------------------

    /** 懒加载两个原生库。失败 = 不可恢复（打一行 warn 后停用）。 */
    private static boolean ensureLibraries() {
        if (user32 != null && kernel32 != null) {
            return true;
        }
        try {
            final PumpUser32 u = Native.load("user32", PumpUser32.class);
            final PumpKernel32 k = Native.load("kernel32", PumpKernel32.class);
            user32 = u;
            kernel32 = k;
            return true;
        } catch (final Throwable t) {
            fatal(t);
            return false;
        }
    }

    /**
     * 取窗口句柄与窗口线程 id。
     *
     * <p>句柄路径与工程里现成的那一处一致：{@code Minecraft#getWindow()} 拿 GLFW 句柄，
     * 再 {@code GLFWNativeWin32.glfwGetWin32Window} 换成 Win32 句柄（GLFW 有线程亲和性，
     * 所以这里先确认自己在游戏主线程上，不在就不碰它）。
     *
     * @return {@code true} = 已可用；{@code false} = 这一次取不到（<b>可恢复</b>，下次再试）
     */
    private static boolean initWindow() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            logInitFailed("mc_null");
            return false;
        }
        if (!minecraft.isSameThread()) {
            logInitFailed("not_main_thread");
            return false;
        }
        if (minecraft.getWindow() == null) {
            logInitFailed("window_null");
            return false;
        }
        long captured = 0L;
        try {
            final long glfwHandle = minecraft.getWindow().getWindow();
            if (glfwHandle == 0L) {
                logInitFailed("glfw_handle_zero");
                return false;
            }
            captured = GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
        } catch (final Throwable t) {
            logInitFailed(className(t));
            return false;
        }
        if (captured == 0L) {
            logInitFailed("hwnd_zero");
            return false;
        }
        try {
            final IntByReference pidRef = new IntByReference(0);
            final int threadId = user32.GetWindowThreadProcessId(Pointer.createConstant(captured), pidRef);
            if (threadId == 0) {
                logInitFailed("no_window_thread");
                return false;
            }
            hwnd = captured;
            windowThreadId = threadId;
            initialized = true;
            info("init_ok hwnd=0x" + Long.toHexString(captured)
                    + " tid=" + Integer.toUnsignedString(threadId)
                    + " pid=" + Integer.toUnsignedString(pidRef.getValue()));
            return true;
        } catch (final Throwable t) {
            logInitFailed(className(t));
            return false;
        }
    }

    // ------------------------------------- 泵 -------------------------------------

    /**
     * 抽一次消息（最多 {@link #PUMP_BUDGET_NANOS}）：取一条、翻译、交给窗口过程，再取下一条。
     *
     * <p>{@code hWnd = NULL} -> 本线程的所有窗口消息与线程消息都抽；{@code MSG} 的内容<b>不解析</b>
     * （只当不透明缓冲区，避免本类依赖 {@code MSG} 的字段布局）。
     */
    private static void pump() {
        final long deadline = System.nanoTime() + PUMP_BUDGET_NANOS;
        final Memory msg = new Memory(MSG_BYTES);
        int dispatched = 0;
        while (user32.PeekMessageW(msg, null, 0, 0, PM_REMOVE) != 0) {
            user32.TranslateMessage(msg);
            user32.DispatchMessageW(msg);
            ++dispatched;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        if (dispatched > 0) {
            dispatches += dispatched;
        }
    }

    // ------------------------------------- 日志 / 降级 -------------------------------------

    /** 每 5 秒一行读数（限流；判据行，格式见类 javadoc 第 4 节）。 */
    private static void logPeriodic() {
        final long nowMs = System.currentTimeMillis();
        if (lastLogMs != 0L && (nowMs - lastLogMs) < LOG_PERIOD_MS) {
            return;
        }
        lastLogMs = nowMs;
        info("on_thread=true calls=" + calls
                + " dispatches=" + dispatches
                + " max_gap_ms=" + maxGapMs
                + " hwnd=0x" + Long.toHexString(hwnd)
                + " tid=" + Integer.toUnsignedString(windowThreadId));
    }

    /** 初始化失败：可恢复，只限流打一行。 */
    private static void logInitFailed(final String reason) {
        final long nowMs = System.currentTimeMillis();
        if (lastInitFailLogMs != 0L && (nowMs - lastInitFailLogMs) < INIT_FAIL_LOG_PERIOD_MS) {
            return;
        }
        lastInitFailLogMs = nowMs;
        info("init_failed reason=" + reason);
    }

    /**
     * 不可恢复：打<b>一行</b> warn，然后卸载自己（之后插点调用变成"一次布尔读"）。
     *
     * <p>不重试：抛过 Throwable 的路径再跑一遍，最可能的结局是每 tick 抛一次（那才是真正拖慢加载的东西）。
     */
    private static void fatal(final Throwable t) {
        disabled = true;
        try {
            ShanhaiPump.uninstall();
        } catch (final Throwable ignored) {
            // 卸载失败也只影响"还挂不挂着"，停用标志已经设上了。
        }
        try {
            warn("disabled reason=" + className(t) + " msg=" + brief(t) + "（这条路已停用，游戏继续）");
        } catch (final Throwable ignored) {
            // 日志本身失败不许变成第二个异常。
        }
    }

    private static void info(final String message) {
        try {
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, message);
        } catch (final Throwable ignored) {
            // 日志系统不可用时丢掉这一行，绝不影响游戏。
        }
    }

    private static void warn(final String message) {
        try {
            ShanhaiMod.LOGGER.warn("{} {}", PREFIX, message);
        } catch (final Throwable ignored) {
            // 同上。
        }
    }

    /** 异常类全限定名（对得上崩溃报告；取不到写 {@code null}）。 */
    private static String className(final Throwable t) {
        try {
            return t == null ? "null" : t.getClass().getName();
        } catch (final Throwable ignored) {
            return "null";
        }
    }

    /** 异常消息首行、硬截断到 160 字（只给日志用，绝不做判据）。 */
    private static String brief(final Throwable t) {
        try {
            final String message = t == null ? null : t.getMessage();
            if (message == null) {
                return "null";
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < message.length() && sb.length() < 160; i++) {
                final char c = message.charAt(i);
                if (c == '\n' || c == '\r') {
                    break;
                }
                sb.append(c < 0x20 ? ' ' : c);
            }
            return sb.toString();
        } catch (final Throwable ignored) {
            return "null";
        }
    }

    // ------------------------------------- 平台判断 -------------------------------------

    /** {@code os.name}（取不到写 unknown）。 */
    private static String osName() {
        try {
            final String os = System.getProperty("os.name");
            return os == null ? "unknown" : os;
        } catch (final Throwable ignored) {
            return "unknown";
        }
    }

    /** 只在 Windows 上才有 user32 与 Win32 窗口句柄（其它平台安静跳过，不靠异常去发现）。 */
    private static boolean isWindows() {
        return osName().toLowerCase(Locale.ROOT).contains("windows");
    }
}
