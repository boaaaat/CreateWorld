# CreateWorld

CreateWorld is a NeoForge 1.21.1 server mod that gives a survival server an isolated creative testing dimension.

## Player Flow

Players craft `Creative Portal Frame` blocks and a `Creative Portal Activator`.

Build a 4x5 rectangular frame with a 2x3 empty inside, then use the activator on the inside edge. Walking into the portal starts a creative testing session:

1. The mod snapshots the player's survival state.
2. Vanilla inventory, armor, offhand, carried cursor stack, and ender chest are cleared before teleport.
3. The player stands in the portal for 5 continuous seconds.
4. The player is moved to `createworld:creative_world` and set to Creative mode.
5. The shared creative world saves normally as part of the server world.
6. The player returns through the generated portal at the creative spawn or with `/createworld return`.
7. Any creative-side items are wiped, then the saved survival state is restored.

## Isolation Strategy

The survival snapshot is stored in the player's persistent NeoForge entity data so it survives disconnects, deaths, and server restarts.

Protected state includes:

- Main inventory, hotbar, armor, and offhand
- Ender chest inventory
- Selected slot
- Game mode
- XP, food data, health, and absorption where vanilla exposes stable save fields
- Modded player item-handler capability slots when another mod exposes a non-vanilla, modifiable `Capabilities.ItemHandler.ENTITY` view

Creative-side drops from player death are canceled in the creative dimension. If a player logs out inside the creative dimension, their creative inventory is wiped again on login before they can return.

The creative spawn platform and return portal are only initialized when the return portal is missing, so player builds in the creative dimension are not reset on every trip.

## Compatibility Plan

The implementation avoids mixins and overwriting vanilla dimension logic. It uses NeoForge events, a normal datapack dimension JSON, standard registries, and the NeoForge item-handler capability hook.

For modded inventory compatibility, the safe path is:

- Vanilla player inventory is handled directly.
- A non-vanilla, modifiable entity item-handler capability is snapshotted, cleared, and restored.
- A non-vanilla, non-modifiable handler with items blocks entry instead of silently deleting or leaking items.

Mods that keep player items outside vanilla inventory and do not expose a writable NeoForge item-handler capability need a small compatibility bridge before this can guarantee isolation for those slots.

## Admin Recovery

`/createworld return` returns the executing player if they are in a session.

`/createworld restore <player>` restores a saved session snapshot for an operator-level emergency recovery.
