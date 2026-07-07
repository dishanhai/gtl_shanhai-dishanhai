package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

/** 持久化并校验永恒格雷工坊升级状态。 */
public final class EternalGregTechWorkshopUpgradeStorage {

    private static final String KEY_UPGRADES = "upgrades";
    private static final String KEY_ACTIVE_PREFIX = "upgrade";

    private final EnumMap<EternalGregTechWorkshopUpgrade, UpgradeData> unlockedUpgrades =
            new EnumMap<>(EternalGregTechWorkshopUpgrade.class);

    public EternalGregTechWorkshopUpgradeStorage() {
        for (EternalGregTechWorkshopUpgrade upgrade : EternalGregTechWorkshopUpgrade.VALUES) {
            unlockedUpgrades.put(upgrade, new UpgradeData());
        }
    }

    public boolean isUpgradeActive(EternalGregTechWorkshopUpgrade upgrade) {
        return getData(upgrade).active;
    }

    public boolean checkPrerequisites(EternalGregTechWorkshopUpgrade upgrade) {
        EternalGregTechWorkshopUpgrade[] prereqs = upgrade.prerequisites();
        if (prereqs.length == 0) {
            return true;
        }
        Stream<UpgradeData> prereqStream = Arrays.stream(prereqs).map(this::getData);
        if (upgrade.requiresAllPrerequisites()) {
            return prereqStream.allMatch(UpgradeData::isActive);
        }
        return prereqStream.anyMatch(UpgradeData::isActive);
    }

    public boolean checkSplit(EternalGregTechWorkshopUpgrade upgrade, int maxSplitUpgrades) {
        if (!EternalGregTechWorkshopUpgrade.SPLIT_UPGRADES.contains(upgrade)) {
            return true;
        }
        long activeSplitUpgrades = EternalGregTechWorkshopUpgrade.SPLIT_UPGRADES.stream()
                .map(this::getData)
                .filter(UpgradeData::isActive)
                .count();
        return activeSplitUpgrades < Math.max(1, maxSplitUpgrades);
    }

    public boolean checkCost(EternalGregTechWorkshopUpgrade upgrade, int availableShards) {
        return upgrade.shardCost() <= Math.max(0, availableShards);
    }

    public boolean checkDependents(EternalGregTechWorkshopUpgrade upgrade) {
        for (EternalGregTechWorkshopUpgrade dependent : upgrade.dependents()) {
            if (!isUpgradeActive(dependent)) {
                continue;
            }
            if (dependent.requiresAllPrerequisites()) {
                return false;
            }
            long activePrereqs = Arrays.stream(dependent.prerequisites())
                    .map(this::getData)
                    .filter(UpgradeData::isActive)
                    .count();
            if (activePrereqs <= 1L) {
                return false;
            }
        }
        return true;
    }

    public void unlockUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        getData(upgrade).active = true;
    }

    public void respecUpgrade(EternalGregTechWorkshopUpgrade upgrade) {
        getData(upgrade).active = false;
    }

    public void resetAll() {
        for (UpgradeData data : unlockedUpgrades.values()) {
            data.active = false;
        }
    }

    public int getTotalActiveUpgrades() {
        int count = 0;
        for (UpgradeData data : unlockedUpgrades.values()) {
            if (data.active) {
                count++;
            }
        }
        return count;
    }

    public Collection<EternalGregTechWorkshopUpgrade> getAllUpgrades() {
        return unlockedUpgrades.keySet();
    }

    public void save(CompoundTag root) {
        CompoundTag upgrades = new CompoundTag();
        for (Map.Entry<EternalGregTechWorkshopUpgrade, UpgradeData> entry : unlockedUpgrades.entrySet()) {
            upgrades.putBoolean(KEY_ACTIVE_PREFIX + entry.getKey().ordinal(), entry.getValue().active);
        }
        root.put(KEY_UPGRADES, upgrades);
    }

    public void load(CompoundTag root) {
        if (!root.contains(KEY_UPGRADES)) {
            return;
        }
        CompoundTag upgrades = root.getCompound(KEY_UPGRADES);
        for (EternalGregTechWorkshopUpgrade upgrade : EternalGregTechWorkshopUpgrade.VALUES) {
            getData(upgrade).active = upgrades.getBoolean(KEY_ACTIVE_PREFIX + upgrade.ordinal());
        }
    }

    private UpgradeData getData(EternalGregTechWorkshopUpgrade upgrade) {
        return unlockedUpgrades.computeIfAbsent(upgrade, ignored -> new UpgradeData());
    }

    private static final class UpgradeData {
        private boolean active;

        private boolean isActive() {
            return active;
        }
    }
}
