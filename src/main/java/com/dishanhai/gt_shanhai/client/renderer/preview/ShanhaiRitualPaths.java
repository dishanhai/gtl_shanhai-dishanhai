package com.dishanhai.gt_shanhai.client.renderer.preview;

import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：密文與能流共用的祭儀軌跡。 */
final class ShanhaiRitualPaths {
    record Pose(Point center, Point tangent, Point outward) {}
    private ShanhaiRitualPaths() {}
    static Pose helix(double progress, double clock, int strand) {
        double angle = progress*Math.PI*4-clock*.7+strand*Math.PI*2/3;
        double s = Math.sin(angle), c = Math.cos(angle);
        return new Pose(new Point(-82+164*progress,28*s,-24+29*c),
                new Point(164,28*c*Math.PI*4,-29*s*Math.PI*4).unit(),new Point(0,s/28,c/29).unit());
    }
    static Pose hoop(double progress, double clock, int hoop) {
        double pulse = hoopPulse(clock,hoop);
        double ry = 26+pulse, rz = 27+pulse;
        double angle = progress*Math.PI*2+clock*.25+hoop*.6;
        double s = Math.sin(angle), c = Math.cos(angle);
        return new Pose(new Point(-70+hoop*28,ry*c,-24+rz*s),
                new Point(0,-ry*s,rz*c).unit(),new Point(0,c/ry,s/rz).unit());
    }
    static double hoopPulse(double clock,int hoop) { return .5+.5*Math.sin(clock*2.6-hoop*.8); }
    static double textWeight(double clock, double progress, int lane) {
        return .5+.5*Math.sin(clock*1.25-progress*Math.PI*4+lane*.8);
    }
    static double energyWeight(double clock, double progress, int lane) { return 1-textWeight(clock,progress,lane); }
    static double edgeFade(double progress) {
        double edge = Math.max(0,Math.min(1,Math.min(progress,1-progress)/.045));
        return edge*edge*(3-2*edge);
    }
}
