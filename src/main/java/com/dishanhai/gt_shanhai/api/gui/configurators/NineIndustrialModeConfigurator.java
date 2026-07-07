package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.dishanhai.gt_shanhai.common.machine.misc.ShanhaiMachineModeMap;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.function.IntSupplier;
import java.util.function.IntConsumer;

public class NineIndustrialModeConfigurator implements IFancyUIProvider {

    private final String[] modeNames;
    private final IntSupplier getter;
    private final IntConsumer setter;
    private final int totalModes;

    public NineIndustrialModeConfigurator(String[] names, IntSupplier get, IntConsumer set, int total) {
        this.modeNames = names;
        this.getter = get;
        this.setter = set;
        this.totalModes = total;
    }

    @Override
    public Component getTitle() { return Component.literal("§b模式切换"); }

    @Override
    public IGuiTexture getTabIcon() { return new ItemStackTexture(Items.BOOK); }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        this.fancyWidget = widget;
        return buildPage();
    }

    private FancyMachineUIWidget fancyWidget;

    private WidgetGroup buildPage() {
        int mode = getter.getAsInt();
        String name = mode >= 0 && mode < modeNames.length ? modeNames[mode] : "?";

        var page = new WidgetGroup(0, 0, 176, 140);

        page.addWidget(new LabelWidget(5, 5,
            Component.literal("§6§l" + name + " §7[" + mode + "/" + (totalModes - 1) + "]").getString()));
        page.addWidget(new LabelWidget(5, 20,
            Component.literal("§7水浒传三十六天罡").getString()));

        // 左右箭头 — 客户端即时更新 + 发包到服务端执行 setCurrentMode
        page.addWidget(new ButtonWidget(5, 36, 80, 14,
            new TextTexture("§7◀ 上一回", -1),
            cd -> {
                int newMode = (getter.getAsInt() - 1 + totalModes) % totalModes;
                setter.accept(newMode); // 客户端即时显示
                rebuildPage();
            }));
        page.addWidget(new ButtonWidget(91, 36, 80, 14,
            new TextTexture("§7下一回 ▶", -1),
            cd -> {
                int newMode = (getter.getAsInt() + 1) % totalModes;
                setter.accept(newMode); // 客户端即时显示
                rebuildPage();
            }));

        // 配方类型列表
        GTRecipeType[] types = ShanhaiMachineModeMap.getRecipeTypes(mode);
        int y = 54;
        for (int i = 0; i < Math.min(types.length, 8); i++) {
            if (types[i] != null && types[i].registryName != null) {
                String typeName = types[i].registryName.getPath();
                String langKey = "gtceu." + typeName;
                String display = Component.translatable(langKey).getString();
                if (display.equals(langKey)) {
                    display = Component.translatable("gtceu.recipe_type." + typeName).getString();
                }
                if (display.equals("gtceu.recipe_type." + typeName)) {
                    display = typeName;
                }
                page.addWidget(new LabelWidget(5, y,
                    Component.literal("§e  ● §7" + display).getString()));
                y += 12;
            }
        }
        return page;
    }

    private void rebuildPage() {
        if (fancyWidget != null) {
            var container = fancyWidget.getPageContainer();
            if (container != null) {
                container.widgets.clear();
                container.addWidget(buildPage());
            }
        }
    }
}
