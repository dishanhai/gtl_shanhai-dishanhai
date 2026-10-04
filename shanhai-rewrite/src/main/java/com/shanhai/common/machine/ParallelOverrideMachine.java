package com.shanhai.common.machine;

import com.shanhai.common.thread.ShanhaiParallelBudget;

/**
 * <b>「玩家可调的并行数」契约 —— 原初主机与 24 台原初模块共用的一条。</b>
 *
 * <h2>为什么要有这个接口（而不是在两台机器上各写一份字段 + setter）</h2>
 * 用户 2026-09-27 原话（逐字）：
 * <blockquote>「在模块和主机的左下角再新增一个全新的按钮，他可以调节主机或者模块的并行数，
 * 作为一个输入框，可以让玩家输入数字，并且右边有一个一键调至最大的按钮」</blockquote>
 * 两台机器的 GUI 面板是同一个控件（{@link ParallelOverrideConfigurator}）⇒ 它只能依赖一个
 * <b>两边都实现</b>的契约。各写一份的后果是本工程最忌讳的形态：<b>两份实现迟早漂移</b>
 * （表现是"模块上的按钮改了并行、主机上的没改"，或者"GUI 显示的数与引擎真读的数不是同一个"）。
 *
 * <h2>语义（写死，控件与 tooltip 都必须照这个说）</h2>
 * <pre>
 *   parallelOverride == {@link #PARALLEL_AUTO}(0)  ⇒  【自动】用机器自己算出来的值
 *   parallelOverride &gt; 0                        ⇒  【覆盖】它就是该机器的并行<b>上限</b>
 *      🔴 且恒 {@code <= } {@link #getParallelOverrideCeiling()}（= 该机器此刻能达到的并行数）
 *         —— 2026-09-27 用户原话：「不允许玩家输入超出机器可以达到最大并行数的数字」
 * </pre>
 * 说它是<b>上限</b>而不是"精确值"的理由（不是随便定的）：两台机器的并行最终都要过
 * {@code IParallelLogic.getMaxParallel / getMinParallel} 这两道闸 —— 真正跑多少由
 * <b>可用输入量</b>与<b>输出空间</b>决定。玩家填 1000 而只有 5 份料时，行为必然是跑 5 份；
 * 把它说成"精确并行"就是<b>假数据</b>（本工程红线）。
 *
 * <h2>钳位纪律（服务端兜底，防伪造包）</h2>
 * 输入框自身有两道钳位（{@code TextFieldWidget.setNumbersOnly} 在客户端与服务端各跑一次），
 * 但网络包不保证只带合法值 ⇒ {@link #clampOverride(long)} 是<b>写入口那一层</b>的兜底：
 * <ul>
 *   <li>{@code <= 0}（含负数、含 0）⇒ 归到 {@link #PARALLEL_AUTO} = 自动，<b>不是</b>非法态；</li>
 *   <li>{@code > }{@link #PARALLEL_MAX} ⇒ 钳到上限；</li>
 *   <li>非数字永远到不了这里（控件那一层的 validator 会把它退回旧值）。</li>
 * </ul>
 *
 * @see ParallelOverrideConfigurator 侧栏那一个面板（左边输入框、右边「一键最大」）
 */
public interface ParallelOverrideMachine {

    /** 未覆盖：跟随机器自己算出来的值。 */
    long PARALLEL_AUTO = 0L;

    /**
     * 覆盖值的<b>绝对</b>上限（= {@code Long.MAX_VALUE}）。
     *
     * <h2>🔴 2026-09-27 用途收窄：它<b>不再</b>是「一键最大」填的那个数，也不再是钳位终点</h2>
     * 用户原话（逐字）：
     * <blockquote>「我说一键最大是到机器可以达到的并行数（也就是设置 0 时机器的并行数），
     * 而且也不允许玩家输入超出机器可以达到最大并行数的数字」</blockquote>
     * ⇒ 真正的天花板是 {@link #getParallelOverrideCeiling()}（= 该机器<b>当前</b>的
     * {@link #getAutoParallel()}），本常量降级为<b>数值类型的硬边界</b>：
     * 它只在"自动值本身取不到 / 溢出保护"时兜底，正常玩法里永远不会被触及
     * （主机的自动值就是 {@code Long.MAX_VALUE}，模块的自动值是并行表某一档）。
     */
    long PARALLEL_MAX = Long.MAX_VALUE;

    /**
     * 钳位（纯函数，唯一一份）。
     *
     * <p>⚠️ 它只做<b>类型层</b>的钳位（去掉负数、压到 {@code Long.MAX_VALUE}）。
     * 「不许超过这台机器能达到的并行数」那一条由 {@link #clampOverrideToCeiling(long)}
     * 完成 —— 因为它需要机器实例（要读当时的自动值）。
     *
     * @param value 任何来源的数（GUI 回调、网络包、命令）
     * @return {@code 0}（自动）或 {@code [1, }{@link #PARALLEL_MAX}{@code ]} 之间的值
     */
    static long clampOverride(long value) {
        if (value <= PARALLEL_AUTO) {
            return PARALLEL_AUTO;
        }
        return Math.min(value, PARALLEL_MAX);
    }

    /**
     * 🔴 <b>按本条机器【当前能达到的并行数】上钳（2026-09-27 用户实机提出）。</b>
     *
     * <pre>
     *   天花板 ceiling = max(0, getParallelOverrideCeiling())        // = getAutoParallel()
     *   ceiling <= 0  ⇒ 只有"自动"这一种合法态（返回 PARALLEL_AUTO）
     *   否则          ⇒ 0（自动） 或 [1, ceiling]
     * </pre>
     * 用<b>实例方法</b>而不是 {@code static}：天花板随机器状态变（模块的自动值每 3 tick 跟物质模块走），
     * 写成静态就只能读到一个过期快照。
     *
     * <p><b>为什么超限是"钳"而不是"拒绝"</b>：拒绝需要记住"上一个合法值"并回退输入框，
     * 而输入框的权威值来自服务端（{@code @DescSynced} 字段）⇒ 钳位后由既有的回灌机制
     * （{@code detectAndSendChanges} → {@code writeUpdateInfo(1, …)}）把钳过的值推回框里，
     * 玩家看到的就是"我刚打的数被改成了这台机器能达到的最大值" —— 比静默拒绝更不容易误解。
     *
     * @param value 任何来源的数（GUI 回调、网络包、命令）
     */
    default long clampOverrideToCeiling(long value) {
        final long ceiling = Math.max(0L, getParallelOverrideCeiling());
        if (ceiling <= PARALLEL_AUTO) {
            // 这台机器此刻只能自动（自动值为 0 / 未知）⇒ 除了"自动"没有合法覆盖值。
            return PARALLEL_AUTO;
        }
        return Math.min(clampOverride(value), ceiling);
    }

    /** 当前覆盖值；{@link #PARALLEL_AUTO} = 未覆盖。 */
    long getParallelOverride();

    /** 写入覆盖值。实现必须自己钳位（{@link #clampOverrideToCeiling(long)}）并在值真变了时才同步。 */
    void setParallelOverride(long value);

    /**
     * 🔴 <b>这台机器<b>当前</b>能达到的并行数 —— 「一键最大」填的就是它，玩家输入的上钳终点也是它。</b>
     *
     * <h2>用户原话（逐字）</h2>
     * <blockquote>「我说一键最大是到机器可以达到的并行数（也就是设置 0 时机器的并行数），
     * 而且也不允许玩家输入超出机器可以达到最大并行数的数字」</blockquote>
     * <h2>🔴 2026-09-27 语义改正（上一版填的是 {@link #PARALLEL_MAX}）</h2>
     * <pre>
     *   ⛔ 上一版：主机返回 MAX_PARALLEL（Long.MAX_VALUE）；模块返回 PARALLEL_MAX（Long.MAX_VALUE）
     *   ✅ 现行  ：返回 {@link #getAutoParallel()} ——「设 0 时机器能达到的那个并行数」
     *             主机：MAX_PARALLEL（本主机的自动值本来就是它 ⇒ 一键最大 = 恒等操作，用户口径 ④）
     *             模块：currentParallel（并行表按物质模块算出的值，默认 64）
     * </pre>
     * ⇒ <b>这个改动同时消掉了两个毛病</b>：①「一键最大」不再把一个玩家肉眼无法验证的
     * {@code 9223372036854775807} 塞进输入框；② 输入框有了一个<b>真实存在</b>的上限可钳。
     */
    long getParallelOverrideCeiling();

    /** 机器自己算出来的并行（未覆盖时用的那个）。<b>只用于显示</b>，不参与写入。 */
    long getAutoParallel();

    /**
     * 🔴 <b>输入框里那个值"到底算不算数" —— 全工程唯一一处判定（用户 2026-10-02 裁决）。</b>
     *
     * <h2>用户裁决（逐字）</h2>
     * <blockquote>「电力自动开着时，那个输入框的值【完全不算数】」—— 选择 <b>A. 不参与</b></blockquote>
     * <pre>
     *   电力自动 <b>开着</b> ⇒ 本方法恒返回 {@link #PARALLEL_AUTO}
     *                        ⇒ 下游 {@link #getEffectiveParallel()} 走的是"跟随机器自动值"那一条
     *                        ⇒ 输入框里填什么都不读、不参与任何运算
     *   电力自动 <b>关着</b> ⇒ 原样返回 {@link #getParallelOverride()}（老行为，一个字没动）
     * </pre>
     *
     * <h2>为什么是"不读"而不是"读了再忽略"</h2>
     * 上一轮只把<b>控件</b>禁用了（{@code setActive(false)}），那个值<b>仍然参与运算</b>
     * ⇒ 界面上的形态是"灰框里有个数、而这个数还在起作用"（看得见却改不动）。
     * 本轮按裁决把<b>语义</b>也改掉：开关开着时那个数在算式里<b>根本不被读取</b>。
     *
     * <p>⚠️ <b>它只影响"参与运算"这一件事</b>：{@link #getParallelOverride()} 仍然原样返回框里的值
     * （面板第 2 行读数与输入框本身的 {@code textSupplier} 读的就是它）——
     * 玩家关掉开关之后，那个值<b>照旧生效</b>（老行为没坏）。
     */
    default long getEffectiveOverride() {
        return isPowerAutoParallel() ? PARALLEL_AUTO : getParallelOverride();
    }

    /**
     * <b>单台口径的"覆盖生效之后的并行" —— 引擎与 int 桥真正读的那个数。</b>
     *
     * <pre>
     *   auto   = max(1, {@link #getAutoParallel()})
     *   覆盖值 = {@link #getEffectiveOverride()}          ← 电力自动开着时恒为 0（不参与）
     *   base   = 覆盖值 &gt; 0 ? min(覆盖值, auto) : auto
     *   生效   = {@link #applyEnergyCap(long)}(base)      ← 电力自动关着时是恒等
     * </pre>
     *
     * <h2>🔴 2026-10-02 第二轮：从"两侧各写一份 @Override"改成"本接口的 default"</h2>
     * 主机（{@code PrimordialOmegaEngineMachine}）与模块（{@code PrimordialModuleMachine}）
     * 此前各自覆写了一份，而两份的<b>逐字</b>逻辑是同一段（差别只有 {@code auto} 从哪来，
     * 而它两边都由 {@link #getAutoParallel()} 提供）。
     * ⇒ 本轮把覆写整个删掉、只留本 default：<b>公式只有一份，结构上不可能漂移</b>
     * （本工程反复记录过"两份实现迟早分叉"）。
     */
    default long getEffectiveParallel() {
        // 🔴 2026-10-02 第六轮：本方法从「调那个静态孪生 effectiveParallel(...)」改成
        //    「调 applyEnergyCap(base)」—— 两者算术【逐位相同】（applyEnergyCap 读的就是
        //    getEnergyParallel() 与 isPowerAutoParallel()；覆盖值的判定两边都由
        //    getEffectiveOverride() 给出）。改完的效果是：全工程只剩【一处钳制点】
        //    ⇒ 第六轮那条「发电机豁免」只需要写在那一处，
        //    结构上不可能出现「这条路豁免了、那条路没豁免」的静默分叉
        //    （本工程反复记载过"两份实现迟早漂移"）。
        //    ⚠️ 数值不变：改前 = max(1,auto) → 覆盖值/auto 二选一 → 有电上限时 ÷T，
        //       改后逐档同值（离线判据 A/C/E 段全部断言 getEffectiveParallel() 本身）。
        final long auto = Math.max(1L, getAutoParallel());
        final long override = getEffectiveOverride();
        final long base = (override > PARALLEL_AUTO) ? Math.min(override, auto) : auto;
        return applyEnergyCap(base);
    }

    /**
     * 🔴🔴 <b>本机「电上限 ÷ T」用的跨配方线程数 T —— 接口层唯一的新增接缝（用户 2026-10-02 第五轮裁决 ①）。</b>
     *
     * <h2>为什么接口必须多这一个方法（而不是把 T 塞进 {@link #applyEnergyCap(long)} 的实参）</h2>
     * {@link ParallelMachine} 的 {@code getMaxParallel()}（int，无参）与
     * {@link #applyEnergyCap(long)} 都是<b>无参</b>契约，而 ÷T 必须落在这一层
     * ⇒ <b>T 只能从「本机状态」取</b>。三种取法的取舍（任务书点名的三方案）：
     * <table border="1">
     *   <caption>方案对照</caption>
     *   <tr><th>方案</th><th>做法</th><th>结论</th></tr>
     *   <tr>
     *     <td><b>甲</b>（采纳）</td>
     *     <td>接口加一个带 T 的方法，<b>默认实现回落旧行为</b>（本方法默认返回 {@code 1} ⇒ 不除）</td>
     *     <td>✅ <b>本方法</b></td>
     *   </tr>
     *   <tr>
     *     <td><b>乙</b></td>
     *     <td>机器侧<b>存</b>一个 T 字段，配方逻辑算的时候写进去</td>
     *     <td>❌ <b>有超功率窗口</b>：T 变了（模块换线程槽 / 主机附加线程变）而配方逻辑还没重跑时，
     *         {@code getMaxParallel()=min(本机上限, 电上限÷T_旧)} 而父类<b>实时</b>读
     *         {@code getMultipleThreads()=T_新} ⇒ 预算 = {@code min(…) × T_新}，
     *         <b>可能 &gt; 电上限 ⇒ 总耗电超 P</b>。甲两个因子同源同刻 ⇒ 这个窗口不存在。</td>
     *   </tr>
     *   <tr>
     *     <td><b>丙</b></td>
     *     <td>机器自己把 T 再推导一遍</td>
     *     <td>⚠️ <b>只有"推导式唯一一份"时才安全</b> ⇒ 已把主机的推导收进
     *         {@code ShanhaiParallelBudget#crossRecipeThreadsForHost(int, int)}
     *         （与 {@code PrimordialEngineRecipeLogic#getMultipleThreads()} <b>同一个函数</b>），
     *         模块直接读 {@code getCrossRecipeThreads()}
     *         （与 {@code PrimordialModuleRecipeLogic#getMultipleThreads()} <b>同一个来源</b>）
     *         ⇒ 不存在"两处各算一遍"的漂移。</td>
     *   </tr>
     * </table>
     * <p>⛔ <b>「甲 + 带 T 的 {@code getMaxParallel} 重载」这条路走不通</b>：{@code getMaxParallel()}
     * 的调用点全在上游 jar 里（字节码原文 {@code invokeinterface ParallelMachine.getMaxParallel:()I}，
     * 取证文件 {@code temp/autoparallel-fix4/javap-MutableRecipesLogic.txt}）⇒
     * 带 T 的重载<b>没有任何调用者</b>，是死代码。⇒ 只能走"机器能从自己身上取到 T"这条。
     *
     * <h2>返回值的口径（两台的唯一来源，逐字）</h2>
     * <pre>
     *   默认（接口自己）：1                       ⇒ ÷1 = 恒等 = 改动前行为（老机器的兜底）
     *   主机：ShanhaiParallelBudget.crossRecipeThreadsForHost(BASE_THREADS, getAdditionalThread())
     *         —— 与 PrimordialEngineRecipeLogic#getMultipleThreads() 【同一个纯函数】⇒ 当前 = 128
     *   模块：getCrossRecipeThreads()
     *         —— 与 PrimordialModuleRecipeLogic#getMultipleThreads() 【同一个来源】⇒ 空槽 = 1
     * </pre>
     * <p>⚠️ 它<b>必须与引擎当轮真正用的那个 T 同值</b>：父类的预算是
     * {@code (long) getMaxParallel() * getMultipleThreads()} ⇒ 本值偏小 ⇒
     * {@code min(本机上限, 电上限÷T_小)} 偏大 ⇒ 乘回 {@code T_真} 后 > 电上限 ⇒ <b>超功率</b>。
     */
    default int getEnergyCapThreads() {
        return 1;
    }

    /**
     * 🔴🔴 <b>「本机不受电上限钳制」—— 用户 2026-10-02 第六轮裁决 ①「给它单独豁免」的落点。</b>
     *
     * <h2>它豁免的是谁（唯一一个实现点）</h2>
     * <pre>
     *   唯一 true 的机器 = 那台<b>发电机</b>：
     *     {@code PrimordialModuleMachine#isEnergyCapExempt()}
     *       → {@code getDefinition().isGenerator()}
     *     全 24 台原初模块里【唯一】isGenerator() 为真的那一台（「原始真空零点能发生器」），
     *     它 {@code setUseMultipleRecipes(false)} ⇒ 走【原生链】
     *     （{@code ModuleRegistry#applyModuleRecipeModifier} 里那一行
     *      {@code totalParallelLimitFor(getCurrentParallel(), getCrossRecipeThreads())}）。
     *   其余 23 台模块 + 主机（{@code PrimordialOmegaEngineMachine}）都用本接口的默认 false
     *     ⇒ 一个字节的行为都不变。
     * </pre>
     *
     * <h2>🔴 机制：<u>「电上限视为 ∞」</u>（<b>不是</b>"不写电上限"）</h2>
     * <pre>
     *   不写（exemption by omission）  ：靠在写入口"故意别写" ⇒ 只要将来任何一条路径写进一个数，
     *                                   豁免【静默失效】，而且失效时机器照常运转、只是并行掉下去，
     *                                   没有任何报错 —— 本工程吃过这类亏。
     *   视为 ∞（exemption by reading）：在【唯一那处算术】applyEnergyCap 里先判一次，
     *                                   不论写进来的是什么值都不参与钳制 ⇒ 写入口怎么变都不影响。
     *   本实现取后者。
     * </pre>
     * 为什么"视为 ∞"这个说法是准确的而不是修辞：本工程里<b>「不钳」的既有表示就是
     * 电上限 ≤ {@link #ENERGY_CAP_NONE}</b>（= 没有上限 / 没算出 / 没能源仓，
     * 见 {@link #effectiveParallel(long, long, boolean, long, int)} 的第一条短路）⇒
     * 「电上限 = ∞」与「不参与钳制」是同一件事的两种说法，不是新增语义。
     *
     * <h2>为什么要豁免它（不是"顺手放宽"，而是保住一条已验收的修复）</h2>
     * <ol>
     *   <li><b>2026-09-30 已验收修复</b>：「零点能反应堆吃跨配方并行」（用户原话：
     *       「零点能反应堆不吃跨配方并行，那个发电量都没加」）的修法就是
     *       {@code 预算 = 本机上限 × T} ⇒ 永恒物质模块表值 2147483647 × 121 线程
     *       = <b>259845521287</b>。这一档是那台机器<b>唯一</b>的产能来源。</li>
     *   <li><b>电上限对它是"错的口径"</b>：电力钳制的语义是「这一轮要吃多少 EU」，
     *       而发电机的 EUt 是<b>输出</b>语义（{@code shanhai$resolveRouting()} 里
     *       「EUt 是输出语义，引擎会算成深负值」那条注释记的就是这件事）。
     *       把"输入功率"套到一台发电机器上，量纲上就不成立。</li>
     *   <li><b>后果是灾难性的、而且现在是可复现的</b>：{@code powerAutoParallel} 自 2026-10-02
     *       第五轮起<b>默认 true</b>，一旦有值被写进 {@code energyParallel}（例如 48），
     *       该机器的接口层会给出 {@code floor(48 ÷ 121) = 0 ⇒ 保底 1}，乘回 T 得 <b>121</b>
     *       —— 从 <b>259845521287 掉到 121</b>（判据 D 段的负面对照就是这个读数）。
     *       ⚠️ 至于"发电那台到底会不会被写进电上限"，上一轮用的是<b>推断</b>
     *       （「它走原生链 ⇒ 不会走 calculateParallels() ⇒ energyParallel 恒为 ENERGY_CAP_NONE」）；
     *       用户本轮明确表示<b>不管这个推断对不对都要豁免</b> ⇒ 本方法不依赖任何推断。</li>
     *   <li><b>而且它对别的机器零影响</b>：豁免判据（{@code isGenerator()}）在 24 台里只有 1 台为真，
     *       且那台走的是与另外 23 台完全不同的代码路径。</li>
     * </ol>
     *
     * <h2>判据（可离线断言，见 handoff/outbound/自动并行-判据5）</h2>
     * <pre>
     *   那台机器：powerAuto = true、电上限被写成 48（电网仓那一档）、T = 121
     *     ⇒ 接口层（getEffectiveParallel）= 2147483647（不受钳制）
     *     ⇒ 原生链（× T=121）        = 259845521287    ← 期望
     *   负面对照（把豁免关掉，同一批输入）：接口层 = 1 ⇒ 原生链 = 121
     * </pre>
     *
     * <p>⚠️ 本方法<b>不读机器实例上的可变状态</b>（实现是读 machine definition 上那个
     * 常量标志）⇒ 构造期被调用也安全，且客户端与服务端【恒同值】
     * （这是面板能如实显示「∞」而不是伪造一个数的前提）。
     */
    default boolean isEnergyCapExempt() {
        return false;
    }

    /**
     * 🔴 <b>{@link #getEffectiveParallel()} 的纯函数形态（判据与生产走同一条代码路径）。</b>
     *
     * <p>抽成 {@code static} 的唯一目的是让<b>离线判据能直接断言它</b>：
     * 本接口与 {@link ParallelPowerBudget}、{@code ShanhaiParallelBudget} 都零 Minecraft 依赖
     * ⇒ 判据脚本可以把它们单独 {@code javac} 出来，逐档断言
     * 「电力自动开着时，框里填 1 与填 99999 <b>结果逐位相同</b>」这种只有实机才看得见的性质。
     *
     * <h2>🔴 2026-10-02 第五轮：本函数多了第 5 个形参 {@code threads}（用户裁决 ①）</h2>
     * 电上限那一项从"直接用"改成"{@code ÷ T}"，落点就在本函数最后那一步
     * （它同时也是 {@link #applyEnergyCap(long)} 的算术）—— 见下面那个 <b>5 参重载</b>。
     *
     * @param autoRaw        机器自己算出来的并行（未做 {@code max(1,·)} 的原始值）
     * @param overrideValue  框里存着的覆盖值（{@code 0} = 自动）
     * @param powerAuto      「电力自动」开关
     * @param energyCap      本轮算出来的电力上限（{@code &lt;= 0} = 没有上限）
     * @deprecated 这是 <b>T = 1</b> 的特例（= 改动前的行为），生产代码请用 5 参版。
     */
    @Deprecated
    static long effectiveParallel(long autoRaw, long overrideValue, boolean powerAuto, long energyCap) {
        return effectiveParallel(autoRaw, overrideValue, powerAuto, energyCap, 1);
    }

    /**
     * 🔴 <b>{@link #getEffectiveParallel()} 的现行形态 —— 5 参、带跨配方线程数 T。</b>
     *
     * <pre>
     *   auto    = max(1, autoRaw)
     *   覆盖值  = powerAuto ? PARALLEL_AUTO : overrideValue     ← 裁决①：电力自动开着时框里的值不参与
     *   base    = 覆盖值 > 0 ? min(覆盖值, auto) : auto
     *   生效    = 电力自动关着 / 电上限 ≤ 0  ⇒ base            （老行为，逐位不变）
     *             否则                     ⇒ max(1, min(base, floor(电上限 ÷ T)))
     *   ────────────────────────────────────────────────────────────────────────
     *   ⚠️ 本值是【每线程】口径：它同时喂给 getMaxParallel()（int 桥）与 Jade/面板显示。
     *      引擎真正吃的【总预算】= 本值 × T（父类自己乘，见 ShanhaiParallelBudget#parallelBudget）。
     * </pre>
     *
     * @param threads 跨配方线程数 T；{@code ≤ 0} 按 1 算（分母恒 ≥ 1 ⇒ 除零在结构上不可能）
     */
    static long effectiveParallel(long autoRaw, long overrideValue, boolean powerAuto, long energyCap,
                                  int threads) {
        final long auto = Math.max(1L, autoRaw);
        // 🔴 裁决① 的落点：电力自动开着 ⇒ 覆盖值【完全不参与本算式】（不是"读了再忽略"）。
        final long override = powerAuto ? PARALLEL_AUTO : overrideValue;
        final long base = (override > PARALLEL_AUTO) ? Math.min(override, auto) : auto;
        if (!powerAuto || energyCap <= ENERGY_CAP_NONE) {
            return base;
        }
        // 🔴 裁决①（第五轮）的落点：÷T 的唯一实现在这里（= 全工程唯一一份算术），
        //    见 ShanhaiParallelBudget#perThreadParallelFor 的 javadoc。
        return ShanhaiParallelBudget.perThreadParallelFor(base, energyCap, threads);
    }

    // ═══════════════════ 🔴 电力自动（2026-10-02 新增 · 用户定方案） ═══════════════════

    /**
     * <b>「电力上限」的"没有"哨兵</b>（{@code 0}）—— 与 {@link #PARALLEL_AUTO} 同值但<b>语义无关</b>，
     * 单独起个名字是为了让读代码的人一眼分清"玩家没覆盖"和"电力没给出上限"。
     */
    long ENERGY_CAP_NONE = 0L;

    /**
     * 🔴 <b>「电力自动」开关：开了 ⇒ 电力上限参与钳位；关了 ⇒ 完全走老行为。</b>
     *
     * <h2>用户原话（逐字，这就是规格）</h2>
     * <blockquote>「好的，我的方案就是通过计算能源仓可以提供的总功率来确定并行数，
     * 注意机器是可以放2个能源仓的，还可以放2个不同的能源仓的，所以你需要仔细计算，
     * 我们通过总功率和此配方的功率来计算并行数，
     * （若是无线电网输入终端，或者创造能源仓则直接把并行拉到最大，这个就不需要我们算了）」</blockquote>
     *
     * <h2>🔴 它<b>不是</b>「输入 0 = 自动」那个旧语义（两者正交）</h2>
     * <pre>
     *   并行覆盖值 == 0      ⇒ 跟随机器自己算的自动值（物质模块并行表 / 主机 MAX）   ← 老语义，一个字没动
     *   并行覆盖值 &gt; 0      ⇒ 玩家手填的上限
     *   本开关   == true    ⇒ 在上面那条之上<b>再取一次</b> min(…, 电力上限)
     * </pre>
     *
     * <h2>⛔ 2026-10-02 第二轮改判：旧的「三态优先级：电力自动 &gt; 手动覆盖 &gt; 0 = 跟随机器」<u>已作废</u></h2>
     * 旧口径的副作用（用户 2026-10-02 第二轮点名）：开着电力自动时，灰着的输入框里那个数
     * <b>仍然作为额外一档上限参与运算</b> ⇒ 「看得见却改不动，还偷偷起作用」。
     * <p>🔴 <b>现行口径（用户裁决 A：不参与）</b>：
     * <pre>
     *   本开关 == true   ⇒ 输入框里的值【完全不参与】—— 并行 = min(机器自动值, 电力上限)
     *   本开关 == false  ⇒ 输入框里的值【照旧生效】（老行为，逐值不变）
     * </pre>
     * 判定落在 {@link #getEffectiveOverride()}（唯一一处），算式落在
     * {@link #effectiveParallel(long, long, boolean, long)}（唯一一处）。
     * <p>开关关着时行为与改动前<b>逐值相同</b>（老存档不会静默改行为 —— 用户点名的一条纪律）。
     *
     * <h2>⛔ 2026-10-02 第三轮改判：旧的「电力上限按跨配方线程数【乘】」<u>已作废</u></h2>
     * <pre>
     * ⛔ 旧原文（作废，逐字留档）：
     *     本开关关着 ⇒ 下面那条乘法不存在；开着 ⇒ 并行预算里"电上限"这一项与"本机上限"一样
     *     【乘上跨配方线程数】（理由：跨配方线程 = 同时跑多份不同配方 ⇒ 总耗电是各份之和）。
     * </pre>
     * 🔴 <b>现行口径（用户 2026-10-02 第三轮裁决：换成【总量】口径）</b>：
     * <pre>
     *   总耗电 = k × 并行 × T（每个线程都在跑、各自带着并行）
     *   要不缺电 ⇒ k × 并行 × T ≤ P ⇒ 并行 ≤ P ÷ (k × T) = 电上限 ÷ T
     *   ⇒ 并行 = min(本机上限, 电上限 ÷ T)        ← "除"，不是"乘"
     * </pre>
     * 旧口径是"每个线程各按 P 算"⇒ 两个线程就吃 2P ⇒ 必然超（这正是用户报的"电力输入不足"）。
     * 该算术的唯一实现是 {@code ShanhaiParallelBudget#perThreadParallelFor(long, long, int)}，
     * 接口层（{@link #applyEnergyCap(long)}）与预算层（{@code ShanhaiParallelBudget#parallelBudget}）同用。
     *
     * <h2>🔴 2026-10-02 第五轮补充：那个 ÷T 落在<b>哪一层</b>（用户裁决 ①）</h2>
     * <pre>
     *   每线程并行 = min(本机上限, floor(电上限 ÷ T))          ← 🔴 落在【接口层】= 本接口的 applyEnergyCap()
     *                                                            它的出口就是 ParallelMachine#getMaxParallel()
     *   引擎总预算 = 每线程并行 × T                            ← 父类 MutableRecipesLogic.calculateParallels() 自己乘
     *   ⇒ 总预算 ≤ 电上限 = P ÷ k ⇒ 总耗电 = k × 总预算 ≤ P    ← 【对任意 T 成立】（除"地板"档）
     * </pre>
     * 上一轮只落在"预算层"，而那个值 ≤ 2³¹ 时引擎会退回父类、父类拿 {@code getMaxParallel()} 重算
     * ⇒ <b>改动在主要路径上不生效</b>。本轮把 ÷T 挪到接口层 ⇒ <b>"退回父类也没关系"</b>
     * （父类读的就是这一层），见 {@link #getEnergyCapThreads()} 与
     * {@code ShanhaiParallelBudget#perThreadParallelFor} 的 javadoc。
     */
    boolean isPowerAutoParallel();

    /**
     * 写开关。实现必须：值没变就直接返回；关掉时把 {@link #getEnergyParallel()} 清回
     * {@link #ENERGY_CAP_NONE}（否则面板会留着一个过期数字 —— 本工程红线：活的界面上不许放假数据）。
     */
    void setPowerAutoParallel(boolean value);

    /**
     * <b>本轮算出来的电力上限</b>（{@code ≤ 0} = 没有上限 / 尚未算过）。
     *
     * <p>写者<b>只有</b>配方逻辑（每轮配方开始时算一次，见
     * {@code PrimordialModuleRecipeLogic#shanhai$powerParallelCap()} /
     * {@code PrimordialEngineRecipeLogic#shanhai$powerParallelCap()}）——
     * <b>中途绝不改</b>，这样结构上不可能打断正在跑的配方（用户点名的一条）。
     */
    long getEnergyParallel();

    /** 写电力上限（{@code ≤ 0} 归到 {@link #ENERGY_CAP_NONE}）；值没变必须直接返回，不刷包。 */
    void setEnergyParallel(long value);

    /**
     * 🔴🔴 <b>本轮的「每并行耗电 k」—— 面板那一行读的就是它（2026-10-02 第八轮新增 · 用户裁决 ②）。</b>
     *
     * <h2>为什么必须让玩家看得见它（用户原话，逐字）</h2>
     * <blockquote>「🔴 理由：<b>用户能看到 {@code 688.13K}，却看不到 {@code 2.1}</b> ⇒
     * 所以这次只能靠反推」</blockquote>
     * 上一轮的诊断是<b>反推</b>出来的（{@code 688128 ÷ 327680 = 2.1}），而面板当时只印了
     * 「电上限 327680」—— 那个数<b>只告诉你结果、不告诉你 k</b>，所以"k 被取整成 2"这件事
     * 在界面上完全不可见。⇒ 现在把 k 直接印出来：<b>以后一眼就能看出对不对</b>。
     *
     * <h2>口径</h2>
     * <b>毫 EU/t</b>（{@code k × 1000}，见 {@code ParallelPowerBudget#perParallelMilliCost}）。
     * {@code ≤ 0} = 本轮没算出（没候选配方 / 没能源仓 / 开关关着 / 抛异常 —— 具体哪一档由
     * {@link #getEnergyCapState()} 说）。
     * <p>⚠️ <b>与 {@code energyParallel} 不同步写入就是假数据</b> ⇒ 它只能经
     * {@link #setEnergyCap(EnergyCapState, long, long)} 落库（同一个写入口，见那里的注释）。
     */
    long getPerParallelMilliCost();

    /**
     * 🔴 <b>唯一一处"把电力上限施加到并行上"的算式</b>（两侧共用，防漂移）。
     *
     * <pre>
     *   开关关着   ⇒ 原样返回 base（老行为，逐值不变）
     *   上限 ≤ 0   ⇒ 原样返回 base（没算出 / 没能源仓 ⇒ 不做电力限制）
     *   🔴 电上限【视为 ∞】（{@link #isEnergyCapInfinite()}）⇒ 原样返回 base
     *      ＝ 本机豁免（那台发电机，2026-10-02 第六轮）
     *      ＋ 能源仓那头无限（创造模式能源仓 / 无线电网输入终端，2026-10-02 第十一轮）
     *   否则       ⇒ max(1, min(base, floor(上限 ÷ T)))    ← 🔴 2026-10-02 第五轮加的那个 ÷T
     *               （下限 1 = 既有的"至少试 1 份"行为；T = {@link #getEnergyCapThreads()}）
     * </pre>
     * <p>⚠️ 豁免那一条<b>不看开关</b>（放在最前面）：它是"这台机器永远不会被电力钳住"的
     * 结构性保证，不该随玩家开关漂移。
     *
     * <h2>⚠️ 这是<b>每线程</b>口径；引擎真正吃的<b>总预算</b> = 本值 × T</h2>
     * 本方法回答的是「<b>这一台机器</b>的并行上限被电力压到多少（<b>每个跨配方线程</b>）」。
     * 受影响的读者：
     * <pre>
     *   · {@code getMaxParallel()} 那道 int 桥  → 父类 MutableRecipesLogic.calculateParallels()
     *                                            里 `(long) getMaxParallel() * getMultipleThreads()`
     *                                            ⇒ 本值 × T = 引擎的【总预算】
     *   · 面板第 2 行读数、GUI「并行上限」行、物品 tooltip、Jade 的 parallel 键
     *   · {@code PrimordialModuleMachine#getCurrentParallel()} → 原生链的实参（那一处故意再 ×T，见 ModuleRegistry）
     * </pre>
     *
     * <h2>⛔ 2026-10-02 第五轮订正：上一轮那句「本方法这一层还没有线程数可读」<u>已作废</u></h2>
     * <pre>
     * ⛔ 旧原文（作废，逐字留档）：
     *   「而<b>本方法这一层还没有线程数可读</b>（本接口拿不到 T；主机的 T 在配方逻辑类里）⇒
     *     「电上限 ÷ T」真正生效的落点仍是 {@code ShanhaiParallelBudget#parallelBudget}，
     *     以及 {@code getMaxParallel()} 那条 int 桥（该桥的入参来自本方法）——
     *     后者的现状与风险见交付报告 §11.2。」
     * </pre>
     * 作废原因（用户 2026-10-02 第五轮裁决 ①「改，让它真生效」）：T 现在由
     * {@link #getEnergyCapThreads()}（<b>新增的接口接缝，默认 1 = 回落旧行为</b>）提供，
     * 两台的实现各自接到自己<b>唯一那份</b>线程来源上 ⇒ <b>÷T 真的落在这一层</b>，
     * 于是"引擎退回父类"这件事不再让改动失效（父类读的就是本方法的出口）。
     *
     * @param base 原有口径下的并行（"覆盖 / 自动"已经算完的值）
     */
    default long applyEnergyCap(long base) {
        return applyEnergyCap(base, getEnergyCapThreads());
    }

    /**
     * {@link #applyEnergyCap(long)} 的<b>带 T 形态</b>（纯函数与生产同源；判据直接断言它）。
     *
     * @param threads 跨配方线程数 T；{@code ≤ 0} 按 1 算 ⇒ T = 1 时与改动前<b>逐位相同</b>
     */
    default long applyEnergyCap(long base, int threads) {
        // 🔴🔴 2026-10-02 第六轮（用户裁决 ①「给它单独豁免」）：电上限【视为 ∞】。
        //    这是全工程【唯一】一处"把电上限施加到并行上"的地方（getEffectiveParallel()
        //    现在也调本方法）⇒ 豁免写在这里 = 没有第二条能绕过去的路。
        //    为什么是"视为 ∞"而不是"不写上限"、为什么豁免的是那台发电机：见
        //    isEnergyCapExempt() 的 javadoc（★ 那段就是任务书要的"写在代码里的理由"）。
        // 🔴🔴 2026-10-02 第十一轮（用户实机 bug）：本行从 isEnergyCapExempt() 放宽成
        //    isEnergyCapInfinite() —— 把「能源仓那头是无限」与「本机豁免」两种 ∞ 收在同一个出口：
        //    创造模式能源仓 / 无线电网输入终端 ⇒ 电上限【就是 ∞】⇒ 用户规格「直接把并行拉到最大」
        //    必须【与"电力自动关着"逐位同值】= 本机上限，<b>不许再被 ÷T</b>。
        //    理由与本轮实测数字（2048 ÷ 9 = 227 vs 2048）逐条写在 isEnergyCapInfinite() 的 javadoc 里。
        if (isEnergyCapInfinite()) {
            return base;
        }
        if (!isPowerAutoParallel()) {
            return base;
        }
        final long cap = getEnergyParallel();
        if (cap <= ENERGY_CAP_NONE) {
            return base;
        }
        return ShanhaiParallelBudget.perThreadParallelFor(base, cap, threads);
    }

    /**
     * 🔴🔴 <b>「电上限视为 ∞」的【唯一判据】—— 本机豁免（那台发电机）<u>或</u>能源仓那头是无限
     * （创造模式能源仓 / 无线电网输入终端）。</b>
     *
     * <h2>用户原话（逐字，这就是规格）</h2>
     * <blockquote>「好的，我的方案就是通过计算能源仓可以提供的总功率来确定并行数，
     * 注意机器是可以放2个能源仓的，还可以放2个不同的能源仓的，所以你需要仔细计算，
     * 我们通过总功率和此配方的功率来计算并行数，
     * （<b>若是无线电网输入终端，或者创造能源仓则直接把并行拉到最大，这个就不需要我们算了</b>）」</blockquote>
     *
     * <h2>🔴 为什么必须新增这一条（2026-10-02 第十一轮 · 用户实机 bug 的根因）</h2>
     * <pre>
     *   用户原话（逐字）：「我给模块用创造能源仓，然后开启自动并行，它居然给我降并行了，
     *                      数值是 <b>2048/9 向下取整</b>」
     *   实测（进游戏截图，两档只差"电力自动"开不开）：
     *     创造能源仓 + 电力自动开 ⇒ 并行 <b>227</b>（面板写着「电上限 ∞」）
     *     同一台机（手动 / 一键最大）⇒ 并行 <b>2048</b>
     *   ⛔ 改前：{@code parallelFromPowerMilli} 的「∞ ⇒ 取原本上限」那一支只抬了【电上限】，
     *      而 {@link #applyEnergyCap(long, int)} 的 {@code ÷ T} 照旧执行 ⇒ 2048 ÷ 9 = 227
     *      —— <b>面板自己都在自相矛盾</b>（写着「生效 227（电上限 ∞）」）。
     *   ✅ 改后：∞ 这一支在第一句就返回 base ⇒ 接口层 = 本机上限 ⇒ 与"电力自动关着"逐位同值。
     * </pre>
     *
     * <h2>🔴 为什么"∞"必须等于"电上限 ≤ ENERGY_CAP_NONE"（而不是另造一个语义）</h2>
     * 本工程里<b>「不钳」的既有表示就是电上限 ≤ {@link #ENERGY_CAP_NONE}</b>（没有上限 / 没算出 /
     * 没能源仓 —— 见 {@link #applyEnergyCap(long, int)} 的第一条短路）⇒
     * <b>「电上限 = ∞」与「不参与钳制」是同一件事的两种说法</b>，不是新增语义。
     * 于是「按规格拉满」这句话有了一个可离线断言的形式：
     * <pre>
     *   创造仓 / 无线电网终端 ⇒ 生效并行 == {@link #getAutoParallel()}（本机上限）
     *                        ⇒ 与「电力自动关着 / 一键最大」<b>逐位同值</b>
     *                        ⇒ 引擎总预算 == 本机上限 × T（{@link #energyCapForBudget()} 为 0）
     * </pre>
     *
     * <h2>🔴 判定用 {@link #getEnergyCapState()} 而不是"电上限那个数"</h2>
     * <pre>
     *   状态与数值是【同一次写入】的（{@link #setEnergyCap(EnergyCapState, long, long)} 三样一起写）
     *   ⇒ 不存在"状态说 ∞、数值是别的"这种漂移窗口；
     *   而「能源仓那头是不是无限」这件事<b>只有状态能表达</b> ——
     *   写进 {@code energyParallel} 的数是<b>本机上限</b>（{@code parallelFromPowerMilli} 的出口），
     *   它本身长得跟一个"有限电上限"一模一样，光看数值分不出来。
     * </pre>
     * <p>⚠️ {@link EnergyCapState#UNLIMITED_BY_EXEMPTION} <b>不</b>走这一条：它由
     * {@link #isEnergyCapExempt()} 覆盖（同一出口、不同原因，面板文案也不同 —— 见那两个枚举的注释）。
     */
    default boolean isEnergyCapInfinite() {
        return isEnergyCapExempt()
                || getEnergyCapState() == EnergyCapState.UNLIMITED;
    }

    /**
     * 🔴🔴 <b>「并行预算」那一层要用的电上限 —— ∞ 时返回 {@link #ENERGY_CAP_NONE}。</b>
     *
     * <pre>
     *   ∞（创造仓 / 无线电网终端 / 本机豁免）⇒ {@link #ENERGY_CAP_NONE}
     *        ⇒ {@code ShanhaiParallelBudget#parallelBudget} 见 ≤ 0 就退回「本机上限 × T」
     *        ⇒ 与「电力自动关着」逐位同值（老行为）
     *   有限                                  ⇒ 原样返回 {@link #getEnergyParallel()}
     * </pre>
     *
     * <h2>🔴 为什么预算层也必须这样（只改接口层不够）</h2>
     * 接口层管的是 {@code getMaxParallel()}（面板"生效"那一行、int 桥、父类的 {@code ×T} 因子），
     * 而引擎另一条路 {@code ShanhaiParallelBudget#parallelBudget(本机上限, 电上限, T)} 直接吃
     * <b>电上限那个数</b> ⇒ 只改接口层时会出现「显示 2048、引擎只拿到 2043」这种
     * <b>显示与生效不一致</b>（2043 = min(2048, 2048÷9) × 9 —— 正是本轮实测里那台机的真实读数）。
     * <p>⇒ 两个调用点（模块 / 主机）必须与接口层读<b>同一个判据</b>，所以这里只此一份实现，
     * 调用点一律调用本方法，<b>不许各写一遍三目表达式</b>（本工程反复记载过"两份实现迟早漂移"）。
     */
    default long energyCapForBudget() {
        return isEnergyCapInfinite() ? ENERGY_CAP_NONE : getEnergyParallel();
    }

    // ═════════ 🔴 2026-10-02 第二轮：「为什么没有上限」必须在界面上说出来 ═════════

    /**
     * 🔴 <b>「电力上限」这一次到底算没算出来、<u>为什么</u>」—— 本枚枚举就是那个"为什么"。</b>
     *
     * <h2>为什么必须加它（用户 2026-10-02 第二轮实机报：装了 64 个物质模块后显示「尚未算出」）</h2>
     * 上一轮的面板只有一句
     * <pre>
     *   final String capText = cap &gt; ENERGY_CAP_NONE ? formatParallel(cap) : "§8尚未算出";
     * </pre>
     * 而 {@link #ENERGY_CAP_NONE}（{@code 0}）这个值<b>同时</b>代表了四件完全不同的事：
     * <pre>
     *   ① 开关关着          ⇒ 根本不按电算；
     *   ② 开关刚打开、还没跑过一轮配方逻辑 ⇒ 还没有数；
     *   ③ 这一轮<b>没有可跑的候选配方</b>  ⇒ 没有"每并行耗电"可用；
     *   ④ <b>没有能源仓 / 全部认不出</b>   ⇒ 总功率 0；
     *   ⑤ <b>抛异常被 catch 吞掉</b>      ⇒ 本轮不限制。
     * </pre>
     * ⇒ 面板把四件事一起印成「尚未算出」，玩家（以及隔着屏幕排错的人）<b>无法区分"机器坏了"和
     * "此刻确实没东西可算"</b>。这正是本工程红线「活的界面上不许放假数据」的邻居：<b>不是假数据，
     * 但是一句没有信息量的话</b>。
     *
     * <h2>🔴 一条必须先说死的结论（可离线取证，见交付报告）</h2>
     * <b>算式本身不可能产出 {@link #ENERGY_CAP_NONE}。</b>
     * <pre>
     *   perParallelMilliCost(...) 恒 ≥ 1000 毫（= 1 EU/t）  （raw ≤ 1 ⇒ 1 EU/t）
     *   parallelFromPowerMilli(...) 恒 ≥ 1                   （三条兜底都返回 max(1, ceiling)）
     * </pre>
     * ⇒ 「尚未算出」<b>从来不是"算出来是 0"，而是"压根没走到算式"或"走到了但被提前返回"</b>。
     * 用户与队长此前的猜测（「专属槽减免 {@code 1 − 0.95^(17/等级)} 算出 0」）在数值上<b>不成立</b>：
     * 该函数的合法域是 {@code [0.05, 1.0]}（{@code level ∈ [0,17]}），恒 {@code > 0}。
     */
    enum EnergyCapState {

        /** 开关关着 ⇒ 完全不按电算。这条<b>不会</b>出现在电力自动那一支的读数里。 */
        OFF,

        /** 开关刚打开 / 刚被清过 ⇒ 本机还没跑过一轮配方逻辑，所以还没有数。 */
        NOT_EVALUATED,

        /** 算过：这一轮<b>没有可跑的候选配方</b> ⇒ 拿不到"每并行耗电" ⇒ 不做电力限制。 */
        NO_CANDIDATE,

        /** 算过：<b>没有能源仓 / 一个都认不出来</b> ⇒ 总功率 0 ⇒ 不做电力限制。 */
        NO_HATCH,

        /** 算过：机上有<b>无线电网输入终端 / 创造能源仓</b> ⇒ 用户规格「直接把并行拉到最大」。 */
        UNLIMITED,

        /** 算过：真的算出了一个电力上限（<b>唯一</b>会显示数字的一档）。 */
        COMPUTED,

        /** 算过：<b>抛异常了</b> ⇒ 本轮不做电力限制；日志里有<b>完整栈</b>（上一轮只打 toString）。 */
        ERROR,

        /**
         * 🔴 <b>2026-10-02 第六轮新增：本机被【单独豁免】⇒ 电上限视为 ∞，根本不存在"钳不钳"这件事。</b>
         *
         * <p>它与 {@link #UNLIMITED} <b>不是同一件事、也不许合并</b>：
         * <pre>
         *   UNLIMITED             ：能源仓那头是无限（无线电网终端 / 创造能源仓）⇒ 按用户规格拉满；
         *                           <b>仍然是一台"吃电"的机器</b>，只是电够。
         *   UNLIMITED_BY_EXEMPTION：<b>这台机器本身</b>不受电力钳制（那台发电机；EUt 是输出语义，
         *                           量纲上就不该按"输入功率"算）—— 写不写电上限都不影响它。
         * </pre>
         * 分成两档的理由是本工程红线「活的界面上不许放假数据」的邻居：两档的<b>数值显示都是 ∞</b>，
         * 但<b>为什么是 ∞</b> 完全不同 —— 合成一格就会让玩家（以及隔着屏幕排错的人）以为
         * "这台发电机插了无线电网终端"。
         *
         * <p>⚠️ 写入者：{@code PrimordialModuleMachine#getEnergyCapState()}（判据 = 同类的
         * {@code isEnergyCapExempt()}）。本档<b>不会</b>由 {@link #classify(boolean, long)} 产出 ——
         * 那个函数判的是"电是怎么算出来的"，与本档无关。
         *
         * <p>⛔ 它<b>必须留在枚举末尾</b>：{@code energyCapState} 是按 {@link #ordinal()} 落库/同步的，
         * 往中间插会静默改掉既有存档的取值。
         */
        UNLIMITED_BY_EXEMPTION
    }

    /** 本轮算出来的电力上限<b>为什么</b>是这个值。读者 = 面板 + 日志 + 离线判据。 */
    EnergyCapState getEnergyCapState();

    /**
     * 🔴 <b>由「这一轮有没有候选配方 + 能源仓总功率」判出状态 —— 全工程唯一一处，纯函数。</b>
     *
     * <p>为什么必须抽成纯函数（而不是在主机侧与模块侧各写一段 {@code if}）：
     * <ol>
     *   <li><b>两侧不许漂移</b>：本工程已多次记载"两份实现迟早分叉"，而分叉的表现是
     *       "主机说没能源仓、模块说已算出"这种只有实机才看得见的静默不一致；</li>
     *   <li><b>它必须能被离线断言</b>：这个类与 {@link ParallelPowerBudget} 都<b>零 Minecraft 依赖</b>
     *       ⇒ 判据脚本可以把它们单独 {@code javac} 出来，逐档断言"这三条分支与
     *       {@link ParallelPowerBudget#parallelFromPowerMilli} 内部那三条兜底一一对应"。
     *       写在配方逻辑里就永远测不到（那两侧都要 Minecraft 才编得过）。</li>
     * </ol>
     *
     * <p>⚠️ <b>它<u>不</u>覆盖 {@link EnergyCapState#ERROR} 与 {@link EnergyCapState#NOT_EVALUATED}</b>：
     * 前者只能由 {@code catch (Throwable)} 决定、后者是字段初值，都不是"算出来的结论"。
     *
     * @param hasCandidates     本轮 {@code lookupRecipeSet()} 是否非空
     * @param totalPowerPerTick 能源仓稳态总功率（{@code ParallelPowerBudget.UNLIMITED} = 特例）
     */
    static EnergyCapState classify(boolean hasCandidates, long totalPowerPerTick) {
        if (!hasCandidates) {
            return EnergyCapState.NO_CANDIDATE;
        }
        if (ParallelPowerBudget.isUnlimitedPower(totalPowerPerTick)) {
            return EnergyCapState.UNLIMITED;
        }
        if (totalPowerPerTick <= 0L) {
            return EnergyCapState.NO_HATCH;
        }
        return EnergyCapState.COMPUTED;
    }

    /**
     * 🔴 <b>状态、电力上限与「每并行耗电」的<u>唯一</u>写入口（三样必须一起写，不许分几次写）。</b>
     *
     * <p>分成多个 setter 的后果是出现"状态说有上限、数值是 0"这种<b>自相矛盾的落库</b>
     * —— 面板会显示「电上限 0」，比不说还糟。所以本方法一次写三样。
     *
     * <p>🔴 <b>2026-10-02 第八轮：加了第 3 个形参 {@code perParallelMilliCost}</b>
     * （面板新增「每并行耗电」那一行的数据源，用户裁决 ②）。加在同一个写入口而不是另起一个
     * setter，理由就是上面那条：k 与电上限<b>出自同一次计算</b>，分两次写必然出现
     * "电上限是新的、k 还是上一轮的"这种只有实机看得见的静默不一致。
     *
     * @param state                 见 {@link EnergyCapState}
     * @param value                 算出来的上限；{@code ≤ 0} 归到 {@link #ENERGY_CAP_NONE}（= 不限制）
     * @param perParallelMilliCost  本轮的每并行耗电（<b>毫</b> EU/t）；{@code ≤ 0} 归到
     *                              {@link #ENERGY_CAP_NONE}（= 本轮没算出）
     */
    void setEnergyCap(EnergyCapState state, long value, long perParallelMilliCost);

    /**
     * 🔴 <b>面板「每并行耗电」那一行的文案（纯函数 ⇒ 界面与离线判据<b>同一份</b>，不可能漂移）。</b>
     *
     * <h2>为什么与 {@link #energyCapReasonText} 分开</h2>
     * 那一行已经用整行解释了「电上限为什么是这个数 / 为什么没有」；
     * 本行只补<b>那个一直看不见的乘数</b>，所以刻意<b>短</b>（面板总宽只有 150px），
     * 且不复述原因（复用上一行的那句话，避免两处文案各自漂移）。
     *
     * <p>⚠️ 每一档都<b>不许编数字</b>：没算出就印 {@code —}，并在括号里<b>指向上一行</b>，
     * 绝不印一个"看起来像 k"的 0（本工程红线：宁可缺，不可假）。
     *
     * @param state                {@link #getEnergyCapState()}
     * @param perParallelMilliCost {@link #getPerParallelMilliCost()}（毫 EU/t）
     */
    static String perParallelCostText(EnergyCapState state, long perParallelMilliCost) {
        if (state == null) {
            return "§7每并行耗电 §8— §8（状态未知，见日志）";
        }
        return switch (state) {
            case COMPUTED -> perParallelMilliCost > ENERGY_CAP_NONE
                    ? "§7每并行耗电 §a" + ParallelPowerBudget.formatMilli(perParallelMilliCost) + " §8EU/t"
                    : "§c每并行耗电 数值非法 §8（内部错误，见日志）";
            case UNLIMITED -> "§7每并行耗电 §8不适用 §8（电无限 ⇒ 按规格拉满）";
            case UNLIMITED_BY_EXEMPTION -> "§7每并行耗电 §8不适用 §8（本机豁免：不受电力钳制）";
            case NO_HATCH -> "§7每并行耗电 §8— §8（没有能源仓）";
            case NO_CANDIDATE -> "§7每并行耗电 §8— §8（本轮没有可跑的配方）";
            case NOT_EVALUATED -> "§7每并行耗电 §8— §8（本机还没跑过一轮配方逻辑）";
            case ERROR -> "§c每并行耗电算的时候抛异常了 §8（完整栈见日志）";
            case OFF -> "§8每并行耗电 —（电力自动关着 ⇒ 不按电力算）";
        };
    }

    /**
     * 面板括号里的那个<b>短值</b>（纯函数 ⇒ 界面与离线判据<b>同一份</b>，不可能漂移）。
     *
     * <p>⚠️ 它<b>只说值、不说原因</b>（面板宽度只有 150px）；原因由
     * {@link #energyCapReasonText(EnergyCapState, long)} 单独占一行说。
     */
    static String energyCapShortText(EnergyCapState state, long cap) {
        if (state == null) {
            return "§8未算";
        }
        return switch (state) {
            // ⚠️ COMPUTED 仍要再判一次 cap：生产路径上它恒 ≥ 1（setEnergyCap 会钳），
            //    但本函数是【纯函数、任何输入都要给出人话】⇒ cap 不合法时绝不许印出一个 "-1" 这种
            //    看起来像数字的假数据（本工程红线）。离线判据会把 cap = -1/0 扫一遍。
            case COMPUTED -> cap > ENERGY_CAP_NONE ? "§a" + cap : "§c数值非法";
            case UNLIMITED -> "§a∞";
            // 🔴 第六轮：豁免同样显示 ∞（数值恒不会被印出来 ⇒ 不可能显示一个"其实没生效"的数）。
            case UNLIMITED_BY_EXEMPTION -> "§a∞";
            case NO_HATCH, NO_CANDIDATE -> "§8无";
            case NOT_EVALUATED -> "§8待算";
            case ERROR -> "§c异常";
            case OFF -> "§8—";
        };
    }

    /**
     * 🔴 <b>面板那一行的"原因句" —— 用户点名要的那一句。</b>
     *
     * <p>用户 2026-10-02 第二轮的要求（逐字）：显示「尚未算出」时<b>要说清为什么</b>。
     * 本方法就是那个"为什么"，而且它<b>是一条纯函数</b>：
     * 面板调它、离线判据也调它 ⇒ **判据里断言"面板不会再说尚未算出"就是对面板本身的断言**
     * （本工程吃过"判据与生产各写一份 ⇒ 永远自洽"的亏，见交付报告 §判据）。
     *
     * <p>⚠️ 每一档都必须<b>自解释</b>：玩家读完这一行要能判断"是机器坏了、还是此刻本来就没东西可算"。
     */
    static String energyCapReasonText(EnergyCapState state, long cap) {
        if (state == null) {
            return "§c电上限状态未知（内部错误，见日志）";
        }
        return switch (state) {
            case COMPUTED -> cap > ENERGY_CAP_NONE
                    ? "§7电上限 §a" + cap + " §8（按本轮候选配方算出来的）"
                    : "§c电上限数值非法 §8（内部错误，见日志）";
            // 🔴 2026-10-02 第十一轮：这一句必须把【真实生效口径】说清 —— 改前那一轮面板上写的是
            //    「生效 227（电上限 ∞）」这种自相矛盾的读数（∞ 却只给了 227）。现在既然按规格拉满，
            //    就把"拉满到什么、与什么同值"一起说出来（本工程红线：活的界面上不许放假数据）。
            case UNLIMITED -> "§7电上限 §a∞ §8（无线电网终端 / 创造能源仓 ⇒ 按规格拉满："
                    + "并行 = 本机上限，与关掉电力自动同值）";
            // 🔴 第六轮：豁免的那一句必须与上一句【不同】—— 说清是"这台机器本身不参与电力钳制"，
            //    不是"它插了无线电网终端"。两句话混用 = 让玩家按错误的方向排查。
            case UNLIMITED_BY_EXEMPTION ->
                    "§7电上限 §a∞ §8（本机豁免：发电模块不受电力钳制 ⇒ 并行按本机口径算）";
            case NO_HATCH -> "§7电上限 §8无 §8（没能源仓 / 认不出仓 ⇒ 不做电力限制）";
            case NO_CANDIDATE -> "§7电上限 §8无 §8（本轮没有可跑的配方 ⇒ 不做电力限制）";
            case NOT_EVALUATED -> "§7电上限 §8还没算 §8（本机还没跑过一轮配方逻辑）";
            case ERROR -> "§c电上限算的时候抛异常了 §8⇒ 本轮不限制（完整栈见日志）";
            case OFF -> "§8开关关着 ⇒ 不按电力算";
        };
    }
}
