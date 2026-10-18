package com.shanhai.add.gravity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.common.ForgeMod;

import java.util.UUID;

/**
 * 生物（玩家 / 怪 / 动物 / 坐骑 / 盔甲架）那条轨的落点 —— 唯一会碰 Minecraft 类的地方。
 *
 * <h2>为什么用 {@code forge:entity_gravity} 的 modifier，而不是"改 {@code travel} 里的局部变量"</h2>
 * <ol>
 *   <li><b>入口唯一且最稳</b>：{@code LivingEntity#travel} 的物理段被
 *       {@code isControlledByLocalInstance()} 门控，本地玩家/自己骑的坐骑<b>客户端也跑</b>
 *       ⇒ 只要两端拿同一个 G，客户端预测与服务端权威<b>走同一条公式</b>，不会分叉（不分叉 = 不橡皮筋）。</li>
 *   <li><b>不碰局部变量</b>：注入点只是 {@code travel} 的 {@code HEAD}（永远能命中），
 *       不需要 {@code @ModifyVariable} 去猜 {@code d0} 落在第几号槽位、也不需要 {@code @Redirect}
 *       去抢 {@code AttributeInstance#getValue()} 那一条调用（那两种写法都容易和别的 mod 打架，
 *       一打架就是启动崩）。</li>
 *   <li><b>自动两端一致</b>：这条属性是 {@code setSyncable(true)} 的（ForgeMod），
 *       我们加上去的 modifier 会被 {@code ServerEntity#sendDirtyEntityData} 发
 *       {@code ClientboundUpdateAttributesPacket} 同步给客户端（含玩家自己，
 *       {@code broadcastAndSend} 会额外发给 {@code ServerPlayer} 本人）⇒ 客户端拿到的重力值
 *       是<b>服务端算出来的那个</b>，不是客户端自己猜的。</li>
 * </ol>
 *
 * <h2>数学（G = 倍率）</h2>
 * <pre>
 *   原版：value = base + ΣADDITION  = 0.08（缓降时 0.08-0.07 = 0.01）
 *   我们：value = (base + ΣADDITION) × (1 + (G-1)) = 原版 × G
 *   ⇒ G=1 时 amount=0（等于没加，逐字原版）；G=0 时 amount=-1 ⇒ 0；G=-1 时 amount=-2 ⇒ -0.08
 * </pre>
 * 乘的是<b>整条属性值</b>（含缓降那条），而缓降已被改成乘法 0.125（见 {@code LivingEntity} 的
 * {@code <clinit>} 注入），所以 {@code 0.08 × 0.125 × G} 恒成立：
 * G=1 ⇒ 0.01、G=0 ⇒ 0、G=-1 ⇒ -0.01（不会变成"缓升 -0.07"）。
 *
 * <h2>为什么只在 {@code travel} 的 HEAD 里懒应用</h2>
 * 每 tick 每个生物都会走 {@code travel} ⇒ 新刷出来的怪、刚加载的区块里的生物
 * <b>第一次 tick 就自动带上倍率</b>，不需要"登录/刷怪事件"去补。
 * 已经是目标值的时候一个字节都不写（{@code cur.amount == G-1} 直接 return）⇒ 不会每 tick 把属性标脏、
 * 不会刷屏发属性包。
 */
public final class ShanhaiGravityLiving {

    /** 我们自己那条 modifier 的固定 UUID（客户端/服务端同一条，才不会互相顶掉）。 */
    public static final UUID MODIFIER_ID = UUID.fromString("7E1B0C3A-5D2F-4A58-9C61-8F4B2D6E0A17");

    /** modifier 名字（只用于调试显示）。 */
    public static final String MODIFIER_NAME = "shanhai gravity multiplier";

    /** 缓降那条 modifier 要换成的操作类型（原版是 ADDITION，我们换成等价乘法）。 */
    public static final AttributeModifier.Operation SLOW_FALLING_OPERATION =
            AttributeModifier.Operation.MULTIPLY_TOTAL;

    private static boolean warnedNoAttribute;
    private static int errorLogged;

    private ShanhaiGravityLiving() {}

    /**
     * 把当前倍率落到一只生物的 {@code forge:entity_gravity} 上（幂等，按需写）。
     *
     * @param entity {@code travel} 里的 {@code (Object) this}（mixin 体内不许引用 MC 成员，
     *               所以传 Object 过来在这里转）
     */
    public static void apply(Object entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        try {
            final AttributeInstance gravity = living.getAttribute(ForgeMod.ENTITY_GRAVITY.get());
            if (gravity == null) {
                // 极少数生物用了自定义属性表、没有这条属性 ⇒ 它这条轨不归我们管（记一次，不刷屏）
                if (!warnedNoAttribute) {
                    warnedNoAttribute = true;
                    com.shanhai.ShanhaiMod.LOGGER.warn(
                            "{} living_without_entity_gravity class={}（这只生物不会有重力倍率）",
                            ShanhaiGravity.PREFIX, living.getClass().getName());
                }
                return;
            }
            final double mult = ShanhaiGravity.get();
            final AttributeModifier current = gravity.getModifier(MODIFIER_ID);
            if (mult == ShanhaiGravity.VANILLA) {
                if (current != null) {
                    gravity.removeModifier(MODIFIER_ID);
                }
                return;
            }
            final double want = mult - 1.0D;      // MULTIPLY_TOTAL：value = 原值 × (1 + want) = 原值 × G
            if (current != null && Double.compare(current.getAmount(), want) == 0) {
                return;                            // 已经是这个倍率 ⇒ 一个字节都不写（不标脏、不发包）
            }
            if (current != null) {
                gravity.removeModifier(MODIFIER_ID);
            }
            gravity.addTransientModifier(
                    new AttributeModifier(MODIFIER_ID, MODIFIER_NAME, want,
                            AttributeModifier.Operation.MULTIPLY_TOTAL));
        } catch (Throwable t) {
            // 我们坏了也绝不能改变"生物的 travel"这件原版行为；
            // 而且这里每 tick 每生物都会走一次 ⇒ 日志只许打前几次，否则就是刷屏卡死（本工程血账）
            if (errorLogged < 5) {
                errorLogged++;
                com.shanhai.ShanhaiMod.LOGGER.error("{} apply_failed entity={} err={}（同类只报前 5 次）",
                        ShanhaiGravity.PREFIX, entity.getClass().getName(), t.toString());
            }
        }
    }
}
