package com.shanhai.common.machine;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;

import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import com.shanhai.ShanhaiMod;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.UUID;

/**
 * 🔴 <b>「自持」无线电网能量 handler</b>——把一台机器直接挂到 gtmthings 的全局能源池上，
 * <b>不再需要结构里装「无线电网终端」部件</b>（= 伪神之锻炉
 * {@code GTLAddWirelessWorkableElectricMultipleRecipesMachine$SelfWirelessNetworkHandler} 的同形做法）。
 *
 * <h2>它替什么、不替什么</h2>
 * <ul>
 *   <li><b>替</b>：终端部件那条<b>BigInteger 通道</b>（电超过 {@code long} 的那一档，
 *       见 {@code ShanhaiWirelessWrap} / {@code PrimordialEngineRecipeLogic#shanhai$routeInfiniteEnergy}）；</li>
 *   <li><b>不替</b>：能源仓。≤{@code long} 的常规档仍由能源仓扣电
 *       ⇒ <b>装了终端 / 没装终端，常规档逐位相同</b>。</li>
 * </ul>
 *
 * <h2>🔴 为什么是一个"无状态门面"（要落盘的只有 uuid）</h2>
 * {@code WirelessEnergyManager} <b>没有</b> remove/注销接口（只有
 * {@code strongCheckOrAddUser} / {@code addEUToGlobalEnergyMap} / {@code getUserEU} / {@code setUserEU}），
 * 所以本 trait 不需要登记任何东西，也<b>不调用</b> {@code strongCheckOrAddUser}
 * （伪神也不调；第一笔扣电/入池本身就是建池）。
 * ⇒ 本类唯一的可变项是 {@link #shanhai$uuid}，而它<b>由机器落盘</b>（机器的 {@code @Persisted} 字段），
 * 本类只拿一份镜像 ⇒ 不存在"trait 自己存了 uuid、机器存了另一个"这种双份真相。
 *
 * <h2>🔴 为什么扣电传负数（口径，不是巧合）</h2>
 * 见 {@link ShanhaiWirelessConsumeRules} 的类注释：上游 {@code addEUToGlobalEnergyMap} 里
 * <b>负 = 扣电</b>；余额会变负时它<b>一个字节都不写</b>并返回 {@code false}
 * ⇒ 调用方（{@code ShanhaiWirelessWrap#consume}）拿到的 {@code false} 会走既有的
 * {@code FAIL_NO_ENOUGH_EU_IN} + {@code doDamping()} ⇔ <b>不需要任何新状态</b>。
 *
 * <h2>⚠️ 它<b>不是</b>{@code getWirelessNetworkEnergyHandler()} 的返回值</h2>
 * 那是硬约束：那个接口 getter 一旦返回非 null，gtladditions {@code MutableRecipesLogic#getRecipe()}
 * 就整体改走 {@code buildFinalWirelessRecipe}（字节码偏移 34-35：{@code aload_2 / ifnull 50}）
 * ⇒ 本工程挂在 Normal 支上的 N3/N5/N6 + 批处理 + 影子对账<b>一行都不执行</b>。
 * 本类只经<b>另一个名字</b>的接缝 {@code shanhaiWirelessEnergyHandler()} 暴露。
 */
public class ShanhaiSelfWirelessEnergyHandler extends MachineTrait implements IWirelessNetworkEnergyHandler {

    /**
     * 本类自己的 holder（{@link IWirelessNetworkEnergyHandler} 继承链上的 {@code IManaged} 要求非 null）。
     *
     * <p>本类<b>没有任何</b> {@code @Persisted}/{@code @DescSynced} 字段 ⇒ 这个 holder 只用来满足接口，
     * 不会让任何东西落盘（与 {@link #shanhai$uuid} 的"由机器落盘"不冲突）。
     */
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(ShanhaiSelfWirelessEnergyHandler.class);

    /** 池子键的镜像（<b>真身在机器上</b>，见类注释）；{@code null} = 未绑定 ⇒ 一切判定都按"离线"处理。 */
    @Nullable
    private UUID shanhai$uuid;

    /** 「有人拿正数来 consume」只喊一次（否则每 tick 一行）。 */
    private boolean shanhai$notConsumeLogged;

    /** 生产侧唯一的池子适配器：把上游那个静态方法包成纯核认识的 {@link ShanhaiWirelessConsumeRules.Pool}。 */
    private final ShanhaiWirelessConsumeRules.Pool shanhai$pool =
            (key, delta) -> WirelessEnergyManager.addEUToGlobalEnergyMap(key, delta, getMachine());

    public ShanhaiSelfWirelessEnergyHandler(@NotNull MetaMachine machine) {
        super(machine);
    }

    /** 绑定池子键（调用点：机器放置 / 读档 / 结构成型；{@code null} = 解绑）。 */
    public void shanhai$setUuid(@Nullable UUID uuid) {
        this.shanhai$uuid = uuid;
    }

    /** 当前池子键（只读；给机器的日志行用）。 */
    public @Nullable UUID shanhai$uuid() {
        return shanhai$uuid;
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /** = 伪神 {@code maxAvailableEnergy}：没有键 / 读不到 ⇒ {@code ZERO}（绝不返回 null）。 */
    @Override
    public @NotNull BigInteger getMaxAvailableEnergy() {
        final UUID key = shanhai$uuid;
        return ShanhaiWirelessConsumeRules.available(key == null ? null : WirelessEnergyManager.getUserEU(key));
    }

    /** = 伪神 {@code isOnline}：<b>有键 ∧ 余额 &gt; 0</b>（键为空 / 池子 0 ⇒ false）。 */
    @Override
    public boolean isOnline() {
        final UUID key = shanhai$uuid;
        return ShanhaiWirelessConsumeRules.isOnline(key, key == null ? null : WirelessEnergyManager.getUserEU(key));
    }

    @Override
    public boolean consumeEnergy(int energy) {
        return consumeEnergy(BigInteger.valueOf(energy));
    }

    @Override
    public boolean consumeEnergy(long energy) {
        return consumeEnergy(BigInteger.valueOf(energy));
    }

    /**
     * 唯一实现（int/long 两条重载都转调它 ⇒ <b>不存在第二份判定</b>）。
     *
     * @param energy <b>负数 = 扣电</b>（上游 {@code WirelessGTRecipe} 给的就是负数）；正数一律拒绝
     */
    @Override
    public boolean consumeEnergy(@Nullable BigInteger energy) {
        final ShanhaiWirelessConsumeRules.Verdict verdict =
                ShanhaiWirelessConsumeRules.consume(shanhai$pool, shanhai$uuid, energy);
        if (verdict == ShanhaiWirelessConsumeRules.Verdict.NOT_CONSUME && !shanhai$notConsumeLogged) {
            shanhai$notConsumeLogged = true;
            ShanhaiMod.LOGGER.warn("[SHANHAI-WIRELESS] 自持无线网 handler 收到【正数】扣电请求 amount={} "
                            + "⇒ 已拒绝（consume 只许减、不许加；透传就会凭空造电）机器={} pos=?",
                    energy, getMachine());
        }
        return verdict.accepted();
    }
}
