package com.server.rtpreset;

import net.md_5.bungee.api.ChatColor;
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // BỘ PHÂN TÍCH MÃ MÀU HEX (CATPPUCCIN PALETTE)
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

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
        getLogger().info("RtpResetTimer (Catppuccin Edition) da duoc khoi dong!");
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
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

    private void broadcast(String message) {
        Bukkit.broadcastMessage(color(message));
    }

    private void startTimerLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                remainingSeconds--;

                switch (remainingSeconds) {
                    // MỐC BÌNH THƯỜNG: SKY (#89dceb) + LAVENDER (#b4befe)
                    case 1200:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#b4befe;20m");
                        break;
                    case 900:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#b4befe;15m");
                        break;
                    case 600:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#b4befe;10m");
                        break;
                    case 300:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#b4befe;5m");
                        break;
                    case 180:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#b4befe;3m");
                        break;

                    // MỐC CẢNH BÁO: SKY (#89dceb) + PEACH (#fab387)
                    case 60:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#fab387;1m");
                        break;
                    case 30:
                        broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#fab387;30s");
                        break;

                    // MỐC KHẨN CẤP: RED (#f38ba8)
                    case 5:
                        broadcast("<glyph:warning_blue> &#f38ba8;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 5s");
                        break;
                    case 4:
                        broadcast("<glyph:warning_blue> &#f38ba8;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 4s");
                        break;
                    case 3:
                        broadcast("<glyph:warning_blue> &#f38ba8;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 3s");
                        break;
                    case 2:
                        broadcast("<glyph:warning_blue> &#f38ba8;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 2s");
                        break;
                    case 1:
                        broadcast("<glyph:warning_blue> &#f38ba8;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ 1s");
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
        // ĐANG RESET: MAUVE (#cba6f7)
        broadcast("<glyph:warning_blue> &#cba6f7;ʀᴇsᴇᴛᴛɪɴɢ ʀᴛᴘ ᴅᴇsᴇʀᴛ ᴍᴀᴘ...");

        // 1. DỊCH CHUYỂN NGƯỜI CHƠI VỀ LOBBY AN TOÀN
        World lobbyWorld = Bukkit.getWorld(LOBBY_WORLD_NAME);
        if (lobbyWorld != null) {
            Location lobbyLocation = new Location(lobbyWorld, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
            World rtpWorld = Bukkit.getWorld(RTP_WORLD_NAME);

            if (rtpWorld != null) {
                for (Player player : rtpWorld.getPlayers()) {
                    player.teleport(lobbyLocation);
                    player.sendMessage(color("<glyph:warning_blue> &#89b4fa;ʏᴏᴜ ᴡᴇʀᴇ ᴛᴇʟᴇᴘᴏʀᴛᴇᴅ ᴛᴏ ʟᴏʙʙʏ ʙᴇᴄᴀᴜsᴇ &#b4befe;ʀᴛᴘ ᴅᴇsᴇʀᴛ &#89b4fa;ɪs ʀᴇsᴇᴛᴛɪɴɢ!"));
                }
            }
        } else {
            getLogger().severe("Khong tim thay world sanh: " + LOBBY_WORLD_NAME);
        }

        // 2. UNLOAD KHỎI RAM
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload " + RTP_WORLD_NAME);

        // 3. ĐỢI 3 GIÂY (60 TICKS) ĐỂ GIẢI PHÓNG RAM RỒI NẠP LẠI
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load " + RTP_WORLD_NAME);

            // 4. ĐỢI 2 GIÂY (40 TICKS) ĐỂ CHUNK HOÀN THIỆN RỒI THIẾT LẬP BORDER
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World loadedWorld = Bukkit.getWorld(RTP_WORLD_NAME);
                if (loadedWorld != null) {
                    WorldBorder border = loadedWorld.getWorldBorder();
                    border.setCenter(BORDER_CENTER_X, BORDER_CENTER_Z);
                    border.setSize(BORDER_SIZE);

                    // HOÀN TẤT: GREEN (#a6e3a1)
                    broadcast("<glyph:warning_blue> &#a6e3a1;ʀᴛᴘ ᴅᴇsᴇʀᴛ ʜᴀs ʙᴇᴇɴ sᴜᴄᴄᴇssғᴜʟʟʏ ʀᴇsᴇᴛ!");
                    broadcast("<glyph:warning_blue> &#89dceb;ʀᴇsᴇᴛ ᴍᴀᴘ ʀᴛᴘ ᴅᴇsᴇʀᴛ ɪɴ &#b4befe;30m");
                } else {
                    getLogger().severe("Khong the tim thay world " + RTP_WORLD_NAME + " sau khi load!");
                }
            }, 40L);
        }, 60L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("rtpreset.admin")) {
            sender.sendMessage(color("<glyph:warning_blue> &#f38ba8;ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪssɪᴏɴ ᴛᴏ ᴜsᴇ ᴛʜɪs ᴄᴏᴍᴍᴀɴᴅ!"));
            return true;
        }

        sender.sendMessage(color("<glyph:warning_blue> &#fab387;ᴍᴀɴᴜᴀʟʟʏ ʀᴇsᴇᴛᴛɪɴɢ ʀᴛᴘ ᴅᴇsᴇʀᴛ..."));
        remainingSeconds = RESET_INTERVAL_SECONDS;
        performWorldReset();
        return true;
    }
}
