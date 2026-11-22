package com.shanhai.fluid;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.reality.ShanhaiRealityFluidTable;
import com.shanhai.registry.ShanhaiRegistration;
import com.tterrag.registrate.util.entry.FluidEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 32 个「现实流体」占位流体的注册点：{@code shanhai:reality_fluid_1 … _32}。
 *
 * <h2>1. 照的是工程里既有的那条链（不是新基建）</h2>
 * 与 {@link ShanhaiFluids}（B2 批的 25 个流体）<b>同一条</b>：
 * 全工程唯一的 {@link ShanhaiRegistration#REGISTRATE}，调用它<b>继承自</b>
 * {@code AbstractRegistrate} 的 {@code fluid(...)}（{@code javap} 已核实存在这些重载：
 * {@code fluid(String, ResourceLocation, ResourceLocation, FluidBuilder$FluidTypeFactory)}）。
 * 每个 id 落进两个 Forge 原生注册表：
 * <pre>
 *   ForgeRegistries.FLUIDS        ← {@code shanhai:reality_fluid_N}（源 + 流动两态）
 *   ForgeRegistries.FLUID_TYPES   ← {@code shanhai:reality_fluid_N}（{@link ShanhaiRealityFluidType}）
 * </pre>
 *
 * <h2>2. 🔴 为什么这次必须显式 {@code noBlock() / noBucket()}</h2>
 * {@code FluidBuilder} 默认会给每个流体派生一个 {@code LiquidBlock} 与一个
 * {@code <id>_bucket} 桶物品（{@link ShanhaiFluids} 的类注释逐字写了这条：
 * {@code create(...)} 内部已串好 {@code defaultSource/defaultBlock/defaultBucket}）。
 * 而用户拍板的第一版范围是：<b>不做桶、不做流体方块、不进 JEI 独立条目、不生成到世界</b>。
 * ⇒ 这里把这两项关掉（{@code javap -c} 核实：{@code noBlock()/noBucket()} 只是把
 * {@code defaultBlock/defaultBucket} 置 {@code FALSE}，且在 {@code register()} 里被读取，
 * <b>不会</b>触发 {@code defaultBlock()} 那个 "Cannot set a default block…" 异常）。
 * <p>关掉之后的直接结果：注册表里<b>只有</b> FluidType + 源/流动两态流体，
 * 没有 LiquidBlock、没有 BucketItem ⇒ JEI / 创造栏不会多出 32 个桶；世界里也不会有方块形态。
 *
 * <h2>3. 贴图：注册期给的是"兜底"，真正显示的那张每次现读表</h2>
 * {@code fluid(id, still, flow, factory)} 里的两个 {@link ResourceLocation} 只是
 * {@code FluidBuilder} 的必填参数（默认工厂会用它们建贴图）。本类传的是<b>原版水</b>
 * （任何环境都存在），而真正的 still/flow 由
 * {@link com.shanhai.client.reality.ShanhaiRealityFluidClientExtensions} 每次渲染现读
 * {@link ShanhaiRealityFluidTable} ⇒ 玩家在面板里换贴图立刻生效。
 *
 * <h2>4. 显示名 / 颜色</h2>
 * 名字走 {@link ShanhaiRealityFluidType#getDescription()}（现读表），
 * 颜色走客户端扩展的 {@code getTintColor()}（现读表）。两者都<b>不</b>依赖 lang 文件。
 *
 * <h2>5. 注册时机</h2>
 * 本类的静态字段初始化即"把 builder 入列"。入口 {@link #init()} 由
 * {@code ShanhaiMod} 的构造器在 {@code ShanhaiRegistry.init()} 之后<b>只加一行</b>调用
 * （流体是 Forge 原生注册表，构造期入列即可，不受 GTCEu {@code GTRegistry} 冻结窗口约束 ——
 * 与 {@link ShanhaiFluids#init()} 同一个理由）。
 */
public final class ShanhaiRealityFluids {

    /** 占位流体个数（用户拍板：32）。 */
    public static final int SLOT_COUNT = ShanhaiRealityFluidTable.SLOT_COUNT;

    /** id 前缀。 */
    public static final String ID_PREFIX = ShanhaiRealityFluidTable.ID_PREFIX;

    /** 注册期兜底贴图（原版水；真正的贴图每次现读表）。 */
    private static final ResourceLocation FALLBACK_STILL = new ResourceLocation("minecraft", "block/water_still");
    private static final ResourceLocation FALLBACK_FLOW = new ResourceLocation("minecraft", "block/water_flow");

    /** 32 个流体的注册句柄（下标 = 槽位，0 基）。 */
    @SuppressWarnings("unchecked")
    public static final FluidEntry<ForgeFlowingFluid.Flowing>[] FLUIDS =
            (FluidEntry<ForgeFlowingFluid.Flowing>[]) new FluidEntry<?>[SLOT_COUNT];

    static {
        for (int i = 0; i < SLOT_COUNT; i++) {
            FLUIDS[i] = registerOne(i);
        }
    }

    private ShanhaiRealityFluids() {}

    /**
     * 触发本类静态初始化（进而把 32 个流体交给 REGISTRATE）。
     *
     * <p>与 {@link ShanhaiFluids#init()} 逐字同款：方法体为空，只为"显式触达类"而存在，
     * <b>不要在这里塞任何逻辑</b>。
     */
    public static void init() {
        // 静态字段初始化即注册。
    }

    /** 注册一个占位流体（源 + 流动两态 + 一份 FluidType；不带方块、不带桶）。 */
    private static FluidEntry<ForgeFlowingFluid.Flowing> registerOne(int slot) {
        final String id = ID_PREFIX + (slot + 1);
        return ShanhaiRegistration.REGISTRATE
                .fluid(id, FALLBACK_STILL, FALLBACK_FLOW,
                        (props, stillTex, flowTex) -> new ShanhaiRealityFluidType(props, slot))
                .noBlock()
                .noBucket()
                .register();
    }

    // ------------------------------------------------------------------ 查询（注册表填充之后才可用）

    /** 第 {@code slot}（0 基）个流体的注册句柄。 */
    public static FluidEntry<ForgeFlowingFluid.Flowing> entry(int slot) {
        return slot >= 0 && slot < SLOT_COUNT ? FLUIDS[slot] : null;
    }

    /** 第 {@code slot}（0 基）个流体本体；拿不到（注册没跑完）= {@code null}。 */
    public static Fluid fluid(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return null;
        }
        return ForgeRegistries.FLUIDS.getValue(ShanhaiRealityFluidTable.fluidId(slot));
    }

    /** 第 {@code slot}（0 基）个流体的 {@code FluidType}；拿不到 = {@code null}。 */
    public static ShanhaiRealityFluidType type(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return null;
        }
        // ⚠️ FLUID_TYPES 是 Forge 的"延迟注册表"（类型是 Supplier<IForgeRegistry<…>>）⇒ 必须先 .get()
        final net.minecraftforge.fluids.FluidType t =
                ForgeRegistries.FLUID_TYPES.get().getValue(ShanhaiRealityFluidTable.fluidId(slot));
        return t instanceof ShanhaiRealityFluidType ours ? ours : null;
    }

    /** 一行读数（开机日志用）。 */
    public static String statsLine() {
        return "fluids=" + SLOT_COUNT + " id_prefix=" + ShanhaiMod.MOD_ID + ":" + ID_PREFIX
                + " blocks=none buckets=none worldgen=none";
    }
}
