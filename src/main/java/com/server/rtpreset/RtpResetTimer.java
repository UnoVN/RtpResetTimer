package com.server.rtpreset;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

    // TỌA ĐỘ SẢNH LOBBY (157.5, 115.0, -5.5)
    private static final double SPAWN_X = 157.5;
    private static final double SPAWN_Y = 115.0;
    private static final double SPAWN_Z = -5.5;
    private static final float SPAWN_YAW = 90.0f;
    private static final float SPAWN_PITCH = 0.0f;

    // THIẾT LẬP WORLDBORDER
    private static final double BORDER_CENTER_X = 420.0;
    private static final double BORDER_CENTER_Z = -455.0;
    private static final double BORDER_SIZE = 838.0;

    private static final int RESET_INTERVAL_SECONDS = 1800; // 30 PHÚT
    private int remainingSeconds = RESET_INTERVAL_SECONDS;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RtpExpansion(this).register();
            getLogger().info("Placeholder %rtp_timer% da duoc dang ky thanh cong!");
        }

        if (getCommand("rtpreset") != null) {
            getCommand("rtpreset").setExecutor(this);
        }

        startTimerLoop();
        getLogger().info("RtpResetTimer da duoc khoi dong!");
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    private void broadcast(String message) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void startTimerLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                remainingSeconds--;

                switch (remainingSeconds) {
                    case 1200:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 20m");
                        break;
                    case 900:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 15m");
                        break;
                    case 600:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 10m");
                        break;
                    case 300:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 5m");
                        break;
                    case 180:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 3m");
                        break;
                    case 60:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 1m");
                        break;
                    case 30:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 30s");
                        break;
                    case 5:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 5s");
                        break;
                    case 4:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 4s");
                        break;
                    case 3:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 3s");
                        break;
                    case 2:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 2s");
                        break;
                    case 1:
                        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 1s");
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
        broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛᴛɪɴɢ ʀᴛᴘ ᴅᴇsᴇʀᴛ ᴍᴀᴘ...");

        // 1. DỊCH CHUYỂN NGƯỜI CHƠI TRONG RTPDESERT VỀ LOBBY
        World lobbyWorld = Bukkit.getWorld(LOBBY_WORLD_NAME);
        if (lobbyWorld != null) {
            Location lobbyLocation = new Location(lobbyWorld, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
            World rtpWorld = Bukkit.getWorld(RTP_WORLD_NAME);

            if (rtpWorld != null) {
                for (Player player : rtpWorld.getPlayers()) {
                    player.teleport(lobbyLocation);
                    player.sendMessage(color("<glyph:warning_blue> &bʏᴏᴜ ᴡᴇʀᴇ ᴛᴇʟᴇᴘᴏʀᴛᴇᴅ ᴛᴏ ʟᴏʙʙʏ ʙᴇᴄᴀᴜsᴇ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪs ʀᴇsᴇᴛᴛɪɴɢ!"));
                }
            }
        } else {
            getLogger().severe("Khong tim thay world sanh: " + LOBBY_WORLD_NAME);
        }

        // 2. UNLOAD KHỎI BỘ NHỚ RAM
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload " + RTP_WORLD_NAME);

        // 3. CHỜ 3 GIÂY (60 TICKS) ĐỂ GIẢI PHÓNG RAM RỒI NẠP LẠI
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load " + RTP_WORLD_NAME);

            // 4. CHỜ 2 GIÂY (40 TICKS) ĐỂ KHỞI TẠO XONG CHUNK RỒI ÁP ĐẶT WORLDBORDER
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World loadedWorld = Bukkit.getWorld(RTP_WORLD_NAME);
                if (loadedWorld != null) {
                    WorldBorder border = loadedWorld.getWorldBorder();
                    border.setCenter(BORDER_CENTER_X, BORDER_CENTER_Z);
                    border.setSize(BORDER_SIZE);
                    broadcast("<glyph:warning_blue> &bʀᴛᴘ ᴅᴇsᴇʀᴛ ʜᴀs ʙᴇᴇɴ sᴜᴄᴄᴇssғᴜʟʟʏ ʀᴇsᴇᴛ!");
                    broadcast("<glyph:warning_blue> &bʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 30m");
                } else {
                    getLogger().severe("Khong the tim thay world " + RTP_WORLD_NAME + " sau khi load!");
                }
            }, 40L);
        }, 60L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("rtpreset.admin")) {
            sender.sendMessage(color("<glyph:warning_blue> &cʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪssɪᴏɴ ᴛᴏ ᴜsᴇ ᴛʜɪs ᴄᴏᴍᴍᴀɴᴅ!"));
            return true;
        }

        sender.sendMessage(color("<glyph:warning_blue> &bᴍᴀɴᴜᴀʟʟʏ ʀᴇsᴇᴛᴛɪɴɢ ʀᴛᴘ ᴅᴇsᴇʀᴛ..."));
        remainingSeconds = RESET_INTERVAL_SECONDS;
        performWorldReset();
        return true;
    }
}
