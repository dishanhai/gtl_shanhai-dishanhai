package com.shanhai.client.guard;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import com.shanhai.common.guard.ShanhaiGuardValueLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
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

    /**
     * 要开新屏幕 —— 新屏幕是死亡界面且玩家持有模块 ⇒ 取消（<b>不用 mixin</b>）。
     *
     * <p>⚠️ 1.20.1 官方映射里这个类是 {@code net.minecraft.client.gui.screens.DeathScreen}；
     * 需求里写的 {@code GuiGameOver} 是 1.12–1.16 时代的 MCP 名字，<b>在 1.20.1 里不存在</b>
     *（实测：mapped jar 里有 {@code net/minecraft/client/gui/screens/DeathScreen.class}，
     * 没有 {@code GuiGameOver}）。
     *
     * <p>{@code ScreenEvent.Opening} 实测带 {@code @Cancelable} ⇒ 取消它，屏幕不会打开。
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        if (!(event.getNewScreen() instanceof DeathScreen)) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;
        }
        if (!ShanhaiGuardCore.blocksGameOverScreen(ShanhaiGuardEvents.isGuarded(player), true)) {
            return;
        }
        event.setCanceled(true);
        // 🔴 2026-10-10：日志那一行【照旧】（不是替换）；聊天栏那一条是同一个闸门、同一份计数多出来的去处。
        final ShanhaiGuardCore.LogGate.Batch batch = LOG.admitBatch(KIND_SCREEN, System.currentTimeMillis());
        if (batch == null) {
            return;
        }
        ShanhaiMod.LOGGER.info(batch.logLine());
        final String line = batch.chatLine();
        if (line != null) {
            // 客户端这半边直接写进【自己】的聊天栏（服务端那半边走 ServerPlayer#sendSystemMessage）。
            // 前缀与正文全在核里生成：{@code [创始现实修改模块] CANCELLED ScreenEvent.Opening x1}。
            // 🔴 2026-10-11：正文口径改成"拦了哪个【事件】/哪条【代码路】"（用户原话：「其实我希望左下角
            //   显示拦截了什么事件，或者取消了什么事件，写这些东西」）⇒ 这一类的事件名就是这里取消的那个
            //   ScreenEvent.Opening。**本文件不需要跟着改**：正文与机制名两张表都住在核里。
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(line), false);
        }
    }

    // ═══════════════════════ ③ 客户端每 tick：别让死亡动画把人卡住（2026-10-17 新增） ═══════════════════════
    //
    // 实机症状：服务端把死亡界面压住了、血也补了，但人僵住不能动 —— 因为【客户端自己】进入了死亡态
    //   （本地 deathTime 在涨）。客户端这半边要做的就是：每 tick 末把本地死亡态清掉。
    //
    // 🔴 1.20.1（Forge 47.4.16）没有 TickEvent.ClientTickEvent.Post 这个子类（javap 实测：
    //    `public class TickEvent$ClientTickEvent extends TickEvent`，只有构造器与 phase 字段）
    //    ⇒ 「tick 末」= phase == END，与公共侧 ServerTickEvent 的写法一致。
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
    // 🔴 另一处只在「会不会与服务端打架」上的收紧（字节码实证，见 {@link #onClientTick} 里 setScreen 那段）：
    //    死亡界面只在<b>真的脱离死态</b>（isDeadOrDying()=false）后才关 —— 否则 1.20.1 的
    //    {@code Minecraft#setScreen(null)} 会当场重建一个 DeathScreen，未开死亡界面时还会发 respawn 包。

    /** 客户端这一路的日志类别（诊断类：只进日志，不进聊天栏 —— 聊天栏那一路在服务端）。 */
    private static final String KIND_CLIENT_RESET = ShanhaiGuardCore.KIND_CLIENT_RESET;

    /**
     * 客户端 tick 末：受保护 ＋ 不是创造/旁观 ＋ 本地玩家处于死亡态
     * ⇒ 清掉死亡态（{@code deathTime}/{@code hurtTime} 归零、两条腿补满血），
     * 已经脱离死态且当前屏幕是死亡界面就关掉。
     *
     * <p>🔴 2026-11-08：判据换成 {@link ShanhaiClientDeathUnlock#decide}（多补「hp &lt;= 0 / hp = NaN」与
     * 「创造/旁观」两道门）；日志换成规格那一行 {@code client_death_unlock}（每次动作一行、每秒最多一行）。
     *
     * <p>为什么必须关屏幕：{@code DeathScreen} 一旦开着，本地输入会被它吃掉（人不能动）；
     * 服务端那半边的 {@code ScreenEvent.Opening} 只能拦"正要打开"的那一次，
     * 拦不住"已经开着的"（例如进入死亡态时客户端自己开的那次、或取消失败的那次）。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
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
        // ── 动作【前】的读数：日志里要对账的就是这几个（screen 也取这一拍）──
        final float hpBefore = player.getHealth();
        final boolean deadBefore = player.isDeadOrDying();
        final int deathTimeBefore = player.deathTime;
        final float fieldHealth = ShanhaiGuardValueLayer.readHealthField(player);
        final String screenBefore = minecraft.screen == null ? null : minecraft.screen.getClass().getName();
        // 🔴 判据：既有那两条（交叉校验 ＋ 字段真值）原样委托给核，另补两道门
        //    ——「hp <= 0 / hp = NaN」这一环（本次缺口）与「创造 / 旁观 ⇒ 不动」。
        final ShanhaiGuardCore.Decision decision = ShanhaiClientDeathUnlock.decide(
                hpBefore, deadBefore, deathTimeBefore, player.hurtTime, fieldHealth,
                guarded,
                player.isCreative() || player.isSpectator(),
                ShanhaiGuardCore.enabled);
        if (!decision.yes) {
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
        // ── 死亡界面：只有【真的脱离死态】才关 ──
        //    🔴 1.20.1 {@code Minecraft#setScreen(null)} 的字节码（javap -c 实证，不是推测）：
        //        screen == null && level != null && player.isDeadOrDying()
        //          ⇒ player.shouldShowDeathScreen() ? new DeathScreen(...) : player.respawn()
        //        （后者给服务端发 ServerboundClientCommandPacket(PERFORM_RESPAWN)）
        //    ⇒ 还判死的时候关屏 = 每 tick 重建一个死亡界面、甚至每 tick 发一次 respawn 包
        //      （那就是「与服务端打架」）⇒ 条件里带上 !deadAfter；没关的原因写进日志尾巴。
        final boolean screenWasDeath = minecraft.screen instanceof DeathScreen;
        final boolean closed = screenWasDeath && !deadAfter;
        if (closed) {
            minecraft.setScreen(null);   // 死亡界面已开着 ⇒ 直接关掉（否则人一直卡在"死但没死透"）
        }
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
        tail.append(" ｜ closed=").append(closed);
        if (screenWasDeath && !closed) {
            tail.append("（死亡界面没关：dead_after=true ⇒ 此刻 setScreen(null) 会重建 DeathScreen ／ 发 respawn 包）");
        }
        tail.append(" ｜ ").append(decision.reason);
        ShanhaiMod.LOGGER.info(ShanhaiClientDeathUnlock.logLine(hpBefore, hpAfter, deadBefore, deadAfter,
                deathTimeBefore, screenBefore, via) + tail);
    }
}
