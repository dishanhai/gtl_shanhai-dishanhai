package com.shanhai.common.compat;

import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes;
import com.gtladd.gtladditions.utils.antichrist.AntichristPosHelper;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 全工程【唯一】引用 gtladditions 非 api 符号的文件。
 *
 * <p>当前转发<b>两组</b>非 api 符号，都注明来源 FQN：
 * <ul>
 *   <li>结构/几何四个：{@code com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader}
 *       与 {@code com.gtladd.gtladditions.utils.antichrist.AntichristPosHelper}；</li>
 *   <li>配方类型十三个（2026-09-22 新增）：{@code com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes}。
 *       <br>⚠️ 本行数字是历史留档：2026-10-03 再 +1（{@link #evolutionOfPrimordial()} 太素衍化）、
 *       2026-11-01 再 +2（{@link #chaosWeave()} 混沌编织、{@link #starCoreStripper()} 星核剥离）
 *       ⇒ <b>现共 16 个</b>。</li>
 * </ul>
 *
 * <h2>为什么存在这个类</h2>
 * 主机与模块的几何、结构数据都来自 gtladditions 的伪神之锻炉（ForgeOfTheAntichrist）。
 * 把全部这类调用收敛到一个薄适配类里，将来上游签名变动时只需改这一个文件。
 *
 * <h2>🔴 为什么严禁引用 MultiBlockStructure</h2>
 * <ol>
 *   <li><b>共享可变单例</b>：{@code MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST()} 走
 *       {@code kotlin.Lazy} 缓存，返回<b>同一个</b> {@link FactoryBlockPattern} 实例；
 *       而 {@code FactoryBlockPattern.where(char, pred)} 是 {@code symbolMap.put(...); return this;}
 *       —— <b>原地修改，无拷贝、无 clone</b>。gtladditions 自家主机 + 5 台模块机共 6 处引用同一 getter，
 *       我们一调 {@code where()} 就是在改<b>别人</b>的符号表（顺序依赖：后 build 的一方对未显式设置的符号
 *       会静默继承对方的谓词）。旧山海正是这么写的——那是在赌 mod 加载顺序，重写版不继承这个赌注。</li>
 *   <li><b>跨变体唯一不兼容的类</b>：实测 4 变体 × 5 类哈希矩阵，我们依赖的
 *       {@code StructureResourceLoader} / {@code AntichristPosHelper} / {@code ForgeOfTheAntichrist} /
 *       {@code ForgeOfTheAntichristModuleBase} 在「非亚空间 3.2.8」与「亚空间 3.2.8」两个变体间<b>逐字节相同</b>；
 *       <b>唯一不同的就是 {@code MultiBlockStructure}</b>。任何一处引用都会打破"跨变体二进制安全"这个性质，
 *       导致部署到另一个变体时 {@code NoSuchMethodError}。</li>
 * </ol>
 * 安全等价入口是 {@link StructureResourceLoader#loadFactoryPattern}：内部每次
 * {@code FactoryBlockPattern.start()} <b>新建实例</b>（缓存只存 {@code String[][]} 行数据），
 * 同一份 {@code .bin}、同一套几何，只是不碰全局状态。
 */
public final class GtlAddCompat {

    private GtlAddCompat() {}

    /**
     * 伪神之锻炉<b>主机</b>几何。每次返回全新、可安全改写的 {@link FactoryBlockPattern}。
     *
     * <p>⚠️ <b>必须传 3 个方向参数</b>。{@code loadFactoryPattern} 的合法分支只有 0 或 3
     * （传 1/2 个会直接抛异常）；而 {@code FactoryBlockPattern.start()} 的默认值是
     * {@code (LEFT, UP, FRONT)}，主机 {@code .bin} 却是按 {@code (LEFT, DOWN, BACK)} 布局的
     * —— <b>三轴里两轴相反</b>。省略方向参数会让整座 187 层结构镜像翻转，
     * 16 个 {@code J} 格落到完全不同的世界坐标，于是 {@link #moduleSlots} 永远指不到槽位，
     * <b>且不抛异常、不打日志</b>。
     */
    public static FactoryBlockPattern hostPattern() {
        return StructureResourceLoader.INSTANCE.loadFactoryPattern(
                "multiblock/forge_of_the_antichrist.bin", "forge_of_the_antichrist",
                RelativeDirection.LEFT, RelativeDirection.DOWN, RelativeDirection.BACK);
    }

    /**
     * 伪神之锻炉<b>模块</b>机几何。
     *
     * <p>⚠️ 与主机不同，这里要用 <b>0 参重载</b>（模块那份 lambda 传 0 个 {@code RelativeDirection}，
     * 即默认 {@code (LEFT, UP, FRONT)}）。<b>两份 {@code .bin} 的朝向约定本来就不同，禁止互相套用。</b>
     */
    public static FactoryBlockPattern modulePattern() {
        return StructureResourceLoader.INSTANCE.loadFactoryPattern(
                "multiblock/forge_of_the_antichrist_module.bin", "forge_of_the_antichrist_module");
    }

    /**
     * <b>归墟视界枢核</b>（{@code shanhai:guixu_vision_core}）主机几何 —— 我们<b>自己的</b> {@code .bin}。
     *
     * <p>与 {@link #hostPattern()} 走<b>同一条</b>安全入口（每次 {@code FactoryBlockPattern.start()}
     * 新建实例、绝不碰 {@code MultiBlockStructure} 那个共享可变单例），区别只在资源路径：
     * <ul>
     *   <li>资源 = {@code multiblock/guixu_vision_core.bin}（由 {@code temp/host-shape/gen5.mjs} 的
     *       {@code --bin} 生成：148×135×131、18,533 个方块、24 个模块槽 'M'、1 个控制器 '~'）；</li>
     *   <li>id = {@code guixu_vision_core}（loader 会校验 .bin 头里的 id，不一致直接抛）；</li>
     *   <li>方向三元组与 {@link #hostPattern()} <b>完全相同</b>的 {@code (LEFT, DOWN, BACK)} ——
     *       实测轴映射是「aisle 轴 = 机器朝向的反方向、row 轴 = -Y、col 轴 = UP × 朝向」，
     *       {@code com.shanhai.machine.host.GuixuHostGeometry#rotate} 按同一套写（两处必须同源）。</li>
     * </ul>
     *
     * <p>🔴 <b>文件为什么放在 {@code assets/gtladditions/structures/multiblock/} 下</b>：
     * {@code StructureResourceLoader} 的根路径是<b>写死</b>的
     * {@code "assets/gtladditions/structures"}（反编译实证：{@code ROOT_PATH} 常量），
     * 且它用 {@code getClass().getClassLoader().getResourceAsStream(...)} 按<b>类路径</b>取资源、
     * 不区分是哪个 jar。所以我们把自己的 {@code .bin} 放进本 mod jar 的同一路径即可被它读到；
     * <b>不需要</b>、也<b>不允许</b>去改 gtladditions 的 jar。文件名带 {@code guixu_} 前缀避免重名。
     */
    public static FactoryBlockPattern guixuVisionCorePattern() {
        return StructureResourceLoader.INSTANCE.loadFactoryPattern(
                "multiblock/guixu_vision_core.bin", "guixu_vision_core",
                RelativeDirection.LEFT, RelativeDirection.DOWN, RelativeDirection.BACK);
    }

    /**
     * 16 个模块槽位（主机侧正算）。纯函数、无共享状态。
     *
     * <p>委托给 {@link AntichristPosHelper}，与 gtladditions 那台<b>在游戏里正常工作</b>的主机
     * {@code ForgeOfTheAntichrist.getModuleScanPositions()} 走<b>完全相同</b>的代码路径
     * （它也是直接 {@code return AntichristPosHelper.INSTANCE.calculateModulePositions(...)}）。
     * 这是"几何与真实结构自洽"的唯一证据来源，不要用手推公式替代。
     */
    public static BlockPos[] moduleSlots(BlockPos hostPos, Direction hostFacing) {
        return AntichristPosHelper.INSTANCE.calculateModulePositions(hostPos, hostFacing);
    }

    /**
     * 16 个候选主机位（模块侧反算）。纯函数、无共享状态。
     *
     * <p>与 {@link #moduleSlots} 是同一 helper 上的一对互逆函数，因此主机与模块<b>在构造上必然一致</b>。
     */
    public static BlockPos[] candidateHosts(BlockPos modulePos, Direction facing) {
        return AntichristPosHelper.INSTANCE.calculatePossibleHostPositions(modulePos, facing);
    }

    // ═════════════════════ gtladditions 的【配方类型】转发（2026-09-22 新增）═════════════════════
    //
    // 🔴 全部 13 个都转发自同一个 FQN：
    //    com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes
    //    （⚠️ 注意包名是 `common.recipe` —— 和 `MultiBlockStructure` 一样属于【非 api】包。）
    //
    // 为什么必须走这里、不能在 ModuleRegistry 里直接引用：
    //   本工程有一条明写的隔离墙 ——「`common` 侧唯一引用 gtladditions 非 API 的类是 GtlAddCompat」。
    //   批二有 7 台模块要用这些类型（永恒熔炼炉 / 生物核心 / 混沌蜉蝣炉 / 反熵凝聚核心 /
    //   韶光聚合核心 / 未央重构模块 / 蚀刻模块）；直接引用会开第二个非 API 触点，
    //   而那是**不可逆的架构让步**（2026-09-22 队长裁决：选薄封装）。
    //
    // ⚠️ 该类里**两种访问形态并存**，别"统一"掉（`javap` 实测）：
    //   · `MOLECULAR_DECONSTRUCTION` 是 **静态字段**；
    //   · 其余 12 个是 Kotlin object 上的 **getter**（`GTLAddRecipesTypes.INSTANCE.getXxx()`）。
    //
    // ⚠️ 这些 getter 返回的是 Kotlin object 在**首次访问时**构造/注册的 `GTRecipeType`。
    //   与 `GTLAddSoundEntries` 不同，这里返回的是**已注册的配方类型句柄**（gtladditions 自己
    //   在它的配方类型注册窗口里建好的），不是"再去注册一次"——但**本工程尚未在真机验证过这一点**，
    //   故本段全部标为"待冒烟"。[推断]

    /** 转发自 {@code com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes.MOLECULAR_DECONSTRUCTION}（**静态字段**，非 getter）。 */
    public static GTRecipeType molecularDeconstruction() {
        return GTLAddRecipesTypes.MOLECULAR_DECONSTRUCTION;
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getCHAOTIC_ALCHEMY()}。 */
    public static GTRecipeType chaoticAlchemy() {
        return GTLAddRecipesTypes.INSTANCE.getCHAOTIC_ALCHEMY();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getSTELLAR_LGNITION()}（⚠️ 原版拼写就是 `LGNITION`，不是 `IGNITION`）。 */
    public static GTRecipeType stellarIgnition() {
        return GTLAddRecipesTypes.INSTANCE.getSTELLAR_LGNITION();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getBIOLOGICAL_SIMULATION()}。 */
    public static GTRecipeType biologicalSimulation() {
        return GTLAddRecipesTypes.INSTANCE.getBIOLOGICAL_SIMULATION();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getSPACE_ORE_PROCESSOR()}。 */
    public static GTRecipeType spaceOreProcessor() {
        return GTLAddRecipesTypes.INSTANCE.getSPACE_ORE_PROCESSOR();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getANTIENTROPY_CONDENSATION()}（⚠️ 原版拼写 `ANTIENTROPY`，不是 `ANTI_ENTROPY`）。 */
    public static GTRecipeType antientropyCondensation() {
        return GTLAddRecipesTypes.INSTANCE.getANTIENTROPY_CONDENSATION();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getLEYLINE_CRYSTALLIZE()}。 */
    public static GTRecipeType leylineCrystallize() {
        return GTLAddRecipesTypes.INSTANCE.getLEYLINE_CRYSTALLIZE();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getMATTER_EXOTIC()}。 */
    public static GTRecipeType matterExotic() {
        return GTLAddRecipesTypes.INSTANCE.getMATTER_EXOTIC();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getNIGHTMARE_CRAFTING()}。 */
    public static GTRecipeType nightmareCrafting() {
        return GTLAddRecipesTypes.INSTANCE.getNIGHTMARE_CRAFTING();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getEM_RESONANCE_CONVERSION_FIELD()}。 */
    public static GTRecipeType emResonanceConversionField() {
        return GTLAddRecipesTypes.INSTANCE.getEM_RESONANCE_CONVERSION_FIELD();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getTECTONIC_FAULT_GENERATOR()}。 */
    public static GTRecipeType tectonicFaultGenerator() {
        return GTLAddRecipesTypes.INSTANCE.getTECTONIC_FAULT_GENERATOR();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getVOIDFLUX_REACTION()}。 */
    public static GTRecipeType voidfluxReaction() {
        return GTLAddRecipesTypes.INSTANCE.getVOIDFLUX_REACTION();
    }

    /** 转发自 {@code …common.recipe.GTLAddRecipesTypes.INSTANCE.getPHOTON_MATRIX_ETCH()}。 */
    public static GTRecipeType photonMatrixEtch() {
        return GTLAddRecipesTypes.INSTANCE.getPHOTON_MATRIX_ETCH();
    }

    /**
     * 转发自 {@code …common.recipe.GTLAddRecipesTypes.EVOLUTION_OF_PRIMORDIAL}
     * （🆕 2026-10-03；<b>public 静态字段</b>，不是 Kotlin object 的 getter —— 与
     * {@link #molecularDeconstruction()} 同一种形态，见本类注释里"两种访问形态并存"那一段）。
     *
     * <p><b>中文名 = 太素衍化</b>；<b>注册 id = {@code gtceu:evolution_of_primordial}</b>
     * —— 命名空间是 {@code gtceu}，<b>不是</b> {@code gtladditions}。实证（本轮实际执行，非推断）：
     * <ol>
     *   <li>{@code javap -c …GTLAddRecipesTypes} 的 {@code <clinit>} 里，偏移 1192 是
     *       {@code ldc_w #635 // String evolution_of_primordial}，紧接
     *       {@code invokestatic GTRecipeTypes.register:(String,String,RecipeType[])} ⇒ gtladditions
     *       <b>也是调 gtceu 自己的 register</b>；</li>
     *   <li>{@code javap -c …common.data.GTRecipeTypes} 的 {@code register(String,String,RecipeType...)}
     *       第一句就是 {@code new GTRecipeType(GTCEu.id(name), group, proxyRecipes)}；</li>
     *   <li>{@code javap -c …GTCEu.id(String)} = {@code new ResourceLocation("gtceu", toLowerCaseUnder(name))}
     *       ⇒ 任何走这条路注册的类型，其 registryName 恒为 {@code gtceu:<id>}。</li>
     * </ol>
     * 旁证：语言文件键也是 {@code gtceu.evolution_of_primordial}（不在 {@code gtladditions.*} 下），
     * 出处 {@code handoff/outbound/类型名自动反查.md:514}。
     */
    public static GTRecipeType evolutionOfPrimordial() {
        return GTLAddRecipesTypes.EVOLUTION_OF_PRIMORDIAL;
    }

    /**
     * 转发自 {@code …common.recipe.GTLAddRecipesTypes.CHAOS_WEAVE}
     * （🆕 2026-11-01；<b>public 静态字段</b>，不是 Kotlin object 的 getter —— 与
     * {@link #molecularDeconstruction()} / {@link #evolutionOfPrimordial()} 同一种形态。
     * 实证：{@code javap -p …GTLAddRecipesTypes} 的第一行字段就是
     * {@code public static final com.gregtechceu.gtceu.api.recipe.GTRecipeType CHAOS_WEAVE;}）。
     *
     * <p><b>中文名 = 混沌编织</b>（用户原话里的"混沌编制"是笔误，2026-11-01 已拍板按官方真名走）；
     * <b>注册 id = {@code gtceu:chaos_weave}</b>。依据：{@code assets/gtceu/lang/zh_cn.json} 里
     * {@code "gtceu.chaos_weave":"混沌编织"}（@ gtladditions jar），且
     * {@code gtceu.machine.taixuturbidarray.tooltip.10} 写着"可用配方类型：混沌编织"
     * ⇒ 它确实是一个配方类型的显示名；export 里该 {@code "type"} 有 7 条配方。
     *
     * <p>⚠️ 为什么必须走这里而不是在 {@code ModuleRegistry} 里直接引用
     * {@code com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes}：
     * 本工程有一条明写的隔离墙 ——「{@code common} 侧<b>唯一</b>引用 gtladditions 非 API 的类是
     * {@link GtlAddCompat}」（见本类类注释）。{@code common.recipe} 属于<b>非 api</b> 包，
     * 直接引用会开第二个非 API 触点，那是不可逆的架构让步。
     * <p>用途：新 #2「原初混沌质能熔炉」（{@code shanhai:primordial_mass_energy_furnace}）的
     * 4 条类型里的第 3 条。
     */
    public static GTRecipeType chaosWeave() {
        return GTLAddRecipesTypes.CHAOS_WEAVE;
    }

    /**
     * 转发自 {@code …common.recipe.GTLAddRecipesTypes#getSTAR_CORE_STRIPPER()}
     * （🆕 2026-11-01；<b>Kotlin object 的 getter</b>，不是静态字段 —— 与
     * {@link #voidfluxReaction()} 等 12 个同一种形态）。
     *
     * <p>实证（{@code javap -p …GTLAddRecipesTypes}，本轮实跑）：
     * <pre>
     *   private static final com.gregtechceu.gtceu.api.recipe.GTRecipeType STAR_CORE_STRIPPER;
     *   public final com.gregtechceu.gtceu.api.recipe.GTRecipeType getSTAR_CORE_STRIPPER();
     *   public static final …GTLAddRecipesTypes INSTANCE;
     * </pre>
     * ⇒ 字段是 <b>private</b>，只能走 {@code INSTANCE.getSTAR_CORE_STRIPPER()}（写静态字段直接编译不过）。
     *
     * <p><b>中文名 = 星核剥离</b>；<b>注册 id = {@code gtceu:star_core_stripper}</b>。
     * 依据：{@code temp/type-coverage/缺口.md} 第 76 行「{@code gtceu:star_core_stripper} ｜ 星核剥离 ｜
     * {@code gtceu.star_core_stripper} @ gtladditions」（该类型 148 条配方，属"未覆盖大缺口"一档）。
     * <p>用途：第 29 台「原初深空汲取核心」（{@code shanhai:primordial_deep_space_extraction_core}）
     * 类型数组的第 9 条（2026-11-01 用户点单，追加在末尾）。
     */
    public static GTRecipeType starCoreStripper() {
        return GTLAddRecipesTypes.INSTANCE.getSTAR_CORE_STRIPPER();
    }
}
