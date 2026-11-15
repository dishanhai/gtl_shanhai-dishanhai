package com.shanhai.common.reality;

import com.shanhai.ShanhaiMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 65 件 {@code mc_*} 事件物品的<b>实现类</b>（阶段 2：把它们从裸 {@code Item} 换成"能开面板"的真类）。
 *
 * <h2>一、只换实现类，别的都不动</h2>
 * 阶段 1 这 65 件是 {@code ShanhaiItems#module(id)}（裸 {@code Item} + 静态模型 + 静态贴图）。
 * 本类<b>只</b>多两件事：{@link #use} 开面板、{@link #appendHoverText} 加一行操作提示。
 * <b>不动</b>：注册 id、显示名（lang 键 {@code item.shanhai.<id>}）、图标（{@code models/item/<id>.json}
 * ＋ {@code textures/item/<id>.png}）、创造栏位置（{@code ShanhaiCreativeModeTabs#fill} 里的 accept 顺序）、
 * 堆叠上限（仍是默认 64 —— 属性在注册那一刻由 {@code Item.Properties} 定，这里一个字节没改）。
 *
 * <h2>二、🔴 为什么 {@code use} 只在服务端开面板、客户端只回 {@code sidedSuccess}</h2>
 * 逐字照抄工程既有先例 {@code HostPatternMoverItem#use}（主机样板搬运工具）：
 * <pre>
 *   if (!level.isClientSide &amp;&amp; player instanceof ServerPlayer serverPlayer) { 工厂.open(...); }
 *   return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
 * </pre>
 * <ul>
 *   <li><b>必须走 {@code UIFactory}</b>：纯客户端开的面板，LDLib 的控件动作在服务端只认
 *       {@code player.containerMenu instanceof ModularUIContainer}，否则<b>直接丢弃、不报错</b>
 *       （{@code ShanhaiCreativeItemsFactory} / {@code HostPatternMoverFactory} 的类注释里各写了一遍）；</li>
 *   <li><b>客户端不许回 {@code pass}</b>：回 pass 会在本地把这次交互再跑一遍（原版会再走一次
 *       {@code use}）；回 {@code sidedSuccess} 才是"这一下我们吃掉了"。</li>
 * </ul>
 *
 * <h2>三、⚠️ {@code use} 会在"右键方块但方块不吃这一下"之后也被调用</h2>
 * 原版 {@code ServerPlayerGameMode#useItemOn} 的顺序是：{@code onItemUseFirst}（本类不覆写 ⇒ PASS）
 * → 方块自己的 {@code use}（吃到就返回，比如箱子/机器 GUI）→ 最后才 {@code ItemStack#use}。
 * ⇒ <b>右键空气</b>必定开面板；右键"什么也不做的方块"也会开；右键箱子/机器仍是方块 GUI
 * （它们先吃掉了）。这与先例 {@code HostPatternMoverItem} 的落点一致，不是本类的新行为。
 */
public class ShanhaiRealityInputItem extends Item {

    /** 注册 id 的 path 部分（例：{@code mc_livingattackevent}）。 */
    private final String path;

    public ShanhaiRealityInputItem(String path, Item.Properties properties) {
        super(properties);
        this.path = path == null ? "" : path;
    }

    /** 注册 id 的 path 部分（{@code mc_livingattackevent}）。 */
    public String path() {
        return this.path;
    }

    /**
     * 完整物品 id（{@code shanhai:mc_livingattackevent}）—— 面板 Holder、落盘 JSON 的键、日志都用它。
     *
     * <p>为什么不在构造器里算好：注册那一刻 {@code BuiltInRegistries.ITEM} 还没有本物品，
     * 只有字符串是现成的；用一个不依赖注册表的纯字符串函数就没有时序问题。
     */
    public String fullId() {
        return ShanhaiMod.MOD_ID + ":" + this.path;
    }

    // ------------------------------------------------------------------ 右键 = 开面板

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        // 🔴 真正开面板只在服务端那一支；客户端只回 sidedSuccess（见类注释 §二）
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            ShanhaiRealityInputFactory.open(serverPlayer, fullId());
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    // ------------------------------------------------------------------ 提示（只多一行"怎么用"）

    /**
     * 多一行操作提示。
     *
     * <p>⚠️ 这一行<b>不</b>是需求里写的面板内容，是为了"让玩家知道这物品能右键"（阶段 1 的 95 件里
     * 只有本批 65 件有右键行为）。文案走 lang（{@code shanhai.reality_input.tooltip.use}）。
     */
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("shanhai.reality_input.tooltip.use")
                .withStyle(ChatFormatting.DARK_AQUA));
    }
}
