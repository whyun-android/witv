package com.whyun.witv.server;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.VisibleForTesting;
import com.whyun.witv.WiTVApp;
import com.whyun.witv.data.PreferenceManager;
import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.EpgProgram;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.repository.ChannelRepository;
import com.whyun.witv.data.repository.EpgRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {

    /**
     * 局域网管理页端口。此前这个数字散落在 Application、播放页、设置页三处硬编码，
     * 改端口要同时改三处且很容易漏，统一收敛到这里。
     */
    public static final int PORT = 9979;

    private final Context context;
    private final AppDatabase db;
    private final Gson gson = new Gson();
    private final ChannelRepository channelRepo;
    private final EpgRepository epgRepo;

    private static final Map<String, String> MIME_MAP = new HashMap<>();
    static {
        MIME_MAP.put("html", "text/html; charset=utf-8");
        MIME_MAP.put("css", "text/css; charset=utf-8");
        MIME_MAP.put("js", "application/javascript; charset=utf-8");
        MIME_MAP.put("json", "application/json; charset=utf-8");
        MIME_MAP.put("png", "image/png");
        MIME_MAP.put("svg", "image/svg+xml");
        MIME_MAP.put("ico", "image/x-icon");
    }

    /** 拼出用户要在浏览器里输入的完整地址。 */
    public static String buildUrl(String host) {
        return "http://" + host + ":" + PORT;
    }

    public WebServer(Context context, int port) {
        super(port);
        this.context = context;
        this.db = AppDatabase.getInstance(context);
        this.channelRepo = new ChannelRepository(context);
        this.epgRepo = new EpgRepository(context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // CORS headers
        Response response;
        try {
            if (uri.startsWith("/api/")) {
                response = handleApi(uri, method, session);
            } else {
                response = serveStaticFile(uri);
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            // 错误信息可能含中文（源地址解析失败等），必须带上 charset
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json; charset=utf-8", gson.toJson(err));
        }

        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");

        return response;
    }

    private Response handleApi(String uri, Method method, IHTTPSession session) throws IOException {
        // OPTIONS preflight
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "");
        }

        // --- Sources API ---
        if (uri.equals("/api/sources") && method == Method.GET) {
            return getSources();
        }
        if (uri.equals("/api/sources") && method == Method.POST) {
            return addSource(session);
        }
        if (uri.matches("/api/sources/\\d+") && method == Method.DELETE) {
            long id = extractId(uri);
            return deleteSource(id);
        }
        if (uri.matches("/api/sources/\\d+/activate") && method == Method.POST) {
            long id = extractIdBeforeSegment(uri, "/activate");
            return activateSource(id);
        }
        if (uri.matches("/api/sources/\\d+/channels") && method == Method.GET) {
            long id = extractIdBeforeSegment(uri, "/channels");
            return getChannels(id);
        }
        if (uri.matches("/api/sources/\\d+/reload") && method == Method.POST) {
            long id = extractIdBeforeSegment(uri, "/reload");
            return reloadSource(id);
        }

        // --- Settings API ---
        if (uri.equals("/api/settings") && method == Method.GET) {
            return getSettings();
        }
        if (uri.equals("/api/settings") && method == Method.PUT) {
            return updateSettings(session);
        }

        // --- Favorites API ---
        if (uri.equals("/api/favorites") && method == Method.GET) {
            return getFavorites(session);
        }
        if (uri.matches("/api/favorites/\\d+") && method == Method.POST) {
            long channelId = extractId(uri);
            return addFavorite(channelId);
        }
        if (uri.matches("/api/favorites/\\d+") && method == Method.DELETE) {
            long channelId = extractId(uri);
            return removeFavorite(channelId);
        }

        // --- EPG API ---
        if (uri.equals("/api/epg/reload") && method == Method.POST) {
            return reloadEpg();
        }
        if (uri.matches("/api/epg/channel/\\d+") && method == Method.GET) {
            long channelId = extractId(uri);
            return getChannelEpg(channelId, session);
        }

        // --- Version API ---
        if (uri.equals("/api/version") && method == Method.GET) {
            return getVersion();
        }

        return jsonError(Response.Status.NOT_FOUND, "API not found: " + uri);
    }

    // --- Source handlers ---

    private Response getSources() {
        List<M3USource> sources = db.m3uSourceDao().getAll();
        return jsonOk(gson.toJson(sources));
    }

    private Response addSource(IHTTPSession session) throws IOException {
        String body = readBody(session);
        JsonObject json = gson.fromJson(body, JsonObject.class);

        String name = json.has("name") ? json.get("name").getAsString() : "";
        String url = json.has("url") ? json.get("url").getAsString() : "";

        if (url.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "URL is required");
        }
        if (name.isEmpty()) {
            name = url;
        }

        M3USource source = new M3USource(name, url, null, System.currentTimeMillis(), false);
        long id = db.m3uSourceDao().insert(source);
        source.id = id;

        // Auto-activate if it's the first source
        List<M3USource> all = db.m3uSourceDao().getAll();
        if (all.size() == 1) {
            db.m3uSourceDao().activate(id);
            source.isActive = true;
        }

        new Thread(() -> {
            try {
                source.id = id;
                channelRepo.loadSource(source);
                // Auto-load EPG if URL was extracted from M3U
                M3USource updated = db.m3uSourceDao().getById(id);
                if (updated != null && updated.epgUrl != null && !updated.epgUrl.isEmpty()) {
                    epgRepo.loadEpg(updated.epgUrl);
                    new PreferenceManager(context).markEpgAutoRefreshSuccess(updated.epgUrl);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return jsonOk(gson.toJson(source));
    }

    private Response deleteSource(long id) {
        M3USource source = db.m3uSourceDao().getById(id);
        if (source == null) {
            return jsonError(Response.Status.NOT_FOUND, "Source not found");
        }
        boolean wasActive = source.isActive;
        db.m3uSourceDao().delete(source);
        if (wasActive) {
            List<M3USource> remaining = db.m3uSourceDao().getAll();
            if (!remaining.isEmpty()) {
                M3USource next = remaining.get(0);
                db.m3uSourceDao().deactivateAll();
                db.m3uSourceDao().activate(next.id);
                new Thread(() -> {
                    try {
                        List<Channel> existing = db.channelDao().getBySource(next.id);
                        if (existing.isEmpty()) {
                            next.isActive = true;
                            channelRepo.loadSource(next);
                        }
                        WiTVApp.getInstance().notifyActiveSourceChanged(next.id);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            } else {
                WiTVApp.getInstance().notifyActiveSourceChanged(-1L);
            }
        }
        return jsonOk("{\"success\":true}");
    }

    private Response activateSource(long id) {
        M3USource source = db.m3uSourceDao().getById(id);
        if (source == null) {
            return jsonError(Response.Status.NOT_FOUND, "Source not found");
        }
        db.m3uSourceDao().deactivateAll();
        db.m3uSourceDao().activate(id);

        // Load channels if not yet loaded
        new Thread(() -> {
            try {
                List<Channel> existing = db.channelDao().getBySource(id);
                if (existing.isEmpty()) {
                    source.isActive = true;
                    channelRepo.loadSource(source);
                }
                WiTVApp.getInstance().notifyActiveSourceChanged(id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return jsonOk("{\"success\":true}");
    }

    private Response getChannels(long sourceId) {
        List<String> groups = channelRepo.getGroups(sourceId);
        JsonObject result = new JsonObject();
        result.add("groups", gson.toJsonTree(groups));

        Map<String, List<Channel>> channelsByGroup = new HashMap<>();
        for (String group : groups) {
            channelsByGroup.put(group, channelRepo.getChannelsByGroup(sourceId, group));
        }
        result.add("channels", gson.toJsonTree(channelsByGroup));

        return jsonOk(gson.toJson(result));
    }

    private Response reloadSource(long id) {
        M3USource source = db.m3uSourceDao().getById(id);
        if (source == null) {
            return jsonError(Response.Status.NOT_FOUND, "Source not found");
        }

        new Thread(() -> {
            try {
                channelRepo.loadSource(source);
                if (source.isActive) {
                    WiTVApp.getInstance().notifyActiveSourceChanged(id);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return jsonOk("{\"success\":true,\"message\":\"Reloading in background\"}");
    }

    // --- Settings handlers ---

    private Response getSettings() {
        M3USource active = db.m3uSourceDao().getActive();
        JsonObject settings = new JsonObject();
        settings.addProperty("epgUrl", active != null ? (active.epgUrl != null ? active.epgUrl : "") : "");
        settings.addProperty("udpxyProxyBase", new PreferenceManager(context).getUdpxyProxyBase());
        return jsonOk(gson.toJson(settings));
    }

    private Response updateSettings(IHTTPSession session) throws IOException {
        String body = readBody(session);
        JsonObject json = gson.fromJson(body, JsonObject.class);

        M3USource active = db.m3uSourceDao().getActive();
        if (active != null && json.has("epgUrl")) {
            active.epgUrl = json.get("epgUrl").getAsString();
            db.m3uSourceDao().update(active);
        }
        // 组播转单播代理：遥控器输入 URL 很痛苦，Web 管理页是更合适的入口
        if (json.has("udpxyProxyBase")) {
            new PreferenceManager(context).setUdpxyProxyBase(
                    json.get("udpxyProxyBase").getAsString());
        }

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("udpxyProxyBase", new PreferenceManager(context).getUdpxyProxyBase());
        return jsonOk(gson.toJson(result));
    }

    // --- Favorite handlers ---

    private Response getFavorites(IHTTPSession session) {
        String sourceIdParam = session.getParms().get("sourceId");
        List<Channel> favorites;
        if (sourceIdParam != null && !sourceIdParam.isEmpty()) {
            favorites = channelRepo.getFavoriteChannels(Long.parseLong(sourceIdParam));
        } else {
            favorites = channelRepo.getAllFavoriteChannels();
        }
        JsonObject result = new JsonObject();
        result.add("channels", gson.toJsonTree(favorites));
        result.add("ids", gson.toJsonTree(channelRepo.getAllFavoriteChannelIds()));
        return jsonOk(gson.toJson(result));
    }

    private Response addFavorite(long channelId) {
        Channel channel = db.channelDao().getById(channelId);
        if (channel == null) {
            return jsonError(Response.Status.NOT_FOUND, "Channel not found");
        }
        channelRepo.addFavorite(channelId);
        return jsonOk("{\"success\":true}");
    }

    private Response removeFavorite(long channelId) {
        channelRepo.removeFavorite(channelId);
        return jsonOk("{\"success\":true}");
    }

    // --- EPG handlers ---

    private Response reloadEpg() {
        M3USource active = db.m3uSourceDao().getActive();
        if (active == null || active.epgUrl == null || active.epgUrl.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "No EPG URL configured");
        }

        final String epgUrl = active.epgUrl;
        new Thread(() -> {
            try {
                epgRepo.loadEpg(epgUrl);
                new PreferenceManager(context).markEpgAutoRefreshSuccess(epgUrl);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return jsonOk("{\"success\":true,\"message\":\"EPG reloading in background\"}");
    }

    // --- Channel EPG handler ---

    private static final int EPG_WEB_DEFAULT_LIMIT = 24;
    private static final int EPG_WEB_MAX_LIMIT = 100;

    private Response getChannelEpg(long channelId, IHTTPSession session) {
        Channel channel = db.channelDao().getById(channelId);
        if (channel == null) {
            return jsonError(Response.Status.NOT_FOUND, "Channel not found");
        }
        int limit = EPG_WEB_DEFAULT_LIMIT;
        String limitParam = session.getParms().get("limit");
        if (limitParam != null && !limitParam.isEmpty()) {
            try {
                limit = Integer.parseInt(limitParam.trim());
            } catch (NumberFormatException ignored) {
                limit = EPG_WEB_DEFAULT_LIMIT;
            }
        }
        if (limit < 1) {
            limit = 1;
        }
        if (limit > EPG_WEB_MAX_LIMIT) {
            limit = EPG_WEB_MAX_LIMIT;
        }
        List<EpgProgram> programs = epgRepo.getUpcomingPrograms(channel.tvgId, channel.tvgName, limit);
        JsonObject result = new JsonObject();
        result.addProperty("limit", limit);
        result.add("programs", gson.toJsonTree(programs));
        return jsonOk(gson.toJson(result));
    }

    // --- Version handler ---

    private Response getVersion() {
        JsonObject result = new JsonObject();
        try {
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            result.addProperty("version", versionName);
        } catch (Exception e) {
            result.addProperty("version", "unknown");
        }
        return jsonOk(gson.toJson(result));
    }

    // --- Static file serving ---

    private Response serveStaticFile(String uri) {
        if (uri.equals("/") || uri.isEmpty()) {
            uri = "/index.html";
        }

        String assetPath = "web" + uri;
        try {
            AssetManager assets = context.getAssets();
            InputStream is = assets.open(assetPath);
            byte[] data = readStream(is);
            is.close();

            String ext = "";
            int dot = assetPath.lastIndexOf('.');
            if (dot >= 0) ext = assetPath.substring(dot + 1);

            String mime = MIME_MAP.getOrDefault(ext, "application/octet-stream");
            if (isBinaryAssetExt(ext)) {
                return newFixedLengthResponse(Response.Status.OK, mime,
                        new ByteArrayInputStream(data), data.length);
            }
            return newFixedLengthResponse(Response.Status.OK, mime,
                    new String(data, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }
    }

    // --- Helpers ---

    private long extractId(String uri) {
        String[] parts = uri.split("/");
        return Long.parseLong(parts[parts.length - 1]);
    }

    private long extractIdBeforeSegment(String uri, String segment) {
        String prefix = uri.substring(0, uri.indexOf(segment));
        String[] parts = prefix.split("/");
        return Long.parseLong(parts[parts.length - 1]);
    }

    /**
     * 按 UTF-8 读取请求体。
     *
     * <p>不能用 {@code session.parseBody()}：NanoHTTPD 内部是
     * {@code new String(postBytes, contentType.getEncoding())}，而 {@code getEncoding()} 在
     * Content-Type 不带 charset 时默认返回 <b>US-ASCII</b>，中文会被整体替换成 {@code ?}。
     * 浏览器 {@code fetch} 发 {@code application/json} 时通常就不带 charset，
     * 所以这里直接读原始字节自行解码，不依赖请求头。
     */
    private String readBody(IHTTPSession session) throws IOException {
        long contentLength = parseContentLength(session.getHeaders());
        return readBodyFrom(session.getInputStream(), contentLength);
    }

    /**
     * 请求体上限。这个服务只收播放源地址和设置项，最大的一条也就几百字节，
     * 1 MiB 已经宽裕得离谱。
     *
     * <p>必须有上限：服务监听在局域网上且没有鉴权，任何能连上的设备发一个
     * {@code Content-Length: 2000000000} 就能让下面按长度预分配数组，直接 OOM 杀掉整个应用。
     */
    @VisibleForTesting
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static long parseContentLength(Map<String, String> headers) {
        if (headers == null) {
            return 0L;
        }
        String value = headers.get("content-length");
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 从流中精确读取 {@code contentLength} 个字节并按 UTF-8 解码。
     *
     * <p>必须读满而不是读一次就算：{@code InputStream.read} 允许返回少于请求的字节数，
     * 中文请求体被截断同样会变成乱码。
     */
    @VisibleForTesting
    static String readBodyFrom(InputStream inputStream, long contentLength) throws IOException {
        if (inputStream == null || contentLength <= 0) {
            return "";
        }
        // 先判断再分配：超限时一个字节都不能先占，否则这个检查就白写了
        if (contentLength > MAX_BODY_BYTES) {
            throw new IOException("Request body too large: " + contentLength);
        }
        int remaining = (int) contentLength;
        byte[] body = new byte[remaining];
        int offset = 0;
        while (offset < remaining) {
            int read = inputStream.read(body, offset, remaining - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return new String(body, 0, offset, StandardCharsets.UTF_8);
    }

    private static boolean isBinaryAssetExt(String ext) {
        if (ext == null) {
            return false;
        }
        switch (ext.toLowerCase()) {
            case "png":
            case "ico":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
                return true;
            default:
                return false;
        }
    }

    private byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }

    private Response jsonOk(String json) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json);
    }

    private Response jsonError(Response.Status status, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("error", message);
        return newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(err));
    }
}
