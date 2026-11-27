package com.bi4byf.n7500link.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent G1 token/session manager. It never persists raw secrets. */
public final class AuthManager {
    public interface TokenStore {
        String loadHash();
        long loadCreatedAt();
        void save(String hash, long createdAt);
    }

    public interface Clock {
        long now();
    }

    public static final class LoginResult {
        public final boolean ok;
        public final boolean rateLimited;
        public final String sessionCookie;

        private LoginResult(boolean ok, boolean rateLimited, String sessionCookie) {
            this.ok = ok;
            this.rateLimited = rateLimited;
            this.sessionCookie = sessionCookie;
        }
    }

    private static final int MAX_FAILURES = 5;
    private static final long FAILURE_WINDOW_MS = 60000L;

    private final TokenStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final byte[] rateSalt = randomBytes(16);
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, FailureWindow> failures = new LinkedHashMap<>();
    private String tokenHash;
    private long tokenCreatedAt;

    private static final class Session {
        final long expiresAt;
        long lastSeenAt;
        Session(long expiresAt) { this.expiresAt = expiresAt; }
    }

    private static final class FailureWindow {
        int count;
        long startedAt;
    }

    public AuthManager(TokenStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        this.tokenHash = store.loadHash();
        this.tokenCreatedAt = store.loadCreatedAt();
    }

    public boolean hasToken() { return tokenHash != null && !tokenHash.isEmpty(); }
    public long tokenCreatedAt() { return tokenCreatedAt; }

    /** Creates the first token and returns its one-time plaintext value. */
    public String initializeToken() {
        if (hasToken()) return null;
        return rotateToken();
    }

    /** Rotates the access token, invalidates all sessions, and returns plaintext once. */
    public String rotateToken() {
        String raw = "n75_" + base64Url(randomBytes(32));
        String hash = sha256Hex(raw);
        synchronized (this) {
            tokenHash = hash;
            tokenCreatedAt = clock.now();
            sessions.clear();
            failures.clear();
            store.save(hash, tokenCreatedAt);
        }
        return raw;
    }

    public LoginResult login(String rawToken, String sourceTag) {
        String sourceHash = sourceHash(sourceTag);
        synchronized (this) {
            purgeExpired();
            if (isRateLimited(sourceHash)) {
                return new LoginResult(false, true, null);
            }
            if (!constantTimeTokenEquals(rawToken)) {
                recordFailure(sourceHash);
                return new LoginResult(false, false, null);
            }
            clearFailures(sourceHash);
            String cookie = base64Url(randomBytes(32));
            long expires = clock.now() + GatewayContract.SESSION_MAX_AGE_MS;
            sessions.put(sha256Hex(cookie), new Session(expires));
            while (sessions.size() > GatewayContract.MAX_SESSIONS) {
                String oldest = sessions.keySet().iterator().next();
                sessions.remove(oldest);
            }
            return new LoginResult(true, false, cookie);
        }
    }

    public boolean authenticateBearer(String authorization, String sourceTag) {
        String sourceHash = sourceHash(sourceTag);
        synchronized (this) {
            if (isRateLimited(sourceHash)) return false;
            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                recordFailure(sourceHash);
                return false;
            }
            boolean ok = constantTimeTokenEquals(authorization.substring(7).trim());
            if (!ok) recordFailure(sourceHash); else clearFailures(sourceHash);
            return ok;
        }
    }

    public boolean authenticateCookie(String cookie, String sourceTag) {
        String sourceHash = sourceHash(sourceTag);
        synchronized (this) {
            purgeExpired();
            if (isRateLimited(sourceHash)) return false;
            String sessionValue = cookieValue(cookie, "n75_session");
            if (sessionValue == null) return false;
            Session session = sessions.get(sha256Hex(sessionValue));
            if (session == null || session.expiresAt <= clock.now()) {
                recordFailure(sourceHash);
                return false;
            }
            session.lastSeenAt = clock.now();
            clearFailures(sourceHash);
            return true;
        }
    }

    public boolean revokeCookie(String cookie) {
        String sessionValue = cookieValue(cookie, "n75_session");
        if (sessionValue == null) return false;
        synchronized (this) {
            return sessions.remove(sha256Hex(sessionValue)) != null;
        }
    }

    public boolean isRateLimitedForSource(String sourceTag) {
        String sourceHash = sourceHash(sourceTag);
        synchronized (this) {
            purgeFailedWindows();
            return isRateLimited(sourceHash);
        }
    }

    public static String cookieValue(String header, String name) {
        if (header == null || name == null) return null;
        String[] parts = header.split(";");
        for (String part : parts) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) return pair[1];
        }
        return null;
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean constantTimeTokenEquals(String raw) {
        if (raw == null || tokenHash == null) return false;
        byte[] expected = tokenHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256Hex(raw).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String sourceHash(String sourceTag) {
        String source = sourceTag == null ? "unknown" : sourceTag;
        return sha256Hex(new String(rateSalt, StandardCharsets.UTF_8) + ":" + source);
    }

    private boolean isRateLimited(String sourceHash) {
        purgeFailedWindows();
        FailureWindow window = failures.get(sourceHash);
        return window != null && window.count >= MAX_FAILURES;
    }

    private void recordFailure(String sourceHash) {
        long now = clock.now();
        FailureWindow window = failures.get(sourceHash);
        if (window == null || now - window.startedAt > FAILURE_WINDOW_MS) {
            window = new FailureWindow();
            window.startedAt = now;
            failures.put(sourceHash, window);
        }
        window.count++;
        if (failures.size() > 128) {
            String oldest = failures.keySet().iterator().next();
            failures.remove(oldest);
        }
    }

    private void clearFailures(String sourceHash) {
        failures.remove(sourceHash);
    }

    private void purgeFailedWindows() {
        long now = clock.now();
        java.util.Iterator<Map.Entry<String, FailureWindow>> iterator = failures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, FailureWindow> entry = iterator.next();
            if (now - entry.getValue().startedAt > FAILURE_WINDOW_MS) iterator.remove();
        }
    }

    private void purgeExpired() {
        long now = clock.now();
        java.util.Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAt <= now) iterator.remove();
        }
    }

    private byte[] randomBytes(int count) {
        byte[] value = new byte[count];
        random.nextBytes(value);
        return value;
    }

    private static String base64Url(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        for (byte b : value) {
            int n = b & 0xFF;
            out.append(alphabet.charAt((n >> 6) & 0x03));
            out.append(alphabet.charAt((n >> 4) & 0x03));
            out.append(alphabet.charAt((n >> 2) & 0x03));
            out.append(alphabet.charAt(n & 0x03));
        }
        return out.toString();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) {
            int n = b & 0xFF;
            out.append(Character.toUpperCase(Character.forDigit(n >> 4, 16)));
            out.append(Character.toUpperCase(Character.forDigit(n & 0x0F, 16)));
        }
        return out.toString();
    }
}
