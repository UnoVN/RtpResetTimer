public void performWorldReset() {
        Bukkit.broadcastMessage("§c[Reset] §fĐang tiến hành làm mới thế giới §artpdessert§f...");

        // 1. Dịch chuyển người chơi ra khỏi rtpdessert về spawn
        World rtpWorld = Bukkit.getWorld("rtpdessert");
        if (rtpWorld != null) {
            for (Player player : rtpWorld.getPlayers()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spawn " + player.getName());
                player.sendMessage("§cBạn đã được đưa về Spawn vì khu vực đang reset!");
            }
        }

        // 2. Unload thế giới khỏi RAM
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm unload rtpdessert");

        // 3. Đợi 3 giây (60 ticks) để SWM giải phóng bộ nhớ rồi nạp lại (bỏ chữ 'file')
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "swm load rtpdessert");

            // 4. Đợi thêm 2 giây (40 ticks) để SWM nạp xong chunk rồi gắn WorldBorder
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World loadedWorld = Bukkit.getWorld("rtpdessert");
                if (loadedWorld != null) {
                    WorldBorder border = loadedWorld.getWorldBorder();
                    border.setCenter(420.0, -455.0);
                    border.setSize(841.0);
                    Bukkit.broadcastMessage("§a[Hệ Thống] §fThế giới §artpdessert §fđã được làm mới hoàn tất!");
                } else {
                    getLogger().warning("Khong the tim thay world rtpdessert sau khi load!");
                }
            }, 40L);
        }, 60L);
    }
