package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Consumer;

public class MachineModeConfigurator implements IFancyUIProvider {

    private final Collection<IMultiController> controllers;
    private GTRecipeType current;
    private final Consumer<GTRecipeType> onChange;

    public MachineModeConfigurator(Collection<IMultiController> controllers,
                                    GTRecipeType current,
                                    Consumer<GTRecipeType> onChange) {
        this.controllers = controllers;
        this.current = current;
        this.onChange = onChange;
    }

    @Override
    public Component getTitle() {
        return Component.literal("§b配方模式");
    }

    @Override
    public IGuiTexture getTabIcon() {
        return new ItemStackTexture(Items.COMPARATOR);
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        var types = extractRecipeTypes(controllers);
        int h = Math.max(20, types.size() * 22 + 10);
        var group = new WidgetGroup(0, 0, 156, h);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);

        if (types.isEmpty()) {
            group.addWidget(new ImageWidget(4, 4, 148, 16,
                    new TextTexture("§7无可用配方类型")));
            return group;
        }

        int y = 5;
        for (var type : types) {
            boolean sel = type == current;
            String name = type.registryName != null ? type.registryName.toString() : null;
            if (name == null || name.isEmpty()) name = "配方#" + types.indexOf(type);
            String display = sel ? "§a▶ " + name : "§7  " + name;

            // 透明背景按钮，仅处理点击
            var btn = new ButtonWidget(4, y, 148, 22, IGuiTexture.EMPTY, cd -> {
                if (!cd.isRemote && onChange != null) {
                    onChange.accept(type);
                    current = type;
                }
            });
            group.addWidget(btn);

            // 文字用 ImageWidget 叠加（避免 TextTexture 被背景吞噬）
            group.addWidget(new ImageWidget(6, y + 3, 144, 16,
                    new TextTexture(display)));
            y += 22;
        }
        return group;
    }

    public static List<GTRecipeType> extractRecipeTypes(Collection<IMultiController> controllers) {
        var set = new LinkedHashSet<GTRecipeType>();
        if (controllers == null) return List.of();
        for (var ctrl : controllers) {
            if (ctrl instanceof MultiblockControllerMachine mc) {
                var def = mc.getDefinition();
                if (def != null) {
                    var rt = def.getRecipeTypes();
                    if (rt != null && rt.length > 0) {
                        Collections.addAll(set, rt);
                    }
                }
            }
        }
        return new ArrayList<>(set);
    }
}
