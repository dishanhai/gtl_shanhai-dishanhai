package com.shanhai.machine.wildcard;

import com.mojang.logging.LogUtils;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.machine.module.PrimordialModuleMachine;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>「额外产出倍率」的来源读取器</b> —— 从「控制器」对象上读出那台机器给配方的额外产出倍率。
 *
 * <h2>🔴 两种来源（用户 2026-10-04 第二轮修正①逐字：
 * 「其实不止是伪神之锻炉，还有我们的原始终焉引擎，它也能提供额外产出」）</h2>
 * <table border="1">
 *   <tr><th>来源</th><th>怎么读</th><th>真源（唯一）</th></tr>
 *   <tr>
 *     <td>① 原始终焉引擎（本工程自己的机器）</td>
 *     <td><b>直接调 API</b>（<b>不用反射</b>）：<br>
 *         {@code PrimordialRecipeEffects.outputMultiplier(engine.moduleSlotBonus())}</td>
 *     <td>{@code PrimordialRecipeEffects#outputMultiplier(int)}
 *         —— 它<b>就是</b>那台机器 GUI 上印的「产出倍率：×N」，
 *         也就是 {@code applyHostTailEffects} 里真正乘进配方产出的那个数
 *         （源码双向核对：{@code outputMultiplier(gateBonus) = gateBonus <= 0 ? 1 : 1 + gateBonus}，
 *         而 {@code gateBonus} 的<b>唯一</b>入口是
 *         {@code PrimordialOmegaEngineMachine#moduleSlotBonus()}，
 *         它也就是那个 {@code @DescSynced} 镜像 {@code matterBonusLevel} 的实况重算）。</td>
 *   </tr>
 *   <tr>
 *     <td>② 伪神之锻炉控制器（gtladditions 上游那台）</td>
 *     <td><b>反射</b>读 {@code public final double getRecipeOutputMultiply()}</td>
 *     <td>同一个方法名就是唯一读点（详见下面 §一）。</td>
 *   </tr>
 * </table>
 * <p>两种都对不上 ⇒ {@link ShanhaiAutoForgeMultiplier#REASON_NO_SOURCE}
 * ⇒ 生效倍率按 {@link ShanhaiAutoForgeMultiplier#NO_EXTRA}（<b>×1</b>）算 ——
 * 这<b>不是</b>「退回手填」（用户修正④：手填值只在开关关着时才算数）。
 *
 * <h2>一、伪神锻那一路读的是哪一个方法（{@code javap} 实证，不是我猜的）</h2>
 * <pre>
 *   类 ： com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist
 *   方法： public final double getRecipeOutputMultiply();          ← 【真值】，本类读它
 *          public final double getRecipeOutputMultiplyPreview();   ← 预览值（GUI 用），本类【不】读
 *   实证命令（打在 libs 里那份真 jar 上，不是打在反编译产物上）：
 *     javap -p -c -cp shanhai-rewrite\libs\gtladditions-3.2.8Custom-fix2.jar \
 *           com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist
 *   上游实现（javap -c 原文，逐行）：
 *     getRecipeOutputMultiply() = baseRecipeOutputMultiply() × getRecursiveReverseBuffState().getOutputMultiplier()
 *     baseRecipeOutputMultiply():
 *         0: bipush 14            // 14.0 先压栈（等一下要乘）
 *         4: getfield runningSecs:J
 *        11: Math.min(JJ)J        // min(runningSecs, 14400)
 *        19: ddiv                 // ÷ 14400.0            ⇒ ratio ∈ [0,1]
 *        20: ldc2_w 2.0d
 *        23: Math.pow(DD)D        // 🔴 【ratio^2.0】（不是 2^ratio！操作数顺序见 §三）
 *        26: dmul                 // 14 × ratio²
 *        31: dadd                 // + 1.0
 *      ⇒ base = 1 + 14 × (min(runningSecs,14400)/14400)²  ∈ 【1, 15】
 *        （runningSecs=0 ⇒ 1；跑满 14400 秒 = 4 小时 ⇒ 15 —— 用户说的「最高 15」就是它 ✓）
 *     RecursiveReverseBuffState.outputMultiplier（在 RecursiveReverseArray#buildBuffState 里造）：
 *        … 680: iload 6 / 685: ldc2_w 2.0d / 691: dconst_1 …
 *      ⇒ 那个因子只可能是 【1.0 或 2.0】⇒ 总倍率 ∈ [1, 30]
 * </pre>
 *
 * <h2>二、🔴 为什么伪神锻那一路走反射，而原始终焉引擎那一路直接调</h2>
 * <ol>
 *   <li><b>伪神锻在 gtladditions 的「非 api」包里</b>
 *       （{@code com.gtladd.gtladditions.common.machine.multiblock.<b>controller</b>}）。
 *       本工程那条隔离墙规定 {@code common} 侧只允许 {@code GtlAddCompat} 引用它的非 api 符号，
 *       而 {@code GtlAddCompat} 只转发【结构/几何/配方类型】这类常量与纯函数，
 *       <b>不收机器实例的类型</b> ⇒ 这里必须按「方法名 + 签名」反射找，找到就调、找不到就退。</li>
 *   <li><b>跨变体二进制安全</b>：本工程实测过 4 个 gtladditions 变体；直接引用具体类会在换变体时
 *       炸 {@code NoSuchMethodError}。反射找不到时只返回 NO_READ ⇒ 按 ×1 算，<b>不会让机器崩</b>。</li>
 *   <li><b>原始终焉引擎是本工程自己的类</b>（{@code com.shanhai.common.machine.PrimordialOmegaEngineMachine}）
 *       ⇒ 既没有变体问题、也不受那条隔离墙约束 ⇒ <b>直接调 API</b>，一行反射都不需要。
 *       🔴 用户修正①的措辞就是这个意思（「还有<b>我们的</b>原始终焉引擎」）。</li>
 * </ol>
 *
 * <h2>三、🔴 一个必须写下来的教训（2026-10-04 修正③）</h2>
 * 上一轮我把 {@code baseRecipeOutputMultiply} 的公式读成了 {@code 2^(ratio)}，于是算出
 * 「0 秒 = 15、4 小时 = 29」——<b>错了两处</b>：既把 {@code Math.pow} 的两个操作数读反了
 * （字节码先 {@code ddiv} 出 ratio、再压 {@code 2.0}，所以是 {@code ratio²}），
 * 也把值域的方向搞反了。正确口径是 <b>[1, 15]</b>，用户说的「最高 15」是对的。
 * 现在这条公式已经<b>变成自检里的一条断言</b>（游戏里跑的是同一份字节码）⇒
 * 不会再靠「我记得字节码长什么样」。
 *
 * <h2>四、🔴 失败一律返回 {@link ShanhaiAutoForgeMultiplier#NO_READ}（绝不上抛）</h2>
 * 调用方（{@code SuperWildcardPatternBufferPartMachine}）拿到的只有两种结果：
 * 「一个合法倍率 + 来源标注」或「NO_READ + 一个短原因」。<b>没有第三种</b> ——
 * 不存在「抛异常让配方算错」这条路径。文本分档（见 {@code ShanhaiAutoForgeMultiplier} 的常量）：
 * <pre>
 *   未成型（无控制器）      常态（老机器默认自动开，但很多控制器不给额外产出）
 *   控制器无额外产出        常态（既不是伪神锻、也不是原始终焉引擎）
 *   返回值非法              ⇒ 按 ×1 算
 *   读取异常                ⇒ 按 ×1 算
 *   伪神锻但签名变了        🔴 这一档要 ERROR（上游改过方法签名，必须看得见）
 * </pre>
 *
 * <h2>五、并发与开销</h2>
 * 伪神锻那一路的 {@code Class → Method} 解析结果<b>按类缓存</b>（{@link ConcurrentHashMap}），
 * 且<b>连「没有这个方法」也缓存</b>（用 {@link Optional#empty()} 表示）——
 * 否则每 5 秒一次 {@code getMethod} 抛一次 {@code NoSuchMethodException} 是白烧的。
 * 解析本身不碰任何实例状态。原始终焉引擎那一路只是一次普通方法调用 + 一次纯函数查表。
 */
public final class ShanhaiForgeMultiplierReader {

    /** 上游那个「真值」方法名（逐字，来自 javap）。 */
    public static final String METHOD_NAME = "getRecipeOutputMultiply";

    /**
     * 模块找主机的方法名（{@code public 无参 getHost()}）。
     *
     * <p>{@code javap -p} 实证：{@code ForgeOfTheAntichristModuleBase} 上有
     * {@code public com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist getHost()}。
     */
    public static final String HOST_METHOD_NAME = "getHost";

    /** 上游那台控制器的 FQN —— 只用于「签名变了要报 ERROR」这一档的判定，不做编译期引用。 */
    public static final String FORGE_OF_THE_ANTICHRIST_CLASS =
            "com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code Class → 解析结果}；{@code Optional.empty()} = 这个类上没有那个方法（负缓存）。 */
    private static final Map<Class<?>, Optional<Method>> RESOLVED = new ConcurrentHashMap<>();

    /** 同上，另一张表给 {@code getHost()} 用（两张表分开：命中的概率完全不同）。 */
    private static final Map<Class<?>, Optional<Method>> HOST_METHODS = new ConcurrentHashMap<>();

    /** 解析失败只报一次 ERROR 的闸门（免得每 5 秒一条）。 */
    private static volatile boolean signatureBrokenReported;

    private ShanhaiForgeMultiplierReader() {}

    /** 一次读取的结果：要么 {@code value} 合法（且 {@code note} = 来源），要么 {@code value == NO_READ}（{@code note} = 原因）。 */
    public static final class Result {

        /** 规范化后的倍率；{@link ShanhaiAutoForgeMultiplier#NO_READ} = 没读到。 */
        public final int value;

        /**
         * 读到时 = 来源标注（{@code ShanhaiAutoForgeMultiplier.SOURCE_*}）；
         * 读不到时 = 短原因（{@code ShanhaiAutoForgeMultiplier.REASON_*}）。
         * 两种都会被机器<b>原样同步给面板第二行</b>（前缀由纯函数加：「来源：」/「原因：」）。
         */
        public final String note;

        public Result(int value, String note) {
            this.value = value;
            this.note = note == null ? "" : note;
        }

        /** 便捷构造：读不到。 */
        public static Result none(String reason) {
            return new Result(ShanhaiAutoForgeMultiplier.NO_READ, reason);
        }

        @Override
        public String toString() {
            return value == ShanhaiAutoForgeMultiplier.NO_READ
                    ? "Result{NO_READ, reason=" + note + '}'
                    : "Result{" + value + ", source=" + note + '}';
        }
    }

    /**
     * 从 {@code controller} 上读那份「额外产出倍率」。
     *
     * <h2>🔴 来源分派顺序（用户修正① + 追加 A「模块也能提供额外产出」）</h2>
     * <ol>
     *   <li>{@code null} ⇒ {@link ShanhaiAutoForgeMultiplier#REASON_NO_CONTROLLER}；</li>
     *   <li><b>本工程自己的原始终焉引擎</b> ⇒ {@link #readPrimordialEngine}
     *       （<b>直接调 API</b>，不用反射）；</li>
     *   <li><b>本工程自己的模块</b>（原初临界加工模块那一脉）⇒ 直接调 API，读<b>它主机</b>的产出倍率
     *       （它自己的 {@code addSharedEffectDisplayText} 就是这么算的：
     *       {@code outputMultiplier(getHost().moduleSlotBonus())}）；</li>
     *   <li><b>gtladditions 的伪神之锻炉</b> ⇒ 反射 {@code getRecipeOutputMultiply()}；</li>
     *   <li><b>gtladditions 的伪神之锻炉模块</b> ⇒ 反射 {@code getHost()} 拿到它主机
     *       （就是那台伪神之锻炉），再按第 4 条读；</li>
     *   <li>都不是 ⇒ {@link ShanhaiAutoForgeMultiplier#REASON_NO_SOURCE}（⇒ 生效倍率 ×1）。</li>
     * </ol>
     * <p>⚠️ 第 3/5 条是用户 2026-10-04 追加①的落点：他实测「装到那台模块多方块里显示读不到」。
     * 现场形态是「仓室装在<b>以模块机器为控制器</b>的多方块里」⇒ {@code getControllers()} 拿到的
     * 就是那台模块 ⇒ 本方法必须先认出模块、再看它的主机。
     *
     * @param controller 那个多方块的控制器对象
     *                   （<b>故意用 {@link Object}</b>：本方法不许把上游类型写进编译期签名）
     * @param min        合法下界（= {@code ShanhaiForgePatternMode.MIN_MULTIPLIER}）
     * @param max        合法上界（= {@code ShanhaiForgePatternMode.MAX_MULTIPLIER}）
     * @return 永远非 null；读不到时返回 {@code NO_READ} + 原因，<b>绝不抛异常</b>
     */
    public static Result read(Object controller, int min, int max) {
        // 🔴 最外层兜底：连【类加载/链接失败】（NoClassDefFoundError、ExceptionInInitializerError）
        //    都不许逃出去。上面那句 instanceof 在极早期就可能触发 PrimordialOmegaEngineMachine
        //    的类加载 —— 那是本工程自己的类，但「永远在」这个假设不该由调用方来承担。
        try {
            return readUnguarded(controller, min, max);
        } catch (Throwable t) {
            LOGGER.info("[SHANHAI-WILDCARD] 自动倍率：读取额外产出倍率时抛出 {} ⇒ 本次按「无额外产出 ×1」算：{}",
                    t.getClass().getName(), t.toString());
            return Result.none(ShanhaiAutoForgeMultiplier.REASON_FAILED);
        }
    }

    /** {@link #read} 的本体（不含最外层兜底）。 */
    private static Result readUnguarded(Object controller, int min, int max) {
        if (controller == null) {
            return Result.none(ShanhaiAutoForgeMultiplier.REASON_NO_CONTROLLER);
        }
        // ── 来源①：原始终焉引擎（走【类型判断 + 直接调 API】，本工程自己的机器，不需要反射） ──
        if (controller instanceof PrimordialOmegaEngineMachine engine) {
            return readPrimordialEngine(engine, min, max, ShanhaiAutoForgeMultiplier.SOURCE_PRIMORDIAL_ENGINE);
        }
        // ── 来源②：本工程自己的模块 ⇒ 直接调 API 读【它主机】的产出倍率 ──
        if (controller instanceof PrimordialModuleMachine module) {
            final PrimordialOmegaEngineMachine host = module.getHost();
            if (host == null) {
                // 本工程模块自己的算法就是「没有主机 ⇒ gateBonus = 0 ⇒ ×1」
                // （见 PrimordialModuleMachine#addSharedEffectDisplayText）⇒ 这是一次【成功】的读取。
                return new Result(
                        ShanhaiAutoForgeMultiplier.clamp(ShanhaiAutoForgeMultiplier.NO_EXTRA, min, max),
                        ShanhaiAutoForgeMultiplier.SOURCE_PRIMORDIAL_MODULE);
            }
            return readPrimordialEngine(host, min, max, ShanhaiAutoForgeMultiplier.SOURCE_PRIMORDIAL_MODULE);
        }
        // ── 来源③：gtladditions 的伪神之锻炉（反射；上游类型不进编译期签名） ──
        final Result direct = readByOutputMultiplyMethod(
                controller, min, max, ShanhaiAutoForgeMultiplier.SOURCE_FORGE_OF_THE_ANTICHRIST);
        if (direct.value != ShanhaiAutoForgeMultiplier.NO_READ) {
            return direct;
        }
        // ── 来源④：gtladditions 的伪神之锻炉【模块】⇒ 反射 getHost() 拿它主机，再按来源③读 ──
        final Object host = reflectiveHost(controller);
        if (host != null) {
            final Result viaHost = readByOutputMultiplyMethod(
                    host, min, max, ShanhaiAutoForgeMultiplier.SOURCE_FORGE_MODULE);
            if (viaHost.value != ShanhaiAutoForgeMultiplier.NO_READ) {
                return viaHost;
            }
            return viaHost;     // 主机认出来了但读不到 ⇒ 把那条具体原因带回去（比「无额外产出」更有用）
        }
        return Result.none(ShanhaiAutoForgeMultiplier.REASON_NO_SOURCE);
    }

    /**
     * 按「上游那台控制器自己的 {@code public double getRecipeOutputMultiply()}」读一次。
     *
     * @param source 读成功时记下来的<b>来源标注</b>（控制器 / 模块，取决于调用方）
     */
    private static Result readByOutputMultiplyMethod(Object target, int min, int max, String source) {
        final Class<?> clazz = target.getClass();
        final Optional<Method> resolved = RESOLVED.computeIfAbsent(clazz, ShanhaiForgeMultiplierReader::resolve);
        if (resolved.isEmpty()) {
            return Result.none(reasonForMissingMethod(clazz));
        }
        try {
            final Object raw = resolved.get().invoke(target);
            if (!(raw instanceof Number number)) {
                return Result.none(ShanhaiAutoForgeMultiplier.REASON_ILLEGAL_VALUE);
            }
            final int normalized = ShanhaiAutoForgeMultiplier.normalizeRead(number.doubleValue(), min, max);
            return normalized == ShanhaiAutoForgeMultiplier.NO_READ
                    ? Result.none(ShanhaiAutoForgeMultiplier.REASON_ILLEGAL_VALUE)
                    : new Result(normalized, source);
        } catch (Throwable t) {
            // 🔴 这里必须是 Throwable：上游可能抛 Error（内部状态未就绪时的
            //    NullPointerException / ExceptionInInitializerError / NoClassDefFoundError）。
            //    任何一种都只允许按 ×1 算，绝不许把异常带进配方路径。
            LOGGER.info("[SHANHAI-WILDCARD] 自动倍率：读取 {}.{}() 时异常 ⇒ 本次按「无额外产出 ×1」算（不刷屏，仅首次打印）：{}",
                    clazz.getName(), METHOD_NAME, t.toString());
            return Result.none(ShanhaiAutoForgeMultiplier.REASON_FAILED);
        }
    }

    /**
     * 反射取「模块的主机」——{@code public 无参 getHost()}。
     *
     * <p>用途只有一个：认出 gtladditions 的<b>伪神之锻炉模块</b>
     * （{@code ForgeOfTheAntichristModuleBase}，javap 实证它有
     * {@code public ForgeOfTheAntichrist getHost()}），然后按主机去读。
     * <b>又一次不把上游类型写进编译期签名。</b>
     *
     * <p>⚠️ 认不出来/取不到一律返回 {@code null}（调用方据此给「无额外产出」）⇒ 失败是软的。
     */
    private static Object reflectiveHost(Object module) {
        try {
            final Class<?> clazz = module.getClass();
            final Method getter = HOST_METHODS.computeIfAbsent(clazz, c -> {
                try {
                    final Method m = c.getMethod(HOST_METHOD_NAME);
                    m.setAccessible(true);
                    return Optional.of(m);
                } catch (Throwable t) {
                    return Optional.<Method>empty();
                }
            }).orElse(null);
            return getter == null ? null : getter.invoke(module);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * <b>来源①：原始终焉引擎</b>（本工程自己的机器）。
     *
     * <h2>唯一真源（用户修正①点名的那一脉）</h2>
     * <pre>
     *   PrimordialRecipeEffects.outputMultiplier(engine.moduleSlotBonus())
     *       = gateBonus &lt;= 0 ? 1 : 1 + gateBonus          ⇒ 值域 【1, 18】
     *   gateBonus = PrimordialOmegaEngineMachine#moduleSlotBonus()
     *       = 主机「物质模块专属槽」内单一物品类型且数量 == 64 时的模块等级，否则 0
     * </pre>
     * <p><b>判据（为什么这就是"当前生效的额外产出倍率"）</b>：
     * <ol>
     *   <li>它<b>就是</b>那台机器 GUI 上印的那一行 ——
     *       {@code PrimordialOmegaEngineMachine#addDisplayText} 里
     *       {@code int outputMultiplier = PrimordialRecipeEffects.outputMultiplier(bonus);}
     *       然后印「产出倍率：×N（额外产出 N 份）」；</li>
     *   <li>它<b>同时</b>是真正乘进配方产出的那个数 ——
     *       {@code PrimordialRecipeEffects.applyHostTailEffects(recipe, gateBonus, …)} 里
     *       {@code multiplyOutputs(recipe, outputMultiplier(gateBonus))}
     *       （{@code applyHostTailEffectsA} 同款）；</li>
     *   <li>全库 grep {@code outputMultiplier} 的调用点只有这几处（机器 GUI / 引擎配方逻辑 /
     *       模块侧 / 星体渲染），<b>没有任何第二份"额外产出倍率"的定义</b>。</li>
     * </ol>
     * <p>⚠️ {@link PrimordialOmegaEngineMachine#moduleSlotBonus()} 在服务端是<b>实况重算</b>
     * （{@code refreshMatterBonusLevel()}），所以这里读到的就是此刻生效的值，
     * 不需要等某个 tick。
     */
    private static Result readPrimordialEngine(PrimordialOmegaEngineMachine engine, int min, int max, String source) {
        try {
            final int gateBonus = engine.moduleSlotBonus();
            final int raw = PrimordialRecipeEffects.outputMultiplier(gateBonus);
            final int normalized = ShanhaiAutoForgeMultiplier.normalizeRead(raw, min, max);
            return normalized == ShanhaiAutoForgeMultiplier.NO_READ
                    ? Result.none(ShanhaiAutoForgeMultiplier.REASON_ILLEGAL_VALUE)
                    : new Result(normalized, source);
        } catch (Throwable t) {
            LOGGER.info("[SHANHAI-WILDCARD] 自动倍率：读取原始终焉引擎的产出倍率时异常 "
                            + "⇒ 本次按「无额外产出 ×1」算（不刷屏，仅首次打印）：{}", t.toString());
            return Result.none(ShanhaiAutoForgeMultiplier.REASON_FAILED);
        }
    }

    /** 该类是不是上游那台伪神锻（只用于报错档位，不做编译期引用）。 */
    public static boolean isForgeOfTheAntichrist(Class<?> clazz) {
        return clazz != null && FORGE_OF_THE_ANTICHRIST_CLASS.equals(clazz.getName());
    }

    /**
     * 「没有那个方法」的原因分两档：
     * 类名就是伪神锻 ⇒ 说明上游改过签名（{@link ShanhaiAutoForgeMultiplier#REASON_SIGNATURE_BROKEN}，要报 ERROR）；
     * 否则就是「这台控制器本来就不给额外产出」（常态，INFO）。
     */
    private static String reasonForMissingMethod(Class<?> clazz) {
        if (!isForgeOfTheAntichrist(clazz)) {
            return ShanhaiAutoForgeMultiplier.REASON_NO_SOURCE;
        }
        if (!signatureBrokenReported) {
            signatureBrokenReported = true;
            LOGGER.error("[SHANHAI-WILDCARD] 自动倍率不可用：{} 上找不到 public double {}(无参)。"
                            + "⇒ 说明上游改过方法签名。本机将按「无额外产出 ×1」算（不影响配方正确性），"
                            + "但「自动倍率」这个开关对这台控制器不会读到任何额外产出。"
                            + "（这【不是】静默失效：本条 ERROR 就是它的证据。）",
                    FORGE_OF_THE_ANTICHRIST_CLASS, METHOD_NAME);
        }
        return ShanhaiAutoForgeMultiplier.REASON_SIGNATURE_BROKEN;
    }

    /**
     * 解析 {@code public double getRecipeOutputMultiply()}（无参、返回 {@code double}）。
     *
     * <p>🔴 只认 {@code getMethod}（<b>public</b>）+ <b>返回类型恰为 {@code double}</b>：
     * <ul>
     *   <li>用 {@code getDeclaredMethod} + {@code setAccessible} 会连 private 的同名方法也捞进来
     *       ——那是「本工程自己造的可见性」，不是上游给的契约；</li>
     *   <li>校验返回类型是为了避开<b>同名但语义不同</b>的重载（上游签名是 {@code ()D}，
     *       javap 实证）。返回类型对不上 ⇒ 当作「没有这个方法」。</li>
     * </ul>
     */
    private static Optional<Method> resolve(Class<?> clazz) {
        try {
            final Method method = clazz.getMethod(METHOD_NAME);
            if (method.getReturnType() != double.class) {
                return Optional.empty();
            }
            LOGGER.info("[SHANHAI-WILDCARD] 自动倍率：已解析 {}.{}()  （签名 ()D，public），后续按类缓存",
                    clazz.getName(), METHOD_NAME);
            return Optional.of(method);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
