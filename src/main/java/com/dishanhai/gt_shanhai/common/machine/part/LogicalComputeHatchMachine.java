package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.capability.IOpticalComputationProvider;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableComputationContainer;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * 逻辑算力仓——独立算力模块，提供无限 CWU（int 上限）。
 * 使用 NotifiableComputationContainer 实现光学算力能力，
 * 被 ResearchStationMachine.onStructureFormed 正确识别。
 */
public class LogicalComputeHatchMachine extends MultiblockPartMachine {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:compute");

    private boolean bypassCWU = true;

    public LogicalComputeHatchMachine(IMachineBlockEntity holder) {
        super(holder);
        attachTraits(new NotifiableComputationContainer(this, IO.IN, false) {
            @Override
            public List<Integer> handleRecipeInner(IO io, GTRecipe recipe, List<Integer> left,
                                                    String slotName, boolean simulate) {
                if (bypassCWU) return null;
                return left;
            }

            @Override
            public int requestCWUt(int cwu, boolean simulate,
                                    Collection<IOpticalComputationProvider> visited) {
                if (bypassCWU) return cwu;
                return 0;
            }

            @Override
            public int getMaxCWUt(Collection<IOpticalComputationProvider> visited) {
                if (bypassCWU) return Integer.MAX_VALUE;
                return 0;
            }
        });
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        LOG.info("[added] 逻辑算力仓已加入控制器! controller={}", controller.getClass().getSimpleName());
    }

    @Override
    public void removedFromController(IMultiController controller) {
        super.removedFromController(controller);
        LOG.info("[removed] 逻辑算力仓已移出控制器! controller={}", controller.getClass().getSimpleName());
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putBoolean("BypassCWU", bypassCWU);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("BypassCWU")) {
            bypassCWU = tag.getBoolean("BypassCWU");
        }
    }

    @Override
    public Widget createUIWidget() {
        var group = new WidgetGroup(0, 0, 176, 80);
        var scrollGroup = new DraggableScrollableWidgetGroup(4, 4, 168, 72);
        scrollGroup.setBackground(GuiTextures.DISPLAY);
        var textPanel = new ComponentPanelWidget(4, 5, this::buildText);
        textPanel.setMaxWidthLimit(156);
        textPanel.clickHandler((cmd, cd) -> {
            if (cd.isRemote) return;
            if ("cwutog".equals(cmd)) {
                bypassCWU = !bypassCWU;
            }
        });
        scrollGroup.addWidget(textPanel);
        group.addWidget(scrollGroup);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private void buildText(List<Component> list) {
        list.add(Component.literal("§3§l逻辑算力仓"));
        list.add(Component.literal(""));
        list.add(Component.literal("§b门与门堆叠，晶振无声跳动。"));
        list.add(Component.literal("§7它不懂因果，只会逐条执行。"));
        list.add(Component.literal(""));
        list.add(Component.literal("§7算力绕过(CWU): " + (bypassCWU ? "§a● 已激活" : "§8○ 已关闭")));
        var toggle = Component.literal("§r  ");
        toggle.append(ComponentPanelWidget.withButton(
                Component.literal(bypassCWU ? "§c[关闭]" : "§a[开启]"), "cwutog"));
        list.add(toggle);
    }
}
