package com.shanhai.client.render.hostfx;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 归墟视界枢核 · <b>藏块台账的独立对账驱动器（client tick，不依赖 BER）</b>。
 *
 * <h2>它为什么必须存在（用户第 ① 条："结构被打掉后世界缺块"）</h2>
 * 旧实现的还原只有一条路：{@code GuixuHostFxRenderer.render(...)} 走到 {@code not_formed}
 * 那道闸门时调 {@code HostFxHide.restore}。而下面三种情况里 {@code render()} <b>一行都不会跑</b>：
 * <ol>
 *   <li>控制器方块被拆掉 ⇒ 方块实体没了 ⇒ BER 不再被调用；</li>
 *   <li>控制器的机器对象变成了别的东西（{@code no_machine} 早退）；</li>
 *   <li>区块卸载 / 玩家走远 / 换维度 ⇒ 这一帧根本轮不到它。</li>
 * </ol>
 * ⇒ 台账里记着"这些格被我们写成了 AIR"，却<b>没有任何人</b>去写回 ⇒ 世界里就是一堆空洞。
 * <p>本类把对账搬到 <b>client tick</b> 上：每
 * {@link HostFxHidePolicy#RECONCILE_TICKS} tick 跑一次 {@link HostFxHide#reconcile}，
 * 用"心跳超时"判定"我们已经看不见这台主机了" ⇒ 立刻按台账写回 + WARN。
 *
 * <h2>为什么是 tick 而不是渲染帧</h2>
 * tick 在<b>没有渲染</b>的场合也会跑（例如窗口最小化、F3 关闭渲染的极少数驱动行为），
 * 而渲染帧不会。用户要的是"世界不缺块"，那是 tick 层的事实。
 *
 * <h2>成本</h2>
 * 每秒一次；{@code HIDDEN} 为空时是一次 {@code isEmpty()} 判断。
 * 有台账时最多遍历台账（正常 ≈ 2300 格）+ 逐格一次 {@code getBlockState}（只在
 * "已经没有心跳"的那次才会走到还原分支）。按 20 tick 一次 ⇒ 平均 115 次/秒，可忽略。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HostFxHideTicker {

    private HostFxHideTicker() {}

    /** 上一次对账的 tick 计数（只用 tick 计数，不用墙钟 —— 停服/暂停时不该继续跑）。 */
    private static int ticks;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticks < HostFxHidePolicy.RECONCILE_TICKS) {
            return;
        }
        ticks = 0;
        try {
            final Minecraft mc = Minecraft.getInstance();
            final ClientLevel level = mc == null ? null : mc.level;
            if (level == null) {
                return;                          // 主菜单 / 加载中：没有世界可写
            }
            HostFxHide.reconcile(level, System.currentTimeMillis());
        } catch (Throwable t) {
            // tick 上抛异常会进 crash report；这里只记一次，绝不外抛
            ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] reconcile_failed err={}", t.toString());
        }
    }
}
