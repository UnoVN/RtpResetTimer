package com.server.rtpreset;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

public final class RtpResetTimer extends JavaPlugin implements CommandExecutor {

    private static final String RTP_WORLD_NAME = "rtpdesert";
    private static final String LOBBY_WORLD_NAME = "world";

    // LOBBY SPAWN COORDINATES (157.500, 115.000, -5.500)
    private static final double SPAWN_X = 157.5;
    private static final double SPAWN_Y = 115.0;
    private static final double SPAWN_Z = -5.5; // NOTE: -5.500 (CHANGE TO -5500.0 ONLY IF YOUR PLATFORM IS AT -5500)
    private static final float SPAWN_YAW = 90.0f;
    private static final float SPAWN_PITCH = 0.0f;

    // WORLDBORDER CONFIGURATION
    private static final double BORDER_CENTER_X = 420.0;
    private static final double BORDER_CENTER_Z = -455.0;
    private static final double BORDER_SIZE = 838.0;

    private static final int RESET_INTERVAL_SECONDS = 1800; // 30 MINUTES
    private int remainingSeconds = RESET_INTERVAL_SECONDS;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RtpExpansion(this).register();
            getLogger().info("PLACEHOLDER %rtp_timer% REGISTERED SUCCESSFULLY!");
        }

        if (getCommand("rtpreset") != null) {
            getCommand("rtpreset").setExecutor(this);
        }

        startTimerLoop();
        getLogger().info("RTPRESETTIMER ENABLED SUCCESSFULLY!");
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    private void startTimerLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                remainingSeconds--;

                switch (remainingSeconds) {
                    case 1200:
                        Bukkit.broadcastMessage("§6§l[RTP] §eRTPDESERT WILL RESET IN 20 MINUTES!");
                        break;
                    case 900:
                        Bukkit.broadcastMessage("§6§l[RTP] §eRTPDESERT WILL RESET IN 15 MINUTES!");
                        break;
                    case 600:
                        Bukkit.broadcastMessage("§6§l[RTP] §eRTPDESERT WILL RESET IN 10 MINUTES!");
                        break;
                    case 300:
                        Bukkit.broadcastMessage("§e§l[RTP] §eRTPDESERT WILL RESET IN 5 MINUTES!");
                        break;
                    case 180:
                        Bukkit.broadcastMessage("§e§l[RTP] §eRTPDESERT WILL RESET IN 3 MINUTES!");
                        break;
                    case 60:
                        Bukkit.broadcastMessage("§c§l[RTP] §cRTPDESERT WILL RESET IN 1 MINUTE!");
                        break;
                    case 30:
                        Bukkit.broadcastMessage("§c§l[RTP] §cRTPDESERT WILL RESET IN 30 SECONDS!");
                        break;
                    case 5:
                        Bukkit.broadcastMessage("§c§l[RTP] §cRTPDESERT WILL RESET IN 5 SECONDS!");
                        break;
                    case 4:
                        Bukkit.broadcastMessage("§c§l[RTP] §cRTPDESERT WILL RESET IN 4 SECONDS!");
                        break;
                    case 3:
                        Bukkit.broadcastMessage("§c§l[RTP] §cRTPDESERT WILL RESET IN 3 SECONDS!");
                        break;
                    case 2:
                        Bukkit.broadcastMessage("§c§l[RTP] §cRTPDESERT WILL RESET IN 2 SECONDS!");
                        break;
                    case 1:
                        Bukkit.broadcastMessage("§4§l[RTP] §cRTPDESERT WILL RESET IN 1 SECOND! TELEPORTING PLAYERS TO LOBBY...");
                        break;
                }

                if (remainingSeconds <= 0) {
                    remainingSeconds = RESET_INTERVAL_SECONDS;
                    performWorldReset();
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    public void performWorldReset() {
        Bukkit.broadcastMessage("§4§l[RESET] §cRESETTING RTPDESERT MAP...");

        // 1. SAFELY TELEPORT PLAYERS ONLY FROM RTPDESERT TO THE EXACT LOBBY COORDINATES
        World lobbyWorld = Bukkit.getWorld(LOBBY_WORLD_NAME);
        if (lobbyWorld != null) {
            Location lobbyLocation = new Location(lobbyWorld, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
            World rtpWorld = Bukkit.getWorld(RTP_WORLD_NAME);

            if (rtpWorld != null) {
                for (Player player : rtpWorld.getPlayers()) {
                    player.teleport(lobbyLocation);
                    player.sendMessage("§C§LYOU WERE TELEPORTED TO LOBBY BECAUSE RTPDESERT IS RESETTING!");
                }
            }
        } else {
            getLogger().severe("LOBBY WORLD '" + LOBBY_WORLD_NAME + "' NOT FOUND! SKIPPING TELEPORTATION.");
        }

        // 2. UNLOAD RTPDESERT FROM RAM
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload " + RTP_WORLD_NAME);

        // 3. WAIT 3 SECONDS (60 TICKS) FOR MEMORY RELEASE, THEN LOAD THE WORLD
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load " + RTP_WORLD_NAME);

            // 4. WAIT 2 SECONDS (40 TICKS) FOR CHUNKS TO INITIALIZE, THEN APPLY WORLDBORDER
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World loadedWorld = Bukkit.getWorld(RTP_WORLD_NAME);
                if (loadedWorld != null) {
                    WorldBorder border = loadedWorld.getWorldBorder();
                    border.setCenter(BORDER_CENTER_X, BORDER_CENTER_Z);
                    border.setSize(BORDER_SIZE);
                    Bukkit.broadcastMessage("§A§L[RTP] §aRTPDESERT HAS BEEN SUCCESSFULLY RESET!");
                } else {
                    getLogger().severe("FAILED TO FIND WORLD " + RTP_WORLD_NAME + " AFTER LOADING!");
                }
            }, 40L);
        }, 60L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("rtpreset.admin")) {
            sender.sendMessage("§C§LYOU DO NOT HAVE PERMISSION TO USE THIS COMMAND!");
            return true;
        }

        sender.sendMessage("§E§LMANUALLY RESETTING WORLD " + RTP_WORLD_NAME.toUpperCase() + "...");
        remainingSeconds = RESET_INTERVAL_SECONDS;
        performWorldReset();
        return true;
    }
}
