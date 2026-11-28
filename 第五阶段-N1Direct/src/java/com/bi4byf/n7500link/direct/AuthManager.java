package com.bi4byf.n7500link.direct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import java.util.LinkedHashMap;
import java.util.Locale;
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
    private static final char[] TOKEN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

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

    /**
     * Normalizes clipboard whitespace and letter case. The generated alphabet is
     * uppercase and contains no whitespace, so this cannot merge two valid tokens.
     */
    public static String normalizeToken(String rawToken) {
        if (rawToken == null) return null;
        StringBuilder normalized = new StringBuilder(rawToken.length());
        for (int i = 0; i < rawToken.length(); i++) {
            char value = rawToken.charAt(i);
            if (!Character.isWhitespace(value) && !Character.isSpaceChar(value)
                    && value != '\u200B' && value != '\u200C' && value != '\u200D'
                    && value != '\uFEFF' && value != '\u2060') {
                normalized.append(value);
            }
        }
        return normalized.toString().toUpperCase(Locale.US);
    }

    /** Creates the first token and returns its one-time plaintext value. */
    public String initializeToken() {
        if (hasToken()) return null;
        return rotateToken();
    }

    /** Returns whether a normalized token follows the manual-token contract. */
    public static boolean isValidAccessToken(String normalizedToken) {
        if (normalizedToken == null
                || normalizedToken.length() < GatewayContract.ACCESS_TOKEN_MIN_LENGTH
                || normalizedToken.length() > GatewayContract.ACCESS_TOKEN_MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < normalizedToken.length(); i++) {
            char value = normalizedToken.charAt(i);
            boolean asciiLetter = value >= 'A' && value <= 'Z';
            boolean asciiDigit = value >= '0' && value <= '9';
            if (!asciiLetter && !asciiDigit) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces the token with an operator-supplied value. The caller never receives
     * a copy from this method; raw values must not be written to logs.
     */
    public boolean setCustomToken(String rawToken) {
        String normalized = normalizeToken(rawToken);
        if (!isValidAccessToken(normalized)) return false;
        String hash = sha256Hex(normalized);
        synchronized (this) {
            tokenHash = hash;
            tokenCreatedAt = clock.now();
            sessions.clear();
            failures.clear();
            store.save(hash, tokenCreatedAt);
        }
        return true;
    }

    /** Clears failed-login throttles without revoking any valid browser sessions. */
    public int clearLoginRateLimit() {
        synchronized (this) {
            int cleared = failures.size();
            failures.clear();
            return cleared;
        }
    }

    /** Rotates the access token, invalidates all sessions, and returns plaintext once. */
    public String rotateToken() {
        String raw = compactToken();
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

    /** Ten unambiguous characters keep manual entry practical while retaining 50 bits. */
    private String compactToken() {
        StringBuilder value = new StringBuilder(GatewayContract.ACCESS_TOKEN_LENGTH);
        for (int i = 0; i < GatewayContract.ACCESS_TOKEN_LENGTH; i++) {
            value.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]);
        }
        return value.toString();
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

    /** Checks a locally retained token without exposing or changing the stored hash. */
    public synchronized boolean matchesToken(String rawToken) {
        return constantTimeTokenEquals(rawToken);
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
        String normalized = normalizeToken(raw);
        if (normalized == null || normalized.isEmpty() || tokenHash == null) return false;
        byte[] expected = tokenHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256Hex(normalized).getBytes(StandardCharsets.US_ASCII);
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

