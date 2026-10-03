package com.dishanhai.gt_shanhai.integration.jei;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.ModuleLevelCondition;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.utils.CycleItemStackHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Adds a display-only catalyst slot so JEI can index module-gated recipes as uses. */
public final class ModuleCatalystSlotUI {

    private static final int SLOT_SIZE = 18;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ModuleCatalystSlotUI() {}

    public static void install(GTRecipeType type) {
        if (type == null) {
            return;
        }
        try {
            type.getRecipeUI().setUiBuilder(ModuleCatalystSlotUI::append);
        } catch (Throwable t) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-JEI] 配方類型 {} 的物質模組展示回呼掛載失敗",
                    type.registryName, t);
        }
    }

    private static void append(GTRecipe recipe, WidgetGroup group) {
        try {
            if (recipe == null || group == null) {
                return;
            }
            ItemStack module = requiredModule(recipe);
            if (module == null || module.isEmpty()) {
                return;
            }

            SlotWidget slot = new SlotWidget();
            slot.initTemplate();
            slot.setHandlerSlot(new CycleItemStackHandler(
                    Collections.singletonList(Collections.singletonList(module))), 0);
            slot.setIngredientIO(IngredientIO.CATALYST);
            slot.setCanTakeItems(false);
            slot.setCanPutItems(false);
            slot.setBackgroundTexture(GuiTextures.SLOT);
            Component tooltip = Component.literal("§b催化剂：")
                    .append(Component.literal(DShanhaiTextUtil.stripFcsString(module.getHoverName().getString())))
                    .append(Component.literal(" §7（不占输入槽、不消耗）"));
            slot.setOnAddedTooltips((widget, lines) -> {
                if (lines != null) {
                    lines.add(tooltip);
                }
            });

            placeSlot(recipe, group, slot);
            group.addWidget(slot);
        } catch (Throwable t) {
            String key = t.getClass().getName();
            if (WARNED.add(key)) {
                GTDishanhaiMod.LOGGER.warn("[SHANHAI-JEI] 配方 {} 的物質模組展示槽建立失敗",
                        recipe == null ? "null" : recipe.id, t);
            }
        }
    }

    static ItemStack requiredModule(GTRecipe recipe) {
        if (recipe.id != null) {
            List<ModuleLevelCondition> requirements = ModuleLevelCondition.getRequirements(recipe.id.toString());
            ItemStack module = fromRequirements(requirements);
            if (module != null) {
                return module;
            }
        }
        if (recipe.conditions != null) {
            for (RecipeCondition condition : recipe.conditions) {
                if (condition instanceof ModuleLevelCondition gate) {
                    ItemStack module = moduleStack(gate.moduleId);
                    if (module != null) {
                        return module;
                    }
                }
            }
        }
        return null;
    }

    private static ItemStack fromRequirements(List<ModuleLevelCondition> requirements) {
        if (requirements != null) {
            for (ModuleLevelCondition gate : requirements) {
                ItemStack module = moduleStack(gate.moduleId);
                if (module != null) {
                    return module;
                }
            }
        }
        return null;
    }

    private static ItemStack moduleStack(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == Items.AIR ? null : new ItemStack(item);
    }

    private static void placeSlot(GTRecipe recipe, WidgetGroup group, SlotWidget slot) {
        int width = group.getSize().width;
        int height = group.getSize().height;
        int xOffset = 0;
        GTRecipeTypeUI ui = recipe.getType().getRecipeUI();
        if (ui != null) {
            xOffset = (ui.getJEISize().width - ui.getOriginalWidth()) / 2;
        }
        int x = Math.max(1, width - xOffset - 38);
        int y = height - 30;
        if (group.widgets != null && !group.widgets.isEmpty()) {
            Widget template = group.widgets.get(0);
            if (template != null) {
                y = Math.max(y, template.getPositionY() + template.getSizeHeight() + 1);
            }
        }
        if (y + SLOT_SIZE + 1 > height) {
            group.setSize(width, y + SLOT_SIZE + 1);
        }
        slot.setSelfPosition(x, y);
    }
}
