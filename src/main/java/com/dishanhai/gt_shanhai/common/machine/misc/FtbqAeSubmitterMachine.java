package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.ftbq.FtbqSubmitterToastSuppressor;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.dishanhai.gt_shanhai.common.shop.ShopAeNetwork;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.loot.WeightedReward;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.LootReward;
import dev.ftb.mods.ftbquests.quest.reward.RandomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardClaimType;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class FtbqAeSubmitterMachine extends MetaMachine
        implements IInteractedMachine, IUIMachine, DShanhaiAENetworkMachine, ShopAeNetwork.Provider {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            FtbqAeSubmitterMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);
    private static final int SUBMIT_INTERVAL = 20;
    private static final Map<UUID, FtbqAeSubmitterMachine> LOADED_SUBMITTERS = new HashMap<>();

    @Persisted
    @DescSynced
    private String teamIdText = "";
    @Persisted
    @DescSynced
    private String rewardPlayerIdText = "";
    @Persisted
    @DescSynced
    private String submitterIdText = "";
    @Persisted
    private String queuedTaskIdsText = "";
    private final List<Long> queuedTaskIds = new ArrayList<>();
    @Persisted
    @DescSynced
    private boolean suppressCompletionToast;
    @Persisted
    @DescSynced
    private boolean claimOnlyQueuedRewards;
    @Persisted
    private boolean rewardClaimModeInitialized;
    @Persisted
    private final GridNodeHolder nodeHolder;
    @DescSynced
    private boolean isOnline;
    @DescSynced
    private String status = "未配置";
    @DescSynced
    private long lastSubmitted;
    @DescSynced
    private int queueSize;
    @DescSynced
    private long lastClaimedRewards;
    private TickableSubscription tickSubscription;

    public FtbqAeSubmitterMachine(IMachineBlockEntity holder) {
        super(holder);
        this.nodeHolder = new GridNodeHolder(this);
        ensureSubmitterId();
        exposeGridNodeOnAllSides();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ensureSubmitterId();
        ensureRewardClaimMode();
        loadQueuedTasksFromText();
        exposeGridNodeOnAllSides();
        if (!isRemote()) {
            LOADED_SUBMITTERS.put(UUID.fromString(submitterIdText), this);
            ShopAeNetwork.register(this);
            tickSubscription = subscribeServerTick(this::submitTick);
        }
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (tickSubscription != null) {
            tickSubscription.unsubscribe();
            tickSubscription = null;
        }
        if (!isRemote()) {
            UUID id = parseUuid(submitterIdText);
            if (id != null && LOADED_SUBMITTERS.get(id) == this) {
                LOADED_SUBMITTERS.remove(id);
            }
            ShopAeNetwork.unregister(this);
        }
    }

    // ===== ShopAeNetwork.Provider：把本机绑定队伍的在线 AE 网络暴露给山海商店 AE 模式复用 =====

    @Override
    public boolean servesPlayer(ServerPlayer player) {
        if (player == null || ServerQuestFile.INSTANCE == null) return false;
        TeamData data = ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
        UUID boundTeam = parseTeamId();
        return boundTeam != null && boundTeam.equals(data.getTeamId());
    }

    @Override
    public MEStorage storage() {
        var grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    @Override
    public appeng.api.networking.IGrid grid() {
        return getMainNode().getGrid();
    }

    @Override
    public IManagedGridNode getMainNode() {
        return nodeHolder.getMainNode();
    }

    private void exposeGridNodeOnAllSides() {
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override
    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public void setOnline(boolean online) {
        this.isOnline = online;
    }

    @Override
    public String getAeJadeKind() {
        return "FTBQ 自动提交器";
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return true;
    }

    @Override
    public ModularUI createUI(Player player) {
        ModularUI ui = new ModularUI(176, 154, this, player)
                .background(new IGuiTexture[]{GuiTextures.BACKGROUND})
                .widget(new LabelWidget(5, 5, "§6FTBQ AE 自动提交器"))
                .widget(new LabelWidget(5, 20, () -> isOnline ? "§aAE 网络在线" : "§cAE 网络离线"))
                .widget(new LabelWidget(5, 34, () -> "§7状态: §f" + status))
                .widget(new LabelWidget(5, 48, () -> "§7UUID: §b" + submitterIdText))
                .widget(new LabelWidget(5, 62, () -> "§7领取玩家: §b" + shortRewardPlayerId()))
                .widget(new LabelWidget(5, 74, () -> "§7队列: §b" + queueSize + " §7提交: §b" + lastSubmitted + " §7奖励: §b" + lastClaimedRewards))
                .widget(new LabelWidget(5, 102, () -> suppressCompletionToast ? "§7完成提示: §a已屏蔽自动提交" : "§7完成提示: §e正常显示"))
                .widget(new LabelWidget(5, 116, () -> claimOnlyQueuedRewards ? "§7奖励领取: §a仅队列任务" : "§7奖励领取: §e全部任务"));

        ui.widget(new LabelWidget(5, 88, "§e队伍UUID"));
        TextFieldWidget teamField = new TextFieldWidget(58, 86, 112, 14,
                () -> teamIdText,
                value -> {
                    teamIdText = value.trim();
                    markDirty();
                });
        teamField.setCurrentString(teamIdText);
        ui.widget(teamField);

        ui.widget(new ButtonWidget(5, 136, 38, 14, new TextTexture("绑定", -1), clickData -> bindPlayerTeam(player)));
        ui.widget(new ButtonWidget(47, 136, 42, 14, new TextTexture("清队列", -1), clickData -> clearQueue()));
        ui.widget(new ButtonWidget(93, 136, 38, 14, new TextTexture("提示", -1), clickData -> toggleSuppressCompletionToast()));
        ui.widget(new ButtonWidget(135, 136, 36, 14, new TextTexture("奖励", -1), clickData -> toggleClaimOnlyQueuedRewards()));
        return ui;
    }

    private void bindPlayerTeam(Player player) {
        if (isRemote() || player == null || ServerQuestFile.INSTANCE == null) return;
        TeamData data = ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
        teamIdText = data.getTeamId().toString();
        rewardPlayerIdText = player.getUUID().toString();
        status = "已绑定队伍和领取玩家";
        updateQueueSize();
        markDirty();
    }

    private void clearQueue() {
        if (isRemote()) return;
        queuedTaskIds.clear();
        saveQueuedTasksToText();
        updateQueueSize();
        status = "已清空任务队列";
        markDirty();
    }

    private void toggleSuppressCompletionToast() {
        if (isRemote()) return;
        suppressCompletionToast = !suppressCompletionToast;
        status = suppressCompletionToast ? "已屏蔽自动提交完成提示" : "已显示自动提交完成提示";
        markDirty();
    }

    private void toggleClaimOnlyQueuedRewards() {
        if (isRemote()) return;
        claimOnlyQueuedRewards = !claimOnlyQueuedRewards;
        status = claimOnlyQueuedRewards ? "奖励领取改为仅队列任务" : "奖励领取改为全部任务";
        markDirty();
    }

    private void submitTick() {
        if (getLevel() == null || getLevel().getGameTime() % SUBMIT_INTERVAL != 0L) return;
        runQueuedWork();
    }

    private void runQueuedWork() {
        if (isRemote()) return;
        if (!isOnline) {
            status = "AE 网络离线";
            return;
        }
        var grid = getMainNode().getGrid();
        if (grid == null) {
            status = "未连接 AE 网络";
            return;
        }
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) {
            status = "FTBQ 尚未加载";
            return;
        }
        UUID teamId = parseTeamId();
        if (teamId == null) {
            status = "队伍 UUID 无效";
            return;
        }
        UUID rewardPlayerId = parseRewardPlayerId();
        if (rewardPlayerId == null) {
            status = "领取玩家 UUID 无效";
            return;
        }
        TeamData data = file.getOrCreateTeamData(teamId);
        if (queuedTaskIds.isEmpty()) {
            lastSubmitted = 0L;
            lastClaimedRewards = claimRewardsToAe(file, grid.getStorageService().getInventory(), data, rewardPlayerId);
            status = lastClaimedRewards > 0L ? "领取奖励 " + lastClaimedRewards : "等待任务登记";
            updateQueueSize();
            markDirty();
            return;
        }

        long submittedTotal = 0L;
        int removed = pruneAndSubmitQueuedTasks(file, data, grid.getStorageService().getInventory());
        submittedTotal += lastSubmitted;
        long claimed = claimRewardsToAe(file, grid.getStorageService().getInventory(), data, rewardPlayerId);
        lastClaimedRewards = claimed;
        updateQueueSize();
        if (submittedTotal > 0L || claimed > 0L || removed > 0) {
            status = "提交 " + submittedTotal + " / 奖励 " + claimed;
            markDirty();
        } else {
            status = "AE 中缺少队列目标物品";
        }
    }

    private int pruneAndSubmitQueuedTasks(ServerQuestFile file, TeamData data, MEStorage storage) {
        int removed = 0;
        lastSubmitted = 0L;
        Iterator<Long> iterator = queuedTaskIds.iterator();
        while (iterator.hasNext()) {
            long taskId = iterator.next();
            Task task = file.getTask(taskId);
            if (!(task instanceof ItemTask itemTask)) {
                iterator.remove();
                removed++;
                saveQueuedTasksToText();
                continue;
            }
            if (data.isCompleted(itemTask)) {
                continue;
            }
            if (!submitItemTask(storage, data, itemTask)) {
                continue;
            }
            if (data.isCompleted(itemTask)) {
                return removed;
            }
            return removed;
        }
        return removed;
    }

    public String getJadeTeamIdText() {
        UUID id = parseTeamId();
        return id == null ? "未绑定" : id.toString();
    }

    public String getJadeRewardPlayerIdText() {
        UUID id = parseRewardPlayerId();
        return id == null ? "未绑定" : id.toString();
    }

    public String getJadeSubmitterIdText() {
        UUID id = parseUuid(submitterIdText);
        return id == null ? "未生成" : id.toString();
    }

    public String getJadeStatusText() {
        return status == null || status.isBlank() ? "未知" : status;
    }

    public int getJadeQueueSize() {
        return queueSize;
    }

    public long getJadeLastSubmitted() {
        return lastSubmitted;
    }

    public long getJadeLastClaimedRewards() {
        return lastClaimedRewards;
    }

    public boolean isJadeSuppressingCompletionToast() {
        return suppressCompletionToast;
    }

    public boolean isJadeClaimingOnlyQueuedRewards() {
        return claimOnlyQueuedRewards;
    }

    public String getJadeQueueDetail() {
        if (queuedTaskIds.isEmpty()) return "空";
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (Long taskId : queuedTaskIds) {
            if (taskId == null || !isValidTaskId(taskId.longValue())) continue;
            if (shown > 0) builder.append(", ");
            builder.append(formatJadeTask(taskId, file));
            shown++;
            if (shown >= 3) break;
        }
        int hidden = queuedTaskIds.size() - shown;
        if (hidden > 0) builder.append(" ... +").append(hidden);
        return builder.length() == 0 ? "空" : builder.toString();
    }

    private String formatJadeTask(long taskId, ServerQuestFile file) {
        String idText = Long.toUnsignedString(taskId, 16);
        if (file == null) return idText;
        Task task = file.getTask(taskId);
        if (task == null) return idText + "(失效)";
        Quest quest = task.getQuest();
        String title = quest == null ? "未知任务" : quest.getTitle().getString();
        if (title == null || title.isBlank()) title = idText;
        return title;
    }

    private boolean submitItemTask(MEStorage storage, TeamData data, ItemTask itemTask) {
        if (!data.canStartTasks(itemTask.getQuest())) {
            return false;
        }
        if (!itemTask.consumesResources()) {
            return false;
        }

        long submitted = extractFromAe(storage, itemTask, data);
        if (submitted > 0L) {
            if (suppressCompletionToast) {
                FtbqSubmitterToastSuppressor.runSuppressed(() -> data.addProgress(itemTask, submitted));
            } else {
                data.addProgress(itemTask, submitted);
            }
            lastSubmitted += submitted;
            return true;
        }
        return false;
    }

    private UUID parseTeamId() {
        return parseUuid(teamIdText);
    }

    private UUID parseRewardPlayerId() {
        return parseUuid(rewardPlayerIdText);
    }

    private static UUID parseUuid(String text) {
        try {
            return text == null || text.isBlank() ? null : UUID.fromString(text.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void ensureSubmitterId() {
        if (parseUuid(submitterIdText) == null) {
            submitterIdText = UUID.randomUUID().toString();
            markDirty();
        }
    }

    private void ensureRewardClaimMode() {
        if (!rewardClaimModeInitialized) {
            claimOnlyQueuedRewards = true;
            rewardClaimModeInitialized = true;
            markDirty();
        }
    }

    private String shortRewardPlayerId() {
        UUID id = parseRewardPlayerId();
        if (id == null) return "未绑定";
        String text = id.toString();
        return text.substring(0, 8);
    }

    private long extractFromAe(MEStorage storage, ItemTask task, TeamData data) {
        long remaining = task.getMaxProgress() - data.getProgress(task);
        if (remaining <= 0L) return 0L;

        ItemStack targetStack = task.getItemStack();
        long extracted = extractExactTarget(storage, targetStack, remaining);
        if (extracted > 0L) return extracted;
        if (ItemMatchingSystem.INSTANCE.isItemFilter(targetStack)) {
            return extractMatchingTargets(storage, task, remaining);
        }
        return 0L;
    }

    private long extractExactTarget(MEStorage storage, ItemStack targetStack, long remaining) {
        if (targetStack.isEmpty()) return 0L;
        AEItemKey targetKey = AEItemKey.of(targetStack);
        if (targetKey == null) return 0L;
        return storage.extract(targetKey, remaining, Actionable.MODULATE, IActionSource.empty());
    }

    private long extractMatchingTargets(MEStorage storage, ItemTask task, long remaining) {
        long extractedTotal = 0L;
        for (Object2LongMap.Entry<AEKey> entry : storage.getAvailableStacks()) {
            if (!(entry.getKey() instanceof AEItemKey itemKey)) continue;
            if (!task.test(itemKey.toStack())) continue;
            long extracted = storage.extract(itemKey, remaining - extractedTotal, Actionable.MODULATE, IActionSource.empty());
            extractedTotal += extracted;
            if (extractedTotal >= remaining) return extractedTotal;
        }
        return extractedTotal;
    }

    private long claimRewardsToAe(ServerQuestFile file, MEStorage storage, TeamData data, UUID rewardPlayerId) {
        if (file == null || getLevel() == null) return 0L;
        long claimed = 0L;
        Collection<Quest> quests = claimOnlyQueuedRewards ? getQueuedRewardQuests(file) : getAllQuests(file);
        for (Quest quest : quests) {
            if (!data.isCompleted(quest)) continue;
            for (Reward reward : quest.getRewards()) {
                if (data.getClaimType(rewardPlayerId, reward) != RewardClaimType.CAN_CLAIM) continue;
                long inserted = claimOneRewardToAe(storage, data, rewardPlayerId, reward);
                if (inserted > 0L) {
                    claimed += inserted;
                }
            }
        }
        return claimed;
    }

    private Collection<Quest> getAllQuests(ServerQuestFile file) {
        List<Quest> quests = new ArrayList<>();
        file.forAllQuests(quests::add);
        return quests;
    }

    private Collection<Quest> getQueuedRewardQuests(ServerQuestFile file) {
        Set<Quest> quests = new HashSet<>();
        for (Long taskId : queuedTaskIds) {
            if (taskId == null || !isValidTaskId(taskId.longValue())) continue;
            Task task = file.getTask(taskId.longValue());
            if (task != null && task.getQuest() != null) {
                quests.add(task.getQuest());
            }
        }
        return quests;
    }

    private long claimOneRewardToAe(MEStorage storage, TeamData data, UUID rewardPlayerId, Reward reward) {
        if (reward instanceof RandomReward randomReward) {
            return claimRandomRewardToAe(storage, data, rewardPlayerId, randomReward);
        }
        List<ItemStack> rewardStacks = new ArrayList<>();
        ServerPlayer player = getLevel().getServer() == null ? null : getLevel().getServer().getPlayerList().getPlayer(rewardPlayerId);
        if (!reward.automatedClaimPre(getHolder().self(), rewardStacks, getLevel().getRandom(), rewardPlayerId, player)) {
            return 0L;
        }
        if (rewardStacks.isEmpty()) {
            return 0L;
        }
        long total = insertRewardStacks(storage, rewardStacks, true);
        if (total <= 0L) return 0L;
        if (!gtShanhai$claimReward(data, rewardPlayerId, reward)) {
            return 0L;
        }
        insertRewardStacks(storage, rewardStacks, false);
        reward.automatedClaimPost(getHolder().self(), rewardPlayerId, player);
        return total;
    }

    private long claimRandomRewardToAe(MEStorage storage, TeamData data, UUID rewardPlayerId, RandomReward randomReward) {
        if (randomReward instanceof ChoiceReward) return 0L;
        var table = randomReward.getTable();
        if (table == null) return 0L;

        boolean includeEmpty = randomReward instanceof LootReward;
        List<ItemStack> rewardStacks = new ArrayList<>();
        List<Reward> selectedRewards = new ArrayList<>();
        ServerPlayer player = getLevel().getServer() == null ? null : getLevel().getServer().getPlayerList().getPlayer(rewardPlayerId);
        for (WeightedReward weightedReward : table.generateWeightedRandomRewards(getLevel().getRandom(), 1, includeEmpty)) {
            Reward selected = weightedReward.getReward();
            if (selected == null) continue;
            List<ItemStack> selectedStacks = new ArrayList<>();
            if (!selected.automatedClaimPre(getHolder().self(), selectedStacks, getLevel().getRandom(), rewardPlayerId, player)) {
                continue;
            }
            if (selectedStacks.isEmpty()) {
                selected.automatedClaimPost(getHolder().self(), rewardPlayerId, player);
                continue;
            }
            rewardStacks.addAll(selectedStacks);
            selectedRewards.add(selected);
        }
        if (rewardStacks.isEmpty()) return 0L;
        long total = insertRewardStacks(storage, rewardStacks, true);
        if (total <= 0L) return 0L;
        if (!gtShanhai$claimReward(data, rewardPlayerId, randomReward)) return 0L;
        insertRewardStacks(storage, rewardStacks, false);
        for (Reward selected : selectedRewards) {
            selected.automatedClaimPost(getHolder().self(), rewardPlayerId, player);
        }
        return total;
    }

    // FTBQ 跨版本兼容：不同 FTBQ 版本 TeamData.claimReward 的第三参类型（long / Date）
    // 与返回类型（boolean / void）不同，直接硬编码 (UUID,Reward,long):boolean 在 4.22 等版本会
    // 抛 NoSuchMethodError 崩服。改为运行时反射探测并缓存匹配的方法。
    private static volatile Method gtShanhai$claimRewardMethod;
    private static volatile boolean gtShanhai$claimRewardResolved = false;

    private static Method gtShanhai$resolveClaimReward(TeamData data) {
        if (gtShanhai$claimRewardResolved) return gtShanhai$claimRewardMethod;
        synchronized (FtbqAeSubmitterMachine.class) {
            if (gtShanhai$claimRewardResolved) return gtShanhai$claimRewardMethod;
            Method found = null;
            for (Method m : data.getClass().getMethods()) {
                if (!"claimReward".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                // 只认三参、且首参为 UUID、次参为 Reward 的重载（第三参 long 或 Date）
                if (p.length == 3 && p[0] == UUID.class && Reward.class.isAssignableFrom(p[1])) {
                    Class<?> third = p[2];
                    if (third == long.class || third == Long.class || java.util.Date.class.isAssignableFrom(third)) {
                        found = m;
                        break;
                    }
                }
            }
            gtShanhai$claimRewardMethod = found;
            gtShanhai$claimRewardResolved = true;
            return found;
        }
    }

    private boolean gtShanhai$claimReward(TeamData data, UUID player, Reward reward) {
        Method m = gtShanhai$resolveClaimReward(data);
        if (m == null) {
            // 未找到兼容重载：跳过 claim（不发奖但不崩服），并告警一次
            GTDishanhaiMod.LOGGER.warn("[FtbqAeSubmitter] 未找到兼容的 TeamData.claimReward 重载，跳过奖励领取");
            return false;
        }
        try {
            Class<?> third = m.getParameterTypes()[2];
            long now = System.currentTimeMillis();
            Object thirdArg = java.util.Date.class.isAssignableFrom(third) ? new java.util.Date(now) : Long.valueOf(now);
            Object result = m.invoke(data, player, reward, thirdArg);
            // 返回 void 的版本视为已成功领取
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException e) {
            GTDishanhaiMod.LOGGER.warn("[FtbqAeSubmitter] 调用 TeamData.claimReward 失败: {}", e.toString());
            return false;
        }
    }

    private long insertRewardStacks(MEStorage storage, List<ItemStack> rewardStacks, boolean simulate) {
        long total = 0L;
        for (ItemStack stack : rewardStacks) {
            if (stack.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return 0L;
            long amount = stack.getCount();
            long accepted = storage.insert(key, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE, IActionSource.empty());
            if (accepted < amount) return 0L;
            total += amount;
        }
        return total;
    }

    public boolean addQueuedTask(long taskId, UUID teamId) {
        if (isRemote() || !isValidTaskId(taskId) || teamId == null) return false;
        UUID boundTeamId = parseTeamId();
        if (boundTeamId == null || !boundTeamId.equals(teamId)) return false;
        if (queuedTaskIds.contains(taskId)) {
            status = "任务已在队列";
            updateQueueSize();
            markDirty();
            return true;
        }
        queuedTaskIds.add(taskId);
        saveQueuedTasksToText();
        updateQueueSize();
        status = "已加入任务队列";
        markDirty();
        return true;
    }

    private void updateQueueSize() {
        queueSize = queuedTaskIds.size();
    }

    private void loadQueuedTasksFromText() {
        queuedTaskIds.clear();
        if (queuedTaskIdsText == null || queuedTaskIdsText.isBlank()) {
            updateQueueSize();
            return;
        }
        String[] parts = queuedTaskIdsText.split(",");
        for (String part : parts) {
            try {
                long id = Long.parseUnsignedLong(part.trim(), 16);
                if (isValidTaskId(id) && !queuedTaskIds.contains(id)) {
                    queuedTaskIds.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        saveQueuedTasksToText();
        updateQueueSize();
    }

    private void saveQueuedTasksToText() {
        StringBuilder builder = new StringBuilder();
        for (Long id : queuedTaskIds) {
            if (id == null || !isValidTaskId(id.longValue())) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append(Long.toUnsignedString(id, 16));
        }
        queuedTaskIdsText = builder.toString();
    }

    public static boolean queueTask(ServerPlayer player, UUID submitterId, long taskId) {
        if (player == null || submitterId == null || !isValidTaskId(taskId) || ServerQuestFile.INSTANCE == null) return false;
        TeamData data = ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
        FtbqAeSubmitterMachine submitter = LOADED_SUBMITTERS.get(submitterId);
        return submitter != null && submitter.addQueuedTask(taskId, data.getTeamId());
    }

    private static boolean isValidTaskId(long taskId) {
        return taskId != 0L;
    }

    public static List<SubmitterEntry> listSubmitters(ServerPlayer player) {
        List<SubmitterEntry> result = new ArrayList<>();
        if (player == null || ServerQuestFile.INSTANCE == null) return result;
        TeamData data = ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
        UUID teamId = data.getTeamId();
        Collection<FtbqAeSubmitterMachine> machines = new ArrayList<>(LOADED_SUBMITTERS.values());
        for (FtbqAeSubmitterMachine machine : machines) {
            UUID id = parseUuid(machine.submitterIdText);
            UUID boundTeam = machine.parseTeamId();
            if (id != null && teamId.equals(boundTeam)) {
                result.add(new SubmitterEntry(id, machine.getPos().toShortString(), machine.queueSize, machine.isOnline));
            }
        }
        return result;
    }

    public record SubmitterEntry(UUID id, String pos, int queueSize, boolean online) {
    }

    public static MachineDefinition register() {
        MachineDefinition def = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "ftbq_ae_submitter",
                MachineDefinition::createDefinition,
                FtbqAeSubmitterMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/ftbq_ae_submitter_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/ftbq_ae_submitter")))
                .register();

        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§6§lFTBQ AE 自动提交器"));
            tooltips.add(Component.literal("§7可连接 AE 线缆，从网络真实抽取物品后提交到绑定队伍的 FTBQ 物品目标"));
            tooltips.add(Component.literal("§7先绑定队伍，再在 FTBQ 任务详情页点击加入按钮登记目标"));
            tooltips.add(Component.literal("§7任务完成后的物品奖励会优先写回连接的 AE 网络"));
            tooltips.add(Component.literal("§c只处理消耗型物品目标，不伪造任务完成").withStyle(ChatFormatting.RED));
        });
        return def;
    }
}
