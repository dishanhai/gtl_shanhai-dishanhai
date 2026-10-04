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
 *   <li>配方类型十三个（2026-09-22 新增）：{@code com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes}。</li>
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
}
