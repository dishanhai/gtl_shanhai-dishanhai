package com.shanhai.common.compat;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 终末之环（{@code shanhai:halo_end}）的 <b>Curios 运行时注册入口</b>（common 侧）。
 *
 * <h2>为什么必须走「运行时注册」而不是 {@code implements ICurioItem}</h2>
 * 物品类直接 {@code implements ICurioItem} 会把 Curios 变成<b>硬依赖</b>：
 * 常量池里出现 {@code top/theillusivec4/curios/...} ⇒ Curios 不在时类初始化/校验阶段
 * 直接 {@code NoClassDefFoundError}，<b>整个 mod 加载失败</b>（不是"功能不生效"）。
 * 本项目 {@code mods.toml} 里<b>没有</b> curios 依赖声明（见报告判据 4），
 * 所以必须让「碰 Curios 类型的代码」在 Curios 缺席时<b>根本不被加载</b>。
 *
 * <h2>两道分离（这是本功能唯一容易出事故的地方）</h2>
 * <ol>
 *   <li><b>本类</b>只做「守卫 + 转发」，常量池里<b>不出现</b>任何 Curios 类型；</li>
 *   <li>{@link HaloCurioBridge} 是 common 侧<b>唯一</b>引用 Curios 类型的类，
 *       它只在 {@link ModList#isLoaded(String)} 为真之后才被 {@code invokestatic} 触达。</li>
 * </ol>
 * ⇒ 「Curios 不在时不会崩」这句话可以逐类核对：把本类与 {@link HaloCurioBridge} 分别
 * {@code javap -c} 一次，前者常量池无 curios，后者有但不可能被调到。
 *
 * <h2>为什么是 {@code FMLCommonSetupEvent}</h2>
 * {@code CuriosApi.registerCurio(Item, ICurioItem)} 只是往
 * {@code CuriosImplMixinHooks.REGISTRY}（一张静态 {@code Map}）里放一条记录，
 * 不碰任何注册表 ⇒ 只要物品<b>已经构造出来</b>就能注册。CommonSetup 时物品注册已完成
 * （{@code ShanhaiRegistry.init()} 在 mod 构造期入列、CommonSetup 在所有注册表冻结之后）。
 * 客户端渲染器的注册则是另一条路（{@code FMLClientSetupEvent}），见
 * {@code com.shanhai.client.event.ShanhaiHaloClientSetup}。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HaloCurioRegistrar {

    /** 可 grep 的前缀：成功与失败各打一行，且<b>互斥</b>（"静默不发生"必须能与"出故障"区分开）。 */
    public static final String PREFIX = "[SHANHAI-HALO]";

    /** 槽位归属的承载方式：物品标签 {@code curios:curio}，见本包 {@code resources/data/curios/tags/items/curio.json}。 */
    private static final String CURIOS_MODID = "curios";

    private HaloCurioRegistrar() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // 🔴 守卫必须在本类内、且在触达 HaloCurioBridge 之前。
        if (!ModList.get().isLoaded(CURIOS_MODID)) {
            ShanhaiMod.LOGGER.info("{} Curios 未加载 ⇒ 终末之环的饰品槽功能静默跳过（游戏照常，不影响其它功能）",
                    PREFIX);
            return;
        }
        try {
            HaloCurioBridge.register();
            ShanhaiMod.LOGGER.info("{} 终末之环已注册为 Curios 饰品：任意槽（canEquip 恒 true）"
                    + "；槽位归属靠物品标签 curios:curio", PREFIX);
        } catch (Throwable t) {
            // 注意：这里【吞掉】异常是刻意的 —— 一个彩蛋功能不许把整局游戏拖下水。
            // 但必须留痕（否则"没生效"与"没注册"在日志上长得一样）。
            ShanhaiMod.LOGGER.error("{} 终末之环注册 Curios 饰品失败（本功能不生效，其余功能不受影响）", PREFIX, t);
        }
    }
}
