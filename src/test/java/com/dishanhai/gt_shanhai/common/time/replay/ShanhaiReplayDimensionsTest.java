// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import org.junit.jupiter.api.Test;
class ShanhaiReplayDimensionsTest {
 @Test void coalescesEachDimensionIndependently() {
  var j=new ShanhaiReplayJournal(16);j.record(10,1,44L,100,101,-1);j.record(10,2,44L,200,201,-1);j.record(10,1,44L,101,102,-1);
  var changes=j.takeNewestTick();
  if(changes.size()!=2)throw new AssertionError("dimension collision discarded state: "+changes);
  var one=changes.stream().filter(c->c.dim()==1).findFirst().orElseThrow();
  var two=changes.stream().filter(c->c.dim()==2).findFirst().orElseThrow();
  if(one.fromState()!=100 || one.afterState()!=102 || two.fromState()!=200)throw new AssertionError("incorrect coalescing");
 }
}
