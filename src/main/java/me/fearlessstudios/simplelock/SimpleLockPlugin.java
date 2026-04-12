package me.fearlessstudios.simplelock;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
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
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
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
    private static final String PLUGIN_VERSION = "1.1.0";
    private static final double CONFIG_VERSION = 1.1D;
    private static final String MODRINTH_PROJECT_ID = "simplelock";
    private static final String[] MODRINTH_LOADERS = {"paper", "spigot"};
    private static final String MODRINTH_PROJECT_URL = "https://modrinth.com/plugin/simplelock";
    private static final String[] DISPLAY_TYPES = {"chat", "action_bar", "bossbar", "none"};
    private static final int INFO_TRUSTED_PLAYER_SUMMARY_LIMIT = 6;
    private static final int ADMIN_CONFIG_PATH_SUMMARY_LIMIT = 4;
    private static final int ADMIN_SETTINGS_GUI_SIZE = 45;
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
    private GriefPreventionHook griefPreventionHook;
    private LandsHook landsHook;
    private VaultHook vaultHook;
    private YamlConfiguration langConfig;
    private volatile String latestAvailableVersion;
    private double loadedConfigVersion;
    private boolean configOutdated;
    private List<String> missingConfigPaths = List.of();
    private List<String> obsoleteConfigPaths = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLangConfig();

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
        String firstLine = event.getLine(0);
        if (!"[Protect]".equalsIgnoreCase(firstLine)) {
            return;
        }

        Block signBlock = event.getBlock();
        if (!(signBlock.getState() instanceof Sign)) {
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

        if (getConfig().getBoolean("economy.enabled", false)) {

            double cost = getConfig().getDouble("economy.protect_cost", 0.0);
            if (cost > 0.0) {
                if (vaultHook == null || !vaultHook.isReady()) {
                    logDebug("economy", "Economy enabled but Vault is not ready.");
                } else if (!vaultHook.has(player, cost)) {
                    event.setCancelled(true);
                    sendConfiguredMessage(
                            player,
                            format(lang("messages.economy_not_enough", "&cYou need &6$%amount% &cto protect this container."), "amount", String.format("%.2f", cost))
                    );
                    return;
                } else if (!vaultHook.withdraw(player, cost)) {
                    event.setCancelled(true);
                    return;
                } else {
                    sendConfiguredMessage(
                            player,
                            format(lang("messages.economy_paid", "&aYou paid &6$%amount% &ato protect this container."), "amount", String.format("%.2f", cost))
                    );
                    logDebug("economy", player.getName() + " paid " + cost + " to protect container at " + formatLoc(container.getBlock()));
                }
            }
        }

        String uuid = player.getUniqueId().toString();
        String name = player.getName();

        protectContainer(container, uuid, name, new HashSet<>());

        event.setLine(0, ChatColor.DARK_BLUE + "[Protected]");
        event.setLine(1, ChatColor.BLACK + name);

        // Apply PDC and wax after the sign state is fully committed by the server.
        Bukkit.getScheduler().runTask(this, () -> {
            if (signBlock.getState() instanceof Sign placedSign) {
                protectSign(placedSign, uuid, name);
            }
        });

        sendConfiguredMessage(player, lang("messages.chest_protected", "&aContainer protected."));
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
        sendConfiguredMessage(player, lang("messages.chest_denied", "&cThis container is protected."));
        logDebug("access_denied", player.getName() + " denied open at " + formatLoc(container.getBlock()));
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
                sendConfiguredMessage(player, lang("messages.chest_break_denied", "&cYou cannot break this protected container."));
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
                sendConfiguredMessage(player, lang("messages.sign_break_denied", "&cYou cannot break this protection sign."));
                logDebug("access_denied", player.getName() + " denied sign break at " + formatLoc(block));
                return;
            }

            Container attachedContainer = getAttachedContainer(sign);
            if (attachedContainer != null) {
                removeProtection(attachedContainer);
                refundProtectionCost(player, owner, attachedContainer);
            } else {
                clearProtection(sign);
            }

            sendConfiguredMessage(player, lang("messages.chest_unprotected", "&aContainer unprotected."));
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

            if (canAccess(player, nearbyChest, nearbyData, false)) {
                protectContainer(placedChest, nearbyData.uuid(), nearbyData.name(), nearbyData.trustedPlayers());
                return;
            }

            event.setCancelled(true);
            sendConfiguredMessage(
                    player,
                    lang(
                            "messages.chest_attach_denied",
                            "&cYou cannot attach a chest to someone else's protected chest."
                    )
            );
            logDebug("access_denied", player.getName() + " was denied attaching chest to protected chest at " + formatLoc(block));
            return;
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHopperPlace(BlockPlaceEvent event) {
        if (!getConfig().getBoolean("protection.hopper_protection.enabled", true)
                || event.getBlockPlaced().getType() != Material.HOPPER) {
            return;
        }

        Player player = event.getPlayer();

        for (Container container : getAdjacentProtectableContainers(event.getBlockPlaced())) {
            ProtectionData data = getData(container);
            if (data == null || canAccess(player, container, data, false)) {
                continue;
            }

            event.setCancelled(true);
            sendConfiguredMessage(player, lang(
                    "messages.hopper_place_denied",
                    "&cYou cannot place a hopper next to someone else's protected container."
            ));
            logDebug(
                    "hopper_denied",
                    player.getName() + " was denied placing a hopper near protected container at " + formatLoc(container.getBlock())
            );
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!getConfig().getBoolean("protection.hopper_protection.enabled", true)) {
            return;
        }

        boolean sourceIsHopper = event.getSource().getHolder() instanceof org.bukkit.block.Hopper
                || event.getSource().getHolder() instanceof HopperMinecart;
        boolean destinationIsHopper = event.getDestination().getHolder() instanceof org.bukkit.block.Hopper
                || event.getDestination().getHolder() instanceof HopperMinecart;
        if (!sourceIsHopper && !destinationIsHopper) {
            return;
        }

        if (inventoryIsProtectedContainer(event.getSource()) || inventoryIsProtectedContainer(event.getDestination())) {
            event.setCancelled(true);
            logDebug(
                    "hopper_denied",
                    "Blocked hopper transfer between "
                            + describeInventory(event.getSource()) + " and " + describeInventory(event.getDestination()) + "."
            );
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
                        player.sendMessage(ChatColor.YELLOW
                                + "[SimpleLock]: New update available: " + latestAvailableVersion
                                + " | Current version: " + PLUGIN_VERSION);
                        player.sendMessage(ChatColor.GOLD
                                + "[SimpleLock]: Download it here: " + MODRINTH_PROJECT_URL);
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

        if (!(event.getInventory().getHolder() instanceof AdminSettingsGuiHolder adminSettingsGuiHolder)) {
            return;
        }

        handleAdminSettingsGuiClick(event, player, adminSettingsGuiHolder);
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
            sendConfiguredMessage(player, lang("messages.gui_not_owner", "&cYou must own this protected container to manage its whitelist."));
            return;
        }

        int totalPages = getWhitelistTotalPages(getWhitelistCandidates(player, container).size());
        int currentPage = Math.clamp(guiHolder.page(), 0, totalPages - 1);

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

        ItemMeta meta = requireItemMeta(item);
        if (!(meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta)) {
            return;
        }

        OfflinePlayer target = skullMeta.getOwningPlayer();
        if (target == null) {
            return;
        }

        String targetName = displayName(target);

        TrustChangeResult result = toggleTrustedPlayer(container, target.getUniqueId());

        if (result == TrustChangeResult.ADDED) {
            sendConfiguredMessage(player, format(lang("messages.gui_player_added", "&aAdded &e%player% &ato the whitelist."), "player", targetName));
            logDebug("trust_changes", player.getName() + " trusted " + targetName + " at " + formatLoc(container.getBlock()));
        } else if (result == TrustChangeResult.REMOVED) {
            sendConfiguredMessage(player, format(lang("messages.gui_player_removed", "&cRemoved &e%player% &cfrom the whitelist."), "player", targetName));
            logDebug("trust_changes", player.getName() + " untrusted " + targetName + " at " + formatLoc(container.getBlock()));
        } else {
            return;
        }

        refreshWhitelistGui(event.getInventory(), player, container);
    }

    private void handleAdminSettingsGuiClick(InventoryClickEvent event, Player player, AdminSettingsGuiHolder guiHolder) {
        event.setCancelled(true);
        if (!hasSettingsAccess(player)) {
            sendConfiguredMessage(player, lang("messages.no_permission", "&cYou do not have permission."));
            return;
        }

        if (event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        boolean refresh = switch (guiHolder.menu()) {
            case ROOT -> handleAdminSettingsRootClick(event, player);
            case PROTECTION -> handleProtectionSettingsClick(event, player);
            case ECONOMY -> handleEconomySettingsClick(event, player);
            case TOWNY -> handleTownySettingsClick(event, player);
            case GRIEFPREVENTION -> handleGriefPreventionSettingsClick(event, player);
            case LANDS -> handleLandsSettingsClick(event, player);
        };

        if (refresh) {
            refreshAdminSettingsGui(event.getInventory(), guiHolder.menu());
        }
    }

    private boolean handleAdminSettingsRootClick(InventoryClickEvent event, Player player) {
        return switch (event.getRawSlot()) {
            case 10 -> {
                toggleBooleanSetting(player, "gui.enabled", "Whitelist GUI");
                yield true;
            }
            case 11 -> {
                if (!event.isLeftClick() && !event.isRightClick()) {
                    yield false;
                }
                cycleMessageDisplayType(player, event.isLeftClick());
                yield true;
            }
            case 12 -> {
                toggleBooleanSetting(player, "update_checker.enabled", "Update Checker");
                yield true;
            }
            case 13 -> {
                toggleBooleanSetting(player, "update_checker.announce_to_admins", "Admin Update Notices");
                yield true;
            }
            case 20 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.PROTECTION);
                yield false;
            }
            case 21 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.ECONOMY);
                yield false;
            }
            case 23 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.TOWNY);
                yield false;
            }
            case 24 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.GRIEFPREVENTION);
                yield false;
            }
            case 25 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.LANDS);
                yield false;
            }
            default -> false;
        };
    }

    private boolean handleProtectionSettingsClick(InventoryClickEvent event, Player player) {
        return switch (event.getRawSlot()) {
            case 20 -> {
                toggleBooleanSetting(player, "protection.hopper_protection.enabled", "Hopper Protection");
                yield true;
            }
            case 24 -> {
                toggleBooleanSetting(player, "protection.explosion_protection.enabled", "Explosion Protection");
                yield true;
            }
            case 40 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.ROOT);
                yield false;
            }
            default -> false;
        };
    }

    private boolean handleEconomySettingsClick(InventoryClickEvent event, Player player) {
        return switch (event.getRawSlot()) {
            case 20 -> {
                toggleBooleanSetting(player, "economy.enabled", "Economy Integration");
                yield true;
            }
            case 22 -> {
                toggleBooleanSetting(player, "economy.refund_on_unprotect", "Refund On Unprotect");
                yield true;
            }
            case 24 -> {
                if (!event.isLeftClick() && !event.isRightClick()) {
                    yield false;
                }
                adjustProtectCost(player, event.isLeftClick(), event.isShiftClick());
                yield true;
            }
            case 40 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.ROOT);
                yield false;
            }
            default -> false;
        };
    }

    private boolean handleTownySettingsClick(InventoryClickEvent event, Player player) {
        return switch (event.getRawSlot()) {
            case 20 -> {
                toggleBooleanSetting(player, "towny.enabled", "Towny Integration");
                yield true;
            }
            case 21 -> {
                toggleBooleanSetting(player, "towny.mayor_bypass_open", "Mayor Open Bypass");
                yield true;
            }
            case 22 -> {
                toggleBooleanSetting(player, "towny.mayor_bypass_break", "Mayor Break Bypass");
                yield true;
            }
            case 24 -> {
                toggleBooleanSetting(player, "towny.nation_leader_bypass_open", "Nation Leader Open Bypass");
                yield true;
            }
            case 25 -> {
                toggleBooleanSetting(player, "towny.nation_leader_bypass_break", "Nation Leader Break Bypass");
                yield true;
            }
            case 40 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.ROOT);
                yield false;
            }
            default -> false;
        };
    }

    private boolean handleGriefPreventionSettingsClick(InventoryClickEvent event, Player player) {
        return switch (event.getRawSlot()) {
            case 21 -> {
                toggleBooleanSetting(player, "griefprevention.enabled", "GriefPrevention Integration");
                yield true;
            }
            case 22 -> {
                toggleBooleanSetting(player, "griefprevention.container_trust_bypass_open", "Claim Container Bypass");
                yield true;
            }
            case 23 -> {
                toggleBooleanSetting(player, "griefprevention.build_trust_bypass_break", "Claim Break Bypass");
                yield true;
            }
            case 40 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.ROOT);
                yield false;
            }
            default -> false;
        };
    }

    private boolean handleLandsSettingsClick(InventoryClickEvent event, Player player) {
        return switch (event.getRawSlot()) {
            case 21 -> {
                toggleBooleanSetting(player, "lands.enabled", "Lands Integration");
                yield true;
            }
            case 22 -> {
                toggleBooleanSetting(player, "lands.interact_container_bypass_open", "Land Container Bypass");
                yield true;
            }
            case 23 -> {
                toggleBooleanSetting(player, "lands.block_break_bypass_break", "Land Break Bypass");
                yield true;
            }
            case 40 -> {
                openAdminSettingsGui(player, AdminSettingsMenu.ROOT);
                yield false;
            }
            default -> false;
        };
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("simplelock")) {
            return false;
        }

        if (args.length == 0
                || args[0].equalsIgnoreCase("whitelist")
                || args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sendCommandMessage(sender, lang("messages.player_only", "&cPlayer only."));
                return true;
            }
            handleWhitelistCommand(player, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("transfer")) {
            if (!(sender instanceof Player player)) {
                sendCommandMessage(sender, lang("messages.player_only", "&cPlayer only."));
                return true;
            }
            handleTransferCommand(player, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            if (!(sender instanceof Player player)) {
                sendCommandMessage(sender, lang("messages.player_only", "&cPlayer only."));
                return true;
            }
            handleInfoCommand(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("settings")) {
            if (!(sender instanceof Player player)) {
                sendCommandMessage(sender, lang("messages.player_only", "&cPlayer only."));
                return true;
            }

            if (hasSettingsAccess(player)) {
                openAdminSettingsGui(player);
                return true;
            }

            sendCommandMessage(sender, lang("messages.no_permission", "&cYou do not have permission."));
            return true;
        }

        if (args[0].equalsIgnoreCase("forceunprotect")) {
            if (!(sender instanceof Player player)) {
                sendCommandMessage(sender, lang("messages.player_only", "&cPlayer only."));
                return true;
            }

            handleForceUnprotectCommand(player, args);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("simplelock.reload")) {
                sendCommandMessage(sender, lang("messages.no_permission", "&cYou do not have permission."));
                return true;
            }

            reloadConfig();
            reloadLangConfig();
            refreshConfigVersionState();
            refreshExternalIntegrations();
            refreshUpdateChecker();
            sendCommandMessage(sender, lang("messages.config_reloaded", "&aSimpleLock config reloaded."));
            if (configOutdated && sender instanceof Player player) {
                sendOutdatedConfigMessage(player);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("updateconfig")) {
            if (!sender.hasPermission("simplelock.reload")) {
                sendCommandMessage(sender, lang("messages.no_permission", "&cYou do not have permission."));
                return true;
            }

            updateConfigFile(sender);
            return true;
        }

        return true;
    }

    private void handleWhitelistCommand(Player player, String[] args) {
        boolean openGui = args.length == 0
                || args[0].equalsIgnoreCase("gui")
                || (args[0].equalsIgnoreCase("whitelist") && args.length == 1);

        if (openGui) {
            if (!getConfig().getBoolean("gui.enabled", true)) {
                return;
            }

            Container container = requireOwnedTargetProtectedContainer(player);
            if (container == null) {
                return;
            }

            openWhitelistGui(player, container, 0);
            return;
        }

        if (args.length < 3) {
            sendWhitelistUsage(player);
            return;
        }

        boolean add = args[1].equalsIgnoreCase("add");
        boolean remove = args[1].equalsIgnoreCase("remove");
        if (!add && !remove) {
            sendWhitelistUsage(player);
            return;
        }

        Container container = requireOwnedTargetProtectedContainer(player);
        if (container == null) {
            return;
        }

        OfflinePlayer target = resolveKnownPlayer(args[2]);
        if (target == null) {
            sendConfiguredMessage(player, format(
                    lang("messages.player_not_found", "&cPlayer &e%player% &cwas not found."),
                    "player",
                    args[2]
            ));
            return;
        }

        String targetName = resolvedPlayerName(target, args[2]);
        TrustChangeResult result = setTrustedPlayer(container, target.getUniqueId(), add);

        switch (result) {
            case ADDED -> {
                sendConfiguredMessage(player, format(lang("messages.gui_player_added", "&aAdded &e%player% &ato the whitelist."), "player", targetName));
                logDebug("trust_changes", player.getName() + " trusted " + targetName + " at " + formatLoc(container.getBlock()));
            }
            case REMOVED -> {
                sendConfiguredMessage(player, format(lang("messages.gui_player_removed", "&cRemoved &e%player% &cfrom the whitelist."), "player", targetName));
                logDebug("trust_changes", player.getName() + " untrusted " + targetName + " at " + formatLoc(container.getBlock()));
            }
            case ALREADY_TRUSTED -> sendConfiguredMessage(player, format(
                    lang("messages.whitelist_already_added", "&e%player% &cis already on the whitelist."),
                    "player",
                    targetName
            ));
            case NOT_TRUSTED -> sendConfiguredMessage(player, format(
                    lang("messages.whitelist_not_added", "&e%player% &cis not on the whitelist."),
                    "player",
                    targetName
            ));
            case OWNER_TARGET -> sendConfiguredMessage(player, lang(
                    "messages.whitelist_owner_target",
                    "&cYou cannot modify whitelist access for the owner."
            ));
            default -> sendConfiguredMessage(player, lang("messages.gui_no_target", "&cLook at a protected chest or barrel first."));
        }
    }

    private void handleForceUnprotectCommand(Player player, String[] args) {
        if (!player.hasPermission("simplelock.admin")) {
            sendConfiguredMessage(player, lang("messages.no_permission", "&cYou do not have permission."));
            return;
        }

        if (args.length > 1) {
            sendConfiguredMessage(player, lang(
                    "messages.force_unprotect_usage",
                    "&eUse &6/simplelock forceunprotect &ewhile looking at a protected container."
            ));
            return;
        }

        Container container = getTargetProtectableContainer(player);
        if (container == null) {
            sendConfiguredMessage(player, lang(
                    "messages.force_unprotect_no_target",
                    "&cLook at a chest or barrel first."
            ));
            return;
        }

        ProtectionData data = getData(container);
        if (data == null) {
            sendConfiguredMessage(player, lang(
                    "messages.force_unprotect_not_protected",
                    "&eThis container is not protected."
            ));
            return;
        }

        String ownerName = resolveProtectionOwnerName(data);
        int signCount = removeProtection(container);
        sendConfiguredMessage(player, format(
                format(
                        lang("messages.force_unprotect_success", "&aRemoved protection owned by &e%owner%&a. Cleared &e%count% &aprotection sign(s)."),
                        "owner",
                        ownerName
                ),
                "count",
                String.valueOf(signCount)
        ));
        logDebug("protection_cleanup", player.getName() + " force-unprotected " + ownerName + "'s container at " + formatLoc(container.getBlock()));
    }

    private void handleTransferCommand(Player player, String[] args) {
        if (args.length < 2) {
            sendConfiguredMessage(player, lang(
                    "messages.transfer_usage",
                    "&eUse &6/simplelock transfer <player> &ewhile looking at a protected container."
            ));
            return;
        }

        Container container = requireOwnedTargetProtectedContainer(player);
        if (container == null) {
            return;
        }

        ProtectionData data = getData(container);
        if (data == null) {
            sendConfiguredMessage(player, lang("messages.gui_no_target", "&cLook at a protected chest or barrel first."));
            return;
        }

        OfflinePlayer target = resolveKnownPlayer(args[1]);
        if (target == null) {
            sendConfiguredMessage(player, format(
                    lang("messages.player_not_found", "&cPlayer &e%player% &cwas not found."),
                    "player",
                    args[1]
            ));
            return;
        }

        if (data.uuid().equals(target.getUniqueId().toString())) {
            sendConfiguredMessage(player, format(
                    lang("messages.transfer_same_owner", "&e%player% &calready owns this protected container."),
                    "player",
                    resolvedPlayerName(target, args[1])
            ));
            return;
        }

        String targetName = resolvedPlayerName(target, args[1]);
        transferOwnership(container, target.getUniqueId().toString(), targetName);
        sendConfiguredMessage(player, format(
                lang("messages.transfer_success", "&aTransferred protection to &e%player%&a."),
                "player",
                targetName
        ));
        logDebug("ownership_transfers", player.getName() + " transferred protection to " + targetName + " at " + formatLoc(container.getBlock()));
    }

    private void handleInfoCommand(Player player) {
        Container container = getTargetProtectableContainer(player);
        if (container == null) {
            sendConfiguredMessage(player, lang(
                    "messages.info_no_target",
                    "&cLook at a chest or barrel first."
            ));
            return;
        }

        ProtectionData data = getData(container);
        if (data == null) {
            sendConfiguredMessage(player, lang(
                    "messages.info_not_protected",
                    "&eThis container is not protected."
            ));
            return;
        }

        sendProtectionInfo(player, container, data);
    }

    private void sendWhitelistUsage(Player player) {
        sendConfiguredMessage(player, lang(
                "messages.whitelist_usage",
                "&eUse &6/simplelock whitelist&e, &6/simplelock whitelist add <player>&e, or &6/simplelock whitelist remove <player>&e."
        ));
    }

    private Container requireOwnedTargetProtectedContainer(Player player) {
        Container container = getTargetProtectedContainer(player);
        if (container == null) {
            sendConfiguredMessage(player, lang("messages.gui_no_target", "&cLook at a protected chest or barrel first."));
            return null;
        }

        if (isNotWhitelistOwner(player, container)) {
            sendConfiguredMessage(player, lang("messages.gui_not_owner", "&cYou must own this protected container to manage its whitelist."));
            return null;
        }

        return container;
    }

    private void openWhitelistGui(Player player, Container container, int requestedPage) {
        int totalPages = getWhitelistTotalPages(getWhitelistCandidates(player, container).size());
        int page = Math.clamp(requestedPage, 0, totalPages - 1);
        String title = lang("gui.whitelist_title", "&8Whitelist Players");
        Inventory inventory = Bukkit.createInventory(
                new TrustGuiHolder(container.getLocation(), page),
                54,
                color(title + " &7(" + (page + 1) + "/" + totalPages + ")")
        );

        refreshWhitelistGui(inventory, player, container);
        player.openInventory(inventory);
    }

    private void openAdminSettingsGui(Player player) {
        openAdminSettingsGui(player, AdminSettingsMenu.ROOT);
    }

    private void openAdminSettingsGui(Player player, AdminSettingsMenu menu) {
        Inventory inventory = Bukkit.createInventory(
                new AdminSettingsGuiHolder(menu),
                ADMIN_SETTINGS_GUI_SIZE,
                color(getAdminSettingsTitle(menu))
        );

        refreshAdminSettingsGui(inventory, menu);
        player.openInventory(inventory);
    }

    private void refreshWhitelistGui(Inventory inventory, Player viewer, Container container) {
        if (!(inventory.getHolder() instanceof TrustGuiHolder trustGuiHolder)) {
            return;
        }

        ItemStack filler = new ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = requireItemMeta(filler);
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        Set<UUID> trusted = getTrustedPlayers(container);
        List<WhitelistCandidate> candidates = getWhitelistCandidates(viewer, container);
        int totalPages = getWhitelistTotalPages(candidates.size());
        int page = Math.clamp(trustGuiHolder.page(), 0, totalPages - 1);
        int startIndex = page * WHITELIST_PLAYER_SLOTS.length;

        for (int slotIndex = 0; slotIndex < WHITELIST_PLAYER_SLOTS.length; slotIndex++) {
            int candidateIndex = startIndex + slotIndex;
            if (candidateIndex >= candidates.size()) {
                break;
            }

            WhitelistCandidate candidate = candidates.get(candidateIndex);
            OfflinePlayer target = candidate.player();
            boolean isTrusted = candidate.trusted();

            ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            ItemMeta rawMeta = requireItemMeta(head);
            if (!(rawMeta instanceof org.bukkit.inventory.meta.SkullMeta meta)) {
                continue;
            }

            meta.setOwningPlayer(target);
            meta.setDisplayName((isTrusted ? ChatColor.GREEN : ChatColor.RED) + displayName(target));
            meta.setLore(List.of(
                    (isTrusted ? ChatColor.GREEN : ChatColor.RED) + "Whitelist Status: " + (isTrusted ? "Trusted" : "Not Trusted"),
                    (candidate.online() ? ChatColor.GREEN : ChatColor.GRAY) + "Player Status: " + (candidate.online() ? "Online" : "Offline"),
                    ChatColor.GRAY + "Click to " + (isTrusted ? "remove from" : "add to") + " whitelist",
                    ChatColor.DARK_GRAY + "UUID: " + target.getUniqueId()
            ));

            head.setItemMeta(meta);
            inventory.setItem(WHITELIST_PLAYER_SLOTS[slotIndex], head);
        }

        inventory.setItem(WHITELIST_PREVIOUS_PAGE_SLOT, createWhitelistPageItem(
                "Previous Page",
                page > 0,
                page,
                totalPages
        ));
        inventory.setItem(WHITELIST_INFO_SLOT, createWhitelistInfoItem(
                page,
                totalPages,
                countOnlineCandidates(candidates),
                countOfflineTrustedCandidates(candidates),
                trusted.size()
        ));
        inventory.setItem(WHITELIST_NEXT_PAGE_SLOT, createWhitelistPageItem(
                "Next Page",
                page + 1 < totalPages,
                page,
                totalPages
        ));
    }

    private ItemStack createWhitelistPageItem(String title, boolean enabled, int page, int totalPages) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName((enabled ? ChatColor.YELLOW : ChatColor.DARK_GRAY) + title);
        meta.setLore(List.of(
                ChatColor.GRAY + "Page " + (page + 1) + " of " + totalPages,
                (enabled ? ChatColor.GRAY : ChatColor.DARK_GRAY)
                        + (enabled ? "Click to change pages." : "No more pages in this direction.")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createWhitelistInfoItem(int page, int totalPages, int onlineCandidates, int offlineTrusted, int trustedCount) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName(ChatColor.GOLD + "SimpleLock Whitelist");
        meta.setLore(List.of(
                ChatColor.GRAY + "Page " + (page + 1) + " of " + totalPages,
                ChatColor.GRAY + "Online players listed: " + onlineCandidates,
                ChatColor.GRAY + "Offline trusted listed: " + offlineTrusted,
                ChatColor.GREEN + "Trusted players: " + trustedCount,
                ChatColor.YELLOW + "Trusted offline players can be removed directly here."
        ));
        item.setItemMeta(meta);
        return item;
    }

    private List<WhitelistCandidate> getWhitelistCandidates(Player viewer, Container container) {
        List<WhitelistCandidate> candidates = new ArrayList<>();
        Set<UUID> trusted = getTrustedPlayers(container);
        Set<UUID> seen = new HashSet<>();
        UUID viewerId = viewer.getUniqueId();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(viewerId)) {
                candidates.add(new WhitelistCandidate(
                        online,
                        trusted.contains(online.getUniqueId()),
                        true
                ));
                seen.add(online.getUniqueId());
            }
        }

        for (UUID trustedUuid : trusted) {
            if (trustedUuid.equals(viewerId) || !seen.add(trustedUuid)) {
                continue;
            }

            candidates.add(new WhitelistCandidate(
                    Bukkit.getOfflinePlayer(trustedUuid),
                    true,
                    false
            ));
        }

        candidates.sort(Comparator
                .comparingInt((WhitelistCandidate candidate) -> candidate.trusted() ? 0 : 1)
                .thenComparingInt(candidate -> candidate.online() ? 0 : 1)
                .thenComparing(candidate -> displayName(candidate.player()), String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    private int getWhitelistTotalPages(int candidateCount) {
        return Math.max(1, (candidateCount + WHITELIST_PLAYER_SLOTS.length - 1) / WHITELIST_PLAYER_SLOTS.length);
    }

    private int countOnlineCandidates(List<WhitelistCandidate> candidates) {
        return (int) candidates.stream().filter(WhitelistCandidate::online).count();
    }

    private int countOfflineTrustedCandidates(List<WhitelistCandidate> candidates) {
        return (int) candidates.stream().filter(candidate -> candidate.trusted() && !candidate.online()).count();
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

    private String displayName(OfflinePlayer target) {
        return resolvedPlayerName(target, target.getUniqueId().toString().substring(0, 8));
    }

    private String resolveProtectionOwnerName(ProtectionData data) {
        if (data.name() != null && !data.name().isBlank()) {
            return data.name();
        }

        try {
            return displayName(Bukkit.getOfflinePlayer(UUID.fromString(data.uuid())));
        } catch (IllegalArgumentException exception) {
            return data.uuid();
        }
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

    private void refreshAdminSettingsGui(Inventory inventory, AdminSettingsMenu menu) {
        if (!(inventory.getHolder() instanceof AdminSettingsGuiHolder(var holderMenu))) {
            return;
        }

        if (holderMenu != menu) {
            return;
        }

        fillAdminSettingsInventory(inventory);
        inventory.setItem(4, createAdminInfoItem(menu));

        switch (menu) {
            case ROOT -> populateAdminSettingsRoot(inventory);
            case PROTECTION -> populateProtectionSettingsMenu(inventory);
            case ECONOMY -> populateEconomySettingsMenu(inventory);
            case TOWNY -> populateTownySettingsMenu(inventory);
            case GRIEFPREVENTION -> populateGriefPreventionSettingsMenu(inventory);
            case LANDS -> populateLandsSettingsMenu(inventory);
        }
    }

    private void fillAdminSettingsInventory(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = requireItemMeta(filler);
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private void populateAdminSettingsRoot(Inventory inventory) {
        setToggleItem(
                inventory,
                10,
                Material.CHEST,
                "Whitelist GUI",
                getConfig().getBoolean("gui.enabled", true),
                "Allows /simplelock whitelist for protected container owners."
        );
        inventory.setItem(11, createDisplayTypeItem());
        setToggleItem(
                inventory,
                12,
                Material.COMPASS,
                "Update Checker",
                getConfig().getBoolean("update_checker.enabled", true),
                "Checks Modrinth for newer plugin versions."
        );
        setToggleItem(
                inventory,
                13,
                Material.BELL,
                "Admin Update Notices",
                getConfig().getBoolean("update_checker.announce_to_admins", true),
                "Shows update notices to admins when they join."
        );

        setSubmenuItem(
                inventory,
                20,
                Material.HOPPER,
                "Protection Settings",
                formatStatusLine("Hopper", getConfig().getBoolean("protection.hopper_protection.enabled", true)),
                formatStatusLine("Explosion", getConfig().getBoolean("protection.explosion_protection.enabled", false)),
                ChatColor.GRAY + "Open the protection submenu."
        );
        setSubmenuItem(
                inventory,
                21,
                Material.EMERALD,
                "Economy Settings",
                formatStatusLine("Integration", getConfig().getBoolean("economy.enabled", false)),
                formatStatusLine("Refunds", getConfig().getBoolean("economy.refund_on_unprotect", false)),
                ChatColor.GRAY + "Open the economy submenu."
        );
        setSubmenuItem(
                inventory,
                23,
                Material.MAP,
                "Towny Settings",
                formatStatusLine("Integration", getConfig().getBoolean("towny.enabled", false)),
                ChatColor.GRAY + "Open the Towny submenu.",
                ChatColor.DARK_GRAY + "Mayor and nation leader bypasses."
        );
        setSubmenuItem(
                inventory,
                24,
                Material.GOLDEN_SHOVEL,
                "GriefPrevention Settings",
                formatStatusLine("Integration", getConfig().getBoolean("griefprevention.enabled", false)),
                ChatColor.GRAY + "Open the GriefPrevention submenu.",
                ChatColor.DARK_GRAY + "Claim inventory and build bypasses."
        );
        setSubmenuItem(
                inventory,
                25,
                Material.GRASS_BLOCK,
                "Lands Settings",
                formatStatusLine("Integration", getConfig().getBoolean("lands.enabled", false)),
                ChatColor.GRAY + "Open the Lands submenu.",
                ChatColor.DARK_GRAY + "Container and break bypasses."
        );
    }

    private void populateProtectionSettingsMenu(Inventory inventory) {
        setToggleItem(
                inventory,
                20,
                Material.HOPPER,
                "Hopper Protection",
                getConfig().getBoolean("protection.hopper_protection.enabled", true),
                "Blocks hopper transfers into or out of protected containers."
        );
        setToggleItem(
                inventory,
                24,
                Material.TNT,
                "Explosion Protection",
                getConfig().getBoolean("protection.explosion_protection.enabled", false),
                "Prevents explosions from destroying protected containers and signs."
        );
        inventory.setItem(40, createBackItem());
    }

    private void populateEconomySettingsMenu(Inventory inventory) {
        setToggleItem(
                inventory,
                20,
                Material.EMERALD,
                "Economy Integration",
                getConfig().getBoolean("economy.enabled", false),
                "Requires Vault and an economy provider for protect costs."
        );
        setToggleItem(
                inventory,
                22,
                Material.GOLD_INGOT,
                "Refund On Unprotect",
                getConfig().getBoolean("economy.refund_on_unprotect", false),
                "Refunds the configured percentage of protect cost when the owner removes a protection sign."
        );
        inventory.setItem(24, createProtectCostItem());
        inventory.setItem(40, createBackItem());
    }

    private void populateTownySettingsMenu(Inventory inventory) {
        setToggleItem(
                inventory,
                20,
                Material.MAP,
                "Towny Integration",
                getConfig().getBoolean("towny.enabled", false),
                "Turns Towny bypass integration on or off."
        );
        setToggleItem(
                inventory,
                21,
                Material.IRON_DOOR,
                "Mayor Open Bypass",
                getConfig().getBoolean("towny.mayor_bypass_open", false),
                "Allows mayors to open protected containers in their town."
        );
        setToggleItem(
                inventory,
                22,
                Material.IRON_PICKAXE,
                "Mayor Break Bypass",
                getConfig().getBoolean("towny.mayor_bypass_break", false),
                "Allows mayors to break protected containers in their town."
        );
        setToggleItem(
                inventory,
                24,
                Material.DIAMOND,
                "Nation Leader Open Bypass",
                getConfig().getBoolean("towny.nation_leader_bypass_open", false),
                "Allows nation leaders to open protected containers."
        );
        setToggleItem(
                inventory,
                25,
                Material.DIAMOND_PICKAXE,
                "Nation Leader Break Bypass",
                getConfig().getBoolean("towny.nation_leader_bypass_break", false),
                "Allows nation leaders to break protected containers."
        );
        inventory.setItem(40, createBackItem());
    }

    private void populateGriefPreventionSettingsMenu(Inventory inventory) {
        setToggleItem(
                inventory,
                21,
                Material.GOLDEN_SHOVEL,
                "GriefPrevention Integration",
                getConfig().getBoolean("griefprevention.enabled", false),
                "Turns GriefPrevention claim bypass integration on or off."
        );
        setToggleItem(
                inventory,
                22,
                Material.CHEST_MINECART,
                "Claim Container Bypass",
                getConfig().getBoolean("griefprevention.container_trust_bypass_open", false),
                "Allows players with GP container trust to open protected containers."
        );
        setToggleItem(
                inventory,
                23,
                Material.IRON_PICKAXE,
                "Claim Break Bypass",
                getConfig().getBoolean("griefprevention.build_trust_bypass_break", false),
                "Allows players with GP build trust to break protected containers."
        );
        inventory.setItem(40, createBackItem());
    }

    private void populateLandsSettingsMenu(Inventory inventory) {
        setToggleItem(
                inventory,
                21,
                Material.GRASS_BLOCK,
                "Lands Integration",
                getConfig().getBoolean("lands.enabled", false),
                "Turns Lands area bypass integration on or off."
        );
        setToggleItem(
                inventory,
                22,
                Material.BARREL,
                "Land Container Bypass",
                getConfig().getBoolean("lands.interact_container_bypass_open", false),
                "Allows players with Lands container access to open protected containers."
        );
        setToggleItem(
                inventory,
                23,
                Material.DIAMOND_PICKAXE,
                "Land Break Bypass",
                getConfig().getBoolean("lands.block_break_bypass_break", false),
                "Allows players with Lands break access to break protected containers."
        );
        inventory.setItem(40, createBackItem());
    }

    private String getAdminSettingsTitle(AdminSettingsMenu menu) {
        if (menu == AdminSettingsMenu.ROOT) {
            return lang("gui.admin_title", "&8SimpleLock Settings");
        }

        return menu.title();
    }

    private ItemStack createAdminInfoItem(AdminSettingsMenu menu) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName(ChatColor.GOLD + menu.infoTitle());
        meta.setLore(switch (menu) {
            case ROOT -> List.of(
                    ChatColor.GRAY + "Changes save immediately.",
                    ChatColor.GRAY + "Global options stay on this page.",
                    ChatColor.GRAY + "Protection, economy, and integrations are grouped into submenus.",
                    ChatColor.YELLOW + "Left click toggles or moves forward.",
                    ChatColor.YELLOW + "Right click moves backward where supported."
            );
            case PROTECTION -> List.of(
                    ChatColor.GRAY + "Core protection rules for containers and signs.",
                    ChatColor.YELLOW + "Click a setting to toggle it.",
                    ChatColor.DARK_GRAY + "Use the arrow below to return."
            );
            case ECONOMY -> List.of(
                    ChatColor.GRAY + "Vault-based charges and refunds for protections.",
                    ChatColor.YELLOW + "Protect Cost: left/right = 1.00, shift = 10.00.",
                    ChatColor.DARK_GRAY + "Use the arrow below to return."
            );
            case TOWNY -> List.of(
                    ChatColor.GRAY + "Towny-specific bypass options for protected containers.",
                    ChatColor.YELLOW + "Click a setting to toggle it.",
                    ChatColor.DARK_GRAY + "Use the arrow below to return."
            );
            case GRIEFPREVENTION -> List.of(
                    ChatColor.GRAY + "GriefPrevention claim trust bypass options.",
                    ChatColor.YELLOW + "Click a setting to toggle it.",
                    ChatColor.DARK_GRAY + "Use the arrow below to return."
            );
            case LANDS -> List.of(
                    ChatColor.GRAY + "Lands area-role bypass options.",
                    ChatColor.YELLOW + "Click a setting to toggle it.",
                    ChatColor.DARK_GRAY + "Use the arrow below to return."
            );
        });
        item.setItemMeta(meta);
        return item;
    }

    private void setToggleItem(Inventory inventory, int slot, Material material, String title, boolean enabled, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.RED) + title);
        meta.setLore(List.of(
                (enabled ? ChatColor.GREEN : ChatColor.RED) + "Current: " + (enabled ? "Enabled" : "Disabled"),
                ChatColor.GRAY + description,
                ChatColor.YELLOW + "Click to toggle."
        ));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private void setSubmenuItem(Inventory inventory, int slot, Material material, String title, String... lines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = requireItemMeta(item);
        List<String> lore = new ArrayList<>(List.of(lines));

        meta.setDisplayName(ChatColor.GOLD + title);
        lore.add(ChatColor.YELLOW + "Click to open.");

        meta.setLore(lore);
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private ItemStack createBackItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName(ChatColor.YELLOW + "Back");
        meta.setLore(List.of(
                ChatColor.GRAY + "Return to the main settings menu."
        ));
        item.setItemMeta(meta);
        return item;
    }

    private String formatStatusLine(String label, boolean enabled) {
        return ChatColor.GRAY + label + ": " + (enabled ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled");
    }

    private ItemStack createDisplayTypeItem() {
        String current = getConfig().getString("messages.display_type", "chat").toLowerCase(Locale.ROOT);

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName(ChatColor.AQUA + "Message Display Type");
        meta.setLore(List.of(
                ChatColor.GREEN + "Current: " + prettifyDisplayType(current),
                ChatColor.GRAY + "Controls how plugin messages are shown.",
                ChatColor.YELLOW + "Left click: next mode",
                ChatColor.YELLOW + "Right click: previous mode"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createProtectCostItem() {
        double current = getConfig().getDouble("economy.protect_cost", 0.0);

        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = requireItemMeta(item);
        meta.setDisplayName(ChatColor.GOLD + "Protect Cost");
        meta.setLore(List.of(
                ChatColor.GREEN + "Current: $" + formatAmount(current),
                ChatColor.GRAY + "Cost charged when a container is protected.",
                ChatColor.YELLOW + "Left click: +1.00",
                ChatColor.YELLOW + "Right click: -1.00",
                ChatColor.YELLOW + "Shift + click: +/-10.00"
        ));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("simplelock")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("whitelist");
            completions.add("transfer");
            completions.add("info");
            if (sender.hasPermission("simplelock.admin")) {
                completions.add("forceunprotect");
            }
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

    private List<Container> getAdjacentProtectableContainers(Block block) {
        List<Container> containers = new ArrayList<>();

        for (BlockFace face : CARDINAL_FACES) {
            Container container = getProtectableContainer(block.getRelative(face));
            if (container != null) {
                containers.add(container);
            }
        }

        Container above = getProtectableContainer(block.getRelative(BlockFace.UP));
        if (above != null) {
            containers.add(above);
        }

        Container below = getProtectableContainer(block.getRelative(BlockFace.DOWN));
        if (below != null) {
            containers.add(below);
        }

        return containers;
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

        return setTrustedPlayer(container, uuid, !data.trustedPlayers().contains(uuid));
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

    private int removeProtection(Container container) {
        List<Sign> signs = getProtectionSigns(container);
        unprotectContainer(container);
        for (Sign sign : signs) {
            clearProtection(sign);
        }
        return signs.size();
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
        sign.setWaxed(true);
        sign.getSide(Side.FRONT).setLine(0, ChatColor.DARK_BLUE + "[Protected]");
        sign.getSide(Side.FRONT).setLine(1, ChatColor.BLACK + name);
        sign.update();
    }

    private void protectSign(Sign sign, String uuid, String name) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(ownerUuidKey, PersistentDataType.STRING, uuid);
        pdc.set(ownerNameKey, PersistentDataType.STRING, name);
        pdc.set(protectSignKey, PersistentDataType.BYTE, (byte) 1);
        sign.setWaxed(true);
        sign.update();
    }

    private void clearProtection(Sign sign) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.remove(ownerUuidKey);
        pdc.remove(ownerNameKey);
        pdc.remove(protectSignKey);
        sign.setWaxed(false);
        clearProtectionText(sign);
        sign.update();
    }

    private void clearProtectionText(Sign sign) {
        for (Side side : Side.values()) {
            for (int line = 0; line < 4; line++) {
                sign.getSide(side).setLine(line, "");
            }
        }
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
                    if (blocks.isEmpty() || !isSameBlock(blocks.getFirst(), rightBlock)) {
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

        return open
                ? hasOpenBypass(player, container)
                : hasBreakBypass(player, container);
    }

    private boolean hasOpenBypass(Player player, Container container) {
        if (townyHook != null
                && getConfig().getBoolean("towny.mayor_bypass_open", false)
                && townyHook.isMayor(player, container.getLocation())) {
            logDebug("bypass_use", player.getName() + " used mayor open bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (townyHook != null
                && getConfig().getBoolean("towny.nation_leader_bypass_open", false)
                && townyHook.isNationLeader(player, container.getLocation())) {
            logDebug("bypass_use", player.getName() + " used nation leader open bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (griefPreventionHook != null
                && getConfig().getBoolean("griefprevention.container_trust_bypass_open", false)
                && griefPreventionHook.canOpen(player, container.getLocation())) {
            logDebug("bypass_use", player.getName() + " used GriefPrevention container bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (landsHook != null
                && getConfig().getBoolean("lands.interact_container_bypass_open", false)
                && landsHook.canOpen(player, container.getLocation(), container.getBlock().getType())) {
            logDebug("bypass_use", player.getName() + " used Lands container bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        return false;
    }

    private boolean hasBreakBypass(Player player, Container container) {
        if (townyHook != null
                && getConfig().getBoolean("towny.mayor_bypass_break", false)
                && townyHook.isMayor(player, container.getLocation())) {
            logDebug("bypass_use", player.getName() + " used mayor break bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (townyHook != null
                && getConfig().getBoolean("towny.nation_leader_bypass_break", false)
                && townyHook.isNationLeader(player, container.getLocation())) {
            logDebug("bypass_use", player.getName() + " used nation leader break bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (griefPreventionHook != null
                && getConfig().getBoolean("griefprevention.build_trust_bypass_break", false)
                && griefPreventionHook.canBreak(player, container.getLocation())) {
            logDebug("bypass_use", player.getName() + " used GriefPrevention break bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        if (landsHook != null
                && getConfig().getBoolean("lands.block_break_bypass_break", false)
                && landsHook.canBreak(player, container.getLocation(), container.getBlock().getType())) {
            logDebug("bypass_use", player.getName() + " used Lands break bypass at " + formatLoc(container.getBlock()));
            return true;
        }

        return false;
    }

    private void sendProtectionInfo(Player player, Container container, ProtectionData data) {
        sendChatMessage(player, lang(
                "messages.info_header",
                "&6SimpleLock Protection Info"
        ));
        sendChatMessage(player, format(
                lang("messages.info_type", "&eType: &f%type%"),
                "type",
                describeContainerType(container)
        ));
        sendChatMessage(player, format(
                lang("messages.info_owner", "&eOwner: &f%owner%"),
                "owner",
                resolveProtectionOwnerName(data)
        ));

        String trustedMessage = lang(
                "messages.info_trusted",
                "&eTrusted (%count%): &f%players%"
        );
        trustedMessage = format(trustedMessage, "count", String.valueOf(data.trustedPlayers().size()));
        trustedMessage = format(trustedMessage, "players", summarizeTrustedPlayers(data.trustedPlayers()));
        sendChatMessage(player, trustedMessage);

        sendChatMessage(player, format(
                lang("messages.info_signs", "&eProtection signs: &f%count%"),
                "count",
                String.valueOf(getProtectionSigns(container).size())
        ));

        String claimContexts = summarizeClaimContexts(container);
        if (claimContexts != null) {
            sendChatMessage(player, format(
                    lang("messages.info_integrations", "&eClaim context: &f%integrations%"),
                    "integrations",
                    claimContexts
            ));
        }
    }

    private String describeContainerType(Container container) {
        if (container instanceof Chest chest) {
            return chest.getInventory().getHolder() instanceof DoubleChest ? "Double Chest" : "Chest";
        }

        if (container instanceof Barrel) {
            return "Barrel";
        }

        return container.getBlock().getType().name().toLowerCase(Locale.ROOT);
    }

    private String summarizeTrustedPlayers(Set<UUID> trustedPlayers) {
        if (trustedPlayers.isEmpty()) {
            return "none";
        }

        List<String> names = trustedPlayers.stream()
                .map(uuid -> displayName(Bukkit.getOfflinePlayer(uuid)))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        if (names.size() <= INFO_TRUSTED_PLAYER_SUMMARY_LIMIT) {
            return String.join(", ", names);
        }

        return String.join(", ", names.subList(0, INFO_TRUSTED_PLAYER_SUMMARY_LIMIT))
                + " +" + (names.size() - INFO_TRUSTED_PLAYER_SUMMARY_LIMIT) + " more";
    }

    private String summarizeClaimContexts(Container container) {
        List<String> contexts = new ArrayList<>();

        if (griefPreventionHook != null) {
            String claim = griefPreventionHook.describeClaim(container.getLocation());
            if (claim != null) {
                contexts.add("GriefPrevention: " + claim);
            }
        }

        if (landsHook != null) {
            String area = landsHook.describeArea(container.getLocation());
            if (area != null) {
                contexts.add("Lands: " + area);
            }
        }

        return contexts.isEmpty() ? null : String.join(", ", contexts);
    }

    private String describeInventory(Inventory inventory) {
        if (inventory == null) {
            return "unknown inventory";
        }

        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Container container && inventoryIsProtectedContainer(inventory)) {
            return "protected " + describeContainerType(container).toLowerCase(Locale.ROOT) + " at " + formatLoc(container.getBlock());
        }

        if (holder instanceof DoubleChest dc
                && inventoryIsProtectedContainer(inventory)
                && dc.getLeftSide() instanceof Chest left) {
            return "protected double chest at " + formatLoc(left.getBlock());
        }

        if (holder instanceof HopperMinecart) {
            return "hopper minecart";
        }

        return holder == null ? inventory.getType().name().toLowerCase(Locale.ROOT) : holder.getClass().getSimpleName();
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
        String colored = color(message);

        switch (type) {
            case "none" -> {
            }
            case "action_bar" -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(colored));
            case "bossbar" -> {
                BossBar bossBar = Bukkit.createBossBar(colored, BarColor.RED, BarStyle.SOLID);
                bossBar.setProgress(1.0D);
                bossBar.addPlayer(player);
                Bukkit.getScheduler().runTaskLater(this, bossBar::removeAll, 60L);
            }
            default -> player.sendMessage(colored);
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

        String gameVersion = getServer().getBukkitVersion().split("-")[0].trim();

        CompletableFuture.runAsync(() -> {
            try {
                String currentVersion = PLUGIN_VERSION;
                String latestVersion = new ModrinthUpdateChecker().fetchLatestVersion(
                        MODRINTH_PROJECT_ID,
                        MODRINTH_LOADERS,
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

        griefPreventionHook = null;
        if (getConfig().getBoolean("griefprevention.enabled", false)
                && Bukkit.getPluginManager().getPlugin("GriefPrevention") != null) {
            GriefPreventionHook candidate = new GriefPreventionHook();
            if (candidate.isReady()) {
                griefPreventionHook = candidate;
                log("GriefPrevention integration enabled.");
            } else {
                log("GriefPrevention was found, but its API is not ready yet.");
            }
        } else if (!getConfig().getBoolean("griefprevention.enabled", false)) {
            log("GriefPrevention integration disabled in config.");
        } else {
            log("GriefPrevention integration not enabled because GriefPrevention is not installed.");
        }

        landsHook = null;
        if (getConfig().getBoolean("lands.enabled", false)
                && Bukkit.getPluginManager().getPlugin("Lands") != null) {
            LandsHook candidate = new LandsHook(this);
            if (candidate.isReady()) {
                landsHook = candidate;
                log("Lands integration enabled.");
            } else {
                log("Lands was found, but its API is not ready yet.");
            }
        } else if (!getConfig().getBoolean("lands.enabled", false)) {
            log("Lands integration disabled in config.");
        } else {
            log("Lands integration not enabled because Lands is not installed.");
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
        YamlConfiguration bundledConfig = loadBundledConfig();
        loadedConfigVersion = getConfig().getDouble("config_version", 0.0);
        missingConfigPaths = findMissingConfigPaths(bundledConfig, getConfig());
        obsoleteConfigPaths = findObsoleteConfigPaths(bundledConfig, getConfig());
        boolean versionOutdated = loadedConfigVersion < CONFIG_VERSION;
        configOutdated = versionOutdated || !missingConfigPaths.isEmpty() || !obsoleteConfigPaths.isEmpty();

        if (configOutdated) {
            if (versionOutdated) {
                log("Config file is outdated. Expected config_version "
                        + formatConfigVersion(CONFIG_VERSION) + " but found " + formatConfigVersion(loadedConfigVersion)
                        + ". Review the latest config.yml and update your settings.");
            }
            if (!missingConfigPaths.isEmpty()) {
                log("Missing config paths (" + missingConfigPaths.size() + "):");
                missingConfigPaths.forEach(path -> log(" - " + path));
            }
            if (!obsoleteConfigPaths.isEmpty()) {
                log("Obsolete config paths (" + obsoleteConfigPaths.size() + "):");
                obsoleteConfigPaths.forEach(path -> log(" - " + path));
            }
            log("Use /simplelock updateconfig to back up, merge missing default settings, and remove obsolete keys.");
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
        if (loadedConfigVersion < CONFIG_VERSION) {
            String message = lang(
                    "messages.config_outdated_admin",
                    "&eSimpleLock config is outdated. Expected config_version &6%expected%&e but found &6%current%&e."
            );
            message = format(
                    format(message, "expected", formatConfigVersion(CONFIG_VERSION)),
                    "current",
                    formatConfigVersion(loadedConfigVersion)
            );
            sendChatMessage(player, message);
        }

        if (!missingConfigPaths.isEmpty()) {
            String missingMessage = lang(
                    "messages.config_missing_paths_admin",
                    "&eMissing config paths: &6%paths%"
            );
            sendChatMessage(player, format(missingMessage, "paths", summarizeMissingConfigPaths()));
        }

        if (!obsoleteConfigPaths.isEmpty()) {
            String obsoleteMessage = lang(
                    "messages.config_obsolete_paths_admin",
                    "&eObsolete config paths: &6%paths%"
            );
            sendChatMessage(player, format(obsoleteMessage, "paths", summarizeObsoleteConfigPaths()));
        }

        sendChatMessage(player, lang(
                "messages.config_update_hint",
                "&eUse &6/simplelock updateconfig &eto back up your current config, merge missing defaults, and remove obsolete keys."
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

        if ("towny.enabled".equals(path)
                || "griefprevention.enabled".equals(path)
                || "lands.enabled".equals(path)
                || "economy.enabled".equals(path)) {
            refreshExternalIntegrations();
        } else if ("update_checker.enabled".equals(path)) {
            refreshUpdateChecker();
        }

        sendSettingUpdate(player, settingName, updated ? "Enabled" : "Disabled");
    }

    private void updateConfigFile(CommandSender sender) {
        YamlConfiguration bundledConfig = loadBundledConfig();
        List<String> missingBeforeUpdate = findMissingConfigPaths(bundledConfig, getConfig());
        List<String> obsoleteBeforeUpdate = findObsoleteConfigPaths(bundledConfig, getConfig());
        boolean versionNeedsUpdate = loadedConfigVersion < CONFIG_VERSION;

        if (missingBeforeUpdate.isEmpty() && obsoleteBeforeUpdate.isEmpty() && !versionNeedsUpdate) {
            sendCommandMessage(sender, lang(
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

            for (String path : obsoleteBeforeUpdate) {
                getConfig().set(path, null);
            }

            getConfig().set("config_version", CONFIG_VERSION);
            saveConfig();
            reloadConfig();
            reloadLangConfig();
            refreshConfigVersionState();
            refreshExternalIntegrations();
            refreshUpdateChecker();

            String success = lang(
                    "messages.config_update_success",
                    "&aConfig updated. Backup created at &e%backup%&a. Added &e%added% &amissing setting(s) and removed &e%removed% &aobsolete setting(s)."
            );
            success = format(success, "backup", backupPath.getFileName().toString());
            success = format(success, "added", String.valueOf(missingBeforeUpdate.size()));
            success = format(success, "removed", String.valueOf(obsoleteBeforeUpdate.size()));
            sendCommandMessage(sender, success);

            if (!missingBeforeUpdate.isEmpty()) {
                String merged = lang(
                        "messages.config_update_added_paths",
                        "&eMerged paths: &6%paths%"
                );
                sendCommandMessage(sender, format(merged, "paths", summarizePaths(missingBeforeUpdate, 6)));
            }

            if (!obsoleteBeforeUpdate.isEmpty()) {
                String removed = lang(
                        "messages.config_update_removed_paths",
                        "&eRemoved obsolete paths: &6%paths%"
                );
                sendCommandMessage(sender, format(removed, "paths", summarizePaths(obsoleteBeforeUpdate, 6)));
            }
        } catch (IOException exception) {
            log("Config update failed: " + exception.getMessage());
            sendCommandMessage(sender, lang(
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

    private void refundProtectionCost(Player player, String ownerUuid, Container container) {
        if (!player.getUniqueId().toString().equals(ownerUuid)
                || !getConfig().getBoolean("economy.enabled", false)
                || !getConfig().getBoolean("economy.refund_on_unprotect", false)) {
            return;
        }

        double protectCost = getConfig().getDouble("economy.protect_cost", 0.0);
        double refundPercentage = Math.clamp(getConfig().getDouble("economy.refund_percentage", 50.0), 0.0, 100.0);
        double refundAmount = Math.round(protectCost * refundPercentage) / 100.0;
        if (refundAmount <= 0.0) {
            return;
        }

        if (vaultHook == null || !vaultHook.isReady()) {
            logDebug("economy", "Economy enabled but Vault is not ready to refund protection removal.");
            return;
        }

        if (!vaultHook.deposit(player, refundAmount)) {
            logDebug("economy", "Failed to refund " + refundAmount + " to " + player.getName()
                    + " for unprotecting container at " + formatLoc(container.getBlock()));
            return;
        }

        sendConfiguredMessage(
                player,
                format(
                        lang("messages.economy_refunded", "&aYou were refunded &6$%amount% &afor removing this protection."),
                        "amount",
                        formatAmount(refundAmount)
                )
        );
        logDebug("economy", player.getName() + " was refunded " + refundAmount
                + " for unprotecting container at " + formatLoc(container.getBlock()));
    }

    private void sendSettingUpdate(Player player, String settingName, String value) {
        String message = lang("messages.setting_updated", "&aUpdated &e%setting% &ato &e%value%&a.");
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

    private void reloadLangConfig() {
        Path langPath = getDataFolder().toPath().resolve("lang.yml");
        boolean created = false;

        if (Files.notExists(langPath)) {
            saveResource("lang.yml", false);
            created = true;
        }

        langConfig = YamlConfiguration.loadConfiguration(langPath.toFile());
        if (created) {
            migrateLegacyLanguageValues(langPath);
        }
    }

    private void migrateLegacyLanguageValues(Path langPath) {
        YamlConfiguration bundledLang = loadBundledLangConfig();
        boolean migrated = false;

        for (String path : bundledLang.getKeys(true)) {
            if (path.isBlank()
                    || bundledLang.isConfigurationSection(path)
                    || !getConfig().contains(path)) {
                continue;
            }

            langConfig.set(path, getConfig().get(path));
            migrated = true;
        }

        if (!migrated) {
            return;
        }

        try {
            langConfig.save(langPath.toFile());
            langConfig = YamlConfiguration.loadConfiguration(langPath.toFile());
            log("Migrated legacy language values from config.yml to lang.yml.");
        } catch (IOException exception) {
            log("Failed to save migrated lang.yml: " + exception.getMessage());
        }
    }

    private YamlConfiguration loadBundledConfig() {
        return loadBundledYaml("config.yml");
    }

    private YamlConfiguration loadBundledLangConfig() {
        return loadBundledYaml("lang.yml");
    }

    private YamlConfiguration loadBundledYaml(String resourceName) {
        YamlConfiguration config = new YamlConfiguration();

        try (InputStream stream = getResource(resourceName)) {
            if (stream == null) {
                return config;
            }

            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException exception) {
            log("Failed to read bundled " + resourceName + ": " + exception.getMessage());
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

    private List<String> findObsoleteConfigPaths(FileConfiguration bundledConfig, FileConfiguration currentConfig) {
        List<String> currentPaths = new ArrayList<>(currentConfig.getKeys(true));
        currentPaths.removeIf(String::isBlank);
        currentPaths.sort(Comparator
                .comparingInt(this::pathDepth)
                .thenComparing(String.CASE_INSENSITIVE_ORDER));

        List<String> obsoletePaths = new ArrayList<>();
        for (String path : currentPaths) {
            if (bundledConfig.contains(path) || hasAncestorPath(obsoletePaths, path)) {
                continue;
            }
            obsoletePaths.add(path);
        }

        obsoletePaths.sort(String.CASE_INSENSITIVE_ORDER);
        return obsoletePaths;
    }

    private String summarizeMissingConfigPaths() {
        return summarizePaths(missingConfigPaths, ADMIN_CONFIG_PATH_SUMMARY_LIMIT);
    }

    private String summarizeObsoleteConfigPaths() {
        return summarizePaths(obsoleteConfigPaths, ADMIN_CONFIG_PATH_SUMMARY_LIMIT);
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

    private boolean hasAncestorPath(List<String> paths, String candidatePath) {
        for (String path : paths) {
            if (candidatePath.startsWith(path + ".")) {
                return true;
            }
        }
        return false;
    }

    private int pathDepth(String path) {
        int depth = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '.') {
                depth++;
            }
        }
        return depth;
    }

    private void sendChatMessage(Player player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        player.sendMessage(color(message));
    }

    private void sendCommandMessage(CommandSender sender, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        if (sender instanceof Player player) {
            sendChatMessage(player, message);
            return;
        }

        sender.sendMessage(color(message));
    }

    private String lang(String path, String fallback) {
        return langConfig == null ? fallback : langConfig.getString(path, fallback);
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private ItemMeta requireItemMeta(ItemStack item) {
        return Objects.requireNonNull(item.getItemMeta(), "Missing ItemMeta for " + item.getType());
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

    private record WhitelistCandidate(OfflinePlayer player, boolean trusted, boolean online) {
    }

    private record TrustGuiHolder(Location location, int page) implements InventoryHolder {
        @Override
        @SuppressWarnings("NullableProblems")
        public Inventory getInventory() {
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

    private enum AdminSettingsMenu {
        ROOT("SimpleLock Admin Settings", null),
        PROTECTION("Protection Settings", "&8Protection Settings"),
        ECONOMY("Economy Settings", "&8Economy Settings"),
        TOWNY("Towny Settings", "&8Towny Settings"),
        GRIEFPREVENTION("GriefPrevention Settings", "&8GriefPrevention Settings"),
        LANDS("Lands Settings", "&8Lands Settings");

        private final String infoTitle;
        private final String title;

        AdminSettingsMenu(String infoTitle, String title) {
            this.infoTitle = infoTitle;
            this.title = title;
        }

        private String infoTitle() {
            return infoTitle;
        }

        private String title() {
            return title;
        }
    }

    private record AdminSettingsGuiHolder(AdminSettingsMenu menu) implements InventoryHolder {
        @Override
        @SuppressWarnings("NullableProblems")
        public Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }
}
