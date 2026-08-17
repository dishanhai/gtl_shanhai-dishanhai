package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualItemProviderTooltipTest {

    private final VirtualItemProviderItem providerItem = new VirtualItemProviderItem(new Item.Properties());

    @Test
    void boundTooltipAlwaysPrefixesTargetNameWithStoredCount() {
        assertBoundCountPrefix(64);
        assertBoundCountPrefix(1);
    }

    private void assertBoundCountPrefix(int count) {
        ItemStack provider = new ItemStack(providerItem);
        ItemStack target = new ItemStack(Items.IRON_INGOT, count);
        provider.getOrCreateTag().put(VirtualItemProviderHelper.TARGET_ITEM_KEY, target.save(new CompoundTag()));

        List<Component> tooltip = new ArrayList<>();
        providerItem.appendHoverText(provider, null, tooltip, TooltipFlag.NORMAL);

        String bindingLine = tooltip.get(tooltip.size() - 1).getString();
        assertTrue(bindingLine.startsWith("已绑定: " + count + "x "),
                () -> "绑定提示必须包含目标数量，实际为: " + bindingLine);
    }
}
