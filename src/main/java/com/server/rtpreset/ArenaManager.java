package com.server.rtpreset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public class ArenaManager {

    public static class Arena {
        public String name;
        public Location spawn1;
        public Location spawn2;
        public boolean inUse = false;

        public Arena(String name) {
            this.name = name;
        }

        public boolean isReady() {
            return spawn1 != null && spawn2 != null && spawn1.getWorld() != null && spawn2.getWorld() != null;
        }
    }

    public static class ArenaMatch {
        public final Arena arena;
        public final RtpManager.RtpItem item;
        public final UUID p1;
        public final UUID p2;
        public final int totalRounds;
        public int currentRound = 1;
        public int p1Wins = 0;
        public int p2Wins = 0;
        public boolean processingRoundEnd = false;

        public ArenaMatch(Arena arena, RtpManager.RtpItem item, UUID p1, UUID p2, int totalRounds) {
            this.arena = arena;
            this.item = item;
            this.p1 = p1;
            this.p2 = p2;
            this.totalRounds = totalRounds;
        }

        public Location getSpawn(UUID uuid) {
            return uuid.equals(p1) ? arena.spawn1 : arena.spawn2;
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
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, ArenaMatch> activeMatches = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingRespawns = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingChatName = new ConcurrentHashMap<>();

    public ArenaManager(RtpResetTimer plugin) {
        this.plugin = plugin;
        loadArenas();
    }

    public void shutdown() {
        Location lobby = plugin.getLobbyLocation();
        for (UUID uuid : new HashSet<>(activeMatches.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && lobby != null) p.teleport(lobby);
        }
        for (Arena a : arenas.values()) a.inUse = false;
        activeMatches.clear();
        pendingRespawns.clear();
        awaitingChatName.clear();
    }

    public void loadArenas() {
        arenas.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arenas");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Arena arena = new Arena(key);
            arena.spawn1 = readLoc(sec.getConfigurationSection(key + ".spawn1"));
            arena.spawn2 = readLoc(sec.getConfigurationSection(key + ".spawn2"));
            arenas.put(key, arena);
        }
    }

    public void saveArenas() {
        plugin.getConfig().set("arenas", null);
        for (Arena a : arenas.values()) {
            writeLoc("arenas." + a.name + ".spawn1", a.spawn1);
            writeLoc("arenas." + a.name + ".spawn2", a.spawn2);
        }
        plugin.saveConfig();
    }

    private Location readLoc(ConfigurationSection s) {
        if (s == null) return null;
        World w = Bukkit.getWorld(s.getString("world", "world"));
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private void writeLoc(String path, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        plugin.getConfig().set(path + ".world", loc.getWorld().getName());
        plugin.getConfig().set(path + ".x", loc.getX());
        plugin.getConfig().set(path + ".y", loc.getY());
        plugin.getConfig().set(path + ".z", loc.getZ());
        plugin.getConfig().set(path + ".yaw", loc.getYaw());
        plugin.getConfig().set(path + ".pitch", loc.getPitch());
    }

    public boolean isInMatch(UUID uuid) {
        return activeMatches.containsKey(uuid);
    }

    public synchronized Arena reserveAvailableArena() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : arenas.values()) {
            if (!a.inUse && a.isReady()) free.add(a);
        }
        if (free.isEmpty()) return null;
        Arena chosen = free.get(new Random().nextInt(free.size()));
        chosen.inUse = true;
        return chosen;
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

    public void startArenaMatch(Player p1, Player p2, Arena arena, RtpManager.RtpItem item) {
        int totalRounds = Math.max(1, plugin.getConfig().getInt("arena-settings.total-rounds", 3));
        ArenaMatch match = new ArenaMatch(arena, item, p1.getUniqueId(), p2.getUniqueId(), totalRounds);
        activeMatches.put(p1.getUniqueId(), match);
        activeMatches.put(p2.getUniqueId(), match);

        p1.teleport(arena.spawn1);
        p2.teleport(arena.spawn2);
        healPlayer(p1);
        healPlayer(p2);

        Title fTitle = Title.title(
                plugin.toComponent(plugin.getMsg("rtp-messages.match-started-title", "&#a6e3a1FIGHT!")),
                plugin.toComponent("&#a6adc8Round &#89b4fa1/" + totalRounds + " &8| &#cdd6f4" + arena.name),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))
        );
        p1.showTitle(fTitle);
        p2.showTitle(fTitle);
        p1.playSound(arena.spawn1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p2.playSound(arena.spawn2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    public void handlePlayerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        ArenaMatch match = activeMatches.get(dead.getUniqueId());
        if (match == null) return;

        if (match.processingRoundEnd) {
            pendingRespawns.put(dead.getUniqueId(),
                    match.currentRound <= match.totalRounds ? match.getSpawn(dead.getUniqueId()) : plugin.getLobbyLocation());
            return;
        }
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

        if (finishedRound < match.totalRounds) {
            // CÒN ROUND TIẾP THEO -> HỒI SINH TẠI ARENA SPAWN & REGEN MAP
            match.currentRound++;
            pendingRespawns.put(dead.getUniqueId(), match.getSpawn(dead.getUniqueId()));

            String roundMsg = plugin.getMsg("rtp-messages.arena-round-end",
                    "{prefix}&#cdd6f4{winner} &#a6adc8won Round &#89b4fa{round}/{total} &#a6adc8(&#a6e3a1{p1_wins}&#a6adc8-&#f38ba8{p2_wins}&#a6adc8)!")
                    .replace("{winner}", winnerName)
                    .replace("{round}", String.valueOf(finishedRound))
                    .replace("{total}", String.valueOf(match.totalRounds))
                    .replace("{p1_wins}", String.valueOf(match.p1Wins))
                    .replace("{p2_wins}", String.valueOf(match.p2Wins));

            if (p1 != null) p1.sendMessage(plugin.toComponent(roundMsg));
            if (p2 != null) p2.sendMessage(plugin.toComponent(roundMsg));

            runArenaRegen(match.arena.name);

            int delay = plugin.getConfig().getInt("arena-settings.round-restart-delay-ticks", 25);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!activeMatches.containsKey(match.p1)) return;
                match.processingRoundEnd = false;

                if (p1 != null && p1.isOnline() && !p1.isDead()) {
                    p1.teleport(match.arena.spawn1);
                    healPlayer(p1);
                }
                if (p2 != null && p2.isOnline() && !p2.isDead()) {
                    p2.teleport(match.arena.spawn2);
                    healPlayer(p2);
                }

                String rTitle = plugin.getMsg("rtp-messages.arena-round-title", "&#89b4faROUND {round}/{total}")
                        .replace("{round}", String.valueOf(match.currentRound))
                        .replace("{total}", String.valueOf(match.totalRounds));
                String rSub = plugin.getMsg("rtp-messages.arena-round-subtitle", "&#cdd6f4{p1} &#a6e3a1{p1_wins} &8- &#f38ba8{p2_wins} &#cdd6f4{p2}")
                        .replace("{p1}", p1Name).replace("{p2}", p2Name)
                        .replace("{p1_wins}", String.valueOf(match.p1Wins))
                        .replace("{p2_wins}", String.valueOf(match.p2Wins));

                Title nextTitle = Title.title(plugin.toComponent(rTitle), plugin.toComponent(rSub),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300)));
                if (p1 != null) { p1.showTitle(nextTitle); p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f); }
                if (p2 != null) { p2.showTitle(nextTitle); p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f); }
            }, delay);

        } else {
            // ĐÃ HOÀN THÀNH ĐỦ 3 ROUND -> VỀ LOBBY & REGEN ARENA
            Location lobby = plugin.getLobbyLocation();
            if (lobby != null) pendingRespawns.put(dead.getUniqueId(), lobby);
            finishMatch(match);
        }
    }

    public void handlePlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location target = pendingRespawns.remove(uuid);
        if (target == null) {
            ArenaMatch match = activeMatches.get(uuid);
            if (match != null) target = match.getSpawn(uuid);
        }
        if (target != null) {
            event.setRespawnLocation(target);
            Location finalTarget = target;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (event.getPlayer().isOnline()) {
                    event.getPlayer().teleport(finalTarget);
                    healPlayer(event.getPlayer());
                }
            }, 2L);
        }
    }

    public void handlePlayerQuit(Player quitter) {
        UUID uuid = quitter.getUniqueId();
        awaitingChatName.remove(uuid);
        pendingRespawns.remove(uuid);

        ArenaMatch match = activeMatches.get(uuid);
        if (match == null) return;

        if (uuid.equals(match.p1)) match.p2Wins = match.totalRounds;
        else match.p1Wins = match.totalRounds;
        finishMatch(match);
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
            if (lobby != null) {
                if (p1 != null && p1.isOnline() && !p1.isDead()) { p1.teleport(lobby); healPlayer(p1); }
                if (p2 != null && p2.isOnline() && !p2.isDead()) { p2.teleport(lobby); healPlayer(p2); }
            }
            runArenaRegen(match.arena.name);
            match.arena.inUse = false;
        }, 20L);
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
            String s1 = a.spawn1 != null ? "&#a6e3a1Set (" + a.spawn1.getBlockX() + ", " + a.spawn1.getBlockY() + ", " + a.spawn1.getBlockZ() + ")" : "&#f38ba8Not Set";
            String s2 = a.spawn2 != null ? "&#a6e3a1Set (" + a.spawn2.getBlockX() + ", " + a.spawn2.getBlockY() + ", " + a.spawn2.getBlockZ() + ")" : "&#f38ba8Not Set";
            String st = a.inUse ? "&#fab387In Match" : (a.isReady() ? "&#a6e3a1Ready" : "&#f38ba8Incomplete");

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

        String s1 = arena.spawn1 != null ? "&#a6e3a1" + arena.spawn1.getBlockX() + ", " + arena.spawn1.getBlockY() + ", " + arena.spawn1.getBlockZ() : "&#f38ba8Not Set";
        String s2 = arena.spawn2 != null ? "&#a6e3a1" + arena.spawn2.getBlockX() + ", " + arena.spawn2.getBlockY() + ", " + arena.spawn2.getBlockZ() : "&#f38ba8Not Set";

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
            saveArenas();
            admin.playSound(admin.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            openSetupEditGui(admin, created);
            return;
        }

        List<Arena> list = new ArrayList<>(arenas.values());
        if (slot >= 0 && slot < list.size() && slot < 18) {
            Arena target = list.get(slot);
            if (click.isRightClick()) {
                arenas.remove(target.name);
                saveArenas();
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
            arena.spawn1 = admin.getLocation().clone();
            saveArenas();
            admin.playSound(admin.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            admin.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#a6e3a1Set Spawn Location 1 for arena &#cdd6f4" + arena.name + "&#a6e3a1!"));
            openSetupEditGui(admin, arena);
        } else if (slot == 15) {
            arena.spawn2 = admin.getLocation().clone();
            saveArenas();
            admin.playSound(admin.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            admin.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#a6e3a1Set Spawn Location 2 for arena &#cdd6f4" + arena.name + "&#a6e3a1!"));
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
            arena.name = input;
            arenas.put(input, arena);
            saveArenas();
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            player.sendMessage(plugin.toComponent(plugin.getMsg("messages.prefix", "") +
                    "&#a6e3a1Arena name set to &#cdd6f4" + input + "&#a6e3a1!"));
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
