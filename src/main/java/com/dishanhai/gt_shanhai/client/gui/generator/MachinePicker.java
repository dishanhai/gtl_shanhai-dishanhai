package com.dishanhai.gt_shanhai.client.gui.generator;

import com.dishanhai.gt_shanhai.client.gui.shop.MultiPickerScreen;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * 山海：選取 GT 機器的共用邏輯。樣板生成器的機器格與配方修改器的機器格都走這裡：
 * 商店自建的限制模式選取器（只列 {@link MetaMachineItem}，可搜索、按模組分區），
 * 以「替換這一格」打開並預選目前的機器。客戶端專用，common 端的部件只在點擊時才呼叫到它。
 */
public final class MachinePicker {
    private static List<ResourceLocation> machineIds;

    private MachinePicker() {}

    /** 所有已註冊的 GT 機器物品 id。註冊表啟動後不再變動，第一次用到時算一次。 */
    static List<ResourceLocation> machineIds() {
        if (machineIds == null) {
            machineIds = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof MetaMachineItem)
                    .map(BuiltInRegistries.ITEM::getKey).toList();
        }
        return machineIds;
    }

    /**
     * 建好但不打開的選取器。確認後 {@code chosen} 收到選中的機器（數量 1）；
     * 清空後確認收到 {@link ItemStack#EMPTY}，由呼叫方當作清除。取消不會呼叫。
     */
    public static MultiPickerScreen create(Screen parent, ItemStack current, Consumer<ItemStack> chosen) {
        ItemStack[] result = {ItemStack.EMPTY};
        MultiPickerScreen picker = new MultiPickerScreen(parent, machineIds(),
                (stack, count) -> result[0] = stack.copyWithCount(1));
        picker.replacing();
        if (current != null && !current.isEmpty()) picker.preselect(current.copyWithCount(1), 1);
        picker.setAfterConfirm(() -> chosen.accept(result[0]));
        return picker;
    }

    /** 從目前畫面打開選取器，確認後回到原畫面。 */
    public static void open(ItemStack current, Consumer<ItemStack> chosen) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(create(minecraft.screen, current, chosen));
    }
}
