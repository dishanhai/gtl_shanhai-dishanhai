package com.shanhai.mixin;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.machine.wildcard.UpstreamAutoForgeMultiplierHost;
import com.shanhai.machine.wildcard.AutoForgeMultiplierPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>把「自动倍率」那三行加进上游自己的面板</b>（用户 2026-10-04 追加需求 B）。
 * <h2>为什么是「往它的面板里加」而不是「再挂一个面板」</h2>
 * 上游那台本来就有自己的「神锻样板模式」侧栏面板
 * （{@code com.gtladd.gtladditions.api.machine.gui.FOAPatternConfigurator}）。
 * 如果再挂一个我们自己的面板，玩家会看到<b>两个长得一样的面板</b>；
 * 而这里追加三行，得到的就是<b>与我们自己那台完全一致</b>的那一个面板 ——
 * 用户逐字要的正是这个（「与我们自己那台完全一致」）。
 *
 * <h2>🔴 怎么做到「上游那 4 个控件一个字都不动」</h2>
 * 本注入在 {@code createConfigurator()} <b>返回之后</b>拿它返回的那个 {@code WidgetGroup}，
 * 只做两件事：
 * <ol>
 *   <li>{@code group.setSize(140, 104)} —— 把面板撑高（上游那 4 个控件的坐标尺寸一个没改，
 *       最右边缘仍是 112 &lt; 140）；</li>
 *   <li>{@code AutoForgeMultiplierPanel.appendTo(group, host)} —— 追加自动倍率开关 + 两行状态，
 *       并把<b>它那个</b> {@code IntInputWidget} 接管过来做「自动开着 ⇒ 禁用 + 变灰」。</li>
 * </ol>
 * 上游的控件对象、坐标、文案、{@code getIcon()}/{@code getTooltips()} 全部原样。
 *
 * <h2>⚠️ 怎么拿到那台机器（不把上游类型写进编译期签名）</h2>
 * 上游的 configurator 里有个 {@code private final MESuperPatternBufferPartMachine machine}。
 * 本 mixin <b>不用 @Shadow</b>（那要求把字段类型写进签名）——
 * 改为让 {@link AutoForgeMultiplierPanel#findHost(Object)} 反射扫一遍字段，
 * 取第一个 {@code instanceof AutoForgeMultiplierHost} 的（= 被
 * {@link ShanhaiSuperPatternBufferAutoMultiplierMixin} 加装过的那台机器）。
 * 取不到就<b>什么都不做</b>（fail-soft）。
 *
 * <h2>🔴 fail-soft 三件套</h2>
 * 住在 {@code "required": false} 的 {@code shanhai.gtladditions.mixin.json}、
 * 注入写死 {@code require = 0}、配置里 {@code defaultRequire: 0}。
 * 注入失败 ⇒ 上游面板照旧（118×56 那四个控件），<b>不崩、不缺件</b>。
 */
@Mixin(targets = "com.gtladd.gtladditions.api.machine.gui.FOAPatternConfigurator", remap = false)
public abstract class ShanhaiFoaPanelAutoMultiplierMixin implements AutoForgeMultiplierPanel.UpstreamPanelExtender {

    /** {@code createConfigurator()} 的完整描述符（{@code javap -s} 实证）。 */
    private static final String CREATE_DESC = "createConfigurator()Lcom/lowdragmc/lowdraglib/gui/widget/Widget;";

    /**
     * 上游的面板建好之后，就地追加我们的三行。
     *
     * <p>⚠️ {@code require = 0}：上游哪天把这个方法改名/改返回类型 ⇒ 静默跳过，不崩。
     *
     * <p>⚠️ 不改返回值（{@code cir.setReturnValue}）：我们是<b>就地</b>改那个 group
     * （上游把它 addWidget 进 Tab 的 view 之后，改它的 size 会触发 Tab 自己重排）
     * ⇒ 返回的还是同一个对象，下游不需要重挂。
     *
     * <p>🔴 <b>传给面板的宿主是 {@link UpstreamAutoForgeMultiplierHost}（本工程自己的类）</b>，
     * <b>不是</b>那台机器本身 —— 那台机器的接口方法由 mixin 提供，一旦漏合并就是
     * {@code AbstractMethodError}（上一版就是这么崩的）。本 mixin 在这里只做两件事：
     * 反射反查机器（那段反射在 {@link UpstreamAutoForgeMultiplierHost#ofHolder} 里）＋ 交给面板。
     */
    @Inject(method = CREATE_DESC, at = @At("RETURN"), require = 0)
    private void shanhai$appendAutoMultiplierRows(CallbackInfoReturnable<Widget> cir) {
        final Widget widget = cir.getReturnValue();
        if (!(widget instanceof WidgetGroup group)) {
            return;     // 上游换了返回类型 ⇒ 什么都不做（fail-soft）
        }
        final UpstreamAutoForgeMultiplierHost host = UpstreamAutoForgeMultiplierHost.ofHolder(this);
        if (host == null) {
            return;     // 那台机器没被加装（例如本 mixin 生效而那台没生效）⇒ 什么都不做
        }
        AutoForgeMultiplierPanel.appendTo(group, host);
    }
}
