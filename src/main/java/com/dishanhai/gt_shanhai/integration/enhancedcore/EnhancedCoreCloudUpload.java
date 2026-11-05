package com.dishanhai.gt_shanhai.integration.enhancedcore;

import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.PatternEncodingLogic;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.mixin.PatternEncodingTermMenuAccessor;
import com.gtl.enhancedcore.common.cloud.CloudLibraryNetwork;
import com.gtl.enhancedcore.common.cloud.CloudLibraryService;
import com.gtl.enhancedcore.common.cloud.CloudTerminalMenu;
import com.gtl.enhancedcore.common.cloud.store.CloudPatternStore;
import com.gtl.enhancedcore.common.cloud.transfer.CloudTerminalUpload;

import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 山海：僅在 API 能力檢查通過後載入，沿用終端來源的持久上傳與恢復協定。 */
public final class EnhancedCoreCloudUpload {

    private EnhancedCoreCloudUpload() {}

    public static EnhancedCoreCloudUploadBatch.Result tryUpload(ServerPlayer player, PatternEncodingTermMenu menu,
            List<ItemStack> patterns, BooleanSupplier consumeBlank) {
        if (!(menu instanceof CloudTerminalMenu terminal) || player.containerMenu != menu
                || !menu.isValidMenu() || !menu.stillValid(player) || !menu.isPowered()) return null;
        var node = menu.getNetworkNode();
        if (node == null || !node.isActive()) return null;
        var block = menu.getBlockEntity();
        if (block != null && (!(block.getLevel() instanceof ServerLevel level)
                || !CloudLibraryService.claimAllows(player, level, block.getBlockPos()))) return null;
        var binding = CloudLibraryNetwork.resolve(node.getGrid(), player);
        if (!binding.available()) return null;
        var library = CloudPatternStore.get(player.serverLevel()).library(binding.libraryId());
        if (!library.healthy() || !library.authorized(player.getUUID())) return null;

        PatternEncodingLogic logic = ((PatternEncodingTermMenuAccessor) menu).gtShanhai$getEncodingLogic();
        if (CloudTerminalUpload.quarantined(logic)) {
            return new EnhancedCoreCloudUploadBatch.Result(0, 0, 0, EnhancedCoreCloudUploadBatch.Status.REVIEW);
        }
        var output = logic.getEncodedPatternInv();
        return EnhancedCoreCloudUploadBatch.run(patterns, new EnhancedCoreCloudUploadBatch.Port<>() {
            @Override public boolean duplicate(ItemStack pattern) { return library.containsOriginal(pattern); }
            @Override public boolean outputEmpty() { return output.getStackInSlot(0).isEmpty(); }
            @Override public boolean consumeBlank() { return consumeBlank.getAsBoolean(); }

            @Override
            public void stage(ItemStack pattern) {
                output.setItemDirect(0, pattern.copyWithCount(1));
                logic.saveChanges();
            }

            @Override
            public EnhancedCoreCloudUploadBatch.Upload upload(ItemStack pattern) {
                // 原生 menu 重新驗證容器、AE 安全、網路與權限，再寫入 intent／扣來源／ACK。
                // 原方法會攔截一般上傳失敗；不得因此自行退款或重新建立已進 escrow 的原件。
                try {
                    terminal.cloud$upload();
                    if (CloudTerminalUpload.quarantined(logic)) return EnhancedCoreCloudUploadBatch.Upload.REVIEW;
                    if (output.getStackInSlot(0).isEmpty() && library.containsOriginal(pattern)) {
                        return EnhancedCoreCloudUploadBatch.Upload.COMMITTED;
                    }
                    return output.getStackInSlot(0).isEmpty()
                            ? EnhancedCoreCloudUploadBatch.Upload.REVIEW : EnhancedCoreCloudUploadBatch.Upload.RETAINED;
                } catch (RuntimeException failure) {
                    GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] cloud upload requires review", failure);
                    return EnhancedCoreCloudUploadBatch.Upload.REVIEW;
                }
            }
        });
    }
}
