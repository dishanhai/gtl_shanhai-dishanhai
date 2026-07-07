package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.List;

public class ProgrammableHatchRecipeTypeConfigurator implements IFancyUIProvider {

    private final ProgrammableHatchPartMachine machine;
    private WidgetGroup mainPage;
    private int listOffset;

    public ProgrammableHatchRecipeTypeConfigurator(ProgrammableHatchPartMachine machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return Component.literal("§b配方类型");
    }

    @Override
    public IGuiTexture getTabIcon() {
        return new ItemStackTexture(Items.COMPARATOR);
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        this.mainPage = buildPage();
        return this.mainPage;
    }

    private WidgetGroup buildPage() {
        List<GTRecipeType> types = machine.getSelectableRecipeTypes();
        WidgetGroup page = new WidgetGroup(0, 0, 176, 170);
        page.setBackground(GuiTextures.BACKGROUND_INVERSE);

        page.addWidget(new ImageWidget(5, 5, 166, 14, new TextTexture("§6§l可编程仓配方类型")));
        page.addWidget(new ImageWidget(5, 18, 166, 14, new TextTexture("§7组合/机器模式不强制覆盖")));

        int y = 34;
        addTypeButton(page, y, -1, null, machine.isCombinedMode());

        WidgetGroup recipeList = new WidgetGroup(4, 58, 168, 108);
        recipeList.setBackground(GuiTextures.DISPLAY);
        page.addWidget(recipeList);

        int visibleRows = 4;
        int maxOffset = Math.max(0, types.size() - visibleRows);
        if (listOffset > maxOffset) listOffset = maxOffset;
        if (listOffset < 0) listOffset = 0;

        page.addWidget(new ButtonWidget(150, 58, 18, 16, new TextTexture("§f▲", -1), cd -> {
            if (listOffset > 0) {
                listOffset--;
                rebuildPage();
            }
        }));
        page.addWidget(new ButtonWidget(150, 150, 18, 16, new TextTexture("§f▼", -1), cd -> {
            if (listOffset < maxOffset) {
                listOffset++;
                rebuildPage();
            }
        }));
        if (!types.isEmpty()) {
            int from = listOffset + 1;
            int to = Math.min(types.size(), listOffset + visibleRows);
            page.addWidget(new ImageWidget(52, 148, 92, 14, new TextTexture("§7" + from + "-" + to + "/" + types.size(), -1)));
        }

        y = 4;
        int end = Math.min(types.size(), listOffset + visibleRows);
        for (int i = listOffset; i < end; i++) {
            GTRecipeType type = types.get(i);
            addTypeButton(recipeList, y, i, type, type == machine.getSelectedRecipeType());
            y += 22;
        }
        return page;
    }

    private void addTypeButton(WidgetGroup page, int y, int index, GTRecipeType type, boolean selected) {
        String display = selected ? "§a§l[x] " : "§f[ ] ";
        display += type == null ? "§f组合/机器模式" : recipeTypeDisplayName(type);
        page.addWidget(new ButtonWidget(4, y, 168, 18, IGuiTexture.EMPTY, cd -> {
            machine.selectRecipeTypeByIndex(index);
            rebuildPage();
        }));
        page.addWidget(new ImageWidget(6, y + 2, 164, 14, new TextTexture(display, -1)));
    }

    private void rebuildPage() {
        if (mainPage == null || mainPage.widgets == null) return;
        mainPage.widgets.clear();
        WidgetGroup next = buildPage();
        if (next.widgets == null) return;
        for (Widget child : next.widgets) {
            mainPage.addWidget(child);
        }
    }

    private static String recipeTypeDisplayName(GTRecipeType type) {
        if (type == null || type.registryName == null) return "unknown";
        String fallback = type.registryName.toString();
        for (String key : recipeTypeLanguageKeys(type)) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) return translated;
        }
        return fallback;
    }

    private static String[] recipeTypeLanguageKeys(GTRecipeType type) {
        String namespace = type.registryName.getNamespace();
        String path = type.registryName.getPath();
        return new String[] {
                type.registryName.toLanguageKey(),
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path
        };
    }
}
