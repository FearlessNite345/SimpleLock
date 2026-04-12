## 🔒 SimpleLock

A simple and lightweight chest protection plugin that allows players to secure chests using signs with optional whitelist access, Towny integration, and configurable protection settings.

---

## ✨ Features

- 🔐 Protect chests using a sign with `[Protect]`
- 👥 Manage access with a simple whitelist GUI
- 📄 Paginated whitelist GUI for online players
- 👤 Manage offline players with whitelist add/remove commands
- 🧠 Full double chest support with no bypass issues
- 🛢️ Barrel protection support
- 🔄 Transfer ownership without rebuilding protection
- 🏙️ Optional Towny support for mayor and nation leader bypass
- 💰 Optional Vault support to charge for protection
- 🚫 Hopper protection enabled by default
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

- `/simplelock reload`  
  Reload the plugin config

- `/simplelock updateconfig`  
  Create a timestamped backup of `config.yml` and merge in any missing default settings from the latest plugin version

---

## 🔐 Permissions

- `simplelock.admin`  
  Bypass all protections and receive admin-only update/config warnings

- `simplelock.settings`  
  Open the in-game admin settings GUI

- `simplelock.reload`  
  Reload the config and run `/simplelock updateconfig`

No permission node is required for owners to use `/simplelock whitelist`, `/simplelock whitelist add/remove`, or `/simplelock transfer` on their own protected containers.

---

## ⚙️ Usage

Place a sign on a chest or barrel and write `[Protect]` on the first line.  
That container is now protected.

Use `/simplelock whitelist` while looking at the container to manage who can access it.

`config.yml` now includes `config_version`. If an older config is detected, SimpleLock logs a warning to console, lists any missing config paths, and notifies admins when they join or reload the plugin.

Run `/simplelock updateconfig` to create a timestamped backup and merge missing default keys into the live config. Existing values are kept as-is, so this is safe for upgrades, but you should still review the backup or latest bundled config if you want to adopt changed defaults or updated comments.

---

## 🔧 Compatibility

- Paper
- Spigot
- Towny (optional)
- Vault (optional)
