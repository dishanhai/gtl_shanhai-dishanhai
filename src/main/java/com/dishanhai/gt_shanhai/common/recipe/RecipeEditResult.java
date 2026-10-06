package com.dishanhai.gt_shanhai.common.recipe;

public record RecipeEditResult(Status status, String message, long revision) {

    public enum Status {
        SUCCESS,
        CONFLICT,
        VALIDATION_ERROR,
        REBUILD_FAILED,
        PERMISSION_DENIED
    }
}
