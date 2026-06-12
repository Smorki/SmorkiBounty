package com.smorki.smorkibounty;

// ─── Bukkit / Paper / Folia ───────────────────────────────────────────────────
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

// ─── Vault ────────────────────────────────────────────────────────────────────
import net.milkbowl.vault.economy.Economy;

// ─── Java stdlib ─────────────────────────────────────────────────────────────
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * SmorkiBounty – a single-file Folia/Paper 1.21.11 bounty plugin.
 *
 * <p>Threading contract
 * <ul>
 *   <li>All economy reads/writes run on the Folia {@link AsyncScheduler}.</li>
 *   <li>GUI content is sorted on async before the inventory is opened on the
 *       correct region thread via {@code runAtEntity}.</li>
 *   <li>Death reward payout is fully async; broadcasts are dispatched from
 *       the global region scheduler so they are safe from any context.</li>
 *   <li>The main region tick thread is never blocked.</li>
 * </ul>
 */
@SuppressWarnings("UnstableApiUsage")
public final class SmorkiBounty extends JavaPlugin {

    // ── Singleton handles ────────────────────────────────────────────────────
    private static SmorkiBounty INSTANCE;
    private static Economy      ECONOMY;

    /** Thread-safe bounty store: targetUUID → amount */
    static final ConcurrentHashMap<UUID, Double> BOUNTIES = new ConcurrentHashMap<>();

    /** Players currently viewing the bounty GUI (prevents item-click exploits). */
    static final Set<UUID> GUI_VIEWERS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Current bounty GUI page per viewer. */
    static final ConcurrentHashMap<UUID, Integer> GUI_PAGES = new ConcurrentHashMap<>();

    static final MiniMessage MM = MiniMessage.miniMessage();

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        INSTANCE = this;

        printAscii();
        saveDefaultConfig(); // writes config.yml if absent
        loadConfig();
        loadBounties();

        if (!hookVault()) {
            getLogger().severe("Vault / Economy not found – disabling SmorkiBounty.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register commands
        Objects.requireNonNull(getCommand("bounty"))
               .setExecutor(new BountyCommand());
        Objects.requireNonNull(getCommand("bounty"))
               .setTabCompleter(new BountyCommand());
        Objects.requireNonNull(getCommand("bounties"))
               .setExecutor(new BountyCommand());
        Objects.requireNonNull(getCommand("bounties"))
               .setTabCompleter(new BountyCommand());
        Objects.requireNonNull(getCommand("smorkibounty"))
               .setExecutor(new BountyCommand());
        Objects.requireNonNull(getCommand("smorkibounty"))
               .setTabCompleter(new BountyCommand());

        // Register listener
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new BountyMenu.MenuListener(), this);

        getLogger().info("SmorkiBounty enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Persist bounties on shutdown
        saveBounties();
        getLogger().info("SmorkiBounty disabled.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static SmorkiBounty inst()    { return INSTANCE; }
    static Economy      economy() { return ECONOMY; }

    private boolean hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        ECONOMY = rsp.getProvider();
        getLogger().info("Hooked into Vault economy: " + ECONOMY.getName());
        return true;
    }

    /** Re-reads config values (used by /smorkibounty reload). */
    static void loadConfig() {
        // Config is read on-demand via Cfg helpers – no cache needed.
        inst().getLogger().info("Configuration (re)loaded.");
    }

    private void printAscii() {
        String[] art = {
            " ",
            "  ██████  ███    ███  ██████  ██████  ██   ██ ██",
            "  ██      ████  ████ ██    ██ ██   ██ ██  ██  ██",
            "  ███████ ██ ████ ██ ██    ██ ██████  █████   ██",
            "       ██ ██  ██  ██ ██    ██ ██   ██ ██  ██  ██",
            "  ██████  ██      ██  ██████  ██   ██ ██   ██ ██",
            "            B O U N T Y   S Y S T E M",
            " "
        };
        for (String line : art) getLogger().info(line);
    }

    // ── Default config.yml ───────────────────────────────────────────────────
    // Paper/Folia automatically writes src/main/resources/config.yml;
    // we embed it via saveDefaultConfig() which reads the jar resource.
    // The actual default content is declared here so IDE tooling can see it.
    // At runtime the file on disk (or jar resource) is the source of truth.

    /* config.yml (embedded as jar resource – see src/main/resources/config.yml)
    ─────────────────────────────────────────────────────────────────────────────
    settings:
      minimum-bounty: 1000
      high-bounty-threshold: 50000
      tax-percentage: 5.0
    messages:
      prefix: "<gray>SMORKIBOUNTY » </gray>"
      bounty-placed: "<gray>A bounty of <gold>$%amount%</gold> has been placed on %player%!</gray>"
      bounty-claimed-high: "<gray>LEGENDARY HUNT! %killer% has executed %victim% and claimed a massive bounty of <gold>$%amount%</gold>!</gray>"
      bounty-claimed-normal: "<gray>%killer% claimed the <gold>$%amount%</gold> bounty on %victim%.</gray>"
      insufficient-funds: "<gray>You do not have enough money in your account!</gray>"
    gui:
      title: "<gray>Bounties</gray>"
      border-item: "BLACK_STAINED_GLASS_PANE"
      skull-name: "<gray>%target_player%</gray>"
      skull-lore:
        - "<dark_gray>----------------------</dark_gray>"
        - "<gray>Current Bounty: <gold>$%amount%</gold></gray>"
        - "<gray>Status: <red>Wanted Dead</red></gray>"
        - "<dark_gray>----------------------</dark_gray>"
    ─────────────────────────────────────────────────────────────────────────────
    */

    // ── Cfg – thin config accessor (always reads live FileConfiguration) ─────

    static final class Cfg {
        private Cfg() {}

        static FileConfiguration c()  { return inst().getConfig(); }

        static double   minBounty()       { return c().getDouble("settings.minimum-bounty",      1_000); }
        static double   highThreshold()   { return c().getDouble("settings.high-bounty-threshold",50_000); }
        static double   taxPct()          { return c().getDouble("settings.tax-percentage",       5.0); }
        static String   prefix()          { return c().getString ("messages.prefix",              ""); }
        static String   msgPlaced()       { return c().getString ("messages.bounty-placed",       ""); }
        static String   msgClaimedHigh()  { return c().getString ("messages.bounty-claimed-high", ""); }
        static String   msgClaimedNorm()  { return c().getString ("messages.bounty-claimed-normal",""); }
        static String   msgNoFunds()      { return c().getString ("messages.insufficient-funds",  ""); }
        static String   guiTitle()        { return c().getString ("gui.title",                    "Bounties"); }
        static String   borderMat()       { return c().getString ("gui.border-item",              "BLACK_STAINED_GLASS_PANE"); }
        static String   skullName()       { return c().getString ("gui.skull-name",               "%target_player%"); }

        @SuppressWarnings("unchecked")
        static List<String> skullLore() {
            return (List<String>) c().getList("gui.skull-lore", Collections.emptyList());
        }

        /** Converts legacy %placeholders% into MiniMessage tags and deserializes the result. */
        static Component deserialize(String raw, TagResolver... extra) {
            String converted = raw.replaceAll("%([A-Za-z0-9_]+)%", "<$1>");
            return MM.deserialize(converted, extra);
        }

        /** Builds a prefixed Component from a raw MiniMessage string. */
        static Component prefixed(String raw, TagResolver... extra) {
            String full = prefix() + raw;
            return deserialize(full, extra);
        }
    }

    // ── Persistence for bounties (simple YAML file) ──────────────────────

    private static File bountyFile() {
        File dir = inst().getDataFolder();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "bounties.yml");
    }

    static void loadBounties() {
        File f = bountyFile();
        if (!f.exists()) {
            inst().getLogger().info("No bounties file found; starting fresh.");
            return;
        }
        try {
            var cfg = YamlConfiguration.loadConfiguration(f);
            int loaded = 0;
            for (String key : cfg.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    double val = cfg.getDouble(key, 0.0);
                    if (val > 0.0) {
                        BOUNTIES.put(id, val);
                        loaded++;
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            inst().getLogger().info("Loaded " + loaded + " bounties from disk.");
        } catch (Exception ex) {
            inst().getLogger().log(Level.SEVERE, "Failed to load bounties.yml", ex);
        }
    }

    static void saveBounties() {
        File f = bountyFile();
        var cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : BOUNTIES.entrySet()) {
            cfg.set(e.getKey().toString(), e.getValue());
        }
        try {
            cfg.save(f);
        } catch (IOException ex) {
            inst().getLogger().log(Level.SEVERE, "Failed to save bounties.yml", ex);
        }
    }

    // =========================================================================
    // 3. BountyCommand
    // =========================================================================

    /**
     * Handles:
     * <ul>
     *   <li>{@code /bounty set <player> <amount>} – place a bounty (async economy deduction)</li>
     *   <li>{@code /bounties}                   – open the sorted bounty GUI</li>
     *   <li>{@code /smorkibounty reload}           – reload config</li>
     * </ul>
     */
    static final class BountyCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender,
                                 Command cmd,
                                 String label,
                                 String[] args) {

            // ── /smorkibounty reload ─────────────────────────────────────────
            if (cmd.getName().equalsIgnoreCase("bounties")) {
                return handleGui(sender);
            }

            if (cmd.getName().equalsIgnoreCase("smorkibounty")) {
                if (!sender.hasPermission("smorkibounty.reload")) {
                    sender.sendMessage(Cfg.prefixed("<red>No permission.</red>"));
                    return true;
                }
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    inst().reloadConfig();
                    loadConfig();
                    sender.sendMessage(Cfg.prefixed("<green>Config reloaded.</green>"));
                } else {
                    sender.sendMessage(Cfg.prefixed("<yellow>Usage: /smorkibounty reload</yellow>"));
                }
                return true;
            }

            // ── /bounty ──────────────────────────────────────────────────────
            if (args.length == 0) {
                sendBountyHelp(sender);
                return true;
            }

            return switch (args[0].toLowerCase()) {
                case "set"  -> handleSet(sender, args);
                default     -> { sendBountyHelp(sender); yield true; }
            };
        }

        // ── /bounty set <player> <amount> ────────────────────────────────────

        private boolean handleSet(CommandSender sender, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Cfg.prefixed("<red>Only players may place bounties.</red>"));
                return true;
            }
            if (!player.hasPermission("smorkibounty.set")) {
                player.sendMessage(Cfg.prefixed("<red>No permission.</red>"));
                return true;
            }
            if (args.length < 3) {
                player.sendMessage(Cfg.prefixed("<yellow>Usage: /bounty set <player> <amount></yellow>"));
                return true;
            }

            // Parse amount synchronously (cheap, no I/O)
            double amount;
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(Cfg.prefixed("<red>Invalid amount.</red>"));
                return true;
            }
            if (amount < Cfg.minBounty()) {
                player.sendMessage(Cfg.prefixed(
                    "<red>Minimum bounty is <gold>$" + Cfg.minBounty() + "</gold>.</red>"));
                return true;
            }

            final String targetName = args[1];
            @SuppressWarnings("deprecation")
            final OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

            if (!target.hasPlayedBefore() && !target.isOnline()) {
                player.sendMessage(Cfg.prefixed("<red>Player not found.</red>"));
                return true;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(Cfg.prefixed("<red>You cannot place a bounty on yourself.</red>"));
                return true;
            }

            final double finalAmount = amount;

            // ── ALL economy work is async ────────────────────────────────────
            Bukkit.getAsyncScheduler().runNow(inst(), task -> {
                Economy eco = economy();
                if (!eco.has(player, finalAmount)) {
                    // Send message back safely (global region scheduler is safe anywhere)
                    Bukkit.getGlobalRegionScheduler().run(inst(), t ->
                        player.sendMessage(Cfg.prefixed(Cfg.msgNoFunds()))
                    );
                    return;
                }

                eco.withdrawPlayer(player, finalAmount);
                BOUNTIES.merge(target.getUniqueId(), finalAmount, Double::sum);

                // Persist change
                saveBounties();

                String displayName = target.getName() != null ? target.getName() : targetName;
                TagResolver tr = TagResolver.builder()
                    .resolver(Placeholder.parsed("amount", String.format("%.2f", finalAmount)))
                    .resolver(Placeholder.parsed("player", displayName))
                    .build();
                Component msg = Cfg.prefixed(Cfg.msgPlaced(), tr);

                Bukkit.getGlobalRegionScheduler().run(inst(), t ->
                    Bukkit.broadcast(msg)
                );
            });

            return true;
        }

        // ── /bounty gui ──────────────────────────────────────────────────────

        private boolean handleGui(CommandSender sender) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Cfg.prefixed("<red>Only players can open the GUI.</red>"));
                return true;
            }
            if (!player.hasPermission("smorkibounty.gui")) {
                player.sendMessage(Cfg.prefixed("<red>No permission.</red>"));
                return true;
            }
            BountyMenu.openAsync(player);
            return true;
        }

        private static void sendBountyHelp(CommandSender s) {
            s.sendMessage(Cfg.prefixed(
                "<yellow>/bounty set <player> <amount></yellow> <gray>– Place a bounty</gray>"));
            s.sendMessage(Cfg.prefixed(
                "<yellow>/bounties</yellow> <gray>– View active bounties</gray>"));
        }

        // ── Tab completion ───────────────────────────────────────────────────

        @Override
        public List<String> onTabComplete(CommandSender sender,
                                          Command cmd,
                                          String alias,
                                          String[] args) {
            if (cmd.getName().equalsIgnoreCase("smorkibounty")) {
                return args.length == 1
                    ? List.of("reload").stream()
                          .filter(s -> s.startsWith(args[0].toLowerCase()))
                          .collect(Collectors.toList())
                    : Collections.emptyList();
            }

            // /bounty
            if (args.length == 1) {
                return List.of("set").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                String prefix = args[1].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                return List.of("1000", "5000", "10000", "50000");
            }
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // 4. BountyMenu
    // =========================================================================

    /**
     * Builds a 54-slot inventory entirely on an async thread, then opens it on
     * the player's entity-region thread. No blocking on the main tick thread.
     *
     * <p>Layout (54 slots):
     * <pre>
     *  Row 0: border  border  border  border  border  border  border  border  border
     *  Row 1: border  skull   skull   skull   skull   skull   skull   skull   border
     *  Row 2: border  skull   skull   skull   skull   skull   skull   skull   border
     *  Row 3: border  skull   skull   skull   skull   skull   skull   skull   border
     *  Row 4: border  skull   skull   skull   skull   skull   skull   skull   border
     *  Row 5: border  border  border  border  border  border  border  border  border
     * </pre>
     * Inner content area: 28 slots (7 × 4 rows).
     */
    static final class BountyMenu {

        private BountyMenu() {}

        private static final int PREVIOUS_SLOT = 45;
        private static final int NEXT_SLOT     = 53;

        private static final Set<Integer> CONTROL_SLOTS = Set.of(
            PREVIOUS_SLOT, NEXT_SLOT
        );

        private static final class BountyHolder implements InventoryHolder {
            @Override
            public Inventory getInventory() {
                return null;
            }
        }

        // Slots that hold skulls in the inner 7×4 GUI content area.
        private static final int[] CONTENT_SLOTS = buildContentSlots();

        private static int[] buildContentSlots() {
            List<Integer> list = new ArrayList<>();
            for (int row = 1; row <= 4; row++) {
                int base = row * 9;
                for (int column = 1; column <= 7; column++) {
                    list.add(base + column);
                }
            }
            return list.stream().mapToInt(Integer::intValue).toArray();
        }

        private static boolean isBorderSlot(int slot) {
            if (slot < 0 || slot >= 54) return false;
            for (int contentSlot : CONTENT_SLOTS) {
                if (contentSlot == slot) return false;
            }
            return !CONTROL_SLOTS.contains(slot);
        }

        /**
         * Entry point – schedules an async task that:
         * <ol>
         *   <li>Snapshots + sorts the bounty map.</li>
         *   <li>Builds all {@link ItemStack}s.</li>
         *   <li>Constructs the {@link Inventory}.</li>
         *   <li>Schedules opening on the player's entity thread.</li>
         * </ol>
         */
        static void openAsync(Player player) {
            openAsync(player, GUI_PAGES.getOrDefault(player.getUniqueId(), 0));
        }

        static void openAsync(Player player, int requestedPage) {
            Bukkit.getAsyncScheduler().runNow(inst(), task -> {
                // 1. Snapshot + sort (descending amount) – entirely async
                List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(BOUNTIES.entrySet());
                sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) CONTENT_SLOTS.length));
                int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
                int fromIndex = page * CONTENT_SLOTS.length;
                int toIndex = Math.min(sorted.size(), fromIndex + CONTENT_SLOTS.length);

                // 2. Build inventory (Bukkit inventory creation is safe off main thread
                //    in Paper/Folia as of 1.20+; it becomes a data structure until opened)
                Component title = Cfg.deserialize(Cfg.guiTitle() + " <gray>(Page " + (page + 1) + "/" + totalPages + ")</gray>");
                Inventory inv   = Bukkit.createInventory(new BountyHolder(), 54, title);

                for (int source = fromIndex, slotIndex = 0; source < toIndex; source++, slotIndex++) {
                    Map.Entry<UUID, Double> entry = sorted.get(source);
                    ItemStack skull = buildSkull(entry.getKey(), entry.getValue());
                    inv.setItem(CONTENT_SLOTS[slotIndex], skull);
                }

                ItemStack borderItem = buildControlItem(Material.valueOf(Cfg.borderMat()), " ");
                for (int slot = 0; slot < 54; slot++) {
                    if (isBorderSlot(slot)) {
                        inv.setItem(slot, borderItem);
                    }
                }

                inv.setItem(PREVIOUS_SLOT, buildControlItem(Material.ARROW, "<gray>Previous Page</gray>"));
                inv.setItem(NEXT_SLOT, buildControlItem(Material.ARROW, "<gray>Next Page</gray>"));

                // 5. Open on the player's region thread (runAtEntity is Folia-safe)
                player.getScheduler().run(inst(), t -> {
                    GUI_VIEWERS.add(player.getUniqueId());
                    GUI_PAGES.put(player.getUniqueId(), page);
                    player.openInventory(inv);
                }, null /* retired Runnable – player is offline, do nothing */);
            });
        }

        // ── Item builders ────────────────────────────────────────────────────

        private static ItemStack buildControlItem(Material mat, String name) {
            ItemStack item = new ItemStack(mat);
            ItemMeta  meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(MM.deserialize(name));
                item.setItemMeta(meta);
            }
            return item;
        }

        private static ItemStack buildSkull(UUID targetUuid, double amount) {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta  = (SkullMeta) skull.getItemMeta();
            if (meta == null) return skull;

            // Resolve name – prefer online player, fall back to offline player record
            Player online = Bukkit.getPlayer(targetUuid);
            String name   = online != null
                          ? online.getName()
                          : Objects.toString(Bukkit.getOfflinePlayer(targetUuid).getName(), "Unknown");

            // Apply skin (safe to call off main thread in Paper 1.20+)
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));

            // Display name
            TagResolver tr = TagResolver.builder()
                .resolver(Placeholder.parsed("target_player", name))
                .resolver(Placeholder.parsed("amount",        String.format("%.2f", amount)))
                .build();

            meta.displayName(Cfg.deserialize(Cfg.skullName(), tr));

            // Lore
            List<Component> lore = Cfg.skullLore().stream()
                .map(line -> Cfg.deserialize(line, tr))
                .collect(Collectors.toList());
            meta.lore(lore);

            skull.setItemMeta(meta);
            return skull;
        }

        // ── Inventory event listener (inner static class) ────────────────────

        static final class MenuListener implements Listener {

            /**
             * Prevents players from taking items out of the GUI.
             * InventoryClickEvent fires on the region thread that owns the player.
             */
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onClick(InventoryClickEvent event) {
                if (!(event.getWhoClicked() instanceof Player player)) return;
                if (!(event.getView().getTopInventory().getHolder() instanceof BountyHolder)) return;
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);

                int clickedSlot = event.getRawSlot();
                int currentPage = GUI_PAGES.getOrDefault(player.getUniqueId(), 0);
                if (clickedSlot == PREVIOUS_SLOT) {
                    BountyMenu.openAsync(player, currentPage - 1);
                } else if (clickedSlot == NEXT_SLOT) {
                    BountyMenu.openAsync(player, currentPage + 1);
                }
            }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onDrag(InventoryDragEvent event) {
                if (!(event.getWhoClicked() instanceof Player player)) return;
                if (!(event.getView().getTopInventory().getHolder() instanceof BountyHolder)) return;
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
            }

            @EventHandler
            public void onClose(InventoryCloseEvent event) {
                if (event.getPlayer() instanceof Player player) {
                    GUI_VIEWERS.remove(player.getUniqueId());
                    GUI_PAGES.remove(player.getUniqueId());
                }
            }
        }
    }

    // =========================================================================
    // 5. PlayerDeathListener
    // =========================================================================

    /**
     * Listens for {@link PlayerDeathEvent}.
     *
     * <p>When the victim has an active bounty and was killed by another player:
     * <ol>
     *   <li>Remove the bounty entry atomically.</li>
     *   <li>Apply tax, deposit reward to killer – <strong>all async</strong>.</li>
     *   <li>Broadcast the appropriate message (high-threshold vs normal).</li>
     * </ol>
     */
    static final class PlayerDeathListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
        public void onPlayerDeath(PlayerDeathEvent event) {
            Player victim = event.getEntity();
            UUID   victimUuid = victim.getUniqueId();

            // Check if victim has a bounty (atomic remove – claim once)
            Double rawBounty = BOUNTIES.remove(victimUuid);
            if (rawBounty == null) return; // no bounty

            // Persist removal asynchronously
            Bukkit.getAsyncScheduler().runNow(inst(), t -> saveBounties());

            // Must have been killed by a player
            if (!(victim.getKiller() instanceof Player killer)) {
                // Killer is not a player – put bounty back
                BOUNTIES.put(victimUuid, rawBounty);
                return;
            }

            final double bounty  = rawBounty;
            final String victimName = victim.getName();
            final String killerName = killer.getName();
            final UUID   killerUuid = killer.getUniqueId();

            // ── Async: deposit reward & broadcast ────────────────────────────
            Bukkit.getAsyncScheduler().runNow(inst(), task -> {
                double tax     = bounty * (Cfg.taxPct() / 100.0);
                double reward  = bounty - tax;

                // Deposit via Vault (async-safe)
                economy().depositPlayer(Bukkit.getOfflinePlayer(killerUuid), reward);

                // Build broadcast message
                String template = bounty >= Cfg.highThreshold()
                    ? Cfg.msgClaimedHigh()
                    : Cfg.msgClaimedNorm();

                TagResolver tr = TagResolver.builder()
                    .resolver(Placeholder.parsed("killer", killerName))
                    .resolver(Placeholder.parsed("victim", victimName))
                    .resolver(Placeholder.parsed("amount", String.format("%.2f", reward)))
                    .build();

                Component broadcast = Cfg.prefixed(template, tr);

                // Broadcast on the global region scheduler (safe from async context)
                Bukkit.getGlobalRegionScheduler().run(inst(), t ->
                    Bukkit.broadcast(broadcast)
                );
            });
        }
    }
}
