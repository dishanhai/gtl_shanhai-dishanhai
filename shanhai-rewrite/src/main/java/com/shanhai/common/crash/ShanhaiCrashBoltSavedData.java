package com.shanhai.common.crash;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「哪几发异常投掷物已经崩过了」的<b>存档级</b>记录（世界存档里的 {@code data/shanhai_crash_bolts.dat}）。
 *
 * <h2>一、为什么必须有它（这是本阶段最大的真陷阱）</h2>
 * 崩端路径是 {@code runServer} 的 catch（写报告）→ {@code finally stopServer()}（存世界）。
 * 也就是说<b>崩溃报告写完，游戏还会把世界存一遍</b>。如果那个投掷物实体那一刻还在世界里，
 * 它就会被写进 region 文件 ⇒ 下次进世界它又加载出来、又开始计时 ⇒
 * <b>进不去的存档</b>（玩家只能删档）。
 * 取证：{@code temp/reality-items/报告.md} §6.6（含本机真崩溃报告的存档行为旁证）。
 *
 * <p>⇒ 两道闸：
 * <ol>
 *   <li>崩之前 {@code entity.discard()}（主闸，见 {@code ShanhaiCrashBoltScheduler#fire}）；</li>
 *   <li>把"这一发已经触发过"写进本类（副闸）：万一某一次实体在崩溃前就已经被写进过 region 文件
 *       （比如玩家在计时中途退出、或者上一次存档正好卡在那一 tick），
 *       实体加载时会查这里，查到就<b>立刻自我移除、不再计时</b>。</li>
 * </ol>
 * 两道闸缺一不可：只 discard ⇒ 挡不住"已经被写盘过的那一份"；
 * 只记标记 ⇒ 下次进世界那个实体还躺在插地的地方（玩家会看到它却永远不崩，更怪）。
 *
 * <h2>二、为什么键是实体 UUID</h2>
 * 投掷物是一次性的（崩完就 discard），UUID 天然唯一；用 UUID 就不需要"次数/时间戳"
 * 这类会过期的语义，也不会因为玩家换个地方扔就串号。
 *
 * <h2>三、写法</h2>
 * 标准 {@link net.minecraft.world.level.saveddata.SavedData}：
 * 1.20.1 的落盘形状是 {@code CompoundTag}，{@code fired} 下一个 {@code int[4]} 列表
 * （{@link NbtUtils#createUUID(UUID)} / {@link NbtUtils#loadUUID(Tag)} 是原版自带的编解码，
 * 不自己拼 long 对，少一处错位风险）。
 */
public final class ShanhaiCrashBoltSavedData extends net.minecraft.world.level.saveddata.SavedData {

    /** 存档文件名（{@code data/shanhai_crash_bolts.dat}）。 */
    public static final String DATA_NAME = "shanhai_crash_bolts";

    /** NBT 键：已触发过的投掷物 UUID 列表。 */
    public static final String TAG_FIRED = "fired";

    /** 已触发过的投掷物 UUID。用 {@link LinkedHashSet} 只为调试时顺序稳定。 */
    private final Set<UUID> fired = new LinkedHashSet<>();

    // ------------------------------------------------------------------ 取实例

    /**
     * 取当前世界的这一份数据（不存在就建）。
     *
     * <p>⚠️ 用<b>主世界</b>的 {@code DimensionDataStorage}：{@code MinecraftServer#overworld()}
     * 一定存在，而具体扔在哪个维度都无所谓 —— 这一份是"整个存档共用"的。
     */
    public static ShanhaiCrashBoltSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(ShanhaiCrashBoltSavedData::load, ShanhaiCrashBoltSavedData::new, DATA_NAME);
    }

    /** 便捷版：从任意服务端世界取（实体侧用这个，省一次 {@code getServer()} 判空）。 */
    public static ShanhaiCrashBoltSavedData get(ServerLevel level) {
        return get(level.getServer());
    }

    /** 这一发是不是已经崩过了（实体加载 / 计时前查）。 */
    public static boolean isFired(ServerLevel level, UUID id) {
        return get(level).isFired(id);
    }

    // ------------------------------------------------------------------ 内容

    /** 反序列化。 */
    public static ShanhaiCrashBoltSavedData load(CompoundTag tag) {
        ShanhaiCrashBoltSavedData data = new ShanhaiCrashBoltSavedData();
        ListTag list = tag.getList(TAG_FIRED, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.fired.add(NbtUtils.loadUUID(list.get(i)));
            } catch (RuntimeException badEntry) {
                // 单条坏数据不该让整个世界打不开；跳过它（下一条仍然读）
            }
        }
        return data;
    }

    /** 序列化。 */
    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : this.fired) {
            list.add(NbtUtils.createUUID(id));
        }
        tag.put(TAG_FIRED, list);
        return tag;
    }

    /** 这一发是否已触发过。 */
    public boolean isFired(UUID id) {
        return this.fired.contains(id);
    }

    /**
     * 记「这一发已经触发」。
     *
     * <p>该打的 {@code setDirty()} 必须打：不打的话 {@code DimensionDataStorage} 不会把它写盘，
     * 副闸就形同虚设。
     */
    public void markFired(UUID id) {
        if (this.fired.add(id)) {
            this.setDirty();
        }
    }

    /** 已记录多少发（诊断 / 日志用）。 */
    public int firedCount() {
        return this.fired.size();
    }
}
