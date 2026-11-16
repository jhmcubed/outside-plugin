package wiki.outside;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the plugin reads for the player's page beyond what's worn and carried, kept apart from RuneLite so it's tested
 * (docs/protocol.md, "Kept for the page"):
 * <ul>
 * <li>an item's charges, from the game's own chat line;</li>
 * </ul>
 */
public final class Kept
{
	private Kept()
	{
	}

	// ---- charges (Jace: "show charges on the items that have charges like my tonalztics of ralos and archivist's
	// folio, if you can collect that info from the client"): the game says them only in its chat, when an item's
	// checked ("Your tonalztics of ralos has 1,234 charges left.") or as it's used, so they're read from there, as the
	// Hub's charge plugins read them

	/** "1,234 charges", "1 charge", "has 50 charges left", "(57 charges)", "uses remaining: 12" … */
	private static final Pattern CHARGES = Pattern.compile(
		"(?i)(?:\\b([0-9][0-9,]*)\\s+(?:charges?|uses?|scrolls?|doses?)\\b)|(?:(?:charges?|uses?)(?:\\s+(?:left|remaining))?\\s*[:]?\\s+([0-9][0-9,]*)\\b)");
	/** "none", "no charges left", "has run out" */
	private static final Pattern NONE = Pattern.compile("(?i)\\b(no charges|no more charges|has run out of charges|has no charges|is out of charges)\\b");

	/** The charges a game message tells of, or null: none told. 0 for "no charges". */
	public static Integer charges(String message)
	{
		if (message == null)
		{
			return null;
		}
		if (NONE.matcher(message).find())
		{
			return 0;
		}
		Matcher m = CHARGES.matcher(message);
		if (!m.find())
		{
			return null;
		}
		String n = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
		try
		{
			long v = Long.parseLong(n);
			return v > Integer.MAX_VALUE ? null : (int) v;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** An item's name as the game says it in chat: lower case, without its "(charged)", "(e)", "(4)" or the like. */
	public static String spoken(String name)
	{
		return name == null ? "" : name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim().toLowerCase(Locale.ROOT);
	}

	/** Whether a message is about this item (its spoken name in it), its name at least 5 letters so a short word ("ring") never does. */
	public static boolean about(String message, String name)
	{
		String n = spoken(name);
		return n.length() >= 5 && message != null && message.toLowerCase(Locale.ROOT).contains(n);
	}

}
