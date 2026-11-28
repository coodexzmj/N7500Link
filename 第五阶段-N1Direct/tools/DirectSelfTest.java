package com.bi4byf.n7500link.direct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Offline acceptance for the fifth-stage direct catalog and low-risk control surface. */
public final class DirectSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        testGaiaAllowlist();
        testSettingsBlock();
        testChannelCatalog();
        testVolumeWriteback();
        testVolumeRollback();
        testSettingsScanAndSide();
        testMutexAndCancellation();
        testHttpBoundary();
        System.out.println("PASS: " + checks + " fifth-stage checks");
    }

    private static void testGaiaAllowlist() {
        checkFrame(GaiaCodec.deviceInfoRequest(), GaiaCodec.CMD_GET_DEV_INFO, "device info");
        checkFrame(GaiaCodec.settingsRequest(), GaiaCodec.CMD_READ_SETTINGS, "settings read");
        checkFrame(GaiaCodec.statusRequest(), GaiaCodec.CMD_GET_HT_STATUS, "status read");
        checkFrame(GaiaCodec.volumeRequest(), GaiaCodec.CMD_GET_VOLUME, "volume read");
        checkFrame(GaiaCodec.channelRequest(7), GaiaCodec.CMD_READ_RF_CH, "channel read");
        checkFrame(GaiaCodec.setVolumeRequest(0), GaiaCodec.CMD_SET_VOLUME, "volume write 0");
        checkFrame(GaiaCodec.setVolumeRequest(15), GaiaCodec.CMD_SET_VOLUME, "volume write 15");
        for (int i = 0; i < 16; i++) {
            check(GaiaCodec.isAllowedReadCommand(i) == expectedRead(i), "read allowlist " + i);
            check(GaiaCodec.isAllowedWriteCommand(i) == expectedWrite(i), "write allowlist " + i);
        }
        expectVolumeRejection(-1);
        expectVolumeRejection(16);
        expectSettingsRejection(new byte[9]);
        check(GaiaCodec.writeSettingsRequest(new byte[10]).length == 18,
                "complete settings frame");
        check(GaiaCodec.isResponse(new byte[] {0, 2, (byte) 0x80, 22, 0}),
                "response bit detection");
    }

    private static boolean expectedRead(int command) {
        return command == GaiaCodec.CMD_GET_DEV_INFO
                || command == GaiaCodec.CMD_REGISTER_NOTIFICATION
                || command == GaiaCodec.CMD_READ_SETTINGS
                || command == GaiaCodec.CMD_READ_RF_CH
                || command == GaiaCodec.CMD_GET_HT_STATUS
                || command == GaiaCodec.CMD_GET_VOLUME;
    }

    private static boolean expectedWrite(int command) {
        return command == GaiaCodec.CMD_WRITE_SETTINGS
                || command == GaiaCodec.CMD_SET_VOLUME;
    }

    private static void testSettingsBlock() {
        byte[] payload = settingsPayload(1, 2, "A", false, 3);
        SettingsBlock block = SettingsBlock.fromPayload(payload);
        check(block.channelA == 1 && block.channelB == 2, "settings channel decode");
        check("A".equals(block.side) && !block.scan && block.squelch == 3,
                "settings flags decode");
        check((block.withScan(true)[1] & 0x80) != 0, "scan edit sets bit only");
        check((block.withScan(true)[1] & 0x7F) == (payload[1] & 0x7F),
                "scan edit preserves other flags");
        check((block.withSide("B")[1] & 0x30) == 0x20, "side B edit");
        check(block.matchesBaseline(block.baselinePayload()), "settings baseline copy");
        try {
            block.withSide("C");
            throw new AssertionError("expected side C rejection");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void testChannelCatalog() throws Exception {
        CatalogHarness complete = new CatalogHarness(250L);
        try {
            check("EMPTY".equals(complete.catalog.state()), "catalog starts empty");
            check(complete.catalog.autoTriggerRefresh(), "catalog auto refresh starts");
            check(complete.catalog.isRefreshing(), "catalog exposes refreshing state");
            awaitCatalogState(complete.catalog, "COMPLETE", 5000L);
            check(complete.catalog.generation() == 1, "catalog single generation");
            check(complete.catalog.toJson().contains("\"successCount\":32"),
                    "catalog reads all 32 channels");
            check(complete.transport.requestedChannels.size() == 32,
                    "catalog sends 32 channel reads");
            check(complete.transport.requestedChannels.get(0) == 0
                    && complete.transport.requestedChannels.get(31) == 31,
                    "catalog fixed request order");

            String channel1 = complete.catalog.channelJson(1);
            check(channel1.contains("\"rxMHz\":145.012500"),
                    "catalog RX MHz conversion");
            check(channel1.contains("\"txMHz\":146.012500"),
                    "catalog TX MHz conversion");
            check(channel1.contains("\"name\":\"\u6D4B\u8BD5 01\""),
                    "catalog GBK channel name");
            check(channel1.contains("\"status\":\"OK\""), "catalog channel success");

            String emptyChannel = complete.catalog.channelJson(31);
            check(emptyChannel.contains("\"rxMHz\":null")
                    && emptyChannel.contains("\"txMHz\":null")
                    && emptyChannel.contains("\"name\":null")
                    && emptyChannel.contains("\"status\":\"OK\""),
                    "catalog 0/0 empty channel remains successful");
            check(!complete.catalog.autoTriggerRefresh(),
                    "catalog auto refresh once per epoch");

            complete.transport.timeoutChannels.add(1);
            check(complete.catalog.requestRefresh(), "catalog second refresh starts");
            awaitCatalogState(complete.catalog, "PARTIAL", 5000L);
            String stale = complete.catalog.channelJson(1);
            check(stale.contains("\"status\":\"TIMEOUT\""), "catalog timeout status");
            check(stale.contains("\"stale\":true"), "catalog timeout marks stale");
            check(stale.contains("\"rxMHz\":145.012500"),
                    "catalog timeout preserves last frequency");
            check(stale.contains("CHANNEL_RESPONSE_TIMEOUT"), "catalog timeout code");
            check(complete.catalog.toJson().contains("\"lastErrorCode\":\"CHANNEL_RESPONSE_TIMEOUT\""),
                    "catalog summary exposes timeout code");

            complete.changeEpoch();
            check("EMPTY".equals(complete.catalog.state()), "catalog epoch clears state");
            String cleared = complete.catalog.channelJson(1);
            check(cleared.contains("\"status\":\"PENDING\"")
                    && cleared.contains("\"rxMHz\":null")
                    && cleared.contains("\"name\":null"),
                    "catalog epoch clears cached values");
        } finally {
            complete.close();
        }

        CatalogHarness partial = new CatalogHarness(200L);
        try {
            partial.transport.timeoutChannels.add(5);
            check(partial.catalog.requestRefresh(), "catalog partial refresh starts");
            awaitCatalogState(partial.catalog, "PARTIAL", 5000L);
            String timeout = partial.catalog.channelJson(5);
            check(timeout.contains("\"status\":\"TIMEOUT\""), "catalog isolated channel timeout");
            check(timeout.contains("CHANNEL_RESPONSE_TIMEOUT"), "catalog isolated timeout code");
        } finally {
            partial.close();
        }

        CatalogHarness idempotent = new CatalogHarness(200L);
        try {
            idempotent.port.controlBusy = true;
            check(idempotent.catalog.requestRefresh(), "catalog idempotent first refresh");
            int generation = idempotent.catalog.generation();
            check(idempotent.catalog.requestRefresh(), "catalog repeated refresh accepted");
            check(idempotent.catalog.generation() == generation,
                    "catalog repeated refresh does not advance generation");
            check(idempotent.catalog.isRefreshing(), "catalog stays refreshing while blocked");
        } finally {
            idempotent.close();
        }
    }

    private static void testVolumeWriteback() throws Exception {
        Harness harness = new Harness(5, settingsPayload(1, 2, "A", false, 3));
        try {
            LowRiskWriteController.Submission submission = harness.controller.submitVolume(6);
            check(submission.accepted && submission.httpStatus == 202, "volume accepted");
            awaitState(harness.controller, LowRiskWriteController.READBACK_OK, 4000);
            check(harness.transport.volume == 6, "volume applied");
            check(harness.transport.writeCount == 1, "one volume write");
            check("音量 6".equals(harness.controller.view().observedDisplay),
                    "volume observed display");

            harness.transport.resetCounters();
            LowRiskWriteController.Submission noChange = harness.controller.submitVolume(6);
            check(noChange.accepted, "no-change accepted");
            awaitState(harness.controller, LowRiskWriteController.READBACK_OK, 3000);
            check(harness.transport.writeCount == 0, "no-change sends no write");
            check("NO_CHANGE".equals(harness.controller.view().reason), "no-change reason");
        } finally {
            harness.close();
        }
    }

    private static void testVolumeRollback() throws Exception {
        Harness harness = new Harness(4, settingsPayload(1, 2, "A", false, 2));
        try {
            harness.transport.ignoreVolumeWrite = true;
            LowRiskWriteController.Submission submission = harness.controller.submitVolume(5);
            check(submission.accepted, "rollback test accepted");
            awaitState(harness.controller, LowRiskWriteController.ROLLBACK_OK, 5000);
            check(harness.transport.writeCount == 2, "one target write and one rollback");
            check(harness.transport.lastVolumeWrite == 4, "rollback restores baseline");
            check("BASELINE_RESTORED".equals(harness.controller.view().reason),
                    "rollback reason");
        } finally {
            harness.close();
        }
    }

    private static void testSettingsScanAndSide() throws Exception {
        Harness harness = new Harness(8, settingsPayload(1, 2, "A", false, 2));
        try {
            LowRiskWriteController.Submission scan = harness.controller.submitScan(true);
            check(scan.accepted, "scan accepted");
            awaitState(harness.controller, LowRiskWriteController.READBACK_OK, 4000);
            SettingsBlock updated = SettingsBlock.fromPayload(harness.transport.settings);
            check(updated.scan, "scan applied");
            check(updated.side.equals("A"), "scan preserves side");

            LowRiskWriteController.Submission side = harness.controller.submitSide("B");
            check(side.accepted, "side accepted");
            awaitState(harness.controller, LowRiskWriteController.READBACK_OK, 4000);
            updated = SettingsBlock.fromPayload(harness.transport.settings);
            check("B".equals(updated.side), "side applied");
            check(updated.scan, "side preserves scan");
        } finally {
            harness.close();
        }
    }

    private static void testMutexAndCancellation() throws Exception {
        Harness harness = new Harness(7, settingsPayload(1, 2, "A", false, 0));
        try {
            harness.transport.respondReads = false;
            LowRiskWriteController.Submission first = harness.controller.submitVolume(6);
            check(first.accepted, "first operation accepted");
            awaitState(harness.controller, LowRiskWriteController.BASELINE_READ, 1000);
            LowRiskWriteController.Submission second = harness.controller.submitScan(true);
            check(!second.accepted && second.httpStatus == 409
                    && "CONTROL_BUSY".equals(second.code), "mutex rejects second write");
            check(harness.transport.writeCount == 0, "busy request sent no write");
            harness.controller.cancel(LowRiskWriteController.CANCELLED, "USER_TEST_CANCEL");
            check(LowRiskWriteController.CANCELLED.equals(harness.controller.view().state),
                    "cancel terminal state");
        } finally {
            harness.close();
        }
    }

    private static void testHttpBoundary() throws Exception {
        Harness harness = new Harness(9, settingsPayload(1, 2, "A", false, 1));
        CatalogHarness catalogHarness = new CatalogHarness(250L);
        LocalWebServer server = null;
        try {
            MemoryTokenStore store = new MemoryTokenStore();
            AuthManager auth = new AuthManager(store, new AuthManager.Clock() {
                @Override public long now() { return System.currentTimeMillis(); }
            });
            String token = auth.initializeToken();
            check(token != null && token.length() == 10, "compact token length");
            String normalizedToken = AuthManager.normalizeToken(
                    " \r\n" + token.toLowerCase(Locale.US) + "\t ");
            check(token.equals(normalizedToken), "login token ignores clipboard whitespace and case");
            StatusRepository repository = new StatusRepository(null);
            SseHub sse = new SseHub();
            server = new LocalWebServer(InetAddress.getByName("127.0.0.1"), 0, auth,
                    repository, sse, () -> harness.controller, () -> catalogHarness.catalog,
                    () -> null,
                    () -> "{\"ok\":true,\"state\":\"WAITING_PCM\",\"lastError\":null,"
                            + "\"lastPcmAt\":null,\"counters\":{\"readBytes\":123}}",
                    null, name -> asset(name),
                    (category, severity, code, source, instance, session) -> { });
            server.start();

            HttpResult health = request(server.port(), "GET", "/healthz", null, null);
            check(health.status == 200 && health.body.contains(GatewayContract.VERSION), "health endpoint");
            HttpResult unauth = request(server.port(), "GET", "/api/v1/control", null, null);
            check(unauth.status == 401, "control requires authentication");
            HttpResult unauthCatalog = request(server.port(), "GET", "/api/v1/channels", null, null);
            check(unauthCatalog.status == 401, "channel catalog requires authentication");
            HttpResult unauthDiagnostics = request(server.port(), "GET",
                    "/api/v1/audio/diagnostics", null, null);
            check(unauthDiagnostics.status == 401,
                    "audio diagnostics requires authentication");

            HttpResult loginPage = request(server.port(), "GET", "/login", null, null);
            check(loginPage.status == 200 && loginPage.contentType.startsWith("text/html"),
                    "login page is served");
            HttpResult shortToken = form(server.port(), "/api/v1/session", "token=ABC");
            check(shortToken.status == 401 && shortToken.contentType.startsWith("text/html"),
                    "login failure stays on friendly HTML page");

            HttpResult login = form(server.port(), "/api/v1/session",
                    "token=" + URLEncoder.encode(
                            " \r\n" + token.toLowerCase(Locale.US) + "\t ", "UTF-8"));
            check(login.status == 303, "login succeeds with clipboard whitespace and lower case");
            String cookie = cookiePair(login.setCookie);
            check(cookie != null && cookie.startsWith("n75_session="), "session cookie");

            HttpResult control = request(server.port(), "GET", "/api/v1/control", "Cookie", cookie);
            check(control.status == 200 && control.body.contains("\"available\":true"),
                    "authenticated control status");
            HttpResult diagnostics = request(server.port(), "GET",
                    "/api/v1/audio/diagnostics", "Cookie", cookie);
            check(diagnostics.status == 200
                    && diagnostics.contentType.startsWith("application/json")
                    && diagnostics.body.contains("\"state\":\"WAITING_PCM\"")
                    && diagnostics.body.contains("\"readBytes\":123"),
                    "authenticated audio diagnostics");
            String diagnosticLower = diagnostics.body.toLowerCase(Locale.US);
            check(!diagnosticLower.contains("token")
                    && !diagnosticLower.contains("mac")
                    && !diagnosticLower.contains("wavpath")
                    && !diagnosticLower.contains("rawframe"),
                    "audio diagnostics exposes no secrets or raw stream");

            HttpResult catalog = request(server.port(), "GET", "/api/v1/channels",
                    "Cookie", cookie);
            check(catalog.status == 200 && catalog.body.contains("\"total\":32"),
                    "channel list returns fixed 32 entries");
            awaitCatalogState(catalogHarness.catalog, "COMPLETE", 5000L);
            HttpResult completeCatalog = request(server.port(), "GET", "/api/v1/channels",
                    "Cookie", cookie);
            check(completeCatalog.status == 200
                    && completeCatalog.body.contains("\"state\":\"COMPLETE\"")
                    && completeCatalog.body.contains("\"successCount\":32"),
                    "channel list completes catalog");

            HttpResult channelDetail = request(server.port(), "GET", "/api/v1/channels/1",
                    "Cookie", cookie);
            check(channelDetail.status == 200
                    && channelDetail.body.contains("\"rxMHz\":145.012500")
                    && channelDetail.body.contains("\"name\":\"\u6D4B\u8BD5 01\""),
                    "channel detail returns decoded data");
            HttpResult outOfRange = request(server.port(), "GET", "/api/v1/channels/32",
                    "Cookie", cookie);
            check(outOfRange.status == 400
                    && outOfRange.body.contains("CHANNEL_OUT_OF_RANGE"),
                    "channel upper bound rejected");
            HttpResult numericOverflow = request(server.port(), "GET",
                    "/api/v1/channels/999999999999999999", "Cookie", cookie);
            check(numericOverflow.status == 400
                    && numericOverflow.body.contains("CHANNEL_OUT_OF_RANGE"),
                    "channel numeric overflow rejected");
            HttpResult badChannelPath = request(server.port(), "GET",
                    "/api/v1/channels/not-a-number", "Cookie", cookie);
            check(badChannelPath.status == 404, "non-numeric channel path is not found");
            HttpResult wrongCatalogMethod = request(server.port(), "POST",
                    "/api/v1/channels", "Cookie", cookie);
            check(wrongCatalogMethod.status == 405, "channel list method is fixed");
            HttpResult refreshGet = request(server.port(), "GET",
                    "/api/v1/channels/refresh", "Cookie", cookie);
            check(refreshGet.status == 405, "channel refresh method is fixed");
            HttpResult refresh = request(server.port(), "POST",
                    "/api/v1/channels/refresh", "Cookie", cookie);
            check(refresh.status == 202 && refresh.body.contains("\"accepted\":true"),
                    "channel refresh accepted");
            HttpResult refreshAgain = request(server.port(), "POST",
                    "/api/v1/channels/refresh", "Cookie", cookie);
            check(refreshAgain.status == 202, "channel refresh is idempotent over HTTP");

            catalogHarness.port.ready = false;
            HttpResult offlineCatalog = request(server.port(), "GET",
                    "/api/v1/channels", "Cookie", cookie);
            check(offlineCatalog.status == 503
                    && offlineCatalog.body.contains("RADIO_NOT_READY"),
                    "channel catalog reports radio not ready");
            catalogHarness.port.ready = true;

            HttpResult noConfirm = json(server.port(), "/api/v1/control/volume",
                    "{\"volume\":5}", cookie);
            check(noConfirm.status == 400 && noConfirm.body.contains("CONFIRMATION_REQUIRED"),
                    "confirm true is mandatory");

            HttpResult invalidVolume = json(server.port(), "/api/v1/control/volume",
                    "{\"volume\":16,\"confirm\":true}", cookie);
            check(invalidVolume.status == 400 && invalidVolume.body.contains("VOLUME_OUT_OF_RANGE"),
                    "volume upper bound rejected before write");

            HttpResult invalidSide = json(server.port(), "/api/v1/control/side",
                    "{\"side\":\"C\",\"confirm\":true}", cookie);
            check(invalidSide.status == 400 && invalidSide.body.contains("SIDE_OUT_OF_RANGE"),
                    "side C rejected before write");

            StringBuilder oversized = new StringBuilder("{\"confirm\":true,\"padding\":\"");
            for (int i = 0; i < 1100; i++) oversized.append('x');
            oversized.append("\"}");
            HttpResult tooLarge = json(server.port(), "/api/v1/control/volume",
                    oversized.toString(), cookie);
            check(tooLarge.status == 413 && tooLarge.body.contains("PAYLOAD_TOO_LARGE"),
                    "request body bounded");

            HttpResult wrongType = request(server.port(), "POST", "/api/v1/control/volume",
                    "Cookie", cookie, "text/plain", "{}");
            check(wrongType.status == 415, "JSON content type enforced");
            check(harness.transport.writeCount == 0, "invalid HTTP requests sent no write");
        } finally {
            if (server != null) server.stop();
            catalogHarness.close();
            harness.close();
        }
    }

    private static void awaitState(LowRiskWriteController controller, String wanted,
                                   long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            LowRiskWriteController.View view = controller.view();
            if (wanted.equals(view.state)) {
                checks++;
                return;
            }
            if (view.state != null && view.state.contains("FAILED")) {
                throw new AssertionError("unexpected state " + view.state
                        + " reason=" + view.reason);
            }
            Thread.sleep(25);
        }
        LowRiskWriteController.View view = controller.view();
        throw new AssertionError("timeout waiting for " + wanted + " state="
                + view.state + " reason=" + view.reason);
    }

    private static ChannelCatalogService awaitCatalogState(ChannelCatalogService catalog,
                                                            String wanted,
                                                            long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (wanted.equals(catalog.state())) {
                checks++;
                return catalog;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("timeout waiting for catalog " + wanted
                + " state=" + catalog.state() + " json=" + catalog.toJson());
    }

    private static final class CatalogHarness {
        final CatalogPort port = new CatalogPort();
        final CatalogTransport transport = new CatalogTransport(port);
        final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        final CommandScheduler scheduler;
        final ChannelCatalogService catalog;

        CatalogHarness(long responseTimeoutMs) {
            final ChannelCatalogService[] holder = new ChannelCatalogService[1];
            scheduler = new CommandScheduler(new CommandScheduler.Listener() {
                @Override public void onReadResult(CommandScheduler.ReadResult result) {
                    if (holder[0] != null) holder[0].onReadResult(result);
                }
                @Override public void onWriteResult(CommandScheduler.WriteResult result) { }
                @Override public void onTransportFault(int epoch, String code) { }
            }, transport, responseTimeoutMs);
            transport.scheduler = scheduler;
            port.scheduler = scheduler;
            scheduler.startEpoch(1);
            catalog = new ChannelCatalogService(port, System::currentTimeMillis,
                    (task, delayMs) -> timers.schedule(task, delayMs, TimeUnit.MILLISECONDS));
            holder[0] = catalog;
            catalog.beginEpoch(1);
        }

        void changeEpoch() {
            port.epoch = 2;
            scheduler.startEpoch(2);
            catalog.beginEpoch(2);
        }

        void close() {
            catalog.markOffline("TEST_SHUTDOWN");
            scheduler.shutdown();
            timers.shutdownNow();
        }
    }

    private static final class CatalogPort implements ChannelCatalogService.Port {
        volatile int epoch = 1;
        volatile boolean ready = true;
        volatile boolean controlBusy;
        CommandScheduler scheduler;

        @Override public int epoch() { return epoch; }
        @Override public boolean isReady(int value) {
            return ready && scheduler != null && scheduler.isReady(value);
        }
        @Override public boolean isControlBusy() { return controlBusy; }
        @Override public boolean requestCatalogChannel(int channelId, long requestId) {
            return scheduler != null
                    && scheduler.requestCatalogChannel(channelId, requestId);
        }
    }

    private static final class CatalogTransport implements CommandScheduler.Transport {
        final CatalogPort port;
        final List<Integer> requestedChannels = Collections.synchronizedList(new ArrayList<>());
        final Set<Integer> timeoutChannels = Collections.newSetFromMap(
                new ConcurrentHashMap<Integer, Boolean>());
        CommandScheduler scheduler;
        volatile boolean respondChannels = true;

        CatalogTransport(CatalogPort port) { this.port = port; }

        @Override public boolean isReady(int epoch) {
            return port.ready && port.epoch == epoch;
        }

        @Override public void send(int epoch, byte[] frame) throws IOException {
            int command = ((frame[6] & 0xFF) << 8 | (frame[7] & 0xFF)) & 0x7FFF;
            if (command != GaiaCodec.CMD_READ_RF_CH) return;
            int channelId = frame[8] & 0xFF;
            requestedChannels.add(channelId);
            if (!respondChannels || timeoutChannels.contains(channelId)) return;
            scheduler.onResponse(epoch, response(GaiaCodec.CMD_READ_RF_CH,
                    channelPayload(channelId)));
        }
    }

    private static byte[] channelPayload(int channelId) {
        byte[] payload = new byte[25];
        payload[0] = (byte) channelId;
        if (channelId == 31) return payload;
        writeInt(payload, 1, 146_000_000 + channelId * 12_500);
        writeInt(payload, 5, 145_000_000 + channelId * 12_500);
        String name = channelId == 1 ? "\u6D4B\u8BD5 01"
                : "CH " + (channelId < 10 ? "0" : "") + channelId;
        try {
            byte[] nameBytes = name.getBytes("GBK");
            System.arraycopy(nameBytes, 0, payload, 15,
                    Math.min(nameBytes.length, 10));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
        return payload;
    }

    private static void writeInt(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    private static final class Harness {
        final FakeTransport transport;
        final CommandScheduler scheduler;
        final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
        final LowRiskWriteController controller;

        Harness(int volume, byte[] settings) {
            transport = new FakeTransport(volume, settings);
            final LowRiskWriteController[] holder = new LowRiskWriteController[1];
            scheduler = new CommandScheduler(new CommandScheduler.Listener() {
                @Override public void onReadResult(CommandScheduler.ReadResult result) {
                    holder[0].onReadResult(result);
                }
                @Override public void onWriteResult(CommandScheduler.WriteResult result) {
                    holder[0].onWriteResult(result);
                }
                @Override public void onTransportFault(int epoch, String code) { }
            }, transport);
            transport.scheduler = scheduler;
            scheduler.startEpoch(1);
            controller = new LowRiskWriteController(new LowRiskWriteController.Port() {
                @Override public boolean isControlReady() { return true; }
                @Override public int epoch() { return 1; }
                @Override public boolean requestSettingsRead() { return scheduler.requestSettings(); }
                @Override public boolean requestVolumeRead() { return scheduler.requestVolume(); }
                @Override public boolean requestVolumeWrite(int value) {
                    return scheduler.requestVolumeWrite(value);
                }
                @Override public boolean requestSettingsWrite(byte[] completePayload) {
                    return scheduler.requestSettingsWrite(completePayload);
                }
                @Override public boolean hasPendingRead(boolean volume) {
                    return scheduler.hasPendingCommand(volume
                            ? GaiaCodec.CMD_GET_VOLUME : GaiaCodec.CMD_READ_SETTINGS);
                }
            }, Runnable::run, timers);
            holder[0] = controller;
        }

        void close() {
            controller.cancel("TEST_SHUTDOWN");
            scheduler.shutdown();
            timers.shutdownNow();
        }
    }

    private static final class FakeTransport implements CommandScheduler.Transport {
        CommandScheduler scheduler;
        volatile int volume;
        volatile byte[] settings;
        volatile boolean ignoreVolumeWrite;
        volatile boolean ignoreSettingsWrite;
        volatile boolean respondReads = true;
        int writeCount;
        int lastVolumeWrite;

        FakeTransport(int volume, byte[] settings) {
            this.volume = volume;
            this.settings = settings.clone();
        }

        @Override public boolean isReady(int epoch) { return epoch == 1; }

        @Override public void send(int epoch, byte[] frame) throws IOException {
            int command = ((frame[6] & 0xFF) << 8 | (frame[7] & 0xFF)) & 0x7FFF;
            int payloadLength = frame[3] & 0xFF;
            if (command == GaiaCodec.CMD_SET_VOLUME) {
                writeCount++;
                int requested = frame[8] & 0xFF;
                lastVolumeWrite = requested;
                if (!ignoreVolumeWrite) volume = requested;
                scheduler.onResponse(epoch, response(GaiaCodec.CMD_SET_VOLUME, new byte[] {(byte) volume}));
            } else if (command == GaiaCodec.CMD_WRITE_SETTINGS) {
                writeCount++;
                byte[] requested = new byte[payloadLength];
                System.arraycopy(frame, 8, requested, 0, payloadLength);
                if (!ignoreSettingsWrite) settings = requested;
                scheduler.onResponse(epoch, response(GaiaCodec.CMD_WRITE_SETTINGS, settings));
            } else if (respondReads) {
                if (command == GaiaCodec.CMD_GET_VOLUME) {
                    scheduler.onResponse(epoch, response(GaiaCodec.CMD_GET_VOLUME, new byte[] {(byte) volume}));
                } else if (command == GaiaCodec.CMD_READ_SETTINGS) {
                    scheduler.onResponse(epoch, response(GaiaCodec.CMD_READ_SETTINGS, settings));
                }
            }
        }

        void resetCounters() { writeCount = 0; lastVolumeWrite = -1; }
    }


    private static byte[] response(int command, byte[] payload) {
        byte[] body = new byte[5 + payload.length];
        body[0] = 0;
        body[1] = 2;
        body[2] = (byte) (0x80 | ((command >> 8) & 0x7F));
        body[3] = (byte) (command & 0xFF);
        body[4] = 0;
        System.arraycopy(payload, 0, body, 5, payload.length);
        return body;
    }

    private static byte[] settingsPayload(int channelA, int channelB,
                                          String side, boolean scan, int squelch) {
        byte[] value = new byte[12];
        value[0] = (byte) (((channelA & 0x0F) << 4) | (channelB & 0x0F));
        int sideBits = "B".equals(side) ? 0x20 : 0x10;
        value[1] = (byte) (sideBits | (scan ? 0x80 : 0) | (squelch & 0x0F));
        return value;
    }

    private static void checkFrame(byte[] frame, int command, String label) {
        check(frame != null && frame.length >= 8, label + " frame length");
        check(frame[0] == (byte) 0xFF && frame[1] == 0x01, label + " header");
        check((((frame[6] & 0xFF) << 8 | (frame[7] & 0xFF)) & 0x7FFF) == command,
                label + " command");
        check((frame[6] & 0x80) == 0, label + " request bit");
    }

    private static void expectVolumeRejection(int value) {
        try {
            GaiaCodec.setVolumeRequest(value);
            throw new AssertionError("expected volume rejection: " + value);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void expectSettingsRejection(byte[] value) {
        try {
            GaiaCodec.writeSettingsRequest(value);
            throw new AssertionError("expected settings rejection");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static String asset(String name) throws IOException {
        if ("index.html".equals(name)) return "<html>index</html>";
        if ("login.html".equals(name)) {
            return "<html>login {{LOGIN_ERROR}}</html>";
        }
        if ("app.js".equals(name)) return "console.log('test')";
        if ("style.css".equals(name)) return "body{}";
        throw new IOException("missing asset " + name);
    }

    private static HttpResult request(int port, String method, String path,
                                      String headerName, String headerValue) throws IOException {
        return request(port, method, path, headerName, headerValue, null, null);
    }

    private static HttpResult request(int port, String method, String path,
                                      String headerName, String headerValue,
                                      String contentType, String body) throws IOException {
        HttpURLConnection connection = open(port, method, path);
        if (headerName != null) connection.setRequestProperty(headerName, headerValue);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        return read(connection);
    }

    private static HttpResult json(int port, String path, String body,
                                   String cookie) throws IOException {
        return request(port, "POST", path, "Cookie", cookie,
                "application/json; charset=utf-8", body);
    }

    private static HttpResult form(int port, String path, String body) throws IOException {
        HttpURLConnection connection = open(port, "POST", path);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return read(connection);
    }

    private static HttpURLConnection open(int port, String method, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(3000);
        return connection;
    }

    private static HttpResult read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        StringBuilder body = new StringBuilder();
        if (input != null) {
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (count > 0) output.write(buffer, 0, count);
                }
                body.append(output.toString("UTF-8"));
            }
        }
        String setCookie = connection.getHeaderField("Set-Cookie");
        String contentType = connection.getHeaderField("Content-Type");
        connection.disconnect();
        return new HttpResult(status, body.toString(), setCookie, contentType);
    }

    private static String cookiePair(String setCookie) {
        if (setCookie == null) return null;
        int semi = setCookie.indexOf(';');
        return semi < 0 ? setCookie : setCookie.substring(0, semi);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("FAILED: " + label);
        checks++;
    }

    private static final class HttpResult {
        final int status;
        final String body;
        final String setCookie;
        final String contentType;
        HttpResult(int status, String body, String setCookie, String contentType) {
            this.status = status;
            this.body = body;
            this.setCookie = setCookie;
            this.contentType = contentType == null ? "" : contentType;
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
