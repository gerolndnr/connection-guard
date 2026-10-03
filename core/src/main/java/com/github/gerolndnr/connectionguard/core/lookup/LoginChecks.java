package com.github.gerolndnr.connectionguard.core.lookup;

import com.github.gerolndnr.connectionguard.core.ConnectionGuard;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.extensions.AdmissionHooks;
import com.github.gerolndnr.connectionguard.core.admission.LoginAdmission;
import com.github.gerolndnr.connectionguard.core.identity.Exemptions;
import com.github.gerolndnr.connectionguard.core.vpn.VpnResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One caller's permission + admission + detection budget. Never cancels shared detector work. */
public final class LoginChecks {
    private static final AtomicInteger active = new AtomicInteger();
    private LoginChecks() { }
    public static int active() { return active.get(); }
    public static final class Permission {
        private final Boolean known;
        private final Supplier<CompletableFuture<Boolean>> lookup;
        private Permission(Boolean known, Supplier<CompletableFuture<Boolean>> lookup) { this.known = known; this.lookup = lookup; }
        public static Permission known(boolean exempt) { return new Permission(exempt, null); }
        public static Permission lookup(Supplier<CompletableFuture<Boolean>> lookup) { return new Permission(null, Objects.requireNonNull(lookup)); }
    }
    public static final class External {
        public final List<AdmissionObservation> observations;
        private final boolean denied, unknown, closed, stopped;
        private External(AdmissionHooks.Snapshot snapshot,List<AdmissionObservation> values,boolean stopped){
            observations=snapshot.fresh(values);this.stopped=stopped;closed=snapshot.closed;
            denied=observations.stream().anyMatch(v->v.getResponse().getStatus()==AdmissionResponse.Status.DENY);
            unknown=observations.stream().anyMatch(v->v.getResponse().getStatus()==AdmissionResponse.Status.UNKNOWN);
        }
        public boolean isDenied(){return denied;}
        public boolean shouldRefuse(boolean observe){return !observe && (denied || unknown && closed || stopped);}
        public DecisionObservation.Reason refusalReason(){return denied?DecisionObservation.Reason.EXTERNAL_POLICY:DecisionObservation.Reason.EXTERNAL_UNAVAILABLE;}
    }
    public static final class Result {
        private final VpnResult vpn;
        private final GeoLookup geo;
        private final boolean vpnExempt, geoExempt, cancelled, expired;
        private final LoginAdmission admission;
        private final long duration;
        private final AdmissionHooks.Snapshot hookSnapshot;
        private final List<AdmissionObservation> hookResults;
        private final boolean stoppedForHook;
        private Result(String ip, VpnResult vpn, GeoLookup geo, boolean vpnExempt, boolean geoExempt,
                       LoginAdmission admission, FailureReason unresolved, long duration, boolean expired,
                       AdmissionHooks.Snapshot hookSnapshot,List<AdmissionObservation> hookResults,boolean stoppedForHook) {
            this.hookSnapshot=hookSnapshot;this.hookResults=hookResults;this.stoppedForHook=stoppedForHook;
            this.vpnExempt = vpnExempt; this.geoExempt = geoExempt; this.admission = admission;
            this.cancelled = unresolved == FailureReason.CANCELLED; this.expired = expired; this.duration = duration;
            this.vpn = vpn != null ? vpn : vpnExempt ? new VpnResult(ip, false) : ConnectionGuard.unknownVpn(ip, unresolved);
            this.geo = geo != null ? geo : new GeoLookup(Optional.empty(), geoExempt ? FailureReason.NONE : unresolved, false, duration);
        }
        public External external(){return new External(hookSnapshot,hookResults,stoppedForHook);}
        public VpnResult vpn() { return vpn; }
        public GeoLookup geo() { return geo; }
        public boolean vpnExempt() { return vpnExempt; }
        public boolean geoExempt() { return geoExempt; }
        public boolean isCancelled() { return cancelled; }
        public boolean isExpired() { return expired; }
        public long durationMillis() { return duration; }
        public boolean isAdmitted() { return admission == null || admission.isAllowed(); }
        public boolean shouldDenyAdmission() { return admission != null && admission.shouldDeny(); }
    }
    public static CompletableFuture<Result> check(String address, LookupSettings limits, long startedNanos, boolean observe,
                                                   Permission vpn, Permission geo) {
        return check(address,limits,startedNanos,observe,vpn,geo,null);
    }
    public static CompletableFuture<Result> check(String address, LookupSettings limits, long startedNanos, boolean observe,
                                                   Permission vpn, Permission geo, AdmissionRequest request) {
        String ip = Exemptions.normalize(address); Objects.requireNonNull(vpn); Objects.requireNonNull(geo);
        LookupRuntime runtime;AdmissionHooks.Snapshot hooks;
        synchronized (ConnectionGuard.class) { runtime = ConnectionGuard.getLookupRuntime();hooks=AdmissionHooks.snapshot(); }
        if(request!=null && !request.getIp().equals(ip))throw new IllegalArgumentException("Admission request address differs from login.");
        if (!runtime.isOpen() || !runtime.getSettings().equals(limits))
            return immediate(ip, vpn, geo, FailureReason.CANCELLED, startedNanos,hooks);
        int count;
        do {
            count = active.get();
            if (count >= limits.maxInflight) return immediate(ip, vpn, geo, FailureReason.OVERLOADED, startedNanos,hooks);
        } while (!active.compareAndSet(count, count + 1));
        return new Session(ip, limits, startedNanos, observe, runtime, vpn, geo,hooks,request).start();
    }
    private static CompletableFuture<Result> immediate(String ip, Permission vpn, Permission geo, FailureReason reason, long started,AdmissionHooks.Snapshot hooks) {
        return CompletableFuture.completedFuture(new Result(ip, null, null, Boolean.TRUE.equals(vpn.known), Boolean.TRUE.equals(geo.known),
                null, reason, elapsed(started), false,hooks,unknownHooks(hooks,reason,elapsed(started)),false));
    }
    private static List<AdmissionObservation> unknownHooks(AdmissionHooks.Snapshot hooks,FailureReason reason,long elapsed){
        AdmissionResponse.Reason external=reason==FailureReason.TIMEOUT?AdmissionResponse.Reason.TIMEOUT
                :reason==FailureReason.OVERLOADED?AdmissionResponse.Reason.OVERLOADED:AdmissionResponse.Reason.CANCELLED;
        List<AdmissionObservation> values=new ArrayList<>();for(String id:hooks.ids)values.add(new AdmissionObservation(id,AdmissionResponse.unknown(external),elapsed));return values;
    }
    private static long elapsed(long started) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
    private static final class Session {
        private final String ip;
        private final long started, due;
        private final boolean observe;
        private final LookupRuntime runtime;
        private final Permission vpnPermission, geoPermission;
        private final AdmissionHooks.Snapshot hooks;
        private final AdmissionRequest request;
        private final AdmissionObservation[] hookValues;
        private int hooksReady;
        private boolean stoppedForHook;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile ScheduledFuture<?> deadline;
        private final CompletableFuture<Result> result = new CompletableFuture<Result>() {
            @Override public boolean cancel(boolean interrupt) {
                synchronized (Session.this) {
                    if (finished || isDone()) return false;
                    finished = true;
                }
                releaseResources();
                return super.cancel(interrupt);
            }
        };
        private boolean vpnResolved, geoResolved, vpnExempt, geoExempt, vpnReady, geoReady, launched, finished;
        private VpnResult vpn;
        private GeoLookup geo;
        private LoginAdmission admission;
        private Session(String ip, LookupSettings limits, long started, boolean observe, LookupRuntime runtime, Permission vpn, Permission geo,AdmissionHooks.Snapshot hooks,AdmissionRequest request) {
            this.ip = ip; this.started = started; this.due = started + TimeUnit.MILLISECONDS.toNanos(limits.deadlineMillis);
            this.observe = observe; this.runtime = runtime; this.vpnPermission = vpn; this.geoPermission = geo;
            this.hooks=hooks;this.request=request;this.hookValues=new AdmissionObservation[hooks.ids.size()];
            vpnExempt = Boolean.TRUE.equals(vpn.known); geoExempt = Boolean.TRUE.equals(geo.known);
        }
        private CompletableFuture<Result> start() {
            try { deadline = runtime.schedule(() -> finish(FailureReason.TIMEOUT), Math.max(0, (due - System.nanoTime() + 999999) / 1000000)); }
            catch (RejectedExecutionException closed) {
                finish(FailureReason.CANCELLED); return result;
            }
            result.whenComplete((value, failure) -> releaseResources());
            if(hooks.ids.isEmpty())permissions();
            else for(int i=0;i<hooks.ids.size() && live();i++){
                final int index=i;
                hooks.invoke(i,request,runtime).whenComplete((value,error)->hookResolved(index,value,error));
            }
            return result;
        }
        private void permissions(){permission(vpnPermission,true);permission(geoPermission,false);}
        private void hookResolved(int index,AdmissionObservation value,Throwable error){
            boolean proceed=false,stop=false;
            synchronized(this){
                if(finished || result.isDone())return;
                hookValues[index]=error==null && value!=null?value:new AdmissionObservation(hooks.ids.get(index),AdmissionResponse.unknown(AdmissionResponse.Reason.INVALID_RESPONSE),elapsed(started));
                hooksReady++;
                AdmissionResponse response=hookValues[index].getResponse().fresh(System.currentTimeMillis());
                stop=!observe && (response.getStatus()==AdmissionResponse.Status.DENY || response.getStatus()==AdmissionResponse.Status.UNKNOWN && hooks.closed);
                if(stop)stoppedForHook=true;else proceed=!stoppedForHook && hooksReady==hookValues.length;
            }
            if(stop)finish(FailureReason.OVERLOADED);
            else if(proceed && live())permissions();
            else if(!live())finish(runtime.isOpen()?FailureReason.TIMEOUT:FailureReason.CANCELLED);
        }
        private void releaseResources() {
            if (released.compareAndSet(false, true)) active.decrementAndGet();
            ScheduledFuture<?> current = deadline;
            if (current != null) current.cancel(false);
        }
        private boolean live() {
            synchronized (this) { return !finished && !stoppedForHook && !result.isDone() && System.nanoTime() < due && runtime.isOpen(); }
        }
        private void permission(Permission permission, boolean vpnScope) {
            if (!live()) { finish(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED); return; }
            if (permission.known != null) { permissionResolved(vpnScope, permission.known); return; }
            runtime.submit(() -> {
                if (!live()) return CompletableFuture.completedFuture(false);
                return Objects.requireNonNull(permission.lookup.get());
            }).thenCompose(future -> future).whenComplete((value, failure) -> permissionResolved(vpnScope, failure == null && Boolean.TRUE.equals(value)));
        }
        private void permissionResolved(boolean vpnScope, boolean value) {
            boolean launch = false;
            synchronized (this) {
                if (finished || result.isDone()) return;
                if (System.nanoTime() < due && runtime.isOpen()) {
                    if (vpnScope) { vpnResolved = true; vpnExempt = value; } else { geoResolved = true; geoExempt = value; }
                    if (vpnResolved && geoResolved && !launched) { launched = true; launch = true; }
                }
            }
            if (launch) launch(); else if (!live()) finish(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED);
        }
        private void launch() {
            if (!live()) { finish(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED); return; }
            try {
                LoginAdmission captured = ConnectionGuard.admitLogin(ip, vpnExempt, geoExempt, observe);
                synchronized (this) {
                    if (finished || result.isDone()) return;
                    admission = captured; vpnReady = vpnExempt; geoReady = geoExempt;
                }
                if (!admission.isAllowed()) { finish(FailureReason.OVERLOADED); return; }
                if (vpnExempt && geoExempt) { finish(FailureReason.NONE); return; }
                if (!vpnExempt && live()) ConnectionGuard.getVpnResult(ip).whenComplete((value, failure) -> vpnResolved(value, failure));
                if (!geoExempt && live()) ConnectionGuard.getGeoLookup(ip).whenComplete((value, failure) -> geoResolved(value, failure));
                if (!live()) finish(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED);
            } catch (RuntimeException | LinkageError failure) { finish(LookupException.reason(failure)); }
        }
        private void vpnResolved(VpnResult value, Throwable failure) {
            synchronized (this) {
                if (finished || result.isDone()) return;
                if (System.nanoTime() < due && runtime.isOpen()) {
                    vpn = failure == null && value != null ? value : ConnectionGuard.unknownVpn(ip, failure == null ? FailureReason.INVALID_RESPONSE : LookupException.reason(failure));
                    vpnReady = true;
                }
            }
            ready();
        }
        private void geoResolved(GeoLookup value, Throwable failure) {
            synchronized (this) {
                if (finished || result.isDone()) return;
                if (System.nanoTime() < due && runtime.isOpen()) {
                    geo = failure == null && value != null ? value : new GeoLookup(Optional.empty(), failure == null ? FailureReason.INVALID_RESPONSE : LookupException.reason(failure), false, elapsed(started));
                    geoReady = true;
                }
            }
            ready();
        }
        private void ready() {
            boolean complete;
            synchronized (this) { complete = vpnReady && geoReady; }
            if (complete) finish(FailureReason.NONE); else if (!live()) finish(runtime.isOpen() ? FailureReason.TIMEOUT : FailureReason.CANCELLED);
        }
        private void finish(FailureReason reason) {
            boolean project;
            synchronized (this) {
                if (finished || result.isDone()) return;
                project = (reason == FailureReason.TIMEOUT || System.nanoTime() >= due) && vpn == null && launched && !vpnExempt;
            }
            // Never acquire ConnectionGuard's monitor while holding the session monitor.
            VpnResult partial = project && runtime.isOpen() ? ConnectionGuard.snapshotVpn(ip, due).orElse(null) : null;
            Result next;
            synchronized (this) {
                if (finished || result.isDone()) return;
                boolean expired = System.nanoTime() >= due;
                if (!runtime.isOpen()) reason = FailureReason.CANCELLED;
                else if (expired && reason == FailureReason.NONE) reason = FailureReason.TIMEOUT;
                if (reason == FailureReason.TIMEOUT && vpn == null) vpn = partial;
                finished = true;
                List<AdmissionObservation> nativeValues=unknownHooks(hooks,reason,elapsed(started));
                for(int i=0;i<hookValues.length;i++)if(hookValues[i]!=null)nativeValues.set(i,hookValues[i]);
                next = new Result(ip, vpn, geo, vpnExempt, geoExempt, admission, reason, elapsed(started), expired,hooks,nativeValues,stoppedForHook);
            }
            // Release the slot before arbitrary completion consumers observe publication.
            // The runtime still tracks physically executing workers/deadlines until they return.
            releaseResources();
            result.complete(next);
        }
    }
}
