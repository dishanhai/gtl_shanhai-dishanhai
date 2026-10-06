package com.shanhai.client.renderer.machine;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import org.gtlcore.gtlcore.utils.RenderUtil;

import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.common.machine.PrimordialSphereStyle;
import com.shanhai.config.ShanhaiConfig;

import java.util.function.Consumer;

/**
 * 原始终焉引擎 TESR 渲染器。
 * <p>
 * 蒸汽时代轨道环由 {@link AbstractRingRenderer} 统一处理；中心球体按机器上的
 * {@link PrimordialSphereStyle} 分派给两套互斥的实现：
 * <ul>
 * <li>{@link PrimordialSphereStyle#UNIVERSE} → {@link PrimordialUniverseSphereRenderer}（鸿蒙微型宇宙）</li>
 * <li>{@link PrimordialSphereStyle#NEUTRON_STAR} → {@link PrimordialNeutronStarSphereRenderer}（伪神锻星体管线）</li>
 * </ul>
 * 两条分支的时钟语义不同：宇宙模式沿用「停机归零」的 smoothTick（行星停摆即可），
 * 中子星常驻可见必须用连续时钟，否则累积角度会在工作状态翻转时弹跳。
 */
public class PrimordialOmegaEngineRenderer extends AbstractRingRenderer {

    private static final ResourceLocation SPACE_MODEL = new ResourceLocation("gtlcore", "obj/space");
    private static final ResourceLocation STAR_MODEL = new ResourceLocation("gtlcore", "obj/star");

    public PrimordialOmegaEngineRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    @Override
    protected VertexBuffer[] getRingBuffers(MetaMachine machine) {
        return PrimordialOmegaEngineRingBuffer.getRingBuffers();
    }

    /**
     * 作废本渲染器持有的<b>两组静态 VBO</b>（环形轨道 + 中心球体/行星的 obj 模型）。
     *
     * <h2>为什么必须有这个入口（2026-10-04 贴图 bug）</h2>
     * 这两组 VBO 里存的是<b>已经烘进顶点里的图集 UV</b>，而资源包一重载，客户端就整张重建方块图集、
     * 并把 {@code ModelManager} 换成新实例（{@code Minecraft.reloadResourcePacks()}）⇒
     * 旧 UV 会去采到别的方块的贴图（用户实测：「结构件/控制器面上出现不属于它的贴图」），
     * 重启游戏才对。修法与取证逐条写在
     * {@link PrimordialOmegaEngineRingBuffer} 的类注释里。
     *
     * <p>调用方：{@code ShanhaiClientReloadInvalidator}（客户端资源重载监听器，跑在渲染线程）。
     * <p>⚠️ 环形那条路<b>还有一条不依赖事件注册的自愈路径</b>（见 {@code getRingBuffers()} 里
     * 比 {@code ModelManager} 实例身份那一步）—— 两条互为保险。
     */
    public static void invalidateBakedCaches() {
        PrimordialOmegaEngineRingBuffer.invalidate();
        PrimordialOmegaEngineModelBuffers.invalidate();
    }

    /**
     * 平滑 tick。<b>本方法是「机器在不在跑」进入渲染链的唯一入口</b>。
     *
     * <h2>🔴 「始终渲染为工作状态」开关就接在这里（2026-09-24 新增的第 7 个按钮）</h2>
     * 本方法的返回值一路决定两件事，见 {@link AbstractRingRenderer#render}：
     * <pre>
     *   :111  boolean isWorking = smoothTick &gt; 0;      ← ★唯一的"是否工作状态"推导点
     *   :114  renderAllRings(..., isWorking, ...)       → :149 if (isWorking) 轨道环是否转
     *   :117  renderSpecialEffects(..., isWorking, ...) → 本类 :72 传进
     *         PrimordialNeutronStarSphereRenderer.enqueue(...)
     *             :177 beamAlpha = isWorking ? BEAM_ALPHA : 0.0f   ← ★等离子体光束亮/灭
     * </pre>
     * ⇒ <b>只改这一处</b>（{@code isWorking() || poe.isStarAlwaysWorking()}），
     * <b>光束与轨道环同时常亮/常转</b>，语义与用户交办的「视觉上始终是工作状态」逐字对应。
     * <p>⚠️ <b>刻意不碰半径与脉动</b>：{@code PrimordialNeutronStarSphereRenderer} 的 {@code :304}
     * 与 {@code :320-321} 有明文注释说明它们<b>不按 isWorking 开关</b>（那会让球在停机瞬间硬切一次），
     * 所以本开关也不会让半径/脉动跟着跳 ⇒ 半径钳位（{@code STAR_RADIUS_MAX = 43}）与
     * {@code SAFE_MAX_STAR_RADIUS} 的 NaN 兜底逻辑一行未动。
     * <p>⚠️ 开关开着时返回的是 {@code RenderUtil.getSmoothTick(poe, partialTick)}（<b>连续时钟</b>），
     * 正因如此轨道环不会因为"机器停 ⇒ 值恒 0"而瞬间弹回基准角。
     */
    @Override
    protected float getSmoothTick(MetaMachine machine, float partialTick) {
        if (machine instanceof PrimordialOmegaEngineMachine poe
                && (poe.getRecipeLogic().isWorking() || poe.isStarAlwaysWorking())) {
            return RenderUtil.getSmoothTick(poe, partialTick);
        }
        return 0f;
    }

    @Override
    protected void renderSpecialEffects(MetaMachine machine, BlockEntity blockEntity,
                                        float smoothTick, boolean isWorking,
                                        Direction facing, float partialTick,
                                        PoseStack poseStack, MultiBufferSource buffer) {
        if (sphereStyleOf(machine) == PrimordialSphereStyle.NEUTRON_STAR) {
            // 中子星常驻可见，必须用连续时钟：smoothTick 停机归零会让三层球壳的累积角度瞬间弹回基准。
            // 🔴 2026-09-22 用户裁定：**颜色改随「物质模块专属槽」等级，不随时间** ⇒
            //    这里只传 moduleSlotBonusOf(machine) 一个源（与星体半径【同一个源】），
            //    原来的色源入参 runningSecsOf(machine) 已连字段一起删除。
            // 🔴 2026-09-22 第二次：新增两个【可选】手动覆盖（档 A）。默认（未开手动 / 槽等级不足 15）
            //    两个方法都返回哨兵值（半径 -1、色相 -1）⇒ 渲染路径与加面板之前【逐位相同】。
            // 🔴 2026-09-23 收口：再加【自动配色档】传参。默认 false = 原光谱 7 档
            //    （用户裁决「A · 默认用原来的 7 档」）⇒ 默认仍然与加面板之前逐位相同。
            // 🔴 2026-09-23 第二次：加【彩虹变化周期】传参。默认 0 = 不变化（用户拍板）
            //    ⇒ 不按那个按钮时颜色逐位不变；相位复用上面同一个连续时钟（没有第二口时钟）。
            PrimordialNeutronStarSphereRenderer.enqueue(
                    blockEntity, facing, RenderUtil.getSmoothTick(machine, partialTick), isWorking,
                    moduleSlotBonusOf(machine),
                    starRadiusOverrideOf(machine),
                    starHueOverrideOf(machine),
                    rainbowPaletteOf(machine),
                    rainbowPeriodOf(machine));
        } else {
            // 宇宙模式：一行都不动（N7 的尺寸只在中子星模式生效）。
            // 🔴 新增的中子星旋钮【结构上】进不到这里：本分支完全不读那几个字段，
            //    所以"面板不作用于宇宙模式"不需要任何 if —— 见 createMainPage 里的提示行。
            PrimordialUniverseSphereRenderer.render(smoothTick, facing, poseStack);
        }
    }

    /**
     * 手动半径覆盖（{@code ≤0} = 不覆盖，走等级的 {@code baseRadiusFor}）。
     *
     * <p>🔴 <b>门控集中在这一处</b>：三个条件（是本类主机 / 专属槽等级 ≥
     * {@code PrimordialOmegaEngineMachine.STAR_PANEL_MIN_MODULE_LEVEL} / 处于手动档）全部在这里判，
     * 渲染类只收到一个数，不重复判断。服务端 setter 里还有一道同样的闸门（防伪造包）。
     *
     * <p>客户端读的全是 {@code @DescSynced} 镜像 ⇒ 槽里的 64 个模块被抽走时，
     * 镜像的等级会变回 0，这里的覆盖立刻失效（<b>不留"幽灵外观"</b>）。
     */
    private static int starRadiusOverrideOf(MetaMachine machine) {
        if (!(machine instanceof PrimordialOmegaEngineMachine poe)) {
            return -1;
        }
        return poe.canControlStarRender() && poe.isStarRenderManual() ? poe.getStarRadiusOverride() : -1;
    }

    /** 手动色相覆盖（{@code <0} = 不覆盖，走自动配色档）。门控口径与 {@link #starRadiusOverrideOf} 完全一致。 */
    private static int starHueOverrideOf(MetaMachine machine) {
        if (!(machine instanceof PrimordialOmegaEngineMachine poe)) {
            return -1;
        }
        return poe.canControlStarRender() && poe.isStarRenderManual() ? poe.getStarHueOverride() : -1;
    }

    /**
     * 是否用<b>彩虹</b>自动配色档（{@code false} = <b>原光谱 7 档，默认</b>）。
     *
     * <p>三道门与另外两个覆盖值同款，外加一条"只有跟随等级档才有意义"：
     * 手动档的颜色来自色相环，配不配彩虹都一样 ⇒ 手动档直接返回 {@code false}（原光谱），
     * 这样"手动 + 之前开过彩虹"不会在退出手动档时留下意外效果（真值仍在机器字段里，没被清掉）。
     */
    private static boolean rainbowPaletteOf(MetaMachine machine) {
        if (!(machine instanceof PrimordialOmegaEngineMachine poe)) {
            return false;
        }
        return poe.canControlStarRender() && !poe.isStarRenderManual()
                && poe.getStarPalette() == PrimordialOmegaEngineMachine.STAR_PALETTE_RAINBOW;
    }

    /**
     * <b>彩虹变化周期</b>（tick；{@code ≤0} 或无门控 = 不变化）。
     *
     * <p>门控口径与 {@link #rainbowPaletteOf(MetaMachine)} <b>逐条相同</b>，
     * 而且<b>刻意在它之外再判一次"必须彩虹档"</b>：因为周期只对彩虹档有意义，
     * 光谱档即使字段里有值也必须回 {@code 0} —— 否则"玩家当初在彩虹档调过周期、
     * 后来切回光谱档"就会让默认档的渲染结果<b>与加这个功能之前不同</b>，
     * 那正是 2026-09-22「默认档颜色不许随时间变」这条裁定要禁止的事。
     *
     * <p>⚠️ 这里与 {@link #rainbowPaletteOf} 判同一个门控是<b>有意的少量重复</b>（4 行）：
     * 把 {@code rainbowPaletteOf} 改成返回 {@code int} 会让"两个方法并列、各管一个旋钮"
     * 的既有结构变味（且三个覆盖值方法的写法都是"一个方法一件事"）。
     * 重复的是<b>判断</b>不是<b>算法</b>，没有"两套规则漂移"的风险 —— 门控的唯一权威仍在
     * {@code PrimordialOmegaEngineMachine#canControlStarRender()} / {@code isStarRenderManual()}
     * / {@code getStarPalette()} 这三个方法上。
     */
    private static int rainbowPeriodOf(MetaMachine machine) {
        if (!(machine instanceof PrimordialOmegaEngineMachine poe)) {
            return PrimordialOmegaEngineMachine.STAR_RAINBOW_PERIOD_OFF;
        }
        return poe.canControlStarRender() && !poe.isStarRenderManual()
                && poe.getStarPalette() == PrimordialOmegaEngineMachine.STAR_PALETTE_RAINBOW
                ? poe.getStarRainbowPeriodTicks()
                : PrimordialOmegaEngineMachine.STAR_RAINBOW_PERIOD_OFF;
    }

    /**
     * ⛔ <b>【已删除 · 2026-09-22】原文留档</b> —— 这里原本是：
     * <pre>
     * private static long runningSecsOf(MetaMachine machine) {
     *     return machine instanceof PrimordialOmegaEngineMachine poe ? poe.getRunningSecs() : 0L;
     * }
     * </pre>
     * <b>作废原因</b>：用户裁定「不希望光束的颜色随着时间变化，而希望它随着物质模块的等级变化」
     * ⇒ 色源从运行时间换成 {@link #moduleSlotBonusOf(MetaMachine)}，
     * 而 {@code PrimordialOmegaEngineMachine#getRunningSecs()} 与其 {@code @Persisted @DescSynced} 字段
     * <b>已一并删除</b>（含整条 20-tick 同步链）⇒ 这个方法已无存在依据。
     * <p>🔴 颜色现在读的 {@link #moduleSlotBonusOf(MetaMachine)} <b>本来就存在</b>（星体半径一直在用它）
     * —— <b>没有新增任何色源专用链路</b>。

    /**
     * 主机「物质模块专属槽」的门控结果（{@code 0} = 未生效；{@code 1..17} = 生效等级）。
     *
     * <p>🔴 <b>唯一的口径来源</b>是 {@code PrimordialOmegaEngineMachine.moduleSlotBonus()}：
     * 本渲染器<b>不</b>自己读槽、不自己数 64、不自己查等级表。N3（产出倍率）与服务端走的是同一个方法。
     * 客户端拿到的值由服务端经 {@code @DescSynced} 推来（槽里的物品本身不同步 —— 见该方法的注释）。
     *
     * <p>非本类主机（理论上进不到这条分支）返回 0：宁可"不放大"，也不要凭猜测放大。
     */
    private static int moduleSlotBonusOf(MetaMachine machine) {
        return machine instanceof PrimordialOmegaEngineMachine poe ? poe.moduleSlotBonus() : 0;
    }

    private static PrimordialSphereStyle sphereStyleOf(MetaMachine machine) {
        PrimordialSphereStyle forced = clientForcedStyle();
        if (forced != null) return forced;
        return machine instanceof PrimordialOmegaEngineMachine poe
                ? poe.getSphereStyle()
                : PrimordialSphereStyle.UNIVERSE;
    }

    /**
     * 模组配置里的客户端显示覆盖；返回 null 表示 FOLLOW_MACHINE（听机器的）。
     * <p>
     * 用 COMMON spec 是刻意的：Forge 的 COMMON 不同步（各端各读各自 global config 目录下的 toml），
     * 多人环境下玩家改的就是自己那份，正好符合「只影响我自己画面」的语义。
     * <p>
     * isLoaded() 兜底不可省：配置未加载时 ConfigValue.get() 在开发环境会直接抛 IllegalStateException。
     * 逐帧调用无额外开销——get() 命中 ConfigValue 内部的 cachedValue，cloth 保存走的 set() 与配置重载
     * 都会自行失效该缓存，所以这里不需要再包一层本地缓存或挂 ModConfigEvent 监听。
     */
    private static PrimordialSphereStyle clientForcedStyle() {
        if (!ShanhaiConfig.COMMON_SPEC.isLoaded()) return null;
        return switch (ShanhaiConfig.COMMON.primordialSphereStyle.get()) {
            case UNIVERSE -> PrimordialSphereStyle.UNIVERSE;
            case NEUTRON_STAR -> PrimordialSphereStyle.NEUTRON_STAR;
            default -> null;
        };
    }

    @Override
    protected void registerAdditionalModels(Consumer<ResourceLocation> registry) {
        registry.accept(SPACE_MODEL);
        registry.accept(STAR_MODEL);
        registry.accept(new ResourceLocation("gtlcore", "obj/the_nether"));
        registry.accept(new ResourceLocation("gtlcore", "obj/overworld"));
        registry.accept(new ResourceLocation("gtlcore", "obj/the_end"));
    }
}
