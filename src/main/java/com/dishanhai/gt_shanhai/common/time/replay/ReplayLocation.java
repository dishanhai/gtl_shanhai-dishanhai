// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

/** Identifies a position without conflating equal coordinates in different dimensions. */
record ReplayLocation(int dimension, long position) {}
