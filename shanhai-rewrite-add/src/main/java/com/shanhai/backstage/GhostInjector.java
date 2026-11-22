package com.shanhai.backstage;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>一次性</b>把 {@code DisableProcessWindowsGhosting} 注入目标进程（生产环境 = 游戏父进程），
 * 关掉 Windows 那个「未响应」幽灵态（卡住时整窗被换成白/灰静态位图、标题带画什么都看不见）。
 *
 * <h4>为什么值得注入</h4>
 * <p>Windows 判定某进程的窗口「未响应」后，会由 DWM 接管该窗口的显示（把最后一帧画成幽灵位图），
 * 任何 {@code SetWindowTextW} / {@code DrawTextW} 都不再看得见。本 mod 的全部卖点是
 * 「游戏卡住时标题/标题带照样在走」，所以必须让 Windows 别接管这扇窗。
 * {@code user32!DisableProcessWindowsGhosting} 是微软为这类程序提供的官方开关（Vista+，无参、无返回）。
 *
 * <h4>做法（远程线程跑 13 字节桩）</h4>
 * <pre>
 * OpenProcess(PROCESS_CREATE_THREAD|VM_OPERATION|VM_WRITE|VM_READ, pid)
 *   → VirtualAllocEx(13, MEM_COMMIT|MEM_RESERVE, PAGE_EXECUTE_READWRITE)
 *   → WriteProcessMemory(桩 = 48 B8 &lt;imm64 目标函数地址&gt; FF D0 C3)   // mov rax,addr; call rax; ret
 *   → CreateRemoteThread(桩地址) → WaitForSingleObject(2000)
 *   → CloseHandle(线程/进程) + VirtualFreeEx(MEM_RELEASE)
 * </pre>
 *
 * <h4>🔴 三条红线</h4>
 * <ul>
 *   <li><b>只调一次、绝不重试</b>：失败就记一行日志，标题照写不误。</li>
 *   <li><b>不碰写标题 / 画标题带 / 计时</b>：调用方把它放在自己的线程里，本类不加任何锁、不碰状态文件。</li>
 *   <li><b>不做"通用注入器"</b>：只有 {@link #disableGhosting(long)} 一个用途（自证用的
 *       {@link #inject(long, String, String)} 也只在离线自检里用，游戏里没人调）。</li>
 * </ul>
 *
 * <h4>模块句柄为什么可以直接用</h4>
 * <p>目标函数地址 = {@code GetProcAddress(GetModuleHandleW("user32.dll"), "DisableProcessWindowsGhosting")}
 * 是在<b>本进程</b>算出来的；系统 DLL（user32/kernel32）在同一次开机内被映射到<b>相同基址</b>
 * （ASLR 每启动一次随机、不是每进程随机），所以这个地址在目标进程里同样有效 —— 这也是这条
 * 远程线程注入写法在业界通行的前提。父进程与子进程是同一台机、同一次开机，满足前提。
 *
 * <p>默认开启；{@code config/shanhai/backstage_caption.txt} 里一行 {@code ghost=off} 可关
 * （与现有的 {@code caption_paint=} 同一个文件、同一行格式）。
 */
public final class GhostInjector {

    /** 配置文件名（与 {@code caption_paint=} 同一个文件）。 */
    public static final String CONFIG_NAME = "backstage_caption.txt";
    /** 配置键（整行前缀）。 */
    public static final String CONFIG_KEY = "ghost=";

    /** 桩长度：{@code 48 B8 <imm64> FF D0 C3} = 2 + 8 + 2 + 1 = 13 字节。 */
    public static final int STUB_LENGTH = 13;

    /** {@code PROCESS_CREATE_THREAD | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ}。 */
    public static final int ACCESS = 0x0002 | 0x0008 | 0x0020 | 0x0010;

    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_RESERVE = 0x2000;
    private static final int MEM_RELEASE = 0x8000;
    private static final int PAGE_EXECUTE_READWRITE = 0x40;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT_MS = 2_000;

    /** 成功。 */
    public static final int REASON_OK = 0;
    /** JNA / jnidispatch 不可用（目标进程不会被碰）。 */
    public static final int REASON_JNA_MISSING = -1;
    /** {@code OpenProcess} 失败（权限/进程没了/位数不对）。 */
    public static final int REASON_OPEN_PROCESS = -2;
    /** {@code GetModuleHandleW} 取不到模块（模块名写错/该进程没有它）。 */
    public static final int REASON_MODULE = -3;
    /** {@code GetProcAddress} 找不到函数（系统版本太老）。 */
    public static final int REASON_PROC = -4;
    /** {@code VirtualAllocEx} 失败。 */
    public static final int REASON_ALLOC = -5;
    /** {@code WriteProcessMemory} 失败（或只写进去一部分）。 */
    public static final int REASON_WRITE = -6;
    /** {@code CreateRemoteThread} 失败。 */
    public static final int REASON_THREAD = -7;
    /** 线程 2 秒内没结束（{@code WaitForSingleObject} 超时/失败）。 */
    public static final int REASON_WAIT = -8;

    private GhostInjector() {
    }

    // ─────────────────────────────── 对外动作 ───────────────────────────────

    /** 生产用：把 {@code user32!DisableProcessWindowsGhosting} 注入目标进程一次。 */
    public static Result disableGhosting(long targetPid) {
        return inject(targetPid, "user32.dll", "DisableProcessWindowsGhosting");
    }

    /**
     * 通用入口（自证用）：把 {@code module.dll!function} 注入目标进程一次。
     *
     * @param targetPid 目标进程 pid（生产 = 游戏父进程；自证 = 自己的进程 / 自己 spawn 的子进程）
     */
    public static Result inject(long targetPid, String moduleName, String functionName) {
        final long startedAt = System.nanoTime();
        if (targetPid <= 0L) {
            return Result.fail(startedAt, REASON_OPEN_PROCESS, 0);
        }
        final Kernel32 kernel32;
        try {
            kernel32 = Kernel32Holder.INSTANCE;
        } catch (Throwable t) {
            return Result.fail(startedAt, REASON_JNA_MISSING, 0);
        }
        try {
            final Pointer module = kernel32.GetModuleHandleW(new WString(moduleName));
            if (module == null) {
                return Result.fail(startedAt, REASON_MODULE, kernel32.GetLastError());
            }
            final Pointer function = kernel32.GetProcAddress(module, functionName);
            if (function == null) {
                return Result.fail(startedAt, REASON_PROC, kernel32.GetLastError());
            }
            return injectAddress(kernel32, targetPid, Pointer.nativeValue(function), startedAt);
        } catch (Throwable t) {
            return Result.fail(startedAt, REASON_JNA_MISSING, 0);
        }
    }

    /**
     * 配置：默认 {@code on}；{@code backstage_caption.txt} 里一行 {@code ghost=off} 才关。
     *
     * <p>读不到文件 / 读不懂 ⇒ 仍按 {@code on}（与 {@code caption_paint=} 的"读不到按默认"同一口径）。
     *
     * @param stateFile 状态文件路径（配置与它同目录）
     */
    public static boolean enabledFromConfig(Path stateFile) {
        if (stateFile == null) {
            return true;
        }
        try {
            final Path cfg = stateFile.resolveSibling(CONFIG_NAME);
            if (!Files.isRegularFile(cfg)) {
                return true;
            }
            final List<String> lines = Files.readAllLines(cfg, StandardCharsets.UTF_8);
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                final String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                // 键名与 caption_paint= 同一文件格式（key=value）；`=` 两侧多打的空格按手滑容忍
                if (!"ghost".equalsIgnoreCase(line.substring(0, eq).trim())) {
                    continue;
                }
                final String value = line.substring(eq + 1).trim();
                return !("off".equalsIgnoreCase(value)
                        || "0".equals(value)
                        || "false".equalsIgnoreCase(value)
                        || "no".equalsIgnoreCase(value));
            }
        } catch (Throwable ignored) {
            // 读失败按默认（on）
        }
        return true;
    }

    // ─────────────────────────────── 注入核 ───────────────────────────────

    private static Result injectAddress(Kernel32 kernel32, long targetPid, long address, long startedAt) {
        Pointer process = null;
        Pointer memory = null;
        Pointer thread = null;
        try {
            // ① 打开目标进程
            process = kernel32.OpenProcess(ACCESS, false, (int) targetPid);
            if (process == null) {
                return Result.fail(startedAt, REASON_OPEN_PROCESS, kernel32.GetLastError());
            }
            // ② 13 字节可执行内存
            memory = kernel32.VirtualAllocEx(process, null, STUB_LENGTH,
                    MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
            if (memory == null) {
                return Result.fail(startedAt, REASON_ALLOC, kernel32.GetLastError());
            }
            // ③ 写桩：mov rax, <addr>; call rax; ret
            final byte[] stub = stub(address);
            final LongByReference written = new LongByReference();
            if (!kernel32.WriteProcessMemory(process, memory, stub, stub.length, written)
                    || written.getValue() != stub.length) {
                return Result.fail(startedAt, REASON_WRITE, kernel32.GetLastError());
            }
            // ④ 远程线程跑它
            thread = kernel32.CreateRemoteThread(process, null, 0L, memory, null, 0,
                    new IntByReference());
            if (thread == null) {
                return Result.fail(startedAt, REASON_THREAD, kernel32.GetLastError());
            }
            final int wait = kernel32.WaitForSingleObject(thread, WAIT_TIMEOUT_MS);
            final IntByReference exitCode = new IntByReference();
            final boolean haveExit = kernel32.GetExitCodeThread(thread, exitCode);
            if (wait != WAIT_OBJECT_0) {
                // 超时/失败：线程可能还在目标进程里跑（我们不做第二条远程线程去杀它，只报事实）
                return new Result(false, elapsedMs(startedAt),
                        haveExit ? exitCode.getValue() : 0, REASON_WAIT, wait);
            }
            return new Result(true, elapsedMs(startedAt),
                    haveExit ? exitCode.getValue() : 0, REASON_OK, 0);
        } finally {
            if (thread != null) {
                kernel32.CloseHandle(thread);
            }
            if (memory != null && process != null) {
                kernel32.VirtualFreeEx(process, memory, 0L, MEM_RELEASE);
            }
            if (process != null) {
                kernel32.CloseHandle(process);
            }
        }
    }

    /** 13 字节桩：{@code 48 B8 imm64 FF D0 C3}（mov rax, addr; call rax; ret）。 */
    public static byte[] stub(long address) {
        final byte[] code = new byte[STUB_LENGTH];
        code[0] = (byte) 0x48;
        code[1] = (byte) 0xB8;
        for (int i = 0; i < 8; i++) {
            code[2 + i] = (byte) ((address >>> (8 * i)) & 0xFFL);
        }
        code[10] = (byte) 0xFF;
        code[11] = (byte) 0xD0;
        code[12] = (byte) 0xC3;
        return code;
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    // ─────────────────────────────── 结果 / JNA ───────────────────────────────

    /** 一次注入的结果（纯数据；日志一行由 {@link #logLine()} 给）。 */
    public static final class Result {

        /** 远程线程是否 <b>真的跑完</b>（{@code WAIT_OBJECT_0}）。 */
        public final boolean ok;
        /** 整个调用耗时（毫秒）。 */
        public final long ms;
        /** 远程线程的退出码（= 被调函数的返回值；{@code void} 函数通常为 0）。 */
        public final int exitCode;
        /** {@link GhostInjector} 的失败点常量；{@code 0} = 成功。 */
        public final int reason;
        /** 失败时的 {@code GetLastError()}（{@code 0} = 没取到 / 不适用）。 */
        public final int error;

        Result(boolean ok, long ms, int exitCode, int reason, int error) {
            this.ok = ok;
            this.ms = ms;
            this.exitCode = exitCode;
            this.reason = reason;
            this.error = error;
        }

        static Result fail(long startedAtNanos, int reason, int error) {
            return new Result(false, elapsedMs(startedAtNanos), 0, reason, error);
        }

        /** 子进程日志的那一行（成功/失败各一种形态，失败时带上 Win32 错误码）。 */
        public String logLine() {
            if (ok) {
                return "ghost_disabled ok=1 ms=" + ms;
            }
            return "ghost_disabled ok=0 reason=" + reason + (error != 0 ? " win32=" + error : "");
        }

        @Override
        public String toString() {
            return logLine() + " exit=" + exitCode;
        }
    }

    /**
     * kernel32 的 JNA 绑定（延迟到第一次用时才 {@code Native.load}）。
     *
     * <p>放在嵌套的 holder 里是为了让<b>没有 JNA 的 classpath</b> 也能加载 {@link GhostInjector}
     * （配置读取 / {@link #stub(long)} 都不需要 JNA）——JNA 缺失时只会在真正注入那一步失败。
     */
    private static final class Kernel32Holder {
        static final Kernel32 INSTANCE = load();

        private static Kernel32 load() {
            try {
                return Native.load("kernel32", Kernel32.class);
            } catch (Throwable t) {
                throw new IllegalStateException("jna_unavailable:" + t.getClass().getSimpleName(), t);
            }
        }
    }

    /** 只用到的 12 个 kernel32 函数（x64：HANDLE/LPVOID/FARPROC 全是 64 位 ⇒ 用 {@link Pointer}）。 */
    private interface Kernel32 extends Library {

        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        Pointer VirtualAllocEx(Pointer process, Pointer address, long size, int allocationType, int protect);

        boolean WriteProcessMemory(Pointer process, Pointer address, byte[] buffer, long size,
                                   LongByReference written);

        Pointer CreateRemoteThread(Pointer process, Pointer threadAttributes, long stackSize,
                                   Pointer startAddress, Pointer parameter, int creationFlags,
                                   IntByReference threadId);

        int WaitForSingleObject(Pointer handle, int milliseconds);

        boolean GetExitCodeThread(Pointer thread, IntByReference exitCode);

        boolean CloseHandle(Pointer handle);

        boolean VirtualFreeEx(Pointer process, Pointer address, long size, int freeType);

        Pointer GetModuleHandleW(WString moduleName);

        Pointer GetProcAddress(Pointer module, String functionName);

        int GetLastError();
    }
}
