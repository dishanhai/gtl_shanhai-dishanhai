package com.shanhai.fluid;

import com.shanhai.common.reality.ShanhaiRealityFluidTable;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;

import java.util.function.Consumer;

/**
 * 32 个「现实流体」的 {@link FluidType}（每个流体一份）。
 *
 * <h2>🔴 它为什么必须是自己的一段代码（而不是 Registrate 默认那个）</h2>
 * 用户要求三件事<b>都要热生效</b>：中文名 / tint 颜色 / still 贴图。
 * 这三项默认全是「注册期定死」的：
 * <pre>
 *   FluidType#getDescription()        → Component.translatable(descriptionId)   ← lang 文件，改不了
 *   IClientFluidTypeExtensions#…      → 由 FluidTypeFactory 在构造期塞进来的固定值
 * </pre>
 * ⇒ 本类把<b>显示名</b>改成「每次调用现读 {@link ShanhaiRealityFluidTable}」；
 * 客户端三项（tint / still / flowing）交给
 * {@code com.shanhai.client.reality.ShanhaiRealityFluidClientExtensions}（同样现读表）。
 *
 * <h2>🔴 为什么 {@code initializeClient} 里不能碰自己的字段</h2>
 * {@code FluidType} 的构造器<b>末尾</b>会调一次 {@code initClient()} → {@code initializeClient(...)}
 * （{@code javap -c} 实证：构造器偏移 152 是 {@code invokevirtual initClient}）。
 * 也就是说这个回调发生在<b>子类字段还没赋值</b>的时候 ⇒ 那时候读 {@code this.slot} 拿到的是 0，
 * 32 个流体全会指向同一个槽位。所以这里只把 {@code this} 递进去，槽位在<b>用的时候</b>
 * （渲染那一刻，字段早已就位）由 {@link #shanhaiSlot()} 现取。
 *
 * <h2>不做的（第一版）</h2>
 * 不带桶、不带流体方块、不生成到世界 —— 本类一个"行为"都不加，只改"长什么样"。
 */
public final class ShanhaiRealityFluidType extends FluidType {

    /** 槽位下标（0 基）。 */
    private final int slot;

    public ShanhaiRealityFluidType(FluidType.Properties properties, int slot) {
        super(properties);
        this.slot = slot;
    }

    /** 槽位下标（0 基）—— 客户端扩展在渲染时用它现读表。 */
    public int shanhaiSlot() {
        return this.slot;
    }

    /**
     * 显示名 —— <b>每次调用现读表</b>（改名字 ⇒ 下一次绘制就是新名字，不用 /reload、不用重启）。
     *
     * <p>没填名字时由 {@link ShanhaiRealityFluidTable#displayName(int)} 给出「id ＋（未命名）标记」。
     */
    @Override
    public Component getDescription() {
        return Component.literal(ShanhaiRealityFluidTable.displayName(this.slot));
    }

    /**
     * 带 stack 的那个重载 —— {@code FluidStack#getDisplayName()} 走的<b>就是这一条</b>
     * （{@code javap -c} 实证：{@code getFluid().getFluidType().getDescription(FluidStack)}），
     * 而 JEI / GTCEu 的流体名都从 {@code FluidStack#getDisplayName()} 来。
     */
    @Override
    public Component getDescription(FluidStack stack) {
        return getDescription();
    }

    /**
     * 描述键：给数据生成器（{@code .lang()}）与别的模组看的，<b>不是</b>显示名。
     * 这里固定成 {@code fluid_type.shanhai.reality_fluid_<n>}，不随表变（显示名走上面那个入口）。
     */
    @Override
    public String getDescriptionId() {
        return "fluid_type." + com.shanhai.ShanhaiMod.MOD_ID + "."
                + ShanhaiRealityFluidTable.ID_PREFIX + (this.slot + 1);
    }

    @Override
    public String getDescriptionId(FluidStack stack) {
        return getDescriptionId();
    }

    /**
     * 客户端三项（tint / still / flowing）的接线口。
     *
     * <p>⚠️ 它在<b>超类构造器</b>里被调用（见类注释），所以：
     * <ul>
     *   <li><b>不许</b>在这里读 {@code this.slot}（那时还是 0）—— 只递 {@code this}；</li>
     *   <li>传进去的必须是<b>另一个对象</b>：{@code FluidType} 内部那个 lambda 会检查
     *       "accepted == this" 并当场抛 {@code IllegalStateException}
     *       （{@code javap -c: if_acmpne 16 / new IllegalStateException}）。</li>
     * </ul>
     * 服务端不会走这里（{@code initClient} 里先判 {@code Dist.CLIENT}）。
     */
    @Override
    public void initializeClient(
            Consumer<net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions> consumer) {
        consumer.accept(new com.shanhai.client.reality.ShanhaiRealityFluidClientExtensions(this));
    }
}
