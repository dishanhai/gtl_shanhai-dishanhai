package com.shanhai;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.mojang.logging.LogUtils;
import com.shanhai.client.config.ShanhaiClientConfig;
import com.shanhai.common.recipe.PrimordialFormingRecipeProbe;
import com.shanhai.common.recipe.ShanhaiRecipeEditProbe;
import com.shanhai.config.ShanhaiConfig;
import com.shanhai.machine.module.ModuleSetBlockWatch;
import com.shanhai.machine.module.ModuleSlotWatch;
import com.shanhai.registry.ShanhaiRegistration;
import com.shanhai.registry.ShanhaiRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * 山海重构 · 阶段 1 的 mod 入口。
 *
 * <p>本类只负责「能被 Forge 加载」与「按框架约定挂注册链」两件事。
 *
 * <h2>🔴 两条真启动换来的生命周期约定</h2>
 *
 * <h3>约定 1：{@code @Mod} 类必须有无参构造器</h3>
 * Forge 1.20.1 的 {@code FMLModContainer.constructMod()} 走 {@code getDeclaredConstructor()}（<b>无参</b>），
 * <b>不支持</b>把 {@link IEventBus} 注入 {@code @Mod} 构造器（那是 <b>NeoForge</b> 的能力）。
 * 写成 {@code ShanhaiMod(IEventBus)} 会在 mod 构造期抛
 * {@code NoSuchMethodException: com.shanhai.ShanhaiMod.<init>()}。实测于 t7 首次启动。
 * 所以 mod 事件总线只能自己取：{@link FMLJavaModLoadingContext#get()#getModEventBus()}。
 *
 * <h3>约定 2：多方块机器<b>不能</b>在 mod 构造期注册</h3>
 * <b>实测于 t8 第二次启动</b>（日志 {@code GTL山海9.10test/logs/latest.log} L729-L756）：
 * 在构造器里调 {@code REGISTRATE.multiblock(...)} 会炸在
 * <pre>
 *   ExceptionInInitializerError
 *     at MultiblockMachineBuilder.&lt;init&gt;(MultiblockMachineBuilder.java:94)
 *     at GTRegistrate.multiblock(GTRegistrate.java:155)
 *     at com.shanhai.machine.ShanhaiMachines.init(ShanhaiMachines.java:79)
 *     at com.shanhai.ShanhaiMod.&lt;init&gt;(ShanhaiMod.java:51)
 *   Caused by: IllegalStateException: [register] registry gtceu:compass_section has been frozen
 *     at GTRegistry.register(GTRegistry.java:91)
 *     at CompassSection.register(CompassSection.java:64)
 *     at GTCompassSections.&lt;clinit&gt;(GTCompassSections.java:30)
 * </pre>
 * 根因不是「注册表冻结了」这么简单，而是 <b>GTCEu 的内容注册整体发生在 {@code FMLConstructModEvent}
 * 之后</b>（见 {@code ShanhaiRegistry} 类注释的完整取证）。<b>正确时机 = 在 mod 事件总线上挂一个
 * {@code GTCEuAPI.RegisterEvent&lt;ResourceLocation, MachineDefinition&gt;} 泛型监听器</b>，
 * GTCEu 会在它自己的 {@code GTMachines.init()} 里 post 这个事件 —— 这正是同环境里能正常工作的
 * {@code gtladditions} 的做法（{@code GTLAdditions.kt:37} + {@code GTLAdditionsGTAddon}）。
 *
 * <h2>本类的初始化链</h2>
 * <pre>
 * ShanhaiMod（@Mod 构造器，只做「构造期合法」的事）
 *   ├─ ShanhaiRegistration.register(modEventBus)                  // Registrate 挂总线
 *   ├─ modEventBus.addGenericListener(MachineDefinition.class,…)  // 🔴 机器注册的【唯一】正确入口
 *   ├─ modEventBus.addGenericListener(GTRecipeType.class,…)       // 🔴 配方类型注册【唯一】正确入口（2026-09 新增）
 *   ├─ modEventBus.addGenericListener(RecipeConditionType.class,…) // 🔴 配方【条件】注册【唯一】正确入口（2026-09-26 新增）
 *   └─ ShanhaiRegistry.init()                                     // ① 18 个物品（Registrate 入列）
 *                                                                 //   + CommonSetup 兜底校验
 *
 * （随后由 GTCEu 依次 post 事件）
 *   └─ ShanhaiRegistry.onRecipeConditionRegister(…)                // ⑥ module_level（比下面那条【早】）
 *   └─ ShanhaiRegistry.onRecipeTypeRegister(…)                    // ⑤ 40 个配方类型（只注册、不挂机器）
 *   └─ ShanhaiRegistry.onMachineRegister(…)                       // ② 主机 ③ 模块
 * </pre>
 */
@Mod(ShanhaiMod.MOD_ID)
public class ShanhaiMod {

    public static final String MOD_ID = "shanhai";

    /**
     * 本 mod 的日志器。
     *
     * <p>{@code public} 是刻意的：客户端渲染器（{@code PrimordialOmegaEngineModelBuffers}）在
     * VBO 构建失败时要打一条可定位的 warn —— 那条日志必须从这里取，不能各自
     * {@code LogUtils.getLogger()}（各自的 logger 名会让 grep 漏掉）。
     */
    public static final Logger LOGGER = LogUtils.getLogger();

    public ShanhaiMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Forge 原生配置（ForgeConfigSpec）。用 COMMON 而非 CLIENT 的理由见 ShanhaiConfig 类注释。
        // ⚠️ 这一步【不需要】改 mods.toml：registerConfig 是运行期注册，与 mods.toml 无关。
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ShanhaiConfig.COMMON_SPEC);

        // 🔴 客户端配置（终末之环头顶光环的位姿 8 旋钮）。
        //    ① 必须【在这里】注册：放进 FMLClientSetupEvent 的话，游戏内 Mods 列表认不出 Config 按钮，
        //       用户就没法点齿轮调位姿（这条是用户上一个同类 mod 的既有口径）。
        //    ② 用 FMLEnvironment.dist 守卫：专用服务端不该注册客户端配置
        //       （Dist 只是个枚举，没有 @OnlyIn，服务端引用它不会炸）。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ShanhaiClientConfig.CLIENT_SPEC);
        }

        // Registrate 必须挂在任何 builder 入列之前（本工程只有一个实例，见 ShanhaiRegistration）。
        ShanhaiRegistration.register(modEventBus);

        // 🔴 机器（多方块）注册的唯一正确时机。见类注释「约定 2」。
        // 泛型匹配证据：Forge EventBus 的泛型过滤器是 `e.getGenericType() == type`（身份比较），
        // GTCEu 用 `new GTCEuAPI.RegisterEvent<>(GTRegistries.MACHINES, MachineDefinition.class)`
        // 构造事件，其 GenericEvent 泛型实参就是 MachineDefinition.class ⇒ 本监听器必被命中。
        modEventBus.addGenericListener(MachineDefinition.class, ShanhaiRegistry::onMachineRegister);

        // 🔴 配方类型注册的唯一正确时机（2026-09 新增）：GTCEu 的 GTRecipeTypes.init() 里
        // 在 `GTRegistries.RECIPE_TYPES.freeze()` **之前** post 同一个 RegisterEvent，泛型实参 =
        // GTRecipeType.class（字节码偏移 117 `ldc class GTRecipeType` / 122 `postEvent` / 128 `freeze`）。
        // ⇒ 机制与上面机器那条逐字同款，不是本工程自造的第三种时机。
        modEventBus.addGenericListener(GTRecipeType.class, ShanhaiRegistry::onRecipeTypeRegister);

        // 🔴 配方【条件】注册的唯一正确时机（2026-09-26 新增，物质模块等级条件 module_level）：
        // 同一个 RegisterEvent 机制，但**不是**上面那条 —— 条件走的是**另一个**注册表，
        // 由 GTRecipeConditions.init() 在 RECIPE_CONDITIONS.freeze() 之前 post，泛型实参 =
        // RecipeConditionType.class（字节码：unfreeze → 13 条 register → postEvent(偏移15) → freeze(偏移21)）。
        // ⚠️ 它的调用点比 GTRecipeTypes.init() **早**（CommonProxy.init 里 43 < 94）
        // ⇒ 等 ShanhaiRecipeTypes.init() 跑时 RECIPE_CONDITIONS 已冻结，那时注册必抛
        // `[register] registry gtceu:recipe_condition has been frozen`。详见
        // ShanhaiRegistry#onRecipeConditionRegister 的字节码取证。
        modEventBus.addGenericListener(RecipeConditionType.class, ShanhaiRegistry::onRecipeConditionRegister);

        // ① 18 个物品：纯 Registrate builder 入列，不碰 GTRegistry，构造期合法。
        ShanhaiRegistry.init();

        // 🔴 取证探针 [SHANHAI-SLOT-WATCH]（2026-09-26）：显式挂到 FORGE 总线。
        //    为什么不用 @Mod.EventBusSubscriber：那种自动订阅【成功时一行日志都没有】，
        //    「探针到底挂上了没有」在日志上不可判。显式注册 + 紧跟一行日志 ⇒ 可判。
        //    为什么不是 Dist.DEDICATED_SERVER：用户玩单机，单机的服务端逻辑跑在集成服务器上、
        //    Dist 仍是 CLIENT ⇒ 按 DedicatedServer 注册会在单机里静默失效。
        //    「仅服务端」由处理函数里的 ServerLevel 判定执行（见 ModuleSlotWatch 类注释 §3）。
        MinecraftForge.EVENT_BUS.addListener(ModuleSlotWatch::onBlockBreak);
        LOGGER.info("{} 取证探针已挂上 FORGE 事件总线（范围 = 16 个模块位 + 主机自身；仅服务端生效）",
                ModuleSlotWatch.PREFIX);

        // 🔴 取证探针 [SHANHAI-SETBLOCK]（2026-09-26）：挂在【方块写入的真正入口】
        //    net.minecraft.world.level.chunk.LevelChunk#setBlockState（HEAD）—— 全服务端活世界方块写入的唯一漏斗
        //    （Level.setBlock 体内唯一落盘调用，javap 实证见 ModuleSetBlockWatch 类注释 §2）。
        //    ❗它与上面那条【不同】：SLOT-WATCH 站在「机器被移除」这个【下游结果】上，
        //    对"非玩家路径直接改坐标上的方块"可能一次都不响；本条直接记【调用者栈】=「谁写的」。
        //    这里排一次【挂载自证】（世界加载后跑）：挂上了打 INFO；没挂上打 ERROR 并写明
        //    「不要据此判断没有人写方块」——防止"探针没工作"被读成"没人写"。
        //    ⚠️ 自证【不能】放在本构造器里：冒烟实测那一拍 LevelChunk 还没被混入，反射看到的是未混入的类
        //    ⇒ 会打出一条假阴性的「探针未挂上」。⇒ 挂到 ServerStartedEvent（见 ModuleSetBlockWatch#onServerStarted）。
        MinecraftForge.EVENT_BUS.addListener(ModuleSetBlockWatch::onServerStarted);

        // 🔴 取证探针 [SHANHAI-PFORM]（2026-10-01）：新类型「原初物质定型的配方进没进配方表」。
        //    ⚠️ 2026-10-01 迁移后更新：这 2457 条的正文【已从数据包搬到 KubeJS】
        //    （kubejs\server_scripts\[server_scripts]shanhai_primordial_forming.js），
        //    数据包那边已搬走。探针本身【不用改】—— 它读的是服务器自己的 RecipeManager，
        //    数据包配方与 KJS 配方落进的是同一个桶。
        //    这条链上"配方被静默丢弃"与"全部加载成功"在日志上长得一模一样
        //    ⇒ 在 ServerStartedEvent 上【现数一遍】并打出可比对的数字（只读，不写配方表）。
        //    ⚠️ 用不带优先级的 addListener（与上面两条探针同款，已实测能编译）：
        //       "原有两类型条数"因此可能早于 KubeJS 的 ServerEvents.loaded —— 那一条只作参考口径，
        //       主判据（新类型条数）不受影响，因为没有任何脚本会动它。
        MinecraftForge.EVENT_BUS.addListener(PrimordialFormingRecipeProbe::onServerStarted);
        LOGGER.info("{} 配方条数探针已挂上 FORGE 事件总线（只读；判据 = 新类型条数 == 声明值 且 模头残留 = 0）",
                PrimordialFormingRecipeProbe.PREFIX);

        // 🔴 取证探针 [SHANHAI-EDITPROBE]（2026-10-04）：证明「运行期改一条已注册配方 ⇒ 立刻生效」这条路成立。
        //    机制 = 把 GTCEu 的输入索引 GTRecipeLookup 整个重建（removeAllRecipes + 逐条 addRecipe），
        //    与老 gt_shanhai 的 DShanhaiRecipeModifierAPI.removeAndSync 是同一套做法（逐条反编译取证见类注释）。
        //    🔴 它【会真的改写】运行期索引（读完立刻还原）⇒ 默认不开，必须给环境变量
        //    SHANHAI_EDITPROBE=1 或建 config/shanhai-editprobe.flag 才跑；开关判定结果无条件打日志。
        //    挂 ServerStartedEvent 的理由：那时 KubeJS 那批配方也已经落进 RecipeManager 与索引了。
        MinecraftForge.EVENT_BUS.addListener(ShanhaiRecipeEditProbe::onServerStarted);
        LOGGER.info("{} 配方运行期编辑探针已挂上 FORGE 事件总线（默认不开；开关 = 环境变量 {} 或文件 {}）",
                ShanhaiRecipeEditProbe.PREFIX, ShanhaiRecipeEditProbe.ENV_ENABLE,
                ShanhaiRecipeEditProbe.FLAG_FILE);

        LOGGER.info("[SHANHAI] {} 已加载（阶段 1）", MOD_ID);
    }
}
