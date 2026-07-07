package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.widget.LongInputWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.nbt.CompoundTag;

public class DShanhaiOverclockHatchMachine extends TieredPartMachine {

    private static final String KEY_DIVISOR = "OverclockDivisor";
    private static final long MIN_DIVISOR = 2L;

    private long divisor = -1L;

    public DShanhaiOverclockHatchMachine(IMachineBlockEntity holder, int tier) {
        super(holder, tier);
    }

    public long getCurrentDivisor() {
        long max = getMaxDivisor();
        if (divisor < MIN_DIVISOR || divisor > max) {
            divisor = max;
        }
        return divisor;
    }

    public double getCurrentMultiplier() {
        return 1.0D / (double) getCurrentDivisor();
    }

    public static int applyDurationDivisor(int duration, long divisor) {
        if (duration <= 1 || divisor <= 1L) {
            return Math.max(1, duration);
        }
        long result = ((long) duration + divisor - 1L) / divisor;
        return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, result));
    }

    public long getMaxDivisor() {
        return getMaxDivisorForTier(getTier());
    }

    public static long getMaxDivisorForTier(int tier) {
        return Math.max(MIN_DIVISOR, (long) tier - 6L);
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putLong(KEY_DIVISOR, getCurrentDivisor());
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(KEY_DIVISOR)) {
            setCurrentDivisor(tag.getLong(KEY_DIVISOR));
        }
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 100, 24);
        LongInputWidget input = new LongInputWidget(this::getCurrentDivisor, this::setCurrentDivisor);
        input.setMin(MIN_DIVISOR);
        input.setMax(getMaxDivisor());
        group.addWidget(input);
        group.addWidget(new LabelWidget(24, -16, () -> "gt_shanhai.machine.overclock_hatch.divisor"));
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private void setCurrentDivisor(long value) {
        divisor = Math.max(MIN_DIVISOR, Math.min(value, getMaxDivisor()));
    }
}
