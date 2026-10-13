package com.server.rtpreset;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class RtpListener implements Listener {

    private final RtpResetTimer plugin;

    public RtpListener(RtpResetTimer plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getInventory().getHolder() instanceof RtpGuiHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getInventory().getSize()) {
                plugin.getRtpManager().handleRtpClick(player, event.getRawSlot());
            }
        } else if (event.getInventory().getHolder() instanceof RtpQueueGuiHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getInventory().getSize()) {
                plugin.getRtpManager().handleQueueClick(player, event.getRawSlot());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getRtpManager().removePlayerFromAllQueues(event.getPlayer().getUniqueId());
    }
}
