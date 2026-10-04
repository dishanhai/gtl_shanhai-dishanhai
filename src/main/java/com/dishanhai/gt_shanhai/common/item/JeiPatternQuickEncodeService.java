package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.mixin.PatternEncodingTermMenuAccessor;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.gtlcore.gtlcore.common.data.GTLStats;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternEncoderMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService.Target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class JeiPatternQuickEncodeService {

    private static final int MIN_SDA_FALLBACK_PATTERNS = 20;
    private static final int DUPLICATE_TARGET_HIGHLIGHT_COLOR = 0xFF3030;
    private static final ResourceLocation MOLECULAR_ASSEMBLER_MATRIX_ID =
            new ResourceLocation("gtceu", "molecular_assembler_matrix");
    private static final ResourceLocation PRIMORDIAL_MOLECULAR_ASSEMBLER_MODULE_ID =
            new ResourceLocation("gt_shanhai", "primordial_molecular_assembler_module");
    private static final ResourceLocation MOLECULAR_ASSEMBLER_IO_ID =
            new ResourceLocation("gtceu", "me_molecular_assembler_io");

    /**
     * 只缓存当前 ME Grid 已发现的星律；容量状态每次轻量检查，避免每张样板都重新遍历整张网络。
     * IGrid 使用弱键，世界/网络重建后不会把旧网格永久留在静态缓存中。
     */
    private static final Map<IGrid, StellarTargetCache> STELLAR_TARGET_CACHES = new WeakHashMap<>();

    private JeiPatternQuickEncodeService() {}

    private static AEItemKey blankPatternKey() {
        return AEItemKey.of(AEItems.BLANK_PATTERN.stack());
    }

    public static void encodeAndUpload(ServerPlayer player, PatternEncodingTermMenu menu,
            String anchorRecipeId, boolean wholeRecipeType) {
        CraftingRecipe craftingAnchor = PatternRecipeTypeHelper.resolveVanillaCraftingRecipe(
                player.level(), anchorRecipeId);
        GTRecipe anchor = craftingAnchor == null
                ? PatternRecipeTypeHelper.resolveRecipe(player.level(), anchorRecipeId) : null;
        if (craftingAnchor == null && (anchor == null || anchor.id == null || anchor.recipeType == null
                || anchor.recipeType.registryName == null)) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] recipe resolve failed id={} dimension={}",
                    anchorRecipeId, player.level().dimension().location());
            show(player, "message.gt_shanhai.jei.quick_encode.invalid_recipe");
            return;
        }

        String recipeTypeId;
        List<ItemStack> patterns;
        int recipeCount;
        if (craftingAnchor != null) {
            List<CraftingRecipe> recipes = wholeRecipeType
                    ? collectVanillaCraftingRecipes(player.level())
                    : List.of(craftingAnchor);
            if (recipes.isEmpty()) {
                show(player, "message.gt_shanhai.jei.quick_encode.invalid_recipe");
                return;
            }
            recipeTypeId = PatternRecipeTypeHelper.VANILLA_CRAFTING_RECIPE_TYPE_ID;
            recipeCount = recipes.size();
            patterns = encodeCraftingPatterns(player, menu, recipes);
        } else {
            boolean vanillaSmelting = PatternRecipeTypeHelper.isVanillaSmeltingRecipe(
                    player.level(), anchorRecipeId);
            List<GTRecipe> recipes = wholeRecipeType
                    ? vanillaSmelting
                            ? collectVanillaSmeltingRecipes(player.level())
                            : collectRecipes(anchor.recipeType)
                    : List.of(anchor);
            if (recipes.isEmpty()) {
                show(player, "message.gt_shanhai.jei.quick_encode.invalid_recipe");
                return;
            }
            recipeTypeId = anchor.recipeType.registryName.toString();
            recipeCount = recipes.size();
            patterns = encodePatterns(player, menu, recipes);
        }
        if (patterns.size() != recipeCount) {
            show(player, "message.gt_shanhai.jei.quick_encode.encode_failed");
            return;
        }

        List<UploadedPattern> uploaded = new ArrayList<>(patterns.size());
        List<UploadedPattern> duplicatePatterns = new ArrayList<>();
        List<ItemStack> sdaPatterns = new ArrayList<>();
        Map<ResourceKey<Level>, List<ShanhaiStructureHighlightPacket.Marker>> duplicateHighlights =
                new LinkedHashMap<>();
        List<PatternQuickUploadService.Target> availableTargets;
        try {
            availableTargets = findAutomaticStellarTargets(
                    player, menu.getNetworkNode(), patterns.get(0));
        } catch (RuntimeException exception) {
            // 目标扫描失败不能让网络包静默结束；后续样板仍按既定玩家/SDA回退规则处理。
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] automatic stellar target search failed type={} exception={} message={}",
                    recipeTypeIdForPattern(player, patterns.get(0)),
                    exception.getClass().getName(), exception.getMessage());
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] automatic stellar target search stack", exception);
            availableTargets = List.of();
        }
        PatternQuickUploadService.Target currentTarget = null;
        for (ItemStack pattern : patterns) {
            PatternQuickUploadService.UploadResult result = null;
            while (result == null) {
                if (currentTarget == null) {
                    currentTarget = selectAutomaticTarget(player.level().dimension(),
                            player.blockPosition(), availableTargets);
                }
                if (currentTarget == null) break;
                result = safeInsertIntoTarget(player, pattern, currentTarget);
                if (result != null) break;
                invalidateCachedTarget(menu.getNetworkNode(), currentTarget);
                removeTarget(availableTargets, currentTarget);
                currentTarget = null;
            }
            if (result == null) {
                // 星律样板槽全部占满时保留样板，稍后一次性写入 SDA；有替补星律则下一轮会重新选择。
                sdaPatterns.add(pattern.copy());
                currentTarget = null;
                continue;
            }
            if (result.status() == PatternQuickUploadService.UploadStatus.DUPLICATE) {
                duplicatePatterns.add(new UploadedPattern(pattern, result.target(), result.slot()));
                addDuplicateHighlight(duplicateHighlights, result.target());
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] pattern already present target={} type={} slot={}",
                        result.target().bufferPos(), recipeTypeId, result.slot());
                continue;
            }
            uploaded.add(new UploadedPattern(pattern, result.target(), result.slot()));
        }

        sendDuplicateHighlights(player, duplicateHighlights);

        boolean useInventoryFallback = !sdaPatterns.isEmpty()
                && sdaPatterns.size() < MIN_SDA_FALLBACK_PATTERNS;
        boolean useSda = !useInventoryFallback
                && sdaPatterns.size() >= MIN_SDA_FALLBACK_PATTERNS;
        int inventoryCount = useInventoryFallback ? sdaPatterns.size() : 0;
        int skippedCount = useInventoryFallback || useSda ? 0 : sdaPatterns.size();
        int committedCount = uploaded.size() + inventoryCount
                + (useSda ? sdaPatterns.size() : 0);
        // SDA 门槛只约束回退打包；已经成功写入星律的样板必须保留，不能因
        // 剩余回退样板不足 20 张而整体回滚。未达门槛的回退样板改交给玩家。
        // 重複樣板不扣空白；扣料失敗時只回滾本次真正新增的槽位。
        PatternSource source = committedCount == 0 ? null : findPatternSource(menu, committedCount);
        PatternSource committedSource = source == null ? null : limitPatternSource(source, committedCount);
        if (committedCount > 0 && (committedSource == null || !consumePatternSource(menu, committedSource))) {
            boolean rolledBack = rollback(player, uploaded);
            show(player, rolledBack
                    ? "message.gt_shanhai.jei.quick_encode.missing_blank"
                    : "message.gt_shanhai.jei.quick_encode.rollback_failed",
                    committedCount);
            return;
        }

        ItemStack sda = !useSda ? ItemStack.EMPTY
                : packagePatternsInSda(player, sdaPatterns);
        if (useSda && sda.isEmpty()) {
            boolean restored = restorePatternSource(menu, committedSource);
            boolean rolledBack = rollback(player, uploaded);
            show(player, restored && rolledBack
                    ? "message.gt_shanhai.jei.quick_encode.sda_failed"
                    : "message.gt_shanhai.jei.quick_encode.rollback_failed",
                    sdaPatterns.size());
            return;
        }
        if (!sda.isEmpty()) {
            giveSda(player, sda);
        }
        if (useInventoryFallback) {
            givePatterns(player, sdaPatterns);
        }

        if (committedCount == 0 && skippedCount > 0) {
            show(player, "message.gt_shanhai.jei.quick_encode.sda_min_patterns",
                    skippedCount, MIN_SDA_FALLBACK_PATTERNS);
            return;
        }
        showSuccess(player, wholeRecipeType, patterns.size(), uploaded, duplicatePatterns,
                useSda ? sdaPatterns.size() : 0, inventoryCount, skippedCount,
                recipeTypeId);
        for (int i = 0; i < committedCount; i++) {
            GTLStats.awardPatternEncoded(player);
        }
    }

    private static List<GTRecipe> collectRecipes(GTRecipeType recipeType) {
        if (recipeType == null || recipeType.getLookup() == null
                || recipeType.getLookup().getLookup() == null) {
            return List.of();
        }
        Map<ResourceLocation, GTRecipe> unique = new LinkedHashMap<>();
        try {
            recipeType.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe != null && recipe.id != null && recipe.recipeType == recipeType) {
                    unique.putIfAbsent(recipe.id, recipe);
                }
            });
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] 无法读取配方类型 {} 的完整配方表",
                    recipeType.registryName, exception);
            return List.of();
        }
        List<GTRecipe> recipes = new ArrayList<>(unique.values());
        recipes.sort(Comparator.comparing(recipe -> recipe.id.toString()));
        return recipes;
    }

    private static List<GTRecipe> collectVanillaSmeltingRecipes(Level level) {
        if (level == null || level.getRecipeManager() == null) return List.of();
        Map<ResourceLocation, GTRecipe> unique = new LinkedHashMap<>();
        try {
            for (SmeltingRecipe vanilla : level.getRecipeManager()
                    .getAllRecipesFor(RecipeType.SMELTING)) {
                GTRecipe converted = PatternRecipeTypeHelper.toElectricFurnaceRecipe(vanilla);
                if (converted != null && converted.id != null) {
                    unique.putIfAbsent(vanilla.getId(), converted);
                }
            }
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] 无法读取原版熔炉配方表", exception);
            return List.of();
        }
        List<GTRecipe> recipes = new ArrayList<>(unique.values());
        recipes.sort(Comparator.comparing(recipe -> recipe.id.toString()));
        return recipes;
    }

    private static List<CraftingRecipe> collectVanillaCraftingRecipes(Level level) {
        if (level == null || level.getRecipeManager() == null) return List.of();
        Map<ResourceLocation, CraftingRecipe> unique = new LinkedHashMap<>();
        try {
            for (CraftingRecipe recipe : level.getRecipeManager()
                    .getAllRecipesFor(RecipeType.CRAFTING)) {
                if (recipe != null && recipe.getId() != null) {
                    unique.putIfAbsent(recipe.getId(), recipe);
                }
            }
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] 无法读取原版合成配方表", exception);
            return List.of();
        }
        List<CraftingRecipe> recipes = new ArrayList<>(unique.values());
        recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
        return recipes;
    }

    private static List<ItemStack> encodePatterns(ServerPlayer player, PatternEncodingTermMenu menu,
            List<GTRecipe> recipes) {
        List<ItemStack> patterns = new ArrayList<>(recipes.size());
        for (GTRecipe recipe : recipes) {
            try {
                Ae2GtmProcessingPattern encoded = ShanhaiPatternEncoder.encode(recipe, player, menu, true);
                ItemStack pattern = encoded == null ? ItemStack.EMPTY : encoded.getPatternItemStack();
                if (!isExactValidPattern(player, recipe, pattern)) {
                    GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] 拒绝异常样板 recipe={}", recipe.id);
                    return List.of();
                }
                pattern = pattern.copy();
                pattern.setCount(1);
                PatternEncoderMetadata.writeEncoder(pattern, player.getUUID(), player.getGameProfile().getName());
                patterns.add(pattern);
            } catch (RuntimeException exception) {
                GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] 编码配方失败 recipe={}", recipe.id, exception);
                return List.of();
            }
        }
        return patterns;
    }

    private static List<ItemStack> encodeCraftingPatterns(ServerPlayer player,
            PatternEncodingTermMenu menu,
            List<CraftingRecipe> recipes) {
        List<ItemStack> patterns = new ArrayList<>(recipes.size());
        for (CraftingRecipe recipe : recipes) {
            try {
                ItemStack pattern = ShanhaiPatternEncoder.encodeCrafting(recipe, player, menu);
                if (!isExactValidPattern(player, PatternRecipeTypeHelper.VANILLA_CRAFTING_RECIPE_TYPE_ID,
                        pattern)) {
                    GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] 拒绝异常原版合成样板 recipe={}",
                            recipe.getId());
                    return List.of();
                }
                pattern = pattern.copy();
                pattern.setCount(1);
                PatternEncoderMetadata.writeEncoder(pattern, player.getUUID(),
                        player.getGameProfile().getName());
                patterns.add(pattern);
            } catch (RuntimeException exception) {
                GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] 编码原版合成配方失败 recipe={}",
                        recipe.getId(), exception);
                return List.of();
            }
        }
        return patterns;
    }

    private static boolean isExactValidPattern(ServerPlayer player, GTRecipe recipe, ItemStack pattern) {
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            return false;
        }
        return isExactValidPattern(player, recipe.recipeType.registryName.toString(), pattern)
                && PatternDetailsHelper.decodePattern(pattern, player.level()) instanceof AEProcessingPattern;
    }

    private static boolean isExactValidPattern(ServerPlayer player, String recipeTypeId,
            ItemStack pattern) {
        if (pattern == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
            return false;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, player.level());
        if (PatternRecipeTypeHelper.VANILLA_CRAFTING_RECIPE_TYPE_ID.equals(recipeTypeId)) {
            return details instanceof AECraftingPattern;
        }
        if (!(details instanceof AEProcessingPattern)) return false;
        String encodedTypeId = PatternRecipeTypeHelper.readRecipeTypeId(pattern);
        return PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(encodedTypeId, recipeTypeId);
    }

    private static PatternQuickUploadService.Target findAutomaticStellarTarget(ServerPlayer player,
            IGridNode networkNode, ItemStack pattern) {
        return selectAutomaticTarget(player.level().dimension(), player.blockPosition(),
                findAutomaticStellarTargets(player, networkNode, pattern));
    }

    private static String recipeTypeIdForPattern(ServerPlayer player, ItemStack pattern) {
        if (pattern == null || pattern.isEmpty()) return "";
        try {
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, player.level());
            if (details instanceof AECraftingPattern) {
                return PatternRecipeTypeHelper.VANILLA_CRAFTING_RECIPE_TYPE_ID;
            }
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.debug("[JEIQuickEncode] pattern type decode failed", exception);
        }
        return PatternRecipeTypeHelper.readRecipeTypeId(pattern);
    }

    private static List<PatternQuickUploadService.Target> findAutomaticStellarTargets(
            ServerPlayer player, IGridNode networkNode, ItemStack pattern) {
        if (networkNode == null || networkNode.getGrid() == null) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] no active ME grid for recipe type search");
            return List.of();
        }
        String recipeTypeId = recipeTypeIdForPattern(player, pattern);
        if (recipeTypeId == null || recipeTypeId.isBlank()) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] encoded pattern has no recipe type metadata");
            return List.of();
        }

        List<PatternQuickUploadService.Target> cachedTargets = findCachedStellarTargets(
                player, networkNode.getGrid(), pattern, recipeTypeId);
        if (!cachedTargets.isEmpty()) return cachedTargets;

        // 山海側完整配方類型掃描沒有找到目標時，才用 GTLCore 公開搜尋補充候選位置。
        // 候選的 recipeTypeId 不作匹配依據；實際兼容性由星律主機完整類型集合決定。
        List<PatternQuickUploadService.Target> publicTargets = findPublicStellarTargets(
                player, networkNode, pattern, recipeTypeId);
        return publicTargets;
    }

    private static List<PatternQuickUploadService.Target> findPublicStellarTargets(
            ServerPlayer player, IGridNode networkNode, ItemStack pattern, String recipeTypeId) {
        try {
            PatternQuickUploadService.SearchResult search = PatternQuickUploadService.findTargets(
                    player, networkNode, pattern);
            if (search == null || search.match() == null || search.match().candidates().isEmpty()) {
                return List.of();
            }
            Map<StellarTargetKey, PatternQuickUploadService.Target> unique = new LinkedHashMap<>();
            for (PatternQuickUploadService.Target candidate : search.match().candidates()) {
                if (candidate == null) continue;
                PatternQuickUploadService.Target stellar = filterToSupportedTarget(
                        player.getServer(), candidate, recipeTypeId);
                if (stellar != null) {
                    if (isStellarTarget(player.getServer(), stellar)) {
                        cachePublicTarget(networkNode.getGrid(), player, stellar, recipeTypeId);
                    }
                    unique.putIfAbsent(stellarTargetKey(stellar), stellar);
                }
            }
            return new ArrayList<>(unique.values());
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] public target search failed type={} exception={} message={}",
                    recipeTypeId, exception.getClass().getName(), exception.getMessage());
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] public target search stack", exception);
            return List.of();
        }
    }

    private static PatternQuickUploadService.UploadResult safeInsertIntoTarget(
            ServerPlayer player, ItemStack pattern, PatternQuickUploadService.Target target) {
        String recipeTypeId = recipeTypeIdForPattern(player, pattern);
        try {
            PatternQuickUploadService.UploadResult result = isStellarTarget(player.getServer(), target)
                    ? insertIntoStellarTarget(player, pattern, target)
                    : PatternQuickUploadService.insertIntoTargetSlotResult(player, pattern, target);
            if (result != null && result.status() == PatternQuickUploadService.UploadStatus.DUPLICATE) {
                return result;
            }
            if (result == null || result.status() != PatternQuickUploadService.UploadStatus.INSERTED) {
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] target rejected upload target={} machine={} type={} status={}",
                        target == null ? null : target.bufferPos(),
                        target == null ? null : target.targetMachineId(), recipeTypeId,
                        result == null ? "null" : result.status());
                return null;
            }
            return result;
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] insert exception target={} type={}",
                    target == null ? null : target.bufferPos(), recipeTypeId, exception);
            return null;
        }
    }

    private static void removeTarget(List<PatternQuickUploadService.Target> targets,
            PatternQuickUploadService.Target target) {
        if (target == null) return;
        targets.removeIf(candidate -> candidate.levelKey().equals(target.levelKey())
                && candidate.bufferPos().equals(target.bufferPos()));
    }

    private static List<PatternQuickUploadService.Target> findCachedStellarTargets(ServerPlayer player,
            IGrid grid, ItemStack pattern, String recipeTypeId) {
        StellarTargetCache cache = STELLAR_TARGET_CACHES.get(grid);
        if (cache == null) {
            cache = new StellarTargetCache();
            STELLAR_TARGET_CACHES.put(grid, cache);
        }
        if (cache.scanned && cache.gridSize != grid.size()) {
            cache.scanned = false;
            cache.recipeTypeMissRescanned.clear();
        }
        if (!cache.scanned) {
            scanStellarTargets(player, grid, cache);
        }

        boolean needsRescan = false;
        List<PatternQuickUploadService.Target> candidates = new ArrayList<>();
        for (var iterator = cache.targets.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<StellarTargetKey, CachedStellarTarget> entry = iterator.next();
            CachedStellarTarget cached = entry.getValue();
            TargetValidation validation = validateCachedTarget(player, grid, cached, pattern);
            if (!validation.targetAlive()) {
                iterator.remove();
                needsRescan = true;
                continue;
            }
            if (!cached.recipeTypeIds().equals(validation.recipeTypeIds())) {
                iterator.remove();
                cache.capacityBlocked.remove(entry.getKey());
                needsRescan = true;
                continue;
            }
            if (!validation.canAcceptPattern()) {
                // 容量满是动态状态，不保留为本次候选，但不重新扫描整张网；槽位释放后下次会自动恢复。
                cache.capacityBlocked.add(entry.getKey());
                continue;
            }
            cache.capacityBlocked.remove(entry.getKey());
            if (containsRecipeType(cached.recipeTypeIds(), recipeTypeId)) {
                candidates.add(toRecipeTarget(cached.target(), recipeTypeId));
            }
        }

        if (needsRescan) {
            cache.recipeTypeMissRescanned.clear();
            scanStellarTargets(player, grid, cache);
            candidates.clear();
            for (CachedStellarTarget cached : cache.targets.values()) {
                if (!containsRecipeType(cached.recipeTypeIds(), recipeTypeId)) {
                    continue;
                }
                TargetValidation validation = validateCachedTarget(player, grid, cached, pattern);
                if (validation.targetAlive() && validation.canAcceptPattern()) {
                    candidates.add(toRecipeTarget(cached.target(), recipeTypeId));
                }
            }
        }

        // 首次扫描可能发生在多方块状态表尚未完成恢复的时机；若缓存中没有这个配方类型，
        // 不能把空结果永久当成结论。每个类型只触发一次补扫，既能追回 forming_press 等
        // 当前未被 GTLCore 选择集暴露的类型，也不会在每张样板上反复遍历整张世界。
        if (candidates.isEmpty() && !cache.recipeTypeMissRescanned.contains(recipeTypeId)) {
            cache.recipeTypeMissRescanned.add(recipeTypeId);
            cache.scanned = false;
            scanStellarTargets(player, grid, cache);
            for (CachedStellarTarget cached : cache.targets.values()) {
                if (!containsRecipeType(cached.recipeTypeIds(), recipeTypeId)) continue;
                TargetValidation validation = validateCachedTarget(player, grid, cached, pattern);
                if (validation.targetAlive() && validation.canAcceptPattern()) {
                    candidates.add(toRecipeTarget(cached.target(), recipeTypeId));
                }
            }
        }
        return candidates;
    }

    private static void scanStellarTargets(ServerPlayer player, IGrid grid, StellarTargetCache cache) {
        if (!cache.scanned) {
            cache.targets.clear();
            cache.capacityBlocked.clear();
        }
        cache.scanned = true;
        cache.gridSize = grid.size();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (!PatternContainer.class.isAssignableFrom(machineClass)) continue;
            for (Object machine : grid.getActiveMachines(machineClass)) {
                if (machine instanceof RecipeTypePatternBufferPartMachine stellar) {
                    tryCacheStellarTarget(grid, cache, stellar, "grid");
                }
            }
        }
        // 某些 GTL/原初机器只在多方块状态表中暴露部件，IGrid#getMachineClasses()
        // 不一定包含这些部件类；补扫已加载多方块，仍以同一 ME Grid 和槽位校验为准。
        for (ServerLevel level : player.getServer().getAllLevels()) {
            try {
                MultiblockWorldSavedData savedData = MultiblockWorldSavedData.getOrCreate(level);
                for (var state : List.copyOf(savedData.mapping.values())) {
                    var controller = state.getController();
                    if (controller == null || !controller.isFormed()) continue;
                    for (var part : controller.getParts()) {
                        MetaMachine machine = part == null ? null : part.self();
                        if (machine instanceof RecipeTypePatternBufferPartMachine stellar) {
                            tryCacheStellarTarget(grid, cache, stellar, "multiblock");
                        }
                    }
                }
            } catch (RuntimeException exception) {
                GTDishanhaiMod.LOGGER.debug(
                        "[JEIQuickEncode] loaded multiblock stellar scan failed level={}",
                        level.dimension(), exception);
            }
        }
    }

    private static void tryCacheStellarTarget(IGrid grid, StellarTargetCache cache,
            RecipeTypePatternBufferPartMachine stellar, String source) {
        try {
            cacheStellarTarget(grid, cache, stellar);
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] stellar candidate failed source={} pos={} class={} exception={} message={}",
                    source, stellar == null ? null : stellar.getPos(),
                    stellar == null ? null : stellar.getClass().getName(),
                    exception.getClass().getName(), exception.getMessage());
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] stellar candidate stack", exception);
        }
    }

    private static void cacheStellarTarget(IGrid grid, StellarTargetCache cache,
            RecipeTypePatternBufferPartMachine stellar) {
        boolean activeOnGrid = isActiveOnGrid(stellar, grid);
        boolean visible = stellar.isVisibleInTerminal();
        boolean formed = hasFormedController(stellar);
        if (!activeOnGrid || !visible || !formed) {
            return;
        }
        List<com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController> controllers = new ArrayList<>();
        for (var controller : stellar.getControllers()) {
            if (controller != null) controllers.add(controller);
        }
        List<GTRecipeType> hostTypes;
        try {
            hostTypes = WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(
                    controllers);
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.debug("[JEIQuickEncode] 无法读取星律主机配方类型 pos={}",
                    stellar.getPos(), exception);
            return;
        }
        Set<ResourceLocation> recipeTypeIds = recipeTypeIds(hostTypes);
        if (recipeTypeIds.isEmpty()) {
            GTDishanhaiMod.LOGGER.warn(
                    "[JEIQuickEncode] stellar pos={} has no readable host recipe types",
                    stellar.getPos());
            return;
        }
        if (stellar.getLevel() == null) return;
        ResourceKey<Level> levelKey = stellar.getLevel().dimension();
        Component targetName = stellar.getTerminalGroup() == null
                ? Component.literal("星律样板总成") : stellar.getTerminalGroup().name();
        StellarTargetKey key = new StellarTargetKey(levelKey, stellar.getPos().immutable());
        ResourceLocation representativeTypeId = recipeTypeIds.iterator().next();
        Target target = new Target(levelKey, stellar.getPos().immutable(),
                targetName, representativeTypeId,
                PatternQuickUploadMetadata.recipeTypeName(representativeTypeId), null, null,
                List.of(stellar.getPos().immutable()));
        cache.targets.put(key, new CachedStellarTarget(target, recipeTypeIds));
    }

    /**
     * 直接写入星律样板槽，绕过可能被其他整合模组禁用的 GTLCore 快捷上传入口。
     * 仍使用同一套 Target/UploadResult，保证目标选择、详情与回滚语义一致。
     */
    private static PatternQuickUploadService.UploadResult insertIntoStellarTarget(
            ServerPlayer player, ItemStack pattern, Target target) {
        ServerLevel level = player.getServer().getLevel(target.levelKey());
        if (level == null) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] insert target level missing level={}",
                    target.levelKey());
            return null;
        }
        List<BlockPos> positions = target.bufferPositions();
        if (positions == null || positions.isEmpty()) positions = List.of(target.bufferPos());
        String recipeTypeId = recipeTypeIdForPattern(player, pattern);
        int inspectedPositions = 0;
        int compatiblePositions = 0;
        int validSlots = 0;
        for (BlockPos position : positions) {
            if (position == null) {
                GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] insert skip null candidate position target={}",
                        target.bufferPos());
                continue;
            }
            if (!level.isLoaded(position)) {
                continue;
            }
            inspectedPositions++;
            MetaMachine machine = MetaMachine.getMachine(level, position);
            if (!(machine instanceof RecipeTypePatternBufferPartMachine stellar)) {
                continue;
            }
            Set<ResourceLocation> hostTypes = readHostRecipeTypeIds(stellar);
            boolean visible = stellar.isVisibleInTerminal();
            boolean supports = containsRecipeType(hostTypes, recipeTypeId);
            if (!visible || !supports) continue;
            compatiblePositions++;
            InternalInventory inventory = stellar.getTerminalPatternInventory();
            if (inventory == null) {
                GTDishanhaiMod.LOGGER.warn(
                        "[JEIQuickEncode] insert inventory missing position={} type={}",
                        position, recipeTypeId);
                continue;
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (isSameUploadPattern(inventory.getStackInSlot(slot), pattern)) {
                    Target resolvedTarget = new Target(target.levelKey(), position.immutable(),
                            target.targetName(), target.recipeTypeId(), target.recipeTypeName(),
                            target.targetIcon(), target.targetMachineId(), List.of(position.immutable()));
                    return new PatternQuickUploadService.UploadResult(
                            PatternQuickUploadService.UploadStatus.DUPLICATE, resolvedTarget, slot);
                }
            }
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.isItemValid(slot, pattern)) continue;
                validSlots++;
                ItemStack remainder = inventory.insertItem(slot, pattern.copy(), true);
                if (!remainder.isEmpty()) continue;
                inventory.insertItem(slot, pattern.copy(), false);
                Target resolvedTarget = new Target(target.levelKey(), position.immutable(),
                        target.targetName(), target.recipeTypeId(), target.recipeTypeName(),
                        target.targetIcon(), target.targetMachineId(), List.of(position.immutable()));
                return new PatternQuickUploadService.UploadResult(
                        PatternQuickUploadService.UploadStatus.INSERTED, resolvedTarget, slot);
            }
        }
        GTDishanhaiMod.LOGGER.warn(
                "[JEIQuickEncode] stellar insert failed target={} type={} inspectedPositions={} compatiblePositions={} validSlots={}",
                target.bufferPos(), recipeTypeId,
                inspectedPositions, compatiblePositions, validSlots);
        return null;
    }

    private static void cachePublicTarget(IGrid grid, ServerPlayer player, Target target,
            String recipeTypeId) {
        if (grid == null || target == null) return;
        StellarTargetCache cache = STELLAR_TARGET_CACHES.get(grid);
        if (cache == null) {
            cache = new StellarTargetCache();
            cache.scanned = true;
            cache.gridSize = grid.size();
            STELLAR_TARGET_CACHES.put(grid, cache);
        }
        Set<ResourceLocation> hostTypeIds = new LinkedHashSet<>();
        ServerLevel level = player.getServer().getLevel(target.levelKey());
        if (level != null && level.isLoaded(target.bufferPos())) {
            MetaMachine machine = MetaMachine.getMachine(level, target.bufferPos());
            if (machine instanceof RecipeTypePatternBufferPartMachine stellar) {
                try {
                    hostTypeIds.addAll(recipeTypeIds(WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(
                            stellar.getControllers())));
                } catch (RuntimeException ignored) {
                    // 保留当前已确认的请求类型，下一次轻量验证会自动修正或移除缓存。
                }
            }
        }
        ResourceLocation requested = ResourceLocation.tryParse(recipeTypeId);
        if (requested != null) hostTypeIds.add(requested);
        if (!hostTypeIds.isEmpty()) {
            cache.targets.put(new StellarTargetKey(target.levelKey(), target.bufferPos()),
                    new CachedStellarTarget(target, Set.copyOf(hostTypeIds)));
        }
    }

    private static TargetValidation validateCachedTarget(ServerPlayer player, IGrid grid,
            CachedStellarTarget cached, ItemStack pattern) {
        ResourceKey<Level> levelKey = cached.target().levelKey();
        ServerLevel level = player.getServer().getLevel(levelKey);
        if (level == null || !level.isLoaded(cached.target().bufferPos())) {
            return TargetValidation.dead();
        }
        MetaMachine machine = MetaMachine.getMachine(level, cached.target().bufferPos());
        if (!(machine instanceof RecipeTypePatternBufferPartMachine stellar)
                || !isActiveOnGrid(stellar, grid)
                || !stellar.isVisibleInTerminal()
                || !hasFormedController(stellar)) {
            return TargetValidation.dead();
        }
        Set<ResourceLocation> currentTypeIds;
        try {
            currentTypeIds = recipeTypeIds(WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(
                    stellar.getControllers()));
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] cached target type read failed target={}",
                    cached.target().bufferPos(), exception);
            return TargetValidation.dead();
        }
        if (currentTypeIds.isEmpty()) return TargetValidation.dead();
        boolean canAccept;
        try {
            canAccept = canAcceptPattern(stellar, pattern);
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] cached target capacity check failed target={} type={}",
                    cached.target().bufferPos(), recipeTypeIdForPattern(player, pattern), exception);
            canAccept = false;
        }
        return new TargetValidation(true, currentTypeIds, canAccept);
    }

    private static boolean isActiveOnGrid(RecipeTypePatternBufferPartMachine stellar, IGrid grid) {
        return stellar.getMainNode() != null && stellar.getMainNode().isActive()
                && stellar.getMainNode().getGrid() == grid;
    }

    private static boolean hasFormedController(RecipeTypePatternBufferPartMachine stellar) {
        for (var controller : stellar.getControllers()) {
            if (controller != null && controller.isFormed()) return true;
        }
        return false;
    }

    private static boolean canAcceptPattern(RecipeTypePatternBufferPartMachine stellar,
            ItemStack pattern) {
        InternalInventory inventory = stellar.getTerminalPatternInventory();
        if (inventory == null) return false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (isSameUploadPattern(inventory.getStackInSlot(slot), pattern)) return true;
            if (inventory.isItemValid(slot, pattern)
                    && inventory.insertItem(slot, pattern.copy(), true).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Set<ResourceLocation> recipeTypeIds(List<GTRecipeType> types) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (GTRecipeType type : types) {
            if (type != null && type.registryName != null) ids.add(type.registryName);
        }
        return Set.copyOf(ids);
    }

    private static boolean containsRecipeType(Set<ResourceLocation> recipeTypeIds, String recipeTypeId) {
        for (ResourceLocation id : recipeTypeIds) {
            if (PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(recipeTypeId, id.toString())) return true;
        }
        return false;
    }

    private static Target toRecipeTarget(Target target, String recipeTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeTypeId);
        if (id == null) return target;
        return new Target(target.levelKey(), target.bufferPos(), target.targetName(), id,
                PatternQuickUploadMetadata.recipeTypeName(id), target.targetIcon(),
                target.targetMachineId(), target.bufferPositions());
    }

    private static void invalidateCachedTarget(IGridNode networkNode, Target target) {
        if (networkNode == null || networkNode.getGrid() == null || target == null) return;
        StellarTargetCache cache = STELLAR_TARGET_CACHES.get(networkNode.getGrid());
        if (cache != null) {
            // 插入失败通常表示容量刚好在上一张样板后耗尽；保留发现缓存，避免下一张样板再次扫描全网。
            cache.capacityBlocked.add(new StellarTargetKey(target.levelKey(), target.bufferPos()));
        }
    }

    private static StellarTargetKey stellarTargetKey(Target target) {
        return new StellarTargetKey(target.levelKey(), target.bufferPos());
    }

    private static PatternQuickUploadService.Target filterToStellarTarget(MinecraftServer server,
            PatternQuickUploadService.Target target, String recipeTypeId) {
        if (server == null || target == null) return null;
        ServerLevel level = server.getLevel(target.levelKey());
        if (level == null) return null;

        List<BlockPos> positions = target.bufferPositions();
        if (positions == null || positions.isEmpty()) {
            positions = List.of(target.bufferPos());
        }
        List<BlockPos> stellarPositions = new ArrayList<>();
        for (BlockPos position : positions) {
            if (position == null || !level.isLoaded(position)) {
                continue;
            }
            MetaMachine machine = MetaMachine.getMachine(level, position);
            if (machine instanceof RecipeTypePatternBufferPartMachine stellar) {
                Set<ResourceLocation> hostTypes = readHostRecipeTypeIds(stellar);
                boolean supports = containsRecipeType(hostTypes, recipeTypeId);
                if (supports) {
                    stellarPositions.add(position.immutable());
                }
            }
        }
        if (stellarPositions.isEmpty()) return null;
        return new PatternQuickUploadService.Target(
                target.levelKey(),
                stellarPositions.get(0),
                target.targetName(),
                target.recipeTypeId(),
                target.recipeTypeName(),
                target.targetIcon(),
                target.targetMachineId(),
                List.copyOf(stellarPositions));
    }

    private static PatternQuickUploadService.Target filterToSupportedTarget(MinecraftServer server,
            PatternQuickUploadService.Target target, String recipeTypeId) {
        PatternQuickUploadService.Target stellar = filterToStellarTarget(server, target, recipeTypeId);
        if (stellar != null) return stellar;
        if (isMolecularAssemblerTarget(server, target)) {
            GTDishanhaiMod.LOGGER.info(
                    "[JEIQuickEncode] accepted molecular IO target pos={} controller={} port={} type={}",
                    target.bufferPos(), target.targetMachineId(), MOLECULAR_ASSEMBLER_IO_ID, recipeTypeId);
            return target;
        }
        return null;
    }

    private static boolean isSameUploadPattern(ItemStack first, ItemStack second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()
                || !ItemStack.isSameItem(first, second)) {
            return false;
        }
        ItemStack firstDefinition = first.copy();
        ItemStack secondDefinition = second.copy();
        stripUploadMetadataForComparison(firstDefinition);
        stripUploadMetadataForComparison(secondDefinition);
        return ItemStack.isSameItemSameTags(firstDefinition, secondDefinition);
    }

    private static void stripUploadMetadataForComparison(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("gtlcore", 10)) return;
        CompoundTag gtlcoreTag = tag.getCompound("gtlcore");
        gtlcoreTag.remove("patternQuickUploadRecipeTypes");
        gtlcoreTag.remove("patternEncoderId");
        gtlcoreTag.remove("patternEncoderName");
        if (gtlcoreTag.isEmpty()) {
            tag.remove("gtlcore");
        } else {
            tag.put("gtlcore", gtlcoreTag);
        }
    }

    private static boolean supportsRecipeType(RecipeTypePatternBufferPartMachine stellar,
            String recipeTypeId) {
        return containsRecipeType(readHostRecipeTypeIds(stellar), recipeTypeId);
    }

    private static Set<ResourceLocation> readHostRecipeTypeIds(
            RecipeTypePatternBufferPartMachine stellar) {
        if (stellar == null) return Set.of();
        try {
            return recipeTypeIds(WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(
                    stellar.getControllers()));
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] host recipe type read failed pos={}",
                    stellar.getPos(), exception);
            return Set.of();
        }
    }

    static PatternQuickUploadService.Target selectAutomaticTarget(ResourceKey<Level> playerLevel,
            BlockPos playerPos, List<PatternQuickUploadService.Target> targets) {
        if (targets == null || targets.isEmpty()) return null;
        return targets.stream().min(Comparator
                .comparingInt((PatternQuickUploadService.Target target) ->
                        isMolecularAssemblerControllerHint(target) ? 1 : 0)
                .thenComparingInt((PatternQuickUploadService.Target target) ->
                        target.levelKey().equals(playerLevel) ? 0 : 1)
                .thenComparingLong(target -> distanceSquared(playerLevel, playerPos, target))
                .thenComparing(target -> target.targetMachineId() == null
                        ? "" : target.targetMachineId().toString())
                .thenComparing(target -> target.levelKey().location().toString())
                .thenComparingLong(target -> target.bufferPos().asLong()))
                .orElse(null);
    }

    private static long distanceSquared(ResourceKey<Level> playerLevel, BlockPos playerPos,
            PatternQuickUploadService.Target target) {
        if (!target.levelKey().equals(playerLevel)) return Long.MAX_VALUE;
        long dx = (long) target.bufferPos().getX() - playerPos.getX();
        long dy = (long) target.bufferPos().getY() - playerPos.getY();
        long dz = (long) target.bufferPos().getZ() - playerPos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static PatternSource findPatternSource(PatternEncodingTermMenu menu, int required) {
        RestrictedInputSlot blankSlot = ((PatternEncodingTermMenuAccessor) menu)
                .gtShanhai$getBlankPatternSlot();
        ItemStack slotStack = blankSlot.getItem();
        int slotAmount = AEItems.BLANK_PATTERN.isSameAs(slotStack)
                ? Math.min(required, slotStack.getCount()) : 0;
        long networkAmount = required - slotAmount;
        MEStorage storage = menu.getHost().getInventory();
        if (networkAmount > 0L && (storage == null
            || storage.extract(blankPatternKey(), networkAmount, Actionable.SIMULATE,
                        menu.getActionSource()) != networkAmount)) {
            return null;
        }
        return new PatternSource(blankSlot, slotAmount, storage, networkAmount);
    }

    private static boolean consumePatternSource(PatternEncodingTermMenu menu, PatternSource source) {
        if (source.networkAmount() > 0L) {
            long extracted = source.storage().extract(blankPatternKey(), source.networkAmount(),
                    Actionable.MODULATE, menu.getActionSource());
            if (extracted != source.networkAmount()) {
                if (extracted > 0L) {
                    source.storage().insert(blankPatternKey(), extracted,
                            Actionable.MODULATE, menu.getActionSource());
                }
                return false;
            }
        }
        if (source.slotAmount() > 0) {
            ItemStack stack = source.blankSlot().getItem();
            if (!AEItems.BLANK_PATTERN.isSameAs(stack) || stack.getCount() < source.slotAmount()) {
                if (source.networkAmount() > 0L) {
                    source.storage().insert(blankPatternKey(), source.networkAmount(),
                            Actionable.MODULATE, menu.getActionSource());
                }
                return false;
            }
            stack.shrink(source.slotAmount());
            source.blankSlot().set(stack.isEmpty() ? ItemStack.EMPTY : stack);
        }
        return true;
    }

    private static PatternSource limitPatternSource(PatternSource source, int required) {
        int slotAmount = Math.min(required, source.slotAmount());
        return new PatternSource(source.blankSlot(), slotAmount, source.storage(),
                required - slotAmount);
    }

    private static boolean restorePatternSource(PatternEncodingTermMenu menu, PatternSource source) {
        boolean success = true;
        if (source.networkAmount() > 0L) {
            long inserted = source.storage().insert(blankPatternKey(), source.networkAmount(),
                    Actionable.MODULATE, menu.getActionSource());
            success = inserted == source.networkAmount();
        }
        if (source.slotAmount() > 0) {
            ItemStack stack = source.blankSlot().getItem();
            if (stack.isEmpty()) {
                ItemStack restored = AEItems.BLANK_PATTERN.stack();
                restored.setCount(source.slotAmount());
                source.blankSlot().set(restored);
            } else if (AEItems.BLANK_PATTERN.isSameAs(stack)
                    && stack.getCount() + source.slotAmount() <= stack.getMaxStackSize()) {
                stack.grow(source.slotAmount());
                source.blankSlot().set(stack);
            } else if (source.storage() == null
                    || source.storage().insert(blankPatternKey(), source.slotAmount(),
                            Actionable.MODULATE, menu.getActionSource()) != source.slotAmount()) {
                success = false;
            }
        }
        return success;
    }

    private static ItemStack packagePatternsInSda(ServerPlayer player, List<ItemStack> patterns) {
        ItemStack sda = new ItemStack(GTDishanhaiMod.SUPER_DISK_ARRAY.get());
        SuperDiskArrayInventory.claimOwnership(sda);
        SuperDiskArrayInventory inventory = SuperDiskArrayInventory.create(sda, null);
        if (inventory == null) return ItemStack.EMPTY;

        List<ItemStack> inserted = new ArrayList<>();
        for (ItemStack pattern : patterns) {
            long accepted = inventory.insert(AEItemKey.of(pattern), 1L, Actionable.MODULATE,
                    IActionSource.empty());
            if (accepted != 1L) {
                for (ItemStack rollbackPattern : inserted) {
                    inventory.extract(AEItemKey.of(rollbackPattern), 1L, Actionable.MODULATE,
                            IActionSource.empty());
                }
                return ItemStack.EMPTY;
            }
            inserted.add(pattern);
        }
        if (!(sda.getItem() instanceof IAEItemPowerStorage powerStorage)) {
            return ItemStack.EMPTY;
        }
        double maxPower = powerStorage.getAEMaxPower(sda);
        if (!(maxPower > 0.0D)) return ItemStack.EMPTY;
        powerStorage.injectAEPower(sda, maxPower, Actionable.MODULATE);
        if (powerStorage.getAECurrentPower(sda) + 1.0E-6D < maxPower) {
            return ItemStack.EMPTY;
        }
        return sda;
    }

    private static void giveSda(ServerPlayer player, ItemStack sda) {
        if (!player.getInventory().add(sda)) {
            player.drop(sda, false);
        }
    }

    private static void givePatterns(ServerPlayer player, List<ItemStack> patterns) {
        for (ItemStack pattern : patterns) {
            ItemStack delivered = pattern.copy();
            delivered.setCount(1);
            if (!player.getInventory().add(delivered)) {
                player.drop(delivered, false);
            }
        }
    }

    private static boolean rollback(ServerPlayer player, List<UploadedPattern> uploaded) {
        boolean success = true;
        for (int i = uploaded.size() - 1; i >= 0; i--) {
            UploadedPattern entry = uploaded.get(i);
            if (!removeFromTarget(player, entry.pattern(), entry.target(), entry.slot())) {
                success = false;
                GTDishanhaiMod.LOGGER.error(
                        "[JEIQuickEncode] 回滚失败 target={} pos={} slot={}",
                        entry.target().targetName().getString(), entry.target().bufferPos(), entry.slot());
            }
        }
        return success;
    }

    private static boolean removeFromTarget(ServerPlayer player, ItemStack pattern,
            Target target, int slot) {
        if (!isStellarTarget(player.getServer(), target)) {
            return PatternQuickUploadService.removeFromTarget(player, pattern, target, slot);
        }
        ServerLevel level = player.getServer().getLevel(target.levelKey());
        if (level == null) return false;
        BlockPos position = target.bufferPos();
        if (!level.isLoaded(position)) return false;
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (!(machine instanceof RecipeTypePatternBufferPartMachine stellar)) return false;
        InternalInventory inventory = stellar.getTerminalPatternInventory();
        if (slot < 0 || slot >= inventory.size()) return false;
        ItemStack stored = inventory.getStackInSlot(slot);
        if (!ItemStack.isSameItemSameTags(stored, pattern)
                || stored.getCount() < pattern.getCount()) return false;
        ItemStack extracted = inventory.extractItem(slot, pattern.getCount(), true);
        if (!ItemStack.isSameItemSameTags(extracted, pattern)) return false;
        inventory.extractItem(slot, pattern.getCount(), false);
        return true;
    }

    private static void show(ServerPlayer player, String translationKey, Object... args) {
        Component message = Component.translatable(translationKey, args);
        player.displayClientMessage(message, true);
        Component detail = Component.literal("[JEI快速编写] ").append(message);
        player.sendSystemMessage(detail);
    }

    private static void showSuccess(ServerPlayer player, boolean wholeRecipeType, int total,
            List<UploadedPattern> uploaded, List<UploadedPattern> duplicatePatterns,
            int sdaCount, int inventoryCount,
            int skippedCount, String recipeTypeId) {
        Component message;
        if (skippedCount > 0) {
            message = Component.translatable("message.gt_shanhai.jei.quick_encode.direct_partial_success",
                    uploaded.size(), skippedCount, MIN_SDA_FALLBACK_PATTERNS);
        } else if (inventoryCount > 0) {
            message = Component.translatable(
                    "message.gt_shanhai.jei.quick_encode.inventory_fallback_success",
                    uploaded.size(), inventoryCount);
        } else if (!duplicatePatterns.isEmpty()) {
            int newlyEncoded = uploaded.size() + sdaCount + inventoryCount;
            message = Component.translatable("message.gt_shanhai.jei.quick_encode.deduplicated_success",
                    newlyEncoded, duplicatePatterns.size());
        } else if (sdaCount == 0) {
            message = Component.translatable(wholeRecipeType
                    ? "message.gt_shanhai.jei.quick_encode.batch_success"
                    : "message.gt_shanhai.jei.quick_encode.single_success", total);
        } else {
            message = Component.translatable("message.gt_shanhai.jei.quick_encode.partial_success",
                    total, uploaded.size(), sdaCount);
        }
        player.displayClientMessage(message, true);

        List<UploadedPattern> targetPatterns = new ArrayList<>(uploaded);
        targetPatterns.addAll(duplicatePatterns);
        Component targetDetails = describeTargets(player, targetPatterns, sdaCount, inventoryCount);
        MutableComponent detail = Component.translatable(
                "message.gt_shanhai.jei.quick_encode.operation_detail",
                total, recipeTypeId == null ? "unknown" : recipeTypeId,
                targetDetails, sdaCount, inventoryCount, skippedCount);
        if (!duplicatePatterns.isEmpty()) {
            detail.append(Component.literal("; ")).append(Component.translatable(
                    "message.gt_shanhai.jei.quick_encode.duplicate_detail", duplicatePatterns.size()));
        }
        player.sendSystemMessage(detail);
    }

    private static Component describeTargets(ServerPlayer player, List<UploadedPattern> uploaded,
            int sdaCount, int inventoryCount) {
        if (uploaded.isEmpty()) {
            return Component.translatable(inventoryCount > 0
                    ? "message.gt_shanhai.jei.quick_encode.target_inventory"
                    : sdaCount > 0
                            ? "message.gt_shanhai.jei.quick_encode.target_sda"
                            : "message.gt_shanhai.jei.quick_encode.target_none");
        }
        Map<StellarTargetKey, Target> targets = new LinkedHashMap<>();
        Map<StellarTargetKey, Integer> counts = new LinkedHashMap<>();
        for (UploadedPattern entry : uploaded) {
            Target target = entry.target();
            StellarTargetKey key = stellarTargetKey(target);
            targets.putIfAbsent(key, target);
            counts.merge(key, 1, Integer::sum);
        }
        MutableComponent details = Component.empty();
        for (Map.Entry<StellarTargetKey, Target> entry : targets.entrySet()) {
            if (!details.getSiblings().isEmpty()) details.append(Component.literal("; "));
            Target target = entry.getValue();
            String targetName = target.targetName() == null
                    ? "星律样板总成" : target.targetName().getString();
            details.append(Component.literal(targetName + " @ "));
            details.append(teleportLink(target));
            details.append(Component.literal(" x" + counts.get(entry.getKey())));
            details.append(Component.literal("; "));
            details.append(describeSlots(player, target));
        }
        return details;
    }

    private static Component teleportLink(Target target) {
        BlockPos pos = target.bufferPos();
        String dimension = target.levelKey().location().toString();
        String command = "/shanhai stellar_tp " + dimension + " "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String label = "[" + dimension + " " + pos.getX() + ", " + pos.getY()
                + ", " + pos.getZ() + "]";
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("message.gt_shanhai.jei.quick_encode.teleport"))));
    }

    private static Component describeSlots(ServerPlayer player, Target target) {
        try {
            ServerLevel level = player.getServer().getLevel(target.levelKey());
            if (level != null && level.isLoaded(target.bufferPos())) {
                MetaMachine machine = MetaMachine.getMachine(level, target.bufferPos());
                InternalInventory inventory = machine instanceof PatternContainer container
                        ? container.getTerminalPatternInventory() : null;
                if (inventory == null && level.getBlockEntity(target.bufferPos())
                        instanceof PatternContainer container) {
                    inventory = container.getTerminalPatternInventory();
                }
                if (inventory != null) {
                    int occupied = 0;
                    for (int slot = 0; slot < inventory.size(); slot++) {
                        if (!inventory.getStackInSlot(slot).isEmpty()) occupied++;
                    }
                    return Component.translatable("message.gt_shanhai.jei.quick_encode.slots",
                            occupied, inventory.size() - occupied);
                }
            }
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] failed to read target slot counts pos={}",
                    target.bufferPos(), exception);
        }
        return Component.translatable("message.gt_shanhai.jei.quick_encode.slots_unavailable");
    }

    private record PatternSource(RestrictedInputSlot blankSlot, int slotAmount,
                                 MEStorage storage, long networkAmount) {}

    private record UploadedPattern(ItemStack pattern, PatternQuickUploadService.Target target, int slot) {}

    private static final class StellarTargetCache {
        private final Map<StellarTargetKey, CachedStellarTarget> targets = new LinkedHashMap<>();
        private final Set<StellarTargetKey> capacityBlocked = new LinkedHashSet<>();
        private final Set<String> recipeTypeMissRescanned = new LinkedHashSet<>();
        private boolean scanned;
        private int gridSize = -1;
    }

    private record StellarTargetKey(ResourceKey<Level> levelKey, BlockPos pos) {}

    private record CachedStellarTarget(Target target, Set<ResourceLocation> recipeTypeIds) {}

    private record TargetValidation(boolean targetAlive, Set<ResourceLocation> recipeTypeIds,
                                    boolean canAcceptPattern) {
        private static TargetValidation dead() {
            return new TargetValidation(false, Set.of(), false);
        }
    }

    private static void addDuplicateHighlight(
            Map<ResourceKey<Level>, List<ShanhaiStructureHighlightPacket.Marker>> highlights,
            PatternQuickUploadService.Target target) {
        if (target == null || target.levelKey() == null || target.bufferPos() == null) return;
        List<ShanhaiStructureHighlightPacket.Marker> markers = highlights.get(target.levelKey());
        if (markers == null) {
            markers = new ArrayList<>();
            highlights.put(target.levelKey(), markers);
        }
        ShanhaiStructureHighlightPacket.Marker marker =
                new ShanhaiStructureHighlightPacket.Marker(target.bufferPos(), DUPLICATE_TARGET_HIGHLIGHT_COLOR);
        if (!markers.contains(marker)) markers.add(marker);
    }

    private static void sendDuplicateHighlights(ServerPlayer player,
            Map<ResourceKey<Level>, List<ShanhaiStructureHighlightPacket.Marker>> highlights) {
        long expiresAt = System.currentTimeMillis() + 15000L;
        for (Map.Entry<ResourceKey<Level>, List<ShanhaiStructureHighlightPacket.Marker>> entry
                : highlights.entrySet()) {
            ShanhaiStructureHighlightPacket.sendTo(player, entry.getKey(), expiresAt, entry.getValue());
        }
    }

    private static boolean isStellarTarget(MinecraftServer server, Target target) {
        return target != null && !isMolecularAssemblerTarget(server, target);
    }

    private static boolean isMolecularAssemblerControllerHint(Target target) {
        if (target == null || target.targetMachineId() == null) return false;
        ResourceLocation machineId = target.targetMachineId();
        return MOLECULAR_ASSEMBLER_MATRIX_ID.equals(machineId)
                || PRIMORDIAL_MOLECULAR_ASSEMBLER_MODULE_ID.equals(machineId);
    }

    private static boolean isMolecularAssemblerTarget(MinecraftServer server, Target target) {
        if (server == null || target == null) return false;
        ServerLevel level = server.getLevel(target.levelKey());
        if (level == null || !level.isLoaded(target.bufferPos())) return false;
        MetaMachine machine = MetaMachine.getMachine(level, target.bufferPos());
        if (machine != null) {
            // 多方块控制器 ID 只用于描述目标，真正能接收分子样板的是 IO 端口。
            return MOLECULAR_ASSEMBLER_IO_ID.equals(machine.getDefinition().getId());
        }
        // ExtendedAE 独立矩阵是方块实体 PatternContainer，不是 GT MetaMachine；
        // 它仍可直接接收分子样板，但不能被当成星律或原初主机。
        return MOLECULAR_ASSEMBLER_MATRIX_ID.equals(target.targetMachineId())
                && level.getBlockEntity(target.bufferPos()) instanceof PatternContainer;
    }
}
