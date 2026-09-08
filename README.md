# Perphet

**Perphet** is a client-side [Fabric](https://fabricmc.net/) mod for **Hypixel Disasters** that creates a secondary game based around guessing how many people survive the match! It records data and generates predictions itself, in addition to allowing other people to use !dpred [guess] to join in on the fun even if they don't have the mod. 

It is built around historical data from completed Disasters games. As each wave rolls, Perphet compares the current game with previous games and produces an updated end-of-game survivor prediction.

Perphet is passive: it reads information already visible to the Minecraft client. It does not automate movement, clicks, chat, commands, or gameplay packets.

> Perphet is an independent project and is not affiliated with or endorsed by Hypixel.

## Features

- Live end-of-game survivor predictions after each disaster wave
- Five selectable prediction models:
  - **KNN**
  - **Wave Avg**
  - **Exact History**
  - **Disaster %**
  - **Chat DEDS**
- Detailed Chat DEDS breakdowns by disaster
- Wave-, start-wave-, and map-specific historical modes
- Fall, void, and unattributed-loss modeling
- Misc-death correlation adjustment
- Sample-size and uncertainty diagnostics
- Community prediction leaderboard using `!dpred`
- Ticket-to-Ride-style prediction loss score
- Historical **“Better than X% of games”** percentile
- Optional deaths and recorder-status overlays
- Per-wave total-health tracking
- Per-player health tracking for up to 16 fixed tab-list slots
- Local CSV/JSONL game history
- Commands for disabling logging or deleting the latest saved game
- Sanitized gameplay dataset included in the repository

## Requirements

- **Minecraft 26.2**
- **Fabric Loader 0.19.3+**
- **Fabric API**
- **Java 25+**
- **Mod Menu 20.0.1+** recommended for the settings screen

## Installation

### Modrinth

Once Perphet is available on [Modrinth](https://modrinth.com/), install it through the Modrinth App and make sure Fabric API is installed in the same profile.

### Manual installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Download the latest Perphet `.jar` from [GitHub Releases](https://github.com/perpetualyt/perphet/releases).
4. Put the jar in your Minecraft `mods` folder.
5. Launch Minecraft using the Fabric profile.

[Mod Menu](https://modrinth.com/mod/modmenu) is optional, but recommended for changing Perphet settings in-game.

# Prediction models

Every prediction model has the same output target:

> **How many players will be alive at the end of the game?**

The models differ in what historical information they use.

## KNN — distance-weighted historical games

KNN is Perphet's general-purpose empirical similarity model.

The idea is simple: **games that currently look similar to this game should receive more influence on the prediction.** Instead of trying to assign a separate death rate to every disaster, KNN compares the entire current game state against completed historical games and averages their final survivor counts.

For every compatible historical game, Perphet calculates:

```text
distance =
    0.30 × |current players - historical players at this wave|
  + 0.08 × |starting players - historical starting players|
  + 3.00 × disaster-set Jaccard distance
  + 0.35 × map mismatch
```

### What goes into the distance?

**Current players.**  
A historical game is more relevant if approximately the same number of players were alive when the corresponding wave rolled.

**Starting players.**  
Games that began with similar lobby sizes receive a small additional preference.

**Disasters seen so far.**  
At W1, the model compares W1 disasters. At W2, it compares the cumulative set of disasters seen through W2. At W3, it compares the complete observed disaster set.

The disaster-set term uses Jaccard distance:

```text
Jaccard distance = 1 - |intersection| / |union|
```

So identical observed disaster sets have distance `0`, while increasingly different sets receive a larger penalty.

**Map.**  
A historical game on the same map receives no map penalty. A different map adds `0.35` to the distance.

### How are historical games weighted?

Each compatible historical game receives:

```text
weight = 1 / (0.35 + distance)^2
```

The predicted final survivor count is then:

```text
prediction =
    Σ(weight × historical final survivors)
    ----------------------------------------
                Σ(weight)
```

This means a very close historical match can matter much more than a distant one.

Despite the name **KNN**, the current implementation does not cut the history down to a fixed `k` nearest games. It uses **all compatible completed games**, with the inverse-square weighting making distant games contribute much less.

KNN requires at least **3 compatible historical rows**. Historical rows must use the same recorder/player-count basis as the current game.

### Example

Suppose W2 rolls with:

```text
12 players alive
15 starting players
Flood + Tornado + Lightning observed so far
Map: Library
```

A previous Library game that also had 12 players alive and nearly the same disaster set will get a large weight.

A game with 6 players alive, a very different disaster set, and a different map still can contribute, but its weight will be much smaller.

### Strengths

- Uses the **whole observed game state** rather than estimating each disaster independently.
- Naturally captures some interactions between disasters because it compares complete historical games.
- Uses map directly.
- Works without needing reliable death-message attribution for every disaster.

### Limitations

- Similar-looking games can still have very different outcomes.
- Rare disaster combinations may have no genuinely close historical neighbors.
- The map term is only a mismatch penalty; it does not explicitly learn a separate map mortality coefficient.
- Because it predicts directly from historical final survivor counts, it is less interpretable than Chat DEDS.

---

## Wave Avg

Wave Avg ignores disaster identity and asks how many players historically die in each remaining wave.

At W1 it subtracts average W1, W2, and W3 losses. At W2 it subtracts average W2 and W3 losses. At W3 it subtracts average W3 losses.

With **percentage-based averages** enabled, the model uses historical fractions of players killed rather than raw death counts.

---

## Exact History

Exact History uses the actual disaster combinations that have rolled.

For the current wave it looks for historical examples of the same disaster combination, using order-insensitive matching. If an exact current-wave combination is unavailable, it backs off to broader wave-average behavior.

Future waves that have not rolled yet still have unknown disaster identities, so they are represented by historical future-wave averages.

---

## Disaster %

Disaster % treats each observed disaster as a feature in a regularized regression model.

The target is whole-game mortality:

```text
total deaths / starting players
```

The model fits disaster coefficients jointly, so each coefficient estimates the association of that disaster with mortality while accounting for the other observed disasters in the training data.

Separate prediction-stage fits avoid using disasters from future waves that have not rolled yet.

---

# Chat DEDS — disaster-attributed expected deaths

**Chat DEDS is Perphet's most explicit death model.**

Instead of asking only whether a historical game looked similar, it tries to answer:

> **How many deaths should each known disaster cause in each wave?**

It uses the mod's recorded per-disaster death-message attribution, plus separately tracked Fall, Void, and Misc losses.

The current-wave expected deaths are:

```text
DEDS =
    Σ expected deaths from each seen disaster
  + average Fall deaths for this wave
  + average Void deaths for this wave
  + adjusted Misc deaths for this wave
```

The displayed per-disaster values are therefore components of the total expected loss, rather than independent predictions of final survivors.

## Direct disaster deaths

When Perphet recognizes a death message as belonging to a disaster, that death is stored in a wave-specific column such as:

```text
wave_1_deaths_flood
wave_2_deaths_flood
wave_3_deaths_flood
```

Chat DEDS uses these **wave-specific attributed deaths**, not the whole-game `deaths_<disaster>` total, when estimating DEDS.

Each disaster is estimated separately and the means are added together. At the moment, Chat DEDS does **not** fit explicit pairwise disaster-synergy terms.

That means:

```text
expected deaths from A + B
≈ expected deaths from A
+ expected deaths from B
```

rather than learning a special `A × B` interaction.

## Persistent and delayed hazards

A disaster is not assumed to become harmless simply because its sidebar entry expires.

Once a disaster has appeared, Perphet can continue projecting deaths from it into later waves. This matters for hazards whose consequences can persist beyond the wave in which they first rolled.

For example, a W1 **Floor Is Lava** or **Flood** sample can contribute to the estimate of deaths occurring in W2 or W3.

This is also why **start wave** and **death wave** are treated as two different pieces of information in HYPER mode.

## Specificity modes

Chat DEDS can decide how narrowly to select historical samples.

| Mode | Same target/death wave? | Same disaster start wave? | Same map? |
| --- | :---: | :---: | :---: |
| **OFF** | No | No | No |
| **ON** | Yes | No | No |
| **HYPER** | Yes | Yes | No |
| **HYPER-HYPER** | Yes | Yes | Yes |
| **AUTO** | Depends on disaster | Depends on disaster | No |

### OFF

OFF pools active samples for a disaster across W1, W2, and W3.

Because player counts can differ substantially between waves, each historical sample is converted to a mortality fraction:

```text
historical attributed deaths
-----------------------------
historical players at wave roll
```

That fraction is then rescaled to the current live player count.

Example:

```text
historical sample:
4 Flood deaths / 10 players = 40%

current game:
15 players alive

estimated Flood deaths:
0.40 × 15 = 6
```

OFF therefore has the largest potential sample size, but gives up wave-specific information.

### ON

ON uses only attributed deaths from the **same target wave number**.

If Perphet is predicting W2 Flood deaths, it uses historical `wave_2_deaths_flood` samples from games in which Flood was still relevant in W2.

It does not care whether Flood originally started in W1 or W2.

This makes ON more specific than OFF while retaining more data than HYPER.

### HYPER

HYPER separates two concepts:

1. **When the disaster started**
2. **Which wave's deaths are being predicted**

Both must match the historical trajectory.

For example, suppose Flood rolls in W1 and the mod is projecting Flood's W3 deaths.

HYPER uses:

```text
historical games where Flood STARTED in W1
```

but reads:

```text
wave_3_deaths_flood
```

from those games.

It does **not** search for Floods that started in W3.

This is useful for persistent hazards because the same disaster can have a very different death trajectory depending on how long it has already been active.

### HYPER-HYPER

HYPER-HYPER adds **map specificity** to HYPER.

For the same example:

```text
current map: Library
Flood started: W1
target deaths: W3
```

HYPER-HYPER uses only historical rows satisfying all three:

```text
map = Library
Flood start wave = W1
death wave = W3
```

The map restriction applies to the row-derived Chat DEDS history used for direct disaster estimates, future-wave candidates, and the ordinary Fall/Void/Misc baselines.

There is intentionally no cross-map fallback inside HYPER-HYPER. A highly specific combination can therefore have a very small sample size.

### AUTO

AUTO uses the ordinary **ON** behavior for most disasters.

For persistent hazards that especially benefit from trajectory conditioning—currently **Flood** and **The Floor Is Lava**—AUTO uses **HYPER** behavior instead.

## What happens with tiny sample sizes?

Chat DEDS intentionally has **no global minimum-support cutoff**.

If a very specific disaster estimate has only one historical example, Perphet can still use it and expose the low sample size rather than silently substituting a broader model.

If no matching direct-death sample exists, that direct contribution defaults to zero instead of forcing the whole prediction to `N/A`.

This makes the `SIZE`/sample-support diagnostics important when using HYPER or HYPER-HYPER.

## Fall, Void, and Misc

Not every player loss is directly attributable to a disaster death message.

Perphet records separate wave-level counts for:

- **Fall**
- **Void**
- **Misc**

`Misc` comes from scoreboard player loss that was not assigned to the explicit tracked death categories. It can therefore include quits and other unattributed losses.

Chat DEDS learns average Fall, Void, and Misc values for each wave and adds them to the direct disaster predictions.

There is no extra catch-all residual added afterward.

## Misc correlation adjustment

A generic wave-average Misc value can miss indirect effects. Some disasters may make otherwise unattributed losses more or less common without directly appearing in the death message.

Perphet therefore has a separate **Misc correlation adjustment**.

The model first removes the ordinary wave baseline:

```text
residual Misc =
observed Misc - average Misc for that wave
```

It then fits all disaster/age indicators jointly:

```text
residual Misc ≈
Σ beta[disaster, age]
```

where:

```text
age 0 = the wave the disaster started
age 1 = one wave later
age 2 = two waves later
```

The fit uses **ridge regularization with λ = 1**. The coefficients are measured in expected Misc deaths, so they can be added directly to the ordinary Misc baseline.

The cache updates only at **20-game milestones**:

```text
20, 40, 60, 80, ...
```

This keeps the fitted coefficients from changing after every individual game.

In HYPER-HYPER, the row-derived Chat DEDS history is map-specific, but this separate Misc-correlation fit is currently global rather than per-map.

## How END is predicted

DEDS is the expected loss in the **current wave**, but the green `G` prediction is the expected number of players alive at the **end of the game**.

At W3 this is straightforward:

```text
END =
current players
- current W3 DEDS
```

At W1 and W2, future disaster identities are not fully known yet.

Perphet handles the remaining game in three parts:

**1. Carry-over from disasters already seen.**  
A known disaster can continue contributing expected deaths in later waves. The same OFF/ON/HYPER/HYPER-HYPER logic is used when projecting those later deaths.

**2. Disasters that have not rolled yet.**  
Their identities are unknown, so Perphet averages historical sets of disasters that newly appeared in the corresponding future wave. Candidate future histories containing disasters already seen in the current game are excluded when possible because a disaster cannot roll twice. If that leaves no candidates, the model falls back to the broader future-wave distribution.

**3. Future Fall, Void, and Misc.**  
The corresponding historical wave averages are added, including the known-disaster Misc-correlation adjustments that can be carried forward.

Conceptually:

```text
W1 END =
current players
- W1 expected loss
- expected W2 remaining loss
- expected W3 remaining loss

W2 END =
current players
- W2 expected loss
- expected W3 remaining loss

W3 END =
current players
- W3 expected loss
```

The raw fractional prediction is retained internally for scoring even though the normal survivor guess is displayed as an integer.

## Reading the Chat DEDS diagnostics

The overlay is intended to show both the prediction and how much evidence supports it.

- **DEDS** — expected total player loss for the current wave
- **per-disaster death values** — the direct attributed components of DEDS
- **Fall / Void / Misc** — non-direct components
- **SIZE** — historical support for the disaster estimates used
- **STDEV** — historical spread/uncertainty in the current-wave estimate
- **G** — predicted final survivors

Small support is not hidden. A precise-looking mean with `N=1` should be treated very differently from the same mean with a large historical sample.

---

# Community predictions

Players can submit a prediction in visible chat with:

```text
!dpred 5
```

or:

```text
!disasterpred 5
```

The number is their predicted final survivor count.

Perphet reads these messages and displays a local prediction leaderboard. The mod does not send prediction messages itself.

# Local commands

These commands are handled entirely by the client.

## Toggle game-stat logging

```text
/dpred logs off
/dpred logs on
/dpred logs status
```

The longer `/disasterpred` alias works as well.

If logging is disabled for a game, that game is excluded from the saved game dataset.

## Delete the last saved game

```text
/dpred delete-last
```

or:

```text
/disasterpred delete-last
```

This removes the most recently saved game and rebuilds the local model dataset so the deleted game no longer affects predictions.

# Health tracking

Perphet records modeled total player health at each wave using the tab list.

It also tracks up to 16 fixed tab-list slots:

```text
wave_1_player_01_health ... wave_1_player_16_health
wave_2_player_01_health ... wave_2_player_16_health
wave_3_player_01_health ... wave_3_player_16_health
```

The player order is captured near the start of the game and kept fixed, so the same slot refers to the same player throughout that game.

If a tab-health snapshot is incomplete or inconsistent with the observed player count, Perphet rejects the snapshot rather than saving a misleading partial health total.

# Data

Completed games are stored locally and become training data for future predictions.

The repository also includes:

```text
data/games.csv
```

This is a sanitized gameplay dataset for analysis and reproducibility. Direct game identifiers, timestamps, and the Hypixel header are removed from the repository copy.

# Configuration

With Mod Menu installed, Perphet exposes its settings through the normal **Mods** menu.

Options include:

- prediction model
- Chat DEDS specificity
- percentage-based averages
- recorder handling
- overlays
- prediction windows
- percentile display
- diagnostics

# Building from source

```bash
git clone https://github.com/perpetualyt/perphet.git
cd perphet
./gradlew build
```

The built jar is placed in:

```text
build/libs/
```

# License

Perphet is licensed under the [MIT License](LICENSE).
