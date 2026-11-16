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
		description = "Your station key (it starts outstation1:): made for you by \"Make my Outside page\" in the Outside side tab,"
			+ " or from the outstation or a friend's invite. With it, what you wear and carry and your stats show live on your Outside page.",
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
		description = "What's switched on below goes to the Outside relay (on Cloudflare, unless your key names another),"
			+ " and your Outside page shows it to anyone. Off: nothing is sent anywhere.",
		warning = "This sends what you wear and carry, your looting bag, rune pouch, skills and vitals, and your IP"
			+ " address, to the Outside relay, a server not controlled or verified by the RuneLite developers."
			+ " Your Outside page shows them to anyone who opens it.",
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
		keyName = "vitals",
		name = "Hitpoints, prayer, run and special",
		description = "And which prayers are on",
		position = 4
	)
	default boolean vitals()
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
		keyName = "reports",
		name = "Levels, kills, drops and log slots",
		description = "Your levels as you gain them, kill counts, collection log slots, pets, clue counts, quests, diary tiers,"
			+ " combat tasks, slayer tasks and drops worth at least the amount below, as the game tells you them, for your page's event log and outside.wiki's feed (as"
			+ " Dink would send them; while your outstation transmits, Dink's go through it instead)",
		position = 12
	)
	default boolean reports()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportLoot",
		name = "Drops worth at least",
		description = "The least a drop is worth (in coins, at Grand Exchange prices) for it to go on your page",
		position = 12
	)
	default int reportLoot()
	{
		return 100_000;
	}

}
