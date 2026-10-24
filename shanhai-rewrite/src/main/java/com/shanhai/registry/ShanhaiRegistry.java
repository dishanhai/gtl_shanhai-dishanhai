package com.shanhai.registry;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.ShanhaiRecipeTypes;
import com.shanhai.machine.module.ModuleLevelCondition;
import com.shanhai.fluid.ShanhaiFluids;
import com.shanhai.item.ShanhaiCreativeModeTabs;
import com.shanhai.item.ShanhaiItems;
import com.shanhai.machine.ShanhaiMachines;
import com.shanhai.machine.module.ModuleRegistry;
import com.shanhai.machine.stellar.ShanhaiStellarMachines;
import com.shanhai.machine.wildcard.ShanhaiWildcardMachines;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 阶段 1 的注册总入口。
 *
 * <h2>🔴 为什么现在是「两段」而不是「一段」（第二次真启动换来的）</h2>
 *
 * 旧写法把整条链塞在 {@code @Mod} 构造器里，结果炸在
 * {@code MultiblockMachineBuilder.<init>(:94) → GTCompassSections.<clinit> →
 * GTRegistry.register: registry gtceu:compass_section has been frozen}。
 * 取证（全部来自反编译/字节码，不是推测）：
 *
 * <ol>
 *   <li>{@code GTRegistry} 的 {@code frozen} 字段<b>默认就是 true</b>
 *       （{@code GTRegistry.java:28 protected boolean frozen = true;}）。
 *       即「新注册表一出生就是冻结的」，只有显式 {@code unfreeze()} 才能写。</li>
 *   <li>{@code GTCompassSections.<clinit>} 的第一条字节码就是
 *       {@code GTRegistries.COMPASS_SECTIONS.unfreeze()}
 *       （{@code javap -c}: 0: getstatic GTRegistries.COMPASS_SECTIONS / 3: invokevirtual unfreeze）,
 *       之后才逐个 {@code CompassSection.register()}；第一个 register 在原源码第 30 行
 *       （与崩溃栈 {@code GTCompassSections.<clinit>(GTCompassSections.java:30)} 吻合）。</li>
 *   <li>{@code GTRegistry.unfreeze()/freeze()} 都带一道门禁
 *       {@code checkActiveModContainerIsGregtech()}（{@code GTRegistry.java:64-68}）：
 *       只有当 {@code ModLoadingContext.get().getActiveContainer()} 是
 *       {@code gtceu} / {@code minecraft} / 该注册表的命名空间时才生效，<b>否则静默什么都不做</b>
 *       （不抛异常）。</li>
 *   <li>在<b>我们自己的 mod 构造期</b>，active container 是 {@code shanhai}
 *       ⇒ {@code unfreeze()} 静默失效 ⇒ 紧接着的 {@code CompassSection.register()} 撞上
 *       {@code frozen == true} ⇒ 抛 {@code IllegalStateException}，
 *       而它发生在 {@code <clinit>} 里，所以外层包装成 {@code ExceptionInInitializerError}。</li>
 *   <li>触发点：{@code MultiblockMachineBuilder} 的构造器最后一行
 *       （{@code javap -l}: 构造函数 LineNumberTable {@code line 94: 78} ↔
 *       {@code getstatic GTCompassSections.MULTIBLOCK}) 执行 {@code this.compassSections(GTCompassSections.MULTIBLOCK)}。
 *       <b>只要 new 一个 MultiblockMachineBuilder，就必然强制 GTCompassSections 类初始化。</b></li>
 *   <li>GTCEu 那边为什么不会炸：它的内容注册<b>不在</b> mod 构造器里，而是在
 *       {@code CommonProxy} 构造器里 {@code eventBus.register(this)}，
 *       再由 {@code @SubscribeEvent modConstruct(FMLConstructModEvent e) { e.enqueueWork(CommonProxy::init); }}
 *       延后执行（{@code CommonProxy.java:224-226}）。{@code CommonProxy.init()} 里
 *       第 138 行先 {@code GTCompassSections.init()}（此时 active container 是 gtceu，unfreeze 生效），
 *       第 147 行才 {@code GTMachines.init()}。而 {@code FMLConstructModEvent} 按 Forge 契约
 *       晚于<b>所有</b> mod 构造器 ⇒ 我们的构造期永远早于它。</li>
 * </ol>
 *
 * <h2>正确时机 = GTCEu 的 {@code RegisterEvent}（与 gtladditions 一致）</h2>
 * {@code GTMachines.init()} 的字节码尾部（{@code javap -l}）：
 * <pre>
 *   line 2792:  ModLoader.get().postEvent(new GTCEuAPI.RegisterEvent&lt;&gt;(GTRegistries.MACHINES, MachineDefinition.class));
 *   line 2794:  GTRegistries.MACHINES.freeze();
 * </pre>
 * 也就是说，在这两行之间 GTCEu 主动开放了一个「给 addon 注册机器」的窗口：
 * {@code GTRegistries.MACHINES} 此时是 unfrozen 的，而 {@code GTCompassSections} 已经在第 138 行
 * 初始化完毕。参照 mod {@code gtladditions} 正是这么做的：
 * <pre>
 *   // GTLAdditions.kt:37（@Mod 构造器里）
 *   modEventBus.addGenericListener(MachineDefinition.class, GTLAdditions::_init_$lambda$1);
 *   // → GTLAddMachines.INSTANCE.init() → MultiBlockMachine 的 40+ 台机器全在这里注册
 * </pre>
 * 所以本工程也照抄这一条：机器注册挂在 {@code MachineDefinition} 泛型监听器上，
 * 而不是自己发明第三种时机。
 *
 * <h2>初始化链（冻结）</h2>
 * <pre>
 * 构造期（ShanhaiMod）
 *   └─ ShanhaiRegistry.init()
 *        └─ ① ShanhaiCreativeModeTabs.init()     // 创造模式物品栏（18 物品 + 主机）
 *        └─ ② ShanhaiItems.init()                // 18 个物品（Registrate builder 入列即完成）
 *
 * GTCEu 的 GTRecipeType RegisterEvent（GTRecipeTypes.init 内部 post，freeze 之前）
 *   └─ ShanhaiRegistry.onRecipeTypeRegister(event)
 *        └─ ⑤ ShanhaiRecipeTypes.init()          // 3 个配方类型（只注册、不挂机器；幂等）
 *
 * GTCEu 的 MachineDefinition RegisterEvent（GTMachines.init 内部 post）
 *   └─ ShanhaiRegistry.onMachineRegister(event)
 *        └─ ③ ShanhaiMachines.init()             // 主机
 *             └─ ④ ModuleRegistry.init()         // 模块框架 + 物质重组核心（幂等）
 *
 * FMLCommonSetupEvent（全部注册表已冻结）
 *   └─ verifyMachinesRegistered()                // 兜底：监听器没跑就响亮地失败
 *   └─ verifyRecipeTypesRegistered()             // 同上，针对 ⑤
 * </pre>
 *
 * <p>①② 仍排在③之前：三者最终都跑在同一个线程上、顺序不变，物品的 {@code ItemEntry} 依然先就位。
 */
public final class ShanhaiRegistry {

    private ShanhaiRegistry() {}

    /**
     * 构造期唯一允许做的事：创造模式物品栏 + 18 个物品的 Registrate builder 入列
     * + 挂 CommonSetup 兜底校验。<b>不要</b>在这里注册机器（见类注释）。
     */
    public static void init() {
        // ① 本 mod 的创造模式物品栏（18 个物品 + 主机控制器的归属）。
        //    🔴 必须在物品之前：没有归属栏 ⇒ 创造栏看不见、JEI 也搜不到
        //    （JEI 的物品表由 CreativeModeTabs 的 display items 建，见 ShanhaiCreativeModeTabs 类注释）。
        ShanhaiCreativeModeTabs.init();

        // ② 18 个物品 + B1a 的 131 条 + B1b/B1c 的 17 条
        ShanhaiItems.init();

        // ②b 25 个流体 + 25 个桶（B2）：同一套 REGISTRATE，构造期入列即可
        //    （流体/物品是 Forge 原生注册表，不受 GTCEu GTRegistry 冻结窗口约束）。
        ShanhaiFluids.init();

        // 兜底：万一上面那个泛型监听器没被命中（框架约定变了 / 事件被改名），
        // 机器会【静默不存在】—— 那是最难查的一类失败。这里把它变成加载期响亮失败。
        try {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ShanhaiRegistry::verifyMachinesRegistered);
            // 同上，配方类型的兜底（2026-09 新增）：三条 GTRecipeType 句柄必须已就位。
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ShanhaiRegistry::verifyRecipeTypesRegistered);
            // 同上，物质模块等级条件（module_level）的兜底（2026-09-26 新增）：
            // 「条件没注册上」在日志里与「注册上了」长得一模一样，只有 KJS 配方会静默退回旧形态。
            FMLJavaModLoadingContext.get().getModEventBus().addListener(ShanhaiRegistry::verifyRecipeConditionRegistered);
        } catch (Throwable t) {
            throw new IllegalStateException("[SHANHAI] 无法挂载主机注册兜底校验，拒绝带着「机器可能不存在」的隐患继续加载", t);
        }
    }

    /**
     * 🔴 物质模块等级条件（{@code module_level}）注册的【唯一】入口。
     *
     * <h2>为什么是<b>这个</b>事件、而<b>不是</b> {@code ShanhaiRecipeTypes.init()} 那一拍</h2>
     * <p>「放在与 {@code ShanhaiRecipeTypes.init()} 同相位（freeze 之前）」这个说法是<b>错的</b>，
     * 已用字节码否证 —— 两个 {@code GTRegistry} 是<b>不同的</b>注册表，被冻结的时刻也不同：
     * <pre>
     * GTRecipeConditions.init()   （javap -c，逐条指令）
     *    3: new GTCEuAPI$RegisterEvent（gethold=GTRegistries.RECIPE_CONDITIONS, 泛型实参=RecipeConditionType.class）
     *    0: GTRegistries.RECIPE_CONDITIONS.unfreeze()
     *    9…381: register("biome"|"dimension"|"pos_y"|…|"rpm")   ← GTCEu 自己的 13 条
     *   15: ModLoader.postEvent(event)     ← 🔴 给 addon 的窗口
     *   21: GTRegistries.RECIPE_CONDITIONS.freeze()
     *
     * GTRecipeConditions.init() 的调用点在 CommonProxy.init() 里排在第 43 条指令，
     * 而 GTRecipeTypes.init()（也就是 ShanhaiRecipeTypes.init() 被触发的那一拍）排在第 94 条
     *   （同一次 javap：40: GTRecipeCapabilities.init / 43: GTRecipeConditions.init /
     *     49: GTElements.init / 70: GTCompassSections.init / 94: GTRecipeTypes.init / 97: GTMachines.init）
     * </pre>
     * ⇒ <b>等 {@code ShanhaiRecipeTypes.init()} 跑的时候，{@code RECIPE_CONDITIONS} 早就是 frozen 了</b>，
     * 那时候注册只会抛 {@code IllegalStateException: [register] registry gtceu:recipe_condition has been frozen}
     * （{@code GTRegistry.register} 偏移 1-4 就是 {@code getfield frozen; ifeq 31} ⇒ frozen 时抛）。
     * 而且 <b>不能</b>自己 {@code unfreeze()}：{@code GTRegistry.unfreeze()} 带一道
     * {@code checkActiveModContainerIsGregtech()} 门禁，在我们自己的事件回调里 active container 不是
     * gtceu/minecraft ⇒ <b>静默什么都不做</b>（不抛异常），紧接着 register 就炸。
     * <p>⇒ 正确时机 = <b>GTCEu 自己 post 的那个 {@code RegisterEvent&lt;String, RecipeConditionType&gt;}</b>，
     * 机制与 {@code MachineDefinition} / {@code GTRecipeType} 两条<b>逐字同款</b>
     * （本工程已在这两条上稳定运行），不是自造的第三种时机。
     */
    public static void onRecipeConditionRegister(GTCEuAPI.RegisterEvent<String, RecipeConditionType> event) {
        // ⑥ 物质模块等级条件（老山海的 module_level，见 ModuleLevelCondition 类注释）
        GTRegistries.RECIPE_CONDITIONS.register("module_level", ModuleLevelCondition.TYPE);
        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] 配方条件已注册：module_level（物质模块等级门槛，"
                + "配方写法 .addCondition(new ModuleLevelCondition('<物质模块物品id>', <数量>))）；"
                + "此刻 RECIPE_CONDITIONS 未冻结（本回调由 GTRecipeConditions.init() 在 freeze 之前 post）。");
    }

    /**
     * CommonSetup（全部注册表已冻结）兜底：{@code module_level} 条件类型必须已注册。
     *
     * <p>判据可直接 grep：{@code [SHANHAI-SPEC] 配方条件兜底校验通过：module_level = <非 null>}。
     * 没注册上就当场抛 —— 否则唯一症状是「KJS 配方悄悄退回旧形态」，那正是本项目最忌讳的
     * 「悄悄不发生与出故障长得一样」。
     */
    private static void verifyRecipeConditionRegistered(FMLCommonSetupEvent event) {
        final RecipeConditionType<?> type = GTRegistries.RECIPE_CONDITIONS.get("module_level");
        if (type == null) {
            throw new IllegalStateException("[SHANHAI] 配方条件 module_level 没有被注册："
                    + "RecipeConditionType 的 GTCEuAPI.RegisterEvent 监听器没有生效。"
                    + "调用链应为 ShanhaiMod 构造器 → modEventBus.addGenericListener(RecipeConditionType.class, "
                    + "ShanhaiRegistry::onRecipeConditionRegister) → GTRegistries.RECIPE_CONDITIONS.register(...)。"
                    + "⚠️ 不要改到 ShanhaiRecipeTypes.init() 那一拍去注册 —— 那时 gtceu:recipe_condition 已冻结"
                    + "（GTRecipeConditions.init 在 CommonProxy.init 第 43 条指令，GTRecipeTypes.init 在第 94 条）。");
        }
        if (type != ModuleLevelCondition.TYPE) {
            throw new IllegalStateException("[SHANHAI] module_level 这个键指向的不是本工程的 "
                    + "ModuleLevelCondition.TYPE（实际 = " + type + "）⇒ 有别的 mod 抢注了同名键。"
                    + "KJS 里 new ModuleLevelCondition(...) 仍能构造，但它进不了配方。");
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] 配方条件兜底校验通过：module_level = {}", type);
    }

    /**
     * 🔴 配方类型注册的唯一入口：GTCEu 在 {@code GTRecipeTypes.init()} 里 post 的
     * {@code GTCEuAPI.RegisterEvent&lt;ResourceLocation, GTRecipeType&gt;}。
     *
     * <p>由 {@code ShanhaiMod} 构造器注册到 mod 事件总线（<b>不要</b>改成直接调用 ——
     * 直接调会撞「注册表已冻结」，参见 {@link ShanhaiRecipeTypes} 类注释里的窗口字节码）。
     *
     * <p>时机上与机器注册<b>没有耦合</b>：注册类型与挂机器是两件事，
     * 谁先谁后都不影响 24 台模块与主机。
     */
    public static void onRecipeTypeRegister(GTCEuAPI.RegisterEvent<ResourceLocation, GTRecipeType> event) {
        // ⑤ 全部 41 条真类型（显示类型 0 条）—— 逐条照抄原版 DShanhaiRecipeTypes 的<b>真类型</b>部分。
        //    ⚠️ 2026-09-27 订正：本行原文写「全部 76 条配方类型（40 真类型 + 36 显示类型）」，
        //       这三个数<b>全部作废</b>：真类型实为 41 条（第 41 条 = primordial_matter_deconstruction，
        //       2026-09-26 用户点单），36 个 GTNH 显示类型已<b>整组删除</b>（NINE_INDUSTRIAL_MODES /
        //       DISPLAY_TYPE_COUNT / countMissingDisplay() 都不复存在），故显示类型为 0。
        //       权威数字以 ShanhaiRecipeTypes.REAL_TYPE_COUNT（= 41）为准，不要再手写条数。
        ShanhaiRecipeTypes.init();
    }

    /**
     * CommonSetup（全部注册表已冻结）兜底：41 条真类型句柄必须全部就位。
     *
     * <p>🔴 <b>2026-09-23 订正</b>（用户裁决裁剪配方类型注册后）：本方法原先按「76 条」校验，
     * 现改为按 {@code ShanhaiRecipeTypes.REAL_TYPE_COUNT}（=<b>41</b>）；
     * 36 个显示类型整组删除 ⇒ <b>不再有"显示类型"这一路校验，也不再引用 {@code NINE_INDUSTRIAL_MODES}</b>。
     *
     * <p>⚠️ <b>2026-09-27 二次订正</b>：上面那句里的「=<b>41</b>」当时写的是「=<b>16</b>」，
     * 而且本段首句当时写「16 条配方类型句柄」—— 两个 16 都已作废：
     * {@code REAL_TYPE_COUNT} 早已随第 41 条（{@code primordial_matter_deconstruction}，
     * 2026-09-26 用户点单）改成 <b>41</b>，而「16」是 2026-09-23 裁剪后的<b>中间态</b>。
     * 现状：真类型 <b>41</b> 条 / 显示类型 <b>0</b> 条（36 个 GTNH 显示类型已整组删除）。
     * ⇒ 别再手写条数，一律引用 {@code ShanhaiRecipeTypes.REAL_TYPE_COUNT}。
     * 这是 CommonSetup 的<b>硬失败</b>，写错会直接崩在启动期 —— 故本方法内的每个符号都必须是存活字段。
     *
     * <p>「配方类型没注册上」这类失败在日志里与「注册上了」长得一模一样（都是什么都没发生），
     * 所以这里把它变成加载期响亮失败 —— 与 {@link #verifyMachinesRegistered} 同一条纪律。
     */
    private static void verifyRecipeTypesRegistered(FMLCommonSetupEvent event) {
        if (!ShanhaiRecipeTypes.allRegistered()) {
            throw new IllegalStateException("[SHANHAI] 配方类型没有注册全（2026-09-23 裁剪后应恰为真类型 "
                    + ShanhaiRecipeTypes.REAL_TYPE_COUNT + " 条；36 个显示类型已整组删除）。"
                    + "抽查三句柄＝primordial_matter_recombination="
                    + ShanhaiRecipeTypes.PRIMORDIAL_MATTER_RECOMBINATION
                    + " / primordial_stellar_reaction=" + ShanhaiRecipeTypes.PRIMORDIAL_STELLAR_REACTION
                    + " / taixu_smelting=" + ShanhaiRecipeTypes.TAIXU_SMELTING
                    + "。调用链应为 ShanhaiMod 构造器 → modEventBus.addGenericListener(GTRecipeType.class, "
                    + "ShanhaiRegistry::onRecipeTypeRegister) → ShanhaiRecipeTypes.init()。"
                    + "若 GTCEu 改了事件类型/泛型，这里会当场炸，而不是让它们在 JEI / 语言文件里静默消失。");
        }
    }

    /**
     * 🔴 机器注册的唯一入口：GTCEu 在 {@code GTMachines.init()} 里 post 的
     * {@code GTCEuAPI.RegisterEvent&lt;ResourceLocation, MachineDefinition&gt;}。
     *
     * <p>由 {@code ShanhaiMod} 构造器注册到 mod 事件总线（<b>不要</b>改成直接调用）。
     */
    public static void onMachineRegister(GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition> event) {
        // ③ 主机（青铜神锻 / 原始终焉引擎）
        ShanhaiMachines.init();
        // ④ 由 ShanhaiMachines.init() 内部调用 ModuleRegistry.init()，
        //    保持与原冻结链完全一致的顺序，只是整体挪到了正确的时机。
        //
        // ⑤ 超级通配符ME样板总成（2026-10-01 用户点单）：通配符ME样板总成的全部功能 + 10 个通配符样板槽。
        //    ⚠️ 必须排在 ShanhaiMachines.init() 之后：后者末尾有「模块表为空就抛」的硬校验，
        //       那一段的失败语义是"模块链坏了"；把本行插在它前面会让该判据指向错的元凶。
        ShanhaiWildcardMachines.init();
        // ⑥ 星律样板总成（阶段一骨架）：普通样板槽 1080 格（9×6×20 页）、可翻页。
        //    ⚠️ 同样必须排在 ShanhaiMachines.init() 之后，理由与上一行相同。
        ShanhaiStellarMachines.init();
    }

    /** CommonSetup（全部注册表已冻结）兜底：机器定义句柄必须已经就位。 */
    private static void verifyMachinesRegistered(FMLCommonSetupEvent event) {
        if (ShanhaiMachines.primordialOmegaEngine() == null) {
            throw new IllegalStateException("[SHANHAI] 主机 shanhai:"
                    + ShanhaiMachines.PRIMORDIAL_OMEGA_ENGINE_ID
                    + " 没有被注册：MachineDefinition 的 GTCEuAPI.RegisterEvent 监听器没有生效。"
                    + "调用链应为 ShanhaiMod 构造器 → modEventBus.addGenericListener(MachineDefinition.class, "
                    + "ShanhaiRegistry::onMachineRegister) → ShanhaiMachines.init()。"
                    + "若 GTCEu 改了事件类型/泛型，这里会当场炸，而不是让主机在游戏里静默消失。");
        }
        // 超级通配符ME样板总成：同一道兜底。
        // 🔴 为什么必须在这里硬失败：这台仓室「没注册上」在日志里与「注册上了」长得一模一样
        //    （都是什么都没发生），但宿主 KubeJS 的无限元件物品列表【按字符串 id】引用它
        //    （kubejs:super_ae_infinite_cell 的 KeyList）⇒ 缺了就是 KeyList 里一条
        //    AEKeyHelper.item(...) 解析到 null 的异常，会把整段启动脚本一起带下去 ——
        //    那是「悄悄不发生」，本工程最忌讳的一类失败。
        if (ShanhaiWildcardMachines.superWildcardPatternBuffer() == null) {
            throw new IllegalStateException("[SHANHAI] 仓室 shanhai:"
                    + ShanhaiWildcardMachines.SUPER_WILDCARD_PATTERN_BUFFER_ID
                    + " 没有被注册：调用链应为 ShanhaiMod 构造器 → modEventBus.addGenericListener("
                    + "MachineDefinition.class, ShanhaiRegistry::onMachineRegister) → ShanhaiWildcardMachines.init()。");
        }
    }
}
