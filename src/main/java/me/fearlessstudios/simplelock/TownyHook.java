package me.fearlessstudios.simplelock;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.*;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class TownyHook {

    public boolean isMayor(Player player, Location location) {
        Town town = TownyAPI.getInstance().getTown(location);
        if (town == null) return false;

        Resident res = TownyAPI.getInstance().getResident(player);
        return res != null && res.equals(town.getMayor());
    }

    public boolean isNationLeader(Player player, Location location) {
        Town town = TownyAPI.getInstance().getTown(location);
        if (town == null || town.getNationOrNull() == null) return false;

        Nation nation = town.getNationOrNull();
        Resident res = TownyAPI.getInstance().getResident(player);
        return res != null && res.equals(nation.getKing());
    }
}
