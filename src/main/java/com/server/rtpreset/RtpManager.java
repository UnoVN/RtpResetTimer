package com.server.rtpreset;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RtpManager {

    public static class RtpItem {
        public String key, type, name, icon, modeDisplay, mapDisplay, worldName;
        public int slot, minX, maxX, minZ, maxZ;
        public Material material;
        public List<String> lore;

        public boolean isArenaMode() {
            return "ARENA".equalsIgnoreCase(type) || "boxcart".equalsIgnoreCase(key);
        }
    }

    public static class QueueGuiHolder implements InventoryHolder {
        public final Set<String> selectedModes = new LinkedHashSet<>();

        public QueueGuiHolder(Collection<String> initialModes) {
            if (initialModes != null) this.selectedModes.addAll(initialModes);
        }

        @Override
        public @NotNull Inventory getInventory() { return null; }
    }

    public static class DuelGuiHolder implements InventoryHolder {
        public final UUID targetUuid;
        public String selectedModeKey;
        public int customWins;
        public String selectedArenaName;

        public DuelGuiHolder(UUID targetUuid, String selectedModeKey, int customWins, String selectedArenaName) {
            this.targetUuid = targetUuid;
            this.selectedModeKey = selectedModeKey;
            this.customWins = customWins;
            this.selectedArenaName = selectedArenaName;
        }

        @Override
        public @NotNull Inventory getInventory() { return null; }
    }

    public record DuelRequest(UUID senderUuid, String modeKey, int customWins, String arenaName, long timestamp) {}

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
        item.type = sec.getString("type", key.equalsIgnoreCase("boxcart") ? "ARENA" : "RTP");
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

    private Set<String> getPlayerQueuedModes(UUID uuid) {
        Set<String> modes = new LinkedHashSet<>();
        for (Map.Entry<String, List<UUID>> entry : queueMap.entrySet()) {
            if (entry.getValue().contains(uuid)) modes.add(entry.getKey());
        }
        return modes;
    }

    private String formatModeList(Collection<String> keys, boolean includeCount) {
        if (keys == null || keys.isEmpty()) {
            return plugin.getConfig().getString("rtpq-menu.none-selected", "&#f38ba8None");
        }
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            RtpItem item = rtpqItems.get(key);
            if (item == null) continue;
            if (includeCount) {
                int size = queueMap.getOrDefault(key, Collections.emptyList()).size();
                parts.add(item.icon + " &#cdd6f4" + item.modeDisplay + " &#a6adc8(&#89b4fa" + size + "/2&#a6adc8)");
            } else {
                parts.add(item.icon + " &#cdd6f4" + item.modeDisplay);
            }
        }
        return String.join("&#a6adc8, ", parts);
    }

    private void startQueueMonitor() {
        queueMonitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                boolean timeoutEnabled = plugin.getConfig().getBoolean("rtpq-menu.queue-timeout.enabled", true);
                int maxTimeoutSec = plugin.getConfig().getInt("rtpq-menu.queue-timeout.timeout-seconds", 120);
                long now = System.currentTimeMillis();

                for (UUID uuid : new HashSet<>(queueJoinTime.keySet())) {
                    Player p = Bukkit.getPlayer(uuid);
                    Set<String> activeModes = getPlayerQueuedModes(uuid);
                    if (p == null || !p.isOnline() || activeModes.isEmpty()) {
                        for (List<UUID> list : queueMap.values()) list.remove(uuid);
                        queueJoinTime.remove(uuid);
                        continue;
                    }

                    int elapsedSec = (int) ((now - queueJoinTime.getOrDefault(uuid, now)) / 1000L);
                    String modesStr = formatModeList(activeModes, false);

                    if (timeoutEnabled && elapsedSec >= maxTimeoutSec) {
                        for (List<UUID> list : queueMap.values()) list.remove(uuid);
                        queueJoinTime.remove(uuid);
                        String tf = String.format("%02d:%02d", maxTimeoutSec / 60, maxTimeoutSec % 60);
                        p.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.queue-timeout", "{prefix}&#f38ba8Queue timed out ({timeout}).")
                                .replace("{modes}", modesStr).replace("{timeout}", tf)));
                        p.sendActionBar(plugin.toComponent(plugin.getMsg("rtp-messages.queue-timeout-actionbar", "&#f38ba8Queue timed out")
                                .replace("{modes}", modesStr)));
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
                        continue;
                    }

                    String elapsedStr = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60);
                    String ab = plugin.getMsg("rtp-messages.queue-waiting-actionbar", "&#a6adc8Queue: {modes} &8| &#a6adc8Time: &#cdd6f4{elapsed}")
                            .replace("{modes}", modesStr).replace("{elapsed}", elapsedStr);
                    p.sendActionBar(plugin.toComponent(ab));
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private String applyPlaceholders(String text, RtpItem item) {
        if (text == null) return "";
        if (item == null) {
            String none = plugin.getConfig().getString("rtpq-menu.none-selected", "&#f38ba8None");
            return text.replace("{display_name}", none)
                       .replace("{mode}", none)
                       .replace("{map}", "-")
                       .replace("{world}", "-")
                       .replace("{icon}", "");
        }
        return text.replace("{display_name}", item.name)
                   .replace("{mode}", item.modeDisplay)
                   .replace("{map}", item.mapDisplay)
                   .replace("{world}", item.worldName)
                   .replace("{icon}", item.icon);
    }

    private String applyDuelPlaceholders(String text, Player sender, Player target, RtpItem item, int wins, String arenaName) {
        String s = applyPlaceholders(text, item);
        String mapLabel = (item != null && item.isArenaMode())
                ? (arenaName != null ? arenaName : plugin.getConfig().getString("duel-menu.map-button.random-display", "Random Available"))
                : (item != null ? item.mapDisplay : "-");
        s = s.replace("{wins}", String.valueOf(wins))
             .replace("{arena}", mapLabel)
             .replace("{map}", mapLabel);
        if (sender != null) s = s.replace("{sender_name}", sender.getName()).replace("{sender_prefix}", getPlayerPrefix(sender));
        if (target != null) s = s.replace("{target_name}", target.getName()).replace("{target_prefix}", getPlayerPrefix(target)).replace("{target}", target.getName());
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
        Component title = plugin.toComponent(plugin.getConfig().getString("rtp-menu.title", "&#cdd6f4&lRANDOM TELEPORT"));
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
        openRtpQueueGui(player, null);
    }

    public void openRtpQueueGui(Player player, Set<String> customSelected) {
        Set<String> activeQueues = getPlayerQueuedModes(player.getUniqueId());
        Set<String> selected;
        if (customSelected != null) {
            selected = new LinkedHashSet<>(customSelected);
        } else if (!activeQueues.isEmpty()) {
            selected = new LinkedHashSet<>(activeQueues);
        } else {
            selected = new LinkedHashSet<>();
        }

        Component title = plugin.toComponent(plugin.getConfig().getString("rtpq-menu.title", "&#cdd6f4&l1V1 BATTLE QUEUE"));
        int size = plugin.getConfig().getInt("rtpq-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new QueueGuiHolder(selected), size, title);
        applyFiller(inv, "rtpq-menu");

        String modesSummary = formatModeList(selected, false);
        String stSel = plugin.getConfig().getString("rtpq-menu.status-selected", "&#a6e3a1✔ Selected");
        String stUnsel = plugin.getConfig().getString("rtpq-menu.status-unselected", "<glyph:left_click> &#a6adc8Click to select");

        for (RtpItem item : rtpqItems.values()) {
            ItemStack is = new ItemStack(item.material);
            ItemMeta meta = is.getItemMeta();
            if (meta != null) {
                boolean isSel = selected.contains(item.key);
                meta.displayName(plugin.toComponent(applyPlaceholders(item.name, item)).decoration(TextDecoration.ITALIC, false));
                meta.setEnchantmentGlintOverride(isSel);
                List<UUID> q = queueMap.getOrDefault(item.key, Collections.emptyList());
                String statusText = isSel ? stSel : stUnsel;

                List<Component> lore = new ArrayList<>();
                for (String l : item.lore) {
                    String line = applyPlaceholders(l, item)
                            .replace("{current_queue}", String.valueOf(q.size()))
                            .replace("{queue_status}", statusText)
                            .replace("{click_action}", statusText);
                    lore.add(plugin.toComponent(line).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
        }

        boolean showLeave = !activeQueues.isEmpty() && (selected.isEmpty() || selected.equals(activeQueues));
        String btnPath = showLeave ? "rtpq-menu.leave-button" : "rtpq-menu.confirm-button";
        int btnSlot = plugin.getConfig().getInt(btnPath + ".slot", 22);
        Material btnMat = Material.matchMaterial(plugin.getConfig().getString(btnPath + ".material", showLeave ? "RED_CONCRETE" : "LIME_CONCRETE").toUpperCase());
        ItemStack btn = new ItemStack(btnMat != null ? btnMat : (showLeave ? Material.RED_CONCRETE : Material.LIME_CONCRETE));
        ItemMeta btnMeta = btn.getItemMeta();
        if (btnMeta != null) {
            String bName = plugin.getConfig().getString(btnPath + ".name", showLeave ? "&#f38ba8&lLEAVE QUEUE" : "&#a6e3a1&lCONFIRM QUEUE")
                    .replace("{selected_modes}", showLeave ? formatModeList(activeQueues, false) : modesSummary);
            btnMeta.displayName(plugin.toComponent(bName).decoration(TextDecoration.ITALIC, false));
            List<Component> bLore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList(btnPath + ".lore")) {
                bLore.add(plugin.toComponent(l.replace("{selected_modes}", showLeave ? formatModeList(activeQueues, false) : modesSummary)).decoration(TextDecoration.ITALIC, false));
            }
            btnMeta.lore(bLore);
            btn.setItemMeta(btnMeta);
        }
        if (btnSlot >= 0 && btnSlot < size) inv.setItem(btnSlot, btn);

        player.openInventory(inv);
    }

    public void handleQueueGuiClick(Player player, QueueGuiHolder holder, int slot) {
        for (RtpItem item : rtpqItems.values()) {
            if (item.slot == slot) {
                if (holder.selectedModes.contains(item.key)) holder.selectedModes.remove(item.key);
                else holder.selectedModes.add(item.key);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                openRtpQueueGui(player, holder.selectedModes);
                return;
            }
        }

        int confirmSlot = plugin.getConfig().getInt("rtpq-menu.confirm-button.slot", 22);
        int leaveSlot = plugin.getConfig().getInt("rtpq-menu.leave-button.slot", 22);
        if (slot == confirmSlot || slot == leaveSlot) {
            confirmQueueSelection(player, holder.selectedModes);
        }
    }

    private void confirmQueueSelection(Player player, Set<String> selectedModes) {
        UUID uuid = player.getUniqueId();
        if (inCountdown.contains(uuid) || plugin.getArenaManager().isInMatch(uuid)) return;

        Set<String> activeQueues = getPlayerQueuedModes(uuid);
        if (!activeQueues.isEmpty() && (selectedModes.isEmpty() || selectedModes.equals(activeQueues))) {
            player.closeInventory();
            leaveQueueCommand(player);
            return;
        }

        if (selectedModes.isEmpty()) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.no-mode-selected", "{prefix}&#f38ba8Select at least one mode first.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        player.closeInventory();
        for (List<UUID> list : queueMap.values()) list.remove(uuid);

        List<RtpItem> matchedCandidates = new ArrayList<>();
        Map<String, Player> opponentByMode = new HashMap<>();

        for (String key : selectedModes) {
            RtpItem item = rtpqItems.get(key);
            List<UUID> q = queueMap.get(key);
            if (item == null || q == null) continue;

            q.removeIf(u -> {
                Player p = Bukkit.getPlayer(u);
                return p == null || !p.isOnline();
            });

            if (!q.contains(uuid)) q.add(uuid);

            if (q.size() >= 2) {
                for (UUID otherUuid : q) {
                    if (!otherUuid.equals(uuid)) {
                        Player other = Bukkit.getPlayer(otherUuid);
                        if (other != null && other.isOnline()) {
                            matchedCandidates.add(item);
                            opponentByMode.put(key, other);
                            break;
                        }
                    }
                }
            }
        }

        if (!matchedCandidates.isEmpty()) {
            Collections.shuffle(matchedCandidates);
            for (RtpItem chosenItem : matchedCandidates) {
                Player opponent = opponentByMode.get(chosenItem.key);
                if (opponent == null) continue;

                ArenaManager.Arena reservedArena = null;
                if (chosenItem.isArenaMode()) {
                    reservedArena = plugin.getArenaManager().reserveAvailableArena();
                    if (reservedArena == null) {
                        Component noArena = plugin.toComponent(plugin.getMsg("rtp-messages.no-arena-available",
                                "{prefix}&#f38ba8No Boxcart arena is currently available!"));
                        player.sendMessage(noArena);
                        opponent.sendMessage(noArena);
                        continue;
                    }
                }

                for (List<UUID> list : queueMap.values()) {
                    list.remove(uuid);
                    list.remove(opponent.getUniqueId());
                }
                queueJoinTime.remove(uuid);
                queueJoinTime.remove(opponent.getUniqueId());
                int defWins = plugin.getConfig().getInt("arena-settings.first-to-wins", 3);
                startMatchmaking(opponent, player, chosenItem, reservedArena, defWins);
                return;
            }
        }

        queueJoinTime.put(uuid, System.currentTimeMillis());
        String modesWithCount = formatModeList(selectedModes, true);
        player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.joined-queue", "{prefix}&#a6adc8Joined queue: {modes}").replace("{modes}", modesWithCount)));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Bukkit.broadcast(plugin.toComponent(plugin.getMsg("rtp-messages.queue-broadcast", "{prefix}&#cdd6f4{player} &#a6adc8is queuing for {modes}")
                .replace("{player}", player.getName()).replace("{modes}", modesWithCount)));
    }

    // ==================== MENU DUEL: KHÔNG AUTO-SELECT, CHỌN WINS & CHỌN MAP ====================
    public void openDuelGui(Player sender, Player target, String modeKey, int customWins, String selectedArenaName) {
        if (rtpqItems.isEmpty()) return;
        int defWins = Math.max(1, plugin.getConfig().getInt("arena-settings.first-to-wins", 3));
        if (customWins <= 0) customWins = defWins;

        RtpItem selectedItem = modeKey != null ? rtpqItems.get(modeKey) : null;

        // Nếu Arena đã chọn bị người khác vào chơi, tự động chuyển về Random Available
        if (selectedArenaName != null) {
            ArenaManager.Arena check = plugin.getArenaManager().getArena(selectedArenaName);
            if (check == null || check.inUse || !check.isReadyForMatch()) {
                selectedArenaName = null;
            }
        }

        String rawTitle = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.title", "&#cdd6f4&lDUEL: &#89b4fa{target_name}"),
                sender, target, selectedItem, customWins, selectedArenaName);
        int size = plugin.getConfig().getInt("duel-menu.size", 27);
        Inventory inv = Bukkit.createInventory(new DuelGuiHolder(target.getUniqueId(), modeKey, customWins, selectedArenaName),
                size, plugin.toComponent(rawTitle));
        applyFiller(inv, "duel-menu");

        // 1. Đầu đối thủ (Slot 4)
        int headSlot = plugin.getConfig().getInt("duel-menu.target-head.slot", 4);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(target);
            String headName = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.target-head.name", "&#cdd6f4&lOPPONENT INFO"),
                    sender, target, selectedItem, customWins, selectedArenaName);
            skullMeta.displayName(plugin.toComponent(headName).decoration(TextDecoration.ITALIC, false));
            List<Component> hLore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList("duel-menu.target-head.lore")) {
                hLore.add(plugin.toComponent(applyDuelPlaceholders(l, sender, target, selectedItem, customWins, selectedArenaName)).decoration(TextDecoration.ITALIC, false));
            }
            skullMeta.lore(hLore);
            head.setItemMeta(skullMeta);
        }
        if (headSlot >= 0 && headSlot < size) inv.setItem(headSlot, head);

        // 2. Các chế độ (Không auto-select lúc mới mở)
        String statusSel = plugin.getConfig().getString("duel-menu.status-selected", "&#a6e3a1✔ Selected");
        String statusUnsel = plugin.getConfig().getString("duel-menu.status-unselected", "<glyph:left_click> &#a6adc8Click to select");
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
                    String line = applyDuelPlaceholders(l, sender, target, item, customWins, selectedArenaName)
                            .replace("{duel_status}", isSel ? statusSel : statusUnsel);
                    mLore.add(plugin.toComponent(line).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(mLore);
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                is.setItemMeta(meta);
            }
            if (item.slot >= 0 && item.slot < size) inv.setItem(item.slot, is);
        }

        // 3. Nếu đang chọn chế độ Boxcart (ARENA) -> Hiện nút Chỉnh số Win (Slot 20) & Chọn Map (Slot 24)
        if (selectedItem != null && selectedItem.isArenaMode()) {
            int rSlot = plugin.getConfig().getInt("duel-menu.rounds-button.slot", 20);
            Material rMat = Material.matchMaterial(plugin.getConfig().getString("duel-menu.rounds-button.material", "CLOCK").toUpperCase());
            ItemStack rBtn = new ItemStack(rMat != null ? rMat : Material.CLOCK, Math.min(64, customWins));
            ItemMeta rMeta = rBtn.getItemMeta();
            if (rMeta != null) {
                String rName = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.rounds-button.name", "&#89b4fa&lFIRST TO: &#cdd6f4{wins} WINS"),
                        sender, target, selectedItem, customWins, selectedArenaName);
                rMeta.displayName(plugin.toComponent(rName).decoration(TextDecoration.ITALIC, false));
                List<Component> rLore = new ArrayList<>();
                for (String l : plugin.getConfig().getStringList("duel-menu.rounds-button.lore")) {
                    rLore.add(plugin.toComponent(applyDuelPlaceholders(l, sender, target, selectedItem, customWins, selectedArenaName)).decoration(TextDecoration.ITALIC, false));
                }
                rMeta.lore(rLore);
                rBtn.setItemMeta(rMeta);
            }
            if (rSlot >= 0 && rSlot < size) inv.setItem(rSlot, rBtn);

            int mSlot = plugin.getConfig().getInt("duel-menu.map-button.slot", 24);
            Material mMat = Material.matchMaterial(plugin.getConfig().getString("duel-menu.map-button.material", "PAPER").toUpperCase());
            ItemStack mBtn = new ItemStack(mMat != null ? mMat : Material.PAPER);
            ItemMeta mMeta = mBtn.getItemMeta();
            if (mMeta != null) {
                String mName = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.map-button.name", "&#89b4fa&lARENA: &#cdd6f4{arena}"),
                        sender, target, selectedItem, customWins, selectedArenaName);
                mMeta.displayName(plugin.toComponent(mName).decoration(TextDecoration.ITALIC, false));

                List<Component> mLore = new ArrayList<>();
                String randDisp = plugin.getConfig().getString("duel-menu.map-button.random-display", "Random Available");
                mLore.add(plugin.toComponent((selectedArenaName == null ? "&#a6e3a1▶ " : "&#a6adc8• ") + randDisp).decoration(TextDecoration.ITALIC, false));

                for (ArenaManager.Arena a : plugin.getArenaManager().getAllArenas()) {
                    if (a.inUse) {
                        mLore.add(plugin.toComponent("&#f38ba8✖ " + a.name + " &8(In Match)").decoration(TextDecoration.ITALIC, false));
                    } else if (!a.isReadyForMatch()) {
                        mLore.add(plugin.toComponent("&#f38ba8✖ " + a.name + " &8(Incomplete)").decoration(TextDecoration.ITALIC, false));
                    } else if (a.name.equalsIgnoreCase(selectedArenaName)) {
                        mLore.add(plugin.toComponent("&#a6e3a1▶ " + a.name + " &8(Selected)").decoration(TextDecoration.ITALIC, false));
                    } else {
                        mLore.add(plugin.toComponent("&#a6adc8• " + a.name + " &8(Available)").decoration(TextDecoration.ITALIC, false));
                    }
                }
                mLore.add(Component.empty());
                mLore.add(plugin.toComponent("<glyph:left_click> &#a6e3a1Click to cycle available arenas").decoration(TextDecoration.ITALIC, false));
                mMeta.lore(mLore);
                mBtn.setItemMeta(mMeta);
            }
            if (mSlot >= 0 && mSlot < size) inv.setItem(mSlot, mBtn);
        }

        // 4. Nút Gửi lời mời Duel (Slot 22)
        int sendSlot = plugin.getConfig().getInt("duel-menu.send-button.slot", 22);
        Material sendMat = Material.matchMaterial(plugin.getConfig().getString("duel-menu.send-button.material", "LIME_CONCRETE").toUpperCase());
        ItemStack sendBtn = new ItemStack(sendMat != null ? sendMat : Material.LIME_CONCRETE);
        ItemMeta sendMeta = sendBtn.getItemMeta();
        if (sendMeta != null) {
            String sName = applyDuelPlaceholders(plugin.getConfig().getString("duel-menu.send-button.name", "&#a6e3a1&lSEND DUEL"),
                    sender, target, selectedItem, customWins, selectedArenaName);
            sendMeta.displayName(plugin.toComponent(sName).decoration(TextDecoration.ITALIC, false));
            List<Component> sLore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList("duel-menu.send-button.lore")) {
                sLore.add(plugin.toComponent(applyDuelPlaceholders(l, sender, target, selectedItem, customWins, selectedArenaName)).decoration(TextDecoration.ITALIC, false));
            }
            sendMeta.lore(sLore);
            sendBtn.setItemMeta(sendMeta);
        }
        if (sendSlot >= 0 && sendSlot < size) inv.setItem(sendSlot, sendBtn);

        sender.openInventory(inv);
    }

    public void handleDuelGuiClick(Player sender, DuelGuiHolder holder, int slot, ClickType click) {
        Player target = Bukkit.getPlayer(holder.targetUuid);
        if (target == null || !target.isOnline()) {
            sender.closeInventory();
            sender.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-player-not-found", "{prefix}&#f38ba8Player not found.")));
            return;
        }

        for (RtpItem item : rtpqItems.values()) {
            if (item.slot == slot) {
                sender.playSound(sender.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                openDuelGui(sender, target, item.key, holder.customWins, holder.selectedArenaName);
                return;
            }
        }

        RtpItem selected = holder.selectedModeKey != null ? rtpqItems.get(holder.selectedModeKey) : null;

        if (selected != null && selected.isArenaMode()) {
            int rSlot = plugin.getConfig().getInt("duel-menu.rounds-button.slot", 20);
            int mSlot = plugin.getConfig().getInt("duel-menu.map-button.slot", 24);

            if (slot == rSlot) {
                int minW = plugin.getConfig().getInt("duel-menu.rounds-button.min-wins", 1);
                int maxW = plugin.getConfig().getInt("duel-menu.rounds-button.max-wins", 10);
                int nextWins = click.isRightClick() ? holder.customWins - 1 : holder.customWins + 1;
                if (nextWins > maxW) nextWins = minW;
                if (nextWins < minW) nextWins = maxW;
                sender.playSound(sender.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                openDuelGui(sender, target, holder.selectedModeKey, nextWins, holder.selectedArenaName);
                return;
            }

            if (slot == mSlot) {
                List<String> availableNames = new ArrayList<>();
                availableNames.add(null); // null = Random Available
                for (ArenaManager.Arena a : plugin.getArenaManager().getAllArenas()) {
                    if (!a.inUse && a.isReadyForMatch()) {
                        availableNames.add(a.name);
                    }
                }
                int idx = availableNames.indexOf(holder.selectedArenaName);
                String nextArena = availableNames.get((idx + 1) % availableNames.size());
                sender.playSound(sender.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                openDuelGui(sender, target, holder.selectedModeKey, holder.customWins, nextArena);
                return;
            }
        }

        int sendSlot = plugin.getConfig().getInt("duel-menu.send-button.slot", 22);
        if (slot == sendSlot) {
            if (selected == null) {
                sender.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.no-mode-selected", "{prefix}&#f38ba8Select at least one mode first.")));
                sender.playSound(sender.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
            sender.closeInventory();
            sendDuelRequest(sender, target, selected, holder.customWins, holder.selectedArenaName);
        }
    }

    public void handleDuelCommand(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-usage", "{prefix}&#a6adc8Usage: &#cdd6f4/duel <player>")));
            return;
        }

        if (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny")) {
            if (args.length < 2) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-usage", "{prefix}&#a6adc8Usage: &#cdd6f4/duel <accept|deny> <player>")));
                return;
            }
            Player challenger = Bukkit.getPlayerExact(args[1]);
            if (challenger == null || !challenger.isOnline()) {
                player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-player-not-found", "{prefix}&#f38ba8Player not found.")));
                return;
            }
            respondDuel(player, challenger, args[0].equalsIgnoreCase("accept"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-player-not-found", "{prefix}&#f38ba8Player not found.")));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-self", "{prefix}&#f38ba8You cannot duel yourself.")));
            return;
        }
        if (inCountdown.contains(player.getUniqueId()) || inCountdown.contains(target.getUniqueId())
                || plugin.getArenaManager().isInMatch(player.getUniqueId()) || plugin.getArenaManager().isInMatch(target.getUniqueId())) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-busy", "{prefix}&#f38ba8That player is currently in a match.")));
            return;
        }
        int defWins = plugin.getConfig().getInt("arena-settings.first-to-wins", 3);
        // Mở GUI Duel với modeKey = null (Không tự động chọn HT KIT)
        openDuelGui(player, target, null, defWins, null);
    }

    private void sendDuelRequest(Player sender, Player target, RtpItem item, int customWins, String arenaName) {
        if (inCountdown.contains(sender.getUniqueId()) || inCountdown.contains(target.getUniqueId())
                || plugin.getArenaManager().isInMatch(sender.getUniqueId()) || plugin.getArenaManager().isInMatch(target.getUniqueId())) {
            sender.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-busy", "{prefix}&#f38ba8Player is busy.")));
            return;
        }

        int expireSec = plugin.getConfig().getInt("duel-menu.request-expire-seconds", 60);
        long now = System.currentTimeMillis();
        Map<UUID, DuelRequest> targetReqs = duelRequests.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>());

        DuelRequest existing = targetReqs.get(sender.getUniqueId());
        if (existing != null && (now - existing.timestamp()) < expireSec * 1000L) {
            sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-already-sent", "{prefix}&#f38ba8Request already sent."),
                    sender, target, item, customWins, arenaName)));
            return;
        }

        targetReqs.put(sender.getUniqueId(), new DuelRequest(sender.getUniqueId(), item.key, customWins, arenaName, now));
        sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-sent", "{prefix}&#a6adc8Duel request sent."),
                sender, target, item, customWins, arenaName)));
        sender.playSound(sender.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);

        for (String line : plugin.getConfig().getStringList("rtp-messages.duel-received")) {
            target.sendMessage(plugin.toComponent(applyDuelPlaceholders(line, sender, target, item, customWins, arenaName)));
        }
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
    }

    private void respondDuel(Player target, Player sender, boolean accept) {
        Map<UUID, DuelRequest> targetReqs = duelRequests.get(target.getUniqueId());
        DuelRequest req = targetReqs != null ? targetReqs.remove(sender.getUniqueId()) : null;
        int expireSec = plugin.getConfig().getInt("duel-menu.request-expire-seconds", 60);

        if (req == null || (System.currentTimeMillis() - req.timestamp()) > expireSec * 1000L) {
            target.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-no-pending", "{prefix}&#f38ba8No pending duel request.").replace("{sender}", sender.getName())));
            return;
        }

        RtpItem item = rtpqItems.get(req.modeKey());
        if (item == null) return;

        if (!accept) {
            target.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-denied-target", "{prefix}&#a6adc8Declined duel."),
                    sender, target, item, req.customWins(), req.arenaName())));
            sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-denied-sender", "{prefix}&#f38ba8Duel declined."),
                    sender, target, item, req.customWins(), req.arenaName())));
            return;
        }

        if (inCountdown.contains(sender.getUniqueId()) || inCountdown.contains(target.getUniqueId())
                || plugin.getArenaManager().isInMatch(sender.getUniqueId()) || plugin.getArenaManager().isInMatch(target.getUniqueId())) {
            target.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.duel-busy", "{prefix}&#f38ba8Player is busy.")));
            return;
        }

        ArenaManager.Arena reservedArena = null;
        if (item.isArenaMode()) {
            reservedArena = plugin.getArenaManager().reserveArena(req.arenaName());
            if (reservedArena == null) {
                Component noArena = plugin.toComponent(plugin.getMsg("rtp-messages.no-arena-available",
                        "{prefix}&#f38ba8Selected arena is currently in use! Please try again."));
                target.sendMessage(noArena);
                sender.sendMessage(noArena);
                return;
            }
        }

        for (List<UUID> list : queueMap.values()) {
            list.remove(sender.getUniqueId());
            list.remove(target.getUniqueId());
        }
        queueJoinTime.remove(sender.getUniqueId());
        queueJoinTime.remove(target.getUniqueId());

        target.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-accepted-target", "{prefix}&#a6e3a1Accepted duel!"),
                sender, target, item, req.customWins(), req.arenaName())));
        sender.sendMessage(plugin.toComponent(applyDuelPlaceholders(plugin.getMsg("rtp-messages.duel-accepted-sender", "{prefix}&#a6e3a1Duel accepted!"),
                sender, target, item, req.customWins(), req.arenaName())));

        startMatchmaking(sender, target, item, reservedArena, req.customWins());
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

    public boolean leaveQueueCommand(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> activeModes = getPlayerQueuedModes(uuid);
        queueJoinTime.remove(uuid);
        if (!activeModes.isEmpty()) {
            String modesStr = formatModeList(activeModes, false);
            for (List<UUID> list : queueMap.values()) list.remove(uuid);
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.left-queue", "{prefix}&#a6adc8Left queue: {modes}").replace("{modes}", modesStr)));
            return true;
        }
        player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.not-in-queue", "{prefix}&#f38ba8You are not in any match or queue.")));
        return false;
    }

    public void removePlayerFromAllQueues(UUID uuid) {
        for (List<UUID> list : queueMap.values()) list.remove(uuid);
        queueJoinTime.remove(uuid);
        duelRequests.remove(uuid);
        inCountdown.remove(uuid);
    }

    private void startMatchmaking(Player p1, Player p2, RtpItem item, ArenaManager.Arena reservedArena, int customWins) {
        p1.closeInventory();
        p2.closeInventory();
        inCountdown.add(p1.getUniqueId());
        inCountdown.add(p2.getUniqueId());

        String mapName = reservedArena != null ? reservedArena.name : item.mapDisplay;
        for (String line : plugin.getConfig().getStringList("rtp-messages.match-found-broadcast")) {
            Bukkit.broadcast(plugin.toComponent(applyPlaceholders(line.replace("{map}", mapName), item)
                    .replace("{player1}", p1.getName()).replace("{player2}", p2.getName())));
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
                    if (reservedArena != null) plugin.getArenaManager().releaseArena(reservedArena);
                    cancel();
                    if (p1.isOnline()) p1.sendMessage(plugin.toComponent("<glyph:warning_blue> &#f38ba8Opponent disconnected."));
                    if (p2.isOnline()) p2.sendMessage(plugin.toComponent("<glyph:warning_blue> &#f38ba8Opponent disconnected."));
                    return;
                }
                if (timer > 0) {
                    Component emojiTitle = getCircleEmoji(timer);
                    Component sub = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-countdown-subtitle", "{icon} &#cdd6f4{mode}"), item));
                    Title titleObj = Title.title(emojiTitle, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(1250), Duration.ofMillis(250)));
                    p1.showTitle(titleObj);
                    p2.showTitle(titleObj);

                    String ab = applyPlaceholders(plugin.getMsg("rtp-messages.match-actionbar", "{icon} &#cdd6f4{mode}"), item);
                    p1.sendActionBar(plugin.toComponent(ab.replace("{opponent_prefix}", p2Prefix).replace("{opponent_name}", p2.getName()).replace("{time}", String.valueOf(timer))));
                    p2.sendActionBar(plugin.toComponent(ab.replace("{opponent_prefix}", p1Prefix).replace("{opponent_name}", p1.getName()).replace("{time}", String.valueOf(timer))));

                    p1.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    p2.playSound(p2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
                    timer--;
                } else {
                    inCountdown.remove(p1.getUniqueId());
                    inCountdown.remove(p2.getUniqueId());
                    cancel();
                    if (reservedArena != null) {
                        plugin.getArenaManager().startArenaMatch(p1, p2, reservedArena, item, customWins);
                    } else {
                        teleportMatch(p1, p2, item);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private Component getCircleEmoji(int count) {
        String cfg = plugin.getConfig().getString("rtpq-menu.countdown-titles." + count);
        if (cfg != null && !cfg.isEmpty()) return plugin.toComponent(cfg);
        return switch (count) {
            case 5 -> plugin.toComponent("&#f38ba8❺");
            case 4 -> plugin.toComponent("&#f38ba8❹");
            case 3 -> plugin.toComponent("&#fab387❸");
            case 2 -> plugin.toComponent("&#fab387❷");
            case 1 -> plugin.toComponent("&#a6e3a1❶");
            default -> plugin.toComponent("&#fab387" + count);
        };
    }

    private void teleportMatch(Player p1, Player p2, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null || plugin.isWorldResetting(item.worldName)) {
            Component msg = plugin.toComponent(plugin.getMsg("rtp-messages.world-resetting", "{prefix}&#f38ba8Map is currently resetting, please wait a moment!"));
            p1.sendMessage(msg); p2.sendMessage(msg);
            return;
        }

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
            Component err = plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location."), item));
            p1.sendMessage(err); p2.sendMessage(err);
            return;
        }

        loc1.setDirection(loc2.toVector().subtract(loc1.toVector()).normalize());
        loc2.setDirection(loc1.toVector().subtract(loc2.toVector()).normalize());
        p1.teleport(loc1);
        p2.teleport(loc2);

        Title fTitle = Title.title(
                plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-started-title", "&#a6e3a1FIGHT!"), item)),
                plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.match-started-subtitle", "&#a6adc8Good luck"), item)),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500))
        );
        p1.showTitle(fTitle); p2.showTitle(fTitle);
        p1.playSound(loc1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        p2.playSound(loc2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void executeRtp(Player player, RtpItem item) {
        World world = Bukkit.getWorld(item.worldName);
        if (world == null || plugin.isWorldResetting(item.worldName)) {
            player.sendMessage(plugin.toComponent(plugin.getMsg("rtp-messages.world-resetting", "{prefix}&#f38ba8Map is currently resetting, please wait a moment!")));
            return;
        }
        player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.teleporting-rtp", "&#a6adc8Teleporting..."), item)));
        Location loc = findSafeLocation(world, item.minX, item.maxX, item.minZ, item.maxZ, Collections.singleton(player.getUniqueId()));
        if (loc != null) {
            player.teleport(loc);
            player.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.teleport-success", "&#a6adc8Teleported!"), item)
                    .replace("{x}", String.valueOf(loc.getBlockX())).replace("{y}", String.valueOf(loc.getBlockY())).replace("{z}", String.valueOf(loc.getBlockZ()))));
        } else {
            player.sendMessage(plugin.toComponent(applyPlaceholders(plugin.getMsg("rtp-messages.safe-loc-not-found", "&#f38ba8Could not find safe location."), item)));
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
