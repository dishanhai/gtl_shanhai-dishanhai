package com.shanhai.machine.creation;

/**
 * 「界面诊断开关」的<b>纯逻辑</b>（<b>零</b> Minecraft / LDLib 依赖 ⇒ 可离线自证"默认关闭"）。
 *
 * <h2>它解决什么问题</h2>
 * 用户两次实机回报「界面下面还是 color」这件事，而全仓源码里那个带引号的字面量命中 <b>0</b>
 * （P4 已做过全库 + 四个依赖 jar 的逐字节扫描，见 {@code temp/molmanip/P4界面修正.md}）。
 * ⇒ 靠"读代码猜"已经<b>失败过一次</b>，P5 改走<b>实测</b>：把界面里每一个控件的<b>类名 + 序列化出来的文本</b>
 * 打一行日志，让日志直接指认"哪个控件印出那三个字母"。
 *
 * <h2>🔴 默认零日志</h2>
 * 只有环境变量 {@value #WIDGET_DUMP_ENV}={@code 1} 时 {@link #widgetDumpEnabled()} 才为 {@code true}；
 * 没给这个变量时：<b>不遍历、不打印、不改任何控件</b>（默认外观一像素不动）。
 *
 * <h2>为什么"序列化出来的文本"能抓到它</h2>
 * LDLib 的 {@code LabelWidget#writeInitialData(FriendlyByteBuf)} 在非 clientSideWidget 时会把
 * {@code component} 用 {@code FriendlyByteBuf#writeComponent} 写进去（{@code javap -c} 逐偏移核过）
 * ⇒ 把这段字节按 UTF-8 解出来就能看到<b>这个控件真正要画的字</b>，包括那个来路不明的标记。
 */
public final class CreationDiagnostics {

    /** 诊断开关的环境变量名（<b>唯一</b>一处定义）。 */
    public static final String WIDGET_DUMP_ENV = "SHANHAI_WIDGET_DUMP";

    /** 诊断日志前缀（要 grep 的就是它）。 */
    public static final String WIDGET_DUMP_PREFIX = "[SHANHAI-GUIDUMP]";

    /** 单个控件的文本最多打多少字符（防止一行日志过长）。 */
    public static final int MAX_TEXT_CHARS = 160;

    private CreationDiagnostics() {
    }

    /** 进程级缓存：环境变量在 JVM 生命周期内不会变，读一次就够（也避免每帧查环境）。 */
    private static final class Holder {
        static final boolean ENABLED = parseWidgetDumpSwitch(System.getenv(WIDGET_DUMP_ENV));
    }

    /** 诊断开关此刻是否打开（默认 {@code false}）。 */
    public static boolean widgetDumpEnabled() {
        return Holder.ENABLED;
    }

    /**
     * 开关解析的<b>纯函数</b>形态（离线自证用它逐条断言）。
     *
     * <p>只有 {@code "1"} / {@code "true"}（大小写与首尾空白不敏感）算打开；
     * {@code null} / 空串 / 其它任何值都算关闭 —— <b>"不认识就当关"</b>，绝不因为一个笔误开始刷日志。
     */
    public static boolean parseWidgetDumpSwitch(String rawValue) {
        if (rawValue == null) {
            return false;
        }
        final String trimmed = rawValue.trim();
        return "1".equals(trimmed) || "true".equalsIgnoreCase(trimmed);
    }

    /**
     * 一条控件诊断行的<b>唯一格式</b>。
     *
     * @param where     整棵树的位置标签（哪一页 / 哪一层）
     * @param className 控件类名（{@code getClass().getSimpleName()}）
     * @param text      控件要画的文本（取不到就传 {@code null} 或空串）
     */
    public static String widgetDumpLine(String where, String className, String text) {
        final String safeWhere = where == null || where.isEmpty() ? "?" : where;
        final String safeClass = className == null || className.isEmpty() ? "?" : className;
        final String shown = sanitizeText(text);
        return WIDGET_DUMP_PREFIX + " where=" + safeWhere + " class=" + safeClass
                + (shown.isEmpty() ? " text=<取不到>" : " text=" + shown);
    }

    /**
     * 把控件文本压成"一行、可 grep"的样子：换行/制表变空格、{@code §} 颜色码转义成 {@code ~}、
     * 丢掉不可打印控制字符、超长截断。
     *
     * <p>为什么要转义 {@code §}：否则日志里那一行的后半段会被终端/日志查看器当成颜色码吞掉，
     * 恰恰把要找的字藏起来。
     */
    public static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_TEXT_CHARS + 8));
        for (int i = 0; i < text.length() && sb.length() < MAX_TEXT_CHARS; i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
            } else if (c == '\u00a7') {
                sb.append('~');
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (text.length() > MAX_TEXT_CHARS) {
            out = out + "…(截断)";
        }
        return out;
    }

    /**
     * 从 {@code FriendlyByteBuf} 序列化出来的字节里<b>捞可打印文本</b>（诊断用，尽力而为）。
     *
     * <p>做法：按 UTF-8 解，保留"连续可打印字符段"长度 &ge; {@code minRun} 的那些段，用 {@code |} 连起来。
     * 它是<b>取证工具</b>而不是协议解析：只要能把要找的那几个字母捞出来就达成目的。
     */
    public static String printableRuns(byte[] raw, int minRun) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        final int need = Math.max(1, minRun);
        String decoded;
        try {
            decoded = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < decoded.length(); i++) {
            final char c = decoded.charAt(i);
            final boolean printable = c == ' ' || c == ':' || c == '_' || c == '-' || c == '.' || c == '/'
                    || c == '[' || c == ']' || c == '(' || c == ')' || c == '%' || c == '+'
                    || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3000 && c <= 0x303f)
                    || (c >= 0xff00 && c <= 0xffef);
            if (printable) {
                run.append(c);
            } else {
                appendRun(out, run, need);
            }
            if (out.length() > MAX_TEXT_CHARS) {
                break;
            }
        }
        appendRun(out, run, need);
        return out.toString();
    }

    private static void appendRun(StringBuilder out, StringBuilder run, int need) {
        if (run.length() >= need) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(run.toString().trim());
        }
        run.setLength(0);
    }
}
