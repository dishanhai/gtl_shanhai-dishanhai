package com.dishanhai.gt_shanhai.common.saveedit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShanhaiSaveEditTextTest {
    @Test
    void entityLabelsResolveAfterTheModelHasAlreadyLoaded() {
        ShanhaiSaveEditText.install((key, args) -> key);
        var input = ShanhaiSaveEditEntityUi.inputs().get(0);
        String key = input.label();
        try {
            ShanhaiSaveEditText.install((name, args) -> "translated:" + name);
            assertEquals("translated:" + key, input.label());
        } finally {
            ShanhaiSaveEditText.install((name, args) -> name);
        }
    }

    @Test
    void levelLabelsResolveAfterTheStaticFieldListHasLoaded() {
        ShanhaiSaveEditText.install((key, args) -> key);
        var field = ShanhaiLevelDataSpec.FIELDS.get(0);
        String key = field.label();
        try {
            ShanhaiSaveEditText.install((name, args) -> "translated:" + name);
            assertEquals("translated:" + key, field.label());
        } finally {
            ShanhaiSaveEditText.install((name, args) -> name);
        }
    }
}
