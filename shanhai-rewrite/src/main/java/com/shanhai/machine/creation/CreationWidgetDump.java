package com.shanhai.machine.creation;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.LoggerFactory;

/**
 * <b>界面控件诊断 dump（临时工具，默认关闭）</b> —— 用来一次性指认"哪个控件印出 color"。
 *
 * <h2>为什么需要它（P4 的失败记录）</h2>
 * 用户两次实机回报「界面下面还是 color」，而全仓源码里那个带引号的字面量命中 <b>0</b>、
 * 四个依赖 jar 里 {@code ldc} 出它的类只有 4 个且全是 JSON/XML 字段名
 * （证据见 {@code temp/molmanip/P4界面修正.md} 第 1 条）。⇒ <b>靠读代码猜已经失败过一次</b>，
 * 这一轮改成"把界面里每个控件的类名与它要画的字打出来"，让日志直接指认。
 *
 * <h2>怎么打开（默认不生效）</h2>
 * 环境变量 {@code SHANHAI_WIDGET_DUMP=1} ⇒ 游戏启动后<b>不需要任何按键</b>，
 * 打开本机的样板页 / 神锻页时就会在日志里出现 {@code [SHANHAI-GUIDUMP]} 行。
 * <ul>
 *   <li>不给这个变量 ⇒ {@link CreationDiagnostics#widgetDumpEnabled()} 恒 {@code false}
 *       ⇒ <b>不遍历、不打印、不改任何控件</b>（默认外观一像素不动）；</li>
 *   <li>给了 ⇒ ① 整棵界面树逐控件打一行（类名 + 位置/尺寸 + 从 {@code writeInitialData}
 *       里捞出来的文本）；② 额外做一次<b>减法</b>：本机"底部那几行我们自己画的 LabelWidget"
 *       <b>整段不添加</b>，并把它们的文本单独打成 {@code [SHANHAI-GUIDUMP] (减法)} 行
 *       ⇒ 若那个标记随之消失 ⇒ 是我们加的；若仍在 ⇒ 不是我们画的（再用 ① 的行抓它）。</li>
 * </ul>
 *
 * <h2>为什么"文本"能取到</h2>
 * LDLib 的 {@code LabelWidget#writeInitialData(FriendlyByteBuf)} 在非 clientSideWidget 时会把
 * {@code component} 用 {@code FriendlyByteBuf#writeComponent} 写进缓冲区（{@code javap -c} 逐偏移核过），
 * 所以把那段字节按 UTF-8 解、保留连续可打印段，就能看到这个控件真正要画的字
 * （见 {@link CreationDiagnostics#printableRuns(byte[], int)}）。
 * <p>⚠️ 这是<b>取证工具</b>而不是协议解析：任何控件取不到文本（抛异常 / 无文本）都只影响那一行日志，
 * 绝不影响界面本身（每次调用都包 try/catch）。
 */
final class CreationWidgetDump {

    /** 一次 dump 最多打多少行（防爆日志：1000 格样板页一页就有 50 个槽）。 */
    private static final int MAX_LINES = 400;

    /** dump 行的日志器名（前缀 {@code [SHANHAI-GUIDUMP]} 在每行文本里）。 */
    private static final String LOGGER_NAME = "shanhai/guidump";

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private CreationWidgetDump() {
    }

    /** 诊断开关（{@code SHANHAI_WIDGET_DUMP=1} 才为 true；另有下面那 3 次自动 dump）。 */
    static boolean enabled() {
        return CreationDiagnostics.widgetDumpEnabled() || AUTO_DUMP_BUDGET.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    /**
     * 🔴 <b>默认自动 dump 的剩余次数（3 次）</b> —— 用户不方便去设环境变量（PCL 启动器里改环境变量很麻烦），
     * 所以这一版让它<b>默认就打前 3 次</b>：用户打开样板页/神锻页各一次，日志里就有
     * {@code [SHANHAI-GUIDUMP]} 行 ⇒ 主代理拿日志指认"哪个控件印出 color"，下一版删掉这个预算。
     * <p>⚠️ 只影响<b>日志</b>：减法实验（会改变界面外观）仍只由环境变量触发，见 {@link #subtractMode()}。
     */
    private static final java.util.concurrent.atomic.AtomicInteger AUTO_DUMP_BUDGET =
            new java.util.concurrent.atomic.AtomicInteger(3);

    /** "减法模式"只由环境变量开启（它会改界面外观，不能默认打开）。 */
    static boolean subtractMode() {
        return CreationDiagnostics.widgetDumpEnabled();
    }

    /**
     * 把整棵界面树打一遍（<b>只在开关打开时</b>做任何事）。
     *
     * @param where 位置标签（例如 {@code "pattern-page"}）
     * @param root  根控件（本机拿到的是 {@code FancyMachineUIWidget}，也就是整屏）
     */
    static void dumpTree(String where, Widget root) {
        if (!enabled() || root == null) {
            return;
        }
        try {
            final int[] budget = {MAX_LINES};
            dumpRecursive(where, root, 0, budget);
            if (budget[0] <= 0) {
                log(CreationDiagnostics.widgetDumpLine(where, "(已截断)", "达到 " + MAX_LINES + " 行上限"));
            }
        } catch (Throwable t) {
            log(CreationDiagnostics.widgetDumpLine(where, "(dump 失败)", t.toString()));
        }
    }

    private static void dumpRecursive(String where, Widget widget, int depth, int[] budget) {
        if (widget == null || budget[0] <= 0) {
            return;
        }
        budget[0]--;
        log(CreationDiagnostics.widgetDumpLine(where + "/" + depth,
                widget.getClass().getSimpleName() + positionOf(widget), peekText(widget)));
        if (widget instanceof WidgetGroup group) {
            for (Widget child : group.widgets) {
                dumpRecursive(where, child, depth + 1, budget);
            }
        }
    }

    /** 控件坐标（拿不到就是空串；诊断信息，不值得让它抛异常）。 */
    private static String positionOf(Widget widget) {
        try {
            return " @(" + widget.getPosition().x + "," + widget.getPosition().y
                    + " " + widget.getSize().width + "x" + widget.getSize().height + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 猜一个控件要画的文本（尽力而为）：把它 {@code writeInitialData} 出来的字节按 UTF-8 里
     * 的可打印段捞出来。<b>不保证</b>每个控件都有文本（按钮/槽位本来就是图形 + tooltip）。
     *
     * <h2>🔴 P8 补强：{@code LabelWidget} 走反射读真字段，不再靠猜</h2>
     * 旧版对每个控件都只 dump {@code writeInitialData} 的字节，实机 147 行里
     * <b>每一行都是 {@code text=<取不到>}</b> ⇒ 等于没有证据。
     * LDLib 的 {@code LabelWidget} 有三个真字段（{@code javap -p} 实证）：
     * <pre>
     *   protected Supplier&lt;String&gt; textSupplier;   // 每 tick 现算的文本
     *   protected Component component;              // 非 null 时"原样画这条 Component"（不过 I18n）
     *   private   String lastTextValue;             // 真正要画的那串（String 分支会再过一次 I18n）
     * </pre>
     * ⇒ 三个都打出来，下一次实机就能看见"这个 LabelWidget 到底要画什么"。
     */
    static String peekText(Widget widget) {
        if (widget == null) {
            return "";
        }
        if (widget instanceof com.lowdragmc.lowdraglib.gui.widget.LabelWidget label) {
            final String real = labelFields(label);
            if (real != null && !real.isEmpty()) {
                return real;
            }
        }
        try {
            final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                widget.writeInitialData(buffer);
                final byte[] raw = new byte[buffer.readableBytes()];
                buffer.readBytes(raw);
                return CreationDiagnostics.printableRuns(raw, 3);
            } finally {
                buffer.release();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * {@code LabelWidget} 的真实字段内容（反射；任何一步取不到就返回 {@code null} 交给字节兜底）。
     *
     * <p>⚠️ 纯诊断：只读、只在本类 {@link #enabled()} 时会走到，任何异常都吞掉（绝不影响界面）。
     */
    private static String labelFields(com.lowdragmc.lowdraglib.gui.widget.LabelWidget label) {
        try {
            final Class<?> c = com.lowdragmc.lowdraglib.gui.widget.LabelWidget.class;
            final java.lang.reflect.Field fSupplier = c.getDeclaredField("textSupplier");
            final java.lang.reflect.Field fComponent = c.getDeclaredField("component");
            final java.lang.reflect.Field fLast = c.getDeclaredField("lastTextValue");
            fSupplier.setAccessible(true);
            fComponent.setAccessible(true);
            fLast.setAccessible(true);
            final Object supplier = fSupplier.get(label);
            final Object component = fComponent.get(label);
            final Object last = fLast.get(label);
            String supplied = "<supplier=null>";
            if (supplier instanceof java.util.function.Supplier<?> s) {
                try {
                    supplied = String.valueOf(s.get());
                } catch (Throwable t) {
                    supplied = "<supplier.get() 抛 " + t.getClass().getSimpleName() + ">";
                }
            }
            return "lastText=" + CreationDiagnostics.sanitizeText(String.valueOf(last))
                    + " supplier=" + CreationDiagnostics.sanitizeText(supplied)
                    + " component=" + (component == null ? "null"
                    : CreationDiagnostics.sanitizeText(String.valueOf(component)));
        } catch (Throwable t) {
            return null;
        }
    }

    /** "减法"实验：这一行本机自己画的 LabelWidget 这一次<b>不添加</b>，只把它的文本打出来。 */
    static void logSubtracted(String where, String text) {
        if (!enabled()) {
            return;
        }
        log(CreationDiagnostics.WIDGET_DUMP_PREFIX + " (减法) where=" + where + " 本次不添加的 LabelWidget text="
                + CreationDiagnostics.sanitizeText(text));
    }

    /** dump 行统一出口（每行都带 {@code [SHANHAI-GUIDUMP]} 前缀，可 grep）。 */
    static void log(String line) {
        LOGGER.info(line);
    }

    /** 日志器名（诊断/排查引用；不参与逻辑）。 */
    static String loggerName() {
        return LOGGER_NAME;
    }
}
