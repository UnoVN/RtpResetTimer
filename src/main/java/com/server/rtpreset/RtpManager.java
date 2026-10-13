package com.server.rtpreset;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("deprecation")
public class RtpManager {

    public static class RtpItem {
        public String key;
        public int slot;
        public Material material;
        public String name;
        public String modeDisplay;
        public String mapDisplay;
        public List<String> lore;
        public String worldName;
        public int minX, maxX, minZ, maxZ;
    }

    private final RtpResetTimer plugin;
    private final Map<String, RtpItem> rtpItems = new LinkedHashMap<>();
    private final Map<String, RtpItem> rtpqItems = new LinkedHashMap<>();
    private final Map<String, List<UUID>> queueMap = new ConcurrentHashMap<>();
    private final Set<UUID> inCountdown = ConcurrentHashMap.newKeySet();

    public RtpManager(RtpResetTimer plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        rtpItems.clear();
        rtpqItems.clear();
        queueMap.clear();
        inCountdown.clear();

        ConfigurationSection rtpSec = plugin.getConfig().getConfigurationSection("rtp-menu.items");
        if (rtpSec != null) {
            for (String key : rtpSec.getKeys(false)) {
                ConfigurationSection sub = rtpSec.getConfigurationSection(key);
                if (sub != null) {
                    rtpItems.put(key, parseItem(sub, key));
                }
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
        item.mapDisplay = sec.getString("map-display", "RTP Desert");
        item.lore = sec.getStringList("lore");
        item.worldName = sec.getString("world", "rtpdesert");
        item.minX = sec.getInt("min-x", 0);
        item.maxX = sec.getInt("max-x", 100);
        item.minZ = sec.getInt("min-z", 0);
        item.maxZ = sec.getInt("max-z", 100);
        return item;
    }

    private void applyFiller(Inventory inv, String menuPath) {
        if (!plugin.getConfig().getBoolean(menuPath + ".filler.enabled", true)) return;
        String matName = plugin.getConfig().getString(menuPath + ".filler.material", "BLACK_STAINED_GLASS_PANE");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.BLACK_STAINED_GLASS_PANE;

        ItemStack filler = new ItemStack(mat);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(plugin.getConfig().getString(menuPath + ".filler.name", " ")));
            filler.setItemMeta(meta);
        }

        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    public void openRtpGui(Player player) {
        String title = plugin.color(plugin.getConfig().getString("rtp-menu.title", "&#89b4fa&lRANDOM TELEPORT"));
        int size = plugin.getConfig().getInt("rtp-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpGuiHolder(), size, title);

        applyFiller(inv, "rtp-menu");

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
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) {
                inv.setItem(item.slot, is);
            }
        }
        player.openInventory(inv);
    }

    public void openRtpQueueGui(Player player) {
        String title = plugin.color(plugin.getConfig().getString("rtpq-menu.title", "&#fab387&l1V1 BATTLE QUEUE"));
        int size = plugin.getConfig().getInt("rtpq-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpQueueGuiHolder(), size, title);

        applyFiller(inv, "rtpq-menu");

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
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) {
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

    // PHƯƠNG THỨC XỬ LÝ LỆNH /rtpq leave (KHẮC PHỤC LỖI BIÊN DỊCH DÒNG 336)
    public boolean leaveQueueCommand(Player player) {
        for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
            if (entry.getValue().remove(player.getUniqueId())) {
                RtpItem item = rtpqItems.get(entry.getKey());
                String displayName = item != null ? item.name : entry.getKey();
                String msg = plugin.getMsg("rtp-messages.left-queue", "{prefix}&#f38ba8You left the queue for {display_name}.")
                        .replace("{display_name}", displayName);
                player.sendMessage(plugin.color(msg));
                return true;
            }
        }
        player.sendMessage(plugin.color(plugin.getMsg("rtp-messages.not-in-queue", "{prefix}&#f38ba8You are not currently in any queue.")));
        return false;
    }

    public void removePlayerFromAllQueues(UUID uuid) {
        for (List<UUID> list : queueMap.values()) {
            list.remove(uuid);
        }
        inCountdown.remove(uuid);
    }

    private void toggleQueue(Player player, RtpItem item) {
        if (inCountdown.contains(player.getUniqueId())) {
            return;
        }

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
        String joinMsg = plugin.getMsg("rtp-messages.joined-queue", "{prefix}&#89dcebYou joined the queue for {display_name} ({current}/2)!")
                .replace("{display_name}", item.name)
                .replace("{mode}", item.modeDisplay)
                .replace("{current}", String.valueOf(q.size()));
        player.sendMessage(plugin.color(joinMsg));

        if (q.size() == 1) {
            String bcast = plugin.getMsg("rtp-messages.queue-broadcast", "{prefix}&#89b4faPlayer {player} is looking for a 1v1 match in {mode} ({current}/2)!")
                    .replace("{player}", player.getName())
                    .replace("{display_name}", item.name)
                    .replace("{mode}", item.modeDisplay)
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

        inCountdown.add(p1.getUniqueId());
        inCountdown.add(p2.getUniqueId());

        List<String> broadcastLines = plugin.getConfig().getStringList("rtp-messages.match-found-broadcast");
        if (!broadcastLines.isEmpty()) {
            for (String line : broadcastLines) {
                String formatted = line.replace("{player1}", p1.getName())
                        .replace("{player2}", p2.getName())
                        .replace("{mode}", item.modeDisplay)
                        .replace("{map}", item.mapDisplay)
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
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
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
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
                    cancel();
                    teleportMatch(p1, p2, item);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private String getCircleEmoji(int count) {
        String fromConfig = plugin.getConfig().getString("rtpq-menu.countdown-titles." + count);
        if (fromConfig != null && !fromConfig.isEmpty()) {
            return plugin.color(fromConfig);
        }
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
            Location candidate1 = findSafeLocation(world, item.minX, item.maxX, item.min
                                                   
