package com.dishanhai.gt_shanhai.common.machine.starlifter;

/** Exports the production mesh bands and rest orientations for the structure compiler. */
public final class StarLifterMeshExport {
    public static void main(String[] args) {
        System.out.println("scale," + StarLifterGeometry.DEFAULT_RADIUS + "," + StarLifterGeometry.STAR_RADIUS);
        for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
            var ring = StarLifterGeometry.RINGS.get(i);
            var q = ring.orientation(0);
            System.out.println("ring," + ring.radius() + "," + ring.width() + "," + ring.height()
                    + "," + ring.heading() + "," + ring.axisAzimuth() + "," + ring.spatialPeriod()
                    + "," + ring.rimPeriod() + "," + q.x + "," + q.y + "," + q.z + "," + q.w);
            StarLifterGeometry.ring(i, new StarLifterGeometry.Mesh() {
                @Override
                public void quad(StarLifterGeometry.Surface surface, StarLifterGeometry.Point a,
                                 StarLifterGeometry.Point b, StarLifterGeometry.Point c, StarLifterGeometry.Point d) {}

                @Override
                public void band(StarLifterGeometry.Surface surface, double start, double end,
                                 double inner, double outer, double bottom, double top) {
                    System.out.println(surface + "," + start + "," + end + "," + inner + "," + outer + "," + bottom + "," + top);
                }
            });
        }
    }
}
