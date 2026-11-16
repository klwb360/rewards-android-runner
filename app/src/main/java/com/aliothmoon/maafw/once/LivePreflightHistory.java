package com.aliothmoon.maafw.once;

import java.io.IOException;

/** Read-only same-day history decisions. A prior attempt never proves completion. */
final class LivePreflightHistory {
    private final LiveRewardsLedger ledger;
    private final String day;
    LivePreflightHistory(LiveRewardsLedger ledger,String day){
        if(ledger==null||day==null||!day.equals(ledger.day()))throw new IllegalArgumentException("Same-day journal required");
        this.ledger=ledger;this.day=day;
    }
    boolean attempted(LiveRewardsTypes.Task seed)throws IOException{
        if(!day.equals(ledger.day()))throw new SecurityException("Preflight journal day changed");
        return ledger.attempted(seed);
    }
    static LiveRewardsTypes.Task checkInSeed(){return new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.CHECK_IN,
            LiveCardIdentity.hashText("CHECK_IN"),0,"",0,false,false,-1);}
    static LiveRewardsTypes.Task unreadable(LiveRewardsTypes.Task seed){
        if(seed==null)throw new IllegalArgumentException("Current roster seed required");
        return new LiveRewardsTypes.Task(seed.kind(),seed.descriptorHash(),seed.ordinal(),"",seed.points(),false,false,-1);
    }
}
