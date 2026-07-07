package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * GTNH .mb 结构文件解析器
 *
 * 原出处: GTnotleisure / GTNH 多方块结构定义格式
 *
 * 每行 = 一个 Y 层, 逗号分隔的段为该层的不同切片。
 * GTCEu 的 aisle 要求所有层有相同数量的段（Z 深度）且同层段等长。
 * 此解析器自动补齐短层和短段。
 *
 * @author 山海恒长在 / dishanhai
 */
public class DShanhaiMBParser {

    public static BlockPattern parse(String mbPath,
            Function<Character, TraceabilityPredicate> charMapping) {
        return parse(new ResourceLocation(mbPath), charMapping);
    }

    public static BlockPattern parse(ResourceLocation mbPath,
            Function<Character, TraceabilityPredicate> charMapping) {
        List<String[]> layers = loadMB(mbPath);
        return buildPattern(layers, charMapping);
    }

    public static BlockPattern parseTransposed(ResourceLocation mbPath,
            Function<Character, TraceabilityPredicate> charMapping) {
        List<String[]> layers = transposeLayers(loadMB(mbPath));
        return buildPattern(layers, charMapping);
    }

    /** 转置后若宽度为偶数，补一列空白，让控制器可落在真实中心列 */
    public static BlockPattern parseTransposedCentered(ResourceLocation mbPath,
            Function<Character, TraceabilityPredicate> charMapping) {
        List<String[]> layers = transposeLayers(loadMB(mbPath));
        return buildPattern(ensureOddWidth(layers), charMapping);
    }

    public static BlockPattern parseSequence(List<ResourceLocation> mbPaths,
            Function<Character, TraceabilityPredicate> charMapping) {
        return parseSequence(mbPaths, charMapping, false,
            RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK);
    }

    public static BlockPattern parseSequenceReversed(List<ResourceLocation> mbPaths,
            Function<Character, TraceabilityPredicate> charMapping,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        List<String[]> layers = new ArrayList<>();
        for (ResourceLocation mbPath : mbPaths) {
            List<String[]> piece = loadMB(mbPath);
            Collections.reverse(piece);
            layers.addAll(piece);
        }
        return buildPattern(layers, charMapping, aisleDir, rowDir, colDir);
    }

    public static BlockPattern parseSequenceTransposed(List<ResourceLocation> mbPaths,
            Function<Character, TraceabilityPredicate> charMapping,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        return parseSequence(mbPaths, charMapping, true, aisleDir, rowDir, colDir);
    }

    public static BlockPattern parseSequence(List<ResourceLocation> mbPaths,
            Function<Character, TraceabilityPredicate> charMapping,
            boolean transpose,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        List<String[]> layers = new ArrayList<>();
        for (ResourceLocation mbPath : mbPaths) {
            List<String[]> piece = loadMB(mbPath);
            if (transpose) piece = transposeLayers(piece);
            layers.addAll(piece);
        }
        return buildPattern(layers, charMapping, aisleDir, rowDir, colDir);
    }

    public static BlockPattern parsePlacedSequence(List<PiecePlacement> pieces,
            Function<Character, TraceabilityPredicate> charMapping,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        Map<BlockPosKey, Character> cells = new LinkedHashMap<>();
        int minX = 0;
        int minY = 0;
        int minZ = 0;
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;

        for (PiecePlacement placement : pieces) {
            List<String[]> piece = loadMB(placement.path);
            if (placement.transpose) piece = transposeLayers(piece);

            for (int z = 0; z < piece.size(); z++) {
                String[] rows = piece.get(z);
                for (int y = 0; y < rows.length; y++) {
                    String row = rows[y];
                    for (int x = 0; x < row.length(); x++) {
                        char ch = row.charAt(x);
                        if (ch == ' ') continue;

                        int globalX = placement.horizontalOffset - x;
                        int globalY = placement.verticalOffset - y;
                        int globalZ = placement.depthOffset - z;
                        BlockPosKey key = new BlockPosKey(globalX, globalY, globalZ);
                        Character previous = cells.putIfAbsent(key, ch);
                        if (previous != null && previous != ch) {
                            throw new IllegalStateException("Conflicting MB chars at " + key
                                    + ": " + previous + " / " + ch);
                        }

                        minX = Math.min(minX, globalX);
                        minY = Math.min(minY, globalY);
                        minZ = Math.min(minZ, globalZ);
                        maxX = Math.max(maxX, globalX);
                        maxY = Math.max(maxY, globalY);
                        maxZ = Math.max(maxZ, globalZ);
                    }
                }
            }
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int depth = maxZ - minZ + 1;
        char[][][] volume = new char[depth][height][width];
        for (char[][] aisle : volume) {
            for (char[] row : aisle) {
                Arrays.fill(row, ' ');
            }
        }

        for (Map.Entry<BlockPosKey, Character> entry : cells.entrySet()) {
            BlockPosKey pos = entry.getKey();
            volume[pos.z - minZ][pos.y - minY][pos.x - minX] = entry.getValue();
        }

        List<String[]> layers = new ArrayList<>(depth);
        for (int z = 0; z < depth; z++) {
            String[] rows = new String[height];
            for (int y = 0; y < height; y++) {
                rows[y] = new String(volume[z][y]);
            }
            layers.add(rows);
        }

        return buildPattern(layers, charMapping, aisleDir, rowDir, colDir);
    }

    public static PiecePlacement piece(ResourceLocation path,
            int horizontalOffset, int verticalOffset, int depthOffset, boolean transpose) {
        return new PiecePlacement(path, horizontalOffset, verticalOffset, depthOffset, transpose);
    }

    public static final class PiecePlacement {
        private final ResourceLocation path;
        private final int horizontalOffset;
        private final int verticalOffset;
        private final int depthOffset;
        private final boolean transpose;

        private PiecePlacement(ResourceLocation path,
                int horizontalOffset, int verticalOffset, int depthOffset, boolean transpose) {
            this.path = path;
            this.horizontalOffset = horizontalOffset;
            this.verticalOffset = verticalOffset;
            this.depthOffset = depthOffset;
            this.transpose = transpose;
        }
    }

    private record BlockPosKey(int x, int y, int z) {}

    /** 直接接受 Java String[][] 数组（已 transposed 的 GTNH 格式） */
    public static BlockPattern parseArray(String[][] data,
            Function<Character, TraceabilityPredicate> charMapping,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        List<String[]> layers = new ArrayList<>();
        for (String[] layer : data) layers.add(layer);
        return buildPattern(layers, charMapping, aisleDir, rowDir, colDir);
    }

    /** 直接解析内存中的 mb 格式字符串，无需文件 */
    public static BlockPattern parseRaw(String mbData,
            Function<Character, TraceabilityPredicate> charMapping) {
        return parseRaw(mbData, charMapping, false);
    }

    /** 直接解析内存中的 mb 格式字符串，可选 transpose（交换 Y↔Z，适配 GTNH StructureLib） */
    public static BlockPattern parseRaw(String mbData,
            Function<Character, TraceabilityPredicate> charMapping, boolean transpose) {
        List<String[]> layers = new ArrayList<>();
        for (String line : mbData.split("\n")) {
            if (line.isEmpty()) continue;
            layers.add(line.split(",", -1));
        }
        if (transpose) {
            layers = transposeLayers(layers);
        }
        return buildPattern(layers, charMapping);
    }

    /** +方向参数 */
    public static BlockPattern parseRaw(String mbData,
            Function<Character, TraceabilityPredicate> charMapping,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        List<String[]> layers = new ArrayList<>();
        for (String line : mbData.split("\n")) {
            if (line.isEmpty()) continue;
            layers.add(line.split(",", -1));
        }
        return buildPattern(layers, charMapping, aisleDir, rowDir, colDir);
    }

    /** 交换 Y/Z 维度，模拟 GTNH StructureLib.transpose() */
    private static List<String[]> transposeLayers(List<String[]> src) {
        src = normalizeLayers(src);
        int srcY = src.size();
        int srcZ = src.get(0).length;
        List<String[]> dst = new ArrayList<>(srcZ);
        for (int z = 0; z < srcZ; z++) {
            String[] newLayer = new String[srcY];
            for (int y = 0; y < srcY; y++) {
                newLayer[y] = src.get(y)[z];
            }
            dst.add(newLayer);
        }
        return dst;
    }

    private static List<String[]> normalizeLayers(List<String[]> src) {
        int maxDepth = 0;
        int maxWidth = 0;
        for (String[] layer : src) {
            maxDepth = Math.max(maxDepth, layer.length);
            for (String row : layer) maxWidth = Math.max(maxWidth, row.length());
        }
        List<String[]> dst = new ArrayList<>(src.size());
        for (String[] layer : src) {
            String[] copy = new String[maxDepth];
            for (int i = 0; i < maxDepth; i++) {
                String row = i < layer.length ? layer[i] : "";
                while (row.length() < maxWidth) row = row + " ";
                copy[i] = row;
            }
            dst.add(copy);
        }
        return dst;
    }

    private static List<String[]> ensureOddWidth(List<String[]> src) {
        int maxWidth = 0;
        for (String[] layer : src) {
            for (String row : layer) maxWidth = Math.max(maxWidth, row.length());
        }
        if ((maxWidth & 1) == 1) return src;
        List<String[]> dst = new ArrayList<>(src.size());
        for (String[] layer : src) {
            String[] copy = new String[layer.length];
            for (int i = 0; i < layer.length; i++) copy[i] = layer[i] + " ";
            dst.add(copy);
        }
        return dst;
    }

    // ═══════════════════════════════════════════════════════════════
    // 文件加载
    // ═══════════════════════════════════════════════════════════════

    private static List<String[]> loadMB(ResourceLocation path) {
        List<String[]> layers = new ArrayList<>();
        String filePath = "/assets/" + path.getNamespace()
                + "/multiblock/" + path.getPath().replace("multiblock/", "") + ".mb";
        try (InputStream is = DShanhaiMBParser.class.getResourceAsStream(filePath)) {
            if (is == null) {
                throw new FileNotFoundException("MB file not found: " + filePath);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                layers.add(line.split(",", -1));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load MB: " + path, e);
        }
        return layers;
    }

    // ═══════════════════════════════════════════════════════════════
    // 构建 BlockPattern
    // ═══════════════════════════════════════════════════════════════

    /** 直接解析内存中的 mb 格式字符串，可选 transpose 和方向 */
    public static BlockPattern parseRaw(String mbData,
            Function<Character, TraceabilityPredicate> charMapping,
            boolean transpose, boolean reverseZ,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {
        List<String[]> layers = new ArrayList<>();
        for (String line : mbData.split("\n")) {
            if (line.isEmpty()) continue;
            layers.add(line.split(",", -1));
        }
        if (transpose) layers = transposeLayers(layers);
        if (reverseZ) layers = reverseZ(layers);
        return buildPattern(layers, charMapping, aisleDir, rowDir, colDir);
    }

    private static List<String[]> reverseZ(List<String[]> src) {
        List<String[]> dst = new ArrayList<>(src.size());
        for (String[] layer : src) {
            int n = layer.length;
            String[] rev = new String[n];
            for (int i = 0; i < n; i++) rev[i] = layer[n - 1 - i];
            dst.add(rev);
        }
        return dst;
    }

    private static BlockPattern buildPattern(List<String[]> layers,
            Function<Character, TraceabilityPredicate> mapping) {
        return buildPattern(layers, mapping,
            RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK);
    }

    private static BlockPattern buildPattern(List<String[]> layers,
            Function<Character, TraceabilityPredicate> mapping,
            RelativeDirection aisleDir, RelativeDirection rowDir, RelativeDirection colDir) {

        // 1. 计算最大段数（Z 深度）和每段最大宽度
        int maxDepth = 0;
        int maxWidth = 0;
        for (String[] segs : layers) {
            maxDepth = Math.max(maxDepth, segs.length);
            for (String s : segs) {
                maxWidth = Math.max(maxWidth, s.length());
            }
        }

        // 2. 对所有层补齐到统一的 Z 深度和宽度
        List<String[]> normalized = new ArrayList<>();
        for (String[] segs : layers) {
            String[] padded = new String[maxDepth];
            for (int i = 0; i < maxDepth; i++) {
                if (i < segs.length) {
                    String s = segs[i];
                    // 补齐到最大宽度（右侧填充空格）
                    while (s.length() < maxWidth) s = s + " ";
                    padded[i] = s;
                } else {
                    // 缺失的段用全空格填充
                    padded[i] = " ".repeat(maxWidth);
                }
            }
            normalized.add(padded);
        }

        // 3. 收集所有字符并注册映射
        Set<Character> allChars = new LinkedHashSet<>();
        for (String[] segs : normalized) {
            for (String s : segs) {
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c != ' ') allChars.add(c);
                }
            }
        }
        FactoryBlockPattern builder = FactoryBlockPattern.start(aisleDir, rowDir, colDir);
        for (char c : allChars) {
            TraceabilityPredicate pred = mapping.apply(c);
            if (pred != null) {
                builder.where(c, pred);
            }
        }

        // 4. 逐层构建
        for (String[] segs : normalized) {
            builder.aisle(segs);
        }

        return builder.build();
    }
}
