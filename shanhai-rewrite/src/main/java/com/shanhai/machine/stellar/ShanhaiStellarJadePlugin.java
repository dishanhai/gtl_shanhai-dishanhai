package com.shanhai.machine.stellar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.shanhai.common.machine.ShanhaiIdleReason;
import com.shanhai.common.machine.ShanhaiIdleReasonSource;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 抬头上（Jade）显示本工程需要的两件事：
 * <ol>
 *   <li>「被闸拦下多少块样板」（阶段七：拦截必须看得出来）；</li>
 *   <li>🆕「<b>这台机器为什么没跑</b>」（2026-11-01，用户原话：「主要是它现在 jade 都没有写出机器不工作的原因」）。</li>
 * </ol>
 *
 * <h2>为什么是「服务端写数据 + 客户端画行」</h2>
 * 服务端 provider 把值写进 Jade 的 per-block NBT（{@code appendServerData}），
 * 客户端 provider 再读它画行（{@code appendTooltip}）——
 * 这是 Jade 的唯一正规通道（客户端拿不到机器字段）。写法与 GTCEu 自己的机器 provider 同形。
 *
 * <h2>⚠️ 注册面刻意放宽到「所有 GT 机器」—— 而且这是<b>唯一</b>能盖住我们全部机器的做法</h2>
 * {@code registerBlockDataProvider}/{@code registerBlockComponent} 只接受<b>BlockEntity / Block 类</b>，
 * 而我们的机器和所有 GT 机器共用 {@link MetaMachineBlockEntity}/{@link MetaMachineBlock}，
 * 没有更细的粒度 ⇒ 每个 provider 内部第一件事就是 {@code instanceof} 否掉别人
 * （计数两行 {@code instanceof StellarSampleAssemblerPartMachine}；「为什么没跑」那一行
 * {@code instanceof ShanhaiIdleReasonSource}），
 * 对其它机器<b>不写任何键、不加任何行</b>（代价 = 每次抬头多一次 instanceof）。
 *
 * <h2>🔴 2026-11-01 第二轮：注册面为什么不是「不显示」的原因（现场证据）</h2>
 * 用户反馈原话（逐字）：<b>「jade 还是不显示」</b>。这一次先把"到底哪一环断了"钉在日志上，
 * 结论是<b>注册面与数据通道都是通的，断的是最上游——那一刻我们根本没有原因可写</b>：
 * <pre>
 *   ① 插件真的被加载了（不是"没注册"）：
 *      latest.log:2473 [16:42:38.887] [Jade/]: Start loading plugin at
 *          com.shanhai.machine.stellar.ShanhaiStellarJadePlugin
 *      同一份日志里 Jade 的 ERROR/异常行 = 0（{@code [Jade/]} 前缀共 20 行，全是 plugin 加载 + 配置同步）
 *   ② Jade 的服务端数据收集真的在跑（玩家正看向一台模块机器）：
 *      latest.log:7120 [16:43:47.468] [Server thread] [SHANHAI-DISPLAY] 已写入模块 Jade 真值：parallel=65536 …
 *      —— 那一行来自 {@code ShanhaiInfiniteThreadDisplayMixin} 对
 *      {@code appendServerData(CompoundTag, BlockAccessor)} 的 RETURN 注入 ⇒ 服务端那一刻确实在收集
 *   ③ 而 80 ms 之前，我们这台机器发布的读数是"没有原因"：
 *      latest.log:7099 [16:43:47.388] [SHANHAI-JADE-REASON] machine=shanhai:primordial_causal_weaving_matrix
 *          pos=useless_mod:uselessdim2@-88,130,83 shown=none
 *      （整份日志 61 条 {@code [SHANHAI-JADE-REASON]} 里，只有 <b>1</b> 条 {@code shown≠none}：
 *        {@code shanhai:worldline_cracking_hub@-60,130,71}，latest.log:8267）
 * </pre>
 * ⇒ 抬头上"什么都没有"的成因 = 上面 ③（{@code shown=none} ⇒ 服务端一个键都不写 ⇒ 客户端按纪律一行都不加），
 * <b>不是</b> ①②。所以本轮把「覆盖到谁」与「画几行」抽成可离线断言的
 * {@link ShanhaiJadeLineModel}，并在启动日志里留一行 {@code [SHANHAI-JADE-COVER]} 把注册面钉死
 * （这两个量一旦被改小，症状与"没有原因"长得一模一样，日志是唯一的区分手段）。
 * <p>⚠️ 诚实边界：{@code shown=none} 的那台机器<b>该不该</b>有原因，是另一件事（原因的计算在
 * {@code PrimordialModuleRecipeLogic} 的三道门里），<b>不在本插件能决定的范围内</b>——
 * 本插件只保证"有原因就一定画得出、没原因就一定不画"。
 *
 * <h2>🔴 三条通道，一条都不许静默</h2>
 * 本类负责 Jade；机器 GUI 上还有同一对计数（{@code shanhaiGateCountLine}），日志里另有带槽号的 WARN。
 * 三处数字来自<b>同两个字段</b>（{@code shanhaiUntypedPatternCount} / {@code shanhaiAmbiguousPatternCount}）。
 * 「为什么没跑」那条同样三处对得上：Jade 一行 / 机器字段（{@code ShanhaiIdleReasonSource}）/
 * 日志 {@code [SHANHAI-JADE-REASON] machine=… pos=… shown=…}。
 */
@WailaPlugin("shanhai")
public final class ShanhaiStellarJadePlugin implements IWailaPlugin {

    /** 机器抬头上：未标注计数 + 歧义计数（两行由客户端 provider 画）。 */
    public static final ResourceLocation UID_COUNTS_SERVER = new ResourceLocation("shanhai", "stellar_gate_counts_s");

    /** 机器抬头上：画行。 */
    public static final ResourceLocation UID_COUNTS_CLIENT = new ResourceLocation("shanhai", "stellar_gate_counts_c");

    /** 服务端数据键（两个 int）。 */
    static final String KEY_UNTYPED = "shanhaiUntyped";

    static final String KEY_AMBIGUOUS = "shanhaiAmbiguous";

    // ═══════════════ 抬头上「为什么没跑」那一行（2026-11-01 新增） ═══════════════
    //
    //  用户原话（逐字）：「主要是它现在 jade 都没有写出机器不工作的原因」
    //
    //  这是【我们自己的】一条通道，与上游那条（gtlcore 的 RecipeLogicProviderMixin 读
    //  IRecipeStatus.reason，只在 getBoolean("Working")==false 的分支里渲）互不依赖：
    //    · 数据源 = 机器上的 ShanhaiIdleReasonSource#shanhaiIdleReasonJson()
    //      （服务端权威；判定/清空规则只有一份，在 ShanhaiIdleReason）
    //    · 通道   = 本插件既有的 IServerDataProvider（服务端写 per-block NBT → 客户端画行），
    //      与上面 CountsProvider/LinesProvider 同一条路、同一套注册面。

    /** 抬头上：服务端写「为什么没跑」。 */
    public static final ResourceLocation UID_REASON_SERVER = new ResourceLocation("shanhai", "idle_reason_s");

    /** 抬头上：客户端画那一行。 */
    public static final ResourceLocation UID_REASON_CLIENT = new ResourceLocation("shanhai", "idle_reason_c");

    /**
     * 服务端数据键 —— 值的形态 = 原因的 {@code Component} <b>JSON</b>。
     *
     * <p>🔴 为什么不送已经翻好的中文：原因几乎都是 {@code TranslatableComponent}
     * （{@code shanhai.recipe.fail.*}），服务端的语言表里没有本模组的键
     * ⇒ 只有把 JSON 送过去、让<b>客户端</b>按玩家语言翻译才是对的。
     */
    static final String KEY_IDLE_REASON = "shanhaiIdleReason";

    // ═══════════════ 注册面（唯一一份；改这里就等于改"覆盖到哪些方块"） ═══════════════
    //
    //  2026-11-01 第二轮：把注册面从"散在 register/registerClient 里的两个字面量"抬成公开常量，
    //  理由只有一个 —— 它能被**离线断言**（temp/jade-and-locate-2），也能在**启动日志**里留读数：
    //     [SHANHAI-JADE-COVER] 注册面 client=[…MetaMachineBlock] server=[…MetaMachineBlockEntity] …
    //  这两个量一旦被改小（例如只注册星律那块方块），症状与"没有原因"完全一样（抬头上一行都没有），
    //  所以必须有一行日志 + 一条离线断言把它们钉住。判据的唯一一份在 ShanhaiJadeLineModel。

    /**
     * 客户端画行 provider 注册到的<b>方块类</b>：我们的 25 台模块 / 主机 / 归墟视界枢核 /
     * 各式仓室共用这一个基类 ⇒ <b>一个基类盖住全部</b>（Jade 按父类链匹配，字节码见
     * {@link ShanhaiJadeLineModel} 类注释 §3）。
     *
     * <p>⚠️ 与 {@link ShanhaiJadeLineModel#MACHINE_BLOCK_CLASS_NAME} 必须逐字一致，启动时自检。
     */
    public static final List<Class<? extends Block>> CLIENT_BLOCK_CLASSES = List.of(MetaMachineBlock.class);

    /** 服务端写数据的 provider 注册到的<b>方块实体类</b>（同一套理由）。 */
    public static final List<Class<? extends BlockEntity>> SERVER_BLOCK_ENTITY_CLASSES =
            List.of(MetaMachineBlockEntity.class);

    private static final String LANG_JADE_UNTYPED = "shanhai.machine.stellar_sample_assembler.jade.untyped";

    private static final String LANG_JADE_AMBIGUOUS = "shanhai.machine.stellar_sample_assembler.jade.ambiguous";

    /** 「注册面那一行只打一次」的闸门（{@link #register}/{@link #registerClient} 各调一次）。 */
    private static volatile boolean SHANHAI_COVERAGE_LOGGED = false;

    @Override
    public void register(IWailaCommonRegistration registration) {
        for (Class<? extends BlockEntity> beClass : SERVER_BLOCK_ENTITY_CLASSES) {
            registration.registerBlockDataProvider(new CountsProvider(), beClass);
            registration.registerBlockDataProvider(new ReasonProvider(), beClass);
        }
        shanhai$logCoverageOnce();
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        for (Class<? extends Block> blockClass : CLIENT_BLOCK_CLASSES) {
            registration.registerBlockComponent(new LinesProvider(), blockClass);
            registration.registerBlockComponent(new ReasonLineProvider(), blockClass);
        }
        shanhai$logCoverageOnce();
    }

    /**
     * 启动时把「我们到底注册给了谁」打一行、顺手跑一遍离线那份自检（{@link ShanhaiJadeLineModel#selfTest()}）。
     *
     * <p>🔴 为什么这事儿必须在游戏里也做一次：注册面被改小之后，抬头上"一行都没有"这个现象
     * 与"这一刻没有原因"<b>完全一样</b>（后者是正常行为）⇒ 没有这行日志就没法区分。
     * 判据本身是纯 JDK 的 {@link ShanhaiJadeLineModel}，离线跑的是<b>同一份字节码</b>。
     *
     * <p>只打一次（register 与 registerClient 各调一次 ⇒ 用静态闸门），失败也<b>不许</b>影响 Jade 加载：
     * 这条线是纯显示，异常一律降级成一行 ERROR。
     */
    private static void shanhai$logCoverageOnce() {
        if (SHANHAI_COVERAGE_LOGGED) {
            return;
        }
        SHANHAI_COVERAGE_LOGGED = true;
        try {
            final List<String> client = new ArrayList<>(CLIENT_BLOCK_CLASSES.size());
            for (Class<? extends Block> c : CLIENT_BLOCK_CLASSES) {
                client.add(c.getName());
            }
            final List<String> server = new ArrayList<>(SERVER_BLOCK_ENTITY_CLASSES.size());
            for (Class<? extends BlockEntity> c : SERVER_BLOCK_ENTITY_CLASSES) {
                server.add(c.getName());
            }
            com.mojang.logging.LogUtils.getLogger().info(ShanhaiJadeLineModel.checkSurface(client, server));
            com.mojang.logging.LogUtils.getLogger().info(ShanhaiJadeLineModel.selfTest());
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().error(
                    "[SHANHAI-JADE-COVER] 注册面自检失败（抬头那行可能永远不出现）：{}", String.valueOf(t));
        }
    }

    /** 服务端：只对星律样板总成写两个计数。 */
    private static final class CountsProvider implements IServerDataProvider<BlockAccessor> {

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity be)) {
                return;
            }
            if (!(be.getMetaMachine() instanceof StellarSampleAssemblerPartMachine machine)) {
                return;
            }
            final int untyped = machine.shanhaiUntypedPatternCount();
            final int ambiguous = machine.shanhaiAmbiguousPatternCount();
            if (untyped <= 0 && ambiguous <= 0) {
                return;
            }
            data.putInt(KEY_UNTYPED, untyped);
            data.putInt(KEY_AMBIGUOUS, ambiguous);
        }

        @Override
        public ResourceLocation getUid() {
            return UID_COUNTS_SERVER;
        }
    }

    /** 客户端：读服务端数据，画两行（0 的不画）。 */
    private static final class LinesProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            final CompoundTag data = accessor.getServerData();
            if (data == null) {
                return;
            }
            final int untyped = data.getInt(KEY_UNTYPED);
            final int ambiguous = data.getInt(KEY_AMBIGUOUS);
            if (untyped > 0) {
                tooltip.add(Component.translatable(LANG_JADE_UNTYPED, untyped));
            }
            if (ambiguous > 0) {
                tooltip.add(Component.translatable(LANG_JADE_AMBIGUOUS, ambiguous));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID_COUNTS_CLIENT;
        }
    }

    /**
     * 服务端：把机器上「为什么没跑」的原因（Component 的 JSON）原样塞进 per-block NBT。
     *
     * <p>🔴 <b>没有原因就一个键都不写</b>（不是写空串）：客户端那边因此连"空行"都不会出现，
     * 与 {@code CountsProvider} 的「0 不写」同一条纪律 —— 本工程红线「宁可缺，不可假」。
     *
     * <p>⚠️ 注册面同样放宽到 {@code MetaMachineBlockEntity}（Jade 只接受 BlockEntity/Block 类），
     * 所以第一件事就是 {@code instanceof ShanhaiIdleReasonSource} 否掉别人 ——
     * 那个接口把模块一脉（25 台标准模块 / 创造编译矩阵…）与主机一脉（原始终焉引擎 / 归墟视界枢核）
     * 一次盖住，插件里不需要写任何具体机器类的强转。
     */
    private static final class ReasonProvider implements IServerDataProvider<BlockAccessor> {

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity be)) {
                return;
            }
            if (!(be.getMetaMachine() instanceof ShanhaiIdleReasonSource source)) {
                return;
            }
            final String json = source.shanhaiIdleReasonJson();
            // 唯一判据在 ShanhaiJadeLineModel（纯 JDK，离线断言跑的是同一份字节码）：
            // 有原因 ⇒ 1 行 ⇒ 写键；没有 ⇒ 0 行 ⇒ 一个键都不写。
            if (!ShanhaiJadeLineModel.writesServerKey(json)) {
                return;     // 没有原因 ⇒ 不写键 ⇒ 客户端那一行完全不出现
            }
            data.putString(KEY_IDLE_REASON, json);
        }

        @Override
        public ResourceLocation getUid() {
            return UID_REASON_SERVER;
        }
    }

    /**
     * 客户端：有原因才画一行「为什么没跑：<原因>」，没有就<b>一行都不加</b>。
     *
     * <p>整行 = {@code Component.translatable("shanhai.jade.not_running_reason", 原因)}，
     * 中文文案 = {@code 为什么没跑：%s}（{@code assets/shanhai/lang/zh_cn.json}），
     * 里面那个 {@code %s} 是<b>客户端</b>反序列化出来的 Component ⇒ 翻译也在客户端做。
     */
    private static final class ReasonLineProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            final CompoundTag data = accessor.getServerData();
            if (data == null) {
                return;
            }
            final String json = data.getString(KEY_IDLE_REASON);
            // 🔴 与上面那个服务端写口共用同一个判据（ShanhaiJadeLineModel#lines）：
            //    0 行 ⇒ 什么都不加；1 行 ⇒ 加一行。两条路径不许各判一次，否则"服务端写了、客户端不画"
            //    这种最难查的偏叉会重新长出来。
            if (ShanhaiJadeLineModel.lines(json) == 0) {
                return;     // 没有原因 ⇒ 这一行完全不出现（不是空行、不是占位）
            }
            final Component reason;
            try {
                reason = Component.Serializer.fromJson(json);
            } catch (Throwable t) {
                // 反序列化炸了 ⇒ 宁可这一行不出现，也不许把一串 JSON 原文画上去（那就是假数据），
                // 更不许把异常抛进 Jade 的 tooltip 链（那是每帧都会跑的地方）。
                return;
            }
            if (reason == null) {
                // 反序列化不了 ⇒ 宁可不显示，也不显示一串 JSON 原文（那就是假数据）。
                return;
            }
            tooltip.add(Component.translatable(ShanhaiIdleReason.LANG_KEY, reason));
        }

        @Override
        public ResourceLocation getUid() {
            return UID_REASON_CLIENT;
        }
    }

    /**
     * 🔴 <b>必须是 public 无参构造器</b>——Jade 是用反射 {@code Class#getDeclaredConstructor().newInstance()}
     * 造插件的，构造器私有 ⇒ 加载期直接炸。这不是推测，是 2026-10-24 客户端日志里的原文：
     * <pre>
     *   [Jade/]: Error loading plugin at com.shanhai.machine.stellar.ShanhaiStellarJadePlugin
     *   java.lang.IllegalAccessException: class snownee.jade.util.CommonProxy (in module jade)
     *       cannot access a member of class com.shanhai.machine.stellar.ShanhaiStellarJadePlugin
     *       (in module shanhai) with modifiers "private"
     * </pre>
     * <p>⚠️ 那句 "with modifiers private" 说的就是本构造器（上一版是 {@code private ShanhaiStellarJadePlugin() {}}）。
     * 后果：<b>本类的两个 provider 一个都没注册</b> —— 抬头上那两行（未标注样板数 / 歧义样板数）永远不显示，
     * 而日志里那行 ERROR 是唯一的证据。审计脚本 {@code verify-phase7.ps1} 的 Q 组盯着它。
     */
    public ShanhaiStellarJadePlugin() {}
}
