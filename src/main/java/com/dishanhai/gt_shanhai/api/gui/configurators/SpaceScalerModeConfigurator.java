package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

import java.util.function.IntSupplier;
import java.util.function.IntConsumer;

public class SpaceScalerModeConfigurator implements IFancyUIProvider {

    private final String[] modeNames;
    private final IntSupplier getter;
    private final IntConsumer setter;
    private final int totalModes;

    public SpaceScalerModeConfigurator(String[] names, IntSupplier get, IntConsumer set, int total) {
        this.modeNames = names;
        this.getter = get;
        this.setter = set;
        this.totalModes = total;
    }

    @Override
    public Component getTitle() { return Component.literal("§b缩放模式"); }

    @Override
    public IGuiTexture getTabIcon() { return new ItemStackTexture(Items.COMPARATOR); }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        this.fancyWidget = widget;
        return buildPage();
    }

    private FancyMachineUIWidget fancyWidget;

    private WidgetGroup buildPage() {
        int mode = getter.getAsInt();
        String name = mode >= 0 && mode < modeNames.length ? modeNames[mode] : "?";

        var page = new WidgetGroup(0, 0, 176, 120);
        page.addWidget(new LabelWidget(5, 5,
            Component.literal("§6§l" + name + " §7[" + mode + "/" + (totalModes - 1) + "]").getString()));
        page.addWidget(new LabelWidget(5, 20,
            Component.literal("§7切换工作模式以处理不同配方类型").getString()));

        page.addWidget(new ButtonWidget(5, 36, 80, 14,
            new TextTexture("§7◀ 上一模式", -1),
            cd -> {
                int newMode = (getter.getAsInt() - 1 + totalModes) % totalModes;
                setter.accept(newMode);
                rebuildPage();
            }));
        page.addWidget(new ButtonWidget(91, 36, 80, 14,
            new TextTexture("§7下一模式 ▶", -1),
            cd -> {
                int newMode = (getter.getAsInt() + 1) % totalModes;
                setter.accept(newMode);
                rebuildPage();
            }));

        int y = 56;
        for (int i = 0; i < totalModes; i++) {
            String prefix = (i == mode) ? "§a▶ " : "§7  ";
            page.addWidget(new LabelWidget(10, y,
                Component.literal(prefix + modeNames[i]).getString()));
            y += 14;
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
