package com.shanhai.mixin;

import com.shanhai.common.itemedit.ShanhaiItemOverrideHooks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 山海重构 · <b>「改写任意已存在物品」的三条拦截面（挂在 {@code ItemStack} 上）</b>。
 *
 * <h2>1. 每条注入的类/方法/位置 + 为什么是这里（{@code javap} 取证，不是猜的）</h2>
 * <pre>
 *   javap -p -c &lt;forge-1.20.1-47.4.16_mapped_parchment-1.20.1.jar&gt; net.minecraft.world.item.ItemStack
 *
 *   ① public net.minecraft.network.chat.Component getHoverName();
 *        54: aload_0  55: invokevirtual Item.getName:(ItemStack)Component  62: areturn
 *        ⇒ 物品栏 / tooltip / JEI 列表都读它（改名主通道）
 *        ⇒ 注入点 RETURN（本方法有 37 / 62 两处 areturn，两处都会被注入，逻辑同一条）
 *
 *   ② public java.util.List&lt;Component&gt; getTooltipLines(Player, TooltipFlag);
 *         0: invokestatic Lists.newArrayList()  ⇒ 返回值是【可变】ArrayList
 *      1410: aload_3  1411: areturn
 *        ⇒ 注入点 RETURN：append 就地追加；replace 返回一份新列表
 *
 *   ③ public int getMaxStackSize();
 *         1: invokevirtual Item.getMaxStackSize:(ItemStack)I   8: ireturn
 *        ⇒ 逐物品栈生效的那个口子；拦这里 = 对任意物品（原版 / 别的 mod）一视同仁
 *        ⚠️ 为什么不拦 Item#getMaxStackSize()：它是【final】（javap：public final int getMaxStackSize();）
 * </pre>
 * 三个方法都<b>不是 final</b>，都只有一个重载（描述符写错的空间都没有）。
 *
 * <h2>2. 🔴 本类体内零 Minecraft 成员引用（本工程铁律）</h2>
 * 见 {@code ShanhaiSetBlockWatchMixin} 类注释 §2 的实测证据：mixin 类体内对 MC 方法的调用
 * <b>在生成的 refmap 里没有条目</b> ⇒ 生产环境拿开发名去 SRG 命名的类上找、找不到。
 * 所以本类只做三件事：<b>类型对得上的形参</b>、{@code (Object) this}、一次
 * {@link ShanhaiItemOverrideHooks} 静态调用。{@code getItem()} / {@code isEmpty()} /
 * {@code BuiltInRegistries.ITEM.getKey()} 全部在那个普通类里做（普通类会被 reobf，SRG 名正常）。
 * <p>（形参/返回值的<b>类型</b>{@code Component} / {@code Player} / {@code TooltipFlag} 不需要映射：
 * 类名不混淆，只有成员名才需要。）
 *
 * <h2>3. 🔴 它是「零行为改动」的</h2>
 * <ul>
 *   <li>只用 {@code @Inject(at = RETURN, cancellable = true)}，<b>不</b> {@code @Overwrite}、
 *       <b>不</b> {@code @Redirect}；</li>
 *   <li>钩子返回 {@code null} / 哨兵值 ⇒ <b>不</b> {@code setReturnValue} ⇒ 原版返回值原样走；
 *       表为空时（没配任何覆盖）第一道闸就返回，等于没挂钩；</li>
 *   <li>钩子内部整段 {@code try/catch(Throwable)} 自吞 + 一次性 WARN；本类再套一层 ⇒
 *       我们坏了也不会改变任何原版行为（{@code ItemStack} 是全局最热的类，这里静默失效比炸更安全）；</li>
 *   <li>注入点找不到时 {@code require = 1} 让<b>加载期就炸</b>（与 {@code ShanhaiSmokeMixin} 同一取舍：
 *       "看着像挂上了、其实没挂"是最坏的失败形态）。</li>
 * </ul>
 */
@Mixin(ItemStack.class)
public abstract class ShanhaiItemStackOverrideMixin {

    /** ① 显示名。{@code null} = 不改（原版结果原样返回）。 */
    @Inject(
            method = "getHoverName()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private void shanhai$overrideHoverName(CallbackInfoReturnable<Component> cir) {
        try {
            final Component out = ShanhaiItemOverrideHooks.hoverName((Object) this, cir.getReturnValue());
            if (out != null) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变原版的名字
        }
    }

    /** ② 提示行。{@code null} = 不改（append 模式是就地追加，原列表已被改）。 */
    @Inject(
            method = "getTooltipLines(Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private void shanhai$overrideTooltip(Player player, TooltipFlag flag,
                                         CallbackInfoReturnable<List<Component>> cir) {
        try {
            final List<Component> out = ShanhaiItemOverrideHooks.tooltip((Object) this, cir.getReturnValue());
            if (out != null) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // 第二道保险：提示行出问题也绝不吞掉原版 tooltip
        }
    }

    /**
     * ③ 堆叠上限。{@code null} = 不改。
     *
     * <p>⚠️ 这是全游戏最热的路径之一（每帧每格的堆叠判定都走它），所以钩子第一句就是
     * {@code active()} 的 volatile 快查 —— 没配覆盖时等价于没挂钩。
     */
    @Inject(
            method = "getMaxStackSize()I",
            at = @At("RETURN"),
            cancellable = true,
            require = 1)
    private void shanhai$overrideMaxStack(CallbackInfoReturnable<Integer> cir) {
        try {
            final Integer out = ShanhaiItemOverrideHooks.maxStack((Object) this, cir.getReturnValue());
            if (out != null) {
                cir.setReturnValue(out);
            }
        } catch (Throwable ignored) {
            // 第二道保险：堆叠上限绝不许因我们而异常
        }
    }
}
