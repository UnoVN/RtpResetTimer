package com.server.rtpreset;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class RtpExpansion extends PlaceholderExpansion {

    private final RtpResetTimer plugin;

    public RtpExpansion(RtpResetTimer plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rtp";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Nhan";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // %rtp_timer% -> tra ve thoi gian cua world mac dinh
        if (params.equalsIgnoreCase("timer")) {
            int remaining = plugin.getRemainingSeconds();
            if (remaining < 0) return "00:00";
            int minutes = remaining / 60;
            int seconds = remaining % 60;
            return String.format("%02d:%02d", minutes, seconds);
        }

        // %rtp_timer_<key>% (vi du: %rtp_timer_desert%, %rtp_timer_plains%)
        if (params.toLowerCase().startsWith("timer_")) {
            String worldKey = params.substring(6);
            int remaining = plugin.getRemainingSeconds(worldKey);
            if (remaining < 0) return "00:00";
            int minutes = remaining / 60;
            int seconds = remaining % 60;
            return String.format("%02d:%02d", minutes, seconds);
        }

        return null;
    }
}
