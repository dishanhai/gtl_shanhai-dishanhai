package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.shanhai.machine.wildcard.AutoForgeMultiplierHost;
import com.shanhai.machine.wildcard.ShanhaiAutoForgeMultiplier;
import com.shanhai.machine.wildcard.ShanhaiAutoForgeMultiplierDriver;
import com.shanhai.machine.wildcard.UpstreamAutoForgeMultiplierHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * <b>给上游「超级样板总成」加装「自动倍率」</b>
 * （用户 2026-10-04 追加需求 B：「为什么超级样板总成没有」→「要，给上游那台也加上」）。
 *
 * <h2>目标</h2>
 * {@code com.gtladd.gtladditions.common.machine.multiblock.part.MESuperPatternBufferPartMachine}
 * —— gtladditions 的机器，<b>不是我们的类</b>，所以只能 mixin。
 *
 * <h2>🔴🔴 本类【只新增字段】+ 一个构造器处理器（2026-10-04 第二次返工的核心纪律）</h2>
 * 上一版把「机器对象」当宿主交给底板控件，而宿主方法由本 mixin 提供 —— 结果漏实现了两个方法
 * （mixin 类必须 abstract ⇒ javac 不拦），接口照样挂上去了，于是<b>一开面板就
 * {@code AbstractMethodError}</b>（崩溃报告 {@code crash-2026-10-04_19.36.51-server.txt}）。
 *
 * <p>⇒ 现在<b>运行时调用链一个方法都不经过本 mixin</b>：
 * <ul>
 *   <li>面板 / 驱动拿到的是 {@link UpstreamAutoForgeMultiplierHost}（<b>本工程自己的类</b>：
 *       反射读写下面这三个字段、反射调上游自己的公开方法、直接转型调 gtceu 继承来的方法）
 *       ⇒ 这条链上<b>没有</b> mixin 提供的方法；</li>
 *   <li>周期复读用的 {@code Runnable} 也在那个类里建（{@code tickerFor}）
 *       ⇒ 目标类里连 lambda 合成方法都不会多出来；</li>
 *   <li>本 mixin 多出来的方法只有<b>下面那一个构造器处理器</b>（只能由注入点自己调用、
 *       不参与任何接口分派 ⇒ 结构上不可能抛 {@code AbstractMethodError}）。</li>
 * </ul>
 *
 * <p>⚠️ 下面那 17 个 {@code @Override} 是刻意保留的<b>"契约面"、不是调用链</b>：
 * 它们让「本类实现了 {@link AutoForgeMultiplierHost}」这件事<b>可被冒烟探针逐条断言</b>
 * —— 这正是上次溜掉的洞（旧探针只验字段、没验方法）。运行时没有任何一处走它们。
 *
 * <h2>🔴 fail-soft 三件套（用户点名的硬要求）</h2>
 * <ol>
 *   <li>本 mixin 住在单独的配置 {@code shanhai.gtladditions.mixin.json} 里，该配置
 *       <b>{@code "required": false}</b> ⇒ 上游改了类名/方法/构造器签名，代价是<b>一行 WARN</b>；</li>
 *   <li>下面的 {@code @Inject} 写死 <b>{@code require = 0}</b>；</li>
 *   <li>配置里另有 {@code "injectors": {"defaultRequire": 0}} 兜底。</li>
 * </ol>
 * 注入失败时的表现：<b>机器照常工作</b>（上游那台行为一个字没改），只是没有「自动倍率」开关。
 *
 * <h2>⚠️ 为什么用 {@code targets = "…"} / 为什么不用 {@code @Shadow}</h2>
 * <ul>
 *   <li>{@code targets} 走字符串 ⇒ 上游类型不进本工程的编译期签名（本工程红线）；</li>
 *   <li><b>不 @Shadow</b>：Mixin 的注解处理器<b>只搜目标类自己、不搜继承链</b>
 *       （实测 {@code Cannot find target for @Shadow method …}）；而且 shadow 也是一种
 *       "mixin 提供的方法"，本次返工后一律不用 —— 需要父类能力时走
 *       {@code ((MetaMachine)(Object) this)} 直接转型。</li>
 * </ul>
 *
 * <h2>⚠️ 上游那台既有行为：一个字都不许动</h2>
 * 本类不碰它的四个公开方法实现、不碰 {@code attachConfigurators}；
 * 「自动」动手时只调它自己的公开写入口 {@code setFOAPatternOutputMultiplier(int)}
 * （模式开着时它自己会 {@code refreshAllByProduct()} —— 玩家手改倍率走的就是这条路）。
 */
@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.part.MESuperPatternBufferPartMachine", remap = false)
public abstract class ShanhaiSuperPatternBufferAutoMultiplierMixin implements AutoForgeMultiplierHost {

    /** 上游主构造器（{@code javap -p} 实证：{@code (IMachineBlockEntity, int, int, int)}）。 */
    private static final String CTOR_DESC =
            "<init>(Lcom/gregtechceu/gtceu/api/machine/IMachineBlockEntity;III)V";

    // ═══════════════════════ 唯一新增的"状态"：三个字段 ═══════════════════════
    //
    // 🔴 刻意【不写字段初始化器】：mixin 字段初始化器是否会被搬进目标构造器属于 Mixin 实现细节，
    //    而这三个值都必须在构造器之后确定 ⇒ 统一在下面那个 @Inject 里赋值（唯一写点，不赌）。
    //
    // 🔴 注解能不能活下来？能，而且是实证过的：Mixin 的 `mergeNewFields`（javap -c 复核）
    //    把 mixin 的 FieldNode **整个** add 进目标类的 fields 列表 ⇒ 注解跟着走；
    //    而 LDLib 的 `ManagedFieldUtils.getManagedFields(Class)` 用的是 getDeclaredFields()
    //    ⇒ 这三个字段会被自动登记为托管字段（同步/持久化）。
    //    本整合包里已有先例：gtlcore 的 `QuantumChestMachineMixin` 就是
    //    `@Unique @Persisted @DescSynced private long gtlcore$storedAmount;` 这个写法。

    /** 自动倍率开关（默认 <b>开</b>；用户逐字：「这个开关默认打开」）。 */
    @Unique @DescSynced @Persisted private boolean shanhai$autoForgeMultiplier;

    /** 最近一次读到的倍率；{@code -1} = 没读到。 */
    @Unique @DescSynced private int shanhai$autoForgeMultiplierRead;

    /** 读到时是来源、读不到时是原因（面板第二行）。 */
    @Unique @DescSynced private String shanhai$autoForgeMultiplierNote;

    // ═══════════════════════ 唯一新增的方法：构造器处理器 ═══════════════════════

    /**
     * 主构造器末尾：给三个字段写默认值（<b>默认开</b>），并把周期复读挂到服务端 tick 上。
     *
     * <p>⚠️ {@code require = 0}：上游哪天改了构造器签名 ⇒ 静默跳过，机器照常工作（只是没有自动倍率）。
     *
     * <p>⚠️ 为什么在构造器里写默认值而不是字段初始化器：见字段区那段注释。
     * 老存档读档时 LDLib 的持久化只在 NBT 里<b>有那个键</b>时才覆盖
     * （{@code IManagedAccessor.writePersistedFields} 的判据就是「键存在」）⇒
     * 没写过的老机器就是这三个默认值，行为与「默认开」一致。
     *
     * <p>⚠️ {@code subscribeServerTick} 是 gtceu {@code MetaMachine} 上的方法
     * （{@code javap -p} 实证），用<b>直接转型</b>调用（不用 {@code @Shadow}）；
     * 它内部自己判 {@code isRemote()}（客户端侧是空操作），而且只把任务塞进 {@code waitingToAdd}，
     * 构造期不会跑任何逻辑。
     *
     * <p>🔴 那个 {@code Runnable} 由 {@link UpstreamAutoForgeMultiplierHost#tickerFor(Object)}
     * <b>在本工程自己的类里</b>创建 ⇒ 目标类里不会多出 lambda 合成方法。
     */
    @Inject(method = CTOR_DESC, at = @At("RETURN"), require = 0)
    private void shanhai$initAutoForgeMultiplier(CallbackInfo ci) {
        this.shanhai$autoForgeMultiplier = true;
        this.shanhai$autoForgeMultiplierRead = ShanhaiAutoForgeMultiplier.NO_READ;
        this.shanhai$autoForgeMultiplierNote = ShanhaiAutoForgeMultiplier.REASON_AUTO_OFF;
        ((MetaMachine) (Object) this).subscribeServerTick(UpstreamAutoForgeMultiplierHost.tickerFor(this));
    }

    // ═══════════════════════ 契约面（AutoForgeMultiplierHost 的 17 个方法）═══════════════════════
    //
    // 🔴 这些方法【不在任何调用链上】：面板与驱动一律走 UpstreamAutoForgeMultiplierHost（本工程的类）。
    //    留着的唯一目的是让「本类实现了这个接口」可被校验 ——
    //    冒烟探针会对【合并后的目标类】逐个 getMethod(...) 断言它们真的在（这次的教训）。
    //    ⛔ 禁止把本对象当 AutoForgeMultiplierHost 交给任何控件 / 驱动 / 第三方。

    /** 取本工程那份适配器（取不到就返回 null ⇒ 下面每个方法退化成中性值，绝不抛）。 */
    @Unique
    private UpstreamAutoForgeMultiplierHost shanhai$upstream() {
        return UpstreamAutoForgeMultiplierHost.of(this);
    }

    @Override
    public boolean isForgePatternModeEnabled() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host != null && host.isForgePatternModeEnabled();
    }

    @Override
    public void setForgePatternModeEnabled(boolean enabled) {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        if (host != null) {
            host.setForgePatternModeEnabled(enabled);
        }
    }

    @Override
    public int getForgePatternMultiplier() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host == null ? 1 : host.getForgePatternMultiplier();
    }

    @Override
    public void setForgePatternMultiplier(int multiplier) {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        if (host != null) {
            host.setForgePatternMultiplier(multiplier);
        }
    }

    @Override
    public boolean isAutoForgeMultiplierEnabled() {
        return this.shanhai$autoForgeMultiplier;
    }

    @Override
    public void setAutoForgeMultiplierEnabled(boolean enabled) {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        if (host != null) {
            host.setAutoForgeMultiplierEnabled(enabled);
        } else {
            this.shanhai$autoForgeMultiplier = enabled;
        }
    }

    @Override
    public int shanhaiAutoForgeMultiplierRead() {
        return this.shanhai$autoForgeMultiplierRead;
    }

    @Override
    public String shanhaiAutoForgeMultiplierNote() {
        return this.shanhai$autoForgeMultiplierNote;
    }

    @Override
    public void shanhaiStoreAutoForgeMultiplier(int read, String note) {
        this.shanhai$autoForgeMultiplierRead = read;
        this.shanhai$autoForgeMultiplierNote = note;
    }

    @Override
    public String shanhaiAutoForgeMultiplierText() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host == null ? "" : host.shanhaiAutoForgeMultiplierText();
    }

    @Override
    public String shanhaiAutoForgeMultiplierReasonText() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host == null ? "" : host.shanhaiAutoForgeMultiplierReasonText();
    }

    @Override
    public ShanhaiAutoForgeMultiplierDriver shanhaiAutoForgeMultiplierDriver() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host == null ? null : host.shanhaiAutoForgeMultiplierDriver();
    }

    @Override
    public boolean shanhaiIsRemote() {
        return ((MetaMachine) (Object) this).isRemote();
    }

    @Override
    public List<?> shanhaiControllers() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host == null ? List.of() : host.shanhaiControllers();
    }

    @Override
    public int shanhaiAppliedForgeMultiplier() {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        return host == null ? 1 : host.shanhaiAppliedForgeMultiplier();
    }

    @Override
    public void shanhaiApplyForgeMultiplier(int multiplier) {
        final UpstreamAutoForgeMultiplierHost host = this.shanhai$upstream();
        if (host != null) {
            host.shanhaiApplyForgeMultiplier(multiplier);
        }
    }

    @Override
    public String shanhaiLogTag() {
        return UpstreamAutoForgeMultiplierHost.LOG_TAG;
    }
}
