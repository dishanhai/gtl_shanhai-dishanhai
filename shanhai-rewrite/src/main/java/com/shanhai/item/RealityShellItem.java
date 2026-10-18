package com.shanhai.item;

import com.shanhai.common.itemedit.ShanhaiItemLore;
import com.shanhai.common.reality.ShanhaiRealityItemTable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 「现实物品」的一个占位壳（{@code shanhai:reality_item_1..128}）。
 *
 * <h2>1. 🔴 为什么它必须在加载期就注册（而不是运行期 new）</h2>
 * 1.20.1 的物品注册表在 {@code RegisterEvent} 之后就是冻结的 ⇒ <b>运行期造不出新物品</b>。
 * 用户要的是"128 个我自己命名的物品"，而 1.20.1 又没有 DataComponents
 * （用"一件物品 + NBT 变体"会破坏堆叠/JEI/配方匹配）⇒ 唯一正确解就是
 * <b>预注册 128 个真物品，运行期只改它们的"长相"</b>。本类就是那个壳。
 *
 * <h2>2. 每个壳的可变部分全靠覆写读表（都是"每次调都用最新表"）</h2>
 * <ul>
 *   <li>{@link #getName(ItemStack)} —— 显示名（第 ⑨ 条：<b>改名只改显示名，id 不变</b>，
 *       所以存档里已有的同类物品会跟着改名，绝不会消失）；</li>
 *   <li>{@link #getMaxStackSize()} —— 堆叠上限（每个槽位是<b>独立的注册物品</b>，所以能各不同）；</li>
 *   <li>{@link #getRarity(ItemStack)} —— 稀有度（名字颜色）；</li>
 *   <li>{@link #appendHoverText} —— 提示（一行自定义 + 一行固定写 id，方便在 JEI 里认）；</li>
 *   <li>图标不在这里：那是客户端模型的事（{@code ShanhaiRealityItemModels}），
 *       按"表版本号"每次渲染现取。</li>
 * </ul>
 *
 * <h2>3. 未定义时的"默认壳"</h2>
 * 名字 = {@code 现实物品 #n}，图标 = 内置色块 {@code n % 16}（第 ③ 条）。
 * <b>清空名字不会删物品</b>（第 ⑨ 条）—— 它只是退回这个默认壳。
 *
 * <h2>4. 不带任何行为（第 ② 条）</h2>
 * 不 extends 任何工具/食物/方块类，也不覆写 {@code use}/{@code hurtEnemy} 之类的钩子
 * ⇒ 它就是"能堆叠、能在配方里当原料/产物、能显示名字和提示"的纯占位物。
 */
public class RealityShellItem extends Item {

    /** 槽位下标（0 基）；对外的 id 后缀 = 它 + 1。 */
    private final int slot;

    public RealityShellItem(int slot, Item.Properties properties) {
        super(properties);
        this.slot = slot;
    }

    /** 槽位下标（0 基）。 */
    public int shanhaiSlot() {
        return slot;
    }

    /** 这个槽位是否已经填过名字（= 会出现在创造栏 / JEI 里）。 */
    public boolean shanhaiDefined() {
        return ShanhaiRealityItemTable.isDefined(slot);
    }

    @Override
    public Component getName(ItemStack stack) {
        // 🔴 每次都现读表（表是"整表快照 + volatile 引用"，读到的一定是完整的一份）
        return Component.literal(ShanhaiRealityItemTable.displayName(slot));
    }

    /**
     * 🔴 2026-10-17 用户报「放进机器产物里显示的是源码」—— 真因：
     * GTCEu 的 Jade 提供器 {@code integration.jade.provider.RecipeOutputProvider#getItemName}
     * 走的是 <b>{@code ItemStack.getItem().getDescription()}（无参重载，不是 getName(ItemStack)）</b>；
     * 默认实现是 {@code Component.translatable(getDescriptionId())} ⇒ 没有 lang 就显示
     * {@code item.shanhai.reality_item_N}（Jade 还套了一层方括号）。
     * （{@code javap} 实证：{@code Item} 里返回 Component 的只有 {@code getDescription()} 与
     * {@code getName(ItemStack)} 两个 ⇒ 那条路走的必然是前者。）
     * ⇒ 把 {@code getDescription()} 也覆写成显示名，两条路都拿到正确名字。
     */
    @Override
    public Component getDescription() {
        return Component.literal(ShanhaiRealityItemTable.displayName(slot));
    }

    /**
     * 🔴 <b>堆叠上限必须覆写带 {@link ItemStack} 的那个重载</b>。
     *
     * <p>零参数的 {@code Item#getMaxStackSize()} 在 1.20.1 Forge 里是 <b>final</b>
     * （{@code javap} 实证：{@code public final int getMaxStackSize();}），覆写不了；
     * 而 {@code ItemStack#getMaxStackSize()} 的字节码是
     * <pre>
     *   invokevirtual Item.getMaxStackSize:(Lnet/minecraft/world/item/ItemStack;)I
     * </pre>
     * ⇒ 逐物品栈生效的那个口子是 Forge 加的 {@code IForgeItem#getMaxStackSize(ItemStack)}
     * （{@code javap} 实证：{@code public default int getMaxStackSize(net.minecraft.world.item.ItemStack);}）。
     * 每个槽位是独立的注册物品，所以每个槽位能有各自的堆叠上限。
     */
    @Override
    public int getMaxStackSize(ItemStack stack) {
        return ShanhaiRealityItemTable.maxStackOf(slot);
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return rarityByName(ShanhaiRealityItemTable.rarityOf(slot));
    }

    /** 行首标记：按住 Shift 才显示。（常量实体现在 {@link ShanhaiItemLore}，此处只是别名） */
    public static final String TAG_SHIFT = ShanhaiItemLore.TAG_SHIFT;
    /** 行首标记：按住 Ctrl 才显示。（常量实体现在 {@link ShanhaiItemLore}，此处只是别名） */
    public static final String TAG_CTRL = ShanhaiItemLore.TAG_CTRL;

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                               TooltipFlag flag) {
        final String lore = ShanhaiRealityItemTable.loreOf(slot);
        // 🔴 2026-10-17 用户点单：提示行可以带「按住 Shift / Ctrl 才显示」的条件。
        // 键位状态是纯客户端的（Screen）⇒ 经 DistExecutor 间接取，服务端那份永远走 false 分支。
        final boolean shift = ShanhaiItemLore.shiftHeld();
        final boolean ctrl = ShanhaiItemLore.ctrlHeld();
        // 🔴 2026-11 提取：解析逻辑搬到 com.shanhai.common.itemedit.ShanhaiItemLore
        //    （「改写任意已存在物品」那个功能必须用同一份实现 —— 行首标记 [s]/[c] 的语义只许有一个出处）。
        //    本方法的对外行为逐字不变：同样的行首标记、同样的 §7 前缀、同样的门槛、同样的提示行。
        final boolean hasGated = ShanhaiItemLore.addLoreLines(lore, shift, ctrl, tooltip);
        if (hasGated && !shift && !ctrl) {
            tooltip.add(Component.literal(ShanhaiItemLore.HINT));
        }
        // 🔴 2026-10-17 用户点单：堆叠上限放开到 int，但 >64 要在提示里给出风险说明
        if (ShanhaiRealityItemTable.maxStackOf(slot) > ShanhaiRealityItemTable.DEFAULT_STACK) {
            tooltip.add(Component.literal("§8⚠ 堆叠上限 >64：部分原版/模组逻辑仍按 64 处理，可能出现未知 bug"));
        }
        // 🔴 2026-10-17：不再自己写 id 行 —— 下面 super 那一链已经会打出
        // 「shanhai:reality_item_N」和 mod 名（Forge 常规行为），自己再写一条就重复
        //（用户截图里 id 出现两行）。super 保持调用 ⇒ 与普通物品同形。
        super.appendHoverText(stack, level, tooltip, flag);
    }

    /** 字符串 ⇒ 原版稀有度（认不出来 = 普通）。 */
    public static Rarity rarityByName(String name) {
        if (name == null) {
            return Rarity.COMMON;
        }
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> Rarity.COMMON;
        };
    }

    /** 稀有度 ⇒ 面板上显示的中文（面板与命令共用一份口径）。 */
    public static String rarityZh(String name) {
        return switch (ShanhaiRealityItemTable.sanitizeRarity(name)) {
            case "uncommon" -> "罕见";
            case "rare" -> "稀有";
            case "epic" -> "史诗";
            default -> "普通";
        };
    }
}
