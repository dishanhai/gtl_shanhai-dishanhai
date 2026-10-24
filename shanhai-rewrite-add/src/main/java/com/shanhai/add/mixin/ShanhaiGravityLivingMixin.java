package com.shanhai.add.mixin;

import com.shanhai.add.gravity.ShanhaiGravity;
import com.shanhai.add.gravity.ShanhaiGravityLiving;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海 · 全量调制重力【注入点 1/17】{@code LivingEntity}（生物整条轨）。
 *
 * <h2>为什么挂在 {@code LivingEntity} 上就覆盖了"所有生物"</h2>
 * 全 jar 里重力只有这一条走 Forge 属性：{@code LivingEntity#travel} 里
 * {@code double d0 = gravity.getValue();}（47.3.0 sources 第 2027 行），然后
 * 第 2143 行 {@code d2 -= d0;} 施加。玩家 / 所有怪 / 动物 / 村民 / 盔甲架 / 被骑的马猪
 * 全都继承这一条；{@code Player#travel} 无条件 {@code super.travel(...)}，{@code LocalPlayer} 根本不覆写
 * ⇒ <b>客户端预测与服务端权威走同一条公式</b>（这是不橡皮筋的前提）。
 *
 * <h2>三处注入</h2>
 * <table border="1">
 *   <tr><th>#</th><th>位置</th><th>做什么</th><th>理由</th></tr>
 *   <tr><td>1a</td><td>{@code travel} 的 {@code HEAD}</td>
 *       <td>把当前倍率落到 {@code forge:entity_gravity} 的 MULTIPLY_TOTAL modifier 上</td>
 *       <td>HEAD 是<b>最稳</b>的注入点（不靠局部变量槽位、不抢别人的调用）；
 *           乘的是属性最终值 ⇒ 水/岩浆({@code -d0/4})/鞘翅({@code d0*(-1+0.75*cos)})
 *           这些<b>共用 d0 的分支自动跟着缩放</b>，不用给它们各写一处</td></tr>
 *   <tr><td>1b</td><td>{@code &lt;clinit&gt;} 里 {@code -0.07D} 那个常量</td>
 *       <td>-0.07 → -0.875（{@code ShanhaiGravity.slowFallingAmount} 换算）</td>
 *       <td>缓降原版是<b>加法</b> modifier（"Add -0.07 to 0.08 so we get the vanilla default of 0.01"）。
 *           加法与"再乘 G"混在一起时 G=0 会留下 -0.07（缓降变"缓升"）。
 *           换成乘法后 {@code value = 0.08 × 0.125 × G}：G=1 ⇒ 0.01（原版）、G=0 ⇒ 0、G=-1 ⇒ -0.01</td></tr>
 *   <tr><td>1c</td><td>{@code &lt;clinit&gt;} 里 {@code Operation.ADDITION} 那个 getstatic</td>
 *       <td>ADDITION → MULTIPLY_TOTAL</td>
 *       <td>1b 只换数字不换操作的话，-0.875 被当成加法直接加到 0.08 上（-0.795，灾难）。
 *           两个必须成对改</td></tr>
 * </table>
 *
 * <h2>🔴 本类体内<b>没有一处</b> Minecraft 成员引用</h2>
 * 只有形参/返回值的<b>类型</b>（{@code Vec3} / {@code AttributeModifier.Operation}）、{@code (Object) this}、
 * 以及对普通类（{@code ShanhaiGravity*}，会被正常 reobf）的静态调用。
 * 理由：mixin 类里的 MC 成员引用在 refmap 里没有条目（见 {@code ShanhaiSetHealthGuardMixin} 类注释），
 * 生产环境会拿开发名去 SRG 命名的类上找 ⇒ {@code NoSuchMethodError}。
 *
 * <h2>为什么全部 {@code require = 1}</h2>
 * 目标方法与常量在 1.20.1（47.4.16）上必然存在（普查报告 §2/§3 有文件:行级取证）。
 * 对不上就该<b>启动当场崩</b>，而不是"静默不生效、看起来像已经改了重力"—— 静默失效在这条线上是最坏的失败形态。
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiGravityLivingMixin {

    /**
     * 1a：每次 {@code travel} 之前，把倍率同步到这只生物的 {@code forge:entity_gravity} 上（幂等）。
     *
     * <p>注入在 HEAD 的理由：{@code travel} 的第一件事就是算 {@code d0}，必须在它之前写好；
     * 而且 HEAD 不依赖任何局部变量的槽位，跨版本/跨编译器最稳。
     */
    @Inject(method = "travel", at = @At("HEAD"), require = 1)
    private void shanhai$applyGravityMultiplier(Vec3 movement, CallbackInfo ci) {
        ShanhaiGravityLiving.apply((Object) this);
    }

    /**
     * 1b：把缓降那条 modifier 的量从 -0.07（加法）换成 -0.875（乘法的等价值）。
     *
     * <p>为什么只改常量、且用 {@link ShanhaiGravity#slowFallingAmount} 反推而不是写死 -0.875：
     * 换算是 {@code (0.08 + 原值)/0.08 - 1}，写清楚推导，别人一眼能看懂为什么是 -0.875；
     * 上游若把 -0.07 改成别的数，这里也跟着对（G=1 仍然等于原版）。
     */
    @ModifyConstant(method = "<clinit>", constant = @Constant(doubleValue = -0.07D), require = 1)
    private static double shanhai$slowFallingAmount(double vanilla) {
        return ShanhaiGravity.slowFallingAmount(vanilla);
    }

    /**
     * 1c：把那条 modifier 的操作类型从 ADDITION 换成 MULTIPLY_TOTAL（与 1b 成对）。
     *
     * <p>目标是 {@code new AttributeModifier(SLOW_FALLING_ID, "Slow falling acceleration reduction",
     * -0.07, Operation.ADDITION)} 里读 {@code ADDITION} 的那一条 {@code getstatic}；
     * {@code <clinit>} 里 {@code ADDITION} 只出现这一次（另一条 SPEED_MODIFIER_SPRINTING 用的是
     * MULTIPLY_TOTAL），所以不需要 ordinal。
     *
     * <p>返回类型必须是 {@code AttributeModifier.Operation}（与 getstatic 的字段类型一致），
     * 值由普通类给 ⇒ 本类体内零 MC 成员引用。
     */
    @Redirect(
            method = "<clinit>",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;ADDITION:"
                            + "Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;"),
            require = 1)
    private static AttributeModifier.Operation shanhai$slowFallingOperation() {
        return ShanhaiGravityLiving.SLOW_FALLING_OPERATION;
    }
}
