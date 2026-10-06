package com.shanhai.client.event;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.renderer.machine.PrimordialOmegaEngineRenderer;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · <b>客户端资源重载时的渲染缓存作废点</b>（只管一件事）。
 *
 * <h2>1. 🔴 它修的是什么（2026-10-04 用户实测的贴图 bug）</h2>
 * 现象（用户原话）：
 * <blockquote>
 * 「每次我重新加载材质就会出现这样的材质错误，需要重启游戏才能正常，哪怕是重新加载了毫不相干的材质包，
 * 就比如刚才你的旧物质模块材质」；配图 = 多方块结构件/控制器面上出现<b>不属于它的贴图</b>
 * （绿色网格、蓝色同心圈之类）。
 * </blockquote>
 * 根因、判据与逐条取证写在 {@link PrimordialOmegaEngineRenderer#invalidateBakedCaches()} 与
 * {@code PrimordialOmegaEngineRingBuffer} 的类注释里（一句话：VBO 里存的是<b>烘死在图集上的 UV</b>，
 * 而重载会整张重建图集 ⇒ 旧 UV 采到别人的 sprite；重启会重烘 ⇒ 恢复正常）。
 *
 * <h2>2. 为什么挂在 {@code RegisterClientReloadListenersEvent}（Bus.MOD）</h2>
 * 它是 Forge 提供给 mod 的<b>唯一</b>"每次资源重载都会跑一次、且跑在渲染线程上"的钩子：
 * <ul>
 *   <li>{@code PreparableReloadListener.apply(…)} 由 {@code ReloadableResourceManager} 的
 *       <b>game executor</b>（= 渲染线程）执行 —— 判据是日志里每次重载都有的那一行
 *       {@code [Render thread/INFO] [TextureAtlas/]: Created: 16384x16384x4 …blocks.png-atlas}
 *       （图集上传就在同一批 apply 里）⇒ 在这里 {@code VertexBuffer.close()}（= {@code glDeleteBuffers}）是安全的；</li>
 *   <li>{@code Dist.CLIENT} + {@code Bus.MOD} 注解 ⇒ <b>无头专用服务端根本不加载本类</b>
 *       （与本工程其它客户端装配点同款，例如 {@code ShanhaiHaloClientSetup}）。</li>
 * </ul>
 *
 * <h2>3. 🔴 它<b>不是</b>唯一保险（刻意做成两条）</h2>
 * {@code PrimordialOmegaEngineRingBuffer.getRingBuffers()} 里还有一条<b>不依赖事件注册</b>的自愈判据
 * （比 {@code Minecraft.getModelManager()} 的实例身份，重载必换新实例）。
 * 理由：本工程的红线是「静默失效最贵」—— 监听器万一没注册上（事件总线写错、被别的 mod 吞掉），
 * 只留一条路就等于这个 bug 原样复发。两条路各自独立，任一条成立就能修。
 *
 * <h2>4. 判据（用户/日志侧怎么看出它真的生效了）</h2>
 * <ul>
 *   <li>启动时（注册那一刻）打一行 {@code [SHANHAI-RELOAD] …注册…} ——
 *       <b>有这行 = 监听器装上了</b>；没有 = 只能靠上面那条自愈路径；</li>
 *   <li>每次 load 资源包之后打一行 {@code [SHANHAI-RELOAD] cache_invalidated …} ——
 *       <b>有这行 = 本次重载确实作废过缓存</b>（这就是用户"load 一次包 → 贴图不再乱"那条验收
 *       在日志侧的对应读数）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiClientReloadInvalidator {

    /** 日志前缀 —— 用户验收时按这一个串过滤即可。 */
    public static final String PREFIX = "[SHANHAI-RELOAD]";

    private ShanhaiClientReloadInvalidator() {}

    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {

            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                // 没有需要异步准备的东西（只是丢几个引用），重活全部放在 apply（渲染线程）里。
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager resourceManager, ProfilerFiller profiler) {
                invalidateBakedCaches();
            }
        });
        ShanhaiMod.LOGGER.info("{} 客户端：资源重载监听器已注册（判据：本条在日志里 = 钩子已装上；"
                + "此后每次 load 资源包都会再打一行 cache_invalidated）", PREFIX);
    }

    /**
     * 作废所有"把图集 UV 烘死进顶点"的静态缓存。
     *
     * <p>异常一律吞掉并留一行 WARN：这一步失败的最坏后果是"回到重载前的错误贴图"，
     * 绝不该因为它把资源重载整条链带崩（本工程铁律：渲染侧算错要安静，但必须留痕）。
     */
    private static void invalidateBakedCaches() {
        try {
            PrimordialOmegaEngineRenderer.invalidateBakedCaches();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} 作废失败 ⇒ 保持原样（最坏＝贴图仍按旧图集采样，重启可恢复） ex={}",
                    PREFIX, t.getClass().getName());
            return;
        }
        ShanhaiMod.LOGGER.info("{} cache_invalidated：环形轨道 VBO ＋ 中心球体/行星 obj VBO 已作废，"
                + "下一次渲染用重载后的块图集重新烘焙", PREFIX);
    }
}
