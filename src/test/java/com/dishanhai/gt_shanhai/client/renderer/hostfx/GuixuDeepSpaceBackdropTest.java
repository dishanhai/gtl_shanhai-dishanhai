package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensSpec;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class GuixuDeepSpaceBackdropTest {
    @Test void blackBackdropStaysBehindTheEnvironmentHasSoftEdgesAndABoundedBudget() throws Exception {
        Class<?> type = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuDeepSpaceBackdrop");
        Class<?> sinkType = Class.forName(type.getName() + "$Sink");
        for (double horizon : new double[]{.8, 3, 12})
        for (float[] camera : new float[][]{{0, 0, 60}, {30, 50, -80}, {0, 60, 0}}) {
            int[] vertices = {0}; float[] alphaRange = {1, 0};
            double radius = 4 + 2.2 * horizon;
            double length = Math.sqrt(camera[0]*camera[0]+camera[1]*camera[1]+camera[2]*camera[2]);
            Object sink = Proxy.newProxyInstance(sinkType.getClassLoader(), new Class<?>[]{sinkType}, (proxy, method, args) -> {
                if (!method.getName().equals("vertex")) return null;
                double x = ((Number)args[0]).doubleValue(), y = ((Number)args[1]).doubleValue(), z = ((Number)args[2]).doubleValue();
                float alpha = ((Number)args[3]).floatValue();
                assertTrue(Double.isFinite(x+y+z+alpha));
                assertTrue(Math.sqrt(x*x+y*y+z*z) <= radius + .001,
                        "The new environment must stay near the black hole, inside the 48-block machine ring");
                if (length > 0) assertEquals(-radius*.25, (x*camera[0]+y*camera[1]+z*camera[2])/length, .0001);
                alphaRange[0] = Math.min(alphaRange[0], alpha); alphaRange[1] = Math.max(alphaRange[1], alpha);
                assertTrue(alpha >= 0 && alpha <= 1);vertices[0]++;return null;
            });
            type.getMethod("emit", float[].class, double.class, sinkType).invoke(null, camera, horizon, sink);
            assertTrue(vertices[0] > 0 && vertices[0] <= 4096);
            assertEquals(0, alphaRange[0]); assertTrue(alphaRange[1] > .99);
        }
    }

    @Test void insideLensNeverCapturesAnyBlackBackdropVertices() throws Exception {
        for (double horizon : new double[]{.8, 3, 5.196152422706632, 12}) {
            double threshold = BlackHoleLensSpec.insideThresholdBlocks(horizon / BlackHoleLensSpec.B_CRIT);
            for (double ratio : new double[]{0, .25, .9, .999999, 1}) {
                float d = (float)(threshold * ratio);
                // Float cameras can round just outside the double threshold.
                if (d > threshold) d = Math.nextDown(d);
                for (float[] camera : new float[][]{{d, 0, 0}, {0, -d, 0}, {0, 0, d}})
                    assertEquals(0, emission(camera, horizon)[0], "Inside source must contain no black curtain");
            }
        }
    }

    @Test void backdropFadesContinuouslyOutsideTheInsideLensBoundary() throws Exception {
        double horizon = 5.196152422706632;
        double threshold = BlackHoleLensSpec.insideThresholdBlocks(horizon / BlackHoleLensSpec.B_CRIT);
        double previous = 0;
        for (double offset : new double[]{.0001, .01, .1, .5, 1, 2, 4, 20}) {
            double alpha = emission(new float[]{0, 0, (float)(threshold + offset)}, horizon)[1];
            assertTrue(alpha >= previous && alpha <= .99701, "Approaching the hole must smoothly remove the curtain");
            if (offset <= .01) assertTrue(alpha < .001, "No opacity jump at the inside boundary");
            previous = alpha;
        }
        assertTrue(previous > .99, "Distant deep space must retain its black background");
    }

    private static double[] emission(float[] camera, double horizon) throws Exception {
        Class<?> type = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuDeepSpaceBackdrop");
        Class<?> sinkType = Class.forName(type.getName() + "$Sink");
        double[] result = {0, 0};
        Object sink = Proxy.newProxyInstance(sinkType.getClassLoader(), new Class<?>[]{sinkType}, (proxy, method, args) -> {
            if (method.getName().equals("vertex")) {
                result[0]++;
                result[1] = Math.max(result[1], ((Number)args[3]).doubleValue());
            }
            return null;
        });
        type.getMethod("emit", float[].class, double.class, sinkType).invoke(null, camera, horizon, sink);
        return result;
    }
}
