package com.shanhai.backstage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 后台进程用的 Win32 薄封装：<b>只走反射</b>，编译期不依赖 JNA。
 *
 * <h4>为什么不用 {@code interface User32 extends Library}</h4>
 * <p>那种写法要在编译期 import {@code com.sun.jna.*}。本 mod 的 Gradle 依赖里<b>没有</b> jna
 * （它只在游戏运行期的 libraries 里），为了让 {@code :compileJava} 在任何环境都成立，
 * 这里用 JNA 的底层 API 反射完成同样的事：
 * <pre>
 * NativeLibrary.getInstance("user32")
 *   → getFunction("SetWindowTextW" / "SendMessageTimeoutW" / "IsWindow" / "GetWindowTextW")
 *   → Function.invoke(Class, Object[])
 * </pre>
 * 参数里的 HWND 用 {@code com.sun.jna.Pointer(long)} 表示，宽字符串用 {@code com.sun.jna.Memory}
 * + {@code Memory.setWideString(0, s)} 手工铺 UTF-16（{@code SetWindowTextW} 要的就是 {@code wchar_t*},
 * 而 {@code Memory.setWideString} 在 Windows 上写的是 UTF-16LE）——不依赖 JNA 的 {@code WString} 映射，
 * 少一层类型转换就少一条会失败的路径。
 *
 * <h4>卡住判据</h4>
 * <p>{@link #isHung(long, int)} = {@code SendMessageTimeoutW(hwnd, WM_NULL, 0, 0,
 * SMTO_ABORTIFHUNG | SMTO_BLOCK, timeoutMs, &result)} 返回 0。这是 Windows 自己判「程序未响应」
 * 用的那条路：目标线程在抽消息 ⇒ 立刻返回非 0；目标线程没在抽（卡住）⇒ 超时返回 0。
 *
 * <p>🔴 <b>它只回答"卡没卡"，不回答"卡住时能不能改标题"</b>——后者是 B 组实验的内容
 * （{@code temp/backstage-probe} 里单独测）。如果实验证明卡住时 {@code SetWindowTextW} 会被
 * SendMessage 卡住，那么写标题的一方就只在探活成功时才调它（见 {@link Win32TitleSink}）。
 */
public final class Win32 {

    /** 探测用消息：{@code WM_NULL}（不改变任何状态，只走一遍消息泵）。 */
    private static final int WM_NULL = 0x0000;

    /** {@code WM_SETTEXT}：改窗口文本（{@link #setTextViaDefWindowProc} 用）。 */
    private static final int WM_SETTEXT = 0x000C;

    /** {@code SMTO_BLOCK(0x1) | SMTO_ABORTIFHUNG(0x2)}。 */
    private static final int SMTO_FLAGS = 0x0001 | 0x0002;

    private final Method functionInvoke;
    private final Constructor<?> pointerCtor;
    private final Constructor<?> memoryCtor;
    private final Method setWideString;
    private final Method getWideString;

    private final Object setWindowTextW;
    private final Object sendMessageTimeoutW;
    private final Object isWindow;
    private final Object getWindowTextW;
    private final Object defWindowProcW;
    private final Object getLastErrorFn;

    private Win32(Method functionInvoke, Constructor<?> pointerCtor, Constructor<?> memoryCtor,
                  Method setWideString, Method getWideString,
                  Object setWindowTextW, Object sendMessageTimeoutW, Object isWindow,
                  Object getWindowTextW, Object defWindowProcW, Object getLastErrorFn) {
        this.functionInvoke = functionInvoke;
        this.pointerCtor = pointerCtor;
        this.memoryCtor = memoryCtor;
        this.setWideString = setWideString;
        this.getWideString = getWideString;
        this.defWindowProcW = defWindowProcW;
        this.setWindowTextW = setWindowTextW;
        this.sendMessageTimeoutW = sendMessageTimeoutW;
        this.isWindow = isWindow;
        this.getWindowTextW = getWindowTextW;
        this.getLastErrorFn = getLastErrorFn;
    }

    /**
     * 试探性创建。JNA 不在 / user32 加载失败 / 任何一个函数取不到 ⇒ 返回 {@code null}，
     * 原因写进 {@link #failureReason()}（子进程据此打 {@code fallback=...} 一行并降级为只算不写）。
     */
    public static Win32 tryCreate() {
        try {
            final Class<?> nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary");
            final Class<?> functionClass = Class.forName("com.sun.jna.Function");
            final Class<?> pointerClass = Class.forName("com.sun.jna.Pointer");
            final Class<?> memoryClass = Class.forName("com.sun.jna.Memory");

            final Method getInstance = nativeLibraryClass.getMethod("getInstance", String.class);
            final Method getFunction = nativeLibraryClass.getMethod("getFunction", String.class);
            final Object user32 = getInstance.invoke(null, "user32");
            final Object kernel32 = getInstance.invoke(null, "kernel32");

            final Object setText = getFunction.invoke(user32, "SetWindowTextW");
            final Object sendTimeout = getFunction.invoke(user32, "SendMessageTimeoutW");
            final Object isWin = getFunction.invoke(user32, "IsWindow");
            final Object getText = getFunction.invoke(user32, "GetWindowTextW");
            final Object defProc = getFunction.invoke(user32, "DefWindowProcW");
            final Object getLastError = getFunction.invoke(kernel32, "GetLastError");

            final Method invoke = functionClass.getMethod("invoke", Class.class, Object[].class);
            final Constructor<?> pointer = pointerClass.getConstructor(long.class);
            final Constructor<?> memory = memoryClass.getConstructor(long.class);
            final Method setWide = memoryClass.getMethod("setWideString", long.class, String.class);
            final Method getWide = memoryClass.getMethod("getWideString", long.class);

            final Win32 win32 = new Win32(invoke, pointer, memory, setWide, getWide,
                    setText, sendTimeout, isWin, getText, defProc, getLastError);
            failureReason = "";
            return win32;
        } catch (Throwable t) {
            failureReason = describe(t);
            return null;
        }
    }

    private static volatile String failureReason = "not_attempted";

    /** {@link #tryCreate()} 失败的原因（{@code jna_missing} / 异常类名），成功时为空串。 */
    public static String failureReason() {
        return failureReason;
    }

    /** 反射一时拿不到的可读原因（只是日志用，别拿它做判据）。 */
    private static String describe(Throwable t) {
        if (t instanceof ClassNotFoundException) {
            return "jna_missing:" + ((ClassNotFoundException) t).getMessage();
        }
        if (t instanceof UnsatisfiedLinkError) {
            return "native_missing:" + t.getMessage();
        }
        return t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
    }

    // ─────────────────────────────── Win32 动作 ───────────────────────────────

    /** 句柄指向的窗口还在不在（{@code IsWindow}）。 */
    public boolean isWindow(long hwnd) {
        try {
            final Object rc = functionInvoke.invoke(isWindow, args(Integer.TYPE, new Object[]{pointer(hwnd)}));
            return rc instanceof Number && ((Number) rc).intValue() != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 窗口是否卡住：{@code SendMessageTimeoutW(WM_NULL, timeout)} 返回 0 ⇒ 是。
     *
     * <p>任何异常都当「卡住」（宁可少写一次标题，也不要往一个可能已死的窗口里塞字符串）。
     */
    public boolean isHung(long hwnd, int timeoutMs) {
        try {
            final Object result = memoryCtor.newInstance(8L);
            final Object[] fnArgs = new Object[]{
                    pointer(hwnd),
                    Integer.valueOf(WM_NULL),
                    Long.valueOf(0L),
                    Long.valueOf(0L),
                    Integer.valueOf(SMTO_FLAGS),
                    Integer.valueOf(Math.max(1, timeoutMs)),
                    result
            };
            final Object rc = functionInvoke.invoke(sendMessageTimeoutW, args(Long.TYPE, fnArgs));
            return !(rc instanceof Number) || ((Number) rc).longValue() == 0L;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * 🔴 <b>B 方案：旁路改标题</b> —— 直接调 {@code DefWindowProcW(hwnd, WM_SETTEXT, 0, 文本指针)}。
     *
     * <h4>为什么不走 SetWindowTextW</h4>
     * <p>2026-11-21 实测（{@code temp/backstage-probe} 的 {@code WritePathExperiment}）：
     * 目标窗口线程卡死时，跨进程 {@code SetWindowTextW} 会<b>阻塞整个卡死期</b>（10 秒），
     * 而这条直调路径 <b>2 ms 返回</b>且外部 {@code GetWindowTextW} 读回新值 ——
     * 因为它把 {@code WM_SETTEXT} 直接交给默认窗口过程处理，不经过目标线程的消息队列。
     *
     * <p>⚠️ 这是<b>跨进程直接改窗口对象</b>的用法：文本字段确实变了（我们自己的普通窗口上实测），
     * 但<b>在 GLFW 窗口上没有验证过</b>，而且"标题栏/幽灵层会不会跟着重画"也<b>没有验证</b>。
     * 所以调用方必须：① 每次写完用 {@link #getWindowText} 读回比对；
     * ② 失败/不一致时<b>这一次</b>回退 {@link #setWindowText}（{@link Win32TitleSink} 就是这么做的）。
     *
     * @return {@code true} = 默认窗口过程返回非 0（WM_SETTEXT 的约定是 TRUE = 成功）
     */
    public boolean setTextViaDefWindowProc(long hwnd, String title) {
        if (title == null) {
            return false;
        }
        try {
            final long bytes = ((long) title.length() + 1L) * 2L;
            final Object buffer = memoryCtor.newInstance(bytes);
            setWideString.invoke(buffer, 0L, title);
            final Object[] fnArgs = new Object[]{
                    pointer(hwnd),
                    Integer.valueOf(WM_SETTEXT),
                    Long.valueOf(0L),   // wParam
                    buffer              // lParam = 宽字符串指针（本进程地址空间）
            };
            final Object rc = functionInvoke.invoke(defWindowProcW, args(Long.TYPE, fnArgs));
            return rc instanceof Number && ((Number) rc).longValue() != 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 写窗口标题（{@code SetWindowTextW}，UTF-16）。
     *
     * <p>🔴 这个调用在目标线程卡住时<b>会阻塞</b>（可见带标题栏窗口，10 秒实测）。
     * 所以它只允许在 {@link Win32TitleSink} 的写线程里被调用，绝不许放在计时循环上；
     * 现在它只作为 {@link #setTextViaDefWindowProc} 失败时的<b>单次回退</b>。
     *
     * @return 成功（返回值非 0）
     */
    public boolean setWindowText(long hwnd, String title) {
        if (title == null) {
            return false;
        }
        try {
            final long bytes = ((long) title.length() + 1L) * 2L;
            final Object buffer = memoryCtor.newInstance(bytes);
            setWideString.invoke(buffer, 0L, title);
            final Object[] fnArgs = new Object[]{pointer(hwnd), buffer};
            final Object rc = functionInvoke.invoke(setWindowTextW, args(Integer.TYPE, fnArgs));
            return rc instanceof Number && ((Number) rc).intValue() != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 读窗口当前标题（诊断 / 实验用；读不到返回空串）。 */
    public String getWindowText(long hwnd, int maxChars) {
        try {
            final int cap = Math.max(8, Math.min(maxChars, 4096));
            final Object buffer = memoryCtor.newInstance(((long) cap + 1L) * 2L);
            final Object[] fnArgs = new Object[]{pointer(hwnd), buffer, Integer.valueOf(cap)};
            final Object rc = functionInvoke.invoke(getWindowTextW, args(Integer.TYPE, fnArgs));
            if (rc instanceof Number && ((Number) rc).intValue() > 0) {
                final Object text = getWideString.invoke(buffer, 0L);
                return text == null ? "" : String.valueOf(text);
            }
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 线程的 {@code GetLastError()}（只在实验/诊断里用）。 */
    public int lastError() {
        try {
            final Object rc = functionInvoke.invoke(getLastErrorFn, args(Integer.TYPE, new Object[0]));
            return rc instanceof Number ? ((Number) rc).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Object pointer(long hwnd) throws Exception {
        return pointerCtor.newInstance(hwnd);
    }

    private static Object[] args(Class<?> returnType, Object[] args) {
        return new Object[]{returnType, args};
    }
}
