package com.dishanhai.gt_shanhai.common.item.generator;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.network.PatternGeneratorPresetPacket;
import com.dishanhai.gt_shanhai.network.PatternGeneratorStatePacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import static com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorCodec.*;

/** 山海生成器伺服器會話；沿用原 Widget 的配方、選取和生成流程。 */
public final class PatternGeneratorSession {
    private static final String PREFIX = "gt_shanhai.gui.pattern_generator.";
    public static final int TYPE = 101, CIRCUIT = 102, WHITE = 103, CLEAR = 104, PAGE = 105,
            SELECT = 106, SELECT_ALL = 107, GENERATE = 108, CANCEL = 109, FILL = 110, COLUMNS = 111,
            INCLUDE_TAG = 112, EXPORT_PRESET = 113, APPLY_PRESET = 114, INSPECT_PRESET = 115,
            EXT = 116, BUNDLE = 117, SET_SLOT = 118, CLOSE = 119, PRESET_FILE = 120, PRESET_PREVIEW = 121;
    private static final int BATCH_PER_TICK = 8;
    private final ServerPlayer player;
    private final InteractionHand hand;
    private final UUID sessionId = UUID.randomUUID();
    private final ItemStack originalStack;
    private final Item boundItem;
    private final UUID generatorId;
    private final PatternGeneratorSettings settings;
    private final PatternGeneratorActionChunks actionChunks = new PatternGeneratorActionChunks();
    private final Map<String, Integer> typeCounts = new HashMap<>();
    private List<GTRecipeType> types = List.of();
    private List<PatternGeneratorRecipes.Entry> all = List.of();
    private List<PatternGeneratorRecipes.Entry> matching = List.of();
    private final Set<String> unchecked = new HashSet<>();
    private Set<String> restoredSelection;
    private List<PatternGeneratorPreview> previewPage = List.of();
    private int revision, page, count, selected, pendingCount, generated, failed, skipped, availablePatterns, queuedTransfers;
    private long lastAvailableCheck = -1;
    private boolean running, ready, closed;
    private int lastActionRequestId;
    private Component feedback = Component.empty();

    PatternGeneratorSession(ServerPlayer player, InteractionHand hand) {
        this.player = player;
        this.hand = hand;
        originalStack = player.getItemInHand(hand);
        boundItem = originalStack.getItem();
        settings = PatternGeneratorSettings.load(originalStack);
        settings.transferArmed = false;
        if (settings.generatorId == null) settings.generatorId = UUID.randomUUID();
        generatorId = settings.generatorId;
        queuedTransfers = settings.transferRecipes.size();
        ready = settings.inputWhite.isBlank() && settings.outputWhite.isBlank();
        refreshMachine();
        rebuild(false);
        availablePatterns = PatternGeneratorTransfer.available(player, generatorId);
        save();
    }

    public UUID sessionId() { return sessionId; }
    public InteractionHand hand() { return hand; }
    public boolean ownedBy(ServerPlayer candidate) { return player == candidate; }
    ServerPlayer owner() { return player; }

    public byte[] assembleAction(int action, UUID transferId, int index, int count, int totalBytes, byte[] payload) {
        return assembleAction(action, transferId, index, count, totalBytes, payload, 0);
    }

    public byte[] assembleAction(int action, UUID transferId, int index, int count, int totalBytes,
                                 byte[] payload, int requestId) {
        return actionChunks.accept(action, transferId, index, count, totalBytes, payload,
                player.serverLevel().getGameTime(), requestId);
    }

    public void acknowledgeRequest(int requestId) {
        if (requestId < 0) throw new IllegalArgumentException("Invalid pattern generator request id");
        lastActionRequestId = requestId;
    }

    public boolean stillHeld() {
        ItemStack held = player.getItemInHand(hand);
        if (closed || !player.isAlive() || player.isRemoved() || held.isEmpty() || held.getItem() != boundItem)
            return false;
        CompoundTag root = held.getTag();
        if (root == null) return false;
        CompoundTag stored = root.getCompound(PatternGeneratorSettings.TAG);
        return stored.hasUUID("generatorId") && generatorId.equals(stored.getUUID("generatorId"));
    }

    private CompoundTag stateSettings() {
        CompoundTag tag = settings.write();
        tag.remove("pending");
        tag.remove("transferRecipes");
        tag.remove("transferConfiguration");
        tag.putInt("transferCount", queuedTransfers);
        return tag;
    }

    public PatternGeneratorState state() {
        return new PatternGeneratorState(sessionId, stateSettings(), revision, page, count, selected, pendingCount,
                generated, running, ready, availablePatterns, feedback, typeCounts, previewPage, lastActionRequestId);
    }

    public void tick() {
        actionChunks.expire(player.serverLevel().getGameTime());
        if (!stillHeld()) {
            PatternGeneratorSessions.close(player, sessionId);
            return;
        }
        if (running) generateBatch();
        long tick = player.serverLevel().getGameTime();
        if (lastAvailableCheck < 0 || tick - lastAvailableCheck >= 20) {
            lastAvailableCheck = tick;
            int available = PatternGeneratorTransfer.available(player, settings.generatorId);
            if (availablePatterns != available) {
                availablePatterns = available;
                push();
            }
        }
    }

    void close() {
        if (closed) return;
        actionChunks.clear();
        running = false;
        save();
        closed = true;
    }

    private void save() {
        if (closed) return;
        PatternGeneratorCodec.validateConfiguration(stateSettings());
        // 原工具已移手時保存原引用，不可把 NBT 寫到剛換上的物品。
        if (stillHeld()) player.getItemInHand(hand).getOrCreateTag().put(PatternGeneratorSettings.TAG, settings.write());
        else if (!originalStack.isEmpty()) originalStack.getOrCreateTag().put(PatternGeneratorSettings.TAG, settings.write());
        player.getInventory().setChanged();
    }

    /** 設定候選必須能完整送到客戶端；超限時恢復原設定，保留工具原有 NBT。 */
    private boolean configurationAccepted(CompoundTag previous) {
        try {
            PatternGeneratorCodec.validateConfiguration(stateSettings());
            return true;
        } catch (PatternGeneratorCodec.ConfigurationTooLargeException oversized) {
            settings.read(previous);
            feedback = Component.translatable(PREFIX + "state_too_large");
            push();
            return false;
        }
    }

    private void push() {
        if (!closed) ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PatternGeneratorStatePacket(state()));
    }

    private void sendPreset(int kind, Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(payload);
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PatternGeneratorPresetPacket(sessionId, kind, bytes));
        } finally { payload.release(); }
    }

    private void setSlot(FriendlyByteBuf buffer) {
        if (!stillHeld()) return;
        boolean machine = buffer.readBoolean();
        boolean input = false, exclude = false;
        int slot = -1;
        if (!machine) {
            input = buffer.readBoolean();
            exclude = buffer.readBoolean();
            slot = buffer.readVarInt();
        }
        CompoundTag tag = buffer.readNbt();
        GenericStack stack = tag == null ? null : GenericStack.readTag(tag);
        if (tag != null && stack == null) return;
        if (stack != null) {
            if (!(stack.what() instanceof AEItemKey || stack.what() instanceof AEFluidKey)) return;
            stack = new GenericStack(stack.what(), 1);
        }
        if (machine) {
            if (stack == null || stack.what() instanceof AEItemKey item && item.getItem() instanceof MetaMachineItem)
                setMachine(stack);
        } else setFilter(input, exclude, slot, stack);
    }

    private void refreshMachine() {
        types = PatternGeneratorRecipes.machineTypes(settings.machine);
        if (types.stream().noneMatch(type -> type.registryName.toString().equals(settings.recipeType))) {
            settings.recipeType = types.isEmpty() ? "" : types.get(0).registryName.toString();
        }
        typeCounts.clear();
        for (GTRecipeType type : types) {
            typeCounts.put(type.registryName.toString(), player.server.getRecipeManager().getAllRecipesFor(type).size());
        }
        GTRecipeType type = types.stream().filter(candidate -> candidate.registryName.toString().equals(settings.recipeType))
                .findFirst().orElse(null);
        all = type == null ? List.of() : PatternGeneratorRecipes.recipes(player, type);
    }

    private void setMachine(GenericStack machine) {
        if (pendingCount > 0 || queuedTransfers > 0) return;
        CompoundTag previous = settings.write();
        settings.machine = machine == null ? ItemStack.EMPTY : ((AEItemKey) machine.what()).toStack(1);
        settings.recipeType = PatternGeneratorRecipes.machineTypes(settings.machine).stream()
                .map(type -> type.registryName.toString()).findFirst().orElse("");
        if (!configurationAccepted(previous)) return;
        refreshMachine();
        rebuild(true);
        save();
        push();
    }

    private void setFilter(boolean input, boolean exclude, int slot, GenericStack stack) {
        if (pendingCount > 0 || queuedTransfers > 0 || slot < 0 || slot >= PatternGeneratorSettings.FILTER_SLOTS) return;
        CompoundTag previous = settings.write();
        settings.filterSlots(input, exclude)[slot] = stack;
        settings.filterTags(input, exclude)[slot] = "";
        if (!configurationAccepted(previous)) return;
        page = 0;
        rebuild(true);
        save();
        push();
    }

    private void rebuild(boolean resetSelection) {
        matching = ready ? all.stream().filter(entry -> entry.matches(settings))
                .sorted(PatternGeneratorRecipes.comparator(settings.sort)).toList() : List.of();
        if (resetSelection) unchecked.clear();
        if (!settings.pending.isEmpty()) {
            Set<String> queued = Set.copyOf(settings.pending);
            unchecked.clear();
            for (var entry : matching) {
                if (!queued.contains(entry.id())) unchecked.add(entry.id());
            }
        }
        if (ready && restoredSelection != null) {
            unchecked.clear();
            for (var entry : matching) {
                if (!restoredSelection.contains(entry.id())) unchecked.add(entry.id());
            }
            restoredSelection = null;
        }
        count = matching.size();
        selected = (int) matching.stream().filter(entry -> !unchecked.contains(entry.id())).count();
        pendingCount = settings.pending.size();
        queuedTransfers = settings.transferRecipes.size();
        if (queuedTransfers > 0) {
            matching.forEach(entry -> unchecked.add(entry.id()));
            selected = 0;
        }
        page = PatternGeneratorLayout.page(page, count, settings.previewColumns);
        revision++;
        feedback = Component.empty();
        refreshPreview();
    }

    private void refreshPreview() {
        int start = page * PatternGeneratorLayout.capacity(settings.previewColumns);
        previewPage = matching.stream().skip(start).limit(PatternGeneratorLayout.capacity(settings.previewColumns))
                .map(entry -> preview(entry, !unchecked.contains(entry.id()))).toList();
    }

    private PatternGeneratorPreview preview(PatternGeneratorRecipes.Entry entry, boolean checked) {
        boolean[] overflow = new boolean[1];
        List<GenericStack> inputs = scaleStacks(entry.encodedInputs(), PatternGeneratorScale.clamp(settings.scale), overflow);
        List<GenericStack> outputs = scaleStacks(entry.encodedOutputs(),
                PatternGeneratorScale.outputFactor(settings.scale, settings.outputScale), overflow);
        return new PatternGeneratorPreview(entry.id(), entry.circuit(), RecipeHelper.getInputEUt(entry.recipe()), entry.recipe().duration,
                GTValues.VN[entry.tier()], inputs, outputs, checked, overflow[0]);
    }

    private static List<GenericStack> scaleStacks(List<GenericStack> stacks, int factor, boolean[] overflow) {
        try {
            return PatternGeneratorRecipes.scaled(stacks, factor);
        } catch (ArithmeticException ignored) {
            overflow[0] = true;
            return stacks;
        }
    }

    public void handle(int id, FriendlyByteBuf buffer) {
        if (!stillHeld()) return;
        if (id == CLOSE) {
            PatternGeneratorSessions.close(player, sessionId);
            return;
        }
        if (id == SET_SLOT) {
            setSlot(buffer);
            return;
        }
        if (id < TYPE || id > BUNDLE) return;
        if (id == EXPORT_PRESET) {
            exportPreset(buffer);
            return;
        }
        if (id == APPLY_PRESET || id == INSPECT_PRESET) {
            importPreset(id, buffer);
            return;
        }
        if (id == COLUMNS) {
            CompoundTag previous = settings.write();
            settings.previewColumns = PatternGeneratorLayout.columns(buffer.readVarInt());
            if (!configurationAccepted(previous)) return;
            page = 0;
            refreshPreview();
            save();
            push();
            return;
        }
        if (id == FILL) {
            int expected = buffer.readVarInt();
            if (running || pendingCount > 0 || expected != revision || !ready) {
                push();
                return;
            }
            if (settings.transferRecipes.isEmpty() && selected > 0) {
                var chosen = matching.stream().filter(entry -> !unchecked.contains(entry.id())).toList();
                if (chosen.size() > PatternGeneratorPresetFiles.MAX_RECIPES) {
                    feedback = Component.translatable(PREFIX + "fill_too_many", PatternGeneratorPresetFiles.MAX_RECIPES);
                    push();
                    return;
                }
                CompoundTag candidateState = stateSettings();
                candidateState.putInt("transferCount", chosen.size());
                try {
                    PatternGeneratorCodec.validateConfiguration(candidateState);
                } catch (PatternGeneratorCodec.ConfigurationTooLargeException oversized) {
                    feedback = Component.translatable(PREFIX + "state_too_large");
                    push();
                    return;
                }
                settings.transferConfiguration = settings.configuration();
                for (var entry : chosen) {
                    settings.transferRecipes.add(entry.id());
                    unchecked.add(entry.id());
                }
                if (settings.generatorId == null) settings.generatorId = UUID.randomUUID();
                queuedTransfers = settings.transferRecipes.size();
                selected = 0;
            }
            availablePatterns = PatternGeneratorTransfer.available(player, settings.generatorId);
            if (availablePatterns == 0 && queuedTransfers == 0) {
                push();
                return;
            }
            settings.transferArmed = true;
            save();
            PatternGeneratorSessions.close(player, sessionId);
            player.displayClientMessage(Component.translatable(PREFIX + "fill_hint"), true);
            return;
        }
        if (id == CANCEL) {
            settings.pending.clear();
            pendingCount = 0;
            running = false;
            settings.transferRecipes.clear();
            settings.transferConfiguration = new CompoundTag();
            queuedTransfers = 0;
            settings.transferArmed = false;
            PatternGeneratorTransfer.cancel(player);
            feedback = Component.translatable(PREFIX + "cancelled");
            save();
            push();
            return;
        }
        if (id == WHITE) {
            String input = buffer.readUtf(256);
            String output = buffer.readUtf(256);
            BitSet inputItems = readBits(buffer, BuiltInRegistries.ITEM.size());
            BitSet inputFluids = readBits(buffer, BuiltInRegistries.FLUID.size());
            BitSet outputItems = readBits(buffer, BuiltInRegistries.ITEM.size());
            BitSet outputFluids = readBits(buffer, BuiltInRegistries.FLUID.size());
            if ((pendingCount > 0 || queuedTransfers > 0)
                    && (!input.equals(settings.inputWhite) || !output.equals(settings.outputWhite))) {
                push();
                return;
            }
            CompoundTag previous = settings.write();
            settings.inputWhite = input;
            settings.outputWhite = output;
            if (!configurationAccepted(previous)) return;
            copyBits(settings.inputNamedItems, inputItems);
            copyBits(settings.inputNamedFluids, inputFluids);
            copyBits(settings.outputNamedItems, outputItems);
            copyBits(settings.outputNamedFluids, outputFluids);
            ready = true;
            rebuild(true);
            save();
            push();
            return;
        }
        if (id == EXT) {
            int scale = PatternGeneratorScale.clamp(buffer.readVarInt());
            int outputScale = PatternGeneratorScale.clamp(buffer.readVarInt());
            int minTier = PatternGeneratorSettings.tier(buffer.readVarInt());
            int maxTier = PatternGeneratorSettings.tier(buffer.readVarInt());
            int sort = PatternGeneratorFilter.sortMode(buffer.readVarInt());
            boolean skip = buffer.readBoolean();
            String query = PatternGeneratorSettings.limit(buffer.readUtf(256));
            if (pendingCount > 0 || queuedTransfers > 0) {
                push();
                return;
            }
            if (minTier >= 0 && maxTier >= 0 && minTier > maxTier) {
                int swap = minTier;
                minTier = maxTier;
                maxTier = swap;
            }
            CompoundTag previous = settings.write();
            settings.scale = scale;
            settings.outputScale = outputScale;
            settings.minTier = minTier;
            settings.maxTier = maxTier;
            settings.sort = sort;
            settings.skipOwned = skip;
            settings.recipeQuery = query;
            if (!configurationAccepted(previous)) return;
            ready = true;
            page = 0;
            rebuild(true);
            save();
            push();
            return;
        }
        if (id == BUNDLE) {
            generateBundle(buffer);
            return;
        }
        if (id == GENERATE) {
            int expected = buffer.readVarInt();
            if (running || queuedTransfers > 0 || expected != revision || !ready) {
                push();
                return;
            }
            if (settings.pending.isEmpty()) {
                for (var entry : matching) {
                    if (!unchecked.contains(entry.id())) settings.pending.add(entry.id());
                }
            }
            if (!settings.pending.isEmpty()) {
                generated = 0;
                failed = 0;
                skipped = 0;
                running = true;
                pendingCount = settings.pending.size();
                feedback = Component.empty();
                save();
                push();
            }
            return;
        }
        if (id == PAGE) {
            int expected = buffer.readVarInt();
            int direction = buffer.readVarInt();
            if (expected == revision && Math.abs(direction) == 1) {
                page = PatternGeneratorLayout.page(page + direction, count, settings.previewColumns);
                refreshPreview();
            }
            push();
            return;
        }
        if (pendingCount > 0 || queuedTransfers > 0) {
            push();
            return;
        }
        if (id == TYPE) {
            String chosen = buffer.readUtf(256);
            if (types.stream().anyMatch(type -> type.registryName.toString().equals(chosen))) {
                CompoundTag previous = settings.write();
                settings.recipeType = chosen;
                if (!configurationAccepted(previous)) return;
                page = 0;
                refreshMachine();
                rebuild(true);
                save();
            }
        } else if (id == INCLUDE_TAG) {
            boolean input = buffer.readBoolean();
            boolean exclude = buffer.readBoolean();
            int slot = buffer.readVarInt();
            String tag = buffer.readUtf(256);
            if (slot >= 0 && slot < PatternGeneratorSettings.FILTER_SLOTS) {
                CompoundTag previous = settings.write();
                settings.filterTags(input, exclude)[slot] = PatternGeneratorSettings.validTag(
                        settings.filterSlots(input, exclude)[slot], tag);
                if (!configurationAccepted(previous)) return;
                page = 0;
                rebuild(true);
                save();
            }
        } else if (id == CIRCUIT) {
            int chosen = buffer.readVarInt();
            if (chosen >= PatternGeneratorFilter.ANY_CIRCUIT && chosen <= 32) {
                CompoundTag previous = settings.write();
                settings.circuit = chosen;
                if (!configurationAccepted(previous)) return;
                page = 0;
                rebuild(true);
                save();
            }
        } else if (id == CLEAR) {
            settings.clearFilters();
            ready = true;
            rebuild(true);
            save();
        } else if (id == SELECT) {
            int expected = buffer.readVarInt();
            String chosen = buffer.readUtf(256);
            if (expected == revision && matching.stream().anyMatch(entry -> entry.id().equals(chosen))) {
                if (!unchecked.remove(chosen)) unchecked.add(chosen);
                selected = (int) matching.stream().filter(entry -> !unchecked.contains(entry.id())).count();
                refreshPreview();
            }
        } else if (id == SELECT_ALL) {
            int expected = buffer.readVarInt();
            boolean checked = buffer.readBoolean();
            if (expected == revision) {
                unchecked.clear();
                if (!checked) matching.forEach(entry -> unchecked.add(entry.id()));
                selected = checked ? count : 0;
                refreshPreview();
            }
        }
        push();
    }

    private void exportPreset(FriendlyByteBuf buffer) {
        int expected = buffer.readVarInt();
        String name = buffer.readUtf(48).strip();

        if (expected != revision || !ready || pendingCount > 0 || selected <= 0 || name.isEmpty()) {
            sendPreset(PRESET_FILE, output -> output.writeBoolean(false));
            return;
        }
        var ids = matching.stream().filter(entry -> !unchecked.contains(entry.id())).map(PatternGeneratorRecipes.Entry::id).toList();
        var config = settings.configuration();
        try {
            var preset = PatternGeneratorPresetFiles.create(name, player.getName().getString(),
                    player.getUUID().toString(), config.toString(), ids);
            PatternGeneratorPresetFiles.decode(PatternGeneratorPresetFiles.encode(preset));
            sendPreset(PRESET_FILE, output -> {
                output.writeBoolean(true);
                output.writeUtf(preset.name(), 48);
                output.writeUtf(preset.author(), 64);
                output.writeUtf(preset.authorId(), 64);
                output.writeUtf(preset.savedAt(), 64);
                output.writeNbt(config);
                writeIds(output, ids);
            });
        } catch (RuntimeException invalid) {
            sendPreset(PRESET_FILE, output -> output.writeBoolean(false));
        }
    }

    private void importPreset(int action, FriendlyByteBuf buffer) {
        int request = 0;
        try {
            request = buffer.readVarInt();
            CompoundTag config = buffer.readNbt();
            List<String> ids = readIds(buffer);
            if (config == null || config.toString().length() > 65536 || ids.isEmpty()) {
                throw new IllegalArgumentException("Empty preset");
            }
            var candidate = new PatternGeneratorSettings();
            candidate.applyConfiguration(config);
            List<GTRecipeType> supported = PatternGeneratorRecipes.machineTypes(candidate.machine);
            GTRecipeType type = supported.stream().filter(entry -> entry.registryName.toString().equals(candidate.recipeType))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported machine/mode in preset"));
            Set<String> wanted = Set.copyOf(ids);
            List<PatternGeneratorRecipes.Entry> available = PatternGeneratorRecipes.recipes(player, type).stream()
                    .filter(entry -> wanted.contains(entry.id())).toList();
            if (action == INSPECT_PRESET) {
                int token = request;
                List<PatternGeneratorPreview> samples = available.stream().limit(150).map(entry -> preview(entry, false)).toList();
                sendPreset(PRESET_PREVIEW, output -> {
                    output.writeVarInt(token);
                    output.writeBoolean(true);
                    output.writeItem(candidate.machine);
                    output.writeUtf(PatternGeneratorRecipes.typeTranslation(type), 256);
                    output.writeVarInt(available.size());
                    output.writeVarInt(ids.size() - available.size());
                    output.writeVarInt(samples.size());
                    for (var value : samples) writePreview(output, value);
                });
            } else {
                if (pendingCount > 0 || queuedTransfers > 0 || running) {
                    push();
                    return;
                }
                CompoundTag previous = settings.write();
                settings.applyConfiguration(candidate.configuration());
                if (!configurationAccepted(previous)) return;
                settings.inputNamedItems.clear();
                settings.inputNamedFluids.clear();
                settings.outputNamedItems.clear();
                settings.outputNamedFluids.clear();
                restoredSelection = wanted;
                ready = settings.inputWhite.isBlank() && settings.outputWhite.isBlank();
                page = 0;
                refreshMachine();
                rebuild(true);
                save();
                push();
            }
        } catch (RuntimeException invalid) {
            if (action == INSPECT_PRESET) {
                int token = request;
                sendPreset(PRESET_PREVIEW, output -> {
                    output.writeVarInt(token);
                    output.writeBoolean(false);
                });
            } else {
                feedback = Component.translatable(PREFIX + "preset_unavailable");
                push();
            }
        }
    }

    private void generateBundle(FriendlyByteBuf buffer) {
        int expected = buffer.readVarInt();
        if (running || queuedTransfers > 0 || pendingCount > 0 || expected != revision || !ready) {
            push();
            return;
        }
        var chosen = matching.stream().filter(entry -> !unchecked.contains(entry.id())).toList();
        if (chosen.isEmpty()) {
            feedback = Component.translatable(PREFIX + "bundle_empty");
            push();
            return;
        }
        if (chosen.size() > SelectedRecipePattern.MAX_RECIPES) {
            feedback = Component.translatable(PREFIX + "bundle_too_many", SelectedRecipePattern.MAX_RECIPES);
            push();
            return;
        }
        int scale = PatternGeneratorScale.clamp(settings.scale);
        int outputScale = PatternGeneratorScale.clamp(settings.outputScale);
        var ids = new ArrayList<String>(chosen.size());
        for (var entry : chosen) {
            try {
                if (entry.recipe().recipeType == null || entry.recipe().recipeType.registryName == null
                        || types.stream().noneMatch(type -> type == entry.recipe().recipeType)
                        || !entry.recipe().recipeType.registryName.toString().equals(settings.recipeType)) {
                    throw new IllegalArgumentException("Recipe no longer belongs to the selected machine mode");
                }
                PatternGeneratorRecipes.validate(entry, player);
                boolean[] overflow = new boolean[1];
                scaleStacks(entry.encodedInputs(), scale, overflow);
                scaleStacks(entry.encodedOutputs(), PatternGeneratorScale.outputFactor(scale, outputScale), overflow);
                if (overflow[0]) throw new ArithmeticException("pattern amount overflow");
                ids.add(entry.id());
            } catch (ArithmeticException overflow) {
                feedback = Component.translatable(PREFIX + "scale_overflow");
                push();
                return;
            } catch (RuntimeException error) {
                feedback = Component.translatable(PREFIX + "recipes_changed");
                push();
                return;
            }
        }
        ItemStack bundle = SelectedRecipePattern.create(new SelectedRecipePattern.Contents(
                scale, outputScale, settings.recipeType, ids));
        if (bundle.isEmpty()) {
            feedback = Component.translatable(PREFIX + "bundle_empty");
            push();
            return;
        }
        player.getInventory().placeItemBackInInventory(bundle);
        feedback = Component.translatable(PREFIX + "bundle_result", ids.size());
        player.displayClientMessage(feedback, true);
        push();
    }

    private void generateBatch() {

        for (int index = 0; index < BATCH_PER_TICK && !settings.pending.isEmpty(); index++) {
            String id = settings.pending.get(0);
            var entry = matching.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (entry == null || key == null || player.server.getRecipeManager().byKey(key).orElse(null) != entry.recipe()) {
                running = false;
                feedback = Component.translatable(PREFIX + "recipes_changed");
                break;
            }
            try {
                if (settings.skipOwned && PatternGeneratorTransfer.owns(player, id)) {
                    skipped++;
                } else {
                    if (types.stream().noneMatch(type -> type == entry.recipe().recipeType)
                            || !entry.recipe().recipeType.registryName.toString().equals(settings.recipeType)) {
                        throw new IllegalArgumentException("Recipe no longer belongs to the selected machine mode");
                    }
                    ItemStack pattern = PatternGeneratorRecipes.encode(entry, player, settings.scale, settings.outputScale);
                    if (pattern.isEmpty()) throw new IllegalStateException("Recipe cannot be encoded: " + id);
                    PatternGeneratorTransfer.mark(pattern, settings);
                    player.getInventory().placeItemBackInInventory(pattern);
                    generated++;
                }
            } catch (RuntimeException error) {
                failed++;
                GTDishanhaiMod.LOGGER.error("Pattern generator could not encode recipe {}", id, error);
            }
            settings.pending.remove(0);
            unchecked.add(id);
        }
        pendingCount = settings.pending.size();
        availablePatterns = PatternGeneratorTransfer.available(player, settings.generatorId);
        selected = (int) matching.stream().filter(entry -> !unchecked.contains(entry.id())).count();
        refreshPreview();
        if (settings.pending.isEmpty()) {
            running = false;
            if (skipped > 0) feedback = Component.translatable(PREFIX + "result_skipped", generated, skipped, failed);
            else if (failed == 0) feedback = Component.translatable(PREFIX + "result", generated);
            else feedback = Component.translatable(PREFIX + "result_failed", generated, failed);
            player.displayClientMessage(feedback, true);
        }
        save();
        push();
    }

    private static void copyBits(BitSet target, BitSet source) {
        target.clear();
        target.or(source);
    }
}

