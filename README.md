# CreateWorld

CreateWorld is a NeoForge 1.21.1 server mod that gives a survival server an isolated creative testing dimension.

## Player Flow

Players craft `Creative Portal Frame` blocks and a `Creative Portal Activator`.

Build a flat 5x5 floor frame with a 3x3 empty center, then use the activator on the frame or inside edge. Standing on the portal surface starts a creative testing session:

1. The player stands in the portal for 5 continuous seconds.
2. Any open menu is closed so temporary input/cursor stacks return to the player before the snapshot.
3. The mod snapshots the player's survival state.
4. Vanilla inventory, armor, offhand, carried cursor stack, and ender chest are cleared before teleport.
5. The player is moved to `createworld:creative_world` and set to Creative mode.
6. The shared creative world saves normally as part of the server world.
7. The player returns through the generated portal at the creative spawn or with `/createworld return`.
8. Any creative-side items are wiped, then the saved survival state is restored.

## Isolation Strategy

The survival snapshot is stored in the player's persistent NeoForge entity data so it survives disconnects, deaths, and server restarts.

Protected state includes:

- Main inventory, hotbar, armor, and offhand
- Ender chest inventory
- Selected slot and carried cursor stack
- Game mode
- Serialized vanilla player state, including XP, food data, health, absorption, active effects, fire/air/fall state, attributes, flight/build abilities, recipe book, respawn point, and NeoForge player attachments
- Player persistent NeoForge data, restored from the pre-session snapshot after clearing creative-session data
- Scoreboard objective scores and team membership
- Vanilla non-inventory carry states such as shoulder entities, entity scoreboard tags, custom entity name state, last-death location, and explosion impulse state
- Modded player item-handler capability slots when another mod exposes a non-vanilla, modifiable combined `Capabilities.ItemHandler.ENTITY` view

Creative-side player death is canceled and the player is reset at the creative spawn, which prevents many grave/corpse mods from capturing creative items. Creative-side drops from player death are canceled as a second layer. If a player logs out inside the creative dimension, their creative inventory is wiped before save and again on login before they can return. If a session tag is ever found while the player is outside the creative dimension, CreateWorld sends the player back to the saved return location before restoring survival state.

If a player is ever found in the creative dimension without a valid session, CreateWorld ejects them to the overworld. Forced entry during normal gameplay preserves their current inventory rather than deleting survival items; login recovery takes the stricter path and wipes creative-side contents before ejecting.

On restore, CreateWorld clears creative-session persistent player data, data attachments, shoulder entities, entity tags, custom name state, and other transient carry fields before loading the saved snapshot. This prevents creative-only keys or vanilla NBT side channels from surviving simply because the original survival snapshot did not have those keys.

The creative spawn platform and flat return portal are only initialized when the return portal is missing, so player builds in the creative dimension are not reset on every trip.

## Anti-Cheat Guards

CreateWorld blocks unauthorized travel into `createworld:creative_world`. Other mods can no longer teleport players there as a valid entry path; players must enter through a CreateWorld portal so the survival snapshot exists first.

Inside an active creative session, the default security config:

- Blocks non-operator commands except `/createworld return`
- Cancels attempts to change an active creative-session player out of Creative mode while they remain in the creative dimension
- Blocks non-CreateWorld dimension exits during a creative session and blocks non-player entities from crossing the creative-world boundary
- Dismounts and ejects passengers at the session boundary, and strips saved root-vehicle/passenger NBT from the session snapshot
- Deletes item entities created in the creative dimension
- Clears block drops before they spawn as item entities
- Prevents item pickup
- Cancels creative-side XP gain, XP orb spawning/pickup, fishing rewards, spawn-point changes, stat awards, and advancement criteria before vanilla advancement rewards complete
- Cancels or sanitizes common progress/reward hooks for bone meal, animal taming, crafting, smelting, potion brewing, enchanting, anvil repair, and villager trades
- Clears living drops in the creative dimension and blocks attacking storage-capable entities
- Restores the saved recipe book and player scoreboard objective values on exit
- Blocks use, placement, and interaction for item/fluid/energy capability items, blocks, and entities
- Blocks risky or payload-carrying items when used on entities and while long-use actions are starting, ticking, or stopping
- Blocks creative-made item stacks that carry stored contents, loot-table containers, block/entity NBT data, custom data, bundle contents, charged projectiles, or recipe unlock payloads
- Blocks risky item-on-item stacking behavior, such as stuffing creative payload items into another item stack
- Blocks breaking, left-clicking, or tool-modifying tagged/risky storage and network blocks that already exist in the creative world
- Closes non-inventory containers opened by mods
- Blocks tagged or risky-id blocks/items/entities such as vanilla storage blocks, shulker boxes, chest boats, ender chests, global networks, graves, chunk loaders, teleporters, and storage mounts
- Blocks new storage-like entities such as armor stands and item frames from being created as item containers
- Blocks mounting/interacting with storage-capable entities

## Compatibility Plan

The implementation avoids mixins and overwriting vanilla dimension logic. It uses NeoForge events, a normal datapack dimension JSON, standard registries, and the NeoForge item-handler capability hook.

For modded inventory compatibility, the safe path is:

- Vanilla player inventory is handled directly.
- NeoForge's vanilla `PlayerInvWrapper`, and other exact 41-slot mirrors of vanilla inventory, are ignored because vanilla inventory is already handled directly.
- Any other modifiable combined entity item-handler capability is snapshotted, cleared, restored, and kept empty during the creative session.
- A non-vanilla, non-modifiable player item handler blocks entry instead of silently deleting or leaking items.
- A non-vanilla player item handler exposed only through `Capabilities.ItemHandler.ENTITY_AUTOMATION` also blocks entry unless the normal combined entity item handler is safely writable.

Mods that keep player items outside vanilla inventory and do not expose a writable NeoForge item-handler capability need a small compatibility bridge before this can guarantee isolation for those slots.

Server packs can extend the deny lists with datapack tags:

- `createworld:blocked_creative_interaction` block tag
- `createworld:blocked_creative_placement` block tag
- `createworld:blocked_creative_use` item tag
- `createworld:blocked_creative_interaction` entity type tag

The common config also includes `riskyIdPatterns`, which blocks registry ids containing high-risk words such as `waystone`, `wireless`, `network`, `backpack`, `tank`, `pipe`, `grave`, `chunk_loader`, and `entangled`.

`blockCreativeProgress` is enabled by default. It blocks the common persistent progress channels used by quest, recipe, and reward mods. Some NeoForge progress events are not cancellable, so CreateWorld sanitizes their exposed item/offer payloads as early as possible and restores the saved player state on exit. Mods that directly grant custom rewards from non-cancellable events or private APIs may still need a small compatibility rule or deny-list entry.

`blockCreativeItemPayloads` is also enabled by default. It catches creative inventory packets that create otherwise-normal item stacks carrying nested item contents, custom data, entity data, block-entity data, or recipe unlock payloads.

## Admin Recovery

`/createworld return` returns the executing player if they are in a session.

`/createworld restore <player>` exits and restores a saved session for an operator-level emergency recovery instead of restoring survival items in-place inside the creative dimension.
