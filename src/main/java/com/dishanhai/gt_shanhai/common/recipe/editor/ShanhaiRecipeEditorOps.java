package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class ShanhaiRecipeEditorOps {

    private static final AtomicLong REVISION = new AtomicLong();

    private final ShanhaiRecipeOverrideStore store;

    public ShanhaiRecipeEditorOps(ShanhaiRecipeOverrideStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public record Edit(ShanhaiRecipeBase base, String baseFingerprint) {}

    public record Result(Status status, String message, long revision) {
        public enum Status {
            SUCCESS,
            CONFLICT,
            VALIDATION_ERROR,
            REBUILD_FAILED,
            PERMISSION_DENIED
        }
    }

    public Result preview(Edit edit) {
        String validation = validationError(edit);
        if (validation != null) {
            return new Result(Result.Status.VALIDATION_ERROR, validation, REVISION.get());
        }
        return new Result(Result.Status.SUCCESS, "preview-ready", REVISION.get());
    }

    public Result commit(Edit edit) {
        return commitInternal(edit, false);
    }

    private Result commitInternal(Edit edit, boolean requireSnapshot) {
        return storeCommit(edit, requireSnapshot, false, null);
    }

    public Result commit(Edit edit, MinecraftServer server) {
        return commit(edit, server, false, null);
    }

    /**
     * Adds a recipe that has no original. Rejects an id that already exists.
     */
    public Result create(Edit edit, MinecraftServer server) {
        String validation = validationError(edit);
        if (validation != null) {
            return new Result(Result.Status.VALIDATION_ERROR, validation, REVISION.get());
        }
        String id = edit.base().recipeId();
        if (liveIdTaken(id)) {
            return new Result(Result.Status.VALIDATION_ERROR, "这个配方 id 已经存在", REVISION.get());
        }
        if (com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(
                ResourceLocation.tryParse(edit.base().recipeTypeId())) == null) {
            return new Result(Result.Status.VALIDATION_ERROR, "invalid-recipe-type", REVISION.get());
        }
        try {
            store.putCreated(edit.base(), edit.baseFingerprint(), "shanhai-recipe-editor");
        } catch (Exception e) {
            return new Result(Result.Status.REBUILD_FAILED, "override-write-failed", REVISION.get());
        }
        if (server == null) {
            return new Result(Result.Status.SUCCESS, "recipe-created", REVISION.incrementAndGet());
        }
        try {
            RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                    edit.base().recipeTypeId(),
                    RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            RecipeRebuildService.rebuildVanillaManager(
                    server, java.util.Set.of(edit.base().recipeTypeId()));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(
                    edit.base().recipeTypeId(), id);
            return new Result(Result.Status.SUCCESS, "recipe-created-and-rebuilt", report.revision());
        } catch (Throwable t) {
            return new Result(Result.Status.REBUILD_FAILED, "override-written-rebuild-failed", REVISION.get());
        }
    }

    private static ShanhaiRecipeBase withRecipeId(ShanhaiRecipeBase base, String recipeId) {
        return new ShanhaiRecipeBase(
                base.recipeTypeId(), recipeId, base.duration(), base.eut(),
                base.inputs(), base.outputs(), base.tickInputs(), base.tickOutputs(),
                base.conditions(), base.blastTemp());
    }

    /**
     * {@code keepOriginal} false replaces or removes the opened recipe.
     * True keeps it and writes {@code liveRecipeId} beside it.
     * A null or equal live id keeps the opened id, which is the in-place replace.
     */
    public Result commit(Edit edit, MinecraftServer server, boolean keepOriginal, String liveRecipeId) {
        Result stored = storeCommit(edit, true, keepOriginal, liveRecipeId);
        if (stored.status() != Result.Status.SUCCESS || server == null) return stored;
        try {
            RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                    edit.base().recipeTypeId(),
                    RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            RecipeRebuildService.rebuildVanillaManager(
                    server, java.util.Set.of(edit.base().recipeTypeId()));
            String live = canonicalLive(edit.base().recipeId(), liveRecipeId);
            Optional<ShanhaiRecipeOverrideStore.Entry> written =
                    store.find(edit.base().recipeTypeId(), live);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(
                    edit.base().recipeTypeId(), live);
            if (written.isPresent()
                    && !written.get().keepOriginal()
                    && !written.get().sourceRecipeId().equals(written.get().recipeId())) {
                com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(
                        edit.base().recipeTypeId(), written.get().sourceRecipeId());
            }
            return new Result(Result.Status.SUCCESS, "override-written-and-rebuilt", report.revision());
        } catch (Throwable t) {
            return new Result(Result.Status.REBUILD_FAILED, "override-written-rebuild-failed", REVISION.get());
        }
    }

    private Result storeCommit(Edit edit, boolean requireSnapshot, boolean keepOriginal, String liveRecipeId) {
        String validation = validationError(edit);
        if (validation != null) {
            return new Result(Result.Status.VALIDATION_ERROR, validation, REVISION.get());
        }
        ShanhaiRecipeBase current = currentBase(edit.base());
        if (current == null) {
            if (requireSnapshot) {
                return new Result(Result.Status.VALIDATION_ERROR, "recipe-not-found", REVISION.get());
            }
            // Keep the standalone/KubeJS API deterministic when no runtime
            // snapshot is installed; live packet commits always require one.
            if (!Objects.equals(ShanhaiRecipeFingerprint.of(edit.base()), edit.baseFingerprint())) {
                return new Result(Result.Status.CONFLICT, "base-fingerprint-mismatch", REVISION.get());
            }
        } else if (!Objects.equals(ShanhaiRecipeFingerprint.of(current), edit.baseFingerprint())) {
            return new Result(Result.Status.CONFLICT, "base-fingerprint-mismatch", REVISION.get());
        }
        String opened = edit.base().recipeId();
        String live = canonicalLive(opened, liveRecipeId);
        if (live.isEmpty()) {
            return new Result(Result.Status.VALIDATION_ERROR, "配方 id 不合法", REVISION.get());
        }
        Optional<ShanhaiRecipeOverrideStore.Entry> existing = store.find(edit.base().recipeTypeId(), opened);
        boolean openedAddition = existing
                .filter(entry -> !entry.sourceRecipeId().equals(entry.recipeId()))
                .isPresent();
        boolean openedCreated = existing.map(ShanhaiRecipeOverrideStore.Entry::created).orElse(false);
        String upstream = openedAddition ? existing.get().sourceRecipeId() : opened;
        if (keepOriginal && live.equals(upstream) && !openedCreated) {
            return new Result(Result.Status.VALIDATION_ERROR, "共存需要新的配方 id", REVISION.get());
        }
        if (!live.equals(opened) && liveIdTaken(live)) {
            return new Result(Result.Status.VALIDATION_ERROR, "这个配方 id 已经存在", REVISION.get());
        }
        try {
            if (openedCreated) {
                ShanhaiRecipeBase written = live.equals(opened) ? edit.base() : withRecipeId(edit.base(), live);
                store.putCreated(written, edit.baseFingerprint(), "shanhai-recipe-editor");
                if (!keepOriginal && !live.equals(opened)) {
                    store.remove(edit.base().recipeTypeId(), opened);
                }
            } else if (!keepOriginal && live.equals(opened) && !openedAddition) {
                store.put(edit.base(), edit.baseFingerprint(), "shanhai-recipe-editor");
            } else {
                store.putLinked(edit.base(), edit.baseFingerprint(), live, upstream,
                        keepOriginal, "shanhai-recipe-editor");
            }
            return new Result(Result.Status.SUCCESS, "override-written", REVISION.incrementAndGet());
        } catch (Exception e) {
            return new Result(Result.Status.REBUILD_FAILED, "override-write-failed", REVISION.get());
        }
    }

    public Result setIo(
            MinecraftServer server,
            GTRecipe target,
            JsonObject inputs,
            JsonObject outputs,
            JsonObject tickInputs,
            boolean rebuildIndex,
            boolean persist) {
        if (target == null) return invalidResult();
        ShanhaiRecipeBase source = ShanhaiRecipeBase.from(target);
        ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                source.recipeTypeId(), source.recipeId(), source.duration(), source.eut(),
                inputs == null ? source.inputs() : inputs,
                outputs == null ? source.outputs() : outputs,
                tickInputs == null ? source.tickInputs() : tickInputs,
                source.tickOutputs(),
                source.conditions());
        return applyBaseEdit(new Edit(edited, ShanhaiRecipeFingerprint.of(source)), server, rebuildIndex, persist);
    }

    public Result applyEdits(
            MinecraftServer server,
            GTRecipe target,
            JsonObject inputs,
            JsonObject outputs,
            JsonObject tickInputs,
            Integer duration,
            Long eut,
            JsonArray conditions,
            boolean rebuildIndex,
            boolean persist) {
        if (target == null) return invalidResult();
        ShanhaiRecipeBase source = ShanhaiRecipeBase.from(target);
        ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                source.recipeTypeId(), source.recipeId(),
                duration == null ? source.duration() : duration,
                eut == null ? source.eut() : eut,
                inputs == null ? source.inputs() : inputs,
                outputs == null ? source.outputs() : outputs,
                tickInputs == null ? source.tickInputs() : tickInputs,
                source.tickOutputs(),
                conditions == null ? source.conditions() : conditions);
        return applyBaseEdit(new Edit(edited, ShanhaiRecipeFingerprint.of(source)), server, rebuildIndex, persist);
    }

    public Result removeRecipe(MinecraftServer server, GTRecipe target, boolean persist) {
        if (target == null || target.getId() == null) return invalidResult();
        boolean removed = DShanhaiRecipeModifierAPI.setRecipeEnabled(target.getId().toString(), false);
        if (!removed) {
            return new Result(Result.Status.VALIDATION_ERROR, "recipe-not-found", REVISION.get());
        }
        if (server != null && target.recipeType != null && target.recipeType.registryName != null) {
            String typeId = target.recipeType.registryName.toString();
            RecipeRebuildService.rebuildType(
                    typeId,
                    RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(
                    typeId, target.getId().toString());
        }
        return new Result(Result.Status.SUCCESS, persist ? "recipe-removed-and-persisted" : "recipe-removed",
                REVISION.incrementAndGet());
    }

    public Result restore(MinecraftServer server, GTRecipe target) {
        if (target == null || target.getId() == null) return invalidResult();
        boolean restored = DShanhaiRecipeModifierAPI.setRecipeEnabled(target.getId().toString(), true);
        if (!restored) return new Result(Result.Status.VALIDATION_ERROR, "recipe-not-disabled", REVISION.get());
        if (server != null && target.recipeType != null && target.recipeType.registryName != null) {
            String typeId = target.recipeType.registryName.toString();
            RecipeRebuildService.rebuildType(
                    typeId,
                    RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(
                    typeId, target.getId().toString());
        }
        return new Result(Result.Status.SUCCESS, "recipe-restored", REVISION.incrementAndGet());
    }

    public Result rollback(String recipeId) {
        try {
            store.remove(recipeId);
            return new Result(Result.Status.SUCCESS, "override-removed", REVISION.incrementAndGet());
        } catch (Exception e) {
            return new Result(Result.Status.REBUILD_FAILED, "override-remove-failed", REVISION.get());
        }
    }

    public Result rollback(String recipeId, MinecraftServer server) {
        Optional<ShanhaiRecipeOverrideStore.Entry> existing = store.find(recipeId);
        Result removed = rollback(recipeId);
        if (removed.status() != Result.Status.SUCCESS || server == null) {
            return removed;
        }
        String typeId = existing.flatMap(entry -> {
            if (entry.payload().has("recipeTypeId")) {
                return Optional.ofNullable(entry.payload().get("recipeTypeId").getAsString());
            }
            return Optional.empty();
        }).orElseGet(() -> typeIdFor(recipeId));
        if (typeId.isEmpty()) return removed;
        try {
            RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                    typeId, RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            RecipeRebuildService.rebuildVanillaManager(server, java.util.Set.of(typeId));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(typeId, recipeId);
            return new Result(Result.Status.SUCCESS, "override-removed-and-rebuilt", report.revision());
        } catch (Throwable t) {
            return new Result(Result.Status.REBUILD_FAILED, "override-removed-rebuild-failed", REVISION.get());
        }
    }

    /**
     * Explicit restore entry point used by the holo editor. Unlike the old
     * rollback path it also rebuilds when the override file is already absent;
     * this is required after a delete/restore cycle where the live lookup no
     * longer contains the recipe.
     */
    public Result restore(String recipeId, MinecraftServer server) {
        return rollback(recipeId, server);
    }

    public Result undo(String recipeId) {
        return rollback(recipeId);
    }

    private static String typeIdFor(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return "";
        for (String typeId : RecipeOriginalSnapshotStore.typeIds()) {
            if (RecipeOriginalSnapshotStore.copyOf(typeId, recipeId) != null) return typeId;
        }
        return "";
    }

    private static boolean valid(Edit edit) {
        return edit != null
                && edit.base() != null
                && !edit.base().recipeTypeId().isEmpty()
                && !edit.base().recipeId().isEmpty()
                && edit.baseFingerprint() != null
                && !edit.baseFingerprint().isEmpty();
    }

    private String validationError(Edit edit) {
        if (!valid(edit)) return "invalid-edit";
        return ShanhaiRecipeEditorValidation.validateBase(edit.base());
    }

    private ShanhaiRecipeBase currentBase(ShanhaiRecipeBase requested) {
        if (requested == null) return null;
        GTRecipe effective = RecipeRebuildService.editableOf(
                requested.recipeTypeId(), requested.recipeId());
        return effective == null ? null : ShanhaiRecipeBase.from(effective);
    }

    private static String canonicalLive(String opened, String liveRecipeId) {
        if (liveRecipeId == null || liveRecipeId.isBlank()) return opened == null ? "" : opened;
        ResourceLocation parsed = ResourceLocation.tryParse(liveRecipeId.trim());
        return parsed == null ? "" : parsed.toString();
    }

    private boolean liveIdTaken(String liveId) {
        if (store.find(liveId).isPresent()) return true;
        if (DShanhaiRecipeModifierAPI.findRecipeTypeById(liveId) != null) return true;
        for (String typeId : RecipeOriginalSnapshotStore.typeIds()) {
            if (RecipeOriginalSnapshotStore.copyOf(typeId, liveId) != null) return true;
        }
        return false;
    }

    private Result applyBaseEdit(Edit edit, MinecraftServer server, boolean rebuildIndex, boolean persist) {
        if (!valid(edit)) return invalidResult();
        Result result = commitInternal(edit, server != null);
        if (result.status() != Result.Status.SUCCESS || server == null || !rebuildIndex) return result;
        try {
            RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                    edit.base().recipeTypeId(),
                    RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            RecipeRebuildService.rebuildVanillaManager(server, java.util.Set.of(edit.base().recipeTypeId()));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipeToAll(
                    edit.base().recipeTypeId(), edit.base().recipeId());
            return new Result(Result.Status.SUCCESS,
                    persist ? "override-written-and-rebuilt" : "override-written-and-rebuilt-session",
                    report.revision());
        } catch (Throwable ignored) {
            return new Result(Result.Status.REBUILD_FAILED, "override-written-rebuild-failed", REVISION.get());
        }
    }

    private static Result invalidResult() {
        return new Result(Result.Status.VALIDATION_ERROR, "invalid-edit", REVISION.get());
    }
}
