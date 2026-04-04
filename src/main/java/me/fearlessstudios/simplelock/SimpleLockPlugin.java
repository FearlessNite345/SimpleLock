package me.fearlessstudios.simplelock;

import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SimpleLockPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final BlockFace[] CARDINAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private NamespacedKey ownerUuidKey;
    private NamespacedKey ownerNameKey;
    private NamespacedKey protectSignKey;
    private NamespacedKey trustedPlayersKey;

    private TownyHook townyHook;
    private VaultHook vaultHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ownerUuidKey = new NamespacedKey(this, "owner_uuid");
        ownerNameKey = new NamespacedKey(this, "owner_name");
        protectSignKey = new NamespacedKey(this, "protect_sign");
        trustedPlayersKey = new NamespacedKey(this, "trusted_players");

        if (getConfig().getBoolean("towny.enabled", true)
                && Bukkit.getPluginManager().getPlugin("Towny") != null) {
            townyHook = new TownyHook();
            log("Towny detected, support enabled.");
        } else {
            log("Towny not detected or disabled.");
        }

        if (getConfig().getBoolean("economy.enabled", false)
                && Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vaultHook = new VaultHook();
            if (vaultHook.isReady()) {
                log("Vault detected, economy support enabled.");
            } else {
                log("Vault detected, but no economy provider was found.");
            }
        } else {
            log("Vault not detected or economy disabled.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand simpleLockCommand = getCommand("simplelock");
        if (simpleLockCommand != null) {
            simpleLockCommand.setExecutor(this);
            simpleLockCommand.setTabCompleter(this);
        } else {
            log("Command 'simplelock' is missing from plugin.yml.");
        }

        log("SimpleLock enabled.");
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
        if (!(attached.getState() instanceof Chest chest)) {
            event.setCancelled(true);
            return;
        }

        if (isProtected(chest)) {
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
                    logDebug("economy", player.getName() + " paid " + cost + " to protect chest at " + formatLoc(chest.getBlock()));
                }
            }
        }

        String uuid = player.getUniqueId().toString();
        String name = player.getName();

        protectChest(chest, uuid, name, new HashSet<>());
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

        if (!(event.getClickedBlock().getState() instanceof Chest chest)) {
            return;
        }

        ProtectionData data = getData(chest);
        if (data == null) {
            return;
        }

        Player player = event.getPlayer();
        if (canAccess(player, chest, data, true)) {
            return;
        }

        event.setCancelled(true);
        sendConfiguredMessage(player, getConfig().getString("messages.chest_denied"));
        logDebug("access_denied", player.getName() + " denied open at " + formatLoc(chest.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignEdit(PlayerOpenSignEvent event) {
        Sign sign = event.getSign();
        if (!isProtect(sign)) {
            return;
        }

        String owner = sign.getPersistentDataContainer().get(ownerUuidKey, PersistentDataType.STRING);
        if (owner == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getUniqueId().toString().equals(owner) || player.hasPermission("simplelock.admin")) {
            return;
        }

        event.setCancelled(true);
        sendConfiguredMessage(player, getConfig().getString("messages.sign_denied"));
        logDebug("access_denied", player.getName() + " denied sign edit at " + formatLoc(sign.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        if (block.getState() instanceof Chest chest) {
            ProtectionData data = getData(chest);
            if (data != null && !canAccess(player, chest, data, false)) {
                event.setCancelled(true);
                sendConfiguredMessage(player, getConfig().getString("messages.chest_break_denied"));
                logDebug("access_denied", player.getName() + " denied chest break at " + formatLoc(block));
                return;
            }
        }

        if (block.getState() instanceof Sign sign && isProtect(sign)) {
            String owner = sign.getPersistentDataContainer().get(ownerUuidKey, PersistentDataType.STRING);
            if (owner != null
                    && !player.getUniqueId().toString().equals(owner)
                    && !player.hasPermission("simplelock.admin")) {
                event.setCancelled(true);
                sendConfiguredMessage(player, getConfig().getString("messages.sign_break_denied"));
                logDebug("access_denied", player.getName() + " denied sign break at " + formatLoc(block));
            }
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
                        && townyHook.isMayor(player, nearbyChest)) {
                    protectChest(placedChest, nearbyData.uuid(), nearbyData.name(), nearbyData.trustedPlayers());
                    logDebug("bypass_use", player.getName() + " used mayor bypass while attaching chest at " + formatLoc(block));
                    return;
                }

                if (getConfig().getBoolean("towny.nation_leader_bypass_break", false)
                        && townyHook.isNationLeader(player, nearbyChest)) {
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

        if (inventoryIsProtectedChest(event.getSource()) || inventoryIsProtectedChest(event.getDestination())) {
            event.setCancelled(true);
            logDebug("hopper_denied", "Blocked hopper interaction involving protected chest.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (getConfig().getBoolean("protection.explosion_protection.enabled", false)) {
            protectExplosionList(event.blockList());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (getConfig().getBoolean("protection.explosion_protection.enabled", false)) {
            protectExplosionList(event.blockList());
        }
    }

    private void protectExplosionList(List<Block> blocks) {
        blocks.removeIf(block -> {
            boolean remove = block.getState() instanceof Chest chest && isProtected(chest)
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

        if (!(event.getInventory().getHolder() instanceof TrustGuiHolder holder)) {
            return;
        }

        event.setCancelled(true);

        Location location = holder.location();
        if (!(location.getBlock().getState() instanceof Chest chest)) {
            return;
        }

        if (!isProtected(chest)) {
            return;
        }

        if (isNotWhitelistOwner(player, chest)) {
            sendConfiguredMessage(player, getConfig().getString("messages.gui_not_owner"));
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

        boolean added = toggleTrustedPlayer(chest, target.getUniqueId());

        if (added) {
            sendConfiguredMessage(player, format(getConfig().getString("messages.gui_player_added"), "player", targetName));
            logDebug("trust_changes", player.getName() + " trusted " + targetName + " at " + formatLoc(chest.getBlock()));
        } else {
            sendConfiguredMessage(player, format(getConfig().getString("messages.gui_player_removed"), "player", targetName));
            logDebug("trust_changes", player.getName() + " untrusted " + targetName + " at " + formatLoc(chest.getBlock()));
        }

        Bukkit.getScheduler().runTask(this, () -> openWhitelistGui(player, chest));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }

        if (!command.getName().equalsIgnoreCase("simplelock")) {
            return false;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (!getConfig().getBoolean("gui.enabled", true)) {
                return true;
            }

            Block target = player.getTargetBlockExact(6);
            if (target == null || !(target.getState() instanceof Chest chest)) {
                sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
                return true;
            }

            if (!isProtected(chest)) {
                sendConfiguredMessage(player, getConfig().getString("messages.gui_no_target"));
                return true;
            }

            if (isNotWhitelistOwner(player, chest)) {
                sendConfiguredMessage(player, getConfig().getString("messages.gui_not_owner"));
                return true;
            }

            openWhitelistGui(player, chest);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("simplelock.reload")) {
                sendConfiguredMessage(player, getConfig().getString("messages.no_permission"));
                return true;
            }

            reloadConfig();
            sendConfiguredMessage(player, getConfig().getString("messages.config_reloaded"));
        }

        return true;
    }

    private void openWhitelistGui(Player player, Chest chest) {
        String title = getConfig().getString("gui.whitelist_title", "&8Whitelist Players");
        Inventory inventory = Bukkit.createInventory(
                new TrustGuiHolder(chest.getLocation()),
                54,
                LegacyComponentSerializer.legacyAmpersand().deserialize(title)
        );

        ItemStack filler = new ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        Set<UUID> trusted = getTrustedPlayers(chest);

        int[] playerSlots = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34
        };

        int index = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (index >= playerSlots.length) {
                break;
            }

            if (online.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

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
            inventory.setItem(playerSlots[index], head);
            index++;
        }

        ItemStack info = new ItemStack(org.bukkit.Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text("SimpleLock Whitelist", NamedTextColor.GOLD));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(Component.text("Click a player head to toggle access.", NamedTextColor.GRAY));
        infoLore.add(Component.text("Green = Whitelisted", NamedTextColor.GREEN));
        infoLore.add(Component.text("Red = Not Whitelisted", NamedTextColor.RED));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(4, info);

        player.openInventory(inventory);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("simplelock")) {
            return List.of();
        }

        return args.length == 1 ? List.of("gui", "reload") : List.of();
    }

    private boolean inventoryIsProtectedChest(Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest chest) {
            return isProtected(chest);
        }

        if (holder instanceof DoubleChest dc) {
            return dc.getLeftSide() instanceof Chest left && isProtected(left)
                    || dc.getRightSide() instanceof Chest right && isProtected(right);
        }

        return false;
    }

    private boolean isProtected(Chest chest) {
        return getData(chest) != null;
    }

    private boolean isNotWhitelistOwner(Player player, Chest chest) {
        if (player.hasPermission("simplelock.admin")) {
            return false;
        }

        ProtectionData data = getData(chest);
        return data == null || !data.uuid().equals(player.getUniqueId().toString());
    }

    private boolean toggleTrustedPlayer(Chest chest, UUID uuid) {
        ProtectionData data = getData(chest);
        if (data == null) {
            return false;
        }

        Set<UUID> trusted = new HashSet<>(data.trustedPlayers());
        boolean added = trusted.add(uuid);
        if (!added) {
            trusted.remove(uuid);
        }

        applyTrustedToChest(chest, trusted);
        return added;
    }

    private Set<UUID> getTrustedPlayers(Chest chest) {
        ProtectionData data = getData(chest);
        return data == null ? new HashSet<>() : new HashSet<>(data.trustedPlayers());
    }

    private void protectChest(Chest chest, String uuid, String name, Set<UUID> trustedPlayers) {
        InventoryHolder holder = chest.getInventory().getHolder();

        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide() instanceof Chest left) {
                set(left, uuid, name, trustedPlayers);
            }
            if (dc.getRightSide() instanceof Chest right) {
                set(right, uuid, name, trustedPlayers);
            }
        } else {
            set(chest, uuid, name, trustedPlayers);
        }
    }

    private void set(Chest chest, String uuid, String name, Set<UUID> trustedPlayers) {
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        pdc.set(ownerUuidKey, PersistentDataType.STRING, uuid);
        pdc.set(ownerNameKey, PersistentDataType.STRING, name);
        pdc.set(trustedPlayersKey, PersistentDataType.STRING, serializeTrustedPlayers(trustedPlayers));
        chest.update();
    }

    private void applyTrustedToChest(Chest chest, Set<UUID> trustedPlayers) {
        ProtectionData data = getData(chest);
        if (data != null) {
            protectChest(chest, data.uuid(), data.name(), trustedPlayers);
        }
    }

    private void protectSign(Sign sign, String uuid, String name) {
        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        pdc.set(ownerUuidKey, PersistentDataType.STRING, uuid);
        pdc.set(ownerNameKey, PersistentDataType.STRING, name);
        pdc.set(protectSignKey, PersistentDataType.BYTE, (byte) 1);
        sign.update();
    }

    private boolean isProtect(Sign sign) {
        Byte b = sign.getPersistentDataContainer().get(protectSignKey, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    private ProtectionData getData(Chest chest) {
        return getDataFromAnyHalf(chest);
    }

    private ProtectionData getDataFromAnyHalf(Chest chest) {
        ProtectionData direct = readSingleChest(chest);
        if (direct != null) {
            return direct;
        }

        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide() instanceof Chest left) {
                ProtectionData leftData = readSingleChest(left);
                if (leftData != null) {
                    return leftData;
                }
            }

            if (dc.getRightSide() instanceof Chest right) {
                return readSingleChest(right);
            }
        }

        return null;
    }

    private ProtectionData readSingleChest(Chest chest) {
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        String uuid = pdc.get(ownerUuidKey, PersistentDataType.STRING);
        String name = pdc.get(ownerNameKey, PersistentDataType.STRING);
        String trustedRaw = pdc.get(trustedPlayersKey, PersistentDataType.STRING);

        return uuid == null ? null : new ProtectionData(uuid, name, deserializeTrustedPlayers(trustedRaw));
    }

    private boolean canAccess(Player player, Chest chest, ProtectionData data, boolean open) {
        if (player.hasPermission("simplelock.admin")) {
            logDebug("bypass_use", player.getName() + " used admin bypass at " + formatLoc(chest.getBlock()));
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
                    && townyHook.isMayor(player, chest)) {
                logDebug("bypass_use", player.getName() + " used mayor open bypass at " + formatLoc(chest.getBlock()));
                return true;
            }

            if (!open
                    && getConfig().getBoolean("towny.mayor_bypass_break", false)
                    && townyHook.isMayor(player, chest)) {
                logDebug("bypass_use", player.getName() + " used mayor break bypass at " + formatLoc(chest.getBlock()));
                return true;
            }

            if (open
                    && getConfig().getBoolean("towny.nation_leader_bypass_open", false)
                    && townyHook.isNationLeader(player, chest)) {
                logDebug("bypass_use", player.getName() + " used nation leader open bypass at " + formatLoc(chest.getBlock()));
                return true;
            }

            if (!open
                    && getConfig().getBoolean("towny.nation_leader_bypass_break", false)
                    && townyHook.isNationLeader(player, chest)) {
                logDebug("bypass_use", player.getName() + " used nation leader break bypass at " + formatLoc(chest.getBlock()));
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

    private String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
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

    private record TrustGuiHolder(Location location) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }
}