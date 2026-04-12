package me.fearlessstudios.simplelock;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class LandsHook {

    private final LandsIntegration integration;

    public LandsHook(Plugin plugin) {
        this.integration = LandsIntegration.of(plugin);
    }

    public boolean isReady() {
        return integration != null;
    }

    public boolean canOpen(Player player, Location location, Material material) {
        Area area = getArea(location);
        LandPlayer landPlayer = getLandPlayer(player);
        return area != null && landPlayer != null && area.hasRoleFlag(landPlayer, Flags.INTERACT_CONTAINER, material, false);
    }

    public boolean canBreak(Player player, Location location, Material material) {
        Area area = getArea(location);
        LandPlayer landPlayer = getLandPlayer(player);
        return area != null && landPlayer != null && area.hasRoleFlag(landPlayer, Flags.BLOCK_BREAK, material, false);
    }

    public String describeArea(Location location) {
        Area area = getArea(location);
        if (area == null) {
            return null;
        }

        String landName = area.getLand().getName();
        String areaName = area.getName();
        if (areaName.isBlank() || areaName.equalsIgnoreCase(landName)) {
            return landName;
        }

        return landName + " / " + areaName;
    }

    private Area getArea(Location location) {
        if (!isReady() || location == null) {
            return null;
        }

        return integration.getArea(location);
    }

    private LandPlayer getLandPlayer(Player player) {
        return integration.getLandPlayer(player.getUniqueId());
    }
}
