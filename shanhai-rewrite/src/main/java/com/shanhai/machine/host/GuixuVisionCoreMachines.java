package com.shanhai.machine.host;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.shanhai.registry.ShanhaiRegistration;

import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 归墟视界枢核（{@code shanhai:guixu_vision_core}）· 注册入口。
 *
 * <h2>时机</h2>
 * 由 {@code ShanhaiMachines#init()} 调用 —— 那是 GTCEu 的 MachineDefinition {@code RegisterEvent}
 * 里，<b>不是</b> mod 构造期（构造期注册会炸在 {@code registry gtceu:compass_section has been frozen}，
 * 详见 {@code ShanhaiRegistry} 类注释）。本类另有 {@link AtomicBoolean} 幂等守卫。
 *
 * <h2>注册了什么</h2>
 * <ul>
 *   <li>方块 + 物品 + BlockEntity 同 ID：{@code shanhai:guixu_vision_core}（显示名走 lang
 *       {@code block.shanhai.guixu_vision_core} = 归墟视界枢核 / Guixu Vision Core）；</li>
 *   <li>{@code RotationState.NON_Y_AXIS} —— GTCEu 只允许 4 个水平朝向（用户定："朝向必须水平"），
 *       造型的正位是朝西（控制室玻璃朝 -X 看黑洞）；</li>
 *   <li>结构图案 = {@link GuixuVisionCoreStructure}（读我们自己的 {@code .bin}，见那个类的注释）。</li>
 * </ul>
 *
 * <h2>当前进度（2026-10-23）</h2>
 * <ul>
 *   <li>✅ 能摆出来、能成型、24 个槽位能识别并回连（第一轮）；</li>
 *   <li>✅ <b>能量双仓</b>：结构同时收能源仓与动力仓（{@link GuixuVisionCoreStructure#ringBandPredicate()}），
 *       抬头分两行画两侧存量（{@link GuixuVisionCoreMachine#addDisplayText}）；</li>
 *   <li>✅ <b>材料增产</b>（主机那套 + 5）与 <b>耗能减免</b>（同款）：整条链 =
 *       {@link GuixuVisionCoreMachine#applyGuixuRecipeModifier}（已挂在本定义的 {@code .recipeModifier}）；</li>
 *   <li>✅ <b>始终渲染为工作状态</b>：基类那个侧栏开关 + {@link GuixuVisionCoreMachine#isActive()} 覆写；</li>
 *   <li>⛔ <b>还没做</b>：三个自有配方类型（视界物质解压／视界能流抽取／原初范式简化 —— 用户明确
 *       「id 等他写配方时定」）、<b>模块等级复写</b>（需要在模块侧加等级读点，等用户点头）、
 *       并行数的玩家覆盖、自己的外壳贴图与 overlay。</li>
 * </ul>
 *
 * <h2>⚠️ 一个临时挂载（必须知道）</h2>
 * {@code .recipeTypes(GTRecipeTypes.ASSEMBLER_RECIPES)} 是<b>占位</b>，不是本机的正式配方类型。
 * 原因：{@code WorkableMultiblockMachine#getRecipeType()} 的实现是
 * {@code recipeTypes[activeRecipeType]}（反编译实证：没有越界检查），
 * 而 {@code RecipeLogic} 每 tick 都会走这条路 ⇒ <b>recipeTypes 为空数组会在首次 tick 抛
 * ArrayIndexOutOfBoundsException</b>。本机本阶段<b>一个部件仓都没有</b>（图案里没挂任何
 * {@code PartAbility}），所以它捡不到任何输入、只会待机 —— 挂一个真类型是"能跑起来"的最小代价。
 * 阶段 3 把这里换成三个自有类型（视界物质解压／视界能流抽取／原初范式简化）。
 */
public final class GuixuVisionCoreMachines {

    /** 注册名（方块 / 物品 / BlockEntity 三合一）。 */
    public static final String ID = "guixu_vision_core";

    /**
     * 外壳贴图：{@code workableCasingRenderer} 的第一个参数（是<b>贴图</b>不是方块）。
     * 暂用 GT 自带的深色机壳（{@code machine_casing_robust_tungstensteel}）—— 阶段 3 换自己的贴图。
     */
    private static final ResourceLocation CASING_TEXTURE =
            GTCEu.id("block/casings/solid/machine_casing_robust_tungstensteel");

    /**
     * 工作面 overlay 目录：目前<b>故意指向一个不存在的路径</b>。
     * 反编译实证 {@code WorkableOverlayModel} 对缺失的贴图是
     * {@code if (resManager.getResource(png).isPresent()) ... else continue} ⇒ 静默少一个面，
     * 机器照样成型、只是不显示"运行中"的环。等有了自己的 overlay 再填这里。
     */
    private static final ResourceLocation OVERLAY_DIR =
            GTCEu.id("block/multiblock/guixu_vision_core");

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static MultiblockMachineDefinition definition;

    private GuixuVisionCoreMachines() {}

    /** 由 {@code ShanhaiMachines#init()} 调用（幂等）。 */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        // 参数表自检：24 个槽位 / 四朝向旋转 / 包围盒 —— 红了就在这里炸，别等到游戏里静默不成型
        GuixuHostGeometry.verify();
        // 字段落盘/同步自检：本类两个新字段必须在 LDLib 的 holder 里（父 holder 跳级会整段静默丢失）
        GuixuVisionCoreMachine.assertFieldHolderComplete();

        definition = ShanhaiRegistration.REGISTRATE
                .multiblock(ID, GuixuVisionCoreMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                // 占位配方类型，理由见类注释「一个临时挂载」
                .recipeTypes(GTRecipeTypes.ASSEMBLER_RECIPES)
                // 材料增产（主机那套 + 5）／耗能减免（同款）／并行 —— 整条链见那个方法
                // ⚠️ 2026-10-23 实查发现：本机继承的配方逻辑是基类的多配方引擎
                //    （PrimordialEngineRecipeLogic，构造器里 setUseMultipleRecipes(true)），
                //    而那条路径「完全不经过 RecipeModifierList.apply」⇒ 本条 .recipeModifier 目前【不会被执行】。
                //    两条修法（换引擎 / 在引擎里加钩子）见 temp/host-p2/报告-第二轮.md §8，等队长决定，
                //    这里先照挂、不删（删了将来换引擎时又得想起来加回来）。
                .recipeModifier(GuixuVisionCoreMachine::applyGuixuRecipeModifier)
                .pattern(GuixuVisionCoreStructure::createPattern)
                // ⚠️ 这里必须先 workableCasingRenderer、再 renderer —— 顺序不能反！
                //    字节码实证（MachineBuilder#workableCasingRenderer）：它<b>就是</b> renderer(供应商) 的糖，
                //    ⇒ 后调用的那个覆盖先调用的。反过来的话本机的动态特效渲染器根本不会被用到，
                //    而且不报错、不崩、只是"没有特效"（典型静默失败）。
                .workableCasingRenderer(CASING_TEXTURE, OVERLAY_DIR)
                // 🔴 2026-10-24：换成【动态特效渲染器】（内环转动 / 8 艘飞船 / 光束 / 喷流 + 把黑洞挂到环心）。
                //    - 它 extends WorkableCasingMachineRenderer（构造参数就是 CASING_TEXTURE / OVERLAY_DIR），
                //      所以机壳与工作面 overlay 的行为与原来逐字一致；
                //    - hasTESR/isGlobalRenderer = true ⇒ 开机器的 TESR；
                //    - 供应商只有客户端会调 ⇒ 服务端不会加载 client 包里的那个类
                //      （与老主机 `.renderer(() -> new PrimordialOmegaEngineRenderer(...))` 同一形态）。
                .renderer(() -> new com.shanhai.client.render.hostfx.GuixuHostFxRenderer(CASING_TEXTURE, OVERLAY_DIR))
                // 🔴🔴 2026-10-24 补上这一行 —— 这就是"特效一点没看见、日志连 gate 都没有"的根因。
                //    GTCEu `MachineBuilder#register()` 原文：
                //        if (this.hasTESR) blockEntityBuilder = blockEntityBuilder.renderer(() -> GTRendererProvider::getOrCreate);
                //    ⇒ 只给 `.renderer(...)` **不会**给方块实体挂 BER；必须显式 `.hasTESR(true)`。
                //    没有 BER ⇒ 我们的 `render(BlockEntity, …)` 一次都不会被调用（连 gate 日志都打不出来）。
                //    老主机（原始终焉引擎）是有的：`ShanhaiMachines:212` 那一行 `.hasTESR(true)`，上一轮我漏了它。
                .hasTESR(true)
                .appearanceBlock(GuixuVisionCoreStructure::appearanceBlock)
                .register();

        // 🔴 注册期自检：确认"最后装上去的渲染器"就是本机的动态特效渲染器。
        //    必须查 —— `workableCasingRenderer(...)` 与 `renderer(...)` 是同一个 setter 的两层糖，
        //    写反顺序的后果是"特效一行都不画，而且不报错"。这里用<b>类名字符串</b>比较而不是 instanceof：
        //    instanceof 会在服务端也去加载 client 包的类（专用服务器上会 NoClassDefFoundError）。
        // 🔴 2026-10-24 实战修正：**这段自检只能在客户端做**。专用服务器上 `getRenderer()` 返回的是
        //    LDLib 的占位实现（`IRenderer$1` —— 供应商在服务端根本不被求值，客户端类不会加载），
        //    照原样断言会让服务端启动即炸（冒烟实测：`IllegalStateException ... 实际 = com.lowdragmc...IRenderer$1`
        //    ⇒ `Failed to start the minecraft server`）。服务端改为只打一行日志。
        final var renderer = definition.getRenderer();
        final String rendererName = (renderer == null) ? "null" : renderer.getClass().getName();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            if (!"com.shanhai.client.render.hostfx.GuixuHostFxRenderer".equals(rendererName)) {
                throw new IllegalStateException("[SHANHAI-HOST] 动态特效渲染器没挂上：实际 = " + rendererName
                        + " —— 检查 .workableCasingRenderer(...) 是否写在了 .renderer(...) 之后（它会覆盖后者）。");
            }
            com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOST] 动态特效渲染器已挂上：{}", rendererName);
        } else {
            com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOST] 服务端：跳过动态特效渲染器自检（读到 {} 属正常）", rendererName);
        }
    }

    /** 主机定义句柄（注册后非空）。 */
    public static MultiblockMachineDefinition guixuVisionCore() {
        return definition;
    }
}
