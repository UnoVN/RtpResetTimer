package com.server.rtpreset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RtpManager {

    public static class RtpItem {
        public String key, name, icon, modeDisplay, mapDisplay, worldName;
        public int slot, minX, maxX, minZ, maxZ;
        public Material material;
        public List<String> lore;
    }

    public static class DuelGuiHolder implements InventoryHolder {
        public final UUID targetUuid;
        public String selectedModeKey;

        public DuelGuiHolder(UUID targetUuid, String selectedModeKey) {
            this.targetUuid = targetUuid;
            this.selectedModeKey = selectedModeKey;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return null;
        }
    }

    public record DuelRequest(UUID senderUuid, String modeKey, long timestamp) {}

    private final RtpResetTimer plugin;
    private final Map<String, RtpItem> rtpItems = new LinkedHashMap<>();
    private final Map<String, RtpItem> rtpqItems = new LinkedHashMap<>();
    private final Map<String, List<UUID>> queueMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queueJoinTime = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, DuelRequest>> duelRequests = new ConcurrentHashMap<>();
    private final Set<UUID> inCountdown = ConcurrentHashMap.newKeySet();
    private BukkitTask queueMonitorTask;

    public RtpManager(RtpResetTimer plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void shutdown() {
        if (queueMonitorTask != null) {
            queueMonitorTask.cancel();
            queueMonitorTask = null;
        }
        queueMap.clear();
        queueJoinTime.clear();
        duelRequests.clear();
        inCountdown.clear();
    }

    public void loadConfig() {
        shutdown();
        rtpItems.clear();
        rtpqItems.clear();

        ConfigurationSection rtpSec = plugin.getConfig().getConfigurationSection("rtp-menu.items");
        if (rtpSec != null) {
            for (String key : rtpSec.getKeys(false)) {
                ConfigurationSection sub = rtpSec.getConfigurationSection(key);
                if (sub != null) rtpItems.put(key, parseItem(sub, key));
            }
        }

        ConfigurationSection rtpqSec = plugin.getConfig().getConfigurationSection("rtpq-menu.items");
        if (rtpqSec != null) {
            for (String key : rtpqSec.getKeys(false)) {
                ConfigurationSection sub = rtpqSec.getConfigurationSection(key);
                if (sub != null) {
                    rtpqItems.put(key, parseItem(sub, key));
                    queueMap.put(key, new ArrayList<>());
                }
            }
        }
        startQueueMonitor();
    }

    private RtpItem parseItem(ConfigurationSection sec, String key) {
        RtpItem item = new RtpItem();
        item.key = key;
        item.slot = sec.getInt("slot", 0);
        Material mat = Material.matchMaterial(sec.getString("material", "STONE").toUpperCase());
        item.material = mat != null ? mat : Material.STONE;
        item.icon = sec.getString("icon", "<white><sprite:items:item/crossbow_standby></white>");
        item.name = sec.getString("name", key);
        item.modeDisplay = sec.getString("mode-display", key.toUpperCase());
        item.mapDisplay = sec.getString("map-display", "RTP Desert");
        item.lore = sec.getStringList("lore");
        item.worldName = sec.getString("world", "rtpdesert");
        item.minX = sec.getInt("min-x", 0);
        item.maxX = sec.getInt("max-x", 100);
        item.minZ = sec.getInt("min-z", 0);
        item.maxZ = sec.getInt("max-z", 100);
        return item;
    }

    private void startQueueMonitor() {
        queueMonitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                boolean timeoutEnabled = plugin.getConfig().getBoolean("rtpq-menu.queue-timeout.enabled", true);
                int maxTimeoutSec = plugin.getConfig().getInt("rtpq-menu.queue-timeout.timeout-seconds", 120);
                long now = System.currentTimeMillis();

                for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
                    RtpItem item = rtpqItems.get(entry.getKey());
                    if (item == null) continue;
                    Iterator<UUID> it = entry.getValue().iterator();
                    while (it.hasNext()) {
                        UUID uuid = it.next();
                        Player p = Bukkit.getPlayer(uuid);
                        if (p == null || !p.isOnline()) {
                            it.remove();
                            queueJoinTime.remove(uuid);
                            continue;
                        }
                        int elapsedSec = (int) ((now - queueJoinTime.getOrDefault(uuid, now)) / 1000L);
                        if (timeoutEnabled && elapsedSec >= maxTimeoutSec) {
                            it.remove();
                            queueJoinTime.remove(uuid);
                            String tf = String.format("%02d:%02d", maxTimeoutSec / 60, maxTimeoutSec % 60);
                            p.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-timeout", "{prefix}&#f38ba8Queue timed out!"), item).replace("{timeout}", tf)));
                            p.sendActionBar(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-timeout-actionbar", "&#f38ba8&lQUEUE TIMED OUT!"), item)));
                            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                            continue;
                        }
                        String elapsedStr = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60);
                        p.sendActionBar(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-waiting-actionbar", "&#89dcebQueue: {icon} &#fab387{mode} &8| &#a6e3a1{elapsed}"), item).replace("{elapsed}", elapsedStr)));
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private String applyPlaceholders(String text, RtpItem item) {
        if (text == null || item == null) return text != null ? text : "";
        return text.replace("{display_name}", item.name)
                   .replace("{mode}", item.modeDisplay)
                   .replace("{map}", item.mapDisplay)
                   .replace("{world}", item.worldName)
                   .replace("{icon}", item.icon);
    }

    private String applyDuelPlaceholders(String text, Player sender, Player target, RtpItem item) {
        String s = applyPlaceholders(text, item);
        if (sender != null) {
            s = s.replace("{sender_name}", sender.getName()).replace("{sender_prefix}", getPlayerPrefix(sender));
        }
        if (target != null) {
            s = s.replace("{target_name}", target.getName()).replace("{target_prefix}", getPlayerPrefix(target)).replace("{target}", target.getName());
        }
        return s;
    }

    private void applyFiller(Inventory inv, String menuPath) {
        if (!plugin.getConfig().getBoolean(menuPath + ".filler.enabled", true)) return;
        Material mat = Material.matchMaterial(plugin.getConfig().getString(menuPath + ".filler.material", "BLACK_STAINED_GLASS_PANE").toUpperCase());
        ItemStack filler = new ItemStack(mat != null ? mat : Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.toComponent(plugin.getConfig().getString(menuPath + ".filler.name", " ")).decoration(TextDecoration.ITALIC, false));
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    public void openRtpGui(Player player) {
        Component title = plugin.toComponent(plugin.getConfig().getString("rtp-menu.title", "&#89b4fa&lRANDOM TELEPORT"));
        int size = plugin.getConfig().getInt("rtp-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpGuiHolder(), size, title);
        applyFiller(inv, "rtp-menu");

        for (RtpItem item : rtpItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                meta.displayName(plugin.toComponent(applyPlaceholders(item.name, item)).decoration(TextDecoration.ITALIC, false));
                List<Component> lore = new ArrayList<>();
                for (String l : item.lore) lore.add(plugin.toComponent(applyPlaceholders(l, item)).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
        }
        player.openInventory(inv);
    }

    public void openRtpQueueGui(Player player) {
        Component title = plugin.toComponent(plugin.getConfig().getString("rtpq-menu.title", "&#fab387&l1V1 BATTLE QUEUE"));
        int size = plugin.getConfig().getInt("rtpq-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new RtpQueueGuiHolder(), size, title);
        applyFiller(inv, "rtpq-menu");

        String actJoin = plugin.getConfig().getString("rtpq-menu.action-join", "<glyph:left_click> &#a6e3a1Click to join queue");
        String actLeave = plugin.getConfig().getString("rtpq-menu.action-leave", "<glyph:left_click> &#f38ba8Click to leave queue");

        for (RtpItem item : rtpqItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                meta.displayName(plugin.toComponent(applyPlaceholders(item.name, item)).decoration(TextDecoration.ITALIC, false));
                List<UUID> q = queueMap.getOrDefault(item.key, Collections.emptyList());
                String clickAct = q.contains(player.getUniqueId()) ? actLeave : actJoin;
                List<Component> lore = new ArrayList<>();
                for (String l : item.lore) {
                    lore.add(plugin.toComponent(applyPlaceholders(l, item).replace("{current_queue}", String.valueOf(q.size())).replace("{click_action}", clickAct)).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
        }
        player.openInventory(inv);
    }

    // MỞ MENU DUEL VỚI ĐẦU NGƯỜI CHƠI, CHỌN MODE VÀ NÚT GỬI DUEL
    public void openDuelGui(Player sender, Player target, String modeKey) {
        if (rtpqItems.isEmpty()) return;
        if (modeKey == null || !rtpqItems.containsKey(modeKey)) {
            modeKey = rtpqItems.keySet().iterator().next();
        }
        RtpItem selectedItem = rtpqItems.get(modeKey);

        String rawTitle = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.title", "&#cba6f7&lDUEL: &#cdd6f4{target_name}"), sender, target, selectedItem);
        int size = plugin.getConfig().getInt("duel-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new DuelGuiHolder(target.getUniqueId(), modeKey), size, plugin.toComponent(rawTitle));
        applyFiller(inv, "duel-menu");

        // 1. Đầu đối thủ (Target Player Head)
        int headSlot = plugin.getConfig().getInt("duel-menu.target-head.slot", 4);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(target);
            String headName = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.target-head.name", "&#cba6f7&lOPPONENT INFO"), sender, target, selectedItem);
            skullMeta.displayName(plugin.toComponent(headName).decoration(TextDecoration.ITALIC, false));
            List<Component> hLore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList("duel-menu.target-head.lore")) {
                hLore.add(plugin.toComponent(applyDuelPlaceholders(l, sender, target, selectedItem)).decoration(TextDecoration.ITALIC, false));
            }
            skullMeta.lore(hLore);
            head.setItemMeta(skullMeta);
        }
        if (headSlot >= 0 && headSlot < size) inv.setItem(headSlot, head);

        // 2. Các nút chọn Mode (HT KIT, LT KIT)
        String statusSel = plugin.getConfig().getString("duel-menu.status-selected", "&#a6e3a1✔ Currently Selected");
        String statusUnsel = plugin.getConfig().getString("duel-menu.status-unselected", "<glyph:left_click> &#fab387Click to select this mode");
        List<String> modeLoreCfg = plugin.getConfig().getStringList("duel-menu.mode-lore");

        for (RtpItem item : rtpqItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                boolean isSel = item.key.equals(modeKey);
                meta.displayName(plugin.toComponent(applyPlaceholders(item.name, item)).decoration(TextDecoration.ITALIC, false));
                meta.setEnchantmentGlintOverride(isSel);
                List<Component> mLore = new ArrayList<>();
                for (String l : modeLoreCfg) {
                    String line = applyDuelPlaceholders(l, sender, target, item).replace("{duel_status}", isSel ? statusSel : statusUnsel);
                    mLore.add(plugin.toComponent(line).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(mLore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
        }

        // 3. Nút Gửi lời mời Duel (Send Button)
        int sendSlot = plugin.getConfig().getInt("duel-menu.send-button.slot", 22);
        Material sendMat = Material.matchMaterial(plugin.getConfig().getString("duel-menu.send-button.material", "LIME_CONCRETE").toUpperCase());
        ItemStack sendBtn = new ItemStack(sendMat != null ? sendMat : Material.LIME_CONCRETE);
        ItemMeta sendMeta = sendBtn.getItemMeta();
        if (sendMeta != null) {
            String sName = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.send-button.name", "&#a6e3a1&lSEND DUEL REQUEST"), sender, target, selectedItem);
            sendMeta.displayName(plugin.toComponent(sName).decoration(TextDecoration.ITALIC, false));
            List<Component> sLore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList("duel-menu.send-button.lore")) {
                sLore.add(plugin.toComponent(applyDuelPlaceholders(l, sender, target, selectedItem)).decoration(TextDecoration.ITALIC, false));
            }
            sendMeta.lore(sLore);
            sendBtn.setItemMeta(sendMeta);
        }
        if (sendSlot >= 0 && sendSlot < size) inv.setItem(sendSlot, sendBtn);

        sender.openInventory(inv);
    }

    public void handleDuelGuiClick(Player sender, DuelGuiHolder holder, int slot) {
        Player target = Bukkit.getPlayer(holder.targetUuid);
        if (target == null || !target.isOnline()) {
            sender.closeInventory();
            sender.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-player-not-found", "{prefix}&#f38ba8Player is offline!")));
            return;
        }

        for (RtpItem item : rtpqItems.values()) {
            if (item.slot == slot) {
                sender.playSound(sender.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                openDuelGui(sender, target, item.key);
                return;
            }
        }

        int sendSlot = plugin.getConfig().getInt("duel-menu.send-button.slot", 22);
        if (slot == sendSlot) {
            sender.closeInventory();
            RtpItem selected = rtpqItems.get(holder.selectedModeKey);
            if (selected != null) sendDuelRequest(sender, target, selected);
        }
    }

    public void handleDuelCommand(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-usage", "{prefix}&#89dcebUsage: /duel <player>")));
            return;
        }

        if (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny")) {
            if (args.length < 2) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-usage", "{prefix}&#89dcebUsage: /duel <accept|deny> <player>")));
                return;
            }
            Player challenger = Bukkit.getPlayerExact(args[1]);
            if (challenger == null || !challenger.isOnline()) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-player-not-found", "{prefix}&#f38ba8Player not found or is offline!")));
                return;
            }
            respondDuel(player, challenger, args[0].equalsIgnoreCase("accept"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-player-not-found", "{prefix}&#f38ba8Player not found or is offline!")));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-self", "{prefix}&#f38ba8You cannot duel yourself!")));
            return;
        }
        if (inCountdown.contains(player.getUniqueId()) || inCountdown.contains(target.getUniqueId())) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-busy", "{prefix}&#f38ba8That player is currently starting a match!")));
            return;
        }
        openDuelGui(player, target, null);
    }

    private void sendDuelRequest(Player sender, Player target, RtpItem item) {
        if (inCountdown.contains(sender.getUniqueId()) || inCountdown.contains(target.getUniqueId())) {
            sender.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-busy", "{prefix}&#f38ba8Player is busy!")));
            return;
        }

        int expireSec = plugin.getConfig().getInt("duel-menu.request-expire-seconds", 60);
        long now = System.currentTimeMillis();
        Map<UUID, DuelRequest> targetReqs = duelRequests.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>());

        DuelRequest existing = targetReqs.get(sender.getUniqueId());
        if (existing != null && (now - existing.timestamp()) < expireSec * 1000L) {
            sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-already-sent", "{prefix}&#f38ba8Request already sent!"), sender, target, item)));
            return;
        }

        targetReqs.put(sender.getUniqueId(), new DuelRequest(sender.getUniqueId(), item.key, now));
        sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-sent", "{prefix}&#a6e3a1Sent duel request!"), sender, target, item)));
        sender.playSound(sender.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);

        for (String line : plugin.getConfig().getStringList("rtp-messages.duel-received")) {
            target.sendMessage(plugin.toComponent(applyDuelPlaceholders(line, sender, target, item)));
        }
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
    }

    private void respondDuel(Player target, Player sender, boolean accept) {
        Map<UUID, DuelRequest> targetReqs = duelRequests.get(target.getUniqueId());
        DuelRequest req = targetReqs != null ? targetReqs.remove(sender.getUniqueId()) : null;
        int expireSec = plugin.getConfig().getInt("duel-menu.request-expire-seconds", 60);

        if (req == null || (System.currentTimeMillis() - req.timestamp()) > expireSec * 1000L) {
            target.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-no-pending", "{prefix}&#f38ba8No pending duel request!").replace("{sender}", sender.getName())));
            return;
        }

        RtpItem item = rtpqItems.get(req.modeKey());
        if (item == null) return;

        if (!accept) {
            target.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-denied-target", "{prefix}&#f38ba8Declined duel."), sender, target, item)));
            sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-denied-sender", "{prefix}&#f38ba8Duel declined."), sender, target, item)));
            return;
        }

        if (inCountdown.contains(sender.getUniqueId()) || inCountdown.contains(target.getUniqueId())) {
            target.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-busy", "{prefix}&#f38ba8Player is busy!")));
            return;
        }

        // Xóa khỏi mọi hàng đợi trước khi vào trận Duel
        for (List<UUID> list : queueMap.values()) {
            list.remove(sender.getUniqueId());
            list.remove(target.getUniqueId());
        }
        queueJoinTime.remove(sender.getUniqueId());
        queueJoinTime.remove(target.getUniqueId());

        target.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-accepted-target", "{prefix}&#a6e3a1Accepted duel!"), sender, target, item)));
        sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-accepted-sender", "{prefix}&#a6e3a1Duel accepted!"), sender, target, item)));

        startMatchmaking(sender, target, item);
    }

    public void handleRtpClick(Player player, int slot) {
        for (RtpItem item : rtpItems.values()) {
            if (item.slot == slot) {
                player.closeInventory();
                executeRtp(player, item);
                return;
            }
        }
    }

    public void handleQueueClick(Player player, int slot) {
        for (RtpItem item : rtpqItems.values()) {
            if (item.slot == slot) {
                player.closeInventory();
                toggleQueue(player, item);
                return;
            }
        }
    }

    public boolean leaveQueueCommand(Player player) {
        UUID uuid = player.getUniqueId();
        queueJoinTime.remove(uuid);
        for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
            if (entry.getValue().remove(uuid)) {
                RtpItem item = rtpqItems.get(entry.getKey());
                player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.left-queue", "{prefix}&#f38ba8You left the queue."), item)));
                return true;
            }
        }
        player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.not-in-queue", "{prefix}&#f38ba8You are not currently in any queue.")));
        return false;
    }

    public void removePlayerFromAllQueues(UUID uuid) {
        for (List<UUID> list : queueMap.values()) list.remove(uuid);
        queueJoinTime.remove(uuid);
        duelRequests.remove(uuid);
        inCountdown.remove(uuid);
    }

    private void toggleQueue(Player player, RtpItem item) {
        UUID uuid = player.getUniqueId();
        if (inCountdown.contains(uuid)) return;
        List<UUID> q = queueMap.get(item.key);
        if (q == null) return;

        if (q.contains(uuid)) {
            q.remove(uuid);
            queueJoinTime.remove(uuid);
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.left-queue", "{prefix}&#f38ba8You left the queue."), item)));
            return;
        }

        for (List<UUID> list : queueMap.values()) {
            if (list.contains(uuid)) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.already-in-other-queue", "{prefix}&#f38ba8You are already in another queue!")));
                return;
            }
        }

        q.add(uuid);
        queueJoinTime.put(uuid, System.currentTimeMillis());
        player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.joined-queue", "{prefix}&#89dcebJoined queue!"), item).replace("{current}", String.valueOf(q.size()))));

        if (q.size() == 1) {
            Bukkit.broadcast(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.queue-broadcast", "{prefix}&#89b4fa{player} is queuing!"), item).replace("{player}", player.getName()).replace("{current}", "1")));
        }

        if (q.size() >= 2) {
            UUID u1 = q.remove(0), u2 = q.remove(0);
            queueJoinTime.remove(u1);
            queueJoinTime.remove(u2);
            Player p1 = Bukkit.getPlayer(u1), p2 = Bukkit.getPlayer(u2);
            if (p1 != null && p2 != null && p1.isOnline() && p2.isOnline()) {
                startMatchmaking(p1, p2, item);
            } else {
                if (p1 != null && p1.isOnline()) { q.add(u1); queueJoinTime.put(u1, System.currentTimeMillis()); }
                if (p2 != null && p2.isOnline()) { q.add(u2); queueJoinTime.put(u2, System.currentTimeMillis()); }
            }
        }
    }

    private void startMatchmaking(Player p1, Player p2, RtpItem item) {
        p1.closeInventory();
        p2.closeInventory();
        inCountdown.add(p1.getUniqueId());
        inCountdown.add(p2.getUniqueId());

        for (String line : plugin.getConfig().getStringList("rtp-messages.match-found-broadcast")) {
            Bukkit.broadcast(plugin.toComponent(applyPlaceholders(line, item).replace("{player1}", p1.getName()).replace("{player2}", p2.getName())));
        }

        int countdown = plugin.getConfig().getInt("rtpq-menu.countdown-seconds", 5);
        String p1Prefix = getPlayerPrefix(p1), p2Prefix = getPlayerPrefix(p2);

        new BukkitRunnable() {
            int timer = countdown;
            @Override
            public void run() {
                if (!p1.isOnline() || !p2.isOnline()) {
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
                    cancel();
                    if (p1.isOnline()) p1.sendMessage(plugin.toComponent("<glyph:warning_blue> &#f38ba8Opponent disconnected!"));
                    if (p2.isOnline()) p2.sendMessage(plugin.toComponent("<glyph:warning_blue> &#f38ba8Opponent disconnected!"));
                    return;
                }
                if (timer > 0) {
                    Component emojiTitle = getCircleEmoji(timer);
                    Component sub = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-countdown-subtitle", "&#cdd6f4Mode: {icon} &#fab387{mode}"), item));
                    Title titleObj = Title.title(emojiTitle, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(1250), Duration.ofMillis(250)));
                    p1.showTitle(titleObj);
                    p2.showTitle(titleObj);

                    String ab = applyPlaceholders(plugin.getMsg("rtp-messages.match-actionbar", "&#89dcebMode: {icon} &#fab387{mode}"), item);
                    p1.sendActionBar(plugin.toComponent(ab.replace("{opponent_prefix}", p2Prefix).replace("{opponent_name}", p2.getName()).replace("{time}", String.valueOf(timer))));
                    p2.sendActionBar(plugin.toComponent(ab.replace("{opponent_prefix}", p1Prefix).replace("{opponent_name}", p1.getName()).replace("{time}", String.valueOf(timer))));

                    p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    timer--;
                } else {
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
                    cancel();
                    teleportMatch(p1, p2, item);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private Component getCircleEmoji(int count) {
        String cfg = plugin.getConfig().getString("rtpq-menu.countdown-titles." + count);
        if (cfg != null && !cfg.isEmpty()) return plugin.toComponent(cfg);
        return plugin.toComponent("&#fab387&l" + count);
    }

    private void teleportMatch(Player p1, Player p2, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null) return;

        int gap = plugin.getConfig().getInt("rtpq-menu.player-gap", 8);
        Set<UUID> ignored = new HashSet<>(Arrays.asList(p1.getUniqueId(), p2.getUniqueId()));
        Location loc1 = null, loc2 = null;
        int maxAttempts = plugin.getConfig().getInt("rtp-settings.max-search-attempts", 60);

        for (int i = 0; i < maxAttempts; i++) {
            Location c1 = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, ignored);
            if (c1 == null) continue;
            int[][] dirs = {{gap, 0}, {-gap, 0}, {0, gap}, {0, -gap}};
            for (int[] d : dirs) {
                int tx = c1.getBlockX() + d[0], tz = c1.getBlockZ() + d[1];
                int ty = getSolidSurfaceY(world, tx, tz);
                if (Math.abs(ty - (c1.getBlockY() - 1)) <= 1) {
                    Location c2 = new Location(world, tx + 0.5, ty + 1.0, tz + 0.5);
                    if (world.getWorldBorder().isInside(c2) && isTerrainFlatAndSafe(world, tx, tz, ty)) {
                        loc1 = c1; loc2 = c2; break;
                    }
                }
            }
            if (loc1 != null && loc2 != null) break;
        }

        if (loc1 == null || loc2 == null) {
            Component err = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location!"), item));
            p1.sendMessage(err); p2.sendMessage(err);
            return;
        }

        loc1.setDirection(loc2.toVector().subtract(loc1.toVector()).normalize());
        loc2.setDirection(loc1.toVector().subtract(loc2.toVector()).normalize());
        p1.teleport(loc1);
        p2.teleport(loc2);

        Title fTitle = Title.title(
                plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-started-title", "&#a6e3a1&lFIGHT!"), item)),
                plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-started-subtitle", "&#cdd6f4Good luck!"), item)),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500))
        );
        p1.showTitle(fTitle); p2.showTitle(fTitle);
        p1.playSound(loc1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p2.playSound(loc2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void executeRtp(Player player, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null) return;
        player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.teleporting-rtp", "&#89dcebFinding safe location..."), item)));
        Location loc = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, Collections.singleton(player.getUniqueId()));
        if (loc != null) {
            player.teleport(loc);
            player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.teleport-success", "&#a6e3a1Teleported!"), item)
                    .replace("{x}", String.valueOf(loc.getBlockX())).replace("{y}", String.valueOf(loc.getBlockY())).replace("{z}", String.valueOf(loc.getBlockZ()))));
        } else {
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location!"), item)));
        }
    }

    private boolean isHarmlessPassable(Material m) {
        return m.isAir() || m == Material.DEAD_BUSH || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN;
    }

    private int getSolidSurfaceY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        while (y > world.getMinHeight() && isHarmlessPassable(world.getBlockAt(x, y, z).getType())) y--;
        return y;
    }

    private Location findSafeLocation(World world, int minX, int maxX, int minZ, int maxZ, Set<UUID> ignored) {
        int x1 = Math.min(minX, maxX), x2 = Math.max(minX, maxX);
        int z1 = Math.min(minZ, maxZ), z2 = Math.max(minZ, maxZ);
        int maxAttempts = plugin.getConfig().getInt("rtp-settings.max-search-attempts", 60);
        double minDistSq = Math.pow(plugin.getConfig().getDouble("rtp-settings.min-player-distance", 50.0), 2);

        for (int i = 0; i < maxAttempts; i++) {
            int rx = ThreadLocalRandom.current().nextInt(x1, x2 + 1);
            int rz = ThreadLocalRandom.current().nextInt(z1, z2 + 1);
            int y = getSolidSurfaceY(world, rx, rz);
            if (y <= world.getMinHeight() + 5 || y >= world.getMaxHeight() - 5) continue;

            Location cand = new Location(world, rx + 0.5, y + 1.0, rz + 0.5);
            if (!world.getWorldBorder().isInside(cand)) continue;

            boolean near = false;
            for (Player p : world.getPlayers()) {
                if (ignored != null && ignored.contains(p.getUniqueId())) continue;
                if (p.getLocation().distanceSquared(cand) < minDistSq) { near = true; break; }
            }
            if (!near && isTerrainFlatAndSafe(world, rx, rz, y)) return cand;
        }
        return null;
    }

    private boolean isTerrainFlatAndSafe(World world, int cx, int cz, int baseY) {
        int maxSlope = plugin.getConfig().getInt("rtp-settings.max-terrain-slope", 2);
        int minY = baseY, maxY = baseY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int sy = getSolidSurfaceY(world, cx + dx, cz + dz);
                minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
                if ((maxY - minY) > maxSlope || !isSafeBlock(world.getBlockAt(cx + dx, sy, cz + dz).getType())) return false;
            }
        }
        return isHarmlessPassable(world.getBlockAt(cx, baseY + 1, cz).getType())
                && isHarmlessPassable(world.getBlockAt(cx, baseY + 2, cz).getType());
    }

    private boolean isSafeBlock(Material m) {
        return m.isSolid() && m != Material.LAVA && m != Material.WATER && m != Material.MAGMA_BLOCK
                && m != Material.FIRE && m != Material.SOUL_FIRE && m != Material.CACTUS
                && m != Material.WITHER_ROSE && m != Material.BEDROCK && m != Material.OBSIDIAN
                && m != Material.RESPAWN_ANCHOR && m != Material.COBWEB;
    }

    private String getPlayerPrefix(Player player) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            String p = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%");
            if (!p.equals("%luckperms_prefix%")) return p;
        }
        return "";
    }
    }
                        
