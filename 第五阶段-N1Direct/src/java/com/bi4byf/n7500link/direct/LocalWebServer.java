package com.bi4byf.n7500link.direct;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Small bounded HTTP server with fixed read-only catalog and low-risk control routes. */
public final class LocalWebServer {
    public interface AssetSource {
        String load(String name) throws IOException;
    }

    public interface AuditSink {
        void record(String category, String severity, String code,
                    String sourceTag, String instanceId, String sessionHashPrefix);
    }

    public interface TokenControl {
        boolean setCustomToken(String rawToken);
        int clearLoginRateLimit();
    }

    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 1024;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int REQUEST_THREADS = 4;
    private static final int SSE_THREADS = 4;
    private static final int LIVE_AUDIO_THREADS = 4;
    private static final int AUDIO_BUFFER_BYTES = 64 * 1024;

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final AuthManager auth;
    private final StatusRepository repository;
    private final SseHub sse;
    private final Supplier<LowRiskWriteController> controlSupplier;
    private final Supplier<ChannelCatalogService> catalogSupplier;
    private final Supplier<AudioRoomService> audioSupplier;
    private final Supplier<String> audioDiagnosticsSupplier;
    private final TokenControl tokenControl;
    private final AssetSource assets;
    private final AuditSink audit;
    private final ExecutorService requests = Executors.newFixedThreadPool(REQUEST_THREADS);
    private final ExecutorService sseWriters = Executors.newFixedThreadPool(SSE_THREADS);
    private final ExecutorService liveAudioWriters =
            Executors.newFixedThreadPool(LIVE_AUDIO_THREADS);
    private final Map<Socket, AudioRoomService.LiveSubscription> liveConnections =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LocalWebServer(InetAddress bindAddress, int port, AuthManager auth,
                          StatusRepository repository, SseHub sse,
                          Supplier<LowRiskWriteController> controlSupplier,
                          Supplier<ChannelCatalogService> catalogSupplier,
                          Supplier<AudioRoomService> audioSupplier,
                          AssetSource assets, AuditSink audit) {
        this(bindAddress, port, auth, repository, sse, controlSupplier, catalogSupplier,
                audioSupplier, null, null, assets, audit);
    }

    public LocalWebServer(InetAddress bindAddress, int port, AuthManager auth,
                          StatusRepository repository, SseHub sse,
                          Supplier<LowRiskWriteController> controlSupplier,
                          Supplier<ChannelCatalogService> catalogSupplier,
                          Supplier<AudioRoomService> audioSupplier,
                          TokenControl tokenControl,
                          AssetSource assets, AuditSink audit) {
        this(bindAddress, port, auth, repository, sse, controlSupplier, catalogSupplier,
                audioSupplier, null, tokenControl, assets, audit);
    }

    public LocalWebServer(InetAddress bindAddress, int port, AuthManager auth,
                          StatusRepository repository, SseHub sse,
                          Supplier<LowRiskWriteController> controlSupplier,
                          Supplier<ChannelCatalogService> catalogSupplier,
                          Supplier<AudioRoomService> audioSupplier,
                          Supplier<String> audioDiagnosticsSupplier,
                          TokenControl tokenControl,
                          AssetSource assets, AuditSink audit) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.auth = auth;
        this.repository = repository;
        this.sse = sse;
        this.controlSupplier = controlSupplier;
        this.catalogSupplier = catalogSupplier;
        this.audioSupplier = audioSupplier;
        this.audioDiagnosticsSupplier = audioDiagnosticsSupplier;
        this.tokenControl = tokenControl;
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
        for (Socket liveSocket : liveConnections.keySet()) closeQuietly(liveSocket);
        for (AudioRoomService.LiveSubscription subscription : liveConnections.values()) {
            if (subscription != null) subscription.release();
        }
        liveConnections.clear();
        sse.closeAll();
        heartbeat.shutdownNow();
        requests.shutdownNow();
        sseWriters.shutdownNow();
        liveAudioWriters.shutdownNow();
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

        if (hasForbiddenQueryAuth(request)) {
            writeError(output, 400, "QUERY_TOKEN_FORBIDDEN",
                    "登录令牌只能通过登录页或 HttpOnly 会话 Cookie 传递");
            audit("auth", "WARN", "QUERY_TOKEN_REJECTED", sourceTag, repository.instanceId(), null);
            return false;
        }

        if ("/healthz".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            response(output, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"service\":" + JsonUtil.quote(GatewayContract.APP_NAME) + ",\"version\":"
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

        // Token changes must use an existing HttpOnly cookie session, never a bearer
        // token whose value might be cached by a proxy or command-line client.
        if ("/api/v1/security/token".equals(path)) {
            if ("GET".equals(method)) {
                handleTokenStatus(output, request, sourceTag);
                return false;
            }
            if ("POST".equals(method)) {
                handleTokenUpdate(output, request, sourceTag);
                return false;
            }
            return methodNotAllowed(output, "GET, POST");
        }
        if ("/api/v1/security/rate-limit/clear".equals(path)) {
            if (!"POST".equals(method)) return methodNotAllowed(output, "POST");
            handleRateLimitClear(output, request, sourceTag);
            return false;
        }

        String[] audioPath = audioPathParts(path);
        if (audioPath != null && audioPath.length == 2 && "live".equals(audioPath[1])) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            return handleAudioLive(socket, output, audioPath[0], request, sourceTag);
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
        if ("/api/v1/audio/diagnostics".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            handleAudioDiagnostics(output);
            return false;
        }
        if ("/api/v1/events".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            return startSse(socket, output, sourceTag);
        }

        if ("/api/v1/channels/refresh".equals(path)) {
            if (!"POST".equals(method)) return methodNotAllowed(output, "POST");
            handleChannelRefresh(output, sourceTag);
            return false;
        }
        if ("/api/v1/channels".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            handleChannelList(output);
            return false;
        }
        if (path.startsWith("/api/v1/channels/")) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            handleChannelDetail(output, path);
            return false;
        }

        if ("/api/v1/audio/rooms".equals(path)) {
            if ("GET".equals(method)) {
                handleAudioRooms(output);
                return false;
            }
            if ("POST".equals(method)) {
                handleAudioRoomCreate(output, request, sourceTag);
                return false;
            }
            return methodNotAllowed(output, "GET, POST");
        }
        if (audioPath != null) {
            if (audioPath.length == 2 && "clips".equals(audioPath[1])) {
                if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
                handleAudioClips(output, audioPath[0]);
                return false;
            }
            if (audioPath.length == 4 && "clips".equals(audioPath[1])
                    && "wav".equals(audioPath[3])) {
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    return methodNotAllowed(output, "GET, HEAD");
                }
                handleAudioClip(output, audioPath[0], audioPath[2],
                        "HEAD".equals(method), request.headers.get("range"));
                return false;
            }
        }

        if ("/api/v1/control".equals(path)) {
            if (!"GET".equals(method)) return methodNotAllowed(output, "GET");
            handleControlStatus(output);
            return false;
        }
        if ("/api/v1/control/volume".equals(path)) {
            if (!"POST".equals(method)) return methodNotAllowed(output, "POST");
            handleVolumeControl(output, request, sourceTag);
            return false;
        }
        if ("/api/v1/control/scan".equals(path)) {
            if (!"POST".equals(method)) return methodNotAllowed(output, "POST");
            handleScanControl(output, request, sourceTag);
            return false;
        }
        if ("/api/v1/control/side".equals(path)) {
            if (!"POST".equals(method)) return methodNotAllowed(output, "POST");
            handleSideControl(output, request, sourceTag);
            return false;
        }
        writeError(output, 404, "NOT_FOUND", "请求的接口不存在");
        audit("http", "WARN", "NOT_FOUND", sourceTag, repository.instanceId(), null);
        return false;
    }

    private AudioRoomService audio() {
        if (audioSupplier == null) return null;
        try {
            return audioSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String[] audioPathParts(String path) {
        String prefix = "/api/v1/audio/rooms/";
        if (path == null || !path.startsWith(prefix) || path.length() <= prefix.length()) {
            return null;
        }
        String tail = path.substring(prefix.length());
        String[] parts = tail.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) return null;
        }
        return parts;
    }

    private static boolean hasForbiddenQueryAuth(Request request) {
        if (request.query == null || request.query.isEmpty()) return false;
        for (String rawKey : request.query.keySet()) {
            String key = rawKey.toLowerCase(Locale.US)
                    .replace("_", "").replace("-", "");
            if ("token".equals(key) || "accesstoken".equals(key)
                    || "auth".equals(key) || "session".equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void handleAudioDiagnostics(BufferedOutputStream output) throws IOException {
        Supplier<String> supplier = audioDiagnosticsSupplier;
        if (supplier == null) {
            writeError(output, 503, "AUDIO_DIAGNOSTICS_UNAVAILABLE",
                    "音频诊断尚未初始化");
            return;
        }
        String json;
        try {
            json = supplier.get();
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null || json.trim().isEmpty()) {
            writeError(output, 503, "AUDIO_DIAGNOSTICS_UNAVAILABLE",
                    "音频诊断暂时不可用");
            return;
        }
        response(output, 200, "application/json; charset=utf-8", json);
    }

    private void handleAudioRooms(BufferedOutputStream output) throws IOException {
        AudioRoomService service = audio();
        if (service == null) {
            writeError(output, 503, "AUDIO_UNAVAILABLE", "本地音频房间尚未初始化");
            return;
        }
        response(output, 200, "application/json; charset=utf-8", service.roomsJson());
    }

    private void handleAudioRoomCreate(BufferedOutputStream output, Request request,
                                       String sourceTag) throws IOException {
        AudioRoomService service = audio();
        if (service == null) {
            writeError(output, 503, "AUDIO_UNAVAILABLE", "本地音频房间尚未初始化");
            return;
        }
        Map<String, Object> fields = parseJsonObject(request);
        for (String key : fields.keySet()) {
            if (!"id".equals(key) && !"name".equals(key)) {
                throw new RequestException(400, "INVALID_FIELDS", "创建房间包含不支持的字段");
            }
        }
        Object idValue = fields.get("id");
        Object nameValue = fields.get("name");
        if (!(idValue instanceof String)
                || (nameValue != null && !(nameValue instanceof String))) {
            throw new RequestException(400, "INVALID_FIELDS", "房间 id 与 name 必须为字符串");
        }
        AudioRoomService.CreateResult result = service.createRoom(
                (String) idValue, nameValue == null ? null : (String) nameValue);
        if (!result.accepted) {
            int status = "ROOM_EXISTS".equals(result.code) ? 409
                    : "ROOM_STORAGE_FAILED".equals(result.code)
                    || "ROOM_STATE_WRITE_FAILED".equals(result.code)
                    || "AUDIO_STOPPED".equals(result.code) ? 503 : 400;
            writeError(output, status, result.code, roomCreateMessage(result.code));
            return;
        }
        response(output, 201, "application/json; charset=utf-8",
                "{\"ok\":true,\"accepted\":true,\"code\":"
                        + JsonUtil.quote(result.code)
                        + ",\"id\":" + JsonUtil.quote(result.id) + "}");
        audit("audio", "INFO", "AUDIO_ROOM_CREATED", sourceTag,
                repository.instanceId(), null);
    }

    private static String roomCreateMessage(String code) {
        if ("ROOM_EXISTS".equals(code)) return "同名本地音频房间已经存在";
        if ("INVALID_ROOM_ID".equals(code)) {
            return "房间 id 仅允许 1–32 位小写字母、数字或连字符";
        }
        if ("ROOM_STORAGE_FAILED".equals(code)
                || "ROOM_STATE_WRITE_FAILED".equals(code)
                || "AUDIO_STOPPED".equals(code)) {
            return "N1 无法保存本地音频房间";
        }
        return "创建本地音频房间失败";
    }

    private void handleAudioClips(BufferedOutputStream output, String roomId) throws IOException {
        AudioRoomService service = audio();
        if (service == null) {
            writeError(output, 503, "AUDIO_UNAVAILABLE", "本地音频房间尚未初始化");
            return;
        }
        String json = service.clipsJson(roomId);
        if (json == null) {
            writeError(output, 404, "AUDIO_ROOM_NOT_FOUND", "本地音频房间不存在");
            return;
        }
        response(output, 200, "application/json; charset=utf-8", json);
    }

    private boolean handleAudioLive(Socket socket, BufferedOutputStream output,
                                    String roomId, Request request, String sourceTag)
            throws IOException {
        if (auth.isRateLimitedForSource(sourceTag)) {
            writeError(output, 429, "RATE_LIMITED", "鉴权失败次数过多，请稍后再试");
            audit("auth", "WARN", "RATE_LIMITED", sourceTag, repository.instanceId(), null);
            return false;
        }
        if (!auth.authenticateCookie(request.headers.get("cookie"), sourceTag)) {
            writeError(output, 401, "AUTH_REQUIRED", "本地音频直播只接受同一登录会话");
            audit("auth", "WARN", "AUDIO_COOKIE_REQUIRED", sourceTag,
                    repository.instanceId(), null);
            return false;
        }
        AudioRoomService service = audio();
        if (service == null) {
            writeError(output, 503, "AUDIO_UNAVAILABLE", "本地音频房间尚未初始化");
            return false;
        }
        if (!AudioRoomService.isValidRoomId(roomId) || !service.hasRoom(roomId)) {
            writeError(output, 404, "AUDIO_ROOM_NOT_FOUND", "本地音频房间不存在");
            return false;
        }
        AudioRoomService.LiveSubscription subscription;
        try {
            subscription = service.subscribeLive(roomId);
        } catch (IOException e) {
            writeError(output, 503, "AUDIO_STORAGE_UNAVAILABLE", "无法为直播保存历史音频");
            return false;
        }
        if (subscription == null) {
            writeError(output, 429, "LIVE_CLIENTS_FULL", "该房间同时收听人数已达上限");
            return false;
        }

        liveConnections.put(socket, subscription);
        try {
            socket.setSoTimeout(0);
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: audio/L16;rate=32000;channels=1\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "X-Content-Type-Options: nosniff\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            liveAudioWriters.execute(() -> streamAudioLive(socket, output, subscription));
            return true;
        } catch (RuntimeException | IOException e) {
            liveConnections.remove(socket);
            subscription.release();
            closeQuietly(socket);
            return true;
        }
    }

    private void streamAudioLive(Socket socket, BufferedOutputStream output,
                                 AudioRoomService.LiveSubscription subscription) {
        byte[] pcm = null;
        try {
            while (running && !socket.isClosed()
                    && !subscription.client().isClosed()
                    && !Thread.currentThread().isInterrupted()) {
                pcm = subscription.client().next(15000L);
                if (pcm == null || pcm.length == 0) continue;
                output.write(Integer.toHexString(pcm.length)
                        .getBytes(StandardCharsets.US_ASCII));
                output.write('\r');
                output.write('\n');
                output.write(pcm);
                output.write('\r');
                output.write('\n');
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            try {
                output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (IOException ignored) {}
            liveConnections.remove(socket, subscription);
            subscription.release();
            closeQuietly(socket);
        }
    }

    private void handleAudioClip(BufferedOutputStream output, String roomId,
                                 String clipId, boolean head, String rangeHeader)
            throws IOException {
        AudioRoomService service = audio();
        if (service == null) {
            writeError(output, 503, "AUDIO_UNAVAILABLE", "本地音频房间尚未初始化");
            return;
        }
        AudioRoomService.ClipHandle clip = service.findClip(roomId, clipId, rangeHeader);
        if (clip == null) {
            writeError(output, 404, "AUDIO_CLIP_NOT_FOUND", "历史音频片段不存在");
            return;
        }
        ByteRange range;
        if (head && !isRangeRequested(clip)) {
            range = new ByteRange(0L, clip.sizeBytes - 1L, clip.sizeBytes);
        } else {
            range = parseByteRange(clip);
        }
        if (range == null) {
            writeRangeError(output, clip.sizeBytes, head);
            return;
        }
        int status = range.partial ? 206 : 200;
        output.write(("HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: audio/wav\r\n"
                + "Accept-Ranges: bytes\r\n"
                + (range.partial ? "Content-Range: bytes " + range.start + "-"
                        + range.end + "/" + clip.sizeBytes + "\r\n" : "")
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Content-Length: " + range.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (head) return;

        RandomAccessFile file = new RandomAccessFile(clip.file, "r");
        try {
            file.seek(range.start);
            byte[] buffer = new byte[AUDIO_BUFFER_BYTES];
            long remaining = range.length;
            while (remaining > 0L) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int count = file.read(buffer, 0, wanted);
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
        } finally {
            file.close();
        }
    }

    private static boolean isRangeRequested(AudioRoomService.ClipHandle clip) {
        return clip.rangeHeader != null;
    }

    private static ByteRange parseByteRange(AudioRoomService.ClipHandle clip) {
        String header = clip.rangeHeader;
        if (header == null) {
            return new ByteRange(0L, clip.sizeBytes - 1L, clip.sizeBytes, false);
        }
        String value = header.trim();
        if (!value.toLowerCase(Locale.US).startsWith("bytes=") || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) return null;
        try {
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1));
                if (suffix <= 0L) return null;
                long start = Math.max(0L, clip.sizeBytes - suffix);
                return new ByteRange(start, clip.sizeBytes - 1L,
                        clip.sizeBytes - start, true);
            }
            long start = Long.parseLong(spec.substring(0, dash));
            if (start < 0L || start >= clip.sizeBytes) return null;
            long end = dash == spec.length() - 1
                    ? clip.sizeBytes - 1L : Long.parseLong(spec.substring(dash + 1));
            if (end < start || end >= clip.sizeBytes) return null;
            return new ByteRange(start, end, end - start + 1L, true);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeRangeError(BufferedOutputStream output, long size,
                                        boolean head) throws IOException {
        byte[] body = head ? new byte[0] : ("{\"ok\":false,\"code\":\"RANGE_NOT_SATISFIABLE\","
                + "\"message\":\"请求的音频字节范围无效\",\"requestId\":\"local\"}")
                .getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 416 " + reason(416) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Content-Range: bytes */" + size + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!head && body.length > 0) output.write(body);
    }

    private void handleControlStatus(BufferedOutputStream output) throws IOException {
        LowRiskWriteController controller = controlSupplier == null ? null : controlSupplier.get();
        if (controller == null) {
            writeError(output, 503, "RADIO_NOT_READY", "电台控制会话尚未启动");
            return;
        }
        response(output, 200, "application/json; charset=utf-8", controller.statusJson());
    }

    private void handleChannelList(BufferedOutputStream output) throws IOException {
        ChannelCatalogService catalog = catalog();
        if (catalog == null || !catalog.isRadioReady()) {
            writeError(output, 503, "RADIO_NOT_READY", "电台尚未连接，无法读取信道目录");
            return;
        }
        // The first authenticated list request starts at most one refresh for this epoch.
        catalog.autoTriggerRefresh();
        response(output, 200, "application/json; charset=utf-8", catalog.toJson());
    }

    private void handleChannelDetail(BufferedOutputStream output, String path) throws IOException {
        String value = path.substring("/api/v1/channels/".length());
        if (value.isEmpty() || !isAsciiDigits(value)) {
            writeError(output, 404, "NOT_FOUND", "请求的接口不存在");
            return;
        }
        int channelId;
        try {
            channelId = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            writeError(output, 400, "CHANNEL_OUT_OF_RANGE", "信道编号必须为 0 到 31");
            return;
        }
        if (channelId < 0 || channelId >= ChannelCatalogService.CHANNEL_COUNT) {
            writeError(output, 400, "CHANNEL_OUT_OF_RANGE", "信道编号必须为 0 到 31");
            return;
        }
        ChannelCatalogService catalog = catalog();
        if (catalog == null || !catalog.isRadioReady()) {
            writeError(output, 503, "RADIO_NOT_READY", "电台尚未连接，无法读取信道详情");
            return;
        }
        response(output, 200, "application/json; charset=utf-8", catalog.channelJson(channelId));
    }

    private void handleChannelRefresh(BufferedOutputStream output, String sourceTag)
            throws IOException {
        ChannelCatalogService catalog = catalog();
        if (catalog == null || !catalog.isRadioReady()) {
            writeError(output, 503, "RADIO_NOT_READY", "电台尚未连接，无法刷新信道目录");
            return;
        }
        if (!catalog.requestRefresh()) {
            writeError(output, 503, "CATALOG_REFRESH_FAILED", "信道目录刷新未能启动");
            return;
        }
        response(output, 202, "application/json; charset=utf-8",
                "{\"ok\":true,\"accepted\":true,\"state\":\"REFRESHING\",\"refreshEpoch\":"
                        + catalog.epoch() + "}");
        audit("catalog", "INFO", "CHANNEL_REFRESH_STARTED", sourceTag,
                repository.instanceId(), null);
    }

    private ChannelCatalogService catalog() {
        if (catalogSupplier == null) return null;
        try {
            return catalogSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isAsciiDigits(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private void handleVolumeControl(BufferedOutputStream output, Request request,
                                     String sourceTag) throws IOException {
        Map<String, Object> fields = parseControlFields(request);
        requireConfirm(fields);
        Object value = fields.get("volume");
        if (!(value instanceof Integer) || ((Integer) value).intValue() < 0
                || ((Integer) value).intValue() > 15) {
            writeError(output, 400, "VOLUME_OUT_OF_RANGE", "音量必须为 0 到 15 的整数");
            return;
        }
        LowRiskWriteController controller = controlSupplier == null ? null : controlSupplier.get();
        if (controller == null) {
            writeError(output, 503, "RADIO_NOT_READY", "电台控制会话尚未启动");
            return;
        }
        LowRiskWriteController.Submission submission =
                controller.submitVolume(((Integer) value).intValue());
        writeSubmission(output, submission, "volume", sourceTag);
    }

    private void handleScanControl(BufferedOutputStream output, Request request,
                                   String sourceTag) throws IOException {
        Map<String, Object> fields = parseControlFields(request);
        requireConfirm(fields);
        Object value = fields.get("scan");
        if (!(value instanceof Boolean)) {
            writeError(output, 400, "SCAN_OUT_OF_RANGE", "扫描状态必须为 true 或 false");
            return;
        }
        LowRiskWriteController controller = controlSupplier == null ? null : controlSupplier.get();
        if (controller == null) {
            writeError(output, 503, "RADIO_NOT_READY", "电台控制会话尚未启动");
            return;
        }
        LowRiskWriteController.Submission submission =
                controller.submitScan(((Boolean) value).booleanValue());
        writeSubmission(output, submission, "scan", sourceTag);
    }

    private void handleSideControl(BufferedOutputStream output, Request request,
                                   String sourceTag) throws IOException {
        Map<String, Object> fields = parseControlFields(request);
        requireConfirm(fields);
        Object value = fields.get("side");
        if (!(value instanceof String)
                || (!"A".equals(value) && !"B".equals(value))) {
            writeError(output, 400, "SIDE_OUT_OF_RANGE", "信道侧仅允许 A 或 B");
            return;
        }
        LowRiskWriteController controller = controlSupplier == null ? null : controlSupplier.get();
        if (controller == null) {
            writeError(output, 503, "RADIO_NOT_READY", "电台控制会话尚未启动");
            return;
        }
        LowRiskWriteController.Submission submission =
                controller.submitSide((String) value);
        writeSubmission(output, submission, "side", sourceTag);
    }

    private static void requireConfirm(Map<String, Object> fields) throws IOException {
        Object value = fields.get("confirm");
        if (!(value instanceof Boolean) || !((Boolean) value).booleanValue()) {
            throw new RequestException(400, "CONFIRMATION_REQUIRED",
                    "控制操作必须显式包含 confirm:true");
        }
    }

    private void writeSubmission(BufferedOutputStream output,
                                 LowRiskWriteController.Submission submission,
                                 String action, String sourceTag) throws IOException {
        String body = "{\"ok\":" + submission.accepted
                + ",\"accepted\":" + submission.accepted
                + ",\"code\":" + JsonUtil.quote(submission.code)
                + ",\"operationId\":" + JsonUtil.nullableString(submission.operationId)
                + ",\"state\":" + JsonUtil.quote(submission.state)
                + ",\"action\":" + JsonUtil.quote(action) + "}";
        response(output, submission.httpStatus, "application/json; charset=utf-8", body);
        if (submission.accepted) {
            audit("control", "INFO", submission.code, sourceTag,
                    repository.instanceId(), null);
        } else {
            audit("control", "WARN", submission.code, sourceTag,
                    repository.instanceId(), null);
        }
    }

    private static Map<String, Object> parseJsonObject(Request request) throws IOException {
        String contentType = request.headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase(Locale.US)
                .startsWith("application/json")) {
            throw new RequestException(415, "UNSUPPORTED_MEDIA_TYPE",
                    "请求必须使用 application/json");
        }
        if (request.body == null || request.body.length == 0) {
            throw new RequestException(400, "BAD_JSON", "缺少 JSON 请求体");
        }
        try {
            return RestrictedJson.parseObject(new String(request.body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new RequestException(400, "BAD_JSON", "JSON 请求体格式不正确");
        }
    }

    private static Map<String, Object> parseControlFields(Request request) throws IOException {
        return parseJsonObject(request);
    }

    private void handleLogin(BufferedOutputStream output, Request request, String sourceTag)
            throws IOException {
        String contentType = request.headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase(Locale.US)
                .contains("application/x-www-form-urlencoded")) {
            serveLogin(output, sourceTag, 400, "登录请求不完整",
                    "请返回 N1Direct 登录页，再次粘贴令牌后提交。");
            return;
        }
        Map<String, String> form = parseForm(request.body);
        String token = AuthManager.normalizeToken(form.get("token"));
        if (token == null
                || token.length() < GatewayContract.ACCESS_TOKEN_MIN_LENGTH
                || token.length() > GatewayContract.ACCESS_TOKEN_MAX_LENGTH) {
            serveLogin(output, sourceTag, 401, "令牌不完整",
                    "请输入 10–64 位访问令牌。令牌字母会自动转为大写，粘贴带入的空格和换行会被忽略。");
            audit("auth", "WARN", "AUTH_FAILED", sourceTag, repository.instanceId(), null);
            return;
        }
        AuthManager.LoginResult result = auth.login(token, sourceTag);
        if (result.rateLimited) {
            serveLogin(output, sourceTag, 429, "请稍后再试",
                    "同一设备连续输错 5 次后需要等待约 1 分钟。也可以回到 N1 应用直接复制当前令牌，"
                            + "无需重新旋转。");
            audit("auth", "WARN", "RATE_LIMITED", sourceTag, repository.instanceId(), null);
            return;
        }
        if (!result.ok) {
            serveLogin(output, sourceTag, 401, "令牌不正确或已失效",
                    "请确认使用的是 N1 应用“网关与令牌”页当前显示的访问令牌。令牌保存在 N1 私有存储，"
                            + "可随时再次复制；只有遗失或需要撤销所有会话时才旋转令牌。"
                            + "");
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

    private void handleTokenStatus(BufferedOutputStream output, Request request,
                                   String sourceTag) throws IOException {
        if (!authenticateCookieOnly(request, sourceTag)) {
            writeError(output, 401, "COOKIE_SESSION_REQUIRED",
                    "设置访问令牌必须使用浏览器登录会话");
            return;
        }
        if (tokenControl == null) {
            writeError(output, 503, "TOKEN_CONTROL_UNAVAILABLE", "令牌设置接口尚未就绪");
            return;
        }
        long createdAt = auth.tokenCreatedAt();
        response(output, 200, "application/json; charset=utf-8",
                "{\"ok\":true,\"configured\":" + auth.hasToken()
                        + ",\"createdAt\":" + (createdAt <= 0L ? "null"
                        : JsonUtil.quote(GatewayContract.utc(createdAt)))
                        + ",\"minLength\":" + GatewayContract.ACCESS_TOKEN_MIN_LENGTH
                        + ",\"maxLength\":" + GatewayContract.ACCESS_TOKEN_MAX_LENGTH
                        + ",\"sessionOnly\":true}");
    }

    private void handleTokenUpdate(BufferedOutputStream output, Request request,
                                   String sourceTag) throws IOException {
        if (!authenticateCookieOnly(request, sourceTag)) {
            writeError(output, 401, "COOKIE_SESSION_REQUIRED",
                    "设置访问令牌必须使用浏览器登录会话");
            audit("auth", "WARN", "TOKEN_UPDATE_REJECTED", sourceTag,
                    repository.instanceId(), null);
            return;
        }
        if (tokenControl == null) {
            writeError(output, 503, "TOKEN_CONTROL_UNAVAILABLE", "令牌设置接口尚未就绪");
            return;
        }
        Map<String, Object> fields = parseJsonObject(request);
        if (!Boolean.TRUE.equals(fields.get("confirm"))) {
            writeError(output, 400, "CONFIRMATION_REQUIRED", "设置令牌需要二次确认");
            return;
        }
        Object rawValue = fields.get("token");
        if (!(rawValue instanceof String)) {
            writeError(output, 400, "INVALID_TOKEN",
                    "令牌须为 10–64 位 ASCII 大写字母或数字，字母会自动转为大写");
            return;
        }
        String normalized = AuthManager.normalizeToken((String) rawValue);
        if (!AuthManager.isValidAccessToken(normalized)) {
            writeError(output, 400, "INVALID_TOKEN",
                    "令牌须为 10–64 位 ASCII 大写字母或数字，字母会自动转为大写");
            return;
        }
        if (!tokenControl.setCustomToken(normalized)) {
            writeError(output, 409, "TOKEN_UPDATE_FAILED", "令牌未被更新，请重试");
            return;
        }
        // The custom token invalidates every browser session. Clear the cookie
        // before redirecting so a second tab cannot silently reuse the old one.
        output.write(("HTTP/1.1 303 See Other\r\n"
                + "Location: /login?changed=1\r\n"
                + "Set-Cookie: n75_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        audit("auth", "INFO", "TOKEN_SET", sourceTag, repository.instanceId(), null);
    }

    private void handleRateLimitClear(BufferedOutputStream output, Request request,
                                      String sourceTag) throws IOException {
        if (!authenticateCookieOnly(request, sourceTag)) {
            writeError(output, 401, "COOKIE_SESSION_REQUIRED",
                    "清除登录限流必须使用浏览器登录会话");
            return;
        }
        if (tokenControl == null) {
            writeError(output, 503, "TOKEN_CONTROL_UNAVAILABLE", "令牌设置接口尚未就绪");
            return;
        }
        int cleared = tokenControl.clearLoginRateLimit();
        response(output, 200, "application/json; charset=utf-8",
                "{\"ok\":true,\"cleared\":" + Math.max(0, cleared) + "}");
        audit("auth", "INFO", "LOGIN_LIMIT_CLEARED", sourceTag,
                repository.instanceId(), null);
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

    private boolean authenticateCookieOnly(Request request, String sourceTag) {
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
        serveLogin(output, sourceTag, 200, null, null);
    }

    private void serveLogin(BufferedOutputStream output, String sourceTag,
                            int status, String title, String message) throws IOException {
        String content;
        try {
            content = assets.load("login.html");
        } catch (IOException e) {
            content = null;
        }
        if (content == null) {
            audit("assets", "WARN", "ASSET_LOAD_FAILED", sourceTag,
                    repository.instanceId(), null);
            response(output, 500, "text/plain; charset=utf-8", "登录页资源缺失，请重新安装应用");
            return;
        }
        String errorHtml = "";
        if (message != null) {
            errorHtml = "<div class=\"login-alert\" role=\"alert\"><strong>"
                    + escapeHtml(title == null ? "无法登录" : title)
                    + "</strong><br>" + escapeHtml(message) + "</div>";
        }
        content = content.replace("{{LOGIN_ERROR}}", errorHtml);
        response(output, status, "text/html; charset=utf-8", content);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
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
        if (!"GET".equals(method) && !"HEAD".equals(method)
                && !"POST".equals(method) && !"DELETE".equals(method)) {
            throw new RequestException(405, "METHOD_NOT_ALLOWED", "请求方法不受支持");
        }
        String target = requestLine[1];
        if (!target.startsWith("/")) throw new RequestException(400, "BAD_REQUEST", "请求路径无效");
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;
        Map<String, List<String>> queryFields = parseQuery(
                query >= 0 ? target.substring(query + 1) : null);

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
        return new Request(method, path, queryFields, headers, body);
    }

    private static Map<String, List<String>> parseQuery(String source) throws RequestException {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, List<String>> result = new LinkedHashMap<>();
        String[] pairs = source.split("&", -1);
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            try {
                String key = URLDecoder.decode(parts[0], "UTF-8");
                String value = parts.length == 2
                        ? URLDecoder.decode(parts[1], "UTF-8") : "";
                if (key.isEmpty()) continue;
                List<String> values = result.get(key);
                if (values == null) {
                    values = new ArrayList<>(1);
                    result.put(key, values);
                }
                values.add(value);
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                throw new RequestException(400, "BAD_QUERY", "查询参数格式不正确");
            }
        }
        return result;
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
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 416: return "Range Not Satisfiable";
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

    /** Flat object parser limited to booleans, integers and short strings. */
    private static final class RestrictedJson {
        private final String source;
        private int index;

        private RestrictedJson(String source) { this.source = source; }

        static Map<String, Object> parseObject(String source) {
            if (source == null) throw new IllegalArgumentException("missing");
            RestrictedJson parser = new RestrictedJson(source);
            Map<String, Object> result = parser.object();
            parser.whitespace();
            if (parser.index != source.length()) throw new IllegalArgumentException("trailing");
            return result;
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            expect('{');
            whitespace();
            if (consume('}')) return result;
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                whitespace();
                Object value = value();
                if (result.put(key, value) != null) throw new IllegalArgumentException("duplicate");
                whitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private Object value() {
            if (index >= source.length()) throw new IllegalArgumentException("value");
            char c = source.charAt(index);
            if (c == '"') return string();
            if (c == 't' && source.startsWith("true", index)) {
                index += 4; return Boolean.TRUE;
            }
            if (c == 'f' && source.startsWith("false", index)) {
                index += 5; return Boolean.FALSE;
            }
            if (c == '-' || (c >= '0' && c <= '9')) return integer();
            throw new IllegalArgumentException("unsupported value");
        }

        private Integer integer() {
            int start = index;
            if (consume('-') && index >= source.length()) throw new IllegalArgumentException("number");
            if (index >= source.length() || !digit(source.charAt(index))) {
                throw new IllegalArgumentException("number");
            }
            if (source.charAt(index) == '0' && index + 1 < source.length()
                    && digit(source.charAt(index + 1))) {
                throw new IllegalArgumentException("leading zero");
            }
            while (index < source.length() && digit(source.charAt(index))) index++;
            if (index < source.length()) {
                char next = source.charAt(index);
                if (next == '.' || next == 'e' || next == 'E') {
                    throw new IllegalArgumentException("non-integer");
                }
            }
            try {
                return Integer.valueOf(source.substring(start, index));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("integer");
            }
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') return value.toString();
                if (c < 0x20) throw new IllegalArgumentException("control");
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (index >= source.length()) throw new IllegalArgumentException("escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        if (index + 4 > source.length()) throw new IllegalArgumentException("unicode");
                        String hex = source.substring(index, index + 4);
                        if (!hex.matches("[0-9A-Fa-f]{4}")) {
                            throw new IllegalArgumentException("unicode");
                        }
                        value.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                        break;
                    default: throw new IllegalArgumentException("escape");
                }
            }
            throw new IllegalArgumentException("unterminated");
        }

        private void whitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw new IllegalArgumentException("expected");
        }

        private static boolean digit(char value) { return value >= '0' && value <= '9'; }
    }
    private static final class ByteRange {
        final long start;
        final long end;
        final long length;
        final boolean partial;

        ByteRange(long start, long end, long length) {
            this(start, end, length, false);
        }

        ByteRange(long start, long end, long length, boolean partial) {
            this.start = start;
            this.end = end;
            this.length = length;
            this.partial = partial;
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, List<String>> query;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, List<String>> query,
                Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class RequestException extends IOException {
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
