# Add-on guide

This guide is for mod developers writing an add-on that listens for trades on Variable Economy's player exchange. It assumes you know NeoForge modding, but nothing about this mod's code.

The exchange's API is one event, `ExchangeTradeEvent`. Everything else an add-on uses (accounts, money, item locks, net worth, the audit log, core's events and the general setup) is core's API, described in [core's add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md). Read that first; this guide covers only what's particular to the exchange. For what the exchange does, read the [admin guide](admin-guide.md).

The API runs on the server only. A client-only mod can't use it.

## The package

| Mod | Mod ID | Artifact | Package | What's in it |
| --- | --- | --- | --- | --- |
| Exchange | `variable_economy_exchange` | `exchange` | `variableeconomy.exchange.api.event` | Player exchange events. |

The exchange is an optional add-on on top of core: a server may have core without it. Its package is there only when the exchange is installed, so check its mod ID before using it, even when your mod requires core (see [An optional dependency](#an-optional-dependency)).

### Stability

- The API is the package in the table above. It's marked `@ApiStatus.Experimental` until 1.0: it may still change in a 0.x minor release (0.6 to 0.7, say). Every change is listed in this file's [changelog](#changelog).
- Everything else in the jar is internal. Those packages are marked `@ApiStatus.Internal` and may change in any release, a patch release included. Don't call them, even when a class is public: public there means "used by another package of this mod", not "supported".

## Depending on the exchange

The mods are distributed on CurseForge, and you can depend on the released jars through [CurseMaven](https://www.cursemaven.com/), with the repository block from core's guide. The exchange is CurseForge project **1818114**; the exchange 0.6.1 is `curse.maven:variable-economy-exchange-1818114:9717539`, and core 0.6.1 is `curse.maven:variable-economy-1818087:9710072`. Use those in place of the `variableeconomy:` coordinates below. CurseMaven serves only the mod jars, without sources or javadoc.

For the sources and javadoc, publish them to your local Maven from source. Clone this repository, check out the release you target (tags start with `v`, such as `v0.6.1`), and run `gradlew publishToMavenLocal`. That publishes `variableeconomy:exchange:<version>`, with the version without the `v` (`0.6.1`), and a sources jar and a javadoc jar beside it, so your IDE shows the javadoc and the source. The javadoc covers only the exchange's API package. The exchange's artifact doesn't bring core with it: publish core the same way from [its repository](https://github.com/The-Azotus/variable-economy-core), and depend on both. Core's add-on guide explains the setup in full.

### Gradle

Compile against core and the exchange, and put them on the dev runtime so they run in your dev client and server. The `localRuntime` configuration (the NeoForge MDK template has it) keeps them out of your published POM.

```groovy
repositories {
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeGroup 'variableeconomy' }
    }
}

configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    compileOnly 'variableeconomy:core:0.6.1'
    compileOnly 'variableeconomy:exchange:0.6.1'
    localRuntime 'variableeconomy:core:0.6.1'
    localRuntime 'variableeconomy:exchange:0.6.1'
}
```

The exchange is off by default, so turn it on in your dev world (`enabled = true` under `[exchange]` in `config/variable_economy_exchange-server.toml`) to see its events.

### neoforge.mods.toml

Declare the exchange as an optional dependency of your mod, replacing `yourmod` with your mod ID (and core as required or optional, as core's guide shows):

```toml
[[dependencies.yourmod]]
    modId="variable_economy_exchange"
    type="optional"
    versionRange="[0.6,)"
    ordering="AFTER"
    side="BOTH"
```

- Variable Economy's mods must be installed on the server and on every client, so use `side="BOTH"`.
- Since the API may change in a 0.x minor release, you can cap the range at the next minor version, such as `[0.6,0.7)`, and raise it once you've checked the changelog.

### An optional dependency

With `type="optional"`, your mod must load without the exchange. Java loads a class's referenced types when the class is first used, so:

1. Check `ModList.get().isLoaded("variable_economy_exchange")` before touching anything of the exchange's.
2. Keep every reference to its types (the event type, lambdas) in a class of its own that is loaded only after that check passes.
3. Register your listeners from that class with `NeoForge.EVENT_BUS.addListener`, not with `@EventBusSubscriber`: NeoForge loads a subscriber class whether or not the mod is there.

```java
// In your mod's constructor:
if (ModList.get().isLoaded("variable_economy_exchange")) {
    ExchangeListeners.register();
}

// ExchangeListeners.java, loaded only when the exchange is installed:
final class ExchangeListeners {
    static void register() {
        NeoForge.EVENT_BUS.addListener((ExchangeTradeEvent event) -> {
            // ...
        });
    }
}
```

Use one class per Variable Economy add-on you touch, each behind its own check. [Core's add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md) has a full worked example set up this way.

## Events

The exchange posts its event on `NeoForge.EVENT_BUS` (the game bus). Amounts are in bits.

**Exchange** (`variableeconomy.exchange.api.event`):

| Event | Fires | Fields (getters) |
| --- | --- | --- |
| `ExchangeTradeEvent` | Two players traded on the player exchange: once per fill of an order (an order that fills against three others posts three, and a player filling one picked order posts one), and once per purchase from a listing. | `getServer`, `getBuyer`, `getSeller` (UUIDs), `getItem` (one unit as an `ItemStack`, a new copy each call; a listing's keeps its components), `getQuantity`, `getUnitPrice`, `getValue` (what the seller was paid), `getTax` (what the buyer paid on top, destroyed), `getBuyerPaid` (value and tax), `isFromListing` |

The exchange is off by default, so its event fires only on servers that turn it on.

- **What it means.** The seller has been paid `getValue()`, or is owed it if the bank couldn't be reached; the buyer paid `getBuyerPaid()`. The bought units are in the buyer's collection box. Either player may be offline.
- **The price.** `getUnitPrice()` is the waiting order's price for an order fill (the trade happens at the price of the order that was already on the book), or the listing's price.
- **The tax.** A buy order's tax is rounded up once over all it buys, so the taxes of its fills add up to that.
- **Posted after the change is complete.** Money and items have moved and the saved data is updated. When one action changes several things (an order filling against several others), its events are collected and posted together after the last change, never in the middle: an incoming order's events are posted once it's on the book, and a fill's or a listing purchase's once the units are in the buyer's box.
- **Balance events wait for the end of the tick.** So an `ExchangeTradeEvent` comes before the accounts' `BalanceChangedEvent` (core's event).
- **Never cancellable.** To stop an item being traded, lock it with core's item lock rules (`CoreApi.itemGate()`): the exchange hides a locked item from that player and refuses orders, purchases and fills for it.
- **Only for changes that happened**, and always on the server thread, so a listener may call core's API.
- **A listener that throws is logged and ignored**, so it can't undo or interrupt the exchange's work. Listeners after it on the same event may then not run.
- A listener that moves money through the API causes further events. Guard against looping.

To subscribe, add a listener:

```java
NeoForge.EVENT_BUS.addListener((ExchangeTradeEvent event) -> { /* ... */ });
```

## What the exchange does with core's API

These come from core's API, and are described in [core's add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md):

- **Money.** Every payment goes through core's `Money`: the money a buy order holds is charged when it's posted and paid out as it fills or closes; the tax and the posting fee are destroyed. Its reasons are in the exchange's own namespace, `variable_economy_exchange`, and write no audit line; their paths aren't part of the API.
- **Net worth.** The exchange registers a net-worth contributor for the money its open buy orders hold, so it counts on the leaderboard, and an account holding one can't be deleted.
- **Item locks.** The exchange respects every lock, ProgressiveStages' and add-ons' alike.
- **Coins.** `CoreApi.isCoin` items are never traded on the exchange.

## Changelog

- **0.6.1 (2026-11-17):** the exchange is its own repository and release; nothing in its API changed.

- **0.6.0 (2026-11-14):** breaks every add-on. The mod is renamed from Dynamic-Economy to Variable Economy and split into four mods; [core's add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md#changelog) lists the whole change. For the exchange:
  - the exchange is a separate optional mod, `variable_economy_exchange`, with its own artifact, `exchange`. Check its mod ID before using its package.
  - its blocks, items, menus, packets and money reasons are in its own namespace, `variable_economy_exchange`.
  - it has its own config file and command root: its commands moved to `/exchange`, its admin commands to `/exchange admin`.
  - `ExchangeTradeEvent` moves to `variableeconomy.exchange.api.event`.
  - `ExchangeTradeEvent.getTax()` is now paid by the buyer on top of `getValue()`, which the seller gets whole; `getBuyerPaid()` is new.
  - Amounts are `long` throughout.
- **0.5.0:** first version of the API, as Dynamic-Economy, before the split.
