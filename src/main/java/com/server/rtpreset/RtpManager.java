package com.server.rtpreset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RtpManager {

    public static class RtpItem {
        public String key, name, icon, modeDisplay, mapDisplay, worldName;
        public int slot, minX, maxX, minZ, maxZ;
        public Material material;
        public List<String> lore;
    }

    private final RtpResetTimer plugin;
    private final Map<String, RtpItem> rtpItems = new LinkedHashMap<>();
    private final Map<String, RtpItem> rtpqItems = new LinkedHashMap<>();
    private final Map<String, List<UUID>> queueMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queueJoinTime = new ConcurrentHashMap<>();
    private final Set<UUID> inCountdown = ConcurrentHashMap.newKeySet();
    private BukkitTask queueMonitorTask;

    public RtpManager(RtpResetTimer plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void shutdown() {
        if (queueMonitorTask != null) {
            queueMonitorTask.cancel();
            queueMonitorTask = null;
        }
        queueMap.clear();
        queueJoinTime.clear();
        inCountdown.clear();
    }

    public void loadConfig() {
        shutdown();
        rtpItems.clear();
        rtpqItems.clear();

        ConfigurationSection rtpSec = plugin.getConfig().getConfigurationSection("rtp-menu.items");
        if (rtpSec != null) {
            for (String key : rtpSec.getKeys(false)) {
                ConfigurationSection sub = rtpSec.getConfigurationSection(key);
                if (sub != null) rtpItems.put(key, parseItem(sub, key));
            }
        }

        ConfigurationSection rtpqSec = plugin.getConfig().getConfigurationSection("rtpq-menu.items");
        if (rtpqSec != null) {
            for (String key : rtpqSec.getKeys(false)) {
                ConfigurationSection sub = rtpqSec.getConfigurationSection(key);
                if (sub != null) {
                    rtpqItems.put(key, parseItem(sub, key));
                    queueMap.put(key, new ArrayList<>());
                }
            }
        }
        startQueueMonitor();
    }

    private RtpItem parseItem(ConfigurationSection sec, String key) {
        RtpItem item = new RtpItem();
        item.key = key;
        item.slot = sec.getInt("slot", 0);
        Material mat = Material.matchMaterial(sec.getString("material", "STONE").toUpperCase());
        item.material = mat != null ? mat : Material.STONE;
        item.icon = sec.getString("icon", "<white><sprite:items:item/crossbow_standby></white>");
        item.name = sec.getString("name", key);
        item.modeDisplay = sec.getString("mode-display", key.toUpperCase());
        item.mapDisplay = sec.getString("map-display", "RTP Desert");
        item.lore = sec.getStringList("lore");
        item.worldName = sec.getString("world", "rtpdesert");
        item.minX = sec.getInt("min-x", 0);
        item.maxX = sec.getInt("max-x", 100);
        item.minZ = sec.getInt("min-z", 0);
        item.maxZ = sec.getInt("max-z", 100);
        return item;
    }

    private void startQueueMonitor() {
        queueMonitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                boolean timeoutEnabled = plugin.getConfig().getBoolean("rtpq-menu.queue-timeout.enabled", true);
                int maxTimeoutSec = plugin.getConfig().getInt("rtpq-menu.queue-timeout.timeout-seconds", 120);
                long now = System.currentTimeMillis();

                for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
                    RtpItem item = rtpqItems.get(entry.getKey());
                    if (item == null) continue;
                    List<UUID> list = entry.getValue();

                    Iterator<UUID> it = list.iterator();
                    while (it.hasNext()) {
                        UUID uuid = it.next();
                        Player p = Bukkit.getPlayer(uuid);
                        if (p == null || !p.isOnline()) {
                            it.remove();
                            queueJoinTime.remove(uuid);
                            continue;
                        }

                        long joinedAt = queueJoinTime.getOrDefault(uuid, now);
                        int elapsedSec = (int) ((now - joinedAt) / 1000L);

                        if (timeoutEnabled && elapsedSec >= maxTimeoutSec) {
                            it.remove();
                            queueJoinTime.remove(uuid);
                            String timeoutFmt = String.format("%02d:%02d", maxTimeoutSec / 60, maxTimeoutSec % 60);
                            p.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-timeout",
                                    "{prefix}&#f38ba8You were removed from the {icon} &#fab387{mode} &#f38ba8queue!"), item).replace("{timeout}", timeoutFmt)));
                            p.sendActionBar(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-timeout-actionbar",
                                    "&#f38ba8&lQUEUE TIMED OUT! &8({icon} &#fab387{mode}&8)"), item)));
                            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                            continue;
                        }

                        String elapsedStr = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60);
                        String ab = applyPlaceholders(plugin.getMsg("rtp-messages.queue-waiting-actionbar",
                                "&#89dcebQueue: {icon} &#fab387{mode} &8| &#89dcebElapsed: &#a6e3a1{elapsed}"), item)
                                .replace("{elapsed}", elapsedStr);
                        p.sendActionBar(plugin.toComponent(ab));
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private String applyPlaceholders(String text, RtpItem item) {
        if (text == null || item == null) return "";
        return text.replace("{display_name}", item.name)
                   .replace("{mode}", item.modeDisplay)
                   .replace("{map}", item.mapDisplay)
                   .replace("{world}", item.worldName)
                   .replace("{icon}", item.icon);
    }

    private void applyFiller(Inventory inv, String menuPath) {
        if (!plugin.getConfig().getBoolean(menuPath + ".filler.enabled", true)) return;
        Material mat = Material.matchMaterial(plugin.getConfig().getString(menuPath + ".filler.material", "BLACK_STAINED_GLASS_PANE").toUpperCase());
        ItemStack filler = new ItemStack(mat != null ? mat : Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.toComponent(plugin.getConfig().getString(menuPath + ".filler.name", " ")).decoration(TextDecoration.ITALIC, false));
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    public void openRtpGui(Player player) {
        Component title = plugin.toComponent(plugin.getConfig().getString("rtp-menu.title", "&#89b4fa&lRANDOM TELEPORT"));
        int size = plugin.getConfig().getInt("rtp-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpGuiHolder(), size, title);
        applyFiller(inv, "rtp-menu");

        for (RtpItem item : rtpItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                meta.displayName(plugin.toComponent(applyPlaceholders(item.name, item)).decoration(TextDecoration.ITALIC, false));
                List<Component> coloredLore = new ArrayList<>();
                for (String l : item.lore) {
                    coloredLore.add(plugin.toComponent(applyPlaceholders(l, item)).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(coloredLore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
        }
        player.openInventory(inv);
    }

    public void openRtpQueueGui(Player player) {
        Component title = plugin.toComponent(plugin.getConfig().getString("rtpq-menu.title", "&#fab387&l1V1 BATTLE QUEUE"));
        int size = plugin.getConfig().getInt("rtpq-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpQueueGuiHolder(), size, title);
        applyFiller(inv, "rtpq-menu");

        String actionJoin = plugin.getConfig().getString("rtpq-menu.action-join", "<glyph:left_click> &#a6e3a1Click to join queue");
        String actionLeave = plugin.getConfig().getString("rtpq-menu.action-leave", "<glyph:left_click> &#f38ba8Click to leave queue");

        for (RtpItem item : rtpqItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                meta.displayName(plugin.toComponent(applyPlaceholders(item.name, item)).decoration(TextDecoration.ITALIC, false));
                List<UUID> q = queueMap.getOrDefault(item.key, Collections.emptyList());
                boolean inThisQueue = q.contains(player.getUniqueId());
                String clickActionText = inThisQueue ? actionLeave : actionJoin;

                List<Component> coloredLore = new ArrayList<>();
                for (String l : item.lore) {
                    String line = applyPlaceholders(l, item)
                            .replace("{current_queue}", String.valueOf(q.size()))
                            .replace("{click_action}", clickActionText);
                    coloredLore.add(plugin.toComponent(line).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(coloredLore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
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

    public boolean leaveQueueCommand(Player player) {
        UUID uuid = player.getUniqueId();
        queueJoinTime.remove(uuid);
        for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
            if (entry.getValue().remove(uuid)) {
                RtpItem item = rtpqItems.get(entry.getKey());
                String msg = applyPlaceholders(plugin.getMsg("rtp-messages.left-queue", "{prefix}&#f38ba8You left the {icon} &#fab387{mode} &#f38ba8queue."), item);
                player.sendMessage(plugin.toComponent(msg));
                return true;
            }
        }
        player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.not-in-queue", "{prefix}&#f38ba8You are not currently in any queue.")));
        return false;
    }

    public void removePlayerFromAllQueues(UUID uuid) {
        for (List<UUID> list : queueMap.values()) list.remove(uuid);
        queueJoinTime.remove(uuid);
        inCountdown.remove(uuid);
    }

    private void toggleQueue(Player player, RtpItem item) {
        UUID uuid = player.getUniqueId();
        if (inCountdown.contains(uuid)) return;
        List<UUID> q = queueMap.get(item.key);
        if (q == null) return;

        if (q.contains(uuid)) {
            q.remove(uuid);
            queueJoinTime.remove(uuid);
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.left-queue", "{prefix}&#f38ba8You left the {icon} &#fab387{mode} &#f38ba8queue."), item)));
            return;
        }

        for (List<UUID> list : queueMap.values()) {
            if (list.contains(uuid)) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.already-in-other-queue", "{prefix}&#f38ba8You are already in another queue!")));
                return;
            }
        }

        q.add(uuid);
        queueJoinTime.put(uuid, System.currentTimeMillis());

        player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.joined-queue", "{prefix}&#89dcebYou joined the {icon} &#fab387{mode} &#89dcebqueue ({current}/2)!"), item)
                .replace("{current}", String.valueOf(q.size()))));

        if (q.size() == 1) {
            Bukkit.broadcast(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-broadcast", "{prefix}&#89b4faPlayer {player} is queuing for {icon} &#fab387{mode} (1/2)!"), item)
                    .replace("{player}", player.getName()).replace("{current}", "1")));
        }

        if (q.size() >= 2) {
            UUID u1 = q.remove(0);
            UUID u2 = q.remove(0);
            queueJoinTime.remove(u1);
            queueJoinTime.remove(u2);

            Player p1 = Bukkit.getPlayer(u1);
            Player p2 = Bukkit.getPlayer(u2);
            if (p1 != null && p2 != null && p1.isOnline() && p2.isOnline()) {
                startMatchmaking(p1, p2, item);
            } else {
                if (p1 != null && p1.isOnline()) {
                    q.add(u1);
                    queueJoinTime.put(u1, System.currentTimeMillis());
                }
                if (p2 != null && p2.isOnline()) {
                    q.add(u2);
                    queueJoinTime.put(u2, System.currentTimeMillis());
                }
            }
        }
    }

    private void startMatchmaking(Player p1, Player p2, RtpItem item) {
        p1.closeInventory();
        p2.closeInventory();
        inCountdown.add(p1.getUniqueId());
        inCountdown.add(p2.getUniqueId());

        for (String line : plugin.getConfig().getStringList("rtp-messages.match-found-broadcast")) {
            Bukkit.broadcast(plugin.toComponent(applyPlaceholders(line, item)
                    .replace("{player1}", p1.getName()).replace("{player2}", p2.getName())));
        }

        int countdown = plugin.getConfig().getInt("rtpq-menu.countdown-seconds", 5);
        String p1Prefix = getPlayerPrefix(p1);
        String p2Prefix = getPlayerPrefix(p2);

        new BukkitRunnable() {
            int timer = countdown;
            @Override
            public void run() {
                if (!p1.isOnline() || !p2.isOnline()) {
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
                    cancel();
                    if (p1.isOnline()) p1.sendMessage(plugin.toComponent("<glyph:warning_blue> &#f38ba8Opponent disconnected!"));
                    if (p2.isOnline()) p2.sendMessage(plugin.toComponent("<glyph:warning_blue> &#f38ba8Opponent disconnected!"));
                    return;
                }
                if (timer > 0) {
                    Component emojiTitle = getCircleEmoji(timer);
                    Component sub = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-countdown-subtitle", "&#cdd6f4Mode: {icon} &#fab387{mode}"), item));
                    Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1250), Duration.ofMillis(250));
                    Title titleObj = Title.title(emojiTitle, sub, times);
                    p1.showTitle(titleObj);
                    p2.showTitle(titleObj);

                    String abTemplate = applyPlaceholders(plugin.getMsg("rtp-messages.match-actionbar", "&#89dcebMode: {icon} &#fab387{mode} &8| &#89b4faOpponent: {opponent_prefix}{opponent_name}"), item);
                    p1.sendActionBar(plugin.toComponent(abTemplate.replace("{opponent_prefix}", p2Prefix).replace("{opponent_name}", p2.getName()).replace("{time}", String.valueOf(timer))));
                    p2.sendActionBar(plugin.toComponent(abTemplate.replace("{opponent_prefix}", p1Prefix).replace("{opponent_name}", p1.getName()).replace("{time}", String.valueOf(timer))));

                    p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    timer--;
                } else {
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
                    cancel();
                    teleportMatch(p1, p2, item);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private Component getCircleEmoji(int count) {
        String cfg = plugin.getConfig().getString("rtpq-menu.countdown-titles." + count);
        if (cfg != null && !cfg.isEmpty()) return plugin.toComponent(cfg);
        return switch (count) {
            case 5 -> plugin.toComponent("&#f38ba8&l❺");
            case 4 -> plugin.toComponent("&#f38ba8&l❹");
            case 3 -> plugin.toComponent("&#fab387&l❸");
            case 2 -> plugin.toComponent("&#fab387&l❷");
            case 1 -> plugin.toComponent("&#a6e3a1&l❶");
            default -> plugin.toComponent("&#fab387&l" + count);
        };
    }

    private void teleportMatch(Player p1, Player p2, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null) return;

        int gap = plugin.getConfig().getInt("rtpq-menu.player-gap", 8);
        Set<UUID> ignored = new HashSet<>(Arrays.asList(p1.getUniqueId(), p2.getUniqueId()));
        Location loc1 = null, loc2 = null;
        int maxAttempts = plugin.getConfig().getInt("rtp-settings.max-search-attempts", 60);

        for (int i = 0; i < maxAttempts; i++) {
            Location c1 = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, ignored);
            if (c1 == null) continue;
            int[][] dirs = {{gap, 0}, {-gap, 0}, {0, gap}, {0, -gap}};
            for (int[] d : dirs) {
                int tx = c1.getBlockX() + d[0], tz = c1.getBlockZ() + d[1];
                int ty = getSolidSurfaceY(world, tx, tz);
                if (Math.abs(ty - (c1.getBlockY() - 1)) <= 1) {
                    Location c2 = new Location(world, tx + 0.5, ty + 1.0, tz + 0.5);
                    if (world.getWorldBorder().isInside(c2) && isTerrainFlatAndSafe(world, tx, tz, ty)) {
                        loc1 = c1;
                        loc2 = c2;
                        break;
                    }
                }
            }
            if (loc1 != null && loc2 != null) break;
        }

        if (loc1 == null || loc2 == null) {
            Component err = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find a safe flat location!"), item));
            p1.sendMessage(err);
            p2.sendMessage(err);
            return;
        }

        Vector to2 = loc2.toVector().subtract(loc1.toVector()).normalize();
        Vector to1 = loc1.toVector().subtract(loc2.toVector()).normalize();
        loc1.setDirection(to2);
        loc2.setDirection(to1);
        p1.teleport(loc1);
        p2.teleport(loc2);

        Component fTitle = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-started-title", "&#a6e3a1&lFIGHT!"), item));
        Component fSub = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-started-subtitle", "&#cdd6f4Good luck!"), item));
        Title.Times times = Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500));
        Title fightTitleObj = Title.title(fTitle, fSub, times);
        p1.showTitle(fightTitleObj);
        p2.showTitle(fightTitleObj);
        p1.playSound(loc1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p2.playSound(loc2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void executeRtp(Player player, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null) return;
        player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.teleporting-rtp", "&#89dcebFinding safe location..."), item)));
        Location loc = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, Collections.singleton(player.getUniqueId()));
        if (loc != null) {
            player.teleport(loc);
            player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.teleport-success", "&#a6e3a1Teleported!"), item)
                    .replace("{x}", String.valueOf(loc.getBlockX())).replace("{y}", String.valueOf(loc.getBlockY())).replace("{z}", String.valueOf(loc.getBlockZ()))));
        } else {
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find a safe flat location!"), item)));
        }
    }

    private boolean isHarmlessPassable(Material m) {
        return m.isAir() || m == Material.DEAD_BUSH || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN;
    }

    private int getSolidSurfaceY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        while (y > world.getMinHeight() && isHarmlessPassable(world.getBlockAt(x, y, z).getType())) y--;
        return y;
    }

    private Location findSafeLocation(World world, int minX, int maxX, int minZ, int maxZ, Set<UUID> ignored) {
        int x1 = Math.min(minX, maxX), x2 = Math.max(minX, maxX);
        int z1 = Math.min(minZ, maxZ), z2 = Math.max(minZ, maxZ);
        int maxAttempts = plugin.getConfig().getInt("rtp-settings.max-search-attempts", 60);
        double minDistSq = Math.pow(plugin.getConfig().getDouble("rtp-settings.min-player-distance", 50.0), 2);

        for (int i = 0; i < maxAttempts; i++) {
            int rx = ThreadLocalRandom.current().nextInt(x1, x2 + 1);
            int rz = ThreadLocalRandom.current().nextInt(z1, z2 + 1);
            int y = getSolidSurfaceY(world, rx, rz);
            if (y <= world.getMinHeight() + 5 || y >= world.getMaxHeight() - 5) continue;

            Location cand = new Location(world, rx + 0.5, y + 1.0, rz + 0.5);
            if (!world.getWorldBorder().isInside(cand)) continue;

            boolean near = false;
            for (Player p : world.getPlayers()) {
                if (ignored != null && ignored.contains(p.getUniqueId())) continue;
                if (p.getLocation().distanceSquared(cand) < minDistSq) { near = true; break; }
            }
            if (!near && isTerrainFlatAndSafe(world, rx, rz, y)) return cand;
        }
        return null;
    }

    private boolean isTerrainFlatAndSafe(World world, int cx, int cz, int baseY) {
        int maxSlope = plugin.getConfig().getInt("rtp-settings.max-terrain-slope", 2);
        int minY = baseY, maxY = baseY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int sy = getSolidSurfaceY(world, cx + dx, cz + dz);
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
                if ((maxY - minY) > maxSlope || !isSafeBlock(world.getBlockAt(cx + dx, sy, cz + dz).getType())) return false;
            }
        }
        return isHarmlessPassable(world.getBlockAt(cx, baseY + 1, cz).getType())
                && isHarmlessPassable(world.getBlockAt(cx, baseY + 2, cz).getType());
    }

    private boolean isSafeBlock(Material m) {
        return m.isSolid() && m != Material.LAVA && m != Material.WATER && m != Material.MAGMA_BLOCK
                && m != Material.FIRE && m != Material.SOUL_FIRE && m != Material.CACTUS
                && m != Material.WITHER_ROSE && m != Material.BEDROCK && m != Material.OBSIDIAN
                && m != Material.RESPAWN_ANCHOR && m != Material.COBWEB;
    }

    private String getPlayerPrefix(Player player) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            String p = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%");
            if (!p.equals("%luckperms_prefix%")) return p;
        }
        return "";
    }
                    }
