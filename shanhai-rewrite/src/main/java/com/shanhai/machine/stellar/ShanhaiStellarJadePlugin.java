package com.shanhai.machine.stellar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.shanhai.common.machine.ShanhaiIdleReason;
import com.shanhai.common.machine.ShanhaiIdleReasonSource;
import com.shanhai.machine.host.GuixuVisionCoreMachine;

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

    // ═══════════════ 抬头上「二级缓存（L2）压着多少」那一行（2026-11-07 新增） ═══════════════
    //
    //  为什么必须有这一行：产物 buffer 分两层（见 ShanhaiBufferOverflow 类注释 §一）——
    //  L1 是 gtlcore 的 long 表（上游 provider 逐条显示它），L2 是"顶满 Long.MAX 之后多出来"的 Int128 表。
    //  上游那几行**只看得到 L1** ⇒ L1 被 Ticker 搬空、L2 还压着量时，抬头看起来"机器是空的"，而实际不是。
    //  我们加这一行把 L2 的条数/合计 + 两层合计一起摆出来，不改上游任何一行（不加 mixin）。

    /** 抬头上：服务端写「二级缓存压着多少」。 */
    public static final ResourceLocation UID_WIDE_SERVER = new ResourceLocation("shanhai", "buf_wide_s");

    /** 抬头上：客户端画那一行。 */
    public static final ResourceLocation UID_WIDE_CLIENT = new ResourceLocation("shanhai", "buf_wide_c");

    /** 服务端数据键：L2 条目数（int）。 */
    static final String KEY_WIDE_COUNT = "shanhaiWideCount";

    /** 服务端数据键：L2 正值合计（long）。 */
    static final String KEY_WIDE_TOTAL = "shanhaiWideTotal";

    /** 服务端数据键：两层合计待退量（long）—— 抬头上的"真数"。 */
    static final String KEY_PENDING_TOTAL = "shanhaiPendingTotal";

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

    /** 抬头上「二级缓存 + 两层合计」那一行（L2 空时这一行完全不出现）。 */
    private static final String LANG_JADE_OVERFLOW = "shanhai.machine.stellar_sample_assembler.jade.overflow";

    /** 「注册面那一行只打一次」的闸门（{@link #register}/{@link #registerClient} 各调一次）。 */
    private static volatile boolean SHANHAI_COVERAGE_LOGGED = false;

    @Override
    public void register(IWailaCommonRegistration registration) {
        for (Class<? extends BlockEntity> beClass : SERVER_BLOCK_ENTITY_CLASSES) {
            registration.registerBlockDataProvider(new CountsProvider(), beClass);
            registration.registerBlockDataProvider(new ReasonProvider(), beClass);
            registration.registerBlockDataProvider(new OverflowProvider(), beClass);
            registration.registerBlockDataProvider(new GuiLinesProvider(), beClass);
        }
        shanhai$logCoverageOnce();
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        for (Class<? extends Block> blockClass : CLIENT_BLOCK_CLASSES) {
            registration.registerBlockComponent(new LinesProvider(), blockClass);
            registration.registerBlockComponent(new ReasonLineProvider(), blockClass);
            registration.registerBlockComponent(new OverflowLineProvider(), blockClass);
            registration.registerBlockComponent(new GuiLinesLineProvider(), blockClass);
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
     * <b>服务端：把「二级缓存压着多少」写进 per-block NBT</b>（L2 空 ⇒ 一个键都不写）。
     *
     * <p>🔴 为什么"0 不写"而不是写 0：本工程红线「宁可缺，不可假」——
     * 一个永远是 0 的读数会让人以为"这条链在跑、只是没量"，而真相是"这台机压根没有 L2 层"。
     * 空 => 客户端那一行完全不出现，与 {@code CountsProvider} / {@code ReasonProvider} 同一条纪律。
     *
     * <p>🔴 写的三个数各自回答一个问题（都不许省）：
     * <ul>
     *   <li>{@code count} = L2 里有<b>几种</b>东西（"是不是只有一两种卡住"）；</li>
     *   <li>{@code wideTotal} = L2 里<b>多少件</b>（"顶满之后多出来多少"）；</li>
     *   <li>{@code pendingTotal} = <b>两层合计</b>（"这台机真正还欠网络多少件"）——
     *       抬头上的真数是它，不是上游那几行（那几行只看得见 L1）。</li>
     * </ul>
     */
    private static final class OverflowProvider implements IServerDataProvider<BlockAccessor> {

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity be)) {
                return;
            }
            if (!(be.getMetaMachine() instanceof StellarSampleAssemblerPartMachine machine)) {
                return;
            }
            final int count = machine.shanhaiBufferOverflowCount();
            if (count <= 0) {
                return;     // L2 空 ⇒ 不写键 ⇒ 客户端那一行不出现
            }
            data.putInt(KEY_WIDE_COUNT, count);
            data.putLong(KEY_WIDE_TOTAL, machine.shanhaiBufferOverflowTotal());
            data.putLong(KEY_PENDING_TOTAL, machine.shanhaiBufferPendingTotal());
        }

        @Override
        public ResourceLocation getUid() {
            return UID_WIDE_SERVER;
        }
    }

    /** <b>客户端：画「二级缓存 / 两层合计」那一行</b>（服务端没写键 ⇒ 什么都不画）。 */
    private static final class OverflowLineProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            final CompoundTag data = accessor.getServerData();
            if (data == null) {
                return;
            }
            final int count = data.getInt(KEY_WIDE_COUNT);
            if (count <= 0) {
                return;     // 与写口共用同一个判据（0 行 ⇒ 不画），两边不会漂
            }
            tooltip.add(Component.translatable(LANG_JADE_OVERFLOW,
                    Integer.valueOf(count),
                    Long.valueOf(data.getLong(KEY_WIDE_TOTAL)),
                    Long.valueOf(data.getLong(KEY_PENDING_TOTAL))));
        }

        @Override
        public ResourceLocation getUid() {
            return UID_WIDE_CLIENT;
        }
    }

    // ═══════════════ 归墟视界枢核：把「机器 GUI 抬头那几行」搬进 Jade（2026-11-17 新增） ═══════════════
    //
    //  用户原话（逐字）：「但是 jade 你要修啊」。症状（实机截图）：Jade 里既没有「发电功率」，
    //  也没有「能源仓 (IN) / 动力仓 (OUT) / 已连接模块」—— 那几行只写在
    //  GuixuVisionCoreMachine#addDisplayText 里，而那个方法**不是** Jade 的取数口。
    //
    //  🔴 根因（字节码实证，不是推测）：整个依赖集里唯一调用 addDisplayText 的地方是 gtceu 的
    //  IDisplayUIMachine#createUI —— BootstrapMethods #0 =
    //    REF_invokeInterface com/gregtechceu/gtceu/api/machine/feature/multiblock/IDisplayUIMachine
    //        .addDisplayText:(Ljava/util/List;)V
    //  它被塞进 ComponentPanelWidget（= 机器 GUI 那一页）。⇒ addDisplayText 只喂【机器 GUI】，
    //  与 Jade 完全无关：gtceu / gtlcore / gtladditions / gtmthings 的 integration.jade.provider.*
    //  里没有任何一个类引用 addDisplayText（逐个 class 的常量池实测 = 0 命中）。
    //  用户看到的那几行（机器模式 / ME网络库存 / 延迟 / 总耗能倍率 / 总耗时倍率）来自
    //  gtceu MachineModeProvider、gtlcore MeInventoryAmountProvider / TickTimeProvider /
    //  RecipeMultiplierProvider —— 都是各自独立的 provider，与 addDisplayText 无关。
    //
    //  ⇒ 本段补一条【我们自己的】通道：服务端调同一个 addDisplayText（文案只有一份、零复制）
    //    → 每行 Component 序列化成 JSON → per-block NBT → 客户端原样画出来。
    //    ★ 例外：「机器模式：…」那一行 <b>不搬</b> —— Jade 上已经由 ShanhaiMachineModeNameMixin
    //      （改 gtceu MachineModeProvider 那一行）画过，搬过去就是同一行出现两次
    //      （判据见 LINE_ALREADY_DRAWN_BY_JADE）。
    //    取数放在**服务端**：isFormed() / 两侧能量仓 / 发电读数在服务端才是权威；客户端那一路
    //    要靠 8 个 @DescSynced 镜像全同步（任何一个没跟上就是假数）⇒ 与服务端同一条路最稳。

    /** 抬头上：服务端把「机器 GUI 抬头那几行」写进 per-block NBT。 */
    public static final ResourceLocation UID_GUI_LINES_SERVER = new ResourceLocation("shanhai", "gui_lines_s");

    /** 抬头上：客户端把那些行原样画出来。 */
    public static final ResourceLocation UID_GUI_LINES_CLIENT = new ResourceLocation("shanhai", "gui_lines_c");

    /** 服务端数据键：行的 {@link Component} JSON（{@code ListTag} of {@code StringTag}）。 */
    static final String KEY_GUI_LINES = "shanhaiGuiLines";

    /**
     * 这些开头的行<b>不再重复画</b> —— 它们已经由别的通道画过（Jade 上同一件事说两遍就是噪音）。
     *
     * <p>目前只有一条：「机器模式：…」。{@code ShanhaiMachineModeNameMixin} 已经在 gtceu 的
     * {@code MachineModeProvider} 上把那一行换成新名字并 {@code ci.cancel()} 掉上游
     * ⇒ 本通道再从 {@code addDisplayText} 搬一行过去就是同一行出现两次。
     *
     * <p>⚠️ 这个判据是<b>前缀字面量</b>（全角冒号，与 {@code addDisplayText} 里那一行逐字一致）：
     * 若以后 GUI 那行改了措辞，这里就匹配不上 ⇒ 退化方向是「Jade 上多一行重复」，
     * <b>不会</b>丢行、也不会影响机器行为。
     */
    static final String LINE_ALREADY_DRAWN_BY_JADE = "机器模式：";

    /** 探针日志的最小间隔：1000 ms（抬头每帧都可能重建，不限流会刷屏）。 */
    private static final long SHANHAI_JADE_PROBE_MIN_INTERVAL_MS = 1_000L;

    /** 上一次真打探针的墙钟 / 这 1 秒窗口内折叠掉的次数（「只限流不报数 = 静默」）。 */
    private static long shanhaiJadeProbeLastMs;

    private static int shanhaiJadeProbeSkipped;

    /** 客户端「收到 N 行、只画出 M 行」那条异常探针上次真打的时间（正常时一行都不打）。 */
    private static long shanhaiJadeDrawProbeLastMs;

    /**
     * 服务端：对归墟视界枢核调一次 {@code addDisplayText}，把行原样塞进 per-block NBT。
     *
     * <p>🔴 一行都取不到（或全部序列化失败）⇒ <b>一个键都不写</b>：客户端因此连空行都不会出现
     * （与 {@code CountsProvider} / {@code ReasonProvider} 的「0 不写」同一条纪律）。
     *
     * <p>🔴 注册面同样放宽到 {@code MetaMachineBlockEntity}（Jade 只接受 BlockEntity/Block 类），
     * 所以第一件事就是 {@code instanceof GuixuVisionCoreMachine} 否掉别的机器 —— 对其它机器的
     * 抬头一个字都不加、一个键都不写。
     */
    private static final class GuiLinesProvider implements IServerDataProvider<BlockAccessor> {

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity be)) {
                return;
            }
            if (!(be.getMetaMachine() instanceof GuixuVisionCoreMachine machine)) {
                return;     // 只对归墟：别的机器抬头一行都不变
            }
            final List<Component> lines = new ArrayList<>(16);
            try {
                machine.addDisplayText(lines);      // ← 与机器 GUI 抬头同一个方法（文案唯一一份）
            } catch (Throwable t) {
                com.mojang.logging.LogUtils.getLogger().error(
                        "[SHANHAI-JADE] 归墟抬头取数异常（本轮不写任何行，机器行为不受影响）：{}", String.valueOf(t));
                return;
            }
            if (lines.isEmpty()) {
                return;
            }
            final ListTag json = new ListTag();
            String firstText = null;
            for (Component line : lines) {
                if (line == null) {
                    continue;
                }
                String plain = null;
                try {
                    plain = line.getString();
                } catch (Throwable ignored) {
                    // 取不到纯文本只影响下面那两条判据，不影响画行
                }
                if (plain != null && plain.startsWith(LINE_ALREADY_DRAWN_BY_JADE)) {
                    // 「机器模式：…」这一行 Jade 已经画过（ShanhaiMachineModeNameMixin 在
                    // gtceu MachineModeProvider 上换名并 cancel 上游）⇒ 这里不再画第二遍。
                    continue;
                }
                try {
                    json.add(StringTag.valueOf(Component.Serializer.toJson(line)));
                    if (firstText == null) {
                        firstText = plain;
                    }
                } catch (Throwable ignored) {
                    // 单行序列化失败 ⇒ 丢这一行，不丢整块（更不许把异常抛进 Jade 的取数链）
                }
            }
            if (json.size() == 0) {
                return;
            }
            data.put(KEY_GUI_LINES, json);
            shanhai$probeJadeLines(accessor, json.size(), firstText == null ? "?" : firstText);
        }

        @Override
        public ResourceLocation getUid() {
            return UID_GUI_LINES_SERVER;
        }
    }

    /** 客户端：读服务端那串 JSON，逐行画出来（没有那个键 ⇒ 一行都不加）。 */
    private static final class GuiLinesLineProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            final CompoundTag data = accessor.getServerData();
            if (data == null) {
                return;
            }
            final ListTag json = data.getList(KEY_GUI_LINES, Tag.TAG_STRING);
            if (json.size() == 0) {
                return;
            }
            int drawn = 0;
            for (int i = 0; i < json.size(); i++) {
                try {
                    final Component line = Component.Serializer.fromJson(json.getString(i));
                    if (line == null) {
                        continue;
                    }
                    tooltip.add(line);
                    ++drawn;
                } catch (Throwable ignored) {
                    // 反序列化炸了 ⇒ 宁可少一行，也不许把 JSON 原文画上去（那就是假数据），
                    // 更不许把异常抛进 Jade 的 tooltip 链（那是每帧都会跑的地方）。
                }
            }
            shanhai$probeJadeDrawn(json.size(), drawn);
        }

        @Override
        public ResourceLocation getUid() {
            return UID_GUI_LINES_CLIENT;
        }
    }

    /**
     * 只读诊断（限流：≤1 行/秒）：下次实机判「Jade 到底拿到了哪些行」。
     *
     * <p>判据：抬头里出现 <b>lines=</b> 那么多行、且第一行逐字等于 {@code first=} 打出的那句
     * ⇒ 通道通；<b>日志有这一行而抬头里没有</b> ⇒ 断在客户端画行那一步（那时
     * {@code side=client received=/drawn=} 那行 WARN 会一起出现）。
     *
     * <p>本方法<b>只读</b>：不改机器任何字段、不改抬头内容，只维护「这一行 1 秒最多一次」的计时器。
     */
    private static void shanhai$probeJadeLines(BlockAccessor accessor, int count, String first) {
        final long now = System.currentTimeMillis();
        if (now - shanhaiJadeProbeLastMs < SHANHAI_JADE_PROBE_MIN_INTERVAL_MS) {
            ++shanhaiJadeProbeSkipped;
            return;
        }
        final int skipped = shanhaiJadeProbeSkipped;
        shanhaiJadeProbeSkipped = 0;
        shanhaiJadeProbeLastMs = now;
        com.mojang.logging.LogUtils.getLogger().info(
                "[SHANHAI-JADE] probe side=server machine=guixu pos={} lines={} first={} skipped={}",
                accessor.getPosition(), Integer.valueOf(count), first, Integer.valueOf(skipped));
    }

    /** 客户端只说「收到的没画全」这一种异常（正常 ⇒ 一行都不打；限流 5 s）。 */
    private static void shanhai$probeJadeDrawn(int received, int drawn) {
        if (drawn >= received) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - shanhaiJadeDrawProbeLastMs < 5_000L) {
            return;
        }
        shanhaiJadeDrawProbeLastMs = now;
        com.mojang.logging.LogUtils.getLogger().warn(
                "[SHANHAI-JADE] probe side=client machine=guixu received={} drawn={}（有行反序列化失败）",
                Integer.valueOf(received), Integer.valueOf(drawn));
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
