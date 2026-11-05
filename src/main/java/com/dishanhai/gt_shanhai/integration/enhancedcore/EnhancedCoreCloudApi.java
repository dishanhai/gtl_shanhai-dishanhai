package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.util.List;
import java.util.function.Predicate;

/** 山海：以方法能力辨識可選雲端 API，不載入任何外部型別。 */
public final class EnhancedCoreCloudApi {

    public record Method(String owner, String name, String descriptor) {}

    private static final String CLOUD = "com.gtl.enhancedcore.common.cloud.";
    private static final List<Method> REQUIRED = List.of(
            new Method(CLOUD + "CloudTerminalMenu", "cloud$upload", "()V"),
            new Method(CLOUD + "CloudTerminalMenu", "cloud$diagnostic", "()Ljava/lang/String;"),
            new Method(CLOUD + "CloudLibraryNetwork", "resolve",
                    "(Lappeng/api/networking/IGrid;Lnet/minecraft/server/level/ServerPlayer;)Lcom/gtl/enhancedcore/common/cloud/CloudLibraryNetwork$Binding;"),
            new Method(CLOUD + "transfer.CloudTerminalUpload", "processingOriginal",
                    "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Z"),
            new Method(CLOUD + "transfer.CloudTerminalUpload", "upload",
                    "(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;Lappeng/parts/encoding/PatternEncodingLogic;)V"),
            new Method(CLOUD + "CloudPatternBufferMachine", "cloudProbe",
                    "(Lcom/gtl/enhancedcore/common/cloud/store/CloudPatternStore$Entry;)Lcom/gtl/enhancedcore/common/cloud/CloudPatternMatching$Probe;"),
            new Method(CLOUD + "CloudCpuRefill", "physicalUnits", "(Lappeng/api/crafting/IPatternDetails;)Ljava/util/Map;"),
            // 這兩個合成方法也是原初選擇集的注入目標；版本變更不能半套啟用橋接。
            new Method("com.gtl.enhancedcore.common.recipe.iv.IvBuffers", "lambda$push$21",
                    "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;)Z"),
            new Method("com.gtl.enhancedcore.common.recipe.iv.IvBuffers", "lambda$cloudControllers$6",
                    "(Lcom/gtl/enhancedcore/common/cloud/CloudPatternBufferMachine;Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;)Z"));

    private EnhancedCoreCloudApi() {}

    public static boolean supports(boolean installed, Predicate<Method> probe) {
        if (!installed) return false;
        try {
            return REQUIRED.stream().allMatch(probe);
        } catch (LinkageError | RuntimeException unavailable) {
            return false;
        }
    }
}
