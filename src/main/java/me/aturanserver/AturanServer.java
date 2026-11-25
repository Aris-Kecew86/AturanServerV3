package me.aturanserver;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AturanServer: world border, End nonaktif, nametag hilang, chat nonaktif,
 * hard permanen, duduk di tangga, teleport acak + ban singkat saat mati.
 */
public final class AturanServer extends JavaPlugin implements Listener {

    private static final String PERM_BYPASS_CHAT = "aturanserver.bypass.chat";
    private static final String PERM_BYPASS_BAN = "aturanserver.bypass.ban";
    private static final String PERM_BYPASS_END = "aturanserver.bypass.end";

    private static final Set<Material> TIDAK_AMAN = EnumSet.of(
            Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.POWDER_SNOW, Material.POINTED_DRIPSTONE, Material.SWEET_BERRY_BUSH);

    private final Map<UUID, Long> banSampai = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownPesan = new ConcurrentHashMap<>();
    private final Map<UUID, ArmorStand> kursi = new HashMap<>();
    private final Set<UUID> lindungJatuh = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();

    private NamespacedKey kPernahJoin;
    private NamespacedKey kTpSetelahMati;
    private Team teamSembunyi;
    private volatile boolean chatAktif;

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kPernahJoin = new NamespacedKey(this, "pernah_join");
        kTpSetelahMati = new NamespacedKey(this, "tp_setelah_mati");
        chatAktif = getConfig().getBoolean("chat.aktif", false);

        getServer().getPluginManager().registerEvents(this, this);

        siapkanTeam();
        for (World w : Bukkit.getWorlds()) {
            terapkanAturan(w);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            sembunyikanNama(p);
        }

        // Cek tiap 1 detik: hard, border, End, nametag
        Bukkit.getScheduler().runTaskTimer(this, this::tugasBerkala, 20L, 20L);
        // Bersihkan kursi kosong tiap 0,5 detik
        Bukkit.getScheduler().runTaskTimer(this, this::bersihkanKursi, 10L, 10L);
    }

    @Override
    public void onDisable() {
        for (ArmorStand as : kursi.values()) {
            as.remove();
        }
        kursi.clear();
        try {
            if (teamSembunyi != null) {
                teamSembunyi.unregister();
            }
        } catch (IllegalStateException ignored) {
            // team sudah tidak ada
        }
    }

    // =====================================================================
    // Aturan dunia: hard + world border
    // =====================================================================

    private void terapkanAturan(World w) {
        // Hard permanen untuk semua dunia
        if (getConfig().getBoolean("difficulty.paksa-hard", true) && w.getDifficulty() != Difficulty.HARD) {
            w.setDifficulty(Difficulty.HARD);
        }

        // World border asli (bukan teleport manual)
        World.Environment env = w.getEnvironment();
        double ukuran;
        if (env == World.Environment.NORMAL) {
            ukuran = getConfig().getDouble("border.overworld", 8000);
        } else if (env == World.Environment.NETHER) {
            ukuran = getConfig().getDouble("border.nether", 1000);
        } else {
            return;
        }
        WorldBorder b = w.getWorldBorder();
        Location c = b.getCenter();
        if (c.getX() != 0.0 || c.getZ() != 0.0) {
            b.setCenter(0.0, 0.0);
        }
        if (b.getSize() != ukuran) {
            b.setSize(ukuran);
        }
        int peringatan = getConfig().getInt("border.peringatan-jarak", 50);
        if (b.getWarningDistance() != peringatan) {
            b.setWarningDistance(peringatan);
        }
    }

    private void tugasBerkala() {
        for (World w : Bukkit.getWorlds()) {
            terapkanAturan(w);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            sembunyikanNama(p);
            if (endNonaktif()
                    && p.getWorld().getEnvironment() == World.Environment.THE_END
                    && !p.hasPermission(PERM_BYPASS_END)) {
                keluarDariEnd(p);
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        terapkanAturan(e.getWorld());
    }

    // =====================================================================
    // The End nonaktif
    // =====================================================================

    private boolean endNonaktif() {
        return getConfig().getBoolean("end.nonaktif", true);
    }

    private boolean tujuanEnd(Location to) {
        return to != null && to.getWorld() != null
                && to.getWorld().getEnvironment() == World.Environment.THE_END;
    }

    private World overworld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) {
                return w;
            }
        }
        return Bukkit.getWorlds().get(0);
    }

    private void keluarDariEnd(Player p) {
        World w = overworld();
        p.teleportAsync(w.getSpawnLocation());
        info(p, "The End dinonaktifkan di server ini.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!endNonaktif() || e.getPlayer().hasPermission(PERM_BYPASS_END)) {
            return;
        }
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL || tujuanEnd(e.getTo())) {
            e.setCancelled(true);
            info(e.getPlayer(), "The End dinonaktifkan di server ini.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleportEnd(PlayerTeleportEvent e) {
        if (!endNonaktif() || e.getPlayer().hasPermission(PERM_BYPASS_END)) {
            return;
        }
        if (tujuanEnd(e.getTo())) {
            e.setCancelled(true);
            info(e.getPlayer(), "The End dinonaktifkan di server ini.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (endNonaktif() && tujuanEnd(e.getTo())) {
            e.setCancelled(true);
        }
    }

    // =====================================================================
    // Interaksi: blok Eye of Ender + duduk di tangga
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) {
            return;
        }
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();

        // Portal End tidak bisa diaktifkan
        if (endNonaktif() && b.getType() == Material.END_PORTAL_FRAME
                && e.getItem() != null && e.getItem().getType() == Material.ENDER_EYE
                && !p.hasPermission(PERM_BYPASS_END)) {
            e.setCancelled(true);
            info(p, "The End dinonaktifkan di server ini.");
            return;
        }

        // Duduk: klik kanan tangga, tangan kosong, tidak sneak
        if (e.getHand() == EquipmentSlot.HAND && e.getItem() == null && !p.isSneaking()) {
            if (duduk(p, b)) {
                e.setCancelled(true);
            }
        }
    }

    private boolean duduk(Player p, Block b) {
        if (!getConfig().getBoolean("duduk.aktif", false)) {
            return false;
        }
        if (!(b.getBlockData() instanceof Stairs stairs)) {
            return false;
        }
        if (stairs.getHalf() != Bisected.Half.BOTTOM) {
            return false;
        }
        if (p.isInsideVehicle() || kursi.containsKey(p.getUniqueId())) {
            return false;
        }
        if (!b.getRelative(BlockFace.UP).isPassable()) {
            return false;
        }
        Location pusat = b.getLocation().add(0.5, 0.5, 0.5);
        if (!p.getWorld().equals(pusat.getWorld()) || p.getLocation().distanceSquared(pusat) > 9.0) {
            return false;
        }

        double offset = getConfig().getDouble("duduk.y-offset", 0.0);
        Location loc = b.getLocation().add(0.5, 0.5 + offset, 0.5);
        ArmorStand as = b.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setGravity(false);
            a.setMarker(true);
            a.setSmall(true);
            a.setBasePlate(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
        });
        as.addPassenger(p);
        kursi.put(p.getUniqueId(), as);
        return true;
    }

    private void bersihkanKursi() {
        kursi.entrySet().removeIf(en -> {
            ArmorStand as = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            boolean selesai = !as.isValid() || p == null || !p.isOnline() || !as.getPassengers().contains(p);
            if (selesai) {
                as.remove();
            }
            return selesai;
        });
    }

    // =====================================================================
    // Nametag hilang
    // =====================================================================

    private void siapkanTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam("aturan_hide");
        if (t == null) {
            t = sb.registerNewTeam("aturan_hide");
        }
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        teamSembunyi = t;
    }

    private void sembunyikanNama(Player p) {
        if (teamSembunyi == null || !getConfig().getBoolean("nametag.sembunyikan", true)) {
            return;
        }
        if (!teamSembunyi.hasEntry(p.getName())) {
            teamSembunyi.addEntry(p.getName());
        }
    }

    // =====================================================================
    // Chat nonaktif (benar-benar)
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        if (chatAktif || e.getPlayer().hasPermission(PERM_BYPASS_CHAT)) {
            return;
        }
        e.setCancelled(true);
        e.viewers().clear();
        info(e.getPlayer(), "Chat dinonaktifkan di server ini.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandChat(PlayerCommandPreprocessEvent e) {
        if (chatAktif || e.getPlayer().hasPermission(PERM_BYPASS_CHAT)) {
            return;
        }
        String raw = e.getMessage();
        if (raw.length() < 2) {
            return;
        }
        String cmd = raw.substring(1).stripLeading().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int titikDua = cmd.indexOf(':');
        if (titikDua >= 0) {
            cmd = cmd.substring(titikDua + 1); // minecraft:msg -> msg
        }
        if (getConfig().getStringList("chat.perintah-diblokir").contains(cmd)) {
            e.setCancelled(true);
            info(e.getPlayer(), "Chat dinonaktifkan di server ini.");
        }
    }

    // =====================================================================
    // Join pertama, mati, ban singkat, teleport acak
    // =====================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        sembunyikanNama(p);

        var pdc = p.getPersistentDataContainer();
        boolean pertama = !p.hasPlayedBefore() && !pdc.has(kPernahJoin, PersistentDataType.BYTE);
        pdc.set(kPernahJoin, PersistentDataType.BYTE, (byte) 1);

        boolean habisMati = pdc.has(kTpSetelahMati, PersistentDataType.BYTE);
        if (habisMati) {
            pdc.remove(kTpSetelahMati);
        }

        boolean tpPertama = pertama && getConfig().getBoolean("teleport.join-pertama", true);
        if (tpPertama || habisMati) {
            Bukkit.getScheduler().runTaskLater(this, () -> teleportAcak(p, 0), 20L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        ArmorStand as = kursi.remove(id);
        if (as != null) {
            as.remove();
        }
        cooldownPesan.remove(id);
        lindungJatuh.remove(id);
    }

    private boolean bakalDiban(Player p) {
        return getConfig().getInt("ban.detik", 15) > 0 && !p.hasPermission(PERM_BYPASS_BAN);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!bakalDiban(p)) {
            return;
        }
        int detik = getConfig().getInt("ban.detik", 15);
        banSampai.put(p.getUniqueId(), System.currentTimeMillis() + detik * 1000L);

        // Respawn dulu supaya data pemain tersimpan dalam keadaan hidup, lalu kick
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (p.isDead()) {
                p.spigot().respawn();
            }
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) {
                    p.kick(pesanBan(sisaDetik(p.getUniqueId())));
                }
            }, 2L);
        }, 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (e.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
            return;
        }
        // Punya checkpoint bed / respawn anchor -> tidak diteleport acak
        if (e.isBedSpawn() || e.isAnchorSpawn()) {
            return;
        }
        if (!getConfig().getBoolean("teleport.setelah-mati", true)) {
            return;
        }
        Player p = e.getPlayer();
        if (bakalDiban(p)) {
            // Akan di-kick; teleport dilakukan saat dia join lagi
            p.getPersistentDataContainer().set(kTpSetelahMati, PersistentDataType.BYTE, (byte) 1);
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> teleportAcak(p, 0), 2L);
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Long sampai = banSampai.get(e.getUniqueId());
        if (sampai == null) {
            return;
        }
        long sisaMs = sampai - System.currentTimeMillis();
        if (sisaMs <= 0) {
            banSampai.remove(e.getUniqueId());
            return;
        }
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                pesanBan((int) Math.ceil(sisaMs / 1000.0)));
    }

    private int sisaDetik(UUID id) {
        Long sampai = banSampai.get(id);
        if (sampai == null) {
            return getConfig().getInt("ban.detik", 15);
        }
        return Math.max(1, (int) Math.ceil((sampai - System.currentTimeMillis()) / 1000.0));
    }

    private Component pesanBan(int sisa) {
        return Component.text("Kamu mati!\n", NamedTextColor.RED)
                .append(Component.text("Tunggu " + sisa + " detik lalu join lagi.", NamedTextColor.YELLOW));
    }

    // ---------------------------------------------------------------------
    // Teleport acak ke permukaan yang aman (tidak jatuh dari ketinggian)
    // ---------------------------------------------------------------------

    private void teleportAcak(Player p, int percobaan) {
        if (!p.isOnline()) {
            return;
        }
        if (percobaan > 25) {
            info(p, "Gagal menemukan lokasi aman, coba lagi nanti.");
            return;
        }
        World w = overworld();
        int maks = (int) (w.getWorldBorder().getSize() / 2.0) - 50;
        int radius = Math.max(50, Math.min(getConfig().getInt("teleport.radius", 3500), maks));
        int x = random.nextInt(radius * 2 + 1) - radius;
        int z = random.nextInt(radius * 2 + 1) - radius;

        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    Block alas = w.getBlockAt(x, y, z);
                    if (!aman(alas)) {
                        teleportAcak(p, percobaan + 1);
                        return;
                    }
                    Location tujuan = new Location(w, x + 0.5, y + 1.0, z + 0.5, p.getLocation().getYaw(), 0f);
                    lindungi(p);
                    p.teleportAsync(tujuan).thenAccept(ok -> {
                        if (ok) {
                            p.sendMessage(Component.text("Kamu diteleport ke lokasi acak!", NamedTextColor.GREEN));
                        }
                    });
                }));
    }

    private boolean aman(Block alas) {
        Material m = alas.getType();
        if (!m.isSolid() || alas.isLiquid() || TIDAK_AMAN.contains(m)) {
            return false;
        }
        Block a = alas.getRelative(0, 1, 0);
        Block b = alas.getRelative(0, 2, 0);
        return a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid();
    }

    // Perlindungan sementara: tidak kena damage jatuh / api / lava
    private void lindungi(Player p) {
        int detik = getConfig().getInt("teleport.proteksi-detik", 10);
        int tick = detik * 20;
        UUID id = p.getUniqueId();
        lindungJatuh.add(id);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, tick, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, tick, 4, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, tick, 0, false, false, false));
        Bukkit.getScheduler().runTaskLater(this, () -> lindungJatuh.remove(id), tick);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !lindungJatuh.contains(p.getUniqueId())) {
            return;
        }
        switch (e.getCause()) {
            case FALL, FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CONTACT -> e.setCancelled(true);
            default -> {
            }
        }
    }

    // =====================================================================
    // Perintah
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String nama = c.getName().toLowerCase(Locale.ROOT);

        if (nama.equals("chat")) {
            if (a.length != 1 || !(a[0].equalsIgnoreCase("on") || a[0].equalsIgnoreCase("off"))) {
                s.sendMessage(Component.text("Gunakan: /chat <on|off>", NamedTextColor.YELLOW));
                return true;
            }
            chatAktif = a[0].equalsIgnoreCase("on");
            getConfig().set("chat.aktif", chatAktif);
            saveConfig();
            Bukkit.broadcast(chatAktif
                    ? Component.text("Chat diaktifkan.", NamedTextColor.GREEN)
                    : Component.text("Chat dinonaktifkan.", NamedTextColor.RED));
            return true;
        }

        if (nama.equals("aturan")) {
            if (a.length == 1 && a[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                chatAktif = getConfig().getBoolean("chat.aktif", false);
                for (World w : Bukkit.getWorlds()) {
                    terapkanAturan(w);
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    sembunyikanNama(p);
                }
                s.sendMessage(Component.text("Konfigurasi dimuat ulang.", NamedTextColor.GREEN));
            } else {
                s.sendMessage(Component.text("Gunakan: /aturan reload", NamedTextColor.YELLOW));
            }
            return true;
        }
        return false;
    }

    // =====================================================================
    // Util
    // =====================================================================

    // Pesan ke pemain dengan jeda 2 detik supaya tidak spam
    private void info(Player p, String teks) {
        long now = System.currentTimeMillis();
        Long last = cooldownPesan.get(p.getUniqueId());
        if (last != null && now - last < 2000L) {
            return;
        }
        cooldownPesan.put(p.getUniqueId(), now);
        p.sendMessage(Component.text(teks, NamedTextColor.RED));
    }
}
