package me.ereanor.kick;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Kick genel API'si ile konuşan katman (token, olay aboneliği, genel anahtar). */
public final class KickApi {

    private static final String API = "https://api.kick.com/public/v1";
    private static final String ID = "https://id.kick.com/oauth";
    private static final String[] OLAYLAR = {
            "channel.subscription.new", "channel.subscription.renewal",
            "channel.subscription.gifts", "channel.followed", "chat.message.sent"};

    private final EreanorKick plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final SecureRandom random = new SecureRandom();

    private volatile String appToken;
    private volatile long appTokenExp;
    private volatile String userToken;
    private volatile long userTokenExp;
    private volatile PublicKey publicKey;
    private volatile long broadcasterId;
    private volatile String durum = "başlamadı";
    private String pkceVerifier;
    private String oauthState;

    public KickApi(EreanorKick plugin) {
        this.plugin = plugin;
    }

    public String durum() { return durum; }
    public long broadcasterId() { return broadcasterId; }
    public boolean anahtarVar() { return publicKey != null; }
    public PublicKey publicKey() { return publicKey; }

    // ---------------------------------------------------------------- HTTP

    private String istek(String method, String url, String bearer, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (contentType != null) b.header("Content-Type", contentType);
        b.header("Accept", "application/json");
        if (body != null) b.method(method, HttpRequest.BodyPublishers.ofString(body));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 400) {
            String g = r.body() == null ? "" : r.body();
            if (g.length() > 300) g = g.substring(0, 300);
            throw new IllegalStateException("HTTP " + r.statusCode() + " " + url.replace(API, "").replace(ID, "") + " -> " + g);
        }
        return r.body() == null ? "" : r.body();
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String cfg(String yol) {
        return plugin.getConfig().getString(yol, "").trim();
    }

    // ---------------------------------------------------------------- Token

    private synchronized String appTokenAl() throws Exception {
        if (appToken != null && System.currentTimeMillis() < appTokenExp - 60_000) return appToken;
        String cevap = istek("POST", ID + "/token", null, "application/x-www-form-urlencoded",
                form("grant_type", "client_credentials", "client_id", cfg("kick.client-id"),
                        "client_secret", cfg("kick.client-secret")));
        JsonObject o = JsonParser.parseString(cevap).getAsJsonObject();
        appToken = o.get("access_token").getAsString();
        appTokenExp = System.currentTimeMillis() + (o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600) * 1000L;
        return appToken;
    }

    private synchronized String userTokenAl() throws Exception {
        String yenileme = plugin.refreshToken();
        if (yenileme == null || yenileme.isEmpty()) return null;
        if (userToken != null && System.currentTimeMillis() < userTokenExp - 60_000) return userToken;
        String cevap = istek("POST", ID + "/token", null, "application/x-www-form-urlencoded",
                form("grant_type", "refresh_token", "client_id", cfg("kick.client-id"),
                        "client_secret", cfg("kick.client-secret"), "refresh_token", yenileme));
        tokenKaydet(JsonParser.parseString(cevap).getAsJsonObject());
        return userToken;
    }

    private void tokenKaydet(JsonObject o) {
        userToken = o.get("access_token").getAsString();
        userTokenExp = System.currentTimeMillis() + (o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600) * 1000L;
        if (o.has("refresh_token")) plugin.refreshTokenKaydet(o.get("refresh_token").getAsString());
    }

    /** Kullanıcı token'ı varsa onu, yoksa uygulama token'ını verir. */
    private String token() throws Exception {
        String u = userTokenAl();
        return u != null ? u : appTokenAl();
    }

    // ---------------------------------------------------------------- OAuth (yedek yol)

    public synchronized String yetkiUrl() {
        byte[] v = new byte[48];
        random.nextBytes(v);
        pkceVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
        byte[] s = new byte[16];
        random.nextBytes(s);
        oauthState = Base64.getUrlEncoder().withoutPadding().encodeToString(s);
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(pkceVerifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ID + "/authorize?" + form("response_type", "code", "client_id", cfg("kick.client-id"),
                "redirect_uri", cfg("oauth.redirect-uri"), "scope", "events:subscribe user:read channel:read",
                "code_challenge", challenge, "code_challenge_method", "S256", "state", oauthState);
    }

    public synchronized boolean oauthDonus(String code, String state) {
        if (code == null || state == null || !state.equals(oauthState) || pkceVerifier == null) return false;
        try {
            String cevap = istek("POST", ID + "/token", null, "application/x-www-form-urlencoded",
                    form("grant_type", "authorization_code", "client_id", cfg("kick.client-id"),
                            "client_secret", cfg("kick.client-secret"), "redirect_uri", cfg("oauth.redirect-uri"),
                            "code_verifier", pkceVerifier, "code", code));
            tokenKaydet(JsonParser.parseString(cevap).getAsJsonObject());
            oauthState = null;
            pkceVerifier = null;
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("OAuth dönüşü başarısız: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- Başlangıç

    /** Anahtarı, kanal kimliğini ve olay aboneliklerini hazırlar. Ana iş parçacığında ÇAĞRILMAZ. */
    public void hazirla() {
        try {
            if (cfg("kick.client-id").isEmpty() || cfg("kick.client-secret").isEmpty()) {
                durum = "config.yml'de kick.client-id / client-secret boş";
                plugin.getLogger().warning(durum);
                return;
            }
            String t = token();
            anahtarYukle(t);
            kanalBul(t);
            abonelikleriKur(t);
        } catch (Exception e) {
            durum = "hata: " + e.getMessage();
            plugin.getLogger().warning("Kick hazırlığı başarısız: " + e.getMessage());
        }
    }

    public void anahtarYukle(String t) throws Exception {
        String cevap = istek("GET", API + "/public-key", t, null, null);
        JsonObject o = JsonParser.parseString(cevap).getAsJsonObject();
        String pem = o.getAsJsonObject("data").get("public_key").getAsString()
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private void kanalBul(String t) throws Exception {
        long elle = plugin.getConfig().getLong("kick.broadcaster-user-id", 0);
        if (elle > 0) {
            broadcasterId = elle;
            return;
        }
        String slug = cfg("kick.channel-slug");
        String cevap = istek("GET", API + "/channels?slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8), t, null, null);
        JsonArray d = JsonParser.parseString(cevap).getAsJsonObject().getAsJsonArray("data");
        if (d == null || d.isEmpty()) throw new IllegalStateException("kanal bulunamadı: " + slug);
        broadcasterId = d.get(0).getAsJsonObject().get("broadcaster_user_id").getAsLong();
    }

    private void abonelikleriKur(String t) throws Exception {
        Set<String> var = new HashSet<>();
        try {
            String liste = istek("GET", API + "/events/subscriptions", t, null, null);
            JsonElement de = JsonParser.parseString(liste).getAsJsonObject().get("data");
            if (de != null && de.isJsonArray()) {
                for (JsonElement e : de.getAsJsonArray()) {
                    JsonObject s = e.getAsJsonObject();
                    if (s.has("event") && !s.get("event").isJsonNull()) var.add(s.get("event").getAsString());
                    else if (s.has("name") && !s.get("name").isJsonNull()) var.add(s.get("name").getAsString());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Mevcut abonelikler okunamadı, hepsi yeniden denenecek: " + e.getMessage());
        }
        List<String> eksik = new ArrayList<>();
        for (String o : OLAYLAR) if (!var.contains(o)) eksik.add(o);
        if (eksik.isEmpty()) {
            durum = "hazır (olay abonelikleri zaten var)";
            plugin.getLogger().info("Kick olay abonelikleri tamam, kanal id " + broadcasterId);
            return;
        }
        JsonObject govde = new JsonObject();
        JsonArray ev = new JsonArray();
        for (String o : eksik) {
            JsonObject x = new JsonObject();
            x.addProperty("name", o);
            x.addProperty("version", 1);
            ev.add(x);
        }
        govde.add("events", ev);
        govde.addProperty("method", "webhook");
        govde.addProperty("broadcaster_user_id", broadcasterId);
        String cevap;
        try {
            cevap = istek("POST", API + "/events/subscriptions", t, "application/json", govde.toString());
        } catch (IllegalStateException e) {
            if (e.getMessage().startsWith("HTTP 401") || e.getMessage().startsWith("HTTP 403")) {
                durum = "uygulama token'ı yetmedi; /ereanorkick yetkilendir ile kanal sahibi onay vermeli";
                plugin.getLogger().warning("Abonelik reddedildi (" + e.getMessage() + "). /ereanorkick yetkilendir dene.");
                return;
            }
            throw e;
        }
        int hata = 0;
        JsonElement de = JsonParser.parseString(cevap).getAsJsonObject().get("data");
        if (de != null && de.isJsonArray()) {
            for (JsonElement e : de.getAsJsonArray()) {
                JsonObject s = e.getAsJsonObject();
                if (s.has("error") && !s.get("error").isJsonNull() && !s.get("error").getAsString().isEmpty()) {
                    hata++;
                    plugin.getLogger().warning("Abonelik hatası " + s.get("name") + ": " + s.get("error").getAsString());
                }
            }
        }
        durum = hata == 0 ? "hazır" : "kısmen hazır (" + hata + " olayda hata, log'a bak)";
        plugin.getLogger().info("Kick olay abonelikleri kuruldu: " + eksik + ", kanal id " + broadcasterId);
    }
}
