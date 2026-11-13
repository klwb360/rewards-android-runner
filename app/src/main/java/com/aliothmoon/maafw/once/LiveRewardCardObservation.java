package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Same-image full-display adapter. Initial supported route is deliberately bounded:
 * exact daily heading + its aligned region ratio + a later exact more-activities heading.
 * No assumed screen location, tree bounds, supplied ROI, rescale, raw-row retention or actor.
 * Semantics are bounded in-memory task anchors, never exported as diagnostic OCR text. */
final class LiveRewardCardObservation {
    enum Route { DAILY_REGION_COUNTER, MORE_VISIBLE_ITEM }
    static final class TargetSpec {
        private final Route route;
        private final String exactTitle;
        private final int points,familySize;
        private final String targetBody,siblingBody;
        private final boolean nativeReturn,boundedProfile,selectedPending,uniquePending,uniqueAfter;
        private TargetSpec(Route route,String title,int points){this(route,title,points,1,null,null,false);}
        private TargetSpec(Route route,String title,int points,int familySize,String targetBody,String siblingBody,boolean nativeReturn){this(route,title,points,familySize,targetBody,siblingBody,nativeReturn,false);}
        private TargetSpec(Route route,String title,int points,int familySize,String targetBody,String siblingBody,boolean nativeReturn,boolean boundedProfile){this(route,title,points,familySize,targetBody,siblingBody,nativeReturn,boundedProfile,false);}
        private TargetSpec(Route route,String title,int points,int familySize,String targetBody,String siblingBody,boolean nativeReturn,boolean boundedProfile,boolean selectedPending){
            this(route,title,points,familySize,targetBody,siblingBody,nativeReturn,boundedProfile,selectedPending,false,false);
        }
        private TargetSpec(Route route,String title,int points,int familySize,String targetBody,String siblingBody,boolean nativeReturn,boolean boundedProfile,boolean selectedPending,boolean uniquePending,boolean uniqueAfter){
            this.uniquePending=uniquePending;this.uniqueAfter=uniqueAfter;
            this.route=route;exactTitle=title;this.points=points;this.familySize=familySize;this.targetBody=targetBody;this.siblingBody=siblingBody;this.nativeReturn=nativeReturn;this.boundedProfile=boundedProfile;this.selectedPending=selectedPending;
        }
        static TargetSpec dailyRegionCounter(){return new TargetSpec(Route.DAILY_REGION_COUNTER,null,0);}
        /** Reviewed one-time browse target only. Never automatically fall back to question cards. */
        static TargetSpec moreQuoteOfTheDay(){return new TargetSpec(Route.MORE_VISIBLE_ITEM,"Quote of the day",5);}
        static TargetSpec moreTriviaChallenge(){return new TargetSpec(Route.MORE_VISIBLE_ITEM,"Do you know the answer?",5,2,
            "Challenge yourself with these trivia questions","Test your knowledge on these topics",true);}
        static TargetSpec moreTriviaChallengeBounded(){return new TargetSpec(Route.MORE_VISIBLE_ITEM,"Do you know the answer?",5,2,
            "Challenge yourself with these trivia questions","Test your knowledge on these topics",true,true);}
        static TargetSpec moreTriviaSelectedPendingB(){return new TargetSpec(Route.MORE_VISIBLE_ITEM,"Do you know the answer?",5,2,
            "Test your knowledge on these topics","Challenge yourself with these trivia questions",true,false,true);}
        static TargetSpec moreUniquePending(){return new TargetSpec(Route.MORE_VISIBLE_ITEM,"Do you know the answer?",5,2,null,null,true,false,false,true,false);}
        TargetSpec forObservation(boolean after){return uniquePending?new TargetSpec(route,exactTitle,points,familySize,targetBody,siblingBody,nativeReturn,boundedProfile,selectedPending,true,after):this;}
        boolean uniquePending(){return uniquePending;}
        boolean selectedPending(){return selectedPending;}
        boolean boundedProfile(){return boundedProfile;}
        Route route(){return route;}
        boolean requiresNativeReturn(){return nativeReturn;}
    }
    enum Status { OBSERVED, SECTION_COMPLETE_SKIP, HOLD }
    enum Reason { READY, COMPLETE_REGION, INVALID_INPUT, BAD_NATIVE_BATCH, CANCELED,
        DAILY_HEADING_NOT_UNIQUE, LOWER_BOUNDARY_NOT_UNIQUE, COUNTER_NOT_UNIQUE,
        ROW_CROSSES_SECTION, LOCAL_CONFIDENCE, LOCAL_TEXT_UNKNOWN, BAD_PROGRESS, LOCATOR_BLOCKED,
        CARD_IDENTITY_NOT_UNIQUE, MORE_HEADING_NOT_UNIQUE, TARGET_TITLE_NOT_UNIQUE, MORE_CONTEXT_UNPROVEN, MORE_GEOMETRY_BLOCKED, MORE_ASSOCIATION_BLOCKED, MORE_FAMILY_COVERAGE, MORE_FAMILY_BODY }
    /** New first-A business profile only. Empty completed body cannot prove static A/B identity. */
    static boolean pendingBoundedFirstFamily(Frame frame){
        if(frame==null||frame.status()!=Status.OBSERVED||frame.cards().size()!=2)return false;
        var spec=TargetSpec.moreTriviaChallengeBounded();int a=0,b=0;
        for(var card:frame.cards()){
            if(card.complete||card.route!=Route.MORE_VISIBLE_ITEM||card.points!=5||!card.title.equals(spec.exactTitle))return false;
            if(card.bodySemantic.equals(spec.targetBody))a++;else if(card.bodySemantic.equals(spec.siblingBody))b++;else return false;
        }
        return a==1&&b==1;
    }
    static final class Card {
        private final String title, identity,bodySemantic;
        private final Route route;
        private final Box titleBox,card,indicator,number;
        private final int points,bodyRows;
        private final boolean complete;
        private Card(Route route,String title,String identity,String bodySemantic,int bodyRows,Box titleBox,
                     Box card,Box indicator,Box number,int points,boolean complete) {
            this.route=route;this.title=title;this.identity=identity;this.bodySemantic=bodySemantic;this.bodyRows=bodyRows;this.titleBox=titleBox;
            this.card=card;this.indicator=indicator;this.number=number;this.points=points;this.complete=complete;
        }
        String title(){return title;}
        Box cardBox(){return card;}
        Box indicatorBox(){return indicator;}
        Box numberBox(){return number;}
        int displayedPoints(){return points;}
        boolean complete(){return complete;}
        boolean sameTask(Card other){return other!=null&&points==other.points&&identity.equals(other.identity);}
        boolean configuredTarget(TargetSpec spec){return spec!=null&&title.equals(spec.exactTitle)&&points==spec.points
            &&(spec.targetBody==null||bodySemantic.equals(spec.targetBody));}
        private Card familyBody(String canonical){
            return new Card(route,title,"MORE_FAMILY\n更多活动\n"+title+"\n"+canonical,canonical,bodyRows,titleBox,card,indicator,number,points,complete);
        }
        boolean matchesRosterCompactCompletion(Card after,TargetSpec spec){
            return spec!=null&&spec.familySize==2&&spec.nativeReturn&&configuredTarget(spec)&&after!=null
                &&route==Route.MORE_VISIBLE_ITEM&&after.route==route&&after.title.equals(title)&&after.points==points
                &&!complete&&after.complete&&bodyRows>0&&after.bodyRows==0&&compactLayout(after,.22);
        }
        /** Narrow AFTER relation, never target selection or standalone completion proof.
         * Both Cards are sealed by independent native per-item validation. Session must first
         * recheck fresh same-scope source/More/header context. No pixel or old OCR replay. */
        boolean matchesControlledCompactCompletion(Card after,TargetSpec spec){
            if(after==null||spec==null||spec.route!=Route.MORE_VISIBLE_ITEM
                ||!"Quote of the day".equals(spec.exactTitle)||spec.points!=5
                ||route!=Route.MORE_VISIBLE_ITEM||after.route!=route
                ||!title.equals(spec.exactTitle)||!after.title.equals(title)||points!=5||after.points!=points
                ||complete||!after.complete||bodyRows<1||after.bodyRows!=0)return false;
            return compactLayout(after);
        }
        private boolean compactLayout(Card after){return compactLayout(after,.20);}
        private boolean compactLayout(Card after,double maxAspect){
            double width=card.width(),afterWidth=after.card.width();
            if(Math.abs(afterWidth-width)>Math.max(4,.02*width))return false;
            // Bounded compact family, calibrated as a conservative hypothesis from the
            // observed external completion layout, not claimed as a native AFTER pass.
            double shrink=after.card.height()/(double)card.height();
            double compactAspect=after.card.height()/afterWidth;
            if(shrink<.40||shrink>.72||compactAspect<.10||compactAspect>maxAspect)return false;
            double leftBefore=(titleBox.left()-card.left())/width;
            double leftAfter=(after.titleBox.left()-after.card.left())/afterWidth;
            double topBefore=(titleBox.top()-card.top())/width;
            double topAfter=(after.titleBox.top()-after.card.top())/afterWidth;
            if(Math.abs(leftAfter-leftBefore)>.04||Math.abs(topAfter-topBefore)>.05
                ||!similarScale(titleBox.width()/width,after.titleBox.width()/afterWidth,.80,1.20)
                ||!similarScale(titleBox.height()/width,after.titleBox.height()/afterWidth,.70,1.40))return false;
            double tickY=(after.indicator.centerY()-after.card.top())/after.card.height();
            return after.titleBox.right()<after.number.left()&&tickY>=.25&&tickY<=.75
                &&Math.abs(after.titleBox.centerY()-after.indicator.centerY())<=.45*after.card.height();
        }
        private static boolean similarScale(double before,double after,double min,double max){
            return before>0&&after>=before*min&&after<=before*max;
        }
        // No generated toString(): task anchors must not leak into receipts or disk logs.
    }
    enum AdapterGate { NOT_EVALUATED, NONE, FAMILY_RESULT, POINTS_OR_CONTEXT, PARTITION_MEMBERSHIP,
        TITLE_MEMBERSHIP, TITLE_GEOMETRY, LOCAL_ROW_STATE, LEFT_KNOWN_CONFLICT,
        RIGHT_STATE_FIELD, CENTRAL_BODY_FIELD, BODY_CLASSIFICATION, AUXILIARY_BOUNDARY,
        UNEXPLAINED_CARD, FAMILY_CARD_COUNT, UNEXPLAINED_ROW }
    enum BodyClass { NOT_EVALUATED, TARGET, SIBLING, EMPTY_COMPLETE, CONFLICT }
    enum AuxiliaryKind { PUZZLE, QUOTE }
    record FamilyItemDiagnostic(int frameItemIndex,int points,BodyClass canonicalBody,
            RewardRegionLocator.DiagnosticCheck partitionValid,int leftRows,int identityRows,int stateRows) {}
    record AuxiliaryDiagnostic(AuxiliaryKind kind,int titleCount,RewardRegionLocator.DiagnosticCheck highConfidence,
            RewardRegionLocator.VisibleItemStatus association,RewardRegionLocator.DiagnosticCheck pointsFive,
            RewardRegionLocator.DiagnosticCheck afterFamily,RewardRegionLocator.DiagnosticCheck geometryValid,
            int failureCardMatches,Box failureCardBox,int failureLocalRows,boolean failureRowsTruncated,
            RewardRegionLocator.CrossingCoordinateSpace failureCoordinateSpace,boolean failureRowsPartitioned,
            List<RewardRegionLocator.LocalRowDiagnostic> failureRows) {
        AuxiliaryDiagnostic { failureRows=List.copyOf(failureRows); }
    }
    private static final class ItemTrace {
        final int index;final int points;BodyClass body=BodyClass.NOT_EVALUATED;
        RewardRegionLocator.DiagnosticCheck partition=RewardRegionLocator.DiagnosticCheck.NOT_EVALUATED;
        int left=-1,identity=-1,state=-1;
        ItemTrace(int index,int points){this.index=index;this.points=points;}
        FamilyItemDiagnostic snapshot(){return new FamilyItemDiagnostic(index,points,body,partition,left,identity,state);}
    }
    private static final class AuxiliaryTrace {
        final AuxiliaryKind kind;int count;
        RewardRegionLocator.DiagnosticCheck high=RewardRegionLocator.DiagnosticCheck.NOT_EVALUATED,
            points=RewardRegionLocator.DiagnosticCheck.NOT_EVALUATED,after=RewardRegionLocator.DiagnosticCheck.NOT_EVALUATED,
            geometry=RewardRegionLocator.DiagnosticCheck.NOT_EVALUATED;
        RewardRegionLocator.VisibleItemStatus association;
        int failureMatches=-1,failureCount=-1;Box failureBox;boolean failureTruncated;
        RewardRegionLocator.CrossingCoordinateSpace failureSpace=RewardRegionLocator.CrossingCoordinateSpace.NOT_AVAILABLE;
        List<RewardRegionLocator.LocalRowDiagnostic> failureRows=List.of();
        AuxiliaryTrace(AuxiliaryKind kind){this.kind=kind;}
        AuxiliaryDiagnostic snapshot(){return new AuxiliaryDiagnostic(kind,count,high,association,points,after,geometry,failureMatches,failureBox,failureCount,failureTruncated,failureSpace,false,failureRows);}
    }
    /** Only bounded typed scalars, never OCR strings, IDs, hashes or image data. */
    record Diagnostic(int nativeRows,int headerCount,double headerConfidence,int targetTitleCount,double targetTitleConfidence,
                      int counterCount,double counterConfidence,RewardRegionLocator.Status geometry,int visualCards,
                      int targetCardMatches,boolean targetCardFullyVisible,Box targetCard,Box indicator,
                      RewardRegionLocator.VisibleItemStatus association,RewardRegionGate.Fraction fraction,
                      int exactTitleRows,int knownSingleTitleRows,List<RewardRegionLocator.TitleDiagnostic> titleEvidence,
                      RewardRegionLocator.FamilyStatus familyStatus,RewardRegionLocator.FamilyReason familyReason,
                      int familyItemCount,RewardRegionLocator.FailureDiagnostic familyFailure,
                      AdapterGate adapterFirstGate,List<FamilyItemDiagnostic> familyItemDiagnostics,
                      List<AuxiliaryDiagnostic> auxiliaryDiagnostics,int unexplainedCardsSeen,int unexplainedRowsSeen){
        Diagnostic{titleEvidence=List.copyOf(titleEvidence);familyItemDiagnostics=List.copyOf(familyItemDiagnostics);auxiliaryDiagnostics=List.copyOf(auxiliaryDiagnostics);}
        String summary(){return "nativeRows="+nativeRows+" moreHeaders="+headerCount+" headerConfidence="+headerConfidence
            +" targetTitles="+targetTitleCount+" targetTitleConfidence="+targetTitleConfidence+" headerCounters="+counterCount
            +" counterConfidence="+counterConfidence+" geometry="+geometry+" visualCards="+visualCards
            +" targetCardMatches="+targetCardMatches+" targetCardFullyVisible="+targetCardFullyVisible
            +" targetCard="+scalarBox(targetCard)+" indicator="+scalarBox(indicator)+" association="+association
            +" headerFraction="+(fraction==null?"UNOBSERVED":fraction.numerator()+"/"+fraction.denominator())
            +" exactTitleRows="+exactTitleRows+" knownSingleTitleRows="+knownSingleTitleRows+" knownTitleEvidenceShown="+titleEvidence.size()+" knownTitleEvidence="+titleEvidence
            +" familyStatus="+familyStatus+" familyReason="+familyReason+" familyItemCount="+familyItemCount+" familyFailure="+familyFailure
            +" adapterFirstGate="+adapterFirstGate+" familyItemDiagnostics="+familyItemDiagnostics+" auxiliaryDiagnostics="+auxiliaryDiagnostics
            +" unexplainedCardsSeen="+unexplainedCardsSeen+" unexplainedRowsSeen="+unexplainedRowsSeen;}
    }
    private static String scalarBox(Box b){return b==null?"NONE":b.left()+","+b.top()+","+b.right()+","+b.bottom();}
    private static final class MoreTrace {
        int nativeRows=-1,headers,targets,counters=-1,cards=-1,matches,exactTitleRows,knownSingleTitleRows;
        List<RewardRegionLocator.TitleDiagnostic> titleEvidence=List.of();
        RewardRegionLocator.FamilyStatus familyStatus;RewardRegionLocator.FamilyReason familyReason;
        int familyItemCount=-1;RewardRegionLocator.FailureDiagnostic familyFailure;
        AdapterGate adapterFirstGate=AdapterGate.NOT_EVALUATED;
        final List<ItemTrace> items=new ArrayList<>();final List<AuxiliaryTrace> auxiliaries=new ArrayList<>();
        int unexplainedCardsSeen=-1,unexplainedRowsSeen=-1;boolean auxiliaryFailureCaptured;
        void fail(AdapterGate gate){if(adapterFirstGate==AdapterGate.NONE||adapterFirstGate==AdapterGate.NOT_EVALUATED)adapterFirstGate=gate;}
        double headerConfidence=-1,targetConfidence=-1,counterConfidence=-1;
        boolean complete;Box card,indicator;RewardRegionLocator.Status geometry;
        RewardRegionLocator.VisibleItemStatus association;RewardRegionGate.Fraction fraction;
        Diagnostic snapshot(){
            List<FamilyItemDiagnostic> itemScalars=new ArrayList<>();for(ItemTrace item:items)itemScalars.add(item.snapshot());
            List<AuxiliaryDiagnostic> auxiliaryScalars=new ArrayList<>();for(AuxiliaryTrace item:auxiliaries)auxiliaryScalars.add(item.snapshot());
            return new Diagnostic(nativeRows,headers,headerConfidence,targets,targetConfidence,counters,
            counterConfidence,geometry,cards,matches,complete,card,indicator,association,fraction,exactTitleRows,knownSingleTitleRows,titleEvidence,familyStatus,familyReason,familyItemCount,familyFailure,adapterFirstGate,itemScalars,auxiliaryScalars,unexplainedCardsSeen,unexplainedRowsSeen);}
    }
    /** Safe sealed same-frame business evidence. No readonly result or original OCR row is retained. */
    private static final class UniqueSeal {
        final boolean accepted,headerSkip;final RewardRegionLocator.FailureGate gate;final int failureCard,pending,complete,known,unmodeledText;final Card target;final RewardRegionLocator.VetoDiagnostic veto;
        UniqueSeal(boolean accepted,boolean headerSkip,RewardRegionLocator.FailureGate gate,int failureCard,int pending,int complete,int known,Card target){this(accepted,headerSkip,gate,failureCard,pending,complete,known,target,0);}
        UniqueSeal(boolean accepted,boolean headerSkip,RewardRegionLocator.FailureGate gate,int failureCard,int pending,int complete,int known,Card target,int unmodeledText){this(accepted,headerSkip,gate,failureCard,pending,complete,known,target,unmodeledText,RewardRegionLocator.VetoDiagnostic.none());}
        UniqueSeal(boolean accepted,boolean headerSkip,RewardRegionLocator.FailureGate gate,int failureCard,int pending,int complete,int known,Card target,int unmodeledText,RewardRegionLocator.VetoDiagnostic veto){this.veto=veto;this.unmodeledText=unmodeledText;this.accepted=accepted;this.headerSkip=headerSkip;this.gate=gate;this.failureCard=failureCard;this.pending=pending;this.complete=complete;this.known=known;this.target=target;}
        String summary(){return "uniqueBusinessProof="+(headerSkip?"HEADER_FULL_SKIP":accepted?"STRICT_NATIVE":"HOLD")+" uniqueFirstGate="+gate+" failureCard="+failureCard+" pending="+pending+" ownNumberTicks="+complete+" consumedCandidateVeto="+(accepted?"APPLIED":"NOT_QUALIFIED")+" unmodeledText="+(accepted?Integer.toString(unmodeledText):"UNOBSERVED")+" "+veto.summary()+" candidateUniqueness=RECOGNIZED_VISIBLE_CARDS_ONLY completeRewardsFamily=false causalAttribution=UNPROVEN";}
    }
    private static final class SelectedProof {
        final boolean accepted;final RewardRegionLocator.FailureGate gate;final int failureCard,pending,complete,targetMatches;
        final List<Box> unmodeled;
        private SelectedProof(boolean accepted,RewardRegionLocator.FailureGate gate,int failureCard,int pending,int complete,int targetMatches,List<Box> unmodeled){
            this.accepted=accepted;this.gate=gate;this.failureCard=failureCard;this.pending=pending;this.complete=complete;this.targetMatches=targetMatches;this.unmodeled=List.copyOf(unmodeled);}
    }
    static final class Frame {
        private final Route route;
        private final Diagnostic diagnostic;private final SelectedProof selectedProof;private final UniqueSeal uniqueSeal;
        private final Status status;
        private final Reason reason;
        private final List<Card> cards;
        private final Box section;
        private final RewardRegionGate.Fraction fraction;
        private Frame(Status s,Reason r,List<Card> cards,Box section,RewardRegionGate.Fraction f) {
            this(Route.DAILY_REGION_COUNTER,s,r,cards,section,f);
        }
        private Frame(Route route,Status s,Reason r,List<Card> cards,Box section,RewardRegionGate.Fraction f) {
            this(route,s,r,cards,section,f,null);
        }
        private Frame(Route route,Status s,Reason r,List<Card> cards,Box section,RewardRegionGate.Fraction f,Diagnostic diagnostic) {this(route,s,r,cards,section,f,diagnostic,null);}
        private Frame(Route route,Status s,Reason r,List<Card> cards,Box section,RewardRegionGate.Fraction f,Diagnostic diagnostic,SelectedProof selectedProof) {this(route,s,r,cards,section,f,diagnostic,selectedProof,null);}
        private Frame(Route route,Status s,Reason r,List<Card> cards,Box section,RewardRegionGate.Fraction f,Diagnostic diagnostic,SelectedProof selectedProof,UniqueSeal uniqueSeal) {
            this.uniqueSeal=uniqueSeal;
            this.route=route;this.diagnostic=diagnostic;this.selectedProof=selectedProof;status=s;reason=r;this.cards=List.copyOf(cards);this.section=section;fraction=f;
        }
        RewardRegionLocator.VetoDiagnostic uniqueVetoDiagnostic(){return uniqueSeal==null?RewardRegionLocator.VetoDiagnostic.none():uniqueSeal.veto;}
        boolean uniqueProofReady(){return uniqueSeal!=null&&(uniqueSeal.headerSkip&&status==Status.SECTION_COMPLETE_SKIP||uniqueSeal.accepted&&status==Status.OBSERVED);}
        boolean uniqueBefore(){return uniqueProofReady()&&!uniqueSeal.headerSkip&&uniqueSeal.pending==1&&uniqueSeal.target!=null&&!uniqueSeal.target.complete();}
        boolean uniqueAllComplete(){return uniqueProofReady()&&!uniqueSeal.headerSkip&&uniqueSeal.pending==0&&uniqueSeal.complete==cards.size()&&cards.size()==5&&fraction!=null&&fraction.numerator()==fraction.denominator();}
        Card uniqueTarget(){return uniqueBefore()?uniqueSeal.target:null;}
        String uniqueEvidenceSummary(){return uniqueSeal==null?"uniqueBusinessProof=NOT_OBSERVED":uniqueSeal.summary();}
        boolean selectedProofReady(){return selectedProof!=null&&selectedProof.accepted&&status==Status.OBSERVED&&cards.size()==2;}
        boolean selectedBeforeB(){if(!selectedProofReady()||selectedProof.pending!=1||selectedProof.complete!=1||selectedProof.targetMatches!=1)return false;
            for(Card card:cards)if(card.configuredTarget(TargetSpec.moreTriviaSelectedPendingB()))return !card.complete();return false;}
        boolean selectedAllComplete(){return selectedProofReady()&&selectedProof.complete==2&&selectedProof.pending==0;}
        String selectedEvidenceSummary(){return selectedEvidenceSummary(false);}
        String selectedEvidenceSummary(boolean beforeSelection){return selectedProof==null?"selectedBusinessProof=NOT_OBSERVED":
            "selectedBusinessProof="+(selectedProof.accepted?"STRICT_NATIVE":"HOLD")+" selectedFirstGate="+selectedProof.gate+" failedFrameCard="+selectedProof.failureCard
            +" knownTitleItems="+cards.size()+(beforeSelection&&!selectedAllComplete()?" exactBMatches="+selectedProof.targetMatches:"")+" pending="+selectedProof.pending+" ownFiveTick="+selectedProof.complete
            +" unmodeledCv="+selectedProof.unmodeled+" completeRewardsFamily=false completionCause=UNPROVEN";}


        Route route(){return route;}
        Diagnostic diagnostic(){return diagnostic;}
        Status status(){return status;}
        Reason reason(){return reason;}
        List<Card> cards(){return cards;}
        Box sectionBox(){return section;}
        RewardRegionGate.Fraction fraction(){return fraction;}
    }
    private static Frame hold(Reason r){return new Frame(Status.HOLD,r,List.of(),null,null);}
    private static Frame holdMore(Reason r,MoreTrace trace){
        return new Frame(Route.MORE_VISIBLE_ITEM,Status.HOLD,r,List.of(),null,null,trace.snapshot());
    }
    private static RewardRegionGate.Fraction actualHeaderFraction(String raw){
        StringBuilder text=new StringBuilder();
        for(int i=0;i<raw.length();i++){
            char ch=raw.charAt(i);
            if(ch>='\uff10'&&ch<='\uff19')ch=(char)('0'+ch-'\uff10');
            else if(ch=='\uff0f')ch='/';
            text.append(ch);
        }
        var match=java.util.regex.Pattern.compile("^([0-9]{1,4})[ \\u00a0\\u2009\\u202f]*/[ \\u00a0\\u2009\\u202f]*([0-9]{1,4})$").matcher(text.toString().trim());
        if(!match.matches())return null;
        int n=Integer.parseInt(match.group(1)),d=Integer.parseInt(match.group(2));
        return d>0&&n<=d?new RewardRegionGate.Fraction(n,d):null;
    }
    private static boolean quality(double score){return Double.isFinite(score)&&score>=.85&&score<=1;}
    private static Box box(FrameStructureEvidence.Line row){return new Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height());}
    private static Box local(Box b,int top){return new Box(b.left(),b.top()-top,b.right(),b.bottom()-top);}
    private static Box full(Box b,int top){return new Box(b.left(),b.top()+top,b.right(),b.bottom()+top);}
    private static String anchor(String s){return s.trim().replaceAll("[ \\t\\r\\n\\u00a0\\u2009\\u202f]+"," ");}
    private static boolean numeric(String s){return s.matches("[+＋﹢0-9０-９/／ .\\u00a0\\u2009\\u202f]+");}
    private static boolean blockedState(String text){
        String s=text.toLowerCase(java.util.Locale.ROOT);
        for(String token:List.of("登录","登入","锁定","未开放","明天","订阅","付款","支付","隐私","授权","购买","充值","兑换","下单","安装","下载",
            "sign in","locked","tomorrow","subscribe","subscription","payment","privacy","consent"))
            if(s.contains(token))return true;
        if(s.matches(".*\\b(?:buy|purchase|redeem|checkout|install|download)\\b.*")||s.contains("top up")||s.contains("place order"))return true;
        return s.matches(".*[0-9]+[ ]*(?:小时|分钟|hours?|minutes?).*");
    }
    private static Card card(Box bounds,Box indicator,Box number,int points,boolean complete,
                             List<RewardRegionLocator.Row> rows,int top) {
        RewardRegionLocator.Row title=null;
        List<String> body=new ArrayList<>();
        for(var row:rows)if(bounds.contains(row.box())&&blockedState(row.text()))return null;
        for(var row:rows)if(bounds.contains(row.box())&&row.box().right()<indicator.left()) {
            String text=anchor(row.text());
            if(text.isEmpty()||numeric(text))continue;
            // Require one reliable title row in the upper left content area. Wrapped or competing
            // title hypotheses stop rather than constructing an identity from geometric order.
            if(row.box().top()<bounds.top()+bounds.height()*.5) {
                if(title!=null)return null;
                title=row;
            } else body.add(text);
        }
        if(title==null)return null;
        String label=anchor(title.text());
        if(label.codePointCount(0,label.length())<2||label.length()>160)return null;
        body.sort(Comparator.naturalOrder());
        StringBuilder id=new StringBuilder("每日活动\n").append(label);
        for(String text:body)id.append('\n').append(text);
        return new Card(Route.DAILY_REGION_COUNTER,label,id.toString(),String.join("\n",body),body.size(),full(title.box(),top),full(bounds,top),full(indicator,top),
            full(number,top),points,complete);
    }
    static Frame observe(String runId,long sequence,byte[] gray,int width,int height,
                         List<FrameStructureEvidence.Line> lines,BooleanSupplier cancel,TargetSpec spec) {
        if(spec==null)return hold(Reason.INVALID_INPUT);
        return spec.route==Route.MORE_VISIBLE_ITEM?observeMore(runId,sequence,gray,width,height,lines,cancel,spec)
            :observe(runId,sequence,gray,width,height,lines,cancel);
    }
    static Frame observe(String runId,long sequence,byte[] gray,int width,int height,
                         List<FrameStructureEvidence.Line> lines,BooleanSupplier cancel) {
        if(cancel==null||gray==null||width<32||height<32||width>4096||height>4096
            ||(long)width*height>4L*1024*1024||gray.length!=(long)width*height
            ||runId==null||runId.isEmpty()||runId.length()>160||sequence<1)return hold(Reason.INVALID_INPUT);
        if(cancel.getAsBoolean())return hold(Reason.CANCELED);
        // This checks batch shape, not provenance. Only the owning Capture seals provenance.
        Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
        if(!OcrRowsAssessment.assess(binding,binding,lines,width,height).shapeValid())return hold(Reason.BAD_NATIVE_BATCH);
        FrameStructureEvidence.Line daily=null,more=null;
        int dailyCount=0,moreCount=0;
        for(var row:lines)if(row.text().equals("每日活动")){daily=row;dailyCount++;}
        if(dailyCount!=1||!quality(daily.score())||daily.x()>.2*width)return hold(Reason.DAILY_HEADING_NOT_UNIQUE);
        for(var row:lines)if(row.text().equals("更多活动")&&row.y()>daily.y()+daily.height()) {
            more=row;moreCount++;
        }
        if(moreCount!=1||!quality(more.score())||more.x()>.2*width)return hold(Reason.LOWER_BOUNDARY_NOT_UNIQUE);
        int top=Math.max(0,daily.y()-1),bottom=more.y();
        if(bottom-top<32)return hold(Reason.LOWER_BOUNDARY_NOT_UNIQUE);
        Box roi=new Box(0,top,width,bottom);
        FrameStructureEvidence.Line counter=null;int counters=0;
        for(var row:lines)if(row.x()>=.65*width&&roi.contains(box(row))
                &&Math.abs(box(row).centerY()-box(daily).centerY())<=.6*Math.max(row.height(),daily.height())
                &&row.text().matches("[0-9０-９]{1,4}[ \\u00a0\\u2009\\u202f]*[/／][ \\u00a0\\u2009\\u202f]*[0-9０-９]{1,4}")) {
            counter=row;counters++;
        }
        if(counters!=1||!quality(counter.score()))return hold(Reason.COUNTER_NOT_UNIQUE);
        var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,runId,sequence,"DAILY_ACTIVITIES");
        List<RewardRegionLocator.Row> rows=new ArrayList<>();
        for(var row:lines) {
            Box b=box(row);
            if(!roi.overlaps(b))continue;
            if(!roi.contains(b))return hold(Reason.ROW_CROSSES_SECTION);
            if(!quality(row.score()))return hold(Reason.LOCAL_CONFIDENCE);
            if(anchor(row.text()).isEmpty())return hold(Reason.LOCAL_TEXT_UNKNOWN);
            rows.add(new RewardRegionLocator.Row(row.text(),local(b,top),row.score(),RewardRegionLocator.Origin.STATIC_OCR));
        }
        // Keep the complete native batch in the section. Do not drop conflicting/low rows.
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++)
            if(rows.get(i).box().overlaps(rows.get(j).box()))return hold(Reason.BAD_NATIVE_BATCH);
        var progress=RewardRegionGate.assess(key,List.of(new RewardRegionGate.Reading(key,counter.text(),counter.score(),
            true,true,RewardRegionGate.CounterRole.REGION_PROGRESS,RewardRegionGate.TextBasis.CONTEXTUAL_OCR)));
        if(progress.gate()==RewardRegionGate.Gate.UNKNOWN_STOP)return hold(Reason.BAD_PROGRESS);
        byte[] crop=new byte[width*(bottom-top)];
        try {
            for(int y=top;y<bottom;y++) {
                if(cancel.getAsBoolean())return hold(Reason.CANCELED);
                System.arraycopy(gray,y*width,crop,(y-top)*width,width);
            }
            var geometry=RewardRegionLocator.observeGray8(key,crop,width,bottom-top,cancel);
            if(cancel.getAsBoolean())return hold(Reason.CANCELED);
            if(geometry.status()!=RewardRegionLocator.Status.OBSERVED)return hold(Reason.LOCATOR_BLOCKED);
            for(var visual:geometry.cards())if(!visual.fullyVisible())return hold(Reason.LOCATOR_BLOCKED);
            var section=new RewardRegionLocator.Section(key,"每日活动",local(box(daily),top),daily.score(),RewardRegionLocator.Origin.STATIC_OCR);
            var result=RewardRegionLocator.associate(geometry,progress,List.of(section),rows);
            boolean sectionComplete=progress.gate()==RewardRegionGate.Gate.COMPLETE_SKIP;
            if(!sectionComplete&&result.status()!=RewardRegionLocator.Status.CANDIDATES
                &&result.status()!=RewardRegionLocator.Status.SKIPPED_VISIBLE_ITEMS)return hold(Reason.LOCATOR_BLOCKED);
            List<Card> cards=new ArrayList<>();
            for(var candidate:result.candidates()) {
                RewardRegionLocator.Row number=null;int count=0;
                for(var row:rows)if(candidate.badgeBox().contains(row.box())&&row.text().matches("[+＋﹢].*")) {number=row;count++;}
                if(count!=1)return hold(Reason.CARD_IDENTITY_NOT_UNIQUE);
                Card c=card(candidate.cardBox(),candidate.badgeBox(),number.box(),candidate.displayedPoints(),false,rows,top);
                if(c==null)return hold(Reason.CARD_IDENTITY_NOT_UNIQUE);
                cards.add(c);
            }
            var skipped=sectionComplete?RewardRegionLocator.observeCompletedItems(geometry,rows):result.skippedItems();
            for(var item:skipped) {
                Card c=card(item.cardBox(),item.tick().diskBox(),item.numberBox(),item.displayedNumber(),true,rows,top);
                if(c==null)return hold(Reason.CARD_IDENTITY_NOT_UNIQUE);
                cards.add(c);
            }
            HashSet<String> titles=new HashSet<>();
            for(Card c:cards)if(!titles.add(c.title))return hold(Reason.CARD_IDENTITY_NOT_UNIQUE);
            // Duplicate titles on unclassified cards also invalidate a cross-frame task anchor.
            for(Card c:cards)for(var visual:geometry.cards())if(!full(visual.box(),top).equals(c.card)) {
                for(var row:rows)if(visual.box().contains(row.box())&&anchor(row.text()).equals(c.title))
                    return hold(Reason.CARD_IDENTITY_NOT_UNIQUE);
            }
            if(cancel.getAsBoolean())return hold(Reason.CANCELED);
            return new Frame(sectionComplete?Status.SECTION_COMPLETE_SKIP:Status.OBSERVED,
                sectionComplete?Reason.COMPLETE_REGION:Reason.READY,cards,roi,progress.fraction());
        } finally {Arrays.fill(crop,(byte)0);}
    }

    /** The header-to-viewport ROI is an observation window, never a complete More section. */
    private static Frame observeMore(String runId,long sequence,byte[] gray,int width,int height,
                                     List<FrameStructureEvidence.Line> lines,BooleanSupplier cancel,TargetSpec spec) {
        MoreTrace trace=new MoreTrace();trace.nativeRows=lines==null?-1:lines.size();
        if(cancel==null||gray==null||width<32||height<32||width>4096||height>4096
            ||(long)width*height>4L*1024*1024||gray.length!=(long)width*height
            ||runId==null||runId.isEmpty()||runId.length()>160||sequence<1)return holdMore(Reason.INVALID_INPUT,trace);
        if(cancel.getAsBoolean())return holdMore(Reason.CANCELED,trace);
        Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
        if(!OcrRowsAssessment.assess(binding,binding,lines,width,height).shapeValid())return holdMore(Reason.BAD_NATIVE_BATCH,trace);
        FrameStructureEvidence.Line header=null,title=null;int headings=0,titles=0;
        // Count the original full-frame batch, including low-confidence duplicate hypotheses.
        for(var row:lines){
            if(row.text().equals("更多活动")){header=row;headings++;trace.headerConfidence=headings==1?row.score():Math.min(trace.headerConfidence,row.score());}
            if(row.text().equals(spec.exactTitle)){title=row;titles++;trace.targetConfidence=titles==1?row.score():Math.min(trace.targetConfidence,row.score());}
            if(spec.familySize>1&&RewardRegionLocator.matchesKnownTitleText(row.text(),spec.exactTitle))trace.knownSingleTitleRows++;
        }
        trace.headers=headings;trace.targets=titles;trace.exactTitleRows=titles;
        if(headings!=1||!quality(header.score())||header.x()>.2*width)return holdMore(Reason.MORE_HEADING_NOT_UNIQUE,trace);
        // The reviewed two-card family now requires CV-owned TitleEvidence. An exact
        // single-row count of zero is neither proof of absence nor permission to guess.
        if(spec.familySize==1){
            if(titles!=1||!quality(title.score()))return holdMore(Reason.TARGET_TITLE_NOT_UNIQUE,trace);
            for(var row:lines)if(row.text().equals(spec.exactTitle)&&(!quality(row.score())||row.y()<header.y()+header.height()))return holdMore(Reason.MORE_CONTEXT_UNPROVEN,trace);
            for(var row:lines)if(row.text().equals("每日活动")&&row.y()>header.y()&&row.y()<title.y())return holdMore(Reason.MORE_CONTEXT_UNPROVEN,trace);
        }
        // Early diagnostic-only header hypotheses survive even if CV later finds no cards.
        trace.counters=0;
        for(var row:lines)if(row!=header&&Math.abs(box(row).centerY()-box(header).centerY())<=.6*Math.max(row.height(),header.height())
            &&(row.x()>=.65*width||anchor(row.text()).matches(".*[0-9０-９/／?].*"))){
            trace.counters++;trace.counterConfidence=trace.counters==1?row.score():Math.min(trace.counterConfidence,row.score());
        }
        // Family-only full-frame CV keeps known title candidates above/below More in
        // coverage checks. The one copy still belongs to this OwnedFrame and is wiped.
        int top=spec.familySize>1?0:Math.max(0,header.y()-1);
        if(height-top<32)return holdMore(Reason.MORE_CONTEXT_UNPROVEN,trace);
        Box roi=new Box(0,top,width,height);
        var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,runId,sequence,"MORE_VISIBLE_ITEM");
        List<RewardRegionLocator.Row> rows=new ArrayList<>();
        for(var row:lines){
            Box b=box(row);
            if(!roi.overlaps(b))continue;
            if(!roi.contains(b))return holdMore(Reason.ROW_CROSSES_SECTION,trace);
            // Do not discard low/empty sibling OCR. The typed per-item API checks the target
            // and relevant header; an unrelated partial sibling is not a section-wide veto.
            rows.add(new RewardRegionLocator.Row(row.text(),local(b,top),row.score(),RewardRegionLocator.Origin.STATIC_OCR));
        }
        byte[] crop=new byte[width*(height-top)];
        try {
            for(int y=top;y<height;y++){
                if(cancel.getAsBoolean())return holdMore(Reason.CANCELED,trace);
                System.arraycopy(gray,y*width,crop,(y-top)*width,width);
            }
            var geometry=RewardRegionLocator.observeVisibleMoreGray8(key,crop,width,height-top,cancel);
            trace.geometry=geometry.status();trace.cards=geometry.cards().size();
            for(var card:geometry.cards())if(title!=null&&card.box().contains(local(box(title),top))){
                trace.matches++;trace.card=full(card.box(),top);trace.complete=card.fullyVisible();
                if(card.badges().size()==1)trace.indicator=full(card.badges().get(0).box(),top);
                else if(card.tickMarks().size()==1)trace.indicator=full(card.tickMarks().get(0).diskBox(),top);
            }
            if(cancel.getAsBoolean())return holdMore(Reason.CANCELED,trace);
            if(geometry.status()!=RewardRegionLocator.Status.OBSERVED)return holdMore(Reason.MORE_GEOMETRY_BLOCKED,trace);
            Box heading=local(box(header),top);
            int firstCardTop=height-top;
            for(var card:geometry.cards())if(card.box().top()>=heading.bottom())firstCardTop=Math.min(firstCardTop,card.box().top());
            RewardRegionLocator.Row fractionRow=null;int headerCounters=0;trace.counters=0;
            for(var row:rows){
                if(row.box().equals(heading)){if(spec.uniquePending&&!row.text().equals("更多活动"))return holdMore(Reason.BAD_PROGRESS,trace);continue;}
                if(row.box().top()<heading.top()||row.box().bottom()>firstCardTop)continue;
                String text=anchor(row.text());
                boolean possibleCounter=text.matches(".*[0-9０-９/／?].*");
                boolean rightLane=row.box().left()>=.65*width;
                if(!possibleCounter&&!rightLane)continue;
                // Explicitly inspect every reported header-counter hypothesis. Malformed,
                // low-confidence, split, shifted or ambiguous readings cannot become NO_COUNTER.
                headerCounters++;fractionRow=row;trace.counters=headerCounters;trace.counterConfidence=headerCounters==1?row.confidence():Math.min(trace.counterConfidence,row.confidence());
                if(!quality(row.confidence())||text.isEmpty()||!rightLane
                    ||Math.abs(row.box().centerY()-heading.centerY())>.6*Math.max(row.box().height(),heading.height()))
                    return holdMore(Reason.BAD_PROGRESS,trace);
            }
            // This reviewed live Quote configuration has an independently observed header ratio.
            // A missing native reading is missing evidence, never proven NO_COUNTER.
            if(headerCounters!=1)return holdMore(Reason.COUNTER_NOT_UNIQUE,trace);
            trace.fraction=actualHeaderFraction(fractionRow.text());
            if(trace.fraction==null)return holdMore(Reason.BAD_PROGRESS,trace);
            var counter=new RewardRegionLocator.VisibleItemCounter(RewardRegionLocator.CounterState.HEADER_FRACTION,fractionRow);
            var section=new RewardRegionLocator.Section(key,"更多活动",heading,header.score(),RewardRegionLocator.Origin.STATIC_OCR);
            if(spec.uniquePending&&!spec.uniqueAfter&&trace.fraction.numerator()==trace.fraction.denominator())
                return new Frame(Route.MORE_VISIBLE_ITEM,Status.SECTION_COMPLETE_SKIP,Reason.COMPLETE_REGION,List.of(),roi,trace.fraction,trace.snapshot(),null,new UniqueSeal(false,true,null,-1,-1,-1,-1,null));
            if(spec.uniquePending){var result=observeUniqueFamily(geometry,section,rows,counter,roi,top,trace);return cancel.getAsBoolean()?holdMore(Reason.CANCELED,trace):result;}
            if(spec.selectedPending)return observeSelectedFamily(geometry,section,rows,counter,spec,roi,top,trace);
            if(spec.familySize>1)return observeMoreFamily(geometry,section,rows,lines,counter,spec,roi,top,trace);
            var result=RewardRegionLocator.associateVisibleItem(geometry,section,rows,spec.exactTitle,counter);
            trace.association=result.status();
            if(result.status()==RewardRegionLocator.VisibleItemStatus.HOLD)return holdMore(Reason.MORE_ASSOCIATION_BLOCKED,trace);
            if(result.status()==RewardRegionLocator.VisibleItemStatus.REGION_COMPLETE_SKIP)
                return new Frame(Route.MORE_VISIBLE_ITEM,Status.SECTION_COMPLETE_SKIP,Reason.COMPLETE_REGION,List.of(),roi,result.fraction(),trace.snapshot());
            boolean complete=result.status()==RewardRegionLocator.VisibleItemStatus.ITEM_COMPLETE_SKIP;
            Box bounds=complete?result.skipped().cardBox():result.candidate().cardBox();
            Box indicator=complete?result.skipped().tick().diskBox():result.candidate().badgeBox();
            int points=complete?result.skipped().displayedNumber():result.candidate().displayedPoints();
            if(points!=spec.points||result.title()==null||result.number()==null)return holdMore(Reason.CARD_IDENTITY_NOT_UNIQUE,trace);
            Card selected=visibleItemCard(spec.exactTitle,bounds,indicator,result.number().box(),points,complete,rows,top);
            if(selected==null)return holdMore(Reason.CARD_IDENTITY_NOT_UNIQUE,trace);
            if(cancel.getAsBoolean())return holdMore(Reason.CANCELED,trace);
            return new Frame(Route.MORE_VISIBLE_ITEM,Status.OBSERVED,Reason.READY,List.of(selected),roi,result.fraction(),trace.snapshot());
        } finally {Arrays.fill(crop,(byte)0);}
    }
    private static Frame observeUniqueFamily(RewardRegionLocator.Geometry geometry,RewardRegionLocator.Section section,List<RewardRegionLocator.Row> rows,
            RewardRegionLocator.VisibleItemCounter counter,Box roi,int top,MoreTrace trace){
        var proof=RewardRegionLocator.observeUniquePending(geometry,section,rows,counter);
        if(!proof.accepted())return new Frame(Route.MORE_VISIBLE_ITEM,Status.HOLD,Reason.MORE_ASSOCIATION_BLOCKED,List.of(),roi,proof.fraction(),trace.snapshot(),null,
            new UniqueSeal(false,false,proof.failure(),proof.failureCard(),proof.pending(),proof.complete(),proof.knownCompleted(),null,0,proof.veto()));
        List<Card> cards=new ArrayList<>();Card target=null;
        for(var item:proof.items()){
            List<RewardRegionLocator.Row> ordered=new ArrayList<>(item.identityRows());ordered.sort(Comparator.comparingInt((RewardRegionLocator.Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
            if(ordered.isEmpty()&&!item.complete())return holdMore(Reason.CARD_IDENTITY_NOT_UNIQUE,trace);
            StringBuilder identity=new StringBuilder();Box title=ordered.isEmpty()?null:ordered.get(0).box();
            for(var row:ordered){if(identity.length()>0)identity.append(' ');identity.append(anchor(row.text()));}
            Card c=new Card(Route.MORE_VISIBLE_ITEM,ordered.isEmpty()?"":anchor(ordered.get(0).text()),"UNIQUE_MORE\n"+identity,"",ordered.size(),title==null?null:full(title,top),full(item.cardBox(),top),full(item.indicatorBox(),top),full(item.number().box(),top),item.points(),item.complete());
            cards.add(c);if(!c.complete())target=c;
        }
        trace.adapterFirstGate=AdapterGate.NONE;return new Frame(Route.MORE_VISIBLE_ITEM,Status.OBSERVED,Reason.READY,cards,roi,proof.fraction(),trace.snapshot(),null,
            new UniqueSeal(true,false,null,-1,proof.pending(),proof.complete(),proof.knownCompleted(),target,proof.unmodeledText()));
    }
    private static Frame observeSelectedFamily(RewardRegionLocator.Geometry geometry,RewardRegionLocator.Section section,
            List<RewardRegionLocator.Row> rows,RewardRegionLocator.VisibleItemCounter counter,TargetSpec spec,Box roi,int top,MoreTrace trace){
        var family=RewardRegionLocator.observeSelectedPendingFamily(geometry,section,rows,spec.exactTitle,counter);
        if(!family.accepted()){
            trace.fail(AdapterGate.FAMILY_RESULT);return new Frame(Route.MORE_VISIBLE_ITEM,Status.HOLD,Reason.MORE_ASSOCIATION_BLOCKED,List.of(),roi,family.fraction(),trace.snapshot(),
                new SelectedProof(false,family.failure(),family.failureCard(),0,0,0,family.unmodeled()));
        }
        List<Card> cards=new ArrayList<>();int pending=0,complete=0,matches=0;
        for(var item:family.items()){
            boolean done=item.status()==RewardRegionLocator.VisibleItemStatus.ITEM_COMPLETE_SKIP;
            Box card=done?item.skipped().cardBox():item.candidate().cardBox();Box indicator=done?item.skipped().tick().diskBox():item.candidate().badgeBox();
            List<RewardRegionLocator.Row> ordered=new ArrayList<>(item.body());ordered.sort(Comparator.comparingInt((RewardRegionLocator.Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
            StringBuilder text=new StringBuilder();for(var row:ordered){if(text.length()>0)text.append(' ');text.append(anchor(row.text()));}
            String body=text.toString();Card sealed;
            if(!done){
                ItemTrace detail=new ItemTrace(trace.items.size(),5);trace.items.add(detail);
                sealed=visibleTitleItemCard(spec,item.title(),item.partition(),item.body(),card,indicator,item.number(),5,false,rows,top,trace,detail);
                if(sealed==null)return new Frame(Route.MORE_VISIBLE_ITEM,Status.HOLD,Reason.MORE_FAMILY_BODY,List.of(),roi,family.fraction(),trace.snapshot(),
                    new SelectedProof(false,RewardRegionLocator.FailureGate.TEMPLATE_CONFLICT,-1,0,0,0,family.unmodeled()));
                sealed=sealed.familyBody(body);detail.body=body.equals(spec.targetBody)?BodyClass.TARGET:BodyClass.SIBLING;
            }else sealed=new Card(Route.MORE_VISIBLE_ITEM,spec.exactTitle,"SELECTED_PENDING_VISIBLE\n"+spec.exactTitle+"\n"+body,body,ordered.size(),
                full(item.title().envelope(),top),full(card,top),full(indicator,top),full(item.number().box(),top),5,true);
            cards.add(sealed);if(done)complete++;else pending++;if(sealed.configuredTarget(spec))matches++;
        }
        trace.adapterFirstGate=AdapterGate.NONE;return new Frame(Route.MORE_VISIBLE_ITEM,Status.OBSERVED,Reason.READY,cards,roi,family.fraction(),trace.snapshot(),
            new SelectedProof(true,null,-1,pending,complete,matches,family.unmodeled()));
    }
    private static Frame holdFamily(Reason reason,MoreTrace trace,AdapterGate gate){trace.fail(gate);return holdMore(reason,trace);}
    private static Frame observeMoreFamily(RewardRegionLocator.Geometry geometry,RewardRegionLocator.Section section,
            List<RewardRegionLocator.Row> rows,List<FrameStructureEvidence.Line> fullRows,RewardRegionLocator.VisibleItemCounter counter,
            TargetSpec spec,Box roi,int top,MoreTrace trace){
        // Explicit fixed-layout API. This assumption applies only to the reviewed two-member More family.
        trace.adapterFirstGate=AdapterGate.NONE;
        var family=RewardRegionLocator.observeTitleFamilyPartitioned(geometry,section,rows,spec.exactTitle,counter);
        trace.familyStatus=family.status();trace.familyReason=family.reason();
        trace.familyItemCount=family.items().size();trace.familyFailure=family.failure();
        List<RewardRegionLocator.TitleDiagnostic> safe=new ArrayList<>();
        trace.targets=family.diagnostics().size();trace.targetConfidence=-1;
        for(var diagnostic:family.diagnostics()){
            List<Box> originals=new ArrayList<>();for(Box b:diagnostic.rawBoxes())originals.add(full(b,top));
            if(safe.size()<8)safe.add(new RewardRegionLocator.TitleDiagnostic(diagnostic.kind(),diagnostic.partCount(),diagnostic.minConfidence(),originals));
            trace.targetConfidence=trace.targetConfidence<0?diagnostic.minConfidence():Math.min(trace.targetConfidence,diagnostic.minConfidence());
        }
        trace.titleEvidence=List.copyOf(safe);
        if(family.status()==RewardRegionLocator.FamilyStatus.REGION_COMPLETE_SKIP)
            return new Frame(Route.MORE_VISIBLE_ITEM,Status.SECTION_COMPLETE_SKIP,Reason.COMPLETE_REGION,List.of(),roi,family.fraction(),trace.snapshot());
        if(family.status()!=RewardRegionLocator.FamilyStatus.OBSERVED||family.items().size()!=spec.familySize)
            return holdFamily(Reason.MORE_ASSOCIATION_BLOCKED,trace,AdapterGate.FAMILY_RESULT);
        List<Card> cards=new ArrayList<>();List<Box> localBoxes=new ArrayList<>();List<RewardRegionLocator.Row> adoptedParts=new ArrayList<>();List<List<RewardRegionLocator.Row>> adoptedTitles=new ArrayList<>();int lastFamilyBottom=0;
        for(var item:family.items()){
            boolean complete=item.status()==RewardRegionLocator.VisibleItemStatus.ITEM_COMPLETE_SKIP;
            Box bounds=complete?item.skipped().cardBox():item.candidate().cardBox();
            Box indicator=complete?item.skipped().tick().diskBox():item.candidate().badgeBox();
            int points=complete?item.skipped().displayedNumber():item.candidate().displayedPoints();
            ItemTrace detail=new ItemTrace(trace.items.size(),points);trace.items.add(detail);
            if(points!=5||bounds.top()<=section.titleBox().bottom())return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.POINTS_OR_CONTEXT);
            Card card=visibleTitleItemCard(spec,item.title(),item.partition(),item.body(),bounds,indicator,item.number(),points,complete,rows,top,trace,detail);
            adoptedParts.addAll(item.title().originalParts());adoptedTitles.add(item.title().originalParts());
            if(card==null)return holdFamily(Reason.MORE_FAMILY_BODY,trace,AdapterGate.LOCAL_ROW_STATE);
            List<RewardRegionLocator.Row> body=new ArrayList<>(item.body());
            body.sort(Comparator.comparingInt((RewardRegionLocator.Row r)->r.box().top()).thenComparingInt(r->r.box().left()));
            StringBuilder canonical=new StringBuilder();for(var row:body){if(canonical.length()>0)canonical.append(' ');canonical.append(anchor(row.text()));}
            String text=canonical.toString();
            detail.body=text.equals(spec.targetBody)?BodyClass.TARGET:text.equals(spec.siblingBody)?BodyClass.SIBLING:
                complete&&text.isEmpty()?BodyClass.EMPTY_COMPLETE:BodyClass.CONFLICT;
            if(detail.body==BodyClass.CONFLICT)return holdFamily(Reason.MORE_FAMILY_BODY,trace,AdapterGate.BODY_CLASSIFICATION);
            cards.add(card.familyBody(text));localBoxes.add(bounds);lastFamilyBottom=Math.max(lastFamilyBottom,bounds.bottom());
        }
        if(spec.boundedProfile){
            List<RewardRegionLocator.TitleEvidence> global;
            try{global=RewardRegionLocator.enumerateKnownTitleEvidence(rows,spec.exactTitle);}
            catch(IllegalArgumentException unknown){return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.TITLE_MEMBERSHIP);}
            for(var row:rows)if(!containsIdentity(adoptedParts,row)&&RewardRegionLocator.matchesKnownTitleFragment(row.text(),spec.exactTitle))
                return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.TITLE_MEMBERSHIP);
            boolean[] used=new boolean[adoptedTitles.size()];
            for(var evidence:global){int match=-1,count=0;
                for(int i=0;i<adoptedTitles.size();i++)if(sameOrderedRows(evidence.originalParts(),adoptedTitles.get(i))){match=i;count++;}
                if(count!=1||used[match])return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.TITLE_MEMBERSHIP);used[match]=true;
            }
            if(global.size()!=adoptedTitles.size())return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.TITLE_MEMBERSHIP);
            for(boolean found:used)if(!found)return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.TITLE_MEMBERSHIP);
        }else for(var row:rows)if(RewardRegionLocator.matchesKnownTitleText(row.text(),spec.exactTitle)&&!containsIdentity(adoptedParts,row))
            return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.TITLE_MEMBERSHIP);
        Box lower=null;
        if(!spec.boundedProfile)for(AuxiliaryKind kind:AuxiliaryKind.values()){
            String title=kind==AuxiliaryKind.PUZZLE?"Complete this puzzle":"Quote of the day";
            AuxiliaryTrace detail=new AuxiliaryTrace(kind);trace.auxiliaries.add(detail);boolean high=true;
            for(var row:fullRows)if(row.text().equals(title)){detail.count++;high&=quality(row.score());}
            if(detail.count>0)detail.high=high?RewardRegionLocator.DiagnosticCheck.YES:RewardRegionLocator.DiagnosticCheck.NO;
            if(detail.count!=1||!high)continue;
            var auxiliary=RewardRegionLocator.associateVisibleItem(geometry,section,rows,title,counter);detail.association=auxiliary.status();
            if(auxiliary.status()!=RewardRegionLocator.VisibleItemStatus.ITEM_COMPLETE_SKIP){captureAuxiliaryFailure(trace,detail,geometry,rows,title);continue;}
            detail.points=auxiliary.skipped().displayedNumber()==5?RewardRegionLocator.DiagnosticCheck.YES:RewardRegionLocator.DiagnosticCheck.NO;
            if(detail.points!=RewardRegionLocator.DiagnosticCheck.YES){captureAuxiliaryFailure(trace,detail,geometry,rows,title);continue;}
            Box bounds=auxiliary.skipped().cardBox();detail.after=bounds.top()>lastFamilyBottom?RewardRegionLocator.DiagnosticCheck.YES:RewardRegionLocator.DiagnosticCheck.NO;
            if(detail.after!=RewardRegionLocator.DiagnosticCheck.YES){captureAuxiliaryFailure(trace,detail,geometry,rows,title);continue;}
            detail.geometry=visibleItemCard(title,bounds,auxiliary.skipped().tick().diskBox(),auxiliary.number().box(),5,true,rows,top)!=null
                ?RewardRegionLocator.DiagnosticCheck.YES:RewardRegionLocator.DiagnosticCheck.NO;
            if(detail.geometry!=RewardRegionLocator.DiagnosticCheck.YES){captureAuxiliaryFailure(trace,detail,geometry,rows,title);continue;}
            if(!spec.boundedProfile&&(lower==null||bounds.top()<lower.top()))lower=bounds;
        }
        if(spec.boundedProfile)lower=boundedGeometryLower(geometry.cards(),section.titleBox(),localBoxes);
        if(lower==null)return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.AUXILIARY_BOUNDARY);
        int visual=0;trace.unexplainedCardsSeen=0;
        for(var candidate:geometry.cards())if(candidate.box().bottom()>section.titleBox().bottom()&&candidate.box().top()<lower.top()){
            if(!candidate.fullyVisible()||!localBoxes.contains(candidate.box())){trace.unexplainedCardsSeen++;return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.UNEXPLAINED_CARD);}visual++;
        }
        if(visual!=spec.familySize)return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.FAMILY_CARD_COUNT);
        Box band=new Box(0,section.titleBox().bottom(),geometry.width(),lower.top());trace.unexplainedRowsSeen=0;
        for(var row:rows)if(band.overlaps(row.box())){
            boolean explained=false;for(Box box:localBoxes)if(box.contains(row.box()))explained=true;
            if(!explained){trace.unexplainedRowsSeen++;return holdFamily(Reason.MORE_FAMILY_COVERAGE,trace,AdapterGate.UNEXPLAINED_ROW);}
        }
        trace.matches=0;trace.card=null;trace.indicator=null;trace.complete=false;
        for(Card card:cards)if(card.configuredTarget(spec)){trace.matches++;trace.card=card.cardBox();trace.indicator=card.indicatorBox();trace.complete=true;}
        return new Frame(Route.MORE_VISIBLE_ITEM,Status.OBSERVED,Reason.READY,cards,roi,family.fraction(),trace.snapshot());
    }
    /** First failed auxiliary only. Original boxes/scores, no new association decision.
     * These legacy auxiliary rows are NOT lane-partitioned: UNCLASSIFIED below is a
     * diagnostic role, never a statement that central/body/state validation passed.
     * Current family input is full-display origin0; coordinates retain LOCATOR_INPUT. */
    private static void captureAuxiliaryFailure(MoreTrace trace,AuxiliaryTrace detail,RewardRegionLocator.Geometry geometry,
            List<RewardRegionLocator.Row> rows,String exactTitle){
        if(trace.auxiliaryFailureCaptured)return;trace.auxiliaryFailureCaptured=true;
        RewardRegionLocator.Row title=null;int titles=0;
        for(var row:rows)if(row.text().equals(exactTitle)){title=row;titles++;}
        detail.failureMatches=0;if(titles!=1)return;
        Box bounds=null;for(var card:geometry.cards())if(card.box().contains(title.box())){detail.failureMatches++;bounds=card.box();}
        if(detail.failureMatches!=1)return;
        detail.failureBox=bounds;detail.failureCount=0;List<RewardRegionLocator.LocalRowDiagnostic> safe=new ArrayList<>();
        for(var row:rows)if(bounds.overlaps(row.box())){
            detail.failureCount++;
            if(safe.size()<8)safe.add(new RewardRegionLocator.LocalRowDiagnostic(row.box(),row.confidence(),
                bounds.contains(row.box())?RewardRegionLocator.RowRegion.UNCLASSIFIED:RewardRegionLocator.RowRegion.CROSSES_CARD));
        }
        detail.failureRows=List.copyOf(safe);detail.failureTruncated=detail.failureCount>safe.size();
        detail.failureSpace=RewardRegionLocator.CrossingCoordinateSpace.LOCATOR_INPUT;
    }    /** Geometric cutoff only, never a Card/task/completion/click. All candidates participate before validation. */
    static Box boundedGeometryLower(List<RewardRegionLocator.Card> visuals,Box heading,List<Box> family){
        if(visuals==null||heading==null||family==null||family.size()!=2)return null;
        int last=0;for(Box b:family)last=Math.max(last,b.bottom());
        RewardRegionLocator.Card nearest=null;int equalTop=0;
        for(var visual:visuals){Box b=visual.box();if(family.contains(b)||b.bottom()<=heading.bottom())continue;
            if(nearest==null||b.top()<nearest.box().top()){nearest=visual;equalTop=1;}
            else if(b.top()==nearest.box().top())equalTop++;
        }
        if(nearest==null||equalTop!=1||!nearest.fullyVisible()||nearest.box().top()<=last)return null;
        for(var visual:visuals)if(visual!=nearest&&visual.box().overlaps(nearest.box()))return null;
        return nearest.box();
    }
    private static boolean sameOrderedRows(List<RewardRegionLocator.Row> a,List<RewardRegionLocator.Row> b){
        if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;
    }
    private static boolean containsIdentity(List<RewardRegionLocator.Row> rows,RewardRegionLocator.Row target){
        for(var row:rows)if(row==target)return true;return false;
    }
    private static Card rejectFamilyCard(MoreTrace trace,AdapterGate gate){trace.fail(gate);return null;}
    private static boolean completionText(String text){
        String s=text.toLowerCase(java.util.Locale.ROOT);
        return s.contains("已完成")||s.contains("已赚取")||s.contains("已领取")||s.contains("已获得")
            ||s.matches(".*\\b(?:complete|completed|earned|claimed|credited)\\b.*");
    }
    private static boolean leftKnownConflict(List<RewardRegionLocator.Row> left,TargetSpec spec){
        for(int i=0;i<left.size();i++){
            String a=anchor(left.get(i).text());
            if(RewardRegionLocator.matchesKnownTitleText(a,spec.exactTitle)||a.equals(spec.targetBody)||a.equals(spec.siblingBody))return true;
            for(int j=i+1;j<left.size();j++){
                RewardRegionLocator.Row first=left.get(i),second=left.get(j);
                if(first.box().top()>second.box().top()){first=left.get(j);second=left.get(i);}
                String pair=anchor(first.text())+" "+anchor(second.text());
                if(RewardRegionLocator.matchesKnownTitleText(pair,spec.exactTitle)||pair.equals(spec.targetBody)||pair.equals(spec.siblingBody))return true;
            }
        }
        return false;
    }
    private static Card visibleTitleItemCard(TargetSpec spec,RewardRegionLocator.TitleEvidence title,
            RewardRegionLocator.RowPartition partition,List<RewardRegionLocator.Row> adoptedBody,Box bounds,Box indicator,
            RewardRegionLocator.Row number,int points,boolean complete,List<RewardRegionLocator.Row> rows,int top,MoreTrace trace,ItemTrace detail){
        if(partition==null){detail.partition=RewardRegionLocator.DiagnosticCheck.NO;return rejectFamilyCard(trace,AdapterGate.PARTITION_MEMBERSHIP);}
        var left=partition.leftUnmodeled();var central=partition.centralIdentity();var right=partition.rightState();
        detail.left=left.size();detail.identity=central.size();detail.state=right.size();
        List<RewardRegionLocator.Row> members=new ArrayList<>();
        for(var group:List.of(left,central,right))for(var row:group){
            if(!containsIdentity(rows,row)||containsIdentity(members,row)||!bounds.contains(row.box())){
                detail.partition=RewardRegionLocator.DiagnosticCheck.NO;return rejectFamilyCard(trace,AdapterGate.PARTITION_MEMBERSHIP);
            }
            members.add(row);
        }
        // Preserve the complete original batch. No independent lane geometry or filtered rows.
        for(var row:rows)if(bounds.overlaps(row.box())&&!containsIdentity(members,row)){
            detail.partition=RewardRegionLocator.DiagnosticCheck.NO;return rejectFamilyCard(trace,AdapterGate.PARTITION_MEMBERSHIP);
        }
        for(int i=0;i<members.size();i++)for(int j=i+1;j<members.size();j++)if(members.get(i).box().overlaps(members.get(j).box())){
            detail.partition=RewardRegionLocator.DiagnosticCheck.NO;return rejectFamilyCard(trace,AdapterGate.PARTITION_MEMBERSHIP);
        }
        detail.partition=RewardRegionLocator.DiagnosticCheck.YES;
        if(title==null||title.kind()==null||title.originalParts().isEmpty()||title.originalParts().size()>2
            ||(title.kind()==RewardRegionLocator.TitleMatchKind.TWO_LINES)!=(title.originalParts().size()==2))return rejectFamilyCard(trace,AdapterGate.TITLE_MEMBERSHIP);
        Box envelope=null;double minimum=1;StringBuilder literal=new StringBuilder();List<RewardRegionLocator.Row> parts=new ArrayList<>();
        for(var part:title.originalParts()){
            if(!containsIdentity(central,part)||containsIdentity(parts,part)||!quality(part.confidence()))return rejectFamilyCard(trace,AdapterGate.TITLE_MEMBERSHIP);
            parts.add(part);minimum=Math.min(minimum,part.confidence());Box b=part.box();
            envelope=envelope==null?b:new Box(Math.min(envelope.left(),b.left()),Math.min(envelope.top(),b.top()),Math.max(envelope.right(),b.right()),Math.max(envelope.bottom(),b.bottom()));
            if(literal.length()>0)literal.append(' ');literal.append(part.text());
        }
        if(!envelope.equals(title.envelope())||Double.compare(minimum,title.minConfidence())!=0
            ||!RewardRegionLocator.matchesKnownTitleText(literal.toString(),spec.exactTitle)
            ||envelope.right()>=indicator.left()||envelope.top()>=bounds.top()+.5*bounds.height())return rejectFamilyCard(trace,AdapterGate.TITLE_GEOMETRY);
        if(number==null||!containsIdentity(right,number))return rejectFamilyCard(trace,AdapterGate.RIGHT_STATE_FIELD);
        List<RewardRegionLocator.Row> bodyMembers=new ArrayList<>();
        for(var row:adoptedBody){if(!containsIdentity(central,row)||containsIdentity(parts,row)||containsIdentity(bodyMembers,row))return rejectFamilyCard(trace,AdapterGate.PARTITION_MEMBERSHIP);bodyMembers.add(row);}
        if(central.size()!=parts.size()+bodyMembers.size())return rejectFamilyCard(trace,AdapterGate.CENTRAL_BODY_FIELD);
        // Global known conflicts run before a left-unmodeled row can be skipped. U is
        // a fixed-schema non-evidence lane, not a claim that its pixels are decoration.
        for(var row:members){
            String text=anchor(row.text());
            if(blockedState(text)||completionText(text)||text.indexOf('/')>=0||text.indexOf('／')>=0
                ||text.indexOf('√')>=0||text.indexOf('✓')>=0
                ||!containsIdentity(right,row)&&text.matches(".*[+＋﹢][ ]*[0-9０-９].*"))return rejectFamilyCard(trace,AdapterGate.LOCAL_ROW_STATE);
            if(!containsIdentity(parts,row)&&RewardRegionLocator.matchesKnownTitleText(text,spec.exactTitle))return rejectFamilyCard(trace,AdapterGate.LEFT_KNOWN_CONFLICT);
        }
        if(leftKnownConflict(left,spec))return rejectFamilyCard(trace,AdapterGate.LEFT_KNOWN_CONFLICT);
        List<String> body=new ArrayList<>();
        for(var row:members){
            if(containsIdentity(left,row))continue;
            String text=anchor(row.text());
            if(!quality(row.confidence())||text.isEmpty())return rejectFamilyCard(trace,AdapterGate.LOCAL_ROW_STATE);
            if(containsIdentity(parts,row)||row==number)continue;
            if(containsIdentity(right,row)){
                if(!pointsUnit(text)||complete||!attachedPointsUnit(row.box(),number.box(),indicator))return rejectFamilyCard(trace,AdapterGate.RIGHT_STATE_FIELD);
                continue;
            }
            if(!containsIdentity(bodyMembers,row)||row.box().top()<envelope.bottom()||row.box().right()>=indicator.left()
                ||numeric(text)||text.matches(".*[0-9０-９+＋﹢/／].*"))return rejectFamilyCard(trace,AdapterGate.CENTRAL_BODY_FIELD);
            body.add(text);
        }
        body.sort(Comparator.naturalOrder());StringBuilder identity=new StringBuilder("MORE_VISIBLE_ITEM\n更多活动\n").append(spec.exactTitle);
        for(String text:body)identity.append('\n').append(text);
        return new Card(Route.MORE_VISIBLE_ITEM,spec.exactTitle,identity.toString(),String.join("\n",body),body.size(),full(envelope,top),
            full(bounds,top),full(indicator,top),full(number.box(),top),points,complete);
    }
    private static Card visibleItemCard(String exactTitle,Box bounds,Box indicator,Box number,int points,
                                       boolean complete,List<RewardRegionLocator.Row> rows,int top){
        RewardRegionLocator.Row title=null;List<String> body=new ArrayList<>();
        for(var row:rows)if(bounds.overlaps(row.box())){
            if(!bounds.contains(row.box())||!quality(row.confidence())||anchor(row.text()).isEmpty()||blockedState(row.text()))return null;
            if(row.text().equals(exactTitle)){
                if(title!=null)return null;
                title=row;continue;
            }
            if(row.box().equals(number))continue; // The one exact typed native number, never a cropped fragment.
            String text=anchor(row.text());
            if(pointsUnit(text)){
                if(complete||!attachedPointsUnit(row.box(),number,indicator))return null;
                continue;
            }
            // Every other row must be actual left-hand semantic body. Do not silently
            // discard indicator/right-side text, another number, progress, or number/unit
            // merged rows. Unknown local obligations cannot borrow the configured title.
            if(row.box().right()>=indicator.left()||numeric(text)||text.matches(".*[0-9０-９+＋﹢/／].*"))return null;
            body.add(text);
        }
        if(title==null||title.box().right()>=indicator.left()||title.box().top()>=bounds.top()+.5*bounds.height())return null;
        body.sort(Comparator.naturalOrder());
        StringBuilder identity=new StringBuilder("MORE_VISIBLE_ITEM\n更多活动\n").append(exactTitle);
        for(String text:body)identity.append('\n').append(text);
        return new Card(Route.MORE_VISIBLE_ITEM,exactTitle,identity.toString(),String.join("\n",body),body.size(),full(title.box(),top),full(bounds,top),full(indicator,top),full(number,top),points,complete);
    }

    private static boolean pointsUnit(String text){
        String s=text.toLowerCase(java.util.Locale.ROOT);
        return s.equals("pts")||s.equals("points")||s.equals("点")||s.equals("积分");
    }
    private static boolean attachedPointsUnit(Box unit,Box number,Box indicator){
        Box safe=new Box(indicator.left()+2,indicator.top()+2,indicator.right()-2,indicator.bottom()-2);
        if(!safe.contains(unit)||!safe.contains(number)||unit.overlaps(number)
            ||unit.width()>.40*indicator.width()||unit.height()>.40*indicator.height()
            ||(long)unit.width()*unit.height()>.12*indicator.width()*indicator.height())return false;
        double dx=Math.max(0,Math.max(unit.left()-number.right(),number.left()-unit.right()));
        double dy=Math.max(0,Math.max(unit.top()-number.bottom(),number.top()-unit.bottom()));
        return dx<=.18*indicator.width()&&dy<=.15*indicator.height();
    }
    private LiveRewardCardObservation(){}
}



