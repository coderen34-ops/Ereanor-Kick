package me.ereanor.kick;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kick aboneliklerini TagPlugin suffix'ine bağlar.
 * Abone olan ve /kickbagla ile hesabını bağlamış oyuncuya "tag setsuffix" verilir, süre bitince "tag removesuffix".
 */
public final class EreanorKick extends JavaPlugin implements Listener, CommandExecutor {

    /** Diske yazılan veri. Anahtarlar Gson için String. */
    public static final class Veri {
        public Map<String, Bag> baglar = new HashMap<>();      // kickKullanıcıId -> bağ
        public Map<String, Long> abonelik = new HashMap<>();   // kickKullanıcıId -> bitiş (ms)
        public Map<String, Long> elle = new HashMap<>();       // uuid -> bitiş (ms) (yönetici verdi)
        public Map<String, String> isimler = new HashMap<>();  // uuid -> son bilinen MC adı
        public Map<String, String> uygulanan = new HashMap<>(); // uuid -> bizim verdiğimiz tag adı
        public Map<String, Long> takipciler = new HashMap<>();  // kickKullanıcıId -> takip zamanı (ms)
        public boolean tagOlusturuldu;
        public String yenilemeToken = "";
    }

    public static final class Bag {
        public String uuid;
        public String kickAdi;
    }

    private static final class Kod {
        final UUID uuid;
        final long bitis;
        Kod(UUID uuid, long bitis) { this.uuid = uuid; this.bitis = bitis; }
    }

    private static final String KOD_HARF = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object kilit = new Object();
    private final Map<String, Kod> kodlar = new ConcurrentHashMap<>();
    private final SecureRandom rastgele = new SecureRandom();
    private Veri veri = new Veri();
    private File veriDosyasi;
    private KickApi api;
    private WebhookServer web;

    // ------------------------------------------------------------------ yaşam döngüsü

    @Override
    public void onEnable() {
        saveDefaultConfig();
        veriDosyasi = new File(getDataFolder(), "data.json");
        veriYukle();

        api = new KickApi(this);
        web = new WebhookServer(this, api);
        try {
            web.baslat();
        } catch (Exception e) {
            getLogger().severe("Webhook sunucusu açılamadı (port dolu olabilir): " + e.getMessage());
        }

        getCommand("kickbagla").setExecutor(this);
        getCommand("ereanorkick").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().getPlugin("TagPlugin") == null) {
            getLogger().warning("TagPlugin bulunamadı! Suffix komutları çalışmayacak.");
        }

        // TagPlugin yüklendikten sonra tag'i bir kere oluştur
        Bukkit.getScheduler().runTaskLater(this, this::tagOlusturBirKere, 100L);
        // Kick tarafı ağ işi, ana iş parçacığını bekletmesin
        Bukkit.getScheduler().runTaskAsynchronously(this, api::hazirla);
        // Her 5 dakikada süresi dolanları kontrol et
        Bukkit.getScheduler().runTaskTimer(this, this::hepsiniYenile, 20L * 30, 20L * 60 * 5);
    }

    @Override
    public void onDisable() {
        if (web != null) web.durdur();
        veriKaydet();
    }

    // ------------------------------------------------------------------ veri

    private void veriYukle() {
        try {
            if (veriDosyasi.exists()) {
                Veri v = gson.fromJson(Files.readString(veriDosyasi.toPath(), StandardCharsets.UTF_8), Veri.class);
                if (v != null) veri = v;
            }
        } catch (Exception e) {
            getLogger().warning("data.json okunamadı, boş başlıyorum: " + e.getMessage());
        }
        if (veri.baglar == null) veri.baglar = new HashMap<>();
        if (veri.abonelik == null) veri.abonelik = new HashMap<>();
        if (veri.elle == null) veri.elle = new HashMap<>();
        if (veri.isimler == null) veri.isimler = new HashMap<>();
        if (veri.uygulanan == null) veri.uygulanan = new HashMap<>();
        if (veri.takipciler == null) veri.takipciler = new HashMap<>();
        if (veri.yenilemeToken == null) veri.yenilemeToken = "";
    }

    private void veriKaydet() {
        synchronized (kilit) {
            try {
                getDataFolder().mkdirs();
                Files.writeString(veriDosyasi.toPath(), gson.toJson(veri), StandardCharsets.UTF_8);
            } catch (Exception e) {
                getLogger().warning("data.json yazılamadı: " + e.getMessage());
            }
        }
    }

    String refreshToken() {
        synchronized (kilit) { return veri.yenilemeToken; }
    }

    void refreshTokenKaydet(String t) {
        synchronized (kilit) { veri.yenilemeToken = t; }
        veriKaydet();
    }

    // ------------------------------------------------------------------ tag işleri

    private String aboneTag() {
        return getConfig().getString("tag.abone-adi", "abone").trim();
    }

    private String takipciTag() {
        return getConfig().getString("tag.takipci-adi", "takipci").trim();
    }

    private void konsol(String komut) {
        getLogger().info("> " + komut);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), komut);
    }

    private void tagOlusturBirKere() {
        boolean yap;
        synchronized (kilit) {
            yap = getConfig().getBoolean("tag.auto-create", true) && !veri.tagOlusturuldu;
            if (yap) veri.tagOlusturuldu = true;
        }
        if (!yap) return;
        konsol("tag create " + aboneTag() + " " + getConfig().getString("tag.abone-format", "&a[Abone]"));
        konsol("tag create " + takipciTag() + " " + getConfig().getString("tag.takipci-format", "&7[Takipçi]"));
        veriKaydet();
    }

    private long bitisHesapla(UUID u) {
        long en = 0;
        synchronized (kilit) {
            Long m = veri.elle.get(u.toString());
            if (m != null) en = m;
            for (Map.Entry<String, Bag> e : veri.baglar.entrySet()) {
                if (u.toString().equals(e.getValue().uuid)) {
                    Long s = veri.abonelik.get(e.getKey());
                    if (s != null && s > en) en = s;
                }
            }
        }
        return en;
    }

    private String adBul(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            synchronized (kilit) { veri.isimler.put(u.toString(), p.getName()); }
            return p.getName();
        }
        synchronized (kilit) {
            String a = veri.isimler.get(u.toString());
            if (a != null) return a;
        }
        return Bukkit.getOfflinePlayer(u).getName();
    }

    private boolean takipciMi(UUID u) {
        synchronized (kilit) {
            for (Map.Entry<String, Bag> e : veri.baglar.entrySet()) {
                if (u.toString().equals(e.getValue().uuid) && veri.takipciler.containsKey(e.getKey())) return true;
            }
        }
        return false;
    }

    /** Ana iş parçacığında çağrılır. Abone > takipçi > hiçbiri sırasıyla suffix'i ayarlar. */
    private void yenile(UUID u) {
        long bitis = bitisHesapla(u);
        long tolerans = getConfig().getLong("tag.grace-days", 2) * 86_400_000L;
        boolean aboneAktif = bitis > 0 && System.currentTimeMillis() < bitis + tolerans;
        String hedef = aboneAktif ? aboneTag() : null; // sadece abone; takipci kaldirildi
        String ad = adBul(u);
        if (ad == null) return;
        String mevcut;
        synchronized (kilit) { mevcut = veri.uygulanan.get(u.toString()); }
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            // MeslekSistemi NPC indirimleri için: abone / takipci / yok
            p.removeMetadata("ereanorkick_tier", this);
            if (hedef != null) p.setMetadata("ereanorkick_tier",
                    new org.bukkit.metadata.FixedMetadataValue(this, aboneAktif ? "abone" : "takipci"));
        }
        if (hedef != null && !hedef.equals(mevcut)) {
            konsol("tag setsuffix " + ad + " " + hedef);
            synchronized (kilit) { veri.uygulanan.put(u.toString(), hedef); }
            veriKaydet();
            if (p != null) {
                p.sendMessage(hedef.equals(aboneTag())
                        ? "§aKick aboneliğin tanındı, Abone suffix'in verildi. Teşekkürler!"
                        : "§7Kick takipçiliğin tanındı, Takipçi suffix'in verildi.");
            }
        } else if (hedef == null && mevcut != null) {
            konsol("tag removesuffix " + ad);
            synchronized (kilit) { veri.uygulanan.remove(u.toString()); }
            veriKaydet();
            if (p != null) p.sendMessage("§eKick aboneliğin sona erdi, suffix'in kaldırıldı.");
        }
    }

    private void hepsiniYenile() {
        Set<UUID> hepsi = new HashSet<>();
        synchronized (kilit) {
            for (String s : veri.uygulanan.keySet()) hepsi.add(UUID.fromString(s));
            for (String s : veri.elle.keySet()) hepsi.add(UUID.fromString(s));
            for (Bag b : veri.baglar.values()) hepsi.add(UUID.fromString(b.uuid));
        }
        for (UUID u : hepsi) yenile(u);
    }

    @EventHandler
    public void girisYapinca(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        synchronized (kilit) { veri.isimler.put(u.toString(), e.getPlayer().getName()); }
        yenile(u);
    }

    // ------------------------------------------------------------------ Kick olayları (webhook iş parçacığı)

    private static String yazi(JsonObject o, String anahtar) {
        JsonElement e = o.get(anahtar);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static long sayi(JsonObject o, String anahtar) {
        JsonElement e = o.get(anahtar);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static long bitisParse(String s) {
        try {
            if (s != null) return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis() + 32L * 86_400_000L;
    }

    void olay(String tur, JsonObject o) {
        try {
            switch (tur) {
                case "channel.subscription.new", "channel.subscription.renewal" -> {
                    JsonObject s = o.getAsJsonObject("subscriber");
                    if (s == null) return;
                    long id = sayi(s, "user_id");
                    if (id > 0) abonelikGeldi(id, yazi(s, "username"), bitisParse(yazi(o, "expires_at")));
                }
                case "channel.subscription.gifts" -> {
                    long bitis = bitisParse(yazi(o, "expires_at"));
                    JsonArray g = o.getAsJsonArray("giftees");
                    if (g == null) return;
                    for (JsonElement e : g) {
                        JsonObject s = e.getAsJsonObject();
                        long id = sayi(s, "user_id");
                        if (id > 0) abonelikGeldi(id, yazi(s, "username"), bitis);
                    }
                }
                case "channel.followed" -> {
                    JsonObject s = o.getAsJsonObject("follower");
                    if (s == null) s = o.getAsJsonObject("user");
                    if (s == null) {
                        getLogger().warning("channel.followed olayında takipçi alanı bulunamadı: " + o.keySet());
                        return;
                    }
                    long id = sayi(s, "user_id");
                    if (id > 0) takipGeldi(id, yazi(s, "username"));
                }
                case "chat.message.sent" -> {
                    JsonObject s = o.getAsJsonObject("sender");
                    String icerik = yazi(o, "content");
                    if (s == null || icerik == null) return;
                    long id = sayi(s, "user_id");
                    if (id > 0) sohbetMesaji(id, yazi(s, "username"), icerik);
                }
                default -> { }
            }
        } catch (Exception e) {
            getLogger().warning("Olay işlenemedi (" + tur + "): " + e.getMessage());
        }
    }

    private void abonelikGeldi(long kickId, String kickAdi, long bitis) {
        synchronized (kilit) { veri.abonelik.put(Long.toString(kickId), bitis); }
        veriKaydet();
        getLogger().info("Kick aboneliği: " + kickAdi + " (" + kickId + ")");
        Bukkit.getScheduler().runTask(this, () -> {
            Bag b;
            synchronized (kilit) { b = veri.baglar.get(Long.toString(kickId)); }
            if (b != null) yenile(UUID.fromString(b.uuid));
        });
    }

    private void takipGeldi(long kickId, String kickAdi) {
        synchronized (kilit) { veri.takipciler.putIfAbsent(Long.toString(kickId), System.currentTimeMillis()); }
        veriKaydet();
        getLogger().info("Kick takipçisi: " + kickAdi + " (" + kickId + ")");
        Bukkit.getScheduler().runTask(this, () -> {
            Bag b;
            synchronized (kilit) { b = veri.baglar.get(Long.toString(kickId)); }
            if (b != null) yenile(UUID.fromString(b.uuid));
        });
    }

    private void sohbetMesaji(long kickId, String kickAdi, String icerik) {
        long simdi = System.currentTimeMillis();
        for (String parca : icerik.trim().split("\\s+")) {
            Kod k = kodlar.get(parca.toUpperCase(Locale.ROOT));
            if (k == null || k.bitis < simdi) continue;
            kodlar.remove(parca.toUpperCase(Locale.ROOT));
            Bukkit.getScheduler().runTask(this, () -> bagla(k.uuid, kickId, kickAdi));
            return;
        }
    }

    private void bagla(UUID u, long kickId, String kickAdi) {
        synchronized (kilit) {
            // bir Kick hesabı bir MC hesabına, bir MC hesabı bir Kick hesabına bağlı olsun
            veri.baglar.entrySet().removeIf(e -> u.toString().equals(e.getValue().uuid));
            Bag b = new Bag();
            b.uuid = u.toString();
            b.kickAdi = kickAdi;
            veri.baglar.put(Long.toString(kickId), b);
        }
        veriKaydet();
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.sendMessage("§aKick hesabın bağlandı: §f" + kickAdi);
        }
        yenile(u);
    }

    // ------------------------------------------------------------------ komutlar

    @Override
    public boolean onCommand(CommandSender gonderen, Command komut, String etiket, String[] a) {
        if (komut.getName().equalsIgnoreCase("kickbagla")) return bagKomutu(gonderen, a);
        return yonetim(gonderen, a);
    }

    private boolean bagKomutu(CommandSender g, String[] a) {
        if (!(g instanceof Player p)) {
            g.sendMessage("Bu komut sadece oyunda kullanılır.");
            return true;
        }
        UUID u = p.getUniqueId();
        if (a.length > 0 && a[0].equalsIgnoreCase("kaldir")) {
            boolean vardi;
            synchronized (kilit) { vardi = veri.baglar.entrySet().removeIf(e -> u.toString().equals(e.getValue().uuid)); }
            veriKaydet();
            p.sendMessage(vardi ? "§eKick bağın kaldırıldı." : "§cZaten bağlı bir Kick hesabın yok.");
            yenile(u);
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("durum")) {
            String kickAdi = null;
            synchronized (kilit) {
                for (Bag b : veri.baglar.values()) if (u.toString().equals(b.uuid)) kickAdi = b.kickAdi;
            }
            long bitis = bitisHesapla(u);
            p.sendMessage(kickAdi == null ? "§eBağlı Kick hesabın yok." : "§aBağlı Kick hesabın: §f" + kickAdi);
            p.sendMessage(bitis > System.currentTimeMillis() ? "§aAbonelik bitişi: §f" + new java.util.Date(bitis) : "§eAktif abonelik görünmüyor.");
            return true;
        }
        String kod = yeniKod();
        long ttl = getConfig().getLong("link.code-ttl-minutes", 10) * 60_000L;
        kodlar.values().removeIf(k -> k.uuid.equals(u) || k.bitis < System.currentTimeMillis());
        kodlar.put(kod, new Kod(u, System.currentTimeMillis() + ttl));
        String kanal = getConfig().getString("kick.channel-slug", "ereanor");
        p.sendMessage("§aKick hesabını bağlamak için §fkick.com/" + kanal + " §asohbetine şu kodu yaz:");
        p.sendMessage("§e§l" + kod + " §7(" + (ttl / 60_000L) + " dk geçerli)");
        return true;
    }

    private String yeniKod() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(KOD_HARF.charAt(rastgele.nextInt(KOD_HARF.length())));
        return sb.toString();
    }

    private boolean yonetim(CommandSender g, String[] a) {
        if (!g.hasPermission("ereanorkick.admin")) {
            g.sendMessage("§cYetkin yok.");
            return true;
        }
        if (a.length == 0) {
            g.sendMessage("§e/ereanorkick <durum|yenile|yetkilendir|ver <oyuncu> <gün>|al <oyuncu>>");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "durum" -> {
                int aktif = 0;
                long simdi = System.currentTimeMillis();
                synchronized (kilit) {
                    for (Long b : veri.abonelik.values()) if (b > simdi) aktif++;
                    g.sendMessage("§aKick: §f" + api.durum());
                    g.sendMessage("§aKanal id: §f" + api.broadcasterId() + " §7| imza anahtarı: §f" + (api.anahtarVar() ? "var" : "yok"));
                    g.sendMessage("§aBağlı oyuncu: §f" + veri.baglar.size() + " §7| aktif abonelik: §f" + aktif
                            + " §7| suffix verilen: §f" + veri.uygulanan.size());
                }
            }
            case "yenile" -> {
                g.sendMessage("§eKick bağlantısı yenileniyor...");
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    api.hazirla();
                    g.sendMessage("§aSonuç: §f" + api.durum());
                });
            }
            case "yetkilendir" -> {
                g.sendMessage("§eBu bağlantıyı kanal sahibi hesabıyla tarayıcıda aç ve onayla:");
                g.sendMessage("§f" + api.yetkiUrl());
            }
            case "ver" -> {
                if (a.length < 3) {
                    g.sendMessage("§cKullanım: /ereanorkick ver <oyuncu> <gün>");
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                long gun;
                try {
                    gun = Long.parseLong(a[2]);
                } catch (NumberFormatException e) {
                    g.sendMessage("§cGün sayısı geçersiz.");
                    return true;
                }
                synchronized (kilit) {
                    veri.elle.put(op.getUniqueId().toString(), System.currentTimeMillis() + gun * 86_400_000L);
                    veri.isimler.put(op.getUniqueId().toString(), a[1]);
                }
                veriKaydet();
                yenile(op.getUniqueId());
                g.sendMessage("§a" + a[1] + " için " + gun + " gün abone yetkisi verildi.");
            }
            case "al" -> {
                if (a.length < 2) {
                    g.sendMessage("§cKullanım: /ereanorkick al <oyuncu>");
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                synchronized (kilit) { veri.elle.remove(op.getUniqueId().toString()); }
                veriKaydet();
                yenile(op.getUniqueId());
                g.sendMessage("§eElle verilen abone yetkisi kaldırıldı (gerçek abonelik varsa suffix kalır).");
            }
            default -> g.sendMessage("§cBilinmeyen alt komut.");
        }
        return true;
    }
}
