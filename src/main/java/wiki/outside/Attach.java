package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A station key ("outstation1:" and base64url JSON {relay, channel, key}, as the outstation and a friend's invite
 * give it; "portal1:" from before the name was outstation): where the plugin sends the game, and with what key.
 */
public final class Attach
{
	private final String relay;
	private final String channel;
	private final String key;

	public Attach(String relay, String channel, String key)
	{
		this.relay = relay;
		this.channel = channel;
		this.key = key;
	}

	public String getRelay()
	{
		return relay;
	}

	public String getChannel()
	{
		return channel;
	}

	public String getKey()
	{
		return key;
	}

	/** The key read (with RuneLite's own Gson), or null when it isn't one. */
	public static Attach parse(String code, Gson gson)
	{
		if (code == null)
		{
			return null;
		}
		String c = code.trim();
		int colon = c.indexOf(':');
		if (colon < 0)
		{
			return null;
		}
		String prefix = c.substring(0, colon);
		if (!prefix.equals("outstation1") && !prefix.equals("portal1"))
		{
			return null;
		}
		try
		{
			String json = new String(Base64.getUrlDecoder().decode(c.substring(colon + 1)), StandardCharsets.UTF_8);
			JsonObject o = gson.fromJson(json, JsonObject.class);
			String relay = o.get("relay").getAsString();
			String channel = o.get("channel").getAsString();
			String key = o.get("key").getAsString();
			if (relay.isEmpty() || channel.isEmpty() || key.length() < 8)
			{
				return null;
			}
			return new Attach(relay, channel, key);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** The snapshot's address (11/16/2026; docs/protocol.md, "The snapshot"): POST http(s)://…/game/<channel>?key=…&v=…. */
	public String postUrl(String version)
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/game/" + enc(channel) + "?") + "&v=" + enc(version);
	}

	/** The relay's keep for the page (11/14/2026): http(s)://…/keep/<channel>/<what>?key=… (a bare relay as https://). */
	public String keepUrl(String what)
	{
		String base = relay.trim().replaceAll("/+$", "");
		if (base.startsWith("wss://"))
		{
			base = "https://" + base.substring(6);
		}
		else if (base.startsWith("ws://"))
		{
			base = "http://" + base.substring(5);
		}
		else if (!base.contains("://"))
		{
			base = "https://" + base;
		}
		return base + "/keep/" + enc(channel) + "/" + enc(what) + "?key=" + enc(key);
	}

	/** Who stands behind the page (11/15/2026): http(s)://…/proof/<channel>, anyone's. */
	public String proofUrl()
	{
		return keepUrl("x").replaceFirst("/keep/.*$", "/proof/" + enc(channel));
	}

	/** Friends only, set from the plugin (11/16/2026, the permissions review): http(s)://…/code/<channel>?key=…. */
	public String codeUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/code/" + enc(channel) + "?");
	}

	/** The page taken down by its own key (11/16/2026): http(s)://…/channels/<channel>?key=…. */
	public String takeDownUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/channels/" + enc(channel) + "?");
	}

	/** The page's kept game, as anyone reads it (403 when it's friends only and no code is given). */
	public String gameReadUrl()
	{
		return keepUrl("x").replaceFirst("/keep/.*$", "/game/" + enc(channel));
	}

	/** The page with a watch code, for friends (11/16/2026). */
	public String page(String code)
	{
		return code == null || code.isEmpty() ? page() : page() + "?code=" + enc(code);
	}

	/** The outstation's page on Outside. */
	public String page()
	{
		// (11/16/2026: outside.wiki/<name>, as Open my page opens it; it was ghillie.wiki/outside?player=)
		return "https://outside.wiki/" + enc(channel);
	}

	private static String enc(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
