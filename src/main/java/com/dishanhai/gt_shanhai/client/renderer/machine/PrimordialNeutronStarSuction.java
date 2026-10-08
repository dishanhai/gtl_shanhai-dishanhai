package com.dishanhai.gt_shanhai.client.renderer.machine;

/**
 * 中子星光束旁星球的本地坐标。纯计算，不碰 Minecraft。
 * <p>
 * 坐标系与伪神锻光束一致：原点是星心，光束截面在 XY，光束沿 -Z 指向机头。
 * 星球从机头外侧走进环包裹的中子星区，停一下再返回。
 * 环带沿光束是实心圆盘。贴着法线 {@code (-sin θ, cos θ)} 走时，环面分量为 0，
 * 环自己转也不会把球卷进去。两环叠在一起就改骑较厚的那一圈；法线还没盖住，
 * 先在环口横向改道，到位再穿。判定比真实环带宽一截，避免停在带边上被下一刻卷进去。
 * <p>
 * 轨迹在客户端渲染线程上按时间积分。
 */
final class PrimordialNeutronStarSuction {

    static final int COUNT = 4;

    /** {@code gtlcore:models/obj/star.obj} 的半边长。星球模型共用这颗网格。 */
    static final float MODEL_HALF_EXTENT = 74.0f;

    /** 远端，停在机头张口（{@code -121.5}）之前。 */
    private static final float FAR_Z = -106.0f;

    /** 抵达星面后再留出的空隙。 */
    private static final float SURFACE_GAP = 5.0f;

    /** 还有这么远就要进环带时，先开始改道。 */
    private static final float LEAD = 12.0f;

    /** 法线外侧再留一截，用来跟上环的转动。 */
    private static final float SLACK = 2.6f;

    /** 比真实环带再宽一截。停在这条线外面，下一刻的转动卷不进盘。 */
    private static final float SWEEP = 1.6f;

    private static final float ALONG_SPEED = 0.55f;
    private static final float LATERAL_SPEED = 2.3f;
    private static final float LINGER_TICKS = 40.0f;

    private static final int PHASE_WAIT = 0;
    private static final int PHASE_IN = 1;
    private static final int PHASE_LINGER = 2;
    private static final int PHASE_OUT = 3;
    private static final int TRACKS = COUNT + 1;

    /** 靠光束中轴。过环时会暂时离开这条线。 */
    private static final float[] LATERAL_X = {6.5f, -8.0f, 9.0f, -6.0f};
    /**
     * 左右两侧贴着光束中线会插入原初模块。
     * 通道里的高度留在模块上方，过环时再按环的法线改道。
     */
    private static final float[] LATERAL_Y = {6.8f, 8.8f, 8.2f, 7.2f};
    private static final float[] BLOCK_RADIUS = {1.05f, 1.35f, 0.90f, 1.15f};

    /** 微缩中子星的球半径。比四颗岩石行星稍大，仍远小于宿主星。 */
    static final float MINIATURE_RADIUS = 1.8f;

    private static final float MINIATURE_X = -2.6f;
    private static final float MINIATURE_Y = 8.4f;

    private static final boolean[] LIVE = new boolean[TRACKS];
    private static final float[] CURSOR_TICK = new float[TRACKS];
    private static final float[] CURSOR_X = new float[TRACKS];
    private static final float[] CURSOR_Y = new float[TRACKS];
    private static final float[] CURSOR_Z = new float[TRACKS];
    private static final float[] LINGER_LEFT = new float[TRACKS];
    private static final int[] PHASE = new int[TRACKS];

    private static float aimX;
    private static float aimY;
    private static float heldX;
    private static float heldY;

    private PrimordialNeutronStarSuction() {}

    static float blockRadius(int index) {
        return BLOCK_RADIUS[index];
    }

    static void resetMotion() {
        for (int i = 0; i < TRACKS; i++) {
            LIVE[i] = false;
        }
    }

    /**
     * @param out 长度至少 3。{@code [0]=x [1]=y [2]=z}，单位是方块。
     */
    static void writeLocalPosition(int index, float tick, float starRadius, float[] out) {
        integrate(index, tick, starRadius, blockRadius(index), out);
    }

    /** 微缩中子星走同一套过环往复，只是半径和通道偏移不同。 */
    static void writeMiniatureLocalPosition(float tick, float starRadius, float[] out) {
        integrate(COUNT, tick, starRadius, MINIATURE_RADIUS, out);
    }

    private static void integrate(int index, float tick, float starRadius, float planetRadius, float[] out) {
        if (!LIVE[index] || tick < CURSOR_TICK[index] - 0.5f) {
            begin(index, tick);
        }
        float left = tick - CURSOR_TICK[index];
        int guard = 0;
        while (left > 0.01f && guard++ < 40) {
            float dt = left > 1.0f ? 1.0f : left;
            step(index, CURSOR_TICK[index] + dt, dt, starRadius, planetRadius);
            CURSOR_TICK[index] += dt;
            left -= dt;
        }
        CURSOR_TICK[index] = tick;
        out[0] = CURSOR_X[index];
        out[1] = CURSOR_Y[index];
        out[2] = CURSOR_Z[index];
    }

    private static void begin(int index, float tick) {
        LIVE[index] = true;
        CURSOR_TICK[index] = tick;
        CURSOR_X[index] = channelX(index, tick);
        CURSOR_Y[index] = channelY(index, tick);
        CURSOR_Z[index] = FAR_Z;
        LINGER_LEFT[index] = 0.0f;
        PHASE[index] = PHASE_WAIT;
    }

    private static void step(int index, float tick, float dt, float starRadius, float planetRadius) {
        float x = CURSOR_X[index];
        float y = CURSOR_Y[index];
        float z = CURSOR_Z[index];
        float xySpeed = LATERAL_SPEED * dt;
        float zSpeed = ALONG_SPEED * dt;
        float goal = goalZ(index, starRadius, planetRadius);
        float fat = planetRadius + SWEEP;
        float zTry = moveToward(z, goal, zSpeed);
        if (hardCount(z, fat) >= 2 && !fatClear(x, y, z, tick, starRadius, planetRadius)) {
            zTry = retreatZ(z, zSpeed, fat);
        }

        if (zTry != z) {
            chooseAim(index, tick, zTry, goal, planetRadius, starRadius, x, y);
            if (slideFat(x, y, aimX, aimY, zTry, tick, starRadius, planetRadius, xySpeed, true)
                    && corridorFat(x, y, heldX, heldY, z, zTry, tick, starRadius, planetRadius)) {
                CURSOR_X[index] = heldX;
                CURSOR_Y[index] = heldY;
                CURSOR_Z[index] = zTry;
                settle(index, tick, xySpeed, starRadius, planetRadius);
                advancePhase(index, tick, dt, starRadius, planetRadius);
                return;
            }
        }
        chooseAim(index, tick, z, goal, planetRadius, starRadius, x, y);
        if (slideFat(x, y, aimX, aimY, z, tick, starRadius, planetRadius, xySpeed, true)) {
            CURSOR_X[index] = heldX;
            CURSOR_Y[index] = heldY;
        }
        settle(index, tick, xySpeed, starRadius, planetRadius);
        advancePhase(index, tick, dt, starRadius, planetRadius);
    }

    /** 往目标走，停在仍算干净的最远点。{@code fatOnly} 时必须留有扫掠余量。 */
    private static boolean slideFat(float x, float y, float targetX, float targetY, float z, float tick,
                                   float starRadius, float planetRadius, float xySpeed, boolean fatOnly) {
        approachInto(x, y, targetX, targetY, xySpeed);
        float farX = heldX;
        float farY = heldY;
        if (stepOk(farX, farY, z, tick, starRadius, planetRadius, fatOnly)) {
            return true;
        }
        float bestX = x;
        float bestY = y;
        boolean any = stepOk(x, y, z, tick, starRadius, planetRadius, fatOnly);
        float lo = 0.0f;
        float hi = 1.0f;
        for (int i = 0; i < 6; i++) {
            float mid = (lo + hi) * 0.5f;
            float mx = x + (farX - x) * mid;
            float my = y + (farY - y) * mid;
            if (stepOk(mx, my, z, tick, starRadius, planetRadius, fatOnly)) {
                bestX = mx;
                bestY = my;
                any = true;
                lo = mid;
            } else {
                hi = mid;
            }
        }
        if (!any) return false;
        heldX = bestX;
        heldY = bestY;
        return true;
    }

    private static boolean stepOk(float x, float y, float z, float tick, float starRadius, float planetRadius,
                                 boolean fatOnly) {
        if (fatOnly) return fatClear(x, y, z, tick, starRadius, planetRadius);
        return clear(x, y, z, tick, starRadius, planetRadius);
    }

    /**
     * 落点不在宽裕区时，朝这圈法线上的点靠。只接受宽裕落点，
     * 避免沿着切线越偏越远，下一刻被环转进盘里。
     */
    private static void settle(int index, float tick, float xySpeed, float starRadius, float planetRadius) {
        float x = CURSOR_X[index];
        float y = CURSOR_Y[index];
        float z = CURSOR_Z[index];
        if (fatClear(x, y, z, tick, starRadius, planetRadius)) return;
        int ring = worstRing(x, y, z, tick, planetRadius + SWEEP);
        if (ring < 0) ring = worstRing(x, y, z, tick, planetRadius);
        if (ring < 0) ring = nearestRing(-z, planetRadius + SWEEP);
        if (slideOnto(index, x, y, z, tick, xySpeed, starRadius, planetRadius, ring, true)) return;
        if (!clear(x, y, z, tick, starRadius, planetRadius)) {
            if (slideOnto(index, x, y, z, tick, xySpeed, starRadius, planetRadius, ring, false)) return;
            if (shrinkOffInnerEdge(index, x, y, z, tick, xySpeed, starRadius, planetRadius)) return;
            searchClear(index, x, y, z, tick, xySpeed, starRadius, planetRadius);
        }
    }

    /** 定向走不通时，在这一步的速度圆里找一个还在环外的点。 */
    private static void searchClear(int index, float x, float y, float z, float tick, float xySpeed,
                                   float starRadius, float planetRadius) {
        float bestScore = Float.MAX_VALUE;
        float bestX = x;
        float bestY = y;
        boolean found = false;
        for (int sector = 0; sector < 16; sector++) {
            double angle = sector * Math.PI / 8.0;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            for (int step = 1; step <= 4; step++) {
                float dist = xySpeed * step / 4.0f;
                float sx = x + dx * dist;
                float sy = y + dy * dist;
                if (!clear(sx, sy, z, tick, starRadius, planetRadius)) continue;
                float score = (float) Math.hypot(sx - aimX, sy - aimY) + 0.15f * (float) Math.hypot(sx, sy);
                if (fatClear(sx, sy, z, tick, starRadius, planetRadius)) score -= 8.0f;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = sx;
                    bestY = sy;
                    found = true;
                }
            }
        }
        if (!found) return;
        CURSOR_X[index] = bestX;
        CURSOR_Y[index] = bestY;
    }

    /** 半径刚擦过环带内缘时，朝光束轴收一截就能离开环带。 */
    private static boolean shrinkOffInnerEdge(int index, float x, float y, float z, float tick, float xySpeed,
                                          float starRadius, float planetRadius) {
        float len = (float) Math.hypot(x, y);
        if (len < 0.3f) return false;
        float nx = x;
        float ny = y;
        boolean found = false;
        for (int i = 1; i <= 6; i++) {
            float scale = Math.max(0.0f, len - xySpeed * i / 6.0f) / len;
            float sx = x * scale;
            float sy = y * scale;
            if (clear(sx, sy, z, tick, starRadius, planetRadius)) {
                nx = sx;
                ny = sy;
                found = true;
            }
        }
        if (!found) return false;
        CURSOR_X[index] = nx;
        CURSOR_Y[index] = ny;
        return true;
    }

    /** 先试穿得最深的那圈，再试另外两圈。宽裕点走不到、人已经在盘里时，退到真实环带外面。 */
    private static boolean slideOnto(int index, float x, float y, float z, float tick, float xySpeed,
                                    float starRadius, float planetRadius, int ring, boolean fatOnly) {
        for (int k = 0; k < PrimordialEngineRingOffset.COUNT; k++) {
            int current = ring < 0 ? k : (ring + k) % PrimordialEngineRingOffset.COUNT;
            float sign = closerSign(tick, current, planetRadius, x, y);
            if (takeNormal(index, x, y, z, tick, xySpeed, starRadius, planetRadius, current, sign, fatOnly)) return true;
            if (takeNormal(index, x, y, z, tick, xySpeed, starRadius, planetRadius, current, -sign, fatOnly)) return true;
        }
        return false;
    }

    private static boolean takeNormal(int index, float x, float y, float z, float tick, float xySpeed,
                                     float starRadius, float planetRadius, int ring, float sign, boolean fatOnly) {
        float tx = normalX(tick, ring, sign, planetRadius);
        float ty = normalY(tick, ring, sign, planetRadius);
        if (!slideFat(x, y, tx, ty, z, tick, starRadius, planetRadius, xySpeed, fatOnly)) return false;
        if (heldX == x && heldY == y) return false;
        CURSOR_X[index] = heldX;
        CURSOR_Y[index] = heldY;
        return true;
    }

    private static int worstRing(float x, float y, float z, float tick, float planetRadius) {
        int worst = -1;
        float worstGap = 0.0f;
        for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
            float angle = PrimordialEngineRingOffset.angleDegrees(ring, tick);
            if (PrimordialEngineRingOffset.clearsRing(x, y, z, ring, angle, planetRadius)) continue;
            float gap = PrimordialEngineRingOffset.halfThickness(ring) + planetRadius
                    - Math.abs(PrimordialEngineRingOffset.planeDistance(x, y, angle));
            if (worst < 0 || gap > worstGap) {
                worst = ring;
                worstGap = gap;
            }
        }
        return worst;
    }

    /** 已经叠在两环里又不宽裕，就退回只剩一圈的那一侧。 */
    private static float retreatZ(float z, float zSpeed, float planetRadius) {
        float inward = z + zSpeed;
        float outward = z - zSpeed;
        int inCount = hardCount(inward, planetRadius);
        int outCount = hardCount(outward, planetRadius);
        if (outCount < inCount) return outward;
        if (inCount < outCount) return inward;
        return outCount <= hardCount(z, planetRadius) ? outward : z;
    }

    private static float goalZ(int index, float starRadius, float planetRadius) {
        if (PHASE[index] == PHASE_IN || PHASE[index] == PHASE_LINGER) {
            return nearZ(starRadius, planetRadius);
        }
        return FAR_Z;
    }

    private static void advancePhase(int index, float tick, float dt, float starRadius, float planetRadius) {
        float z = CURSOR_Z[index];
        float near = nearZ(starRadius, planetRadius);
        if (PHASE[index] == PHASE_WAIT) {
            if (tick < index * 80.0f) return;
            if (z < FAR_Z + 1.5f) {
                PHASE[index] = PHASE_IN;
            }
            return;
        }
        float distance = (float) Math.sqrt(CURSOR_X[index] * CURSOR_X[index]
                + CURSOR_Y[index] * CURSOR_Y[index] + z * z);
        if (PHASE[index] == PHASE_IN
                && (z > near - 1.2f || distance < starRadius + planetRadius + 12.0f)) {
            PHASE[index] = PHASE_LINGER;
            LINGER_LEFT[index] = LINGER_TICKS;
            return;
        }
        if (PHASE[index] == PHASE_LINGER) {
            LINGER_LEFT[index] -= dt;
            if (LINGER_LEFT[index] <= 0.0f) {
                PHASE[index] = PHASE_OUT;
            }
            return;
        }
        if (PHASE[index] == PHASE_OUT && z < FAR_Z + 1.5f) {
            PHASE[index] = PHASE_WAIT;
        }
    }

    private static float nearZ(float starRadius, float planetRadius) {
        float radius = starRadius > 1.0f ? starRadius : 13.0f;
        float near = -(radius + planetRadius + SURFACE_GAP);
        if (near <= FAR_Z + 16.0f) return FAR_Z + 16.0f;
        return near;
    }

    /**
     * 选这一格要贴的环。带宽按扫掠余量算。两环都压到光束上时，
     * 只有较厚那圈的法线能一路留在宽裕区里，才改骑它。
     */
    private static void chooseAim(int index, float tick, float z, float goal, float planetRadius,
                                 float starRadius, float fromX, float fromY) {
        boolean inward = PHASE[index] == PHASE_IN || PHASE[index] == PHASE_LINGER;
        float along = -z;
        float bandRadius = planetRadius + SWEEP;
        int hard0 = -1;
        int hard1 = -1;
        int next = -1;
        float nextGap = LEAD + 1.0f;
        for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
            float inner = PrimordialEngineRingOffset.bandInner(ring, bandRadius);
            float outer = PrimordialEngineRingOffset.bandOuter(ring, bandRadius);
            if (along >= inner && along <= outer) {
                if (hard0 < 0) hard0 = ring;
                else hard1 = ring;
                continue;
            }
            float gap = -1.0f;
            if (inward && along > outer) gap = along - outer;
            else if (!inward && along < inner) gap = inner - along;
            if (gap >= 0.0f && gap <= LEAD && gap < nextGap) {
                nextGap = gap;
                next = ring;
            }
        }
        if (hard0 < 0 && next < 0) {
            float cx = channelX(index, tick);
            float cy = channelY(index, tick);
            if (fatClear(cx, cy, z, tick, starRadius, planetRadius)) {
                aimX = cx;
                aimY = cy;
            } else {
                writeSticky(tick, nearestRing(-z, planetRadius + SWEEP), planetRadius, fromX, fromY);
            }
            return;
        }
        if (hard0 < 0) {
            writeSticky(tick, next, planetRadius, fromX, fromY);
            return;
        }
        if (hard1 < 0 && next < 0) {
            writeSticky(tick, hard0, planetRadius, fromX, fromY);
            return;
        }
        int other = hard1 >= 0 ? hard1 : next;
        int thick = PrimordialEngineRingOffset.halfThickness(hard0) >= PrimordialEngineRingOffset.halfThickness(other)
                ? hard0 : other;
        float sign = closerSign(tick, thick, planetRadius, fromX, fromY);
        if (rideClearsAhead(tick, z, goal, thick, sign, planetRadius, starRadius)
                && segmentFat(fromX, fromY, tick, z, thick, sign, planetRadius, starRadius)) {
            writeNormal(tick, thick, sign, planetRadius);
            return;
        }
        float flip = -sign;
        if (rideClearsAhead(tick, z, goal, thick, flip, planetRadius, starRadius)
                && segmentFat(fromX, fromY, tick, z, thick, flip, planetRadius, starRadius)) {
            writeNormal(tick, thick, flip, planetRadius);
            return;
        }
        int stand = hard1 < 0 ? hard0 : thick;
        writeSticky(tick, stand, planetRadius, fromX, fromY);
    }

    /** 贴着同一侧法线往前，重叠区每一刻都还在宽裕区里。 */
    private static boolean rideClearsAhead(float tick, float z, float goal, int ring, float sign,
                                          float planetRadius, float starRadius) {
        float dir = goal >= z ? 1.0f : -1.0f;
        float fat = planetRadius + SWEEP;
        float zz = z;
        boolean sawPair = hardCount(z, fat) >= 2;
        for (int i = 0; i < 22; i++) {
            if (!normalFat(tick + i, zz, ring, sign, planetRadius, starRadius)) return false;
            int count = hardCount(zz, fat);
            if (count >= 2) sawPair = true;
            if (sawPair && count < 2 && i > 0) return true;
            float next = zz + dir * ALONG_SPEED;
            if (dir > 0.0f && next > goal) return true;
            if (dir < 0.0f && next < goal) return true;
            zz = next;
        }
        return sawPair;
    }

    private static boolean segmentFat(float fromX, float fromY, float tick, float z, int ring, float sign,
                                     float planetRadius, float starRadius) {
        float x = normalX(tick, ring, sign, planetRadius);
        float y = normalY(tick, ring, sign, planetRadius);
        if (!fatClear(fromX, fromY, z, tick, starRadius, planetRadius)) {
            return fatClear(x, y, z, tick, starRadius, planetRadius)
                    && Math.hypot(x - fromX, y - fromY) <= LATERAL_SPEED + 0.01f;
        }
        for (int i = 1; i <= 4; i++) {
            float t = i / 4.0f;
            if (!fatClear(fromX + (x - fromX) * t, fromY + (y - fromY) * t, z, tick, starRadius, planetRadius)) {
                return false;
            }
        }
        return true;
    }

    private static boolean normalFat(float tick, float z, int ring, float sign, float planetRadius, float starRadius) {
        return fatClear(normalX(tick, ring, sign, planetRadius), normalY(tick, ring, sign, planetRadius),
                z, tick, starRadius, planetRadius);
    }

    private static void writeSticky(float tick, int ring, float planetRadius, float fromX, float fromY) {
        writeNormal(tick, ring, closerSign(tick, ring, planetRadius, fromX, fromY), planetRadius);
    }

    private static float closerSign(float tick, int ring, float planetRadius, float fromX, float fromY) {
        float x = normalX(tick, ring, 1.0f, planetRadius);
        float y = normalY(tick, ring, 1.0f, planetRadius);
        float plus = (float) Math.hypot(x - fromX, y - fromY);
        float minus = (float) Math.hypot(-x - fromX, -y - fromY);
        return plus <= minus ? 1.0f : -1.0f;
    }

    private static void writeNormal(float tick, int ring, float sign, float planetRadius) {
        aimX = normalX(tick, ring, sign, planetRadius);
        aimY = normalY(tick, ring, sign, planetRadius);
    }

    private static float normalX(float tick, int ring, float sign, float planetRadius) {
        double radians = Math.toRadians(PrimordialEngineRingOffset.angleDegrees(ring, tick));
        return (float) (-Math.sin(radians) * need(ring, planetRadius) * sign);
    }

    private static float normalY(float tick, int ring, float sign, float planetRadius) {
        double radians = Math.toRadians(PrimordialEngineRingOffset.angleDegrees(ring, tick));
        return (float) (Math.cos(radians) * need(ring, planetRadius) * sign);
    }

    private static float need(int ring, float planetRadius) {
        return PrimordialEngineRingOffset.halfThickness(ring) + planetRadius + SLACK;
    }

    /** 光束位置离哪一圈的环带最近。通道还切着环时，继续贴着这一圈走。 */
    private static int nearestRing(float along, float planetRadius) {
        int best = 0;
        float bestGap = Float.MAX_VALUE;
        for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
            float inner = PrimordialEngineRingOffset.bandInner(ring, planetRadius);
            float outer = PrimordialEngineRingOffset.bandOuter(ring, planetRadius);
            float gap = along < inner ? inner - along : (along > outer ? along - outer : 0.0f);
            if (gap < bestGap) {
                bestGap = gap;
                best = ring;
            }
        }
        return best;
    }

    private static int hardCount(float z, float planetRadius) {
        float along = -z;
        int count = 0;
        for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
            float inner = PrimordialEngineRingOffset.bandInner(ring, planetRadius);
            float outer = PrimordialEngineRingOffset.bandOuter(ring, planetRadius);
            if (along >= inner && along <= outer) count++;
        }
        return count;
    }

    private static boolean corridorFat(float x0, float y0, float x1, float y1, float z0, float z1, float tick,
                                      float starRadius, float planetRadius) {
        for (int i = 1; i <= 3; i++) {
            float t = i / 3.0f;
            if (!fatClear(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t,
                    tick, starRadius, planetRadius)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fatClear(float x, float y, float z, float tick, float starRadius, float planetRadius) {
        if (!starClear(x, y, z, starRadius, planetRadius)) return false;
        float fat = planetRadius + SWEEP;
        for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
            float angle = PrimordialEngineRingOffset.angleDegrees(ring, tick);
            if (!PrimordialEngineRingOffset.clearsRing(x, y, z, ring, angle, fat)) return false;
        }
        return true;
    }

    private static boolean clear(float x, float y, float z, float tick, float starRadius, float planetRadius) {
        if (!starClear(x, y, z, starRadius, planetRadius)) return false;
        for (int ring = 0; ring < PrimordialEngineRingOffset.COUNT; ring++) {
            float angle = PrimordialEngineRingOffset.angleDegrees(ring, tick);
            if (!PrimordialEngineRingOffset.clearsRing(x, y, z, ring, angle, planetRadius)) return false;
        }
        return true;
    }

    private static boolean starClear(float x, float y, float z, float starRadius, float planetRadius) {
        float radius = starRadius > 1.0f ? starRadius : 13.0f;
        float distance = (float) Math.sqrt(x * x + y * y + z * z);
        return distance > radius + planetRadius + 2.0f;
    }

    private static float channelX(int index, float tick) {
        float x = index == COUNT ? MINIATURE_X : LATERAL_X[index];
        return x + (float) Math.sin(tick * 0.05f + index * 1.7f) * 0.35f;
    }

    private static float channelY(int index, float tick) {
        float y = index == COUNT ? MINIATURE_Y : LATERAL_Y[index];
        return y + (float) Math.sin(tick * 0.037f + index) * 0.45f;
    }

    private static void approachInto(float x, float y, float targetX, float targetY, float maxStep) {
        float dx = targetX - x;
        float dy = targetY - y;
        float reach = (float) Math.hypot(dx, dy);
        if (reach <= maxStep || reach == 0.0f) {
            heldX = targetX;
            heldY = targetY;
            return;
        }
        float scale = maxStep / reach;
        heldX = x + dx * scale;
        heldY = y + dy * scale;
    }

    private static float moveToward(float current, float goal, float maxDelta) {
        float delta = goal - current;
        if (delta > maxDelta) return current + maxDelta;
        if (delta < -maxDelta) return current - maxDelta;
        return goal;
    }
}
