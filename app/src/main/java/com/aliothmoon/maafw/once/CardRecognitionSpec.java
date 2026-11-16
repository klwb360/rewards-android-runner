package com.aliothmoon.maafw.once;

/** A read-only recognition recipe. It is independent of historical test menus and never grants input. */
record CardRecognitionSpec(Kind kind,int index,boolean refined,boolean separatedCoins,int expectedDay) {
    enum Kind { CHECK_IN, DAILY_ACTIVITY, MORE_ACTIVITY, WEEKLY_READ_ONLY }
    CardRecognitionSpec {
        if(kind==null||((kind==Kind.CHECK_IN||kind==Kind.WEEKLY_READ_ONLY)?index!=0:index<1||index>8)
                ||expectedDay<0||expectedDay>7||expectedDay!=0&&kind!=Kind.CHECK_IN)
            throw new IllegalArgumentException("Fixed local card recognition recipe required");
    }
    static CardRecognitionSpec activity(OverviewUiRegions.Role role,int index){
        if(role!=OverviewUiRegions.Role.DAILY_ACTIVITIES&&role!=OverviewUiRegions.Role.MORE_ACTIVITIES)
            throw new IllegalArgumentException("Activity role required");
        return new CardRecognitionSpec(role==OverviewUiRegions.Role.DAILY_ACTIVITIES?Kind.DAILY_ACTIVITY:Kind.MORE_ACTIVITY,index,true,false,0);
    }
    static CardRecognitionSpec checkIn(int expectedDay){return new CardRecognitionSpec(Kind.CHECK_IN,0,true,true,expectedDay);}
    static CardRecognitionSpec historical(RemainingCardChoice choice){
        if(choice==null)return null;
        return new CardRecognitionSpec(choice.weekly()?Kind.WEEKLY_READ_ONLY:
                choice.role()==OverviewUiRegions.Role.DAILY_ACTIVITIES?Kind.DAILY_ACTIVITY:Kind.MORE_ACTIVITY,
                choice.index(),choice.repair(),choice.coinRepair(),0);
    }
    boolean coinPanel(){return kind==Kind.CHECK_IN||kind==Kind.WEEKLY_READ_ONLY;}
    boolean checkIn(){return kind==Kind.CHECK_IN;}
    boolean weekly(){return kind==Kind.WEEKLY_READ_ONLY;}
    String key(){return kind.name()+"_"+index;}
}
