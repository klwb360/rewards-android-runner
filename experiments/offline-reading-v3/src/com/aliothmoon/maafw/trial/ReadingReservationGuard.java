package com.aliothmoon.maafw.trial;

import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.State;
import io.github.klwb360.rewards.core.RewardsCore.Task;
import io.github.klwb360.rewards.core.RewardsCore.Limits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Offline policy for binding one article hash to an already-persisted READING intent. It has no
 * Android storage or action implementation. A future durable journal must serialize compare/write
 * across processes and must never clear its pending binding before the core intent is resolved.
 */
public final class ReadingReservationGuard {
    public record History(String day,Set<String> hashes,String pendingState,String pendingArticle) {
        public History {
            if(day==null || !LocalDate.parse(day).toString().equals(day) || hashes==null || hashes.size()>10
                    || pendingState==null || pendingArticle==null)throw new IllegalArgumentException("invalid reading journal");
            hashes=Set.copyOf(hashes);
            for(String hash:hashes)if(!validHash(hash))throw new IllegalArgumentException("invalid article hash");
            if(pendingState.isEmpty()!=pendingArticle.isEmpty()
                    || !pendingState.isEmpty() && (!validHash(pendingState) || !validHash(pendingArticle)
                    || !hashes.contains(pendingArticle)))throw new IllegalArgumentException("invalid pending journal binding");
        }
        @Override public String toString(){return "ReadingHistory[redacted]";}
    }
    public interface HistoryStore {
        History read() throws IOException;
        /** Atomically compare, persist and synchronize across processes. */
        boolean compareAndSet(History expected,History replacement) throws IOException;
    }
    public enum Outcome { RESERVED, BLOCKED, DUPLICATE, LIMIT, PENDING, STORAGE_UNCERTAIN }
    public static final class Permit {
        private final ReadingReservationGuard owner;private final AtomicBoolean used=new AtomicBoolean();
        private Permit(ReadingReservationGuard owner){this.owner=owner;}
    }
    public record Reservation(Outcome outcome,Permit permit) {}

    private final SingleTrialCoordinator.StateStore core;
    private final HistoryStore history;
    private final SingleTrialCoordinator.StartGate ownership;
    private final Limits limits;
    private final SingleTrialExecutor.Clock clock;
    private boolean attempted;
    private State reservedState;
    private History reservedHistory;
    private long reservedElapsed;

    public ReadingReservationGuard(SingleTrialCoordinator.StateStore core,HistoryStore history,
                                   SingleTrialCoordinator.StartGate ownership,Limits limits,SingleTrialExecutor.Clock clock){
        this.core=Objects.requireNonNull(core);this.history=Objects.requireNonNull(history);
        this.ownership=Objects.requireNonNull(ownership);this.limits=Objects.requireNonNull(limits);this.clock=Objects.requireNonNull(clock);
    }

    /** A validated same-day journal must already exist; this class never silently resets one. */
    public synchronized Reservation reserve(State pending,String title,BooleanSupplier allowed){
        if(attempted)return new Reservation(Outcome.BLOCKED,null);attempted=true;
        try{
            long started=clock.elapsedMillis(),epoch=clock.epochMillis();
            if(!validPending(pending,epoch,started) || !validTitle(title) || !allowed(allowed) || !gate())return blocked();
            if(!pending.equals(core.read()))return new Reservation(Outcome.STORAGE_UNCERTAIN,null);
            History old=history.read();
            if(old==null || !pending.day().equals(old.day()))return blocked();
            if(!old.pendingState().isEmpty())return new Reservation(Outcome.PENDING,null);
            String article=RewardsCore.hashTerm(title);
            if(old.hashes().contains(article))return new Reservation(Outcome.DUPLICATE,null);
            if(old.hashes().size()>=10)return new Reservation(Outcome.LIMIT,null);
            Set<String> hashes=new HashSet<>(old.hashes());hashes.add(article);
            History next=new History(old.day(),hashes,fingerprint(pending),article);
            if(!allowed(allowed) || !gate() || !pending.equals(core.read()))return blocked();
            if(!history.compareAndSet(old,next) || !next.equals(history.read()) || !pending.equals(core.read())
                    || !allowed(allowed) || !gate())return new Reservation(Outcome.STORAGE_UNCERTAIN,null);
            long checked=clock.elapsedMillis(),checkedEpoch=clock.epochMillis();
            if(checked<started || checked-started>30000 || !validPending(pending,checkedEpoch,checked))
                return new Reservation(Outcome.STORAGE_UNCERTAIN,null);
            reservedState=pending;reservedHistory=next;reservedElapsed=started;
            return new Reservation(Outcome.RESERVED,new Permit(this));
        }catch(IOException | RuntimeException failure){return new Reservation(Outcome.STORAGE_UNCERTAIN,null);}
    }

    /** Consumed before one article click; any failed check still consumes the permit. */
    public synchronized boolean consume(Permit permit,BooleanSupplier allowed){
        if(permit==null || permit.owner!=this || !permit.used.compareAndSet(false,true))return false;
        try{
            long started=clock.elapsedMillis(),epoch=clock.epochMillis();
            if(reservedState==null || reservedHistory==null || started<reservedElapsed || started-reservedElapsed>30000
                    || !validPending(reservedState,epoch,started) || !allowed(allowed) || !gate())return false;
            if(!reservedState.equals(core.read()) || !reservedHistory.equals(history.read()) || !allowed(allowed) || !gate())return false;
            long checked=clock.elapsedMillis(),checkedEpoch=clock.epochMillis();
            return checked>=started && checked>=reservedElapsed && checked-reservedElapsed<=30000
                    && validPending(reservedState,checkedEpoch,checked);
        }
        catch(IOException | RuntimeException failure){return false;}
    }

    private boolean validPending(State state,long epoch,long elapsed){
        try{return state!=null && Task.READING.name().equals(state.pendingTask()) && state.pendingHash().isEmpty()
                && state.readingAttempts()>0 && state.readingAttempts()<=limits.maxReadingArticles()
                && elapsed>=state.runStartedAt() && elapsed-state.runStartedAt()<limits.maxRunMillis()
                && state.readingStartedAt()>0 && elapsed>=state.readingStartedAt()
                && elapsed-state.readingStartedAt()<limits.maxReadingMillis()
                && state.day().equals(Instant.ofEpochMilli(epoch).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());}
        catch(RuntimeException failure){return false;}
    }
    private static boolean validTitle(String title){return title!=null && !title.isBlank() && title.length()<=512
            && title.codePoints().noneMatch(c->Character.isISOControl(c) || c>=0xD800 && c<=0xDFFF);}
    private static boolean validHash(String value){return value!=null && value.matches("[0-9a-f]{64}");}
    private boolean gate(){try{return ownership.ready(Task.READING);}catch(IOException | RuntimeException failure){return false;}}
    private static boolean allowed(BooleanSupplier condition){try{return condition!=null && condition.getAsBoolean();}catch(RuntimeException failure){return false;}}
    private static Reservation blocked(){return new Reservation(Outcome.BLOCKED,null);}
    static String fingerprint(State state){
        List<String> hashes=new ArrayList<>(state.searchHashes());hashes.sort(String::compareTo);
        String value=String.join("|","reading-intent-v1",state.day(),Long.toString(state.runStartedAt()),
                Long.toString(state.readingStartedAt()),Integer.toString(state.readingAttempts()),Integer.toString(state.readingNoCredit()),
                Integer.toString(state.searchAttempts()),Integer.toString(state.searchNoCredit()),String.join(",",hashes),
                state.pendingTask(),Integer.toString(state.pendingBefore()),Integer.toString(state.pendingTarget()),state.pendingHash());
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray();StringBuilder result=new StringBuilder(64);
            for(byte b:hash)result.append(hex[(b>>>4)&15]).append(hex[b&15]);return result.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
