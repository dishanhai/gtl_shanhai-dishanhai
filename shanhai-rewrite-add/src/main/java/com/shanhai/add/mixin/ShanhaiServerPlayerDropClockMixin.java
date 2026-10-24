package com.shanhai.add.mixin;

import com.shanhai.add.time.ShanhaiClockHooks;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>缺陷 ① 「Q 丢物品凭空消失」的服务端落点</b>。
 *
 * <h2>1. 为什么拦在 {@code ServerPlayer#drop(boolean)} 的 HEAD</h2>
 * 原版（47.3.0 sources `ServerPlayer.java:1674-1684`）：
 * <pre>
 *   public boolean drop(boolean p_182295_) {
 *      ItemStack selected = inventory.getSelected();
 *      ...
 *      ItemStack itemstack = inventory.removeFromSelected(p_182295_);   // ← 背包在这里就扣了
 *      this.containerMenu.findSlot(...).ifPresent(...);
 *      return ForgeHooks.onPlayerTossEvent(this, itemstack, true) != null;   // ← 我们的 ItemTossEvent 才在这里
 *   }
 * </pre>
 * 旧实现挂在 {@code ItemTossEvent}（可取消）上 ⇒ 只是"不生成物品实体"，<b>扣掉的数量回不来</b> ⇒
 * 用户实机看到的现象正是"时间暂停不让我丢物品，但按 Q 物品栏的物品还是减少了"。
 *
 * <p>HEAD 取消 ⇒ 上面的 {@code removeFromSelected} 一行都不跑 ⇒ <b>数量不变、也不产生 ItemEntity</b>。
 *
 * <h2>2. 这条链是谁调进来的</h2>
 * {@code ServerGamePacketListenerImpl#handlePlayerAction} 的
 * {@code DROP_ITEM} / {@code DROP_ALL_ITEMS} 两个分支（`ServerGamePacketListenerImpl.java:1002-1013`）
 * 分别调 {@code this.player.drop(false)} / {@code this.player.drop(true)} ⇒ <b>服务端唯一入口</b>。
 * 客户端那一半见 {@code ShanhaiClientPlayerDropClockMixin}（{@code LocalPlayer#drop(boolean)} 会先在本地扣）。
 *
 * <h2>3. 本类体内零 Minecraft 成员引用</h2>
 * 只有形参/返回值的<b>类型</b>、{@code (Object) this}、以及对普通类
 * {@link ShanhaiClockHooks}（会被正常 reobf）的静态调用 —— 与工程既有约定一致
 * （refmap 只映射 {@code @Inject} 的方法名，成员引用在 SRG 命名下会找不到）。
 */
@Mixin(ServerPlayer.class)
public abstract class ShanhaiServerPlayerDropClockMixin {

    /**
     * @param dropAll 原版形参（true = 整叠丢；false = 丢一个），本注入不使用，照抄以匹配描述符
     * @param cir     返回值句柄：返回 {@code false} = "这次丢弃失败"（原版语义就是布尔成功标志）
     */
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$denyDropBeforeInventoryLoss(boolean dropAll, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ShanhaiClockHooks.denyItemDrop((Object) this)) {
                // 原版 drop(boolean) 的返回值就是"成功与否"，Minecraft#handleKeybinds 用它决定要不要挥手
                cir.setReturnValue(Boolean.FALSE);
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 照常丢（宁可漏拦一次，也不能让玩家在正常状态下丢不出东西）
        }
    }
}
