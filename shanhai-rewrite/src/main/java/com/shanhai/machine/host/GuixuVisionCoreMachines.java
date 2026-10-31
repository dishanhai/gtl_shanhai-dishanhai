package com.shanhai.machine.host;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.shanhai.common.recipe.ShanhaiRecipeTypes;
import com.shanhai.registry.ShanhaiRegistration;

import net.minecraft.resources.ResourceLocation;

import org.gtlcore.gtlcore.GTLCore;

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
 * <h2>当前进度（2026-10-25）</h2>
 * <ul>
 *   <li>✅ 能摆出来、能成型、24 个槽位能识别并回连（第一轮）；</li>
 *   <li>✅ <b>能量双仓</b>：结构同时收能源仓与动力仓（{@link GuixuVisionCoreStructure#ringBandPredicate()}），
 *       抬头分两行画两侧存量（{@link GuixuVisionCoreMachine#addDisplayText}）；</li>
 *   <li>✅ <b>材料增产</b>（主机那套 + 5）与 <b>耗能减免</b>（同款）：整条链 =
 *       {@link GuixuVisionCoreMachine#applyGuixuRecipeModifier}（已挂在本定义的 {@code .recipeModifier}）；</li>
 *   <li>✅ <b>始终渲染为工作状态</b>：基类那个侧栏开关 + {@link GuixuVisionCoreMachine#isActive()} 覆写；</li>
 *   <li>✅ <b>三个自有配方类型</b>（2026-10-25 落地）：视界物质解压 {@code gtceu:vision_matter_decompression} ／
 *       视界能流抽取 {@code gtceu:vision_energy_extraction} ／ 原初范式简化
 *       {@code gtceu:primordial_paradigm_simplification} —— 见下一节「配方类型」。三者**都还没有配方**
 *       （用户自己写；IO 上限是先按拍板定的，写配方时要按实际用量复核）；</li>
 *   <li>⛔ <b>还没做</b>：<b>模块等级复写</b>（需要在模块侧加等级读点，等用户点头）、
 *       并行数的玩家覆盖、<b>自己的外壳贴图</b>（{@code CASING_TEXTURE} 现借"时空扭曲者"的）与 overlay。</li>
 * </ul>
 *
 * <h2>配方类型（2026-10-25 起是三个真类型，不再是占位）</h2>
 * {@code .recipeTypes(ShanhaiRecipeTypes.VISION_MATTER_DECOMPRESSION,
 * ShanhaiRecipeTypes.VISION_ENERGY_EXTRACTION,
 * ShanhaiRecipeTypes.PRIMORDIAL_PARADIGM_SIMPLIFICATION)} ——
 * 三个类型都在 {@code common\recipe\ShanhaiRecipeTypes} 里注册（**走它的 {@code register(name, category)}
 * 入口**，那条入口才会自动登记进 {@code REGISTERED_TYPES} ⇒ JEI 物质模块展示槽、类型条数 fail-fast
 * 都会自动带上它们）。
 *
 * <p>🔴 <b>为什么 recipeTypes 不能留空数组</b>（2026-10-23 换占位时就实证过，现在仍然成立）：
 * {@code WorkableMultiblockMachine#getRecipeType()} 的实现是 {@code recipeTypes[activeRecipeType]}
 * （javap 实证：{@code getfield recipeTypes} → {@code getfield activeRecipeType} → {@code aaload}，
 * <b>没有越界检查</b>），而 {@code RecipeLogic} 每 tick 都会走这条路 ⇒ 空数组会在首次 tick 抛
 * ArrayIndexOutOfBoundsException。
 *
 * <p>📜 <b>历史</b>：2026-10-23 → 2026-10-25 之间这里挂的是 {@code GTRecipeTypes.ASSEMBLER_RECIPES}
 * （纯占位，只为"不炸"；当时本机一个部件仓都没有，捡不到输入、只会待机）。三个真类型落地后
 * **占位已移除**：本文件现在既没有 `GTRecipeTypes` 的 import、也没有它的任何活代码引用
 * （`GTRecipeTypes` 这个词只剩本段与下面 `.recipeTypes(...)` 上那两句历史注记）。
 *
 * <p>🔴 <b>选中态（{@code activeRecipeType}）不会越界</b>（换 3 个类型时逐条核过字节码）：
 * 它是 {@code @Persisted int}、默认 0；唯一写者是 GTCEu 的 {@code MachineModeFancyConfigurator}
 * （侧栏「机器模式」页），而那一页**只在 {@code getRecipeTypes().length > 1} 时才被挂上**
 * （{@code IFancyUIMachine#attachSideTabs} 字节码偏移 20-39：{@code getRecipeTypes().length} 与 1 比较、
 * {@code if_icmple} 跳过），按钮按 {@code length} 逐条生成（{@code 2 + i*20}，下标恒 0..n-1）
 * ⇒ 本机从本轮起才第一次出现这个页，且填进去的下标永远合法。
 * 配方检索每 tick **现读** {@code machine.getRecipeType()}
 * （{@code MutableRecipesLogic#findAndHandleMultipleRecipe} 偏移 20 / 119 两次 {@code getRecipeType()}）
 * ⇒ 切换类型后下一轮就按新类型找配方，**不需要**额外的 dirty 标记。
 */
public final class GuixuVisionCoreMachines {

    /** 注册名（方块 / 物品 / BlockEntity 三合一）。 */
    public static final String ID = "guixu_vision_core";

    /**
     * 外壳贴图：{@code workableCasingRenderer} 的第一个参数（是<b>贴图</b>不是方块）。
     *
     * <p>用户 2026-10-25 点单：「给主机控制器方块换个贴图，换成 add 中时空扭曲者的」⇒ 就是这张。
     * 取值来源（反编译实证，不是猜的）：gtladditions 的 {@code TimeSpaceDistorterRenderer} 构造器
     * 第一句就是 {@code GTLCore.id("block/casings/dimension_injection_casing")}，
     * 第二句是它的 overlay 目录 {@code gtceu.id("block/multiblock/fusion_reactor")}；
     * 该机器定义的 appearanceBlock 也是 {@code GTLBlocks.DIMENSION_INJECTION_CASING}（同一个方块）⇒
     * 用这张贴图 = 控制器方块长得和「时空扭曲者」一模一样。
     * 贴图本体在 {@code gtlcore-*.jar!/assets/gtlcore/textures/block/casings/dimension_injection_casing.png}
     * （带 {@code .png.mcmeta} ⇒ 它是<b>动态贴图</b>）。
     */
    private static final ResourceLocation CASING_TEXTURE =
            GTLCore.id("block/casings/dimension_injection_casing");

    /**
     * 工作面 overlay 目录 = <b>与「时空扭曲者」同一个</b>（用户 2026-10-25 实测反馈：
     * 「你这个前面这个图没上去啊，就换了个壳」）。
     *
     * <p>原来这里故意指向一个不存在的 {@code block/multiblock/guixu_vision_core}：
     * {@code WorkableOverlayModel} 对缺失的贴图是 {@code if (…isPresent()) … else continue}
     * ⇒ 正面那张"面孔图"整片不画，只剩外壳贴图。用户要的是<b>连正面也对齐时空扭曲者</b>
     * ⇒ 取 gtladditions {@code TimeSpaceDistorterRenderer} 构造器第二句那个目录
     * {@code gtceu:block/multiblock/fusion_reactor}（已核：该目录在 gtceu jar 里存在，
     * 含 {@code overlay_front.png} / {@code overlay_front_active.png} 等整套面片）。
     */
    private static final ResourceLocation OVERLAY_DIR =
            GTCEu.id("block/multiblock/fusion_reactor");

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
                // 本机的三个自有配方类型（2026-10-25；不再是占位 ASSEMBLER）。
                // 🔴 顺序 = 侧栏「机器模式」页里按钮的顺序，也是 activeRecipeType 的下标口径 ⇒
                //    **只允许往后追加，不许插到前面**（否则已有存档里玩家的选择会指向另一个类型）。
                .recipeTypes(ShanhaiRecipeTypes.VISION_MATTER_DECOMPRESSION,
                        ShanhaiRecipeTypes.VISION_ENERGY_EXTRACTION,
                        ShanhaiRecipeTypes.PRIMORDIAL_PARADIGM_SIMPLIFICATION)
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
                // 🆕 2026-10-25（缺陷 C）：机器描述 —— 用户原话「你帮我个主机补个描述，可以模仿
                //    gtladd 种伪神之锻炉和终焉曲折量子波动矩阵的写法」。
                //  范式（逐字取自 gtladditions-3.2.8 的 assets/gtladditions/lang/zh_cn.json）：
                //    gtladditions.multiblock.forge_of_the_antichrist.tooltip.{0..8}
                //      = 3 行 §7§o 的意境句 → `§7=====` 分隔 → 机制句（§6§lN倍§r 那种写法）
                //    gtladditions.multiblock.apocalyptic_torsion_quantum_matrix.tooltip.{0..5}
                //      = 3 行 §7§o / §9§o 意境句 → 机制句（100%、1024 线程）
                //  ⇒ 本次照同一套：意境 3 行 + 分隔 + 机制 4 行（**只写已经实现了的效果**，
                //    每条都在报告里对应到一个类/方法；没实现的（例如"跨类型轮转"）一个字都没写）。
                .tooltips(
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.0"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.1"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.2"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.3"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.4"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.5"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.6"),
                        net.minecraft.network.chat.Component.translatable(
                                "shanhai.multiblock.guixu_vision_core.tooltip.7"))
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
