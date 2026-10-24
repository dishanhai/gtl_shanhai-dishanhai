package com.shanhai.machine;

import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.mojang.logging.LogUtils;
import com.shanhai.client.renderer.machine.PrimordialOmegaEngineRenderer;
import com.shanhai.common.machine.PrimordialEngineRecipeLogic;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.machine.engine.EngineBlocks;
import com.shanhai.machine.engine.PrimordialOmegaEngineStructure;
import com.shanhai.machine.host.GuixuVisionCoreMachines;
import com.shanhai.machine.module.ModuleRegistry;
import com.shanhai.registry.ShanhaiRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.List;

/**
 * 青铜神锻（原始终焉引擎）主机的注册入口 —— java-core 对外的唯一注册点。
 *
 * <h2>🔴 本类的调用时机：GTCEu 的 MachineDefinition RegisterEvent，<b>不是</b> mod 构造期</h2>
 * 旧写法在 {@code ShanhaiMod} 构造器里直接调 {@code init()}，炸在
 * {@code MultiblockMachineBuilder.<init>(:94) → GTCompassSections.<clinit> →
 * GTRegistry.register: registry gtceu:compass_section has been frozen}
 * （根因取证见 {@code com.shanhai.registry.ShanhaiRegistry} 类注释）。
 * 现在的时机与同环境里能正常工作的 {@code gtladditions} 完全一致
 * （{@code GTLAdditions.kt:37} 的 {@code addGenericListener(MachineDefinition.class, …)}）。
 *
 * <h2>初始化链（本类占中间一环）</h2>
 * <pre>
 * ShanhaiMod（@Mod 构造器，构造期）
 *   └─ ShanhaiRegistration.register(modEventBus)
 *   └─ modEventBus.addGenericListener(MachineDefinition.class, ShanhaiRegistry::onMachineRegister)
 *   └─ ShanhaiRegistry.init()
 *        └─ ① ShanhaiItems.init()      // java-assets（Registrate 入列，构造期合法）
 *
 * GTCEu 的 MachineDefinition RegisterEvent（GTMachines.init 内部 post）
 *   └─ ShanhaiRegistry.onMachineRegister(event)
 *        └─ ② ShanhaiMachines.init()   // ← 本类（内部调用 ③）
 *             └─ ③ ModuleRegistry.init()    // java-module（幂等，重复调用直接返回）
 * </pre>
 * 本类按任务书调用 {@code ModuleRegistry.init()}；{@code ModuleRegistry.init()} 自带
 * {@code AtomicBoolean} 幂等守卫（见其类注释「幂等：重复调用直接返回」），因此即使
 * 本类被重复触达也<b>不会重复注册</b>。本类自己另有一道 {@code AtomicBoolean} 守卫，
 * 防止 GTCEu 将来多 post 一次事件导致 {@code registry contains key … already}。
 *
 * <h2>主机定义</h2>
 * <ul>
 *   <li>注册名 {@code shanhai:primordial_omega_engine}（方块 + 物品 + BlockEntity 同 ID，规格 §4.2）</li>
 *   <li>5 类 GTCEu <b>原版</b>工序直接 {@code .recipeTypes(...)}：熔炉 / 组装机 / 合金炉 / 锻造锤 / 研磨机。
 *       没有 machine mode、没有电路切换、没有旧山海的 {@code SelectableRecipeTypeSetMachine} 一脉
 *       （规格 §6.2 方案 A，验收 A8）。</li>
 *   <li>{@code rotationState(NON_Y_AXIS)}：16 个模块位是按「控制器四方位 + 4 个竖直层」定义的，
 *       只能绕 Y 轴转（上游同为 NON_Y_AXIS）。</li>
 *   <li>结构复用 gtladditions 的 {@code .bin}，谓词见 {@link PrimordialOmegaEngineStructure}。</li>
 * </ul>
 */
public final class ShanhaiMachines {

    /** 注册名（方块 / 物品 / BlockEntity 三合一）。 */
    public static final String PRIMORDIAL_OMEGA_ENGINE_ID = "primordial_omega_engine";

    // ---------------------------------------------------------------- 外观（要换贴图只改这两行）

    /**
     * <b>主机外壳贴图</b>——{@code workableCasingRenderer} 的<b>第一个</b>参数。
     *
     * <p>语义（反编译 GTCEu 1.4.4 {@code WorkableCasingMachineRenderer} 构造器原文）：
     * <pre>
     *   super(tint ? GTCEu.id("block/cube/tinted/all") : GTCEu.id("block/cube/all"));
     *   this.overlayModel = new WorkableOverlayModel(workableModel);
     *   this.baseCasing = baseCasing;
     *   this.setTextureOverride(Map.of("all", baseCasing));
     * </pre>
     * 即：它被塞进 {@code gtceu:block/cube/tinted/all} 这个模型的 {@code all} 纹理槽
     * ⇒ <b>本参数是"贴图"（textures/…png），不是模型、也不是方块</b>。
     *
     * <p>取值与上游逐字一致（{@code originals/upstream/…/DShanhaiMachines.java:265}）：
     * 上游给自定义渲染器传的第一参就是它。
     * <p>⚠️ 注意别和 {@code appearanceBlock} 混了：上游的 {@code appearanceBlock} 写的是
     * 方块 id {@code gtceu:steam_machine_casing}，它<b>就是</b> {@code GTBlocks.CASING_BRONZE_BRICKS}
     * （取证：反编译 GTBlocks {@code CASING_BRONZE_BRICKS = createCasingBlock("steam_machine_casing",
     * GTCEu.id("block/casings/solid/machine_casing_bronze_plated_bricks"))}），两者同源，不是矛盾。
     */
    public static final ResourceLocation CASING_TEXTURE =
            new ResourceLocation("gtceu", "block/casings/solid/machine_casing_bronze_plated_bricks");

    /**
     * <b>工作面 overlay 的目录</b>——{@code workableCasingRenderer} 的<b>第二个</b>参数。
     *
     * <p>语义：{@code new WorkableOverlayModel(workableModel)} 会按六个面去找
     * {@code textures/<本参数>/overlay_<face>.png}（反编译 {@code WorkableOverlayModel}）：
     * <pre>
     *   String overlayPath = "/overlay_" + overlayFace.name().toLowerCase(Locale.ROOT);
     *   ResourceLocation normalSprite = new ResourceLocation(location.getNamespace(), location.getPath() + overlayPath);
     *   private ResourceLocation getTextureLocation(ResourceLocation l) {
     *       return new ResourceLocation(l.getNamespace(), "textures/%s.png".formatted(l.getPath()));
     *   }
     * </pre>
     * 面名枚举 = {@code FRONT / BACK / TOP / BOTTOM / SIDE}
     * ⇒ 本参数指向的目录里至少要有一对
     * <b>{@code assets/shanhai/textures/block/multiblock/primordial_omega_engine/overlay_front.png}
     * + {@code overlay_front_active.png}</b>（激活态可选，缺了会退回静态那张）。
     *
     * <p>🔴 <b>缺这套贴图不会报错</b>：{@code registerTextureAtlas} 对每个面做
     * {@code if (!resManager.getResource(png).isPresent()) continue;}
     * ⇒ 文件不在就<b>静默少一个面</b>，日志无异常、编译无异常、结构照常成型，
     * 只是主机看上去像块普通青铜砖。本工程 2026-09 的"主机贴图缺失"就是这个坑
     * （资产漏迁，不是路径写错）。
     */
    public static final ResourceLocation OVERLAY_TEXTURE_DIR =
            new ResourceLocation("shanhai", "block/multiblock/primordial_omega_engine");

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 主机挂的 5 类「基础工序」（全是 GTCEu 原版类型，验收 A8）。
     *
     * <p>🔴 <b>2026-09-25（任务 A/B）：从 {@code .recipeTypes(...)} 的内联实参提成常量。</b>
     * 目的同 {@code PrimordialEngineRecipeLogic.BASE_THREADS}：让物品 tooltip 里列的
     * 「可用配方类型」与机器<b>真正挂的</b>那份<b>同源</b>（同一数组对象），
     * 杜绝"tooltip 列的和机器实际接受的不一致"这种活体假数据。
     * <p>内容与提取前逐字相同，顺序也相同。
     */
    public static final GTRecipeType[] HOST_RECIPE_TYPES = {
            GTRecipeTypes.FURNACE_RECIPES,
            GTRecipeTypes.ASSEMBLER_RECIPES,
            GTRecipeTypes.ALLOY_SMELTER_RECIPES,
            GTRecipeTypes.FORGE_HAMMER_RECIPES,
            GTRecipeTypes.MACERATOR_RECIPES,
    };

    private static MultiblockMachineDefinition primordialOmegaEngine;

    /** 幂等守卫：GTCEu 若多 post 一次 MachineDefinition 事件，重复注册会抛 "contains key … already"。 */
    private static final java.util.concurrent.atomic.AtomicBoolean INITIALIZED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private ShanhaiMachines() {}

    /**
     * 由 {@code ShanhaiRegistry.onMachineRegister(…)} 调用 ——
     * 即 GTCEu 的 {@code GTMachines.init()} 内部 post {@code GTCEuAPI.RegisterEvent} 的那一刻。
     * <b>绝不能</b>在 mod 构造期调用（见类注释）。
     */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            LOGGER.info("[SHANHAI-SPEC] ShanhaiMachines.init() 重复调用，已跳过（幂等）");
            return;
        }

        // ① 🔴 建 pattern 之前先查 kubejs:steam_assembly_block 并判空。
        //    它是宿主 KubeJS 的方块，本 mod 里没有对应常量，只能走 id 字符串查表；
        //    缺了必须抛（带明确 id）—— 绝不允许退化成 air：退化 = 编译通过、结构永不成型、日志无异常。
        //    ⚠️ 本类跑在 GTCEu 的 RegisterEvent 里，仍然早于 KubeJS 把 startup_scripts 的方块
        //    真正写进注册表的时机（实测日志：本 mod 构造 19:20:27，KubeJS 脚本 19:20:33），
        //    所以这里「查不到」是【正常且预期】的分支，由 CommonSetup 硬校验接管。
        //    真正的方块句柄由主机的 pattern 惰性求值（SupplierMemoizer 记忆化）时取用，那时注册表早已冻结。
        Block steamAssemblyBlock = EngineBlocks.lookupSteamAssemblyBlock();
        if (steamAssemblyBlock != null) {
            EngineBlocks.cacheSteamAssemblyBlock(steamAssemblyBlock);
            LOGGER.info("[SHANHAI-SPEC] 空槽占位方块已就位：{}", EngineBlocks.STEAM_ASSEMBLY_BLOCK);
        } else {
            // 宿主 KubeJS 的方块此刻还没入表，这是正常的加载顺序，不是错误。
            // 所以这里不误报崩溃，改挂一次 CommonSetup 硬校验（仍在 mod 加载期抛）；
            // 结构谓词侧（EngineBlocks.steamAssemblyBlock()）另有一道兜底判空。
            armSteamAssemblyBlockCheck();
        }

        // ② 主机本体
        primordialOmegaEngine = ShanhaiRegistration.REGISTRATE
                .multiblock(PRIMORDIAL_OMEGA_ENGINE_ID, PrimordialOmegaEngineMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                // 5 类「基础工序」：全部是 GTCEu 原版 GTRecipeType，零自定义（验收 A8）
                // 🔴 2026-09-25：数组已提成 HOST_RECIPE_TYPES 常量（同一份同时喂 tooltip，见常量注释）
                .recipeTypes(HOST_RECIPE_TYPES)
                .pattern(PrimordialOmegaEngineStructure::createPattern)
                // 外观 + TESR：外壳贴图 + 工作面 overlay 目录（常量见本类顶部，换贴图只改那两行）。
                //
                // 用 .renderer(() -> new PrimordialOmegaEngineRenderer(...))（与上游 :264-266 逐字同形）。
                // 它与上一版的 .workableCasingRenderer(CASING_TEXTURE, OVERLAY_TEXTURE_DIR) 只差"传入的是子类"：
                // 反编译 GTCEu 1.4.4 MachineBuilder:250-251 原文就是
                //     workableCasingRenderer(baseCasing, workableModel)
                //         { return this.renderer(() -> new WorkableCasingMachineRenderer(baseCasing, workableModel)); }
                // PrimordialOmegaEngineRenderer → AbstractRingRenderer → WorkableCasingMachineRenderer，
                // 所以基类那套外壳 + overlay 渲染照旧，新增的是轨道环与中心球体。
                //
                // 🔴 这里必须传【Supplier】而不是渲染器实例，这不是风格问题：
                // 反编译 MachineBuilder.register() 原文
                //     setRenderer(LDLib.isClient() ? this.renderer.get() : IRenderer.EMPTY);
                // GTCEu 用 LDLib.isClient() 做了客户端门禁 —— 专用服务端上这个 supplier 不会被调用，
                // 于是 com.shanhai.client.* 那批客户端专属类在服务端【不被加载】。
                // 若改成直接 new 实例，服务端加载本类时就会去解析客户端类。
                .renderer(() -> new PrimordialOmegaEngineRenderer(CASING_TEXTURE, OVERLAY_TEXTURE_DIR))
                .appearanceBlock(() -> GTBlocks.CASING_BRONZE_BRICKS.get())
                // hasTESR(true) 的作用（反编译 MachineBuilder.register 原文）：
                //     if (this.hasTESR) blockEntityBuilder = blockEntityBuilder.renderer(() -> GTRendererProvider::getOrCreate);
                // 即给方块实体挂上 LDLib 的 BER。
                // ⚠️ 步骤 1 时它对纯 WorkableCasingMachineRenderer 是【可证的 no-op】；
                //    现在【必需】了：AbstractRingRenderer 覆写了 hasTESR(→true) 与 render(BlockEntity, …)，
                //    轨道环与中心球体全走 BER 的 PoseStack + MultiBufferSource 即时模式。
                .hasTESR(true)
                // ───── 配方修饰链（N4 并行 → N3 产出倍率 → N6 时长 min 天花板 → N5 耗电减免）⚠️ 换多配方引擎后本链已不执行 ─────
                // 🔴 这里是**替换**而不是叠加：MachineBuilder#recipeModifier 的字节码是
                //     this.recipeModifier = x instanceof RecipeModifierList list ? list : new RecipeModifierList(x);
                //   本行之前主机没调过它，吃的是 MachineBuilder 的默认值
                //     new RecipeModifierList(GTRecipeModifiers.ELECTRIC_OVERCLOCK.apply(OverclockingLogic.NON_PERFECT_OVERCLOCK))
                //   ⇒ 换成自写链之后，主机**不再挂原版电过载**。这是有意为之的取舍，
                //   完整理由（含"为什么不能简单地把电过载串回来"）写在
                //   PrimordialOmegaEngineMachine#applyHostRecipeModifier 的 javadoc 里。
                //   ⚠️ 顺序与作用域（2026-09 队长裁决）：
                //     N6 在本链是**时长 min 天花板**（duration = min(原时长, max(1, getLimitedDuration()))，不是恒等、也不是下限）；
                //     N5 主机**只吃耗电减免**（EUt ×= f），**不吃时长减免** —— 详见那两个 javadoc 的对照表。
                .recipeModifier(PrimordialOmegaEngineMachine::applyHostRecipeModifier)
                // 🔴 2026-09-25（任务 A + B）：主机的物品 tooltip 属性行 —— 与 24 台模块
                //   走【同一个】追加器（com.shanhai.machine.MachineTooltips），措辞与排版一致。
                //   取值全部是主机的真实值，来源与 Jade 里那两行同源：
                //     · 最大并行数 = getMaxParallel()（当前实现 = Integer.MAX_VALUE，用户 2026-09-22
                //       裁决「照伪神填 + 显示真实数值」）；这里直接引用同一个来源，
                //       不写死字面量（改口径时只改机器那一处）。
                //     · 跨配方线程数 = PrimordialEngineRecipeLogic.BASE_THREADS（128，
                //       即 Jade 里那个 getMultipleThreads() 的结果；同一个常量，不会漂移）。
                //     · 可用配方类型 = HOST_RECIPE_TYPES（机器真正挂的那一份）。
                .tooltips(MachineTooltips.forMachine(
                        PRIMORDIAL_OMEGA_ENGINE_ID,
                        Component.literal(String.valueOf(PrimordialOmegaEngineMachine.MAX_PARALLEL_DISPLAY)),
                        Component.literal(String.valueOf(PrimordialEngineRecipeLogic.BASE_THREADS)),
                        HOST_RECIPE_TYPES))
                .register();

        LOGGER.info("[SHANHAI-SPEC] 主机已注册：shanhai:{}（16 个模块位 / 5 类原版工序）", PRIMORDIAL_OMEGA_ENGINE_ID);

        // ③ 原初模块（物资重组核心等）。java-module 的实现是幂等的，重复调用安全。
        ModuleRegistry.init();

        // ④ 归墟视界枢核（新主机；2026-10-23 起）。
        //    ⚠️ 必须排在 ModuleRegistry.init() 之后：它的 M 谓词要读 ModuleRegistry.allModuleBlocks()。
        //    本阶段只做「能成型 + 24 接口可回连」，配方逻辑/并行/双向仓全部留到阶段 3。
        GuixuVisionCoreMachines.init();

        // ④ fail-fast：模块表为空 = 上一步没生效，此时若放行，主机的 J 谓词会退化成
        //    「只认占位方块」⇒ 装了模块的结构永久不成型且无异常。宁可在这里炸。
        //
        //    🔴 这里必须用「模块定义条数」而不是「模块方块表」（ModuleRegistry.registeredModuleCount()
        //    而不是 allModuleBlocks()）：本方法跑在 GTCEu 的 MachineDefinition RegisterEvent 里，
        //    <b>早于</b> Forge 的方块 RegisterEvent。而 Registrate 的 {@code RegistryEntry.get()}
        //    在对应 RegisterEvent 之前必然抛 {@code NullPointerException: Registry entry not present: …}
        //    （证据：Registrate-MC1.20-1.3.3 的 {@code RegistryEntry.get()} 走
        //    {@code Objects.requireNonNull(getUnchecked(), …)}，而 {@code RegistryObject} 的
        //    value 只在 {@code updateReference(RegisterEvent)} 里被填）。
        //    所以此刻取 {@code definition.getBlock()} 会当场 NPE —— 这是与本次崩溃同源的
        //    「框架生命周期时序」坑，第二次真启动才会暴露。方块句柄只在 pattern 惰性求值时取用。
        if (ModuleRegistry.registeredModuleCount() == 0) {
            throw new IllegalStateException("[SHANHAI] 模块表为空：ModuleRegistry.init() 没有注册出任何模块。"
                    // ⛔ 【2026-09-22 作废 · 用户撤回 · 原文照留】本行曾短暂写作：
                    //     + "主机 16 个模块位（J 谓词，2026-09-22 起）= 空气 ∪ kubejs:steam_assembly_block ∪ 模块控制器方块，"
                    //   作废原因：用户撤回「拆一个模块不该停全机」⇒ J 谓词恢复为不含 air（见 moduleSlotPredicate()）。
                    + "主机 16 个模块位（J 谓词）= kubejs:steam_assembly_block ∪ 模块控制器方块，"
                    + "模块方块缺失会让「装了模块」的结构永久不成型且日志无异常，因此在加载期直接失败。");
        }
        LOGGER.info("[SHANHAI-SPEC] 模块表：{} 个（J 位谓词在 pattern 惰性求值时并入）",
                ModuleRegistry.registeredModuleCount());
    }

    /** 主机定义句柄（注册后非空）。 */
    public static MultiblockMachineDefinition primordialOmegaEngine() {
        return primordialOmegaEngine;
    }

    // ------------------------------------------------------- J 位（16 个模块位）

    /**
     * <b>J 谓词 = 空槽占位方块 ∪ 全部模块控制器方块</b>（规格 §4.1 谓词表 J 行）。
     *
     * <ul>
     *   <li>空槽必须放 {@code kubejs:steam_assembly_block}，否则主机结构不成型 —— 16 格<b>每一格</b>
     *       都必须有合法方块（guide {@code primordial_index.md:182}；设计如此，不是 bug）。</li>
     *   <li>装了模块的那一格是模块控制器方块，句柄表来自
     *       {@link ModuleRegistry#allModuleBlocks()}（未来 24 个模块自动并入，主机侧不用改）。</li>
     * </ul>
     *
     * <p>两个来源都「拿不到就抛」，绝不放行成「谓词恒 false」的静默失败。
     * 本方法在建 pattern 时（首次结构检查 / JEI 预览）调用，那时注册表早已冻结、各注册段也已跑完。
     *
     * <h2>🔴 2026-09-22：本条谓词曾短暂被放宽为「允许空气」，<b>同一天被用户撤回</b></h2>
     * 撤回时的用户原话：「<b>等下，我后悔了，我返回之前那个替换或者拆除模块就停止全机的决定</b>」
     * ⇒ 恢复「<b>拆 / 换模块即停全机</b>」的原行为 ⇒ 本条谓词<b>不再</b>含 {@code Predicates.air()}。
     * 🔴 <b>那次放宽【从未部署、从未进游戏】</b>：那一轮只跑到 {@code compileJava}，没有 build、没有部署。
     * <p>⇒ 现在「挖掉一台模块 ⇒ 主机 J 位变空气 ⇒ 主机结构失效 ⇒ {@code safeClearModules()}」
     * 这条链<b>重新成立</b>（与撤回之前完全一致）。被删掉的那一行以注释形式留在方法体里。
     *
     * <hr>
     * <p>⛔ <b>【2026-09-22 作废 · 本轮 air 版 javadoc 原文照留，一字未改】</b>
     * （其内部自带的那段"作废块"针对的是本文正文，也一并照留，以见全貌）：
     * <pre>
     * <b>J 谓词 = 空气 ∪ 空槽占位方块 ∪ 全部模块控制器方块</b>（规格 §4.1 谓词表 J 行，2026-09-22 放宽）。
     *
     *   - 🔴 <b>2026-09-22（用户裁决 A「拆一个模块不该停全机」）：16 个模块位<b>允许空气</b>。</b>
     *     之前「空槽必须放占位方块」的语义会让<b>挖掉任意一台模块 = 主机整体不成型</b>
     *     ⇒ 其余 15 台被 {@code safeClearModules()} 全部断开再重连。放宽后主机不再因为空槽而失效。
     *   - 空槽占位方块 {@code kubejs:steam_assembly_block} <b>保留为合法方块</b>（不是删掉）：
     *     老存档里已经摆好的占位方块必须继续让主机成型，否则升级一次就把玩家的机器顶停。
     *   - 装了模块的那一格是模块控制器方块，句柄表来自
     *     {@link ModuleRegistry#allModuleBlocks()}（24 个模块自动并入，主机侧不用改）。
     *
     * 🔴 用的是 {@code Predicates.air()}，不是 {@code Predicates.any()}
     * 这两者在本版本里是<b>两个不同的</b> {@code SimplePredicate} 常量：
     * {@code javap -c Predicates} 原文 —— {@code air()} = {@code new TraceabilityPredicate(SimplePredicate.AIR)}、
     * {@code any()} = {@code new TraceabilityPredicate(SimplePredicate.ANY)}。
     * {@code any()} 会连"玩家往槽里塞任意方块"一起放行 ⇒ <b>严禁</b>在本位使用。
     * 上游用例（不是我们自造）：全 {@code libs/} 33 个 jar、20 个引用 {@code Predicates} 的 class 里，
     * <b>9 个</b>调用 {@code Predicates.air()}（正向对照：{@code Predicates.blocks} 15 个 / {@code Predicates.any} 13 个）
     * —— 含 gtladditions {@code MultiBlockMachine}（字节码原文
     * {@code ldc "O" → invokestatic Predicates.air:()… → FactoryBlockPattern.where:(C,…)}，
     * 即 gtladditions 自己就把某个符号映射成 air）与 gtlcore {@code AdvancedMultiBlockMachine}（7 处）。
     *
     * 放行空气不会让别的东西混进来（三条已核）
     *   1. {@code TraceabilityPredicate#test} 是 {@code limited.anyMatch(...) || common.anyMatch(...)}
     *      （字节码偏移 9–85）⇒ 加一条 air 只是给这个"或"多一个分支，<b>不放宽其余分支</b>。
     *   2. {@code FactoryBlockPattern#where(char, pred)} 只在 {@code isAny() || isAir()} 时才直通，
     *      否则包一层拷贝构造（偏移 0–45）。本谓词加不加 air 都是 {@code common.size() > 1}
     *      ⇒ {@code isAir()} 恒为 false ⇒ <b>走的是同一条包装分支，行为零变化</b>。
     *   3. {@code hasAir()} 唯一的消费者是 {@code SimplePredicate#getToolTips}（偏移 235–253），
     *      只往提示里加一行 {@code gtceu.multiblock.pattern.replaceable_air} ⇒ <b>纯文案</b>。
     *
     * 两个来源都「拿不到就抛」，绝不放行成「谓词恒 false」的静默失败。
     * 本方法在建 pattern 时（首次结构检查 / JEI 预览）调用，那时注册表早已冻结、各注册段也已跑完。
     *
     * ── 以下是 air 版当年自己写的作废块（针对上面这段正文；一并照留）──
     * ⛔ 【2026-09-22 作废 · 原文照留】以下是放宽之前的原文（一字未改）：
     * <b>J 谓词 = 空槽占位方块 ∪ 全部模块控制器方块</b>
     *   - 空槽必须放 kubejs:steam_assembly_block，否则主机结构不成型 —— 16 格每一格
     *     都必须有合法方块（guide primordial_index.md:182；设计如此，不是 bug）。
     * 作废原因：用户 2026-09-22 裁决「拆一个模块不该停全机」。
     * 「每格都必须有合法方块」这条设计会让"挖掉一台模块"直接停掉另外 15 台，代价明显不可接受。
     * 这是对规格 §4.1 谓词表 J 行的偏离，需走规格变更流程（我未改 {@code docs/specs/}，归队长排期）。
     * </pre>
     * <b>上面这一整段被作废的原因</b>：用户在同一天撤回（原话见上文）。
     * <b>归属留档</b>：该放宽<b>由实现代理在本轮实施、从未部署、从未进游戏</b>，撤回亦未进游戏。
     */
    public static TraceabilityPredicate moduleSlotPredicate() {
        List<Block> moduleBlocks = ModuleRegistry.allModuleBlocks();
        if (moduleBlocks.isEmpty()) {
            throw new IllegalStateException("[SHANHAI] 模块方块表为空，拒绝生成「只认占位方块」的 J 谓词："
                    + "那会让装了模块的主机结构永久不成型，且不抛异常、不打日志。"
                    + "请确认 ModuleRegistry.init() 已被调用（ShanhaiRegistry.init() 第 ③ 段 / ShanhaiMachines.init()）。");
        }
        TraceabilityPredicate predicate = Predicates.blocks(EngineBlocks.steamAssemblyBlock());
        for (Block moduleBlock : moduleBlocks) {
            predicate = predicate.or(Predicates.blocks(moduleBlock));
        }
        // ⛔ 【2026-09-22 作废 · 用户撤回 · 原文照留】这一行曾让"空槽"合法（让 `拆/换模块不再停全机`）：
        //     predicate = predicate.or(Predicates.air());
        //   作废原因：用户 2026-09-22 裁决 A 后又撤回
        //   （原话「我后悔了，我返回之前那个替换或者拆除模块就停止全机的决定」）
        //   ⇒ 恢复「拆 / 换模块即停全机」的原行为。**该改动从未部署、从未进游戏。**
        //   要重新启用就把它放回来（并同步本方法 javadoc 的正文）。
        return predicate;
    }

    // ------------------------------------------------------------- 占位方块校验

    private static void armSteamAssemblyBlockCheck() {
        try {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ShanhaiMachines::verifySteamAssemblyBlock);
            LOGGER.info("[SHANHAI-SPEC] {} 此刻尚未进入注册表（KubeJS startup_scripts 的方块要晚于 "
                            + "GTCEu 的 MachineDefinition RegisterEvent 才入表，属正常顺序），"
                            + "已挂 CommonSetup 硬校验；届时仍缺失则加载直接失败",
                    EngineBlocks.STEAM_ASSEMBLY_BLOCK);
        } catch (Throwable t) {
            // 事件总线都拿不到（不该发生）：就地硬失败，绝不静默放过
            throw new IllegalStateException("[SHANHAI] 无法为 " + EngineBlocks.STEAM_ASSEMBLY_BLOCK
                    + " 挂载加载期校验，且此刻注册表里也查不到它 —— 拒绝带着缺失的占位方块继续加载", t);
        }
    }

    /** CommonSetup（全部注册表冻结之后）的硬校验：宿主 KubeJS 的占位方块必须存在。 */
    private static void verifySteamAssemblyBlock(FMLCommonSetupEvent event) {
        Block block = EngineBlocks.lookupSteamAssemblyBlock();
        if (block == null) {
            throw new IllegalStateException("[SHANHAI] 缺少方块 " + EngineBlocks.STEAM_ASSEMBLY_BLOCK
                    + "：它是主机 16 个模块位的空槽占位方块（规格 §2.3），由宿主 KubeJS 的 "
                    + "kubejs/startup_scripts/block.js 提供，本 mod 不注册、也不允许注册同名方块（A17）。"
                    + "排查：宿主 kubejs 启动脚本是否报错（<实例>/logs/kubejs/startup.log）；"
                    + "若确实没有它，按规格 §8 R1 换占位方块并走 v1.2 变更流程。");
        }
        EngineBlocks.cacheSteamAssemblyBlock(block);
        LOGGER.info("[SHANHAI-SPEC] 空槽占位方块校验通过：{}", EngineBlocks.STEAM_ASSEMBLY_BLOCK);
    }
}
