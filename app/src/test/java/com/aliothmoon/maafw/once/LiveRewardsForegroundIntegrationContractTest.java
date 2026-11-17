package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;

/** Pure lease/authority behavior plus static Android wiring. No service or input is executed. */
public final class LiveRewardsForegroundIntegrationContractTest {
    private static final String ANDROID="http://schemas.android.com/apk/res/android";
    private static final class Clock implements LiveRewardsForegroundLease.Clock,LiveRunAuthority.Clock {
        long now=100;String day="2026-11-17";
        public long elapsedRealtime(){return now;}public String day(){return day;}
    }
    private static final class Fixture {
        final Clock clock=new Clock();final Object owner=new Object(),service=new Object();
        final AtomicInteger ready=new AtomicInteger(),revoked=new AtomicInteger(),destroyed=new AtomicInteger();
        final LiveRewardsForegroundLease lease=new LiveRewardsForegroundLease(owner,
                "11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222",100,
                100+LiveRunAuthority.MAX_RUN_MILLIS,clock.day,clock,ready::incrementAndGet,revoked::incrementAndGet,destroyed::incrementAndGet);
        void makeReady(){assertTrue(lease.beginDispatch(owner));assertTrue(lease.deliver(service));assertTrue(lease.foregroundReturned(service));}
        LiveRunAuthority authority(){return LiveRunAuthority.synthetic(owner,new RealCaptureClaim.ProcessEpoch(),clock,lease::ready);}
    }
    @Test public void startReturnAndDeliveryCannotMakeTheSameAuthorityCurrent(){
        var f=new Fixture();var authority=f.authority();assertFalse(authority.current());
        assertTrue(f.lease.beginDispatch(f.owner));f.lease.dispatchReturned(f.owner);
        assertTrue(f.lease.startReturned());assertFalse(f.lease.ready());assertFalse(authority.current());assertEquals(0,f.ready.get());
        assertTrue(f.lease.deliver(f.service));assertFalse(authority.current());
        assertTrue(f.lease.foregroundReturned(f.service));assertTrue(authority.current());assertEquals(1,f.ready.get());
        assertFalse(f.lease.foregroundReturned(f.service));assertEquals(1,f.ready.get());
    }
    @Test public void anActualServiceLossRevokesTheSameAuthorityAndCannotBeResurrected(){
        var f=new Fixture();f.makeReady();var authority=f.authority();assertTrue(authority.current());
        f.lease.serviceDestroyed(new Object());assertTrue(authority.current());assertEquals(0,f.destroyed.get());
        f.lease.serviceDestroyed(f.service);assertFalse(authority.current());
        assertEquals(LiveRewardsForegroundLease.Reason.SERVICE_LOST,f.lease.reason());assertEquals(1,f.revoked.get());assertEquals(1,f.destroyed.get());
        assertFalse(f.lease.beginDispatch(f.owner));assertFalse(f.lease.deliver(new Object()));assertFalse(f.lease.foregroundReturned(f.service));
        f.lease.serviceDestroyed(f.service);assertEquals(1,f.destroyed.get());assertFalse(authority.current());
    }
    @Test public void aStopRequestIsNotTheActualDestructionAcknowledgement(){
        var f=new Fixture();f.makeReady();var authority=f.authority();
        f.lease.requestClose(f.owner);assertFalse(authority.current());assertFalse(f.lease.destroyed());assertEquals(0,f.destroyed.get());
        f.lease.dispatchReturned(f.owner);assertFalse(f.lease.destroyed());
        f.lease.serviceDestroyed(f.service);assertTrue(f.lease.destroyed());assertEquals(1,f.destroyed.get());assertEquals(0,f.revoked.get());
        f.lease.requestClose(f.owner);f.lease.serviceDestroyed(f.service);assertEquals(1,f.destroyed.get());
    }
    @Test public void foregroundReadinessCannotExtendTheOriginalDayOrDeadline(){
        var expired=new Fixture();expired.makeReady();var authority=expired.authority();
        long originalDeadline=authority.deadline();expired.clock.now=originalDeadline;assertFalse(authority.current());assertFalse(expired.lease.ready());
        assertEquals(originalDeadline,authority.deadline());assertEquals(LiveRewardsForegroundLease.Reason.DEADLINE,expired.lease.reason());
        var midnight=new Fixture();midnight.makeReady();var same=midnight.authority();midnight.clock.day="2026-11-18";
        assertFalse(same.current());assertFalse(midnight.lease.ready());assertEquals("2026-11-17",same.day());
        midnight.clock.day="2026-11-17";assertFalse(same.current());assertFalse(midnight.lease.ready());
        var startup=new Fixture();assertTrue(startup.lease.beginDispatch(startup.owner));startup.clock.now=startup.lease.startupDeadline;
        assertFalse(startup.lease.deliver(startup.service));assertFalse(startup.lease.foregroundReturned(startup.service));assertEquals(0,startup.ready.get());
    }
    @Test public void theAcknowledgementTimestampMustBeInsideTheStartupWindow(){
        var reads=new AtomicInteger();var ready=new AtomicInteger();boolean[] atBoundary={false};
        var clock=new LiveRewardsForegroundLease.Clock(){
            public long elapsedRealtime(){return atBoundary[0]?(reads.incrementAndGet()==1?5_099:5_100):100;}
            public String day(){return "2026-11-17";}
        };
        Object owner=new Object(),service=new Object();
        var lease=new LiveRewardsForegroundLease(owner,"11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",100,1_800_100,"2026-11-17",clock,ready::incrementAndGet,()->{},()->{});
        assertTrue(lease.beginDispatch(owner));assertTrue(lease.deliver(service));atBoundary[0]=true;
        boolean accepted=lease.foregroundReturned(service);
        if(accepted)assertTrue("The exact timestamp used to admit readiness must precede its original startup deadline",lease.readyElapsed()<lease.startupDeadline);
        assertEquals(accepted?1:0,ready.get());
    }

    private static Path root(){
        Path path=Path.of(System.getProperty("staging.root",".")).toAbsolutePath().normalize();
        for(int i=0;i<4&&path!=null;i++,path=path.getParent())
            if(Files.isRegularFile(path.resolve("capture-review/src/main/AndroidManifest.xml")))return path;
        throw new IllegalStateException("Production source root required for foreground contract");
    }
    private static Unit source(String name)throws Exception{
        return Unit.parse(Files.readString(root().resolve("app/src/main/java/com/aliothmoon/maafw/once/"+name+".java")));
    }
    /** Small source-contract lexer, not a Java compiler or proof of arbitrary control flow. */
    private static final class Token {
        final String text;final boolean literal;
        Token(String text,boolean literal){this.text=text;this.literal=literal;}
        boolean is(String value){return !literal&&text.equals(value);}
        boolean identifier(){return !literal&&!text.isEmpty()&&Character.isJavaIdentifierStart(text.charAt(0));}
    }
    private static List<Token> lex(String text){
        List<Token> result=new ArrayList<>();
        for(int i=0;i<text.length();){
            char c=text.charAt(i);
            if(Character.isWhitespace(c)){i++;continue;}
            if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='/'){
                i+=2;while(i<text.length()&&text.charAt(i)!='\n')i++;continue;
            }
            if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='*'){
                int end=text.indexOf("*/",i+2);assertTrue("Closed source comment required",end>=0);i=end+2;continue;
            }
            if(c=='\"'||c=='\''){
                boolean textBlock=c=='\"'&&text.startsWith("\"\"\"",i);int step=textBlock?3:1;i+=step;
                StringBuilder value=new StringBuilder();boolean closed=false;
                while(i<text.length()){
                    if(textBlock&&text.startsWith("\"\"\"",i)){i+=3;closed=true;break;}
                    char next=text.charAt(i++);
                    if(!textBlock&&next==c){closed=true;break;}
                    if(next=='\\'&&i<text.length()){value.append(text.charAt(i++));continue;}
                    value.append(next);
                }
                assertTrue("Closed source literal required",closed);result.add(new Token(value.toString(),true));continue;
            }
            if(Character.isJavaIdentifierStart(c)){
                int start=i++;while(i<text.length()&&Character.isJavaIdentifierPart(text.charAt(i)))i++;
                result.add(new Token(text.substring(start,i),false));continue;
            }
            String pair=i+1<text.length()?text.substring(i,i+2):"";
            if(Set.of("::","->","&&","||","<=",">=","==","!=").contains(pair)){result.add(new Token(pair,false));i+=2;}
            else{result.add(new Token(String.valueOf(c),false));i++;}
        }
        return result;
    }
    private static int matching(List<Token> tokens,int open){
        String begin=tokens.get(open).text,end=begin.equals("(")?")":begin.equals("{")?"}":"]";int depth=0;
        for(int i=open;i<tokens.size();i++){
            if(tokens.get(i).is(begin))depth++;
            if(tokens.get(i).is(end)&&--depth==0)return i;
        }
        throw new AssertionError("Balanced source delimiter required");
    }
    private static String joined(List<Token> tokens){StringBuilder out=new StringBuilder();for(var token:tokens)out.append(token.text);return out.toString();}
    private static final class Unit {
        final List<Token> tree;final Map<String,List<Token>> methods=new LinkedHashMap<>();
        private Unit(List<Token> tokens){
            tree=tokens;int declaration=0;
            while(declaration<tokens.size()&&!tokens.get(declaration).is("class"))declaration++;
            assertTrue("Top-level source class required",declaration+1<tokens.size());String className=tokens.get(declaration+1).text;
            int open=declaration+2;while(open<tokens.size()&&!tokens.get(open).is("{"))open++;
            assertTrue("Class body required",open<tokens.size());int end=matching(tokens,open),member=open+1;
            for(int i=member;i<end;){
                if(tokens.get(i).is(";")){member=++i;continue;}
                if(tokens.get(i).is("{")){i=matching(tokens,i)+1;member=i;continue;}
                if(tokens.get(i).is("(")){
                    int close=matching(tokens,i),body=close+1;while(body<end&&!tokens.get(body).is("{")&&!tokens.get(body).is(";")&&!tokens.get(body).is("->"))body++;
                    boolean field=false;for(int j=member;j<i;j++)field|=tokens.get(j).is("=")||tokens.get(j).is("class")||tokens.get(j).is("record");
                    if(i>member&&tokens.get(i-1).identifier()&&body<end&&tokens.get(body).is("{")&&!field){
                        String name=tokens.get(i-1).text;int finished=matching(tokens,body);
                        if(!name.equals(className))assertNull("Unique source contract method required: "+name,methods.put(name,List.copyOf(tokens.subList(body+1,finished))));
                        i=finished+1;member=i;continue;
                    }
                    i=close+1;continue;
                }
                i++;
            }
        }
        static Unit parse(String source){return new Unit(lex(source));}
        List<Token> method(String name){var method=methods.get(name);assertNotNull("Production method required: "+name,method);return method;}
    }
    private static final class Call {
        final String name;final int end;final List<List<Token>> arguments;
        Call(String name,int end,List<List<Token>> arguments){this.name=name;this.end=end;this.arguments=arguments;}
    }
    private static List<Call> invocations(List<Token> tokens){
        List<Call> result=new ArrayList<>();
        for(int i=0;i<tokens.size();i++){
            if(tokens.get(i).is("new")){
                int next=i+1;while(next<tokens.size()&&(tokens.get(next).identifier()||tokens.get(next).is(".")))next++;
                result.add(new Call("new "+joined(tokens.subList(i+1,next)),next,List.of()));
            }
            if(tokens.get(i).is("::")&&i+1<tokens.size())result.add(new Call("ref "+tokens.get(i+1).text,i+2,List.of()));
            if(!tokens.get(i).is("(")||i==0||!tokens.get(i-1).identifier())continue;
            int first=i-1;while(first>=2&&tokens.get(first-1).is(".")&&tokens.get(first-2).identifier())first-=2;
            String name=joined(tokens.subList(first,i));
            if(Set.of("if","for","while","switch","catch","synchronized","try").contains(name)||first>0&&tokens.get(first-1).is("new"))continue;
            int close=matching(tokens,i);List<List<Token>> arguments=new ArrayList<>();int start=i+1;
            for(int j=start;j<close;j++){
                if(tokens.get(j).is("(")||tokens.get(j).is("{")||tokens.get(j).is("[")){j=matching(tokens,j);continue;}
                if(tokens.get(j).is(",")){arguments.add(tokens.subList(start,j));start=j+1;}
            }
            if(start<close)arguments.add(tokens.subList(start,close));result.add(new Call(name,close+1,arguments));
        }
        return result;
    }
    private static List<String> calls(List<Token> tokens){List<String> result=new ArrayList<>();for(var call:invocations(tokens))result.add(call.name);return result;}
    private static String simple(String call){int dot=call.lastIndexOf('.');return dot<0?call:call.substring(dot+1);}
    private static boolean hasCall(List<Token> tokens,String name){return calls(tokens).stream().anyMatch(call->simple(call).equals(name));}
    private static final class Statement {
        final String kind;final List<Token> tokens,condition;final List<Statement> body;final Statement yes,no;final int end;
        Statement(String kind,List<Token> tokens,List<Token> condition,List<Statement> body,Statement yes,Statement no,int end){
            this.kind=kind;this.tokens=tokens;this.condition=condition;this.body=body;this.yes=yes;this.no=no;this.end=end;
        }
    }
    private static List<Statement> statements(List<Token> tokens){
        List<Statement> result=new ArrayList<>();for(int i=0;i<tokens.size();){var statement=statement(tokens,i);result.add(statement);i=statement.end;}return result;
    }
    private static Statement statement(List<Token> tokens,int start){
        String kind=tokens.get(start).literal?"LITERAL":tokens.get(start).text;int end=start+1;List<Token> condition=List.of();List<Statement> body=List.of();Statement yes=null,no=null;
        if(tokens.get(start).is("if")){
            assertTrue("If condition required",tokens.get(end).is("("));int close=matching(tokens,end);condition=tokens.subList(end+1,close);
            yes=statement(tokens,close+1);end=yes.end;
            if(end<tokens.size()&&tokens.get(end).is("else")){no=statement(tokens,end+1);end=no.end;}
        }else if(tokens.get(start).is("try")){
            if(tokens.get(end).is("("))end=matching(tokens,end)+1;
            assertTrue("Try body required",tokens.get(end).is("{"));int close=matching(tokens,end);body=statements(tokens.subList(end+1,close));end=close+1;
            while(end<tokens.size()&&(tokens.get(end).is("catch")||tokens.get(end).is("finally"))){
                end++;if(tokens.get(end).is("("))end=matching(tokens,end)+1;end=matching(tokens,end)+1;
            }
        }else if(tokens.get(start).is("{")){
            end=matching(tokens,start)+1;body=statements(tokens.subList(start+1,end-1));
        }else{
            end=start;while(end<tokens.size()&&!tokens.get(end).is(";")){
                if(tokens.get(end).is("(")||tokens.get(end).is("{")||tokens.get(end).is("["))end=matching(tokens,end);end++;
            }
            if(end<tokens.size())end++;
        }
        return new Statement(kind,tokens.subList(start,end),condition,body,yes,no,end);
    }
    private static boolean terminates(Statement statement){
        if(statement.kind.equals("return")||statement.kind.equals("throw"))return true;
        if(statement.kind.equals("{"))return !statement.body.isEmpty()&&terminates(statement.body.get(statement.body.size()-1));
        return statement.kind.equals("if")&&statement.no!=null&&terminates(statement.yes)&&terminates(statement.no);
    }
    private static boolean rejectsUnless(List<Token> condition,String method){
        for(int i=0;i+1<condition.size();i++)if(condition.get(i).is("!")){
            int end=i+1;if(condition.get(end).is("("))end=matching(condition,end)+1;
            else{while(end<condition.size()&&(condition.get(end).identifier()||condition.get(end).is(".")))end++;
                if(end<condition.size()&&condition.get(end).is("("))end=matching(condition,end)+1;}
            if(hasCall(condition.subList(i+1,end),method))return true;
        }
        return false;
    }
    private static List<Statement> sequence(List<Token> method){
        List<Statement> result=new ArrayList<>();for(var statement:statements(method)){
            if(statement.kind.equals("try"))for(var nested:statement.body)result.addAll(nested.kind.equals("try")?sequence(nested.tokens):List.of(nested));
            else result.add(statement);
        }return result;
    }
    private static void readyBeforeAuthority(Unit source){
        var ready=source.method("foregroundReady");boolean guarded=false,minted=false,handedOff=false;
        for(var statement:sequence(ready)){
            var invoked=calls(statement.tokens);
            if(invoked.contains("new LiveRunAuthority")){assertTrue("Ready must reject before consuming visible authority",guarded);minted=true;}
            if(invoked.stream().anyMatch(call->simple(call).equals("handoff")||call.equals("ref handoff"))){assertTrue("Handoff cannot precede the unique authority",minted);handedOff=true;}
            if(statement.kind.equals("if")&&rejectsUnless(statement.condition,"ready")&&terminates(statement.yes))guarded=true;
        }
        assertTrue("Ready creates the one live authority",minted);assertTrue("Ready wires the existing handoff",handedOff);
        for(var entry:source.methods.entrySet())if(!entry.getKey().equals("foregroundReady"))
            assertFalse("No other callback may mint authority",calls(entry.getValue()).contains("new LiveRunAuthority"));
    }
    private static void destroyedBeforeRelease(Unit source){
        var finish=source.method("finishAfterForeground");boolean guarded=false,report=false,released=false,receipt=false;
        for(var statement:sequence(finish)){
            for(String call:calls(statement.tokens)){
                if(simple(call).equals("publishTerminalReports")){assertTrue("Terminal report requires actual cleanup acknowledgement",guarded);report=true;}
                if(simple(call).equals("releaseIfClosed")){assertTrue("Root release requires acknowledgement and the report attempt",guarded&&report);released=true;}
                if(simple(call).equals("writeReleaseReceipt")){assertTrue("RELEASE follows the exact root release",released);receipt=true;}
            }
            if(statement.kind.equals("if")&&rejectsUnless(statement.condition,"allResourcesClosed")&&terminates(statement.yes))guarded=true;
        }
        assertTrue("Finalization must retain report/root/receipt sequence",report&&released&&receipt);
        var complete=source.method("allResourcesClosed");
        assertTrue("Native cleanup remains part of completion",hasCall(complete,"nativeWorkClosed"));
        assertTrue("Service destruction remains part of completion",hasCall(complete,"destroyed"));
    }
    private static void workOutcomeSurvivesForeground(Unit source){
        var close=source.method("closeOnOwner");int seal=-1,overwritten=-1;
        for(int i=0;i+3<close.size();i++){
            if(close.get(i).is("terminalWorkStage")&&close.get(i+1).is("=")&&close.get(i+2).is("stage")&&close.get(i+3).is(";"))seal=i;
            if(close.get(i).is("stage")&&close.get(i+1).is("=")&&close.get(i+2).literal&&close.get(i+2).text.equals("FOREGROUND_STOPPING"))overwritten=i;
        }
        assertTrue("Foreground shutdown must preserve the actual terminal work stage",seal>=0&&overwritten>seal);
        var finish=source.method("finishAfterForeground");assertTrue(hasCall(finish,"workStageAfterForeground"));
        for(var token:finish)assertFalse("A missing coordinator summary cannot mean cancellation",token.is("summary"));
    }
    private static void noIntentAuthorityOrRestart(Unit source){
        Set<String> forbidden=Set.of("getExtras","getStringExtra","getParcelableExtra","getSerializableExtra","putExtra",
                "issueLiveRewardsApproval","fixtureLiveRewardsApproval","synthetic","forTest");
        for(String call:calls(source.tree)){
            assertFalse("Service must not reconstruct or receive action authority: "+call,forbidden.contains(simple(call)));
            assertFalse("Service grants only liveness: "+call,Set.of("new LiveRunAuthority","new LiveActionPermit","new LiveRewardsCoordinator","new LiveRewardsRuntime").contains(call));
            assertFalse("Service cannot implicitly launch a run",call.equals("LiveRewardsRuntime.start"));
        }
        List<String> returned=new ArrayList<>();var lifecycle=source.method("onStartCommand");
        for(int i=0;i<lifecycle.size();i++)if(lifecycle.get(i).is("return")){
            int end=i+1;while(end<lifecycle.size()&&!lifecycle.get(end).is(";"))end++;
            returned.add(joined(lifecycle.subList(i+1,end)));
        }
        assertFalse("Service lifecycle must have an explicit nonsticky result",returned.isEmpty());
        for(String value:returned)assertTrue("Service must never request automatic restart",value.equals("START_NOT_STICKY")||value.equals("Service.START_NOT_STICKY"));
    }
    @Test public void architectureChecksRejectUnsafeCallbacksInsteadOfMatchingComments()throws Exception{
        String head="class Contract { void cancel(){} void handoff(){} ";
        readyBeforeAuthority(Unit.parse(head+"void foregroundReady(){if(!lease.ready()){cancel();return;} authority=new LiveRunAuthority();handoff();}}"));
        assertThrows(AssertionError.class,()->readyBeforeAuthority(Unit.parse(head+"void foregroundReady(){/* ready checked */ authority=new LiveRunAuthority();handoff();}}")));
        assertThrows(AssertionError.class,()->readyBeforeAuthority(Unit.parse(head+"void foregroundReady(){String fake=\"if(!lease.ready()){cancel();return;}\";authority=new LiveRunAuthority();handoff();}}")));
        assertThrows(AssertionError.class,()->readyBeforeAuthority(Unit.parse(head+"void foregroundReady(){if(!lease.ready()){\"return\".toString();}authority=new LiveRunAuthority();handoff();}}")));
        assertThrows(AssertionError.class,()->readyBeforeAuthority(Unit.parse(head+"void foregroundReady(){if(!lease.ready()){cancel();return;}handoff();authority=new LiveRunAuthority();}}")));
        noIntentAuthorityOrRestart(Unit.parse("class Contract { int onStartCommand(){return START_NOT_STICKY;} }"));
        assertThrows(AssertionError.class,()->noIntentAuthorityOrRestart(Unit.parse("class Contract { int onStartCommand(){intent.getExtras();return START_NOT_STICKY;} }")));
        assertThrows(AssertionError.class,()->noIntentAuthorityOrRestart(Unit.parse("class Contract { int onStartCommand(){return START_STICKY;} }")));
        String finish="class Contract { boolean allResourcesClosed(){return nativeWorkClosed()&&lease.destroyed();} ";
        destroyedBeforeRelease(Unit.parse(finish+"void finishAfterForeground(){if(!allResourcesClosed())return;publishTerminalReports();root.releaseIfClosed();writeReleaseReceipt();}}"));
        assertThrows(AssertionError.class,()->destroyedBeforeRelease(Unit.parse(finish+"void finishAfterForeground(){lease.requestClose();publishTerminalReports();root.releaseIfClosed();writeReleaseReceipt();}}")));
        assertThrows(AssertionError.class,()->destroyedBeforeRelease(Unit.parse(finish+"void finishAfterForeground(){if(!allResourcesClosed())return;publishTerminalReports();writeReleaseReceipt();root.releaseIfClosed();}}")));
        String terminal="class Contract { void closeOnOwner(){terminalWorkStage=stage;stage=\"FOREGROUND_STOPPING\";} ";
        workOutcomeSurvivesForeground(Unit.parse(terminal+"void finishAfterForeground(){stage=workStageAfterForeground(stage,terminalWorkStage);}}"));
        assertThrows(AssertionError.class,()->workOutcomeSurvivesForeground(Unit.parse("class Contract { void closeOnOwner(){stage=\"FOREGROUND_STOPPING\";} void finishAfterForeground(){stage=summary==null?\"CANCELED\":\"COMPLETE\";}}")));
        assertThrows(AssertionError.class,()->workOutcomeSurvivesForeground(Unit.parse(terminal+"void finishAfterForeground(){stage=summary==null?\"CANCELED\":\"COMPLETE\";}}")));
    }
    @Test public void thePrivateServiceCannotReceiveAuthorityOrRestartAProcessRun()throws Exception{
        var service=source("LiveRewardsForegroundService");noIntentAuthorityOrRestart(service);
        var start=calls(service.method("onStartCommand"));
        assertTrue("Foreground admission must acknowledge the actual promotion command",start.indexOf("startForeground")>=0);
        assertTrue("A returned start request cannot replace successful promotion",start.indexOf("exact.foregroundReturned")>start.indexOf("startForeground"));
    }
    @Test public void foregroundReadinessPrecedesTheOnlyAuthorityAndExistingHandoff()throws Exception{
        var runtime=source("LiveRewardsRuntime");readyBeforeAuthority(runtime);
        assertTrue("All existing authority consumers must depend on foreground readiness",hasCall(runtime.method("sessionCurrent"),"ready"));
        assertFalse("The visible entry cannot hand off before the service acknowledges readiness",hasCall(runtime.method("start"),"handoff"));
    }
    @Test public void actualServiceDestructionPrecedesTerminalReportAndRootRelease()throws Exception{
        var runtime=source("LiveRewardsRuntime");destroyedBeforeRelease(runtime);
        assertTrue("The owner requests closure without pretending it completed",hasCall(runtime.method("closeOnOwner"),"requestClose"));
        assertTrue("Worker completion cannot directly release a still-running service",hasCall(runtime.method("run"),"closeOnOwner"));
        var lifecycle=calls(source("LiveRewardsForegroundService").method("onDestroy"));
        assertTrue(lifecycle.indexOf("super.onDestroy")>=0);
        assertTrue("Actual callback acknowledges destruction after Android superclass cleanup",lifecycle.indexOf("exact.serviceDestroyed")>lifecycle.indexOf("super.onDestroy"));
    }
    @Test public void startupFailureReceiptsUseTheSameSealedDayWithoutNeedingAnAuthority()throws Exception{
        var runtime=source("LiveRewardsRuntime");
        for(String method:List.of("reportJson","writeReleaseReceipt")){
            assertFalse("A pre-ready cancellation has no action authority",calls(runtime.method(method)).contains("authority.day"));
            boolean sealed=false;
            for(var call:invocations(runtime.method(method)))if(simple(call.name).equals("put")&&call.arguments.size()==2){
                var key=call.arguments.get(0);var value=call.arguments.get(1);
                sealed|=key.size()==1&&key.get(0).literal&&key.get(0).text.equals("day")&&value.size()==1&&value.get(0).is("runDay");
            }
            assertTrue("Receipts retain the visible run's sealed day",sealed);
        }
        var bootstrap=calls(runtime.method("bootstrapCurrent"));
        assertTrue("Startup remains bounded by the sealed date",bootstrap.contains("runDay.equals"));
        assertTrue("Startup reads the current date without replacing the sealed date",bootstrap.contains("LocalDate.now"));
        boolean bounded=false;var gate=runtime.method("bootstrapCurrent");
        for(var call:invocations(gate))if(simple(call.name).equals("elapsedRealtime")&&call.end+3<gate.size())
            bounded|=gate.get(call.end).is("<")&&joined(gate.subList(call.end+1,call.end+4)).equals("approval.deadline");
        assertTrue("Startup may not create a fresh thirty-minute clock",bounded);
        var entry=calls(runtime.method("startBound"));
        assertTrue("The original day is sealed before visible approval is issued",entry.indexOf("LocalDate.now")>=0
                &&entry.indexOf("LocalDate.now")<entry.indexOf("VisibleCaptureAuthority.issueLiveRewardsApproval"));
    }
    @Test public void theReviewManifestAddsOnlyTheApprovedPrivateSpecialUseService()throws Exception{
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var document=factory.newDocumentBuilder().parse(root().resolve("capture-review/src/main/AndroidManifest.xml").toFile());
        Set<String> permissions=new HashSet<>();var entries=document.getElementsByTagName("uses-permission");
        for(int i=0;i<entries.getLength();i++){String name=((org.w3c.dom.Element)entries.item(i)).getAttributeNS(ANDROID,"name");if(!name.isEmpty())permissions.add(name);}
        assertEquals(Set.of("moe.shizuku.manager.permission.API_V23","android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION","android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),permissions);
        int matches=0;var services=document.getElementsByTagName("service");
        for(int i=0;i<services.getLength();i++){
            var entry=(org.w3c.dom.Element)services.item(i);if(!entry.getAttributeNS(ANDROID,"name").equals(".once.LiveRewardsForegroundService"))continue;
            matches++;assertEquals("false",entry.getAttributeNS(ANDROID,"exported"));assertEquals(":capture_once",entry.getAttributeNS(ANDROID,"process"));
            assertEquals("specialUse",entry.getAttributeNS(ANDROID,"foregroundServiceType"));assertEquals("true",entry.getAttributeNS(ANDROID,"stopWithTask"));
            assertEquals(0,entry.getElementsByTagName("intent-filter").getLength());
            var properties=entry.getElementsByTagName("property");boolean subtype=false;
            for(int j=0;j<properties.getLength();j++){var property=(org.w3c.dom.Element)properties.item(j);
                if(property.getAttributeNS(ANDROID,"name").equals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))subtype=!property.getAttributeNS(ANDROID,"value").isBlank();}
            assertTrue("Special-use purpose must be declared",subtype);
        }
        assertEquals(1,matches);
    }
    @Test public void theFiveSecondReturnPathStillHasNoPageReadBeforeBack()throws Exception{
        assertEquals(5_000L,LiveActionPermit.RETURN_DELAY_MILLIS);assertEquals(5_000L,LiveRewardsInput.RETURN_DELAY_MS);
        var input=source("LiveRewardsInput");Set<String> forbidden=Set.of("template","visit","beforeTap","classifyVisit","readLiveRun","read","readUi","readVisit","capture","observe");
        for(String name:List.of("returnAfterTap","current","knownClean","stopped"))
            for(String call:calls(input.method(name)))assertFalse("Return path must retain its no-page-read boundary: "+call,forbidden.contains(simple(call)));
        assertTrue("Return still dispatches through the same bound Back actor",hasCall(input.method("returnAfterTap"),"backActor"));
    }
    @Test public void readOnlyEntryNeverConstructsOrFallsThroughToBusinessExecution()throws Exception{
        var runtime=source("LiveRewardsRuntime");
        for(String entry:List.of("start","startReadOnly")){
            var factory=invocations(runtime.method(entry)).stream().filter(call->simple(call.name).equals("startBound")).findFirst();
            assertTrue("Both visible entries use the same lifecycle factory",factory.isPresent());
            var arguments=factory.get().arguments;assertEquals(4,arguments.size());
            assertTrue("The entry seals its task-input purpose",arguments.get(3).size()==1&&arguments.get(3).get(0).is(entry.equals("start")?"true":"false"));
        }
        boolean branch=false;
        for(var statement:sequence(runtime.method("run"))){
            if(statement.kind.equals("if")&&joined(statement.condition).equals("!taskInputAllowed")){
                assertTrue("Read-only execution returns rather than resuming the live branch",terminates(statement.yes));
                assertTrue(hasCall(statement.yes.tokens,"runReadOnly"));branch=true;
            }
            if(calls(statement.tokens).contains("new LiveRewardsInput")||calls(statement.tokens).contains("new LiveRewardsCoordinator"))
                assertTrue("Task-capable objects remain after the read-only return branch",branch);
        }
        assertTrue("A distinct no-task-input branch is mandatory",branch);
        var readOnly=runtime.method("runReadOnly");
        assertTrue(hasCall(readOnly,"attachLedger"));assertTrue(hasCall(readOnly,"preflight"));
        for(String forbidden:List.of("new LiveRewardsInput","new LiveRewardsCoordinator","ledger.begin","authority.cardScope","input.tap","input.returnAfterTap"))
            assertFalse("Read-only path has no business operation: "+forbidden,calls(readOnly).contains(forbidden));
    }
    @Test public void focusPostObservationCannotResumeTheFailedPreflightOrReturnAnInputSource()throws Exception{
        var runtime=source("LiveRewardsRuntime");var diagnostic=runtime.method("diagnoseReadOnlyRejection");
        for(String required:List.of("hasFocusRejection","nativeResourcesClosed","claimFocusDiagnostics","diagnoseReadOnlyFocus"))assertTrue(required,hasCall(diagnostic,required));
        for(String forbidden:List.of("preflight","readUi","observe","cardScope","tap","returnAfterTap"))assertFalse(forbidden,hasCall(diagnostic,forbidden));
        boolean rethrows=false;var original=runtime.method("runReadOnly");
        for(int i=0;i+2<original.size();i++)rethrows|=original.get(i).is("throw")&&original.get(i+1).is("original")&&original.get(i+2).is(";");
        assertTrue("After-observation preserves the original terminal failure",rethrows);
        var side=runtime.method("writeFocusReceipt");assertTrue(hasCall(side,"focusDiagnostics"));
        assertFalse("Side receipt cannot acquire a new source",hasCall(side,"template"));
    }
    @Test public void foregroundCleanupDoesNotInferCancellationFromMissingLiveCoordinator()throws Exception{
        workOutcomeSurvivesForeground(source("LiveRewardsRuntime"));
    }
}
