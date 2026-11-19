package com.aliothmoon.maafw.once;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Device implementation owned by one explicitly selected reading entry in the visible runtime. */
final class NewsAndroidPort implements ReadingNewsCycle.Ports {
    private final Context context;
    private final LiveRunAuthority authority;
    private final LiveRewardsNavigation navigation;
    private final BingNavigationSnapshot.Shape shape;
    private final String runId;
    private final BooleanSupplier hidden;
    private final LiveRewardsPerception.ArtifactSink artifacts;
    private final LiveRewardsLedger.Store files;
    private final String preparation;
    private final AtomicBoolean started = new AtomicBoolean();
    private BingNavigationSnapshot.Ready original;
    private int anchorY = -1;
    private NewsFeedModel.Card opened;
    private long tapReturned = -1;
    private volatile String step = "READY", outcome = "READY", detail = "NONE";
    private volatile boolean listConfirmed, uncertain, ocrClean = true;
    private volatile int treeReads, crops, marked, swipes, taps, tapInvoked, tapSuccess, entryTaps;
    private volatile int backs, backInvoked, backSuccess, lastNodes, lastStride;
    private int serial;
    private ReadingNewsCycle cycle;
    private Snapshot lastCandidate;
    private ReadingNewsCycle.CardProof lastProof;

    NewsAndroidPort(LiveRewardsRuntime.ReadingApproval selected,Context context,LiveRunAuthority authority,
                    LiveRewardsNavigation navigation,BingNavigationSnapshot.Shape shape,String runId,
                    BooleanSupplier hidden,LiveRewardsPerception.ArtifactSink artifacts) throws IOException {
        if (selected == null || context == null || authority == null || !authority.live() || !authority.current()
                || navigation == null || shape == null || shape.rotation()!=0 || shape.width()<=0 || shape.height()<=shape.width()
                || hidden == null || !hidden.getAsBoolean() || artifacts == null
                || !UUID.fromString(runId).toString().equals(runId)) throw reject("READING_OWNER");
        this.context=context.getApplicationContext(); this.authority=authority; this.navigation=navigation;
        this.shape=shape; this.runId=runId; this.hidden=hidden; this.artifacts=artifacts;
        files=new LiveRewardsLedger.FileStore(context.getNoBackupFilesDir().toPath());
        preparation=ReadingNewsLedger.DIRECTORY+"/"+authority.day()+"/preparations/"+runId+"/";
    }

    void run() throws Exception {
        if (!started.compareAndSet(false,true)) throw reject("READING_ALREADY_STARTED");
        try {
            check();
            reserve("PREPARATION");
            step="ENTRY_SOURCE";
            original=navigation.template();
            Snapshot entry=null;
            for(int i=0;i<=8;i++){
                entry=readEntry();
                expectPage(entry,NewsFeedModel.Page.REWARDS);
                if (entry.frame().entry()!=null) break;
                if (i==8) throw reject("ENTRY_NOT_VISIBLE");
                var direction = switch (entry.frame().reason()) {
                    case "ENTRY_ABOVE" -> ReviewShizukuSwipeActor.Direction.DOWN;
                    case "ENTRY_BELOW" -> ReviewShizukuSwipeActor.Direction.UP;
                    default -> throw reject("ENTRY_CLIP_POSITION");
                };
                step="REVEAL_ENTRY";
                int distance=ReviewShizukuSwipeActor.SwipeTarget.standardDistance(shape.height());
                swipe(entry,direction,distance,false);
                pause(450);
            }
            if (entry==null || entry.frame().entry()==null) throw reject("ENTRY_NOT_VISIBLE");
            if (entry.frame().entryComplete()) { outcome=step="ENTRY_ALREADY_COMPLETE"; return; }
            step="ENTRY_OCR";
            var located=locate(entry,true);
            if(located.ocr().kind()!=NewsFeedModel.Kind.ENTRY) throw reject(located.ocr().reason());
            if(!authority.taskInputAllowed()) { outcome=step="PRECHECK_READY"; return; }
            step="OPEN_ENTRY";
            tap(located,()->reserve("ENTRY_TAP"),true);
            pause(450);
            step="FIRST_FEED";
            Snapshot first=awaitFeed(true);
            listConfirmed=true;
            if(first.frame().card()!=null)anchorY=(int)first.frame().card().box().centerY();
            check();
            cycle=new ReadingNewsCycle(new ReadingNewsLedger(files,authority.day(),runId));
            var result=cycle.run(this);
            outcome=result.status().name(); step=outcome;
            if(result.status()!=ReadingNewsCycle.Status.DAILY_LIMIT && result.status()!=ReadingNewsCycle.Status.FEED_END)
                throw reject(outcome);
            check();
        } catch(Exception stopped) {
            if ("READY".equals(outcome)) outcome="STOPPED";
            String code=stopped.getMessage();
            if (code!=null && code.matches("[A-Z][A-Z0-9_]{0,95}")) detail=code;
            throw stopped;
        }
    }

    @Override public long now(){return SystemClock.elapsedRealtime();}
    @Override public boolean current(){return !Thread.currentThread().isInterrupted() && authority.current() && hidden.getAsBoolean();}
    @Override public boolean knownClean(){
        return !uncertain && ocrClean && navigation.knownClean() && AndroidLiveUiReader.knownClean()
                && AndroidNewsTreeReader.knownClean() && AndroidCardRegionCapture.knownClean()
                && ReviewShizukuTapActor.newsClean() && ReviewShizukuBackActor.newsClean() && ReviewShizukuSwipeActor.newsClean();
    }
    @Override public void pause(long millis)throws Exception{
        check();
        if (millis<=0 || millis>ReadingNewsCycle.WAIT_MILLIS || millis>=authority.deadline()-now())
            throw reject("READING_WAIT_BUDGET");
        Thread.sleep(millis); check();
    }
    private void check(){if(!current()||!knownClean())throw reject("READING_OWNERSHIP_OR_CLEANUP");}
    private void reserve(String slot)throws IOException{
        check();
        files.create(preparation+slot+".burned","profile=READING_NEWS_V1 day="+authority.day()+"\n");
    }

    private BingNavigationSnapshot.Ready source(){
        check();
        var visit=navigation.visit(original);
        if (visit==null || visit.source()==null || !visit.source().matchesOriginal(original) || !visit.source().fresh(now()))
            throw reject(visit==null?"NEWS_SOURCE_MISSING":"NEWS_SOURCE_"+visit.gate().name());
        return visit.source().ready();
    }
    private record Snapshot(NewsFeedModel.Frame frame,BingNavigationSnapshot.Ready source,int window,long started) {}
    private record Located(Snapshot snapshot,NewsLocalOcr.Result ocr) {}
    private void sameSource(BingNavigationSnapshot.Ready expected) {
        if (!expected.sameLiveWindow(source())) throw reject("NEWS_WINDOW_CHANGED");
    }

    final class TreeGrant {
        final String nonce=UUID.randomUUID().toString().replace("-","");
        final long deadline=Math.min(authority.deadline(),now()+9000L);
        private final BingNavigationSnapshot.Ready before;
        private boolean spent;
        private TreeGrant(BingNavigationSnapshot.Ready before){this.before=before;}
        boolean current(){return NewsAndroidPort.this.current() && now()<deadline;}
        void consume()throws IOException{
            if(spent || !current() || !before.fresh(now()) || treeReads>=512) throw reject("NEWS_TREE_LEASE");
            sameSource(before);
            if(!before.fresh(now()) || !current())throw reject("NEWS_TREE_STALE");
            spent=true; reserve("TREE_"+(++treeReads));
        }
    }
    private Snapshot readFrame(NewsFeedModel.Card article)throws Exception{
        check();
        var before=source();
        var tree=AndroidNewsTreeReader.read(context,new TreeGrant(before));
        var after=source();
        var data=tree.source();
        if (!bound(data,before,after,shape,now())) throw reject("NEWS_TREE_SOURCE_BRACKET");
        var frame=NewsFeedModel.inspect(tree.nodes(),shape.width(),shape.height(),anchorY,article);
        if (frame.page()==NewsFeedModel.Page.REWARDS && !original.sameSource(after)) throw reject("NEWS_REWARDS_WINDOW");
        lastNodes=data.nodeCount(); lastStride=frame.stride(); detail=frame.reason();
        return new Snapshot(frame,after,data.windowId(),data.started());
    }
    static boolean bound(NativeUiStructureProtocol.Report data,BingNavigationSnapshot.Ready before,
                         BingNavigationSnapshot.Ready after,BingNavigationSnapshot.Shape shape,long now){
        return data!=null && data.qualified() && data.structureBound() && data.readiness()!=null
                && data.readiness().idle()==NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED && !data.readiness().blocked()
                && data.displayId()==0 && data.windowId()>=0 && before!=null && after!=null
                && before.sameLiveWindow(after) && before.observed()<=data.started() && before.fresh(data.started())
                && data.finished()<=after.lower() && after.fresh(now) && NativeProgressPublication.frameFresh(now,data.started())
                && shape.equals(after.profile().shape()) && shape.rotation()==0 && data.left()>=0 && data.top()>=0
                && data.right()>data.left() && data.bottom()>data.top() && data.right()<=shape.width() && data.bottom()<=shape.height()
                && (data.rotationBefore()==-1 || data.rotationBefore()==shape.rotation())
                && (data.rotationAfter()==-1 || data.rotationAfter()==shape.rotation());
    }
    private Snapshot readEntry()throws Exception{
        for(int sample=0;;sample++){
            var snapshot=readFrame(null);
            if(sample==2 || !original.sameSource(snapshot.source()) || !entryMayStillLoad(snapshot.frame()))return snapshot;
            pause(500);
        }
    }
    static boolean entryMayStillLoad(NewsFeedModel.Frame frame){
        return frame.page()==NewsFeedModel.Page.UNKNOWN && "NO_FEED_CARDS".equals(frame.reason());
    }
    private static void expectPage(Snapshot s,NewsFeedModel.Page page){
        if(s.frame().page()!=page)throw reject("EXPECTED_"+page.name()+"_"+s.frame().reason());
    }
    private Snapshot awaitFeed(boolean fromEntry)throws Exception{
        for (int sample=0;;sample++) {
            var snapshot=readFrame(fromEntry?null:opened);
            if (snapshot.frame().page()==NewsFeedModel.Page.FEED) return snapshot;
            if (sample==2 || !feedMayStillLoad(snapshot.frame(),fromEntry))
                throw reject("EXPECTED_FEED_"+snapshot.frame().reason());
            pause(500);
        }
    }
    static boolean feedMayStillLoad(NewsFeedModel.Frame frame, boolean fromEntry) {
        return switch(frame.page()){
            case REWARDS -> fromEntry;
            case ARTICLE -> !fromEntry;
            case UNKNOWN -> "NO_FEED_CARDS".equals(frame.reason()) || "NO_VERIFIED_CARD_GROUP".equals(frame.reason());
            default -> false;
        };
    }
    private static boolean sameTarget(Snapshot a,Snapshot b){
        if(a==null || b==null || a.window()!=b.window() || !a.source().sameLiveWindow(b.source()))return false;
        var left=a.frame();var right=b.frame();
        return left.page()==right.page() && Objects.equals(left.viewport(),right.viewport())
                && Objects.equals(left.entry(),right.entry()) && left.entryComplete()==right.entryComplete()
                && Objects.equals(left.card(),right.card()) && left.stride()==right.stride() && left.atEnd()==right.atEnd()
                && Objects.equals(left.reason(),right.reason());
    }
    private Snapshot refresh(Snapshot expected)throws Exception{
        var latest=readFrame(opened);
        if(!sameTarget(expected,latest))throw reject("NEWS_CARD_CHANGED");
        return latest;
    }
    private static Box cardBox(Snapshot frame) {
        return frame.frame().page()==NewsFeedModel.Page.REWARDS?frame.frame().entry():frame.frame().card().box();
    }
    private static SecurityException reject(String code){return new SecurityException(code);}

    // Only the complete, source-bound card plus a thin border can be acquired.
    final class Crop extends OwnedCardCropPermit {
        final Snapshot owner;
        final Box box;
        private final long issued=now(), limit=Math.min(authority.deadline(),issued+9000L);
        private boolean bound,spent,claimed,ended;
        private Crop(Snapshot owner,Box box){this.owner=owner;this.box=box;}
        boolean live(){return authority.live();}
        boolean current(long time){return !ended && time>=issued && time<limit && NewsAndroidPort.this.current();}
        long deadline(){return limit;}
        boolean afterWindow(){return false;}
        boolean activeForRetirement(long time){return !ended && time>=issued && NewsAndroidPort.this.current();}
        boolean bindRequest(Box requested,int width,int height,int rotation,long time){
            if(bound || !current(time) || !box.equals(requested) || width!=shape.width() || height!=shape.height()
                    || rotation!=shape.rotation())return false;
            bound=true;return true;
        }
        boolean consume()throws IOException{
            if(!bound || spent || !current(now()))return false;
            spent=true;reserve("CROP_"+(++serial));return true;
        }
        boolean claimDispatch(long time){
            if(!bound || !spent || claimed || !current(time))return false;
            claimed=true;return true;
        }
    }

    private Located locate(Snapshot frame,boolean publish)throws Exception{
        var card=cardBox(frame);
        if(card==null || crops>=96)throw reject("NEWS_CROP_BUDGET");
        var view=frame.frame().viewport();
        int margin=Math.min(6,Math.max(2,card.width()/128));
        Box crop=new Box(Math.max(view.left(),card.left()-margin),Math.max(view.top(),card.top()-margin),
                Math.min(view.right(),card.right()+margin),Math.min(view.bottom(),card.bottom()+margin));
        if(!view.contains(crop)||crop.equals(view)||!CardRegionCaptureProtocol.validCrop(crop,shape.width(),shape.height(),shape.rotation()))
            throw reject("NEWS_CROP_BOUNDS");
        var before=source();
        if(!frame.source().sameLiveWindow(before)||frame.source().observed()>before.lower()
                || !NativeProgressPublication.frameFresh(now(),frame.started()))throw reject("NEWS_CROP_STALE");
        long lower=now();
        var grant=new Crop(frame,crop);
        var gate=new AndroidCardRegionCapture.Gate() {
            public boolean consume()throws Exception{
                requireCurrent();
                return grant.consume();
            }
            public void requireCurrent(){
                if(!grant.current(now())||!before.fresh(now()))throw reject("NEWS_CROP_OWNER");
                sameSource(before);
            }
        };
        AndroidCardRegionCapture.Result acquired;
        try {
            acquired=AndroidCardRegionCapture.capture(context,UUID.randomUUID().toString().replace("-",""),grant.deadline(),
                    crop,shape.width(),shape.height(),shape.rotation(),grant,NewsAndroidPort.this::current,gate);
        } finally { grant.ended=true; }
        byte[] png=acquired.png();
        try{
            if(!acquired.knownClean()||acquired.status()!=AndroidCardRegionCapture.FixedStatus.CAPTURED||png==null)
                throw reject("NEWS_CROP_"+acquired.status().name());
            crops++;
            var after=source();
            if(!before.sameLiveWindow(after)||acquired.helperStartedMs()<lower||acquired.helperFinishedMs()<acquired.helperStartedMs()
                    ||acquired.helperFinishedMs()>after.lower()||!after.fresh(now()))throw reject("NEWS_CROP_SOURCE_BRACKET");
            var first=NewsLocalOcr.recognize(context,png.clone(),crop,frame.frame(),false,this::current);
            if(!first.clean()){ocrClean=false;throw reject("NEWS_OCR_CLEANUP");}
            var match=first;
            if(first.kind()==NewsFeedModel.Kind.UNKNOWN){
                check();
                match=NewsLocalOcr.recognize(context,png.clone(),crop,frame.frame(),true,this::current);
                if(!match.clean()){ocrClean=false;throw reject("NEWS_OCR_CLEANUP");}
            }
            var latest=refresh(frame);
            if (!NativeProgressPublication.frameFresh(now(),acquired.helperStartedMs()))throw reject("NEWS_OCR_EXPIRED");
            detail=match.reason();
            if(publish && !authority.taskInputAllowed() && latest.frame().page()==NewsFeedModel.Page.REWARDS
                    && match.kind()==NewsFeedModel.Kind.UNKNOWN){
                publishEntryDiagnostic(first,match,crop,png);
            } else if(publish)publishMark(latest,match,crop,png);
            return new Located(latest,match);
        } finally {if(png!=null)Arrays.fill(png,(byte)0);}
    }

    private void publishEntryDiagnostic(NewsLocalOcr.Result narrow,NewsLocalOcr.Result expanded,Box crop,byte[] png)throws Exception{
        String key=LiveRewardsTypes.hash(runId+"|"+(++serial)+"|ENTRY_OCR");
        var diag=new JSONObject().put("schemaVersion",1).put("profile","NEWS_ENTRY_OCR")
                .put("cropWidth",crop.width()).put("cropHeight",crop.height())
                .put("reasonNarrow",narrow.reason()).put("reasonExpanded",expanded.reason())
                .put("narrow",diagnosticParts(narrow.entryDiagnostic())).put("expanded",diagnosticParts(expanded.entryDiagnostic()))
                .put("rawTextIncluded",false).put("rawDataUploaded",false);
        artifacts.write(key,"PREFLIGHT","DIAGNOSTICS.json",diag.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        artifacts.write(key,"PREFLIGHT","CARD.png",png);
    }
    private static JSONArray diagnosticParts(String value){
        if(value==null || value.length()>1536)throw reject("ENTRY_DIAGNOSTIC_SIZE");
        JSONArray parts=new JSONArray();
        for(int offset=0;offset<value.length();offset+=256)
            parts.put(value.substring(offset,Math.min(value.length(),offset+256)));
        return parts;
    }
    private void publishMark(Snapshot latest,NewsLocalOcr.Result result,Box crop,byte[] png)throws Exception{
        if(result.kind()==NewsFeedModel.Kind.UNKNOWN)return;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,png.length,options);
        if(bitmap==null)throw reject("NEWS_MARK_DECODE");
        try(var out=new ByteArrayOutputStream()){
            if(bitmap.getWidth()!=crop.width()||bitmap.getHeight()!=crop.height()||!bitmap.isMutable())throw reject("NEWS_MARK_DIMENSIONS");
            var canvas=new Canvas(bitmap);var stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);stroke.setColor(Color.RED);stroke.setStrokeWidth(4);
            if(result.kind()==NewsFeedModel.Kind.AD){
                var src=latest.frame().card().source();
                if(src==null || !crop.contains(src))throw reject("NEWS_AD_MARK");
                canvas.drawRect(src.left()-crop.left(),src.top()-crop.top(),src.right()-crop.left(),src.bottom()-crop.top(),stroke);
            } else{
                if(!inside(cardBox(latest),result.x(),result.y()))throw reject("NEWS_MARK_POINT");
                canvas.drawCircle(result.x()-crop.left(),result.y()-crop.top(),Math.max(9,Math.min(22,crop.width()/30)),stroke);
            }
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw reject("NEWS_MARK_ENCODE");
            byte[] data=out.toByteArray();
            try{
                String key=LiveRewardsTypes.hash(runId+"|"+(++serial)+"|"+result.kind()+"|"+result.key());
                artifacts.write(key,latest.frame().page()==NewsFeedModel.Page.REWARDS?"PREFLIGHT":"BEFORE","MARKED.png",data);
                marked++;
            } finally{Arrays.fill(data,(byte)0);}
        } finally {bitmap.eraseColor(0);bitmap.recycle();}
    }
    private static boolean inside(Box box,int x,int y){
        return box!=null && x>box.left() && x<box.right()-1 && y>box.top() && y<box.bottom()-1;
    }

    final class Input {
        private final Object target;
        private final Snapshot expected;
        private final ReadingNewsCycle.BurnGate burn;
        private boolean claimed,spent;
        private Snapshot checked;
        private Input(Object target,Snapshot expected,ReadingNewsCycle.BurnGate burn){
            this.target=target;this.expected=expected;this.burn=burn;
        }
        boolean current(){return NewsAndroidPort.this.current() && knownClean();}
        boolean claim(Object exact){
            if(exact!=target||claimed||!current()||(!authority.taskInputAllowed()&&!(target instanceof ReviewShizukuSwipeActor.SwipeTarget)))return false;
            claimed=true;return true;
        }
        boolean consume(Object exact)throws Exception{
            if(exact!=target||!claimed||spent||!current())return false;
            checked=refresh(expected);
            if(!checked.source().fresh(now())||!current())return false;
            spent=true;burn.burn();
            return valid(exact);
        }
        boolean valid(Object exact){
            if(exact!=target || !spent || !current() || checked==null || !checked.source().fresh(now()))return false;
            sameSource(checked.source());
            return current() && checked.source().fresh(now());
        }
    }

    private long tap(Located located,ReadingNewsCycle.BurnGate budget,boolean entry)throws Exception{
        var match=located.ocr();
        if (!authority.taskInputAllowed() || match.kind()!=(entry?NewsFeedModel.Kind.ENTRY:NewsFeedModel.Kind.ARTICLE)
                || !inside(cardBox(located.snapshot()),match.x(),match.y()))throw reject("NEWS_TAP_PROOF");
        var target=new ReviewShizukuTapActor.TapTarget(match.x(),match.y(),shape.width(),shape.height(),shape.rotation());
        var grant=new Input(target,located.snapshot(),budget);
        taps++;
        var result=ReviewShizukuTapActor.newsTap(context,grant,target);
        long success=now();
        if(result.invocationAttempted())tapInvoked++;
        if(result.kind()==ReviewShizukuTapActor.Kind.DISPATCH_UNKNOWN)uncertain=true;
        if(!result.commandExitZero()||!knownClean())throw reject("NEWS_TAP_"+result.reason().name());
        tapSuccess++;if(entry)entryTaps++;
        return success;
    }
    private void swipe(Snapshot current,ReviewShizukuSwipeActor.Direction direction,int distance,boolean measured)throws Exception{
        if (swipes>=64)throw reject("NEWS_SCROLL_LIMIT");
        var target=new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),shape.height(),shape.rotation(),
                direction,distance,measured);
        if(!ReviewShizukuSwipeActor.valid(target)||!inside(current.frame().viewport(),target.x(),target.fromY())
                || !inside(current.frame().viewport(),target.x(),target.toY()))throw reject("NEWS_SCROLL_GEOMETRY");
        var grant=new Input(target,current,()->reserve("SWIPE_"+(++serial)));
        var result=ReviewShizukuSwipeActor.newsSwipe(context,grant,target);
        if(result.kind()==ReviewShizukuSwipeActor.Kind.DISPATCH_UNKNOWN)uncertain=true;
        if(!result.commandExitZero() || !knownClean())throw reject("NEWS_SWIPE_"+result.reason().name());
        swipes++;
    }

    @Override public void assertFeed()throws Exception{
        if(!listConfirmed)throw reject("NEWS_FEED_NOT_ENTERED");
        step="VERIFY_FEED";
        awaitFeed(false);
    }
    @Override public ReadingNewsCycle.CardProof readCard()throws Exception{
        step="IDENTIFY_CARD";
        var current=readFrame(null);
        expectPage(current,NewsFeedModel.Page.FEED);
        var model=current.frame();
        if(model.card()==null){
            if(model.atEnd())return new ReadingNewsCycle.CardProof(ReadingNewsCycle.Kind.END,"","",true);
            throw reject(model.reason());
        }
        if(anchorY<0)anchorY=(int)model.card().box().centerY();
        var evidence=locate(current,true);
        var local=evidence.ocr();
        var kind=switch(local.kind()){
            case ARTICLE -> ReadingNewsCycle.Kind.ARTICLE;
            case AD -> ReadingNewsCycle.Kind.AD;
            default -> ReadingNewsCycle.Kind.UNKNOWN;
        };
        if(kind==ReadingNewsCycle.Kind.ARTICLE && (model.card().kind()!=NewsFeedModel.Kind.ARTICLE
                ||!model.card().key().equals(local.key())))throw reject("NEWS_ARTICLE_IDENTITY");
        lastCandidate=evidence.snapshot();
        lastProof=new ReadingNewsCycle.CardProof(kind,kind==ReadingNewsCycle.Kind.ARTICLE?local.key():"",
                model.card().layoutKey(),model.atEnd());
        return lastProof;
    }
    @Override public long tapArticle(ReadingNewsCycle.CardProof proof,ReadingNewsCycle.BurnGate gate)throws Exception{
        step="TAP_ARTICLE";
        if(!listConfirmed || proof==null || !proof.equals(lastProof) || proof.kind()!=ReadingNewsCycle.Kind.ARTICLE)
            throw reject("NEWS_ARTICLE_NOT_IDENTIFIED");
        var fresh=locate(refresh(lastCandidate),false);
        if(fresh.ocr().kind()!=NewsFeedModel.Kind.ARTICLE || !proof.key().equals(fresh.ocr().key()))
            throw reject("NEWS_ARTICLE_CHANGED");
        opened=fresh.snapshot().frame().card();
        tapReturned=tap(fresh,gate,false); step="ARTICLE_WAIT";
        return tapReturned;
    }
    @Override public ReadingNewsCycle.Page pageAfterWait(ReadingNewsCycle.CardProof card)throws Exception{
        if(!articleDue(card))throw reject("NEWS_ARTICLE_EARLY");
        step="ARTICLE_SOURCE";
        return switch(readFrame(opened).frame().page()){
            case ARTICLE -> ReadingNewsCycle.Page.ARTICLE;
            case FEED -> ReadingNewsCycle.Page.FEED;
            case BLOCKED -> ReadingNewsCycle.Page.BLOCKED;
            default -> ReadingNewsCycle.Page.UNKNOWN;
        };
    }
    private boolean articleDue(ReadingNewsCycle.CardProof card){
        return current() && listConfirmed && opened!=null && card!=null && card.key().equals(opened.key())
                && tapReturned>=0 && now()-tapReturned>=ReadingNewsCycle.WAIT_MILLIS;
    }
    @Override public void back(ReadingNewsCycle.CardProof card,ReadingNewsCycle.BurnGate gate)throws Exception{
        if(!articleDue(card))throw reject("NEWS_BACK_EARLY");
        step="RETURN_ARTICLE";
        var article=readFrame(opened); expectPage(article,NewsFeedModel.Page.ARTICLE);
        var target=new ReviewShizukuBackActor.BackTarget(shape.width(),shape.height(),shape.rotation());
        var grant=new Input(target,article,gate); backs++;
        var result=ReviewShizukuBackActor.newsBack(context,grant,target);
        if(result.invocationAttempted())backInvoked++;
        if(result.kind()==ReviewShizukuBackActor.Kind.DISPATCH_UNKNOWN)uncertain=true;
        if(!result.commandExitZero()||!knownClean())throw reject("NEWS_BACK_"+result.reason().name());
        backSuccess++; tapReturned=-1; pause(350);
    }
    @Override public void advance(ReadingNewsCycle.CardProof card)throws Exception{
        if(!listConfirmed || !Objects.equals(card,lastProof))throw reject("NEWS_SCROLL_CARD");
        step="ADVANCE_CARD";
        var current=refresh(lastCandidate);
        expectPage(current,NewsFeedModel.Page.FEED);
        if(current.frame().stride()<=0)throw reject("NEWS_CARD_HEIGHT");
        swipe(current,ReviewShizukuSwipeActor.Direction.UP,current.frame().stride(),true);
        pause(450);
    }
    String state(){return step;}
    int tapCalls(){return taps;}
    int tapDispatches(){return tapInvoked;}
    int tapSuccesses(){return tapSuccess;}
    int backCalls(){return backs;}
    int backDispatches(){return backInvoked;}
    int backSuccesses(){return backSuccess;}
    int marks(){return marked;}
    int captures(){return crops;}
    boolean inputUnknown(){return uncertain;}
    JSONObject report()throws JSONException{
        var s=cycle==null?null:cycle.snapshot();
        var visits=new JSONArray();
        if(s!=null)for(var visit:s.visits())visits.put(new JSONObject().put("key",visit.articleKey())
                .put("tapElapsed",visit.tapElapsed()).put("waitMillis",visit.waitMillis()).put("automatic",visit.automatic()));
        return new JSONObject().put("schemaVersion",1).put("profile",authority.taskInputAllowed()?"READING_NEWS_V1":"READING_NEWS_PREFLIGHT")
                .put("runId",runId).put("day",authority.day()).put("taskInputAllowed",authority.taskInputAllowed())
                .put("status",outcome).put("step",step).put("reason",detail).put("listConfirmed",listConfirmed)
                .put("pilotPassed",s!=null && s.pilotPassed()).put("entryTaps",entryTaps).put("storyTaps",tapSuccess-entryTaps)
                .put("storyBacks",backSuccess).put("ads",s==null?0:s.ads()).put("visits",visits)
                .put("attempts",s==null?0:s.attempts()).put("openedAndReturned",s==null?0:s.openedAndReturned())
                .put("autoReturns",s==null?0:s.autoReturns()).put("duplicateArticles",s==null?0:s.duplicateArticles())
                .put("unreadable",s==null?0:s.unreadable()).put("swipes",swipes).put("treeReads",treeReads)
                .put("captures",crops).put("marks",marked).put("lastNodeCount",lastNodes).put("lastCardStridePx",lastStride)
                .put("minimumVisitDelayMs",ReadingNewsCycle.WAIT_MILLIS).put("rawTextIncluded",false).put("rawDataUploaded",false);
    }
}
