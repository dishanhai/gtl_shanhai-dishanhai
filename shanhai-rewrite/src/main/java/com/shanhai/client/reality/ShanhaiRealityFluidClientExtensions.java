package com.shanhai.client.reality;

import com.shanhai.common.reality.ShanhaiRealityFluidTable;
import com.shanhai.fluid.ShanhaiRealityFluidType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * 32 个「现实流体」的<b>客户端渲染三件套</b>：tint 颜色 / still 贴图 / flowing 贴图。
 *
 * <h2>🔴 为什么是"每次调用现读表"</h2>
 * 用户要求「改颜色 / 改贴图 ⇒ 立刻能看到，不用 /reload、不用重启」。这三项默认在
 * {@code FluidType} 构造期就定死了（{@code FluidTypeFactory} 把它们塞进渲染属性），
 * 所以这里三个方法<b>一个字段都不缓存</b>，每次都问
 * {@link ShanhaiRealityFluidTable}（它返回的是不可变快照，改表 = 换引用，渲染线程安全）。
 *
 * <h2>🔴 它是在 {@code FluidType} 构造器里被 new 出来的</h2>
 * 见 {@link ShanhaiRealityFluidType} 的类注释：那时子类字段还没赋值，
 * 所以本类<b>不能</b>在构造器里问槽位号；它只保存 {@code type} 引用，
 * 三个 getter 在<b>渲染时</b>才调 {@link ShanhaiRealityFluidType#shanhaiSlot()}。
 *
 * <h2>为什么单独一个文件</h2>
 * {@code IClientFluidTypeExtensions} 是客户端接口，放 {@code fluid} 包会让"专服也会加载的
 * 注册类"直接挂上客户端类型；本类只由客户端那条回调触达（服务端 {@code initClient()} 先判 dist）。
 */
public final class ShanhaiRealityFluidClientExtensions implements IClientFluidTypeExtensions {

    private final ShanhaiRealityFluidType type;

    public ShanhaiRealityFluidClientExtensions(ShanhaiRealityFluidType type) {
        this.type = type;
    }

    /** 颜色（ARGB）。默认 {@code 0xFFFFFFFF} = 原样显示贴图颜色。 */
    @Override
    public int getTintColor() {
        return ShanhaiRealityFluidTable.tint(this.type.shanhaiSlot());
    }

    /** still 贴图（{@code ns:path}，不带 {@code textures/} 与 {@code .png}）。 */
    @Override
    public ResourceLocation getStillTexture() {
        return ShanhaiRealityFluidTable.stillTexture(this.type.shanhaiSlot());
    }

    /** flowing 贴图（同上）。 */
    @Override
    public ResourceLocation getFlowingTexture() {
        return ShanhaiRealityFluidTable.flowTexture(this.type.shanhaiSlot());
    }
}
