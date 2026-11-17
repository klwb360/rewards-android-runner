package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** New day-level one-shot journal. It never reads, repairs or resets any historical namespace. */
final class LiveRewardsLedger {
    static final String DIRECTORY="rewards-live-cycle-v1";
    private interface Store { boolean exists(String path)throws IOException;void create(String path,String data)throws IOException; }
    private final Store store;
    private final String day,runId,dayRoot,runRoot;
    private final boolean production;
    private final Set<String> begunHere=new HashSet<>(),finished=new HashSet<>();
    private int readSlots;

    LiveRewardsLedger(Path noBackupRoot,String day,String runId)throws IOException{
        this(new FileStore(noBackupRoot),day,runId,true);
    }
    private LiveRewardsLedger(Store store,String day,String runId,boolean production)throws IOException{
        if(store==null||day==null||runId==null
                ||!runId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Exact run identity required");
        LocalDate.parse(day);this.store=store;this.day=day;this.runId=runId;this.production=production;
        dayRoot=DIRECTORY+"/"+day;runRoot=dayRoot+"/runs/"+runId;
        store.create(runRoot+"/RUN.burned","profile=LIVE_REWARDS_CYCLE_V1 day="+day+" maxTap=17 maxBack=16\n");
    }
    static LiveRewardsLedger synthetic(String day,String runId)throws IOException{
        return new LiveRewardsLedger(new MemoryStore(),day,runId,false);
    }
    /** Shares a synthetic durable store across restart tests without ever enabling production input. */
    LiveRewardsLedger syntheticNextRun(String nextRunId)throws IOException{
        if(production)throw new SecurityException("Production journal cannot create a fixture");
        return new LiveRewardsLedger(store,day,nextRunId,false);
    }
    boolean production(){return production;}
    String day(){return day;}
    String runId(){return runId;}
    String relativeRunDirectory(){return runRoot;}
    private static String taskKey(LiveRewardsTypes.Task task){
        if(task==null)throw new IllegalArgumentException("Task required");return task.key();
    }
    private String taskPath(LiveRewardsTypes.Task task){return dayRoot+"/tasks/"+taskKey(task);}
    synchronized boolean attempted(LiveRewardsTypes.Task task)throws IOException{
        String p=taskPath(task);return store.exists(p+"/PENDING.burned")
                ||store.exists(p+"/TAP.burned")||store.exists(p+"/BACK.burned");
    }
    synchronized void begin(LiveRewardsTypes.Task task)throws IOException{
        String key=taskKey(task);
        if(begunHere.contains(key)||attempted(task))throw new FileAlreadyExistsException("Task already attempted");
        // Reserve locally before I/O, so an uncertain write cannot be retried in this process.
        begunHere.add(key);
        store.create(taskPath(task)+"/PENDING.burned","kind="+task.kind()+" taskHash="+key+" state=PENDING\n");
    }
    synchronized boolean begunHere(LiveRewardsTypes.Task task){return begunHere.contains(taskKey(task));}
    synchronized void burnTap(LiveRewardsTypes.Task task)throws IOException{
        if(!begunHere(task))throw new IOException("Pending not owned by this run");
        store.create(taskPath(task)+"/TAP.burned","kind="+task.kind()+" taskHash="+task.key()+" action=TAP max=1\n");
    }
    synchronized void burnBack(LiveRewardsTypes.Task task)throws IOException{
        if(task.kind()==LiveRewardsTypes.Kind.CHECK_IN||!begunHere(task))throw new IOException("Activity return only");
        if(!store.exists(taskPath(task)+"/TAP.burned"))throw new IOException("Tap record required before return");
        store.create(taskPath(task)+"/BACK.burned","kind="+task.kind()+" taskHash="+task.key()+" action=BACK max=1\n");
    }
    synchronized void finish(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome)throws IOException{
        if(outcome==null)throw new IllegalArgumentException("Outcome required");String key=taskKey(task);
        // Repeated duplicate roster entries do not rewrite the first scalar result.
        if(!finished.add(key))return;
        store.create(runRoot+"/results/"+key+".burned","kind="+task.kind()+" taskHash="+key+" outcome="+outcome+"\n");
        if(begunHere.contains(key))store.create(taskPath(task)+"/RESULT.burned","kind="+task.kind()+" taskHash="+key+" outcome="+outcome+"\n");
    }
    synchronized void recordRead(String safeSlot)throws IOException{
        if(safeSlot==null||!safeSlot.matches("[A-Z][A-Z0-9_]{0,179}")
                ||safeSlot.startsWith("FOCUS_DIAGNOSTIC_")&&!safeSlot.matches("FOCUS_DIAGNOSTIC_[1-3]")||readSlots>=2048)
            throw new IOException("Bounded scalar read slot required");
        readSlots++;store.create(runRoot+"/reads/"+safeSlot+".burned","slot="+safeSlot+" inputAuthority=false\n");
    }
    private static final class MemoryStore implements Store {
        private final Map<String,String> entries=new HashMap<>();
        public synchronized boolean exists(String path){return entries.containsKey(path);}
        public synchronized void create(String path,String value)throws IOException{
            if(entries.putIfAbsent(path,value)!=null)throw new FileAlreadyExistsException(path);
        }
    }
    private static final class FileStore implements Store {
        private final Path root;
        private final Object rootIdentity;
        FileStore(Path root)throws IOException{
            if(root==null||!root.isAbsolute()||!root.equals(root.normalize()))throw new IOException("Exact private root required");
            this.root=root;rootIdentity=directory(root);
        }
        private static Object directory(Path p)throws IOException{
            BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new IOException("Directory identity unavailable");
            return a.fileKey();
        }
        private void rootSame()throws IOException{if(!rootIdentity.equals(directory(root)))throw new IOException("Private root changed");}
        private Path path(String relative)throws IOException{
            if(relative==null||!relative.matches("[A-Za-z0-9_./-]{1,240}"))throw new IOException("Bounded relative path required");
            Path p=Paths.get(relative);if(p.isAbsolute())throw new IOException("Relative path required");
            for(Path part:p)if(part.toString().equals(".")||part.toString().equals(".."))throw new IOException("Unsafe path");
            Path result=root.resolve(p).normalize();if(!result.startsWith(root))throw new IOException("Path escaped root");return result;
        }
        public synchronized boolean exists(String relative)throws IOException{
            rootSame();Path p=path(relative);Path current=root;
            for(Path part:root.relativize(p)){
                current=current.resolve(part);
                if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS)){rootSame();return false;}
                BasicFileAttributes a=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isSymbolicLink()||a.fileKey()==null)throw new IOException("Unknown journal path");
                if(!current.equals(p)&&!a.isDirectory())throw new IOException("Journal parent is not a directory");
                if(current.equals(p)&&!a.isRegularFile())throw new IOException("Journal record is not regular");
            }
            rootSame();return true;
        }
        public synchronized void create(String relative,String value)throws IOException{
            if(value==null||value.length()>1024||!StandardCharsets.US_ASCII.newEncoder().canEncode(value))throw new IOException("Scalar journal only");
            rootSame();Path file=path(relative),current=root;
            Map<Path,Object> identities=new java.util.LinkedHashMap<>();identities.put(root,rootIdentity);
            for(Path part:root.relativize(file.getParent())){
                Path parent=current;current=current.resolve(part);
                try{Files.createDirectory(current);force(parent);}catch(FileAlreadyExistsException existing){/* Verify below. */}
                identities.put(current,directory(current));
            }
            check(identities);
            try(FileChannel c=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer bytes=StandardCharsets.US_ASCII.encode(value);
                while(bytes.hasRemaining())if(c.write(bytes)<=0)throw new IOException("Journal write incomplete");c.force(true);
            }
            check(identities);for(Path p:identities.keySet())force(p);check(identities);rootSame();
        }
        private static void force(Path p)throws IOException{
            try(FileChannel c=FileChannel.open(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){c.force(true);}
        }
        private static void check(Map<Path,Object> expected)throws IOException{
            for(var e:expected.entrySet())if(!e.getValue().equals(directory(e.getKey())))throw new IOException("Journal directory replaced");
        }
    }
}
