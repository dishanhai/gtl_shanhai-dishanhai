package com.shanhai.mixin;

import com.shanhai.common.itemedit.ShanhaiItemOverrideHooks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>第三条拦截面：{@code Item#getDescription()} 的 {@code RETURN}</b>。
 *
 * <h2>为什么非得单独有一条（这一条是本工程的真实踩坑）</h2>
 * {@code RealityShellItem} 的类注释里记着逐字取证：
 * <pre>
 *   GTCEu 的 Jade 提供器 integration.jade.provider.RecipeOutputProvider#getItemName
 *   走的是 ItemStack.getItem().getDescription()（无参重载），不是 getHoverName()。
 *   默认实现 = Component.translatable(getDescriptionId())
 *   ⇒ 没有 lang 就显示 item.shanhai.reality_item_N（Jade 还套一层方括号）。
 * </pre>
 * 上一轮踩过一次「机器里产物名还是 {@code item.shanhai.xxx}」—— 根因就是<b>只改了
 * {@code getHoverName()} 没改这里</b>。所以这条不是锦上添花，是用户点名的三处之一
 * （物品栏 / JEI / <b>机器产物名</b>）。
 *
 * <h2>javap 取证</h2>
 * <pre>
 *   javap -p net.minecraft.world.item.Item
 *     public net.minecraft.network.chat.Component getDescription();        ← 可覆写，非 final
 *     public final int getMaxStackSize();                                 ← 这个是 final（所以堆叠拦在 ItemStack 上）
 * </pre>
 *
 * <h2>⚠️ 固有边界（写进报告）</h2>
 * 注入点在 {@code Item} <b>基类</b>上 ⇒ 某个模组的物品<b>自己覆写了</b> {@code getDescription()}
 * 的话，它会盖掉我们的注入。这不是本项目能绕过的（除非对每个物品类各挂一个 mixin）。
 *
 * <h2>本类体内零 MC 成员引用</h2>
 * 理由见 {@link ShanhaiItemStackOverrideMixin} 类注释 §2。这里只转交 {@code (Object) this}
 * 与 {@code cir.getReturnValue()}。
 */
@Mixin(Item.class)
public abstract class ShanhaiItemDescriptionMixin {

    /** {@code null} = 不改。 */
    @Inject(
            method = "getDescription()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private void shanhai$overrideDescription(CallbackInfoReturnable<Component> cir) {
        try {
            final Component out = ShanhaiItemOverrideHooks.description((Object) this, cir.getReturnValue());
            if (out != null) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // 第二道保险：描述出问题也绝不改变原版行为
        }
    }
}
