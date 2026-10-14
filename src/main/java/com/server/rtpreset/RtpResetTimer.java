package com.server.rtpreset;

import net.md_5.bungee.api.ChatColor;
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
        public List<String> postResetCommands;
        public boolean isResetting = false;

        public WorldProfile(String key, String targetWorld, String displayName, int resetIntervalSeconds,
                            double borderCenterX, double borderCenterZ, double borderSize, List<String> postResetCommands) {
            this.key = key;
            this.targetWorld = targetWorld;
            this.displayName = displayName;
            this.resetIntervalSeconds = resetIntervalSeconds;
            this.remainingSeconds = resetIntervalSeconds;
            this.borderCenterX = borderCenterX;
            this.borderCenterZ = borderCenterZ;
            this.borderSize = borderSize;
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
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6});?");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        this.rtpManager = new RtpManager(this);
        getServer().getPluginManager().registerEvents(new RtpListener(this), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RtpExpansion(this).register();
            getLogger().info("Placeholder %rtp_timer% registered successfully!");
        }

        if (getCommand("rtpreset") != null) getCommand("rtpreset").setExecutor(this);
        if (getCommand("rtp") != null) getCommand("rtp").setExecutor(this);
        if (getCommand("rtpq") != null) getCommand("rtpq").setExecutor(this);

        startTimerLoop();
        getLogger().info("RtpResetTimer 1.2.0 enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (mainTimerTask != null) mainTimerTask.cancel();
    }

    public RtpManager getRtpManager() {
        return rtpManager;
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
        this.msgCountdownNormal = getConfig().getString("messages.countdown-normal", "{prefix}&#89dcebReset map {display_name} in &#b4befe{time}");
        this.msgCountdownWarning = getConfig().getString("messages.countdown-warning", "{prefix}&#89dcebReset map {display_name} in &#fab387{time}");
        this.msgCountdownUrgent = getConfig().getString("messages.countdown-urgent", "{prefix}&#f38ba8Reset map {display_name} in {time}");

        this.msgResetting = getConfig().getString("messages.resetting", "{prefix}&#cba6f7Resetting {display_name} map...");
        this.msgPlayerTeleported = getConfig().getString("messages.player-teleported", "{prefix}&#89b4faYou were teleported to lobby because &#b4befe{display_name} &#89b4fais resetting!");
        this.msgResetSuccess = getConfig().getString("messages.reset-success", "{prefix}&#a6e3a1{display_name} has been successfully reset!");
        this.msgNextReset = getConfig().getString("messages.next-reset", "{prefix}&#89dcebReset map {display_name} in &#b4befe{time}");

        this.msgAdminNoPermission = getConfig().getString("messages.admin.no-permission", "{prefix}&#f38ba8You do not have permission to use this command!");
        this.msgAdminReloadSuccess = getConfig().getString("messages.admin.reload-success", "{prefix}&#a6e3a1Configuration reloaded! Loaded &#89dceb{count} &#a6e3a1worlds.");
        this.msgAdminManualResetSingle = getConfig().getString("messages.admin.manual-reset-single", "{prefix}&#fab387Manually resetting {display_name}...");
        this.msgAdminManualResetAll = getConfig().getString("messages.admin.manual-reset-all", "{prefix}&#fab387Manually resetting all configured worlds...");
        this.msgAdminWorldNotFound = getConfig().getString("messages.admin.world-not-found", "{prefix}&#f38ba8World not found! Available: &#89dceb{available}");
        this.msgAdminNoWorlds = getConfig().getString("messages.admin.no-worlds", "{prefix}&#f38ba8No worlds configured in config.yml!");

        ConfigurationSection section = getConfig().getConfigurationSection("worlds");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String target = section.getString(key + ".target-world", key);
                String display = section.getString(key + ".display-name", key);
                int interval = section.getInt(key + ".reset-interval-seconds", 1800);
                double bX = section.getDouble(key + ".worldborder.center-x", 0.0);
                double bZ = section.getDouble(key + ".worldborder.center-z", 0.0);
                double bSize = section.getDouble(key + ".worldborder.size", 1000.0);
                List<String> cmds = section.getStringList(key + ".post-reset-commands");

                WorldProfile profile = new WorldProfile(key, target, display, interval, bX, bZ, bSize, cmds);
                worldProfiles.put(key.toLowerCase(), profile);
                if (defaultWorldKey == null) defaultWorldKey = key.toLowerCase();
            }
        }

        if (rtpManager != null) rtpManager.loadConfig();
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

    public String color(String message) {
        if (message == null) return "";
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private String formatMessage(String template, WorldProfile profile, String timeStr) {
        String res = template.replace("{prefix}", prefix);
        if (profile != null) {
            res = res.replace("{display_name}", profile.displayName).replace("{world}", profile.targetWorld);
        }
        if (timeStr != null) res = res.replace("{time}", timeStr);
        return color(res);
    }

    private void broadcast(String formattedMessage) {
        Bukkit.broadcastMessage(formattedMessage);
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
                                broadcast(formatMessage(msgCountdownNormal, profile, formatDuration(profile.remainingSeconds)));
                        case 60, 30 ->
                                broadcast(formatMessage(msgCountdownWarning, profile, formatDuration(profile.remainingSeconds)));
                        case 5, 4, 3, 2, 1 ->
                                broadcast(formatMessage(msgCountdownUrgent, profile, profile.remainingSeconds + "s"));
                    }

                    if (profile.remainingSeconds <= 0) {
                        profile.remainingSeconds = profile.resetIntervalSeconds;
                        performWorldReset(profile);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    public void performWorldReset(WorldProfile profile) {
        profile.isResetting = true;
        broadcast(formatMessage(msgResetting, profile, null));

        World lobbyWorld = Bukkit.getWorld(lobbyWorldName);
        if (lobbyWorld != null) {
            Location lobbyLocation = new Location(lobbyWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
            World targetWorldObj = Bukkit.getWorld(profile.targetWorld);
            if (targetWorldObj != null) {
                for (Player player : targetWorldObj.getPlayers()) {
                    player.teleport(lobbyLocation);
                    player.sendMessage(formatMessage(msgPlayerTeleported, profile, null));
                }
            }
        } else {
            getLogger().severe("Lobby world not found: " + lobbyWorldName);
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload " + profile.targetWorld);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load " + profile.targetWorld);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                World loadedWorld = Bukkit.getWorld(profile.targetWorld);
                if (loadedWorld != null) {
                    WorldBorder border = loadedWorld.getWorldBorder();
                    border.setCenter(profile.borderCenterX, profile.borderCenterZ);
                    border.setSize(profile.borderSize);
                    loadedWorld.setTime(1000L);

                    for (String cmd : profile.postResetCommands) {
                        String trimmed = cmd.trim();
                        if (trimmed.startsWith("time set")) continue;
                        String finalCmd = trimmed.startsWith("execute in ") ? trimmed : "execute in " + profile.targetWorld + " run " + trimmed;
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    }

                    broadcast(formatMessage(msgResetSuccess, profile, null));
                    broadcast(formatMessage(msgNextReset, profile, formatDuration(profile.resetIntervalSeconds)));
                } else {
                    getLogger().severe("World " + profile.targetWorld + " not found after loading!");
                }
                profile.isResetting = false;
            }, 40L);
        }, 60L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("rtp")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is only for players!");
                return true;
            }
            rtpManager.openRtpGui(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("rtpq")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This command is only for players!");
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("leave")) {
                rtpManager.leaveQueueCommand(player);
                return true;
            }
            rtpManager.openRtpQueueGui(player);
            return true;
        }

        if (!sender.hasPermission("rtpreset.admin")) {
            sender.sendMessage(color(msgAdminNoPermission.replace("{prefix}", prefix)));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            loadConfiguration();
            startTimerLoop();
            sender.sendMessage(color(msgAdminReloadSuccess.replace("{prefix}", prefix)
                    .replace("{count}", String.valueOf(worldProfiles.size()))));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("all")) {
            sender.sendMessage(color(msgAdminManualResetAll.replace("{prefix}", prefix)));
            for (WorldProfile p : worldProfiles.values()) {
                p.remainingSeconds = p.resetIntervalSeconds;
                performWorldReset(p);
            }
            return true;
        }

        if (args.length > 0) {
            String key = args[0].toLowerCase();
            WorldProfile profile = worldProfiles.get(key);
            if (profile == null) {
                for (WorldProfile p : worldProfiles.values()) {
                    if (p.targetWorld.equalsIgnoreCase(key)) {
                        profile = p;
                        break;
                    }
                }
            }

            if (profile != null) {
                sender.sendMessage(color(msgAdminManualResetSingle.replace("{prefix}", prefix)
                        .replace("{display_name}", profile.displayName).replace("{world}", profile.targetWorld)));
                profile.remainingSeconds = profile.resetIntervalSeconds;
                performWorldReset(profile);
            } else {
                sender.sendMessage(color(msgAdminWorldNotFound.replace("{prefix}", prefix)
                        .replace("{available}", String.join(", ", worldProfiles.keySet()))));
            }
            return true;
        }

        if (defaultWorldKey != null) {
            WorldProfile defProfile = worldProfiles.get(defaultWorldKey);
            sender.sendMessage(color(msgAdminManualResetSingle.replace("{prefix}", prefix)
                    .replace("{display_name}", defProfile.displayName).replace("{world}", defProfile.targetWorld)));
            defProfile.remainingSeconds = defProfile.resetIntervalSeconds;
            performWorldReset(defProfile);
        } else {
            sender.sendMessage(color(msgAdminNoWorlds.replace("{prefix}", prefix)));
        }
        return true;
    }
}
