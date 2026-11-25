# MobBy 1.1.0 command reference

Use `/mobby help` in game or `mobby help` at the server console. Players create regions in their current world. The console uses the first loaded world unless the command includes a world name.

| Subcommand after `/mobby` | Permission | Behavior |
| --- | --- | --- |
| `active <x1> <y1> <z1> <x2> <y2> <z2> <name>` | `mobby.create` | Create a named cuboid and activate it immediately. Both corners count toward its volume. |
| `start <name>` | `mobby.start` | Resume a stopped region. |
| `stop <name>` | `mobby.stop` | Pause uptime and release the region's claim on chunk tickets. Other active regions can still hold shared chunks. |
| `remove <name>` | `mobby.remove` | Request deletion of the region and its saved data. |
| `remove <name> confirm` | `mobby.remove` | Confirm the pending removal within 30 seconds. |
| `list` | `mobby.list` | List saved regions, status and uptime. |
| `info <name>` | `mobby.info` | Inspect world, bounds, volume, status, uptime, tickets, modules and indexed contents. |
| `reload` | `mobby.reload` | Reload plugin configuration. |
| `help` | `mobby.help` | Show commands, examples and the volume rule. |

The default for `mobby.help` is everyone. All other command permissions default to operators. Grant `mobby.admin` for access to every subcommand. [Permission definitions](../src/main/resources/plugin.yml)

## Console example

```text
mobby active 100 154 100 106 165 106 GoldFarm world_nether
mobby info GoldFarm
```

This example selects a 7 × 12 × 7 region in the Nether. The inclusive volume is 588 blocks. The maximum supported region volume is 2,000 blocks.
