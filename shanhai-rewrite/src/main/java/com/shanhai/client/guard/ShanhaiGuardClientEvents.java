package com.shanhai.client.guard;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.guard.ShanhaiGuardCore;
import com.shanhai.common.guard.ShanhaiGuardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
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
}
