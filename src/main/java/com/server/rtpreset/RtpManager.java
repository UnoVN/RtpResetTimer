package com.server.rtpreset;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RtpManager {

    public static class RtpItem {
        public String key;
        public int slot;
        public Material material;
        public String name;
        public String modeDisplay;
        public List<String> lore;
        public String worldName;
        public int minX, maxX, minZ, maxZ;
    }

    private final RtpResetTimer plugin;
    private final Map<String, RtpItem> rtpItems = new LinkedHashMap<>();
    private final Map<String, RtpItem> rtpqItems = new LinkedHashMap<>();
    private final Map<String, List<UUID>> queueMap = new ConcurrentHashMap<>();

    public RtpManager(RtpResetTimer plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        rtpItems.clear();
        rtpqItems.clear();
        queueMap.clear();

        ConfigurationSection rtpSec = plugin.getConfig().getConfigurationSection("rtp-menu.items");
        if (rtpSec != null) {
            for (String key : rtpSec.getKeys(false)) {
                RtpItem item = parseItem(rtpSec.getConfigurationSection(key), key);
                rtpItems.put(key, item);
            }
        }

        ConfigurationSection rtpqSec = plugin.getConfig().getConfigurationSection("rtpq-menu.items");
        if (rtpqSec != null) {
            for (String key : rtpqSec.getKeys(false)) {
                RtpItem item = parseItem(rtpqSec.getConfigurationSection(key), key);
                rtpqItems.put(key, item);
                queueMap.put(key, new ArrayList<>());
            }
        }
    }

    private RtpItem parseItem(ConfigurationSection sec, String key) {
        RtpItem item = new RtpItem();
        item.key = key;
        item.slot = sec.getInt("slot", 0);
        String matName = sec.getString("material", "STONE");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        item.material = mat != null ? mat : Material.STONE;
        item.name = sec.getString("name", key);
        item.modeDisplay = sec.getString("mode-display", key.toUpperCase());
        item.lore = sec.getStringList("lore");
        item.worldName = sec.getString("world", "world");
        item.minX = sec.getInt("min-x", 0);
        item.maxX = sec.getInt("max-x", 100);
        item.minZ = sec.getInt("min-z", 0);
        item.maxZ = sec.getInt("max-z", 100);
        return item;
    }

    public void openRtpGui(Player player) {
        String title = plugin.color(plugin.getConfig().getString("rtp-menu.title", "RTP"));
        int size = plugin.getConfig().getInt("rtp-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpGuiHolder(), size, title);

        for (RtpItem item : rtpItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(plugin.color(item.name));
                List<String> coloredLore = new ArrayList<>();
                for (String l : item.lore) {
                    coloredLore.add(plugin.color(l));
                }
                meta.setLore(coloredLore);
                is.setItemMeta(meta);
            }
            if (item.slot < size) {
                inv.setItem(item.slot, is);
            }
        }
        player.openInventory(inv);
    }

    public void openRtpQueueGui(Player player) {
        String title = plugin.color(plugin.getConfig().getString("rtpq-menu.title", "RTP QUEUE"));
        int size = plugin.getConfig().getInt("rtpq-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpQueueGuiHolder(), size, title);

        for (RtpItem item : rtpqItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(plugin.color(item.name));
                List<UUID> q = queueMap.getOrDefault(item.key, Collections.emptyList());
                List<String> coloredLore = new ArrayList<>();
                for (String l : item.lore) {
                    coloredLore.add(plugin.color(l.replace("{current_queue}", String.valueOf(q.size()))));
                }
                meta.setLore(coloredLore);
                is.setItemMeta(meta);
            }
            if (item.slot < size) {
                inv.setItem(item.slot, is);
            }
        }
        player.openInventory(inv);
    }

    public void handleRtpClick(Player player, int slot) {
        for (RtpItem item : rtpItems.values()) {
            if (item.slot == slot) {
                player.closeInventory();
                executeRtp(player, item);
                return;
            }
        }
    }

    public void handleQueueClick(Player player, int slot) {
        for (RtpItem item : rtpqItems.values()) {
            if (item.slot == slot) {
                player.closeInventory();
                toggleQueue(player, item);
                return;
            }
        }
    }

    public void removePlayerFromAllQueues(UUID uuid) {
        for (List<UUID> list : queueMap.values()) {
            list.remove(uuid);
        }
    }

    private void toggleQueue(Player player, RtpItem item) {
        List<UUID> q = queueMap.get(item.key);
        if (q == null) return;

        if (q.contains(player.getUniqueId())) {
            q.remove(player.getUniqueId());
            String msg = plugin.getMsg("rtp-messages.left-queue", "{prefix}&#f38ba8You left the queue!")
                    .replace("{display_name}", item.name);
            player.sendMessage(plugin.color(msg));
            return;
        }

        for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
            if (entry.getValue().contains(player.getUniqueId())) {
                player.sendMessage(plugin.color(plugin.getMsg("rtp-messages.already-in-other-queue", "{prefix}&#f38ba8You are already in another queue!")));
                return;
            }
        }

        q.add(player.getUniqueId());
        String joinMsg = plugin.getMsg("rtp-messages.joined-queue", "{prefix}&#89dcebJoined queue {display_name} ({current}/2)!")
                .replace("{display_name}", item.name)
                .replace("{current}", String.valueOf(q.size()));
        player.sendMessage(plugin.color(joinMsg));

        if (q.size() == 1) {
            String bcast = plugin.getMsg("rtp-messages.queue-broadcast", "{prefix}&#89b4faPlayer {player} is looking for a match in {display_name}! ({current}/2)")
                    .replace("{player}", player.getName())
                    .replace("{display_name}", item.name)
                    .replace("{current}", "1");
            Bukkit.broadcastMessage(plugin.color(bcast));
        }

        if (q.size() >= 2) {
            UUID u1 = q.remove(0);
            UUID u2 = q.remove(0);
            Player p1 = Bukkit.getPlayer(u1);
            Player p2 = Bukkit.getPlayer(u2);

            if (p1 != null && p2 != null && p1.isOnline() && p2.isOnline()) {
                startMatchmaking(p1, p2, item);
            } else {
                if (p1 != null && p1.isOnline()) q.add(p1.getUniqueId());
                if (p2 != null && p2.isOnline()) q.add(p2.getUniqueId());
            }
        }
    }

    private void startMatchmaking(Player p1, Player p2, RtpItem item) {
        p1.closeInventory();
        p2.closeInventory();

        List<String> broadcastLines = plugin.getConfig().getStringList("rtp-messages.match-found-broadcast");
        if (!broadcastLines.isEmpty()) {
            for (String line : broadcastLines) {
                String formatted = line.replace("{player1}", p1.getName())
                        .replace("{player2}", p2.getName())
                        .replace("{mode}", item.modeDisplay)
                        .replace("{world}", item.worldName);
                Bukkit.broadcastMessage(plugin.color(formatted));
            }
        }

        int countdown = plugin.getConfig().getInt("rtpq-menu.countdown-seconds", 5);

        String p1Prefix = getPlayerPrefix(p1);
        String p2Prefix = getPlayerPrefix(p2);

        new BukkitRunnable() {
            int timer = countdown;

            @Override
            public void run() {
                if (!p1.isOnline() || !p2.isOnline()) {
                    cancel();
                    if (p1.isOnline()) p1.sendMessage(plugin.color("<glyph:warning_blue> &#f38ba8Opponent disconnected!"));
                    if (p2.isOnline()) p2.sendMessage(plugin.color("<glyph:warning_blue> &#f38ba8Opponent disconnected!"));
                    return;
                }

                if (timer > 0) {
                    String emojiTitle = getCircleEmoji(timer);
                    String sub = plugin.color(plugin.getMsg("rtp-messages.match-countdown-subtitle", "&#cdd6f4Prepare for battle..."));

                    p1.sendTitle(emojiTitle, sub, 0, 25, 5);
                    p2.sendTitle(emojiTitle, sub, 0, 25, 5);

                    sendActionbar(p1, plugin.getMsg("rtp-messages.match-actionbar", "Opponent: {opponent_prefix}{opponent_name}")
                            .replace("{opponent_prefix}", p2Prefix)
                            .replace("{opponent_name}", p2.getName())
                            .replace("{time}", String.valueOf(timer)));

                    sendActionbar(p2, plugin.getMsg("rtp-messages.match-actionbar", "Opponent: {opponent_prefix}{opponent_name}")
                            .replace("{opponent_prefix}", p1Prefix)
                            .replace("{opponent_name}", p1.getName())
                            .replace("{time}", String.valueOf(timer)));

                    p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    timer--;
                } else {
                    cancel();
                    teleportMatch(p1, p2, item);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private String getCircleEmoji(int count) {
        return switch (count) {
            case 5 -> plugin.color("&#f38ba8&l❺");
            case 4 -> plugin.color("&#f38ba8&l❹");
            case 3 -> plugin.color("&#fab387&l❸");
            case 2 -> plugin.color("&#fab387&l❷");
            case 1 -> plugin.color("&#a6e3a1&l❶");
            default -> plugin.color("&#fab387&l" + count);
        };
    }

    private void teleportMatch(Player p1, Player p2, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null) {
            p1.sendMessage(plugin.color("<glyph:warning_blue> &#f38ba8World " + item.worldName + " is not loaded!"));
            p2.sendMessage(plugin.color("<glyph:warning_blue> &#f38ba8World " + item.worldName + " is not loaded!"));
            return;
        }

        int gap = plugin.getConfig().getInt("rtpq-menu.player-gap", 8);
        Set<UUID> ignoredPlayers = new HashSet<>(Arrays.asList(p1.getUniqueId(), p2.getUniqueId()));

        Location loc1 = null;
        Location loc2 = null;

        int maxAttempts = plugin.getConfig().getInt("rtp-settings.max-search-attempts", 60);

        for (int i = 0; i < maxAttempts; i++) {
            Location candidate1 = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, ignoredPlayers);
            if (candidate1 == null) continue;

            int[][] directions = {{gap, 0}, {-gap, 0}, {0, gap}, {0, -gap}};
            for (int[] dir : directions) {
                int targetX = candidate1.getBlockX() + dir[0];
                int targetZ = candidate1.getBlockZ() + dir[1];
                int targetY = world.getHighestBlockYAt(targetX, targetZ);

                if (Math.abs(targetY - (candidate1.getBlockY() - 1)) <= 1) {
                    if (isTerrainFlatAndSafe(world, targetX, targetZ, targetY)) {
                        loc1 = candidate1;
                        loc2 = new Location(world, targetX + 0.5, targetY + 1.0, targetZ + 0.5);
                        break;
                    }
                }
            }
            if (loc1 != null && loc2 != null) break;
        }

        if (loc1 == null || loc2 == null) {
            p1.sendMessage(plugin.color(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location!")));
            p2.sendMessage(plugin.color(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location!")));
            return;
        }

        Vector to2 = loc2.toVector().subtract(loc1.toVector()).normalize();
        Vector to1 = loc1.toVector().subtract(loc2.toVector()).normalize();
        loc1.setDirection(to2);
        loc2.setDirection(to1);

        p1.teleport(loc1);
        p2.teleport(loc2);

        String fightTitle = plugin.color(plugin.getMsg("rtp-messages.match-started-title", "&#a6e3a1&lFIGHT!"));
        String fightSub = plugin.color(plugin.getMsg("rtp-messages.match-started-subtitle", "&#cdd6f4Good luck!"));
        p1.sendTitle(fightTitle, fightSub, 5, 40, 10);
        p2.sendTitle(fightTitle, fightSub, 5, 40, 10);

        p1.playSound(loc1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p2.playSound(loc2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void executeRtp(Player player, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null) {
            player.sendMessage(plugin.color("<glyph:warning_blue> &#f38ba8World " + item.worldName + " is not loaded!"));
            return;
        }

        player.sendMessage(plugin.color(plugin.getMsg("rtp-messages.teleporting-rtp", "&#89dcebFinding safe location...")
                .replace("{display_name}", item.name)));

        Set<UUID> ignoredPlayers = Collections.singleton(player.getUniqueId());
        Location loc = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, ignoredPlayers);

        if (loc != null) {
            player.teleport(loc);
            player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            String successMsg = plugin.getMsg("rtp-messages.teleport-success", "&#a6e3a1Teleported successfully!")
                    .replace("{x}", String.valueOf(loc.getBlockX()))
                    .replace("{y}", String.valueOf(loc.getBlockY()))
                    .replace("{z}", String.valueOf(loc.getBlockZ()));
            player.sendMessage(plugin.color(successMsg));
        } else {
            player.sendMessage(plugin.color(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location!")));
        }
    }

    private Location findSafeLocation(World world, int minX, int maxX, int minZ, int maxZ, Set<UUID> ignoredPlayers) {
        int x1 = Math.min(minX, maxX);
        int x2 = Math.max(minX, maxX);
        int z1 = Math.min(minZ, maxZ);
        int z2 = Math.max(minZ, maxZ);

        int maxAttempts = plugin.getConfig().getInt("rtp-settings.max-search-attempts", 60);
        double minPlayerDist = plugin.getConfig().getDouble("rtp-settings.min-player-distance", 50.0);
        double minPlayerDistSq = minPlayerDist * minPlayerDist;

        for (int i = 0; i < maxAttempts; i++) {
            int rx = ThreadLocalRandom.current().nextInt(x1, x2 + 1);
            int rz = ThreadLocalRandom.current().nextInt(z1, z2 + 1);
            int highestY = world.getHighestBlockYAt(rx, rz);

            if (highestY <= world.getMinHeight() + 5 || highestY >= world.getMaxHeight() - 5) continue;

            Location candidateLoc = new Location(world, rx + 0.5, highestY + 1.0, rz + 0.5);
            boolean nearPlayer = false;
            for (Player p : world.getPlayers()) {
                if (ignoredPlayers != null && ignoredPlayers.contains(p.getUniqueId())) continue;
                if (p.getLocation().distanceSquared(candidateLoc) < minPlayerDistSq) {
                    nearPlayer = true;
                    break;
                }
            }
            if (nearPlayer) continue;

            if (isTerrainFlatAndSafe(world, rx, rz, highestY)) {
                return candidateLoc;
            }
        }
        return null;
    }

    private boolean isTerrainFlatAndSafe(World world, int centerX, int centerZ, int baseY) {
        int maxSlope = plugin.getConfig().getInt("rtp-settings.max-terrain-slope", 2);
        int minY = baseY;
        int maxY = baseY;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int checkX = centerX + dx;
                int checkZ = centerZ + dz;
                int surfaceY = world.getHighestBlockYAt(checkX, checkZ);

                if (surfaceY < minY) minY = surfaceY;
                if (surfaceY > maxY) maxY = surfaceY;

                if ((maxY - minY) > maxSlope) {
                    return false;
                }

                Material mat = world.getBlockAt(checkX, surfaceY, checkZ).getType();
                if (!isSafeBlock(mat)) {
                    return false;
                }
            }
        }

        Material feet = world.getBlockAt(centerX, baseY + 1, centerZ).getType();
        Material head = world.getBlockAt(centerX, baseY + 2, centerZ).getType();
        return (feet.isAir() && head.isAir());
    }

    private boolean isSafeBlock(Material mat) {
        return mat.isSolid()
                && mat != Material.LAVA
                && mat != Material.WATER
                && mat != Material.MAGMA_BLOCK
                && mat != Material.FIRE
                && mat != Material.SOUL_FIRE
                && mat != Material.CACTUS
                && mat != Material.WITHER_ROSE
                && mat != Material.BEDROCK
                && mat != Material.OBSIDIAN
                && mat != Material.RESPAWN_ANCHOR
                && mat != Material.COBWEB;
    }

    private void sendActionbar(Player player, String rawText) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(plugin.color(rawText)));
    }

    private String getPlayerPrefix(Player player) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            String p = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%");
            if (!p.equals("%luckperms_prefix%")) return p;
        }
        return "";
    }
                                              }
                                                    
