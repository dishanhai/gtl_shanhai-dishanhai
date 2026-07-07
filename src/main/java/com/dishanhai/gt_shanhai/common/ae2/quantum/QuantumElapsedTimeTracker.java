package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

public class QuantumElapsedTimeTracker {

    private long lastTime;
    private long elapsedTime;
    private final Reference2LongMap<AEKeyType> startedWorkByType;
    private final Reference2LongMap<AEKeyType> completedWorkByType;

    public QuantumElapsedTimeTracker() {
        this.lastTime = System.nanoTime();
        this.startedWorkByType = new Reference2LongOpenHashMap<>(Iterables.size(AEKeyTypes.getAll()));
        this.completedWorkByType = new Reference2LongOpenHashMap<>(Iterables.size(AEKeyTypes.getAll()));
    }

    public QuantumElapsedTimeTracker(CompoundTag data) {
        this();
        this.elapsedTime = data.getLong("elapsedTime");
        readLongByTypeMap(data.getCompound("startedWork"), startedWorkByType);
        readLongByTypeMap(data.getCompound("completedWork"), completedWorkByType);
    }

    public CompoundTag writeToNBT() {
        CompoundTag data = new CompoundTag();
        data.putLong("elapsedTime", elapsedTime);
        data.put("startedWork", writeLongByTypeMap(startedWorkByType));
        data.put("completedWork", writeLongByTypeMap(completedWorkByType));
        return data;
    }

    void decrementItems(long amount, AEKeyType keyType) {
        updateTime();
        completedWorkByType.merge(keyType, amount, this::saturatedSum);
    }

    void addMaxItems(long amount, AEKeyType keyType) {
        updateTime();
        startedWorkByType.merge(keyType, amount, this::saturatedSum);
    }

    public long getElapsedTime() {
        boolean allDone = true;
        for (AEKeyType keyType : AEKeyTypes.getAll()) {
            if (completedWorkByType.getLong(keyType) < startedWorkByType.getLong(keyType)) {
                allDone = false;
                break;
            }
        }
        if (!allDone) {
            return elapsedTime + (System.nanoTime() - lastTime);
        }
        return elapsedTime;
    }

    public float getProgress() {
        double started = 0.0D;
        double completed = 0.0D;
        for (AEKeyType keyType : AEKeyTypes.getAll()) {
            started += startedWorkByType.getLong(keyType) / (double) keyType.getAmountPerUnit();
            completed += completedWorkByType.getLong(keyType) / (double) keyType.getAmountPerUnit();
        }
        if (started <= 0.0D) return 0.0F;
        return Mth.clamp((float) (completed / started), 0.0F, 1.0F);
    }

    public long getRemainingItemCount() {
        return Math.max(0L, getStartItemCount() - getCompletedItemCount());
    }

    public long getStartItemCount() {
        long total = 0L;
        for (AEKeyType keyType : AEKeyTypes.getAll()) {
            total = saturatedSum(total, startedWorkByType.getLong(keyType) / keyType.getAmountPerUnit());
        }
        return total;
    }

    public long getCompletedItemCount() {
        long total = 0L;
        for (AEKeyType keyType : AEKeyTypes.getAll()) {
            total = saturatedSum(total, completedWorkByType.getLong(keyType) / keyType.getAmountPerUnit());
        }
        return total;
    }

    private void updateTime() {
        long currentTime = System.nanoTime();
        elapsedTime += currentTime - lastTime;
        lastTime = currentTime;
    }

    private long saturatedSum(long a, long b) {
        long result = a + b;
        if (result < 0) return Long.MAX_VALUE;
        return result;
    }

    private static void readLongByTypeMap(CompoundTag tag, Reference2LongMap<AEKeyType> output) {
        for (AEKeyType keyType : AEKeyTypes.getAll()) {
            output.put(keyType, tag.getLong(keyType.getId().toString()));
        }
    }

    private static CompoundTag writeLongByTypeMap(Reference2LongMap<AEKeyType> input) {
        CompoundTag data = new CompoundTag();
        for (Reference2LongMap.Entry<AEKeyType> entry : input.reference2LongEntrySet()) {
            data.putLong(entry.getKey().getId().toString(), entry.getLongValue());
        }
        return data;
    }
}
