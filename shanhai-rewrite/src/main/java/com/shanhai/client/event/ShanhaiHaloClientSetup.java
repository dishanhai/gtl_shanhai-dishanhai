package com.shanhai.client.event;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.render.halo.HaloEndModel;
import com.shanhai.client.render.halo.HaloEndRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 终末之环的<b>客户端</b>装配点（只跑在客户端；专用服务端不会加载本类）。
 *
 * <h2>这里做三件事，每件都有自己的守卫/理由</h2>
 * <ol>
 *   <li>{@code RegisterLayerDefinitions}：注册 {@link HaloEndModel#LAYER_LOCATION} 的模型层。
 *       这一步<b>不需要</b> Curios —— 模型与贴图是纯客户端资源，Curios 不在也照样注册
 *       （少一层可选依赖耦合；且"Curios 不在 ⇒ 连模型都没烘出来"会让将来排查变复杂）。</li>
 *   <li>{@code FMLClientSetupEvent}：Curios 在 → 注册 {@code ICurioRenderer}；
 *       Curios 不在 → 静默跳过并<b>留一行日志</b>（"静默不发生"必须能与"出故障"区分开）。</li>
 *   <li>{@code FMLClientSetupEvent}：<b>YSM 在（且 Curios 在）</b>时才把
 *       {@code RenderLivingEvent.Post} 挂到 FORGE 总线。<b>不在场就根本不注册这个监听器</b>
 *       —— 去重理由见 {@link HaloYsmFallback}。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiHaloClientSetup {

    public static final String PREFIX = "[SHANHAI-HALO]";

    private static final String CURIOS_MODID = "curios";
    private static final String YSM_MODID = "yes_steve_model";

    private ShanhaiHaloClientSetup() {}

    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(HaloEndModel.LAYER_LOCATION, HaloEndModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        boolean curiosLoaded = ModList.get().isLoaded(CURIOS_MODID);
        boolean ysmLoaded = ModList.get().isLoaded(YSM_MODID);

        if (curiosLoaded) {
            HaloEndRenderer.register();
            ShanhaiMod.LOGGER.info("{} 客户端：已注册终末之环的 Curios 渲染器（贴图 {}）",
                    PREFIX, HaloEndRenderer.HALO_TEX);
        } else {
            ShanhaiMod.LOGGER.info("{} 客户端：Curios 未加载 ⇒ 不注册饰品渲染器（模型层照常注册，无副作用）", PREFIX);
        }

        // 🔴 只要 Curios 在场就挂这个 Post 监听器（本轮口径变更，理由如下）：
        //    ① 它是【自算姿势】这条路径的唯一入口，而自算姿势路径可以由用户在
        //       shanhai-client.toml 里用 poseMode=SELF_POSE 强制启用 —— 那时即便 YSM 不在场
        //       也必须能画（例如原版渲染管线被别的 mod 换掉的情况）；
        //    ② AUTO 模式下它仍然只做两件事：打一行"谁在画这个玩家"的日志（判据），
        //       以及在"渲染器不是原版 PlayerRenderer"时才补画 —— 原版管线下一律提前 return，
        //       不会与 CuriosLayer 双画。
        //    Curios 不在 ⇒ 物品根本戴不上 ⇒ 这条路径没有任何意义，仍然跳过。
        if (curiosLoaded) {
            MinecraftForge.EVENT_BUS.addListener(HaloYsmFallback::onRenderLivingPost);
            ShanhaiMod.LOGGER.info("{} 客户端：已挂上 RenderLivingEvent.Post 路径（YSM={}；"
                            + "该监听器在 AUTO 模式下遇到原版 PlayerRenderer 会立即返回，不会双画）",
                    PREFIX, ysmLoaded);
        } else {
            ShanhaiMod.LOGGER.info("{} 客户端：Curios={} ⇒ 不挂 RenderLivingEvent.Post 路径"
                            + "（物品戴不上，画了也没意义）", PREFIX, false);
        }
    }
}
