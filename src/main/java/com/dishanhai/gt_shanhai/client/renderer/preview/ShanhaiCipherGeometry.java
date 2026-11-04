package com.dishanhai.gt_shanhai.client.renderer.preview;

import java.util.ArrayList;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：不依賴字型的未知密文與祭儀文字排列。 */
public final class ShanhaiCipherGeometry {
    public static final int MAX_GLYPHS = 168;
    public static final int MAX_QUADS = 3024;
    public record Frame(List<Quad> quads, int glyphs) {}
    // 每個字形為 5–9 筆，保留直幹、叉枝與封閉菱形，避免依賴缺字字型。
    private static final int[] ALPHABET = {0x003f,0x00db,0x0f01,0x0f61,0x2075,0x10c7,0x053b,0x0a5d,
            0x20ff,0x1335,0x0f85,0x225b,0x1593,0x0ae7,0x31c5,0x2a69};
    private static final double[][] STROKES = {
            {0,-.5,0,.5},{-.4,.35,0,0},{0,0,.4,.35},{-.35,-.45,0,-.2},{0,-.2,.35,-.45},
            {-.3,.5,.3,.5},{-.35,.05,.35,.05},{.3,-.1,-.3,-.1},
            {0,.48,-.28,.2},{-.28,.2,0,-.08},{0,-.08,.28,.2},{.28,.2,0,.48},
            {-.4,-.35,.4,.35},{-.25,-.5,.25,-.5}};
    private ShanhaiCipherGeometry() {}
    public static Frame frame(double seconds, long seed) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite cipher time");
        var out = new ArrayList<Quad>(2400);
        int glyphs = 0;
        int instance = mix((int)(seed ^ (seed >>> 32)));
        Point axisX = new Point(1,0,0);
        for (int halo = 0; halo < 2; halo++) {
            int count = halo == 0 ? 40 : 32;
            Point planeV = halo == 0 ? new Point(0,1,0) : new Point(0,.48,Math.sqrt(1-.48*.48));
            double turn = seconds*(halo == 0 ? .055 : -.04);
            for (int index = 0; index < count; index++) {
                double cycle = seconds/9.5 + phase(mix(instance+halo*71)) - index*.018;
                double angle = index*Math.PI*2/count+turn;
                Point radial = axisX.scale(Math.cos(angle)).add(planeV.scale(Math.sin(angle)));
                Point tangent = axisX.scale(-Math.sin(angle)).add(planeV.scale(Math.cos(angle)));
                double radius = (halo == 0 ? 21 : 23.2)+.12*Math.sin(seconds*.6+index);
                Point center = ShanhaiBlackSunGeometry.CENTER.add(radial.scale(radius));
                glyphs += glyph(out,center,tangent,radial,2.4,cycle,mix(instance+halo*101+index),1);
            }
        }
        // 密文直接貼著正式螺旋向前流動；兩端淡出，避免循環回起點時跳字。
        for (int strand = 0; strand < 3; strand++) {
            for (int index = 0; index < 16; index++) {
                double t = fract(index/16.0+seconds*.045+strand/48.0);
                var pose = ShanhaiRitualPaths.helix(t,seconds,strand);
                double cycle = seconds/8.5+phase(mix(instance+strand*137))-index*.055;
                double weight = ShanhaiRitualPaths.textWeight(seconds,t,strand)*ShanhaiRitualPaths.edgeFade(t);
                glyphs += glyph(out,pose.center(),pose.tangent(),pose.outward(),2.6,cycle,
                        mix(instance+strand*83+index*23),weight);
            }
        }
        // 六道力場環各自轉動，字形切線／法向與能流完全共用。
        for (int hoop = 0; hoop < 6; hoop++) {
            for (int index = 0; index < 8; index++) {
                double t = index/8.0;
                var pose = ShanhaiRitualPaths.hoop(t,seconds,hoop);
                double cycle = seconds/9.5+phase(mix(instance+hoop*109))-index*.06;
                glyphs += glyph(out,pose.center(),pose.tangent(),pose.outward(),2.6,cycle,
                        mix(instance+hoop*173+index*31),ShanhaiRitualPaths.textWeight(seconds,t,hoop+3));
            }
        }
        if (glyphs > MAX_GLYPHS || out.size() > MAX_QUADS) throw new IllegalStateException("Cipher budget exceeded");
        return new Frame(List.copyOf(out),glyphs);
    }

    /** 同包通道環復用字母表、雙層筆畫與生命週期淡出。 */
    static int glyph(List<Quad> out, Point center, Point u, Point v, double size, double cycle, int seed, double weight) {
        double life = cycle-Math.floor(cycle);
        float alpha = (float)(.7*Math.pow(Math.sin(Math.PI*life),2)*weight);
        if (alpha < .006) return 0;
        int page = (int)Math.floor(cycle);
        int code = mix(seed+page*0x9e3779b9);
        int mask = ALPHABET[Math.floorMod(code,ALPHABET.length)];
        boolean gold = (code & 7) == 0;
        for (int stroke = 0; stroke < STROKES.length; stroke++) {
            if ((mask & (1 << stroke)) == 0) continue;
            double[] line = STROKES[stroke];
            Point a = center.add(u.scale(line[0]*size)).add(v.scale(line[1]*size));
            Point b = center.add(u.scale(line[2]*size)).add(v.scale(line[3]*size));
            Point width = u.scale(-(line[3]-line[1])).add(v.scale(line[2]-line[0])).unit();
            strip(out,a,b,width.scale(size*.075),.65f,gold ? .4f : .18f,gold ? .2f : 1,alpha*.28f);
            strip(out,a,b,width.scale(size*.022),gold ? 1 : .88f,gold ? .75f : .58f,gold ? .38f : 1,alpha);
        }
        return 1;
    }

    private static int mix(int value) {
        value = (value ^ (value >>> 16))*0x7feb352d;
        value = (value ^ (value >>> 15))*0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static double phase(int seed) { return (seed & 0xffff)/65536.0; }
    private static double fract(double n) { return n-Math.floor(n); }
}
