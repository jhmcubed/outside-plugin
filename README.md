# Outside, the RuneLite plugin

Your gear, stats, quests, diaries and slayer task on your own page at outside.wiki: your equipment, inventory, looting bag, seed box, chugging barrel, rune pouch and Dizana's quiver, each skill's level and experience, your quests, Achievement Diaries, combat achievements and slayer task. They go when you press **Update my page** in the plugin's side tab, and by themselves once an hour while you're logged in. And what you're doing, as RuneLite's own Discord plugin shows it in your Discord status (the skill you're training, the area you're in by name), so your page and outside.wiki's globe can show it.

## Setting it up

1. Install **Outside** from the Plugin Hub (RuneLite's wrench icon, then Plugin Hub; not there yet: see "Trying it before the Hub" below).
2. Log in, open the **Outside** side tab (a lime ✼) and press **Make my Outside page** (11/15/2026). Your page is made under the name you're logged in as, at outside.wiki/<your name>, and its station key goes into the plugin's settings by itself. Until it's proven yours, the page marks the name **unproven**: gain any experience, press **Update my page**, wait a minute, then log out, and within a few minutes the official hiscores show what the plugin reported before they did, which proves it (docs/protocol.md, "Proving a name"). The side tab says which it is. A name someone else claimed first but never proved can be taken back: the side tab says so, you gain experience, press Update my page, wait a minute and log out, and once the hiscores prove it yours the page is yours and their key stops working (docs/protocol.md, "Taking a name back"). A proven name, or one an outstation or invite vouches for, can't be taken.
   - **More than one character** (11/15/2026): each has a page and a key of its own. A page made in the side tab keeps its key with that character's account, so logging into another shows that one's page (or the button to make one). Nothing is sent on a key whose page isn't the character logged in: an alt never shows on a main's page, even with the main's key in the settings.
3. Its first update goes as the page is made. After that, press **Update my page** whenever you want your page to show what you have now (once a minute at most); it also updates by itself every hour you're logged in. Your page says when it was last updated.

## Its side tab (11/15/2026)

The Outside tab in RuneLite's side bar (a lime ✼): whose page it is and **Open my page**, **Make my Outside page** and whether it's proven yours, **Update my page** (11/16/2026) and when it was last updated, and **Refresh**.

Under **Who sees it** (11/16/2026): whether the page is open to anyone (and on outside.wiki's globe while you play) or friends only, **Make it friends only** / **Open it to anyone**, **Copy the friends' link**, and **Take my page down** (after a confirmation: everything on the page goes and the name is free again).

## What it sends, and only that

**Making your page** (only when you press Make my Outside page; 11/15/2026): your character's name and your account hash (the number RuneLite gives plugins for your account, as RuneProfile's and Dink's plugins send it), once, to Jace's relay on Cloudflare. The relay keeps only a hash of the account hash, for proving the page is yours, and shows it to nobody (docs/protocol.md, "Claiming a name").

To Jace's relay on Cloudflare, only while you're logged in, and only when you press **Update my page**, once an hour (after each hour logged in since the last), and once as your page is made: each time, everything of this that's switched on, at once:
- your character's name and your account type (normal, ironman…)
- **worn and carried** (one switch): what's in each equipment slot and inventory slot; the looting bag's contents once you've looked in it; the seed box's and the chugging barrel's once you've opened them (11/14/2026); the rune pouch's runes; Dizana's quiver's ammunition; and each of those items' name and Grand Exchange price, once each
- **skills** (one switch): each skill's level, boosted level and experience
- **quests, diaries and combat achievements** (one switch, on; 11/15/2026): your quest points, which quests you've done and which you've started (RuneLite's own quest list), each Achievement Diary area's tiers and how many of its tasks you've done, and your combat achievements' points and tasks done in each tier
- **my slayer task** (one switch, on; 11/15/2026): the monster you're assigned, how many are left of how many, the area if your master named one, your slayer points and your task streak, read as RuneLite's own slayer plugin reads them
- **item charges** (one switch, on; 11/14/2026): each worn or carried item's charges, as the game tells you them in its chat when you check one or as it's used (only the number, by the item; kept on this RuneLite's profile so they're known after a restart)
- that you've logged out (if anything went this session)
- **what I'm doing, as Discord shows it** (one switch, on; 11/16/2026): what RuneLite's own Discord plugin shows in your Discord status, and no more: the skill you're training (from your own experience going up; for 5 minutes after the last of it, and gone when a new area loads, as Discord has it) and the area you're in by Discord's own name and group (a boss's, a city, a dungeon, a minigame, a raid or a region: its list of 398, read where each scene loads), with one fixed point of that area for outside.wiki's globe to light you at. Sent as it changes, once a minute at most, and with each update. Never your tile or your world; in the Wilderness, a PvP area or on a PvP or Deadman world, neither the skill nor the area (said at once, as an area that isn't on Discord's list). `Presence.java`, its table written from RuneLite's `DiscordGameEventType` by `discord_areas.py` (in jhmcubed/outstation).

Nothing goes between updates but what you're doing as it changes. An update is never taken in the Wilderness, a PvP area, or on a PvP or Deadman world: pressed there, it waits, and goes as you leave; the hourly one waits the same way. Ferox Enclave, the Wilderness's safe town, is safe. Never: your screen, your chat (only an item's charges, read from the game's own lines), your tile or your world, your hitpoints or prayer, your bank, other players, anything you type. Your page shows what was sent to anyone who opens it, with when it was last updated.

## Credits

The Achievement Diaries' completion rule (Karamja's first three tiers at 2) and the game's diary summary scripts are read as Dink reads them (github.com/pajlads/DinkPlugin, its AchievementDiary and DiaryNotifier), BSD 2-Clause, Copyright (c) 2022, Jake Barter, and the slayer task as RuneLite's own slayer plugin reads it (github.com/runelite/runelite, its SlayerPlugin: the task, boss and area tables and their columns), BSD 2-Clause, Copyright (c) 2017, Tyler, and (c) 2018, Shaun Dreclin. Both under the licence below, with their own names.

Ferox Enclave's bounds (`OutsidePlugin.FEROX`) are the Shortest Path plugin's (github.com/Skretzo/shortest-path, its `WildernessChecker.java`, FEROX_ENCLAVE_1 to 5; BSD 2-Clause), five rectangles of the town inside its barriers: facts about the game's map, credited all the same.

What you're doing (`Presence.java`) is RuneLite's own Discord plugin's, matched on purpose: its areas' names, groups and region ids (`DiscordGameEventType`), and the way it reads them (the region where each scene loads, an instance's where its template lies, `WorldPoint.fromLocalInstance`; the skill from experience drops, its 5-minute timeout, Slayer and Fishing first; `DiscordPlugin`, `DiscordState`), Copyright (c) 2018, Tomas Slusny and PandahRS, BSD 2-Clause like the rest of RuneLite. On a boat, the player's place is read as RuneLite's `Perspective.localToMinimap` reads a point on a boat (`WorldEntity.transformToMainWorld`), and as the Sailing plugin (LlemonDuck/sailing, BSD 2-Clause) does.

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
  - `OutsidePlugin.java`: reading the game, "Update my page" and the hourly sync, the posts to the relay
  - `Kit.java`: the fields and the items in them; kept apart from RuneLite so it's tested
  - `Presence.java`: what you're doing, as RuneLite's Discord plugin shows it, and its areas (written by `discord_areas.py`)
  - `Attach.java`: the station key read back into the relay, the channel and the key
  - `OutsideConfig.java`: the settings
- The relay's side: [`docs/protocol.md`](https://github.com/jhmcubed/outstation/blob/77d8e1df560fe2ac38ef3e14f4afd267e7ffad7c/docs/protocol.md), "The snapshot" and "The game" (jhmcubed/outstation).

## For the Hub's review

Checked against RuneLite's own rules (the plugin-hub README, and the wiki's "Plugin Hub Review" and "Rejected or Rolled Back Features", read 11/07/2026). `py hub_check.py` (from the plugin's folder) checks the source for every forbidden feature and API before each submission or update.

**The reviewer's terms** (runelite/plugin-hub#20811, 11/16/2026): "An 'Update my page' action and an hourly sync should be okay, as should matching Discord activity." This version is built to exactly that: no socket, no external program, nothing live, and what it says of where you are never more than RuneLite's own Discord plugin says.

- **No forbidden features:** no reflection, JNI or JNA, other programs, code downloaded at runtime, `Desktop`/`LinkBrowser.open`, `Thread.sleep`/`interrupt`, `menuAction`, `java.io` files, `KeyboardFocusManager`, or network clients other than OkHttp. RuneLite's own Gson, OkHttp, executor and client thread are injected, never made. The package isn't `net.runelite`.
- **No new dependencies:** `build=standard`, nothing outside runelite-client.
- **Network IO** (a sensitive API, allowed), through RuneLite's OkHttp, to the relay named in the page's key, and only (11/16/2026, the reviewer's "An 'Update my page' action and an hourly sync should be okay, as should matching Discord activity"): a snapshot when the player presses Update my page (once a minute at most) or after each hour logged in; what they're doing as Discord shows it, as it changes (once a minute at most); "logged out"; and the side tab's own asks (make the page, whether it's proven, who sees it, take it down). No socket, nothing every tick, no location but Discord's area name, no hitpoints or prayer, and no other program: it talks to nothing but the relay.
- **The third-party warning:** on the "Send to my Outside page" setting (`@ConfigItem(warning = …)`), and for the Hub's manifest (below).
- **Jagex's rules and the rejected list:**
  - It changes nothing in the game or RuneLite: no overlays, no menu entries, no input, no chat typed.
  - It reads only the player's own character: nothing about other players ("crowdsourcing" is on the rejected list).
  - Location: only what RuneLite's own Discord plugin puts in the player's Discord status, the area by its name (and one fixed point of that area, so the globe can place it), read where each scene loads; never the tile or the world, and nothing at all from the Wilderness or a PvP world. Otherwise it reads where the player is only to tell whether they're somewhere dangerous.
  - It runs no server, and it sends to the Outside relay as RuneProfile's plugin sends to its site: when the player asks, and hourly ("Network IO", above).
  - It's free, with nothing behind a paid service.
  - In the Wilderness, a PvP area, or on a PvP or Deadman world, no update is taken and what they're doing says nothing, so nobody watching can see what a player risks. Not in Ferox Enclave, the Wilderness's safe town, where no one can take what they carry: `OutsidePlugin.held`.

## Where it lives (11/10/2026)

The plugin is written here, in `plugin/` of jhmcubed/outstation, beside the relays it talks to (their tests check its lists against theirs: the skills, bosses, raids, minigames and landing sites). jhmcubed/outside-plugin is the version on the Plugin Hub, written from here by `hub_cut.py` (11/16/2026): everything but the parts not submitted (the script's header lists them: since 11/16/2026, the Hub's answer, the live part (the socket, the kit and vitals every tick, every tie to the outstation) and the field reports; then following and the globe's switches, the delve board, the year, the model, the bank, the page's chat and emotes). Its root holds `runelite-plugin.properties` and the BSD 2-Clause `LICENSE`, as the Hub asks. To publish a change once it's merged here, from a checkout of outside-plugin beside this one:

```
python3 plugin/hub_cut.py ../outside-plugin
```

then commit and push outside-plugin. Change the plugin here, never in outside-plugin, or the next cut won't carry it. `hub_cut.py` itself, and this repository's own notes, never go there.

The manifest, `plugins/outside` in runelite/plugin-hub (the Hub builds outside-plugin's root):

```
repository=https://github.com/jhmcubed/outside-plugin.git
commit=<the full commit hash>
warning=This plugin submits your gear, stats, quests, diaries, slayer task, what you're doing (as Discord shows it), your account hash and your IP address to a 3rd-party server when you press Update my page, and hourly. Your Outside page shows them publicly.
```
