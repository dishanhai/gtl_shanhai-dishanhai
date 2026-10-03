package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.client.ShanhaiJEIPlugin;
import com.dishanhai.gt_shanhai.api.JEIRecipeCache;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeWrapper;

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
 * 配方同步包——配方修改后通知客户端刷新 JEI 显示。
 * 客户端收到后通过 JEI Runtime API 隐藏旧配方并重新注册新配方。
 */
public class RecipeSyncPacket {

    private static final Logger LOG = LoggerFactory.getLogger("RecipeSync");
    private static final String PROTOCOL = "1";
    private static SimpleChannel CHANNEL;

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("gt_shanhai", "recipe_sync"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        CHANNEL.registerMessage(0, RecipeSyncPacket.class,
                RecipeSyncPacket::encode, RecipeSyncPacket::decode, RecipeSyncPacket::handle);
    }

    public static void syncToAll() {
        if (CHANNEL == null) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList() == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CHANNEL.sendTo(new RecipeSyncPacket(), player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT);
        }
        LOG.info("[RecipeSync] 已向 {} 个玩家发送配方同步包", server.getPlayerList().getPlayerCount());
    }

    public RecipeSyncPacket() {}

    public static void encode(RecipeSyncPacket msg, FriendlyByteBuf buf) {}

    public static RecipeSyncPacket decode(FriendlyByteBuf buf) {
        return new RecipeSyncPacket();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void handle(RecipeSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.getConnection() == null) return;
            var jeiRuntime = ShanhaiJEIPlugin.getRuntime();
            if (jeiRuntime == null) {
                LOG.warn("[RecipeSync] JEI 运行时不可用，跳过刷新");
                return;
            }
            var recipeManager = jeiRuntime.getRecipeManager();
            int refreshed = 0;

            // 只刷新有山海运行时规则的类型；普通 GT 配方留在 JEI 原列表中，
            // 避免同步包把全局配方再次 addRecipes 导致成倍显示。
            for (String typeId : DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds()) {
                GTRecipeType gtRecipeType = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES
                        .get(new ResourceLocation(typeId));
                if (gtRecipeType == null) continue;
                var jeiType = GTRecipeTypeCategory.TYPES.apply(gtRecipeType);

                // 从 GT 配方查找表取当前配方（而非原版 RecipeManager）
                var lookup = gtRecipeType.getLookup();
                if (lookup == null) continue;
                var branch = lookup.getLookup();
                if (branch == null) continue;
                Map<String, GTRecipe> recipesById = new LinkedHashMap<>();
                List<GTRecipe> recipesWithoutId = new ArrayList<>();
                branch.getRecipes(true).forEach(r -> {
                    if (r == null) return;
                    if (r.getId() == null) recipesWithoutId.add(r);
                    else recipesById.put(r.getId().toString(), r);
                });
                List<GTRecipe> allRecipes = new ArrayList<>(recipesById.values());
                allRecipes.addAll(recipesWithoutId);

                // 隐藏旧条目
                var oldWrappers = jeiRuntime.getRecipeManager().createRecipeLookup(jeiType)
                        .includeHidden().get()
                        .filter(value -> value instanceof GTRecipeWrapper)
                        .map(value -> (GTRecipeWrapper) value)
                        .toList();
                if (oldWrappers.isEmpty()) oldWrappers = JEIRecipeCache.get(jeiType);
                if (!oldWrappers.isEmpty()) {
                    recipeManager.hideRecipes(jeiType, oldWrappers);
                }
                JEIRecipeCache.clear(jeiType);

                List<GTRecipeWrapper> newWrappers = new ArrayList<>();
                for (GTRecipe r : allRecipes) {
                    // lookup 已經是唯一的執行時來源；JEI 收集 mixin 會在此邊界
                    // 過濾刪除規則並只對非 canonical 配方建立修改副本。
                    newWrappers.add(new GTRecipeWrapper(r));
                }

                if (!newWrappers.isEmpty()) {
                    recipeManager.addRecipes(jeiType, newWrappers);
                    refreshed++;
                }
            }
            LOG.info("[RecipeSync] 已刷新 {} 个 GT 配方类型的 JEI 显示", refreshed);
        });
        ctx.get().setPacketHandled(true);
    }
}
