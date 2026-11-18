package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small in-memory evidence types. Text is hashed before any journal or diagnostic boundary. */
final class LiveRewardsTypes {
    enum Kind { CHECK_IN, DAILY, MORE }
    enum Phase { BEFORE, AFTER_1, AFTER_2 }
    enum Outcome { COMPLETE_SKIP, REGION_COMPLETE_SKIP, UNREADABLE_SKIP, DUPLICATE_SKIP,
        ALREADY_ATTEMPTED_SKIP, OBSERVED_COMPLETE, PROGRESS_CHANGED, UNCONFIRMED, STOPPED }

    record Task(Kind kind,String descriptorHash,int ordinal,String titleHash,int points,
                boolean readable,boolean complete,int day) {
        Task {
            if(kind==null||!hashValue(descriptorHash)||titleHash==null
                    ||!titleHash.isEmpty()&&!hashValue(titleHash)||points<0||points>9999
                    ||(kind==Kind.CHECK_IN?(ordinal!=0||day< -1||day==0||day>7)
                        :(ordinal<1||ordinal>8||day!=-1)))throw new IllegalArgumentException("Bounded task evidence required");
        }
        String key(){return hash(kind.name()+"\n"+descriptorHash+(kind==Kind.CHECK_IN?"":"\n"+points));}
        boolean sameKey(Task other){return other!=null&&key().equals(other.key());}
    }

    record ObservationData(Task task,BingNavigationSnapshot.Ready source,long acquiredElapsed,
            Box screenRoi,int cropWidth,int cropHeight,Box localTarget,double minimumConfidence,
            String captureHash,boolean nativeQualified,boolean clean,boolean uniqueMatch,
            int counterCurrent,int counterTarget,LiveTaskMatcher.RegionSnapshot regionSnapshot) {
        ObservationData(Task task,BingNavigationSnapshot.Ready source,long acquiredElapsed,
                Box screenRoi,int cropWidth,int cropHeight,Box localTarget,double minimumConfidence,
                String captureHash,boolean nativeQualified,boolean clean,boolean uniqueMatch,
                int counterCurrent,int counterTarget) {
            this(task,source,acquiredElapsed,screenRoi,cropWidth,cropHeight,localTarget,minimumConfidence,
                    captureHash,nativeQualified,clean,uniqueMatch,counterCurrent,counterTarget,null);
        }
        ObservationData {
            if(task==null||source==null||acquiredElapsed<0||captureHash==null
                    ||regionSnapshot!=null&&regionSnapshot.kind()!=task.kind()
                    ||!captureHash.isEmpty()&&!hashValue(captureHash)
                    ||!Double.isFinite(minimumConfidence)||minimumConfidence< -1||minimumConfidence>1
                    ||!((counterCurrent==-1&&counterTarget==-1)
                        ||counterCurrent>=0&&counterTarget>0&&counterCurrent<=counterTarget))
                throw new IllegalArgumentException("Scalar observation required");
            if(nativeQualified&&(screenRoi==null||cropWidth<1||cropHeight<1
                    ||screenRoi.width()!=cropWidth||screenRoi.height()!=cropHeight
                    ||!hashValue(captureHash)||minimumConfidence<.85||!clean||!task.readable()))
                throw new IllegalArgumentException("Qualified native crop required");
        }
    }

    /** Only the exact CardScope sealing operation can authorize later admission. */
    static final class Observation {
        private final LiveRunAuthority.CardScope scope;
        private final Phase phase;
        private final ObservationData data;
        private final String reason;
        private Observation(LiveRunAuthority.CardScope scope,Phase phase,ObservationData data,String reason){
            this.scope=scope;this.phase=phase;this.data=data;this.reason=reason;
        }
        static Observation sealed(LiveRunAuthority.CardScope scope,Phase phase,ObservationData data,String reason){
            return new Observation(scope,phase,data,reason);
        }
        ObservationData data(){return data;}
        Task task(){return data.task();}
        BingNavigationSnapshot.Ready source(){return data.source();}
        Object owner(){return scope.owner();}
        Phase phase(){return phase;}
        String reason(){return reason;}
        boolean qualified(){return data.nativeQualified()&&data.clean()&&data.task().readable();}
        boolean fresh(long now){return scope.current(now)&&now>=data.acquiredElapsed()
                &&now-data.acquiredElapsed()<=LiveRunAuthority.MAX_FRAME_AGE;}
        LiveRunAuthority.CardScope scope(){return scope;}
    }

    record Preflight(List<Task> tasks,boolean completeScan,boolean knownClean,boolean sourceReady) {
        Preflight {
            if(tasks==null||tasks.size()>17||tasks.stream().anyMatch(t->t==null))
                throw new IllegalArgumentException("Bounded preflight required");
            tasks=List.copyOf(tasks);
            for(Kind kind:Kind.values())if(tasks.stream().filter(t->t.kind()==kind).count()>(kind==Kind.CHECK_IN?1:8))
                throw new IllegalArgumentException("Per-region preflight limit");
        }
    }

    static boolean hashValue(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    static String hash(String value){
        if(value==null||value.length()>16384)throw new IllegalArgumentException("Bounded hash input required");
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8),digest=null;
        try{
            digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder(64);
            for(byte b:digest){out.append(Character.forDigit((b>>>4)&15,16));out.append(Character.forDigit(b&15,16));}
            return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        finally{Arrays.fill(bytes,(byte)0);if(digest!=null)Arrays.fill(digest,(byte)0);}
    }
    static String identityHash(String raw){
        if(raw==null||raw.length()>4096)throw new IllegalArgumentException("Bounded identity required");
        return hash(Normalizer.normalize(raw,Normalizer.Form.NFKC).replaceAll("[\\u200B-\\u200D\\uFEFF]","")
                .replaceAll("[\\s\\p{Z}]+"," ").trim().toLowerCase(Locale.ROOT));
    }
    private LiveRewardsTypes(){}
}
