package com.shanhai.common.compat;

import com.shanhai.item.ShanhaiItems;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * common 侧<b>唯一</b>引用 Curios 类型的类。
 *
 * <p>🔴 本类的每一个字节码引用都指向 Curios，所以<b>只允许</b>在
 * {@link HaloCurioRegistrar#onCommonSetup} 内的 {@code ModList.isLoaded("curios")} 守卫
 * <b>通过之后</b>被调用。任何"顺手在本类里加个静态常量给别人用"的改动都会把守卫架空。
 *
 * <h2>接口面（javap 原文见报告判据 2）</h2>
 * <pre>
 * CuriosApi.registerCurio(Item, ICurioItem)
 *     descriptor: (Lnet/minecraft/world/item/Item;Ltop/theillusivec4/curios/api/type/capability/ICurioItem;)V
 * ICurioItem.canEquip(SlotContext, ItemStack)              -> boolean
 * ICurioItem.canEquip(String identifier, LivingEntity, ItemStack) -> boolean
 * </pre>
 *
 * <h2>「任意槽」到底是靠什么成立的（这是本功能最容易想当然的一步）</h2>
 * 反编译 Curios 5.14.1 后可以确定：<b>{@code canEquip} 本身并不决定物品能进哪些槽</b>。
 * 槽位归属由 {@code CuriosImplMixinHooks.isStackValid(SlotContext, ItemStack)} 决定：
 * <pre>
 *   Map keys = getItemStackSlots(stack, entity).keySet();   // 该物品【能进】的槽名集合
 *   if (!keys.isEmpty()) {
 *       if (identifier.equals("curio"))            return true;   // 槽名恰为 curio ⇒ 谁都放
 *       if (keys.contains(identifier))             return true;
 *       if (keys.contains("curio"))                return true;   // 🔴 关键分支
 *       return false;
 *   }
 * </pre>
 * 而 {@code getItemStackSlots} 又按「槽的 validators」过滤；{@code SlotType.Builder.build()}
 * 在 {@code validators == null} 时<b>默认</b>塞入 {@code Set.of(new ResourceLocation("curios", "tag"))}
 * （字节码偏移 114–136）。内置谓词 {@code curios:tag}（{@code lambda$static$9}）为：
 * <pre>
 *   stack.is(ItemTags.create("curios:" + slotContext.identifier()))   // 本槽自己的标签
 *     || stack.is(ItemTags.create("curios:curio"))                     // 🔴 通用标签
 * </pre>
 * ⇒ <b>只要物品在物品标签 {@code curios:curio} 里，它对每一个「用默认 validators 的槽」都合法</b>，
 * 于是 {@code keys} 里就含 {@code "curio"} ⇒ {@code isStackValid} 对任意槽名返回 true。
 * 这正是同环境里 {@code ae2wtlib} 的做法（该 jar 内 {@code data/curios/tags/items/curio.json}
 * 列了 4 台无线终端）——任务书要求的「就像无线样板编码终端一样」在实现层面就是这条。
 *
 * <p>同名标签文件的合并由原版 {@code TagLoader} 负责：它对每个位置取
 * {@code listMatchingResources(...)} 返回的 <b>List&lt;Resource&gt;</b> 逐个读入并 {@code add}
 * （仅当该文件的 {@code "replace": true} 才 clear），所以本 mod 的
 * {@code data/curios/tags/items/curio.json} 与 ae2wtlib 的那份<b>叠加而非互相覆盖</b>。
 *
 * <p>{@code canEquip} 仍然实现为恒 {@code true}（任务书要求，且它是"这个槽实例此刻是否允许"
 * 这层的判断，与上面的"槽位归属"是两层，缺一不可）。
 */
public final class HaloCurioBridge {

    /** 恒真的饰品行为：任意槽可戴、可取下、可用右键直接装备。掉落规则保持默认（与物品一致）。 */
    private static final ICurioItem CURIO_ITEM = new ICurioItem() {
        @Override
        public boolean canEquip(SlotContext slotContext, ItemStack stack) {
            return true;
        }

        @Override
        public boolean canEquip(String identifier, net.minecraft.world.entity.LivingEntity entity, ItemStack stack) {
            return true;
        }

        @Override
        public boolean canRightClickEquip(ItemStack stack) {
            return true;
        }

        @Override
        public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
            return true;
        }
    };

    private HaloCurioBridge() {}

    /** 前置：调用方已确认 Curios 在 {@code ModList} 里（见 {@link HaloCurioRegistrar}）。 */
    static void register() {
        CuriosApi.registerCurio(ShanhaiItems.HALO_END.get(), CURIO_ITEM);
    }
}
