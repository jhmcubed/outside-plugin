# Outside, the RuneLite plugin

What you wear and carry, live on your Outside page: your equipment, inventory, looting bag, rune pouch and Dizana's quiver, your hitpoints, prayer, run and special, the prayers you have on, and every skill's level and experience. It sends them to your outstation's channel on the relay, the same one your transmission and its chat go through, and your page draws them beside them: live while you play, as last seen while you're away.

It works with or without the outstation program. With both, your page has your transmission, its chat and your loadout together. With only the plugin, it has your loadout.

## Setting it up

1. Install **Outside** from the Plugin Hub (RuneLite's wrench icon, then Plugin Hub; not there yet: see "Trying it before the Hub" below).
2. Log in, open the **Outside** side tab (a lime ✼) and press **Make my Outside page** (11/15/2026). Your page is made under the name you're logged in as, at outside.wiki/<your name>, and its station key goes into the plugin's settings by itself. Until it's proven yours, the page marks the name **unproven**: gain any experience, wait a minute, then log out, and within a few minutes the official hiscores show what the plugin reported before they did, which proves it (docs/protocol.md, "Proving a name"). The side tab says which it is. A name someone else claimed first but never proved can be taken back: the side tab says so, you gain experience and log out, and once the hiscores prove it yours the page is yours and their key stops working (docs/protocol.md, "Taking a name back"). A proven name, or one an outstation or invite vouches for, can't be taken.
   - Have an outstation or a friend's invite instead? Paste its **station key** (it starts `outstation1:`) in the plugin's settings.
   - **More than one character** (11/15/2026): each has a page and a key of its own. A page made in the side tab keeps its key with that character's account, so logging into another shows that one's page (or the button to make one). Nothing is sent on a key whose page isn't the character logged in: an alt never shows on a main's page, even with the main's key in the settings.
3. The game's chat says "your outstation (name) is receiving what you wear and carry", with your page's address.

## Its side tab (11/15/2026)

The Outside tab in RuneLite's side bar (a lime ✼): whose page it is and **Open my page**, **Make my Outside page** and whether it's proven yours, **Refresh**, and **Copy my station key** (for the outstation program, which transmits to the same page with the same key).

## What it sends, and only that

**Making your page** (only when you press Make my Outside page; 11/15/2026): your character's name and your account hash (the number RuneLite gives plugins for your account, as RuneProfile's and Dink's plugins send it), once, to Jace's relay on Cloudflare. The relay keeps only a hash of the account hash, for proving the page is yours, and shows it to nobody (docs/protocol.md, "Claiming a name").

To your relay (Jace's, on Cloudflare, unless your key says another), over one connection, and only while you're logged in:
- your character's name, your world, and your account type (normal, ironman…)
- **worn and carried** (one switch): what's in each equipment slot and inventory slot; the looting bag's contents once you've looked in it; the seed box's and the chugging barrel's once you've opened them (11/14/2026); the rune pouch's runes; Dizana's quiver's ammunition; and each of those items' name and Grand Exchange price, once each
- **skills** (one switch): each skill's level, boosted level and experience
- **quests, diaries and combat achievements** (one switch, on; 11/15/2026): your quest points, which quests you've done and which you've started (RuneLite's own quest list), each Achievement Diary area's tiers and how many of its tasks you've done, and your combat achievements' points and tasks done in each tier
- **my slayer task** (one switch, on; 11/15/2026): the monster you're assigned, how many are left of how many, the area if your master named one, your slayer points and your task streak, read as RuneLite's own slayer plugin reads them
- **hitpoints, prayer, run and special** (one switch), and which prayers are on
- **item charges** (one switch, on; 11/14/2026): each worn or carried item's charges, as the game tells you them in its chat when you check one or as it's used (only the number, by the item; kept on this RuneLite's profile so they're known after a restart)
- that you've logged out
- **levels, kills, drops and log slots** (one switch, on; 11/15/2026): your levels as you gain them, kill counts, collection log slots, pets, clue counts, quests finished, diary tiers, combat tasks and slayer tasks (since 11/15/2026) and drops worth at least "Drops worth at least" (100,000 coins to begin with), read from the game's own messages and stats as Dink reads them, in Dink's shape, to your relay's Dink route, for your page's event log and outside.wiki's feed. Not from the Wilderness, a PvP area or a PvP or Deadman world, as nothing else is. While your outstation transmits, the relay drops these (Dink's come through it then).

Only what changed goes, at most once a game tick (hitpoints and the like alone, at most every 3 seconds). In the Wilderness, a PvP area, or on a PvP or Deadman world, nothing more goes till you're out, so nobody watching your page sees what you risk; your page says it's paused. Ferox Enclave, the Wilderness's safe town, is safe: it keeps going there. Never: your screen, your chat (only an item's charges, read from the game's own lines), where you are, your bank, other players, anything you type. Your page shows what was sent to anyone who opens it.

## Credits

The Achievement Diaries' completion rule (Karamja's first three tiers at 2) and the game's diary summary scripts are read as Dink reads them (github.com/pajlads/DinkPlugin, its AchievementDiary and DiaryNotifier), BSD 2-Clause, Copyright (c) 2022, Jake Barter, and the slayer task as RuneLite's own slayer plugin reads it (github.com/runelite/runelite, its SlayerPlugin: the task, boss and area tables and their columns), BSD 2-Clause, Copyright (c) 2017, Tyler, and (c) 2018, Shaun Dreclin. Both under the licence below, with their own names.

Ferox Enclave's bounds (`OutsidePlugin.FEROX`) are the Shortest Path plugin's (github.com/Skretzo/shortest-path, its `WildernessChecker.java`, FEROX_ENCLAVE_1 to 5; BSD 2-Clause), five rectangles of the town inside its barriers: facts about the game's map, credited all the same.

The way an instance is placed where its template lies (`WorldPoint.fromLocalInstance`), for telling the Wilderness apart, is RuneLite's own Discord plugin's (Copyright (c) 2018, Tomas Slusny and PandahRS, BSD 2-Clause like the rest of RuneLite). On a boat, the player's place is read as RuneLite's `Perspective.localToMinimap` reads a point on a boat (`WorldEntity.transformToMainWorld`), and as the Sailing plugin (LlemonDuck/sailing, BSD 2-Clause) does.

## Trying it before the Hub

On a PC with RuneLite installed and Java 11 or newer:

```
py run.py client
```

(from the plugin's own folder: the root of jhmcubed/outside-plugin, or `plugin/` in jhmcubed/outstation)

It compiles the plugin against your installed RuneLite and opens RuneLite in developer mode with Outside loaded. A Jagex account needs the launcher's `--insecure-write-credentials` step once before that RuneLite can log in.

## For developers

- `build.gradle`: RuneLite's example-plugin template, as the Plugin Hub builds it (`gradle build` runs the tests).
- `src/main/java/wiki/outside/`:
  - `OutsidePlugin.java`: reading the game each tick, the connection to the relay
  - `Kit.java`: the fields, what changed, which items still need naming; kept apart from RuneLite so it's tested
  - `Attach.java`: the station key read back into the relay, the channel and the key
  - `OutsideConfig.java`: the settings
- The relay's side: `docs/protocol.md`, "The game".

## For the Hub's review

Checked against RuneLite's own rules (the plugin-hub README, and the wiki's "Plugin Hub Review" and "Rejected or Rolled Back Features", read 11/07/2026). `py hub_check.py` (from the plugin's folder) checks the source for every forbidden feature and API before each submission or update.

**Precedents** (11/09/2026): [`docs/hub-precedents.md`](../docs/hub-precedents.md) goes through every piece of information this plugin reads or sends and names the Plugin Hub plugins (and RuneLite's own) that already read or send the same, with links to the exact lines, where this plugin is stricter, the Hub's written rules as they stand, and what the Hub requires of the licence and the repository (its own repository, with `runelite-plugin.properties` and a `LICENSE` at the root).

- **No forbidden features:** no reflection, JNI or JNA, other programs, code downloaded at runtime, `Desktop`/`LinkBrowser.open`, `Thread.sleep`/`interrupt`, `menuAction`, `java.io` files, `KeyboardFocusManager`, or network clients other than OkHttp. RuneLite's own Gson, OkHttp, executor and client thread are injected, never made. The package isn't `net.runelite`.
- **No new dependencies:** `build=standard`, nothing outside runelite-client.
- **Network IO** (a sensitive API, allowed): one WebSocket to the relay named in the station key the player pastes in, through RuneLite's OkHttp. It sends only the player's own game, as listed above; it reads nothing back.
- **The third-party warning:** on the "Send to my outstation" setting (`@ConfigItem(warning = …)`), and for the Hub's manifest (below).
- **Jagex's rules and the rejected list:**
  - It changes nothing in the game or RuneLite: no overlays, no menu entries, no input, no chat typed.
  - It reads only the player's own character: nothing about other players ("crowdsourcing" is on the rejected list).
  - Location: none. It reads where the player is only to tell whether they're somewhere dangerous, and never sends it.
  - It runs no server and exposes nothing over HTTP from the PC ("plugins which expose player information over HTTP" are rejected): it sends to the player's own outstation's relay, as RuneProfile and Wise Old Man's plugins send to theirs.
  - It's free, with nothing behind a paid service.
  - In the Wilderness, a PvP area, or on a PvP or Deadman world, it stops sending, so nobody watching can see what a player risks. Not in Ferox Enclave, the Wilderness's safe town, where no one can take what they carry: `OutsidePlugin.held`.

## Where it lives (11/10/2026)

The plugin is written here, in `plugin/` of jhmcubed/outstation, beside the relays it talks to (their tests check its lists against theirs: the skills, bosses, raids, minigames and landing sites). jhmcubed/outside-plugin is the version on the Plugin Hub, written from here by `hub_cut.py` (11/16/2026): everything but the parts not submitted yet, each brought in by an update (the script's header lists them: following and the globe's switches, the delve board, the year, the model, the bank, the page's chat and emotes). Its root holds `runelite-plugin.properties` and the BSD 2-Clause `LICENSE`, as the Hub asks. To publish a change once it's merged here, from a checkout of outside-plugin beside this one:

```
python3 plugin/hub_cut.py ../outside-plugin
```

then commit and push outside-plugin. Change the plugin here, never in outside-plugin, or the next cut won't carry it. `hub_cut.py` itself, and this repository's own notes, never go there.

The manifest, `plugins/outside` in runelite/plugin-hub (the Hub builds outside-plugin's root):

```
repository=https://github.com/jhmcubed/outside-plugin.git
commit=<the full commit hash>
warning=This plugin submits your character's name and account hash when you make your page, your levels, kill counts, collection log slots, pets, clue counts, quests, diary tiers, combat and slayer tasks and valuable drops, and your equipment, inventory, looting bag, rune pouch, item charges, skills, vitals, quests, achievement diaries, combat achievements and slayer task, and your IP address, to your outstation's relay, a server not controlled or verified by the RuneLite developers; your Outside page and outside.wiki show them publicly.
```
