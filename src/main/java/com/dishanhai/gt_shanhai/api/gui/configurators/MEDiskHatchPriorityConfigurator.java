package com.dishanhai.gt_shanhai.api.gui.configurators;

import com.dishanhai.gt_shanhai.common.machine.part.MEDiskHatchPartMachine;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

public class MEDiskHatchPriorityConfigurator implements IFancyUIProvider {

    private final MEDiskHatchPartMachine machine;

    public MEDiskHatchPriorityConfigurator(MEDiskHatchPartMachine machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return Component.literal("优先级");
    }

    @Override
    public IGuiTexture getTabIcon() {
        return new ItemStackTexture(Items.COMPARATOR);
    }

    @Override
    public Widget createMainPage(FancyMachineUIWidget widget) {
        WidgetGroup page = new WidgetGroup(0, 0, 176, 126);
        page.setBackground(GuiTextures.BACKGROUND_INVERSE);
        page.addWidget(new LabelWidget(6, 6, "设备基础优先级"));
        page.addWidget(new IntInputWidget(28, 24, 120, 18,
                machine::getPriority, machine::setPriority)
                .setMin(Integer.MIN_VALUE)
                .setMax(Integer.MAX_VALUE));

        addButton(page, 6, 48, "+1", 1);
        addButton(page, 47, 48, "+10", 10);
        addButton(page, 88, 48, "+100", 100);
        addButton(page, 129, 48, "+1000", 1000);
        addButton(page, 6, 68, "-1", -1);
        addButton(page, 47, 68, "-10", -10);
        addButton(page, 88, 68, "-100", -100);
        addButton(page, 129, 68, "-1000", -1000);

        page.addWidget(new LabelWidget(6, 92, "存入：高优先级设备优先"));
        page.addWidget(new LabelWidget(6, 104, "取出：低优先级设备优先"));
        page.addWidget(new LabelWidget(6, 116, "内部槽位逐格 -1，设备建议相差 1000"));
        return page;
    }

    private void addButton(WidgetGroup page, int x, int y, String text, int delta) {
        page.addWidget(new ButtonWidget(x, y, 39, 16,
                new TextTexture(text, -1), clickData -> machine.adjustPriority(delta)));
    }
}
