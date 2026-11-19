package wiki.outside;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OutsideConfig.GROUP)
public interface OutsideConfig extends Config
{
	String GROUP = "outside";

	// (11/17/2026, Jace: "organize our plugin settings menu so it's less confusing. use runeprofile as your example":
	// the switches under a few headings, each with a short name; what each does in its description, under the hand)
	@ConfigSection(name = "Your page", description = "Whether this plugin sends to your Outside page at all", position = 0)
	String pageSection = "page";

	@ConfigSection(name = "On your page", description = "What goes on your page, each its own switch", position = 1)
	String shownSection = "shown";

	@ConfigSection(name = "What you're doing", description = "What your page and Outside.wiki's globe say you're doing", position = 2)
	String doingSection = "doing";

	@ConfigSection(name = "Advanced", description = "Your page's key, if you were given one", position = 9, closedByDefault = true)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "stationKey",
		name = "Station key",
		description = "Your page's key (it starts outstation1:): made for you by \"Make my Outside page\" in the Outside"
			+ " side tab and kept with your account, so this stays blank. Paste one here only if you were given one.",
		secret = true,
		position = 0,
		section = advancedSection
	)
	default String stationKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "send",
		name = "Send to my Outside page",
		description = "What's switched on below goes to the Outside relay (on Cloudflare) when you press Update my page in the"
			+ " Outside side tab, and hourly while you're logged in; what you're doing goes as it changes. Your Outside page"
			+ " shows it to anyone, and while you play you show on outside.wiki's globe, unless your page is friends only"
			+ " (the side tab). Off: nothing more is sent, and your gear, stats and progress come off your page.",
		warning = "This submits your gear, stats, quests, diaries, slayer task, what you're doing (as Discord shows it)"
			+ " and your IP address to the Outside relay, a 3rd-party server not controlled or verified by the RuneLite"
			+ " developers, when you press Update my page, and hourly. Your Outside page shows them publicly.",
		position = 0,
		section = pageSection
	)
	default boolean send()
	{
		return true;
	}

	@ConfigItem(
		keyName = "items",
		name = "Gear and inventory",
		description = "Your equipment, inventory, looting bag (once you've looked in it), seed box, chugging barrel, rune pouch and quiver",
		position = 0,
		section = shownSection
	)
	default boolean items()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skills",
		name = "Skills",
		description = "Each skill's level, boosted level and experience",
		position = 1,
		section = shownSection
	)
	default boolean skills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "progress",
		name = "Quests, diaries and progress",
		description = "Your quests (done and started) and quest points, each Achievement Diary's tiers and tasks, your combat"
			+ " achievements' points and tasks by tier, and your collection log's count (and the slots the game's chat says"
			+ " you've added since the last update), and your kill counts and clue counts as the game's chat last said them",
		position = 2,
		section = shownSection
	)
	default boolean progress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "slayer",
		name = "Slayer task",
		description = "Your slayer task (the monster, how many are left of how many, and where if your master named it),"
			+ " your slayer points and your task streak",
		position = 3,
		section = shownSection
	)
	default boolean slayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "presence",
		name = "What I'm doing",
		description = "The skill you're training and the area you're in by name (a city, a dungeon, a boss, a raid, a minigame"
			+ " or a region), as RuneLite's own Discord plugin shows them: sent as they change (once a minute at most) and with"
			+ " each update. Your page shows it, and Outside.wiki's globe lights you in that area. Never your tile or your"
			+ " world, and nothing from the Wilderness or a PvP world.",
		position = 0,
		section = doingSection
	)
	default boolean presence()
	{
		return true;
	}

	@ConfigItem(
		keyName = "charges",
		name = "Item charges",
		description = "Each worn or carried item's charges, as the game tells you them in its chat (when you check one, or"
			+ " as it's used): only the number, by the item",
		position = 4,
		section = shownSection
	)
	default boolean charges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "delve",
		name = "Doom of Mokhaiotl delves",
		description = "When you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level"
			+ " (as the Delve Calculator plugin reads it), and how many a death lost from then on, for your Outside page",
		position = 5,
		section = shownSection
	)
	default boolean delve()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bank",
		name = "Bank",
		description = "On (to begin with): your whole bank (every item, how many, and its tabs) goes to your Outside"
			+ " page with your next update after you use it (Update my page, or the hourly one), where anyone can see it",
		warning = "On, your whole bank (every item and how many) goes to the Outside relay, a server not controlled"
			+ " or verified by the RuneLite developers, with your next update after you use your bank, and your Outside page shows it to"
			+ " anyone who opens it. Switching it off takes it off your page.",
		position = 6,
		section = shownSection
	)
	default boolean bank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deathbank",
		name = "Deathbank",
		description = "On (to begin with): when a death makes a deathbank (the Item Retrieval Service's, as at"
			+ " Hespori, Zulrah or Vorkath), what it took and which one it is go to your Outside page, where anyone"
			+ " can see them, with your next update (and again as its window shows it after you look in it). Emptied, it comes off your page",
		warning = "On, each time a death makes a deathbank or you look in one, what's in it (every item and how many) and which boss or place"
			+ " it's for go to the Outside relay, a server not controlled or verified by the RuneLite developers, with your next update, and"
			+ " your Outside page shows them to anyone who opens it. Switching it off takes it off your page.",
		position = 7,
		section = shownSection
	)
	default boolean deathbank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "model",
		name = "Character model",
		description = "On (to begin with): your character's 3D model as you look now goes to your Outside page"
			+ " (as RuneProfile's does); type ::outsidemodel to send it again after a change. Off takes it down",
		warning = "On, your character's model as you look now (its shape and colours, nothing else) goes to the"
			+ " Outside relay, a server not controlled or verified by the RuneLite developers, and your Outside page"
			+ " shows it to anyone who opens it.",
		position = 8,
		section = shownSection
	)
	default boolean model()
	{
		return true;
	}

	@ConfigItem(
		keyName = "ground",
		name = "Ground under my model",
		description = "On (to begin with): when you capture your character (::outsidemodel, ::outsidemotion or the side"
			+ " tab), the ground round you then (about five tiles each way: its paint and its shape, no objects or"
			+ " people) goes with it, and your page draws you standing on it. Never with the live view's own captures.",
		warning = "On, each time you capture your character, the ground round you then (about five tiles each way) goes"
			+ " to the Outside relay, a server not controlled or verified by the RuneLite developers, and your"
			+ " Outside page shows it to anyone who opens it.",
		position = 9,
		section = shownSection,
		// (11/17/2026, Jace: "this setting should be in the model section": a box in the side tab's Model tab, by the
		// capture it goes with, not here)
		hidden = true
	)
	default boolean ground()
	{
		return true;
	}

	@ConfigItem(
		keyName = "goal",
		name = "Goal items",
		description = "On (to begin with): the items you're after (\"Add a goal item\" in the side tab, or ::outsidegoal) go"
			+ " to your Outside page, one with its 3D model (the one you pick in the side tab, or none) and the rest listed under it,"
			+ " each with your kill count and how dry you are for it. When the game's collection log or your loot says you"
			+ " got one, the page says so. ::outsidegoal clear takes them all off",
		warning = "On, the items you set as your goals (their names, and the 3D model of the one you show) and, when you get"
			+ " one, your kill and clue counts then go to the Outside relay, a server not controlled or verified by the"
			+ " RuneLite developers, and your Outside page shows them to anyone who opens it.",
		position = 10,
		section = shownSection
	)
	default boolean goal()
	{
		return true;
	}
}
