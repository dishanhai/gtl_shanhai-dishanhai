package com.dishanhai.gt_shanhai.integration.jei;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeButtonSvgMeshTest {
    @Test
    void sixToolbarIconsAreDistinctBoundedAndFrontFacing() {
        var names = List.of("quick_encode", "cloud_upload", "recipe_batch",
                "bookmark", "byproduct_upload", "edit_recipe");
        var geometry = new HashSet<List<RecipeButtonSvgMesh.Quad>>();
        for (String name : names) {
            var mesh = RecipeButtonSvgMesh.load(name);
            assertEquals(name.equals("edit_recipe") ? 0x543984 : 0x20252e, mesh.rgb(), name);
            assertFalse(mesh.quads().isEmpty(), name);
            assertTrue(geometry.add(mesh.quads()), name);
            for (var quad : mesh.quads()) {
                float areaSign = (quad.b().x() - quad.a().x()) * (quad.c().y() - quad.a().y())
                        - (quad.b().y() - quad.a().y()) * (quad.c().x() - quad.a().x());
                assertTrue(areaSign < 0, name + " must match GuiGraphics.fill winding");
                for (var p : List.of(quad.a(), quad.b(), quad.c(), quad.d())) {
                    assertTrue(p.x() > 1 && p.x() < 15 && p.y() > 1 && p.y() < 15,
                            name + " must have a clear margin inside the icon");
                }
            }
        }
    }

    @Test
    void strokeCompilerRejectsUnsupportedShapesAndOutOfBoundsGeometry() {
        assertThrows(IOException.class, () -> parse("<image href=\"x.png\"/>"));
        assertThrows(IOException.class, () -> parse("<polyline points=\"0,0 13,13\"/>"));
    }

    @Test
    void twoPointSegmentHasRoundCapsAndScalesFromItsViewBox() throws Exception {
        var quads = parse("<polyline points=\"3,8 13,8\"/>").quads();
        assertTrue(quads.size() > 1);
        assertEquals(2, quads.stream().flatMap(q -> List.of(q.a(), q.b(), q.c(), q.d()).stream())
                .map(RecipeButtonSvgMesh.Point::x).min(Float::compare).orElseThrow(), 0.0001);
        assertEquals(14, quads.stream().flatMap(q -> List.of(q.a(), q.b(), q.c(), q.d()).stream())
                .map(RecipeButtonSvgMesh.Point::x).max(Float::compare).orElseThrow(), 0.0001);
    }

    private static RecipeButtonSvgMesh.IconMesh parse(String body) throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="none"
                     stroke="#20252e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                """ + body + "</svg>";
        return RecipeButtonSvgMesh.parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }
}
