package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gtladd.gtladditions.api.machine.IThreadModifierMachine;
import com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart;

import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 太初分歧引擎 —— 通过两个 UI 槽位的物品调控并行与跨线程。
 *
 * 混沌尚未退场，秩序还未成形。
 * 太初之际，第一条世线从虚无中探出头来。
 */
public class DShanhaiDivergenceEngineMachine extends MultiblockPartMachine
        implements IParallelHatch, IThreadModifierPart, IMachineLife {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            DShanhaiDivergenceEngineMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    private final ItemStackTransfer itemStorage;

    @Persisted @DescSynced
    private boolean parallelEnabled = false;

    @Persisted @DescSynced
    private boolean threadEnabled = false;

    @Persisted @DescSynced
    private int parallelQuantity = 64;

    @Persisted @DescSynced
    private int threadQuantity = 1;


    public DShanhaiDivergenceEngineMachine(IMachineBlockEntity holder) {
        super(holder);
        itemStorage = new ItemStackTransfer(2) {
            @Override public int getSlotLimit(int slot) { return 64; }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty(); }
            @Override public void onContentsChanged(int slot) {
                syncFromSlot(slot);
            }
        };
    }

    // ========== 物品识别 ==========

    /** 槽位 0 物品允许的最大并行值（空槽返回 0 = 不允许调） */
    private int getMaxParallelFromSlot() {
        var stack = itemStorage.getStackInSlot(0);
        if (stack.isEmpty()) return 0;
        if (isParallelItem(stack)) return 32 * stack.getCount();
        return 0;
    }

    /** 槽位 1 物品允许的最大线程值（空槽返回 0 = 不允许调） */
    private int getMaxThreadFromSlot() {
        var stack = itemStorage.getStackInSlot(1);
        if (stack.isEmpty()) return 0;
        if (isSeedItem(stack)) return 8 * stack.getCount();
        return 0;
    }

    /** 当前并行上限（从槽位实时计算） */
    private int getCurrentMaxParallel() {
        int m = getMaxParallelFromSlot();
        return m > 0 ? m : 1;
    }

    /** 当前线程上限（从槽位实时计算） */
    private int getCurrentMaxThread() {
        int m = getMaxThreadFromSlot();
        return m > 0 ? m : 1;
    }

    private static boolean isSeedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && "primordial_worldline_seed".equals(key.getPath());
    }

    /** 槽位 0 是否有已识别的并行控制物品 */
    private boolean hasParallelControlItem() {
        return isParallelItem(itemStorage.getStackInSlot(0));
    }

    /** 槽位 1 是否有已识别的线程控制物品 */
    private boolean hasThreadControlItem() {
        return isSeedItem(itemStorage.getStackInSlot(1));
    }

    private static boolean isParallelItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && "primordial_parallel_particle".equals(key.getPath());
    }

    /** 从槽位同步参数——上限实时计算，仅控制 enable 标志 */
    private void syncFromSlot(int slot) {
        if (slot == 0) {
            if (hasParallelControlItem()) {
                parallelEnabled = true;
                if (parallelQuantity > getCurrentMaxParallel()) {
                    parallelQuantity = getCurrentMaxParallel();
                }
            } else {
                parallelEnabled = false;
                parallelQuantity = 64;
            }
            markDirty();
        } else if (slot == 1) {
            if (hasThreadControlItem()) {
                threadEnabled = true;
                if (threadQuantity > getCurrentMaxThread()) {
                    threadQuantity = getCurrentMaxThread();
                }
            } else {
                threadEnabled = false;
                threadQuantity = 1;
            }
            markDirty();
        }
    }

    // ========== 控制器生命周期 ==========

    @Override
    public void onLoad() {
        super.onLoad();
        // NBT 加载后检查物品状态，防止旧 NBT 残留 enable 标志
        syncFromSlot(0);
        syncFromSlot(1);
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        if (controller instanceof IThreadModifierMachine tm) {
            tm.setThreadPartMachine(this);
        }
    }

    @Override
    public void removedFromController(IMultiController controller) {
        super.removedFromController(controller);
        if (controller instanceof IThreadModifierMachine tm && tm.getThreadPartMachine() == this) {
            tm.setThreadPartMachine(null);
        }
    }

    @Override
    public void onMachineRemoved() {
        // 槽位物品弹出到世界由父类处理
    }

    // ========== IParallelHatch ==========

    @Override
    public int getCurrentParallel() {
        if (!parallelEnabled || !hasParallelControlItem()) return 1;
        return Math.max(1, Math.min(parallelQuantity, getCurrentMaxParallel()));
    }

    @Override
    public boolean canShared() { return false; }

    // ========== IThreadModifierPart ==========

    @Override
    public int getThreadCount() {
        if (!threadEnabled || !hasThreadControlItem()) return 0;
        return Math.max(0, Math.min(threadQuantity, getCurrentMaxThread()));
    }

    // ========== UI ==========

    @Override
    public Widget createUIWidget() {
        int w = 180, h = 170;
        var group = new WidgetGroup(0, 0, w, h);

        // 滚动文字区
        var scrollGroup = new DraggableScrollableWidgetGroup(4, 4, w - 8, h - 8);
        scrollGroup.setBackground(GuiTextures.DISPLAY);

        var textPanel = new ComponentPanelWidget(4, 5, this::buildText);
        textPanel.setMaxWidthLimit(w - 20);
        textPanel.clickHandler((cmd, cd) -> {
            if (cd.isRemote) return;
            int steps;
            if (cd.isCtrlClick && cd.isShiftClick) steps = 1000000;
            else if (cd.isCtrlClick) steps = 1000;
            else if (cd.isShiftClick) steps = 100;
            else steps = 1;
            switch (cmd) {
                case "toggle_par" -> { parallelEnabled = !parallelEnabled; markDirty(); }
                case "par_up" -> {
                    int max = getCurrentMaxParallel();
                    parallelQuantity = (int) Math.min(max, (long) parallelQuantity + steps);
                    markDirty();
                }
                case "par_dn" -> {
                    int max = getCurrentMaxParallel();
                    parallelQuantity = Math.max(1, Math.min(max, parallelQuantity - steps));
                    markDirty();
                }
                case "toggle_thr" -> { threadEnabled = !threadEnabled; markDirty(); }
                case "thr_up" -> {
                    int max = getCurrentMaxThread();
                    threadQuantity = (int) Math.min(max, (long) threadQuantity + steps);
                    markDirty();
                }
                case "thr_dn" -> {
                    int max = getCurrentMaxThread();
                    threadQuantity = Math.max(0, Math.min(max, threadQuantity - steps));
                    markDirty();
                }
                case "show_lore" -> showLoreToAll();
            }
        });
        scrollGroup.addWidget(textPanel);
        group.addWidget(scrollGroup);

        // 槽位
        group.addWidget(new SlotWidget(itemStorage, 0, 4, h - 22)
                .setBackground(SlotWidget.ITEM_SLOT_TEXTURE));
        group.addWidget(new SlotWidget(itemStorage, 1, 24, h - 22)
                .setBackground(SlotWidget.ITEM_SLOT_TEXTURE));

        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private void buildText(List<Component> list) {
        list.add(Component.literal("§5§l太初分歧引擎"));
        list.add(Component.literal(""));

        // 并行
        list.add(Component.literal("§6§l并行: " + (parallelEnabled ? "§a开" : "§7关"))
                .append("  ")
                .append(ComponentPanelWidget.withButton(Component.literal("§6[开/关]"), "toggle_par")));

        list.add(Component.literal("§e" + parallelQuantity + " §7(并行量) ")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[+]"), "par_up"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[-]"), "par_dn")));

        if (!itemStorage.getStackInSlot(0).isEmpty()) {
            var stack = itemStorage.getStackInSlot(0);
            if (isParallelItem(stack)) {
                list.add(Component.literal("§a太初并行子: +" + (32 * stack.getCount()) + "并行"));
            } else {
                list.add(Component.literal("§7" + stack.getHoverName().getString()));
            }
        } else {
            list.add(Component.literal("§8槽位1: 放入并行子解锁并行"));
        }
        list.add(Component.literal(""));

        // 线程
        list.add(Component.literal("§b§l线程: " + (threadEnabled ? "§a开" : "§7关"))
                .append("  ")
                .append(ComponentPanelWidget.withButton(Component.literal("§6[开/关]"), "toggle_thr")));

        list.add(Component.literal("§b" + threadQuantity + " §7(线程量) ")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[+]"), "thr_up"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[-]"), "thr_dn")));

        if (!itemStorage.getStackInSlot(1).isEmpty()) {
            var stack = itemStorage.getStackInSlot(1);
            if (isSeedItem(stack)) {
                list.add(Component.literal("§a太初世线之种: +" + (8 * stack.getCount()) + "线程"));
            } else {
                list.add(Component.literal("§7" + stack.getHoverName().getString()));
            }
        } else {
            list.add(Component.literal("§8槽位2: 放入世线种子解锁16线程"));
        }
        list.add(Component.literal(""));
        list.add(Component.literal("§8Ctrl+点=×1000  Shift+点=×100  Ctrl+Shift=×100w"));
        list.add(Component.literal("§d混沌尚未退场，秩序还未成形。")
                .append(Component.literal(" "))
                .append(ComponentPanelWidget.withButton(Component.literal("§7[?]"), "show_lore")));
    }

    // ========== ManagedFieldHolder ==========

    // ========== 诗意文本 ==========

    private void showLoreToAll() {
        if (getLevel() == null) return;
        getLevel().players().forEach(p -> {
            p.sendSystemMessage(Component.literal("§5§l太初分歧引擎"));
            p.sendSystemMessage(Component.literal("§7混沌尚未退场，秩序还未成形。"));
            p.sendSystemMessage(Component.literal("§7太初之际，第一条世线从虚无中探出头来。"));
            p.sendSystemMessage(Component.literal("§7它不知道哪条分支更优，只负责制造分岔。"));
            p.sendSystemMessage(Component.literal("§7天球还未被命名，但分歧已经发生。"));
            p.sendSystemMessage(Component.literal("§7因果尚未学会走路，每一个方向都是第一次。"));
            p.sendSystemMessage(Component.literal("§7没有一条路径被标记为\"正确\"——"));
            p.sendSystemMessage(Component.literal("§d因为正确本身就是分岔的产物。"));
        });
    }

    @Override
    public ManagedFieldHolder getFieldHolder() { return MANAGED_FIELD_HOLDER; }
}
