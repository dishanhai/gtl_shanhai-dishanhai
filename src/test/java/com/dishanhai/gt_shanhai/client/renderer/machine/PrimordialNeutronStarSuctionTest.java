package com.dishanhai.gt_shanhai.client.renderer.machine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialNeutronStarSuctionTest {

    @BeforeEach
    void resetMotion() {
        PrimordialNeutronStarSuction.resetMotion();
    }

    @Test
    void planetsCrossTheRingsAndReachTheStar() {
        float[] position = new float[3];
        float[] previous = new float[3];
        for (float starRadius : new float[] {13.0f, 35.1f, 44.6f}) {
            for (int index = 0; index < PrimordialNeutronStarSuction.COUNT; index++) {
                PrimordialNeutronStarSuction.resetMotion();
                float radius = PrimordialNeutronStarSuction.blockRadius(index);
                PrimordialNeutronStarSuction.writeLocalPosition(index, 0.0f, starRadius, previous);
                float minDistance = Float.MAX_VALUE;
                float minAlong = 200.0f;
                boolean crossedLargeRing = false;
                boolean returned = false;
                float farAfter = 0.0f;
                boolean reached = false;
                int dwell = 0;
                for (int tick = 1; tick <= 3600; tick++) {
                    PrimordialNeutronStarSuction.writeLocalPosition(index, tick, starRadius, position);
                    float x = position[0];
                    float y = position[1];
                    float z = position[2];
                    for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
                        float angle = PrimordialEngineRingOffset.angleDegrees(ring, tick);
                        assertTrue(PrimordialEngineRingOffset.clearsRing(
                                x, y, z, ring, angle, radius),
                                "星球不能进入第 " + ring + " 环 tick=" + tick
                                        + " idx=" + index + " star=" + starRadius
                                        + " pos=" + x + "," + y + "," + z
                                        + " prev=" + previous[0] + "," + previous[1] + "," + previous[2]
                                        + " radial=" + PrimordialEngineRingOffset.ringRadial(x, y, z, angle)
                                        + " plane=" + PrimordialEngineRingOffset.planeDistance(x, y, angle));
                    }
                    float distance = (float) Math.sqrt(x * x + y * y + z * z);
                    assertTrue(distance > starRadius + radius + 2.0f, "星球不能钻进星体");
                    if (z < -100.0f) {
                        assertTrue(y > 5.5f, "环外通道里的星球必须留在原初模块上方");
                    }
                    float step = (float) Math.sqrt(
                            (x - previous[0]) * (x - previous[0])
                                    + (y - previous[1]) * (y - previous[1])
                                    + (z - previous[2]) * (z - previous[2]));
                    assertTrue(step < 3.5f, "相邻两刻的位移不能跳变 tick=" + tick + " step=" + step
                            + " pos=" + x + "," + y + "," + z);
                    if (-z > 36.0f && -z < 92.0f && distance > starRadius + radius + 14.0f && step < 0.08f) {
                        dwell++;
                    } else {
                        dwell = 0;
                    }
                    assertTrue(dwell < 80, "星球在环区停住 tick=" + tick + " index=" + index
                            + " pos=" + x + "," + y + "," + z);
                    if (distance < minDistance) minDistance = distance;
                    float largeInner = PrimordialEngineRingOffset.bandInner(PrimordialEngineRingOffset.LARGE, radius);
                    if (-z < minAlong) minAlong = -z;
                    if (-z < largeInner) crossedLargeRing = true;
                    if (distance < starRadius + radius + 12.0f) reached = true;
                    if (reached && -z > farAfter) farAfter = -z;
                    if (reached && z < -96.0f) returned = true;
                    previous[0] = x;
                    previous[1] = y;
                    previous[2] = z;
                }
                assertTrue(crossedLargeRing, "星球必须进入大环半径以内 index=" + index + " minAlong=" + minAlong);
                assertTrue(reached, "星球必须抵达中子星区 min=" + minDistance + " star=" + starRadius);
                assertTrue(returned, "抵达后必须折返回远端 index=" + index + " farAfter=" + farAfter);
            }
        }
    }

    @Test
    void miniatureNeutronStarCrossesTheRings() {
        float[] position = new float[3];
        float[] previous = new float[3];
        float radius = PrimordialNeutronStarSuction.MINIATURE_RADIUS;
        for (float starRadius : new float[] {13.0f, 35.1f, 44.6f}) {
            PrimordialNeutronStarSuction.resetMotion();
            PrimordialNeutronStarSuction.writeMiniatureLocalPosition(0.0f, starRadius, previous);
            boolean reached = false;
            boolean returned = false;
            boolean crossed = false;
            int dwell = 0;
            for (int tick = 1; tick <= 3600; tick++) {
                PrimordialNeutronStarSuction.writeMiniatureLocalPosition(tick, starRadius, position);
                float x = position[0];
                float y = position[1];
                float z = position[2];
                for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
                    float angle = PrimordialEngineRingOffset.angleDegrees(ring, tick);
                    assertTrue(PrimordialEngineRingOffset.clearsRing(x, y, z, ring, angle, radius),
                            "微缩中子星不能进入第 " + ring + " 环 tick=" + tick
                                    + " pos=" + x + "," + y + "," + z
                                    + " radial=" + PrimordialEngineRingOffset.ringRadial(x, y, z, angle)
                                    + " plane=" + PrimordialEngineRingOffset.planeDistance(x, y, angle));
                }
                float distance = (float) Math.sqrt(x * x + y * y + z * z);
                assertTrue(distance > starRadius + radius + 2.0f, "微缩中子星不能钻进宿主星体");
                if (z < -100.0f) {
                    assertTrue(y > 5.5f, "环外通道里的微缩中子星必须留在原初模块上方");
                }
                float step = (float) Math.sqrt(
                        (x - previous[0]) * (x - previous[0])
                                + (y - previous[1]) * (y - previous[1])
                                + (z - previous[2]) * (z - previous[2]));
                assertTrue(step < 3.5f, "微缩中子星相邻两刻不能跳变 step=" + step);
                if (-z > 36.0f && -z < 92.0f && distance > starRadius + radius + 14.0f && step < 0.08f) {
                    dwell++;
                } else {
                    dwell = 0;
                }
                assertTrue(dwell < 80, "微缩中子星在环区停住 tick=" + tick
                        + " pos=" + x + "," + y + "," + z);
                if (-z < PrimordialEngineRingOffset.bandInner(PrimordialEngineRingOffset.LARGE, radius)) {
                    crossed = true;
                }
                if (distance < starRadius + radius + 12.0f) reached = true;
                if (reached && z < -96.0f) returned = true;
                previous[0] = x;
                previous[1] = y;
                previous[2] = z;
            }
            assertTrue(crossed, "微缩中子星必须进入大环半径以内");
            assertTrue(reached, "微缩中子星必须抵达中子星区 star=" + starRadius);
            assertTrue(returned, "微缩中子星抵达后必须折返");
        }
    }

    @Test
    void motionDoesNotTeleport() {
        float[] previous = new float[3];
        float[] current = new float[3];
        for (int index = 0; index < PrimordialNeutronStarSuction.COUNT; index++) {
            PrimordialNeutronStarSuction.writeLocalPosition(index, 0.0f, 13.0f, previous);
            for (int tick = 1; tick <= 900; tick++) {
                PrimordialNeutronStarSuction.writeLocalPosition(index, tick, 13.0f, current);
                float dx = current[0] - previous[0];
                float dy = current[1] - previous[1];
                float dz = current[2] - previous[2];
                float step = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                assertTrue(step < 3.5f, "相邻两刻的位移不能跳变 tick=" + tick
                        + " step=" + step + " index=" + index);
                previous[0] = current[0];
                previous[1] = current[1];
                previous[2] = current[2];
            }
        }
    }

    @Test
    void threeRingsSpinAtTheRendererRates() {
        assertEquals(0.0f, PrimordialEngineRingOffset.angleDegrees(0, 0.0f), 0.001f);
        assertEquals(120.0f, PrimordialEngineRingOffset.angleDegrees(1, 0.0f), 0.001f);
        assertEquals(240.0f, PrimordialEngineRingOffset.angleDegrees(2, 0.0f), 0.001f);

        float largeTurn = PrimordialEngineRingOffset.angleDegrees(0, 450.0f);
        float smallTurn = PrimordialEngineRingOffset.angleDegrees(2, 150.0f);
        assertEquals(0.0f, largeTurn, 0.05f);
        assertEquals(240.0f, smallTurn, 0.05f);

        float largeStep = PrimordialEngineRingOffset.angleDegrees(0, 10.0f)
                - PrimordialEngineRingOffset.angleDegrees(0, 0.0f);
        float smallStep = PrimordialEngineRingOffset.angleDegrees(2, 10.0f)
                - PrimordialEngineRingOffset.angleDegrees(2, 0.0f);
        float middleStep = PrimordialEngineRingOffset.angleDegrees(1, 10.0f)
                - PrimordialEngineRingOffset.angleDegrees(1, 0.0f);
        assertTrue(smallStep > largeStep, "小环必须快过大环");
        assertTrue(middleStep < 0.0f, "中环必须反向转");
    }

    @Test
    void fourPlanetsUseThreeExistingModelsWorthOfSizes() {
        assertEquals(4, PrimordialNeutronStarSuction.COUNT);
        for (int index = 0; index < PrimordialNeutronStarSuction.COUNT; index++) {
            float radius = PrimordialNeutronStarSuction.blockRadius(index);
            assertTrue(radius > 0.7f && radius < 1.6f);
        }
    }
}
