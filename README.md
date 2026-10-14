# Manbear – Forge 1.20.1 horror mod

Requires: Minecraft 1.20.1, Forge 47.x, **GeckoLib 4.4.9+** (4.x).

## Build
1. Install JDK 17.
2. Get a Gradle wrapper: copy `gradlew`, `gradlew.bat` and the `gradle/` folder from the official
   Forge 1.20.1 MDK into this folder (or run `gradle wrapper --gradle-version 8.1.1` if you have Gradle).
3. `./gradlew build` -> jar in `build/libs/`.  `./gradlew runClient` to test.
4. Drop the jar + GeckoLib into `mods/`.

## Your assets
`geo/manbear.geo.json`, `textures/entity/manbear.png` and `manbear_eyes.png` are byte-for-byte your files.
`animations/manbear.animation.json` is your file plus two derived clips: `crouch_walk` (walk cycle in the
hunt posture) and `crawl` (slowed `run2` gait), and `walk` is set to loop. Nothing in the textures was touched.
Model scale is `ManbearRenderer.MODEL_SCALE` (0.7 -> ~3 blocks tall, matching the 5-block hitbox).

## Testing quickly (OP)
* Spawn egg in the creative tab, or `/manbear spawn`
* `/manbear mode stalk|hide|peek|fake_chase|chase|lay_trap|ambush|retreat`
* `/manbear spawn [chaser|faker|trapper|sleeper]` forces that kind of spawn (also `behind`, `lurer`)
* `/manbear info`, `/manbear profile` (what he has learned about you), `/manbear remove`, `/manbear reset`
* Config: `config/manbear-common.toml` (spawn odds, speeds, damage, griefing, ...)

## How the requested features are done
| Request | Where |
|---|---|
| Spawn 1/25 per minute, only one per world | `ServerEvents.spawnRoll`, `ManbearData.claim` (a second one discards itself) |
| Stalking, circling while facing you | `ManbearBrain.tickStalk` + `ManbearMoveControl` (strafe input relative to a body that always faces you) |
| Peek behind trees, hide behind bushes | `CoverFinder` (logs/leaves/terrain that break line of sight, plus a side spot to lean out), `tickHide`, `tickPeek` |
| Random mode switching (stalk -> peek -> fake -> real chase) | `ManbearBrain.spontaneous` / `pickNextMode` – weighted, checked every second |
| Fake chase | `tickFake` (charges, stops short, stares; ~10% turn out real) |
| Real chase, lunge, speed = player then slightly faster | `tickChase`, `tickLunge`, `refSpeedMod` (matches your actual sprint speed, up to +12% over 45 s) |
| Break blocks to reach you | `BlockBreaker` (respects mobGriefing + config) |
| Climb / crawl | `WallClimberNavigation` + climb flag; stance system (stand 5.0 / crouch 3.3 / crawl 0.9) picks a smaller body when a route needs it |
| Crouch to be stealthy | stance + `hunt` / `crouch_walk` animations |
| Bear traps, ambush when you step in one | `BearTrapBlock` (hunter_set) -> `ManbearEntity.onTrapSprung` -> `AMBUSH` mode |
| Directional bear noises | positional sound events (`ModSounds`) played from his body |
| Glowing eyes | `ManbearEyesLayer` (your eyes texture, full-bright). The texture only has 2 pixels, so the glow is small. |
| Adapts to the player | `PlayerProfile` (vigilance, panic, aggression, camping, sneak, sprint, base location) saved in the world |

### What adaptation does
Looks at him a lot -> hides/peeks/fakes more and freezes when stared at. Ignores him -> comes closer, chases sooner.
Runs when chased -> more real chases and lunges. Fights back -> more traps, hit-and-run. Camps/holes up -> more chases
with block breaking, traps near your base.

### Sounds
They alias vanilla polar-bear/ravager sounds pitched down (`assets/manbear/sounds.json`). To use your own, put `.ogg`
files in `assets/manbear/sounds/` and point the `sounds.json` entries at them.

## If it does not compile first time
This was written without access to the Forge/GeckoLib jars. The spots most likely to need a tweak if your GeckoLib
version differs slightly: `withScale` and `reRender` (renderer/eyes layer), `AnimationState.isMoving`. The brain, entity and
block code only use vanilla 1.20.1 (official mappings) APIs.

## Update 2: stealth, short appearances, peeking, animals, ambience
* **Stalking is farther away** (default 26-42 blocks, `stalkMinDistance` / `stalkMaxDistance`); hiding/peeking cover is searched at that range too. He only closes in when ambushing, chasing, fake chasing (or if you walk up to him).
* **He vanishes after 90-120 s** (`lifetimeMinSeconds` / `lifetimeMaxSeconds`). If you are not looking at him he disappears immediately; if you are, he backs away and fades. If a chase is running he finishes it first (max +40 s). Bear traps he laid stay in the world (capped by `maxTraps`).
  Because his life is short, the real-chase grace period is now 20 s and he switches modes more often.
* **Peeking** now stays behind the tree and *leans out* around the trunk with the new `peek_left` / `peek_right` animations (built from your `hunt` pose; legs planted, hips/torso/head swing out, one arm grips the tree).
  If he leans into the tree instead of out of it, flip `swapPeekSides` in the config.
* **Animals** panic and run when he is near, and scatter in random directions for the first ~12 s after he appears (`animalsFlee`, `animalFleeRange`).
* **Distorted pig / cow / sheep noises** (`mimicAnimalSounds`) come from him, or sometimes from behind you.

## Update 3: scarier chases
* **Only 1 in 10 spawns are chasers** (`chaseChanceDenominator`). The other 9 never start a real chase on their own: they stalk, hide, peek, fake-charge and lay traps (fake charges become more common). A chaser can still be provoked into attacking if you hit him, and a bear trap you step in still triggers an ambush.
* **Faster chases:** he starts 15% faster than your sprint (`chaseSpeedBonusBase`) and builds to 35% faster after 30 s (`chaseSpeedBonusMax`, `chaseRampSeconds`). Fake charges run 15% faster than you so they look real.
* Delete `config/manbear-common.toml` (or edit the values) so the new defaults apply.

## Update 4
* **Spawn kinds.** Every spawn rolls independently: 1/14 chaser (`chaseChanceDenominator`), 1/9 fake-chaser (`fakeChaseChanceDenominator`), 1/6 trap-layer (`trapChanceDenominator`), 1/13 sleeper (`sleeperChanceDenominator`).
  A spawn with none of those only stalks, hides and peeks. (Getting hit still provokes him, and a trap you step in still brings him.)
* **Sleeper.** Found slumped on the ground with his eyes dark, snoring (you can hear him). Vibrations wake him like a Warden: sprinting/walking nearby, landing jumps, breaking/placing blocks, explosions, projectiles. Sneaking is silent. Wakes -> roars and chases at once. Sleepers last 4-6 min (`sleeper*` settings). `sleepNoiseThreshold` tunes how easily he wakes.
* **Sound design.** Heavy double-layered footsteps whose volume rises steeply as he gets closer, deeper breathing when near, layered deep roars. The roars/footsteps are built from pitched-down vanilla bear/ravager/warden sounds. To use real recordings, drop `.ogg` files into `assets/manbear/sounds/` and point the entries in `sounds.json` at them (e.g. `{"name":"manbear:roar1"}`).
* **Peeking** keeps the leaning pose until he starts moving again (one long lean per peek instead of in-and-out).
* **Stun roar** during real chases: only when you are 16-50 blocks away with line of sight, max 2 per chase, 2 s freeze + camera forced onto him. He keeps running (12% faster) while you are frozen. Settings: `roarsPerChase`, `roarMinDistance`, `roarMaxDistance`, `roarStunTicks`.
* Delete `config/manbear-common.toml` so the new options and defaults are created.

## Update 5
* **"Behind you" event (rarest, 1/20 spawns, `behindChanceDenominator`).** He appears 11-16 blocks directly behind you and stands motionless and silent (you may hear him breathe). He quietly follows if you walk off. When you finally turn and see him he roars (very loud, also played in your ears) and charges at 5x your sprint speed (`behindRushMultiplier`) for the first ~2.5 s, then chases normally. Test: `/manbear spawn behind`.
* **Roar limit fixed.** The 2-per-chase limit used to reset whenever he re-entered chase mode. It now resets only after 30 s without any chase, so he can never roar more than `roarsPerChase` times in one engagement.
* **Peek animation** now runs on its own animation layer (`peek`), independent of the walk/idle logic, and is re-asserted every tick while he holds the lean.
* **Sleep pose** rebuilt: lying flat on his stomach, arms and legs sprawled, head turned with cheek on the ground, slow breathing. (Built on the same forward-pitch pose as your `run2`; the lying height/offset may need a small nudge once you see it: edit `Manbear.position` in the `sleep` animation.)
* **Tunnelling to you in a house.** When he cannot path to you he now walks straight toward you and breaks the first block in the line between you and him (walls, roof, floor), looking both low and high. The old version only checked the block right in front of him, which is why he stood outside.

## Update 6
* **Thick trunks (2x2, 3x3 jungle/dark-oak):** the cover finder now measures trunk width, uses only the back face, hides behind the MIDDLE of a wide trunk and searches further out for a peek spot. Wide trunks use the new `peek_left_wide` / `peek_right_wide` animations (a much deeper lean). Normal trunks are unchanged.
* **Behind-you pounce:** when you turn and see him he now does a real leap: a short coil, then a ballistic jump that lands on you in under half a second (leading a moving target), with a new `leap` animation (coil -> full stretch, jaw open), the loud roar, and then the 5x-speed run if he missed.
* **Swimming:** in deep water he swims straight at you, faster than you swim (`swimSpeedFactor`), with a new `swim` animation and a flatter swim hitbox. Shallow water he wades through. Swimming away no longer works.
* **Animal lure (1/6 spawns, `lureChanceDenominator`):** he slips into cover, a wolf appears between you and him (whining to get your attention). Once you see it, it stares at you for a moment, then sprints to him. When it reaches him he lets out a long roar (about 4 s) and starts a real chase if that spawn is a chaser, otherwise a fake chase. Test: `/manbear spawn lurer`.
* Delete `config/manbear-common.toml` to get the new options.
