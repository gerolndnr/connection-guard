package com.github.gerolndnr.connectionguard.api.v1;
import java.util.Objects;
/** Redacted source status, independent of detection/cache facts. */
public final class AdmissionObservation {
    private final String id;private final AdmissionResponse response;private final long duration;
    public AdmissionObservation(String id,AdmissionResponse response,long durationMillis){
        if(id==null || !id.matches("[a-z][a-z0-9-]{0,31}") || durationMillis<0) throw new IllegalArgumentException("Invalid bounded admission observation.");
        this.id=id;this.response=Objects.requireNonNull(response);this.duration=durationMillis;
    }
    public String getId(){return id;}public AdmissionResponse getResponse(){return response;}public long getDurationMillis(){return duration;}
}
