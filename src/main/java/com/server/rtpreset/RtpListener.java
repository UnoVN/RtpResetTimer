package com.server.rtpreset;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

public class RtpListener implements Listener {

    private final RtpResetTimer plugin;

    public RtpListener(RtpResetTimer plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        int slot = event.getRawSlot();

        if (holder instanceof RtpGuiHolder) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                plugin.getRtpManager().handleRtpClick(player, slot);
            }
        } else if (holder instanceof RtpManager.QueueGuiHolder queueHolder) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                plugin.getRtpManager().handleQueueGuiClick(player, queueHolder, slot);
            }
        } else if (holder instanceof RtpManager.DuelGuiHolder duelHolder) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                plugin.getRtpManager().handleDuelGuiClick(player, duelHolder, slot);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getRtpManager().removePlayerFromAllQueues(event.getPlayer().getUniqueId());
    }
}
