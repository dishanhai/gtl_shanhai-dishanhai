package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.JEIRecipeCache;
import com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Versioned recipe refresh signal. The client recomputes recipes locally; the packet
 * carries only revision and bounded affected-id metadata.
 */
public final class RecipeSyncPacket {

    private static final Logger LOG = LoggerFactory.getLogger("RecipeSync");
    private static final String PROTOCOL = "1";
    private static final int PAYLOAD_VERSION = 1;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_STRING = 512;
    private static SimpleChannel CHANNEL;

    private final long revision;
    private final List<String> typeIds;
    private final List<String> recipeIds;
    private final boolean fullRefresh;

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("gt_shanhai", "recipe_sync"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        CHANNEL.registerMessage(0, RecipeSyncPacket.class,
                RecipeSyncPacket::encode, RecipeSyncPacket::decode, RecipeSyncPacket::handle);
    }

    public RecipeSyncPacket() {
        this(0L, List.of(), List.of(), true);
    }

    public RecipeSyncPacket(long revision, List<String> typeIds, List<String> recipeIds, boolean fullRefresh) {
        this.revision = revision;
        this.typeIds = bounded(typeIds);
        this.recipeIds = bounded(recipeIds);
        this.fullRefresh = fullRefresh;
    }

    public static void syncToAll() {
        if (CHANNEL == null) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList() == null) return;

        List<String> types = new ArrayList<>(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds());
        List<String> recipes = new ArrayList<>();
        for (String typeId : types) {
            GTRecipeType type = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
                    .get(new ResourceLocation(typeId));
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) continue;
            type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe != null && recipe.getId() != null && recipes.size() < MAX_ENTRIES) {
                    recipes.add(recipe.getId().toString());
                }
            });
        }
        RecipeSyncPacket packet = new RecipeSyncPacket(
                DShanhaiRecipeModifierAPI.getRecipeRevision(), types, recipes, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
        LOG.info("[RecipeSync] 已向 {} 个玩家发送配方同步包 rev={} types={} recipes={}",
                server.getPlayerList().getPlayerCount(), packet.revision, packet.typeIds.size(), packet.recipeIds.size());
    }

    public static void encode(RecipeSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(PAYLOAD_VERSION);
        buf.writeVarLong(msg.revision);
        buf.writeBoolean(msg.fullRefresh);
        writeStrings(buf, msg.typeIds);
        writeStrings(buf, msg.recipeIds);
    }

    public static RecipeSyncPacket decode(FriendlyByteBuf buf) {
        if (!buf.isReadable()) return new RecipeSyncPacket();
        int version = buf.readVarInt();
        if (version != PAYLOAD_VERSION) return new RecipeSyncPacket();
        long revision = buf.readVarLong();
        boolean fullRefresh = buf.readBoolean();
        return new RecipeSyncPacket(revision, readStrings(buf), readStrings(buf), fullRefresh);
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
        int refreshed = 0;
        for (String typeId : typeIds) {
            GTRecipeType gtRecipeType = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
                    .get(new ResourceLocation(typeId));
            if (gtRecipeType == null) continue;
            var jeiType = GTRecipeTypeCategory.TYPES.apply(gtRecipeType);
            var lookup = gtRecipeType.getLookup();
            if (lookup == null || lookup.getLookup() == null) continue;
            Map<String, GTRecipe> byId = new LinkedHashMap<>();
            List<GTRecipe> withoutId = new ArrayList<>();
            lookup.getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe == null) return;
                if (recipe.getId() == null) withoutId.add(recipe);
                else byId.put(recipe.getId().toString(), recipe);
            });
            List<GTRecipeWrapper> wrappers = new ArrayList<>();
            for (GTRecipe recipe : byId.values()) wrappers.add(new GTRecipeWrapper(recipe));
            for (GTRecipe recipe : withoutId) wrappers.add(new GTRecipeWrapper(recipe));

            var oldWrappers = recipeManager.createRecipeLookup(jeiType)
                    .includeHidden().get()
                    .filter(value -> value instanceof GTRecipeWrapper)
                    .map(value -> (GTRecipeWrapper) value)
                    .toList();
            if (oldWrappers.isEmpty()) oldWrappers = JEIRecipeCache.get(jeiType);
            if (!oldWrappers.isEmpty()) recipeManager.hideRecipes(jeiType, oldWrappers);
            JEIRecipeCache.clear(jeiType);
            if (!wrappers.isEmpty()) {
                recipeManager.addRecipes(jeiType, wrappers);
                refreshed++;
            }
        }
        LOG.info("[RecipeSync] 已刷新 {} 个 GT 配方类型的 JEI 显示 rev={}", refreshed, msg.revision);
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
}
