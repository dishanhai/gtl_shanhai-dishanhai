package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.api.machine.part.IRecipeThreadSource;
import com.dishanhai.gt_shanhai.common.machine.part.ui.RecipeThreadPanelWidget;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadScheduler;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadSources;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.custom.PlayerInventoryWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/** 山海：只提供批次容量，执行绪物品不进入配方输入，也不增加整机并行额度。 */
public class RecipeThreadHatchPartMachine extends MultiblockPartMachine
        implements IRecipeThreadSource, IMachineLife {
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            RecipeThreadHatchPartMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);

    @Persisted private boolean recipeThreadsEnabled = true;
    @Persisted private final NotifiableItemStackHandler threadSlot;

    public RecipeThreadHatchPartMachine(IMachineBlockEntity holder) {
        super(holder);
        threadSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                if (getLevel() != null && !isRemote()) {
                    RecipeThreadHatchPartMachine.this.markDirty();
                    RecipeThreadSources.changed(RecipeThreadHatchPartMachine.this);
                }
            }
        };
        threadSlot.setFilter(stack -> PrimordialOmegaEngineModuleBase.getRecipeThreadBoostForStack(stack) > 0);
    }

    public NotifiableItemStackHandler getThreadSlot() { return threadSlot; }
    public boolean isRecipeThreadsEnabled() { return recipeThreadsEnabled; }

    public void setRecipeThreadsEnabled(boolean enabled) {
        if (recipeThreadsEnabled == enabled) return;
        recipeThreadsEnabled = enabled;
        markDirty();
        RecipeThreadSources.changed(this);
    }

    public static long capacityForBoost(boolean enabled, long boost) {
        return enabled ? PrimordialThreadScheduler.threadCount(boost) : 1L;
    }

    @Override
    public long getRecipeThreadCapacity() {
        return capacityForBoost(recipeThreadsEnabled,
                PrimordialOmegaEngineModuleBase.getRecipeThreadBoostForStack(threadSlot.getStackInSlot(0)));
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        RecipeThreadSources.changed(this);
    }

    @Override
    public void removedFromController(IMultiController controller) {
        // Wake every owner before its membership disappears; paid batches remain on each controller.
        RecipeThreadSources.changed(this);
        super.removedFromController(controller);
    }

    @Override
    public void onMachineRemoved() {
        if (!isRemote()) {
            clearInventory(threadSlot.storage);
            RecipeThreadSources.changed(this);
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putBoolean("RecipeThreadsEnabled", recipeThreadsEnabled);
        // Slot contents drop as physical items, never also travel inside a dropped hatch.
        if (!forDrop) tag.put("RecipeThreadItem", threadSlot.storage.serializeNBT());
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("RecipeThreadsEnabled")) recipeThreadsEnabled = tag.getBoolean("RecipeThreadsEnabled");
        if (tag.contains("RecipeThreadItem")) threadSlot.storage.deserializeNBT(tag.getCompound("RecipeThreadItem"));
    }

    @Override
    public Widget createUIWidget() {
        return new RecipeThreadPanelWidget(0, 0, 244, 132, this, threadSlot.storage,
                this::isRecipeThreadsEnabled, this::setRecipeThreadsEnabled, this::getRecipeThreadCapacity, false);
    }

    @Override
    public ModularUI createUI(Player player) {
        var root = RecipeThreadPanelWidget.glassRoot(244, 226);
        root.addWidget(createUIWidget());
        var inventory = new PlayerInventoryWidget();
        inventory.setPlayer(player);
        inventory.setSelfPosition(new Position(34, 138));
        inventory.setBackground((com.lowdragmc.lowdraglib.gui.texture.IGuiTexture) null);
        root.addWidget(inventory);
        return new ModularUI(244, 226, this, player).widget(root);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() { return MANAGED_FIELD_HOLDER; }
}
