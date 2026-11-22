package com.shanhai.add.hotfix;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🆕 2026-11-21（根因②＋③）：<b>原始字节基线 ＋ 身份章</b>。
 *
 * <h2>基线解决什么</h2>
 * 现场（2026-11-20 22:56）三次 {@code patch_repair} 的字节长度一路变短
 * （{@code 31902 → 31360 → 31002}）—— 这是"<b>在已被改过的字节上再改</b>"的唯一形状。
 * 从这一轮起：<b>首次见到某类时抓住它的原始 bytes 存快照</b>，之后<i>任何</i>重打都
 * <b>从快照出发</b>（再叠加 {@link HotfixPatchPlan} 的整张计划），<b>禁止在"当前字节"上叠加</b>。
 *
 * <p>"首次见到"的取点在 {@code PatchTransformer#transform} 的第一个参数
 * {@code classfileBuffer}：{@code retransformClasses} 交下来的永远是<b>初始基线</b>
 * （实测见 {@code temp\hotfix\读回取证.md} 的 P1），所以第一次回调收到的就是原样的类字节。
 *
 * <h2>身份章解决什么、以及它<b>不能</b>解决什么</h2>
 * 打完记录 {@code (class, sha256 of patched bytes)}。想核对"类里现在是不是我们那份"就要
 * <b>读到当前字节</b> —— 而我们<b>读不到</b>（P1：{@code retransform} 只给基线；
 * 一次"全 null"的 retransform 还会把类退回基线，见 {@code HotfixPatchReadback} 的 P2）。
 * ⇒ 身份章只能退化成<b>记账</b>：<i>"我上次写进去的是什么 sha"</i>。
 * 日志里凡是这种口径<b>必须如实写</b> {@code identity=bookkeeping_only}，
 * 判据换到行为侧（{@link HotfixPatchEffect}），<b>绝不许伪造"读到了当前字节"</b>。
 *
 * <h2>为什么这个类只碰 {@code java.*}</h2>
 * 与 {@link HotfixPaths}／{@link HotfixPatchPlan} 同样的理由：离线自检要能直接跑它。
 */
public final class HotfixBaseline {

    /** 基线来源：首次见到这个类时抓的（唯一允许的"原始"来源）。 */
    public static final String SOURCE_FIRST_SEEN = "first_seen";

    /** 身份章的口径：只能靠我们自己的记账（读不到当前字节）。 */
    public static final String IDENTITY_BOOKKEEPING_ONLY = "bookkeeping_only";

    /** 基线快照最多留多少个类（防泄漏；快照是整份 class 字节，别无限留）。 */
    public static final int MAX_SNAPSHOTS = 64;

    /** 一份基线快照。 */
    public static final class Snapshot {

        public final String internalName;

        /** 🔴 原始字节（首次见到时的那一份；只读用途，调用方不许改）。 */
        public final byte[] bytes;

        /** sha256 前 16 位（十六进制小写）。 */
        public final String sha;

        public final long atMillis;

        Snapshot(String internalName, byte[] bytes, String sha, long atMillis) {
            this.internalName = internalName;
            this.bytes = bytes;
            this.sha = sha;
            this.atMillis = atMillis;
        }

        public int length() {
            return bytes == null ? 0 : bytes.length;
        }
    }

    private static final Map<String, Snapshot> SNAPSHOTS = new LinkedHashMap<String, Snapshot>();

    /** 身份章：{@code 内部名 ⇒ 我们上次写下去那份字节的 sha256 前 16 位}。 */
    private static final Map<String, String> STAMPS = new LinkedHashMap<String, String>();

    /** 基线落盘目录（诊断用；空串 ⇒ 不落盘）。由装配胶水注入，不写路径字面量。 */
    private static volatile String dumpDir = "";

    /** 落盘失败只记一次（否则每个新类都会刷一行）。 */
    private static volatile String lastDumpError = "";

    private HotfixBaseline() {
        // 纯静态
    }

    /**
     * <b>抓住基线</b>（只有在没有快照时才真的存；已有 ⇒ 原样返回，绝不覆盖）。
     *
     * <p>🔴 绝不覆盖是有意的：第二次回调收到的 {@code classfileBuffer} 可能已经是
     * "被我们改过的那份"或"合并过常量池的底座"（{@code HotfixBytePatch} 的类注释有实测），
     * 一旦拿它覆盖基线，后面所有重打就都站在流沙上了 —— 那正是本次事故的形状。
     *
     * @param internalName 内部名（{@code a/b/C}）
     * @param classfileBuffer transformer 收到的字节（第一次 = 原始基线）
     * @param nowMillis    现在
     * @return 这一类的基线快照（拿不到 ⇒ {@code null}）
     */
    public static Snapshot ensure(String internalName, byte[] classfileBuffer, long nowMillis) {
        if (internalName == null || internalName.isEmpty() || classfileBuffer == null) {
            return null;
        }
        synchronized (SNAPSHOTS) {
            final Snapshot old = SNAPSHOTS.get(internalName);
            if (old != null) {
                return old;
            }
            if (SNAPSHOTS.size() >= MAX_SNAPSHOTS) {
                return null;          // 表满了：不记新基线（宁可不打，也不许把内存吃光）
            }
            final byte[] copy = classfileBuffer.clone();
            final Snapshot snap = new Snapshot(internalName, copy, sha16(copy), nowMillis);
            SNAPSHOTS.put(internalName, snap);
            dumpAsyncSafe(internalName, copy);
            return snap;
        }
    }

    /** 取基线（没有 ⇒ {@code null}）。 */
    public static Snapshot snapshot(String internalName) {
        if (internalName == null) {
            return null;
        }
        synchronized (SNAPSHOTS) {
            return SNAPSHOTS.get(internalName);
        }
    }

    /** 基线字节（没有 ⇒ {@code null}）。</b>只读：调用方不许改它。 */
    public static byte[] bytes(String internalName) {
        final Snapshot s = snapshot(internalName);
        return s == null ? null : s.bytes;
    }

    /** 有几个类的基线（{@code status} 用）。 */
    public static int count() {
        synchronized (SNAPSHOTS) {
            return SNAPSHOTS.size();
        }
    }

    // ═══════════════════════════════ 身份章 ═══════════════════════════════

    /** 记下"我们刚写进去的那份字节的 sha"（身份章的<b>唯一</b>写入点）。 */
    public static void stamp(String internalName, byte[] patchedBytes) {
        if (internalName == null || internalName.isEmpty() || patchedBytes == null) {
            return;
        }
        stampSha(internalName, sha16(patchedBytes));
    }

    /**
     * 同上，但直接给 sha —— {@code PatchTransformer} 已经在回调里算好了 sha，
     * 这里不再拿"retransform 之后的字节"去重算（那个时点我们手里已经没有那份字节了）。
     */
    public static void stampSha(String internalName, String sha) {
        if (internalName == null || internalName.isEmpty() || sha == null || sha.isEmpty()) {
            return;
        }
        synchronized (STAMPS) {
            STAMPS.put(internalName, sha);
        }
    }

    /** 我们上次写进去的 sha；没写过 ⇒ {@code null}。 */
    public static String stampedSha(String internalName) {
        if (internalName == null) {
            return null;
        }
        synchronized (STAMPS) {
            return STAMPS.get(internalName);
        }
    }

    /** 清掉一个类的身份章（显式 undo 之后：类已经回到原始字节，"上次写的是我"这件事不再成立）。 */
    public static void unstamp(String internalName) {
        if (internalName == null) {
            return;
        }
        synchronized (STAMPS) {
            STAMPS.remove(internalName);
        }
    }

    // ═══════════════════════════════ 落盘（诊断，best effort） ═══════════════════════════════

    /** 装配时注入落盘目录（{@code config/shanhai/hotfix-baseline}）；空串 = 不落盘。 */
    public static void bindDumpDir(String dir) {
        dumpDir = dir == null ? "" : dir;
    }

    public static String dumpDir() {
        return dumpDir;
    }

    /**
     * 落盘一个类（{@code <class>.bin}）。<b>绝不抛</b>：落盘只是为了让现场可以离线复核
     * "我们当初到底抓到的是哪一份字节"，它失败不许影响任何动作。
     */
    private static void dumpAsyncSafe(String internalName, byte[] bytes) {
        final String dir = dumpDir;
        if (dir == null || dir.isEmpty()) {
            return;
        }
        try {
            final File d = new File(dir);
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                noteDumpError("mkdirs_failed");
                return;
            }
            // 类名里的 / 换成 . （与日志里的类名逐字一致，便于对着 grep）
            final String base = internalName.replace('/', '.').replace('\\', '.');
            final File f = new File(d, base + ".bin");
            Files.write(f.toPath(), bytes);
        } catch (Throwable t) {
            noteDumpError(t.getClass().getSimpleName());
        }
    }

    private static void noteDumpError(String what) {
        if (!what.equals(lastDumpError)) {
            lastDumpError = what;
        }
    }

    /** 上一次落盘失败的类型（空串 = 没失败过）。 */
    public static String lastDumpError() {
        return lastDumpError;
    }

    // ═══════════════════════════════ sha ═══════════════════════════════

    /**
     * {@code sha256} 的前 16 位十六进制（小写）。
     *
     * <p>为什么只要前 16 位：日志里一行要塞 2~3 个 sha，全长 64 位会把真正有用的字段挤到屏幕外；
     * 16 位（64 bit）足以判"是不是同一份"，判不了的是"伪造碰撞"——那不在我们的威胁模型里。
     */
    public static String sha16(byte[] bytes) {
        if (bytes == null) {
            return "?";
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < d.length; i++) {
                final int v = d[i] & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 停服／退出世界：快照与身份章一起清（下一局重新抓基线）。 */
    public static void reset() {
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.clear();
        }
        synchronized (STAMPS) {
            STAMPS.clear();
        }
    }

    /** 现在手里有哪些类（{@code status}／日志用；只给名字）。 */
    public static String classList() {
        final StringBuilder sb = new StringBuilder(64);
        synchronized (SNAPSHOTS) {
            for (Iterator<String> it = SNAPSHOTS.keySet().iterator(); it.hasNext(); ) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(it.next());
            }
        }
        return sb.toString();
    }

    // ═══════════════════════════ 日志行（唯一拼装处） ═══════════════════════════

    /** {@code patch_baseline class=… sha=<前16> bytes=<N> source=first_seen}。 */
    public static String baselineLine(String className, Snapshot snap) {
        return "patch_baseline class=" + HotfixAutoCounter.token(className)
                + " sha=" + (snap == null ? "?" : snap.sha)
                + " bytes=" + (snap == null ? 0 : snap.length())
                + " source=" + SOURCE_FIRST_SEEN;
    }

    /**
     * {@code patch_rebuild class=… base=orig|live sha_before=<…> sha_after=<…> methods=<N>}。
     *
     * <p>{@code base=orig} 表示"这一次是从基线快照出发重建的"；{@code base=live} 表示
     * 快照没拿到、只能拿 JVM 交下来的那份（那种情况下要如实让人知道"这一趟不是从原始字节出发"）。
     */
    public static String rebuildLine(String className, boolean fromSnapshot, String shaBefore, String shaAfter,
                                     int methods) {
        return "patch_rebuild class=" + HotfixAutoCounter.token(className)
                + " base=" + (fromSnapshot ? "orig" : "live")
                + " sha_before=" + (shaBefore == null ? "?" : shaBefore)
                + " sha_after=" + (shaAfter == null ? "?" : shaAfter)
                + " methods=" + methods;
    }

    /**
     * {@code foreign_write_detected class=… expected=<…> actual=<…> identity=bookkeeping_only evidence=…}。
     *
     * <p>🔴 这条日志的判据是<b>行为侧</b>（{@link HotfixPatchEffect} 判 {@code ineffective}），
     * 不是字节对照 —— 我们读不到当前字节。{@code actual} 只允许写 {@value #ACTUAL_UNREADABLE}。
     */
    public static final String ACTUAL_UNREADABLE = "unreadable";

    public static String foreignWriteLine(String className, String expected, String evidence) {
        return "foreign_write_detected class=" + HotfixAutoCounter.token(className)
                + " expected=" + (expected == null || expected.isEmpty() ? "?" : expected)
                + " actual=" + ACTUAL_UNREADABLE
                + " identity=" + IDENTITY_BOOKKEEPING_ONLY
                + " evidence=" + HotfixAutoCounter.token(evidence);
    }
}
