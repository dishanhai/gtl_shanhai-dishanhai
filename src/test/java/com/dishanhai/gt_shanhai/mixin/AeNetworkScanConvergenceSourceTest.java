package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 体检批次 1 守卫：AE 网络扫描失效模型收敛回原版强度。
 * <p>
 * 背景（性能體檢報告 R1/R2 + C2~C5）：recordDelta 事件驱动标脏把 AE2"每 tick 至多一次
 * 全扫"换成 O(读写交错)/tick 无界放大；每槽独立优先级把全网优先级桶撑到上百个；
 * 终端增量广播的单槽变更集在 revision 跳号时静默丢变更。
 */
class AeNetworkScanConvergenceSourceTest {

    private static final Path DELTA_CACHE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "StorageServiceDeltaCacheMixin.java");
    private static final Path MENU_OPT = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "MEStorageMenuBroadcastOptimizationMixin.java");
    private static final Path PRIORITY = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "MEDiskHatchPriority.java");
    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "config", "DShanhaiConfig.java");

    @Test
    void fullRescanIsDebouncedToOncePerTick() throws IOException {
        String source = Files.readString(DELTA_CACHE);
        assertTrue(source.contains("gtShanhai$lastFullSyncTick == gtShanhai$tickCounter"),
                "同 tick 内至多一次全量重扫：事件驱动标脏配合部件循环内读写交错，"
                        + "会把全扫放大到 O(部件数×槽位数)/tick，必须去抖回原版的 ≤1/tick");
        assertTrue(source.contains("gtShanhai$tickCounter++"),
                "去抖基准 tick 计数必须在 onServerEndTick 无条件自增（含 interestManager 非空分支）");
    }

    @Test
    void periodicForceRescanSafetyNetExists() throws IOException {
        String source = Files.readString(DELTA_CACHE);
        String config = Files.readString(CONFIG);
        assertTrue(source.contains("aeStorageForceRescanTicks"),
                "必须有每 N tick 强制全扫安全网：存储总线背后的外部容器变化不经 insert/extract "
                        + "记录路径，没有安全网时陈旧窗口无界（合成模拟会用脏数据决策）");
        assertTrue(config.contains("forceRescanTicks"),
                "安全网间隔必须可配置");
    }

    @Test
    void recordDeltaRespectsConfigToggle() throws IOException {
        String source = Files.readString(DELTA_CACHE);
        int start = source.indexOf("public void gtShanhai$recordDelta");
        int end = source.indexOf('}', source.indexOf("cachedStacksNeedUpdate = true", start));
        assertTrue(start >= 0 && source.substring(start, end).contains("aeStorageDeltaCacheEnabled"),
                "recordDelta 必须检查配置开关：关闭优化时原版已每 tick 末标脏，"
                        + "再按写入频率标脏会让\"关闭优化\"比原版更慢，管理员无法回滚");
    }

    @Test
    void menuOptimizationGuardsRevisionSkipAndHostIdentity() throws IOException {
        String source = Files.readString(MENU_OPT);
        assertTrue(source.contains("storage == service.getInventory()"),
                "必须断言 storage 就是网络存储：ME 储存箱等单元件 GUI 的 host 也接电网，"
                        + "用网络级缓存顶替会显示整个网络的内容");
        assertTrue(source.contains("revision - this.gtShanhai$lastInventoryRevision == 1"),
                "lastChangedKeys 是单槽信箱，revision 跳号（一 tick 多次重扫）时中间变更集已被"
                        + "覆写丢弃，只有恰好 +1 才能走增量，否则终端数量静默失同步");
        assertTrue(source.contains("gtShanhai$pendingResyncRevision"),
                "跳号回退原版全量 diff 后必须推进 lastInventoryRevision 重新对齐，"
                        + "否则增量模式永远无法恢复");
    }

    @Test
    void diskHatchSlotsShareOnePriorityBucket() throws IOException {
        String source = Files.readString(PRIORITY);
        assertTrue(source.contains("return basePriority;"),
                "全部槽位必须挂同一优先级桶：按槽递减会把 AE2 NetworkStorage 的 TreeMap 撑到"
                        + " 108~256 桶，insert/extract 外层循环无提前退出，全网每次存取 ×20-100 固定开销");
    }
}
