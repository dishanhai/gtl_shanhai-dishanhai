package com.shanhai.client.guard;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 🔴 <b>死亡守卫的【客户端那半边】</b>（2026-10-10 从 {@code common.guard.ShanhaiGuardEvents} 拆出来）。
 *
 * <h2>为什么要拆（专服实测崩溃，原文）</h2>
 * <pre>
 *   Failed to register automatic subscribers. ModID: shanhai, class com.shanhai.…
 *   山海的神人私货 (shanhai) has failed to load correctly
 *   java.lang.RuntimeException: Attempted to load class net/minecraft/client/player/LocalPlayer
 *                           for invalid dist DEDICATED_SERVER
 * </pre>
 * 根因：{@code common.guard.ShanhaiGuardEvents} 带 {@code @Mod.EventBusSubscriber} ⇒ <b>专服上也会被加载</b> ✗，
 * 而取消死亡界面那一段要用 {@code Minecraft}（它的 {@code player} 字段类型是 {@code LocalPlayer}）／
 * {@code DeathScreen}／{@code ScreenEvent} —— <b>全是只有客户端才有的类</b> ⇒ 专服加载期就炸 ✗。
 *
 * <p>修法（本工程既定规矩：<b>兼容类必须分两份</b>）：把客户端那半边挪到本类，
 * 并用 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} <b>声明 dist</b> ——
 * Forge 先用 ASM 读注解数据判 dist、<b>dist 不匹配就不会 {@code Class.forName} 这个类</b> ✓。
 *
 * <h2>两条自查门（{@code javac} 拦不住这个问题，它只在运行期由 dist 校验引爆）</h2>
 * <pre>
 *   ① 本包与 {@code common.guard} 下：{@code Select-String -Pattern 'net\.minecraft\.client'} 必须 0 命中
 *   ② 编出来的 {@code .class} 的常量池里不许有 {@code Class net/minecraft/client/...} 条目
 *      （{@code javap -v} 查；字符串条目是安全的，Class 条目才是引爆点）
 * </pre>
 *
 * <h2>判定仍然一点不在本类</h2>
 * 本类只做一件事：问 {@link ShanhaiGuardCore#blocksGameOverScreen(boolean, boolean)}
 * （是否持有模块由 {@link ShanhaiGuardEvents#isGuarded(Player)} 回答），是就 {@code setCanceled(true)}。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class ShanhaiGuardClientEvents {

    private ShanhaiGuardClientEvents() {}

    /** 日志闸门：同类合并 ＋ 每秒最多一行（与公共侧同一套实现）。 */
    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    /** 类别串（定义在核里；与公共侧共用同一份，避免两处各写一遍字面量）。 */
    private static final String KIND_SCREEN = ShanhaiGuardCore.KIND_SCREEN;

    // ═══════════════════════════════ 🔴 2026-11-20：整段删掉「取消 60 次就放弃」 ═══════════════════════════════
    //
    // 用户原话（逐字就是规格）：「别用 60 次就放弃」。
    //
    // 删掉的东西（连日志一起，一个字段都没留）：
    //   DEATH_SCREEN_CANCEL_MAX = 60          「同一场死亡里最多取消几次」
    //   CANCEL_STREAK / gaveUp / gaveUpAtCount 计数与放弃标志
    //   `death_screen_cancel_giveup count=… max=…` 那一行 ERROR
    //
    // 为什么可以删（这是判据，不是省事）：那条上限存在的唯一理由是
    //   「取消 = 整个 setScreen 直接 return ⇒ 玩家关不掉界面 ⇒ 只能靠放弃来解锁」。
    // 而那个自锁环的<b>根因</b>是客户端仍在死亡态：
    //   Minecraft#tick 里 `if (screen == null && player.isDeadOrDying() && !(screen instanceof DeathScreen))
    //                      setScreen(null);`
    //   这一行每 tick 都会命中 ⇒ setScreen(null) 被改写 DeathScreen ⇒ 我们取消 ⇒ screen 仍是 null ⇒ 20/s。
    // ⇒ 现在把根因拔掉：**每 tick 在 ClientTickEvent.START 就把本地死态掰正**（见 onClientTick），
    //   于是 isDeadOrDying() 永远是 false ⇒ 上面那一行不成立 ⇒ 环自己就不存在了。
    //   取消次数再多也是无害的（每一次都只是"这一拍别开死亡界面"），没有任何理由计数。
    //
    // 🔴 纪律：本文件<b>一个标识符都没留</b>（下面这段注释是唯一还提到它们的去处，作用是"为什么删"）。
    //   判据：`Select-String -Pattern 'CANCEL_STREAK|DEATH_SCREEN_CANCEL_MAX|gaveUpAtCount'`
    //   在<b>代码行</b>上 0 命中（注释行不算 —— 那几句正是在解释它们为什么被删掉）。


    /**
     * 要开新屏幕 —— 新屏幕是死亡界面且玩家持有模块 ⇒ 取消（<b>不用 mixin</b>）。
     *
     * <p>⚠️ 1.20.1 官方映射里这个类是 {@code net.minecraft.client.gui.screens.DeathScreen}；
     * 需求里写的 {@code GuiGameOver} 是 1.12–1.16 时代的 MCP 名字，<b>在 1.20.1 里不存在</b>
     *（实测：mapped jar 里有 {@code net/minecraft/client/gui/screens/DeathScreen.class}，
     * 没有 {@code GuiGameOver}）。
     *
     * <h2>🔴 2026-11-20 重写：不再是「取消了就什么都不做」</h2>
     * 用户原话两条，逐字就是规格：
     * <pre>
     *   「别用 60 次就放弃」
     *   「取消后立刻把 minecraft.screen 维持成玩家原本那个屏；如果原屏是 ChatScreen 也要保证 ESC 能正常关」
     * </pre>
     *
     * <h2>原版 {@code Minecraft#setScreen} 的字节码级事实（Forge 47.3.0 sources jar 实测）</h2>
     * <pre>
     *   970  public void setScreen(@Nullable Screen p_91153_) {
     *   977     } else if (p_91153_ == null &amp;&amp; this.player.isDeadOrDying()) {   ← 用户说的「978 行判断」
     *   978        if (this.player.shouldShowDeathScreen())
     *   979           p_91153_ = new DeathScreen(...);
     *   …
     *   985     Screen old = this.screen;
     *   986     if (p_91153_ != null) {
     *   987        var event = new ScreenEvent.Opening(old, p_91153_);
     *   988        if (MinecraftForge.EVENT_BUS.post(event)) return;      ← 取消 = 整个 setScreen 直接 return
     *   989        p_91153_ = event.getNewScreen();
     *   990     }
     *   992     if (old != null &amp;&amp; p_91153_ != old) { post(ScreenEvent.Closing(old)); old.removed(); }
     *   996     this.screen = p_91153_;
     * </pre>
     * ⇒ <b>取消之后 {@code this.screen} 根本没被赋值、{@code old.removed()} 也没跑</b>
     *（取消发生在 988 行，比 992/996 都早）⇒ 屏幕天然维持原样，这一点是白拿的；
     * 我们唯一要做的是<b>别让"这一拍还想关屏"的意图被吞掉</b>。
     *
     * <h2>现在的四步（顺序就是判据）</h2>
     * <pre>
     *   ① 先判"他来的时候是不是真的在死亡态"（wasDying）。
     *      ⚠️ 必须【先】记下来：下面 ② 会把它掰正，掰正之后再判就永远判不出来。
     *   ② 若在死亡态 ⇒ 当场同一份复位动作（两条腿补血 ＋ deathTime/hurtTime 归零）
     *      ⇒ 让 isDeadOrDying() 变 false（这就是"进入 977 行判断时它已经是 false"的落实）。
     *   ③ 取消这次开屏（不计数、不放弃）—— 然后按 old 屏幕分两条路：
     *      · old 是<b>玩家自己打开的界面</b>（聊天栏 ／ 任何容器界面 ／ 背包 ／ 其它按 ESC 关得掉的屏）
     *        ⇒ 🔴 直接铁定关屏：{@code minecraft.screen = null}【直写字段、不走 setScreen】
     *          （走 setScreen 会在 977 行被重新包装成 DeathScreen、再被我们取消 ⇒ 界面反而关不掉）
     *          ⇒ 判据：「玩家在聊天栏里按 ESC，栏必须关掉」（哪怕死亡界面下一拍还想开）。
     *      · 否则（old 为 null ／ 加载屏 ／ 关不掉的覆盖层）⇒ 保持原行为：留住 old，只取消。
     *   ④ 同一 tick 记一次状态（{@code noteCancelState}）：连续 20 次取消还在同一状态
     *      ⇒ 一行 {@code client_death_screen_cancel_stuck}（给读日志的人看，不是给玩家看）。
     * </pre>
     * {@code 判定仍然一点不在本类}：是否拦死亡界面问 {@link ShanhaiGuardCore#blocksGameOverScreen}，
     * 死态复位问 {@link ShanhaiClientDeathUnlock#decide} / {@link ShanhaiGuardValueLayer#resetDeathFlow}。
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        if (!(event.getNewScreen() instanceof DeathScreen)) {
            return;   // 🔴 只认死亡界面：别的屏（含 newScreen=null = 关屏）一个都不碰
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;
        }
        // 🔴 2026-11-20 诊断（实机「CANCELLED 0 次」的那个分叉）：先把"这一拍到底有没有走到本类"落一行。
        //    0 命中时它把两种完全不同的病分开：
        //      ① 这一行也不出现  ⇒ 事件压根没到本类（注册 / dist 问题）
        //      ② 这一行出现但 guarded=false ⇒ 判据在客户端读不到"受保护"
        //    ⚠️ 原来这里写的是"DeathScreen 是稀有事件（一次死亡一次），不加限流也不会刷屏" —— 这条前提
        //       2026-11-21 被实机否证：读侧被劫持（hp=-Infinity 而 field=20.0）时原版每 tick 都想开死亡界面
        //       ⇒ 本方法每 tick 进一次 ⇒ 这一行实测本局 203 行 / 3 分钟（峰值 ~20 条/秒）。
        //    🔴 但它既是判读的主要证据（事件到没到本类 ／ 判据读不读得到"受保护"），也是"客户端每秒想死
        //       几次"的唯一量化证据 ⇒ 删不得、也不能简单限流（限流就把"想死 20 次"变成"1 次"，丢计数）。
        //    ⇒ 现在走 {@link #logOpening} 的<b>保量降噪</b>：前 {@value #OPENING_VERBATIM} 条逐条打
        //       （带 n=1..5），之后每秒最多 1 行、行尾必须带 n=（本局累计）与 folded=（被折叠掉的条数）；
        //       进世界 / 换维度清零。逐字样例（见那一节的注释）：
        //       [SHANHAI-GUARD] client_death_screen_opening guarded=true enabled=true hp=-Infinity field=20.0 death_time=0 n=137 folded=132
        logOpening(minecraft, player);
        if (!ShanhaiGuardCore.blocksGameOverScreen(ShanhaiGuardEvents.isGuarded(player), true)) {
            return;
        }
        // ── ① 他来的时候是不是真的在死亡态（必须【先】记，② 会把答案改掉）──
        //    ⚠ 这里【不】用 {@code player.isDeadOrDying()}：那个读数在实机被劫持过（hp=-Infinity）。
        //      改用 {@link #looksDying}（与它的字节码逐位等价，见那边的注释）—— 而 wasDying 只决定
        //      "要不要当场掰活"与日志，<b>不参与"能不能关屏"</b>，关屏在 ③ 是无条件做的。
        final boolean wasDying = looksDying(player);
        // ── ② 根因：当场掰正（与客户端每 tick 那一路【同一份】动作，不写第二套）──
        final float max = player.getMaxHealth();
        final String via;
        if (wasDying) {
            if (ShanhaiClientDeathUnlock.maxUsable(max)) {
                via = ShanhaiClientDeathUnlock.viaOf(ShanhaiGuardValueLayer.resetDeathFlow(player).channel);
            } else {
                // getMaxHealth() 不可用 ⇒ 一步都不写（硬写会把血量字段写成 NaN，比 0 更糟）
                via = ShanhaiClientDeathUnlock.VIA_FAILED;
            }
        } else {
            via = "not_dying";   // 人来的时候就没在死态 ⇒ 不需要复位，只取消
        }
        // ⚠ still_dying 只进<b>日志字段</b>（"掰活完还死着没有"），【不再】参与任何"能不能关屏"的判断。
        final boolean stillDying = looksDying(player);
        // ── ③ 取消 ＋ 按 old 屏幕分两条路（玩家想关就当场替他关掉）──
        final Screen old = event.getCurrentScreen();
        final boolean playerIntent = isPlayerOpenedScreen(old);
        event.setCanceled(true);   // 🔴 无论如何都要挡住这一次死亡界面
        if (playerIntent) {
            // 🔴 解卡的关键（2026-11-21 07:14 实机：「我又关不掉聊天栏了」）：
            //    old 是玩家自己打开的界面 ⇒ 他按 ESC 的意思就是"关掉它"，而这一次 setScreen
            //    已经被我们取消（988 行 return）⇒ 那个"关"的意图被一起吞掉了。
            //    ⇒ 直写字段（🔴 不走 setScreen：977 行在 isDeadOrDying()=true 时会把 null 重新
            //      包成 DeathScreen ⇒ 又进本方法 ⇒ 再被取消 ⇒ screen 字段原地不动 ⇒ 关不掉）。
            //    ⚠ 少一次 ScreenEvent.Closing 与 old.removed()（与下面 ④ 直写字段同一条口径）：
            //      ChatScreen#removed() 只是聊天栏滚动复位、AbstractContainerScreen#removed()
            //      只是 menu.removed(player)（关服包在 onClose 里已经发过了）⇒ 对本类无害。
            minecraft.screen = null;
            restoreMouseGrab(minecraft);   // 取消事件 ⇒ setScreen 尾部那次 grabMouse 跑不到，这里补上
            logClosedIntent(old, player);
        } else if (minecraft.screen != old) {
            // 不是玩家可关的界面（old=null ／ 加载屏 ／ 关不掉的覆盖层）⇒ 保持原行为：留住 old。
            // 取消本身不会改 screen 字段（见上面 988/992/996 三行）；这里显式保证一次，不靠"恰好没变"。
            minecraft.screen = old;
        }
        // 🔴 2026-11-21 删掉的"排队关屏"（原来就在这个位置）：
        //    minecraft.execute(() -> { if (mc.screen == old && !mc.player.isDeadOrDying()) mc.setScreen(null); });
        //    ❌ 那个 {@code !isDeadOrDying()} 就是实机「关不掉聊天栏」的直接原因：读侧被劫持
        //       （hp=-Infinity 而 field=20.0）⇒ 它恒为 true ⇒ 这个 lambda 永远不会关屏。
        //    ❌ 只把条件删掉也不行：old=LevelLoadingScreen 时替人关加载屏会卡黑屏；old=null 时无事可做。
        //    ⇒ 玩家可关的界面已经在上面【当场】关掉，其余界面本就不该替他关 ⇒ 整段删除。
        // ── ④ 别把取消做成永动机：同一 tick 记一次状态 ──
        //    ⚠ 必须放在下面那个 1/s 日志闸门【之前】：闸门 return 掉的是日志，不是计数。
        noteCancelState(player, wasDying);
        // 🔴 日志那一行【照旧】（不是替换）；聊天栏那一条是同一个闸门、同一份计数。
        final ShanhaiGuardCore.LogGate.Batch batch = LOG.admitBatch(KIND_SCREEN, System.currentTimeMillis());
        if (batch == null) {
            return;
        }
        ShanhaiMod.LOGGER.info(batch.logLine() + " ｜ was_dying=" + wasDying
                + " still_dying=" + stillDying + " via=" + via
                + " old_screen=" + ShanhaiClientDeathUnlock.screenName(
                        old == null ? null : old.getClass().getName())
                + " player_intent=" + playerIntent
                + " kept_screen=" + !playerIntent + " closed_now=" + playerIntent
                + " giveup=none(2026-11-20 删掉上限)");
        final String line = batch.chatLine();
        if (line != null) {
            // 客户端这半边直接写进【自己】的聊天栏（服务端那半边走 ServerPlayer#sendSystemMessage）。
            // 前缀与正文全在核里生成：{@code [创始现实修改模块] CANCELLED ScreenEvent.Opening x1}。
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(line), false);
        }
    }

    // ═══════ ⑥ client_death_screen_opening 的「保量降噪」（2026-11-21 新增） ═══════
    //
    // 病（实机本局）：读侧被劫持 ⇒ 原版每 tick 都想开死亡界面 ⇒ {@link #onScreenOpening} 每 tick 进一次
    //   ⇒ 这一行峰值 ~20 条/秒（本局 203 行 / 3 分钟）。
    // 两条互相冲突的要求：
    //   ① 它是判读"事件到底到没到本类 ／ 判据读不读得到受保护 ／ 读侧还坏不坏"的主证据 ⇒ <b>不能删</b>；
    //   ② 它同时是"客户端每秒想死几次"的唯一量化证据 ⇒ <b>不能简单限流</b>（限流后只剩每秒 1 行，
    //      分不清"每秒 1 次"和"每秒 20 次"）。
    // ⇒ 口径（用户 2026-11-21 定，逐字就是规格）：
    //     ① 前 5 条【逐条】打（带 n=1..5），一条都不折叠；
    //     ② 之后每秒最多 1 行，那一行必须带【累计计数】n=（本局累计次数）与 folded=（被折叠掉的条数）；
    //     ③ 计数器在每次进世界 / 换维度时清零。
    // 逐字样例（字段顺序与原来一致，n/folded 只在行尾追加）：
    //   [SHANHAI-GUARD] client_death_screen_opening guarded=true enabled=true hp=-Infinity field=20.0 death_time=0 n=137 folded=132
    //   ⇒ 这一局走到这里 137 次，其中 132 条被折叠掉（真打出来的是 n=1..5 与 n=137 这类 1/s 的落点）。
    // 🔴 n 必须真涨：它数的是"本方法走到这里几次"，与打不打日志<b>无关</b>（被折叠的那几条照样 +1）。

    /** 前几条逐条打、一条都不折叠（= 5）。 */
    private static final int OPENING_VERBATIM = 5;

    /** 本局（= 本次进世界 / 换维度以来）进到 {@link #onScreenOpening} 的次数（🔴 与打不打日志无关，只涨不减）。 */
    private static int openingCount;

    /** 本局真打出来的行数（{@code folded = openingCount - openingPrinted}）。 */
    private static int openingPrinted;

    /** 上一次真打那一行的时刻（1/s 窗口；清零后从"很久以前"起算 ⇒ 下一条立刻可打）。 */
    private static long lastOpeningLogMillis = Long.MIN_VALUE / 2L;

    /** 上一次看到的 {@code minecraft.level} 实例（换了 ⇒ 进世界 / 换维度 ⇒ 计数清零）。 */
    private static Object openingLevel;

    /**
     * 打 {@code client_death_screen_opening}（<b>保量降噪</b>：前 {@value #OPENING_VERBATIM} 条一条不落，
     * 之后 1/s 一行且行尾带 {@code n=} ／ {@code folded=}）。
     *
     * <p>🔴 为什么"清零"判在 {@code minecraft.level} 的<b>实例</b>上：本类拿不到可靠的"进世界 / 换维度"
     * 回调（每条回调都能漏），而原版换维度会<b>新建</b>一个 {@code ClientLevel}
     * ⇒ 实例比较是最便宜且不会漏的判据（同一世界内实例不变，所以不会误清）。
     * 过渡期 {@code level} 可能短暂为 {@code null} —— 那也会清一次，宁可多清（代价只是 n 从 1 重数）。
     *
     * @param minecraft 当前客户端（只用来取 {@code level} 实例，可以为 {@code null}）
     * @param player    本地玩家（调用方已判非 {@code null}）
     */
    private static void logOpening(Minecraft minecraft, Player player) {
        // ── ③ 进世界 / 换维度 ⇒ 清零（实例变了就是新世界）──
        final Object level = minecraft == null ? null : minecraft.level;
        if (level != openingLevel) {
            openingLevel = level;
            openingCount = 0;
            openingPrinted = 0;
            lastOpeningLogMillis = Long.MIN_VALUE / 2L;
        }
        // 🔴 先加计数、再判闸门：被折叠掉的那几条也必须算进 n（否则 n 就退化成"打了几行"）
        final int n = ++openingCount;
        final long now = System.currentTimeMillis();
        if (n > OPENING_VERBATIM && now - lastOpeningLogMillis < ShanhaiGuardCore.LOG_MIN_INTERVAL_MILLIS) {
            return;   // 1/s 窗口里 ⇒ 这一条只留计数，由下一次那一行的 folded= 报出来
        }
        lastOpeningLogMillis = now;
        openingPrinted++;
        final int folded = n - openingPrinted;   // 前 5 条恒为 0；之后单调涨（n 停涨时才不动）
        ShanhaiMod.LOGGER.info("{} client_death_screen_opening guarded={} enabled={} hp={} field={} death_time={}"
                        + " n={} folded={}",
                ShanhaiGuardCore.LOG_PREFIX, ShanhaiGuardEvents.isGuarded(player), ShanhaiGuardCore.enabled,
                player.getHealth(), fieldText(player), player.deathTime, n, folded);
    }

    // ═══════ ④ 玩家意图：old 屏幕是不是"玩家自己打开的、能按 ESC 关的界面"（2026-11-21 新增） ═══════
    //
    // 为什么要有这一层：我们取消 ScreenEvent.Opening ⇒ Minecraft#setScreen 在 988 行就 return
    //   ⇒ 【玩家按 ESC 发起的那次关屏请求被一起吞掉】。所以必须分清楚"这一拍到底是谁在关屏"：
    //     · old 是玩家自己打开的界面（聊天栏 ／ 容器 ／ 背包 ／ 暂停菜单 …）⇒ 他是想关掉它 ⇒ 替他关；
    //     · old 为 null ／ 加载屏 ／ 关不掉的覆盖层 ⇒ 不是他的意图 ⇒ 一个都不碰。
    //
    // 判据不是"类名像不像界面"，而是<b>「按 ESC 能不能关」</b>。依据全部是 javap 实测
    // （1.20.1 Forge 47.4.16 parchment 映射 jar）：
    //   Screen#shouldCloseOnEsc()                → iconst_1（默认 true）
    //   ChatScreen#shouldCloseOnEsc              → 未覆写（继承 true）🔴 本次实机主诉的那个屏
    //   AbstractContainerScreen#shouldCloseOnEsc → 未覆写（继承 true）；InventoryScreen 是它的子类
    //   DeathScreen#shouldCloseOnEsc()           → iconst_0（死亡界面永远不是"玩家想关"）
    //   LevelLoadingScreen#shouldCloseOnEsc()    → iconst_0（加载屏也不是"玩家想关"）

    /**
     * {@code old} 是不是<b>玩家自己打开的、可关闭的</b>界面（聊天栏 ／ 容器 ／ 背包 ／ 暂停菜单 …）。
     *
     * <p>是 ⇒ {@link #onScreenOpening} 当场把它关掉（直写 {@code screen} 字段）：因为那一次
     * {@code setScreen(null)} 已经被我们取消、玩家"关"的意图被一起吞掉了（见本节注释）。
     *
     * <p>🔴 这是<b>唯一</b>判定点；{@link #onScreenOpening} 不再自己拼类名判断。
     * 整段包 {@code catch (Throwable)}：守卫代码判不出来就当"不是玩家想关"，绝不许成为新的崩点。
     */
    private static boolean isPlayerOpenedScreen(Screen screen) {
        if (screen == null) {
            return false;   // 没屏可关（原版每 tick 那次 setScreen(null) 就属于这一类）
        }
        try {
            if (screen instanceof ChatScreen) {
                return true;   // 🔴 聊天栏：本次实机主诉「我关不掉聊天栏」
            }
            if (screen instanceof AbstractContainerScreen<?>) {
                return true;   // 任何容器界面（InventoryScreen ／ 背包 ／ 箱子 …）
            }
            if (screen instanceof DeathScreen || screen instanceof LevelLoadingScreen) {
                return false;  // 死亡界面 ／ 加载屏：javap 实测 shouldCloseOnEsc=false，不是他的意图
            }
            return screen.shouldCloseOnEsc();   // 其它界面：ESC 关得掉 = 玩家想关
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 「本地玩家看起来还死着吗」——🔴 <b>只用于"要不要当场掰活"与日志字段</b>，
     * <b>绝不许</b>当"能不能关屏"的前置条件（实机代价见本节注释 ④）。
     *
     * <p>与原版 {@code LivingEntity#isDeadOrDying()} 的字节码<b>逐位等价</b>
     * （javap 实测：{@code getHealth} ／ {@code fconst_0} ／ {@code fcmpg} ／ {@code ifgt false}
     * ⇒ 血 {@code <= 0} <b>或 NaN</b> 都为 true），但这里显式写出来：
     * 一眼能看出它不是"能不能关"的判据，也免得以后又有人把那个读数塞回关屏条件里。
     */
    private static boolean looksDying(Player player) {
        final float hp = player.getHealth();
        return player.deathTime > 0 || hp <= 0.0F || Float.isNaN(hp);
    }

    /**
     * 直写 {@code screen} 字段之后补一次「抓回鼠标」。
     *
     * <p>为什么必须补（javap 实测 {@code Minecraft#setScreen} 尾部）：
     * 走原版路径关屏时，{@code setScreen(null)} 会落到那个 else 分支里调
     * {@code mouseHandler.grabMouse()}（还有 {@code soundManager.resume()}）；
     * 我们取消事件 ⇒ {@code if (MinecraftForge.EVENT_BUS.post(event)) return;} 那一条
     * （sources jar 988 行）直接把整个方法 return 掉 ⇒ <b>那个 else 分支一次都到不了</b>
     * ⇒ 光标会留在外面（玩家看到的是"界面关了，但镜头不跟鼠标转"），
     * 直到他下一次点击才由 {@code MouseHandler#onPress} 自动抓回。
     * ⇒ 这里照原版把这一步补上；{@code grabMouse()} 自带 {@code isWindowActive()} 判断，
     * 窗口没聚焦时本来就是空操作。
     */
    private static void restoreMouseGrab(Minecraft minecraft) {
        try {
            if (minecraft != null && minecraft.mouseHandler != null && !minecraft.mouseHandler.isMouseGrabbed()) {
                minecraft.mouseHandler.grabMouse();
            }
        } catch (Throwable ignored) {
            // 抓鼠标失败绝不许影响关屏本身
        }
    }

    /** {@code client_death_screen_closed_intent} 的 1/s 日志闸门（关屏动作照做，只限日志频率）。 */
    private static long lastClosedIntentLogMillis = Long.MIN_VALUE / 2L;

    /**
     * 同步血量字段（{@code DATA_HEALTH_ID}）真值 ⇒ 日志文本。
     *
     * <p>🔴 读不到就写 {@code unreadable}，<b>绝不写 0 冒充</b>（0 是"人死了"的证据，语义完全不同）。
     * ±∞ 原样打出来 —— 实机那条 {@code hp=-Infinity field=20.0} 的 {@code field=} 就是这一格。
     */
    private static String fieldText(Player player) {
        try {
            return fieldText(ShanhaiGuardValueLayer.readHealthField(player));
        } catch (Throwable ignored) {
            return "unreadable";
        }
    }

    /** 同上，直接给读数的那一版（{@code NaN} = 读不到 ⇒ {@code unreadable}）。 */
    private static String fieldText(float field) {
        return Float.isNaN(field) ? "unreadable" : Float.toString(field);
    }

    /**
     * 玩家可关的界面被我们【当场关掉】之后留一行证据（关屏动作已经做完，这一行只负责可读性）。
     *
     * <pre>
     *   [SHANHAI-GUARD] client_death_screen_closed_intent old=ChatScreen via=direct_field hp=-Infinity field=20.0
     * </pre>
     */
    private static void logClosedIntent(Screen old, Player player) {
        final long now = System.currentTimeMillis();
        if (now - lastClosedIntentLogMillis < ShanhaiGuardCore.LOG_MIN_INTERVAL_MILLIS) {
            return;
        }
        lastClosedIntentLogMillis = now;
        ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "client_death_screen_closed_intent old="
                + ShanhaiClientDeathUnlock.screenName(old == null ? null : old.getClass().getName())
                + " via=direct_field hp=" + player.getHealth() + " field=" + fieldText(player));
    }

    // ═══════ ⑤ 别把取消做成永动机（2026-11-21 新增） ═══════
    //
    // 取消本身只是"这一拍别开死亡界面"，但它【不会】让下一次不再取消：实机这一次的读侧是
    //   [SHANHAI-GUARD] read_side_mismatch hp=-Infinity field=20.0 lost=Infinity hits=2500
    //   ⇒ getHealth() 恒 -Infinity ⇒ 原版每 tick 都想开死亡界面 ⇒ 每 tick 都来一次取消。
    // 取消时已经做过 resetDeathFlow（上面 ② 那一行，全工程唯一一份，不写第二套）；这里只负责【数】：
    //   连续 20 次取消都还在同一状态 ⇒ 一行 client_death_screen_cancel_stuck（给读日志的人看）。
    // 🔴 判据只看【读数与字段】（getHealth() ／ deathTime），不看 isDeadOrDying() 做任何分支。

    /** 连续"同一个原因取消"的次数（状态真的被掰正 ⇒ 清零）。 */
    private static int deathScreenCancelStreak;

    /** {@code client_death_screen_cancel_stuck} 的 1/s 闸门。 */
    private static long lastCancelStuckLogMillis = Long.MIN_VALUE / 2L;

    /** 连续取消到第几次打一行（20 次 ≈ 1 秒：够读，又不刷屏）。 */
    private static final int DEATH_SCREEN_CANCEL_STUCK_EVERY = 20;

    /**
     * 取消之后<b>同一 tick</b> 记一次状态：这次是因为"他在死态"才取消的吗？掰正了没有？
     *
     * <p>{@code wasDying} 是复位【之前】的读数（{@link #onScreenOpening} 里算好的那一份）——
     * 必须在复位前取，复位后再取就永远判不出来"这一次为什么来"。
     */
    private static void noteCancelState(Player player, boolean wasDying) {
        if (!wasDying) {
            deathScreenCancelStreak = 0;   // 这一次不是"因为死态"⇒ 不是同一个原因
            return;
        }
        final float hpNow = player.getHealth();
        final int deathTimeNow = player.deathTime;
        if (hpNow > 0.0F && deathTimeNow <= 0) {
            deathScreenCancelStreak = 0;   // 读数真的掰正了 ⇒ 这一场「同因取消」结束
            return;
        }
        final int count = ++deathScreenCancelStreak;
        if (count % DEATH_SCREEN_CANCEL_STUCK_EVERY != 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastCancelStuckLogMillis < ShanhaiGuardCore.LOG_MIN_INTERVAL_MILLIS) {
            return;
        }
        lastCancelStuckLogMillis = now;
        ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "client_death_screen_cancel_stuck hp=" + hpNow
                + " field=" + ShanhaiGuardValueLayer.readHealthField(player)
                + " death_time=" + deathTimeNow + " count=" + count);
    }

    // ═══════════════════════ ③ 客户端每 tick：别让死亡态把人卡住（2026-10-17 新增 / 2026-11-20 改 START） ═══════════════════════
    //
    // 实机症状：服务端把死亡界面压住了、血也补了，但人僵住不能动 —— 因为【客户端自己】进入了死亡态
    //   （本地 deathTime 在涨）。客户端这半边要做的就是：每 tick 把本地死亡态清掉。
    //
    // 🔴 2026-11-20 用户改口径（逐字）：「每 tick 在 ClientTickEvent.START（不是 END）就把本地
    //    hp<=0 / deathTime>0 / isDeadOrDying() 掰正（原版在 tick 内判断，END 太晚）」。
    //    依据：Minecraft#tick 里那一行 `if (screen == null && player.isDeadOrDying() ...) setScreen(null);`
    //    是在【tick 内】跑的 ⇒ END 掰正等于"挨打完这一拍才补"，永远慢一拍。
    //
    // 🔴 1.20.1（Forge 47.4.16）没有 TickEvent.ClientTickEvent.Post 这个子类（javap 实测：
    //    `public class TickEvent$ClientTickEvent extends TickEvent`，只有构造器与 phase 字段）
    //    ⇒ 相位只有 START / END 两个，本类现在用 START。
    //
    // 🔴 补血也走【两条腿】（ShanhaiGuardValueLayer#resetDeathFlow）：对手可能把 setHealth 锁死、
    //    并给 getHealth() 加偏移 ⇒ 客户端这一路同样要直写 DATA_HEALTH_ID 并只看【字段真值】。
    //
    // 🔴 判据仍在纯逻辑核里（deathFlowResetDecision：isDeadOrDying ／ deathTime&gt;0 ／ 字段真值&lt;=0）——
    //    客户端不写第二份判据。
    //
    // ═════════ 🔴 2026-11-08 补缺口：「客户端血量」那一环 ＋ 「创造 / 旁观」门 ＋ 规格日志 ═════════
    //
    // 用户原话（这是本次改动的全部动因）：
    //   「因为每当我把猫清除的时候，都会左下角有一句自动把血补满的话」
    //   「我感觉我把猫放出来时，玩家可能已经死了，又或者没有血了，所以才打不开」
    // 服务端日志（2026-11-08 test2）已经证明：血被反复写成 -Infinity、客户端确实进过死态 ——
    //   [SHANHAI-GUARD] setHealth_guard value_was=-Infinity -> 20.0
    //   [SHANHAI-GUARD] 拦截 客户端死亡态复位 + 死亡界面
    // ⇒ 这次<b>只补三处缺口，复位动作一个字都没改</b>（还是 resetDeathFlow ＋ 关死亡界面）：
    //   ① 判据补「客户端血量」这一环：原来的入口只有 deathFlowResetDecision（字段真值 ／ deathTime ／
    //      isDeadOrDying ／ 对手桥）⇒ `getHealth() <= 0` 与「血读到 NaN」这两条<b>没有参与</b>。
    //      现在改由 {@link ShanhaiClientDeathUnlock#decide} 组合：它把「交叉校验表」（hp&lt;=0 ／ NaN ／
    //      isDeadOrDying ／ deathTime&gt;0）与上面那条<b>原样委托</b>给纯逻辑核 ⇒ 依旧没有第二份判据。
    //   ② 补「创造 / 旁观」门（原来<b>没有</b>这一道 ⇒ 创造模式也会被我们改本地血量）。
    //   ③ 日志按规格换成一行 {@code client_death_unlock}（hp_before ／ hp_after ／ dead_before ／
    //      dead_after ／ death_time ／ screen ／ via）；既有那行「拦截 客户端死亡态复位（同类合并 N 次）」
    //      与 heal_channel 细节<b>原样并进这一行的尾巴</b> ⇒ 每次动作仍然只打一行、每秒仍然最多一行。
    //
    // 🔴 2026-11-21 末：上一版这里写着「死亡界面只在<b>真的脱离死态</b>（isDeadOrDying()=false）后才关」
    //    —— 这条收紧**写反了**（实机：客户端血 -Infinity ⇒ 永远不成立 ⇒ 一次都没关）。
    //    现在的口径：先掰活、再关；掰活失败也照关（详见 ④ 那一节与 {@link #forceCloseDeathScreen}）。
    //    1.20.1 的 {@code Minecraft#setScreen(null)} 977 行会重建 DeathScreen 这件事仍然成立，
    //    但它是"关屏的方式"问题、不是"关不关"的问题 ⇒ 掰活失败那一支改成直写 screen 字段绕开 977 行。

    /** 客户端这一路的日志类别（诊断类：只进日志，不进聊天栏 —— 聊天栏那一路在服务端）。 */
    private static final String KIND_CLIENT_RESET = ShanhaiGuardCore.KIND_CLIENT_RESET;

    /**
     * 客户端 tick <b>开头</b>（2026-11-20 从 END 改成 START）：受保护 ＋ 不是创造/旁观 ＋
     * 本地玩家血量是 {@code <=0 ／ NaN ／ ±∞} 或处于死亡态 ⇒ 钳回 {@code getMaxHealth()} 并清掉死亡态
     * （{@code deathTime}/{@code hurtTime} 归零、两条腿补满血）；屏幕是死亡界面 ⇒ 掰活之后关掉它
     * （2026-11-21 末：关屏不再要求"已经脱离死态"）。
     *
     * <p>🔴 为什么必须是 START：原版 {@code Minecraft#tick} 在<b>tick 内</b>就会因为
     * {@code player.isDeadOrDying()} 去 {@code setScreen(null)}（→ 977 行改写成 DeathScreen）。
     * END 才复位的人，那一拍已经被打中过一次；START 复位 ⇒ 进 tick 时就已经不是死态 ⇒ 那一行不成立。
     *
     * <p>🔴 2026-11-08：判据换成 {@link ShanhaiClientDeathUnlock#decide}（多补「hp &lt;= 0 / hp = NaN」这一环）；
     * 日志换成规格那一行 {@code client_death_unlock}（每次动作一行、每秒最多一行）。
     *
     * <p>🔴 2026-11-21：原来这里还写着"另补「创造/旁观」两道门" —— <b>那一道门已经删了</b>
     * （{@code creativeOrSpectator} 入参只进日志，见 {@link ShanhaiClientDeathUnlock#decide} 的注释：
     * 原版创造模式只在 {@code Player#hurt} 里挡伤害，对手不走 {@code hurt} 时这道门不在路上；
     * 实机那 203 次 / 3 分钟的死亡界面就是创造模式下发生的）。
     *
     * <p>🔴 2026-11-21 末：tick 开头先走一次 {@link ShanhaiGuardValueLayer#clampLocalPlayerHealth}
     * （根因 1 的客户端钳制），关屏统一交给 {@link #forceCloseDeathScreen}（根因 2 的顺序修正）。
     *
     * <p>为什么必须关屏幕：{@code DeathScreen} 一旦开着，本地输入会被它吃掉（人不能动）；
     * 服务端那半边的 {@code ScreenEvent.Opening} 只能拦"正要打开"的那一次，
     * 拦不住"已经开着的"（例如进入死亡态时客户端自己开的那次、或取消失败的那次）。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 🔴 2026-11-20：改成 START（用户点名的口径：「每 tick 在 ClientTickEvent.START（不是 END）
        //    就把本地 hp<=0 / deathTime>0 / isDeadOrDying() 掰正（原版在 tick 内判断，END 太晚）」）。
        //    依据（Forge 47.3.0 sources jar 实测）：
        //      Minecraft#tick 里 `if (screen == null && player.isDeadOrDying() && !(screen instanceof DeathScreen))
        //                            setScreen(null);`
        //    这一行在 tick 内就会跑 ⇒ END 才掰正的玩家，那一拍已经被 setScreen(null) 打中过一次
        //    （于是 setScreen 的 977 行把 null 改写成 DeathScreen、再被我们取消 ⇒ 又一个回合）。
        //    START 掰正 ⇒ 进 tick 时 isDeadOrDying() 就是 false ⇒ 那一行根本不成立。
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;   // 还没进世界 / 正在切维度
        }
        // 🔴 只处理受保护玩家（客户端这边靠【背包里的模块】判；单机时也会命中服务端登记过的 REMEMBERED）
        final boolean guarded = ShanhaiGuardEvents.isGuarded(player);
        if (!guarded) {
            return;   // 不是受保护玩家 ⇒ 后头的反射读数都不用做（与改动前同一条便宜闸门）
        }
        // ── 🔴 2026-11-21 根因 1：客户端也钳一次（本地玩家血量被写成 <=0 ／ NaN ／ ±∞ ⇒ 改成 getMaxHealth()）──
        //    走 ValueLayer 那一层薄包装：判据 needsHealthClamp()、动作 resetDeathFlow() 全是既有那一份，
        //    这里只是"客户端入口"（不新写判据、不新写补血）。放在【取读数之前】⇒ 这一拍后面的判据
        //    看到的已经是掰活之后的状态。
        //    判据依据（源码实证）：客户端的死亡态读的是 getHealth() = entityData 里的 DATA_HEALTH_ID，
        //    而这条字段可以由 ClientboundSetEntityDataPacket → SynchedEntityData#assignValues 直接写入，
        //    既不经过 set(...)（写入点 mixin 命中不到），也不经过 setHealth 的 clamp。
        final boolean clampedThisTick = ShanhaiGuardValueLayer.clampLocalPlayerHealth(player, guarded);
        // ── 动作【前】的读数：日志里要对账的就是这几个（screen 也取这一拍）──
        final float hpBefore = player.getHealth();
        final boolean deadBefore = player.isDeadOrDying();
        final int deathTimeBefore = player.deathTime;
        final float fieldHealth = ShanhaiGuardValueLayer.readHealthField(player);
        final String screenBefore = minecraft.screen == null ? null : minecraft.screen.getClass().getName();
        // 🔴 2026-11-20：这里原来有一段"一脱离死亡态就把取消额度充满（CANCEL_STREAK.set(0) / gaveUp=false）"，
        //    随「60 次就放弃」整段删掉了 —— 现在没有额度这个概念，也就没有要充的东西。

        // 🔴 判据：既有那两条（交叉校验 ＋ 字段真值）原样委托给核，另补「hp <= 0 / hp = NaN」这一环。
        //    🔴 2026-11-21 07:1x：创造 / 旁观这道门在【实际调用点】就关掉了（不再豁免）。理由：
        //        **「创造模式不会死」是原版的假设，不是保证。** 对手不走 {@code Player#hurt}
        //        （直接写同步血量字段 ／ 直接调 {@code die} ／ 改 {@code getHealth()} 读侧）时，
        //        创造模式这道门<b>根本不在路上</b>。实机这一次就是它在创造模式下让我们"照常撒手"
        //        （客户端 hp=-Infinity、字段真值 20.0 ⇒ client_death_screen_opening 203 次）。
        //       ⇒ 创造 / 旁观<b>照常救</b>；"读数与字段都正常 ⇒ 一个字节都不写"那道闸门仍在
        //         {@link ShanhaiClientDeathUnlock#decide} 自己的快路里（不靠创造模式这一条）。
        //    🔴 2026-11-21 末：判据那边（{@link ShanhaiClientDeathUnlock#decide}）也把这道分支删了
        //       ⇒ 这个入参现在【只进日志】。所以这里改回取<b>真值</b>（原来恒传 false）：
        //       判读时要能一眼看出"这一次是不是在创造模式里发生的"（放在日志尾巴的 creative= 里）。
        //       ⚠️ 参数本身不许删：既有调用点与签名都还在传它。
        final boolean creative = player.isCreative() || player.isSpectator();   // 🔴 只进日志（decide 不看它）
        final ShanhaiGuardCore.Decision decision = ShanhaiClientDeathUnlock.decide(
                hpBefore, deadBefore, deathTimeBefore, player.hurtTime, fieldHealth,
                guarded,
                creative,
                ShanhaiGuardCore.enabled);
        if (!decision.yes) {
            // 🔴 2026-11-21 兜底：判据说"这一拍不用复位" ⇒ 下面那条路永远走不到"关死亡界面"，
            //    但界面可能【真的】开在屏幕上（实机就是这样：ScreenEvent.Opening 那一路一次都没命中）。
            //    ⚠ 这一拍刚在根因 1 那里掰活过（clampedThisTick）就不重复写血。
            forceCloseDeathScreen(minecraft, player, guarded, clampedThisTick);
            return;
        }
        // ── 动作：与服务端 ②/④ 同一份复位动作（两条腿补血 ＋ deathTime = 0 ＋ hurtTime = 0）──
        //    ⚠ getMaxHealth() 不可用（NaN / ±∞ / 非正）时【一步都不写】：硬写会把血量字段写成 NaN，
        //      比 0 更糟 ⇒ 只记 via=failed，留给下一 tick / 服务端那条腿。
        final float max = player.getMaxHealth();
        ShanhaiGuardValueLayer.HealOutcome outcome = null;
        if (ShanhaiClientDeathUnlock.maxUsable(max)) {
            outcome = ShanhaiGuardValueLayer.resetDeathFlow(player);
        }
        // ── 动作【后】的读数（=「客户端血到底回来了没有」的对账）──
        final float hpAfter = player.getHealth();
        final boolean deadAfter = player.isDeadOrDying();
        final String via = ShanhaiClientDeathUnlock.viaOf(
                outcome == null ? ShanhaiGuardCore.HealChannel.FAILED : outcome.channel);
        // ── 死亡界面：关屏统一交给兜底那一条（判据只看屏幕类型，见 {@link #forceCloseDeathScreen}）──
        //    🔴 2026-11-21 这里【不再】要求 "!deadAfter" 才关（上一版就是这条判据写反了：
        //       客户端血是 -∞ 时 isDeadOrDying() 恒为 true ⇒ 一次都没关 ⇒ 实机「我还是被猫打死了」）。
        //    🔴 现在：先掰活（上面那次 resetDeathFlow），再【当场】直写 screen 字段关屏；掰活失败也照关。
        final boolean screenWasDeath = minecraft.screen instanceof DeathScreen;
        final boolean healedThisTick = clampedThisTick || outcome != null;
        forceCloseDeathScreen(minecraft, player, guarded, healedThisTick);
        // ── 日志：每次动作一行 ＋ 每秒最多一行（走既有 LogGate ⇒ 与原来同一份「同类合并」计数）──
        final ShanhaiGuardCore.LogGate.Batch batch = LOG.admitBatch(KIND_CLIENT_RESET, System.currentTimeMillis());
        if (batch == null) {
            return;   // 本窗口已经打过一行 ⇒ 动作照做，日志不再写（1 秒最多一行）
        }
        final StringBuilder tail = new StringBuilder(160);
        tail.append(" ｜ ").append(batch.logLine());   // 既有那行「拦截 …（同类合并 N 次）」原样并进来（不是替换）
        tail.append(" ｜ ").append(outcome == null
                ? "heal_channel=failed ／ getMaxHealth()=" + max + " 不可用（NaN ／ 无限 ／ 非正）⇒ 血一步都没写"
                : outcome.detail);
        if (outcome != null && !outcome.landed()) {
            tail.append(" ｜ ⚠ 写失败：两条腿都没把血落盘（hp_after=").append(hpAfter).append("）");
        }
        tail.append(" ｜ healed_this_tick=").append(healedThisTick);
        // 🔴 creative 只进日志（{@link ShanhaiClientDeathUnlock#decide} 的结论不看它）：判读"这一次是不是
        //    在创造模式里发生的"用的就是这一格（实机那 203 次就发生在创造模式下）。
        tail.append(" ｜ creative=").append(creative);
        tail.append(" ｜ field_before=").append(fieldText(fieldHealth));   // DATA_HEALTH_ID 真值（读不到 ⇒ unreadable）
        tail.append(" ｜ death_screen=").append(screenWasDeath ? "force_closed_direct_field" : "none");
        tail.append(" ｜ ").append(decision.reason);
        ShanhaiMod.LOGGER.info(ShanhaiClientDeathUnlock.logLine(hpBefore, hpAfter, deadBefore, deadAfter,
                deathTimeBefore, screenBefore, via) + tail);
    }

    // ═════════ ④ 兜底：死亡界面【真的】开在屏幕上（2026-11-21 新增 ／ 07:1x 再改判据） ═════════
    //
    // 实机症状（用户原话「我死了是真的看到了重生页面」／「我又关不掉聊天栏了」）：死亡界面确实
    //   开出来了，而 `ScreenEvent.Opening` 那一路的取消有时一次都没命中。
    // ⇒ 所以保留一条<b>不看事件</b>的兜底：每 tick 直接看 {@code Minecraft#screen} 是不是
    //   <b>原版</b> {@link DeathScreen}（🔴 只认原版类、不认任何模组），是、且玩家受保护 ⇒ 掰活 ＋ 关掉它。
    //
    // 🔴 2026-11-21 07:1x 实机第三次：判据里的 {@code isDeadOrDying()} 彻底删掉。
    //     实机读数 `[SHANHAI-GUARD] read_side_mismatch hp=-Infinity field=20.0 lost=Infinity` ⇒ 这个读数恒为 true。
    //     只要它出现在"能不能关屏"的位置上，无论写成 `if (isDeadOrDying()) return;` 还是
    //     `if (!isDeadOrDying()) close();`，结果都是【一次都关不掉】（client_death_screen_force_closed = 0 就是证据）。
    //   ⇒ 现在的判据<b>只有屏幕类型</b>：{@code minecraft.screen instanceof DeathScreen} ＋ 受保护 ⇒ 关。
    //      {@code isDeadOrDying()} ／ {@code getHealth()} 只许出现在<b>日志字段</b>里。
    //   ⇒ 顺序（顺序就是判据）：
    //       ① 先掰活：写血 = getMaxHealth() ／ deathTime = 0 ／ hurtTime = 0
    //          （{@link ShanhaiGuardValueLayer#resetDeathFlow}，全工程唯一一份，不写第二套）
    //          🔴 这一步<b>成功与否都不影响关屏</b>。
    //       ② 再关屏：{@code minecraft.screen = null}【直写字段、不走 setScreen】
    //          （977 行在死态下会把 null 重新包成 DeathScreen ⇒ 又走 ScreenEvent ⇒ 被我们取消
    //            ⇒ 屏幕字段原地不动 ⇒ 走 setScreen 等于没关）。
    //       ③ 记账 ＋ 一行日志（hp ／ death_time ／ guarded ／ healed ／ via=direct_field ／ dead_read）。
    //   🔴 不再用 {@code minecraft.execute} 排队：直写字段根本不走 setScreen（没得重入），
    //      而排队会让"这一拍没关掉"的窗口多挂一拍 —— 实机表现就是界面多闪一下。
    //   🔴 绝不放弃、绝不锁屏：没有「关 N 次就收手」那种上限；反复被打开只每
    //      {@value #DEATH_SCREEN_REOPEN_WARN_EVERY} 次记一行 {@code client_death_screen_reopened count=N}。

    /** 兜底关屏日志的闸门（与 {@link #LOG} 同一条口径 {@code LOG_MIN_INTERVAL_MILLIS}：每秒最多一行）。 */
    private static long lastForceCloseLogMillis = Long.MIN_VALUE / 2L;

    // 🔴 2026-11-21 删掉的字段：lastStillDyingWarnMillis（{@code client_death_screen_still_dying} 那一行 WARN 的闸门）。
    //    删它的原因：它唯一的作用是"掰活失败时再打一行"，而"掰活失败"正是被 isDeadOrDying() 判出来的
    //    ⇒ 那个判据连同这一行一起删。现在 dead_read= 直接并进 client_death_screen_force_closed 那一行。

    /** 「关了又开」的连续计数（真正关掉一次 +1）。 */
    private static int deathScreenForceCloses;

    /** 死亡界面连续不在屏幕上的 tick 数（安静够久 ⇒ 上一场「关了又开」结束，计数清零）。 */
    private static int deathScreenCalmTicks;

    /** 安静这么久（tick，= 5 秒）就把 {@link #deathScreenForceCloses} 清零。 */
    private static final int DEATH_SCREEN_CALM_TICKS = 100;

    /** 连续关到第几次打一行 WARN 留证据（🔴 不放弃、不锁屏，只记录 —— 这条日志只为回答"到底是谁在反复开"）。 */
    private static final int DEATH_SCREEN_REOPEN_WARN_EVERY = 20;

    /**
     * 🔴 <b>兜底：死亡界面已经开在屏幕上 ⇒ 先掰活本地玩家、再当场关掉它</b>（直写 {@code screen} 字段）。
     *
     * <p>只在<b>受保护</b>时动手；{@code screen} 不是死亡界面 ／ 不受保护 ／ 任何异常 ⇒ <b>什么都不做</b>
     * （整段包在 {@code catch (Throwable)} 里：兜底本身绝不许成为新的崩点）。
     *
     * <p>🔴 判据<b>只有屏幕类型</b>（{@code instanceof DeathScreen} ＋ 受保护）：
     * <b>不许</b>再出现 {@code isDeadOrDying()} ／ {@code getHealth()} 之类读数当"能不能关"的前置条件
     * —— 实机代价见本节注释（force_closed = 0）。
     *
     * @param guarded          {@link ShanhaiGuardEvents#isGuarded(Player)} 的结论（唯一判定入口，这里不重算）
     * @param healedBeforeCall 这一拍是不是<b>已经</b>掰活过（tick 路径刚跑过 resetDeathFlow ／ 根因 1 的客户端钳制刚写过血
     *                         ⇒ 不重复写一遍；写两次值一样，但会多打一行读数）
     */
    private static void forceCloseDeathScreen(Minecraft minecraft, Player player, boolean guarded,
                                             boolean healedBeforeCall) {
        try {
            if (minecraft == null || player == null || !guarded) {
                return;   // 不受保护 ⇒ 什么都不做（绝不许拦住正常死亡）
            }
            if (!(minecraft.screen instanceof DeathScreen)) {
                // 屏幕不是死亡界面 ⇒ 一个都不碰；顺手记"安静了多久"（连续被打开的那一场结束就清零）
                if (deathScreenCalmTicks < DEATH_SCREEN_CALM_TICKS) {
                    deathScreenCalmTicks++;
                } else {
                    deathScreenForceCloses = 0;
                }
                return;
            }
            deathScreenCalmTicks = 0;
            // ── ① 先掰活（顺序见本节 ①②③）：写血 = getMaxHealth() ＋ deathTime = 0 ＋ hurtTime = 0 ──
            //    getMaxHealth() 不可用（NaN / ±∞ / 非正）时【一步都不写】：硬写会把血量字段写成 NaN，比 0 更糟。
            //    🔴 这一步成功与否<b>都不影响</b>下面关屏。
            boolean healed = healedBeforeCall;
            if (!healed && ShanhaiClientDeathUnlock.maxUsable(player.getMaxHealth())) {
                healed = ShanhaiGuardValueLayer.resetDeathFlow(player).landed();
            }
            // ── ② 再关屏：判据【只有屏幕类型】，直写字段（不走 setScreen）──
            final float hpNow = player.getHealth();
            final int deathTimeNow = player.deathTime;
            final boolean deadRead = player.isDeadOrDying();   // 🔴 只进日志字段，不参与任何分支
            // ⚠ 直写字段的理由：原版 setScreen(null) 的 977 行会把 null 改写成一个新的 DeathScreen
            //    （死态没解除）⇒ 再走 ScreenEvent ⇒ 被我们取消 ⇒ screen 原地不动 ⇒ 走 setScreen 等于没关。
            //    代价是少一次 ScreenEvent.Closing 与 old.removed()：原版 DeathScreen <b>没有覆写</b>
            //    removed()（javap：只覆写 init/render/mouseClicked/shouldCloseOnEsc/isPauseScreen/tick）
            //    ⇒ 少的这一对通知对本类无害。
            minecraft.screen = null;
            restoreMouseGrab(minecraft);   // 同上：直写字段绕过了 setScreen 尾部的 grabMouse
            logDeathScreenForceClosed(hpNow, fieldText(player), deathTimeNow, guarded, healed, deadRead);
        } catch (Throwable ignored) {
            // 🔴 任何异常 ⇒ 什么都不做（这一条是兜底，不是主干：它失败就当没加过）
        }
    }

    /** 真正关掉一次之后的记账 ＋ 两行日志（INFO 走 1/s 闸门、WARN 每 {@value #DEATH_SCREEN_REOPEN_WARN_EVERY} 次一行）。 */
    private static void logDeathScreenForceClosed(float hpNow, String fieldNow, int deathTimeNow, boolean guarded,
                                                  boolean healed, boolean deadRead) {
        deathScreenForceCloses++;
        final int count = deathScreenForceCloses;
        // 🔴 连续第 20 / 40 / … 次还看到它在开 ⇒ 留证据（不放弃、不锁屏，继续关）
        if (count % DEATH_SCREEN_REOPEN_WARN_EVERY == 0) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "client_death_screen_reopened count=" + count
                    + " hp=" + hpNow + " field=" + fieldNow + " death_time=" + deathTimeNow
                    + " guarded=" + guarded);
        }
        final long now = System.currentTimeMillis();
        if (now - lastForceCloseLogMillis < ShanhaiGuardCore.LOG_MIN_INTERVAL_MILLIS) {
            return;   // 同一秒里已经打过一行 ⇒ 动作照做、日志不再写（1/s，别刷屏）
        }
        lastForceCloseLogMillis = now;
        ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "client_death_screen_force_closed hp=" + hpNow
                + " field=" + fieldNow + " death_time=" + deathTimeNow + " guarded=" + guarded
                + " healed=" + healed + " via=direct_field dead_read=" + deadRead);
    }
}
