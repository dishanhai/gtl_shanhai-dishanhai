package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.integration.jade.provider.MachineModeProvider;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 山海重构 · 让<b>主机（归墟视界枢核）与原初模块</b>在<b>跨配方种类并行真在生效</b>时，
 * 把 Jade 的「机器模式」那一行换成<b>新的模式名</b>；
 * <b>其余一切情形（含所有不是本工程的机器）一个字节都不动</b>。
 *
 * <h2>1. 用户原话（逐字）</h2>
 * <blockquote>「伪神也是跨配方种类并行，它 jade 里面显示是「群星烧却」（一个新的名字），
 * 我们的主机和模块跨配方种类并行还是老的单个配方的名字，会引起歧义，
 * 所以你也分别给模块和主机起个新的名字放到 jade 还有机器里面显示」</blockquote>
 * 名字本体是 {@link PrimordialOmegaEngineMachine#CROSS_TYPE_MODE_NAME_HOST} /
 * {@link PrimordialOmegaEngineMachine#CROSS_TYPE_MODE_NAME_MODULE}（<b>改名只改那两行</b>）。
 *
 * <h2>2. 🔴 「机器模式：%s」那一行到底是谁画的（javap 逐指令实证，不猜）</h2>
 * 目标类 = {@code com.gregtechceu.gtceu.integration.jade.provider.MachineModeProvider}
 * （{@code gtceu-1.20.1-1.4.4.jar}；本工程里那份 javap 转储在
 * {@code temp\machine-custom\javap\MachineModeProvider.c.txt}）：
 * <pre>
 * appendServerData(CompoundTag, BlockAccessor)            ← 服务端，玩家看向方块时才算
 *   :22-32   definition.getRecipeTypes()                  ← 读的是 【definition】的数组
 *   :41-43   arraylength &lt;= 1 ⇒ 直接 return（不写任何键 ⇒ Jade 不画这一行）
 *   :52-65   machine instanceof IRecipeLogicMachine
 *   :76-83   rlm.getRecipeType()                          ← 只用来在数组里找下标（引用相等 if_acmpne）
 *   :139-153 put("RecipeTypes", ListTag[全部类型 registryName]) ＋ put("CurrentRecipeType", 下标|-1)
 *
 * appendTooltip(ITooltip, BlockAccessor, IPluginConfig)   ← 客户端，只读上面那两个键
 *   :48-175  showDetails() ⇒ 先画 "gtceu.top.machine_mode" 表头，再把【全部类型】逐条画出来，
 *            当前那个前缀 " >"（蓝色）
 *   :178-231 收起态 ⇒ 只画 表头 ＋ Component.translatable(ns + "." + path)  ← 【玩家平时看到的那一行】
 * </pre>
 * ⇒ 那一行的字符串 = <b>当前激活配方类型的注册名对应的翻译键</b>。
 * <b>机器类里没有任何方法能改变它</b>（{@code getRecipeType()} 只被用来查下标，
 * 类型名一律取 {@code definition.getRecipeTypes()}）⇒ <b>只能在这里改</b>。
 * <p>📜 同族先例（不是本工程首创）：gtladditions
 * {@code com.gtladd.gtladditions.mixin.gtceu.integration.MachineModeProviderMixin}
 * {@code @Overwrite} 了同一个 {@code appendServerData}，对
 * {@code GTLAddRecipesTypes.MULTIPLE_TYPE_RECIPES} 里的类型强行只写 1 个名字
 * ⇒ 这就是「伪神 jade 显示群星烧却」的实现方式（{@code gtceu.forge_of_the_antichrist} =
 * 「群星烧却」，见 gtladditions 的 {@code assets/gtceu/lang/zh_cn.json}）。
 *
 * <h2>3. 本类怎么做（两处注入，全部只在<b>本工程的机器</b>上生效）</h2>
 * <ol>
 *   <li><b>服务端</b>：{@code appendServerData} 的 {@code @At("RETURN")} 处，
 *       若本次查询的方块是本工程的机器且
 *       {@link PrimordialOmegaEngineMachine#crossTypeModeDisplayName()} 非 {@code null}
 *       （= 跨类型真在生效），就往 Jade 的服务端数据里写一个字符串键
 *       {@link #SHANHAI$MODE_NAME_KEY}。判定<b>全部在服务端做</b> —— 因为模块的 {@code getHost()}
 *       是服务端状态，客户端读不到（与本工程 {@code ParallelProvider} 那两行同一条理由）。</li>
 *   <li><b>客户端</b>：{@code appendTooltip} 的 {@code @At("HEAD")} 处，
 *       若那份服务端数据里<b>有这个键</b>，就画「表头 ＋ 新名字」并 {@code ci.cancel()}
 *       （取消上游那一段渲染）；<b>没有这个键就走上游</b> —— 于是未生效的机器
 *       （老主机 / 未授予 / 锁配方 / 未成型 / 不是本工程的机器）在 Jade 上<b>逐字保持原样</b>。</li>
 * </ol>
 *
 * <h2>4. 为什么 {@code require = 0} ＋ {@code try/catch Throwable}</h2>
 * 纯显示增强：GTCEu 改实现方式时只应该"少一个新名字"，
 * <b>绝不允许把用户的客户端崩掉</b>（与本工程另外两个 Jade mixin 同纪律）。
 * 为了不静默失败，第一次真正换名时打一条 INFO（字面量 {@code [SHANHAI-DISPLAY]}，可 grep）。
 * <p>⚠️ 可验证性边界（诚实声明）：{@code require = 0} ＋ 只有玩家看向方块才会触发
 * ⇒ 无头专服冒烟只能证明"没崩"，证明不了这一行会出现，只能由用户进游戏看一眼。
 *
 * <h2>5. 与 gtladditions 那个 {@code @Overwrite} 共存</h2>
 * Mixin 0.8.5 的 pass 顺序 = MAIN → PREINJECT → INJECT（见
 * {@code ShanhaiInfiniteThreadDisplayMixin} 里 2026-09-26 那次逐字节核实）
 * ⇒ {@code @Overwrite} 在 MAIN pass 落地、本类的 {@code @Inject} 在 INJECT pass 落地
 * ⇒ 本类的 RETURN 注入<b>一定作用在覆盖后的方法体上</b>，后写者胜。
 * 且两边不冲突：它只在"类型属于 {@code MULTIPLE_TYPE_RECIPES}"时改写，本工程的三个配方类型都不在其中。
 */
@Mixin(value = MachineModeProvider.class, priority = 500, remap = false)
public class ShanhaiMachineModeNameMixin {

    /**
     * 承载「要显示的新模式名」的 Jade 服务端数据键（{@code String}）。
     *
     * <p>🔴 <b>键不存在 = 不换名</b>：这是"未生效时逐字保持原样"的<b>结构性</b>保证 ——
     * 客户端只认"有键"这一件事，没有任何"猜状态"的分支。
     */
    @Unique
    private static final String SHANHAI$MODE_NAME_KEY = "shanhai_machine_mode_name";

    /** 只打一次，避免每次刷新都刷屏（与另外两个 Jade mixin 同一个理由）。 */
    @Unique
    private static boolean shanhai$modeNameAnnounced = false;

    /**
     * 「这台机器该显示哪个模式名」——<b>只认本工程的两种机器类</b>，其余一律 {@code null}。
     *
     * <h2>为什么必须分成两个 {@code instanceof}</h2>
     * {@code PrimordialOmegaEngineModuleBase} <b>不是</b> {@code PrimordialOmegaEngineMachine} 的子类
     * （它 {@code extends WorkableElectricMultiblockMachine}，只实现了
     * {@code IModularMachineModule}）⇒ 一个 {@code instanceof PrimordialOmegaEngineMachine}
     * 只能覆盖主机。两种机器的同名方法<b>语义逐字相同</b>
     * （{@code null} = 不换名），契约本体与名字常量都在 {@link PrimordialOmegaEngineMachine} 上。
     *
     * <p>任何异常 ⇒ {@code null}（= 不换名，走上游原路径）：显示路径不许把查询打断。
     */
    @Unique
    private static String shanhai$modeNameOf(MetaMachine machine) {
        if (machine instanceof PrimordialOmegaEngineMachine host) {
            return host.crossTypeModeDisplayName();
        }
        if (machine instanceof PrimordialOmegaEngineModuleBase module) {
            return module.crossTypeModeDisplayName();
        }
        return null;
    }

    /**
     * 服务端：跨类型真在生效时，把新名字写进 Jade 的服务端数据（<b>只写显示用 NBT，不参与任何运算</b>）。
     *
     * @param data     Jade 的服务端数据 tag（同一实例会发给客户端）
     * @param accessor 本次查询的方块访问器
     */
    @Inject(
            method = "appendServerData(Lnet/minecraft/nbt/CompoundTag;Lsnownee/jade/api/BlockAccessor;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0)
    private void shanhai$writeModeName(CompoundTag data, BlockAccessor accessor, CallbackInfo ci) {
        try {
            if (data == null || accessor == null) {
                return;
            }
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) {
                return;
            }
            final MetaMachine metaMachine = mbe.getMetaMachine();
            if (metaMachine == null) {
                return;
            }
            // null ⇒ 跨类型没生效（老主机/未授予/锁配方/未成型/发电那台/不是本工程的机器）⇒【一个键都不写】。
            final String name = shanhai$modeNameOf(metaMachine);
            if (name == null) {
                return;
            }
            data.putString(SHANHAI$MODE_NAME_KEY, name);
            if (!shanhai$modeNameAnnounced) {
                shanhai$modeNameAnnounced = true;
                GTDishanhaiMod.LOGGER.info("[SHANHAI-DISPLAY] 已往 Jade 写机器模式名 {}={}（机器 {}）"
                                + "（用户 2026-10-25：跨配方种类并行时换名，避免被读成只会跑一类；此后不再重复打）",
                        SHANHAI$MODE_NAME_KEY, name, metaMachine.getDefinition() == null ? "?" :
                                metaMachine.getDefinition().getId());
            }
        } catch (Throwable ignored) {
            // 纯显示路径：任何异常都不允许影响游戏
        }
    }

    /**
     * 客户端：服务端数据里<b>有这个键</b>时，画「机器模式：&lt;新名字&gt;」并取消上游那一段渲染。
     *
     * <p>格式与上游收起态那一行保持一致（同样是 {@code Component.translatable("gtceu.top.machine_mode")}
     * 表头 ＋ 一个高亮名字），只是名字换成我们自己的；表头仍然取上游的翻译键 ⇒
     * <b>语言文件里那句中英翻译不用我们另抄一份</b>（少一份会漂移的重复实现）。
     */
    @Inject(
            method = "appendTooltip(Lsnownee/jade/api/ITooltip;Lsnownee/jade/api/BlockAccessor;Lsnownee/jade/api/config/IPluginConfig;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void shanhai$renderModeName(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config,
                                        CallbackInfo ci) {
        try {
            if (tooltip == null || accessor == null) {
                return;
            }
            final CompoundTag data = accessor.getServerData();
            if (data == null || !data.contains(SHANHAI$MODE_NAME_KEY, 8)) {   // 8 = Tag.TAG_STRING
                return;
            }
            final String name = data.getString(SHANHAI$MODE_NAME_KEY);
            if (name == null || name.isEmpty()) {
                return;
            }
            tooltip.add(Component.translatable("gtceu.top.machine_mode")
                    .append(Component.literal("§b" + name)));
            // 取消上游那一段：它会把 activeRecipeType 的名字（或展开时的全部类型列表）画出来，
            // 而此刻那一行已经由上面这句替代（两份都画就等于同一件事说两遍、且互相矛盾）。
            ci.cancel();
        } catch (Throwable ignored) {
            // 纯显示路径：任何异常都不允许影响游戏
        }
    }
}
