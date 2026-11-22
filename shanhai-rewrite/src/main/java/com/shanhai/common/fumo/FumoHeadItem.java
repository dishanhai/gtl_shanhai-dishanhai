package com.shanhai.common.fumo;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Fumo 玩偶的<b>物品形态</b>：一个普通 {@link BlockItem}，额外的行为只有两条 ——
 * ① 可以放进原版头盔格；② 手持右键空气时，按原版换装语义<b>直接戴上</b>（与头盔格里原来那件交换）。
 *
 * <h2>为什么只覆写 {@code getEquipmentSlot(ItemStack)} 就能进头盔格</h2>
 * 1.20.1 Forge 在 {@code IForgeItem} 上提供 {@code @Nullable EquipmentSlot getEquipmentSlot(ItemStack)}
 * （默认 {@code null}），而 {@code ItemStack#getEquipmentSlot()} 会转发到它；
 * {@code LivingEntity#getEquipmentSlotForItem(...)} 又<b>优先</b>取这个值（Forge 补丁：
 * {@code if (slot != null) return slot;}），所以：
 * <ul>
 *   <li>{@code InventoryMenu.ArmorSlot#mayPlace(stack)} 走 {@code stack.canEquip(...)}，
 *       其默认实现 = {@code Mob.getEquipmentSlotForItem(stack) == armorType}
 *       ⇒ 本物品返回 {@code HEAD} ⇒ <b>头盔格接受它</b>（拖进去 / Shift 快速移动都行）；</li>
 *   <li>不需要 {@code ArmorItem}、不需要 Curios、不需要 mixin。</li>
 * </ul>
 *
 * <h2>戴在头上时「显示方块模型」是原版就有的路径（不是 {@code ArmorItem}）</h2>
 * {@code net.minecraft.client.renderer.entity.layers.CustomHeadLayer}（玩家渲染器
 * {@code PlayerRenderer} 构造器里就 addLayer 了它）对头部槽位的物品：
 * <pre>
 *   如果是 {@code AbstractSkullBlock} 的 BlockItem  ⇒ 画头颅模型；
 *   如果是 {@code ArmorItem} 且槽位是 HEAD          ⇒ 什么都不画（交给 HumanoidArmorLayer 画盔甲）；
 *   否则                                           ⇒ 用 {@code ItemDisplayContext.HEAD}
 *                                                    调 {@code ItemInHandRenderer#renderItem(...)} 画物品模型
 * </pre>
 * 我们既不是头颅也不是 {@code ArmorItem} ⇒ 走第三条：<b>用物品模型 + 模型 JSON 里的
 * {@code display.head} 变换</b>画在头上。这就是「静态玩偶能当帽子戴」的完整机制。
 *
 * <p>三个模型的 {@code display.head}：{@code dishanhai_fumo}（自带，{@code translation [0,11.5,0],
 * scale 0.6}）；{@code beibeipuo_fumo} 自己只写了 {@code ground}/{@code gui}，{@code head} 由父模型
 * {@code shanhai:block/fumo/skin_64} 按「逐 display context 回退」提供（已核 {@code BlockModel#getTransform}）；
 * {@code dgyjh02804_fumo} 直接复用 {@code dishanhai_fumo} 的几何，同样自带。
 *
 * <h2>右键空气直接戴上（{@link #use}）</h2>
 * 这里<b>不重写</b>换装逻辑，而是实现原版 {@link Equipable} 接口、调用它的默认方法
 * {@code swapWithEquipmentSlot(...)}（1.20.1 原版 {@code ArmorItem.use} 就是这一行）：
 * 交换「手里的这一件」和「头盔格里那一件」，并把旧头盔作为返回值交回去。
 * <p>🔴 <b>返回值必须用 {@code swapWithEquipmentSlot} 的结果，不能吞掉</b>：
 * {@code Equipable.swapWithEquipmentSlot} 的源码里旧头盔是作为
 * {@code InteractionResultHolder} 的 {@code ItemStack} 返回的（{@code itemstack2}），
 * 由 {@code ServerPlayerGameMode#useItem} 第 293–295 行 {@code player.setItemInHand(hand, itemstack)}
 * 放回手中；若这里改成返回 {@code success(原 stack)}，旧头盔就<b>没有人接</b>，会被丢掉。
 * <p>客户端只返回 success、不改任何状态（真正的交换只在服务端发生，随后由
 * {@code ServerPlayerGameMode#useItem} 末尾的 {@code inventoryMenu.sendAllDataToRemote()} 同步）。
 * <p>旁观者：{@code ServerPlayerGameMode#useItem} 在 {@code SPECTATOR} 模式<b>直接 return PASS</b>
 * （第 277–278 行），{@code use} 根本不会被调用；创造模式仍会照常交换（与原版盔甲右键一致）。
 *
 * <p>⚠️ 右键<b>方块</b>时先走 {@link BlockItem#useOn}（放置方块），只有右键空气（或放置失败后的
 * 回退路径）才会走到 {@code use} —— 「放地上」和「戴头上」两种用法并存。
 *
 * <p>🔴 <b>贴图来源</b>：{@code dgyjh02804_fumo} 的皮肤是<b>上游原皮肤</b>，逐字节复制自
 * {@code gt_shanhai-4.9.4.jar} 的 {@code assets/gt_shanhai/textures/block/dgyjh02804_fumo.png}
 * （sha256 {@code AECCB87C…}，未修改）；{@code #details} 按上游行为继承
 * {@code shanhai:block/fumo/dishanhai_fumo_details}（所以会带 dishanhai 的金色徽章）。
 * 许可以上游仓库为准：{@code dishanhai/gtl_shanhai-dishanhai} 是 <b>LGPL-3.0</b>，本重制版本来就是
 * 该仓库的派生分支，LICENSE / {@code mods.toml} 的许可统一是<b>未结事项</b>（另案处理）。
 * 详见 {@code assets/shanhai/models/block/fumo/CREDITS.txt} 与
 * {@code temp/fumo-recon/CREDITS-shanhai-fumo.md}。
 */
public class FumoHeadItem extends BlockItem implements Equipable {

    public FumoHeadItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * Forge 的「这个 stack 属于哪个装备槽」扩展；只影响能放进哪个槽，不改变放置行为
     * （右键地面仍然是 {@link BlockItem#useOn} 先跑）。
     */
    @Override
    public EquipmentSlot getEquipmentSlot(ItemStack stack) {
        return EquipmentSlot.HEAD;
    }

    /**
     * 原版 {@link Equipable} 的无参版本（与上面那条 Forge 扩展同义）。
     * 实现这个接口的<b>唯一目的</b>是拿到 {@code swapWithEquipmentSlot(...)} 的默认实现，
     * 不重写换装语义。{@code Equipable} 只是给 {@code Mob.getEquipmentSlotForItem} /
     * {@code InventoryMenu.onEquipItem} 用来识别「可装备物」，不改变渲染（渲染仍走
     * {@code CustomHeadLayer}，见类注释）。
     */
    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    /**
     * 手持右键空气 = 直接戴到原版头盔格（与槽里原来那件交换，旧的那件回到手里）。
     *
     * <p>服务端返回 {@code swapWithEquipmentSlot(...)} 的原样结果 —— 它的 {@code ItemStack}
     * 就是旧头盔，必须沿返回值交回 {@code ServerPlayerGameMode#useItem} 才会回到手中。
     * 客户端只回 success、不交换（服务端在同一 tick 末尾发 {@code sendAllDataToRemote}）。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return this.swapWithEquipmentSlot(this, level, player, hand);
    }
}
