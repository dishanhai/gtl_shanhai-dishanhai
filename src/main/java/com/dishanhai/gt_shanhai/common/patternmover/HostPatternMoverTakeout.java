package com.dishanhai.gt_shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.List;

/**
 * 「取出」这件事的<b>纯核</b>（不 import 任何 Minecraft 类 ⇒ 裸 JVM 可跑断言）。
 *
 * <h2>一、🔴 语义（用户 2026-11-06 改的口径，逐条落在这里）</h2>
 * <pre>
 *   取出 = 把该组样板【作为物品交给玩家】，不是销毁。
 *   ① 每一块先 player.getInventory().add(stack)         → 计入 toInventory
 *   ② 背包满了才 player.drop(stack, false) 掉在脚下       → 计入 dropped
 *   ③ 连掉都掉不出来的那一块 ⇒ 【留在物品里】             → 计入 kept（绝不静默丢）
 * </pre>
 * 为什么必须做成"先算后写"的纯核：这条规则里最容易犯的错是
 * <b>「先从物品里删掉、再去发物品」</b> —— 发的过程中任何一步失败，玩家就永久少了那几块。
 * 所以本类<b>只做账</b>：交出去几块、掉了几块、哪几块要留在物品里；
 * 真正的 `storeTo`（写回物品 NBT）由调用方在拿到 {@link Result} 之后做，
 * 并且必须在<b>交出去之前</b>先成功写回（见 {@code HostPatternMoverActions#takeOut} 的注释）。
 *
 * <h2>二、判据（自检第 16 组就是打这两条）</h2>
 * <pre>
 *   removed == toInventory + dropped          ← 交出去的块数 = 背包 + 掉落（一块都不许凭空消失）
 *   wanted  == removed + kept.size()          ← 这一组的总块数 = 交出去的 + 留在物品里的
 * </pre>
 * 负对照：把实现写成"不管发没发出去都从物品里删"（= 旧的销毁语义）⇒ 第一条立刻不成立。
 */
public final class HostPatternMoverTakeout {

    /** 交出去的三种结局。 */
    public enum Fate {
        /** 进了背包。 */
        INVENTORY,
        /** 背包满了，掉在脚下。 */
        DROPPED,
        /** 背包与掉落都没成功 ⇒ 留在物品里（不许静默丢）。 */
        KEPT
    }

    /**
     * 玩家侧的能力（生产实现见 {@code HostPatternMoverActions.PlayerHandout}）。
     *
     * <p>两个方法分开，是为了让"背包 / 掉落"这两个数<b>分别可读</b> ——
     * 合成一个 {@code boolean} 会让面板只能报"取出了 N 块"，
     * 而用户要的是「已取出 N 块（背包 M 块，掉落 D 块）」。
     */
    public interface Handout {

        /** 尝试放进背包；收下 ⇒ true。 */
        boolean toInventory(HostPatternMoverPlacer.Unit unit, int index);

        /** 背包放不下时掉在脚下；成功 ⇒ true。 */
        boolean drop(HostPatternMoverPlacer.Unit unit, int index);
    }

    /**
     * 一次「取出」的账。
     *
     * @param wanted      这一组一共几块（= 交出去 ＋ 留在物品里）
     * @param removed     真的从物品里拿走的块数（= 背包 ＋ 掉落）
     * @param toInventory 进了背包的块数
     * @param dropped     掉在脚下的块数
     * @param keptIndices 留在物品里的那些下标（顺序 = 原表顺序）
     */
    public record Result(int wanted, int removed, int toInventory, int dropped, List<Integer> keptIndices) {

        public Result {
            keptIndices = List.copyOf(keptIndices);
        }

        public static final Result EMPTY = new Result(0, 0, 0, 0, List.of());

        /** 这一组一块都没动（没有匹配 / 全部留在物品里）。 */
        public boolean nothingChanged() {
            return this.removed == 0;
        }

        /** 留在物品里的块数。 */
        public int kept() {
            return this.keptIndices.size();
        }

        /** 🔴 判据 ①：交出去的块数必须等于背包 ＋ 掉落（一块都不许凭空消失）。 */
        public boolean balanced() {
            return this.removed == this.toInventory + this.dropped;
        }

        /** 🔴 判据 ②：这一组的总块数 = 交出去的 ＋ 留在物品里的。 */
        public boolean conserved() {
            return this.wanted == this.removed + kept();
        }

        /** 一行式读数（日志/面板用，格式稳定）。 */
        public String describe() {
            return "taken=" + this.removed + " inventory=" + this.toInventory
                    + " dropped=" + this.dropped + " kept=" + kept() + " wanted=" + this.wanted;
        }
    }

    private HostPatternMoverTakeout() {}

    /**
     * 把清单里<b>属于这一组（按 type）</b>的那些块交给玩家并记账（<b>不写任何 NBT</b>）。
     *
     * @param units   物品里现在全部样板（顺序 = 物品 NBT 顺序）
     * @param type    要取出的那一组（{@code ""} = 类型未识别那一组）
     * @param handout 玩家侧（背包 / 掉落）；{@code null} ⇒ 全部计入 kept（什么也不丢）
     */
    public static Result run(List<? extends HostPatternMoverPlacer.Unit> units, String type, Handout handout) {
        final String want = norm(type);
        final List<Integer> kept = new ArrayList<>();
        int matched = 0;
        int toInventory = 0;
        int dropped = 0;
        if (units != null) {
            for (int i = 0; i < units.size(); i++) {
                final HostPatternMoverPlacer.Unit unit = units.get(i);
                if (unit == null || !norm(unit.type()).equals(want)) {
                    continue;
                }
                matched++;
                if (handout == null) {
                    kept.add(Integer.valueOf(i));
                    continue;
                }
                boolean ok = false;
                try {
                    ok = handout.toInventory(unit, i);
                } catch (Throwable t) {
                    HostPatternMoverLog.warn("{} 取出：放进背包失败 err={}", HostPatternMoverLog.PREFIX, t.toString());
                }
                if (ok) {
                    toInventory++;
                    continue;
                }
                try {
                    ok = handout.drop(unit, i);
                } catch (Throwable t) {
                    HostPatternMoverLog.warn("{} 取出：掉落失败 err={}", HostPatternMoverLog.PREFIX, t.toString());
                }
                if (ok) {
                    dropped++;
                } else {
                    // 🔴 两条路都没走通 ⇒ 留在物品里（宁可"没取成功"，也绝不"取没了"）
                    kept.add(Integer.valueOf(i));
                }
            }
        }
        return new Result(matched, toInventory + dropped, toInventory, dropped, kept);
    }

    private static String norm(String type) {
        return type == null ? "" : type.trim();
    }
}
