package com.dishanhai.gt_shanhai.common.machine.primordial.module.cutter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;

import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import java.util.List;
import java.util.Map;

/**
 * 原初世线切割核心 — 世线切割 / 高纬碎片切割
 * <p>
 * 世线并非不可分割的整体——它们是由无数因果节点串联的细丝。
 * 切割核心以原初谐振频率锁定节点之间的脆弱界面，插入纯几何切割面，将一条完整世线分段剥离。
 * 被切下的世线段成为可独立操作的因果片段：可重组、可封存、可移植至另一条世线的断裂处。
 * 切割不是毁灭，是手术。
 */
public class PrimordialWorldlineCuttingCore extends PrimordialOmegaEngineModuleBase {

    private static final Map<Item, Long> ITEM_PARALLEL_MAP = new java.util.LinkedHashMap<>();
    private static final long DEFAULT_PARALLEL = 64L;

    private long currentParallel = DEFAULT_PARALLEL;
    private final NotifiableItemStackHandler moduleSlot;

    public PrimordialWorldlineCuttingCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        moduleSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        moduleSlot.setFilter(stack -> stack == null || stack.isEmpty()
                || ITEM_PARALLEL_MAP.containsKey(stack.getItem()));
        initItemMap();
    }

    private static void initItemMap() {
        if (!ITEM_PARALLEL_MAP.isEmpty()) return;
        String[][] items = {
            {"dishanhai", "wzrm",    "64"},
            {"dishanhai", "wzjc",    "128"},
            {"dishanhai", "wzcz1",   "256"},
            {"dishanhai", "wzxc",    "512"},
            {"dishanhai", "wzsb",    "1024"},
            {"dishanhai", "wzax",    "2048"},
            {"dishanhai", "wzcz2",   "4096"},
            {"dishanhai", "wzqs",    "8192"},
            {"dishanhai", "wzgl",    "16384"},
            {"dishanhai", "wzhy",    "32768"},
            {"dishanhai", "wzsw",    "65536"},
            {"dishanhai", "wzcx",    "131072"},
            {"dishanhai", "wzdf",    "262144"},
            {"dishanhai", "wzyh",    "524288"},
            {"dishanhai", "wzcz3",   "1048576"},
            {"dishanhai", "reality_anchor_module", "2097152"},
            {"dishanhai", "create_mk", "4194304"},
        };
        for (String[] entry : items) {
            Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new ResourceLocation(entry[0], entry[1]));
            if (item != null) {
                ITEM_PARALLEL_MAP.put(item, Long.parseLong(entry[2]));
            }
        }
    }

    @Override
    public PrimordialModuleRecipeLogic createRecipeLogic(Object... args) {
        return new PrimordialWorldlineCuttingCoreLogic(this);
    }

    @Override
    public PrimordialWorldlineCuttingCoreLogic getRecipeLogic() {
        return (PrimordialWorldlineCuttingCoreLogic) recipeLogic;
    }

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[]{ moduleSlot };
    }

    @Override
    public Widget createUIWidget() {
        Widget w = super.createUIWidget();
        if (w instanceof WidgetGroup g) {
            var s = g.getSize();
            var slot = new SlotWidget(moduleSlot.storage, 0, s.width - 30, s.height - 30, true, true);
            configureParallelModuleSlot(slot);
            g.addWidget(slot);
        }
        return w;
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        textList.add(Component.literal("")
                .append(DShanhaiTextUtil.createUltimateRainbow("原初世线切割核心"))
                .append(Component.literal(" 已就绪")));
        textList.add(Component.literal("切割线程: ")
                .append(Component.literal(String.valueOf(currentParallel))
                        .withStyle(ChatFormatting.AQUA)));
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
    }
}


