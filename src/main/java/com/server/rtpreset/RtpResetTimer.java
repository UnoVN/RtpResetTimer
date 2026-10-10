package com.server.rtpreset;

import org.bukkit.Bukkit;
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

    private static final String WORLD_NAME = "rtpdesert"; // Đúng 1 chữ 's'
    private static final int RESET_INTERVAL_SECONDS = 1800; // 30 phút
    private int remainingSeconds = RESET_INTERVAL_SECONDS;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RtpExpansion(this).register();
            getLogger().info("Da dang ky thanh cong placeholder %rtp_timer%!");
        }

        if (getCommand("rtpreset") != null) {
            getCommand("rtpreset").setExecutor(this);
        }

        startTimerLoop();
        getLogger().info("RtpResetTimer da duoc kich hoat!");
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    private void startTimerLoop() {
        new BukkitRunnable() {
            @Override
            public void run() {
                remainingSeconds--;

                if (remainingSeconds == 300) {
                    Bukkit.broadcastMessage("§e[Hệ Thống] §fKhu vực §a" + WORLD_NAME + " §fsẽ reset sau §e5 phút§f!");
                } else if (remainingSeconds == 60) {
                    Bukkit.broadcastMessage("§e[Hệ Thống] §fKhu vực §a" + WORLD_NAME + " §fsẽ reset sau §e1 phút§f!");
                } else if (remainingSeconds == 10) {
                    Bukkit.broadcastMessage("§c[Cảnh Báo] §fKhu vực §a" + WORLD_NAME + " §fsẽ reset sau §c10 giây§f! Đang đưa về Spawn...");
                } else if (remainingSeconds <= 0) {
                    remainingSeconds = RESET_INTERVAL_SECONDS;
                    performWorldReset();
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    public void performWorldReset() {
        Bukkit.broadcastMessage("§c[Reset] §fĐang tiến hành làm mới thế giới §a" + WORLD_NAME + "§f...");

        // 1. Dịch chuyển người chơi ra khỏi rtpdesert về spawn
        World rtpWorld = Bukkit.getWorld(WORLD_NAME);
        if (rtpWorld != null) {
            for (Player player : rtpWorld.getPlayers()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spawn " + player.getName());
                player.sendMessage("§cBạn đã được đưa về Spawn vì khu vực đang reset!");
            }
        }

        // 2. Unload thế giới
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload " + WORLD_NAME);

        // 3. Đợi 3 giây (60 ticks) để giải phóng RAM rồi nạp lại (không có tham số 'file')
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load " + WORLD_NAME);

            // 4. Đợi 2 giây (40 ticks) để thế giới nạp hoàn chỉnh rồi gắn WorldBorder
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World loadedWorld = Bukkit.getWorld(WORLD_NAME);
                if (loadedWorld != null) {
                    WorldBorder border = loadedWorld.getWorldBorder();
                    border.setCenter(420.0, -455.0);
                    border.setSize(841.0);
                    Bukkit.broadcastMessage("§a[Hệ Thống] §fThế giới §a" + WORLD_NAME + " §fđã được làm mới hoàn tất!");
                } else {
                    getLogger().warning("Khong tim thay world " + WORLD_NAME + " sau khi load!");
                }
            }, 40L);
        }, 60L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("rtpreset.admin")) {
            sender.sendMessage("§cBạn không có quyền thực hiện lệnh này!");
            return true;
        }

        sender.sendMessage("§eĐang cưỡng chế reset thế giới " + WORLD_NAME + "...");
        remainingSeconds = RESET_INTERVAL_SECONDS;
        performWorldReset();
        return true;
    }
}
