// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import org.junit.jupiter.api.Test;
import java.util.*;
class ShanhaiReplayPacerBudgetTest {
 @Test void preservesBudgetAndLayerOrderAcrossTicks() {
  var blocks=new ShanhaiReplayJournal(5000); var progress=new ShanhaiReplayProgressJournal(16);
  for(int i=0;i<3000;i++) if(!blocks.record(10,0,i,1,2,-1)) throw new AssertionError("fixture");
  progress.record(10,0,17,3,4);
  var journals=new ShanhaiReplayPacer.Journals(blocks,new ShanhaiReplayEntityJournal(4),progress,new ShanhaiReplayBlockEntityJournal(4),new ShanhaiReplayScalarJournal(4));
  int[] writes={0}; var order=new ArrayList<Long>(); var stages=new ArrayList<String>();
  var sink=new ShanhaiReplayApplier.Sink(){
   public ShanhaiReplayApplier.Skip precheck(int d,long p,int s){return ShanhaiReplayApplier.Skip.NONE;}
   public boolean setAir(int d,long p,int f){writes[0]++;return true;}
   public boolean isAir(int d,long p){return false;}
   public boolean setTarget(int d,long p,int s,int f,int n){writes[0]++;order.add(p);return true;}
  };
  ShanhaiReplayProgressJournal.Sink ps=(d,p,v)->{writes[0]++;stages.add("progress");return ShanhaiReplayProgressJournal.Outcome.WRITTEN;};
  var targets=new ShanhaiReplayPacer.Targets(sink,null,ps,null,null);var pacer=new ShanhaiReplayPacer();pacer.begin(1,10);
  int rounds=0;
  while(pacer.isActive()) {writes[0]=0;var step=pacer.step(journals,targets);rounds++;
   if(writes[0]>ShanhaiReplayConst.MAX_WRITES_PER_STEP)throw new AssertionError("budget "+writes[0]);
   if(rounds<3 && (!pacer.hasPendingSlice() || pacer.cursor()!=10 || step.slices()!=0 || !stages.isEmpty()))throw new AssertionError("advanced partial slice");
  }
  if(rounds!=3 || order.size()!=3000 || !stages.equals(List.of("progress")))throw new AssertionError("missing work");
  for(int i=0;i<3000;i++)if(order.get(i)!=2999L-i)throw new AssertionError("LIFO at "+i);
  if(pacer.hasPendingSlice() || pacer.cursor()!=9)throw new AssertionError("cursor completion");
 }
}
