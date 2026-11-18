// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import org.junit.jupiter.api.Test;
class ShanhaiReplayPacerFailureTest {
 @Test void failedWriteStopsTheCurrentSlice() {
  var blocks=new ShanhaiReplayJournal(16); for(int i=0;i<3;i++) blocks.record(10,0,i,1,2,-1);
  var js=new ShanhaiReplayPacer.Journals(blocks,new ShanhaiReplayEntityJournal(2),new ShanhaiReplayProgressJournal(2),new ShanhaiReplayBlockEntityJournal(2),new ShanhaiReplayScalarJournal(2));
  int[] calls={0};var sink=new ShanhaiReplayApplier.Sink(){
   public ShanhaiReplayApplier.Skip precheck(int d,long p,int s){return ShanhaiReplayApplier.Skip.NONE;}
   public boolean setAir(int d,long p,int f){return true;} public boolean isAir(int d,long p){return true;}
   public boolean setTarget(int d,long p,int s,int f,int n){calls[0]++;return false;}
  };var p=new ShanhaiReplayPacer();p.begin(1,10);
  var step=p.step(js,new ShanhaiReplayPacer.Targets(sink,null,null,null,null));
  if(!step.stop().code().equals("failed") || p.cursor()!=10 || calls[0]!=1) throw new AssertionError("failure was treated as success: "+step.stop()+" cursor="+p.cursor()+" writes="+calls[0]);
 }
}
