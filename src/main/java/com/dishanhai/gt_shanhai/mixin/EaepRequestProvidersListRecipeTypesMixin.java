package com.dishanhai.gt_shanhai.mixin;

import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.dishanhai.gt_shanhai.common.compat.eaep.EaepProviderRecipeTypeBridge;
import com.dishanhai.gt_shanhai.common.compat.eaep.EaepProviderRecipeTypesPacketAccess;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.extendedae_plus.init.ModNetwork;
import com.extendedae_plus.network.provider.ProvidersListS2CPacket;
import com.extendedae_plus.network.provider.RequestProvidersListC2SPacket;
import com.extendedae_plus.util.PatternProviderDataUtil;
import com.extendedae_plus.util.PatternTerminalUtil;
import com.extendedae_plus.util.uploadPattern.ProviderUploadUtil;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Mixin(value = RequestProvidersListC2SPacket.class, remap = false)
public class EaepRequestProvidersListRecipeTypesMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$handleWithRecipeTypes(RequestProvidersListC2SPacket msg,
            Supplier<NetworkEvent.Context> ctxSupplier, CallbackInfo ci) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (ProviderUploadUtil.hasPendingCtrlQPattern(player)) {
                ItemStack uploadPattern = EaepProviderUploadPatternAccessor.gtShanhai$getPendingCtrlQPattern(player);
                List<PatternContainer> containers = ProviderUploadUtil.listAvailableProvidersFromPlayerNetwork(player);
                sendIndexedContainers(player, containers, uploadPattern);
                return;
            }
            if (player.containerMenu instanceof PatternEncodingTermMenu encMenu) {
                PatternAccessTermMenu accessMenu = PatternTerminalUtil.getPatternAccessMenu(player);
                if (accessMenu != null) {
                    sendPatternAccessProviders(player, accessMenu, ItemStack.EMPTY);
                    return;
                }
                sendIndexedContainers(player, PatternTerminalUtil.listAvailableProvidersFromGrid(encMenu),
                        ItemStack.EMPTY);
            }
        });
        ctx.setPacketHandled(true);
        ci.cancel();
    }

    private static void sendPatternAccessProviders(ServerPlayer player, PatternAccessTermMenu accessMenu,
            ItemStack uploadPattern) {
        List<Long> ids = PatternTerminalUtil.getAllProviderIds(accessMenu);
        List<Long> filteredIds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<List<String>> recipeTypes = new ArrayList<>();
        List<Boolean> stellarProviders = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || !PatternProviderDataUtil.isProviderAvailable(id, accessMenu)) {
                continue;
            }
            int empty = PatternProviderDataUtil.getAvailableSlots(id, accessMenu);
            if (empty <= 0) {
                continue;
            }
            PatternContainer container = PatternTerminalUtil.getPatternContainerById(accessMenu, id);
            filteredIds.add(id);
            names.add(PatternProviderDataUtil.getProviderDisplayName(id, accessMenu));
            slots.add(empty);
            recipeTypes.add(EaepProviderRecipeTypeBridge.collectProviderRecipeTypeIds(container));
            stellarProviders.add(container instanceof RecipeTypePatternBufferPartMachine);
        }
        sendProviders(player, filteredIds, names, slots, recipeTypes, stellarProviders, uploadPattern);
    }

    private static void sendIndexedContainers(ServerPlayer player, List<PatternContainer> containers,
            ItemStack uploadPattern) {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<List<String>> recipeTypes = new ArrayList<>();
        List<Boolean> stellarProviders = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            PatternContainer container = containers.get(i);
            if (container == null) {
                continue;
            }
            int empty = PatternProviderDataUtil.getAvailableSlots(container);
            if (empty <= 0) {
                continue;
            }
            ids.add(-1L - i);
            names.add(PatternProviderDataUtil.getProviderDisplayName(container));
            slots.add(empty);
            recipeTypes.add(EaepProviderRecipeTypeBridge.collectProviderRecipeTypeIds(container));
            stellarProviders.add(container instanceof RecipeTypePatternBufferPartMachine);
        }
        sendProviders(player, ids, names, slots, recipeTypes, stellarProviders, uploadPattern);
    }

    private static void sendProviders(ServerPlayer player, List<Long> ids, List<String> names, List<Integer> slots,
            List<List<String>> recipeTypes, List<Boolean> stellarProviders, ItemStack uploadPattern) {
        ProvidersListS2CPacket packet = new ProvidersListS2CPacket(ids, names, slots);
        EaepProviderRecipeTypesPacketAccess access = (EaepProviderRecipeTypesPacketAccess) packet;
        access.gtShanhai$setProviderRecipeTypeIds(recipeTypes);
        access.gtShanhai$setStellarProviders(stellarProviders);
        if (uploadPattern != null && !uploadPattern.isEmpty()) {
            access.gtShanhai$setUploadRecipeTypeId(PatternRecipeTypeHelper.readRecipeTypeId(uploadPattern));
            access.gtShanhai$setWarningMetadataKnown(true);
        }
        ModNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
