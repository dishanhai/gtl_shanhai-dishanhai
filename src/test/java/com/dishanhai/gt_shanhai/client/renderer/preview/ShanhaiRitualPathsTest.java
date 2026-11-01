package com.dishanhai.gt_shanhai.client.renderer.preview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：密文貼合正式光流路徑、朝向和交替亮度。 */
class ShanhaiRitualPathsTest {
    private static double distance(Point a,Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
    }
    @Test
    void movingPathsHaveOrthonormalGlyphFramesAndOriginalShape() {
        for(int sample=0;sample<=32;sample++) {
            double t=sample/32.0,clock=2.5;
            for(int lane=0;lane<6;lane++) {
                var pose=lane<3?ShanhaiRitualPaths.helix(t,clock,lane):ShanhaiRitualPaths.hoop(t,clock,lane);
                var u=pose.tangent();var v=pose.outward();
                assertEquals(1,distance(u,new Point(0,0,0)),1e-10);
                assertEquals(1,distance(v,new Point(0,0,0)),1e-10);
                assertEquals(0,u.x()*v.x()+u.y()*v.y()+u.z()*v.z(),1e-10);
                if(lane<3) {
                    assertEquals(-82+164*t,pose.center().x(),1e-10);
                    assertEquals(1,Math.pow(pose.center().y()/28,2)+Math.pow((pose.center().z()+24)/29,2),1e-10);
                } else assertEquals(-70+28*lane,pose.center().x(),1e-10);
            }
        }
        assertNotEquals(ShanhaiRitualPaths.helix(.3,1,0),ShanhaiRitualPaths.helix(.3,2,0));
        assertNotEquals(ShanhaiRitualPaths.hoop(.3,1,0),ShanhaiRitualPaths.hoop(.3,2,0));
    }

    @Test
    void textAndEnergyAlternateSmoothlyAndHelixWrapIsInvisible() {
        double low=1,high=0;
        for(int step=0;step<200;step++) {
            double t=step/200.0;
            double text=ShanhaiRitualPaths.textWeight(2,t,1),energy=ShanhaiRitualPaths.energyWeight(2,t,1);
            assertTrue(text>=0&&text<=1&&energy>=0&&energy<=1);
            assertEquals(1,text+energy,1e-12);
            low=Math.min(low,text);high=Math.max(high,text);
        }
        assertTrue(low<.02&&high>.98);
        assertEquals(0,ShanhaiRitualPaths.edgeFade(0));assertEquals(0,ShanhaiRitualPaths.edgeFade(1));
        assertEquals(1,ShanhaiRitualPaths.edgeFade(.5));
        assertTrue(ShanhaiRitualPaths.edgeFade(.001)<.01);
    }

    @Test
    void emittedBodyCipherStrokesStayOnMovingEnergyTrajectories() {
        for(double clock:new double[]{1,2,3.5}) {
            var paths=new java.util.ArrayList<Point>();
            for(int step=0;step<=192;step++) {
                for(int lane=0;lane<3;lane++)paths.add(ShanhaiRitualPaths.helix(step/192.0,clock,lane).center());
                for(int hoop=0;hoop<6;hoop++)paths.add(ShanhaiRitualPaths.hoop(step/192.0,clock,hoop).center());
            }
            int body=0;
            for(var quad:ShanhaiCipherGeometry.frame(clock,42).quads()) {
                Point p=quad.a();
                if(Math.abs(p.x())<30)continue; // 黑日咒文環在中心區域。
                body++;
                assertTrue(paths.stream().anyMatch(c->distance(p,c)<2.8),"A cipher stroke detached from its energy path: "+p);
            }
            assertTrue(body>200);
        }
    }
}
