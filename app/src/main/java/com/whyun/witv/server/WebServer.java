package com.whyun.witv.server;

import android.content.Context;
import android.content.res.AssetManager;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.data.db.entity.Channel;
import com.whyun.witv.data.db.entity.M3USource;
import com.whyun.witv.data.repository.ChannelRepository;
import com.whyun.witv.data.repository.EpgRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class WebServer extends NanoHTTPD {

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
            response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", gson.toJson(err));
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

        // --- EPG API ---
        if (uri.equals("/api/epg/reload") && method == Method.POST) {
            return reloadEpg();
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
        db.m3uSourceDao().delete(source);
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

        return jsonOk("{\"success\":true}");
    }

    // --- EPG handlers ---

    private Response reloadEpg() {
        M3USource active = db.m3uSourceDao().getActive();
        if (active == null || active.epgUrl == null || active.epgUrl.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "No EPG URL configured");
        }

        new Thread(() -> {
            try {
                epgRepo.loadEpg(active.epgUrl);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        return jsonOk("{\"success\":true,\"message\":\"EPG reloading in background\"}");
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
            return newFixedLengthResponse(Response.Status.OK, mime, new String(data, "UTF-8"));
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

    private String readBody(IHTTPSession session) throws IOException {
        Map<String, String> body = new HashMap<>();
        try {
            session.parseBody(body);
        } catch (ResponseException e) {
            throw new IOException(e);
        }
        String postData = body.get("postData");
        return postData != null ? postData : "";
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
