package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.api.config.Actionable;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.ftbq.FtbqSubmitterToastSuppressor;
import com.dishanhai.gt_shanhai.api.ftbq.FtbqRewardBuffer;
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
import com.gregtechceu.gtceu.api.machine.feature.IDropSaveMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.loot.WeightedReward;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.LootReward;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.RandomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardClaimType;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

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
        implements IInteractedMachine, IUIMachine, IDropSaveMachine, DShanhaiAENetworkMachine, ShopAeNetwork.Provider {

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
    private final FtbqRewardBuffer<AEItemKey> pendingRewards = new FtbqRewardBuffer<>();

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

    @Override
    public boolean savePickClone() {
        // Creative pick must not duplicate claimed rewards or the submitter UUID.
        return false;
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        ListTag pending = new ListTag();
        pendingRewards.snapshot().forEach((key, amount) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("key", key.toTag());
            entry.putLong("amount", amount);
            pending.add(entry);
        });
        // Keep the outbox on the dropped machine as well as in chunk saves.
        tag.put("FtbqPendingRewards", pending);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        pendingRewards.clear();
        ListTag pending = tag.getList("FtbqPendingRewards", Tag.TAG_COMPOUND);
        for (int i = 0; i < pending.size(); i++) {
            CompoundTag entry = pending.getCompound(i);
            AEItemKey key = AEItemKey.fromTag(entry.getCompound("key"));
            long amount = entry.getLong("amount");
            if (key != null && amount > 0L) pendingRewards.add(key, amount);
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
        ui.widget(new LabelWidget(58, 88, () -> teamIdText));

        ui.widget(new ButtonWidget(5, 136, 38, 14, new TextTexture("绑定", -1), clickData -> bindPlayerTeam(player)));
        ui.widget(new ButtonWidget(47, 136, 42, 14, new TextTexture("清队列", -1), clickData -> clearQueue()));
        ui.widget(new ButtonWidget(93, 136, 38, 14, new TextTexture("提示", -1), clickData -> toggleSuppressCompletionToast()));
        ui.widget(new ButtonWidget(135, 136, 36, 14, new TextTexture("奖励", -1), clickData -> toggleClaimOnlyQueuedRewards()));
        return ui;
    }

    private void bindPlayerTeam(Player player) {
        if (isRemote() || player == null || ServerQuestFile.INSTANCE == null) return;
        if (!pendingRewards.isEmpty()) {
            status = "奖励待回网，暂不能重新绑定";
            return;
        }
        TeamData data = ServerQuestFile.INSTANCE.getOrCreateTeamData(player);
        if (!data.getTeamId().equals(parseTeamId())) {
            queuedTaskIds.clear();
            saveQueuedTasksToText();
        }
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
        lastSubmitted = 0L;
        lastClaimedRewards = 0L;
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
        MEStorage storage = grid.getStorageService().getInventory();
        lastClaimedRewards = pendingRewards.flush(
                (key, amount) -> storage.insert(key, amount, Actionable.MODULATE, IActionSource.empty()), this::markDirty);
        if (!pendingRewards.isEmpty()) {
            status = "奖励待回网：AE 空间不足";
            return;
        }
        TeamData data = file.getOrCreateTeamData(teamId);
        if (data.isLocked()) {
            status = "队伍任务已锁定";
            return;
        }
        UUID currentRewardTeam = FTBTeamsAPI.api().getManager().getTeamForPlayerID(rewardPlayerId)
                .map(team -> team.getTeamId()).orElse(null);
        if (!teamId.equals(currentRewardTeam)) {
            status = "领取玩家已离队，请重新绑定";
            return;
        }
        if (queuedTaskIds.isEmpty()) {
            lastSubmitted = 0L;
            lastClaimedRewards += claimRewardsToAe(file, grid.getStorageService().getInventory(), data, rewardPlayerId);
            status = !pendingRewards.isEmpty() ? "奖励待回网：AE 空间不足"
                    : lastClaimedRewards > 0L ? "领取奖励 " + lastClaimedRewards : "等待任务登记";
            updateQueueSize();
            markDirty();
            return;
        }

        int removed = pruneAndSubmitQueuedTasks(file, data, storage);
        lastClaimedRewards += claimRewardsToAe(file, storage, data, rewardPlayerId);
        updateQueueSize();
        if (!pendingRewards.isEmpty()) {
            status = "奖励待回网：AE 空间不足";
        } else if (lastSubmitted > 0L || lastClaimedRewards > 0L || removed > 0) {
            status = "提交 " + lastSubmitted + " / 奖励 " + lastClaimedRewards;
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
        if (data.isLocked() || !data.canStartTasks(itemTask.getQuest()) || itemTask.isOnlyFromCrafting()) {
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
        // Never consume the filter item itself. ItemTask.test also handles weak/ignored NBT.
        boolean filter = ItemMatchingSystem.INSTANCE.isItemFilter(targetStack);
        long extracted = !filter && task.test(targetStack) ? extractExactTarget(storage, targetStack, remaining) : 0L;
        return extracted + (extracted < remaining ? extractMatchingTargets(storage, task, remaining - extracted) : 0L);
    }

    private long extractExactTarget(MEStorage storage, ItemStack targetStack, long remaining) {
        if (targetStack.isEmpty()) return 0L;
        AEItemKey targetKey = AEItemKey.of(targetStack);
        if (targetKey == null) return 0L;
        return storage.extract(targetKey, remaining, Actionable.MODULATE, IActionSource.empty());
    }

    private long extractMatchingTargets(MEStorage storage, ItemTask task, long remaining) {
        long extractedTotal = 0L;
        // Extraction may change the underlying inventory; iterate a key snapshot.
        List<AEItemKey> keys = new ArrayList<>();
        for (Object2LongMap.Entry<AEKey> entry : storage.getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey key) keys.add(key);
        }
        for (AEItemKey itemKey : keys) {
            if (ItemMatchingSystem.INSTANCE.isItemFilter(task.getItemStack()) && itemKey.matches(task.getItemStack())) continue;
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
                if (!pendingRewards.isEmpty()) return claimed;
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
        // Validate the whole random table before rolling: unsupported outcomes must not be rerolled away.
        if (!isItemRewardTree(reward, new HashSet<>())) return 0L;
        List<ItemStack> stacks = new ArrayList<>();
        ServerPlayer player = getLevel().getServer().getPlayerList().getPlayer(rewardPlayerId);
        if (!collectRewardItems(reward, stacks, rewardPlayerId, player)) return 0L;
        FtbqRewardBuffer<AEItemKey> prepared = new FtbqRewardBuffer<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) return 0L;
            prepared.add(key, stack.getCount());
        }
        // 2001.4.22: claimReward(ServerPlayer, ...) delivers a second copy. Only mark the claim.
        if (!data.markRewardAsClaimed(rewardPlayerId, reward, System.currentTimeMillis())) return 0L;
        prepared.snapshot().forEach(pendingRewards::add);
        markDirty();
        return pendingRewards.flush(
                (key, amount) -> storage.insert(key, amount, Actionable.MODULATE, IActionSource.empty()), this::markDirty);
    }

    private boolean isItemRewardTree(Reward reward, Set<Reward> visiting) {
        if (reward == null || reward instanceof ChoiceReward || !visiting.add(reward)) return false;
        try {
            if (reward.getClass() == ItemReward.class) return true;
            if (reward.getClass() != RandomReward.class && reward.getClass() != LootReward.class) return false;
            RandomReward random = (RandomReward) reward;
            if (random.getTable() == null) return false;
            for (WeightedReward entry : random.getTable().getWeightedRewards()) {
                if (!isItemRewardTree(entry.getReward(), visiting)) return false;
            }
            return true;
        } finally {
            visiting.remove(reward);
        }
    }

    private boolean collectRewardItems(Reward reward, List<ItemStack> stacks, UUID playerId, ServerPlayer player) {
        if (reward instanceof RandomReward random) {
            for (WeightedReward selected : random.getTable().generateWeightedRandomRewards(
                    getLevel().getRandom(), 1, random instanceof LootReward)) {
                if (!collectRewardItems(selected.getReward(), stacks, playerId, player)) return false;
            }
            // An empty loot roll is still a consumed reward, never a reason to roll again.
            return true;
        }
        return reward.automatedClaimPre(getHolder().self(), stacks, getLevel().getRandom(), playerId, player);
    }

    public boolean addQueuedTask(long taskId, UUID teamId) {
        if (isRemote() || !isValidTaskId(taskId) || teamId == null) return false;
        UUID boundTeamId = parseTeamId();
        if (boundTeamId == null || !boundTeamId.equals(teamId) || ServerQuestFile.INSTANCE == null) return false;
        Task task = ServerQuestFile.INSTANCE.getTask(taskId);
        if (!(task instanceof ItemTask itemTask) || !itemTask.consumesResources() || itemTask.isOnlyFromCrafting()) return false;
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
