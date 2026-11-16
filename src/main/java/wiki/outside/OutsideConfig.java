package wiki.outside;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OutsideConfig.GROUP)
public interface OutsideConfig extends Config
{
	String GROUP = "outside";

	@ConfigItem(
		keyName = "stationKey",
		name = "Station key",
		description = "Your page's key (it starts outstation1:): made for you by \"Make my Outside page\" in the Outside"
			+ " side tab and kept with your account, so this stays blank. Paste one here only if you were given one.",
		secret = true,
		position = 0
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
		position = 1
	)
	default boolean send()
	{
		return true;
	}

	@ConfigItem(
		keyName = "items",
		name = "Worn and carried",
		description = "Your equipment, inventory, looting bag (once you've looked in it), seed box, chugging barrel, rune pouch and quiver",
		position = 2
	)
	default boolean items()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skills",
		name = "Skills",
		description = "Each skill's level, boosted level and experience",
		position = 3
	)
	default boolean skills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "progress",
		name = "Quests, diaries and combat achievements",
		description = "Your quests (done and started) and quest points, each Achievement Diary's tiers and tasks, and your combat"
			+ " achievements' points and tasks by tier",
		position = 3
	)
	default boolean progress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "slayer",
		name = "My slayer task",
		description = "Your slayer task (the monster, how many are left of how many, and where if your master named it),"
			+ " your slayer points and your task streak",
		position = 3
	)
	default boolean slayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "presence",
		name = "What I'm doing, as Discord shows it",
		description = "The skill you're training and the area you're in by name (a city, a dungeon, a boss, a raid, a minigame"
			+ " or a region), as RuneLite's own Discord plugin shows them: sent as they change (once a minute at most) and with"
			+ " each update. Your page shows it, and outside.wiki's globe lights you in that area. Never your tile or your"
			+ " world, and nothing from the Wilderness or a PvP world.",
		position = 3
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
		position = 12
	)
	default boolean charges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "delve",
		name = "Doom of Mokhaiotl delves",
		description = "When you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level"
			+ " (as the Delve Calculator plugin reads it), for your Outside page",
		position = 13
	)
	default boolean delve()
	{
		return true;
	}

}
