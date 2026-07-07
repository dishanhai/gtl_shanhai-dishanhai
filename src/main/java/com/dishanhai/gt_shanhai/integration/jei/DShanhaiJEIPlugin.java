package com.dishanhai.gt_shanhai.integration.jei;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.pack.DShanhaiPackRegistry;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 山海 JEI 插件 — 自动注册包物品到 JEI 显示。
 * 从 DShanhaiPackRegistry 读取所有已注册的包，作为物品显示在 JEI 中。
 */
@JeiPlugin
public class DShanhaiJEIPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(GTDishanhaiMod.MOD_ID, "sda_jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        // 注册 SDA 包的子类型解释器：只根据 UUID 区分不同的包，忽略动态字段
        registration.registerSubtypeInterpreter(
                VanillaTypes.ITEM_STACK,
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                        new ResourceLocation("gt_shanhai", "super_disk_array")),
                (ingredient, context) -> {
                    var tag = ingredient.getTag();
                    if (tag == null || !tag.hasUUID(SuperDiskArrayInventory.TAG_UUID)) {
                        return tag == null ? "" : SuperDiskArrayInventory.generateDeterministicUUID(tag).toString();
                    }
                    // 只用 UUID 作为子类型标识，忽略 total、types、power 等动态字段
                    return tag.getUUID(SuperDiskArrayInventory.TAG_UUID).toString();
                }
        );
    }

    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        // 注册 SDA 包本身到 JEI 物品列表
        var packs = DShanhaiPackRegistry.getAll();
        if (packs != null && !packs.isEmpty()) {
            Map<String, ItemStack> uniqueStacks = new LinkedHashMap<>();
            packs.stream()
                    .map(pack -> {
                        try {
                            // 使用 pack 的 NBT 字符串构建 SDA ItemStack
                            String nbt = pack.nbt();
                            if (nbt == null || nbt.isEmpty()) return ItemStack.EMPTY;

                            // 解析 NBT 并创建 ItemStack
                            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(nbt);
                            ItemStack stack = new ItemStack(
                                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                                            new ResourceLocation("gt_shanhai", "super_disk_array")));
                            stack.setTag(tag);
                            return stack;
                        } catch (Exception e) {
                            return ItemStack.EMPTY;
                        }
                    })
                    .filter(stack -> !stack.isEmpty())
                    .forEach(stack -> uniqueStacks.put(getSdaSubtypeKey(stack), stack));
            List<ItemStack> sdaPackStacks = uniqueStacks.values().stream().collect(Collectors.toList());

            if (!sdaPackStacks.isEmpty()) {
                registration.addExtraIngredients(VanillaTypes.ITEM_STACK, sdaPackStacks);
            }
        }
    }

    private static String getSdaSubtypeKey(ItemStack stack) {
        var tag = stack.getTag();
        if (tag == null) return "";
        if (tag.hasUUID(SuperDiskArrayInventory.TAG_UUID)) return tag.getUUID(SuperDiskArrayInventory.TAG_UUID).toString();
        return SuperDiskArrayInventory.generateDeterministicUUID(tag).toString();
    }
}
