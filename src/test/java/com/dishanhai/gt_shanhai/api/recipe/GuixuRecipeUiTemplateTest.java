package com.dishanhai.gt_shanhai.api.recipe;

// Author: dishanhai
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：以遊戲 NBT 讀取器驗證歸墟配方 UI 沒有漏槽、重疊或越界。 */
class GuixuRecipeUiTemplateTest {
    @Test
    void matterDecompressionExposesEveryMasterSlot() throws Exception {
        checkTemplate("guixu_matter_decompression");
    }

    @Test
    void energyExtractionExposesEveryMasterSlot() throws Exception {
        checkTemplate("guixu_energy_extraction");
    }

    @Test
    void paradigmSimplificationExposesEveryMasterSlot() throws Exception {
        checkTemplate("guixu_paradigm_simplification");
    }

    private void checkTemplate(String type) throws Exception {
        Path file = Path.of("src/main/resources/assets/gtceu/ui/recipe_type", type + ".rtui");
        assertTrue(Files.isRegularFile(file), "缺少歸墟配方 UI：" + file);
        CompoundTag nbt;
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            nbt = NbtIo.read(input);
            assertEquals(-1, input.read(), "NBT 後不應有未解析資料");
        }
        assertNotNull(nbt);
        assertEquals("gtceu:" + type, nbt.getString("recipe_type"));
        assertTrue(nbt.contains("resources", Tag.TAG_COMPOUND));
        CompoundTag root = nbt.getCompound("root");
        CompoundTag size = root.getCompound("size");
        int width = size.getInt("width"), height = size.getInt("height");
        assertEquals(298, width);
        assertEquals(240, height);
        var children = root.getList("children", Tag.TAG_COMPOUND);
        assertEquals(190, children.size(), "189 個完整 IO 槽及一個進度元件");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < children.size(); i++) {
            CompoundTag data = children.getCompound(i).getCompound("data");
            String id = data.getString("id");
            assertFalse(id.isEmpty());
            assertTrue(ids.add(id), "重複元件索引：" + id);
            int[] rect = rectangle(data);
            assertTrue(rect[0] >= 0 && rect[1] >= 0 && rect[2] > 0 && rect[3] > 0, id);
            assertTrue(rect[0] + rect[2] <= width && rect[1] + rect[3] <= height,
                    "元件越界：" + id);
            for (int j = 0; j < i; j++) {
                CompoundTag other = children.getCompound(j).getCompound("data");
                int[] b = rectangle(other);
                assertFalse(rect[0] < b[0] + b[2] && b[0] < rect[0] + rect[2]
                                && rect[1] < b[1] + b[3] && b[1] < rect[1] + rect[3],
                        "元件重疊：" + id + " / " + other.getString("id"));
            }
        }
        Set<String> expected = new HashSet<>(Set.of("progress"));
        for (var entry : Map.of("item_in", 9, "item_out", 144, "fluid_in", 4, "fluid_out", 32).entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) expected.add(entry.getKey() + "_" + i);
        }
        assertEquals(expected, ids, "每種 IO 索引必須唯一且完整");
    }

    private int[] rectangle(CompoundTag widget) {
        CompoundTag pos = widget.getCompound("selfPosition");
        CompoundTag size = widget.getCompound("size");
        return new int[]{pos.getInt("x"), pos.getInt("y"), size.getInt("width"), size.getInt("height")};
    }
}
