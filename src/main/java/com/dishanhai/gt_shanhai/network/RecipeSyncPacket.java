package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.JEIRecipeCache;
import com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin;
import com.dishanhai.gt_shanhai.client.recipe.ShanhaiJeiRecipeViewSync;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Versioned recipe refresh signal. General refreshes identify affected types;
 * editor changes also carry one authoritative GT recipe or a removed recipe ID.
 */
public final class RecipeSyncPacket {

    private static final Logger LOG = LoggerFactory.getLogger("RecipeSync");
    private static final String PROTOCOL = "3";
    private static final int PAYLOAD_VERSION = 3;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_RECIPE_UPDATES = 64;
    private static final int MAX_STRING = 512;
    private static SimpleChannel CHANNEL;

    private final long revision;
    private final List<String> typeIds;
    private final boolean fullRefresh;
    private final Map<String, GTRecipe> recipeUpdates;
    private final List<String> removedRecipeIds;

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("gt_shanhai", "recipe_sync"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        // 只允許伺服器 → 客戶端：本包在客戶端會碰 Minecraft / JEI 類，
        // 若雙向可收，專用伺服器收到誤發或偽造的包就會走進客戶端專用路徑。
        CHANNEL.registerMessage(0, RecipeSyncPacket.class,
                RecipeSyncPacket::encode, RecipeSyncPacket::decode, RecipeSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public RecipeSyncPacket() {
        this(0L, List.of(), true, Map.of(), List.of());
    }

    public RecipeSyncPacket(long revision, List<String> typeIds, boolean fullRefresh,
                            Map<String, GTRecipe> recipeUpdates, List<String> removedRecipeIds) {
        this.revision = revision;
        this.typeIds = bounded(typeIds);
        this.fullRefresh = fullRefresh;
        this.recipeUpdates = boundedRecipes(recipeUpdates);
        this.removedRecipeIds = bounded(removedRecipeIds);
    }

    /**
     * 全量刷新信号：只帶受影響類型。
     *
     * <p>舊版還帶一份 {@code recipeIds}，但客戶端從未讀取，全量同步時最多白寫 4096 個 id 字串。
     * 內容更新走 {@link #syncRecipeToAll} 的 {@code recipeUpdates}。</p>
     */
    public static void syncToAll() {
        syncToAll(Set.of());
    }

    public static void syncToAll(Set<String> affectedTypeIds) {
        if (CHANNEL == null) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList() == null) return;

        Set<String> typeSet = new LinkedHashSet<>();
        if (affectedTypeIds == null || affectedTypeIds.isEmpty()) {
            typeSet.addAll(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds());
        } else {
            // 有明確受影響類型時只發這些：以前每次都夾帶全部規則類型，
            // 逐 type 重建就變成 N 個「全類型」刷新包。
            affectedTypeIds.stream()
                    .filter(typeId -> typeId != null && !typeId.isEmpty())
                    .forEach(typeSet::add);
        }
        if (typeSet.isEmpty()) return;
        List<String> types = new ArrayList<>(typeSet);
        RecipeSyncPacket packet = new RecipeSyncPacket(
                DShanhaiRecipeModifierAPI.getRecipeRevision(), types, false, Map.of(), List.of());
        sendToAll(server, packet);
        LOG.info("[RecipeSync] 已向 {} 个玩家发送配方同步包 rev={} types={}",
                server.getPlayerList().getPlayerCount(), packet.revision, packet.typeIds.size());
    }

    /**
     * 定向同步：帶完整配方內容，專用伺服器上的 override 結果才會出現在客戶端 JEI。
     * 一次最多 {@link #MAX_RECIPE_UPDATES} 條，超過的部分由後續全量刷新補上。
     */
    public static void syncRecipesToAll(String recipeTypeId, Collection<String> recipeIds) {
        if (CHANNEL == null || recipeTypeId == null || recipeTypeId.isEmpty()
                || recipeIds == null || recipeIds.isEmpty()) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList() == null) return;
        GTRecipeType type = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
                .get(new ResourceLocation(recipeTypeId));
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) return;

        Set<String> wanted = new LinkedHashSet<>();
        for (String recipeId : recipeIds) {
            if (recipeId != null && !recipeId.isEmpty()) wanted.add(recipeId);
        }
        Map<String, GTRecipe> updates = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
            if (recipe == null || recipe.getId() == null) return;
            String id = recipe.getId().toString();
            if (wanted.contains(id) && updates.size() < MAX_RECIPE_UPDATES) {
                updates.put(id, recipe.copy());
            }
        });
        for (String recipeId : wanted) {
            if (!updates.containsKey(recipeId)) removed.add(recipeId);
        }
        RecipeSyncPacket packet = new RecipeSyncPacket(
                DShanhaiRecipeModifierAPI.getRecipeRevision(),
                List.of(recipeTypeId), false, updates, removed);
        sendToAll(server, packet);
        LOG.info("[RecipeSync] 定向同步 type={} 更新={} 移除={}", recipeTypeId,
                packet.recipeUpdates.size(), packet.removedRecipeIds.size());
    }

    public static void syncRecipeToAll(String recipeTypeId, String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return;
        syncRecipesToAll(recipeTypeId, List.of(recipeId));
    }

    private static void sendToAll(net.minecraft.server.MinecraftServer server, RecipeSyncPacket packet) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    /**
     * 補發所有覆寫內容給單一玩家。專用伺服器上覆寫只存在伺服器，
     * 客戶端若不收內容就只能看到規則（strip/replace）的結果。
     */
    public static void syncOverridesTo(ServerPlayer player) {
        if (CHANNEL == null || player == null) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        Map<String, Set<String>> idsByType = overrideRecipeIds();
        for (Map.Entry<String, Set<String>> entry : idsByType.entrySet()) {
            RecipeSyncPacket packet = overridePacket(entry.getKey(), entry.getValue());
            if (packet == null) continue;
            CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    /** 覆寫類型 → 受影響配方 id。 */
    private static Map<String, Set<String>> overrideRecipeIds() {
        Map<String, Set<String>> idsByType = new LinkedHashMap<>();
        com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore store =
                new com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore(
                        com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureParent(
                                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.recipeOverrides()));
        for (String typeId : store.typeIds()) {
            Set<String> ids = DShanhaiRecipeModifierAPI.overrideRecipeIdsFor(typeId);
            if (!ids.isEmpty()) idsByType.put(typeId, ids);
        }
        return idsByType;
    }

    private static RecipeSyncPacket overridePacket(String recipeTypeId, Set<String> recipeIds) {
        GTRecipeType type = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
                .get(ResourceLocation.tryParse(recipeTypeId));
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) return null;
        Map<String, GTRecipe> updates = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
            if (recipe == null || recipe.getId() == null) return;
            String id = recipe.getId().toString();
            if (recipeIds.contains(id) && updates.size() < MAX_RECIPE_UPDATES) {
                updates.put(id, recipe.copy());
            }
        });
        for (String recipeId : recipeIds) {
            if (!updates.containsKey(recipeId)) removed.add(recipeId);
        }
        if (updates.isEmpty() && removed.isEmpty()) return null;
        return new RecipeSyncPacket(DShanhaiRecipeModifierAPI.getRecipeRevision(),
                List.of(recipeTypeId), false, updates, removed);
    }

    public static void encode(RecipeSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(PAYLOAD_VERSION);
        buf.writeVarLong(msg.revision);
        buf.writeBoolean(msg.fullRefresh);
        writeStrings(buf, msg.typeIds);
        buf.writeVarInt(msg.recipeUpdates.size());
        msg.recipeUpdates.forEach((id, recipe) -> {
            buf.writeUtf(id, MAX_STRING);
            GTRecipeSerializer.SERIALIZER.toNetwork(buf, recipe);
        });
        writeStrings(buf, msg.removedRecipeIds);
    }

    public static RecipeSyncPacket decode(FriendlyByteBuf buf) {
        if (!buf.isReadable()) return new RecipeSyncPacket();
        int version = buf.readVarInt();
        if (version != PAYLOAD_VERSION) return new RecipeSyncPacket();
        long revision = buf.readVarLong();
        boolean fullRefresh = buf.readBoolean();
        List<String> typeIds = readStrings(buf);
        int updateCount = buf.readVarInt();
        if (updateCount < 0 || updateCount > MAX_RECIPE_UPDATES) {
            throw new IllegalArgumentException("invalid recipe update count");
        }
        Map<String, GTRecipe> updates = new LinkedHashMap<>();
        for (int i = 0; i < updateCount; i++) {
            ResourceLocation id = ResourceLocation.tryParse(buf.readUtf(MAX_STRING));
            if (id == null) throw new IllegalArgumentException("invalid recipe update id");
            updates.put(id.toString(), GTRecipeSerializer.SERIALIZER.fromNetwork(id, buf));
        }
        List<String> removed = readStrings(buf);
        return new RecipeSyncPacket(revision, typeIds, fullRefresh, updates, removed);
    }

    public static void handle(RecipeSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> refreshClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void refreshClient(RecipeSyncPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.getConnection() == null) return;
        IJeiRuntime jeiRuntime = ShanhaiJEIPlugin.getRuntime();
        if (jeiRuntime == null) {
            LOG.warn("[RecipeSync] JEI 运行时不可用，跳过刷新");
            return;
        }
        List<String> typeIds = msg.fullRefresh || msg.typeIds.isEmpty()
                ? new ArrayList<>(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds())
                : msg.typeIds;
        var recipeManager = jeiRuntime.getRecipeManager();
        int refreshedTypes = 0;
        int refreshedRecipes = 0;
        for (String typeId : typeIds) {
            GTRecipeType gtRecipeType = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
                    .get(new ResourceLocation(typeId));
            if (gtRecipeType == null) continue;
            var jeiType = GTRecipeTypeCategory.TYPES.apply(gtRecipeType);
            var lookup = gtRecipeType.getLookup();
            if (lookup == null || lookup.getLookup() == null) continue;
            Set<String> targetedRecipeIds = targetedRecipeIds(msg, typeId);
            boolean targetedRecipeUpdate = !targetedRecipeIds.isEmpty();
            Map<String, GTRecipe> byId = new LinkedHashMap<>();
            List<GTRecipe> withoutId = new ArrayList<>();
            lookup.getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe == null) return;
                if (recipe.getId() == null) withoutId.add(recipe);
                else byId.put(recipe.getId().toString(), recipe);
            });
            boolean recipeTableChanged = false;
            for (Map.Entry<String, GTRecipe> entry : msg.recipeUpdates.entrySet()) {
                GTRecipe recipe = entry.getValue();
                if (recipe != null && recipe.recipeType != null && recipe.recipeType.registryName != null
                        && typeId.equals(recipe.recipeType.registryName.toString())
                        && recipe.getId() != null && entry.getKey().equals(recipe.getId().toString())) {
                    byId.put(entry.getKey(), recipe);
                    recipeTableChanged = true;
                }
            }
            for (String removedId : msg.removedRecipeIds) {
                recipeTableChanged |= byId.remove(removedId) != null;
            }
            if (recipeTableChanged) {
                boolean previous = DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.get();
                DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(true);
                try {
                    lookup.removeAllRecipes();
                    byId.values().forEach(lookup::addRecipe);
                    withoutId.forEach(lookup::addRecipe);
                } finally {
                    DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(previous);
                }
                com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeReverseIndex.invalidate();
                DShanhaiRecipeModifierAPI.invalidateRecipeCaches(
                        "recipe-sync-client", Set.of(typeId));
            }
            List<GTRecipeWrapper> wrappers = new ArrayList<>();
            if (targetedRecipeUpdate) {
                for (String recipeId : targetedRecipeIds) {
                    GTRecipe recipe = byId.get(recipeId);
                    if (recipe != null) wrappers.add(new GTRecipeWrapper(recipe));
                }
            } else {
                for (GTRecipe recipe : byId.values()) wrappers.add(new GTRecipeWrapper(recipe));
                for (GTRecipe recipe : withoutId) wrappers.add(new GTRecipeWrapper(recipe));
            }

            var registeredWrappers = recipeManager.createRecipeLookup(jeiType)
                    .includeHidden().get()
                    .filter(value -> value instanceof GTRecipeWrapper)
                    .map(value -> (GTRecipeWrapper) value)
                    .toList();
            var oldWrappers = targetedRecipeUpdate
                    ? registeredWrappers.stream()
                            .filter(wrapper -> wrapper.recipe != null && wrapper.recipe.getId() != null
                                    && targetedRecipeIds.contains(wrapper.recipe.getId().toString()))
                            .toList()
                    : registeredWrappers;
            if (targetedRecipeUpdate && oldWrappers.isEmpty()) {
                oldWrappers = JEIRecipeCache.get(jeiType).stream()
                        .filter(wrapper -> wrapper.recipe != null && wrapper.recipe.getId() != null
                                && targetedRecipeIds.contains(wrapper.recipe.getId().toString()))
                        .toList();
            } else if (!targetedRecipeUpdate && oldWrappers.isEmpty()) {
                oldWrappers = JEIRecipeCache.get(jeiType);
            }
            if (!oldWrappers.isEmpty()) recipeManager.hideRecipes(jeiType, oldWrappers);
            if (targetedRecipeUpdate) JEIRecipeCache.removeRecipes(jeiType, targetedRecipeIds);
            else JEIRecipeCache.clear(jeiType);
            if (!wrappers.isEmpty()) {
                recipeManager.addRecipes(jeiType, wrappers);
            }
            if (targetedRecipeUpdate) {
                ShanhaiJeiRecipeViewSync.refreshCachedView(
                        jeiRuntime, typeId, targetedRecipeIds, wrappers);
            }
            if (targetedRecipeUpdate || !wrappers.isEmpty()) {
                refreshedTypes++;
                refreshedRecipes += targetedRecipeUpdate ? targetedRecipeIds.size() : wrappers.size();
            }
        }
        LOG.info("[RecipeSync] 已更新 {} 个 JEI 配方，涉及 {} 个 GT 配方类型 rev={}",
                refreshedRecipes, refreshedTypes, msg.revision);
    }

    private static Set<String> targetedRecipeIds(RecipeSyncPacket msg, String recipeTypeId) {
        if (msg.fullRefresh) return Set.of();
        Set<String> targeted = new LinkedHashSet<>();
        for (Map.Entry<String, GTRecipe> entry : msg.recipeUpdates.entrySet()) {
            GTRecipe recipe = entry.getValue();
            if (recipe != null && recipe.recipeType != null && recipe.recipeType.registryName != null
                    && recipeTypeId.equals(recipe.recipeType.registryName.toString())
                    && recipe.getId() != null && entry.getKey().equals(recipe.getId().toString())) {
                targeted.add(entry.getKey());
            }
        }
        if (msg.typeIds.size() == 1 && msg.typeIds.contains(recipeTypeId)) {
            targeted.addAll(msg.removedRecipeIds);
        }
        return Set.copyOf(targeted);
    }

    private static void writeStrings(FriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(Math.min(MAX_ENTRIES, values.size()));
        for (int i = 0; i < values.size() && i < MAX_ENTRIES; i++) {
            buf.writeUtf(values.get(i) == null ? "" : values.get(i), MAX_STRING);
        }
    }

    private static List<String> readStrings(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("invalid recipe sync count");
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(buf.readUtf(MAX_STRING));
        return List.copyOf(values);
    }

    private static List<String> bounded(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> bounded = new ArrayList<>(Math.min(MAX_ENTRIES, values.size()));
        for (String value : values) {
            if (bounded.size() >= MAX_ENTRIES) break;
            if (value != null && !value.isEmpty()) bounded.add(value);
        }
        return List.copyOf(bounded);
    }

    private static Map<String, GTRecipe> boundedRecipes(Map<String, GTRecipe> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, GTRecipe> bounded = new LinkedHashMap<>();
        for (Map.Entry<String, GTRecipe> entry : values.entrySet()) {
            if (bounded.size() >= MAX_RECIPE_UPDATES) break;
            String id = entry.getKey();
            GTRecipe recipe = entry.getValue();
            if (id != null && !id.isEmpty() && recipe != null) bounded.put(id, recipe.copy());
        }
        return Map.copyOf(bounded);
    }
}
