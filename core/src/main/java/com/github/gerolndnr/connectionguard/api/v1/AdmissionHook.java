package com.github.gerolndnr.connectionguard.api.v1;
import java.util.concurrent.CompletableFuture;
/** Trusted selected read-only pre-admission checks. CLEAR never grants a bypass. */
@FunctionalInterface public interface AdmissionHook { CompletableFuture<AdmissionResponse> check(AdmissionRequest request); }
