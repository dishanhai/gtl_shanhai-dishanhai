package com.shanhai.client.event;

import com.shanhai.ShanhaiMod;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 物品的「一句简介」通路：读 lang 键 {@code shanhai.tooltip.desc.<物品注册路径>}。
 *
 * <h2>为什么需要这个类（用户 2026-10-25 点单要修的那件事）</h2>
 * 2026-10-24 把三件「现实物品壳」升成永久物品（{@code advanced_worldline_crystal_core} /
 * {@code miracle_worldline_crystal_core} / {@code material_module_mass_production_module_mk1}）时，
 * <b>名字和图标搬了、描述没搬</b>；而永久物品没有 {@code appendHoverText}，
 * lang 里也从来没有「物品描述」这一类键 ⇒ 描述就那样显示不出来了（用户原话：
 * 「我给他们加了描述，让你写入永久物品，结果我现在看描述没了」）。
 *
 * <h2>🔴 为什么不能只往 lang 里塞键</h2>
 * 本工程<b>唯一的</b> {@code shanhai.tooltip.desc.*} 消费方是 GTCEu 机器的
 * {@code com.shanhai.machine.MachineTooltips}（它只对 {@code MultiBlockMachineDefinition} 的物品生效）。
 * 普通物品没有这条通路 ⇒ 键存在也<b>永远不会显示</b>。
 * （同族先例：{@link HaloEndTooltipHandler} 的类注释就是因为同一个坑才存在。）
 *
 * <p>所以这里补上与机器<b>同一个键格式</b>的通路：客户端挂 {@link ItemTooltipEvent}，
 * 只对 {@code shanhai:*} 的物品、且该物品<b>确实有</b>这个 lang 键时追加行。
 *
 * <h2>🛡 三道守卫（为什么一条都不能少）</h2>
 * <ol>
 *   <li><b>只认 {@code shanhai} 命名空间</b>：别人家的物品 id 撞上同名路径不该被我们改提示；</li>
 *   <li><b>跳过机器方块物品</b>（{@code BlockItem} + {@link MetaMachineBlock}）：
 *       机器的简介由 {@code MachineTooltips} 自己加，键是<b>同一套</b>
 *       ⇒ 不跳过就会在机器提示里出现<b>两遍</b>同一句话；</li>
 *   <li><b>键必须真实存在</b>（{@link I18n#exists}）：否则玩家会看到一行 {@code shanhai.tooltip.desc.xxx}
 *       原文（英文包里尤其容易漏）⇒ 缺键就当"这件物品没有简介"，不显示、不报错。</li>
 * </ol>
 *
 * <h2>格式</h2>
 * 值里可以用真实换行或字面量 {@code \n} 分成多行；每行前面加 {@code §7}（灰），
 * 与「现实物品壳」当年显示的观感逐字一致（{@code ShanhaiItemLore.LINE_PREFIX} 同一个色）。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiItemDescTooltip {

    /** 与 {@code MachineTooltips.DESC_KEY_PREFIX} 逐字相同（机器与物品共用同一套键格式）。 */
    private static final String KEY_PREFIX = "shanhai.tooltip.desc.";
    /** 普通行的颜色前缀，与 {@code ShanhaiItemLore.LINE_PREFIX} 一致。 */
    private static final String LINE_PREFIX = "§7";

    private ShanhaiItemDescTooltip() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        final ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        final ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !ShanhaiMod.MOD_ID.equals(id.getNamespace())) {
            return;
        }
        if (isMachineBlockItem(stack)) {
            return;                                        // 机器简介由 MachineTooltips 负责，别加第二遍
        }
        final String key = KEY_PREFIX + id.getPath();
        if (!I18n.exists(key)) {
            return;                                        // 没写简介 = 不加行（绝不显示裸键名）
        }
        for (String line : I18n.get(key).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            event.getToolTip().add(Component.literal(LINE_PREFIX + line));
        }
    }

    /** 是不是 GTCEu 的机器方块物品（那类物品的简介走 {@code MachineTooltips}）。 */
    private static boolean isMachineBlockItem(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem bi)) {
            return false;
        }
        if (bi.getBlock() instanceof MetaMachineBlock) {
            return true;
        }
        // 兜底：GTCEu 的机器物品还有一个接口族（不同版本类名不同），按接口名判，避免编译期依赖
        final String cn = stack.getItem().getClass().getName();
        return cn.startsWith("com.gregtechceu.gtceu.api.item.");
    }
}
