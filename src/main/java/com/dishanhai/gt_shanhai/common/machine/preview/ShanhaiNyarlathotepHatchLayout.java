package com.dishanhai.gt_shanhai.common.machine.preview;

/** 山海：只在記憶體標記主機面板旁的分子機械方塊，原始 MB 保持不變。 */
public final class ShanhaiNyarlathotepHatchLayout {
    private ShanhaiNyarlathotepHatchLayout() {}

    public static String withLocalHatches(String mb) {
        String[][] slices = mb.lines().map(line -> line.split(",", -1)).toArray(String[][]::new);
        int controllerX = -1, controllerY = -1, controllerZ = -1;
        for (int z = 0; z < slices.length; z++) {
            for (int y = 0; y < slices[z].length; y++) {
                for (int x = 0; x < slices[z][y].length(); x++) {
                    char symbol = slices[z][y].charAt(x);
                    if (symbol == 'b') throw new IllegalArgumentException("Reserved hatch symbol already in source MB");
                    if (symbol != 'a') continue;
                    if (controllerZ >= 0) throw new IllegalArgumentException("Multiple Nyarlathotep controllers");
                    controllerX = x;
                    controllerY = y;
                    controllerZ = z;
                }
            }
        }
        if (controllerZ < 0) throw new IllegalArgumentException("Missing Nyarlathotep controller");
        int marked = 0;
        for (int y = Math.max(0, controllerY - 2); y <= Math.min(slices[controllerZ].length - 1, controllerY + 2); y++) {
            char[] row = slices[controllerZ][y].toCharArray();
            for (int x = Math.max(0, controllerX - 2); x <= Math.min(row.length - 1, controllerX + 2); x++) {
                if (row[x] == 'A') { row[x] = 'b'; marked++; }
            }
            slices[controllerZ][y] = new String(row);
        }
        if (marked != 20) throw new IllegalArgumentException("Expected 20 controller-local molecular casings, found " + marked);
        StringBuilder mapped = new StringBuilder(mb.length());
        for (String[] slice : slices) mapped.append(String.join(",", slice)).append('\n');
        return mapped.toString();
    }
}
