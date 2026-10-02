package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
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
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.mixin.PatternEncodingTermMenuAccessor;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] begin player={} recipeId={} wholeRecipeType={}",
                player.getGameProfile().getName(), anchorRecipeId, wholeRecipeType);
        GTRecipe anchor = PatternRecipeTypeHelper.resolveRecipe(anchorRecipeId);
        if (anchor == null || anchor.id == null || anchor.recipeType == null
                || anchor.recipeType.registryName == null) {
            show(player, "message.gt_shanhai.jei.quick_encode.invalid_recipe");
            return;
        }

        List<GTRecipe> recipes = wholeRecipeType
                ? collectRecipes(anchor.recipeType)
                : List.of(anchor);
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] recipe resolved id={} type={} count={}",
                anchor.id, anchor.recipeType.registryName, recipes.size());
        if (recipes.isEmpty()) {
            show(player, "message.gt_shanhai.jei.quick_encode.invalid_recipe");
            return;
        }

        List<ItemStack> patterns = encodePatterns(player, recipes);
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] pattern encoding finished requested={} encoded={}",
                recipes.size(), patterns.size());
        if (patterns.size() != recipes.size()) {
            show(player, "message.gt_shanhai.jei.quick_encode.encode_failed");
            return;
        }

        PatternSource source = findPatternSource(menu, patterns.size());
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] blank source {} requested={}",
                source == null ? "missing" : "available", patterns.size());
        if (source == null) {
            show(player, "message.gt_shanhai.jei.quick_encode.missing_blank", patterns.size());
            return;
        }

        List<UploadedPattern> uploaded = new ArrayList<>(patterns.size());
        List<ItemStack> sdaPatterns = new ArrayList<>();
        List<PatternQuickUploadService.Target> availableTargets;
        try {
            availableTargets = findAutomaticStellarTargets(
                    player, menu.getNetworkNode(), patterns.get(0));
        } catch (RuntimeException exception) {
            // 目标扫描失败不能让网络包静默结束；后续样板仍按既定玩家/SDA回退规则处理。
            GTDishanhaiMod.LOGGER.error(
                    "[JEIQuickEncode] automatic stellar target search failed type={} exception={} message={}",
                    PatternRecipeTypeHelper.readRecipeTypeId(patterns.get(0)),
                    exception.getClass().getName(), exception.getMessage());
            GTDishanhaiMod.LOGGER.error("[JEIQuickEncode] automatic stellar target search stack", exception);
            availableTargets = List.of();
        }
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] target search type={} candidates={}",
                PatternRecipeTypeHelper.readRecipeTypeId(patterns.get(0)), availableTargets.size());
        PatternQuickUploadService.Target currentTarget = null;
        for (ItemStack pattern : patterns) {
            PatternQuickUploadService.UploadResult result = currentTarget == null
                    ? null
                    : safeInsertIntoStellarTarget(player, pattern, currentTarget);
            if (result == null) {
                invalidateCachedTarget(menu.getNetworkNode(), currentTarget);
                removeTarget(availableTargets, currentTarget);
                currentTarget = null;
                currentTarget = selectAutomaticTarget(player.level().dimension(),
                        player.blockPosition(), availableTargets);
                result = currentTarget == null ? null
                        : safeInsertIntoStellarTarget(player, pattern, currentTarget);
            }
            if (result == null) {
                invalidateCachedTarget(menu.getNetworkNode(), currentTarget);
                removeTarget(availableTargets, currentTarget);
                // 星律样板槽全部占满时保留样板，稍后一次性写入 SDA；有替补星律则下一轮会重新选择。
                sdaPatterns.add(pattern.copy());
                currentTarget = null;
                continue;
            }
            uploaded.add(new UploadedPattern(pattern, result.target(), result.slot()));
        }

        boolean useInventoryFallback = !sdaPatterns.isEmpty()
                && sdaPatterns.size() < MIN_SDA_FALLBACK_PATTERNS;
        boolean useSda = !useInventoryFallback
                && sdaPatterns.size() >= MIN_SDA_FALLBACK_PATTERNS;
        int inventoryCount = useInventoryFallback ? sdaPatterns.size() : 0;
        int skippedCount = useInventoryFallback || useSda ? 0 : sdaPatterns.size();
        int committedCount = uploaded.size() + inventoryCount
                + (useSda ? sdaPatterns.size() : 0);
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] routing result total={} uploaded={} inventoryFallback={} sdaFallback={} skipped={}",
                patterns.size(), uploaded.size(), inventoryCount, useSda ? sdaPatterns.size() : 0, skippedCount);
        // SDA 门槛只约束回退打包；已经成功写入星律的样板必须保留，不能因
        // 剩余回退样板不足 20 张而整体回滚。未达门槛的回退样板改交给玩家。
        PatternSource committedSource = limitPatternSource(source, committedCount);
        if (!consumePatternSource(menu, committedSource)) {
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
        showSuccess(player, wholeRecipeType, patterns.size(), uploaded,
                useSda ? sdaPatterns.size() : 0, inventoryCount, skippedCount,
                PatternRecipeTypeHelper.readRecipeTypeId(patterns.get(0)));
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

    private static List<ItemStack> encodePatterns(ServerPlayer player, List<GTRecipe> recipes) {
        List<ItemStack> patterns = new ArrayList<>(recipes.size());
        for (GTRecipe recipe : recipes) {
            try {
                Ae2GtmProcessingPattern encoded = ShanhaiPatternEncoder.encode(recipe, player, true);
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

    private static boolean isExactValidPattern(ServerPlayer player, GTRecipe recipe, ItemStack pattern) {
        if (pattern == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)
                || !(PatternDetailsHelper.decodePattern(pattern, player.level()) instanceof AEProcessingPattern)) {
            return false;
        }
        String encodedTypeId = PatternRecipeTypeHelper.readRecipeTypeId(pattern);
        return recipe.recipeType != null && recipe.recipeType.registryName != null
                && PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                        encodedTypeId, recipe.recipeType.registryName.toString());
    }

    private static PatternQuickUploadService.Target findAutomaticStellarTarget(ServerPlayer player,
            IGridNode networkNode, ItemStack pattern) {
        return selectAutomaticTarget(player.level().dimension(), player.blockPosition(),
                findAutomaticStellarTargets(player, networkNode, pattern));
    }

    private static List<PatternQuickUploadService.Target> findAutomaticStellarTargets(
            ServerPlayer player, IGridNode networkNode, ItemStack pattern) {
        if (networkNode == null || networkNode.getGrid() == null) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] no active ME grid for recipe type search");
            return List.of();
        }
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(pattern);
        if (recipeTypeId == null || recipeTypeId.isBlank()) {
            GTDishanhaiMod.LOGGER.warn("[JEIQuickEncode] encoded pattern has no recipe type metadata");
            return List.of();
        }

        List<PatternQuickUploadService.Target> cachedTargets = findCachedStellarTargets(
                player, networkNode.getGrid(), pattern, recipeTypeId);
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] cached target lookup type={} result={}",
                recipeTypeId, cachedTargets.size());
        if (!cachedTargets.isEmpty()) return cachedTargets;

        // 快取沒有命中時只做一次公開的 GTLCore 目標搜尋；它只讀取目標，不負責寫入。
        // 實際插入仍走 insertIntoStellarTarget，避免被外部 mixin/入口狀態影響。
        List<PatternQuickUploadService.Target> publicTargets = findPublicStellarTargets(
                player, networkNode, pattern, recipeTypeId);
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] public target lookup type={} result={}",
                recipeTypeId, publicTargets.size());
        if (!publicTargets.isEmpty()) {
            GTDishanhaiMod.LOGGER.debug("[JEIQuickEncode] cache miss recovered {} stellar target(s) for type {}",
                    publicTargets.size(), recipeTypeId);
        }
        return publicTargets;
    }

    private static List<PatternQuickUploadService.Target> findPublicStellarTargets(
            ServerPlayer player, IGridNode networkNode, ItemStack pattern, String recipeTypeId) {
        try {
            PatternQuickUploadService.SearchResult search = PatternQuickUploadService.findTargets(
                    player, networkNode, pattern);
            if (search == null || search.match() == null || search.match().candidates().isEmpty()) {
                GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] GTLCore public search returned no candidates type={} failure={}",
                        recipeTypeId, search == null ? "null" : search.failureMessage());
                return List.of();
            }
            GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] GTLCore public search candidates={} requestedType={}",
                    search.match().candidates().size(), recipeTypeId);
            Map<StellarTargetKey, PatternQuickUploadService.Target> unique = new LinkedHashMap<>();
            for (PatternQuickUploadService.Target candidate : search.match().candidates()) {
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] public candidate pos={} positions={} candidateType={} machineId={}",
                        candidate == null ? null : candidate.bufferPos(),
                        candidate == null ? null : candidate.bufferPositions(),
                        candidate == null ? null : candidate.recipeTypeId(),
                        candidate == null ? null : candidate.targetMachineId());
                if (candidate == null || candidate.recipeTypeId() == null
                        || !PatternRecipeTypeHelper.areRecipeTypeIdsEquivalent(
                                recipeTypeId, candidate.recipeTypeId().toString())) {
                    GTDishanhaiMod.LOGGER.info(
                            "[JEIQuickEncode] public candidate rejected requestedType={} candidateType={}",
                            recipeTypeId, candidate == null ? null : candidate.recipeTypeId());
                    continue;
                }
                PatternQuickUploadService.Target stellar = filterToStellarTarget(
                        player.getServer(), candidate, recipeTypeId);
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] public candidate filtered requestedType={} resultPos={}",
                        recipeTypeId, stellar == null ? null : stellar.bufferPos());
                if (stellar != null) {
                    cachePublicTarget(networkNode.getGrid(), player, stellar, recipeTypeId);
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

    private static PatternQuickUploadService.UploadResult safeInsertIntoStellarTarget(
            ServerPlayer player, ItemStack pattern, PatternQuickUploadService.Target target) {
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(pattern);
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] insert begin target={} positions={} type={} pattern={}",
                target == null ? null : target.bufferPos(),
                target == null ? null : target.bufferPositions(), recipeTypeId,
                pattern == null ? null : pattern.getItem());
        try {
            PatternQuickUploadService.UploadResult result = insertIntoStellarTarget(player, pattern, target);
            GTDishanhaiMod.LOGGER.info(
                    "[JEIQuickEncode] insert end target={} type={} result={} slot={}",
                    target == null ? null : target.bufferPos(), recipeTypeId,
                    result == null ? "null" : "success",
                    result == null ? -1 : result.slot());
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
            GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] grid size changed old={} new={}, invalidating stellar cache",
                    cache.gridSize, grid.size());
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
            GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] no cached target for type={}, forcing one loaded-multiblock rescan",
                    recipeTypeId);
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
        int machineClassCount = 0;
        int stellarCount = 0;
        for (Class<?> machineClass : grid.getMachineClasses()) {
            machineClassCount++;
            if (!PatternContainer.class.isAssignableFrom(machineClass)) continue;
            for (Object machine : grid.getActiveMachines(machineClass)) {
                stellarCount++;
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
                            stellarCount++;
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
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] stellar scan gridSize={} machineClasses={} activePatternContainers={} cachedTargets={} cached={}",
                cache.gridSize, machineClassCount, stellarCount, cache.targets.size(),
                cache.targets.keySet());
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
            GTDishanhaiMod.LOGGER.info(
                    "[JEIQuickEncode] skip stellar pos={} activeOnGrid={} visible={} formed={}",
                    stellar.getPos(), activeOnGrid, visible, formed);
            return;
        }
        List<com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController> controllers = new ArrayList<>();
        for (var controller : stellar.getControllers()) {
            if (controller != null) controllers.add(controller);
        }
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] stellar controllers pos={} classes={}",
                stellar.getPos(), controllers.stream().map(controller ->
                        controller.getClass().getName()).toList());
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
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] stellar candidate pos={} activeOnGrid={} visible={} formed={} hostTypes={}",
                stellar.getPos(), isActiveOnGrid(stellar, grid), stellar.isVisibleInTerminal(),
                hasFormedController(stellar), recipeTypeIds);
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
        Target target = new Target(levelKey, stellar.getPos().immutable(),
                targetName, null, Component.empty(), null, null,
                List.of(stellar.getPos().immutable()));
        cache.targets.put(key, new CachedStellarTarget(target, recipeTypeIds));
        int slotCount = -1;
        try {
            InternalInventory inventory = stellar.getTerminalPatternInventory();
            slotCount = inventory == null ? -1 : inventory.size();
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.warn(
                    "[JEIQuickEncode] cached stellar inventory read failed pos={} exception={} message={}",
                    stellar.getPos(), exception.getClass().getName(), exception.getMessage());
        }
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] cached stellar pos={} hostTypes={} slots={}",
                stellar.getPos(), recipeTypeIds, slotCount);
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
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(pattern);
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
                GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] insert skip unloaded position={}", position);
                continue;
            }
            inspectedPositions++;
            MetaMachine machine = MetaMachine.getMachine(level, position);
            if (!(machine instanceof RecipeTypePatternBufferPartMachine stellar)) {
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] insert skip non-stellar position={} machine={}",
                        position, machine == null ? "null" : machine.getClass().getName());
                continue;
            }
            Set<ResourceLocation> hostTypes = readHostRecipeTypeIds(stellar);
            boolean visible = stellar.isVisibleInTerminal();
            boolean supports = containsRecipeType(hostTypes, recipeTypeId);
            GTDishanhaiMod.LOGGER.info(
                    "[JEIQuickEncode] insert inspect position={} machine={} visible={} hostTypes={} requestedType={}",
                    position, machine.getClass().getName(), visible, hostTypes, recipeTypeId);
            if (!visible || !supports) continue;
            compatiblePositions++;
            InternalInventory inventory = stellar.getTerminalPatternInventory();
            if (inventory == null) {
                GTDishanhaiMod.LOGGER.warn(
                        "[JEIQuickEncode] insert inventory missing position={} type={}",
                        position, recipeTypeId);
                continue;
            }
            GTDishanhaiMod.LOGGER.info(
                    "[JEIQuickEncode] insert inventory position={} slots={}", position, inventory.size());
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.isItemValid(slot, pattern)) continue;
                validSlots++;
                ItemStack remainder = inventory.insertItem(slot, pattern.copy(), true);
                if (!remainder.isEmpty()) continue;
                inventory.insertItem(slot, pattern.copy(), false);
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] insert committed position={} slot={} validSlots={}",
                        position, slot, validSlots);
                Target resolvedTarget = new Target(target.levelKey(), position.immutable(),
                        target.targetName(), target.recipeTypeId(), target.recipeTypeName(),
                        target.targetIcon(), target.targetMachineId(), List.of(position.immutable()));
                return new PatternQuickUploadService.UploadResult(resolvedTarget, slot);
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
            GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] cached target dead level/loaded target={}",
                    cached.target().bufferPos());
            return TargetValidation.dead();
        }
        MetaMachine machine = MetaMachine.getMachine(level, cached.target().bufferPos());
        if (!(machine instanceof RecipeTypePatternBufferPartMachine stellar)
                || !isActiveOnGrid(stellar, grid)
                || !stellar.isVisibleInTerminal()
                || !hasFormedController(stellar)) {
            GTDishanhaiMod.LOGGER.info(
                    "[JEIQuickEncode] cached target validation failed target={} machine={} active={} visible={} formed={}",
                    cached.target().bufferPos(), machine == null ? "null" : machine.getClass().getName(),
                    machine instanceof RecipeTypePatternBufferPartMachine stellar && isActiveOnGrid(stellar, grid),
                    machine instanceof RecipeTypePatternBufferPartMachine stellar && stellar.isVisibleInTerminal(),
                    machine instanceof RecipeTypePatternBufferPartMachine stellar && hasFormedController(stellar));
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
                    cached.target().bufferPos(), PatternRecipeTypeHelper.readRecipeTypeId(pattern), exception);
            canAccept = false;
        }
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] cached target validation target={} currentTypes={} canAccept={}",
                cached.target().bufferPos(), currentTypeIds, canAccept);
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
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] filter candidate skip position={} loaded={}",
                        position, position != null && level.isLoaded(position));
                continue;
            }
            MetaMachine machine = MetaMachine.getMachine(level, position);
            if (machine instanceof RecipeTypePatternBufferPartMachine stellar) {
                Set<ResourceLocation> hostTypes = readHostRecipeTypeIds(stellar);
                boolean supports = containsRecipeType(hostTypes, recipeTypeId);
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] filter candidate position={} machine={} hostTypes={} requestedType={} supports={}",
                        position, machine.getClass().getName(), hostTypes, recipeTypeId, supports);
                if (supports) {
                    stellarPositions.add(position.immutable());
                }
            } else {
                GTDishanhaiMod.LOGGER.info(
                        "[JEIQuickEncode] filter candidate position={} machine={} rejected=not_stellar",
                        position, machine == null ? "null" : machine.getClass().getName());
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
            if (!removeFromStellarTarget(player, entry.pattern(), entry.target(), entry.slot())) {
                success = false;
                GTDishanhaiMod.LOGGER.error(
                        "[JEIQuickEncode] 回滚失败 target={} pos={} slot={}",
                        entry.target().targetName().getString(), entry.target().bufferPos(), entry.slot());
            }
        }
        return success;
    }

    private static boolean removeFromStellarTarget(ServerPlayer player, ItemStack pattern,
            Target target, int slot) {
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
        GTDishanhaiMod.LOGGER.info("[JEIQuickEncode] message key={} argCount={}",
                translationKey, args == null ? 0 : args.length);
        Component message = Component.translatable(translationKey, args);
        player.displayClientMessage(message, true);
        Component detail = Component.literal("[JEI快速编写] ").append(message);
        player.sendSystemMessage(detail);
    }

    private static void showSuccess(ServerPlayer player, boolean wholeRecipeType, int total,
            List<UploadedPattern> uploaded, int sdaCount, int inventoryCount,
            int skippedCount, String recipeTypeId) {
        Component message;
        if (skippedCount > 0) {
            message = Component.translatable("message.gt_shanhai.jei.quick_encode.direct_partial_success",
                    uploaded.size(), skippedCount, MIN_SDA_FALLBACK_PATTERNS);
        } else if (inventoryCount > 0) {
            message = Component.translatable(
                    "message.gt_shanhai.jei.quick_encode.inventory_fallback_success",
                    uploaded.size(), inventoryCount);
        } else if (sdaCount == 0) {
            message = Component.translatable(wholeRecipeType
                    ? "message.gt_shanhai.jei.quick_encode.batch_success"
                    : "message.gt_shanhai.jei.quick_encode.single_success", total);
        } else {
            message = Component.translatable("message.gt_shanhai.jei.quick_encode.partial_success",
                    total, uploaded.size(), sdaCount);
        }
        GTDishanhaiMod.LOGGER.info(
                "[JEIQuickEncode] completed total={} direct={} inventory={} sda={} skipped={} recipeType={}",
                total, uploaded.size(), inventoryCount, sdaCount, skippedCount, recipeTypeId);
        player.displayClientMessage(message, true);

        String targetDetails = describeTargets(uploaded);
        Component detail = Component.translatable(
                "message.gt_shanhai.jei.quick_encode.operation_detail",
                total, recipeTypeId == null ? "unknown" : recipeTypeId,
                targetDetails, sdaCount, inventoryCount, skippedCount);
        player.sendSystemMessage(detail);
    }

    private static String describeTargets(List<UploadedPattern> uploaded) {
        if (uploaded.isEmpty()) return "SDA";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (UploadedPattern entry : uploaded) {
            Target target = entry.target();
            String targetName = target.targetName() == null
                    ? "星律样板总成" : target.targetName().getString();
            String detail = targetName + " @ " + target.levelKey().location()
                    + " " + target.bufferPos();
            counts.put(detail, counts.getOrDefault(detail, 0) + 1);
        }
        List<String> details = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            details.add(entry.getKey() + " x" + entry.getValue());
        }
        return String.join("; ", details);
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
}
