package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.integration.jade.provider.ParallelProvider;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.recipe.PrimordialRecipeEffects;
import com.shanhai.common.thread.ShanhaiParallelBudget;
import com.shanhai.machine.creation.PrimordialCreationCompilationMatrix;
import com.shanhai.machine.module.PrimordialModuleMachine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 山海重构 · 让抬头（Jade）显示「并行」与「跨配方线程」两行 —— <b>主机 + 24 台原初模块</b>。
 *
 * <p>← 2026-09-25（任务 A）起新增模块那一半，见本文件末尾的
 * {@link #shanhai$moduleDisplay(CompoundTag, MetaMachineBlockEntity)}。
 *
 * <h2>它做什么（主机那一路）</h2>
 * 在 {@code ParallelProvider.appendServerData(...)} 的 RETURN 处，如果本次查询的方块是<b>本工程的主机</b>
 * 且已成型，就往 Jade 的服务端数据 {@link CompoundTag} 里写
 * {@code parallel = -114514L} 与 {@code threads = -114514L}（两个哨兵）。
 * <b>不改任何机器行为，只改两个用于显示的 NBT 键。</b>
 *
 * <h2>🔴 2026-09-21 二次改判（T3）：为什么现在<b>必须</b>也写 {@code parallel}</h2>
 * <b>旧版本类只写 {@code threads}</b>，理由是当时「并行 = 无限」那个哨兵由
 * {@code PrimordialOmegaEngineMachine.getMaxParallel()} 返回的负数承担
 * （gtladditions 的 {@code ParallelProviderMixin} 会把
 * {@code ((ParallelMachine) machine).getMaxParallel()} 原样写进 {@code parallel} 键）。
 * <b>那个设计已经作废，因为它会静默停机</b>：
 * <pre>
 *   换多配方引擎后，{@code getMaxParallel()} 被真的乘进并行数：
 *       MutableRecipesLogic.calculateParallels():  totalParallel = (long) getMaxParallel() * getMultipleThreads()
 *   ⇒ -114514 × 1073741824 ≈ -1.23e14（负数、无饱和）
 *   ⇒ 贪心分配开头 if (remain &lt;= 0L) break; ⇒ 返回 null ⇒ getRecipe() 恒为 null
 *   ⇒ 机器【不动、不崩、日志无任何输出】——最坏的静默失败形态。
 * </pre>
 * ⇒ 现在 {@code getMaxParallel()} 返回正数（{@code MAX_PARALLEL} = 2^30），
 * 「无限」<b>只由本类写进显示用的 NBT</b>：<b>显示归显示、运算归运算，两者不再共用同一个数。</b>
 * <p>⚠️ 这一条也意味着：<b>若本 mixin 失效（{@code require = 0} 在目标方法缺失时不报错），
 * 症状是"抬头那两行变成很大的数字（1073741824）"而不是机器坏掉</b> —— 数值行为<b>不受影响</b>。
 * 这是刻意的取舍：显示可以退化，机器不可以。
 *
 * <h2>为什么是 -114514L（而不是自己画彩虹）</h2>
 * 这个哨兵是 <b>gtladditions 自己约定的「无限」标记</b>，证据（[源码原文]，gtladditions 3.2.8）：
 * <pre>
 * com.gtladd.gtladditions.mixin.gtceu.integration.ParallelProviderMixin:42
 *     private static final long INFINITY_FEATURE = -114514L;
 * 同文件 appendTooltip(...)：
 *     if (data.contains("parallel")) {
 *         long parallel = data.getLong("parallel");
 *         if (parallel &gt; 0L) …深紫数字…
 *         else if (parallel == -114514L)
 *             tooltip.add(Component.translatable("gtceu.multiblock.parallel",
 *                     CommonUtils.createLanguageRainbowComponent(
 *                             Component.translatable("gtladditions.multiblock.forge_of_the_antichrist.parallel"))));
 *     }
 *     if (data.contains("threads")) {
 *         int threads = data.getInt("threads");
 *         if (threads &gt; 0) …金色数字…
 *         else if (threads == -114514L)
 *             tooltip.add(Component.translatable("gtladditions.multiblock.threads",
 *                     CommonUtils.createLanguageRainbowComponent(
 *                             Component.translatable("gtladditions.multiblock.forge_of_the_antichrist.parallel"))));
 *     }
 * </pre>
 * 而 {@code gtladditions.multiblock.threads} = {@code 拥有%s个跨配方线程}、
 * {@code gtladditions.multiblock.forge_of_the_antichrist.parallel} = {@code 无限}
 * （[源码原文] {@code assets/gtladditions/lang/zh_cn.json}）。
 * <b>⇒ 我们只要写这两个哨兵，gtladditions 自己的渲染就会产出与「伪神（FOTC）」逐字一致的彩虹「无限」两行</b>，
 * 不需要本工程再抄一份渐变文字实现（少一份会漂移的重复实现）。
 * <p>注意 {@code threads} 那一侧上游是按 {@code int} 读的（{@code data.getInt}），
 * 而哨兵按 {@code long} 写；{@code -114514} 在 int 范围内，{@code getInt} 读回来仍是 {@code -114514}
 * ⇒ 判等成立（上游对 {@code ForgeOfTheAntichrist} 也是这么写的）。
 *
 * <h2>与 gtladditions 的 @Overwrite 共存（这是本 mixin 唯一的脆弱点，明写在此）</h2>
 * gtladditions 的 {@code ParallelProviderMixin} 用 {@code @Overwrite} <b>整体替换</b>了
 * {@code appendServerData} 与 {@code appendTooltip}。本类的 {@code @Inject(RETURN)}
 * <b>落在被覆盖后的方法体上</b>并覆盖它写下的值。
 *
 * <h3>⛔ 2026-09-26：上面那句"为什么成立"的<b>旧依据已作废</b>（原文照留）</h3>
 * <pre>
 *   ⛔ 旧原文（作废）：
 *      Mixin 的规则是 priority 数值小的后应用（默认 1000）⇒ 本类写 priority = 500，于是 …
 *      依据：旧私货 {@code ParallelProviderOverrideMixin}（同样的 priority = 500 + @Inject(RETURN)）
 *      在本环境已跑通过。
 * </pre>
 * 🔴 <b>作废原因（2026-09-26 用 Mixin 0.8.5 的字节码逐条核实）</b>：
 * <pre>
 *   · {@code MixinInfo.compareTo}（mixin-0.8.5.jar 字节码 :27-36）是 {@code this.priority - other.priority}
 *     ⇒ <b>升序</b>；{@code MixinProcessor} 把同一目标类的 mixin 收进 {@code TreeSet}
 *     （字节码 :481-498）交给 {@code TargetClassContext}，{@code MixinApplicatorStandard.apply}
 *     按该 {@code SortedSet} 的顺序处理 ⇒ priority <b>小</b>者<b>先</b>处理。
 *   · 真正决定成败的不是 priority，而是 <b>pass 顺序</b>：
 *     {@code MixinApplicatorStandard$ApplicatorPass} 的枚举顺序 = <b>MAIN(0) → PREINJECT(1) → INJECT(2)</b>
 *     （字节码 {@code <clinit>}）⇒ {@code @Overwrite} 在 MAIN pass 落地、{@code @Inject} 在 INJECT pass 落地
 *     ⇒ <b>@Inject 一定作用在 @Overwrite 之后的函数体上，与 priority 谁大谁小无关</b>。
 * </pre>
 * ⇒ <b>结论不变（我们的 @Inject 会覆盖上游写入的值），但依据从"priority 小者后应用"换成"pass 顺序"。</b>
 * {@code priority = 500} 保留无害（它在本场景里不起作用），但<b>不要再把它当作理由</b>。
 * <p>且上游对 {@code MutableRecipesLogic} 那一支的原文是
 * {@code if (mutableRecipesLogic.isMultipleRecipeMode() && mutableRecipesLogic.getMultipleThreads() > 1)
 *      compoundTag.putLong("threads", mutableRecipesLogic.getMultipleThreads());}
 * —— 我们的覆盖正落在它<b>之后</b>。
 *
 * <h2>为什么 {@code require = 0}</h2>
 * 这是一条<b>纯显示</b>的增强：目标方法找不到时（例如 gtladditions 换了实现方式）
 * 只应该「少一行」，<b>绝不允许把用户的客户端崩掉</b>。
 * 为了不让它变成「静默失败」，第一次真正写入时会打一条 INFO 日志
 * （字面量 {@code [SHANHAI-DISPLAY]}），谁都能在 {@code latest.log} 里 grep 到。
 * <p>⚠️ 注意：{@code require = 0} 意味着<b>专服冒烟证明不了这一行会出现</b> —— 它只能在用户进游戏后验证。
 */
@Mixin(value = ParallelProvider.class, priority = 500, remap = false)
public class ShanhaiInfiniteThreadDisplayMixin {

    /**
     * gtladditions 约定的「无限」哨兵。
     *
     * <p>🔴 取值<b>直接引用</b> {@link PrimordialOmegaEngineMachine#DISPLAY_INFINITE_PARALLEL}
     * 而不是在本类里再写一遍字面量 —— 全工程只留<b>一处</b>定义，两处各自硬编码必然漂移。
     * 那个常量已经是 {@code public static final int}（编译期常量），
     * 引用它不会给本 mixin 增加任何运行期类加载负担。
     */
    @Unique
    private static final long SHANHAI$INFINITE = PrimordialOmegaEngineMachine.DISPLAY_INFINITE_PARALLEL;

    /** 只打一次，避免 Jade 每次刷新都刷屏。 */
    @Unique
    private static boolean shanhai$displayAnnounced = false;

    /**
     * @param data     Jade 的服务端数据 tag（会被发送给客户端用于画 tooltip）
     * @param accessor 本次查询的方块访问器
     */
    @Inject(
            method = "appendServerData(Lnet/minecraft/nbt/CompoundTag;Lsnownee/jade/api/BlockAccessor;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0)
    private void shanhai$infiniteThreads(CompoundTag data, BlockAccessor accessor, CallbackInfo ci) {
        try {
            if (data == null || accessor == null) {
                return;
            }
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) {
                return;
            }
            // 🔴 P5：第 30 台「原初造物编译矩阵」走**它自己的三行**，并把 GT 那两行**整段删掉**。
            //    理由与做法见 shanhai$creationMatrixServerData 的注释（不许两套并存）。
            if (mbe.getMetaMachine() instanceof PrimordialCreationCompilationMatrix matrix) {
                shanhai$creationMatrixServerData(data, matrix);
                return;
            }
            // 🔴 2026-09-25（任务 A）：模块那一支**先**走。这是**纯新增的两行** ——
            //    下面的主机分支一字未动；模块在这里写完就 return，不会落到主机分支
            //    （PrimordialModuleMachine 不是 PrimordialOmegaEngineMachine）。
            shanhai$moduleDisplay(data, mbe);
            if (!(mbe.getMetaMachine() instanceof PrimordialOmegaEngineMachine machine)) {
                return;
            }
            // 只对成型的主机显示；未成型时 gtladditions 本来也不会写 parallel/threads。
            if (!machine.isFormed()) {
                return;
            }

            // ⛔⛔ 【2026-09-22 作废，原文留档】哨兵 → 真实数值（用户裁决「显示真实的数值」）
            //   ⛔ 旧原文（作废）：
            //       // 🔴 两个键一起写：并行与线程都显示「无限」。
            //       //    它们【只进 Jade 的 NBT】，不参与任何运算（数值侧见 …#getMaxParallel）。
            //       data.putLong("parallel", SHANHAI$INFINITE);
            //       data.putLong("threads", SHANHAI$INFINITE);
            //   ⛔ 作废原因：**用户 2026-09-22 裁决「让我们的主机的并行、线程都按照伪神的来填写，
            //      然后显示真实的数值」** ⇒ 不再显示彩虹「无限」，改为**真数**
            //      （预期 parallel = 2147483647、threads = 128）。
            //      ⇒ 同时解掉了本工程「**活的界面上不许放假数据**」那条红线
            //      （数值侧已改成 MAX_VALUE / 128，仍显示"无限"就是活的假数据）。
            //   ✅ 上游渲染判据（已读原文）：`ParallelProviderMixin.appendTooltip` 里
            //      `parallel > 0L` → 深紫数字、`threads > 0` → 金色数字
            //      ⇒ **写正数就会画成真数**，不需要本工程另画一份。
            final int parallel = machine.getMaxParallel();
            int threads = 0;
            if (machine.getRecipeLogic() instanceof com.shanhai.common.machine.PrimordialEngineRecipeLogic logic) {
                threads = logic.getMultipleThreads();
            }
            data.putLong("parallel", parallel);
            data.putLong("threads", threads);

            if (!shanhai$displayAnnounced) {
                shanhai$displayAnnounced = true;
                ShanhaiMod.LOGGER.info("[SHANHAI-DISPLAY] 已写入 Jade 真值：parallel={} threads={}"
                        + "（用户 2026-09-22 裁决：显示真实数值；哨兵 {} 已作废，不再写）",
                        parallel, threads, SHANHAI$INFINITE);
            }
        } catch (Throwable ignored) {
            // 纯显示路径：任何异常都不允许影响游戏
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  2026-09-25（任务 A）：把同样两行也画到【24 台原初模块】的抬头上
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 模块专用的抬头上报（<b>只写 Jade 的服务端 NBT，不参与任何运算</b>）。
     *
     * <h2>用户原话（逐字）</h2>
     * 「还有一件事，可以让模块图中这个地方（图1）显示并行和跨配方线程（以后我会增加），<b>就像主机一样</b>」
     *
     * <h2>「就像主机一样」= 走同一条通道</h2>
     * 主机那两行的实现就是本 mixin 的上面那一段：往 GTCEu {@code ParallelProvider} 的
     * {@code parallel} / {@code threads} 两个键里写值，再由 gtladditions 的
     * {@code ParallelProviderMixin#appendTooltip} 画成
     * 「同时处理至多%d个配方」（深紫）与「拥有%s个跨配方线程」（金色）。
     * ⇒ 模块只要写<b>同样两个键</b>，就会得到<b>与主机逐字同款的两行</b>，
     * 不需要本工程再实现一套渲染。
     *
     * <h2>🔴 为什么原来模块那两行是空的（已读上游原文，附行号）</h2>
     * gtladditions {@code ParallelProviderMixin.appendServerData}（
     * {@code originals/analysis/decomp/limitsrc/com/gtladd/gtladditions/mixin/gtceu/integration/ParallelProviderMixin.java}
     * :85-141）对「成型的 {@code WorkableMultiblockMachine}」按类型分支：
     * {@code GTLAddWorkableElectricMultipleRecipesMachine} → 写；
     * {@code MultipleRecipesLogic} → 写；{@code MutableRecipesLogic} → 写；
     * {@code ParallelMachine} → 写；{@code ConversationMachine} / {@code BasicOreProcessorMachine} → 写；
     * <b>其余一律走 :138-141 的兜底分支 —— 只有并行仓（{@code getParallelHatch()}）才写值</b>。
     * 而本工程的模块：不是那几类机器、配方逻辑是普通 {@code RecipeLogic}、
     * <b>而且刻意不实现 {@code ParallelMachine}</b>（理由见
     * {@code PrimordialModuleMachine.LimitedDurationAdapter} 的注释），
     * 也没有并行仓 ⇒ <b>兜底分支什么都不写 ⇒ 抬头上原本一行都没有</b>。
     *
     * <h2>取值（不许编）</h2>
     * <ul>
     *   <li>{@code parallel} = {@link PrimordialModuleMachine#getJadeParallel()}：
     *       <b>每台自己的真实并行槽值</b>（2026-09-25 路线 ① 之后 24 台都有并行槽了）——
     *       空槽 = {@code PrimordialModuleMachine.DEFAULT_PARALLEL} = 64，放入物质模块按该台自己的表提升。
     *       🔴 <b>2026-09-26：不再钳到 {@code Integer.MAX_VALUE}</b>，直接写真 long
     *       （4.6e18 / 6.9e18 / 9.2e18 会原样画出来），只在 ≥ {@code Long.MAX_VALUE / 2} 时
     *       按老山海口径写「无限」哨兵 —— 判定取
     *       {@link PrimordialModuleMachine#isInfiniteParallel(long)}（<b>三处显示共用的唯一一处阈值</b>）。</li>
     *   <li>{@code threads} = {@link PrimordialModuleMachine#getCrossRecipeThreads()}：
     *       <b>恒为 1</b>。用户 2026-09-25 原话「现在模块是没有跨配方线程，后续我会加，
     *       你现在可以写跨配方线程数为 1」⇒ 这是<b>用户指定的显示值</b>，
     *       上游会把它画成「拥有1个跨配方线程」（与主机那句「拥有128个跨配方线程」同款）。</li>
     * </ul>
     *
     * <h2>为什么要在这里 return 而不是让主机分支去判</h2>
     * 两个类互不继承（都只是 {@code WorkableElectricMultiblockMachine} 的子类），
     * 所以写成"先判模块、命中即返回"最不容易碰坏已经跑通的主机路径。
     *
     * <p>⚠️ <b>可验证性边界（诚实声明）</b>：本方法属于 {@code require = 0} 的纯显示增强，
     * 而且只有客户端在看方块时才会被 Jade 触发 ⇒ <b>无头专服冒烟测试证明不了它生效</b>，
     * 只能由用户进游戏看一眼（验收清单见交付报告）。为了不"静默失败"，
     * 第一次真正写入时会打一条 INFO（字面量 {@code [SHANHAI-DISPLAY]}，可 grep）。
     */
    private static void shanhai$moduleDisplay(CompoundTag data, MetaMachineBlockEntity mbe) {
        if (!(mbe.getMetaMachine() instanceof PrimordialModuleMachine module)) {
            return;
        }
        // 与主机同一判据：只在成型时写（未成型时上面那条兜底分支本来也不写）。
        if (!module.isFormed()) {
            return;
        }
        final long parallel = module.getJadeParallel();
        final int threads = module.getCrossRecipeThreads();
        // 🔴 2026-09-26（任务书 ④）：老山海口径 ——
        //   并行值 <  Long.MAX_VALUE / 2  ⇒ 写真 long（上游 `%d` 键会原样画出真实位数，不再是 21 亿）
        //   并行值 >= Long.MAX_VALUE / 2  ⇒ 写 gtladditions 的「无限」哨兵（画成彩虹「无限」）
        //   阈值取自 PrimordialModuleMachine#isInfiniteParallel(long)：**唯一一处定义**，
        //   与机器 GUI tooltip（formatParallel）用的是同一个判据，不会漂移。
        final boolean infinite = PrimordialModuleMachine.isInfiniteParallel(parallel);
        data.putLong("parallel", infinite ? SHANHAI$INFINITE : parallel);
        data.putLong("threads", threads);
        // 🔴 2026-09-30（同日第二轮）用户拍板：「…然后 jade 写一下提示，这样不会引起误解」。
        //    这里【只写数据】，文案与渲染在 {@link #shanhai$longScaleDurationHint}（客户端侧 @Inject TAIL）。
        //    判据取【并行预算进 long 档】（> 2147483647），与 ModuleRegistry 里那一步用的是**同一个纯函数**
        //    ⇒ 不会出现"提示说已抬下限、机器其实没抬"这种漂移（本工程最忌讳的静默分叉）。
        //    ⚠️ 必须在服务端算：currentParallel 不是 @DescSynced 字段，客户端读到的是字段初值 64。
        //
        // 🔴🔴 2026-10-02 第五轮（用户裁决 ③「Jade 显示跟着改」）：**本处仍然是同口径的，无需改代码**。
        //    上面那个 `parallel` 键写的是 {@link PrimordialModuleMachine#getJadeParallel()}
        //    = getDisplayParallel() = getCurrentParallel() = getEffectiveParallel()
        //    —— 也就是**引擎 getMaxParallel() 的同一个源头**；本轮把「电上限 ÷ T」落进
        //    ParallelOverrideMachine#applyEnergyCap 之后，那一行**自动**跟着变成新口径
        //    （全工程没有任何一处另存一份并行数 ⇒ 界面与引擎结构上不可能分叉）。
        //    而本行的 `totalParallelLimitFor(currentParallel, T)` = currentParallel × T
        //    = **引擎真正吃的总预算**（父类 `(long) getMaxParallel() * getMultipleThreads()` 的逐位同值形态）
        //    ⇒ 它与上面那个数字【本来就是同一口径的两半】（每线程上限 ／ 总预算），本轮一个字都不用改。
        //    ⛔ 上一轮交付报告 §11.5 写的那句「Jade 行仍会显示 259845521287 那一档，而引擎这边拿到
        //       2147483647 ⇒ 显示与引擎口径暂时不一致」——**该结论随第五轮作废**：
        //       显示的是"每线程上限"、引擎吃的也是"每线程上限 × T"，两者从来就是同一件事。
        //    ⚠️ 之所以**只改注释、不改一行代码**：mixin 是本工程常年的禁改区（改错 = 客户端崩屏）。
        //       本轮判据用【编译前后 .class 逐位比对】证明本次改动是"零指令改动"
        //       （见交付报告 §12.4：改后 SHA-256 必须与改前逐位相同）。
        final long parallelBudget = PrimordialModuleMachine.totalParallelLimitFor(
                module.getCurrentParallel(), module.getCrossRecipeThreads());
        if (PrimordialRecipeEffects.isLongScaleParallel(parallelBudget)) {
            data.putInt(SHANHAI$MIN_DURATION_KEY, PrimordialRecipeEffects.LONG_SCALE_MIN_DURATION);
            // 🔴 2026-09-30（同日第三轮）：用户报「它没有到 10 tick」—— 抬头写了 10 tick、配方还是 1 tick。
            //   根因（本次定位）= 上面那行判据只读了【预算 > 阈值】这一件事，**从没读过配方自己的时长**
            //   ⇒ 只要下限那一步没真的改写时长（或只改了设计上不该改的情形），提示就在说谎。
            //   本工程纪律「活的界面上不许放假数据」⇒ 现在**把实际时长一并带过去**，
            //   由客户端按真值决定写"已达"还是写"未生效"（见 #shanhai$longScaleDurationHint）。
            //   ⚠️ 服务端取真值：`lastRecipe` 不是 @DescSynced，客户端读到的是 null。
            final com.gregtechceu.gtceu.api.machine.trait.RecipeLogic hintLogic = module.getRecipeLogic();
            final com.gregtechceu.gtceu.api.recipe.GTRecipe hintRecipe =
                    hintLogic == null ? null : hintLogic.getLastRecipe();
            data.putInt(SHANHAI$ACTUAL_DURATION_KEY, hintRecipe == null ? -1 : hintRecipe.duration);
        }
        if (!shanhai$moduleAnnounced) {
            shanhai$moduleAnnounced = true;
            ShanhaiMod.LOGGER.info("[SHANHAI-DISPLAY] 已写入模块 Jade 真值：parallel={}（写入 {}；{}）threads={}"
                            + "（用户 2026-09-25：模块暂无跨配方线程，显示值写 1；并行按模块自身真实值；"
                            + "2026-09-26：不再被压成 21 亿）",
                    parallel, infinite ? "「无限」哨兵" : String.valueOf(parallel),
                    infinite ? "≥ Long.MAX/2，按老山海口径画无限" : "小于 Long.MAX/2，画真 long", threads);
        }
    }

    /** 只打一次，避免 Jade 每次刷新都刷屏（与主机那条同一个理由）。 */
    @Unique
    private static boolean shanhai$moduleAnnounced = false;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  P5：第 30 台「原初造物编译矩阵」—— 把 GT 那两行（64 并行 / 1 线程）整段替换成我们的三行
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 三行真值落在 Jade 服务端 NBT 的这三个键上（{@code shanhai_creation_line_1..3}）。
     *
     * <p>🔴 <b>必须是自己的键</b>：绝不复用 GT/gtladditions 的 {@code parallel} / {@code threads} —— 那两个键
     * 一旦存在，gtladditions 的 {@code ParallelProviderMixin.appendTooltip} 就会画它自己的文案
     * （「同时处理至多 N 个配方」/「拥有 N 个跨配方线程」），于是就会出现本工程明令禁止的
     * <b>两套显示并存、互相矛盾</b>。
     */
    @Unique
    private static final String SHANHAI$CREATION_LINE_KEY = "shanhai_creation_line_";

    /** 三行的条数（写/读两侧共用；改条数只需改这一处）。 */
    @Unique
    private static final int SHANHAI$CREATION_LINE_COUNT = 3;

    /** 只打一次（Jade 每次刷新都会走这里）。 */
    @Unique
    private static boolean shanhai$creationAnnounced = false;

    /**
     * <b>服务端</b>：给「原初造物编译矩阵」写三行真值 + <b>删掉 GT 那两个键</b>。
     *
     * <h2>为什么这样做（而不是"再叠三行"）</h2>
     * 本类继承 {@link PrimordialModuleMachine} ⇒ 下面那段 {@code shanhai$moduleDisplay} 会按"模块"给它写
     * {@code parallel = 模块并行槽值}（空槽 64）与 {@code threads = 1}。这两个数<b>与本机口径无关</b>
     * （本机接 AE 合成单，不接 GT 配方，也没有跨配方线程）⇒ 用户实机看到的就是
     * 「同时处理至多 64 个配方 / 拥有 1 个跨配方线程」这两行<b>假数据</b>。
     * <p>做法：① 按<b>键名</b>删除 {@code parallel} / {@code threads}（gtladditions 的判据是
     * {@code data.contains(...)} + 值 &gt; 0 ⇒ 键不存在时它<b>一行都不画</b>）；
     * ② 写入我们自己的三个键，由 {@code shanhai$creationMatrixTooltip} 在客户端逐行画出。
     * <p>⚠️ 只在<b>成型</b>时写（与 gtladditions「未成型不写」的行为对齐）。
     */
    private static void shanhai$creationMatrixServerData(CompoundTag data,
                                                        PrimordialCreationCompilationMatrix matrix) {
        // ① 先删上游/gtladditions 的两个键（按 **键名**，不按下标）
        data.remove("parallel");
        data.remove("threads");
        if (!matrix.isFormed()) {
            return;
        }
        // ② 三行真值（数字全部是本机活读：等级/并行/队列/进度）
        final java.util.List<String> lines = matrix.creationDisplayLines();
        for (int i = 0; i < SHANHAI$CREATION_LINE_COUNT && i < lines.size(); i++) {
            data.putString(SHANHAI$CREATION_LINE_KEY + (i + 1), lines.get(i));
        }
        if (!shanhai$creationAnnounced) {
            shanhai$creationAnnounced = true;
            ShanhaiMod.LOGGER.info("[SHANHAI-DISPLAY] 原初造物编译矩阵抬头已换成三行真值（并删掉 GT 的 parallel/threads 两个键）：{}",
                    lines);
        }
    }

    /**
     * <b>客户端</b>：把服务端那三行画到抬头上。
     *
     * <p>只认我们自己的键 ⇒ 其它 29 台模块与主机<b>一个像素都不受影响</b>（它们的 {@code parallel}/{'threads'}
     * 照旧由 {@code shanhai$moduleDisplay} / 主机分支写、由 gtladditions 画）。
     * <p>{@code require = 0}：纯显示增强，目标方法找不到时只该"少三行"，绝不许崩客户端。
     */
    @Inject(
            method = "appendTooltip(Lsnownee/jade/api/ITooltip;Lsnownee/jade/api/BlockAccessor;Lsnownee/jade/api/config/IPluginConfig;)V",
            at = @At("TAIL"),
            remap = false,
            require = 0)
    private void shanhai$creationMatrixTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config,
                                               CallbackInfo ci) {
        try {
            if (tooltip == null || accessor == null) {
                return;
            }
            final CompoundTag data = accessor.getServerData();
            if (data == null || !data.contains(SHANHAI$CREATION_LINE_KEY + 1)) {
                return; // 不是本机 ⇒ 一个字都不动（29 台模块 / 主机走原来那条路）
            }
            for (int i = 1; i <= SHANHAI$CREATION_LINE_COUNT; i++) {
                final String key = SHANHAI$CREATION_LINE_KEY + i;
                if (!data.contains(key)) {
                    continue;
                }
                final String text = data.getString(key);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                tooltip.add(Component.literal(text));
            }
        } catch (Throwable ignored) {
            // 纯显示路径：任何异常都不允许影响游戏
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  2026-09-30（同日第二轮）：抬头加一行「已达最小配方时长 10 tick」提示
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 承载那一行提示的服务端 NBT 键（值 = 实际生效的最小时长，单位 tick）。
     *
     * <p><b>为什么走"服务端写键 / 客户端读键"这条路</b>：Jade 的 {@code appendTooltip} 只在
     * <b>客户端</b>跑，而判定要用的 {@code currentParallel} 在 {@link PrimordialModuleMachine} 里
     * <b>不是</b> {@code @DescSynced} 字段（只有 {@code parallelOverride} 是）⇒ 客户端读到的是字段初值，
     * 会得到"提示该显示却不显示"的假否定。服务端算、键带过来，两侧用的是同一个数。
     */
    @Unique
    private static final String SHANHAI$MIN_DURATION_KEY = "shanhai_min_recipe_duration_ticks";

    /**
     * 承载<b>配方实际时长</b>的服务端 NBT 键（值 = {@code logic.getLastRecipe().duration}；
     * {@code -1} 表示当时没有在跑的配方）。
     *
     * <p>🔴 2026-09-30（同日第三轮）新增。理由见上面写入处的注释：抬头的文案必须以**真值**为准，
     * 不许由"预算过了阈值"直接推出"时长已经抬到 10"。
     */
    @Unique
    private static final String SHANHAI$ACTUAL_DURATION_KEY = "shanhai_actual_recipe_duration_ticks";

    /** 只打一次（同上）。 */
    @Unique
    private static boolean shanhai$durationHintAnnounced = false;

    /**
     * <b>用户 2026-09-30 原话（逐字）</b>：
     * <blockquote>「对了，这个配方加到 long 之后可以加一个最小配方时长为 10tick，然后 jade 写一下提示，
     * 这样不会引起误解」</blockquote>
     *
     * <h2>这一行说的是什么</h2>
     * 当本台模块的并行预算越过 2147483647（= 原原生链的 int 天花板，也就是"进了 long 档"）时，
     * 配方时长会被 {@code PrimordialRecipeEffects#applyLongScaleDurationFloor} 抬到
     * <b>不小于 10 tick</b>。玩家抬头看到「同时处理至多【无限】个配方」时，会以为
     * "无限并行 + 1 tick"= 机器坏了／卡住；这一行是**解释**，不是数值。
     *
     * <p>🔴 <b>2026-09-30 同日第四轮（用户拍板「B. 破一次红线，让那 25 台也抬到 10」）</b>：
     * 下限从 {@code min(10, 原时长)} 改成 <b>绝对 10</b> ⇒ 原时长 1 tick 的配方（引擎链 25 台）
     * 现在**也会**真的到 10。新红线措辞（逐字，见 {@code ShanhaiDurationFloor} 类注释）：
     * 「只有「进了 long 档」（并行预算 &gt; 2,147,483,647）时，配方时长才允许被抬到 10 tick；
     * 其余一切情形，配方时长仍不许超过配方定义的原时长。」
     * 本行的三分支因此**照旧成立**：{@code 下限} 这个数现在恒为 10 ⇒ 只要机器真的到了 10 就写「已达」，
     * 没到就**如实**写「未生效」（这正是第四轮之前那版假提示的病，别改回去）。
     *
     * <h2>为什么挂在 {@code ParallelProvider} 而不是另起一个 provider</h2>
     * 那两行（并行上限 / 跨配方线程）本来就由这个 provider 画，提示紧挨着它们才读得通；
     * 而且本类已经 {@code @Inject} 在它的 {@code appendServerData} 上（同一个 mixin、同一个目标类），
     * <b>不新增 mixin 类、不动 mixin 配置</b>。
     *
     * <h2>⚠️ 与 gtladditions 的 {@code @Overwrite} 共存</h2>
     * 上游用 {@code @Overwrite} 整体替换了 {@code appendTooltip}；本类的 {@code @Inject(TAIL)}
     * 落在**被覆盖之后**的方法体尾部（依据 = Mixin 0.8.5 的 pass 顺序 MAIN→PREINJECT→INJECT，
     * 见本类开头那段 2026-09-26 的核实，与 {@code appendServerData} 那一条同源）。
     *
     * <h2>⚠️ 可验证性边界（诚实声明）</h2>
     * {@code require = 0} + 纯客户端渲染 ⇒ <b>无头专服冒烟证明不了这一行会出现</b>，
     * 只能由用户进游戏看一眼。为了不"静默失败"，第一次真正加行时会打一条 INFO（{@code [SHANHAI-DISPLAY]}）。
     */
    @Inject(
            method = "appendTooltip(Lsnownee/jade/api/ITooltip;Lsnownee/jade/api/BlockAccessor;Lsnownee/jade/api/config/IPluginConfig;)V",
            at = @At("TAIL"),
            remap = false,
            require = 0)
    private void shanhai$longScaleDurationHint(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config,
                                               CallbackInfo ci) {
        try {
            if (tooltip == null || accessor == null) {
                return;
            }
            final CompoundTag data = accessor.getServerData();
            if (data == null || !data.contains(SHANHAI$MIN_DURATION_KEY, 3)) {
                return;
            }
            final int minDuration = data.getInt(SHANHAI$MIN_DURATION_KEY);
            if (minDuration <= 0) {
                return;
            }
            final String ceiling = String.format(java.util.Locale.ROOT, "%,d",
                    ShanhaiParallelBudget.NATIVE_INT_CEILING);
            // 🔴 2026-09-30（同日第三轮）：**以实际时长为准**。用户原话「它没有到 10 tick」——
            //   旧版这里无条件写「已达最小配方时长 10 tick」，而配方其实还是 1 tick ⇒ 假提示，
            //   比没有提示更糟（它会让用户以为已经修好了、不再往下查）。
            final int actual = data.contains(SHANHAI$ACTUAL_DURATION_KEY, 3)
                    ? data.getInt(SHANHAI$ACTUAL_DURATION_KEY) : -1;
            if (actual >= minDuration) {
                tooltip.add(Component.literal("§7已达最小配方时长 §6" + minDuration
                        + " tick §7（并行极高：已超过 §6" + ceiling + "§7）"));
            } else if (actual > 0) {
                // 预算进了 long 档、但配方时长还没抬起来 ⇒ 如实画实际值时长的读数，并点明下限没生效。
                tooltip.add(Component.literal("§7配方时长 §6" + actual + " tick §7（并行极高：已超过 §6"
                        + ceiling + "§7；长档下限 §6" + minDuration + " tick §7未生效）"));
            } else {
                // 没配方在跑 / 读不到 ⇒ 只说"并行极高"，绝不编一个时长出来。
                tooltip.add(Component.literal("§7并行极高：已超过 §6" + ceiling + "§7"));
            }
            if (!shanhai$durationHintAnnounced) {
                shanhai$durationHintAnnounced = true;
                ShanhaiMod.LOGGER.info("[SHANHAI-DISPLAY] 已加抬头提示行：实际配方时长={} tick／下限={} tick"
                        + "（判据 = 并行预算 > {}；actual >= 下限 ⇒ 写「已达」，否则如实写实际值）",
                        actual, minDuration, ShanhaiParallelBudget.NATIVE_INT_CEILING);
            }
        } catch (Throwable ignored) {
            // 纯显示路径：任何异常都不允许影响游戏（与上面那条注入同纪律）。
        }
    }
}
