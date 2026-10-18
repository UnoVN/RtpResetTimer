package com.server.rtpreset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public class ArenaManager {

    public record SavedPoint(String worldName, double x, double y, double z, float yaw, float pitch) {
        public static SavedPoint from(Location loc) {
            return new SavedPoint(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }

        public Location toLocation() {
            World w = Bukkit.getWorld(worldName);
            return w != null ? new Location(w, x, y, z, yaw, pitch) : null;
        }

        public String formatBlock() {
            return worldName + " (" + Location.locToBlock(x) + ", " + Location.locToBlock(y) + ", " + Location.locToBlock(z) + ")";
        }
    }

    public static class Arena {
        public String name;
        public SavedPoint spawn1;
        public SavedPoint spawn2;
        public boolean inUse = false;

        public Arena(String name) {
            this.name = name;
        }

        public boolean isConfigured() {
            return spawn1 != null && spawn2 != null;
        }

        public boolean isReadyForMatch() {
            return isConfigured() && spawn1.toLocation() != null && spawn2.toLocation() != null;
        }
    }

    public static class ArenaMatch {
        public final Arena arena;
        public final RtpManager.RtpItem item;
        public final UUID p1;
        public final UUID p2;
        public final int winsRequired;
        public int currentRound = 1;
        public int p1Wins = 0;
        public int p2Wins = 0;
        public boolean processingRoundEnd = false;

        public ArenaMatch(Arena arena, RtpManager.RtpItem item, UUID p1, UUID p2, int winsRequired) {
            this.arena = arena;
            this.item = item;
            this.p1 = p1;
            this.p2 = p2;
            this.winsRequired = winsRequired;
        }

        public Location getSpawn(UUID uuid) {
            return uuid.equals(p1) ? arena.spawn1.toLocation() : arena.spawn2.toLocation();
        }
    }

    public static class SetupListHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() { return null; }
    }

    public static class SetupEditHolder implements InventoryHolder {
        public final String arenaName;
        public SetupEditHolder(String arenaName) { this.arenaName = arenaName; }
        @Override
        public @NotNull Inventory getInventory() { return null; }
    }

    private final RtpResetTimer plugin;
    private final File dbFile;
    private final File localBackupYml;
    private final File externalBackupYml;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, ArenaMatch> activeMatches = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingRespawns = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingChatName = new ConcurrentHashMap<>();

    public ArenaManager(RtpResetTimer plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.dbFile = new File(plugin.getDataFolder(), "arenas.db");
        this.localBackupYml = new File(plugin.getDataFolder(), "arenas.yml");

        // Thư mục lưu trữ độc lập ngoài RtpResetTimer để không bao giờ mất khi xóa folder plugin
        File extFolder = new File(plugin.getDataFolder().getParentFile(), "RtpArenaData");
        if (!extFolder.exists()) extFolder.mkdirs();
        this.externalBackupYml = new File(extFolder, "arenas_backup.yml");

        initDatabase();
        loadArenasFromDb();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS arenas (" +
                    "name TEXT PRIMARY KEY," +
                    "w1 TEXT, x1 REAL, y1 REAL, z1 REAL, yaw1 REAL, pitch1 REAL," +
                    "w2 TEXT, x2 REAL, y2 REAL, z2 REAL, yaw2 REAL, pitch2 REAL)");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize arenas.db: " + e.getMessage());
        }
    }

    public void loadArenasFromDb() {
        Set<String> inUseSet = new HashSet<>();
        for (Arena a : arenas.values()) {
            if (a.inUse) inUseSet.add(a.name);
        }
        arenas.clear();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM arenas")) {
            while (rs.next()) {
                String name = rs.getString("name");
                Arena arena = new Arena(name);
                arena.inUse = inUseSet.contains(name);

                String w1 = rs.getString("w1");
                if (w1 != null && !w1.isEmpty()) {
                    arena.spawn1 = new SavedPoint(w1, rs.getDouble("x1"), rs.getDouble("y1"), rs.getDouble("z1"),
                            rs.getFloat("yaw1"), rs.getFloat("pitch1"));
                }
                String w2 = rs.getString("w2");
                if (w2 != null && !w2.isEmpty()) {
                    arena.spawn2 = new SavedPoint(w2, rs.getDouble("x2"), rs.getDouble("y2"), rs.getDouble("z2"),
                            rs.getFloat("yaw2"), rs.getFloat("pitch2"));
                }
                arenas.put(name, arena);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load arenas.db: " + e.getMessage());
        }

        // Nếu DB trống (do vừa xóa folder plugin), tự động khôi phục từ bản backup độc lập
        if (arenas.isEmpty()) {
            if (localBackupYml.exists()) loadFromYamlFile(localBackupYml, inUseSet);
            if (arenas.isEmpty() && externalBackupYml.exists()) loadFromYamlFile(externalBackupYml, inUseSet);
            if (!arenas.isEmpty()) {
                for (Arena a : arenas.values()) saveArenaToDbOnly(a);
                plugin.getLogger().info("Restored " + arenas.size() + " arenas from backup storage!");
            }
        }
        syncYamlBackups();
    }

    private void loadFromYamlFile(File file, Set<String> inUseSet) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("arenas");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Arena a = new Arena(key);
            a.inUse = inUseSet.contains(key);
            a.spawn1 = readPointYml(sec.getConfigurationSection(key + ".spawn1"));
            a.spawn2 = readPointYml(sec.getConfigurationSection(key + ".spawn2"));
            arenas.put(key, a);
        }
    }

    private SavedPoint readPointYml(ConfigurationSection s) {
        if (s == null) return null;
        String w = s.getString("world");
        if (w == null || w.isEmpty()) return null;
        return new SavedPoint(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private void syncYamlBackups() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            writePointYml(yml, "arenas." + a.name + ".spawn1", a.spawn1);
            writePointYml(yml, "arenas." + a.name + ".spawn2", a.spawn2);
        }
        try {
            yml.save(localBackupYml);
            yml.save(externalBackupYml);
        } catch (IOException ignored) {}
    }

    private void writePointYml(YamlConfiguration yml, String path, SavedPoint pt) {
        if (pt == null) return;
        yml.set(path + ".world", pt.worldName());
        yml.set(path + ".x", pt.x());
        yml.set(path + ".y", pt.y());
        yml.set(path + ".z", pt.z());
        yml.set(path + ".yaw", pt.yaw());
        yml.set(path + ".pitch", pt.pitch());
    }

    private void saveArenaToDb(Arena a) {
        saveArenaToDbOnly(a);
        syncYamlBackups();
    }

    private void saveArenaToDbOnly(Arena a) {
        String sql = "INSERT OR REPLACE INTO arenas (name, w1, x1, y1, z1, yaw1, pitch1, w2, x2, y2, z2, yaw2, pitch2) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.name);
            if (a.spawn1 != null) {
                ps.setString(2, a.spawn1.worldName());
                ps.setDouble(3, a.spawn1.x()); ps.setDouble(4, a.spawn1.y()); ps.setDouble(5, a.spawn1.z());
                ps.setFloat(6, a.spawn1.yaw()); ps.setFloat(7, a.spawn1.pitch());
            } else {
                ps.setNull(2, Types.VARCHAR);
                ps.setDouble(3, 0); ps.setDouble(4, 0); ps.setDouble(5, 0); ps.setFloat(6, 0); ps.setFloat(7, 0);
            }
            if (a.spawn2 != null) {
                ps.setString(8, a.spawn2.worldName());
                ps.setDouble(9, a.spawn2.x()); ps.setDouble(10, a.spawn2.y()); ps.setDouble(11, a.spawn2.z());
                ps.setFloat(12, a.spawn2.yaw()); ps.setFloat(13, a.spawn2.pitch());
            } else {
                ps.setNull(8, Types.VARCHAR);
                ps.setDouble(9, 0); ps.setDouble(10, 0); ps.setDouble(11, 0); ps.setFloat(12, 0); ps.setFloat(13, 0);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save arena " + a.name + ": " + e.getMessage());
        }
    }

    private void deleteArenaFromDb(String name) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM arenas WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete arena " + name + ": " + e.getMessage());
        }
        syncYamlBackups();
    }

    public Collection<Arena> getAllArenas() {
        return arenas.values();
    }

    public Arena getArena(String name) {
        return name != null ? arenas.get(name) : null;
    }

    public void shutdown() {
        Location lobby = plugin.getLobbyLocation();
        for (UUID uuid : new HashSet<>(activeMatches.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
                if (lobby != null) p.teleport(lobby);
            }
        }
        for (Arena a : arenas.values()) a.inUse = false;
        activeMatches.clear();
        pendingRespawns.clear();
        awaitingChatName.clear();
    }

    public boolean isInMatch(UUID uuid) {
        return activeMatches.containsKey(uuid);
    }

    public boolean isBetweenRounds(UUID uuid) {
        ArenaMatch m = activeMatches.get(uuid);
        return m != null && m.processingRoundEnd;
    }

    public synchronized Arena reserveAvailableArena() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : arenas.values()) {
            if (!a.inUse && a.isReadyForMatch()) free.add(a);
        }
        if (free.isEmpty()) return null;
        Arena chosen = free.get(new Random().nextInt(free.size()));
        chosen.inUse = true;
        return chosen;
    }

    public synchronized Arena reserveArena(String preferredName) {
        if (preferredName != null && !preferredName.isEmpty()) {
            Arena a = arenas.get(preferredName);
            if (a != null && !a.inUse && a.isReadyForMatch()) {
                a.inUse = true;
                return a;
            }
            return null;
        }
        return reserveAvailableArena();
    }

    public void releaseArena(Arena arena) {
        if (arena != null) arena.inUse = false;
    }

    public void runArenaRegen(String arenaName) {
        String cmd = plugin.getConfig().getString("arena-settings.regen-command", "arenaregen regen {arena}")
                .replace("{arena}", arenaName);
        if (!cmd.isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    public void startArenaMatch(Player p1, Player p2, Arena arena, RtpManager.RtpItem item, int customWins) {
        int defaultWins = Math.max(1, plugin.getConfig().getInt("arena-settings.first-to-wins", 3));
        int winsReq = customWins > 0 ? customWins : defaultWins;

        ArenaMatch match = new ArenaMatch(arena, item, p1.getUniqueId(), p2.getUniqueId(), winsReq);
        activeMatches.put(p1.getUniqueId(), match);
        activeMatches.put(p2.getUniqueId(), match);

        p1.setGameMode(GameMode.SURVIVAL);
        p2.setGameMode(GameMode.SURVIVAL);

        Location loc1 = arena.spawn1.toLocation();
        Location loc2 = arena.spawn2.toLocation();
        if (loc1 != null) p1.teleport(loc1);
        if (loc2 != null) p2.teleport(loc2);
        healPlayer(p1);
        healPlayer(p2);

        Title fTitle = Title.title(
                plugin.toComponent(plugin.getMsg("rtp-messages.match-started-title", "&#a6e3a1FIGHT!")),
                plugin.toComponent("&#a6adc8First to &#89b4fa" + winsReq + " &8| &#cdd6f4" + arena.name),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))
        );
        p1.showTitle(fTitle);
        p2.showTitle(fTitle);
        if (loc1 != null) p1.playSound(loc1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        if (loc2 != null) p2.playSound(loc2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    public boolean forfeitMatch(Player forfeiter) {
        UUID uuid = forfeiter.getUniqueId();
        ArenaMatch match = activeMatches.get(uuid);
        if (match == null) return false;

        boolean isP1 = uuid.equals(match.p1);
        if (isP1) match.p2Wins = match.winsRequired;
        else match.p1Wins = match.winsRequired;

        Player p1 = Bukkit.getPlayer(match.p1);
        Player p2 = Bukkit.getPlayer(match.p2);
        Player winner = isP1 ? p2 : p1;
        String winnerName = winner != null ? winner.getName() : "Opponent";

        String ffMsg = plugin.getMsg("rtp-messages.arena-forfeit",
                "{prefix}&#f38ba8{forfeiter} forfeited the match! &#a6e3a1{winner} wins!")
                .replace("{forfeiter}", forfeiter.getName())
                .replace("{winner}", winnerName);
        if (p1 != null) p1.sendMessage(plugin.toComponent(ffMsg));
        if (p2 != null) p2.sendMessage(plugin.toComponent(ffMsg));

        finishMatch(match);
        return true;
    }

    // AUTO RESPAWN + CHUYỂN SPECTATOR + ĐẾM NGƯỢC 3S SANG HIỆP TIẾP THEO
    public void handlePlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        ArenaMatch match = activeMatches.get(dead.getUniqueId());
        if (match == null) return;

        Location deathLoc = dead.getLocation().clone();
        pendingRespawns.put(dead.getUniqueId(), deathLoc);

        // Auto Respawn sau 1 tick để đối thủ không phải chờ
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (dead.isOnline() && dead.isDead()) {
                dead.spigot().respawn();
            }
        }, 1L);

        if (match.processingRoundEnd) return;
        match.processingRoundEnd = true;

        boolean deadIsP1 = dead.getUniqueId().equals(match.p1);
        UUID winnerUuid = deadIsP1 ? match.p2 : match.p1;
        if (deadIsP1) match.p2Wins++; else match.p1Wins++;

        Player p1 = Bukkit.getPlayer(match.p1);
        Player p2 = Bukkit.getPlayer(match.p2);
        Player winner = Bukkit.getPlayer(winnerUuid);
        String winnerName = winner != null ? winner.getName() : "Opponent";
        String p1Name = p1 != null ? p1.getName() : "P1";
        String p2Name = p2 != null ? p2.getName() : "P2";

        int finishedRound = match.currentRound;
        boolean reachedWinTarget = (match.p1Wins >= match.winsRequired || match.p2Wins >= match.winsRequired);

        if (!reachedWinTarget) {
            match.currentRound++;
            String roundMsg = plugin.getMsg("rtp-messages.arena-round-end",
                    "{prefix}&#cdd6f4{winner} &#a6adc8won Round &#89b4fa{round} &#a6adc8(&#a6e3a1{p1_wins}&#a6adc8-&#f38ba8{p2_wins}&#a6adc8, First to &#89b4fa{first_to}&#a6adc8)!")
                    .replace("{winner}", winnerName)
                    .replace("{round}", String.valueOf(finishedRound))
                    .replace("{first_to}", String.valueOf(match.winsRequired))
                    .replace("{p1_wins}", String.valueOf(match.p1Wins))
                    .replace("{p2_wins}", String.valueOf(match.p2Wins));

            if (p1 != null) p1.sendMessage(plugin.toComponent(roundMsg));
            if (p2 != null) p2.sendMessage(plugin.toComponent(roundMsg));

            int cdSeconds = Math.max(1, plugin.getConfig().getInt("arena-settings.round-countdown-seconds", 3));

            new BukkitRunnable() {
                int timer = cdSeconds;

                @Override
                public void run() {
                    if (!activeMatches.containsKey(match.p1)) {
                        cancel();
                        return;
                    }

                    Player curP1 = Bukkit.getPlayer(match.p1);
                    Player curP2 = Bukkit.getPlayer(match.p2);

                    if (timer > 0) {
                        if (timer == 1) {
                            runArenaRegen(match.arena.name);
                        }
                        Component tMain = getRoundCircleEmoji(timer);
                        String subRaw = plugin.getMsg("rtp-messages.arena-next-round-countdown-sub",
                                "&#a6adc8Next round in &#fab387{time}s &8(&#a6e3a1{p1_wins}&#a6adc8-&#f38ba8{p2_wins}&8)")
                                .replace("{time}", String.valueOf(timer))
                                .replace("{p1_wins}", String.valueOf(match.p1Wins))
                                .replace("{p2_wins}", String.valueOf(match.p2Wins));
                        Title cdTitle = Title.title(tMain, plugin.toComponent(subRaw),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(200)));

                        if (curP1 != null && curP1.isOnline()) {
                            curP1.showTitle(cdTitle);
                            curP1.playSound(curP1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                        }
                        if (curP2 != null && curP2.isOnline()) {
                            curP2.showTitle(cdTitle);
                            curP2.playSound(curP2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                        }
                        timer--;
                    } else {
                        cancel();
                        match.processingRoundEnd = false;

                        Location s1 = match.arena.spawn1.toLocation();
                        Location s2 = match.arena.spawn2.toLocation();

                        if (curP1 != null && curP1.isOnline()) {
                            if (curP1.isDead()) curP1.spigot().respawn();
                            curP1.setGameMode(GameMode.SURVIVAL);
                            if (s1 != null) curP1.teleport(s1);
                            healPlayer(curP1);
                        }
                        if (curP2 != null && curP2.isOnline()) {
                            if (curP2.isDead()) curP2.spigot().respawn();
                            curP2.setGameMode(GameMode.SURVIVAL);
                            if (s2 != null) curP2.teleport(s2);
                            healPlayer(curP2);
                        }

                        String rTitle = plugin.getMsg("rtp-messages.arena-round-title", "&#89b4faROUND {round}")
                                .replace("{round}", String.valueOf(match.currentRound));
                        String rSub = plugin.getMsg("rtp-messages.arena-round-subtitle", "&#cdd6f4{p1} &#a6e3a1{p1_wins} &8- &#f38ba8{p2_wins} &#cdd6f4{p2}")
                                .replace("{p1}", p1Name).replace("{p2}", p2Name)
                                .replace("{p1_wins}", String.valueOf(match.p1Wins))
                                .replace("{p2_wins}", String.valueOf(match.p2Wins));

                        Title nextTitle = Title.title(plugin.toComponent(rTitle), plugin.toComponent(rSub),
                                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300)));
                        if (curP1 != null && curP1.isOnline()) {
                            curP1.showTitle(nextTitle);
                            curP1.playSound(curP1.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                        }
                        if (curP2 != null && curP2.isOnline()) {
                            curP2.showTitle(nextTitle);
                            curP2.playSound(curP2.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                        }
                    }
                }
            }.runTaskTimer(plugin, 0L, 20L);

        } else {
            finishMatch(match);
        }
    }

    private Component getRoundCircleEmoji(int count) {
        String cfg = plugin.getConfig().getString("rtpq-menu.countdown-titles." + count);
        if (cfg != null && !cfg.isEmpty()) return plugin.toComponent(cfg);
        return switch (count) {
            case 3 -> plugin.toComponent("&#fab387❸");
            case 2 -> plugin.toComponent("&#fab387❷");
            case 1 -> plugin.toComponent("&#a6e3a1❶");
            default -> plugin.toComponent("&#fab387" + count);
        };
    }

    public void handlePlayerRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        Location target = pendingRespawns.remove(uuid);
        ArenaMatch match = activeMatches.get(uuid);

        if (target == null && match != null) {
            target = match.getSpawn(uuid);
        }

        if (target != null) {
            event.setRespawnLocation(target);
            Location finalTarget = target;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.teleport(finalTarget);
                if (activeMatches.containsKey(uuid)) {
                    p.setGameMode(GameMode.SPECTATOR);
                } else {
                    p.setGameMode(GameMode.SURVIVAL);
                    healPlayer(p);
                }
            }, 1L);
        }
    }

    public void handlePlayerQuit(Player quitter) {
        UUID uuid = quitter.getUniqueId();
        awaitingChatName.remove(uuid);
        pendingRespawns.remove(uuid);
        quitter.setGameMode(GameMode.SURVIVAL);
        if (activeMatches.containsKey(uuid)) {
            forfeitMatch(quitter);
        }
    }

    private void finishMatch(ArenaMatch match) {
        activeMatches.remove(match.p1);
        activeMatches.remove(match.p2);

        Player p1 = Bukkit.getPlayer(match.p1);
        Player p2 = Bukkit.getPlayer(match.p2);
        boolean p1Won = match.p1Wins >= match.p2Wins;
        String finalWinner = p1Won ? (p1 != null ? p1.getName() : "Player 1") : (p2 != null ? p2.getName() : "Player 2");
        int wWins = Math.max(match.p1Wins, match.p2Wins);
        int lWins = Math.min(match.p1Wins, match.p2Wins);

        for (String line : plugin.getConfig().getStringList("rtp-messages.arena-match-end")) {
            String msg = line.replace("{winner}", finalWinner)
                    .replace("{w_wins}", String.valueOf(wWins))
                    .replace("{l_wins}", String.valueOf(lWins))
                    .replace("{arena}", match.arena.name);
            if (p1 != null) p1.sendMessage(plugin.toComponent(msg));
            if (p2 != null) p2.sendMessage(plugin.toComponent(msg));
        }

        Location lobby = plugin.getLobbyLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p1 != null && p1.isOnline()) {
                if (p1.isDead()) p1.spigot().respawn();
                p1.setGameMode(GameMode.SURVIVAL);
                if (lobby != null) p1.teleport(lobby);
                healPlayer(p1);
            }
            if (p2 != null && p2.isOnline()) {
                if (p2.isDead()) p2.spigot().respawn();
                p2.setGameMode(GameMode.SURVIVAL);
                if (lobby != null) p2.teleport(lobby);
                healPlayer(p2);
            }
            runArenaRegen(match.arena.name);
            match.arena.inUse = false;
        }, 30L);
    }

    private void healPlayer(Player p) {
        p.setFireTicks(0);
        AttributeInstance maxHp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        p.setHealth(maxHp != null ? maxHp.getValue() : 20.0);
        p.setFoodLevel(20);
    }

    // ==================== HỆ THỐNG GUI /SETUP ====================
    public void openSetupListGui(Player admin) {
        Inventory inv = Bukkit.createInventory(new SetupListHolder(), 27, plugin.toComponent("&#cdd6f4&lARENA SETUP"));
        ItemStack glass = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        int slot = 0;
        for (Arena a : arenas.values()) {
            if (slot >= 18) break;
            String s1 = a.spawn1 != null ? "&#a6e3a1" + a.spawn1.formatBlock() : "&#f38ba8Not Set";
            String s2 = a.spawn2 != null ? "&#a6e3a1" + a.spawn2.formatBlock() : "&#f38ba8Not Set";
            String st = a.inUse ? "&#fab387In Match" : (a.isConfigured() ? "&#a6e3a1Saved" : "&#f38ba8Incomplete");

            inv.setItem(slot++, createItem(Material.TNT_MINECART, "&#89b4fa&l" + a.name, Arrays.asList(
                    "&#a6adc8Spawn 1: " + s1,
                    "&#a6adc8Spawn 2: " + s2,
                    "&#a6adc8Status: " + st,
                    "",
                    "<glyph:left_click> &#a6e3a1Click to edit arena",
                    "<glyph:right_click> &#f38ba8Right-click to delete"
            )));
        }

        inv.setItem(22, createItem(Material.LIME_CONCRETE, "&#a6e3a1&lCREATE NEW ARENA", Arrays.asList(
                "&#a6adc8Create a new Boxcart Arena",
                "",
                "<glyph:left_click> &#a6e3a1Click to create"
        )));
        admin.openInventory(inv);
    }

    public void openSetupEditGui(Player admin, Arena arena) {
        Inventory inv = Bukkit.createInventory(new SetupEditHolder(arena.name), 27,
                plugin.toComponent("&#cdd6f4&lEDIT ARENA: &#89b4fa" + arena.name));
        ItemStack glass = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        String s1 = arena.spawn1 != null ? "&#a6e3a1" + arena.spawn1.formatBlock() : "&#f38ba8Not Set";
        String s2 = arena.spawn2 != null ? "&#a6e3a1" + arena.spawn2.formatBlock() : "&#f38ba8Not Set";

        inv.setItem(11, createItem(Material.NAME_TAG, "&#89b4fa&lSET ARENA NAME", Arrays.asList(
                "&#a6adc8Current Name: &#cdd6f4" + arena.name,
                "&#a6adc8Regen Cmd: &#89b4faarenaregen regen " + arena.name,
                "",
                "<glyph:left_click> &#a6e3a1Click to type name in chat"
        )));

        inv.setItem(13, createItem(Material.ENDER_PEARL, "&#a6e3a1&lSPAWN LOCATION 1", Arrays.asList(
                "&#a6adc8Current: " + s1,
                "",
                "<glyph:left_click> &#a6e3a1Click to set to your position"
        )));

        inv.setItem(15, createItem(Material.ENDER_PEARL, "&#fab387&lSPAWN LOCATION 2", Arrays.asList(
                "&#a6adc8Current: " + s2,
                "",
                "<glyph:left_click> &#a6e3a1Click to set to your position"
        )));

        inv.setItem(22, createItem(Material.ARROW, "&#a6adc8&lBACK TO ARENA LIST", Collections.singletonList(
                "<glyph:left_click> &#a6adc8Return to /setup"
        )));

        admin.openInventory(inv);
    }

    public void handleSetupListClick(Player admin, int slot, ClickType click) {
        if (slot == 22) {
            int idx = arenas.size() + 1;
            while (arenas.containsKey("arena_" + idx)) idx++;
            Arena created = new Arena("arena_" + idx);
            arenas.put(created.name, created);
            saveArenaToDb(created);
            admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            openSetupEditGui(admin, created);
            return;
        }

        List<Arena> list = new ArrayList<>(arenas.values());
        if (slot >= 0 && slot < list.size() && slot < 18) {
            Arena target = list.get(slot);
            if (click.isRightClick()) {
                arenas.remove(target.name);
                deleteArenaFromDb(target.name);
                admin.playSound(admin.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                openSetupListGui(admin);
            } else {
                admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                openSetupEditGui(admin, target);
            }
        }
    }

    public void handleSetupEditClick(Player admin, SetupEditHolder holder, int slot) {
        Arena arena = arenas.get(holder.arenaName);
        if (arena == null) {
            openSetupListGui(admin);
            return;
        }

        if (slot == 11) {
            admin.closeInventory();
            awaitingChatName.put(admin.getUniqueId(), arena.name);
            admin.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#cdd6f4Type the new arena name in chat (or type &#f38ba8cancel &#cdd6f4to abort):"));
        } else if (slot == 13) {
            arena.spawn1 = SavedPoint.from(admin.getLocation());
            saveArenaToDb(arena);
            admin.playSound(admin.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            admin.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#a6e3a1Saved Spawn 1 for &#cdd6f4" + arena.name + "&#a6e3a1!"));
            openSetupEditGui(admin, arena);
        } else if (slot == 15) {
            arena.spawn2 = SavedPoint.from(admin.getLocation());
            saveArenaToDb(arena);
            admin.playSound(admin.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            admin.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#a6e3a1Saved Spawn 2 for &#cdd6f4" + arena.name + "&#a6e3a1!"));
            openSetupEditGui(admin, arena);
        } else if (slot == 22) {
            openSetupListGui(admin);
        }
    }

    public void handleChatInput(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String oldName = awaitingChatName.remove(player.getUniqueId());
        if (oldName == null) return;

        event.setCancelled(true);
        String input = event.getMessage().trim().split("\\s+")[0];

        Bukkit.getScheduler().runTask(plugin, () -> {
            Arena arena = arenas.get(oldName);
            if (arena == null) return;

            if (input.equalsIgnoreCase("cancel")) {
                openSetupEditGui(player, arena);
                return;
            }

            if (arenas.containsKey(input) && !input.equalsIgnoreCase(oldName)) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                        "&#f38ba8An arena with that name already exists!"));
                openSetupEditGui(player, arena);
                return;
            }

            arenas.remove(oldName);
            deleteArenaFromDb(oldName);
            arena.name = input;
            arenas.put(input, arena);
            saveArenaToDb(arena);

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            player.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#a6e3a1Arena name saved as &#cdd6f4" + input + "&#a6e3a1!"));
            openSetupEditGui(player, arena);
        });
    }

    private ItemStack createItem(Material mat, String name, List<String> lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.toComponent(name).decoration(TextDecoration.ITALIC, false));
            List<Component> cLore = new ArrayList<>();
            for (String l : lore) cLore.add(plugin.toComponent(l).decoration(TextDecoration.ITALIC, false));
            meta.lore(cLore);
            is.setItemMeta(meta);
        }
        return is;
    }
}
