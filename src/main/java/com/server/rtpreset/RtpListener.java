package com.server.rtpreset;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;

@SuppressWarnings("deprecation")
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
                plugin.getRtpManager().handleDuelGuiClick(player, duelHolder, slot, event.getClick());
            }
        } else if (holder instanceof ArenaManager.SetupListHolder) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                plugin.getArenaManager().handleSetupListClick(player, slot, event.getClick());
            }
        } else if (holder instanceof ArenaManager.SetupEditHolder editHolder) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                plugin.getArenaManager().handleSetupEditClick(player, editHolder, slot);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (plugin.getArenaManager().isBetweenRounds(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        plugin.getArenaManager().handleChatInput(event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        plugin.getArenaManager().handlePlayerDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        plugin.getArenaManager().handlePlayerRespawn(event);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getRtpManager().removePlayerFromAllQueues(event.getPlayer().getUniqueId());
        plugin.getArenaManager().handlePlayerQuit(event.getPlayer());
    }
}
