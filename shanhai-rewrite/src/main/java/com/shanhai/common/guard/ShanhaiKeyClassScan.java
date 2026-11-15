package com.shanhai.common.guard;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 山海 · <b>关键类扫描（T 件 ④）：谁改过 LivingEntity / Entity / 事件总线</b>。
 *
 * <h2>它回答什么问题</h2>
 * "有人把 {@code setHealth} 拦了"、"有人把 {@code die} 注了"——这些话平时只能靠读崩溃报告猜。
 * 其实 Mixin 把注入体改成了<b>目标类里的合成方法</b>，名字长这样：
 * <pre>
 *   handler$cni000$shooting_star_demo$quiet          ← 某个 mod 注进来的
 *   redirect$afa000$mymod$onHurt
 *   wrapWithCondition$cni000$create$canPlace
 * </pre>
 * ⇒ 对着关键类取一次 {@code getDeclaredMethods()}，把这些方法数出来、把 modId 段抽出来，
 * 就是<b>"谁改过这个类"的最便宜来源</b>（一次反射，不落地、不改字节码、不进世界也能跑）。
 *
 * <h2>输出（规格点名的形状，一行一个类）</h2>
 * <pre>
 *   [SHANHAI-KEYCLASS] class=net.minecraft.world.entity.LivingEntity injected=7 mods=[create,shooting_star_demo]
 * </pre>
 * 🔴 <b>找不到就如实写 {@code injected=0}，绝不假装</b>（类没加载 ／ 取不到方法表 ⇒ 都是 0）。
 * <p>区分"类没找到"与"类找到了但没人注入"是另一行摘要（{@code scan_done …}，由
 * {@link ShanhaiGuardTriageBridge} 打），<b>不改这一行的格式</b>。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>只读、被动</b>：只调 {@code Class.forName(name, false, loader)}（{@code initialize=false}
 *       —— <b>不触发静态初始化</b>）+ {@code getDeclaredMethods()}，一个字段都不写；</li>
 *   <li><b>不抛</b>：类没找到 ／ 方法签名引用了缺类（{@code NoClassDefFoundError}）／ 安全异常
 *       ⇒ 全部吞掉记成 {@code injected=0}（扫描坏了绝不许影响任何游戏行为）；</li>
 *   <li><b>纯 JDK</b>：只 import {@code java.*} ⇒ 与本包的 {@link ShanhaiGuardTriage} 一样
 *       空 classpath 就能编译、{@link #main} 能离线单跑。</li>
 * </ol>
 *
 * <h2>关键类名单（为什么是这几个）</h2>
 * <pre>
 *   net.minecraft.world.entity.LivingEntity      setHealth/die/hurt 都在这（血量与死亡的总闸）
 *   net.minecraft.world.entity.Entity            setRemoved/remove/discard 都在这（"删掉"的总闸）
 *   net.minecraft.world.entity.player.Player     玩家专属的 hurt/die 分派层
 *   net.minecraft.server.level.ServerPlayer      真实玩家死亡落在这里（它【不调】super.die，见 ValueLayer 注释）
 *   net.minecraft.world.level.Level              伤害入口（damageSources ／ 各种 hurt 分发）
 *   net.minecraft.server.level.ServerLevel       同上，服务端那一半
 *   net.minecraftforge.eventbus.EventBus         事件总线实现（这里是"事件被人吃掉"的总闸）
 *   net.minecraftforge.eventbus.api.IEventBus    事件总线接口
 *   net.minecraftforge.common.MinecraftForge     EVENT_BUS 的持有者
 * </pre>
 * ⚠️ 规格里写的 {@code net.minecraftforge.eventbus.api.EventBus} <b>不存在</b>
 * （6.0.5 的 jar 里只有 {@code api/IEventBus} 与 {@code EventBus}，已用 {@code jar tf} 实证）
 * ⇒ 名单里放的是<b>真实存在</b>的那两个。
 *
 * <p>⚠️ <b>没有</b>做"从 {@code MinecraftForge.EVENT_BUS} 反查所有注册类"这件事：
 * 6.x 的 {@code IEventBus} 没有公开的 listeners 遍历口子，硬做要反射内部表 ⇒ 超出"被动只读"的边界。
 * 这一条写进报告的"没验到的"。
 */
public final class ShanhaiKeyClassScan {

    private ShanhaiKeyClassScan() {}

    /** 本件每行日志的前缀（规格点名）。 */
    public static final String LINE_PREFIX = "[SHANHAI-KEYCLASS] ";

    /** 默认的关键类名单（见类注释；顺序 = 日志顺序）。 */
    public static final String[] DEFAULT_KEY_CLASSES = {
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.level.Level",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraftforge.eventbus.EventBus",
            "net.minecraftforge.eventbus.api.IEventBus",
            "net.minecraftforge.common.MinecraftForge"};

    /** 一个类的扫描结果（不可变）。 */
    public static final class Result {

        /** 全限定名（规格里的 {@code class=}）。 */
        public final String className;
        /** 类拿到了没有（false ⇒ {@link #injected} 一定是 0）。 */
        public final boolean found;
        /** 注入方法条数。 */
        public final int injected;
        /** 从注入方法名里抽出来的 modId 段（已排序去重）。 */
        public final List<String> mods;
        /** 没拿到 ／ 抛异常时的原因（<b>不进</b>规格那一行，只给报告与摘要用）。 */
        public final String note;

        Result(String className, boolean found, int injected, List<String> mods, String note) {
            this.className = className;
            this.found = found;
            this.injected = injected;
            this.mods = mods == null ? Collections.emptyList() : mods;
            this.note = note;
        }

        /** 规格点名的那一行。 */
        public String line() {
            return LINE_PREFIX + "class=" + className
                    + " injected=" + injected
                    + " mods=" + ShanhaiGuardTriage.joinList(mods);
        }

        @Override
        public String toString() {
            return line() + (note == null ? "" : " ／ note=" + note);
        }
    }

    /**
     * 扫一个类。
     *
     * @param className 全限定名（{@code foo.Bar$Baz} 这种二进制名也认）
     * @param loader    用哪个 classloader 找（拿不到 ⇒ 用本类的）
     * @return 结果（<b>永不</b> null；拿不到就是 {@code found=false, injected=0}）
     */
    public static Result scanOne(String className, ClassLoader loader) {
        if (className == null || className.isEmpty()) {
            return new Result(String.valueOf(className), false, 0, Collections.emptyList(), "empty_name");
        }
        ClassLoader cl = loader;
        if (cl == null) {
            cl = ShanhaiKeyClassScan.class.getClassLoader();
        }
        final Class<?> type;
        try {
            type = Class.forName(className, false, cl);   // initialize=false：不触发静态初始化
        } catch (ClassNotFoundException e) {
            return new Result(className, false, 0, Collections.emptyList(), "ClassNotFoundException");
        } catch (LinkageError e) {
            return new Result(className, false, 0, Collections.emptyList(), e.getClass().getSimpleName());
        } catch (Throwable t) {
            return new Result(className, false, 0, Collections.emptyList(), t.getClass().getSimpleName());
        }
        final java.lang.reflect.Method[] methods;
        try {
            methods = type.getDeclaredMethods();
        } catch (LinkageError e) {
            // 方法签名里引用了没加载的类（可选依赖常见）⇒ 如实记 0
            return new Result(className, true, 0, Collections.emptyList(),
                    "getDeclaredMethods:" + e.getClass().getSimpleName());
        } catch (Throwable t) {
            return new Result(className, true, 0, Collections.emptyList(),
                    "getDeclaredMethods:" + t.getClass().getSimpleName());
        }
        final List<String> names = new ArrayList<>(8);
        int injected = 0;
        for (final java.lang.reflect.Method m : methods) {
            if (m == null) {
                continue;
            }
            final String name = m.getName();
            if (ShanhaiGuardTriage.isInjectedMethod(name)) {
                injected++;
                names.add(name);
            }
        }
        return new Result(className, true, injected, ShanhaiGuardTriage.modsOf(names), null);
    }

    /** 扫一批（默认名单见 {@link #DEFAULT_KEY_CLASSES}）。 */
    public static List<Result> scan(ClassLoader loader, String[] classNames) {
        final String[] names = (classNames == null || classNames.length == 0)
                ? DEFAULT_KEY_CLASSES : classNames;
        final List<Result> out = new ArrayList<>(names.length);
        for (final String name : names) {
            out.add(scanOne(name, loader));
        }
        return out;
    }

    /** 扫默认名单。 */
    public static List<Result> scan(ClassLoader loader) {
        return scan(loader, DEFAULT_KEY_CLASSES);
    }

    /** 结果 ⇒ 日志行（一行一个类，顺序 = 名单顺序）。 */
    public static List<String> lines(List<Result> results) {
        final List<String> out = new ArrayList<>();
        if (results == null) {
            return out;
        }
        for (final Result r : results) {
            if (r != null) {
                out.add(r.line());
            }
        }
        return out;
    }

    /** 摘要行：一共几个类、拿到几个、缺了哪些（缺的那几个在明细行里也是 {@code injected=0}，这里点名）。 */
    public static String summaryLine(List<Result> results) {
        int total = 0;
        int found = 0;
        int injected = 0;
        final List<String> missing = new ArrayList<>();
        if (results != null) {
            for (final Result r : results) {
                if (r == null) {
                    continue;
                }
                total++;
                injected += r.injected;
                if (r.found) {
                    found++;
                } else {
                    missing.add(r.className);
                }
            }
        }
        return LINE_PREFIX + "scan_done classes=" + total + " found=" + found
                + " injected_total=" + injected
                + " missing=" + ShanhaiGuardTriage.joinList(missing);
    }

    // ═══════════════════════════════ 自检（纯 JDK，离线跑） ═══════════════════════════════

    /**
     * 自检用的桩：<b>方法名带 {@code $} 是合法的 Java 标识符</b>，所以不用外部 jar 也能造出
     * "跟 Mixin 生成的注入方法一模一样"的样本 ⇒ 扫描器与解析器都能被真反射喂到。
     *
     * <p>⚠️ 这两个桩类会跟着 mod 一起进 jar（各几十字节），这是有意的：离线自检与
     * {@code /shanhai guard selftest} 用的是<b>同一份</b>样本，不依赖测试目录。
     */
    static final class StubInjectedTarget {

        public void handler$cni000$shooting_star_demo$quiet() {}

        public void handler$cni000$shooting_star_demo$loud() {}

        public void redirect$afa000$mymod$onHurt() {}

        public void plainMethod() {}
    }

    /** 自检用的桩（干净类：一条注入方法都不该数出来）。 */
    static final class StubCleanTarget {

        public void plainMethod() {}

        public void anotherMethod() {}
    }

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    /** 自检结果（同 {@link ShanhaiGuardTriage.SelfcheckResult}：命令入口直接回显两个数）。 */
    public static final class SelfcheckResult {

        /** 一共判了几条。 */
        public int checks;
        /** 其中红的几条。 */
        public int reds;

        public boolean green() {
            return reds == 0;
        }

        @Override
        public String toString() {
            return "checks=" + checks + " reds=" + reds;
        }
    }

    private static void expect(SelfcheckResult res, PrintStream out, String group, String name,
                              boolean expected, boolean actual, String reading) {
        res.checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            res.reds++;
        }
        out.println(String.format("%-4s | %-10s | %-52s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    /** 【故意写坏】把所有方法都当注入方法（证明"数出 3 条"不是恒真）。 */
    private static int injectedCountMutantAll(Class<?> type) {
        int n = 0;
        for (final java.lang.reflect.Method m : type.getDeclaredMethods()) {
            n++;
        }
        return n;
    }

    /** 【故意写坏】类没找到也报 found=true（证明"如实报缺失"不是恒真）。 */
    private static boolean foundMutantAlwaysTrue(String className, ClassLoader cl) {
        try {
            Class.forName(className, false, cl);
            return true;
        } catch (Throwable t) {
            return true;   // 故意写坏：照样说"找到了"
        }
    }

    /**
     * 离线自检：{@code javac --release 17 -d <out> ShanhaiGuardTriage.java ShanhaiKeyClassScan.java}
     * ＋ {@code java -cp <out> com.shanhai.common.guard.ShanhaiKeyClassScan}。
     *
     * <p>覆盖：数得对（3 条）／modId 段抽得对／调度干净类得 0／缺类得 {@code injected=0}／
     * 行格式逐字；<b>三条负对照</b>。
     */
    public static SelfcheckResult selfcheck(PrintStream out) {
        final SelfcheckResult res = new SelfcheckResult();
        out.println("=== ShanhaiKeyClassScan 离线自检（纯 JDK + 反射） ===");

        final ClassLoader cl = ShanhaiKeyClassScan.class.getClassLoader();

        // ── ① 数得对：桩类里有 3 条注入方法（+1 条普通方法）──
        final Result injected = scanOne(StubInjectedTarget.class.getName(), cl);
        expect(res, out, "①扫描", "桩类拿到了", true, injected.found, injected.toString());
        expect(res, out, "①扫描", "数出 3 条注入方法（普通方法不算）", true, injected.injected == 3,
                "injected=" + injected.injected);
        expect(res, out, "①扫描", "modId 段抽出来并排序去重 = [mymod,shooting_star_demo]", true,
                "[mymod,shooting_star_demo]".equals(ShanhaiGuardTriage.joinList(injected.mods)),
                ShanhaiGuardTriage.joinList(injected.mods));

        // ── ① 行格式（规格点名）──
        final String line = injected.line();
        expect(res, out, "①格式", "行首是 [SHANHAI-KEYCLASS]", true, line.startsWith(LINE_PREFIX), line);
        expect(res, out, "①格式", "含 class=<全限定名>", true,
                line.contains("class=" + StubInjectedTarget.class.getName()), line);
        expect(res, out, "①格式", "含 injected=3", true, line.contains("injected=3"), line);
        expect(res, out, "①格式", "含 mods=[mymod,shooting_star_demo]", true,
                line.contains("mods=[mymod,shooting_star_demo]"), line);

        // ── ② 干净类：必须 0（否则"数出来"没有意义）──
        final Result clean = scanOne(StubCleanTarget.class.getName(), cl);
        expect(res, out, "②干净类", "干净类拿到了", true, clean.found, "");
        expect(res, out, "②干净类", "干净类 injected=0", true, clean.injected == 0,
                "injected=" + clean.injected);
        expect(res, out, "②干净类", "干净类 mods=[]（不写 [null]）", true,
                "[]".equals(ShanhaiGuardTriage.joinList(clean.mods)), clean.line());
        expect(res, out, "②干净类", "干净类的行里写的是 injected=0", true,
                clean.line().contains("injected=0"), clean.line());

        // ── ③ 缺类：如实 0，且 found=false ──
        final Result missing = scanOne("fake.definitely.NotThere", cl);
        expect(res, out, "③缺类", "缺类 found=false（不假装）", false, missing.found, missing.note);
        expect(res, out, "③缺类", "缺类 injected=0", true, missing.injected == 0, missing.line());
        expect(res, out, "③缺类", "缺类的行照样打出来（injected=0 mods=[]）", true,
                missing.line().startsWith(LINE_PREFIX + "class=fake.definitely.NotThere injected=0 mods=[]"),
                missing.line());

        // ── ④ 批量 ＋ 摘要行 ──
        final String[] batch = {
                StubInjectedTarget.class.getName(),
                StubCleanTarget.class.getName(),
                "fake.definitely.NotThere"};
        final List<Result> results = scan(cl, batch);
        expect(res, out, "④批量", "扫 3 个类得 3 条明细", true, lines(results).size() == 3,
                "lines=" + lines(results).size());
        expect(res, out, "④批量", "摘要行：classes=3 found=2 missing=[…NotThere]", true,
                summaryLine(results).contains("classes=3")
                        && summaryLine(results).contains("found=2")
                        && summaryLine(results).contains("missing=[fake.definitely.NotThere]"),
                summaryLine(results));
        expect(res, out, "④批量", "摘要行的 injected_total = 3（干净类与缺类都是 0）", true,
                summaryLine(results).contains("injected_total=3"), summaryLine(results));

        // ── ④ 默认名单的形状（离线时它们全都不是 MC 类 ⇒ 一律 0，但必须一条不少）──
        final List<Result> dflt = scan(cl, DEFAULT_KEY_CLASSES);
        expect(res, out, "④默认名单", "默认名单 9 个类各一条（不多不少）", true,
                dflt.size() == DEFAULT_KEY_CLASSES.length, "实际 " + dflt.size());
        expect(res, out, "④默认名单", "离线环境下它们一个都加载不到 ⇒ 全部 injected=0", true,
                allZero(dflt), summaryLine(dflt));
        expect(res, out, "④默认名单", "名单里没有规格写错的那个不存在的类名", false,
                containsName(DEFAULT_KEY_CLASSES, "net.minecraftforge.eventbus.api.EventBus"),
                "已换成真实存在的 api.IEventBus / EventBus");

        // ── 负对照 ──
        expect(res, out, "⑤负对照", "『所有方法都算注入』的退化版在干净类上会数出 2（证明 0 是判据给的）",
                true, injectedCountMutantAll(StubCleanTarget.class) == 2,
                "退化版 = " + injectedCountMutantAll(StubCleanTarget.class));
        expect(res, out, "⑤负对照", "同一退化版在桩类上数出 4（第 4 条其实是普通方法 ⇒ 说明它不分青红皂白）",
                true, injectedCountMutantAll(StubInjectedTarget.class) == 4,
                "退化版 = " + injectedCountMutantAll(StubInjectedTarget.class));
        expect(res, out, "⑤负对照", "『缺类也报找到了』的退化版在缺类上返回 true（证明如实报缺失不是恒真）",
                true, foundMutantAlwaysTrue("fake.definitely.NotThere", cl), "");

        out.println("---------------------------------------------------------------");
        out.println("合计 " + res.checks + " 条，红 " + res.reds + " 条 ⇒ " + (res.green() ? "全绿 PASS" : "有红 FAIL"));
        return res;
    }

    private static boolean allZero(List<Result> results) {
        for (final Result r : results) {
            if (r.injected != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsName(String[] names, String want) {
        for (final String n : names) {
            if (want.equals(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 离线自检入口：
     * {@code javac --release 17 -d <out> ShanhaiGuardTriage.java ShanhaiKeyClassScan.java} ＋
     * {@code java -cp <out> com.shanhai.common.guard.ShanhaiKeyClassScan}。
     */
    public static void main(String[] args) {
        final SelfcheckResult res = selfcheck(OUT);
        if (!res.green()) {
            System.exit(1);
        }
    }
}
