package me.fearlessstudios.simplelock;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class GriefPreventionHook {

    public boolean isReady() {
        return GriefPrevention.instance != null && GriefPrevention.instance.dataStore != null;
    }

    public boolean canOpen(Player player, Location location) {
        Claim claim = getClaim(location);
        return claim != null && claim.checkPermission(player, ClaimPermission.Inventory, null) == null;
    }

    public boolean canBreak(Player player, Location location) {
        Claim claim = getClaim(location);
        return claim != null && claim.checkPermission(player, ClaimPermission.Build, null) == null;
    }

    public String describeClaim(Location location) {
        Claim claim = getClaim(location);
        if (claim == null) {
            return null;
        }

        if (claim.isAdminClaim()) {
            return "Admin claim";
        }

        String owner = claim.getOwnerName();
        return owner == null || owner.isBlank() ? "Player claim" : owner + "'s claim";
    }

    private Claim getClaim(Location location) {
        if (!isReady() || location == null) {
            return null;
        }

        return GriefPrevention.instance.dataStore.getClaimAt(location, true, null);
    }
}
