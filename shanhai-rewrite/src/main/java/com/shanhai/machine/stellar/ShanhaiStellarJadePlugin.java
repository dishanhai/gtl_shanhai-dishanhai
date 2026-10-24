package com.shanhai.machine.stellar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * 抬头上（Jade）显示「被闸拦下多少块样板」—— <b>阶段七：拦截必须看得出来</b>。
 *
 * <h2>为什么是「服务端写数据 + 客户端画两行」</h2>
 * 服务端 provider 把两个计数写进 Jade 的 per-block NBT（{@code appendServerData}），
 * 客户端 provider 再读它画行（{@code appendTooltip}）——
 * 这是 Jade 的唯一正规通道（客户端拿不到机器字段）。写法与 GTCEu 自己的机器 provider 同形。
 *
 * <h2>⚠️ 注册面刻意放宽到「所有 GT 机器」</h2>
 * {@code registerBlockDataProvider}/{@code registerBlockComponent} 只接受<b>BlockEntity / Block 类</b>，
 * 而我们的机器和所有 GT 机器共用 {@link MetaMachineBlockEntity}/{@link MetaMachineBlock}，
 * 没有更细的粒度 ⇒ 两个 provider 内部第一件事就是 {@code instanceof StellarSampleAssemblerPartMachine} 否掉别人，
 * 对其它机器<b>不写任何键、不加任何行</b>（代价 = 每次抬头多一次 instanceof）。
 *
 * <h2>🔴 两条通道，一条都不许静默</h2>
 * 本类负责 Jade；机器 GUI 上还有同一对计数（{@code shanhaiGateCountLine}），日志里另有带槽号的 WARN。
 * 三处数字来自<b>同两个字段</b>（{@code shanhaiUntypedPatternCount} / {@code shanhaiAmbiguousPatternCount}）。
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

    private static final String LANG_JADE_UNTYPED = "shanhai.machine.stellar_sample_assembler.jade.untyped";

    private static final String LANG_JADE_AMBIGUOUS = "shanhai.machine.stellar_sample_assembler.jade.ambiguous";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(new CountsProvider(), MetaMachineBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new LinesProvider(), MetaMachineBlock.class);
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
