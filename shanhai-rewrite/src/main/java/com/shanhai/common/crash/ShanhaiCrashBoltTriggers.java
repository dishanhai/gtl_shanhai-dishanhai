package com.shanhai.common.crash;

import com.shanhai.ShanhaiMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequencer;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 16 种异常的<b>真触发</b>工厂 —— 这个类里除了 SQLException（需求允许、且本机确实做不到）之外，
 * <b>没有一行</b> {@code throw new <目标异常>}：每一种都是真的去做那件会失败的事，
 * 由 JDK / JVM / 原版 API 自己把异常扔出来。
 *
 * <h2>一、为什么必须"真跑"</h2>
 * 需求的口径是「不许全用 {@code throw new X}」。技术上的理由同样硬：
 * {@code throw new NullPointerException("...")} 出来的东西<b>没有真实的栈</b>
 * —— 崩溃报告里那一行 {@code at ...} 会全部指向我们自己，
 * 而真异常的第一帧指向的是"真正出事的那个 JDK/原版方法"，
 * 报告才有可读性（也才经得起"这不是伪造的吧"的追问）。
 *
 * <h2>二、调用形状：{@link #produce(int)} 一定会抛，调用方接住它</h2>
 * <pre>
 *   Throwable real = ShanhaiCrashBoltTriggers.produce(index);   // 内部 catch 住真异常并返回
 *   new CrashReport(description, real)                          // 真异常对象原样进报告
 * </pre>
 * 为什么不让它自己抛：4 个 checked 异常（IOException / ClassNotFoundException /
 * NoSuchMethodException / MidiUnavailableException）在方法签名里没法声明，
 * 需要一个统一的"接住 → 放进 CrashReport"的落点。而"真异常从工作线程搬回主线程"
 * 这一步仍然用<b>泛型 sneaky throw</b>（{@link #sneakyThrow(Throwable)}）——
 * 逐字照 {@code temp/reality-items/报告.md} §4.5 的实测结论：它不包装、类型与 message 原样。
 *
 * <h2>三、4 个 checked 异常为什么必须在工作线程上失败</h2>
 * 需求指定「工作线程真失败后再搬到主线程抛」。实现见 {@link #onWorker(WorkerBody)}：
 * 起一个守护线程做那件真会失败的事，{@code join} 之后把<b>那个异常对象本身</b>搬回主线程。
 * ⇒ 搬到主线程的是<b>同一个对象</b>（栈是工作线程的），不是重建的副本。
 *
 * <h2>四、SQLException 是唯一合成的</h2>
 * 本机没有 JDBC 驱动、没有数据库服务（{@code javax.sql} 里没有任何驱动），
 * "真去连一个不存在的数据库"连驱动都加载不出来，只会得到
 * {@code ClassNotFoundException}，得不到 {@code SQLException}。
 * ⇒ 按需求合成一个，并由 {@link ShanhaiCrashBoltTable#description(int)} 在 Description
 * 末尾加 {@code (synthetic)}（读报告的人必须一眼能看出这条不是真跑出来的）。
 */
public final class ShanhaiCrashBoltTriggers {

    /** 工作线程最多等这么久（真失败都是毫秒级的，这个上限只防"永远不返回"的怪机器）。 */
    private static final int WORKER_TIMEOUT_MS = 10_000;

    /** NPE 用的字段：<b>从头到尾没有任何一行给它赋过值</b>（这是"真·空引用"的关键）。 */
    private static String neverAssigned;

    /**
     * 第 {@code index} 号异常的真触发入口。
     *
     * @return 真的由 {@code fire(index)} 抛出来的那个异常对象（调用方负责把它放进 CrashReport）
     */
    public static Throwable produce(int index) {
        try {
            fire(index);
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) {
                // 🔴 OOM 的唯一特殊处理：先把「保命缓冲」还给堆，再还吃满的那一批，
                //    然后才允许上层去 new CrashReport（否则报告自己就分配不出来）。
                ShanhaiCrashBoltReserve.releaseForCrashReport();
                ShanhaiCrashBoltReserve.releaseHoard();
            }
            return t;
        }
        // fire() 的每个分支都必然抛异常；走到这里说明有人改坏了它。
        return new RuntimeException("crash bolt " + index + " did not throw anything (BUG)");
    }

    /** 分发。每个 case 一行，序号顺序 = {@link ShanhaiCrashBoltTable} 的行序。 */
    private static void fire(int index) {
        switch (index) {
            case 0 -> nullPointer();
            case 1 -> noClassDefFound();
            case 2 -> exceptionInInitializer();
            case 3 -> ioException();
            case 4 -> classNotFoundException();
            case 5 -> sqlExceptionSynthetic();
            case 6 -> noSuchElement();
            case 7 -> illegalArgument();
            case 8 -> illegalState();
            case 9 -> unsupportedOperation();
            case 10 -> midiUnavailable();
            case 11 -> concurrentModification();
            case 12 -> indexOutOfBounds();
            case 13 -> noSuchMethod();
            case 14 -> classCast();
            case 15 -> outOfMemory();
            default -> throw new RuntimeException("unknown crash bolt index " + index);
        }
    }

    // ==================================================================
    // 甲组 · 10 个"真触发"（JDK/JVM/原版 API 自己扔）
    // ==================================================================

    /** 0 · NullPointerException —— 真·空引用。 */
    private static void nullPointer() {
        // 🔴 真触发点：neverAssigned 永远是 null，调它的实例方法 ⇒ JVM 抛 NPE
        //    下一行必然抛，所以方法体到这里结束（不需要任何"让 javac 闭嘴"的死代码 ——
        //    死代码里的 throw new 会让"触发器里没有一行 new 目标异常"这条判据说不清）。
        int length = neverAssigned.length();
        if (length == Integer.MIN_VALUE) {
            neverAssigned = "";
        }
    }

    /**
     * 1 · NoClassDefFoundError —— 真·"这个类连初始化都过不去"。
     *
     * <p>顺序是刻意的：<b>第一步</b>先把 {@link BrokenInitBomb} 的类初始化搞失败
     * （那次会得到 {@code ExceptionInInitializerError}，就地吞掉）——
     * JVM 会把"这个类初始化失败过"记在类状态里；<b>第二步</b>再 {@code new} 一次，
     * JVM 就会自己抛 {@code NoClassDefFoundError: Could not initialize class ...}。
     */
    private static void noClassDefFound() {
        try {
            new BrokenInitBomb();          // 第 1 次：JVM 抛 ExceptionInInitializerError（吞掉）
        } catch (Throwable ignored) {
            // 故意吞：这一步只是让 JVM 把类标成"初始化失败"
        }
        // 🔴 真触发点：第 2 次访问 ⇒ JVM 抛 NoClassDefFoundError（不是我们 new 的）
        new BrokenInitBomb();
    }

    /** 2 · ExceptionInInitializerError —— 真·加载一个 {@code <clinit>} 里就抛的类。 */
    private static void exceptionInInitializer() {
        // 🔴 真触发点：new 触发类初始化 ⇒ JVM 把 <clinit> 里的异常包成 ExceptionInInitializerError
        new StaticInitBomb();
    }

    /** 3 · IOException —— 工作线程真去打开一个不存在的文件。 */
    private static void ioException() {
        Throwable real = onWorker(() -> {
            // 🔴 真触发点：这个文件不存在，FileInputStream 的构造会真失败
            FileInputStream in = new FileInputStream(missingFile().toFile());
            in.close();
        });
        if (real == null) {
            throw new RuntimeException("worker produced no IOException (BUG)");
        }
        // 🔴 搬运点：checked 异常用 sneaky throw 原样搬回主线程（类型/message/栈都不变）
        sneakyThrow(real);
    }

    /** 4 · ClassNotFoundException —— 工作线程真去 {@code Class.forName} 一个不存在的名字。 */
    private static void classNotFoundException() {
        Throwable real = onWorker(() -> {
            // 🔴 真触发点：这个类名不存在
            Class.forName("com.shanhai.common.crash.DoesNotExistInThisUniverse");
        });
        if (real == null) {
            throw new RuntimeException("worker produced no ClassNotFoundException (BUG)");
        }
        sneakyThrow(real);
    }

    /** 6 · NoSuchElementException —— 真·对空集合的迭代器调 {@code next()}。 */
    private static void noSuchElement() {
        Iterator<String> empty = new ArrayList<String>().iterator();
        // 🔴 真触发点：空的迭代器上取 next ⇒ NoSuchElementException
        String boom = empty.next();
        if (boom == null) {
            boom = "";
        }
    }

    /**
     * 7 · IllegalArgumentException —— 真·给<b>原版 API</b> 一个非法参数。
     *
     * <p>原版证据：{@code net/minecraft/world/level/block/state/properties/IntegerProperty.java:17}
     * 逐字 {@code throw new IllegalArgumentException("Min value of " + name + " must be 0 or greater")}；
     * 我们给 min = -1 ⇒ 是原版 API 自己拒绝的，不是我们抛的。
     */
    private static void illegalArgument() {
        // 🔴 真触发点：方块状态属性 IntegerProperty 的 min 必须 ≥ 0，这里给 -1
        IntegerProperty.create("shanhai_crash_bolt", -1, 3);
    }

    /**
     * 8 · IllegalStateException —— 真·在<b>错误的状态</b>下调原版 API。
     *
     * <p>原版证据：{@code net/minecraft/core/Registry.java:128-133} 的静态注册帮助方法最终走
     * {@code WritableRegistry#register}，而 {@code net/minecraft/core/MappedRegistry.java:113-121}
     * 的 {@code validateWrite(ResourceKey)} 在注册表已冻结时逐字抛
     * {@code IllegalStateException("Registry is already frozen (trying to add key …)")}。
     * 模组加载结束后 {@code BuiltInRegistries.ITEM} 就是冻结状态 ⇒ 这一步必然被原版拒绝。
     */
    private static void illegalState() {
        // 🔴 真触发点：往一个已经冻结的原版注册表里注册（加载期之外，这就是错误的状态）
        Registry.register(BuiltInRegistries.ITEM,
                new ResourceLocation(ShanhaiMod.MOD_ID, "crash_bolt_after_freeze"), Items.STONE);
    }

    /** 9 · UnsupportedOperationException —— 真·改一个不可变集合。 */
    private static void unsupportedOperation() {
        List<String> immutable = List.of("cannot", "change");
        // 🔴 真触发点：List.of 返回的是 JDK 的不可变列表，add 由 JDK 自己抛 UOE
        immutable.add("me");
    }

    /** 11 · ConcurrentModificationException —— 真·一边 for-each 一边改同一个 ArrayList。 */
    private static void concurrentModification() {
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        for (String s : list) {
            if ("b".equals(s)) {
                // 🔴 真触发点：遍历中改结构 ⇒ 下一次 next() 由 ArrayList 的迭代器抛 CME
                list.add("boom");
            }
        }
    }

    /**
     * 12 · IndexOutOfBoundsException —— 真·越界取。
     *
     * <p>⚠️ 为什么<b>不用数组</b>（{@code int[6][8]}）：JVM 对数组越界抛的是
     * {@code ArrayIndexOutOfBoundsException}（它是 {@code IndexOutOfBoundsException} 的子类），
     * 崩溃报告那一行就会写成 {@code java.lang.ArrayIndexOutOfBoundsException} —— 与这件物品的名字对不上。
     * 用 {@code ArrayList#get} 则是真越界取，且 JDK 抛的正是 {@code IndexOutOfBoundsException}
     * （{@code Objects.checkIndex} 里抛的，类名逐字一致）。
     */
    private static void indexOutOfBounds() {
        List<String> six = new ArrayList<>(List.of("0", "1", "2", "3", "4", "5"));
        int index = six.size() + 2;             // 8
        // 🔴 真触发点：长度 6 的列表取下标 8 ⇒ JDK 抛 IndexOutOfBoundsException
        String boom = six.get(index);
        if (boom == null) {
            boom = "";
        }
    }

    /** 14 · ClassCastException —— 真·强转。 */
    private static void classCast() {
        // 运行时是 String，但编译期只知道是 Object（hideType 把类型信息藏起来）
        Object actuallyString = hideType("shanhai");
        // 🔴 真触发点：checkcast 到 Integer ⇒ JVM 抛 CCE
        Integer boom = (Integer) actuallyString;
        if (boom == 0) {
            boom = Integer.valueOf(1);
        }
    }

    // ==================================================================
    // 乙组 · 2 个 checked（工作线程真失败 + sneaky throw 搬运） 的另一个：NoSuchMethod / MIDI
    // ==================================================================

    /** 10 · MidiUnavailableException —— 工作线程真调 MIDI 子系统。 */
    private static void midiUnavailable() {
        Throwable real = onWorker(() -> {
            // ① 需求点名的真调用：真去要一个音序器
            Sequencer sequencer = MidiSystem.getSequencer();
            if (sequencer != null) {
                sequencer.close();
            }
            // ② 本机 ① 是【成功】的（离线探针实测 MUE_COUNT=0：RealTimeSequencer 可用），
            //    所以"没有音乐"这件事在这台机器上不表现为 getSequencer 失败。继续在同一个
            //    MIDI 子系统里真试下一个入口：本机没有默认 Transmitter，
            //    ⇒ 🔴 真触发点：JDK 自己抛 MidiUnavailableException（离线探针实测，cause 是
            //    IllegalArgumentException "Requested device not installed"）。不是我们 new 的。
            MidiSystem.getTransmitter();
        });
        if (real == null) {
            throw new RuntimeException("worker produced no MidiUnavailableException (BUG)");
        }
        sneakyThrow(real);
    }

    /** 13 · NoSuchMethodException —— 工作线程真去 {@code getMethod} 一个不存在的方法名。 */
    private static void noSuchMethod() {
        Throwable real = onWorker(() -> {
            // 🔴 真触发点：这个方法名不存在 ⇒ 反射由 JDK 自己抛 NoSuchMethodException
            ShanhaiCrashBoltTriggers.class.getMethod("shanhaiNoSuchMethodProbe");
        });
        if (real == null) {
            throw new RuntimeException("worker produced no NoSuchMethodException (BUG)");
        }
        sneakyThrow(real);
    }

    // ==================================================================
    // 丙组 · 2 个特例：合成（SQL） / 真吃满堆（OOM）
    // ==================================================================

    /**
     * 5 · SQLException —— <b>唯一合成</b>的一件。
     *
     * <p>为什么本机做不到真触发：{@code DriverManager} 走的是 SPI，机器上没有任何 JDBC 驱动
     * （{@code java.sql} 模块自带的那几个类里没有驱动实现），{@code DriverManager.getConnection(...)}
     * 自己就会抛 {@code SQLException("No suitable driver found for …")} —— 但那句话说的是
     * "没有驱动"，不是"连数据库失败"，与这件物品要表达的东西不是一回事。
     * ⇒ 按需求合成本件，Description 由 {@link ShanhaiCrashBoltTable#description(int)} 在末尾加
     * {@code (synthetic)}，让读报告的人一眼看出这条不是真跑出来的。
     */
    private static void sqlExceptionSynthetic() {
        // 🔴 合成点（本类唯一一处 new 目标异常）。
        //    用 sneakyThrow 而不是 throw：SQLException 是 checked，fire() 的签名里声明不了它；
        //    sneakyThrow 只是让 javac 闭嘴，对象还是那个对象（报告里的类型/栈/message 全在）。
        sneakyThrow(new SQLException("这个宇宙没有数据库。(synthetic)", "SHANHAI_BOLT_SYNTHETIC"));
    }

    /**
     * 15 · OutOfMemoryError —— 真·把堆吃满。
     *
     * <p>循环 {@code new byte[8 MiB]} 并全部持有，直到 JVM 自己抛 OOM。
     * 堆里那块"保命缓冲"由 {@link ShanhaiCrashBoltReserve#allocate()} 在启动时占好，
     * 捕获 OOM 后由 {@link #produce(int)} 先还回去，崩溃报告才写得出来。
     */
    private static void outOfMemory() {
        // 🔴 真触发点：无限分配并持有 ⇒ JVM 抛 java.lang.OutOfMemoryError: Java heap space
        ShanhaiCrashBoltReserve.consumeAllHeap();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 工作线程要跑的那件"真会失败的事"。 */
    private interface WorkerBody {
        void run() throws Exception;
    }

    /**
     * 在工作线程上跑 {@code body}，把<b>它抛出来的那个异常对象</b>带回来（成功则返回 {@code null}）。
     *
     * <p>⚠️ 为什么不用 {@code CompletableFuture}/{@code ExecutorService}：
     * 那些会把异常包成 {@code ExecutionException}（类型就变了，报告里会多一层 Wrapped）。
     * 这里只用一个裸 {@link Thread} + {@link AtomicReference}，对象原样过手。
     */
    private static Throwable onWorker(WorkerBody body) {
        final AtomicReference<Throwable> box = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                box.set(t);
            }
        }, "shanhai-crash-bolt-worker");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(WORKER_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return interrupted;
        }
        return box.get();
    }

    /**
     * 泛型 sneaky throw：把 checked 异常原样抛出去，<b>不包装、不改类型、不改 message</b>。
     *
     * <p>取证：{@code temp/reality-items/报告.md} §4.5（用游戏那个 java.exe 实测，
     * {@code sneaky_io} / {@code sneaky_sql} 两行输出的异常类型与 message 一字不差）。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /** 藏起静态类型（让 {@code (Integer) obj} 在编译期合法、运行期必炸）。 */
    private static Object hideType(Object value) {
        return value;
    }

    /** 一个<b>真的不存在</b>的路径（每次换 UUID，保证不会撞上一个恰好同名的残留文件）。 */
    private static Path missingFile() {
        return Paths.get("config", "shanhai", "crash-bolt-missing-" + UUID.randomUUID() + ".json");
    }

    /** 参考点：本类的类名（异常 message 里用得到，也方便自查器 grep）。 */
    static String selfName() {
        return ShanhaiCrashBoltTriggers.class.getName();
    }

    // ------------------------------------------------------------------ 两个"类初始化炸弹"

    /** 3 · {@code <clinit>} 里就抛的类（被 {@link #exceptionInInitializer()} 第一次加载）。 */
    static final class StaticInitBomb {
        static final Object BAIT;
        static {
            BAIT = new Object();
            // 用 Boolean.parseBoolean 而不是 if (true)：条件不是编译期常量，
            // ⇒ 初始化块在编译器看来"能正常结束"，javac 不会报错
            if (Boolean.parseBoolean("true")) {
                throw new RuntimeException("SHANHAI_BOLT_STATIC_INIT_FAILED");
            }
        }
    }

    /** 同样的炸弹，第二次访问时 JVM 会改抛 {@code NoClassDefFoundError}（见 {@link #noClassDefFound()}）。 */
    static final class BrokenInitBomb {
        static final Object BAIT;
        static {
            BAIT = new Object();
            if (Boolean.parseBoolean("true")) {
                throw new RuntimeException("SHANHAI_BOLT_BROKEN_INIT_FAILED");
            }
        }
    }

    private ShanhaiCrashBoltTriggers() {}
}
