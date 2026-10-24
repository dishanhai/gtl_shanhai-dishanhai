package com.shanhai.add.time;

/**
 * 山海重构 · <b>「暂停期间准入判定」的纯逻辑核</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么要把它从 {@link ShanhaiClockHooks} 里剥出来</h2>
 * 判定本身是<b>真值表</b>（暂停 × 哪一侧 × 哪个菜单 × 哪种点击），最容易写错的就是某一格的极性。
 * 但它原先散落在直接吃 {@code Level}/{@code AbstractContainerMenu} 的转发点里 ⇒ 不跑游戏就<b>验不了</b>。
 * 剥到这里之后，{@code tools\clock-selfcheck\ClockRulesSelfCheck.java} 用裸 {@code javac}
 * 就能把真值表逐格断言一遍（含负对照），不需要 Forge/游戏类路径。
 *
 * <p>调用方（{@link ShanhaiClockHooks}）只负责把 MC 对象翻译成这里的布尔量。
 *
 * <h2>2. 2026-10-18 这一轮定死的三条口径（每条都有实机缺陷换来的）</h2>
 * <ol>
 *   <li><b>世界写入硬网只拦服务端</b>（{@link #denyWorldWrite}）：
 *       客户端 {@code Level#setBlock} 不会改真世界，只是表现层（{@code GtlAddClientCompat} 的
 *       <b>藏环/恢复环</b>就是纯客户端写）。原来那版只判 {@code SERVER.isPaused()}、不看 level 在哪一侧
 *       ⇒ 暂停期间客户端的藏环写入被一并拦掉 ⇒ 环上那一整片实体方块露出来（用户 2026-10-18 截图）。</li>
 *   <li><b>丢物品要在「发起丢」那一层拦</b>（{@link #denyItemDrop}）：
 *       {@code ServerPlayer#drop(boolean)} 是<b>先</b> {@code removeFromSelected}、<b>后</b>才
 *       {@code ForgeHooks.onPlayerTossEvent} ⇒ 挂在 {@code ItemTossEvent} 上取消时物品已经扣掉了
 *       ⇒ 用户看到"按 Q 背包数量减少、地上没有物品"（凭空消失）。HEAD 拦在扣减之前才对。</li>
 *   <li><b>容器口径对称</b>（{@link #denyContainerClick} / {@link #denyContainerPacket}）：
 *       暂停期间"所有非本人背包的容器/终端一律不能取也不能放"；玩家自己的 {@code InventoryMenu}
 *       仍然可以整理（用户明确要保住），但其中的 <b>丢/抛出</b>动作照样拒（它会生成物品实体 = 改世界）。
 *       另外，AE 终端的菜单<b>覆盖了</b> {@code AbstractContainerMenu#clicked}（网络槽自己处理、不调 super）
 *       ⇒ 只挂在 super 上的拦截<b>只拦得到"放回去"</b>，于是"能拿不能放"。服务端唯一漏斗是
 *       {@code ServerGamePacketListenerImpl#handleContainerClick}，两边都要挂。</li>
 * </ol>
 */
public final class ShanhaiClockRules {

    /** 原版 {@code AbstractContainerMenu.SLOT_CLICKED_OUTSIDE}（点 GUI 外面那个虚拟槽位）。 */
    public static final int SLOT_CLICKED_OUTSIDE = -999;

    private ShanhaiClockRules() {
    }

    // ------------------------------------------------------------------ ① 丢物品

    /**
     * 暂停期间是否拒绝"玩家主动丢出物品"（Q / 拖出界面 / 弃置）。
     *
     * <p>🔴 只该在 <b>{@code Player#drop(boolean)} 的 HEAD</b> 用它——那里扣减还没发生。
     *
     * @param paused 世界时钟是否暂停（服务端读 {@code SERVER}，客户端读镜像）
     */
    public static boolean denyItemDrop(boolean paused) {
        return paused;
    }

    // ------------------------------------------------------------------ ② 容器 / 终端

    /**
     * 容器点击（{@code AbstractContainerMenu#clicked} 那一层，客户端+服务端都会走）。
     *
     * <p>口径（用户拍板）：
     * <ul>
     *   <li><b>不是本人背包</b>（箱子/熔炉/工作台/GT 机器/AE 终端…）⇒ 一律拒（取与放都不许）；</li>
     *   <li><b>本人背包</b>（{@code InventoryMenu}）⇒ 正常整理放行，
     *       <b>但</b>两种"会动世界"的点击仍要拒：{@link #SLOT_CLICKED_OUTSIDE}（把光标上的物品丢到地上）
     *       与 {@code ClickType.THROW}（Q/拖出丢弃）。它们在原版里分别是
     *       {@code player.drop(carried,true)} 与 {@code slot.safeTake(...)+player.drop(stack,true)}，
     *       后者若被 {@code ItemTossEvent} 取消就是<b>又一次"凭空消失"</b>。</li>
     * </ul>
     *
     * @param paused         是否暂停
     * @param ownInventoryMenu 菜单是否就是玩家自己的 {@code InventoryMenu}
     * @param throwClick     点击类型是否为 {@code ClickType.THROW}
     * @param outsideClick   槽位是否 {@link #SLOT_CLICKED_OUTSIDE}
     */
    public static boolean denyContainerClick(boolean paused, boolean ownInventoryMenu,
                                             boolean throwClick, boolean outsideClick) {
        if (!paused) {
            return false;
        }
        if (!ownInventoryMenu) {
            return true;
        }
        return throwClick || outsideClick;
    }

    /**
     * 容器点击包（{@code ServerGamePacketListenerImpl#handleContainerClick} 那一层，服务端唯一漏斗）。
     *
     * <p>为什么还要这一层：AE2 的终端菜单<b>覆盖</b>了 {@code clicked}，网络槽自己处理、<b>不调 super</b>⇒
     * 只挂 {@code AbstractContainerMenu#clicked} 的话"取"这条路径完全漏掉（症状：能拿不能放）。
     * 这一层在 {@code clicked} 之前、且在菜单分派之外 ⇒ 不管子类怎么覆写都盖得住。
     *
     * <p>🔴 <b>2026-10-18 追加：AE2 连这一层都绕过去</b>。它的 ME 终端走自己的
     * {@code MEInteractionPacket#serverPacketData} / {@code InventoryActionPacket#serverPacketData}
     * （javap 实证见 {@code ShanhaiAeGate} 类注释），根本不进 {@code handleContainerClick}。
     * 那两处<b>复用本方法</b>（经 {@link ShanhaiAeGate#deny(boolean, boolean)} 调用）⇒
     * 「原版容器」与「AE 终端」只有这一份判定，不存在第二套极性。
     *
     * @param paused           是否暂停
     * @param ownInventoryMenu 玩家当前 {@code containerMenu} 是否就是 {@code InventoryMenu}
     */
    public static boolean denyContainerPacket(boolean paused, boolean ownInventoryMenu) {
        return paused && !ownInventoryMenu;
    }

    // ------------------------------------------------------------------ ⑤ 世界写入硬网

    /**
     * 世界写入硬网是否拒绝这一笔 {@code Level#setBlock}。
     *
     * <p>🔴 <b>只拦服务端</b>。客户端的一律放行——那是表现层（藏环/恢复环、客户端幻影方块），
     * 服务端根本没有对应的方块，拦掉只会制造"暂停时环露出来 / 恢复后又闪一下"。
     *
     * @param paused     是否暂停
     * @param clientSide 这次 {@code setBlock} 是不是发生在客户端 {@code ClientLevel} 上
     */
    public static boolean denyWorldWrite(boolean paused, boolean clientSide) {
        return paused && !clientSide;
    }

    // ------------------------------------------------------------------ ④ 渲染时钟

    /**
     * 暂停期间给"世界动画"用的渲染插值量 α。
     *
     * <p>🔴 <b>本方法只该被"窄口"调用</b>（例如只作用在某个 renderer 的参数上）。
     * 绝不允许拿它去改 {@code Minecraft#getFrameTime}/全局 {@code Timer#partialTick}——
     * 那会把玩家自己的相机与视角一起钉死（用户红线：鼠标转视角必须正常）。
     *
     * @param paused 是否暂停
     * @param vanilla 原版这一帧的 α
     */
    public static float renderPartialTick(boolean paused, float vanilla) {
        return paused ? 0.0F : vanilla;
    }

    /**
     * 暂停期间"连续渲染时钟"是否应当冻结（{@code GlobalRenderClock#getSmoothTick} 那条墙钟链）。
     *
     * <p>它是真实时间（{@code System.nanoTime()}）驱动 ⇒ 与 tick 冻结完全无关
     * ⇒ 不冻它，机器（原始终焉引擎的旋转环、恒星特效）在暂停期间照样转。
     * 冻法必须是"<b>返回同一个值</b>"而不是返回 0：返回 0 会让动画当场跳到时间原点。
     *
     * @param paused     是否暂停
     * @param frozenTick 暂停期间应当沿用的那个值（{@code NaN} 表示"还没取到，本帧取到就锁住"）
     * @param liveTick   本帧真实算出来的值
     * @return 应当返回给调用方的值
     */
    public static float smoothTick(boolean paused, float frozenTick, float liveTick) {
        // 未暂停 ⇒ 不参与（调用方自己决定要不要清标记）；暂停 ⇒ 沿用锁住的那个值。
        return paused && !Float.isNaN(frozenTick) ? frozenTick : liveTick;
    }

    // ------------------------------------------------------------------ ③ 实体插值

    /**
     * 暂停期间要不要把实体的"上一 tick 位置/朝向"对齐到当前值。
     *
     * <p>这是 ③「空中实体上下鬼畜」的<b>根因</b>：
     * {@code ClientLevel#tickNonPassenger} 的<b>第一句</b>就是 {@code setOldPosAndRot()}
     * （{@code ClientLevel.java:268}）。我们把整个方法 HEAD 取消 ⇒ 这句永远不跑 ⇒
     * {@code xo/yo/zo} 永远停在"暂停前最后一次移动之前"的位置，而渲染插值
     * {@code lerp(α, yOld, y)} 里的 α <b>照常</b>每 tick 从 0 扫到 1 ⇒
     * 渲染位置在"旧位置 ↔ 新位置"之间来回锯齿（幅度 = 一 tick 的位移），
     * 逻辑位置却不动 ⇒ 就是用户看到的"上下鬼畜但不会真的下落"。
     *
     * <p>对齐之后 {@code lerp(α, y, y) == y} 对任何 α 都成立 ⇒ 不动；
     * 而 α 保持实时 ⇒ <b>玩家自己的相机/视角一点没受影响</b>（不碰 partialTick 这条红线）。
     *
     * @param paused          是否暂停
     * @param localPlayer     这个实体是不是本地玩家自己（玩家的 tick 我们从来不拦，别去对齐它）
     */
    public static boolean pinEntityPose(boolean paused, boolean localPlayer) {
        return paused && !localPlayer;
    }
}
