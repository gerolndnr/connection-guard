// SPDX-License-Identifier: AGPL-3.0-or-later
package com.github.gerolndnr.connectionguard.addons.libertybans;

import com.github.gerolndnr.connectionguard.api.v1.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Public read-only LibertyBans 1.1.4 API, resolved through its installed plugin loader.
 * No native SDK is bundled. No private reflection, punishment writes or history queries. */
public final class LibertyBansReader implements AdmissionHook {
    public static final String ID = "libertybans";
    private final ClassLoader nativeLoader;
    private final BooleanSupplier live;
    public LibertyBansReader(ClassLoader nativeLoader, BooleanSupplier live) {
        this.nativeLoader=nativeLoader; this.live=Objects.requireNonNull(live);
    }
    private Class<?> type(String suffix) throws ClassNotFoundException {
        return Class.forName("space.arim.libertybans.api."+suffix, true, nativeLoader);
    }
    private Object call(String owner, Object target, String method, Class<?>[] params, Object... values) throws ReflectiveOperationException {
        return type(owner).getMethod(method, params).invoke(target, values);
    }
    private Object call(String owner, Object target, String method) throws ReflectiveOperationException {
        return call(owner,target,method,new Class<?>[0]);
    }
    private Object provider() throws ReflectiveOperationException {
        Class<?> entry=Class.forName("space.arim.omnibus.OmnibusProvider",true,nativeLoader);
        Object omnibus=entry.getMethod("getOmnibus").invoke(null);
        Object registry=Class.forName("space.arim.omnibus.Omnibus",true,nativeLoader).getMethod("getRegistry").invoke(omnibus);
        Object result=Class.forName("space.arim.omnibus.registry.Registry",true,nativeLoader).getMethod("getProvider",Class.class).invoke(registry,type("LibertyBans"));
        return result instanceof Optional<?> ? ((Optional<?>)result).orElse(null) : null;
    }
    private Set<?> scopes(Object api) throws ReflectiveOperationException {
        Object manager=call("LibertyBans",api,"getScopeManager");
        Object raw=call("scope.ScopeManager",manager,"scopesApplicableToCurrentServer");
        if(!(raw instanceof Set<?>) || ((Set<?>)raw).isEmpty() || ((Set<?>)raw).size()>1024) throw new IllegalArgumentException();
        Set<?> copy=new HashSet<>((Set<?>)raw);
        for(Object scope:copy) if(!type("scope.ServerScope").isInstance(scope)) throw new IllegalArgumentException();
        return Collections.unmodifiableSet(copy);
    }
    private static AdmissionResponse unknown(AdmissionResponse.Reason reason) { return AdmissionResponse.unknown(reason); }
    private static CompletableFuture<AdmissionResponse> unavailable() { return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.UNAVAILABLE)); }
    private Object applicability(Object selector,UUID uuid,Object address) throws ReflectiveOperationException {
        Object builder=call("select.PunishmentSelector",selector,"selectionByApplicabilityBuilder",new Class<?>[]{UUID.class,type("NetworkAddress")},uuid,address);
        return call("select.SelectionByApplicabilityBuilder",builder,"defaultAddressStrictness");
    }
    private CompletableFuture<AdmissionResponse> read(Object api,Object builder,Set<?> selectedScopes,UUID uuid,Object address,Object strictness) throws ReflectiveOperationException {
        Object ban=type("PunishmentType").getField("BAN").get(null);
        builder=call("select.SelectionBuilderBase",builder,"type",new Class<?>[]{type("PunishmentType")},ban);
        Object predicate=type("select.SelectionPredicate").getMethod("matchingAnyOf",Set.class).invoke(null,selectedScopes);
        builder=call("select.SelectionBuilderBase",builder,"scopes",new Class<?>[]{type("select.SelectionPredicate")},predicate);
        builder=call("select.SelectionBuilderBase",builder,"selectActiveOnly");
        builder=call("select.SelectionBuilderBase",builder,"limitToRetrieve",new Class<?>[]{int.class},1);
        Object selection=call("select.SelectionBuilderBase",builder,"build");
        Object stage=call("select.SelectionBase",selection,"getFirstSpecificPunishment");
        if(!(stage instanceof CompletionStage<?>)) return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.INVALID_RESPONSE));
        // Never cancel the native future on caller timeout: core retains the callback's pending slot.
        return ((CompletionStage<?>)stage).toCompletableFuture().handle((result,error)-> {
            try {
                if(!live.getAsBoolean() || provider()!=api) return unknown(AdmissionResponse.Reason.UNAVAILABLE);
                if(!selectedScopes.equals(scopes(api))) return unknown(AdmissionResponse.Reason.STALE_DATA);
                if(uuid!=null) {
                    Object current=applicability(call("LibertyBans",api,"getSelector"),uuid,address);
                    if(!strictness.equals(call("select.SelectionByApplicability",call("select.SelectionBuilderBase",current,"build"),"getAddressStrictness")))return unknown(AdmissionResponse.Reason.STALE_DATA);
                }
                if(error!=null || !(result instanceof Optional<?>)) return unknown(AdmissionResponse.Reason.INVALID_RESPONSE);
                Optional<?> found=(Optional<?>)result;
                if(!found.isPresent()) return AdmissionResponse.clear();
                Object punishment=found.get();
                if(!type("punish.Punishment").isInstance(punishment)
                        || !ban.equals(call("punish.PunishmentBase",punishment,"getType"))
                        || !selectedScopes.contains(call("punish.PunishmentBase",punishment,"getScope")))
                    return unknown(AdmissionResponse.Reason.INVALID_RESPONSE);
                if(Boolean.TRUE.equals(call("punish.Punishment",punishment,"isExpired"))) return unknown(AdmissionResponse.Reason.STALE_DATA);
                if(Boolean.TRUE.equals(call("punish.Punishment",punishment,"isPermanent"))) return AdmissionResponse.activeBan(0);
                Object until=call("punish.Punishment",punishment,"getEndDate");
                if(!(until instanceof Instant) || !((Instant)until).isAfter(Instant.now())) return unknown(AdmissionResponse.Reason.STALE_DATA);
                return AdmissionResponse.activeBan(((Instant)until).toEpochMilli());
            } catch(ReflectiveOperationException|RuntimeException|LinkageError|AssertionError failure) {
                return unknown(AdmissionResponse.Reason.INVALID_RESPONSE);
            }
        });
    }
    @Override public CompletableFuture<AdmissionResponse> check(AdmissionRequest request) {
        if(nativeLoader==null || !live.getAsBoolean()) return unavailable();
        if(request.getRemainingMillis()==0) return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.TIMEOUT));
        try {
            Object api=provider(); if(api==null) return unavailable();
            Set<?> selectedScopes=scopes(api);
            Object address=type("NetworkAddress").getMethod("of",InetAddress.class).invoke(null,InetAddress.getByName(request.getIp()));
            Object selector=call("LibertyBans",api,"getSelector");
            // Applicability can omit first-seen UUID/address pairs (native API contract).
            // Always check exact current victims first; never manufacture history/associations.
            Set<Object> victims=new HashSet<>();
            victims.add(type("AddressVictim").getMethod("of",type("NetworkAddress")).invoke(null,address));
            if(request.getVerifiedUuid().isPresent()) victims.add(type("PlayerVictim").getMethod("of",UUID.class).invoke(null,request.getVerifiedUuid().get()));
            Object builder=call("select.PunishmentSelector",selector,"selectionBuilder");
            Object victimPredicate=type("select.SelectionPredicate").getMethod("matchingAnyOf",Set.class).invoke(null,victims);
            builder=call("select.SelectionOrderBuilder",builder,"victims",new Class<?>[]{type("select.SelectionPredicate")},victimPredicate);
            CompletableFuture<AdmissionResponse> exact=read(api,builder,selectedScopes,null,null,null);
            return exact.thenCompose(value->{
                if(value.getStatus()!=AdmissionResponse.Status.CLEAR || !request.getVerifiedUuid().isPresent())return CompletableFuture.completedFuture(value);
                if(request.getRemainingMillis()==0)return CompletableFuture.completedFuture(unknown(AdmissionResponse.Reason.TIMEOUT));
                try {
                    UUID uuid=request.getVerifiedUuid().get();
                    Object applicable=applicability(selector,uuid,address);
                    Object strictness=call("select.SelectionByApplicability",call("select.SelectionBuilderBase",applicable,"build"),"getAddressStrictness");
                    return read(api,applicable,selectedScopes,uuid,address,strictness);
                } catch(ReflectiveOperationException|RuntimeException|LinkageError|AssertionError failure) { return unavailable(); }
            });
        } catch(ReflectiveOperationException|java.net.UnknownHostException|RuntimeException|LinkageError|AssertionError failure) {
            // Missing/incompatible native SDKs and startup failures never become CLEAR.
            return unavailable();
        }
    }
}
