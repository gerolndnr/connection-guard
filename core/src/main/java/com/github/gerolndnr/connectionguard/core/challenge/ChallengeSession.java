package com.github.gerolndnr.connectionguard.core.challenge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One bounded, connection-owned challenge. Passing is neither authentication nor a policy exemption. */
public final class ChallengeSession implements AutoCloseable {
    public enum State { PENDING, PASSED, REJECTED, EXPIRED, INVALIDATED, CLOSED }
    public enum Answer { ACCEPTED, INCORRECT, NOT_CURRENT, TERMINAL }
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Object owner;
    private final BooleanSupplier current;
    private final LongSupplier clock;
    private final long deadline;
    private final int maximumAttempts;
    private byte[] expected;
    private String display;
    private int attempts;
    private State state = State.PENDING;

    public ChallengeSession(Object owner, BooleanSupplier current, long timeoutMillis, int maximumAttempts) {
        this(owner, current, timeoutMillis, maximumAttempts, System::nanoTime, digits());
    }
    // Only package-local unit fixtures supply a clock/answer. Native adapters cannot mark a session passed.
    ChallengeSession(Object owner, BooleanSupplier current, long timeoutMillis, int maximumAttempts,
                     LongSupplier clock, String digits) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.current = Objects.requireNonNull(current, "current");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeoutMillis < 1 || timeoutMillis > 120000 || maximumAttempts < 1 || maximumAttempts > 8
                || !validDigits(digits)) throw new IllegalArgumentException("Invalid challenge bounds");
        this.maximumAttempts = maximumAttempts;
        this.deadline = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.display = digits;
        this.expected = hash(digits);
    }
    private static String digits() {
        StringBuilder result = new StringBuilder(6);
        for (int i = 0; i < 6; i++) result.append((char) ('0' + RANDOM.nextInt(10)));
        return result.toString();
    }
    private static boolean validDigits(String value) {
        if (value == null || value.length() != 6) return false;
        for (int i = 0; i < 6; i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("Required digest unavailable", error); }
    }
    private boolean validate(Object actor) {
        if (actor != owner) return false;
        if (state != State.PENDING && state != State.PASSED) return false;
        if (clock.getAsLong() - deadline >= 0) { finish(State.EXPIRED); return false; }
        boolean live;
        try { live = current.getAsBoolean(); }
        catch (RuntimeException | LinkageError unavailable) { live = false; }
        if (!live) { finish(State.INVALIDATED); return false; }
        return true;
    }
    /** Only the owning transport may render this text; never log it or use it as identity evidence. */
    public synchronized String displayText(Object actor) {
        return state == State.PENDING && validate(actor) ? display : null;
    }
    public synchronized Answer answer(Object actor, String response) {
        if (actor != owner) return Answer.NOT_CURRENT;
        if (state != State.PENDING) return Answer.TERMINAL;
        if (!validate(actor)) return Answer.NOT_CURRENT;
        boolean match = validDigits(response) && MessageDigest.isEqual(expected, hash(response));
        // Revalidate after comparison: a changed connection/policy cannot publish a late pass.
        if (!validate(actor)) return Answer.NOT_CURRENT;
        attempts++;
        if (match) { finish(State.PASSED); return Answer.ACCEPTED; }
        if (attempts >= maximumAttempts) finish(State.REJECTED);
        return Answer.INCORRECT;
    }
    public synchronized boolean isPassed(Object actor) { return state == State.PASSED && validate(actor); }
    public synchronized State state() { validate(owner); return state; }
    public synchronized long remainingMillis(Object actor) {
        if (!validate(actor)) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong()));
    }
    private void finish(State next) {
        state = next;
        display = null;
        if (expected != null) java.util.Arrays.fill(expected, (byte) 0);
        expected = null;
    }
    @Override public synchronized void close() { finish(State.CLOSED); }
}
