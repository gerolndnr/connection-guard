package com.github.gerolndnr.connectionguard.core.extensions;
import com.github.gerolndnr.connectionguard.api.v1.*;
import com.github.gerolndnr.connectionguard.core.lookup.LookupRuntime;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Bounded native read callbacks; one global slot limit survives timeout/unregister/replacement. */
public final class AdmissionHooks {
    private static final Map<String,Entry> entries=new HashMap<>();
    private static volatile Snapshot selected=new Snapshot(Collections.emptyList(),Collections.emptyList(),false);
    private static final AtomicInteger inflight=new AtomicInteger();
    private AdmissionHooks(){}
    public static synchronized AdmissionRegistration register(String id,AdmissionHook callback){
        if(id==null || !id.matches("[a-z][a-z0-9-]{0,31}"))throw new IllegalArgumentException("Invalid admission ID (value redacted).");
        Objects.requireNonNull(callback);
        if(entries.containsKey(id))throw new IllegalArgumentException("Admission ID already registered.");
        if(entries.size()>=8)throw new IllegalStateException("At most eight admission hooks may register.");
        Entry entry=new Entry(id,callback);entries.put(id,entry);return entry;
    }
    public static int inflight(){return inflight.get();}
    public static synchronized void validateActivation(){if(inflight()!=0)throw new IllegalStateException("Wait for native admission reads before reloading.");}
    public static synchronized void configure(AdmissionHookSettings settings){
        validateActivation();List<Entry> values=new ArrayList<>();for(String id:settings.ids)values.add(entries.get(id));
        selected=new Snapshot(settings.ids,values,settings.closed);
    }
    public static Snapshot snapshot(){return selected;}
    public static synchronized void closeAll(){
        selected=new Snapshot(Collections.emptyList(),Collections.emptyList(),false);
        for(Entry entry:new ArrayList<>(entries.values()))entry.close();
        // Never reset physical pending-read slots around uncooperative native callbacks.
    }
    public static synchronized String describe(){return "admission-hooks selected="+selected.ids.size()+" registered="+entries.size()+" pending="+inflight()+" maximum=32";}
    private static boolean reserve(){int count;do{count=inflight.get();if(count>=32)return false;}while(!inflight.compareAndSet(count,count+1));return true;}
    private static AdmissionResponse unknown(AdmissionResponse.Reason reason){return AdmissionResponse.unknown(reason);}
    private static final class Entry implements AdmissionRegistration{
        final String id;final AdmissionHook callback;volatile boolean registered=true;
        Entry(String id,AdmissionHook callback){this.id=id;this.callback=callback;}
        public String getId(){return id;}public boolean isRegistered(){return registered;}
        public void close(){synchronized(AdmissionHooks.class){registered=false;entries.remove(id,this);}}
    }
    public static final class Snapshot{
        public final List<String> ids;private final List<Entry> captured;public final boolean closed;
        private Snapshot(List<String> ids,List<Entry> captured,boolean closed){this.ids=Collections.unmodifiableList(new ArrayList<>(ids));this.captured=Collections.unmodifiableList(new ArrayList<>(captured));this.closed=closed;}
        public CompletableFuture<AdmissionObservation> invoke(int index,AdmissionRequest request,LookupRuntime runtime){
            String id=ids.get(index);Entry entry=captured.get(index);long start=System.nanoTime();
            if(request==null || entry==null || !entry.registered)return CompletableFuture.completedFuture(observe(id,unknown(AdmissionResponse.Reason.UNAVAILABLE),start));
            if(request.getRemainingMillis()==0)return CompletableFuture.completedFuture(observe(id,unknown(AdmissionResponse.Reason.TIMEOUT),start));
            if(!reserve())return CompletableFuture.completedFuture(observe(id,unknown(AdmissionResponse.Reason.OVERLOADED),start));
            CompletableFuture<AdmissionObservation> result=new CompletableFuture<>();AtomicBoolean released=new AtomicBoolean();
            runtime.submit(()->{
                if(!entry.registered)return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.UNAVAILABLE));
                if(request.getRemainingMillis()==0)return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.TIMEOUT));
                try{return Objects.requireNonNull(entry.callback.check(request));}
                catch(RuntimeException|LinkageError|AssertionError error){return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.INVALID_RESPONSE));}
            }).thenCompose(future->future).whenComplete((response,error)->{
                AdmissionResponse value=error==null && response!=null?response:unknown(AdmissionResponse.Reason.INVALID_RESPONSE);
                if(!entry.registered)value=unknown(AdmissionResponse.Reason.UNAVAILABLE);
                if(released.compareAndSet(false,true))inflight.decrementAndGet();
                result.complete(observe(id,value.fresh(System.currentTimeMillis()),start));
            });
            return result;
        }
        public List<AdmissionObservation> fresh(List<AdmissionObservation> values){
            List<AdmissionObservation> next=new ArrayList<>();long now=System.currentTimeMillis();
            for(int i=0;i<ids.size();i++){
                AdmissionObservation previous=i<values.size()?values.get(i):new AdmissionObservation(ids.get(i),unknown(AdmissionResponse.Reason.TIMEOUT),0);
                Entry entry=captured.get(i);AdmissionResponse value=selected!=this || entry==null || !entry.registered?unknown(AdmissionResponse.Reason.UNAVAILABLE):previous.getResponse().fresh(now);
                next.add(new AdmissionObservation(ids.get(i),value,previous.getDurationMillis()));
            }
            return Collections.unmodifiableList(next);
        }
        private AdmissionObservation observe(String id,AdmissionResponse value,long started){return new AdmissionObservation(id,value,Math.max(0,(System.nanoTime()-started)/1000000));}
    }
}
