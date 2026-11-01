package com.dishanhai.gt_shanhai.client.renderer.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：預覽黑日的純數學幾何。 */
public final class ShanhaiBlackSunGeometry {
    public static final Point CENTER = new Point(0, 36, -24);
    public static final double RADIUS = 9;
    public static final int MAX_QUADS = 6144;
    public static final int MAX_STREAMS = 12;
    public record Frame(List<Quad> opaque, List<Quad> glow, int streams) {}
    private static final List<Quad> CORE = core();

    private ShanhaiBlackSunGeometry() {}

    public static Frame frame(double seconds, long seed, List<Node> nodes, IntPredicate present) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite black sun time");
        var active = new ArrayList<Node>();
        boolean left = false, right = false;
        for (int i = 0; i < nodes.size(); i++) {
            if (!present.test(i)) continue;
            Node node = nodes.get(i);
            active.add(node);
            left |= node.x() < -32;
            right |= node.x() > 32;
        }
        if (active.size() < 24 || !left || !right) return new Frame(List.of(), List.of(), 0);
        double clock = seconds + Math.floorMod(seed ^ (seed >>> 32), 65536) / 65536.0 * 17;
        var glow = new ArrayList<Quad>(4500);
        Point axisX = new Point(1,0,0), axisY = new Point(0,1,0), axisZ = new Point(0,0,1);
        // 三個方向的破裂日冕；從側面或俯視也保有黑日輪廓。
        ring(glow, CENTER, axisX, axisY, 9.8, 9.8, 0.65, clock, 0.8f,0.3f,1,0.65f, true);
        ring(glow, CENTER, axisX, axisZ, 10.3,10.3,0.75,-clock*0.8,1,0.55f,0.18f,0.52f,true);
        ring(glow, CENTER, axisY, axisZ, 11.2,11.2,0.55,clock*0.6,0.6f,0.25f,1,0.55f,true);
        // 互相傾斜、反向旋轉的雙吸積帶，各自具有寬光暈及細亮絲。
        for (int plane = 0; plane < 2; plane++) {
            double tilt = plane == 0 ? 0.38 : -0.62;
            Point v = new Point(0, Math.sin(tilt), Math.cos(tilt));
            double rotation = clock * (plane == 0 ? 0.75 : -0.6);
            double r = plane == 0 ? 15.5 : 18;
            ring(glow,CENTER,axisX,v,r,r*0.85,1.1,rotation,0.64f,0.2f,1,0.38f,true);
            ring(glow,CENTER,axisX,v,r-0.4,r*0.85-0.4,0.13,rotation+0.3,1,0.72f,0.3f,0.72f,true);
        }
        for (int ray = 0; ray < 48; ray++) {
            double angle = ray*Math.PI/24 + clock*0.07;
            double flare = 0.5+0.5*Math.sin(clock*2.8+ray*2.4);
            Point radial = new Point(Math.cos(angle),Math.sin(angle),0);
            Point tangent = new Point(-radial.y(),radial.x(),0);
            Point start = CENTER.add(radial.scale(10));
            Point end = CENTER.add(radial.scale(12+flare*5));
            strip(glow,start,end,tangent.scale(0.4+flare*0.35),0.7f,0.2f,1,0.32f);
            strip(glow,start,end,tangent.scale(0.10),1,0.68f,0.25f,(float)(0.25+flare*0.45));
        }
        // 三道向外掃過整座結構的橢圓衝擊環，保留結構的寬／深比例。
        for (int wave = 0; wave < 3; wave++) {
            double life = fract(clock/4.8+wave/3.0);
            double scale = 0.25+0.75*life;
            ring(glow,new Point(0,-4+life*8,-24),axisX,axisZ,84*scale,27*scale,
                    0.12+life*0.3,0,0.62f,0.35f,1,(float)(0.04+0.32*(1-life)),false);
        }
        // 超出結構表面的六道斷裂力場與三股螺旋流，全景下也能辨識不穩定。
        for (int hoop = 0; hoop < 6; hoop++) {
            double pulse = ShanhaiRitualPaths.hoopPulse(clock,hoop);
            for (int step = 0; step < 96; step++) {
                double t = step/96.0;
                var pose = ShanhaiRitualPaths.hoop(t,clock,hoop);
                Point end = ShanhaiRitualPaths.hoop((step+.77)/96.0,clock,hoop).center();
                double weight = .12+.88*ShanhaiRitualPaths.energyWeight(clock,(step+.385)/96.0,hoop+3);
                strip(glow,pose.center(),end,pose.outward().scale(.24+pulse*.22),.58f,.3f,1,
                        (float)((.3+pulse*.3)*weight));
            }
        }
        for (int strand = 0; strand < 3; strand++) {
            Point previous = ShanhaiRitualPaths.helix(0,clock,strand).center();
            for (int step = 1; step <= 96; step++) {
                var pose = ShanhaiRitualPaths.helix(step/96.0,clock,strand);
                Point next = pose.center();
                Point radial = pose.outward();
                float weight = (float)(.12+.88*ShanhaiRitualPaths.energyWeight(clock,(step-.5)/96.0,strand));
                strip(glow,previous,next,radial.scale(0.42),0.6f,0.22f,1,0.32f*weight);
                strip(glow,previous,next,radial.scale(0.10),0.85f,0.72f,1,0.65f*weight);
                previous = next;
            }
        }
        int streams = Math.min(MAX_STREAMS,active.size());
        for (int stream = 0; stream < streams; stream++) {
            Node node = active.get(stream*active.size()/streams);
            Point start = new Point(node.x()+node.nx()*0.6,node.y()+node.ny()*0.6,node.z()+node.nz()*0.6);
            Point radial = start.add(CENTER.scale(-1)).unit();
            Point end = CENTER.add(radial.scale(RADIUS+0.7));
            Point delta = end.add(start.scale(-1));
            Point u = radial.cross(Math.abs(radial.y()) < 0.9 ? axisY : axisX).unit();
            Point v = radial.cross(u);
            double life = fract(clock*0.35+stream*0.618);
            Point previous = infall(start,delta,u,v,life,stream);
            for (int step = 1; step <= 12; step++) {
                double t = Math.min(1,life+step/12.0*0.25);
                Point next = infall(start,delta,u,v,t,stream);
                float alpha = (float)(0.18+0.48*Math.sin(Math.PI*step/13));
                strip(glow,previous,next,u.scale(0.12),0.72f,0.3f,1,alpha);
                strip(glow,previous,next,v.scale(0.055),1,0.72f,0.35f,alpha);
                previous = next;
            }
        }
        glow.addAll(ShanhaiCipherGeometry.frame(clock,seed).quads());
        if (CORE.size()+glow.size() > MAX_QUADS) throw new IllegalStateException("Black sun quad budget exceeded");
        return new Frame(CORE,List.copyOf(glow),streams);
    }

    private static List<Quad> core() {
        // 固定黑核僅建立一次；真正的黑色表面獨立寫深度，不能放進加性發光層。
        var opaque = new ArrayList<Quad>(512);
        for (int latitude = 0; latitude < 16; latitude++) {
            double a = latitude * Math.PI/16, b = (latitude+1) * Math.PI/16;
            for (int longitude = 0; longitude < 32; longitude++) {
                double c = longitude * Math.PI/16, d = (longitude+1) * Math.PI/16;
                opaque.add(new Quad(sphere(a,c),sphere(b,c),sphere(b,d),sphere(a,d),0,0,0,1));
            }
        }
        return List.copyOf(opaque);
    }

    private static double fract(double n) { return n-Math.floor(n); }

    private static Point sphere(double latitude, double longitude) {
        return CENTER.add(new Point(Math.sin(latitude)*Math.cos(longitude),Math.cos(latitude),
                Math.sin(latitude)*Math.sin(longitude)).scale(RADIUS));
    }

    private static Point infall(Point start, Point delta, Point u, Point v, double t, int stream) {
        double bend = Math.sin(t*Math.PI)*2;
        return start.add(delta.scale(t)).add(u.scale(Math.sin(t*9+stream)*bend))
                .add(v.scale(Math.cos(t*9+stream)*bend));
    }

    private static void ring(List<Quad> out, Point center, Point u, Point v, double ru, double rv,
                             double width, double rotation, float r, float g, float b, float alpha, boolean broken) {
        for (int segment = 0; segment < 96; segment++) {
            double angle = segment*Math.PI/48;
            // 紋理相位連續移動，避免靠整圈同步閃爍製造不穩定。
            double modulation = 0.52+0.48*Math.sin(angle*7-rotation*2);
            double gap = broken ? 0.77 : 1;
            Point a = ellipse(center,u,v,ru,rv,angle+rotation);
            Point c = ellipse(center,u,v,ru,rv,angle+rotation+Math.PI/48*gap);
            Point radial = u.scale(Math.cos(angle+rotation)).add(v.scale(Math.sin(angle+rotation)));
            strip(out,a,c,radial.scale(width),r,g,b,(float)(alpha*(0.35+0.65*modulation)));
        }
    }

    private static Point ellipse(Point center, Point u, Point v, double ru, double rv, double angle) {
        return center.add(u.scale(ru*Math.cos(angle))).add(v.scale(rv*Math.sin(angle)));
    }
}
