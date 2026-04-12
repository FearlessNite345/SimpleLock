## 🔒 SimpleLock

A simple and lightweight container protection plugin for Spigot, Paper, and Purpur servers that allows players to secure chests and barrels using signs with optional whitelist access, claim-plugin integration, and configurable protection settings.

---

## ✨ Features

- 🔐 Protect chests and barrels using a sign with `[Protect]`
- 👥 Manage access with a simple whitelist GUI
- 📄 Paginated whitelist GUI for online players plus offline trusted players
- 👤 Manage offline players with whitelist add/remove commands
- ℹ️ Inspect protected containers with `/simplelock info`
- 🧠 Full double chest support with no bypass issues
- 🛢️ Barrel protection support
- 🔄 Transfer ownership without rebuilding protection
- 🛠️ Admin force-unprotect command for staff cleanup
- 🏙️ Optional Towny support for mayor and nation leader bypass
- 🛡️ Optional GriefPrevention support for claim trust bypasses
- 🌿 Optional Lands support for area role bypasses
- 💰 Optional Vault support to charge for protection
- ♻️ Optional configurable refund when owners remove their own protection
- 🚫 Hopper protection enabled by default, including hopper placement checks
- 💥 Optional explosion protection
- ⚙️ Clean and configurable settings

---

## 🕹️ Commands

- `/simplelock whitelist`  
  Open the whitelist menu for the protected container you are looking at

- `/simplelock whitelist add <player>`  
  Add an online or known offline player to the whitelist of the container you are looking at

- `/simplelock whitelist remove <player>`  
  Remove an online or known offline player from the whitelist of the container you are looking at

- `/simplelock transfer <player>`  
  Transfer ownership of the protected container you are looking at

- `/simplelock info`  
  Show owner, trusted players, sign count, and claim context for the container you are looking at

- `/simplelock forceunprotect`  
  Admin-only command to remove protection from the container you are looking at

- `/simplelock reload`  
  Reload the plugin config

- `/simplelock updateconfig`  
  Create a timestamped backup of `config.yml`, merge in missing default settings, and remove obsolete keys

---

## 🔐 Permissions

- `simplelock.admin`  
  Bypass all protections, use admin override commands, and receive admin-only update/config warnings

- `simplelock.settings`  
  Open the in-game admin settings GUI

- `simplelock.reload`  
  Reload the config and run `/simplelock updateconfig`

No permission node is required for owners to use `/simplelock whitelist`, `/simplelock whitelist add/remove`, `/simplelock transfer`, or `/simplelock info` on their own protected containers.

---

## ⚙️ Usage

Place a sign on a chest or barrel and write `[Protect]` on the first line.  
That container is now protected.

Use `/simplelock whitelist` while looking at the container to manage who can access it.

Trusted offline players now appear in the whitelist GUI as removable entries, so you can clean up old access without falling back to commands.

Use `/simplelock info` while looking at a protected container to quickly inspect ownership, trusted players, protection sign count, and any active GriefPrevention or Lands claim context.

`config.yml` now includes `config_version`. If an older config is detected, SimpleLock logs a warning to console, lists any missing config paths, and notifies admins when they join or reload the plugin.

Chat messages and core GUI titles now live in `lang.yml`, while gameplay/settings stay in `config.yml`.

Run `/simplelock updateconfig` to create a timestamped backup, merge missing default keys into the live config, and remove obsolete keys. Existing values are kept as-is for supported settings, but you should still review the backup or latest bundled config if you want to adopt changed defaults, updated comments, or new optional economy settings.

---

## 🔧 Compatibility

- Supported server software: Spigot, Paper, Purpur
- Not supported: Bukkit, Folia, Sponge
- Towny (optional)
- GriefPrevention (optional)
- Lands (optional)
- Vault (optional)
