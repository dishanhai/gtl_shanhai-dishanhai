package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
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
        if (!valid(edit)) return new Result(Result.Status.VALIDATION_ERROR, "invalid-edit", REVISION.get());
        return new Result(Result.Status.SUCCESS, "preview-ready", REVISION.get());
    }

    public Result commit(Edit edit) {
        if (!valid(edit)) return new Result(Result.Status.VALIDATION_ERROR, "invalid-edit", REVISION.get());
        String currentFingerprint = ShanhaiRecipeFingerprint.of(edit.base());
        if (!Objects.equals(currentFingerprint, edit.baseFingerprint())) {
            return new Result(Result.Status.CONFLICT, "base-fingerprint-mismatch", REVISION.get());
        }
        Optional<ShanhaiRecipeOverrideStore.Entry> existing = store.find(edit.base().recipeId());
        if (existing.isPresent()
                && !Objects.equals(existing.get().baseFingerprint(), edit.baseFingerprint())) {
            return new Result(Result.Status.CONFLICT, "stored-fingerprint-mismatch", REVISION.get());
        }
        try {
            store.put(edit.base(), edit.baseFingerprint(), "shanhai-recipe-editor");
            return new Result(Result.Status.SUCCESS, "override-written", REVISION.incrementAndGet());
        } catch (Exception e) {
            return new Result(Result.Status.REBUILD_FAILED, "override-write-failed", REVISION.get());
        }
    }

    public Result commit(Edit edit, MinecraftServer server) {
        Result stored = commit(edit);
        if (stored.status() != Result.Status.SUCCESS || server == null) return stored;
        try {
            RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                    edit.base().recipeTypeId(),
                    RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            RecipeRebuildService.rebuildVanillaManager(
                    server, java.util.Set.of(edit.base().recipeTypeId()));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
            return new Result(Result.Status.SUCCESS, "override-written-and-rebuilt", report.revision());
        } catch (Throwable t) {
            return new Result(Result.Status.REBUILD_FAILED, "override-written-rebuild-failed", REVISION.get());
        }
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
        if (removed.status() != Result.Status.SUCCESS || server == null || existing.isEmpty()) {
            return removed;
        }
        String typeId = existing.get().payload().has("recipeTypeId")
                ? existing.get().payload().get("recipeTypeId").getAsString() : "";
        if (typeId.isEmpty()) return removed;
        try {
            RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                    typeId, RecipeRebuildService.RebuildReason.EDITOR_COMMIT);
            RecipeRebuildService.rebuildVanillaManager(server, java.util.Set.of(typeId));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
            return new Result(Result.Status.SUCCESS, "override-removed-and-rebuilt", report.revision());
        } catch (Throwable t) {
            return new Result(Result.Status.REBUILD_FAILED, "override-removed-rebuild-failed", REVISION.get());
        }
    }

    public Result undo(String recipeId) {
        return rollback(recipeId);
    }

    private static boolean valid(Edit edit) {
        return edit != null
                && edit.base() != null
                && !edit.base().recipeTypeId().isEmpty()
                && !edit.base().recipeId().isEmpty()
                && edit.baseFingerprint() != null
                && !edit.baseFingerprint().isEmpty();
    }
}
