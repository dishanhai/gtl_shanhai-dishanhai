package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayForceTeleport;

import net.minecraft.server.level.ServerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 服务端：<b>倒流收尾后 250 ms 内，强制 {@code ServerEntity#sendChanges} 走绝对包</b>。
 *
 * <h2>1. 它修的是什么（取证：{@code temp/replay-finalpos/取证-记账.md}）</h2>
 * 解冻后客户端第一发位移会被"多叠一次倒流位移"（观测 1.7~2.4 格/次），因为<b>两个 base 不一致</b>：
 * <ul>
 *   <li>服务端 {@code positionCodec} 的 base 只在<b>真的发出位置包之后</b>才前移
 *       （<b>偏移 1019</b> 的 {@code if (bl4) setBase(vec3)}），而冻结期 {@code sendChanges}
 *       <b>一次都不跑</b>（{@code tickChunks} 被 {@code tick(haveTime,false)} 跳过，取证 F9）⇒ 停在解冻前；</li>
 *   <li>客户端 base 被我们收尾补发的 {@code ClientboundTeleportEntityPacket} 经
 *       {@code syncPacketPositionCodec} 按到了回放末（取证 F7）。</li>
 * </ul>
 * ⇒ 相对包解码成 {@code 2·P末 − P解冻前}。把 {@code teleportDelay} 抬到 401 就命中
 * <b>偏移 648-681</b> 的 {@code teleportDelay > 400} 闸 ⇒ 走 <b>偏移 838</b> 的绝对分支
 * ⇒ 客户端 base 对齐、服务端 base 在 <b>偏移 1019</b> 自愈。
 *
 * <h2>2. 🔴 本类只做三件事：判窗口、转发、计数</h2>
 * <ul>
 *   <li><b>不改原版逻辑</b>：目标方法是 {@code sendChanges} 的 {@code HEAD}，只抬一个计数器字段，
 *       不 {@code @Overwrite} / 不 {@code @Redirect} / 不 cancel / 不改返回值；</li>
 *   <li><b>只动一个字段</b>：{@code teleportDelay}（SRG {@code f_8522_}，{@code private int}）。
 *       它在<b>偏移 838 的绝对分支里被原版自己清回 0</b>（{@code teleportDelay = 0;}）⇒
 *       抬一次 = 发一次绝对包，<b>不需要我们回写</b>；</li>
 *   <li><b>窗口外零行为</b>：{@link ShanhaiReplayForceTeleport#active()} 为假就 {@code return}
 *       （每个实体每次 sendChanges 一次 volatile 读 + 一次 long 比较）。</li>
 * </ul>
 *
 * <h2>3. 🔴🔴 为什么<b>没有</b> {@code @Shadow}（本工程铁律，实测出来的）</h2>
 * mixin 类体内的 MC 成员引用在生成的 refmap 里<b>没有条目</b>，{@code @Shadow} 字段/方法<b>同样没有</b>
 * —— 见 {@code ShanhaiSetBlockWatchMixin} 类注释 §2 的构建产物实测
 * （同一份 refmap 里有 {@code setBlockState} 的映射、<b>没有</b>任何 {@code @Shadow} 字段的
 * {@code level → f_62776_}，而 tsrg 里那条映射"在"）。
 * 运行期类名是 SRG ⇒ 用 {@code @Shadow} 就是拿开发名 {@code teleportDelay} 去
 * {@code ServerEntity} 上找 {@code f_8522_} ⇒ <b>找不到</b> ⇒ 混入失败。
 *
 * <p>所以照本工程既有写法（{@code ShanhaiSetHealthGuardMixin} / {@code ShanhaiSetBlockWatchMixin}）：
 * <b>混入类只当转发壳</b> —— 只传 {@code (Object) this}，反射落在普通类
 * {@link ShanhaiReplayForceTeleport} 里（<b>候选名同时含 {@code teleportDelay} 与 {@code f_8522_}</b>）。
 * 顺带的好处：这条链<b>不依赖 refmap</b>，换映射表也不会静默失效。
 *
 * <h2>4. 为什么 {@code require = 1}</h2>
 * {@code sendChanges()} 在 1.20.1 必然存在（{@code javap -p} 实证：{@code public void sendChanges();}
 * 无重载，SRG {@code m_8533_}）⇒ 找不到就该<b>加载期当场炸</b>。
 * 静默不挂在这条线上是最坏的失败形态：日志上会和"已经修好了"长得一模一样。
 */
@Mixin(ServerEntity.class)
public abstract class ShanhaiServerEntityForceTeleportMixin {

    /**
     * 抬一次 {@code teleportDelay}（窗口内）。
     *
     * <p>{@code require = 1}：注入点必须命中（理由见类注释 §4）。
     *
     * @param ci 原版回调（不使用、不 cancel）
     */
    @Inject(method = "sendChanges", at = @At("HEAD"), require = 1)
    private void shanhai$forceAbsolute(CallbackInfo ci) {
        try {
            // (Object) this ⇒ 本类体内零 MC 成员引用（理由见类注释 §3）。
            if (!ShanhaiReplayForceTeleport.active()) {
                return;
            }
            ShanhaiReplayForceTeleport.forceTeleportDelay((Object) this);
            ShanhaiReplayForceTeleport.noteSent();
        } catch (Throwable ignored) {
            // 我们坏了 ⇒ 这一轮照原版发包（绝不因为一个计数器把服务端发包搞崩）
        }
    }
}
