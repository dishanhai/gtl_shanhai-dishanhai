package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * 山海 AE 网络仓室通用 Jade 信息。
 */
public enum DShanhaiAENetworkInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "ae_network_info");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            DShanhaiAENetworkMachine machine = getMachine(accessor);
            if (machine == null) return;

            var tag = new CompoundTag();
            var node = machine.getMainNode();
            tag.putString("kind", machine.getAeJadeKind());
            tag.putBoolean("online", machine.isOnline());
            tag.putBoolean("nodeOnline", node.isOnline());
            tag.putBoolean("powered", node.isPowered());
            tag.putBoolean("active", node.isActive());
            putIfAvailable(tag, "slots", machine.getAeTotalSlots());
            putIfAvailable(tag, "configured", machine.getAeConfiguredSlots());
            putIfAvailable(tag, "stocked", machine.getAeStockedSlots());
            putIfAvailable(tag, "pendingSlots", machine.getAePendingSlots());
            putIfAvailable(tag, "jobs", machine.getAeActiveJobs());
            putStringIfAvailable(tag, "outputMode", machine.getAeOutputModeName());
            putIfAvailable(tag, "failedKeys", machine.getAeFailedKeyCooldowns());
            putIfAvailable(tag, "networkCooldown", machine.getAeNetworkCooldownTicks());
            putStringIfAvailable(tag, "flushBudget", machine.getAeFlushBudgetText());
            tag.putBoolean("cacheReady", machine.isAeServiceCacheReady());
            if (machine instanceof FtbqAeSubmitterMachine submitter) {
                tag.putBoolean("ftbqSubmitter", true);
                tag.putString("submitterId", submitter.getJadeSubmitterIdText());
                tag.putString("teamId", submitter.getJadeTeamIdText());
                tag.putString("rewardPlayerId", submitter.getJadeRewardPlayerIdText());
                tag.putString("submitterStatus", submitter.getJadeStatusText());
                tag.putInt("queueSize", submitter.getJadeQueueSize());
                tag.putLong("lastSubmitted", submitter.getJadeLastSubmitted());
                tag.putLong("lastClaimedRewards", submitter.getJadeLastClaimedRewards());
                tag.putString("queueDetail", submitter.getJadeQueueDetail());
                tag.putBoolean("suppressToast", submitter.isJadeSuppressingCompletionToast());
                tag.putBoolean("claimOnlyQueued", submitter.isJadeClaimingOnlyQueuedRewards());
            }
            data.put("dShanhaiAeNetwork", tag);
        } catch (Exception ignored) {}
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (getMachine(accessor) == null) return;

        var data = accessor.getServerData().getCompound("dShanhaiAeNetwork");
        if (data == null || data.isEmpty()) return;

        var helper = IElementHelper.get();
        boolean online = data.getBoolean("online") || data.getBoolean("nodeOnline");
        boolean powered = data.getBoolean("powered");
        boolean active = data.getBoolean("active");

        if (online && powered) {
            tooltip.add(helper.text(Component.literal("§a设备在线")));
        } else if (online) {
            tooltip.add(helper.text(Component.literal("§e设备在线 §7(缺少 AE 能量)")));
        } else {
            tooltip.add(helper.text(Component.literal("§c设备离线")));
        }

        tooltip.add(helper.text(Component.literal("§7仓室类型: §e" + data.getString("kind"))));
        tooltip.add(helper.text(Component.literal(active ? "§aAE 节点: 活跃" : "§8AE 节点: 未激活")));

        if (data.contains("slots")) {
            var line = new StringBuilder("§b槽位: ");
            if (data.contains("configured")) {
                line.append("§f").append(data.getInt("configured")).append("/").append(data.getInt("slots"))
                        .append(" §7已配置");
            } else {
                line.append("§f").append(data.getInt("slots")).append(" §7总槽位");
            }
            if (data.contains("stocked")) {
                line.append(", §a").append(data.getInt("stocked")).append(" §7有库存");
            }
            tooltip.add(helper.text(Component.literal(line.toString())));
        }

        if (data.contains("pendingSlots") || data.contains("jobs")) {
            int pending = data.getInt("pendingSlots");
            int jobs = data.getInt("jobs");
            if (pending > 0 || jobs > 0) {
                tooltip.add(helper.text(Component.literal("§d请求: §f" + pending + " §7槽待补货, §f" + jobs + " §7个合成任务")));
            } else {
                tooltip.add(helper.text(Component.literal("§7请求: §8暂无缺口")));
            }
        }

        if (data.contains("outputMode") || data.contains("failedKeys") || data.contains("networkCooldown") || data.contains("flushBudget")) {
            if (data.contains("outputMode")) {
                tooltip.add(helper.text(Component.literal("§d输出模式: §f" + data.getString("outputMode"))));
            }
            if (data.contains("flushBudget")) {
                tooltip.add(helper.text(Component.literal("§7刷写预算: §b" + data.getString("flushBudget"))));
            }
            int failedKeys = data.getInt("failedKeys");
            int cooldown = data.getInt("networkCooldown");
            if (failedKeys > 0 || cooldown > 0) {
                tooltip.add(helper.text(Component.literal("§e输出冷却: §f" + failedKeys + " §7key, §f" + cooldown + " §7tick")));
            } else if (data.contains("failedKeys") || data.contains("networkCooldown")) {
                tooltip.add(helper.text(Component.literal("§7输出冷却: §a无")));
            }
            tooltip.add(helper.text(Component.literal(data.getBoolean("cacheReady")
                    ? "§7AE 服务缓存: §a已就绪"
                    : "§7AE 服务缓存: §8未缓存")));
        }

        if (data.getBoolean("ftbqSubmitter")) {
            tooltip.add(helper.text(Component.literal("§6FTBQ: §f" + data.getString("submitterStatus"))));
            tooltip.add(helper.text(Component.literal("§7提交器: §b" + shortenUuid(data.getString("submitterId")))));
            tooltip.add(helper.text(Component.literal("§7队伍: §b" + shortenUuid(data.getString("teamId"))
                    + " §7领取: §b" + shortenUuid(data.getString("rewardPlayerId")))));
            tooltip.add(helper.text(Component.literal("§7队列: §b" + data.getInt("queueSize")
                    + " §7提交: §b" + data.getLong("lastSubmitted")
                    + " §7奖励: §b" + data.getLong("lastClaimedRewards"))));
            tooltip.add(helper.text(Component.literal(data.getBoolean("suppressToast")
                    ? "§7完成提示: §a自动提交时屏蔽"
                    : "§7完成提示: §e正常显示")));
            tooltip.add(helper.text(Component.literal(data.getBoolean("claimOnlyQueued")
                    ? "§7奖励领取: §a仅队列任务"
                    : "§7奖励领取: §e全部任务")));
            tooltip.add(helper.text(Component.literal("§7目标: §f" + data.getString("queueDetail"))));
        }
    }

    private static DShanhaiAENetworkMachine getMachine(BlockAccessor accessor) {
        if (accessor == null || accessor.getBlockEntity() == null) return null;
        if (!(accessor.getBlockEntity() instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mbe)) return null;
        if (!(mbe.getMetaMachine() instanceof DShanhaiAENetworkMachine machine)) return null;
        return machine;
    }

    private static void putIfAvailable(CompoundTag tag, String key, int value) {
        if (value >= 0) tag.putInt(key, value);
    }

    private static void putStringIfAvailable(CompoundTag tag, String key, String value) {
        if (value != null && !value.isEmpty()) tag.putString(key, value);
    }

    private static String shortenUuid(String text) {
        if (text == null || text.isBlank() || "未绑定".equals(text) || "未生成".equals(text)) return text;
        return text.length() <= 8 ? text : text.substring(0, 8);
    }
}
