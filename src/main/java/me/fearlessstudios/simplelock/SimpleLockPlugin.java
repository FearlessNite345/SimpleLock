package me.fearlessstudios.simplelock;

import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class SimpleLockPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final BlockFace[] CARDINAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final String PLUGIN_VERSION = "1.0.2";
    private static final double CONFIG_VERSION = 1.0D;
    private static final String MODRINTH_PROJECT_ID = "simplelock";
    private static final String MODRINTH_LOADER = "paper";
    private static final String MODRINTH_PROJECT_URL = "https://modrinth.com/plugin/simplelock";
    private static final String[] DISPLAY_TYPES = {"chat", "action_bar", "bossbar", "none"};
    private static final int[] WHITELIST_PLAYER_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int WHITELIST_PREVIOUS_PAGE_SLOT = 48;
    private static final int WHITELIST_INFO_SLOT = 49;
    private static final int WHITELIST_NEXT_PAGE_SLOT = 50;

    private NamespacedKey ownerUuidKey;
    private NamespacedKey ownerNameKey;
    private NamespacedKey protectSignKey;
    private NamespacedKey trustedPlayersKey;

    private TownyHook townyHook;
    private VaultHook vaultHook;
    private volatile String latestAvailableVersion;
    private double loadedConfigVersion;
    private boolean configOutdated;
    private List<String> missingConfigPaths = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ownerUuidKey = new NamespacedKey(this, "owner_uuid");
        ownerNameKey = new NamespacedKey(this, "owner_name");
        protectSignKey = new NamespacedKey(this, "protect_sign");
        trustedPlayersKey = new NamespacedKey(this, "trusted_players");

        refreshConfigVersionState();
        refreshExternalIntegrations();

        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand simpleLockCommand = getCommand("simplelock");
        if (simpleLockCommand != null) {
            simpleLockCommand.setExecutor(this);
            simpleLockCommand.setTabCompleter(this);
        } else {
            log("Command 'simplelock' is missing from plugin.yml.");
        }

        log("SimpleLock enabled.");
        startUpdateCheck();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignCreate(SignChangeEvent event) {
        String firstLine = plain(event.line(0));
        if (!"[Protect]".equalsIgnoreCase(firstLine)) {
            return;
        }

        Block signBlock = event.getBlock();
        if (!(signBlock.getState() instanceof Sign sign)) {
            return;
        }

        if (!(signBlock.getBlockData() instanceof WallSign wallSign)) {
            event.setCancelled(true);
            return;
        }

        Block attached = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
        Container container = getProtectableContainer(attached);
        if (container == null) {
            event.setCancelled(true);
            return;
        }

        if (isProtected(container)) {
            event.setCancelled(true);
            return;
        }

        Player player = event.getPlayer();

        if (getConfig().getBoolean("economy.enabled", false)
                && getConfig().getBoolean("economy.charge_on.sign_create", true)) {

            double cost = getConfig().getDouble("economy.protect_cost", 0.0);
            if (cost > 0.0) {
                if (vaultHook == null || !vaultHook.isReady()) {
                    logDebug("economy", "Economy enabled but Vault is not ready.");
                } else if (!vaultHook.has(player, cost)) {
                    event.setCancelled(true);
                    sendConfiguredMessage(
                            player,
                            format(getConfig().getString("messages.economy_not_enough"), "amount", String.format("%.2f", cost))
                    );
                    return;
                } else if (!vaultHook.withdraw(player, cost)) {
                    event.setCancelled(true);
                    return;
                } else {
                    sendConfiguredMessage(
                            player,
                            format(getConfig().getString("messages.economy_paid"), "amount", String.format("%.2f", cost))
                    );
                    logDebug("economy", player.getName() + " paid " + cost + " to protect container at " + formatLoc(container.getBlock()));
                }
            }
        }

        String uuid = player.getUniqueId().toString();
        String name = player.getName();

        protectContainer(container, uuid, name, new HashSet<>());
        protectSign(sign, uuid, name);

        event.line(0, Component.text("[Protected]", NamedTextColor.DARK_BLUE));
        event.line(1, Component.text(name, NamedTextColor.BLACK));

        sendConfiguredMessage(player, getConfig().getString("messages.chest_protected"));
        logDebug("protection_create", player.getName() + " protected chest at " + formatLoc(signBlock));
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }

        Container container = getProtectableContainer(event.getClickedBlock());
        if (container == null) {
            return;
        }

        ProtectionData data = getData(container);
        if (data == null) {
            return;
        }

        Player player = event.getPlayer();
        if (canAccess(player, container, data, true)) {
            return;
        }

        event.setCancelled(true);
        sendConfiguredMessage(player, getConfig().getString("messages.chest_denied"));
        logDebug("access_denied", player.getName() + " denied open at " + formatLoc(container.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignEdit(PlayerOpenSignEvent event) {
        Sign sign = event.getSign();
        if (!isProtect(sign)) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        sendConfiguredMessage(player, getConfig().getString("messages.sign_denied"));
        logDebug("access_denied", player.getName() + " denied sign edit at " + formatLoc(sign.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        Container container = getProtectableContainer(block);
        if (container != null) {
            ProtectionData data = getData(container);
            if (data != null && !canAccess(player, container, data, false)) {
                event.setCancelled(true);
                sendConfiguredMessage(player, getConfig().getString("messages.chest_break_denied"));
                logDebug("access_denied", player.getName() + " denied chest break at " + formatLoc(block));
                return;
            }
        }

        if (block.getState() instanceof Sign sign && isProtect(sign)) {
            String owner = sign.getPersistentDataContainer().get(ownerUuidKey, PersistentDataType.STRING);
            boolean canBreakProtection = owner != null
                    && (player.getUniqueId().toString().equals(owner) || player.hasPermission("simplelock.admin"));
            if (!canBreakProtection) {
                event.setCancelled(true);
                sendConfiguredMessage(player, getConfig().getString("messages.sign_break_denied"));
                logDebug("access_denied", player.getName() + " denied sign break at " + formatLoc(block));
                return;
            }

            Container attachedContainer = getAttachedContainer(sign);
            clearProtection(sign);

            if (attachedContainer != null) {
                unprotectContainer(attachedContainer);
            }

            sendConfiguredMessage(player, getConfig().getString("messages.chest_unprotected"));
            logDebug("protection_create", player.getName() + " unprotected chest at " + formatLoc(block));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onChestPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!(block.getState() instanceof Chest placedChest)) {
            return;
        }

        Player player = event.getPlayer();

        for (BlockFace face : CARDINAL_FACES) {
            Block relative = block.getRelative(face);
            if (!(relative.getState() instanceof Chest nearbyChest)) {
                continue;
            }

            ProtectionData nearbyData = getDataFromAnyHalf(nearbyChest);
            if (nearbyData == null) {
                continue;
            }

            if (player.hasPermission("simplelock.admin")) {
                protectChest(placedChest, nearbyData.uuid(), nearbyData.name(), nearbyData.trustedPlayers());
                logDebug("bypass_use", player.getName() + " used admin bypass while attaching chest at " + formatLoc(block));
                return;
            }

            if (player.getUniqueId().toString().equals(nearbyData.uuid())) {
                protectChest(placedChest, nearbyData.uuid(), nearbyData.name(), nearbyData.trustedPlayers());
                logDebug("bypass_use", player.getName() + " added their own chest to protected double chest at " + formatLoc(block));
                return;
            }

            if (townyHook != null) {
                if (getConfig().getBoolean("towny.mayor_bypass_break", false)
                        && townyHook.isMayor(player, nearbyChest.getLocation())) {
                    protectChest(placedChest, nearbyData.uuid(), nearbyData.name(), nearbyData.trustedPlayers());
                    logDebug("bypass_use", player.getName() + " used mayor bypass while attaching chest at " + formatLoc(block));
                    return;
                }

                if (getConfig().getBoolean("towny.nation_leader_bypass_break", false)
                        && townyHook.isNationLeader(player, nearbyChest.getLocation())) {
                    protectChest(placedChest, nearbyData.uuid(), nearbyData.name(), nearbyData.trustedPlayers());
                    logDebug("bypass_use", player.getName() + " used nation leader bypass while attaching chest at " + formatLoc(block));
                    return;
                }
            }

            event.setCancelled(true);
            sendConfiguredMessage(
                    player,
                    getConfig().getString(
                            "messages.chest_attach_denied",
                            "&cYou cannot attach a chest to someone else's protected chest."
                    )
            );
            logDebug("access_denied", player.getName() + " was denied attaching chest to protected chest at " + formatLoc(block));
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!getConfig().getBoolean("protection.hopper_protection.enabled", true)) {
            return;
        }

        if (inventoryIsProtectedContainer(event.getSource()) || inventoryIsProtectedContainer(event.getDestination())) {
            event.setCancelled(true);
            logDebug("hopper_denied", "Blocked hopper interaction involving a protected container.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (getConfig().getBoolean("protection.explosion_protection.enabled", false)) {
            protectExplosionList(event.blockList());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isAdminNotificationRecipient(player)) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () ->
                {
                    if (configOutdated) {
                        sendOutdatedConfigMessage(player);
                    }

                    if (getConfig().getBoolean("update_checker.announce_to_admins", true)
                            && latestAvailableVersion != null) {
                        player.sendMessage(Component.text(
                                "[SimpleLock]: New update available: " + latestAvailableVersion
                                        + " | Current version: " + PLUGIN_VERSION,
                                NamedTextColor.YELLOW
                        ));
                        player.sendMessage(Component.text(
                                "[SimpleLock]: Download it here: " + MODRINTH_PROJECT_URL,
                                NamedTextColor.GOLD
                        ));
                    }
                },
                40L
        );
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (getConfig().getBoolean("protection.explosion_protection.enabled", false)) {
            protectExplosionList(event.blockList());
        }
    }

    private void protectExplosionList(List<Block> blocks) {
        blocks.removeIf(block -> {
            Container container = getProtectableContainer(block);
            boolean remove = container != null && isProtected(container)
                    || block.getState() instanceof Sign sign && isProtect(sign);

            if (remove) {
                logDebug("explosions", "Prevented explosion damage at " + formatLoc(block));
            }

            return remove;
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getInventory().getHolder() instanceof TrustGuiHolder trustGuiHolder) {
            handleWhitelistGuiClick(event, player, trustGuiHolder);
            return;
        }

        if (!(event.getInventory().getHolder() instanceof AdminSettingsGuiHolder)) {
            return;
        }

        handleAdminSettingsGuiClick(event, player);
    }

    private void handleWhitelistGuiClick(InventoryClickEvent event, Player player, TrustGuiHolder guiHolder) {
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        Container container = getProtectableContainer(guiHolder.location().getBlock());
        if (container == null) {
            return;
        }

        if (!isProtected(container)) {
            return;
        }

        if (isNotWhitelistOwner(player, container)) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_not_owner"));
            return;
        }

        int totalPages = getWhitelistTotalPages(player);
        int currentPage = Math.max(0, Math.min(guiHolder.page(), totalPages - 1));

        if (event.getRawSlot() == WHITELIST_PREVIOUS_PAGE_SLOT) {
            if (currentPage > 0) {
                openWhitelistGui(player, container, currentPage - 1);
            }
            return;
        }

        if (event.getRawSlot() == WHITELIST_NEXT_PAGE_SLOT) {
            if (currentPage + 1 < totalPages) {
                openWhitelistGui(player, container, currentPage + 1);
            }
            return;
        }

        if (event.getRawSlot() == WHITELIST_INFO_SLOT) {
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta)) {
            return;
        }

        OfflinePlayer target = skullMeta.getOwningPlayer();
        if (target == null) {
            return;
        }

        String targetName = target.getName();
        if (targetName == null || targetName.isBlank()) {
            return;
        }

        TrustChangeResult result = toggleTrustedPlayer(container, target.getUniqueId());

        if (result == TrustChangeResult.ADDED) {
            sendConfiguredMessage(player, format(getConfig().getString("messages.gui_player_added"), "player", targetName));
            logDebug("trust_changes", player.getName() + " trusted " + targetName + " at " + formatLoc(container.getBlock()));
        } else if (result == TrustChangeResult.REMOVED) {
            sendConfiguredMessage(player, format(getConfig().getString("messages.gui_player_removed"), "player", targetName));
            logDebug("trust_changes", player.getName() + " untrusted " + targetName + " at " + formatLoc(container.getBlock()));
        } else {
            return;
        }

        refreshWhitelistGui(event.getInventory(), player, container);
        player.updateInventory();
    }

    private void handleAdminSettingsGuiClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        if (!hasSettingsAccess(player)) {
            sendConfiguredMessage(player, getConfig().getString("messages.no_permission"));
            return;
        }

        if (event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        switch (event.getRawSlot()) {
            case 10 -> toggleBooleanSetting(player, "protection.hopper_protection.enabled", "Hopper Protection");
            case 11 -> toggleBooleanSetting(player, "protection.explosion_protection.enabled", "Explosion Protection");
            case 12 -> toggleBooleanSetting(player, "gui.enabled", "Whitelist GUI");
            case 13 -> {
                if (!event.isLeftClick() && !event.isRightClick()) {
                    return;
                }
                cycleMessageDisplayType(player, event.isLeftClick());
            }
            case 14 -> toggleBooleanSetting(player, "update_checker.enabled", "Update Checker");
            case 15 -> toggleBooleanSetting(player, "update_checker.announce_to_admins", "Admin Update Notices");
            case 19 -> toggleBooleanSetting(player, "towny.enabled", "Towny Integration");
            case 20 -> toggleBooleanSetting(player, "towny.mayor_bypass_open", "Mayor Open Bypass");
            case 21 -> toggleBooleanSetting(player, "towny.mayor_bypass_break", "Mayor Break Bypass");
            case 22 -> toggleBooleanSetting(player, "towny.nation_leader_bypass_open", "Nation Leader Open Bypass");
            case 23 -> toggleBooleanSetting(player, "towny.nation_leader_bypass_break", "Nation Leader Break Bypass");
            case 24 -> toggleBooleanSetting(player, "economy.enabled", "Economy Integration");
            case 25 -> toggleBooleanSetting(player, "economy.charge_on.sign_create", "Charge On Protect");
            case 31 -> {
                if (!event.isLeftClick() && !event.isRightClick()) {
                    return;
                }
                adjustProtectCost(player, event.isLeftClick(), event.isShiftClick());
            }
            default -> {
                return;
            }
        }

        refreshAdminSettingsGui(event.getInventory());
        player.updateInventory();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("simplelock")) {
            return false;
        }

        if (args.length == 0
                || args[0].equalsIgnoreCase("whitelist")
                || args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Player only.");
                return true;
            }
            return handleWhitelistCommand(player, args);
        }

        if (args[0].equalsIgnoreCase("transfer")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Player only.");
                return true;
            }
            return handleTransferCommand(player, args);
        }

        if (args[0].equalsIgnoreCase("settings")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Player only.");
                return true;
            }

            if (!hasSettingsAccess(player)) {
                sendCommandMessage(sender, getConfig().getString("messages.no_permission"));
                return true;
            }

            openAdminSettingsGui(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("simplelock.reload")) {
                sendCommandMessage(sender, getConfig().getString("messages.no_permission"));
                return true;
            }

            reloadConfig();
            refreshConfigVersionState();
            refreshExternalIntegrations();
            refreshUpdateChecker();
            sendCommandMessage(sender, getConfig().getString("messages.config_reloaded"));
            if (configOutdated && sender instanceof Player player) {
                sendOutdatedConfigMessage(player);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("updateconfig")) {
            if (!sender.hasPermission("simplelock.reload")) {
                sendCommandMessage(sender, getConfig().getString("messages.no_permission"));
                return true;
            }

            updateConfigFile(sender);
            return true;
        }

        return true;
    }

    private boolean handleWhitelistCommand(Player player, String[] args) {
        boolean openGui = args.length == 0
                || args[0].equalsIgnoreCase("gui")
                || (args[0].equalsIgnoreCase("whitelist") && args.length == 1);

        if (openGui) {
            if (!getConfig().getBoolean("gui.enabled", true)) {
                return true;
            }

            Container container = getTargetProtectedContainer(player);
            if (container == null) {
                sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
                return true;
            }

            if (isNotWhitelistOwner(player, container)) {
                sendConfiguredMessage(player, getConfig().getString("messages.gui_not_owner"));
                return true;
            }

            openWhitelistGui(player, container, 0);
            return true;
        }

        if (args.length < 3) {
            sendConfiguredMessage(player, getConfig().getString(
                    "messages.whitelist_usage",
                    "&eUse &6/simplelock whitelist&e, &6/simplelock whitelist add <player>&e, or &6/simplelock whitelist remove <player>&e."
            ));
            return true;
        }

        boolean add = args[1].equalsIgnoreCase("add");
        boolean remove = args[1].equalsIgnoreCase("remove");
        if (!add && !remove) {
            sendConfiguredMessage(player, getConfig().getString(
                    "messages.whitelist_usage",
                    "&eUse &6/simplelock whitelist&e, &6/simplelock whitelist add <player>&e, or &6/simplelock whitelist remove <player>&e."
            ));
            return true;
        }

        Container container = getTargetProtectedContainer(player);
        if (container == null) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
            return true;
        }

        if (isNotWhitelistOwner(player, container)) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_not_owner"));
            return true;
        }

        OfflinePlayer target = resolveKnownPlayer(args[2]);
        if (target == null) {
            sendConfiguredMessage(player, format(
                    getConfig().getString("messages.player_not_found", "&cPlayer &e%player% &cwas not found."),
                    "player",
                    args[2]
            ));
            return true;
        }

        String targetName = resolvedPlayerName(target, args[2]);
        TrustChangeResult result = setTrustedPlayer(container, target.getUniqueId(), add);

        switch (result) {
            case ADDED -> {
                sendConfiguredMessage(player, format(getConfig().getString("messages.gui_player_added"), "player", targetName));
                logDebug("trust_changes", player.getName() + " trusted " + targetName + " at " + formatLoc(container.getBlock()));
            }
            case REMOVED -> {
                sendConfiguredMessage(player, format(getConfig().getString("messages.gui_player_removed"), "player", targetName));
                logDebug("trust_changes", player.getName() + " untrusted " + targetName + " at " + formatLoc(container.getBlock()));
            }
            case ALREADY_TRUSTED -> sendConfiguredMessage(player, format(
                    getConfig().getString("messages.whitelist_already_added", "&e%player% &cis already on the whitelist."),
                    "player",
                    targetName
            ));
            case NOT_TRUSTED -> sendConfiguredMessage(player, format(
                    getConfig().getString("messages.whitelist_not_added", "&e%player% &cis not on the whitelist."),
                    "player",
                    targetName
            ));
            case OWNER_TARGET -> sendConfiguredMessage(player, getConfig().getString(
                    "messages.whitelist_owner_target",
                    "&cYou cannot modify whitelist access for the owner."
            ));
            default -> sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
        }

        return true;
    }

    private boolean handleTransferCommand(Player player, String[] args) {
        if (args.length < 2) {
            sendConfiguredMessage(player, getConfig().getString(
                    "messages.transfer_usage",
                    "&eUse &6/simplelock transfer <player> &ewhile looking at a protected container."
            ));
            return true;
        }

        Container container = getTargetProtectedContainer(player);
        if (container == null) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
            return true;
        }

        if (isNotWhitelistOwner(player, container)) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_not_owner"));
            return true;
        }

        ProtectionData data = getData(container);
        if (data == null) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
            return true;
        }

        OfflinePlayer target = resolveKnownPlayer(args[1]);
        if (target == null) {
            sendConfiguredMessage(player, format(
                    getConfig().getString("messages.player_not_found", "&cPlayer &e%player% &cwas not found."),
                    "player",
                    args[1]
            ));
            return true;
        }

        if (data.uuid().equals(target.getUniqueId().toString())) {
            sendConfiguredMessage(player, format(
                    getConfig().getString("messages.transfer_same_owner", "&e%player% &calready owns this protected container."),
                    "player",
                    resolvedPlayerName(target, args[1])
            ));
            return true;
        }

        String targetName = resolvedPlayerName(target, args[1]);
        transferOwnership(container, target.getUniqueId().toString(), targetName);
        sendConfiguredMessage(player, format(
                getConfig().getString("messages.transfer_success", "&aTransferred protection to &e%player%&a."),
                "player",
                targetName
        ));
        logDebug("ownership_transfers", player.getName() + " transferred protection to " + targetName + " at " + formatLoc(container.getBlock()));
        return true;
    }

    private void openWhitelistGui(Player player, Container container, int requestedPage) {
        int totalPages = getWhitelistTotalPages(player);
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        String title = getConfig().getString("gui.whitelist_title", "&8Whitelist Players");
        Inventory inventory = Bukkit.createInventory(
                new TrustGuiHolder(container.getLocation(), page),
                54,
                LegacyComponentSerializer.legacyAmpersand().deserialize(title + " &7(" + (page + 1) + "/" + totalPages + ")")
        );

        refreshWhitelistGui(inventory, player, container);
        player.openInventory(inventory);
    }

    private void openAdminSettingsGui(Player player) {
        String title = getConfig().getString("gui.admin_title", "&8SimpleLock Settings");
        Inventory inventory = Bukkit.createInventory(
                new AdminSettingsGuiHolder(),
                36,
                LegacyComponentSerializer.legacyAmpersand().deserialize(title)
        );

        refreshAdminSettingsGui(inventory);
        player.openInventory(inventory);
    }

    private void refreshWhitelistGui(Inventory inventory, Player viewer, Container container) {
        if (!(inventory.getHolder() instanceof TrustGuiHolder trustGuiHolder)) {
            return;
        }

        ItemStack filler = new ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        Set<UUID> trusted = getTrustedPlayers(container);
        List<Player> candidates = getWhitelistCandidates(viewer);
        int totalPages = getWhitelistTotalPages(candidates.size());
        int page = Math.max(0, Math.min(trustGuiHolder.page(), totalPages - 1));
        int startIndex = page * WHITELIST_PLAYER_SLOTS.length;

        for (int slotIndex = 0; slotIndex < WHITELIST_PLAYER_SLOTS.length; slotIndex++) {
            int candidateIndex = startIndex + slotIndex;
            if (candidateIndex >= candidates.size()) {
                break;
            }

            Player online = candidates.get(candidateIndex);
            boolean isTrusted = trusted.contains(online.getUniqueId());

            ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            ItemMeta rawMeta = head.getItemMeta();
            if (!(rawMeta instanceof org.bukkit.inventory.meta.SkullMeta meta)) {
                continue;
            }

            meta.setOwningPlayer(online);
            meta.displayName(Component.text(online.getName(), isTrusted ? NamedTextColor.GREEN : NamedTextColor.RED));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(
                    "Whitelist Status: " + (isTrusted ? "True" : "False"),
                    isTrusted ? NamedTextColor.GREEN : NamedTextColor.RED
            ));
            lore.add(Component.text(
                    "Click to " + (isTrusted ? "remove from" : "add to") + " whitelist",
                    NamedTextColor.GRAY
            ));
            lore.add(Component.text("UUID: " + online.getUniqueId(), NamedTextColor.DARK_GRAY));
            meta.lore(lore);

            head.setItemMeta(meta);
            inventory.setItem(WHITELIST_PLAYER_SLOTS[slotIndex], head);
        }

        inventory.setItem(WHITELIST_PREVIOUS_PAGE_SLOT, createWhitelistPageItem(
                "Previous Page",
                page > 0,
                page,
                totalPages
        ));
        inventory.setItem(WHITELIST_INFO_SLOT, createWhitelistInfoItem(page, totalPages, candidates.size(), trusted.size()));
        inventory.setItem(WHITELIST_NEXT_PAGE_SLOT, createWhitelistPageItem(
                "Next Page",
                page + 1 < totalPages,
                page,
                totalPages
        ));
    }

    private ItemStack createWhitelistPageItem(String title, boolean enabled, int page, int totalPages) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, enabled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Page " + (page + 1) + " of " + totalPages, NamedTextColor.GRAY));
        lore.add(Component.text(
                enabled ? "Click to change pages." : "No more pages in this direction.",
                enabled ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY
        ));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createWhitelistInfoItem(int page, int totalPages, int onlineCandidates, int trustedCount) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("SimpleLock Whitelist", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Page " + (page + 1) + " of " + totalPages, NamedTextColor.GRAY),
                Component.text("Online players listed: " + onlineCandidates, NamedTextColor.GRAY),
                Component.text("Trusted players: " + trustedCount, NamedTextColor.GREEN),
                Component.text("Use /simplelock whitelist add/remove for offline players.", NamedTextColor.YELLOW)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private List<Player> getWhitelistCandidates(Player viewer) {
        List<Player> candidates = new ArrayList<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(viewer.getUniqueId())) {
                candidates.add(online);
            }
        }

        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    private int getWhitelistTotalPages(Player viewer) {
        return getWhitelistTotalPages(getWhitelistCandidates(viewer).size());
    }

    private int getWhitelistTotalPages(int candidateCount) {
        return Math.max(1, (candidateCount + WHITELIST_PLAYER_SLOTS.length - 1) / WHITELIST_PLAYER_SLOTS.length);
    }

    private OfflinePlayer resolveKnownPlayer(String input) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(input)) {
                return online;
            }
        }

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(input)) {
                return offline;
            }
        }

        return null;
    }

    private String resolvedPlayerName(OfflinePlayer target, String fallback) {
        return target.getName() == null || target.getName().isBlank() ? fallback : target.getName();
    }

    private List<String> getKnownPlayerNames() {
        Set<String> names = new LinkedHashSet<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && !offline.getName().isBlank()) {
                names.add(offline.getName());
            }
        }

        return names.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<String> filterCompletions(List<String> options, String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }

    private void refreshAdminSettingsGui(Inventory inventory) {
        if (!(inventory.getHolder() instanceof AdminSettingsGuiHolder)) {
            return;
        }

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(4, createAdminInfoItem());
        setToggleItem(
                inventory,
                10,
                Material.HOPPER,
                "Hopper Protection",
                getConfig().getBoolean("protection.hopper_protection.enabled", true),
                "Blocks hopper transfers involving protected chests."
        );
        setToggleItem(
                inventory,
                11,
                Material.TNT,
                "Explosion Protection",
                getConfig().getBoolean("protection.explosion_protection.enabled", false),
                "Prevents explosions from destroying protected chests and signs."
        );
        setToggleItem(
                inventory,
                12,
                Material.CHEST,
                "Whitelist GUI",
                getConfig().getBoolean("gui.enabled", true),
                "Allows /simplelock whitelist for protected container owners."
        );
        inventory.setItem(13, createDisplayTypeItem());
        setToggleItem(
                inventory,
                14,
                Material.COMPASS,
                "Update Checker",
                getConfig().getBoolean("update_checker.enabled", true),
                "Checks Modrinth for newer plugin versions."
        );
        setToggleItem(
                inventory,
                15,
                Material.BELL,
                "Admin Update Notices",
                getConfig().getBoolean("update_checker.announce_to_admins", true),
                "Shows update notices to admins when they join."
        );
        setToggleItem(
                inventory,
                19,
                Material.MAP,
                "Towny Integration",
                getConfig().getBoolean("towny.enabled", false),
                "Turns Towny bypass integration on or off."
        );
        setToggleItem(
                inventory,
                20,
                Material.IRON_DOOR,
                "Mayor Open Bypass",
                getConfig().getBoolean("towny.mayor_bypass_open", false),
                "Allows mayors to open protected chests in their town."
        );
        setToggleItem(
                inventory,
                21,
                Material.IRON_PICKAXE,
                "Mayor Break Bypass",
                getConfig().getBoolean("towny.mayor_bypass_break", false),
                "Allows mayors to break protected chests in their town."
        );
        setToggleItem(
                inventory,
                22,
                Material.DIAMOND,
                "Nation Leader Open Bypass",
                getConfig().getBoolean("towny.nation_leader_bypass_open", false),
                "Allows nation leaders to open protected chests."
        );
        setToggleItem(
                inventory,
                23,
                Material.DIAMOND_PICKAXE,
                "Nation Leader Break Bypass",
                getConfig().getBoolean("towny.nation_leader_bypass_break", false),
                "Allows nation leaders to break protected chests."
        );
        setToggleItem(
                inventory,
                24,
                Material.EMERALD,
                "Economy Integration",
                getConfig().getBoolean("economy.enabled", false),
                "Requires Vault and an economy provider for protect costs."
        );
        setToggleItem(
                inventory,
                25,
                Material.OAK_SIGN,
                "Charge On Protect",
                getConfig().getBoolean("economy.charge_on.sign_create", true),
                "Charges players when they create a protection sign."
        );
        inventory.setItem(31, createProtectCostItem());
    }

    private ItemStack createAdminInfoItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("SimpleLock Admin Settings", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Changes save immediately.", NamedTextColor.GRAY),
                Component.text("Left click toggles or moves forward.", NamedTextColor.GRAY),
                Component.text("Right click moves backward where supported.", NamedTextColor.GRAY),
                Component.text("Protect Cost: left/right = 1.00, shift = 10.00.", NamedTextColor.DARK_GRAY)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private void setToggleItem(Inventory inventory, int slot, Material material, String title, boolean enabled, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title, enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        meta.lore(List.of(
                Component.text("Current: " + (enabled ? "Enabled" : "Disabled"), enabled ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text(description, NamedTextColor.GRAY),
                Component.text("Click to toggle.", NamedTextColor.YELLOW)
        ));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private ItemStack createDisplayTypeItem() {
        String current = getConfig().getString("messages.display_type", "chat").toLowerCase(Locale.ROOT);

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Message Display Type", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("Current: " + prettifyDisplayType(current), NamedTextColor.GREEN),
                Component.text("Controls how plugin messages are shown.", NamedTextColor.GRAY),
                Component.text("Left click: next mode", NamedTextColor.YELLOW),
                Component.text("Right click: previous mode", NamedTextColor.YELLOW)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createProtectCostItem() {
        double current = getConfig().getDouble("economy.protect_cost", 0.0);

        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Protect Cost", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Current: $" + formatAmount(current), NamedTextColor.GREEN),
                Component.text("Cost charged when a chest is protected.", NamedTextColor.GRAY),
                Component.text("Left click: +1.00", NamedTextColor.YELLOW),
                Component.text("Right click: -1.00", NamedTextColor.YELLOW),
                Component.text("Shift + click: +/-10.00", NamedTextColor.YELLOW)
        ));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("simplelock")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("whitelist");
            completions.add("transfer");
            if (sender.hasPermission("simplelock.settings") || sender.hasPermission("simplelock.admin")) {
                completions.add("settings");
            }
            if (sender.hasPermission("simplelock.reload")) {
                completions.add("reload");
                completions.add("updateconfig");
            }

            return filterCompletions(completions, args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("whitelist")) {
                return filterCompletions(List.of("add", "remove"), args[1]);
            }

            if (args[0].equalsIgnoreCase("transfer")) {
                return filterCompletions(getKnownPlayerNames(), args[1]);
            }
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("whitelist")
                && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            return filterCompletions(getKnownPlayerNames(), args[2]);
        }

        return List.of();
    }

    private boolean inventoryIsProtectedContainer(Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container && isProtectableContainer(container)) {
            return isProtected(container);
        }

        if (holder instanceof DoubleChest dc) {
            return dc.getLeftSide() instanceof Chest left && isProtected(left)
                    || dc.getRightSide() instanceof Chest right && isProtected(right);
        }

        return false;
    }

    private boolean isProtectableContainer(Container container) {
        return container instanceof Chest || container instanceof Barrel;
    }

    private Container getProtectableContainer(Block block) {
        if (block == null) {
            return null;
        }

        if (block.getState() instanceof Chest chest) {
            return chest;
        }

        if (block.getState() instanceof Barrel barrel) {
            return barrel;
        }

        return null;
    }

    private Container getTargetProtectableContainer(Player player) {
        Block target = player.getTargetBlockExact(6);
        return target == null ? null : getProtectableContainer(target);
    }

    private Container getTargetProtectedContainer(Player player) {
        Container container = getTargetProtectableContainer(player);
        return container != null && isProtected(container) ? container : null;
    }

    private boolean isProtected(Container container) {
        return getData(container) != null;
    }

    private boolean isProtected(Chest chest) {
        return isProtected((Container) chest);
    }

    private boolean isNotWhitelistOwner(Player player, Container container) {
        if (player.hasPermission("simplelock.admin")) {
            return false;
        }

        ProtectionData data = getData(container);
        return data == null || !data.uuid().equals(player.getUniqueId().toString());
    }

    private TrustChangeResult toggleTrustedPlayer(Container container, UUID uuid) {
        ProtectionData data = getData(container);
        if (data == null) {
            return TrustChangeResult.NOT_PROTECTED;
        }

        if (data.uuid().equals(uuid.toString())) {
            return TrustChangeResult.OWNER_TARGET;
        }

        Set<UUID> trusted = new HashSet<>(data.trustedPlayers());
        boolean added = trusted.add(uuid);
        if (!added) {
            trusted.remove(uuid);
        }

        applyTrustedToContainer(container, trusted);
        return added ? TrustChangeResult.ADDED : TrustChangeResult.REMOVED;
    }

    private TrustChangeResult setTrustedPlayer(Container container, UUID uuid, boolean shouldTrust) {
        ProtectionData data = getData(container);
        if (data == null) {
            return TrustChangeResult.NOT_PROTECTED;
        }

        if (data.uuid().equals(uuid.toString())) {
            return TrustChangeResult.OWNER_TARGET;
        }

        Set<UUID> trusted = new HashSet<>(data.trustedPlayers());
        boolean changed;
        if (shouldTrust) {
            changed = trusted.add(uuid);
        } else {
            changed = trusted.remove(uuid);
        }

        if (!changed) {
            return shouldTrust ? TrustChangeResult.ALREADY_TRUSTED : TrustChangeResult.NOT_TRUSTED;
        }

        applyTrustedToContainer(container, trusted);
        return shouldTrust ? TrustChangeResult.ADDED : TrustChangeResult.REMOVED;
    }

    private Set<UUID> getTrustedPlayers(Container container) {
        ProtectionData data = getData(container);
        return data == null ? new HashSet<>() : new HashSet<>(data.trustedPlayers());
    }

    private Set<UUID> getTrustedPlayers(Chest chest) {
        return getTrustedPlayers((Container) chest);
    }

    private void protectContainer(Container container, String uuid, String name, Set<UUID> trustedPlayers) {
        if (container instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();

            if (holder instanceof DoubleChest dc) {
                if (dc.getLeftSide() instanceof Chest left) {
                    set(left, uuid, name, trustedPlayers);
                }
                if (dc.getRightSide() instanceof Chest right) {
                    set(right, uuid, name, trustedPlayers);
                }
                return;
            }
        }

        set(container, uuid, name, trustedPlayers);
    }

    private void protectChest(Chest chest, String uuid, String name, Set<UUID> trustedPlayers) {
        protectContainer(chest, uuid, name, trustedPlayers);
    }

    private void set(Container container, String uuid, String name, Set<UUID> trustedPlayers) {
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        pdc.set(ownerUuidKey, PersistentDataType.STRING, uuid);
        pdc.set(ownerNameKey, PersistentDataType.STRING, name);
        pdc.set(trustedPlayersKey, PersistentDataType.STRING, serializeTrustedPlayers(trustedPlayers));
        container.update();
    }

    private void unprotectContainer(Container container) {
        if (container instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();

            if (holder instanceof DoubleChest dc) {
                if (dc.getLeftSide() instanceof Chest left) {
                    clearContainerData(left);
                }
                if (dc.getRightSide() instanceof Chest right) {
                    clearContainerData(right);
                }
                return;
            }
        }

        clearContainerData(container);
    }

    private void unprotectChest(Chest chest) {
        unprotectContainer(chest);
    }

    private void clearContainerData(Container container) {
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        pdc.remove(ownerUuidKey);
        pdc.remove(ownerNameKey);
        pdc.remove(trustedPlayersKey);
        container.update();
    }

    private void applyTrustedToContainer(Container container, Set<UUID> trustedPlayers) {
        ProtectionData data = getData(container);
        if (data != null) {
            protectContainer(container, data.uuid(), data.name(), trustedPlayers);
        }
    }

    private void applyTrustedToChest(Chest chest, Set<UUID> trustedPlayers) {
        applyTrustedToContainer(chest, trustedPlayers);
    }

    private void transferOwnership(Container container, String uuid, String name) {
        ProtectionData data = getData(container);
        if (data == null) {
            return;
        }

        Set<UUID> trusted = new HashSet<>(data.trustedPlayers());
        trusted.remove(UUID.fromString(uuid));
        protectContainer(container, uuid, name, trusted);

        for (Sign sign : getProtectionSigns(container)) {
            updateProtectionSignOwner(sign, uuid, name);
        }
    }

    private void updateProtectionSignOwner(Sign sign, String uuid, String name) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(ownerUuidKey, PersistentDataType.STRING, uuid);
        pdc.set(ownerNameKey, PersistentDataType.STRING, name);
        pdc.set(protectSignKey, PersistentDataType.BYTE, (byte) 1);
        sign.line(0, Component.text("[Protected]", NamedTextColor.DARK_BLUE));
        sign.line(1, Component.text(name, NamedTextColor.BLACK));
        sign.update();
    }

    private void protectSign(Sign sign, String uuid, String name) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(ownerUuidKey, PersistentDataType.STRING, uuid);
        pdc.set(ownerNameKey, PersistentDataType.STRING, name);
        pdc.set(protectSignKey, PersistentDataType.BYTE, (byte) 1);
        sign.update();
    }

    private void clearProtection(Sign sign) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.remove(ownerUuidKey);
        pdc.remove(ownerNameKey);
        pdc.remove(protectSignKey);
        sign.update();
    }

    private boolean isProtect(Sign sign) {
        Byte b = sign.getPersistentDataContainer().get(protectSignKey, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    private ProtectionData getData(Container container) {
        if (container instanceof Chest chest) {
            return getDataFromAnyHalf(chest);
        }

        ProtectionData data = readSingleContainer(container);
        return data == null ? null : validateProtectionData(container, data);
    }

    private ProtectionData getData(Chest chest) {
        return getData((Container) chest);
    }

    private ProtectionData getDataFromAnyHalf(Chest chest) {
        ProtectionData direct = readSingleContainer(chest);
        if (direct != null) {
            return validateProtectionData(chest, direct);
        }

        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide() instanceof Chest left) {
                ProtectionData leftData = readSingleContainer(left);
                if (leftData != null) {
                    return validateProtectionData(chest, leftData);
                }
            }

            if (dc.getRightSide() instanceof Chest right) {
                ProtectionData rightData = readSingleContainer(right);
                if (rightData != null) {
                    return validateProtectionData(chest, rightData);
                }
            }
        }

        return null;
    }

    private ProtectionData readSingleContainer(Container container) {
        PersistentDataContainer pdc = container.getPersistentDataContainer();
        String uuid = pdc.get(ownerUuidKey, PersistentDataType.STRING);
        String name = pdc.get(ownerNameKey, PersistentDataType.STRING);
        String trustedRaw = pdc.get(trustedPlayersKey, PersistentDataType.STRING);

        return uuid == null ? null : new ProtectionData(uuid, name, deserializeTrustedPlayers(trustedRaw));
    }

    private ProtectionData validateProtectionData(Container container, ProtectionData data) {
        if (hasAttachedProtectionSign(container)) {
            return data;
        }

        unprotectContainer(container);
        logDebug("protection_cleanup", "Removed orphaned protection data at " + formatLoc(container.getBlock()));
        return null;
    }

    private boolean hasAttachedProtectionSign(Container container) {
        return !getProtectionSigns(container).isEmpty();
    }

    private List<Sign> getProtectionSigns(Container container) {
        List<Sign> signs = new ArrayList<>();
        Set<Location> seenLocations = new HashSet<>();

        for (Block containerBlock : getContainerBlocks(container)) {
            for (BlockFace face : CARDINAL_FACES) {
                Block relative = containerBlock.getRelative(face);
                if (!(relative.getState() instanceof Sign sign) || !isProtect(sign)) {
                    continue;
                }

                Container attachedContainer = getAttachedContainer(sign);
                if (attachedContainer != null
                        && isSameBlock(attachedContainer.getBlock(), containerBlock)
                        && seenLocations.add(sign.getLocation())) {
                    signs.add(sign);
                }
            }
        }

        return signs;
    }

    private List<Block> getContainerBlocks(Container container) {
        List<Block> blocks = new ArrayList<>();
        if (container instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                if (dc.getLeftSide() instanceof Chest left) {
                    blocks.add(left.getBlock());
                }
                if (dc.getRightSide() instanceof Chest right) {
                    Block rightBlock = right.getBlock();
                    if (blocks.isEmpty() || !isSameBlock(blocks.get(0), rightBlock)) {
                        blocks.add(rightBlock);
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            blocks.add(container.getBlock());
        }

        return blocks;
    }

    private boolean isSameBlock(Block first, Block second) {
        return first.getWorld().equals(second.getWorld())
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private boolean canAccess(Player player, Container container, ProtectionData data, boolean open) {
        if (player.hasPermission("simplelock.admin")) {
            logDebug("bypass_use", player.getName() + " used admin bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (player.getUniqueId().toString().equals(data.uuid())) {
            return true;
        }

        if (open && data.trustedPlayers().contains(player.getUniqueId())) {
            return true;
        }

        if (townyHook != null) {
            if (open
                    && getConfig().getBoolean("towny.mayor_bypass_open", false)
                    && townyHook.isMayor(player, container.getLocation())) {
                logDebug("bypass_use", player.getName() + " used mayor open bypass at " + formatLoc(container.getBlock()));
                return true;
            }

            if (!open
                    && getConfig().getBoolean("towny.mayor_bypass_break", false)
                    && townyHook.isMayor(player, container.getLocation())) {
                logDebug("bypass_use", player.getName() + " used mayor break bypass at " + formatLoc(container.getBlock()));
                return true;
            }

            if (open
                    && getConfig().getBoolean("towny.nation_leader_bypass_open", false)
                    && townyHook.isNationLeader(player, container.getLocation())) {
                logDebug("bypass_use", player.getName() + " used nation leader open bypass at " + formatLoc(container.getBlock()));
                return true;
            }

            if (!open
                    && getConfig().getBoolean("towny.nation_leader_bypass_break", false)
                    && townyHook.isNationLeader(player, container.getLocation())) {
                logDebug("bypass_use", player.getName() + " used nation leader break bypass at " + formatLoc(container.getBlock()));
                return true;
            }
        }

        return false;
    }

    private String serializeTrustedPlayers(Set<UUID> trustedPlayers) {
        return trustedPlayers == null || trustedPlayers.isEmpty()
                ? ""
                : trustedPlayers.stream().map(UUID::toString).collect(Collectors.joining(";"));
    }

    private Set<UUID> deserializeTrustedPlayers(String raw) {
        Set<UUID> result = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }

        for (String part : raw.split(";")) {
            if (part.isBlank()) {
                continue;
            }

            try {
                result.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return result;
    }

    public void sendConfiguredMessage(Player player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String type = getConfig().getString("messages.display_type", "chat").toLowerCase();
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(message);

        switch (type) {
            case "none" -> {
            }
            case "action_bar" -> player.sendActionBar(component);
            case "bossbar" -> {
                BossBar bossBar = BossBar.bossBar(component, 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
                player.showBossBar(bossBar);
                Bukkit.getScheduler().runTaskLater(this, () -> player.hideBossBar(bossBar), 60L);
            }
            default -> player.sendMessage(component);
        }
    }

    private String format(String input, String key, String value) {
        return input == null ? null : input.replace("%" + key + "%", value);
    }

    private void startUpdateCheck() {
        if (!getConfig().getBoolean("update_checker.enabled", true)) {
            log("Update checker disabled in config.");
            return;
        }

        String gameVersion = getServer().getMinecraftVersion().trim();

        CompletableFuture.runAsync(() -> {
            try {
                String currentVersion = PLUGIN_VERSION;
                String latestVersion = new ModrinthUpdateChecker().fetchLatestVersion(
                        MODRINTH_PROJECT_ID,
                        MODRINTH_LOADER,
                        gameVersion
                );
                int comparison = ModrinthUpdateChecker.compareVersions(latestVersion, currentVersion);

                if (comparison > 0) {
                    latestAvailableVersion = latestVersion;
                    log("New update available: " + latestVersion
                            + " | Current version: " + currentVersion);
                    log("Download it here: " + MODRINTH_PROJECT_URL);
                } else {
                    log("You are running the latest version: " + currentVersion);
                }
            } catch (Exception exception) {
                log("Update check failed: " + exception.getMessage());
            }
        });
    }

    private String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void refreshExternalIntegrations() {
        townyHook = null;
        if (getConfig().getBoolean("towny.enabled", true)
                && Bukkit.getPluginManager().getPlugin("Towny") != null) {
            townyHook = new TownyHook();
            log("Towny integration enabled.");
        } else if (!getConfig().getBoolean("towny.enabled", true)) {
            log("Towny integration disabled in config.");
        } else {
            log("Towny integration not enabled because Towny is not installed.");
        }

        vaultHook = null;
        if (getConfig().getBoolean("economy.enabled", false)
                && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            VaultHook candidate = new VaultHook();
            if (candidate.isReady()) {
                vaultHook = candidate;
                log("Vault economy integration enabled.");
            } else {
                log("Vault was found, but no economy provider is registered.");
            }
        } else if (!getConfig().getBoolean("economy.enabled", false)) {
            log("Vault economy integration disabled in config.");
        } else {
            log("Vault economy integration not enabled because Vault is not installed.");
        }
    }

    private void refreshConfigVersionState() {
        loadedConfigVersion = getConfig().getDouble("config_version", 0.0);
        missingConfigPaths = findMissingConfigPaths(loadBundledConfig(), getConfig());
        configOutdated = loadedConfigVersion < CONFIG_VERSION || !missingConfigPaths.isEmpty();

        if (configOutdated) {
            log("Config file is outdated. Expected config_version "
                    + formatConfigVersion(CONFIG_VERSION) + " but found " + formatConfigVersion(loadedConfigVersion)
                    + ". Review the latest config.yml and update your settings.");
            if (!missingConfigPaths.isEmpty()) {
                log("Missing config paths (" + missingConfigPaths.size() + "):");
                missingConfigPaths.forEach(path -> log(" - " + path));
            }
            log("Use /simplelock updateconfig to back up and merge missing default settings.");
        }
    }

    private void refreshUpdateChecker() {
        latestAvailableVersion = null;
        startUpdateCheck();
    }

    private boolean isAdminNotificationRecipient(Player player) {
        return player.hasPermission("simplelock.admin") || player.isOp();
    }

    private void sendOutdatedConfigMessage(Player player) {
        String message = getConfig().getString(
                "messages.config_outdated_admin",
                "&eSimpleLock config is outdated. Expected config_version &6%expected%&e but found &6%current%&e."
        );
        message = format(
                format(message, "expected", formatConfigVersion(CONFIG_VERSION)),
                "current",
                formatConfigVersion(loadedConfigVersion)
        );
        sendChatMessage(player, message);

        if (!missingConfigPaths.isEmpty()) {
            String missingMessage = getConfig().getString(
                    "messages.config_missing_paths_admin",
                    "&eMissing config paths: &6%paths%"
            );
            sendChatMessage(player, format(missingMessage, "paths", summarizeMissingConfigPaths(4)));
        }

        sendChatMessage(player, getConfig().getString(
                "messages.config_update_hint",
                "&eUse &6/simplelock updateconfig &eto back up and merge missing default settings."
        ));
    }

    private boolean hasSettingsAccess(Player player) {
        return player.hasPermission("simplelock.settings")
                || player.hasPermission("simplelock.admin")
                || player.isOp();
    }

    private void toggleBooleanSetting(Player player, String path, String settingName) {
        boolean updated = !getConfig().getBoolean(path);
        getConfig().set(path, updated);
        saveConfig();

        if ("towny.enabled".equals(path) || "economy.enabled".equals(path)) {
            refreshExternalIntegrations();
        } else if ("update_checker.enabled".equals(path)) {
            refreshUpdateChecker();
        }

        sendSettingUpdate(player, settingName, updated ? "Enabled" : "Disabled");
    }

    private void updateConfigFile(CommandSender sender) {
        YamlConfiguration bundledConfig = loadBundledConfig();
        List<String> missingBeforeUpdate = findMissingConfigPaths(bundledConfig, getConfig());
        boolean versionNeedsUpdate = loadedConfigVersion < CONFIG_VERSION;

        if (missingBeforeUpdate.isEmpty() && !versionNeedsUpdate) {
            sendCommandMessage(sender, getConfig().getString(
                    "messages.config_update_no_changes",
                    "&aConfig is already up to date."
            ));
            return;
        }

        Path configPath = getDataFolder().toPath().resolve("config.yml");
        String backupName = "config.backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".yml";
        Path backupPath = configPath.resolveSibling(backupName);

        try {
            Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

            for (String path : missingBeforeUpdate) {
                getConfig().set(path, bundledConfig.get(path));
            }

            getConfig().set("config_version", CONFIG_VERSION);
            saveConfig();
            reloadConfig();
            refreshConfigVersionState();
            refreshExternalIntegrations();
            refreshUpdateChecker();

            String success = getConfig().getString(
                    "messages.config_update_success",
                    "&aConfig updated. Backup created at &e%backup%&a. Added &e%count% &amissing setting(s)."
            );
            success = format(format(success, "backup", backupPath.getFileName().toString()), "count", String.valueOf(missingBeforeUpdate.size()));
            sendCommandMessage(sender, success);

            if (!missingBeforeUpdate.isEmpty()) {
                String merged = getConfig().getString(
                        "messages.config_update_added_paths",
                        "&eMerged paths: &6%paths%"
                );
                sendCommandMessage(sender, format(merged, "paths", summarizePaths(missingBeforeUpdate, 6)));
            }
        } catch (IOException exception) {
            log("Config update failed: " + exception.getMessage());
            sendCommandMessage(sender, getConfig().getString(
                    "messages.config_update_failed",
                    "&cConfig update failed. Check console for details."
            ));
        }
    }

    private void cycleMessageDisplayType(Player player, boolean forward) {
        String current = getConfig().getString("messages.display_type", "chat").toLowerCase(Locale.ROOT);
        int currentIndex = 0;

        for (int i = 0; i < DISPLAY_TYPES.length; i++) {
            if (DISPLAY_TYPES[i].equals(current)) {
                currentIndex = i;
                break;
            }
        }

        int direction = forward ? 1 : -1;
        String updated = DISPLAY_TYPES[Math.floorMod(currentIndex + direction, DISPLAY_TYPES.length)];
        getConfig().set("messages.display_type", updated);
        saveConfig();
        sendSettingUpdate(player, "Message Display Type", prettifyDisplayType(updated));
    }

    private void adjustProtectCost(Player player, boolean increase, boolean shift) {
        double step = shift ? 10.0 : 1.0;
        double current = getConfig().getDouble("economy.protect_cost", 0.0);
        double updated = increase ? current + step : current - step;
        updated = Math.max(0.0, Math.round(updated * 100.0) / 100.0);

        getConfig().set("economy.protect_cost", updated);
        saveConfig();
        sendSettingUpdate(player, "Protect Cost", "$" + formatAmount(updated));
    }

    private void sendSettingUpdate(Player player, String settingName, String value) {
        String message = getConfig().getString("messages.setting_updated", "&aUpdated &e%setting% &ato &e%value%&a.");
        sendConfiguredMessage(player, format(format(message, "setting", settingName), "value", value));
    }

    private String prettifyDisplayType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "action_bar" -> "Action Bar";
            case "bossbar" -> "Boss Bar";
            case "none" -> "None";
            default -> "Chat";
        };
    }

    private String formatAmount(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private String formatConfigVersion(double version) {
        return String.format(Locale.US, "%.1f", version);
    }

    private YamlConfiguration loadBundledConfig() {
        YamlConfiguration config = new YamlConfiguration();

        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return config;
            }

            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException exception) {
            log("Failed to read bundled config.yml: " + exception.getMessage());
            return config;
        }
    }

    private List<String> findMissingConfigPaths(FileConfiguration bundledConfig, FileConfiguration currentConfig) {
        List<String> missingPaths = new ArrayList<>();

        for (String path : bundledConfig.getKeys(true)) {
            if (path.isBlank() || bundledConfig.isConfigurationSection(path)) {
                continue;
            }

            if (!currentConfig.contains(path)) {
                missingPaths.add(path);
            }
        }

        missingPaths.sort(String.CASE_INSENSITIVE_ORDER);
        return missingPaths;
    }

    private String summarizeMissingConfigPaths(int limit) {
        return summarizePaths(missingConfigPaths, limit);
    }

    private String summarizePaths(List<String> paths, int limit) {
        if (paths.isEmpty()) {
            return "none";
        }

        int count = Math.min(limit, paths.size());
        String summary = String.join(", ", paths.subList(0, count));
        if (paths.size() > count) {
            summary += " +" + (paths.size() - count) + " more";
        }

        return summary;
    }

    private void sendChatMessage(Player player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    private void sendCommandMessage(CommandSender sender, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        if (sender instanceof Player player) {
            sendChatMessage(player, message);
            return;
        }

        sender.sendMessage(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message)));
    }

    private Container getAttachedContainer(Sign sign) {
        if (!(sign.getBlock().getBlockData() instanceof WallSign wallSign)) {
            return null;
        }

        Block attached = sign.getBlock().getRelative(wallSign.getFacing().getOppositeFace());
        return getProtectableContainer(attached);
    }

    private void log(String s) {
        getLogger().info(s);
    }

    private void logDebug(String key, String message) {
        if (getConfig().getBoolean("logging.debug." + key, true)) {
            getLogger().info(message);
        }
    }

    private String formatLoc(Block block) {
        return block.getWorld().getName() + " x=" + block.getX() + " y=" + block.getY() + " z=" + block.getZ();
    }

    private record ProtectionData(String uuid, String name, Set<UUID> trustedPlayers) {
    }

    private record TrustGuiHolder(Location location, int page) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }

    private enum TrustChangeResult {
        ADDED,
        REMOVED,
        ALREADY_TRUSTED,
        NOT_TRUSTED,
        OWNER_TARGET,
        NOT_PROTECTED
    }

    private record AdminSettingsGuiHolder() implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }
}
