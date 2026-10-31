package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
/** 山海：AR 讀數依主機 identity 作廢；不持有繪圖佇列或 Minecraft 物件型別。 */
public final class HostFxReadingCache {
    private Object host;
    final HostFxGlassArSort.Tracker moduleSort = new HostFxGlassArSort.Tracker();
    HostFxGlassArModel.State state;
    long lastBuildTick;
    int[] lastHostOutIds;
    int[] lastHostOutAmts;
    String[] lastHostOutTexts;
    int[] lastModOutIds;
    int[] lastModOutAmts;
    String[][] lastModOutTexts;
    long lastNameMask;
    int[] lastModMachineItems;
    boolean namesResolved;

    public HostFxReadingCache() {
        clear();
    }

    /** 同一台主機保留當 tick 快取；另一實例即使座標相等也必須重讀。 */
    public boolean selectHost(Object selected) {
        if (host == selected) return false;
        clear();
        host = selected;
        return true;
    }

    public void clear() {
        host = null;
        moduleSort.clear();
        state = new HostFxGlassArModel.State();
        lastBuildTick = Long.MIN_VALUE;
        lastHostOutIds = null;
        lastHostOutAmts = null;
        lastHostOutTexts = new String[0];
        lastModOutIds = null;
        lastModOutAmts = null;
        lastModOutTexts = null;
        lastNameMask = Long.MIN_VALUE;
        lastModMachineItems = null;
        namesResolved = false;
    }
}
