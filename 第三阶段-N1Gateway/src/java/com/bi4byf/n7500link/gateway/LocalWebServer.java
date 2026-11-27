package com.bi4byf.n7500link.gateway;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Small bounded HTTP server. It has no route capable of writing to the radio. */
public final class LocalWebServer {
    public interface AssetSource {
        String load(String name) throws IOException;
    }

    public interface AuditSink {
        void record(String category, String severity, String code,
                    String sourceTag, String instanceId, String sessionHashPrefix);
    }

    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 1024;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int REQUEST_THREADS = 4;
    private static final int SSE_THREADS = 4;

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final AuthManager auth;
    private final StatusRepository repository;
    private final SseHub sse;
    private final AssetSource assets;
    private final AuditSink audit;
    private final ExecutorService requests = Executors.newFixedThreadPool(REQUEST_THREADS);
    private final ExecutorService sseWriters = Executors.newFixedThreadPool(SSE_THREADS);
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LocalWebServer(InetAddress bindAddress, int port, AuthManager auth,
                          StatusRepository repository, SseHub sse, AssetSource assets,
                          AuditSink audit) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.auth = auth;
        this.repository = repository;
        this.sse = sse;
        this.assets = assets;
        this.audit = audit;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        if (bindAddress == null || bindAddress.isAnyLocalAddress()) {
            throw new IOException("拒绝绑定通配地址");
        }
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IOException("监听端口无效");
        }
        ServerSocket opened = new ServerSocket();
        opened.setReuseAddress(true);
        opened.bind(new InetSocketAddress(bindAddress, requestedPort), 32);
        serverSocket = opened;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "n1gateway-http-accept");
        acceptThread.start();
        heartbeat.scheduleWithFixedDelay(() -> {
            if (running) sse.broadcastHeartbeat(System.currentTimeMillis());
        }, 15, 15, TimeUnit.SECONDS);
    }

    public int port() {
        ServerSocket value = serverSocket;
        return value == null ? -1 : value.getLocalPort();
    }

    public boolean isRunning() { return running; }

    public void stop() {
        ServerSocket value;
        synchronized (this) {
            if (!running && serverSocket == null) return;
            running = false;
            value = serverSocket;
            serverSocket = null;
        }
        if (value != null) {
            try { value.close(); } catch (IOException ignored) {}
        }
        sse.closeAll();
        heartbeat.shutdownNow();
        requests.shutdownNow();
        sseWriters.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    requests.execute(() -> handleConnection(socket));
                } catch (RuntimeException e) {
                    closeQuietly(socket);
                }
            } catch (SocketException e) {
                if (running) audit("server", "WARN", "ACCEPT_SOCKET_ERROR", null, null, null);
            } catch (IOException e) {
                if (running) audit("server", "WARN", "ACCEPT_FAILED", null, null, null);
            }
        }
    }

    private void handleConnection(Socket socket) {
        boolean handedOff = false;
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            Request request = readRequest(input);
            if (request == null) return;
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String sourceTag = socket.getInetAddress() == null
                    ? "unknown" : socket.getInetAddress().getHostAddress();
            handedOff = route(request, socket, input, output, sourceTag);
            if (!handedOff) output.flush();
        } catch (RequestException e) {
            try {
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                writeError(output, e.status, e.code, e.publicMessage);
                output.flush();
            } catch (IOException ignored) {}
        } catch (IOException ignored) {
        } finally {
            if (!handedOff) closeQuietly(socket);
        }
    }

    private boolean route(Request request, Socket socket, InputStream input,
                          BufferedOutputStream output, String sourceTag) throws IOException {
        String path = request.path;
        String method = request.method;

        if ("/healthz".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            response(output, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"service\":\"N1Gateway\",\"version\":"
                            + JsonUtil.quote(GatewayContract.VERSION)
                            + ",\"schemaVersion\":1}");
            return false;
        }
        if ("/login".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            serveLogin(output, sourceTag);
            return false;
        }
        if ("/assets/app.js".equals(path) || "/assets/style.css".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            if (!serveAsset(output, path.substring(8), sourceTag)) {
                response(output, 500, "text/plain; charset=utf-8", "页面资源缺失，请重新安装应用");
            }
            return false;
        }
        if ("/api/v1/session".equals(path)) {
            if ("POST".equals(method)) {
                handleLogin(output, request, sourceTag);
                return false;
            }
            if ("DELETE".equals(method)) {
                handleLogout(output, request, sourceTag);
                return false;
            }
            return methodNotAllowed(output, "POST, DELETE");
        }

        if (auth.isRateLimitedForSource(sourceTag)) {
            writeError(output, 429, "RATE_LIMITED", "鉴权失败次数过多，请稍后再试");
            audit("auth", "WARN", "RATE_LIMITED", sourceTag, repository.instanceId(), null);
            return false;
        }
        if (!authenticate(request, sourceTag)) {
            if ("/".equals(path)) {
                writeRedirect(output, "/login");
            } else {
                writeError(output, 401, "AUTH_REQUIRED", "需要有效的登录会话或访问令牌");
                audit("auth", "WARN", "AUTH_REQUIRED", sourceTag, repository.instanceId(), null);
            }
            return false;
        }

        if ("/".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            if (!serveAsset(output, "index.html", sourceTag)) {
                response(output, 500, "text/plain; charset=utf-8", "页面资源缺失，请重新安装应用");
            }
            return false;
        }
        if ("/api/v1/snapshot".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            handleSnapshot(output);
            return false;
        }
        if ("/api/v1/events".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            return startSse(socket, output, sourceTag);
        }

        writeError(output, 404, "NOT_FOUND", "请求的接口不存在");
        audit("http", "WARN", "NOT_FOUND", sourceTag, repository.instanceId(), null);
        return false;
    }

    private void handleLogin(BufferedOutputStream output, Request request, String sourceTag)
            throws IOException {
        String contentType = request.headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase(Locale.US)
                .contains("application/x-www-form-urlencoded")) {
            writeError(output, 400, "BAD_REQUEST", "登录表单格式不正确");
            return;
        }
        Map<String, String> form = parseForm(request.body);
        String token = form.get("token");
        if (token == null || token.length() < 20 || token.length() > 256) {
            writeError(output, 401, "AUTH_FAILED", "访问令牌不正确");
            audit("auth", "WARN", "AUTH_FAILED", sourceTag, repository.instanceId(), null);
            return;
        }
        AuthManager.LoginResult result = auth.login(token, sourceTag);
        if (result.rateLimited) {
            writeError(output, 429, "RATE_LIMITED", "鉴权失败次数过多，请稍后再试");
            audit("auth", "WARN", "RATE_LIMITED", sourceTag, repository.instanceId(), null);
            return;
        }
        if (!result.ok) {
            writeError(output, 401, "AUTH_FAILED", "访问令牌不正确");
            audit("auth", "WARN", "AUTH_FAILED", sourceTag, repository.instanceId(), null);
            return;
        }
        String sessionPrefix = AuthManager.sha256Hex(result.sessionCookie).substring(0, 8);
        output.write(("HTTP/1.1 303 See Other\r\n"
                + "Location: /\r\n"
                + "Set-Cookie: n75_session=" + result.sessionCookie
                + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        audit("auth", "INFO", "LOGIN_OK", sourceTag, repository.instanceId(), sessionPrefix);
    }

    private void handleLogout(BufferedOutputStream output, Request request, String sourceTag)
            throws IOException {
        if (!auth.authenticateCookie(request.headers.get("cookie"), sourceTag)) {
            writeError(output, 401, "AUTH_REQUIRED", "需要有效的登录会话");
            return;
        }
        auth.revokeCookie(request.headers.get("cookie"));
        output.write(("HTTP/1.1 204 No Content\r\n"
                + "Set-Cookie: n75_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0\r\n"
                + "Cache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        audit("auth", "INFO", "LOGOUT_OK", sourceTag, repository.instanceId(), null);
    }

    private void handleSnapshot(BufferedOutputStream output) throws IOException {
        StatusRepository.View view = repository.view(System.currentTimeMillis());
        if (!view.hasAnyData() && !GatewayContract.CONN_CONNECTED.equals(view.connectionState)) {
            writeError(output, 503, "RADIO_OFFLINE", "电台尚未连接，暂无可用快照");
            return;
        }
        response(output, 200, "application/json; charset=utf-8",
                repository.toJson(System.currentTimeMillis()));
    }

    private boolean authenticate(Request request, String sourceTag) {
        String authorization = request.headers.get("authorization");
        if (authorization != null && auth.authenticateBearer(authorization, sourceTag)) return true;
        String cookie = request.headers.get("cookie");
        return cookie != null && auth.authenticateCookie(cookie, sourceTag);
    }

    private boolean startSse(Socket socket, BufferedOutputStream output, String sourceTag)
            throws IOException {
        StatusRepository.View view = repository.view(System.currentTimeMillis());
        SseHub.Client client = sse.subscribe(view.instanceId, view.revision,
                view.updatedAtMs, view.stale);
        if (client == null) {
            writeError(output, 429, "TOO_MANY_CLIENTS", "同时查看的页面数量已达到上限");
            return false;
        }
        output.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: keep-alive\r\n"
                + "X-Accel-Buffering: no\r\n"
                + "X-Content-Type-Options: nosniff\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        try {
            sseWriters.execute(() -> streamSse(socket, output, client));
            return true;
        } catch (RuntimeException e) {
            sse.close(client);
            closeQuietly(socket);
            return false;
        }
    }

    private void streamSse(Socket socket, BufferedOutputStream output, SseHub.Client client) {
        try {
            while (running && !client.isClosed() && !socket.isClosed()) {
                String event = client.next(15000L);
                if (event == null) continue;
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (client.isClosed() && "event: close\ndata: {}\n\n".equals(event)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            sse.close(client);
            closeQuietly(socket);
        }
    }

    private void serveLogin(BufferedOutputStream output, String sourceTag) throws IOException {
        if (!serveAsset(output, "login.html", sourceTag)) {
            response(output, 500, "text/plain; charset=utf-8", "登录页资源缺失，请重新安装应用");
        }
    }

    private boolean serveAsset(BufferedOutputStream output, String name,
                               String sourceTag) throws IOException {
        if (name == null || name.contains("..") || name.startsWith("/")) {
            writeError(output, 404, "NOT_FOUND", "资源不存在");
            return true;
        }
        String content;
        try {
            content = assets.load(name);
        } catch (IOException e) {
            audit("assets", "WARN", "ASSET_LOAD_FAILED", sourceTag,
                    repository.instanceId(), null);
            return false;
        }
        if (content == null) {
            audit("assets", "WARN", "ASSET_LOAD_FAILED", sourceTag,
                    repository.instanceId(), null);
            return false;
        }
        String type;
        if (name.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (name.endsWith(".js")) type = "application/javascript; charset=utf-8";
        else if (name.endsWith(".css")) type = "text/css; charset=utf-8";
        else type = "application/octet-stream";
        response(output, 200, type, content);
        return true;
    }

    private Request readRequest(InputStream input) throws IOException, RequestException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(256);
        int state = 0;
        while (state < 4) {
            int value = input.read();
            if (value < 0) throw new RequestException(400, "BAD_REQUEST", "请求头不完整");
            headerBytes.write(value);
            if (headerBytes.size() > MAX_HEADER_BYTES) {
                throw new RequestException(413, "PAYLOAD_TOO_LARGE", "请求头过大");
            }
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
        }
        String headerText = new String(headerBytes.toByteArray(), StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) throw new RequestException(400, "BAD_REQUEST", "缺少请求行");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3 || !"HTTP/1.1".equals(requestLine[2])) {
            throw new RequestException(400, "BAD_REQUEST", "请求行格式不正确");
        }
        String method = requestLine[0].toUpperCase(Locale.US);
        if (!"GET".equals(method) && !"POST".equals(method) && !"DELETE".equals(method)) {
            throw new RequestException(405, "METHOD_NOT_ALLOWED", "请求方法不受支持");
        }
        String target = requestLine[1];
        if (!target.startsWith("/")) throw new RequestException(400, "BAD_REQUEST", "请求路径无效");
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new RequestException(400, "BAD_REQUEST", "请求头格式不正确");
            String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
            String value = lines[i].substring(colon + 1).trim();
            if (key.isEmpty() || key.length() > 64 || value.length() > 1024) {
                throw new RequestException(413, "PAYLOAD_TOO_LARGE", "请求头过大");
            }
            headers.put(key, value);
        }

        int contentLength = 0;
        String lengthValue = headers.get("content-length");
        if (lengthValue != null) {
            try { contentLength = Integer.parseInt(lengthValue); }
            catch (NumberFormatException e) {
                throw new RequestException(400, "BAD_REQUEST", "Content-Length 无效");
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                throw new RequestException(413, "PAYLOAD_TOO_LARGE", "请求体过大");
            }
        }
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int count = input.read(body, offset, contentLength - offset);
            if (count < 0) throw new RequestException(400, "BAD_REQUEST", "请求体不完整");
            offset += count;
        }
        return new Request(method, path, headers, body);
    }

    private static Map<String, String> parseForm(byte[] body) {
        Map<String, String> result = new LinkedHashMap<>();
        String value = new String(body, StandardCharsets.UTF_8);
        if (value.isEmpty()) return result;
        String[] pairs = value.split("&");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(parts[0], "UTF-8");
                String item = parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
                if (!key.isEmpty()) result.put(key, item);
            } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {}
        }
        return result;
    }

    private static void response(BufferedOutputStream output, int status, String type, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (bytes.length > 0) output.write(bytes);
    }

    private static void writeError(BufferedOutputStream output, int status, String code,
                                   String message) throws IOException {
        String body = "{\"ok\":false,\"code\":" + JsonUtil.quote(code)
                + ",\"message\":" + JsonUtil.quote(message)
                + ",\"requestId\":\"local\"}";
        response(output, status, "application/json; charset=utf-8", body);
    }

    private static boolean methodNotAllowed(BufferedOutputStream output, String allow)
            throws IOException {
        byte[] bytes = ("{\"ok\":false,\"code\":\"METHOD_NOT_ALLOWED\","
                + "\"message\":\"请求方法不受支持\",\"requestId\":\"local\"}")
                .getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 405 Method Not Allowed\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Allow: " + allow + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        return false;
    }

    private static void writeRedirect(BufferedOutputStream output, String location)
            throws IOException {
        output.write(("HTTP/1.1 303 See Other\r\n"
                + "Location: " + location + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Response";
        }
    }

    private void audit(String category, String severity, String code, String sourceTag,
                       String instanceId, String sessionHashPrefix) {
        if (audit == null) return;
        try {
            audit.record(category, severity, code, sourceTag, instanceId, sessionHashPrefix);
        } catch (RuntimeException ignored) {}
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class RequestException extends Exception {
        final int status;
        final String code;
        final String publicMessage;

        RequestException(int status, String code, String publicMessage) {
            super(publicMessage);
            this.status = status;
            this.code = code;
            this.publicMessage = publicMessage;
        }
    }
}
