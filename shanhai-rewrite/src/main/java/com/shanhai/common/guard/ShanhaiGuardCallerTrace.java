package com.shanhai.common.guard;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 山海 · <b>拦截取证（A 件）：被我们拦下的那一拍，外部调用方到底是谁</b>。
 *
 * <h2>为什么需要它（现场）</h2>
 * <pre>
 *   每秒 14~30 次：[SHANHAI-GUARD] 拦截 移除 ｜ 受保护玩家被 setRemoved(reason=KILLED) ⇒ 拦下
 * </pre>
 * 拦是拦住了（玩家没被删掉），但<b>谁在每秒几十次地调 {@code setRemoved(KILLED)}</b> 一直只能靠猜。
 * 本类的唯一职责：<b>在被拦下的那一拍把调用栈里的"外部 mod 帧"打出来</b>，让"是谁"从猜变成读。
 *
 * <h2>输出格式（一行一条，规格点名）</h2>
 * <pre>
 *   [SHANHAI-GUARD] removal_caller#1 at=com.example.boss.Foo#onTick:123 frames=com.example.boss.Foo#onTick:123 | com.example.boss.Foo$1#run:45
 *   [SHANHAI-GUARD] die_caller#1     at=...                          frames=...
 * </pre>
 * 全都被过滤掉（一个外部帧都不剩）时写 {@code at=(none) frames=(none)} —— <b>不假装</b>。
 *
 * <h2>过滤口径（"外部 mod 帧"）</h2>
 * <b>规格点名的五个前缀</b>不算外部帧（{@link #SPEC_SKIP_PREFIXES}）：
 * {@code java.*} ／ {@code net.minecraft.*} ／ {@code net.minecraftforge.*} ／ {@code org.spongepowered.*} ／
 * {@code com.shanhai.*}（最后一个是<b>我们自己</b>：本类 → 执行层 → mixin 那三帧）。
 * <p>另外我加了一小串<b>框架噪声</b>前缀（{@link #EXTRA_SKIP_PREFIXES}）：
 * {@code javax./jdk./sun./com.sun.}（反射与 JVM 内部）、{@code com.mojang.}（Brigadier ／ DFU，
 * 属于"原版基础设施"这一族）、{@code cpw.mods.modlauncher.}（启动器）、{@code org.objectweb.asm.}（字节码工具）、
 * {@code com.llamalad7.mixinextras.}（MixinExtras 的注入糖）。**这一串是我加的、不是规格点名的**，
 * 所以它单独成表、可整表删掉而不影响规格那五条；删掉只会让噪音变多，不会让证据变少。
 * ⚠️ 这里<b>没有</b>任何 mod 包名 —— 规格的红线"不按类名硬编码任何 mod"在这一层同样成立。
 *
 * <h2>限流：每局最多 3 次（两个标签各自记账）</h2>
 * {@code removal_caller} 与 {@code die_caller} 各有一本账（{@link #MAX_HITS_PER_LABEL}），
 * 用 {@link AtomicInteger} 的 CAS 记账 ⇒ 并发调也不会打出第 4 行。
 * 「一局」= 一个服务器实例：扩展里的逐级抹除阶梯每 tick 看到服务器实例变了就调
 * {@link #resetAll()}（换世界/重进存档 ⇒ 计数重新给 3 次）。
 * <pre>
 *   🔴 为什么必须先判 {@link #hasQuota(String)} 再抓栈：{@code new Throwable().getStackTrace()} 有实打实的开销，
 *      而被拦下这条路上「每秒 14~30 次」是常态 ⇒ 只有前 3 次才付这笔钱（第 4 次起只是一次 map 读）。
 * </pre>
 *
 * <h2>只在"受保护玩家被拦"时调用</h2>
 * 两个调用点都在<b>判据已经说"拦"之后</b>（{@code setRemoved} 拦截那一路〔执行体已搬进扩展〕的
 * {@code decision.yes} 分支、{@link ShanhaiGuardValueLayer} 的 {@code refuseDie} 里那个 {@code decision.yes} 分支），
 * ⇒ <b>普通实体的移除/死亡一次都不会抓到栈</b>（规格红线）。
 *
 * <h2>纯 JDK</h2>
 * 只 import {@code java.*} ＋ 同包的 {@link ShanhaiGuardCore#LOG_PREFIX}（它也是纯 JDK）
 * ⇒ 空 classpath 就能编译、{@link #main} 能离线单跑（自检条数见报告）。
 *
 * <p>⚠️ 本类<b>不</b>决定"拦不拦"，也<b>不</b>改任何游戏状态：它只读栈、只返回一行字符串。
 * 抓栈自己出异常 ⇒ 返回 {@code null}（调用方原样继续拦），绝不让取证动作影响守卫本身。
 */
public final class ShanhaiGuardCallerTrace {

    private ShanhaiGuardCallerTrace() {}

    /** 取证标签：受保护玩家被 {@code setRemoved} 拦下（规格点名）。 */
    public static final String LABEL_REMOVAL = "removal_caller";

    /** 取证标签：受保护玩家被拒绝 {@code die}（规格点名）。 */
    public static final String LABEL_DIE = "die_caller";

    /** 取栈深度上限（规格：前 ~25 帧）。 */
    public static final int MAX_STACK_FRAMES = 25;

    /** 每个标签最多打几次（规格：每局最多 3 次）。 */
    public static final int MAX_HITS_PER_LABEL = 3;

    /** {@code frames=} 里最多列几帧（规格：外部帧1 | 外部帧2 | 外部帧3）。 */
    public static final int MAX_LISTED_FRAMES = 3;

    /** 规格点名的五个前缀：这些<b>不算</b>外部 mod 帧。 */
    public static final String[] SPEC_SKIP_PREFIXES = {
            "java.",
            "net.minecraft.",
            "net.minecraftforge.",
            "org.spongepowered.",
            "com.shanhai."};

    /**
     * 我另加的<b>框架噪声</b>前缀（不是规格点名的；删掉只会更啰嗦，不会丢证据）。
     * <p>⚠️ 这里<b>不许</b>出现任何具体 mod 的包名（规格红线：不按类名硬编码任何 mod）。
     */
    public static final String[] EXTRA_SKIP_PREFIXES = {
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "com.mojang.",
            "cpw.mods.modlauncher.",
            "org.objectweb.asm.",
            "com.llamalad7.mixinextras."};

    /** 每个标签打了几次。 */
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    // ═══════════════════════════════ 记账（限流） ═══════════════════════════════

    /** 这个标签还剩次数吗（便宜：一次 map 读；用来挡住 {@code getStackTrace()} 的开销）。 */
    public static boolean hasQuota(String label) {
        return used(label) < MAX_HITS_PER_LABEL;
    }

    /** 这个标签已经打了几次。 */
    public static int used(String label) {
        if (label == null) {
            return MAX_HITS_PER_LABEL;
        }
        final AtomicInteger counter = HITS.get(label);
        return counter == null ? 0 : counter.get();
    }

    /** 清零（新的一局 / 离线自检）。 */
    public static void resetAll() {
        HITS.clear();
    }

    /** 给这个标签发一个编号（1..3）；额度用完返回 0。并发安全（CAS）。 */
    static int nextIndex(String label) {
        if (label == null || label.isEmpty()) {
            return 0;
        }
        final AtomicInteger counter = HITS.computeIfAbsent(label, k -> new AtomicInteger());
        while (true) {
            final int cur = counter.get();
            if (cur >= MAX_HITS_PER_LABEL) {
                return 0;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return cur + 1;
            }
        }
    }

    // ═══════════════════════════════ 抓栈（真正干活的那一步） ═══════════════════════════════

    /**
     * 抓一次调用栈并格式化。
     *
     * <p>🔴 额度用完 ⇒ {@code null}（调用方直接跳过，什么也不打）；
     * 抓栈抛异常 ⇒ {@code null}（取证坏了不许影响拦截）。
     *
     * @param label {@link #LABEL_REMOVAL} ／ {@link #LABEL_DIE}
     * @return 一行 {@code [SHANHAI-GUARD] removal_caller#N at=… frames=…}；不打则 {@code null}
     */
    public static String capture(String label) {
        if (!hasQuota(label)) {
            return null;   // 额度用完：连栈都不抓（这是"每秒 30 次"这条路上的关键一次省）
        }
        try {
            return capture(label, new Throwable().getStackTrace());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 同 {@link #capture(String)}，但栈由调用方给（<b>离线自检就靠这个入口</b>，不需要假装自己在游戏里）。
     *
     * @param stack 调 {@code new Throwable().getStackTrace()} 得到的那种数组（可为 {@code null}／空）
     */
    public static String capture(String label, StackTraceElement[] stack) {
        final int index = nextIndex(label);
        if (index <= 0) {
            return null;
        }
        final List<String> external = externalFrameTexts(stack);
        final String at = external.isEmpty() ? "(none)" : external.get(0);
        final List<String> listed = external.size() > MAX_LISTED_FRAMES
                ? external.subList(0, MAX_LISTED_FRAMES) : external;
        return format(label, index, at, listed);
    }

    /** 拼那一行（纯字符串；自检直接验它的形状）。 */
    static String format(String label, int index, String at, List<String> frames) {
        final StringBuilder sb = new StringBuilder(320);
        sb.append(ShanhaiGuardCore.LOG_PREFIX)
                .append(label).append('#').append(index)
                .append(" at=").append(at == null ? "(none)" : at)
                .append(" frames=");
        if (frames == null || frames.isEmpty()) {
            sb.append("(none)");
        } else {
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(frames.get(i));
            }
        }
        return sb.toString();
    }

    /**
     * 从栈里挑出<b>外部 mod 帧</b>（按出现顺序，已转成 {@code 类#方法:行}）。
     * <p>只看前 {@link #MAX_STACK_FRAMES} 帧（规格：前 ~25 帧）。
     */
    static List<String> externalFrameTexts(StackTraceElement[] stack) {
        final List<String> out = new ArrayList<>(MAX_LISTED_FRAMES + 4);
        if (stack == null) {
            return out;
        }
        final int limit = Math.min(stack.length, MAX_STACK_FRAMES);
        for (int i = 0; i < limit; i++) {
            final StackTraceElement e = stack[i];
            if (e == null || isSkipped(e.getClassName())) {
                continue;
            }
            out.add(frameText(e));
        }
        return out;
    }

    /** 这一帧算不算"已知/框架"（= 不算外部 mod 帧）。 */
    public static boolean isSkipped(String className) {
        if (className == null || className.isEmpty()) {
            return true;
        }
        for (final String p : SPEC_SKIP_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        for (final String p : EXTRA_SKIP_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 一帧写成 {@code 类#方法:行}；行号未知（-1）写 {@code :?}。 */
    static String frameText(StackTraceElement e) {
        final int line = e.getLineNumber();
        return e.getClassName() + "#" + e.getMethodName() + ":" + (line >= 0 ? String.valueOf(line) : "?");
    }

    // ═══════════════════════════════ 自检（同文件 main） ═══════════════════════════════

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    private static int checks = 0;
    private static int reds = 0;

    private static void expect(String group, String name, boolean expected, boolean actual, String reading) {
        checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            reds++;
        }
        OUT.println(String.format("%-4s | %-6s | %-46s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    /** 造一帧（自检用；{@code line < 0} = 行号未知）。 */
    private static StackTraceElement frame(String cls, String method, int line) {
        final int cut = cls.lastIndexOf('.');
        final String file = (cut >= 0 ? cls.substring(cut + 1) : cls) + ".java";
        return new StackTraceElement(cls, method, file, line);
    }

    /** 【故意写坏】不过滤任何前缀（负对照：证明那些"被过滤掉"的断言不是恒绿）。 */
    private static List<String> externalFrameTextsMutantNoFilter(StackTraceElement[] stack) {
        final List<String> out = new ArrayList<>();
        if (stack == null) {
            return out;
        }
        for (int i = 0; i < Math.min(stack.length, MAX_STACK_FRAMES); i++) {
            if (stack[i] != null) {
                out.add(frameText(stack[i]));
            }
        }
        return out;
    }

    /**
     * 离线自检：{@code javac -d <out> ShanhaiGuardCore.java ShanhaiGuardCallerTrace.java} 后
     * {@code java -cp <out> com.shanhai.common.guard.ShanhaiGuardCallerTrace}。
     *
     * <p>它证明的是<b>过滤与格式</b>；「真有人在调 setRemoved 吗」只能进游戏看日志。
     */
    public static void main(String[] args) {
        OUT.println("=== ShanhaiGuardCallerTrace 离线自检 ===");
        resetAll();

        // ── ① 过滤：已知前缀都不算外部帧 ──
        final StackTraceElement[] allKnown = {
                frame("java.lang.Thread", "getStackTrace", 1600),
                frame("com.shanhai.common.guard.ShanhaiGuardCallerTrace", "capture", 150),
                frame("com.shanhai.common.guard.ShanhaiGuardValueLayer", "refuseDie", 621),
                frame("net.minecraft.world.entity.Entity", "setRemoved", 545),
                frame("net.minecraftforge.eventbus.EventBus", "post", 120),
                frame("org.spongepowered.asm.mixin.injection.invoke", "inject", 10),
                frame("com.shanhai.mixin.ShanhaiSetBlockWatchMixin", "shanhai$watchSetBlockState", 87)};
        expect("①过滤", "全是已知前缀 ⇒ 一个外部帧都不剩", true,
                externalFrameTexts(allKnown).isEmpty(),
                "剩 " + externalFrameTexts(allKnown).size() + " 帧");

        // ── ① 负对照：把过滤关掉 ⇒ 同样的栈会冒出一堆帧 ──
        expect("①负对照", "过滤关掉后同样的栈会冒帧（证明上面的空不是恒绿）", true,
                !externalFrameTextsMutantNoFilter(allKnown).isEmpty(),
                "不过滤时 " + externalFrameTextsMutantNoFilter(allKnown).size() + " 帧");

        // ── ② at= 就是第一个外部帧，frames= 按顺序最多 3 个 ──
        final StackTraceElement[] withBoss = {
                frame("com.shanhai.common.guard.ShanhaiGuardValueLayer", "refuseDie", 621),
                frame("net.minecraft.world.entity.Entity", "setRemoved", 545),
                frame("com.example.mod.Boss", "onTick", 123),
                frame("com.example.mod.Boss$1", "run", 45),
                frame("com.example.mod.Helper", "kill", 9),
                frame("com.example.mod.Helper", "kill2", 10)};
        final List<String> ext = externalFrameTexts(withBoss);
        expect("②at", "at= = 第一个外部帧", true,
                "com.example.mod.Boss#onTick:123".equals(ext.get(0)), String.valueOf(ext.get(0)));
        expect("②frames", "外部帧按栈顺序排列", true,
                "com.example.mod.Boss$1#run:45".equals(ext.get(1)), String.valueOf(ext.get(1)));
        expect("②frames", "外部帧总数 4（frames= 只列 3）", true, ext.size() == 4, "实际 " + ext.size());

        resetAll();
        final String line = capture(LABEL_REMOVAL, withBoss);
        expect("②格式", "前缀与标签编号", true,
                line != null && line.startsWith(ShanhaiGuardCore.LOG_PREFIX + "removal_caller#1 at="),
                String.valueOf(line));
        expect("②格式", "frames= 最多列 3 帧", true,
                line != null && line.contains(" | ") && line.split(" \\| ", -1).length == 3,
                line == null ? "null" : String.valueOf(line.split(" \\| ", -1).length));
        expect("②格式", "不含第 4 个外部帧", true,
                line != null && !line.contains("Helper#kill2"), "不该出现 Helper#kill2");

        // ── ③ 全都过滤掉时：不假装，写 (none) ──
        resetAll();
        final String none = capture(LABEL_REMOVAL, allKnown);
        expect("③兜底", "一个外部帧都没有 ⇒ at=(none) frames=(none)", true,
                none != null && none.endsWith(" at=(none) frames=(none)"), String.valueOf(none));

        // ── ③ 行号未知 ⇒ :? ──
        resetAll();
        final String unknownLine = capture(LABEL_DIE, new StackTraceElement[]{
                frame("com.example.boss.Foo", "bar", -1)});
        expect("③格式", "行号未知写 :?（不写 :0）", true,
                unknownLine != null && unknownLine.contains("at=com.example.boss.Foo#bar:?"),
                String.valueOf(unknownLine));

        // ── ③ null / 空栈不许抛 ──
        resetAll();
        final String nullStack = capture(LABEL_DIE, null);
        expect("③兜底", "栈是 null ⇒ 照样给一行 (none)，不抛", true,
                nullStack != null && nullStack.endsWith("at=(none) frames=(none)"), String.valueOf(nullStack));

        // ── ④ 只看前 25 帧 ──
        final StackTraceElement[] deep = new StackTraceElement[MAX_STACK_FRAMES + 6];
        for (int i = 0; i < deep.length; i++) {
            deep[i] = frame("net.minecraft.world.entity.Entity", "m" + i, i);
        }
        deep[MAX_STACK_FRAMES - 1] = frame("com.example.boss.Inside", "yes", 1);   // 第 25 帧（下标 24）
        expect("④深度", "第 25 帧（下标 24）里的外部帧会被看到", true,
                externalFrameTexts(deep).contains("com.example.boss.Inside#yes:1"),
                String.valueOf(externalFrameTexts(deep)));
        deep[MAX_STACK_FRAMES - 1] = frame("net.minecraft.world.entity.Entity", "m24", 24);
        deep[MAX_STACK_FRAMES] = frame("com.example.boss.Outside", "no", 2);       // 第 26 帧（下标 25）
        expect("④深度", "第 26 帧（下标 25）里的外部帧不会被看到（口径=前 25 帧）", true,
                externalFrameTexts(deep).isEmpty(), String.valueOf(externalFrameTexts(deep)));

        // ── ⑤ 限流：每局最多 3 次 ──
        resetAll();
        final String h1 = capture(LABEL_REMOVAL, withBoss);
        final String h2 = capture(LABEL_REMOVAL, withBoss);
        final String h3 = capture(LABEL_REMOVAL, withBoss);
        final String h4 = capture(LABEL_REMOVAL, withBoss);
        expect("⑤限流", "第 1 次是 #1", true, h1 != null && h1.contains("removal_caller#1"), String.valueOf(h1));
        expect("⑤限流", "第 2 次是 #2", true, h2 != null && h2.contains("removal_caller#2"), String.valueOf(h2));
        expect("⑤限流", "第 3 次是 #3", true, h3 != null && h3.contains("removal_caller#3"), String.valueOf(h3));
        expect("⑤限流", "第 4 次返回 null（不再打）", true, h4 == null, String.valueOf(h4));
        expect("⑤限流", "额度读数 = 3", true, used(LABEL_REMOVAL) == MAX_HITS_PER_LABEL,
                "used=" + used(LABEL_REMOVAL));
        expect("⑤限流", "额度用完 hasQuota=false", true, !hasQuota(LABEL_REMOVAL), "供调用方先判再抓栈");

        // ── ⑤ 两个标签各自记账（die_caller 不受 removal_caller 用光的影响）──
        expect("⑤限流", "removal 用光后 die_caller 仍有 3 次", true,
                hasQuota(LABEL_DIE) && used(LABEL_DIE) == 0, "die used=" + used(LABEL_DIE));
        final String d1 = capture(LABEL_DIE, withBoss);
        expect("⑤限流", "die_caller 自己的编号从 #1 开始", true,
                d1 != null && d1.contains("die_caller#1"), String.valueOf(d1));

        // ── ⑤ resetAll 之后额度回来 ──
        resetAll();
        expect("⑤限流", "resetAll 之后两个标签都回到 0", true,
                used(LABEL_REMOVAL) == 0 && used(LABEL_DIE) == 0 && hasQuota(LABEL_REMOVAL),
                "removal=" + used(LABEL_REMOVAL) + " die=" + used(LABEL_DIE));

        // ── ⑥ 前缀口径：单独验一遍"哪些前缀会被跳过" ──
        expect("⑥前缀", "net.minecraft.* 跳过", true, isSkipped("net.minecraft.world.entity.Entity"), "");
        expect("⑥前缀", "net.minecraftforge.* 跳过", true, isSkipped("net.minecraftforge.common.X"), "");
        expect("⑥前缀", "org.spongepowered.* 跳过", true, isSkipped("org.spongepowered.asm.mixin.Mixin"), "");
        expect("⑥前缀", "com.shanhai.* 跳过（我们自己）", true, isSkipped("com.shanhai.common.guard.X"), "");
        expect("⑥前缀", "java.* 跳过", true, isSkipped("java.lang.Thread"), "");
        expect("⑥前缀", "外部 mod 包不跳过", false, isSkipped("com.example.mod.Boss"),
                "这是要被打印出来的那一种");
        expect("⑥前缀", "lambda 帧不跳过（mod 的 lambda 也是证据）", false,
                isSkipped("com.example.mod.Boss$$Lambda$123/0x0000"), "");
        expect("⑥前缀", "空类名当作跳过（不炸）", true, isSkipped(null) && isSkipped(""), "");

        OUT.println("---------------------------------------------------------------");
        OUT.println("合计 " + checks + " 条，红 " + reds + " 条 ⇒ " + (reds == 0 ? "全绿 PASS" : "有红 FAIL"));
        if (reds != 0) {
            System.exit(1);
        }
    }
}
