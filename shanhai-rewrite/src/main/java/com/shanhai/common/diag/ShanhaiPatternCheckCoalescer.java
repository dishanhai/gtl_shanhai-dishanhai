package com.shanhai.common.diag;

import com.gregtechceu.gtceu.api.block.ActiveBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.shanhai.config.ShanhaiConfig;
import com.shanhai.machine.host.GuixuVisionCoreMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <b>「每 tick 最多一次」的成型检查合并器</b>（只作用于<b>我们自己那台主机</b>）。
 *
 * <h2>为什么需要它（字节码取证，见 {@code temp\hang\报告.md} §1.1 / §1.2 / §5）</h2>
 * <pre>
 *   LevelChunk#setBlockState
 *     → ChunkMixin#gtceu$onChunkChanged 偏移 74-94：isPosInCache(pos) ⇒ server.execute(...)
 *     → MultiblockState#onBlockStateChanged 偏移 148：checkPatternWithLock()  ← 服务器线程上【同步】全量检查
 *     → BlockPattern#checkPatternAt 偏移 34-206：fingerLength×thumbLength×palmLength 三重全遍历
 * </pre>
 * 归墟视界枢核的图案 = <b>148×135×131 ≈ 261.7 万格</b>（空格子也照样走一遍：{@code javap} 里没有任何跳过分支）。
 * 上游<b>没有任何合并 / 防抖</b>：一个 tick 里改 N 格方块 ⇒ N 次全量检查。
 * 一键搭建铺两万多格 ⇒ 两万多次 261.7 万格扫描 ⇒ 服务器线程单 tick 占住几十秒
 * （日志实证：{@code Can't keep up! Running 11058ms or 221 ticks behind}、ModernFix 看门狗
 * {@code A single server tick has taken 40001} / {@code 80557}）。
 *
 * <h2>它做什么（一句话）</h2>
 * 把「同一 tick 内同一个 {@code MultiblockState} 的多次方块变化」压成<b>一次</b>
 * {@code MultiblockState#onBlockStateChanged} 调用，放在该 tick 的末尾
 * （{@code TickEvent.ServerTickEvent} 的 {@code Phase.END}）执行。
 * 每 tick 每个 state <b>最多一次全量检查</b>，语义上等价于"这个 tick 结束时结构还成不成型"。
 *
 * <h2>🔴 四条红线（逐条对应实现，改这个类之前先读）</h2>
 * <ol>
 *   <li><b>只对我们这台生效。</b>{@link #shouldDefer} 的第 ② 条要求
 *       {@code state.lastController} 的机器 {@code instanceof GuixuVisionCoreMachine}。
 *       其它 mod（以及 gtceu/gtlcore）的任何多方块<b>一律不放行到本类</b> ——
 *       这条是红线：我们是"给一台已知 261.7 万格的机器止血"，不是改上游语义。</li>
 *   <li><b>拿不到服务器线程 / 拿不到 controller ⇒ {@code defer = false}（宁可慢，不可错）。</b>
 *       {@code ServerLifecycleHooks.getCurrentServer() == null}、{@code !server.isSameThread()}、
 *       {@code state.lastController == null} —— 三种情况都<b>直接放行原逻辑</b>。
 *       特别地：异步检查线程（{@code GTCEu Multiblock Async Thread}）永远走不到这里，
 *       所以那条路一个字节都没变。</li>
 *   <li><b>结构失效最多晚 1 tick 才发现。</b>这是合并的<b>必然代价</b>，不是 bug：
 *       拆掉一块方块后，原本"下一个 tick 开头就掉成型"，现在变成"本 tick 末尾才掉成型"
 *       ⇒ 肉眼看到的是<b>最多多等 50 ms</b>（1 tick）才掉成型/才播失效特效。
 *       同理，重新补齐后的成型判定也最多晚 1 tick。</li>
 *   <li><b>任何异常都让原逻辑照常执行。</b>入口方法整体 {@code try/catch(Throwable)}；
 *       {@code flush()} 里单个 state 抛异常只记一行日志、不影响其它 state；
 *       混入侧只有在 {@link #defer} <b>成功返回之后</b>才 {@code ci.cancel()}
 *       ⇒ 只要中间任何一步抛了，原方法照原样跑完。</li>
 * </ol>
 *
 * <h2>为什么用 {@code WeakHashMap} 而不是强引用 Map</h2>
 * 键是 {@code MultiblockState}。表在每个 tick 末尾都会被清空，本来就不会长；
 * 用弱键只是再兜一层底（万一 tick 事件没来——例如世界卸载——也不会把控制器一直攥在内存里）。
 * 所有访问都在 {@link #LOCK} 里。
 *
 * <h2>和探针的关系</h2>
 * 本类是<b>真功能</b>（会改变行为），<b>不是</b>探针 —— 探针（{@code com.shanhai.mixin.probe.*}）
 * 只是把本类的三个计数器读进 {@code [SHANHAI-PATPROBE] summary} 那一行。
 * ⇒ 删掉探针包不会影响本类；反过来删掉本类会让探针少三个字段（必须一起删）。
 */
public final class ShanhaiPatternCheckCoalescer {

    /** 日志前缀（与探针的 {@code [SHANHAI-PATPROBE]} 区分开：一个是"合并器自己的账"，一个是"检查耗时"）。 */
    public static final String PREFIX = "[SHANHAI-PATCOALESCE]";

    /** 只有格子数大于这个阈值才合并（阈值来自取证：261.7 万 ≫ 20 万）。 */
    public static final int CELL_THRESHOLD = 200_000;

    private static final Logger LOGGER = LoggerFactory.getLogger("SHANHAI-PATCOALESCE");

    /** 待处理的方块变化。弱键 + 每 tick 清空 ⇒ 不会泄漏。 */
    private static final Map<MultiblockState, Pending> PENDING = new WeakHashMap<>();

    private static final Object LOCK = new Object();

    /**
     * 守卫：{@code true} = 当前这次 {@code onBlockStateChanged} 是 {@link #flush()} 自己发起的
     * ⇒ 混入侧<b>必须放行</b>（否则 flush 里那次也会被 defer，永远检查不到）。
     */
    private static final ThreadLocal<Boolean> IN_FLUSH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ---- 窗口计数（给 [SHANHAI-PATPROBE] summary 读） ----
    private static int deferredWindow;
    private static int appliedWindow;
    private static long appliedMaxNanosWindow;
    private static long appliedTotalNanosWindow;

    // ---- 图案格子数的缓存（一个机器类型只有一份图案） ----
    private static BlockPattern cachedPattern;
    private static int cachedCells = -1;
    private static volatile Field fFinger;
    private static volatile Field fThumb;
    private static volatile Field fPalm;
    private static volatile boolean fieldsResolved;

    private static boolean armedLogged;
    private static String lastError;

    private ShanhaiPatternCheckCoalescer() {}

    // ================================================================== 判定

    /**
     * 要不要把这一次 {@code onBlockStateChanged} 推到这个 tick 的末尾？
     *
     * <p>三条<b>全满足</b>才返回 {@code true}（顺序即判据顺序，② 在 ③ 之前是为了
     * 让别的机器的图案<b>连读都不读</b>）：
     * <ol>
     *   <li>当前线程就是服务器线程（{@code server.isSameThread()}）；拿不到 server ⇒ false。</li>
     *   <li>{@code state.lastController} 的机器是 {@link GuixuVisionCoreMachine}；拿不到 ⇒ false。</li>
     *   <li>图案格子数 &gt; {@link #CELL_THRESHOLD}；<b>拿不到格子数就退化成 true</b>
     *       （此处已确认是"我们这台"，宁可合并也不放两万次全量检查过去）。</li>
     * </ol>
     */
    public static boolean shouldDefer(MultiblockState state) {
        try {
            if (state == null) {
                return false;
            }
            if (!configEnabled()) {
                return false;
            }
            // ① 必须是服务器线程（异步线程那条路原样不动）
            final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isSameThread()) {
                return false;
            }
            // ② 必须是"我们这台"（红线：其它多方块一律不碰）
            final IMultiController controller = state.lastController;
            if (controller == null) {
                return false;
            }
            final MetaMachine machine = controller.self();
            if (!(machine instanceof GuixuVisionCoreMachine)) {
                return false;
            }
            // ③ 必须是"大图案"（拿不到 ⇒ 退化成合并）
            final int cells = patternCells(controller);
            if (cells < 0) {
                return true;
            }
            return cells > CELL_THRESHOLD;
        } catch (Throwable t) {
            noteError("shouldDefer", t);
            return false; // 红线 2/4：判不出来就放行原逻辑
        }
    }

    /** 记一次待处理的变化（同 tick 同 state 只留最后一次）。 */
    public static void defer(MultiblockState state, BlockPos pos, BlockState blockState) {
        if (state == null || pos == null) {
            return;
        }
        synchronized (LOCK) {
            final Pending old = PENDING.get(state);
            // 🔴 细化（安全朝向）：{@code ActiveBlock} 的变化在原方法里走的是"直接 return、不检查"那条分支
            //    （MultiblockState#onBlockStateChanged 偏移 105-147：isFormed && 新方块是 ActiveBlock
            //     && pos ∈ vaBlocks ⇒ return）。所以：
            //    · 已有非 ActiveBlock 的待办 + 来了一个 ActiveBlock ⇒ 保留旧的（否则会把真正需要检查的那次丢掉）
            //    · 其它情况一律用新值覆盖 ⇒ 仍然满足"每 tick 每 state 至多一次调用"
            final boolean incomingIsActivityOnly = blockState != null
                    && blockState.getBlock() instanceof ActiveBlock;
            if (old != null && incomingIsActivityOnly) {
                // 保留旧条目，只记账
            } else {
                PENDING.put(state, new Pending(pos, blockState));
            }
            deferredWindow++;
        }
    }

    /** 混入侧问：现在是不是 {@link #flush()} 自己发起的调用？（是 ⇒ 必须放行） */
    public static boolean isFlushing() {
        try {
            return Boolean.TRUE.equals(IN_FLUSH.get());
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== 收尾

    /** 挂在 {@code MinecraftForge.EVENT_BUS}（注册见 {@code ShanhaiMod} 的构造器）。 */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        try {
            if (event == null || event.phase != TickEvent.Phase.END) {
                return;
            }
            logArmedOnce();
            flush();
        } catch (Throwable t) {
            noteError("onServerTick", t);
        }
    }

    /**
     * 把这一 tick 攒下的变化逐个补上。
     *
     * <p>每个 state：置守卫 → 调<b>原</b> {@code MultiblockState#onBlockStateChanged} → 清守卫。
     * 单个 state 抛异常只记一行、继续下一个（红线 4）。
     */
    public static void flush() {
        final List<Map.Entry<MultiblockState, Pending>> todo;
        synchronized (LOCK) {
            if (PENDING.isEmpty()) {
                return;
            }
            todo = new ArrayList<>(PENDING.entrySet());
            PENDING.clear();
        }
        for (Map.Entry<MultiblockState, Pending> entry : todo) {
            final MultiblockState state = entry.getKey();
            final Pending pending = entry.getValue();
            if (state == null || pending == null || pending.pos == null) {
                continue;
            }
            final long t0 = System.nanoTime();
            final Boolean previous = IN_FLUSH.get();
            IN_FLUSH.set(Boolean.TRUE);
            try {
                // 这里就是"原逻辑"：走的是同一个方法，只是由我们在 tick 末尾代它调一次
                state.onBlockStateChanged(pending.pos, pending.blockState);
            } catch (Throwable t) {
                noteError("flush.onBlockStateChanged", t);
            } finally {
                IN_FLUSH.set(previous);
            }
            final long nanos = System.nanoTime() - t0;
            synchronized (LOCK) {
                appliedWindow++;
                appliedTotalNanosWindow += nanos;
                if (nanos > appliedMaxNanosWindow) {
                    appliedMaxNanosWindow = nanos;
                }
            }
        }
    }

    // ================================================================== 计数（给探针读）

    /** 一次窗口的读数（探针 summary 用）。 */
    public static final class Window {

        public final int deferred;
        public final int applied;
        public final double appliedMaxMs;
        public final double appliedTotalMs;

        Window(int deferred, int applied, double appliedMaxMs, double appliedTotalMs) {
            this.deferred = deferred;
            this.applied = applied;
            this.appliedMaxMs = appliedMaxMs;
            this.appliedTotalMs = appliedTotalMs;
        }

        public static final Window EMPTY = new Window(0, 0, 0.0, 0.0);
    }

    /** 取走本窗口的计数并清零（探针每次打 summary 时调一次）。 */
    public static Window pollWindow() {
        synchronized (LOCK) {
            if (deferredWindow == 0 && appliedWindow == 0 && appliedMaxNanosWindow == 0L) {
                return Window.EMPTY;
            }
            final Window snapshot = new Window(deferredWindow, appliedWindow,
                    appliedMaxNanosWindow / 1_000_000.0, appliedTotalNanosWindow / 1_000_000.0);
            deferredWindow = 0;
            appliedWindow = 0;
            appliedMaxNanosWindow = 0L;
            appliedTotalNanosWindow = 0L;
            return snapshot;
        }
    }

    /** 当前配置值（未加载时按声明默认 true 处理 —— 与 toml 里那项的默认值一致）。 */
    public static boolean configEnabled() {
        try {
            if (!ShanhaiConfig.isLoaded()) {
                return true;
            }
            return ShanhaiConfig.COMMON.patternCheckCoalesce.get();
        } catch (Throwable t) {
            return true;
        }
    }

    // ================================================================== 内部

    /** 图案格子数；拿不到返回 -1。读的是 {@code BlockPattern} 的三个 {@code protected final int}。 */
    private static int patternCells(IMultiController controller) {
        try {
            final BlockPattern pattern = controller.getPattern();
            if (pattern == null) {
                return -1;
            }
            if (pattern == cachedPattern && cachedCells > 0) {
                return cachedCells;
            }
            resolveFields();
            final Field finger = fFinger;
            final Field thumb = fThumb;
            final Field palm = fPalm;
            if (finger == null || thumb == null || palm == null) {
                return -1;
            }
            final long cells = (long) palm.getInt(pattern) * (long) thumb.getInt(pattern) * (long) finger.getInt(pattern);
            if (cells <= 0 || cells > Integer.MAX_VALUE) {
                return -1;
            }
            cachedPattern = pattern;
            cachedCells = (int) cells;
            return cachedCells;
        } catch (Throwable t) {
            noteError("patternCells", t);
            return -1;
        }
    }

    private static void resolveFields() {
        if (fieldsResolved) {
            return;
        }
        fieldsResolved = true;
        try {
            fFinger = field("fingerLength");
            fThumb = field("thumbLength");
            fPalm = field("palmLength");
        } catch (Throwable t) {
            noteError("resolveFields", t);
        }
    }

    private static Field field(String name) {
        try {
            final Field f = BlockPattern.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            noteError("field:" + name, t);
            return null;
        }
    }

    /** 启动自证：一行，含当前开关与阈值（{@code ServerTickEvent} 第一次 END 时打）。 */
    private static void logArmedOnce() {
        if (armedLogged) {
            return;
        }
        armedLogged = true;
        try {
            LOGGER.info("{} armed={} threshold={} target=GuixuVisionCore only source=ServerTickEvent.End",
                    PREFIX, configEnabled(), CELL_THRESHOLD);
            LOGGER.info("{} 语义代价：结构失效/重新成型最多晚 1 tick（≈50ms）才发现；"
                    + "其它 mod 与 gtceu/gtlcore 的多方块一律不受影响", PREFIX);
        } catch (Throwable ignored) {
            // 日志失败不影响任何逻辑
        }
    }

    /** 同一个错误只打第一次（避免刷屏），其余计数在 summary 里体现。 */
    private static void noteError(String where, Throwable t) {
        try {
            final String msg = where + ": " + t;
            if (msg.equals(lastError)) {
                return;
            }
            lastError = msg;
            LOGGER.warn("{} 内部异常（已按红线放行原逻辑）：{}", PREFIX, msg);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 一条待处理的变化。 */
    private static final class Pending {

        final BlockPos pos;
        final BlockState blockState;

        Pending(BlockPos pos, BlockState blockState) {
            this.pos = pos;
            this.blockState = blockState;
        }
    }
}
