package io.wsocket.sdk;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * wSocket Java SDK — Realtime Pub/Sub client with Presence, History and Connection Recovery.
 *
 * <pre>{@code
 * Client client = new Client("ws://localhost:9001", "your-api-key");
 * client.connect();
 *
 * Channel chat = client.channel("chat:general");
 * chat.subscribe((data, meta) -> System.out.println("[" + meta.channel + "] " + data));
 *
 * // Presence
 * chat.presence().onEnter(m -> System.out.println("joined: " + m.clientId));
 * chat.presence().enter(Map.of("name", "Alice"));
 * chat.presence().get();
 *
 * // History
 * chat.onHistory(result -> System.out.println(result.messages));
 * chat.history(new HistoryOptions().limit(50));
 * }</pre>
 */
public class Client {

    // ─── Types ──────────────────────────────────────────────────

    @FunctionalInterface
    public interface MessageCallback {
        void onMessage(JsonElement data, MessageMeta meta);
    }

    public static class MessageMeta {
        public final String id;
        public final String channel;
        public final long timestamp;

        MessageMeta(String id, String channel, long timestamp) {
            this.id = id;
            this.channel = channel;
            this.timestamp = timestamp;
        }
    }

    public static class PresenceMember {
        public final String clientId;
        public final JsonElement data;
        public final long joinedAt;

        PresenceMember(String clientId, JsonElement data, long joinedAt) {
            this.clientId = clientId;
            this.data = data;
            this.joinedAt = joinedAt;
        }

        static PresenceMember fromJson(JsonElement json) {
            if (json == null || !json.isJsonObject()) return new PresenceMember("", null, 0);
            JsonObject obj = json.getAsJsonObject();
            return new PresenceMember(
                obj.has("clientId") ? obj.get("clientId").getAsString() : "",
                obj.has("data") ? obj.get("data") : null,
                obj.has("joinedAt") ? obj.get("joinedAt").getAsLong() : 0
            );
        }
    }

    public static class HistoryMessage {
        public final String id;
        public final String channel;
        public final JsonElement data;
        public final String publisherId;
        public final long timestamp;
        public final long sequence;

        HistoryMessage(String id, String channel, JsonElement data, String publisherId, long timestamp, long sequence) {
            this.id = id;
            this.channel = channel;
            this.data = data;
            this.publisherId = publisherId;
            this.timestamp = timestamp;
            this.sequence = sequence;
        }
    }

    public static class HistoryResult {
        public final String channel;
        public final List<HistoryMessage> messages;
        public final boolean hasMore;

        HistoryResult(String channel, List<HistoryMessage> messages, boolean hasMore) {
            this.channel = channel;
            this.messages = messages;
            this.hasMore = hasMore;
        }
    }

    public static class HistoryOptions {
        Integer limit;
        Long before;
        Long after;
        String direction;

        public HistoryOptions limit(int limit) { this.limit = limit; return this; }
        public HistoryOptions before(long before) { this.before = before; return this; }
        public HistoryOptions after(long after) { this.after = after; return this; }
        public HistoryOptions direction(String direction) { this.direction = direction; return this; }
    }

    // ─── Presence ───────────────────────────────────────────

    public static class Presence {
        private final String channelName;
        private final Client client;
        private final List<Consumer<PresenceMember>> enterCbs = new CopyOnWriteArrayList<>();
        private final List<Consumer<PresenceMember>> leaveCbs = new CopyOnWriteArrayList<>();
        private final List<Consumer<PresenceMember>> updateCbs = new CopyOnWriteArrayList<>();
        private final List<Consumer<List<PresenceMember>>> membersCbs = new CopyOnWriteArrayList<>();

        Presence(String channelName, Client client) {
            this.channelName = channelName;
            this.client = client;
        }

        /** Enter presence set with optional data. */
        public Presence enter(Object data) {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "presence.enter");
            msg.addProperty("channel", channelName);
            if (data != null) msg.add("data", client.gson.toJsonTree(data));
            client.send(msg);
            return this;
        }

        /** Leave presence set. */
        public Presence leave() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "presence.leave");
            msg.addProperty("channel", channelName);
            client.send(msg);
            return this;
        }

        /** Update presence data. */
        public Presence update(Object data) {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "presence.update");
            msg.addProperty("channel", channelName);
            msg.add("data", client.gson.toJsonTree(data));
            client.send(msg);
            return this;
        }

        /** Get current members. */
        public Presence get() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "presence.get");
            msg.addProperty("channel", channelName);
            client.send(msg);
            return this;
        }

        public Presence onEnter(Consumer<PresenceMember> cb) { enterCbs.add(cb); return this; }
        public Presence onLeave(Consumer<PresenceMember> cb) { leaveCbs.add(cb); return this; }
        public Presence onUpdate(Consumer<PresenceMember> cb) { updateCbs.add(cb); return this; }
        public Presence onMembers(Consumer<List<PresenceMember>> cb) { membersCbs.add(cb); return this; }

        void emitEnter(PresenceMember m) { for (var cb : enterCbs) { try { cb.accept(m); } catch (Exception ignored) {} } }
        void emitLeave(PresenceMember m) { for (var cb : leaveCbs) { try { cb.accept(m); } catch (Exception ignored) {} } }
        void emitUpdate(PresenceMember m) { for (var cb : updateCbs) { try { cb.accept(m); } catch (Exception ignored) {} } }
        void emitMembers(List<PresenceMember> members) { for (var cb : membersCbs) { try { cb.accept(members); } catch (Exception ignored) {} } }
    }

    // ─── Channel ────────────────────────────────────────────

    public static class Channel {
        public final String name;
        private final Client client;
        private boolean subscribed = false;
        private final List<MessageCallback> callbacks = new CopyOnWriteArrayList<>();
        private final List<Consumer<HistoryResult>> historyCbs = new CopyOnWriteArrayList<>();
        private final Presence presence;

        Channel(String name, Client client) {
            this.name = name;
            this.client = client;
            this.presence = new Presence(name, client);
        }

        /** Get presence API for this channel. */
        public Presence presence() { return presence; }

        /** Subscribe to messages on this channel. */
        public Channel subscribe(MessageCallback callback) {
            return subscribe(callback, 0);
        }

        /** Subscribe with optional rewind. */
        public Channel subscribe(MessageCallback callback, int rewind) {
            callbacks.add(callback);
            if (!subscribed) {
                JsonObject msg = new JsonObject();
                msg.addProperty("action", "subscribe");
                msg.addProperty("channel", name);
                if (rewind > 0) msg.addProperty("rewind", rewind);
                client.send(msg);
                subscribed = true;
            }
            return this;
        }

        /** Publish data to this channel. */
        public void publish(Object data) {
            publish(data, true);
        }

        /** Publish data with persist option. */
        public void publish(Object data, boolean persist) {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "publish");
            msg.addProperty("channel", name);
            msg.addProperty("id", UUID.randomUUID().toString());
            msg.add("data", client.gson.toJsonTree(data));
            if (!persist) msg.addProperty("persist", false);
            client.send(msg);
        }

        /** Query message history. */
        public Channel history(HistoryOptions opts) {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "history");
            msg.addProperty("channel", name);
            if (opts.limit != null) msg.addProperty("limit", opts.limit);
            if (opts.before != null) msg.addProperty("before", opts.before);
            if (opts.after != null) msg.addProperty("after", opts.after);
            if (opts.direction != null) msg.addProperty("direction", opts.direction);
            client.send(msg);
            return this;
        }

        /** Listen for history query results. */
        public Channel onHistory(Consumer<HistoryResult> cb) {
            historyCbs.add(cb);
            return this;
        }

        /** Unsubscribe from this channel. */
        public void unsubscribe() {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "unsubscribe");
            msg.addProperty("channel", name);
            client.send(msg);
            subscribed = false;
            callbacks.clear();
            historyCbs.clear();
        }

        void emit(JsonElement data, MessageMeta meta) {
            for (MessageCallback cb : callbacks) {
                try { cb.onMessage(data, meta); } catch (Exception ignored) {}
            }
        }

        void emitHistory(HistoryResult result) {
            for (var cb : historyCbs) {
                try { cb.accept(result); } catch (Exception ignored) {}
            }
        }

        void markForResubscribe() { subscribed = false; }
        boolean hasListeners() { return !callbacks.isEmpty(); }
    }

    // ─── Client ─────────────────────────────────────────────

    private final String url;
    private final String apiKey;
    private final boolean autoReconnect;
    private final int maxReconnectAttempts;
    private final long reconnectDelayMs;
    private final boolean recover;
    private final OkHttpClient httpClient;
    final Gson gson = new Gson();
    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();
    private WebSocket ws;
    private volatile long lastMessageTimestamp = 0;
    private volatile String resumeToken = null;
    private ScheduledExecutorService pingScheduler;

    /** Pub/Sub namespace — client.pubsub.channel("name") */
    public final PubSubNamespace pubsub;
    /** Push namespace — set via client.configurePush() */
    public PushClient push;

    public Client(String url, String apiKey) {
        this(url, apiKey, true, 10, 1000, true);
    }

    public Client(String url, String apiKey, boolean autoReconnect,
                  int maxReconnectAttempts, long reconnectDelayMs, boolean recover) {
        this.url = url;
        this.apiKey = apiKey;
        this.autoReconnect = autoReconnect;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectDelayMs = reconnectDelayMs;
        this.recover = recover;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
        this.pubsub = new PubSubNamespace(this);
    }

    /** Connect to the wSocket server. */
    public void connect() {
        Request request = new Request.Builder()
            .url(url + "/?key=" + apiKey)
            .build();

        ws = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                startPing();
                resubscribeAll();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("[wSocket] Connection failure: " + t.getMessage());
                stopPing();
                if (autoReconnect) reconnect();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                stopPing();
            }
        });
    }

    /** Disconnect from the server. */
    public void disconnect() {
        stopPing();
        if (ws != null) ws.close(1000, "bye");
    }

    /** Get or create a channel reference.
     * @deprecated Use client.pubsub.channel(name) for new code.
     */
    @Deprecated
    public Channel channel(String name) {
        return channels.computeIfAbsent(name, n -> new Channel(n, this));
    }

    /** Configure push notification access. */
    public PushClient configurePush(String baseUrl, String token, String appId) {
        this.push = new PushClient(baseUrl, token, appId, httpClient, gson);
        return this.push;
    }

    void send(JsonObject msg) {
        if (ws != null) ws.send(msg.toString());
    }

    private void startPing() {
        stopPing();
        pingScheduler = Executors.newSingleThreadScheduledExecutor();
        pingScheduler.scheduleAtFixedRate(() -> {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "ping");
            send(msg);
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopPing() {
        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
            pingScheduler = null;
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String action = root.get("action").getAsString();
            String channel = root.has("channel") ? root.get("channel").getAsString() : "";

            switch (action) {
                case "message": {
                    Channel ch = channels.get(channel);
                    if (ch != null) {
                        JsonElement data = root.get("data");
                        String id = root.has("id") ? root.get("id").getAsString() : "";
                        long ts = root.has("timestamp") ? root.get("timestamp").getAsLong() : 0;
                        if (ts > lastMessageTimestamp) lastMessageTimestamp = ts;
                        ch.emit(data, new MessageMeta(id, channel, ts));
                    }
                    break;
                }
                case "presence.enter": {
                    Channel ch = channels.get(channel);
                    if (ch != null) ch.presence().emitEnter(PresenceMember.fromJson(root.get("data")));
                    break;
                }
                case "presence.leave": {
                    Channel ch = channels.get(channel);
                    if (ch != null) ch.presence().emitLeave(PresenceMember.fromJson(root.get("data")));
                    break;
                }
                case "presence.update": {
                    Channel ch = channels.get(channel);
                    if (ch != null) ch.presence().emitUpdate(PresenceMember.fromJson(root.get("data")));
                    break;
                }
                case "presence.members": {
                    Channel ch = channels.get(channel);
                    if (ch != null) {
                        List<PresenceMember> members = new ArrayList<>();
                        JsonArray arr = root.getAsJsonArray("data");
                        if (arr != null) {
                            for (JsonElement el : arr) {
                                members.add(PresenceMember.fromJson(el));
                            }
                        }
                        ch.presence().emitMembers(members);
                    }
                    break;
                }
                case "history": {
                    Channel ch = channels.get(channel);
                    if (ch != null) {
                        ch.emitHistory(parseHistoryResult(root.get("data"), channel));
                    }
                    break;
                }
                case "ack": {
                    if (root.has("id") && "resume".equals(root.get("id").getAsString())) {
                        if (root.has("data") && root.get("data").isJsonObject()) {
                            JsonObject data = root.getAsJsonObject("data");
                            if (data.has("resumeToken")) {
                                resumeToken = data.get("resumeToken").getAsString();
                            }
                        }
                    }
                    break;
                }
                case "error": {
                    String err = root.has("error") ? root.get("error").getAsString() : "unknown";
                    System.err.println("[wSocket] Error: " + err);
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[wSocket] Parse error: " + e.getMessage());
        }
    }

    private void resubscribeAll() {
        if (recover && lastMessageTimestamp > 0) {
            List<String> names = new ArrayList<>();
            for (Channel ch : channels.values()) {
                if (ch.hasListeners()) {
                    names.add(ch.name);
                    ch.markForResubscribe();
                }
            }
            if (!names.isEmpty()) {
                String token = resumeToken;
                if (token == null || token.isEmpty()) {
                    JsonObject payload = new JsonObject();
                    payload.add("channels", gson.toJsonTree(names));
                    payload.addProperty("lastTs", lastMessageTimestamp);
                    token = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload.toString().getBytes());
                }
                JsonObject msg = new JsonObject();
                msg.addProperty("action", "resume");
                msg.addProperty("resumeToken", token);
                send(msg);
                return;
            }
        }
        for (Channel ch : channels.values()) {
            ch.markForResubscribe();
            if (ch.hasListeners()) {
                JsonObject msg = new JsonObject();
                msg.addProperty("action", "subscribe");
                msg.addProperty("channel", ch.name);
                send(msg);
            }
        }
    }

    private void reconnect() {
        for (int i = 1; i <= maxReconnectAttempts; i++) {
            long delay = reconnectDelayMs * (1L << (i - 1));
            System.err.println("[wSocket] Reconnecting in " + delay + "ms (attempt " + i + "/" + maxReconnectAttempts + ")");
            try {
                Thread.sleep(delay);
                connect();
                return;
            } catch (Exception ignored) {}
        }
        System.err.println("[wSocket] Max reconnect attempts reached");
    }

    private HistoryResult parseHistoryResult(JsonElement json, String channel) {
        List<HistoryMessage> messages = new ArrayList<>();
        boolean hasMore = false;
        if (json != null && json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            if (obj.has("channel")) channel = obj.get("channel").getAsString();
            hasMore = obj.has("hasMore") && obj.get("hasMore").getAsBoolean();
            if (obj.has("messages") && obj.get("messages").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("messages")) {
                    if (el.isJsonObject()) {
                        JsonObject m = el.getAsJsonObject();
                        messages.add(new HistoryMessage(
                            m.has("id") ? m.get("id").getAsString() : "",
                            m.has("channel") ? m.get("channel").getAsString() : channel,
                            m.has("data") ? m.get("data") : null,
                            m.has("publisherId") ? m.get("publisherId").getAsString() : "",
                            m.has("timestamp") ? m.get("timestamp").getAsLong() : 0,
                            m.has("sequence") ? m.get("sequence").getAsLong() : 0
                        ));
                    }
                }
            }
        }
        return new HistoryResult(channel, messages, hasMore);
    }

    // ─── Push Notifications ─────────────────────────────────

    /**
     * REST-based push notification client for wSocket.
     * <p>Usage:
     * <pre>
     * PushClient push = new PushClient("http://localhost:9001", "admin-token", "app-id");
     * push.registerFCM("device-token", "user1");
     * push.sendToMember("user1", "Hello", "World");
     * </pre>
     */
    public static class PushClient {
        private final String baseUrl;
        private final String token;
        private final String appId;
        private final OkHttpClient http;
        private final Gson gson;

        public PushClient(String baseUrl, String token, String appId) {
            this(baseUrl, token, appId, new OkHttpClient(), new Gson());
        }

        public PushClient(String baseUrl, String token, String appId, OkHttpClient http, Gson gson) {
            this.baseUrl = baseUrl.replaceAll("/$", "");
            this.token = token;
            this.appId = appId;
            this.http = http;
            this.gson = gson;
        }

        private JsonObject apiRequest(String method, String path, JsonObject body) throws IOException {
            okhttp3.RequestBody reqBody = body != null
                ? okhttp3.RequestBody.create(gson.toJson(body), okhttp3.MediaType.parse("application/json"))
                : null;
            Request req = new Request.Builder()
                .url(baseUrl + path)
                .method(method, reqBody)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .build();
            try (Response resp = http.newCall(req).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "{}";
                if (!resp.isSuccessful()) throw new IOException("Push API error " + resp.code() + ": " + respBody);
                return gson.fromJson(respBody, JsonObject.class);
            }
        }

        /** Register an FCM device token (Android). */
        public String registerFCM(String deviceToken, String memberId) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("platform", "fcm");
            body.addProperty("memberId", memberId);
            body.addProperty("deviceToken", deviceToken);
            JsonObject res = apiRequest("POST", "/api/admin/apps/" + appId + "/push/register", body);
            return res.get("subscriptionId").getAsString();
        }

        /** Register an APNs device token (iOS). */
        public String registerAPNs(String deviceToken, String memberId) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("platform", "apns");
            body.addProperty("memberId", memberId);
            body.addProperty("deviceToken", deviceToken);
            JsonObject res = apiRequest("POST", "/api/admin/apps/" + appId + "/push/register", body);
            return res.get("subscriptionId").getAsString();
        }

        /** Unregister push subscriptions for a member. */
        public int unregister(String memberId, String platform) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("memberId", memberId);
            if (platform != null) body.addProperty("platform", platform);
            JsonObject res = apiRequest("DELETE", "/api/admin/apps/" + appId + "/push/unregister", body);
            return res.get("removed").getAsInt();
        }

        /** Delete a specific push subscription by its ID. */
        public boolean deleteSubscription(String subscriptionId) throws IOException {
            JsonObject res = apiRequest("DELETE", "/api/admin/apps/" + appId + "/push/subscriptions/" + subscriptionId, null);
            return res.has("deleted") && res.get("deleted").getAsBoolean();
        }

        /** Send a push notification to a specific member. */
        public JsonObject sendToMember(String memberId, String title, String msgBody) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("memberId", memberId);
            body.addProperty("title", title);
            body.addProperty("body", msgBody);
            return apiRequest("POST", "/api/admin/apps/" + appId + "/push/send", body);
        }

        /** Broadcast a push notification to all app subscribers. */
        public JsonObject broadcast(String title, String msgBody) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("broadcast", true);
            body.addProperty("title", title);
            body.addProperty("body", msgBody);
            return apiRequest("POST", "/api/admin/apps/" + appId + "/push/send", body);
        }
    }

    // ─── PubSub Namespace ───────────────────────────────────

    /** Scoped API for pub/sub operations. */
    public static class PubSubNamespace {
        private final Client client;

        PubSubNamespace(Client client) {
            this.client = client;
        }

        /** Get or create a channel (same as client.channel()). */
        public Channel channel(String name) {
            return client.channel(name);
        }
    }
}