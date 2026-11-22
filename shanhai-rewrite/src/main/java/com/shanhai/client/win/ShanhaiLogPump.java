package com.shanhai.client.win;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.win.ShanhaiPump;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * 山海重构 · <b>通用日志钩子</b>：凡有日志，就打一次泵（{@link ShanhaiPump#tick()}）。
 *
 * <h2>1. 为什么是"挂日志"，而不是继续猜插点</h2>
 * <p>泵（{@link com.shanhai.common.win.ShanhaiPump}）原本只挂在两条 mixin 上（FML 主线程同步执行器、
 * GT 配方索引入口）。实测 <b>calls=1</b> —— 等于没挂上：那两条路径在生产环境里根本没有被走到那么多次。
 * <p>而"加载卡住"的两个已知现场，恰好都是<b>日志密集</b>的：插件加载 1712 行 / 25 秒、启动 28 行 / 16 秒。
 * 也就是说，<b>凡是在那段卡顿里还在吐日志的地方，就是最需要抽消息的地方</b>。与其继续猜第三个插点，
 * 不如把"日志"本身当成插点：打出任何一条日志 ⇒ 顺便把这 3 ms 的消息预算用掉。
 *
 * <h2>2. 怎么挂（log4j-core 公开 API，不用反射、不改 Forge 配置）</h2>
 * <pre>
 *   LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
 *   Configuration config = ctx.getConfiguration();
 *   AbstractAppender a = new AbstractAppender("shanhai-pump", null, null, true, Property.EMPTY_ARRAY) {
 *       public void append(LogEvent event) { ShanhaiPump.tick(); }      // 只这一句，立刻 return
 *   };
 *   a.start();
 *   config.addAppender(a);                        // ① 登记进这份 configuration
 *   config.getRootLogger().addAppender(a, Level.ALL, null);   // ② 挂到 root（所有 logger 都会经过它）
 *   ctx.updateLoggers();                          // ③ 让已存在的 Logger 立刻看到新 appender
 * </pre>
 * <p>签名是在本机的 {@code log4j-core-2.19.0.jar} 上用 {@code javap} 核过的：
 * {@code LoggerContext.getContext(boolean)} / {@code getConfiguration()} / {@code updateLoggers()}、
 * {@code LoggerConfig.addAppender(Appender, Level, Filter)}（2.19.0 里<b>只有</b>这个三参版本）、
 * {@code AbstractAppender(String, Filter, Layout, boolean)}（protected 构造）。
 *
 * <h2>3. 🔴 append 里只许做这一件事</h2>
 * <p>log4j 在<b>同一个线程里递归打日志</b>时会把它丢掉（本工程 {@code HotfixTriageTap} 的注释里记过这个坑）。
 * 所以 {@link #append(LogEvent)} <b>只调一次 {@code tick()} 就 return</b>：不打日志、不做 IO、不分配东西。
 * 泵的实现（{@code ShanhaiWinPump#onTick}）自己带着 100 ms 节流与"不在窗口线程就返回"的判断，
 * 因此这条热路径的代价是几次内存比较。
 *
 * <h2>4. 失败面</h2>
 * <p>{@link #install()} <b>整段 {@code try/catch(Throwable)}</b>：log4j-core 不在、context 不是 core 实现、
 * 配置只读……都只打一行 {@code [SHANHAI-PUMP] log_hook=failed reason=<类名>}，<b>绝不影响启动</b>。
 * 成功打一行 {@code [SHANHAI-PUMP] log_hook=on appender=shanhai-pump}。
 * <p><b>幂等</b>：重复 {@link #install()} 不会挂第二个（本类自己有标志，并且还会先查
 * {@code configuration.getAppender("shanhai-pump")} 是否已存在）。
 */
public final class ShanhaiLogPump {

    /** 日志前缀（与 {@code ShanhaiWinPump} 同一个词，grep 一次拿全）。 */
    private static final String PREFIX = "[SHANHAI-PUMP]";

    /** appender 名（也是幂等判据）。 */
    private static final String APPENDER_NAME = "shanhai-pump";

    /** 是否已经装好。 */
    private static volatile boolean installed;

    /** 装好的那个 appender（持有强引用：log4j 的 configuration 里已经有一份，这里只是保险）。 */
    private static volatile AbstractAppender appender;

    private ShanhaiLogPump() {
    }

    /** 是否已装上（诊断用）。 */
    public static boolean installed() {
        return installed;
    }

    /**
     * 在 root logger 上挂一个"每次日志都打一次泵"的 appender（<b>幂等</b>，整段吞异常）。
     *
     * <p>调用点：{@code ShanhaiWinPump#onClientSetup}（客户端 setup，泵自己装好之后紧接着）。
     */
    public static void install() {
        if (installed) {
            return;
        }
        synchronized (ShanhaiLogPump.class) {
            if (installed) {
                return;
            }
            try {
                final LoggerContext context = (LoggerContext) LogManager.getContext(false);
                if (context == null) {
                    failed("no_logger_context");
                    return;
                }
                final Configuration configuration = context.getConfiguration();
                if (configuration == null) {
                    failed("no_configuration");
                    return;
                }
                if (configuration.getAppender(APPENDER_NAME) != null) {
                    // 已经挂过了（例如上一次 install 之后本类的标志被清掉，或别的入口先装过）
                    installed = true;
                    info("log_hook=on appender=" + APPENDER_NAME + " (existing)");
                    return;
                }
                final AbstractAppender created = new AbstractAppender(
                        APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(final LogEvent event) {
                        // 🔴 只这一句（见类注释第 3 节：这里打日志会被 log4j 自己丢掉，也会让泵套娃）
                        ShanhaiPump.tick();
                    }
                };
                created.start();
                configuration.addAppender(created);
                configuration.getRootLogger().addAppender(created, Level.ALL, null);
                context.updateLoggers();
                appender = created;
                installed = true;
                info("log_hook=on appender=" + APPENDER_NAME);
            } catch (final Throwable t) {
                failed(className(t));
            }
        }
    }

    private static void info(final String message) {
        try {
            ShanhaiMod.LOGGER.info("{} {}", PREFIX, message);
        } catch (final Throwable ignored) {
            // 日志系统不可用时丢掉这一行，绝不影响游戏
        }
    }

    /** 失败：只打一行 {@code log_hook=failed reason=<类名>}（不重试、不抛）。 */
    private static void failed(final String reason) {
        try {
            ShanhaiMod.LOGGER.warn("{} log_hook=failed reason={}", PREFIX, reason);
        } catch (final Throwable ignored) {
            // 同上
        }
    }

    /** 异常类全限定名（取不到写 {@code null}）。 */
    private static String className(final Throwable t) {
        try {
            return t == null ? "null" : t.getClass().getName();
        } catch (final Throwable ignored) {
            return "null";
        }
    }
}
