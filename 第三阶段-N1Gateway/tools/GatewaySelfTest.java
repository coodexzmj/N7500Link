package com.bi4byf.n7500link.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Offline G1 contract test. It never opens Bluetooth or contacts an external host. */
public final class GatewaySelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testReadOnlyAllowlist();
        testReadOnlyFrames();
        testAuthentication();
        testStatusCacheContract();
        testLoopbackHttp();
        System.out.println("N1Gateway self-test passed: " + checks + " checks");
    }

    private static void testReadOnlyAllowlist() {
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_GET_DEV_INFO), "device info allowed");
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_REGISTER_NOTIFICATION), "notification registration allowed");
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_READ_SETTINGS), "settings read allowed");
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_READ_RF_CH), "channel read allowed");
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_GET_HT_STATUS), "status read allowed");
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_GET_VOLUME), "volume read allowed");
        check(!GaiaCodec.isAllowedReadCommand(14), "channel write rejected");
        check(!GaiaCodec.isAllowedReadCommand(16), "scan write rejected");
        check(!GaiaCodec.isAllowedReadCommand(23), "volume write rejected");
        check(!GaiaCodec.isAllowedReadCommand(35), "frequency mode write rejected");
        check(!GaiaCodec.isAllowedReadCommand(66), "programmable function rejected");
        check(!GaiaCodec.isAllowedReadCommand(GaiaCodec.CMD_EVENT_NOTIFICATION), "notification event cannot be sent");
    }

    private static void testReadOnlyFrames() {
        checkFrame(GaiaCodec.deviceInfoRequest(), 9, "device info frame");
        checkFrame(GaiaCodec.notificationRegistrationRequest(), 10, "notification registration frame");
        checkFrame(GaiaCodec.settingsRequest(), 8, "settings frame");
        checkFrame(GaiaCodec.statusRequest(), 8, "status frame");
        checkFrame(GaiaCodec.volumeRequest(), 8, "volume frame");
        checkFrame(GaiaCodec.channelRequest(7), 9, "channel frame");
        check(GaiaCodec.command(frameBody(GaiaCodec.channelRequest(7))) == GaiaCodec.CMD_READ_RF_CH, "channel request command");
        check(CommandScheduler.NOTIFICATION_REGISTRATION.expectsResponse == false,
                "notification registration is one-way");
        expectIllegalChannel(-1);
        expectIllegalChannel(256);
    }

    private static void testAuthentication() {
        MemoryTokenStore store = new MemoryTokenStore();
        AuthManager auth = new AuthManager(store, () -> 1000L);
        String token = auth.initializeToken();
        check(token != null && token.length() >= 20, "token initialized");
        check(store.hash != null && !store.hash.contains(token), "token stored only as hash");
        AuthManager.LoginResult login = auth.login(token, "test-client");
        check(login.ok && login.sessionCookie != null, "correct token creates session");
        check(auth.authenticateCookie("n75_session=" + login.sessionCookie, "test-client"), "session cookie accepted");
        check(auth.authenticateBearer("Bearer " + token, "bearer-client"), "bearer token accepted");

        AuthManager limited = new AuthManager(new MemoryTokenStore(), () -> 1000L);
        limited.initializeToken();
        for (int i = 0; i < 5; i++) {
            check(!limited.authenticateBearer("Bearer wrong-token-value", "limited"),
                    "bad bearer rejected " + i);
        }
        check(limited.isRateLimitedForSource("limited"), "source rate limited after five failures");
        check(limited.login("wrong-token-value", "limited").rateLimited, "login returns rate-limited");
    }

    private static void testStatusCacheContract() {
        StatusRepository repository = new StatusRepository(null);
        repository.markBluetoothConnecting("N7500");
        check(repository.hasAnyData() == false, "new repository has no data");
        check(repository.view(System.currentTimeMillis()).stale, "empty repository is stale");

        repository.markConnected("N7500", 2000L);
        RadioParser.Parsed status = RadioParser.parseResponse(statusResponse());
        check(status.success && status.status != null, "status response parsed");
        repository.apply(status, GatewayContract.SOURCE_POLL, 2000L);
        String fresh = repository.toJson(2000L);
        check(fresh.contains("\"stale\":false"), "fresh cached snapshot is not stale");
        check(fresh.contains("\"state\":\"CONNECTED\""), "connected state retained");

        repository.markDegraded("RADIO_READ_TIMEOUT");
        check(repository.toJson(2000L).contains("\"RADIO_READ_TIMEOUT\""),
                "read timeout code is exposed without secrets");

        repository.markReconnecting("LINK_LOST");
        String cached = repository.toJson(3000L);
        check(cached.contains("\"stale\":true"), "offline cached snapshot is stale");
        check(cached.contains("\"state\":\"RECONNECTING\""), "offline state retained");
        check(cached.contains("\"LINK_LOST\""), "offline reason retained");
    }

    private static void testLoopbackHttp() throws Exception {
        StatusRepository repository = new StatusRepository(null);
        repository.markBluetoothConnecting("N7500");
        AuthManager auth = new AuthManager(new MemoryTokenStore(), System::currentTimeMillis);
        String token = auth.initializeToken();
        SseHub sse = new SseHub();
        LocalWebServer.AssetSource assets = name -> {
            if ("index.html".equals(name)) return "<html>index</html>";
            if ("login.html".equals(name)) return "<html>login</html>";
            if ("app.js".equals(name)) return "readonly";
            if ("style.css".equals(name)) return "body{}";
            throw new IOException("missing " + name);
        };
        LocalWebServer server = new LocalWebServer(
                java.net.InetAddress.getByName("127.0.0.1"), 0, auth, repository,
                sse, assets, (category, severity, code, source, instance, session) -> {});
        try {
            server.start();
            int port = server.port();
            check(port > 0, "ephemeral loopback port allocated");
            check(request(port, "GET", "/healthz", null, null).status == 200, "healthz is public");
            check(request(port, "GET", "/login", null, null).body.contains("login"), "login page is public");
            check(request(port, "GET", "/assets/app.js", null, null).status == 200,
                    "local script asset is public");
            check(request(port, "GET", "/assets/style.css", null, null).status == 200,
                    "local style asset is public");
            HttpResult anonymous = request(port, "GET", "/api/v1/snapshot", null, null);
            check(anonymous.status == 401, "snapshot requires auth");

            HttpResult bearer = request(port, "GET", "/api/v1/snapshot",
                    "Authorization", "Bearer " + token);
            check(bearer.status == 503, "never-read snapshot returns 503 while offline");

            HttpResult login = form(port, "/api/v1/session", "token=" + token);
            check(login.status == 303, "correct login redirects with 303");
            String cookieHeader = login.headers.get("Set-Cookie");
            check(cookieHeader != null && cookieHeader.contains("HttpOnly"),
                    "session cookie is HttpOnly");
            String cookie = cookieHeader == null ? null : cookieHeader.split(";", 2)[0];
            HttpResult index = request(port, "GET", "/", "Cookie", cookie);
            check(index.status == 200 && index.body.contains("index"),
                    "authenticated index page is served");

            long now = System.currentTimeMillis();
            repository.markConnected("N7500", now);
            RadioParser.Parsed status = RadioParser.parseResponse(statusResponse());
            repository.apply(status, GatewayContract.SOURCE_POLL, now);
            HttpResult cached = request(port, "GET", "/api/v1/snapshot", "Cookie", cookie);
            check(cached.status == 200, "cached authenticated snapshot returns 200");
            check(cached.body.contains("\"schemaVersion\":1"), "snapshot schema version present");
            check(cached.body.contains("\"stale\":false"), "online snapshot freshness present");

            repository.markReconnecting("LINK_LOST");
            HttpResult stale = request(port, "GET", "/api/v1/snapshot", "Cookie", cookie);
            check(stale.status == 200, "offline snapshot with cache returns 200");
            check(stale.body.contains("\"stale\":true"), "offline snapshot marked stale");

            HttpResult logout = request(port, "DELETE", "/api/v1/session", "Cookie", cookie);
            check(logout.status == 204, "logout returns 204");
        } finally {
            server.stop();
        }
    }

    private static byte[] statusResponse() {
        return new byte[] {
            0x00, 0x02, (byte) 0x80, 0x14, 0x00,
            (byte) 0x80, 0x00, 0x00, 0x00
        };
    }

    private static void checkFrame(byte[] frame, int expectedLength, String label) {
        check(frame != null && frame.length == expectedLength, label + " length");
        check(frame != null && frame[0] == (byte) 0xFF && frame[1] == 0x01, label + " header");
        if (frame == null || frame.length < 8) throw new AssertionError("FAILED: " + label + " body");
        int bodyLength = frame.length - 4;
        byte[] body = new byte[bodyLength];
        System.arraycopy(frame, 4, body, 0, bodyLength);
        check(GaiaCodec.group(body) == GaiaCodec.GROUP_BASIC, label + " group");
        check(GaiaCodec.isAllowedReadCommand(GaiaCodec.command(body)), label + " command");
    }

    private static byte[] frameBody(byte[] frame) {
        byte[] body = new byte[frame.length - 4];
        System.arraycopy(frame, 4, body, 0, body.length);
        return body;
    }

    private static void expectIllegalChannel(int channel) {
        try {
            GaiaCodec.channelRequest(channel);
            throw new AssertionError("expected channel rejection: " + channel);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static HttpResult form(int port, String path, String value) throws IOException {
        HttpURLConnection connection = open(port, "POST", path, null, null);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    private static HttpResult request(int port, String method, String path,
                                      String headerName, String headerValue) throws IOException {
        return read(open(port, method, path, headerName, headerValue));
    }

    private static HttpURLConnection open(int port, String method, String path,
                                          String headerName, String headerValue) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        if (headerName != null) connection.setRequestProperty(headerName, headerValue);
        return connection;
    }

    private static HttpResult read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = "";
        if (input != null) {
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                body = output.toString("UTF-8");
            }
        }
        String cookie = connection.getHeaderField("Set-Cookie");
        connection.disconnect();
        return new HttpResult(status, body, cookie);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("FAILED: " + label);
        checks++;
    }

    private static final class HttpResult {
        final int status;
        final String body;
        final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        HttpResult(int status, String body, String setCookie) {
            this.status = status;
            this.body = body;
            if (setCookie != null) headers.put("Set-Cookie", setCookie);
        }
    }

    private static final class MemoryTokenStore implements AuthManager.TokenStore {
        String hash;
        long createdAt;
        @Override public String loadHash() { return hash; }
        @Override public long loadCreatedAt() { return createdAt; }
        @Override public void save(String hash, long createdAt) {
            this.hash = hash;
            this.createdAt = createdAt;
        }
    }
}
