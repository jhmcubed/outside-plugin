package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonNull;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Perspective;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import java.io.IOException;
import java.util.HashSet;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outside: the player's own game, live on their Outside page (docs/protocol.md, "The game"), each part behind its own
 * switch: what they wear and carry (with the looting bag, rune pouch, quiver, seed box and chugging barrel), their
 * skills, hitpoints, prayer, run and special and the prayers on, item charges, their quests, diaries, combat
 * achievements and slayer task (Progress), and field reports for their page's event log (Reports). Each game tick only
 * what changed goes (vitals alone every few seconds), each new item's name and price once. Nothing more goes from the
 * Wilderness, a PvP area or world. Never another player's anything, nor anything they type.
 */
@PluginDescriptor(
	name = "Outside",
	description = "Your gear, inventory, looting bag and stats, live on your Outside page",
	tags = {"outside", "outstation", "loadout", "inventory", "looting bag", "stream", "profile"}
)
public class OutsidePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(OutsidePlugin.class);
	static final String VERSION = "0.2.0";
	/** the relay a page is claimed on when there's no key yet (docs/protocol.md "Claiming a name") */
	static final String HOME_RELAY = "https://relay.ghillie.workers.dev";
	/** vitals alone (run energy draining as you run) go at most this often, in ticks */
	private static final int VITALS_EVERY = 5;
	/** quests and diaries read again at least this often, in ticks (5 minutes) */
	private static final int PROGRESS_EVERY = 500;
	/** the longest wait between tries to reach the relay, in seconds */
	private static final int RETRY_MOST = 60;

	@Inject
	private Client client;

	@Inject
	private OutsideConfig config;

	@Inject
	private OkHttpClient http;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chat;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	/** only on the client's thread (the ticks); a new connection asks for everything again through `resend` */
	private final Kit kit = new Kit();
	private volatile boolean resend;
	/** sending held while somewhere dangerous (the Wilderness, a PvP area or world: held()), and said so once */
	private boolean paused;
	// (quests and diaries, read again when what they depend on changes, or every 5 minutes; the slayer task's names
	// looked up again when its varps change; 11/15/2026)
	private JsonObject questsSeen, diariesSeen, slayerSeen;
	private long progressSig = Long.MIN_VALUE;
	private int progressAt = -1;
	private String slayerKey;
	/** the player's name as the game shows it, from the last tick */
	private volatile String shownName;
	/** the slayer task the game's just said is done, till its next line gives the count and the points */
	private String slayerDoneTask;
	private volatile WebSocket socket;
	private volatile boolean open;
	/** stopped for good until the key changes (a wrong key, or another RuneLite took over) */
	private volatile boolean stopped;
	private Attach attach;
	private ScheduledFuture<?> retry;
	private int retryIn = 5;
	/** the character logged in, as a channel has its name (null: not known yet, or logged out). 11/15/2026, Jace: logged
	 *  into his alt, the side tab still said sburb, and the alt's kit would have gone to sburb's page: a key is now this
	 *  character's own (kept per account), and nothing goes on a key whose page isn't the character logged in. */
	private volatile String character = null;
	/** each skill's real level as last seen this session, for the levels' reports */
	private final java.util.Map<String, Integer> levelsSeen = new java.util.concurrent.ConcurrentHashMap<>();
	/** this key is a challenger's (taking a name back, 11/15/2026): what it sends only proves the name, till it's proven */
	private volatile boolean challenging = false;
	private int ticksSinceSent;
	/** the seed box's and the chugging barrel's slots as last sent by the game (onItemContainerChanged), or null */
	private JsonElement seedsSeen, barrelSeen;
	private boolean toldConnected;
	/** (11/14/2026) each item's charges as the game last told of them, by item id (kept on this RuneLite's profile for
	 *  the account, so they're known after a restart); the item last checked, and on which tick; the client's thread */
	private final Map<Integer, Integer> charges = new HashMap<>();
	private int checkedItem = -1, checkedTick = -100, ticks;
	private static final MediaType JSON = MediaType.parse("application/json");

	@Inject
	private ConfigManager configManager;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	/** the side tab (11/15/2026; OutsidePanel) and its button */
	private OutsidePanel panel;
	private net.runelite.client.ui.NavigationButton navButton;

	@Provides
	OutsideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OutsideConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new OutsidePanel(panelHooks());
		navButton = net.runelite.client.ui.NavigationButton.builder().tooltip("Outside").icon(tabIcon()).priority(8).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		Attach a = key();
		panel.who(a == null ? null : a.getChannel());
		if (a != null)
		{
			readProof();
		}
		connect();
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		close("plugin off");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!OutsideConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if (panel != null && e.getKey().equals("stationKey"))
		{
			Attach a = key();
			panel.who(a == null ? null : a.getChannel());
			readProof();
		}
		// (a new key, or sending switched: start again; a part switched off goes as an empty field next tick)
		if (e.getKey().equals("stationKey") || e.getKey().equals("send"))
		{
			close("settings changed");
			stopped = false;
			toldConnected = false;
			connect();
		}
		resend = true;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			readProof();   // (proven since the last logout? the side tab says so)
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			// (logged out: the relay drops the area and the activity with the rest; next time, the two minutes start
			// again; the stars not reported yet are forgotten)
			// (the seed box and the barrel as the game last sent them: another account may log in next)
			seedsSeen = barrelSeen = null;
			charges.clear();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN && open)
		{
			JsonObject off = new JsonObject();
			off.addProperty("off", true);
			send(off);
			resend = true;
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			character = null;
			levelsSeen.clear();
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			resend = true;
			loadKept();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		// (whose game this is: known from the first tick; another than before, the key and the side tab follow)
		Player me = client.getLocalPlayer();
		String whose = me == null ? null : channelName(me.getName());
		if (me != null && me.getName() != null)
		{
			shownName = me.getName().replace('\u00a0', ' ');
		}
		if (whose != null && !whose.equals(character))
		{
			character = whose;
			characterChanged();
		}
		ticks++;
		if (open && resend)
		{
			resend = false;
			kit.forget();
		}
		// (where they are, read every tick, the relay in reach or not: somewhere dangerous holds things back at once)
		WorldPoint at = here();
		int x = at == null ? -1 : at.getX(), y = at == null ? -1 : at.getY(), region = at == null ? -1 : at.getRegionID();
		boolean wild = wilderness(x, y, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1);
		boolean pvpArea = pvpArea(x, y, region, client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1);
		if (!open)
		{
			return;
		}
		// (somewhere dangerous: nothing more goes till they're out, unless they've said otherwise, so nobody
		// watching their page sees what they risk)
		if (held(pvpWorld(), wild, pvpArea))
		{
			if (!paused)
			{
				paused = true;
				send(Kit.held("wilderness"));
			}
			return;
		}
		if (paused)
		{
			paused = false;
			kit.forget();
		}
		Map<String, JsonElement> now = read();
		JsonObject changes = kit.changes(now);
		ticksSinceSent++;
		if (changes.size() == 0)
		{
			return;
		}
		// (vitals alone, as run energy drains: not every tick)
		boolean onlyVitals = changes.size() == 1 && changes.has("vitals");
		if (onlyVitals && ticksSinceSent < VITALS_EVERY)
		{
			return;
		}
		Set<Integer> fresh = kit.unnamed(now);
		if (!fresh.isEmpty())
		{
			JsonObject items = new JsonObject();
			for (int id : fresh)
			{
				String name = itemManager.getItemComposition(id).getName();
				long price = itemManager.getItemPrice(id);
				JsonArray np = new JsonArray();
				np.add(name);
				np.add(price);
				items.add(String.valueOf(id), np);
			}
			changes.add("items", items);
		}
		changes.addProperty("off", false);
		changes.addProperty("paused", false);
		if (send(changes))
		{
			kit.took(changes);
			ticksSinceSent = 0;
		}
	}

	/** The game as the relay's fields, from what's switched on. */
	private Map<String, JsonElement> read()
	{
		Map<String, JsonElement> m = new LinkedHashMap<>();
		Player me = client.getLocalPlayer();
		if (me != null && me.getName() != null)
		{
			m.put("name", new JsonPrimitive(me.getName()));
		}
		m.put("world", new JsonPrimitive(client.getWorld()));
		m.put("type", new JsonPrimitive(accountType(client.getVarbitValue(VarbitID.IRONMAN))));
		if (config.items())
		{
			m.put("worn", slots(client.getItemContainer(InventoryID.WORN), 14));
			m.put("inv", slots(client.getItemContainer(InventoryID.INV), 28));
			// (the looting bag is known once looked in, and kept as it was till then)
			ItemContainer bag = client.getItemContainer(InventoryID.LOOTING_BAG);
			if (bag != null)
			{
				m.put("bag", items(bag));
			}
			m.put("pouch", runePouch());
			// (the seed box and the chugging barrel, 11/14/2026, Jace: "capture and show the contents inside of the seed
			// box and chugging barrel too": each its slots as the game keeps them, known once the game has told RuneLite,
			// as the looting bag is, and kept as they were till then)
			// (and as the game sent them, caught as they came, onItemContainerChanged: the client may not keep them after)
			ItemContainer seeds = client.getItemContainer(InventoryID.SEED_BOX);
			if (seeds != null)
			{
				seedsSeen = slots(seeds, 6);
			}
			if (seedsSeen != null)
			{
				m.put("seeds", seedsSeen);
			}
			ItemContainer barrel = client.getItemContainer(InventoryID.PREPOT_DEVICE_INV);
			if (barrel != null)
			{
				barrelSeen = slots(barrel, 5);
			}
			if (barrelSeen != null)
			{
				m.put("barrel", barrelSeen);
			}
			int ammo = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
			int amount = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
			m.put("quiver", ammo > 0 && amount > 0 ? Kit.pair(ammo, amount) : com.google.gson.JsonNull.INSTANCE);
		}
		if (config.items() && config.charges())
		{
			// (only the items worn or carried now)
			Set<Integer> here = new HashSet<>();
			for (int c : new int[]{InventoryID.WORN, InventoryID.INV})
			{
				ItemContainer ic = client.getItemContainer(c);
				if (ic != null)
				{
					for (Item it : ic.getItems())
					{
						here.add(it.getId());
					}
				}
			}
			JsonObject ch = new JsonObject();
			for (Map.Entry<Integer, Integer> e : new java.util.TreeMap<>(charges).entrySet())
			{
				if (here.contains(e.getKey()))
				{
					ch.addProperty(String.valueOf(e.getKey()), e.getValue());
				}
			}
			m.put("charges", ch);
		}
		if (config.skills())
		{
			JsonObject skills = new JsonObject();
			for (Skill s : Skill.values())
			{
				// (the total isn't a skill: Skill.OVERALL, deprecated, is skipped by its name)
				if ("Overall".equals(s.getName()))
				{
					continue;
				}
				JsonArray v = new JsonArray();
				v.add(client.getRealSkillLevel(s));
				v.add(client.getBoostedSkillLevel(s));
				v.add(client.getSkillExperience(s));
				skills.add(s.getName(), v);
			}
			m.put("skills", skills);
		}
		if (config.progress())
		{
			long sig = Progress.signature(client);
			if (sig != progressSig || progressAt < 0 || ticks - progressAt >= PROGRESS_EVERY)
			{
				progressSig = sig;
				progressAt = ticks;
				JsonObject before = questsSeen;
				questsSeen = Progress.readQuests(client);
				// (a quest that's done now and wasn't at the last read, this session: a field report; 11/15/2026)
				for (String q : Progress.newlyDone(before, questsSeen))
				{
					report(Reports.quest(q, questsSeen.getAsJsonArray("done").size(), questsSeen.get("total").getAsInt(), myName()));
				}
				diariesSeen = Progress.readDiaries(client);
			}
			m.put("quests", questsSeen);
			m.put("diaries", diariesSeen);
			m.put("combat", Progress.readCombat(client));
		}
		else
		{
			// (switched off: taken off their page)
			m.put("quests", JsonNull.INSTANCE);
			m.put("diaries", JsonNull.INSTANCE);
			m.put("combat", JsonNull.INSTANCE);
		}
		if (config.slayer())
		{
			String k = Progress.slayerKey(client);
			if (!k.equals(slayerKey) || slayerSeen == null)
			{
				slayerKey = k;
				slayerSeen = Progress.readSlayer(client);
			}
			m.put("slayer", slayerSeen);
		}
		else
		{
			m.put("slayer", JsonNull.INSTANCE);
		}
		if (config.vitals())
		{
			JsonObject vitals = new JsonObject();
			vitals.add("hp", Kit.pair(client.getBoostedSkillLevel(Skill.HITPOINTS), client.getRealSkillLevel(Skill.HITPOINTS)));
			vitals.add("prayer", Kit.pair(client.getBoostedSkillLevel(Skill.PRAYER), client.getRealSkillLevel(Skill.PRAYER)));
			vitals.addProperty("run", client.getEnergy() / 100);
			vitals.addProperty("spec", client.getVarpValue(VarPlayerID.SA_ENERGY) / 10);
			m.put("vitals", vitals);
			JsonArray on = new JsonArray();
			for (Prayer p : Prayer.values())
			{
				if (client.getVarbitValue(p.getVarbit()) == 1)
				{
					on.add(p.name());
				}
			}
			m.put("prayers", on);
		}
		return m;
	}

	private static JsonElement slots(ItemContainer c, int size)
	{
		if (c == null)
		{
			return Kit.slots(new int[0], new int[0], size);
		}
		Item[] items = c.getItems();
		int[] ids = new int[items.length];
		int[] qs = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qs[i] = items[i].getQuantity();
		}
		return Kit.slots(ids, qs, size);
	}

	private static JsonElement items(ItemContainer c)
	{
		Item[] items = c.getItems();
		int[] ids = new int[items.length];
		int[] qs = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qs[i] = items[i].getQuantity();
		}
		return Kit.items(ids, qs);
	}

	/** The rune pouch's runes (its six slots, as the game keeps them; the page shows it with a pouch carried). */
	private JsonElement runePouch()
	{
		int[] types = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
			VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
		int[] amounts = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
			VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		int[] ids = new int[6];
		int[] qs = new int[6];
		for (int i = 0; i < 6; i++)
		{
			int type = client.getVarbitValue(types[i]);
			ids[i] = type > 0 && runes != null ? runes.getIntValue(type) : -1;
			qs[i] = client.getVarbitValue(amounts[i]);
		}
		return Kit.items(ids, qs);
	}

	/** Their own experience (the activity's skilling). */
	/** The seed box's and the chugging barrel's contents as the game sends them (when one's checked or filled, or the
	 *  barrel's bank loadout opens), kept for read(): the Hub's Seed Box plugin reads the seed box the same way. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == InventoryID.SEED_BOX)
		{
			seedsSeen = slots(e.getItemContainer(), 6);
		}
		else if (e.getContainerId() == InventoryID.PREPOT_DEVICE_INV)
		{
			barrelSeen = slots(e.getItemContainer(), 5);
		}
	}

	// ---- (11/14/2026) charges: Kept

	/** An item checked ("Check", "Inspect" and the like): the next charges the game tells of are its. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		String opt = e.getMenuOption() == null ? "" : Text.removeTags(e.getMenuOption()).toLowerCase();
		if (e.getItemId() > 0 && (opt.startsWith("check") || opt.equals("inspect") || opt.equals("charges")))
		{
			checkedItem = e.getItemId();
			checkedTick = ticks;
		}
	}

	/** The game's own words of an item's charges: the item just checked, or the worn or carried one it names. */
	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM)
		{
			String said = Text.removeTags(e.getMessage());
			report(Reports.fromChat(said, myName()));
			// (a slayer task's end comes in two lines: the task, then the count and the points; 11/15/2026)
			String task = Reports.slayerTask(said);
			if (task != null)
			{
				slayerDoneTask = task;
			}
			else if (slayerDoneTask != null)
			{
				JsonObject done = Reports.slayerDone(said, slayerDoneTask, myName());
				if (done != null)
				{
					slayerDoneTask = null;
					report(done);
				}
			}
		}
		if (!config.charges() || (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM))
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		Integer n = Kept.charges(msg);
		if (n == null)
		{
			return;
		}
		int item = -1;
		if (checkedItem > 0 && ticks - checkedTick <= 3)
		{
			item = checkedItem;
		}
		else
		{
			for (int c : new int[]{InventoryID.WORN, InventoryID.INV})
			{
				ItemContainer ic = client.getItemContainer(c);
				if (ic == null)
				{
					continue;
				}
				for (Item it : ic.getItems())
				{
					if (item < 0 && it.getId() > 0 && Kept.about(msg, itemManager.getItemComposition(it.getId()).getName()))
					{
						item = it.getId();
					}
				}
			}
		}
		if (item > 0)
		{
			charges.put(item, n);
			checkedItem = -1;
			saveKept();
		}
	}

	/** The side tab's icon: a lime ✼, drawn here (no picture to ship). */
	private static java.awt.image.BufferedImage tabIcon()
	{
		java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = im.createGraphics();
		g.setColor(new java.awt.Color(0xb9, 0xff, 0x00));
		g.fillRect(7, 1, 2, 14);
		g.fillRect(1, 7, 14, 2);
		for (int i = 0; i < 4; i++)
		{
			g.fillRect(3 + i, 3 + i, 2, 2);
			g.fillRect(11 - i, 3 + i, 2, 2);
			g.fillRect(3 + i, 11 - i, 2, 2);
			g.fillRect(11 - i, 11 - i, 2, 2);
		}
		g.dispose();
		return im;
	}

	private OutsidePanel.Hooks panelHooks()
	{
		return new OutsidePanel.Hooks()
		{

			@Override
			public void refresh()
			{
				readProof();
			}

			@Override
			public void claim()
			{
				clientThread.invokeLater(() ->
				{
					if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
					{
						panel.claiming(false, "log in first: your page is named after the character you're logged in as");
						return;
					}
					String shown = client.getLocalPlayer().getName();
					String name = channelName(shown);
					if (name == null)
					{
						panel.claiming(false, "\"" + shown + "\" can't be a page's name");
						return;
					}
					long hash = client.getAccountHash();
					claimPage(name, hash == -1 ? null : Long.toString(hash));
				});
			}

			@Override
			public void copyKey()
			{
				Attach a = key();
				if (a == null)
				{
					panel.keyStatus("no key for this character yet");
					return;
				}
				try
				{
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(stationKey().trim()), null);
					panel.keyStatus("copied: paste it into the outstation's first screen");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.keyStatus("the clipboard's busy: try again");
				}
			}

			@Override
			public void openPage()
			{
				Attach a = key();
				if (a != null)
				{
					net.runelite.client.util.LinkBrowser.browse("https://outside.wiki/" + java.net.URLEncoder.encode(a.getChannel(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
				}
			}
		};
	}

	/** The station key for the character logged in: their own (kept with their account by "Make my Outside page"), else
	 *  the one in the plugin's settings. */
	private String stationKey()
	{
		String own = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "stationKey");
		return own != null && !own.trim().isEmpty() ? own : config.stationKey();
	}

	/** The key read, or null: none, or one whose page isn't the character logged in (another account's). */
	private Attach key()
	{
		Attach a = Attach.parse(stationKey(), gson);
		String c = character;
		return a != null && c != null && !a.getChannel().toLowerCase(java.util.Locale.ROOT).equals(c) ? null : a;
	}

	/** Another character logged in (or the first, once its name is known): the side tab, and the relay on their key. */
	private void characterChanged()
	{
		progressAt = -1;
		questsSeen = null;
		slayerKey = null;
		slayerSeen = null;
		Attach any = Attach.parse(stationKey(), gson), a = key();
		if (panel != null)
		{
			panel.who(a == null ? null : a.getChannel());
			panel.otherKey(any != null && a == null ? any.getChannel() : null, character);
			readProof();
		}
		if (attach != null && (a == null || !a.getChannel().equals(attach.getChannel())))
		{
			close("another character");
		}
		stopped = false;
		toldConnected = false;
		connect();
	}

	/** A player's name as a channel has it: lower case, the spaces, hyphens and underscores the game treats alike taken
	 *  out (its names use a no-break space), 1 to 12 letters or numbers; null when it can't be one. */
	static String channelName(String shown)
	{
		if (shown == null)
		{
			return null;
		}
		String n = shown.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\u00a0_-]", "");
		return n.matches("[a-z0-9]{1,12}") ? n : null;
	}

	/** Claims a page for the name (docs/protocol.md "Claiming a name"): the key that comes back goes in the settings,
	 *  which connects as any key does (onConfigChanged). */
	private void claimPage(String name, String account)
	{
		panel.claiming(true, null);
		JsonObject body = new JsonObject();
		body.addProperty("player", name);
		if (account != null)
		{
			body.addProperty("account", account);
		}
		Request req = new Request.Builder().url(HOME_RELAY + "/claim").header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, gson.toJson(body))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.claiming(false, "the relay's out of reach: try again in a bit");
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					String text = res.body() != null ? res.body().string() : "";
					JsonObject o = null;
					try
					{
						o = gson.fromJson(text, JsonObject.class);
					}
					catch (RuntimeException ignored)
					{
					}
					if (res.code() == 200 && o != null && o.has("code") && Attach.parse(o.get("code").getAsString(), gson) != null)
					{
						boolean back = o.has("proof") && "challenging".equals(o.get("proof").getAsString());
						challenging = back;
						configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "stationKey", o.get("code").getAsString());
						if (back)
						{
							// (taking a name back: someone else claimed it first and hasn't proven it)
							panel.claiming(false, "outside.wiki/" + name + " was claimed by someone who hasn't proven it. Gain any experience,"
								+ " then log out: when the official hiscores show it, the page is yours.");
							say("you're taking back outside.wiki/" + name + ": gain any experience, then log out");
						}
						else
						{
							panel.claiming(false, "your page: outside.wiki/" + name + " (marked unproven till it's proven yours)");
							say("your Outside page is made: outside.wiki/" + name);
						}
						return;
					}
					if (res.code() == 409)
					{
						panel.claiming(false, "outside.wiki/" + name + " is proven or vouched for already. If it's yours, paste its station key in the plugin's settings.");
						return;
					}
					String why = o != null && o.has("error") ? o.get("error").getAsString() : "the relay said " + res.code();
					panel.claiming(false, why);
				}
				catch (IOException ex)
				{
					panel.claiming(false, "the relay's out of reach: try again in a bit");
				}
			}
		});
	}

	/** Whether their page is proven theirs, into the side tab (docs/protocol.md "Proving a name"). */
	private void readProof()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			if (panel != null)
			{
				panel.proof(null, null);
			}
			return;
		}
		http.newCall(new Request.Builder().url(a.proofUrl() + "?key=" + java.net.URLEncoder.encode(a.getKey(), java.nio.charset.StandardCharsets.UTF_8))
			.header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					JsonObject o = res.code() == 200 && res.body() != null ? gson.fromJson(res.body().string(), JsonObject.class) : null;
					String proof = o != null && o.has("proof") ? o.get("proof").getAsString() : null;
					challenging = "challenging".equals(proof);
					panel.proof(proof, o != null && o.has("why") ? o.get("why").getAsString() : null);
				}
				catch (IOException | RuntimeException ignored)
				{
				}
			}
		});
	}

	/** Charges, as kept on this RuneLite's profile for the account. */
	private void loadKept()
	{
		charges.clear();
		try
		{
			String c = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "charges");
			if (c != null)
			{
				JsonObject o = gson.fromJson(c, JsonObject.class);
				for (Map.Entry<String, JsonElement> e : o.entrySet())
				{
					charges.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("outside: charges or delve unreadable", e);
		}
	}

	private void saveKept()
	{
		JsonObject o = new JsonObject();
		for (Map.Entry<Integer, Integer> e : charges.entrySet())
		{
			o.addProperty(String.valueOf(e.getKey()), e.getValue());
		}
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "charges", gson.toJson(o));
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		// (a level gained: its real level above the one last seen this session; the first seen is only remembered)
		String skill = e.getSkill().getName();
		Integer was = levelsSeen.put(skill, e.getLevel());
		if (was != null && e.getLevel() > was && client.getGameState() == GameState.LOGGED_IN)
		{
			java.util.Map<String, Integer> one = new java.util.LinkedHashMap<>();
			one.put(skill, e.getLevel());
			report(Reports.levels(one, myName()));
		}
	}

	/** A monster's loot (RuneLite's own reading of it, as Dink's): worth "Drops worth at least" or more, a report. */
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		if (!config.reports() || e.getNpc() == null)
		{
			return;
		}
		java.util.List<Reports.Item> items = new java.util.ArrayList<>();
		for (ItemStack s : e.getItems())
		{
			int id = itemManager.canonicalize(s.getId());
			items.add(new Reports.Item(id, itemManager.getItemComposition(id).getName(), s.getQuantity(), itemManager.getItemPrice(id)));
		}
		report(Reports.loot(e.getNpc().getName(), items, Math.max(0, config.reportLoot()), myName()));
	}

	/** The name they're logged in as, for a report's line. */
	private String myName()
	{
		Player me = client.getLocalPlayer();
		return me == null || me.getName() == null ? "the subject" : me.getName().replace('\u00a0', ' ');
	}

	/** A report sent to the relay's Dink route (docs/protocol.md "Dink", from=plugin): with "Levels, kills, drops and log
	 *  slots" on, a key for the character logged in, and never while held somewhere dangerous (the Wilderness, a PvP
	 *  area or world), as nothing else is sent there. */
	private void report(JsonObject payload)
	{
		Attach a = attach;
		String c = character;
		if (payload == null || !config.reports() || !config.send() || paused || a == null || c == null
			|| !a.getChannel().toLowerCase(java.util.Locale.ROOT).equals(c))
		{
			return;
		}
		Request req = new Request.Builder().url(a.dinkUrl()).header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, gson.toJson(payload))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: a report didn't reach the relay", ex);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				r.close();
			}
		});
	}

	/**
	 * Where the player is, an instance's as its template lies on the map (11/09/2026: instances are allowed, placed
	 * where they're opened), as RuneLite's own Discord plugin reads it; outside one, where they are; on a boat, where the
	 * boat is at sea (onMainWorld, 11/10/2026). Null before there's a player, or for a moment while a boat's being set up.
	 */
	private WorldPoint here()
	{
		return here(client, client.getLocalPlayer());
	}

	static WorldPoint here(Client client, Player me)
	{
		LocalPoint at = me == null ? null : onMainWorld(client.getTopLevelWorldView(), me.getLocalLocation());
		return at == null ? null : WorldPoint.fromLocalInstance(client, at);
	}

	/**
	 * A point on the main world (11/10/2026, Jace: "I notice there are no players on their boats at sea"). With Sailing,
	 * a player on a boat stands in the boat's own world view (a WorldEntity's), and their local point is the deck's, so
	 * read as it was it gave the boat's own template, off the map, and nothing went. Here the deck's point is carried
	 * out to where the boat is on the main world, the way RuneLite's own code does it (runelite/runelite at e7098b7,
	 * Perspective.localToMinimap: the top-level view's worldEntities().byIndex(the point's world view), then
	 * transformToMainWorld) and the Sailing plugin's SailingUtil.getTopLevelLocalPoint (LlemonDuck/sailing at af987f3);
	 * Group Ironmen Tracker and Shortest Path take the boat's own place (worldEntity.getLocalLocation()), here the fallback.
	 * Null for a point in a world view with no boat on the main world (as while a world hop sets it up): never the deck's.
	 */
	static LocalPoint onMainWorld(WorldView top, LocalPoint p)
	{
		if (p == null || p.getWorldView() == WorldView.TOPLEVEL)
		{
			return p;
		}
		IndexedObjectSet<? extends WorldEntity> boats = top == null ? null : top.worldEntities();
		WorldEntity boat = boats == null ? null : boats.byIndex(p.getWorldView());
		if (boat == null)
		{
			return null;
		}
		LocalPoint at = boat.transformToMainWorld(p);
		if (at == null)
		{
			at = boat.getLocalLocation();
		}
		return at == null ? null : new LocalPoint(at.getX(), at.getY(), WorldView.TOPLEVEL);
	}

	/**
	 * Ferox Enclave (11/10/2026, Jace: "it is a safe area/haven in the wilderness"): the town inside its barriers, where
	 * no player can attack another, as Shortest Path's WildernessChecker draws it (github.com/Skretzo/shortest-path,
	 * src/main/java/shortestpath/pathfinder/WildernessChecker.java at 78593f7, FEROX_ENCLAVE_1 to 5: each {x, y, width,
	 * height}, on every floor). Not the semi-safe ground outside its barriers, nor the dungeon under it.
	 */
	static final int[][] FEROX = {{3123, 3622, 2, 10}, {3125, 3617, 16, 23}, {3138, 3636, 18, 10}, {3141, 3625, 14, 11},
		{3141, 3619, 7, 6}};

	static boolean inFerox(int x, int y)
	{
		for (int[] a : FEROX)
		{
			if (x >= a[0] && x < a[0] + a[2] && y >= a[1] && y < a[1] + a[3])
			{
				return true;
			}
		}
		return false;
	}

	/** In the Wilderness: within its bounds (an instance's template there too), or where the game's own
	 *  in-the-Wilderness flag says so (any part of it outside them); but never in Ferox Enclave, a haven (whatever the
	 *  flag says there, which couldn't be checked in the game). Kept apart from the client so it's tested. */
	static boolean wilderness(int x, int y, boolean insideFlag)
	{
		return !inFerox(x, y) && (inWilderness(x, y) || insideFlag);
	}

	/** In a PvP area: the game's PvP-area flag up, but not in Ferox Enclave and not in a PvP minigame's region
	 *  (11/10/2026, Jace: "there is no danger to someone tracking them there": Activity.PVP_MINIGAME_REGIONS). */
	static boolean pvpArea(int x, int y, int region, boolean pvpFlag)
	{
		return pvpFlag && !inFerox(x, y);
	}

	/**
	 * Where the stream holds (what they wear and carry, their vitals: Kit.held), unless "In the Wilderness too" is on:
	 * a PvP or Deadman world, the Wilderness, a PvP area. Not in Ferox Enclave or a PvP minigame (11/10/2026, Jace:
	 * "stream should keep going"), whatever the game's flags say there.
	 */
	static boolean held(boolean pvpWorld, boolean wilderness, boolean pvpArea)
	{
		return pvpWorld || wilderness || pvpArea;
	}

	/** On a PvP or Deadman world (read here, never sent). */
	private boolean pvpWorld()
	{
		return WorldType.isPvpWorld(client.getWorldType()) || client.getWorldType().contains(WorldType.DEADMAN);
	}

	/** The Wilderness's bounds, above ground and below (its levels count from y 3520 up, and 9920 under). */
	static boolean inWilderness(int x, int y)
	{
		return x >= 2944 && x < 3392 && ((y >= 3520 && y < 3968) || (y >= 9920 && y < 10368));
	}

	static String accountType(int v)
	{
		switch (v)
		{
			case 1: return "IRONMAN";
			case 2: return "ULTIMATE_IRONMAN";
			case 3: return "HARDCORE_IRONMAN";
			case 4: return "GROUP_IRONMAN";
			case 5: return "HARDCORE_GROUP_IRONMAN";
			case 6: return "UNRANKED_GROUP_IRONMAN";
			default: return "NORMAL";
		}
	}

	// ---- the relay ----

	/** RuneLite's Gson, keeping nulls as it writes (Kit.wire): the game socket's messages say "clear this" with them. */
	private Gson wire;

	private boolean send(JsonObject fields)
	{
		WebSocket ws = socket;
		Attach a = attach;
		String c = character;
		if (ws == null || !open || a == null || c == null || !a.getChannel().toLowerCase(java.util.Locale.ROOT).equals(c))
		{
			return false;
		}
		JsonObject msg = new JsonObject();
		msg.add("game", fields);
		if (wire == null)
		{
			wire = Kit.wire(gson);
		}
		return ws.send(wire.toJson(msg));
	}

	private synchronized void connect()
	{
		if (!config.send() || stopped || socket != null)
		{
			return;
		}
		attach = key();
		if (attach == null)
		{
			return;
		}
		Request req = new Request.Builder().url(attach.gameUrl(VERSION)).header("User-Agent", "outside-plugin/" + VERSION).build();
		socket = http.newWebSocket(req, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket ws, Response response)
			{
				open = true;
				retryIn = 5;
				resend = true;
				if (!toldConnected)
				{
					toldConnected = true;
					say(challenging ? "your claim on outside.wiki/" + attach.getChannel() + " is being proven: gain any experience, then log out"
						: "your Outside page is live: " + attach.page().replaceFirst("^https://", ""));
				}
			}

			@Override
			public void onClosing(WebSocket ws, int code, String reason)
			{
				ws.close(1000, null);
			}

			@Override
			public void onClosed(WebSocket ws, int code, String reason)
			{
				ended(ws, code);
			}

			@Override
			public void onFailure(WebSocket ws, Throwable t, Response response)
			{
				log.debug("outside: the relay's out of reach", t);
				ended(ws, 1006);
			}
		});
	}

	private synchronized void ended(WebSocket ws, int code)
	{
		if (ws != socket)
		{
			return;
		}
		socket = null;
		open = false;
		if (code == 4012)
		{
			// (taking a name back, 11/15/2026: proven, the page is theirs now; its key opens it as the holder's)
			challenging = false;
			say("your Outside page is proven yours: outside.wiki/" + attach.getChannel());
			readProof();
			retry = executor.schedule(this::connect, 1, TimeUnit.SECONDS);
			return;
		}
		if (code == 4001 || code == 4003)
		{
			stopped = true;
			say(challenging ? "someone else proved this name first, so your claim on it has ended"
				: "the relay didn't take your station key: paste it again in the plugin's settings");
			return;
		}
		if (code == 4000)
		{
			stopped = true;
			say("another RuneLite is sending to this outstation now, so this one stopped");
			return;
		}
		if (code == 1000 && !config.send())
		{
			return;
		}
		// (out of reach: try again, waiting longer each time)
		if (retry == null || retry.isDone())
		{
			retry = executor.schedule(this::connect, retryIn, TimeUnit.SECONDS);
			retryIn = Math.min(RETRY_MOST, retryIn * 2);
		}
	}

	private synchronized void close(String why)
	{
		if (retry != null)
		{
			retry.cancel(false);
			retry = null;
		}
		WebSocket ws = socket;
		socket = null;
		open = false;
		if (ws != null)
		{
			ws.close(1000, why);
		}
	}

	private void say(String words)
	{
		if (panel != null)
		{
			panel.status(words);
		}
		clientThread.invokeLater(() -> chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("Outside: " + words).build()));
	}
}
