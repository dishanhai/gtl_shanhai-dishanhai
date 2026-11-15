package com.shanhai.add.guard.state;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 山海 · <b>玩家物品栏的「权威快照 / 差异 / 补回」</b>（<b>只调原版 API</b>，不认 mod）。
 *
 * <h2>它只做三件事</h2>
 * <pre>
 *   snapshot(player)        拍快照（主背包 36 ＋ 盔甲 4 ＋ 副手 1 ＝ 41 格，{@code ItemStack#copy}）
 *   lostCount(snap, player) 数「快照里有、现在空了」的槽（只认"变空"，不认"换了种类"）
 *   restoreMissing(...)     把这些槽补回去（种类＋NBT＋数量原样），返回补了几格
 * </pre>
 *
 * <h2>🔴 为什么"补"这件事必须在 {@link ShanhaiStateRules#inventoryVerdict} 判过之后才做</h2>
 * 「缺了就补」会造出<b>复制物品</b>（丢出去 → 地上一个 → 我们又补一个），
 * 那是刷物品漏洞级别的问题，比"物品被抹掉"更糟。
 * ⇒ 本类<b>只负责搬数据</b>，判据（一次少掉 ≥30% 且没开容器且在窗口内）一律在纯 JDK 的那个类里。
 *
 * <h2>不碰哪些东西</h2>
 * <ul>
 *   <li>光标上捏着的那一叠（拖拽中）：{@code containerMenu.getCarried()} 非空 ⇒ 调用方直接不判；</li>
 *   <li>别的容器（箱子/机器界面）：{@code containerMenu != inventoryMenu} ⇒ 调用方直接不判；</li>
 *   <li>末影箱、饰品栏、Curios 等非原版格子：<b>本类不认识、也不碰</b>（只做原版 41 格）。</li>
 * </ul>
 */
public final class ShanhaiStateInventory {

    /** 主背包格数。 */
    public static final int ITEM_SLOTS = 36;
    /** 盔甲格数。 */
    public static final int ARMOR_SLOTS = 4;
    /** 副手格数。 */
    public static final int OFFHAND_SLOTS = 1;
    /** 总格数（41）。 */
    public static final int SLOTS = ITEM_SLOTS + ARMOR_SLOTS + OFFHAND_SLOTS;

    private ShanhaiStateInventory() {}

    /** 一次快照（三张表的拷贝；{@code null} 元素一律换成 {@link ItemStack#EMPTY}）。 */
    public static final class Snapshot {

        final ItemStack[] items;
        final ItemStack[] armor;
        final ItemStack[] offhand;

        Snapshot(ItemStack[] items, ItemStack[] armor, ItemStack[] offhand) {
            this.items = items;
            this.armor = armor;
            this.offhand = offhand;
        }

        /** 快照里非空槽数。 */
        public int nonEmpty() {
            return count(items) + count(armor) + count(offhand);
        }

        private static int count(ItemStack[] a) {
            int n = 0;
            for (int i = 0; i < a.length; i++) {
                if (a[i] != null && !a[i].isEmpty()) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 拍一张快照（<b>只读</b>：不修改玩家任何状态）。
     *
     * @return 快照；玩家/背包取不到 ⇒ {@code null}（调用方按"这一拍不判"处理）
     */
    public static Snapshot snapshot(ServerPlayer player) {
        try {
            final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            if (inv == null) {
                return null;
            }
            return new Snapshot(copy(inv.items, ITEM_SLOTS), copy(inv.armor, ARMOR_SLOTS),
                    copy(inv.offhand, OFFHAND_SLOTS));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 【只读】数「快照里有、现在空了」的槽数（不认"换了种类"）。 */
    public static int lostCount(Snapshot snap, ServerPlayer player) {
        if (snap == null || player == null) {
            return 0;
        }
        try {
            final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            if (inv == null) {
                return 0;
            }
            return diff(snap.items, inv.items, ITEM_SLOTS)
                    + diff(snap.armor, inv.armor, ARMOR_SLOTS)
                    + diff(snap.offhand, inv.offhand, OFFHAND_SLOTS);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把「快照里有、现在空了」的槽补回去。
     *
     * @return 补了几格（0 = 什么都没补）
     */
    public static int restoreMissing(Snapshot snap, ServerPlayer player) {
        if (snap == null || player == null) {
            return 0;
        }
        int restored = 0;
        try {
            final net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            if (inv == null) {
                return 0;
            }
            restored += fill(snap.items, inv.items, ITEM_SLOTS);
            restored += fill(snap.armor, inv.armor, ARMOR_SLOTS);
            restored += fill(snap.offhand, inv.offhand, OFFHAND_SLOTS);
            if (restored > 0) {
                inv.setChanged();                              // 让容器/旁观者看到变化
                player.containerMenu.broadcastChanges();       // 客户端那一份跟着刷新
            }
        } catch (Throwable ignored) {
            // 补不上只意味着"这一次没救回来"，绝不许把 tick 带崩
        }
        return restored;
    }

    /** 现在是否开着"别的容器"（箱子/机器/交易…）。 */
    public static boolean containerOpen(ServerPlayer player) {
        try {
            return player != null && player.containerMenu != null
                    && player.containerMenu != player.inventoryMenu;
        } catch (Throwable t) {
            return true;   // 判不了 ⇒ 当成"开着" ⇒ 不补（保守）
        }
    }

    /** 光标上是否捏着东西（拖拽中）。 */
    public static boolean cursorNonEmpty(ServerPlayer player) {
        try {
            return player != null && player.containerMenu != null
                    && !player.containerMenu.getCarried().isEmpty();
        } catch (Throwable t) {
            return true;   // 判不了 ⇒ 当成"捏着" ⇒ 不补（保守）
        }
    }

    // ------------------------------------------------------------------ 内部

    private static ItemStack[] copy(net.minecraft.core.NonNullList<ItemStack> src, int size) {
        final ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            final ItemStack s = i < src.size() ? src.get(i) : null;
            out[i] = s == null || s.isEmpty() ? ItemStack.EMPTY : s.copy();
        }
        return out;
    }

    private static int diff(ItemStack[] snap, net.minecraft.core.NonNullList<ItemStack> now, int size) {
        int lost = 0;
        for (int i = 0; i < size; i++) {
            final ItemStack was = snap[i];
            if (was == null || was.isEmpty()) {
                continue;
            }
            final ItemStack is = i < now.size() ? now.get(i) : null;
            if (is == null || is.isEmpty()) {
                lost++;
            }
        }
        return lost;
    }

    private static int fill(ItemStack[] snap, net.minecraft.core.NonNullList<ItemStack> now, int size) {
        int restored = 0;
        for (int i = 0; i < size; i++) {
            final ItemStack was = snap[i];
            if (was == null || was.isEmpty()) {
                continue;
            }
            final ItemStack is = i < now.size() ? now.get(i) : null;
            if (is == null || is.isEmpty()) {
                now.set(i, was.copy());
                restored++;
            }
        }
        return restored;
    }
}
