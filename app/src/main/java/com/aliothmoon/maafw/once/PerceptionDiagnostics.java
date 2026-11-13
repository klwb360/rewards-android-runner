package com.aliothmoon.maafw.once;
/** Four fixed query slots; scalar-only, no raw text or frame retained. */
final class PerceptionDiagnostics {
 enum State { NOT_POSTED, POST_ATTEMPTED, TERMINAL, DETAIL_READ }
 private final State[] states=new State[4]; private final int[] statuses=new int[4];
 private final boolean[] hits=new boolean[4]; private int width,height; private String detail="detail=NOT_READ";
 PerceptionDiagnostics(){java.util.Arrays.fill(states,State.NOT_POSTED);}
 synchronized void frame(int w,int h){if(w<=0||h<=0||w>4096||h>4096)throw new IllegalArgumentException();width=w;height=h;}
 synchronized void posted(RewardsQuery query){int i=query.ordinal();if(states[i]!=State.NOT_POSTED)throw new IllegalStateException();states[i]=State.POST_ATTEMPTED;}
 synchronized void terminal(RewardsQuery query,int status){int i=query.ordinal();if(states[i]!=State.POST_ATTEMPTED||(status!=3000&&status!=4000))throw new IllegalStateException();states[i]=State.TERMINAL;statuses[i]=status;}
 synchronized void detail(RewardsQuery query,boolean hit){int i=query.ordinal();if(states[i]!=State.TERMINAL)throw new IllegalStateException();states[i]=State.DETAIL_READ;hits[i]=hit;}
 synchronized void decoded(OcrDetailDecoder.Result result){detail=result.summary();}
 synchronized void sizeRejected(long size){detail="detail=SIZE bytes="+Math.max(0,Math.min(65537,size));}
 synchronized String published(boolean ownerMatched,boolean released,boolean cancelled){
  if(!ownerMatched||!released||cancelled)return null;
  StringBuilder out=new StringBuilder("Perception scalars: frame=").append(width).append('x').append(height).append("; ").append(detail);
  for(RewardsQuery q:RewardsQuery.values()){int i=q.ordinal();out.append("; ").append(q).append('=').append(states[i]).append('/').append(statuses[i]).append('/').append(hits[i]);}
  return out.toString();
 }
}
