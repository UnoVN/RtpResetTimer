package com.server.rtpreset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class RtpResetTimer extends JavaPlugin implements CommandExecutor {

    public static class WorldProfile {
        public String key, targetWorld, displayName;
        public int resetIntervalSeconds, remainingSeconds;
        public double borderCenterX, borderCenterZ, borderSize;
        public boolean broadcastOnlyInWorld;
        public List<String> postResetCommands;
        public boolean isResetting = false;

        public WorldProfile(String key, String targetWorld, String displayName, int resetIntervalSeconds,
                            double borderCenterX, double borderCenterZ, double borderSize,
                            boolean broadcastOnlyInWorld, List<String> postResetCommands) {
            this.key = key;
            this.targetWorld = targetWorld;
            this.displayName = displayName;
            this.resetIntervalSeconds = resetIntervalSeconds;
            this.remainingSeconds = resetIntervalSeconds;
            this.borderCenterX = borderCenterX;
            this.borderCenterZ = borderCenterZ;
            this.borderSize = borderSize;
            this.broadcastOnlyInWorld = broadcastOnlyInWorld;
            this.postResetCommands = postResetCommands != null ? postResetCommands : Collections.emptyList();
        }
    }

    private final Map<String, WorldProfile> worldProfiles = new LinkedHashMap<>();
    private String defaultWorldKey = null;

    private String lobbyWorldName;
    private double spawnX, spawnY, spawnZ;
    private float spawnYaw, spawnPitch;

    private String prefix, msgCountdownNormal, msgCountdownWarning, msgCountdownUrgent;
    private String msgResetting, msgPlayerTeleported, msgResetSuccess, msgNextReset;
    private String msgAdminNoPermission, msgAdminReloadSuccess, msgAdminManualResetSingle;
    private String msgAdminManualResetAll, msgAdminWorldNotFound, msgAdminNoWorlds;

    private BukkitTask mainTimerTask;
    private RtpManager rtpManager;
    private ArenaManager arenaManager;
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6});?");
    private static final String RESET_DECOR = "<!bold><!italic><!underlined><!strikethrough><!obfuscated>";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.arenaManager = new ArenaManager(this);
        this.rtpManager = new RtpManager(this);
        loadConfiguration();

        getServer().getPluginManager().registerEvents(new RtpListener(this), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RtpExpansion(this).register();
            getLogger().info("Placeholder %rtp_timer% registered successfully!");
        }

        if (getCommand("rtpreset") != null) getCommand("rtpreset").setExecutor(this);
        if (getCommand("setup") != null) getCommand("setup").setExecutor(this);
        if (getCommand("rtp") != null) getCommand("rtp").setExecutor(this);
        if (getCommand("rtpq") != null) getCommand("rtpq").setExecutor(this);
        if (getCommand("duel") != null) getCommand("duel").setExecutor(this);
        if (getCommand("forfeit") != null) getCommand("forfeit").setExecutor(this);

        startTimerLoop();
        getLogger().info("RtpResetTimer 1.5.0 (SQLite Arena, 1v1/2v2 & EcoCart Edition) enabled!");
    }

    @Override
    public void onDisable() {
        if (mainTimerTask != null) mainTimerTask.cancel();
        if (rtpManager != null) rtpManager.shutdown();
        if (arenaManager != null) arenaManager.shutdown();
    }

    public RtpManager getRtpManager() {
        return rtpManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public Location getLobbyLocation() {
        World w = Bukkit.getWorld(lobbyWorldName);
        return w != null ? new Location(w, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch) : null;
    }

    public boolean isWorldResetting(String worldName) {
        for (WorldProfile p : worldProfiles.values()) {
            if (p.targetWorld.equalsIgnoreCase(worldName) && p.isResetting) return true;
        }
        return false;
    }

    public String getMsg(String path, String def) {
        return getConfig().getString(path, def).replace("{prefix}", prefix);
    }

    public void loadConfiguration() {
        reloadConfig();
        worldProfiles.clear();
        defaultWorldKey = null;

        this.lobbyWorldName = getConfig().getString("lobby-spawn.world", "world");
        this.spawnX = getConfig().getDouble("lobby-spawn.x", 157.5);
        this.spawnY = getConfig().getDouble("lobby-spawn.y", 115.0);
        this.spawnZ = getConfig().getDouble("lobby-spawn.z", -5.5);
        this.spawnYaw = (float) getConfig().getDouble("lobby-spawn.yaw", 90.0);
        this.spawnPitch = (float) getConfig().getDouble("lobby-spawn.pitch", 0.0);

        this.prefix = getConfig().getString("messages.prefix", "<glyph:warning_blue> ");
        this.msgCountdownNormal = getConfig().getString("messages.countdown-normal", "{prefix}&#a6adc8Resetting &#cdd6f4{display_name} &#a6adc8in &#89b4fa{time}");
        this.msgCountdownWarning = getConfig().getString("messages.countdown-warning", "{prefix}&#a6adc8Resetting &#cdd6f4{display_name} &#a6adc8in &#fab387{time}");
        this.msgCountdownUrgent = getConfig().getString("messages.countdown-urgent", "{prefix}&#f38ba8Resetting {display_name} in {time}");

        this.msgResetting = getConfig().getString("messages.resetting", "{prefix}&#a6adc8Resetting &#cdd6f4{display_name}&#a6adc8...");
        this.msgPlayerTeleported = getConfig().getString("messages.player-teleported", "{prefix}&#a6adc8Teleported to lobby because &#cdd6f4{display_name} &#a6adc8is resetting.");
        this.msgResetSuccess = getConfig().getString("messages.reset-success", "{prefix}&#cdd6f4{display_name} &#a6e3a1has been reset!");
        this.msgNextReset = getConfig().getString("messages.next-reset", "{prefix}&#a6adc8Next reset for &#cdd6f4{display_name} &#a6adc8in &#89b4fa{time}");

        this.msgAdminNoPermission = getConfig().getString("messages.admin.no-permission", "{prefix}&#f38ba8No permission.");
        this.msgAdminReloadSuccess = getConfig().getString("messages.admin.reload-success", "{prefix}&#a6e3a1Config reloaded (&#cdd6f4{count} &#a6e3a1worlds).");
        this.msgAdminManualResetSingle = getConfig().getString("messages.admin.manual-reset-single", "{prefix}&#a6adc8Resetting &#cdd6f4{display_name}&#a6adc8...");
        this.msgAdminManualResetAll = getConfig().getString("messages.admin.manual-reset-all", "{prefix}&#a6adc8Resetting all worlds...");
        this.msgAdminWorldNotFound = getConfig().getString("messages.admin.world-not-found", "{prefix}&#f38ba8World not found. Available: &#cdd6f4{available}");
        this.msgAdminNoWorlds = getConfig().getString("messages.admin.no-worlds", "{prefix}&#f38ba8No worlds configured.");

        boolean globalOnlyInWorld = getConfig().getBoolean("broadcast-only-in-world", true);

        ConfigurationSection section = getConfig().getConfigurationSection("worlds");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String target = section.getString(key + ".target-world", key);
                String display = section.getString(key + ".display-name", key);
                int interval = section.getInt(key + ".reset-interval-seconds", 1800);
                double bX = section.getDouble(key + ".worldborder.center-x", 420.0);
                double bZ = section.getDouble(key + ".worldborder.center-z", -455.0);
                double bSize = section.getDouble(key + ".worldborder.size", 838.0);
                boolean onlyInWorld = section.getBoolean(key + ".broadcast-only-in-world", globalOnlyInWorld);
                List<String> cmds = section.getStringList(key + ".post-reset-commands");

                WorldProfile profile = new WorldProfile(key, target, display, interval, bX, bZ, bSize, onlyInWorld, cmds);
                worldProfiles.put(key.toLowerCase(), profile);
                if (defaultWorldKey == null) defaultWorldKey = key.toLowerCase();

                World existingWorld = Bukkit.getWorld(target);
                if (existingWorld != null) applyWorldSettings(existingWorld, profile);
            }
        }

        if (rtpManager != null) rtpManager.loadConfig();
        if (arenaManager != null) arenaManager.loadArenasFromDb();
    }

    public int getRemainingSeconds() {
        return getDefaultRemainingSeconds();
    }

    public int getDefaultRemainingSeconds() {
        if (defaultWorldKey == null || !worldProfiles.containsKey(defaultWorldKey)) return -1;
        return worldProfiles.get(defaultWorldKey).remainingSeconds;
    }

    public int getRemainingSeconds(String key) {
        WorldProfile profile = worldProfiles.get(key.toLowerCase());
        if (profile == null) {
            for (WorldProfile p : worldProfiles.values()) {
                if (p.targetWorld.equalsIgnoreCase(key)) return p.remainingSeconds;
            }
            return -1;
        }
        return profile.remainingSeconds;
    }

    public Component toComponent(String message) {
        if (message == null || message.isEmpty()) return Component.empty();
        try {
            String s = message.replaceAll("<sprite:(?:minecraft:)?item/([^>]+)>", "<sprite:items:item/$1>")
                              .replaceAll("<sprite:(?:minecraft:)?block/([^>]+)>", "<sprite:blocks:block/$1>");

            Matcher matcher = HEX_PATTERN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(sb, RESET_DECOR + "<#" + matcher.group(1) + ">");
            }
            matcher.appendTail(sb);
            s = convertLegacyCodes(sb.toString());

            return MiniMessage.miniMessage().deserialize(s);
        } catch (Exception e) {
            return Component.text(message);
        }
    }

    private String convertLegacyCodes(String text) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                String rep = switch (next) {
                    case '0' -> RESET_DECOR + "<black>";
                    case '1' -> RESET_DECOR + "<dark_blue>";
                    case '2' -> RESET_DECOR + "<dark_green>";
                    case '3' -> RESET_DECOR + "<dark_aqua>";
                    case '4' -> RESET_DECOR + "<dark_red>";
                    case '5' -> RESET_DECOR + "<dark_purple>";
                    case '6' -> RESET_DECOR + "<gold>";
                    case '7' -> RESET_DECOR + "<gray>";
                    case '8' -> RESET_DECOR + "<dark_gray>";
                    case '9' -> RESET_DECOR + "<blue>";
                    case 'a' -> RESET_DECOR + "<green>";
                    case 'b' -> RESET_DECOR + "<aqua>";
                    case 'c' -> RESET_DECOR + "<red>";
                    case 'd' -> RESET_DECOR + "<light_purple>";
                    case 'e' -> RESET_DECOR + "<yellow>";
                    case 'f' -> RESET_DECOR + "<white>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'k' -> "<obfuscated>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (rep != null) {
                    out.append(rep);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private Component formatMessage(String template, WorldProfile profile, String timeStr) {
        String res = template.replace("{prefix}", prefix);
        if (profile != null) {
            res = res.replace("{display_name}", profile.displayName).replace("{world}", profile.targetWorld);
        }
        if (timeStr != null) res = res.replace("{time}", timeStr);
        return toComponent(res);
    }

    private void broadcastWorld(WorldProfile profile, Component formattedMessage, Set<UUID> extraRecipients) {
        if (profile == null || !profile.broadcastOnlyInWorld) {
            Bukkit.broadcast(formattedMessage);
            return;
        }
        World target = Bukkit.getWorld(profile.targetWorld);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean inTarget = (target != null && p.getWorld().equals(target));
            boolean inExtra = (extraRecipients != null && extraRecipients.contains(p.getUniqueId()));
            if (inTarget || inExtra) {
                p.sendMessage(formattedMessage);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formattedMessage);
    }

    private String formatDuration(int totalSeconds) {
        if (totalSeconds >= 60 && totalSeconds % 60 == 0) return (totalSeconds / 60) + "m";
        if (totalSeconds >= 60) return (totalSeconds / 60) + "m " + (totalSeconds % 60) + "s";
        return totalSeconds + "s";
    }

    private void startTimerLoop() {
        if (mainTimerTask != null) mainTimerTask.cancel();

        mainTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (WorldProfile profile : worldProfiles.values()) {
                    if (profile.isResetting) continue;
                    profile.remainingSeconds--;

                    switch (profile.remainingSeconds) {
                        case 1200, 900, 600, 300, 180 ->
                                broadcastWorld(profile, formatMessage(msgCountdownNormal, profile, formatDuration(profile.remainingSeconds)), null);
                        case 60, 30 ->
                                broadcastWorld(profile, formatMessage(msgCountdownWarning, profile, formatDuration(profile.remainingSeconds)), null);
                        case 5, 4, 3, 2, 1 ->
                                broadcastWorld(profile, formatMessage(msgCountdownUrgent, profile, profile.remainingSeconds + "s"), null);
                    }

                    if (profile.remainingSeconds <= 0) {
                        performWorldReset(profile);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    public void applyWorldSettings(World loadedWorld, WorldProfile profile) {
        try {
            WorldBorder border = loadedWorld.getWorldBorder();
            border.setCenter(profile.borderCenterX, profile.borderCenterZ);
            border.setSize(profile.borderSize);
            border.setWarningDistance(5);

            loadedWorld.setTime(1000L);
            loadedWorld.setGameRuleValue("advance_time", "false");
            loadedWorld.setGameRuleValue("locator_bar", "false");

            for (String cmd : profile.postResetCommands) {
                String trimmed = cmd.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("time set")) continue;

                if (trimmed.startsWith("gamerule ")) {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 3) {
                        String rule = parts[1];
                        String val = parts[2];
                        if (rule.equalsIgnoreCase("doDaylightCycle")) {
                            rule = "advance_time";
                            val = "false";
                        }
                        if (!loadedWorld.setGameRuleValue(rule, val)) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                    "execute in " + loadedWorld.getKey() + " run gamerule " + rule + " " + val);
                        }
                    }
                    continue;
                }

                String finalCmd = trimmed.startsWith("execute in ")
                        ? trimmed
                        : "execute in " + loadedWorld.getKey() + " run " + trimmed;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            }
        } catch (Exception e) {
            getLogger().warning("Error applying settings to " + profile.targetWorld + ": " + e.getMessage());
        }
    }

    public void performWorldReset(WorldProfile profile) {
        if (profile.isResetting) return;
        profile.isResetting = true;
        profile.remainingSeconds = 0;

        try {
            Set<UUID> evacuatedPlayers = new HashSet<>();
            Location lobbyLocation = getLobbyLocation();
            World targetWorldObj = Bukkit.getWorld(profile.targetWorld);

            if (targetWorldObj != null) {
                for (Player player : new ArrayList<>(targetWorldObj.getPlayers())) {
                    evacuatedPlayers.add(player.getUniqueId());
                    if (lobbyLocation != null) {
                        player.teleport(lobbyLocation);
                        player.sendMessage(formatMessage(msgPlayerTeleported, profile, null));
                    }
                }
            }

            broadcastWorld(profile, formatMessage(msgResetting, profile, null), evacuatedPlayers);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (Bukkit.getWorld(profile.targetWorld) != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload " + profile.targetWorld);
                }

                new BukkitRunnable() {
                    int unloadWait = 0;

                    @Override
                    public void run() {
                        unloadWait++;
                        if (Bukkit.getWorld(profile.targetWorld) == null || unloadWait >= 20) {
                            cancel();
                            Bukkit.getScheduler().runTaskLater(RtpResetTimer.this, () -> {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load " + profile.targetWorld);

                                new BukkitRunnable() {
                                    int loadWait = 0;

                                    @Override
                                    public void run() {
                                        loadWait++;
                                        World loadedWorld = Bukkit.getWorld(profile.targetWorld);
                                        if (loadedWorld != null) {
                                            cancel();
                                            Bukkit.getScheduler().runTaskLater(RtpResetTimer.this, () -> {
                                                try {
                                                    applyWorldSettings(loadedWorld, profile);
                                                    broadcastWorld(profile, formatMessage(msgResetSuccess, profile, null), evacuatedPlayers);
                                                    broadcastWorld(profile, formatMessage(msgNextReset, profile, formatDuration(profile.resetIntervalSeconds)), evacuatedPlayers);
                                                } finally {
                                                    profile.remainingSeconds = profile.resetIntervalSeconds;
                                                    profile.isResetting = false;
                                                }
                                            }, 20L);
                                        } else if (loadWait >= 30) {
                                            cancel();
                                            getLogger().severe("Timeout waiting for world " + profile.targetWorld + " to load!");
                                            profile.remainingSeconds = profile.resetIntervalSeconds;
                                            profile.isResetting = false;
                                        }
                                    }
                                }.runTaskTimer(RtpResetTimer.this, 10L, 10L);
                            }, 20L);
                        }
                    }
                }.runTaskTimer(RtpResetTimer.this, 10L, 10L);
            }, 20L);

        } catch (Exception e) {
            getLogger().severe("Unexpected error resetting " + profile.targetWorld + ": " + e.getMessage());
            profile.remainingSeconds = profile.resetIntervalSeconds;
            profile.isResetting = false;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("rtp")) {
            if (!(sender instanceof Player player)) return true;
            rtpManager.openRtpGui(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("rtpq")) {
            if (!(sender instanceof Player player)) return true;
            if (args.length > 0 && (args[0].equalsIgnoreCase("leave") || args[0].equalsIgnoreCase("forfeit"))) {
                if (!arenaManager.forfeitMatch(player)) {
                    rtpManager.leaveQueueCommand(player);
                }
                return true;
            }
            rtpManager.openRtpQueueGui(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("duel")) {
            if (!(sender instanceof Player player)) return true;
            if (args.length > 0 && (args[0].equalsIgnoreCase("leave") || args[0].equalsIgnoreCase("forfeit"))) {
                if (!arenaManager.forfeitMatch(player)) {
                    rtpManager.leaveQueueCommand(player);
                }
                return true;
            }
            rtpManager.handleDuelCommand(player, args);
            return true;
        }

        if (command.getName().equalsIgnoreCase("forfeit")) {
            if (!(sender instanceof Player player)) return true;
            if (!arenaManager.forfeitMatch(player)) {
                rtpManager.leaveQueueCommand(player);
            }
            return true;
        }

        if (!sender.hasPermission("rtpreset.admin")) {
            sender.sendMessage(toComponent(msgAdminNoPermission.replace("{prefix}", prefix)));
            return true;
        }

        if (command.getName().equalsIgnoreCase("setup")) {
            if (!(sender instanceof Player player)) return true;
            arenaManager.openSetupListGui(player);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            loadConfiguration();
            startTimerLoop();
            sender.sendMessage(toComponent(msgAdminReloadSuccess.replace("{prefix}", prefix)
                    .replace("{count}", String.valueOf(worldProfiles.size()))));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
            sender.sendMessage(toComponent(msgAdminManualResetAll.replace("{prefix}", prefix)));
            for (WorldProfile p : worldProfiles.values()) performWorldReset(p);
            return true;
        }

        if (args.length > 0) {
            String key = args[0].toLowerCase();
            WorldProfile profile = worldProfiles.get(key);
            if (profile == null) {
                for (WorldProfile p : worldProfiles.values()) {
                    if (p.targetWorld.equalsIgnoreCase(key)) { profile = p; break; }
                }
            }
            if (profile != null) {
                sender.sendMessage(toComponent(msgAdminManualResetSingle.replace("{prefix}", prefix)
                        .replace("{display_name}", profile.displayName).replace("{world}", profile.targetWorld)));
                performWorldReset(profile);
            } else {
                sender.sendMessage(toComponent(msgAdminWorldNotFound.replace("{prefix}", prefix)
                        .replace("{available}", String.join(", ", worldProfiles.keySet()))));
            }
            return true;
        }

        if (defaultWorldKey != null) {
            WorldProfile defProfile = worldProfiles.get(defaultWorldKey);
            sender.sendMessage(toComponent(msgAdminManualResetSingle.replace("{prefix}", prefix)
                    .replace("{display_name}", defProfile.displayName).replace("{world}", defProfile.targetWorld)));
            performWorldReset(defProfile);
        } else {
            sender.sendMessage(toComponent(msgAdminNoWorlds.replace("{prefix}", prefix)));
        }
        return true;
    }
}
