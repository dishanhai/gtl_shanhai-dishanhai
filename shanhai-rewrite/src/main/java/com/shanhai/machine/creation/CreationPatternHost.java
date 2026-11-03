package com.shanhai.machine.creation;

import com.lowdragmc.lowdraglib.side.item.IItemTransfer;

/**
 * 「样板槽的提供方」= 原初造物编译矩阵的<b>控制器</b>；消费方 = {@link CreationCraftExecutor}（AE 侧的执行器）。
 *
 * <h2>为什么要有这个接口（而不是执行器直接读具体控制器字段）</h2>
 * P2 时它的存在理由是「GTCEu 的仓室只认 {@code IMultiController}，不认控制器是哪一类机器」；
 * P3 把端口方块删掉、执行器搬进控制器之后，它仍然保留，理由变成<b>口径收敛</b>：
 * <ul>
 *   <li>执行器只认这 5 个签名（样板库存 / 槽数 / 版本号 / 并行上限 / 报给 AE 的倍率），
 *       不碰控制器的其它任何状态 ⇒ "算术输入只有一个来源"这件事在类型层面就成立了；</li>
 *   <li>离线自证 / 以后的第二台同型机器，只要实现这 5 个方法就能复用整个执行器。</li>
 * </ul>
 * <p>⚠️ P3 起「能不能接单」的判据不在本接口里，而是 {@code machine.isFormed()}：
 * P2 的等价判据是"端口在哪台成型多方块里、那台控制器是不是本接口"。见 {@link CreationCraftExecutor}。
 *
 * <h2>版本号是干什么的（不是装饰）</h2>
 * AE 的 {@code NetworkCraftingProviders#addProvider} 会在<b>每次网络/节点变化</b>时重新问一遍
 * {@code getAvailablePatterns()}。1000 格逐条 {@code decodePattern} 不可能每次重来，
 * 所以执行器持有一份缓存，并靠 {@link #getCreationPatternVersion()} 判断"要不要重解码 + 要不要
 * {@code ICraftingProvider.requestUpdate(...)} 通知 AE"。
 * ⇒ 版本号必须<b>只在内容真的变了</b>时自增（挂在 {@code ItemStackTransfer#setOnContentsChanged} 上）。
 */
public interface CreationPatternHost {

    /** 样板存储的只读视图（{@code ItemStackTransfer}，1000 格）。 */
    IItemTransfer getCreationPatternInventory();

    /** 有效槽位数（= 1000；单独给出来是为了让执行器不必假设长度）。 */
    int getCreationPatternSlotCount();

    /** 内容版本号：每变一次 +1（服务端自增；执行器靠它决定"要不要重解码"）。 */
    long getCreationPatternVersion();

    /**
     * <b>本机此刻的并行上限</b>（= 一个 10 tick 周期里最多能做完的份数）。
     *
     * <p>来源 = {@code PrimordialCreationCompilationMatrix#extraParallelFor()}（
     * {@code 8^有效物质模块等级}；空槽/无模块 ⇒ 1；等级 ≥ 15 ⇒ 哨兵 {@code Long.MIN_VALUE} = 无限）。
     *
     * <p>🔴 执行器<b>必须</b>先判哨兵（{@code CreationCraftMath#isInfinite(long)}）再参与算术 ——
     * 把它当数字用会得到垃圾。无限模式的口径是"把队列里所有待做份数<b>一次全部做完</b>"（不分批）。
     */
    long getCreationParallelCap();

    /**
     * <b>此刻该报给 AE 的样板倍率</b>（1 = 按样板原样；N = 一块样板实际出 N 份）。
     * <p>🔴 <b>P10 起它恒等于「主机增产」</b>（用户原话「干脆去掉开关，永远吃主机增产」）：
     * 没有开关、没有"手填倍率"，就是 {@code productionMultiplierOf(主机门控等级, 主机自带增产)}。
     *
     * <h2>🔴 这个数是"报给 AE 的"和"实际产出的"<b>同一个数</b></h2>
     * 用户原话（逐字）：「只吃主机增产，那个倍率是你下单是为了方便 ae 计算用的啊，不然它产出 17 个 ae
     * 下单计算时还以为产出了 1 个，这样就多合成了很多份」⇒
     * <ul>
     *   <li>{@code getAvailablePatterns()} 里<b>改写样板 outputs 的数量 × 本值</b>（P2）；</li>
     *   <li>实际出货<b>也</b>用本值（P2）；两者不一致 = AE 按错的数量下单。</li>
     * </ul>
     * <p>执行器每 tick 比对它：<b>值变了就 {@code ICraftingProvider.requestUpdate(...)}</b>
     * —— 否则主机等级变了、AE 还按旧倍率算（那正是"多合份数"的来源）。
     */
    int getAeReportedPatternMultiplier();
}
