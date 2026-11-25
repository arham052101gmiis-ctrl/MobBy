# MobBy 1.1.0 commands

All commands are children of `/mobby` (alias-free, permission node in brackets).

| Command | Description | Permission |
|---|---|---|
| `/mobby active <x1> <y1> <z1> <x2> <y2> <z2> <name>` | Creates a region from two opposite corners and activates it immediately | `mobby.create` |
| `/mobby start <name>` | Starts automation for a stopped region | `mobby.start` |
| `/mobby stop <name>` | Stops automation, releases the region's chunk tickets and pauses uptime | `mobby.stop` |
| `/mobby remove <name>` | Asks for confirmation, then deletes the region and its saved data | `mobby.remove` |
| `/mobby remove <name> confirm` | Confirms the removal (must be run within 30 seconds) | `mobby.remove` |
| `/mobby list` | Lists every saved region, its status, current session and total uptime | `mobby.list` |
| `/mobby info <name>` | World, corners, volume, status, session, total uptime, chunk loading, modules, region contents | `mobby.info` |
| `/mobby reload` | Reloads `config.yml` without touching regions and without restarting the task | `mobby.reload` |
| `/mobby help` | Shows commands, examples and the volume rule | `mobby.help` |

Tab completion is implemented for sub commands and region names (stop only suggests active regions,
start only stopped ones, and `remove` suggests `confirm`).

Console usage: `/mobby active` uses the first loaded world unless you append a ninth argument with a
world name, e.g. `/mobby active 0 60 0 5 65 5 Test world_nether`.

## Permissions

| Permission | Default | Grants |
|---|---|---|
| `mobby.admin` | op | Every MobBy command (children below are all true) |
| `mobby.create` | op | `/mobby active` |
| `mobby.start` | op | `/mobby start` |
| `mobby.stop` | op | `/mobby stop` |
| `mobby.remove` | op | `/mobby remove` |
| `mobby.list` | op | `/mobby list` |
| `mobby.info` | op | `/mobby info` |
| `mobby.reload` | op | `/mobby reload` |
| `mobby.help` | true | `/mobby help` |


The console accepts an extra world name after the region name when creating a region. For example, use `mobby active 100 127 100 106 129 106 GoldFarm world_nether`. Both corners count toward the 2,000-block volume limit.
