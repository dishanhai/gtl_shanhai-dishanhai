package com.dishanhai.gt_shanhai.client.renderer.preview;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：真正的筆畫幾何、環形／貼軌密文分布、浮現節奏與硬預算。 */
class ShanhaiCipherGeometryTest {
    @Test
    void scriptSurroundsSunAndBothTentaclesWithActualStrokes() {
        var frame=ShanhaiCipherGeometry.frame(2,42);
        assertTrue(frame.glyphs()>60);
        assertTrue(frame.quads().size()>600);
        var points=frame.quads().stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).toList();
        assertTrue(points.stream().anyMatch(p->p.y()>54),"Cipher halo above the sun");
        assertTrue(points.stream().anyMatch(p->p.x()<-70));
        assertTrue(points.stream().anyMatch(p->p.x()>70));
        assertTrue(points.stream().anyMatch(p->p.z()<-52));
        assertTrue(points.stream().anyMatch(p->p.z()>4));
    }

    @Test
    void animationIsRepeatableVariedSmoothAndBoundedAcrossPageChanges() {
        assertEquals(ShanhaiCipherGeometry.frame(1,42),ShanhaiCipherGeometry.frame(1,42));
        assertNotEquals(ShanhaiCipherGeometry.frame(1,42),ShanhaiCipherGeometry.frame(1,43));
        assertNotEquals(ShanhaiCipherGeometry.frame(1,42),ShanhaiCipherGeometry.frame(2,42));
        var counts=new HashSet<Integer>();
        float minAlpha=1,maxAlpha=0;
        for(int step=0;step<240;step++) {
            var frame=ShanhaiCipherGeometry.frame(step*.1,42);
            assertTrue(frame.glyphs()>0&&frame.glyphs()<=ShanhaiCipherGeometry.MAX_GLYPHS);
            assertTrue(frame.quads().size()<=ShanhaiCipherGeometry.MAX_QUADS);
            counts.add(frame.quads().size());
            for(var q:frame.quads()) {
                minAlpha=Math.min(minAlpha,q.alpha());maxAlpha=Math.max(maxAlpha,q.alpha());
                assertTrue(q.alpha()>0&&q.alpha()<=.75f);
                for(float color:new float[]{q.red(),q.green(),q.blue()})assertTrue(color>=0&&color<=1);
                for(var p:new Point[]{q.a(),q.b(),q.c(),q.d()}) {
                    assertTrue(Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z()));
                    assertTrue(Math.abs(p.x())<96&&p.y()>-32&&p.y()<60&&p.z()>-60&&p.z()<12);
                }
                double area=Math.sqrt(Math.pow(q.a().x()-q.b().x(),2)+Math.pow(q.a().y()-q.b().y(),2)+Math.pow(q.a().z()-q.b().z(),2));
                assertTrue(area>.01,"No zero-length rune stroke");
            }
        }
        assertTrue(counts.size()>10,"Rows fade and pages use different glyphs");
        assertTrue(minAlpha<.02&&maxAlpha>.4,"Script emerges and vanishes instead of staying solid");
    }

    @Test
    void invalidTimesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->ShanhaiCipherGeometry.frame(Double.NaN,0));
        assertThrows(IllegalArgumentException.class,()->ShanhaiCipherGeometry.frame(Double.POSITIVE_INFINITY,0));
    }
}
