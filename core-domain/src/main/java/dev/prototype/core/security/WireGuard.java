package dev.prototype.core.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Internal request guard; caller supplies current server-observed permission and revision. */
public final class WireGuard {
    public enum Decision { ALLOWED, ADMIN_REQUIRED, STALE_REVISION }
    private final Map<UUID, Bucket> buckets = new HashMap<>();
    private static final double CAPACITY = 12;
    private static final double PER_NANOSECOND = 8.0 / 1_000_000_000.0;

    public static Decision authorize(WireRequest request, boolean currentAdmin, long currentRevision) {
        if (!request.action().adminMutation()) return Decision.ALLOWED;
        if (!currentAdmin) return Decision.ADMIN_REQUIRED;
        return request.revision() == currentRevision ? Decision.ALLOWED : Decision.STALE_REVISION;
    }

    /** Monotonic time is supplied for deterministic tests; call before parsing requests. */
    public boolean consume(UUID actor, long nowNanos) {
        Bucket bucket = buckets.computeIfAbsent(actor, ignored -> new Bucket(nowNanos));
        long elapsed = Math.max(0, nowNanos - bucket.last);
        bucket.last = nowNanos;
        bucket.tokens = Math.min(CAPACITY, bucket.tokens + elapsed * PER_NANOSECOND);
        if (bucket.tokens < 1) return false;
        bucket.tokens -= 1;
        return true;
    }

    public void disconnect(UUID actor) { buckets.remove(actor); }
    public void clear() { buckets.clear(); }

    private static final class Bucket {
        private long last;
        private double tokens = CAPACITY;
        private Bucket(long last) { this.last = last; }
    }
}
