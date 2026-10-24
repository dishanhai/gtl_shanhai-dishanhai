package com.shanhai.machine.stellar;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 「通配符样板的配方类型写在<b>样板自己的 NBT</b> 上」这条约定的<b>唯一落笔处</b>（C 刀 · B 段）。
 *
 * <h2>键名与语义（既有口径，一个字都没改）</h2>
 * <ul>
 *   <li>键 = {@link ShanhaiRecipeTypeIds#KEY_RECIPE_TYPE}（{@code gt_shanhai_recipe_type}）——
 *       与上游老山海<b>同一个键</b>；本轮<b>不新增任何 NBT 键</b>，也不改数据格式；</li>
 *   <li>写入值 = 归一化后的完整 id（走 {@link ShanhaiRecipeTypeIds#canonical(String)}）；</li>
 *   <li>清空 = <b>删键</b>（回「自动识别」），不是写空串 —— 两者读出来都是「没有」，
 *       但删键才不会给样板留下一个假的空值。</li>
 * </ul>
 *
 * <h2>为什么把「改 tag」这三行单独拆出来</h2>
 * 因为它是本轮<b>唯一能离线自证</b>的写回语义：本类只需要一个 {@link CompoundTag}，
 * 不需要机器、不需要注册表、不需要开游戏 ⇒ 探针可以真的跑「写 → 读 → 清 → 读」
 * 与「故意改坏键名必须报红」的负对照（见报告 §负对照）。
 *
 * <p>⚠️ 本类<b>不</b>负责 {@code ItemStack#setTag} / {@code setStackInSlot}：
 * 那是机器侧 {@code applyWildcardRecipeType} 的事（改副本 + 塞回去才会触发内容变更回调，
 * 原地改 tag 不触发 —— 那会「看起来改了、其实没存档」）。
 */
public final class ShanhaiWildcardTypeNbt {

    /** 键（与 {@link ShanhaiRecipeTypeIds#KEY_RECIPE_TYPE} 同一个常量，这里给出本类的明确指向）。 */
    public static final String KEY = ShanhaiRecipeTypeIds.KEY_RECIPE_TYPE;

    private ShanhaiWildcardTypeNbt() {}

    /** 读原始字符串（不做归一化；{@code null} tag / 没有键 ⇒ {@code ""}）。 */
    public static @NotNull String readRaw(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(KEY, net.minecraft.nbt.Tag.TAG_STRING)) {
            return "";
        }
        return tag.getString(KEY);
    }

    /** 读归一化后的 id（{@code ""} = 自动识别）。 */
    public static @NotNull String readCanonical(@Nullable CompoundTag tag) {
        return ShanhaiRecipeTypeIds.canonical(readRaw(tag));
    }

    /**
     * 写入（或清除）类型键。
     *
     * @param tag 目标 tag（调用方保证非 null；一般是「样板副本」的 {@code getOrCreateTag()}）
     * @param raw 任意写法的类型 id；{@code null}/空/归一化后为空 ⇒ <b>删键</b>
     * @return 是否真的改变了内容（{@code false} = 写的是同一个值，调用方可以据此跳过重算）
     */
    public static boolean write(@NotNull CompoundTag tag, @Nullable String raw) {
        final String normalized = ShanhaiRecipeTypeIds.canonical(raw);
        final boolean changed = !normalized.equals(readCanonical(tag));
        if (normalized.isEmpty()) {
            tag.remove(KEY);
        } else {
            tag.putString(KEY, normalized);
        }
        return changed;
    }

    /** 只做清除（语义等同 {@code write(tag, "")}）。 */
    public static boolean clear(@NotNull CompoundTag tag) {
        return write(tag, "");
    }
}
