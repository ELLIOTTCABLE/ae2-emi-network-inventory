# AE2 → EMI network-inventory backport for MC 1.20.4 — implementation plan

**Audience:** a fresh implementation context ("conductor") that will write + test the mod. This document is self-contained; you should not need the originating chat. Read it top to bottom once before touching code.

**Status when handed off:** NeoForge 1.20.4 MDK scaffold is laid down in this folder (`git`-stripped), `gradle.properties` identity is set, example sources removed, a barebones `@Mod` stub exists at `src/main/java/io/ell/ae2emibackport/Ae2EmiBackport.java`. Nothing else is implemented. No Gradle build has been run yet.

**Implementation status (2026-06-19, build session):** Implemented and building green — `./gradlew build` → `build/libs/ae2emibackport-0.1.0.jar`. Every §5 API assumption was verified against the `neoforge/v17.13.0-beta` (AE2) and `1.1.22+1.20.4` (EMI) source tags, not memory.
- **Q1 resolved (covered for free):** AE2WTLib's `AE2wtlibEmiPlugin@17.12` imports and instantiates AE2's own `EmiUseCraftingRecipeHandler`/`EmiEncodePatternHandler` for `WCTMenu`/`WETMenu`; both extend `AbstractRecipeHandler` and both menus reach `MEStorageMenu`. The single base-class mixin covers AE2's 3 terminal handlers + AE2WTLib's 2 — no second handler. Neither concrete subclass overrides `getInventory`.
- **Q3 resolved (no refmap):** NeoForge 20.4 is Mojmap end-to-end; ae2wtlib ships mixins with no refmap field and no AP wiring. Our mixin only *adds* a method, so refmap is omitted.
- **Refinements vs. the §6 sketch:** (a) read `getCraftingSlots()` inside the override rather than mutating AE2's `getInputSources` (smaller surface, no EMI fill-button side-effect); (b) null-guard the `@Nullable` `EmiStackHelper.toEmiStack` (EMI's `EmiPlayerInventory` ctor NPEs on a null element — the #8294 crash class); (c) call `getInputSources`/`getCraftingSlots` via the `StandardRecipeHandler` interface, *not* `@Shadow`, because shadowing with the narrowed `T extends AEBaseMenu` makes javac emit synthetic bridge methods that collide with the target's own at mixin-apply (verified absent via `javap`). `EmiStackHelper` is `public` at this tag (the §5 "package-private" note is wrong), but the mixin still lives in `appeng.integration.modules.emi` to target the package-private `AbstractRecipeHandler`.
- **Still unverified — needs a live client (Q2):** that EMI actually invokes the override in-game, plus the §9 acceptance test. The override *binding* is proven at the bytecode level (`getInventory` erased descriptor matches EMI's default); what remains is runtime behaviour.

**v1.0.0 (2026-06-23) — shipped + tagged.** Network-inventory exposure works in-game (Q2 resolved: verified at an ME crafting terminal; stored items resolve EMI tree / synthetic-favourite completeness). Corrections to the 2026-06-19 notes above:
- The mixin lives at `io.ell.ae2emibackport.mixin.AbstractRecipeHandlerMixin`, **not** `appeng.integration.modules.emi`. A class in AE2's package caused a JPMS split-package `ResolutionException` at boot (NeoForge gives each mod its own module). It targets AE2's package-private base by string: `@Mixin(targets = "appeng.integration.modules.emi.AbstractRecipeHandler")`.
- The actual bug that hid network items: AE2 17.13.0's `EmiStackHelper.toEmiStack` only converts `AEFluidKey` (both registered converters' forward path is fluid-only) → returns null for every item. We now convert `AEItemKey`/`AEFluidKey` → `EmiStack` inline. (Latent AE2 bug; nothing in stock AE2 ever calls `toEmiStack` for items.)
- Base package/group is `io.ell.ae2emibackport`; a `debugLogging` client config (default off) gates a one-shot repo summary.

---

# ▲ NEXT FEATURE (researched, NOT implemented) — EMI synthetic-favourite *crafting execution* at AE2 terminals

**Research complete 2026-06-23; zero implementation.** Self-contained handoff for a fresh context — read before touching code. v1.0.0 only *exposes* the network to EMI (tree completeness); it does **not** execute crafts. This section is about making EMI's craft hotkeys actually craft at AE2 terminals.

## Direction (decided with EC)
Lean **hard toward matching EMI's behaviour, not AE2's**. Reuse AE2 behaviour *only where it correctly matches the EMI tree's assumptions and what the player resolved*. **Default to safe** — defer / disable / deny / noop whenever not totally sure; everything unhandled falls through to AE2's existing fill-only behaviour (or a deferral), never a wrong guess. **Roll out progressively** — one well-understood case at a time, tested in-game, then expand.

**Identity pivot (parked as "later"):** this turns the mod from a faithful AE2 backport into an "EMI-parity layer that overrides AE2 where they diverge." Accepted downstream implications: possible rename; supporting other AE2/MC versions (overriding upstream on other modpacks); deliberately overriding AE2's stated design intent (yueh, below).

## What EMI does (the behaviour to match) — vs emi 1.1.22+1.20.4 / 1.21 source
- Player picks a target recipe → EMI builds a Bill-of-Materials tree → sidebar **synthetic favourites** are the sub-craftables, each with a coloured count = remaining-to-craft, computed dynamically from the *currently-viewed* inventory. **This is exactly what v1.0.0's `getInventory` feeds** (switching player-inv ↔ terminal changes the counts). Colour = completability (green = all ingredients present *and* craftable in the current interface).
- **Abstract ingredients (tags) are resolved by the player** — they click through subtrees and actively pick a concrete item. EMI has first-class resolution machinery: `bom/` package (`MaterialTree`, `MaterialNode`, `BoM`) + `screen/widget/ResolutionButtonWidget`. An unresolved tag node is *not* directly craftable; EMI makes you resolve it first.
- Craft hotkeys call `handler.craft(EmiRecipe, EmiCraftContext)`. `EmiCraftContext` carries `amount` (the exact N) + `Destination` (NONE = fill only / CURSOR / INVENTORY). API contract (Destination javadoc): a handler that can't craft "should do nothing more than FILL" — AE2 takes that fallback today. [`api/recipe/handler/EmiCraftContext.java`, `StandardRecipeHandler.java`]
- **Vanilla exact-N mechanism** (`registry/EmiRecipeFiller.java`): `getStacks(...)` caps the fill at exactly `amount` batches (~line 215); `clientFill(...)` clears the grid, places exactly N-per-slot via vanilla `clickSlot(PICKUP)` from *real* input slots, then `clickSlot(output, QUICK_MOVE)` (INVENTORY) / `PICKUP` (CURSOR) → a **grid-bounded** craft = exactly N.
- **Why EMI's mechanism can't be reused for AE2:** it pulls ingredients from *real slots*; AE2's ingredients are virtual (network repo). That's precisely why AE2 bypasses `clientFill` with its own server packet.
- **Resolver API to defer to** (public, `api/EmiApi.java`): `displayRecipes(EmiIngredient)` (line ~131), `viewRecipeTree()` (~162), `displayRecipe(EmiRecipe)`, `focusRecipe(EmiRecipe)`.

## What AE2 does — vs `neoforge/v17.13.0-beta` (the installed frozen version)
- AE2 overrides `craft()` → `transferRecipe(…, doTransfer=true)` → `CraftingHelper.performTransfer(menu, recipeId, recipe, craftMissing)` → `FillCraftingGridFromRecipePacket(recipeId, templateItems[9], craftMissing)`. **No count field — one set only, and it does not execute.**
- The fill is **multi-source**: server sources network first, then falls back to the **player inventory** ("If still nothing, try taking it from the player inventory", `FillCraftingGridFromRecipePacket.handleOnServer` ~line 197; overflow → player inv ~line 172). Confirmed in-game by EC (0 in network + 6 in inv → '+' pulled 2 from inventory). `findMissingIngredients` likewise checks player inv first (with reservation) then the network repo (`CraftingTermMenu` ~204-262).
- **Craft actions** (`menu/slot/CraftingTermSlot.doClick`): `CRAFT_ITEM` (one → cursor), `CRAFT_STACK` (a stack → cursor), `CRAFT_SHIFT` (a stack → player inventory). `maxTimesToCraft = floor(maxStackSize / outputCount)` — bounded by a **stack**, never an arbitrary N; the count comes from `maxStackSize`, **not** the packet.
- `InventoryActionPacket(action, slot, extraId, slotItem)` — **no usable count channel** for crafts. Client sends them via `appeng/client/gui/AEBaseScreen.java` (~652). To trigger a craft ourselves: `NetworkHandler.instance().sendToServer(new InventoryActionPacket(InventoryAction.CRAFT_ITEM, outputSlot.index, 0))`.
- The craft (`CraftingTermSlot.craftItem`): builds the recipe from the grid as a **template** (`getPattern()`), extracts ingredients via `Platform.extractItemsByRecipe(…, storage = MEStorage, …)` — i.e. from the **network**. Multi-craft restock (the `CRAFT_SHIFT` loop) also reads network `storage`. **OPEN NUANCE:** the first craft consumes the filled grid (which may hold player-inv-sourced items from the fill fallback), but the restock path reads network storage — so multi-craft with player-inv ingredients may diverge from EMI's model. Verify in-game.
- Cursor → network deposit exists: `InventoryAction.PICKUP_OR_SET_DOWN` (`MEStorageMenu` ~line 495) — for routing output to the network. `useRealItems()` = true (grid holds real items).

## yueh's hazard (AE2 #4238)
Maintainer yueh rejected JEI/EMI craft hotkeys as clashing with AE2's design ("making manual crafting a bit more tedious to encourage using patterns") and warned that JEI/EMI *display* stacks can carry fake/missing NBT vs real items → mass-crafting from a display can grab the wrong variant. Mitigated for us (AE2's fill resolves to real network items), but it's the reason to default-safe on ambiguous tag/NBT cases.

## Feasibility (EC's three questions, answered)
1. **At all?** Yes. AE2 crafts from the network using the grid as a persistent template, so exact-N is reachable by issuing **N discrete `CRAFT_ITEM` actions**. A clean *single-action* exact-N is impossible via AE2's protocol (no count-aware craft) — only via repetition or a server component.
2. **Entirely client-side?** Yes for N× `CRAFT_ITEM` (+ a deposit for INVENTORY). No for a clean single-action (needs a server-side count-aware craft).
3. **Without invasive patching that breaks other mods?** Yes — uses AE2's existing public packets; our only change is a cancellable `@Inject` on `AbstractRecipeHandler.craft` in our own mixin, gated by config. EMI untouched; AE2's normal flow intact.

## Approach
**Intercept** `AbstractRecipeHandler.craft(recipe, context)` (cancellable `@Inject` at HEAD, config-gated). **Classify**, then **default to safe** (fall through to AE2's fill-only, or open EMI's resolver). Progressively widen the "known-safe-to-execute" set.

**Two execution modes:**
- **Client-only** (server lacks the mod): issue N× `InventoryActionPacket(CRAFT_ITEM, outputSlot.index, 0)` (each crafts one → cursor; grid template persists, extraction from network), then route the cursor stack (player inv, or network via `PICKUP_OR_SET_DOWN`). Cap each invocation at ~one output-stack (EC accepts re-clicking for bulk). Jank: cursor transit, N packets, the multi-craft sourcing nuance above. **Must verify in-game:** server packet ordering (fill before crafts) and cursor accumulation across packets.
- **Optional server component** (mod on both sides; detect via NeoForge channel/payload presence): a custom `CraftAmount(n, destination)` packet; server loops `craftItem` bounded to N and inserts output directly (no cursor, any N, clean source-aware routing). Needs a small *additive* server-side mixin to reach `CraftingTermSlot`'s private craft, gated on our packet (doesn't change AE2's normal behaviour). This is the clean path; client-only is the fallback.

**Smart output routing (EC refinement):** route results to where ingredients came from — network → network; player-inv → player inv; mixed → safe default (player inv, or defer). This is the EMI-faithful reading of "craft to inventory" (= to where it belongs), not a departure. Per-craft source-detection is hard client-side (AE2 doesn't expose it); the server component makes it clean.

**Abstract/tag deferral (EC's defensive default):** if the target/ingredient is an unresolved tag, do **not** guess — call `EmiApi.displayRecipes(...)` / `viewRecipeTree()` to pop EMI's resolver. Largely aligns with EMI (it already requires resolution). Deeper case: tag *ingredients* in a concrete recipe — AE2's fill picks "most-held", which can differ from the player's tree resolution; to honour the player's choice, fill from the recipe's resolved `EmiIngredient`s rather than AE2's guess (a bigger lever: overrides AE2's fill, not just its craft).

## Progressive rollout (each config-gated, each defaulting to safe; test before next)
0. **[shipped v1.0.0]** Network-inventory exposure → tree completeness.
1. **Abstract/ambiguous deferral** → open EMI resolver instead of crafting. Pure safety, no craft executed.
2. **Exact-N execute, CLEAN case only:** concrete recipe + fully-resolved concrete ingredients + single source available + N ≤ one output-stack; everything else → defer/noop. (Client-only N× `CRAFT_ITEM`.) The core micro-crafting win.
3. **Smart source-aware output routing.**
4. **Hard cases:** multi-source / player-inv-sourced crafts, large-N (cap, or server component), tag-ingredient honouring the player's resolution, NBT edges. Last; each only when proven. The optional server component slots in here for the clean large-N / routing path.

## Code map
- **Ours:** `src/main/java/io/ell/ae2emibackport/mixin/AbstractRecipeHandlerMixin.java` (currently overrides `getInventory`; add the cancellable `craft()` interception here). Config: `Ae2EmiBackportConfig.java`.
- **AE2** (`~/Sync/Code/Source/Applied-Energistics-2`, tag `neoforge/v17.13.0-beta`): `appeng/menu/slot/CraftingTermSlot.java` (`doClick`, `craftItem`), `appeng/menu/me/items/CraftingTermMenu.java` (`findMissingIngredients`, slot wiring, `useRealItems`), `appeng/core/network/serverbound/{InventoryActionPacket,FillCraftingGridFromRecipePacket}.java`, `appeng/integration/modules/itemlists/CraftingHelper.java`, `appeng/util/Platform.java` (`extractItemsByRecipe`), `appeng/menu/me/common/MEStorageMenu.java` (`PICKUP_OR_SET_DOWN`), `appeng/client/gui/AEBaseScreen.java` (~652).
- **EMI** (`~/Sync/Code/Source/emi`; use tag `1.1.22+1.20.4` for the installed version): `dev/emi/emi/api/recipe/handler/{StandardRecipeHandler,EmiCraftContext}.java`, `dev/emi/emi/registry/EmiRecipeFiller.java`, `dev/emi/emi/api/EmiApi.java`, `dev/emi/emi/bom/*`, `dev/emi/emi/screen/widget/ResolutionButtonWidget.java`.
- **Reference only** (wrong MC/AE2, identical fill-only behaviour — do NOT copy): `~/Sync/Code/Source/ae2-emi-crafting-forge` (talchas).

## Verify in-game before trusting
- Multi-craft ingredient sourcing (does the restock pull from player inv too, or network-only? affects exact-N with player-inv ingredients).
- N× `CRAFT_ITEM` accumulates correctly on the cursor across packets (server cursor state, same tick).
- Server-side packet ordering (fill processed before the craft actions).
- Whether abstract targets even reach `craft()` (EMI may filter them via resolution) — tells us how much deferral logic we actually need.

---

## 1. Goal (one sentence)

Make EMI's synthetic favorites / crafting tree / craftables register items that are **already stored in the AE2 ME network** as "present", when the player is at an AE2 terminal — on Minecraft **1.20.4 / NeoForge**, where AE2 never shipped this feature.

Concretely: replicate the behaviour of upstream AE2's client config option **`provideNetworkInventoryToEmi`** (added by AppliedEnergistics/Applied-Energistics-2 **PR #8215**) into a tiny standalone client mod, because that PR only ever reached AE2's 1.21+ line.

## 2. Target environment (exact, verified)

| Thing | Value |
|---|---|
| Modpack | FTB NeoTech (modpacks.ch / feed-the-beast pack id **123**, version id **100363**, display "1.12.2") |
| Minecraft | **1.20.4** |
| NeoForge | **20.4.248** (the MDK and `gradle.properties` are already pinned to this) |
| AE2 | **appliedenergistics2-neoforge-17.13.0-beta** (the **last-ever** 1.20.4 AE2 release, 2024-03-29) |
| EMI | **emi-1.1.22+1.20.4+neoforge** |
| AE2WTLib | **ae2wtlib-17.12.0-beta** (wireless terminals — see open question Q1) |
| Also present | JEI 17.3.1.5 (coexists with EMI), AE2 addons: ae2things, megacells, merequester, extendedae, appmek |

Prism instance path (Windows):
`C:\Users\ec\AppData\Roaming\PrismLauncher\instances\FTB NeoTech\.minecraft`
— mods in `…\.minecraft\mods`, AE2 config will appear at `…\.minecraft\config\ae2*.json` / our config at `…\.minecraft\config\ae2emibackport-client.toml`.

> The user's installed AE2 jar is right there in that `mods/` folder; it's the most reliable compile dependency (see §7, flatDir option).

## 3. Why 1.20.4 lacks the feature (don't re-litigate this)

- The feature is PR #8215, **merged 2024-12-08 into AE2 `main`**, which by then targeted MC 1.21 (AE2's 18.x/19.x line).
- AE2's 1.20.4 line froze at **17.13.0-beta on 2024-03-29** — ~9 months *before* the feature existed. The `origin/1.20.4` branch in the AE2 clone is only 6 trivial commits past that tag (typo/debug-command commits); **the feature is not there**.
- The standalone "AE2 EMI Crafting" mods (`blocovermelho/ae2-emi-crafting`, `talchas/ae2-emi-crafting-forge`) target **1.20.1 / AE2 15.x** and register their *own* full EMI plugin (they exist because 1.20.1 AE2 had **no** native EMI plugin). On 1.20.4 AE2 already has a native plugin, so porting either would (a) be a 1.20.1→1.20.4 + AE2 15.x→17.x migration and (b) collide with the native plugin. **Do not port them.** They are reference-only.

## 4. The exact mechanism (this is the whole feature)

PR #8215 is purely client-side and touches two files. Reproduced verbatim below (from the AE2 `main` diff):

**`appeng/core/AEConfig.java`** — a client config flag + accessor:

```java
// accessor
public boolean isExposeNetworkInventoryToEmi() {
    return client.exposeNetworkInventoryToEmi.getAsBoolean();
}
// field in ClientConfig
public final BooleanValue exposeNetworkInventoryToEmi;
// in ClientConfig() ctor
this.exposeNetworkInventoryToEmi = define(builder, "provideNetworkInventoryToEmi", false,
        "Expose the full network inventory to EMI, which might cause performance problems.");
```

**`appeng/integration/modules/emi/AbstractRecipeHandler.java`** — adds the crafting grid to input sources, and overrides EMI's `getInventory` to append the ME network's client repo:

```java
@Override
public List<Slot> getInputSources(T menu) {
    var slots = new ArrayList<Slot>();
    slots.addAll(menu.getSlots(SlotSemantics.PLAYER_INVENTORY));
    slots.addAll(menu.getSlots(SlotSemantics.PLAYER_HOTBAR));
    slots.addAll(menu.getSlots(SlotSemantics.CRAFTING_GRID));   // <-- #8215 added this line
    return slots;
}

@Override
public EmiPlayerInventory getInventory(AbstractContainerScreen<T> screen) {
    if (!AEConfig.instance().isExposeNetworkInventoryToEmi()) {
        return StandardRecipeHandler.super.getInventory(screen);   // vanilla behaviour: player inv only
    }

    var list = new ArrayList<EmiStack>();

    for (Slot slot : getInputSources(screen.getMenu())) {
        list.add(EmiStack.of(slot.getItem()));
    }

    if (screen.getMenu() instanceof MEStorageMenu menu) {
        var repo = menu.getClientRepo();
        if (repo != null) {
            for (var entry : repo.getAllEntries()) {
                if (entry.getStoredAmount() <= 0) {
                    continue; // Skip items that are only craftable (not actually stored)
                }
                list.add(EmiStackHelper.toEmiStack(new GenericStack(entry.getWhat(), entry.getStoredAmount())));
            }
        }
    }

    return new EmiPlayerInventory(list);
}
```

EMI consults `getInventory()` to decide what the player "has" for synthetic-favorites/craftable/tree completeness (confirmed by EMI author emilyploszaj in emi#696: *"EMI's existing solution is having the recipe handler return an `EmiInventory` with stacks present… EMI only refreshes craftables when the inventory changes"*). That's the entire feature.

## 5. Verified API surface (all present in AE2 17.13.0 + EMI 1.1.22)

Checked against the `neoforge/v17.13.0-beta` tag in the AE2 clone:

- `appeng.integration.modules.emi.AbstractRecipeHandler<T extends AEBaseMenu>` — **package-private abstract**, `implements StandardRecipeHandler<T>`, and **does not** override `getInventory` (clean injection point). Has `getInputSources(T)` returning player inv + hotbar (no crafting grid yet).
- `appeng.menu.me.common.MEStorageMenu#getClientRepo()` → **public**, returns `IClientRepo` (`MEStorageMenu.java:672`).
- `IClientRepo#getAllEntries()` → `Set<GridInventoryEntry>` (used this way by the talchas reference at `Ae2RecipeHandler.java:43`).
- `GridInventoryEntry#getStoredAmount()` (long) and `#getWhat()` (`AEKey`) — present (`Repo.java:411/440`).
- `appeng.integration.modules.emi.EmiStackHelper#toEmiStack(GenericStack)` → **`public static`** (`EmiStackHelper.java:33`). Handles item + fluid via converters, so reusing it is faithful to all stack types. (The *class* `EmiStackHelper` is package-private — see §6 access note.)
- `appeng.api.stacks.GenericStack(AEKey, long)`, `appeng.menu.SlotSemantics.{PLAYER_INVENTORY,PLAYER_HOTBAR,CRAFTING_GRID}` — present.
- EMI: `dev.emi.emi.api.recipe.EmiPlayerInventory`, `dev.emi.emi.api.recipe.handler.StandardRecipeHandler#getInventory(AbstractContainerScreen<T>)` (default method), `dev.emi.emi.api.stack.EmiStack#of(ItemStack)` — present in EMI 1.1.22 (these were already used by the 1.20.1 standalone mods against EMI **1.0.22**, and AE2 17.x already compiles against `StandardRecipeHandler`).

So the backport is mechanically a near-verbatim copy of §4, with one change: the config gate reads **our** config instead of `AEConfig`.

## 6. Chosen approach: a one-class client Mixin mod

Inject the §4 `getInventory` override into AE2's existing `AbstractRecipeHandler` via Mixin. This **augments AE2's native EMI plugin** (no second plugin → no conflict), needs **no AE2 rebuild**, and is stable because AE2 17.13.0 is the frozen final 1.20.4 build (the usual "mixin breaks on update" risk is moot).

Rejected alternatives (already analysed; don't redo): forking + rebuilding AE2 (heavy, replaces a 10 MB core mod, fork-bound forever); porting the standalone 1.20.1 mods (wrong MC + AE2 versions, plugin collision); full from-scratch plugin (re-does what AE2 already ships).

### Mixin design

Create `src/main/java/appeng/integration/modules/emi/AbstractRecipeHandlerMixin.java`.

**Why that package:** placing the mixin in AE2's own package `appeng.integration.modules.emi` lets it (a) reference the package-private target class and (b) call the package-private-classed `EmiStackHelper.toEmiStack` directly, exactly as #8215 does. The mixin config's `package` will point here and list only this one class.

Sketch (types shown erased where the generic `T` is awkward in a mixin — verify during build):

```java
package appeng.integration.modules.emi;

import java.util.ArrayList;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;

import appeng.api.stacks.GenericStack;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.common.MEStorageMenu;
import io.ell.ae2emibackport.Ae2EmiBackportConfig;

@Mixin(AbstractRecipeHandler.class)            // package-private class is visible: same package
public abstract class AbstractRecipeHandlerMixin implements StandardRecipeHandler<AEBaseMenu> {

    @Shadow public abstract java.util.List<Slot> getInputSources(AEBaseMenu menu);

    @Override
    public EmiPlayerInventory getInventory(AbstractContainerScreen<AEBaseMenu> screen) {
        if (!Ae2EmiBackportConfig.exposeNetworkInventoryToEmi()) {
            return StandardRecipeHandler.super.getInventory(screen);
        }
        var list = new ArrayList<EmiStack>();
        for (Slot slot : getInputSources(screen.getMenu())) {
            list.add(EmiStack.of(slot.getItem()));
        }
        if (screen.getMenu() instanceof MEStorageMenu menu) {
            var repo = menu.getClientRepo();
            if (repo != null) {
                for (var entry : repo.getAllEntries()) {
                    if (entry.getStoredAmount() <= 0) continue;
                    list.add(EmiStackHelper.toEmiStack(new GenericStack(entry.getWhat(), entry.getStoredAmount())));
                }
            }
        }
        return new EmiPlayerInventory(list);
    }
}
```

Mixin mechanics — this is a **method-add, not an @Inject** (no vanilla-method target to miss, so the nerb-style `InvalidInjectionException` failure mode does not apply). The added `getInventory` overrides EMI's interface default purely by being a concrete member with the matching **erased** signature. Get the generics/erasure right; if the compiler/Mixin AP complains, fall back to raw types (`AbstractContainerScreen`, `getInputSources(AEBaseMenu)`) — the erased signature is what must line up with `StandardRecipeHandler.getInventory(AbstractContainerScreen)`.

If reusing the package-private-classed `EmiStackHelper` proves troublesome at compile, the fallback is to replicate its tiny conversion (item key → `EmiStack.of(key.toStack((int) amount))`, fluid key → fluid `EmiStack`) — but prefer reuse for fluid fidelity.

## 7. Build wiring (what to add to the scaffold)

**`build.gradle` → `repositories {}`** add:
```groovy
maven { name = "ModMaven";       url = "https://modmaven.dev" }              // AE2
maven { name = "TerraformersMC"; url = "https://maven.terraformersmc.com/" } // EMI
maven { name = "Modrinth";       url = "https://api.modrinth.com/maven" }    // ae2wtlib (only if Q1 needs it)
```

**`build.gradle` → `dependencies {}`** add (NeoGradle uses plain `compileOnly`/`runtimeOnly`, *not* Loom's `modCompileOnly`):
```groovy
// AE2 — needed to compile against its internals. Two options:
//   (a) from ModMaven:
compileOnly "appeng:appliedenergistics2-neoforge:17.13.0-beta"
//   (b) MOST RELIABLE — the user already has the exact jar; flatDir it:
//       copy it to ./libs/ and use:  compileOnly files("libs/appliedenergistics2-neoforge-17.13.0-beta.jar")
//       (source: …\PrismLauncher\instances\FTB NeoTech\.minecraft\mods\)

// EMI API for compile, full EMI at dev runtime:
compileOnly  "dev.emi:emi-neoforge:1.1.22+1.20.4:api"
localRuntime "dev.emi:emi-neoforge:1.1.22+1.20.4"

// For running the dev client to test, also put AE2 (+ its hard deps) on localRuntime,
// or just test in the real Prism instance (see §9) to avoid assembling AE2's dep tree in dev.
```
Mixin is bundled with NeoForge; no extra dependency. Ensure the Mixin annotation processor is active (NeoGradle 1.20.4 MDK enables it; if the refmap isn't generated, add the AP per NeoForge mixin docs).

**`src/main/resources/META-INF/mods.toml`** — it is placeholder-driven (`${mod_id}` etc.), so identity already flows from `gradle.properties`. Do two things:
1. Uncomment + set the mixin block:
   ```toml
   [[mixins]]
   config="ae2emibackport.mixins.json"
   ```
2. Add dependencies on AE2 (required) and EMI (required); set the mod client-only-friendly:
   ```toml
   [[dependencies.ae2emibackport]]
       modId="ae2"            # AE2's modid
       type="required"
       versionRange="[17,18)"
       ordering="AFTER"
       side="CLIENT"
   [[dependencies.ae2emibackport]]
       modId="emi"
       type="required"
       versionRange="*"
       ordering="AFTER"
       side="CLIENT"
   ```
   Consider `displayTest="IGNORE_ALL_VERSION"` so a server without it doesn't red-X clients (this is a client-only mod).

**`src/main/resources/ae2emibackport.mixins.json`** (new):
```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "appeng.integration.modules.emi",
  "compatibilityLevel": "JAVA_17",
  "client": ["AbstractRecipeHandlerMixin"],
  "injectors": { "defaultRequire": 1 },
  "refmap": "ae2emibackport.refmap.json"
}
```
(`client` not `mixins`, since this is client-only.)

## 8. Config (the perf escape hatch)

Replicate AE2's `provideNetworkInventoryToEmi` as our own NeoForge **client** config so the behaviour is toggleable (the feature walks the whole network inventory; #8215 itself warns "might cause performance problems"). Create `Ae2EmiBackportConfig` (client `ModConfigSpec`, registered from the `@Mod` ctor) exposing a static `boolean exposeNetworkInventoryToEmi()`.

**Default: `true`** — the user explicitly wants this on (this differs from upstream's default-off). Keep the off switch for big-network perf. If hitching appears on large networks, see the talchas reference for an off-thread construction pattern (§10, Q4).

## 9. Build / install / test

0. **Toolchain:** JDK 17 is pinned in the project-local `mise.toml`; run `mise trust && mise install` once. Gradle comes from the `./gradlew` wrapper, and all mod deps (NeoForge, AE2, EMI) are resolved by Gradle — nothing else needs installing (no winget/system-global).
1. `./gradlew build` → jar in `build/libs/ae2emibackport-0.1.0.jar`. First run downloads the NeoForge userdev + deps (slow once).
2. Install: drop the jar into the Prism instance `…\FTB NeoTech\.minecraft\mods\`. AE2 + EMI are already there.
   - **Blocker:** that instance currently crashes on launch for an unrelated reason (a bad `fusion` mod update). Verified-good replacement jars are staged at `%TEMP%\ftb-neotech-revert\` (`fusion-1.2.12`, `stevescarts-1.20.4-1.2.16`); the pack must boot before you can test. Confirm with the user before changing their instance.
3. In-game verification (the actual acceptance test):
   - Open an ME **Crafting Terminal**. Pick a multi-step item and create an EMI **synthetic favorite** / open its **tree**.
   - With sub-components **stored in the ME network**, those tree nodes should read as **present/satisfied** (not red "missing"). Pull them out of the network → they should flip to missing. This is the feature.
   - Toggle the config **off** → behaviour reverts to player-inventory-only. Toggle on → returns.
4. Regression: client launches with no mixin-apply error in the log (`grep -i "mixin" latest.log` should show it applied, not failed); ordinary EMI viewing and the `+`/fill-from-network still work.

## 10. Open questions / risks to resolve during implementation

- **Q1 (most important): AE2WTLib wireless terminals.** The pack uses ae2wtlib heavily (tech pack). The mixin covers handlers that extend AE2's `AbstractRecipeHandler`. Determine whether ae2wtlib's wireless crafting/pattern terminals (a) reuse AE2's own menu+EMI handler (then they're covered for free) or (b) register their own EMI handler not extending `AbstractRecipeHandler` (then they need a second mixin/handler — note the standalone mod needed a separate `Ae2WtEmiPlugin`). Clone `https://github.com/62832/AE2WTLib` (or the matching repo) into `~/Sync/Code/Source/` and check whether its wireless terminal menu extends `MEStorageMenu` and how/if it integrates EMI on 17.x.
- **Q2: interface-default override via mixin.** Confirm the added `getInventory` actually overrides EMI's default at runtime (set a breakpoint/log; verify EMI calls it). Get the erased signature exactly right.
- **Q3: refmap / mappings.** NeoForge 1.20.4 runs Mojmap; the mixin touches AE2/EMI types (deobf) and only `AbstractContainerScreen`/`Slot`/`ItemStack` from vanilla. Risk is low (no vanilla-method @Inject), but verify the build emits a valid `ae2emibackport.refmap.json` and the mixin applies. (Context: the originating session debugged a *different* mod whose 1.21-era SRG refmap failed to bind on this exact Mojmap runtime — so explicitly confirm ours binds.)
- **Q4: performance.** Large networks = large `getAllEntries()`. Upstream is synchronous and warns about it; the talchas reference builds the inventory **off-thread** with a `Future` + player-inventory fallback (see references). Ship synchronous first; adopt off-thread only if hitching is observed.
- **Q5: EMI refresh cadence.** EMI refreshes craftables when the inventory changes; AE2's client repo updates as the network changes, so it should propagate. If the tree goes stale, investigate nudging EMI on repo update. (Community report in emi#696: works well in practice.)

## 11. Reference implementations on disk (read these)

- **Canonical (copy from this):** AE2 clone at `~/Sync/Code/Source/Applied-Energistics-2`, branch `main`. The `getInventory` override lives in `src/main/java/appeng/integration/modules/emi/AbstractRecipeHandler.java`. Inspect the exact merged form with:
  `git -C ~/Sync/Code/Source/Applied-Energistics-2 show main:src/main/java/appeng/integration/modules/emi/AbstractRecipeHandler.java`
  Your frozen target (to mixin against) is the `neoforge/v17.13.0-beta` tag of the same file.
  The PR itself: `gh pr diff 8215 --repo AppliedEnergistics/Applied-Energistics-2`.
- **Off-thread variant + standalone structure:** `~/Sync/Code/Source/ae2-emi-crafting-forge` (talchas, Architectury, 1.20.1) —
  `common/src/main/java/org/blocovermelho/ae2emicrafting/client/handler/generic/Ae2BaseRecipeHandler.java` (`getInventory` with `executorService`/`Future`), and `…/helper/InventoryUtils.java` (`menu.getClientRepo().getAllEntries()` walk). Reference only — wrong MC/AE2 versions, registers its own plugin.
- **Fabric original:** `~/Sync/Code/Source/ae2-emi-crafting` (blocovermelho, Kotlin, 1.20.1). Reference only.
- Issues/threads: AE2 #8074 (why it was gated), #8195 (craftables tab vs ME contents), EMI #696 (the API mechanism), PR #8215 (the implementation), PR #8294 (a crash fix for non-EMI-compatible stacks exposed to EMI — worth reading for edge cases).

## 12. Licensing (do not skip)

AE2 is **LGPL-3.0-only**. This mod copies AE2 code (the §4 method), so it is a derivative work and **must be LGPL-3.0-only**. `gradle.properties` is set accordingly. Add a `LICENSE` file (LGPL-3.0) and a short header/attribution on the mixin file pointing at AE2 PR #8215. This matters for redistribution (sharing the jar with friends / posting it).

## 13. Distribution

Client-only (EMI integration is client-side). Friends who want the feature each drop the jar in their client `mods/`; dedicated servers do **not** need it. `displayTest=IGNORE_ALL_VERSION` keeps it from flagging server/client version mismatches.

## 14. Definition of done

- `./gradlew build` produces a jar; client launches in the FTB NeoTech instance with the mixin applied (no error in log).
- At an ME crafting terminal, EMI synthetic-favorite/tree nodes for items **stored in the network** show as present; removing them from storage flips them to missing; the config toggle disables/enables the behaviour.
- Q1 (wireless terminals) explicitly answered — either "covered for free" (with evidence) or a second handler added.
- `LICENSE` (LGPL-3.0) present; attribution header on the mixin.
