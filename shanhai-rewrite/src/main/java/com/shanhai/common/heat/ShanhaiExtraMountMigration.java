package com.shanhai.common.heat;

import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * <b>「额外挂载槽 ×3」从归墟子类上移到主机基类</b>之后的<b>老存档一次性迁移</b>。
 *
 * <h2>为什么需要它（历史与风险）</h2>
 * 2026-10-25 之前，那 3 格 {@code @Persisted NotifiableItemStackHandler extraMountSlots} 声明在
 * <b>子类</b> {@code GuixuVisionCoreMachine} 里（唯一真源 {@code ShanhaiHeatGate.SLOT_COUNT} = 3）。
 * 本轮把它上移到基类 {@code PrimordialOmegaEngineMachine}（字段名 {@code hostExtraMountSlots}），
 * 让模块 / 归墟 / 主机三边共用一份实现。
 * <p>🔴 <b>字段一改名，落盘键名就变</b>（LDLib 的 {@code @Persisted} 键 = 字段名，没有"改名注解"）
 * ⇒ 已经放过线圈 / 维护仓 / 维度碎片的归墟老存档，读档时基类那 3 格是<b>空的</b>，
 * 而玩家那 3 件东西还在旧键里 ⇒ <b>静默丢失配置</b>（不报错、不掉落、界面就是空的 —— 本工程最忌讳的形态）。
 * <p>⇒ 本类的唯一职责：把旧键里那 3 格<b>搬一次</b>到新键，搬完置一个 {@code @Persisted} 标记，
 * 之后<b>永不重搬</b>。
 *
 * <h2>🔴 为什么是"策略核"单独一个类（而不是直接把循环写进机器类）</h2>
 * 本类<b>只 import {@code java.*}</b>（与 {@link ShanhaiHeatGate} 同一条纪律）⇒ 可以离线
 * {@code javac} ＋ 直接跑单测，不需要 Minecraft / 不需要起游戏：
 * <pre>
 *   T = ItemStack（线上）      T = String（离线单测）   ← 同一份代码，泛型抽掉了"物品"这件事
 * </pre>
 * 四条最值钱的纪律就落在这一个方法里，且可被 {@code temp\hostmount\migration\MigrationProbe.java} 断言：
 * <ol>
 *   <li><b>已迁过 ⇒ 一次都不搬</b>（标记的读点在方法第一行，先于任何搬运）；</li>
 *   <li><b>新槽非空 ⇒ 不覆盖、不置标记</b>（下次读档再判一次，永远不丢东西）；</li>
 *   <li><b>搬不动 ⇒ 不置标记、不清旧槽</b>（写进去没落地就置标记，等于把那 3 件东西永久钉死在旧键里 ——
 *       本工程踩过这个坑，任务书也点名了）；</li>
 *   <li><b>先清旧槽、后置标记</b>（顺序不能换：清一半炸了还能重来）。</li>
 * </ol>
 *
 * <h2>为什么两侧都传"读口"而不是数组快照</h2>
 * 第 ⑤ 步要校验"写进去的东西<b>真的读得出来</b>"。若传的是快照数组，写入之后数组里那份不会变，
 * 校验就变成了自欺欺人（永远通过或永远失败）。⇒ 两侧一律传 {@link IntFunction} 现读
 * （线上 = 处理器 {@code getStackInSlot}，单测 = 数组下标）。
 *
 * <h2>为什么不用"猜 raw tag"那条路</h2>
 * 旧字段的<b>类型</b>（{@code NotifiableItemStackHandler}）随字段一起被删掉了 ⇒ 直接读原始
 * {@code CompoundTag} 等于自己复刻 LDLib 的 {@code @Persisted} 序列化格式（版本一升级就静默错位）。
 * 本轮采用的路线是任务书给的第二条：<b>保留旧字段（标 {@code @Deprecated}，只为迁移读一次），迁完清空</b>
 * —— 旧键由 LDLib 自己按原样读回，我们只搬"已经读好的物品"。
 */
public final class ShanhaiExtraMountMigration {

    private ShanhaiExtraMountMigration() {}

    /**
     * 一次迁移的读数（<b>只给日志 / 离线自证用</b>，不参与任何游戏逻辑）。
     *
     * @param migrated   本次是否真的搬了东西
     * @param flagSet    迁移标记在这次调用<b>之后</b>是否应当置上（{@code true} = 以后不再进这个方法）
     * @param movedSlots 本次搬了几格
     * @param reason     一行可 grep 的原因（写进日志，不写进玩家可见文案）
     */
    public record Report(boolean migrated, boolean flagSet, int movedSlots, String reason) {

        /** 一行读数。 */
        public String describe() {
            return "migrated=" + migrated + " flagSet=" + flagSet + " moved=" + movedSlots + " reason=" + reason;
        }
    }

    /**
     * <b>迁移本体</b>：把旧键里有内容的格搬到新键的同号格（只搬一次）。
     *
     * @param slotCount       槽数（线上 = {@code ShanhaiHeatGate.SLOT_COUNT} = 3；单测 = 任意）
     * @param legacyReader    旧键第 i 格现读（{@code null} 视为空）
     * @param hostReader      新键第 i 格现读（{@code null} 视为空）
     * @param alreadyMigrated 上一次是不是已经迁过（{@code @Persisted} 标记的现读值）
     * @param hasContent      判"这一格有东西"（线上 = {@code stack != null && !stack.isEmpty()}）
     * @param writer          往新键第 i 格写（线上 = 处理器 {@code setStackInSlot}）
     * @param clearer         清掉旧键第 i 格（线上 = 处理器 {@code setStackInSlot(i, ItemStack.EMPTY)}）
     */
    public static <T> Report run(int slotCount,
                                 IntFunction<T> legacyReader,
                                 IntFunction<T> hostReader,
                                 boolean alreadyMigrated,
                                 Predicate<T> hasContent,
                                 BiConsumer<Integer, T> writer,
                                 IntConsumer clearer) {
        // ① 已经迁过 ⇒ 一次都不搬（标记的读点必须在最前面：这一行就是"不重复搬"的唯一保证）
        if (alreadyMigrated) {
            return new Report(false, true, 0, "已迁移过（标记已置），跳过");
        }
        if (slotCount <= 0 || legacyReader == null || hostReader == null
                || hasContent == null || writer == null || clearer == null) {
            return new Report(false, false, 0, "入参不完整，不搬也不置标记");
        }

        // ② 新键已经有东西 ⇒ 不覆盖，也【不置标记】（下次读档再判；绝不能把玩家已放的东西盖掉）
        for (int i = 0; i < slotCount; i++) {
            if (has(hasContent, hostReader.apply(i))) {
                return new Report(false, false, 0, "新槽非空，不覆盖（下次读档再判）");
            }
        }

        // ③ 旧键本来就空 ⇒ 没有可搬的，直接置标记收工（否则每次读档都要白扫一遍）
        boolean any = false;
        for (int i = 0; i < slotCount; i++) {
            if (has(hasContent, legacyReader.apply(i))) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new Report(false, true, 0, "旧槽为空，无需迁移");
        }

        final boolean[] moved = new boolean[slotCount];
        int movedSlots = 0;
        try {
            for (int i = 0; i < slotCount; i++) {
                final T item = legacyReader.apply(i);
                if (!has(hasContent, item)) {
                    continue;
                }
                writer.accept(i, item);
                moved[i] = true;
                movedSlots++;
            }
        } catch (Throwable t) {
            // ④ 搬一半炸了 ⇒ 不置标记、不清旧槽（下次读档重来）
            return new Report(false, false, movedSlots, "搬运异常（" + t.getClass().getSimpleName() + "），不置标记");
        }

        // ⑤ 落地校验：写进去必须真的读得出来。没落地就置标记 == 把那几件东西永久钉死在旧键里
        for (int i = 0; i < slotCount; i++) {
            if (moved[i] && !has(hasContent, hostReader.apply(i))) {
                return new Report(false, false, 0, "第 " + (i + 1) + " 格写入未落地，不置标记、不清旧槽");
            }
        }

        // ⑥ 落地确认之后才清旧槽，最后才置标记（顺序不能换）
        try {
            for (int i = 0; i < slotCount; i++) {
                clearer.accept(i);
            }
        } catch (Throwable t) {
            return new Report(false, false, movedSlots, "清旧槽异常（" + t.getClass().getSimpleName() + "），不置标记");
        }
        return new Report(movedSlots > 0, true, movedSlots, "迁移完成");
    }

    /** {@code null} 一律当空（{@code hasContent} 只对具体物品负责）。 */
    private static <T> boolean has(Predicate<T> hasContent, T item) {
        return item != null && hasContent.test(item);
    }
}
