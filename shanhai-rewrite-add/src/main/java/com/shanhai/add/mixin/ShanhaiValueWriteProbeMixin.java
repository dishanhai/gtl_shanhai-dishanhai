package com.shanhai.add.mixin;

import com.shanhai.add.guard.ShanhaiMotionBrake;
import com.shanhai.add.guard.state.ShanhaiStateAuthority;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海 · <b>「别人在写玩家动量/位置的那一刻」的两个注入点</b>（动量一个<b>可取消</b>，位置一个只旁听）。
 *
 * <h2>🔴 为什么需要它（用户当场纠正过一次）</h2>
 * <pre>
 *   「主动拆代码谁叫你只抓伤害事件相关的了，你去抓所有代码」
 *   「昨天我说的那个主动检测并废掉敌方的代码，你到底能不能做」
 * </pre>
 * 轮询那一点上的栈全是<b>我们自己的帧</b>（{@code ServerTickEvent → 我们的类}，敌人的帧早就不在栈上了），
 * 只有在<b>原版被写的那一刻</b>抓，才拿得到 {@code examplemod.…#…} 那种第三方帧。
 *
 * <h2>两个注入点（都是<b>原版</b>类 {@code net.minecraft.world.entity.Entity}，javap 取证）</h2>
 * <pre>
 *   javap -p net.minecraft.world.entity.Entity
 *     public void setDeltaMovement(Vec3);            ← 动量唯一入口（addDeltaMovement / (DDD) 都汇到它）
 *     public void setDeltaMovement(double,double,double);
 *        0..15: 打包成 Vec3 ⇒ invokevirtual setDeltaMovement(Vec3)   ← 所以只挂 Vec3 那个就够
 *     public final void setPosRaw(double,double,double);             ← 位置唯一漏斗（final，override 不掉）
 *     public final void setPos(Vec3) → setPos(DDD) → setPosRaw(DDD)
 *     public void absMoveTo(DDD,FF) → absMoveTo(DDD) → setPos → setPosRaw
 *                                  ⇒ 换维度 / 传送 / 拉回全都过 setPosRaw
 * </pre>
 *
 * <h2>🔴 2026-11-08 之后：动量那个从「只旁听」变成「<b>先判、再决定要不要写</b>」</h2>
 * 现场日志（test2 13:41）给死了结论：我们<b>看见了、也写回旧速度了</b>（{@code restored=true}），
 * 用户还是被推 —— 因为写回发生在<b>下一拍</b>，这一拍的位置/击退包已经按 {@code after=(0.000, 2.000, 0.000)} 走了。
 * <pre>
 *   {@code @At("HEAD")}：那一刻字段里还是<b>旧值</b>，入参就是<b>这一次要写的新值</b>
 *   ⇒ Δv = 入参 − 旧值，正好是判据要的那个量（也是日志里 {@code dv=(x,y,z)} 那一格）
 *   ⇒ 判成"丢弃"就 {@code ci.cancel()}：<b>这次写入干脆不发生</b>（替写 {@code (0, keepY, 0)} 在
 *      {@link ShanhaiMotionBrake#onMotionWriteHead(Object, Object)} 里做，y 保留 ⇒ 不可能卡死）
 * </pre>
 * 判据、硬闸、日志字面量全在<b>纯 JDK</b> 的 {@code com.shanhai.add.guard.ShanhaiMotionBrakeRules} 里
 * （离线 {@code javac} 可单独编译并真跑，见 {@code temp\guard-brake\}）—— 本类<b>只转发</b>，一个判据都不写。
 *
 * <h2>🔴 诚实边界：有些写入<b>我们看不到</b></h2>
 * <b>直接写字段</b>（{@code putfield} / 反射 / 别人的 {@code @Shadow}）绕得开这两个方法。
 * 本工程 {@code temp\momentum\报告.md} 附录 A 就有实例：
 * {@code examplemod.util.EntityUtil#forceSetDeltaMovement} 在偏移 {@code 0–5} 直接
 * {@code putfield f_19827_}（= {@code Entity.deltaMovement}），<b>一次 {@code setDeltaMovement} 都不调</b>。
 * <p>这种写入：<b>拦得住</b>（值层判据与谁写的无关）、<b>抓不到栈</b>（没有调用，就没有帧）；
 * 而且 {@code HEAD} 这一层的"写入即丢"<b>对它们无效</b>（方法根本没被调用）。
 * ⚠️ 2026-11-08 13:41 那段日志里<b>没有</b> {@code violation_stack where=motion} 行
 * ⇒ 那只猫是不是走 {@code setDeltaMovement}，<b>尚未证实</b>（报告「没验到的」一节写明了）。
 *
 * <h2>两个钩子的分工</h2>
 * <table border="1">
 *   <tr><th>位置</th><th>钩子</th><th>做什么</th><th>改行为？</th></tr>
 *   <tr><td>{@code setDeltaMovement(Vec3)} 的 <b>HEAD</b></td><td>{@code shanhai$onMotionWriteHead}</td>
 *       <td>问 {@link ShanhaiMotionBrake}：这次要不要取消（丢弃）</td><td><b>会</b>（{@code ci.cancel()}）</td></tr>
 *   <tr><td>{@code setDeltaMovement(Vec3)} 的 <b>RETURN</b></td><td>{@code shanhai$onMotionWrite}</td>
 *       <td>留证据（{@code violation_stack where=motion}）</td><td>不会</td></tr>
 *   <tr><td>{@code setPosRaw(DDD)} 的 <b>HEAD</b></td><td>{@code shanhai$onPosWrite}</td>
 *       <td>留证据（{@code violation_stack where=pos}）</td><td>不会</td></tr>
 * </table>
 * <p>被判"丢弃"的那一次写入<b>不会</b>再走到 RETURN ⇒ 它的证据由 HEAD 那条路补
 * （{@code violation_stack where=motion_brake}，同样 1/s 限流）。
 *
 * <h2>本类体内零 Minecraft 成员引用（本工程铁律）</h2>
 * 成员引用在 refmap 里没有条目（见 {@code ShanhaiSetHealthGuardMixin} 类注释）；
 * 所以这里只有"类型对得上的形参"＋{@code (Object) this}，取值全在
 * {@link ShanhaiStateAuthority#noteMotionWrite(Object)} /
 * {@link ShanhaiStateAuthority#notePosWrite(Object, double, double, double)} /
 * {@link ShanhaiMotionBrake#onMotionWriteHead(Object, Object)} 里做。
 *
 * <p>⚠️ 目标类是<b>原版</b> {@code net.minecraft.world.entity.Entity}（不是第三方 mod）。
 */
@Mixin(Entity.class)
public abstract class ShanhaiValueWriteProbeMixin {

    /**
     * 动量<b>即将</b>被写（{@code Entity#setDeltaMovement(Vec3)} 的 HEAD）：<b>先判、再决定要不要写</b>。
     *
     * <p>这是本轮（「写入即丢」）唯一改变行为的注入点：返回 {@code true} ⇒ {@code ci.cancel()} ⇒
     * 原方法的字段赋值不执行；替写（{@code (0, keepY, 0)}）已经在 {@link ShanhaiMotionBrake} 里做完。
     *
     * <p>⚠️ 失败姿势：本方法体里 {@code catch (Throwable)} 一律吞掉并<b>放行</b> ——
     * 刹车坏掉只能是"这一次没刹"，绝不许让原版写入消失、也绝不许把 tick 带崩。
     *
     * @param deltaMovement 这一次要写进去的值（形参；只转发，不在这里读字段）
     * @param ci            可取消的回调（{@code cancellable = true}）
     */
    @Inject(
            method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void shanhai$onMotionWriteHead(Vec3 deltaMovement, CallbackInfo ci) {
        try {
            if (ShanhaiMotionBrake.onMotionWriteHead((Object) this, deltaMovement)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 刹车炸了 ⇒ 这次写入照原版走（"没刹"而不是"乱刹"）
        }
    }

    /**
     * 动量被写（{@code Entity#setDeltaMovement(Vec3)} 返回处）：<b>只留证据</b>，不改行为。
     *
     * @param deltaMovement 形参（只占位；真值由普通类读字段，因为本类体内不许引成员）
     * @param ci            原版回调（本类不改控制流、不改返回值）
     */
    @Inject(
            method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("RETURN"),
            require = 1)
    private void shanhai$onMotionWrite(Vec3 deltaMovement, CallbackInfo ci) {
        try {
            ShanhaiStateAuthority.noteMotionWrite((Object) this);
        } catch (Throwable ignored) {
            // 旁听失败只意味着"这一次没留下栈"，绝不许影响写入本身
        }
    }

    /**
     * 位置被写（{@code Entity#setPosRaw(DDD)} 的 HEAD）。
     *
     * @param x/y/z 这次要写进去的坐标（直接就是形参 ⇒ 零成员引用）
     * @param ci    原版回调
     */
    @Inject(
            method = "setPosRaw(DDD)V",
            at = @At("HEAD"),
            require = 1)
    private void shanhai$onPosWrite(double x, double y, double z, CallbackInfo ci) {
        try {
            ShanhaiStateAuthority.notePosWrite((Object) this, x, y, z);
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
