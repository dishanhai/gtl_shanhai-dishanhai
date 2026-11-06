package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternBridge;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/** 山海：原件留在世界庫，成形主庫提供同一 AE 網路的管理與發現入口。 */
public final class StellarCloudLibraryMachine extends MultiblockControllerMachine
        implements IMachineLife, IInteractedMachine, IUIMachine {
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(
            StellarCloudLibraryMachine.class, MultiblockControllerMachine.MANAGED_FIELD_HOLDER);
    private static final Set<StellarCloudLibraryMachine> LOADED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final String PREFIX = "gt_shanhai.cloud.";
    private static final int PAGE_SIZE = StellarCloudLibraryLayout.PAGE_SIZE;
    @Persisted private UUID owner, libraryId;
    @Persisted private final ItemStackTransfer inbox = new ItemStackTransfer(1) {
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate, boolean notify) {
            return uploadOperation != null ? stack : super.insertItem(slot, stack, simulate, notify);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate, boolean notify) {
            return uploadOperation != null && !removing ? ItemStack.EMPTY : super.extractItem(slot, amount, simulate, notify);
        }
    };
    @Persisted private final ItemStackTransfer output = new ItemStackTransfer(1);
    @Persisted private UUID uploadOperation;
    @Persisted private CompoundTag uploadSnapshot = new CompoundTag();
    @DescSynced private boolean online;
    private boolean removing;

    public StellarCloudLibraryMachine(IMachineBlockEntity holder) {
        super(holder);
        inbox.setOnContentsChanged(this::markDirty); output.setOnContentsChanged(this::markDirty);
    }

    public static MultiblockMachineDefinition register() {
        var definition = GTDishanhaiRegistration.REGISTRATE
                .multiblock("cloud_pattern_library", StellarCloudLibraryMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .appearanceBlock(StellarCloudLibraryStructure::appearanceBlock)
                .pattern(StellarCloudLibraryStructure::createPattern)
                .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.machine.StellarCloudLibraryRenderer(
                        new ResourceLocation("gtlcore", "block/casings/iridium_casing"),
                        new ResourceLocation("gt_shanhai", "block/machine/part/recipe_type_pattern_buffer")))
                .hasTESR(true)
                .register();
        definition.setTooltipBuilder((stack, lines) -> {
            lines.add(Component.translatable(PREFIX + "tooltip.library"));
            lines.add(Component.translatable(PREFIX + "tooltip.structure"));
            lines.add(Component.translatable(PREFIX + "tooltip.network"));
            lines.add(Component.translatable(PREFIX + "tooltip.originals"));
        });
        return definition;
    }

    @Override public ManagedFieldHolder getFieldHolder() { return FIELDS; }
    public boolean isOnline() {
        if (getLevel() instanceof ServerLevel) online = aeBindingReady();
        return online && isFormed();
    }
    public @Nullable UUID boundPlayerId() {
        var core = aeCore();
        return core == null ? null : core.boundPlayerId();
    }
    public boolean aeBindingReady() {
        var core = aeCore();
        var library = library();
        return core != null && library != null && Objects.equals(owner, library.owner()) && core.readyFor(this, owner);
    }
    private @Nullable StellarCloudAECoreMachine aeCore() {
        if (!isFormed()) return null;
        StellarCloudAECoreMachine selected = null;
        for (var part : getParts()) {
            if (part instanceof StellarCloudAECoreMachine core) {
                if (selected != null) return null;
                selected = core;
            }
        }
        return selected;
    }
    void refreshNetworkBinding() {
        boolean connected = aeBindingReady();
        if (online != connected) { online = connected; markDirty(); }
    }
    void aeCoreUnloaded() { if (online) { online = false; markDirty(); } }
    @Override public void onStructureFormed() { super.onStructureFormed(); refreshNetworkBinding(); }
    @Override public void onStructureInvalid() { super.onStructureInvalid(); aeCoreUnloaded(); }
    @Override public void onMachinePlaced(LivingEntity placer, ItemStack stack) {
        if (placer instanceof ServerPlayer player) {
            owner = player.getUUID();
            var data = StellarCloudSavedData.get(player.serverLevel());
            if (data.healthy()) libraryId = data.defaultLibrary(owner).id();
            markDirty();
        }
    }
    @Override public void onLoad() {
        // 山海：存檔中的成形狀態不能代替本次載入的結構檢查。
        if (getLevel() instanceof ServerLevel) isFormed = false;
        super.onLoad();
        if (getLevel() instanceof ServerLevel) { LOADED.add(this); reconcileUpload(); }
    }
    @Override public void onUnload() { LOADED.remove(this); super.onUnload(); }
    @Override public void onMachineRemoved() {
        if (!isRemote()) { reconcileUpload(); removing = true; LOADED.remove(this); clearInventory(inbox); clearInventory(output); }
    }
    @Override public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (forDrop) {
            for (String key : List.of("owner", "libraryId", "inbox", "output", "uploadOperation", "uploadSnapshot")) tag.remove(key);
        }
    }

    private @Nullable StellarCloudSavedData.Library library() {
        return getLevel() instanceof ServerLevel level && libraryId != null ? StellarCloudSavedData.get(level).library(libraryId) : null;
    }
    private void changed() { StellarCloudSavedData.get((ServerLevel)getLevel()).setDirty(); markDirty(); }
    private boolean openingAuthorized(ServerPlayer player) {
        var library = library();
        return library != null && library.authorized(player.getUUID()) && player.serverLevel() == getLevel()
                && player.distanceToSqr(getPos().getX() + .5, getPos().getY() + .5, getPos().getZ() + .5) <= 64
                && player.serverLevel().mayInteract(player, getPos());
    }
    private boolean actionAuthorized(Player player) {
        return player instanceof ServerPlayer server && openingAuthorized(server)
                && server.containerMenu instanceof ModularUIContainer menu && menu.getModularUI().holder == this;
    }
    @Override public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return !(player instanceof ServerPlayer server) || openingAuthorized(server);
    }

    boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            return SelectedRecipePattern.isPattern(stack) || WildcardPatternBridge.isWildcardPattern(stack)
                    || PatternDetailsHelper.decodePattern(stack, getLevel()) instanceof appeng.crafting.pattern.AEProcessingPattern;
        } catch (RuntimeException malformed) { return false; }
    }
    private Set<String> patternTypes(ItemStack stack) {
        Set<String> result = new LinkedHashSet<>();
        String type = PatternRecipeTypeHelper.canonicalRecipeTypeId(PatternRecipeTypeHelper.readRecipeTypeId(stack));
        if (!type.isEmpty()) result.add(type);
        if (SelectedRecipePattern.isPattern(stack)) {
            for (var expanded : SelectedRecipePattern.expand(stack, getLevel())) {
                if (expanded.recipe() != null && expanded.recipe().recipeType != null && expanded.recipe().recipeType.registryName != null)
                    result.add(expanded.recipe().recipeType.registryName.toString());
            }
        } else if (WildcardPatternBridge.isWildcardPattern(stack)) {
            for (var expanded : WildcardPatternBridge.expandPatterns(stack, getLevel())) {
                String expandedType = PatternRecipeTypeHelper.readRecipeTypeId(expanded.getDefinition().toStack());
                if (!expandedType.isEmpty()) result.add(PatternRecipeTypeHelper.canonicalRecipeTypeId(expandedType));
            }
        } else if (result.isEmpty()) {
            var recipe = SelectedRecipePattern.matchEncodedPattern(stack, getLevel());
            if (recipe != null && recipe.recipeType != null && recipe.recipeType.registryName != null) result.add(recipe.recipeType.registryName.toString());
        }
        return result;
    }

    /** 分類游標由同一庫共用。昂貴解碼分批，最後一次合併索引，避免每個批次重啟星律掃描。 */
    private void refreshClassification() {
        var library = library(); if (library == null) return;
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        if (library.classificationTarget != revision) {
            library.classificationTarget = revision; library.classificationCursor = 0;
            library.classificationTick = Long.MIN_VALUE;
            library.classificationIds = library.catalog().ids(); library.classificationUpdates.clear();
        }
        long tick = getLevel().getGameTime();
        if (library.classificationTick == tick) return;
        library.classificationTick = tick;
        int limit = Math.min(library.classificationIds.size(), library.classificationCursor + 16);
        while (library.classificationCursor < limit) {
            UUID id = library.classificationIds.get(library.classificationCursor++);
            var row = library.catalog().entry(id); if (row == null) continue;
            ItemStack original = ItemStack.of(row.original());
            try { library.classificationUpdates.put(id, patternTypes(original)); }
            catch (RuntimeException unavailable) { library.classificationUpdates.put(id, Set.of()); }
        }
        if (library.classificationCursor == library.classificationIds.size() && !library.classificationUpdates.isEmpty()) {
            long before = library.catalog().revision();
            library.classificationUpdates.forEach(library.catalog()::reclassify); library.classificationUpdates.clear();
            if (before != library.catalog().revision()) changed();
        }
    }
    private String upload(Player player) {
        if (!actionAuthorized(player)) return PREFIX + "status.denied";
        if (uploadOperation != null) return reconcileUpload() ? PREFIX + "status.uploaded" : PREFIX + "status.recovery";
        ItemStack original = inbox.getStackInSlot(0);
        if (!accepts(original)) return PREFIX + "status.invalid";
        Set<String> types;
        try { types = patternTypes(original); } catch (RuntimeException malformed) { return PREFIX + "status.invalid"; }
        uploadOperation = UUID.randomUUID(); uploadSnapshot = original.copyWithCount(1).save(new CompoundTag()); markDirty();
        var catalog = library().catalog();
        var result = catalog.upload(uploadOperation, uploadSnapshot, new CompoundTag(), types);
        changed();
        if (!result.inserted()) {
            uploadOperation = null; uploadSnapshot = new CompoundTag(); markDirty();
            return PREFIX + "status.duplicate";
        }
        return reconcileUpload() ? PREFIX + "status.uploaded" : PREFIX + "status.recovery";
    }
    /** 未完成來源扣除時，只對同一操作與同一來源原件重試；不拿任意新物品補扣。 */
    private boolean reconcileUpload() {
        if (uploadOperation == null || library() == null) return false;
        var catalog = library().catalog(); UUID entryId = catalog.receipt(uploadOperation);
        ItemStack source = inbox.getStackInSlot(0);
        if (source.isEmpty() || !source.copyWithCount(1).save(new CompoundTag()).equals(uploadSnapshot)) return false;
        if (entryId == null) {
            if (!accepts(source)) return false;
            Set<String> types;
            try { types = patternTypes(source); } catch (RuntimeException malformed) { return false; }
            var retry = catalog.upload(uploadOperation, uploadSnapshot, new CompoundTag(), types); changed();
            if (!retry.inserted()) {
                uploadOperation = null; uploadSnapshot = new CompoundTag(); markDirty(); return false;
            }
            entryId = retry.entry();
        }
        var row = catalog.entry(entryId);
        if (row != null && !row.original().equals(uploadSnapshot)) return false;
        inbox.setStackInSlot(0, source.copyWithCount(source.getCount() - 1));
        uploadOperation = null; uploadSnapshot = new CompoundTag(); markDirty(); return true;
    }

    private String withdraw(Player player, @Nullable UUID id) {
        if (!actionAuthorized(player)) return PREFIX + "status.denied";
        if (id == null || !output.getStackInSlot(0).isEmpty()) return PREFIX + "status.output_full";
        var entry = library().catalog().entry(id);
        if (entry != null && ItemStack.of(entry.original()).isEmpty()) return PREFIX + "status.invalid";
        if (entry != null && entry.leased()) {
            library().catalog().requestWithdrawal(id); changed();
            return PREFIX + "status.withdraw_queued";
        }
        CompoundTag original = library().catalog().withdraw(id);
        if (original == null) return PREFIX + "status.busy";
        output.setStackInSlot(0, ItemStack.of(original)); changed(); return PREFIX + "status.withdrawn";
    }

    @Override public ModularUI createUI(Player player) {
        return StellarCloudLibraryUi.create(this, player, inbox, output, new Session(player));
    }
    private static String limit(String text) { return text.length() <= 80 ? text : text.substring(0, 80); }
    final class Session {
        final Player player; String search = "", type = "", status = PREFIX + "status.ready"; int page; UUID selected, selectedMember;
        long cachedRevision = Long.MIN_VALUE; String cachedSearch = "", cachedType = ""; List<UUID> cachedRows = List.of();
        private final Map<UUID, StellarCloudPatternPreview> previews = new HashMap<>();
        private long previewRecipeRevision = Long.MIN_VALUE, scanTick = Long.MIN_VALUE;
        private final StellarCloudPreviewBudget searchBudget = new StellarCloudPreviewBudget();
        private boolean searchPending;
        Session(Player player) { this.player = player; }
        private boolean canRead() { return player instanceof ServerPlayer server && openingAuthorized(server); }
        boolean inboxAccessible() { return uploadOperation == null && actionAuthorized(player); }
        boolean outputAccessible() { return actionAuthorized(player); }
        List<UUID> rows() {
            var library = library(); if (library == null) return List.of();
            if (!canRead()) return List.of();
            refreshClassification();
            long recipeRevision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
            if (previewRecipeRevision != recipeRevision) {
                previews.clear(); previewRecipeRevision = recipeRevision; cachedRevision = Long.MIN_VALUE;
            }
            long revision = library.catalog().revision();
            if (revision != cachedRevision) previews.keySet().retainAll(library.catalog().ids());
            long tick = getLevel().getGameTime();
            if (revision == cachedRevision && search.equals(cachedSearch) && type.equals(cachedType)
                    && (!searchPending || scanTick == tick)) return cachedRows;
            searchPending = false;
            List<UUID> rows = new ArrayList<>(); String query = search.toLowerCase(Locale.ROOT);
            for (UUID id : library.catalog().ids()) {
                var row = library.catalog().entry(id);
                if (!type.isEmpty() && !row.types().contains(type)) continue;
                ItemStack stack = ItemStack.of(row.original());
                if (!query.isEmpty() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
                        && !row.types().toString().toLowerCase(Locale.ROOT).contains(query)) {
                    var preview = previews.get(id);
                    if (preview == null && searchBudget.take(tick)) preview = preview(row);
                    if (preview == null) { searchPending = true; continue; }
                    if (!preview.searchText().contains(query)) continue;
                }
                rows.add(id);
            }
            cachedRevision = revision; cachedSearch = search; cachedType = type; cachedRows = List.copyOf(rows); scanTick = tick;
            return cachedRows;
        }
        int total() { return !canRead() || library() == null ? 0 : library().catalog().size(); }
        int pages() { return Math.max(1, (rows().size() + PAGE_SIZE - 1) / PAGE_SIZE); }
        void changeSearch(String value) { search = limit(value); page = 0; }
        void previousPage() { page = Math.max(0, page - 1); }
        void nextPage() { page = Math.min(pages() - 1, page + 1); }
        void uploadOriginal() { status = upload(player); }
        void withdrawSelected() { status = withdraw(player, selected); }
        void showMessage(String reasonKey) { status = reasonKey; }
        String uploadDisabledReason() {
            return StellarCloudTransferUiState.upload(actionAuthorized(player), uploadOperation != null,
                    accepts(inbox.getStackInSlot(0)));
        }
        String withdrawDisabledReason() {
            var row = selectedRow();
            return StellarCloudTransferUiState.withdraw(actionAuthorized(player), row != null,
                    output.getStackInSlot(0).isEmpty(), row != null && row.withdrawRequested() && row.leased());
        }
        String memberDisabledReason() {
            return canRead() && library() != null && library().owner().equals(player.getUUID())
                    && actionAuthorized(player) ? "" : PREFIX + "status.denied";
        }
        String accessDisabledReason() {
            return StellarCloudTransferUiState.access(memberDisabledReason().isEmpty(),
                    selectedMember != null && memberCandidates().containsKey(selectedMember));
        }
        void quickUpload(int slot, ItemStack expected) {
            if (!actionAuthorized(player)) { status = PREFIX + "status.denied"; return; }
            if (slot < 0 || slot >= 36) { status = PREFIX + "status.invalid"; return; }
            if (uploadOperation != null || !inbox.getStackInSlot(0).isEmpty()) {
                status = PREFIX + "status.recovery";
                return;
            }
            ItemStack source = player.getInventory().getItem(slot);
            if (!ItemStack.matches(source, expected)) { status = PREFIX + "ui.source_changed"; return; }
            if (!accepts(source)) { status = PREFIX + "status.invalid"; return; }
            // 先從玩家背包移動一份原件，再交給既有可恢復的 inbox 上傳流程。
            ItemStack moved = player.getInventory().removeItem(slot, 1);
            inbox.setStackInSlot(0, moved);
            status = upload(player);
            if (uploadOperation == null && !inbox.getStackInSlot(0).isEmpty()) {
                ItemStack remainder = inbox.getStackInSlot(0).copy();
                player.getInventory().add(remainder);
                inbox.setStackInSlot(0, remainder);
            }
            player.getInventory().setChanged();
        }
        void quickWithdraw(int index, UUID expected) {
            if (!actionAuthorized(player)) { status = PREFIX + "status.denied"; return; }
            var row = row(index);
            if (row == null || !row.id().equals(expected)) { status = PREFIX + "ui.list_changed"; return; }
            if (!row.leased()) {
                ItemStack original = ItemStack.of(row.original());
                if (original.isEmpty()) { status = PREFIX + "status.invalid"; return; }
                boolean fits = player.getInventory().getFreeSlot() >= 0;
                for (int slot = 0; !fits && slot < 36; slot++) {
                    ItemStack existing = player.getInventory().getItem(slot);
                    fits = ItemStack.isSameItemSameTags(existing, original)
                            && existing.getCount() < Math.min(existing.getMaxStackSize(), player.getInventory().getMaxStackSize());
                }
                if (!fits) {
                    status = PREFIX + "status.inventory_full";
                    return;
                }
            }
            status = withdraw(player, row.id());
            if (status.equals(PREFIX + "status.withdrawn")) {
                ItemStack remainder = output.getStackInSlot(0).copy();
                player.getInventory().add(remainder);
                output.setStackInSlot(0, remainder);
                player.getInventory().setChanged();
            }
        }
        List<String> availableTypes() {
            if (!canRead() || library() == null) return List.of("");
            List<String> types = new ArrayList<>(library().catalog().types());
            Collections.sort(types);
            types.addFirst("");
            return types;
        }
        void selectType(String value) {
            if (!actionAuthorized(player) || !availableTypes().contains(value)) return;
            type = value;
            page = 0;
        }
        void select(int index) { if (actionAuthorized(player)) { var row = row(index); selected = row == null ? null : row.id(); } }
        void select(int index, UUID expected) {
            if (!actionAuthorized(player)) { status = PREFIX + "status.denied"; return; }
            var row = row(index);
            if (row == null || !row.id().equals(expected)) { status = PREFIX + "ui.list_changed"; return; }
            selected = expected;
        }
        void clearSelection(int index) {
            if (!actionAuthorized(player)) { status = PREFIX + "status.denied"; return; }
            if (index < 0 || index >= PAGE_SIZE) return;
            if (row(index) == null) selected = null;
            else status = PREFIX + "ui.list_changed";
        }
        private @Nullable StellarCloudCatalog.Row row(int index) {
            var visible = rows();
            page = StellarCloudGridPage.clamp(page, visible.size());
            int at = StellarCloudGridPage.index(page, index, visible.size());
            return at < 0 || library() == null
                    ? null : library().catalog().entry(visible.get(at));
        }
        UUID rowIdentity(int index) { var row = row(index); return row == null ? null : row.id(); }
        ItemStack rowStack(int index) { var row = row(index); return row == null ? ItemStack.EMPTY : ItemStack.of(row.original()); }
        ItemStack rowPreview(int index) { var row = row(index); return row == null ? ItemStack.EMPTY : preview(row).definition(); }
        List<Component> rowTooltip(int index) {
            var row = row(index); if (row == null) return List.of();
            List<Component> lines = new ArrayList<>(preview(row).tooltip());
            lines.add(state(row));
            return lines;
        }
        boolean rowSelected(int index) { var row = row(index); return row != null && row.id().equals(selected); }
        private StellarCloudPatternPreview preview(StellarCloudCatalog.Row row) {
            return previews.computeIfAbsent(row.id(), ignored -> StellarCloudPatternPreview.of(ItemStack.of(row.original()), getLevel()));
        }
        private @Nullable StellarCloudCatalog.Row selectedRow() {
            return !canRead() || selected == null || library() == null ? null : library().catalog().entry(selected);
        }
        Component selectedName() {
            var row = selectedRow();
            return row == null ? Component.translatable(PREFIX + "ui.no_selection") : preview(row).title();
        }
        Component rowState(int index) { return state(row(index)); }
        Component selectedState() { return state(selectedRow()); }
        Component message() {
            if (!canRead()) return Component.translatable(PREFIX + "status.denied");
            return searchPending && status.equals(PREFIX + "status.ready")
                    ? Component.translatable(PREFIX + "ui.indexing", previews.size(), total()) : Component.translatable(status);
        }
        Component selectedType() {
            var row = selectedRow();
            if (row == null) return Component.translatable(PREFIX + "ui.selection_hint");
            var names = Component.empty();
            for (String id : row.types()) {
                if (!names.getSiblings().isEmpty()) names.append(", ");
                names.append(StellarCloudLibraryUi.recipeTypeName(id));
            }
            return Component.translatable(PREFIX + "ui.selected_type", names);
        }
        private Component state(@Nullable StellarCloudCatalog.Row row) {
            return row == null ? Component.empty() : Component.translatable(PREFIX + (row.withdrawRequested()
                    ? "withdraw_requested" : row.leased() ? "leased" : "available"));
        }
        private Map<UUID, String> memberCandidates() {
            if (!canRead() || library() == null || !library().owner().equals(player.getUUID())) return Map.of();
            var server = ((ServerPlayer) player).getServer();
            if (server == null) return Map.of();
            Map<UUID, String> names = new HashMap<>();
            for (UUID id : library().members()) {
                String name = server.getProfileCache().get(id).map(profile -> profile.getName()).orElse(id.toString());
                names.put(id, name);
            }
            for (ServerPlayer online : server.getPlayerList().getPlayers()) names.put(online.getUUID(), online.getGameProfile().getName());
            names.remove(library().owner());
            return names;
        }
        void nextMember() {
            if (!actionAuthorized(player)) return;
            Map<UUID, String> names = memberCandidates();
            List<UUID> candidates = new ArrayList<>(names.keySet());
            candidates.sort(Comparator.comparing((UUID id) -> names.get(id), String.CASE_INSENSITIVE_ORDER).thenComparing(UUID::toString));
            selectedMember = StellarCloudMemberSelection.next(candidates, selectedMember);
        }
        Component memberText() {
            if (selectedMember == null) return Component.translatable(PREFIX + "ui.select_player");
            String name = memberCandidates().get(selectedMember);
            return name == null ? Component.translatable(PREFIX + "ui.select_player") : Component.literal(name);
        }
        void access(boolean grant) {
            if (!actionAuthorized(player) || !library().owner().equals(player.getUUID())) { status = PREFIX + "status.denied"; return; }
            if (selectedMember == null || !memberCandidates().containsKey(selectedMember)) { status = PREFIX + "status.invalid_member"; return; }
            if (grant) library().grant(selectedMember); else library().revoke(selectedMember);
            changed(); status = PREFIX + "status.access_changed";
        }
    }

    private static @Nullable StellarCloudLibraryMachine binding(RecipeTypePatternBufferPartMachine machine, @Nullable UUID expected) {
        if (!(machine.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        var node = machine.getMainNode().getNode();
        if (node == null || !node.isActive()) return null;
        IGrid grid = node.getGrid(); UUID actor = node.getOwningPlayerProfileId();
        StellarCloudLibraryMachine selected = null;
        for (var main : List.copyOf(LOADED)) {
            if (!main.isFormed()) continue;
            if (main.getLevel() == null || main.getLevel().getServer() != level.getServer()) continue;
            var core = main.aeCore();
            if (core == null || !main.aeBindingReady()) continue;
            var mainNode = core.getMainNode().getNode();
            if (mainNode == null || !mainNode.isActive() || mainNode.getGrid() != grid || main.getHolder().getSelf().isRemoved()) continue;
            if (selected != null && !Objects.equals(selected.libraryId, main.libraryId)) return null;
            selected = main;
        }
        if (selected == null || selected.library() == null || !selected.library().authorized(actor)
                || expected != null && !expected.equals(selected.libraryId)) return null;
        return selected;
    }
    public static @Nullable StellarCloudSource resolve(RecipeTypePatternBufferPartMachine machine) {
        var core = binding(machine, null); return core == null ? null : new Source(machine, core.libraryId);
    }
    private static final class Source implements StellarCloudSource {
        private final RecipeTypePatternBufferPartMachine machine; private final UUID library;
        private final StellarCloudSavedData store;
        private UUID runtimeExecutor;
        private long candidateRevision = Long.MIN_VALUE;
        private long publicationRevision;
        private Set<String> candidateTypes = Set.of();
        private List<Entry> candidateSnapshot = List.of();
        Source(RecipeTypePatternBufferPartMachine machine, UUID library) {
            this.machine = machine; this.library = library; this.store = StellarCloudSavedData.get((ServerLevel)machine.getLevel());
        }
        private StellarCloudSavedData data() { return store; }
        private StellarCloudCatalog catalog() { var value = data().library(library); return value == null ? null : value.catalog(); }
        @Override public String sourceId() { return "gt_shanhai:" + library; }
        @Override public boolean available() { return binding(machine, library) != null; }
        @Override public long revision() { return publicationRevision; }
        @Override public List<Entry> candidates(Set<String> hostTypes) {
            var core = binding(machine, library); if (core == null) return List.of();
            core.refreshClassification();
            var catalog = catalog(); long revision = catalog.revision();
            Set<String> expandedTypes = new LinkedHashSet<>(hostTypes);
            for (String type : catalog.types()) {
                if (hostTypes.stream().anyMatch(host -> RecipeTypeSharedSearchSets.isShared(host, type))) expandedTypes.add(type);
            }
            if (revision == candidateRevision && expandedTypes.equals(candidateTypes)) return candidateSnapshot;
            List<Entry> result = new ArrayList<>();
            for (UUID id : catalog.candidates(expandedTypes)) { var row = catalog.entry(id); result.add(new Entry(id, ItemStack.of(row.original()), row.configuration())); }
            candidateTypes = Set.copyOf(expandedTypes); candidateRevision = revision; candidateSnapshot = List.copyOf(result); publicationRevision++;
            return candidateSnapshot;
        }
        @Override public boolean claim(UUID executor, UUID entry) {
            boolean accepted = available() && bindRuntime(executor) && catalog().claim(executor, entry);
            if (accepted) data().setDirty(); return accepted;
        }
        @Override public boolean owns(UUID executor, UUID entry) {
            var catalog = catalog();
            var row = catalog == null ? null : catalog.entry(entry);
            return available() && bindRuntime(executor) && row != null && !row.withdrawRequested() && catalog.owns(executor, entry);
        }
        @Override public void release(UUID executor, UUID entry) {
            var catalog = catalog();
            if (catalog != null && bindRuntime(executor) && data().executorActive(executor, machine)) {
                catalog.release(executor, entry); data().setDirty();
            }
        }
        private boolean bindRuntime(UUID executor) {
            if (runtimeExecutor != null && !runtimeExecutor.equals(executor)) return false;
            if (!data().bindExecutor(executor, machine)) return false;
            runtimeExecutor = executor; return true;
        }
        @Override public CompoundTag saveBinding() {
            CompoundTag tag = new CompoundTag();
            if (runtimeExecutor != null) tag.putUUID("executor", runtimeExecutor);
            return tag;
        }
        @Override public void loadBinding(CompoundTag binding) {
            if (binding.hasUUID("executor")) bindRuntime(binding.getUUID("executor"));
        }
        @Override public void unload() {
            if (runtimeExecutor != null) data().unloadExecutor(runtimeExecutor, machine);
            runtimeExecutor = null;
        }
    }
}
