package com.shanhai.machine.wildcard;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>上游「超级样板总成」的宿主适配器 —— 本工程自己的类</b>（2026-10-04 第二次返工）。
 *
 * <h2>🔴 为什么要有这个类（这次闪退的根治点）</h2>
 * 上一版把<b>机器对象本身</b>当 {@link AutoForgeMultiplierHost} 传给了底板控件：
 * <pre>
 *   LabelWidget 的 Supplier = machine::shanhaiAutoForgeMultiplierText
 * </pre>
 * 那台机器的接口方法是<b>由 mixin 提供</b>的 —— 只要有一个方法没被合并进目标类
 * （上一版就漏实现了 {@code shanhaiAutoForgeMultiplierText()} 与
 * {@code shanhaiAutoForgeMultiplierReasonText()}；mixin 类必须是 {@code abstract}，
 * 所以 javac 不拦），接口照样会挂到类上，于是**一开面板就抛 `AbstractMethodError`**。
 *
 * <p>⇒ 本类把「谁去读这台机器的状态」整个搬到<b>本工程自己的代码</b>里：
 * 面板拿到的永远是<b>本类的一个实例</b>，它
 * <b>只用两种手段</b>碰目标机器：
 * <ol>
 *   <li><b>直接转型调用 gtceu 继承来的方法</b>（{@code isRemote()} / {@code getControllers()}）——
 *       那是 gtceu 的类，与 mixin 无关；</li>
 *   <li><b>反射</b>读写 mixin 加的那三个字段 / 调上游自己的四个 {@code public} 方法。</li>
 * </ol>
 * <b>调用链上不出现任何"由 mixin 提供的方法"</b> ⇒ {@code AbstractMethodError} 这一整类
 * 失败<b>结构上不可能再发生</b>（mixin 只提供"字段"这一种东西）。
 *
 * <h2>每台机器就一个适配器实例</h2>
 * 用 {@link WeakHashMap} 按机器对象缓存（弱引用键 ⇒ 机器被卸载后自动回收，不留泄漏）。
 * tick 与面板注入两边都走 {@link #of(Object)} ⇒ 拿到的是同一个实例、同一份日志闸门与节流计数。
 */
public final class UpstreamAutoForgeMultiplierHost implements AutoForgeMultiplierHost {

    /** 日志前缀（与那台机器分得开）。 */
    public static final String LOG_TAG = "[SHANHAI-SUPER-BUFFER]";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 上游那台机器的四个公开方法（{@code javap -p} 实证，全是 public final）。 */
    private static final String M_IS_FOA_MODE = "isFOAModeEnabled";
    private static final String M_SET_FOA_MODE = "setFOAModeEnabled";
    private static final String M_GET_MULTIPLIER = "getFOAPatternOutputMultiplier";
    private static final String M_SET_MULTIPLIER = "setFOAPatternOutputMultiplier";

    /** mixin 加的三个字段（名字里含这个标记；Mixin 若给 @Unique 字段加前缀也照样能认）。 */
    private static final String FIELD_MARK = "autoForgeMultiplier";
    private static final String FIELD_MARK_READ = "autoForgeMultiplierRead";
    private static final String FIELD_MARK_NOTE = "autoForgeMultiplierNote";

    /** 机器 → 适配器。⚠️ 弱引用键：机器没了就自动回收，不会把整个存档吊住。 */
    private static final Map<Object, UpstreamAutoForgeMultiplierHost> CACHE = new WeakHashMap<>();

    /** 机器 → tick 用的 Runnable（在<b>本类</b>里建 ⇒ 目标类里不会多出 lambda 方法）。 */
    private static final Map<Object, Runnable> TICKERS = new WeakHashMap<>();

    /** {@code 机器类 → 反射元数据}（只解析一次）。 */
    private static final Map<Class<?>, Meta> METAS = new ConcurrentHashMap<>();

    /** 反射元数据；解析失败一律 {@code null}（调用方据此 fail-soft）。 */
    private static final class Meta {
        final Field auto;
        final Field read;
        final Field note;
        final Method isFoaMode;
        final Method setFoaMode;
        final Method getMultiplier;
        final Method setMultiplier;

        Meta(Field auto, Field read, Field note,
             Method isFoaMode, Method setFoaMode, Method getMultiplier, Method setMultiplier) {
            this.auto = auto;
            this.read = read;
            this.note = note;
            this.isFoaMode = isFoaMode;
            this.setFoaMode = setFoaMode;
            this.getMultiplier = getMultiplier;
            this.setMultiplier = setMultiplier;
        }
    }

    private final Object machine;
    private final MultiblockPartMachine part;
    private final Meta meta;

    /** 本机那份驱动（探测/判定/文案/限流日志全在它里面 —— 唯一实现）。 */
    private final ShanhaiAutoForgeMultiplierDriver driver = new ShanhaiAutoForgeMultiplierDriver(this);

    /** 万一 mixin 那个字段取不到（理论上不会：字段与注入同属一个 mixin），退回本对象内的一份值，绝不抛。 */
    private boolean fallbackAuto = true;

    private UpstreamAutoForgeMultiplierHost(Object machine, MultiblockPartMachine part, Meta meta) {
        this.machine = machine;
        this.part = part;
        this.meta = meta;
    }

    // ═══════════════════════════ 入口：按机器对象取（缓存）═══════════════════════════

    /**
     * <b>从上游那个面板对象（{@code FOAPatternConfigurator}）反查出机器的适配器。</b>
     *
     * <p>为什么需要它：上游面板里那个 {@code private final MESuperPatternBufferPartMachine machine}
     * 的类型是 gtladditions 的类，<b>写进 mixin 的 {@code @Shadow} 就等于把它写进本工程的
     * 编译期签名</b>（本工程红线）。这里改为<b>扫一遍字段、取第一个值是
     * {@code MultiblockPartMachine} 的</b> —— 连字段名都不依赖（上游改名也照样找得到），
     * 而且反射代码全部留在<b>本工程自己的类</b>里，mixin 那边只有一行调用。
     *
     * @param panel 上游那个面板对象（或任何持有机器引用的对象）
     * @return 那台机器的适配器；找不到 / 不成立一律 {@code null}（调用方"什么都不做"）
     */
    public static UpstreamAutoForgeMultiplierHost ofHolder(Object panel) {
        if (panel == null) {
            return null;
        }
        try {
            for (final Field field : panel.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!MultiblockPartMachine.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return of(field.get(panel));
            }
        } catch (Throwable t) {
            LOGGER.warn("{} 自动倍率：从上游面板里反查机器失败（{}）⇒ 本次不加那三行",
                    LOG_TAG, t.toString());
        }
        return null;
    }

    /**
     * 取这台机器的适配器；<b>任何一步对不上就返回 {@code null}</b>（调用方一律"什么都不做"，绝不抛）。
     *
     * <p>成立条件（三条都满足才有实例）：
     * ① 机器是 {@code MultiblockPartMachine}（我们直接转型调用它的方法）；
     * ② 上游那四个公开方法都在（否则连手填倍率都读不到）；
     * ③ mixin 那三个字段能解析到（解析不到就退回本对象内的值，仍可用 ⇒ 不返回 null）。
     */
    public static UpstreamAutoForgeMultiplierHost of(Object machine) {
        if (machine == null) {
            return null;
        }
        if (!(machine instanceof MultiblockPartMachine part)) {
            return null;
        }
        final Meta meta = METAS.computeIfAbsent(machine.getClass(), UpstreamAutoForgeMultiplierHost::resolve);
        if (meta == null) {
            return null;
        }
        synchronized (CACHE) {
            final UpstreamAutoForgeMultiplierHost existing = CACHE.get(machine);
            if (existing != null) {
                return existing;
            }
            final UpstreamAutoForgeMultiplierHost created =
                    new UpstreamAutoForgeMultiplierHost(machine, part, meta);
            CACHE.put(machine, created);
            return created;
        }
    }

    /**
     * 给 {@code subscribeServerTick(...)} 用的 Runnable —— <b>在本类里建</b>。
     *
     * <p>⚠️ 刻意不在 mixin 里写 lambda：lambda 的合成方法会落在<b>目标类</b>里，
     * 而本次返工的纪律就是「目标类里除了字段，不要再多出任何我们提供的方法」✗。
     * 在这里建 ⇒ 目标类只多出三个字段。
     */
    public static Runnable tickerFor(Object machine) {
        if (machine == null) {
            return () -> {};
        }
        synchronized (TICKERS) {
            return TICKERS.computeIfAbsent(machine, key -> () -> tickMachine(key));
        }
    }

    /** 每 tick 调一次（内部自带节流与「变了才动」）。 */
    public static void tickMachine(Object machine) {
        try {
            final UpstreamAutoForgeMultiplierHost host = of(machine);
            if (host != null) {
                host.driver.tick();
            }
        } catch (Throwable t) {
            // 🔴 fail-soft：自动倍率自己的任何异常都不许把机器的 tick 弄崩。
            LOGGER.warn("{} 自动倍率：周期复读抛出 {} ⇒ 本 tick 跳过（机器不受影响）：{}",
                    LOG_TAG, t.getClass().getName(), t.toString());
        }
    }

    // ═══════════════════════════ 反射元数据解析 ═══════════════════════════

    /** 解析一台机器类的反射元数据；四个上游方法缺任何一个就返回 {@code null}。 */
    private static Meta resolve(Class<?> clazz) {
        try {
            final Method isFoaMode = clazz.getMethod(M_IS_FOA_MODE);
            final Method setFoaMode = clazz.getMethod(M_SET_FOA_MODE, boolean.class);
            final Method getMultiplier = clazz.getMethod(M_GET_MULTIPLIER);
            final Method setMultiplier = clazz.getMethod(M_SET_MULTIPLIER, int.class);
            Field auto = null;
            Field read = null;
            Field note = null;
            for (final Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                final String name = field.getName();
                if (!name.contains(FIELD_MARK)) {
                    continue;
                }
                if (name.contains(FIELD_MARK_NOTE)) {
                    note = field;
                } else if (name.contains(FIELD_MARK_READ)) {
                    read = field;
                } else {
                    auto = field;
                }
                field.setAccessible(true);
            }
            return new Meta(auto, read, note, isFoaMode, setFoaMode, getMultiplier, setMultiplier);
        } catch (Throwable t) {
            LOGGER.warn("{} 自动倍率：解析上游机器的接口失败（{}）⇒ 这台机器本次不加「自动倍率」",
                    LOG_TAG, t.toString());
            return null;
        }
    }

    // ═══════════════════════════ AutoForgeMultiplierHost：上游那四个方法 ═══════════════════════════

    @Override
    public boolean isForgePatternModeEnabled() {
        try {
            return (Boolean) this.meta.isFoaMode.invoke(this.machine);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void setForgePatternModeEnabled(boolean enabled) {
        try {
            this.meta.setFoaMode.invoke(this.machine, enabled);
        } catch (Throwable t) {
            LOGGER.warn("{} 自动倍率：切「神锻模式」失败：{}", LOG_TAG, t.toString());
        }
    }

    @Override
    public int getForgePatternMultiplier() {
        try {
            return (Integer) this.meta.getMultiplier.invoke(this.machine);
        } catch (Throwable t) {
            return ShanhaiForgePatternMode.MIN_MULTIPLIER;
        }
    }

    @Override
    public void setForgePatternMultiplier(int multiplier) {
        try {
            this.meta.setMultiplier.invoke(this.machine, multiplier);
        } catch (Throwable t) {
            LOGGER.warn("{} 自动倍率：写「输出倍率」失败：{}", LOG_TAG, t.toString());
        }
    }

    // ═══════════════════════════ AutoForgeMultiplierHost：那三个字段 ═══════════════════════════

    @Override
    public boolean isAutoForgeMultiplierEnabled() {
        try {
            return this.meta.auto == null ? this.fallbackAuto : this.meta.auto.getBoolean(this.machine);
        } catch (Throwable t) {
            return this.fallbackAuto;
        }
    }

    @Override
    public void setAutoForgeMultiplierEnabled(boolean enabled) {
        this.fallbackAuto = enabled;
        try {
            if (this.meta.auto != null) {
                this.meta.auto.setBoolean(this.machine, enabled);
            }
        } catch (Throwable t) {
            LOGGER.warn("{} 自动倍率：写「自动开关」字段失败：{}", LOG_TAG, t.toString());
        }
        // 与那台机器同款：值变了就让生效倍率立刻落地（关掉时手填值立刻生效）。
        this.shanhaiApplyForgeMultiplier(this.driver.effective());
    }

    @Override
    public int shanhaiAutoForgeMultiplierRead() {
        try {
            return this.meta.read == null
                    ? ShanhaiAutoForgeMultiplier.NO_READ
                    : this.meta.read.getInt(this.machine);
        } catch (Throwable t) {
            return ShanhaiAutoForgeMultiplier.NO_READ;
        }
    }

    @Override
    public String shanhaiAutoForgeMultiplierNote() {
        try {
            final Object value = this.meta.note == null ? null : this.meta.note.get(this.machine);
            return value == null ? ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF : String.valueOf(value);
        } catch (Throwable t) {
            return ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF;
        }
    }

    @Override
    public void shanhaiStoreAutoForgeMultiplier(int read, String note) {
        try {
            if (this.meta.read != null) {
                this.meta.read.setInt(this.machine, read);
            }
            if (this.meta.note != null) {
                this.meta.note.set(this.machine, note);
            }
        } catch (Throwable t) {
            LOGGER.warn("{} 自动倍率：写同步字段失败：{}", LOG_TAG, t.toString());
        }
    }

    // ═══════════════════════════ AutoForgeMultiplierHost：面板那两行 ═══════════════════════════

    @Override
    public String shanhaiAutoForgeMultiplierText() {
        return this.driver.text();
    }

    @Override
    public String shanhaiAutoForgeMultiplierReasonText() {
        return this.driver.reasonText();
    }

    @Override
    public ShanhaiAutoForgeMultiplierDriver shanhaiAutoForgeMultiplierDriver() {
        return this.driver;
    }

    // ═══════════════════════════ AutoForgeMultiplierHost：驱动要的机器信息 ═══════════════════════════
    //
    // 🔴 这两个直接**转型调用 gtceu 继承来的方法**（不是反射、更不是 mixin 提供的方法）。

    @Override
    public boolean shanhaiIsRemote() {
        return this.part.isRemote();
    }

    @Override
    public List<?> shanhaiControllers() {
        final List<IMultiController> controllers = this.part.getControllers();
        return controllers;
    }

    @Override
    public int shanhaiAppliedForgeMultiplier() {
        return this.getForgePatternMultiplier();
    }

    @Override
    public void shanhaiApplyForgeMultiplier(int multiplier) {
        // 上游自己的公开写入口（玩家手改倍率走的就是它；模式开着时它自己 refreshAllByProduct）。
        this.setForgePatternMultiplier(multiplier);
    }

    @Override
    public String shanhaiLogTag() {
        return LOG_TAG;
    }

    /** 给 tick 与面板注入之外的调用方（日志/自检）用。 */
    @Override
    public String toString() {
        return "UpstreamAutoForgeMultiplierHost[" + this.machine.getClass().getName() + "]";
    }
}
