package me.ereanor.kick;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Kick'in webhook çağrılarını (ve OAuth dönüşünü) alan küçük HTTP sunucusu. */
public final class WebhookServer {

    private final EreanorKick plugin;
    private final KickApi api;
    private HttpServer server;
    private ExecutorService havuz;
    private final Deque<String> sonIdler = new ArrayDeque<>();
    private final Set<String> idKumesi = new HashSet<>();

    public WebhookServer(EreanorKick plugin, KickApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    public void baslat() throws IOException {
        String bind = plugin.getConfig().getString("webhook.bind", "127.0.0.1");
        int port = plugin.getConfig().getInt("webhook.port", 8765);
        String yol = plugin.getConfig().getString("webhook.path", "/kick/webhook");
        String cb = plugin.getConfig().getString("oauth.callback-path", "/kick/callback");
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        havuz = Executors.newFixedThreadPool(2);
        server.setExecutor(havuz);
        server.createContext(yol, this::webhook);
        server.createContext(cb, this::oauth);
        server.start();
        plugin.getLogger().info("Webhook sunucusu açıldı: " + bind + ":" + port + yol);
    }

    public void durdur() {
        if (server != null) server.stop(0);
        if (havuz != null) havuz.shutdownNow();
    }

    private void cevap(HttpExchange ex, int kod, String metin) throws IOException {
        byte[] b = metin.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(kod, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    private void oauth(HttpExchange ex) throws IOException {
        try {
            String q = ex.getRequestURI().getRawQuery();
            String code = null, state = null;
            if (q != null) {
                for (String p : q.split("&")) {
                    String[] kv = p.split("=", 2);
                    if (kv.length != 2) continue;
                    String v = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                    if (kv[0].equals("code")) code = v;
                    else if (kv[0].equals("state")) state = v;
                }
            }
            boolean ok = api.oauthDonus(code, state);
            cevap(ex, ok ? 200 : 400, ok ? "Yetki verildi, bu sekmeyi kapatabilirsin." : "Yetkilendirme başarısız, sunucu log'una bak.");
            if (ok) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, api::hazirla);
        } catch (Exception e) {
            cevap(ex, 500, "hata");
        }
    }

    private void webhook(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                cevap(ex, 405, "");
                return;
            }
            byte[] ham = ex.getRequestBody().readNBytes(1_000_000);
            String govde = new String(ham, StandardCharsets.UTF_8);
            String id = ex.getRequestHeaders().getFirst("Kick-Event-Message-Id");
            String zaman = ex.getRequestHeaders().getFirst("Kick-Event-Message-Timestamp");
            String imza = ex.getRequestHeaders().getFirst("Kick-Event-Signature");
            String tur = ex.getRequestHeaders().getFirst("Kick-Event-Type");
            if (id == null || zaman == null || imza == null || tur == null) {
                cevap(ex, 400, "");
                return;
            }
            if (api.publicKey() == null) {
                cevap(ex, 503, "");
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, api::hazirla);
                return;
            }
            if (!imzaDogru(id, zaman, govde, imza)) {
                plugin.getLogger().warning("Geçersiz imzalı webhook reddedildi.");
                cevap(ex, 401, "");
                return;
            }
            if (!yeniMi(id)) {
                cevap(ex, 200, "");
                return;
            }
            cevap(ex, 200, "");
            JsonObject o = JsonParser.parseString(govde).getAsJsonObject();
            plugin.olay(tur, o);
        } catch (Exception e) {
            plugin.getLogger().warning("Webhook işlenemedi: " + e.getMessage());
            try {
                cevap(ex, 500, "");
            } catch (Exception ignored) {
            }
        }
    }

    private boolean imzaDogru(String id, String zaman, String govde, String imza) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(api.publicKey());
            s.update((id + "." + zaman + "." + govde).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(imza.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized boolean yeniMi(String id) {
        if (idKumesi.contains(id)) return false;
        idKumesi.add(id);
        sonIdler.addLast(id);
        while (sonIdler.size() > 2000) idKumesi.remove(sonIdler.removeFirst());
        return true;
    }
}
