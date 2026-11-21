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
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
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
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.Model;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import java.io.IOException;
import java.util.HashSet;
import net.runelite.api.gameval.InterfaceID;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outside: the player's own game on their Outside page, each part behind its own switch: what they wear and carry
 * (with the looting bag, rune pouch, quiver, seed box and chugging barrel), their skills, item charges, their quests,
 * diaries, combat achievements and slayer task (Progress).
 * The snapshot (11/16/2026, the Plugin Hub's answer; docs/protocol.md, "The snapshot"): all of it posted once when
 * they press "Update my page" in the side tab, and hourly while they're logged in (AUTO_SYNC_MINUTES, as RuneProfile's),
 * never from the Wilderness, a PvP area or world; and what they're doing as RuneLite's own Discord plugin shows it
 * (Presence: the skill being trained, the area by Discord's name), as it changes, at most once a minute. Never another
 * player's anything, nor anything they type.
 * Their character's model, its motions and the live view (Kept), off to begin with.
 * Their bank, off to begin with.
 * Their deathbank, as they last looked in it, off to begin with.
 * Their fights at bosses and raids, recorded to this computer (Encounters, Encounter Recorder's recorder folded in), and
 * one put on their page when they press Put on my page, stripped first (EncounterStrip, EncounterShelf; 11/21/2026).
 */
@PluginDescriptor(
	name = "Outside",
	// (11/21/2026: the Plugin Hub's name, which RuneLite's getPluginDirectory needs for this plugin's own folder of files,
	// plugin-data/outside: the encounters are kept there)
	internalName = "outside",
	description = "Your gear, stats, quests, diaries and slayer task on your own page at Outside.wiki",
	tags = {"outside", "loadout", "inventory", "profile", "quests", "diaries", "slayer", "discord"}
)
public class OutsidePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(OutsidePlugin.class);
	static final String VERSION = "0.3.0";
	/** the relay a page is claimed on when there's no key yet (docs/protocol.md "Claiming a name") */
	static final String HOME_RELAY = Attach.HOME;
	/** quests and diaries read again at least this often, in ticks (5 minutes) */
	private static final int PROGRESS_EVERY = 500;
	/** the hourly sync (11/16/2026, the Hub's answer; as RuneProfile's AutoSyncScheduler, AUTO_SYNC_MINUTES = 60): a
	 *  snapshot after this many minutes logged in since the last, counted in game ticks (100 a minute), so it waits
	 *  while they're logged out */
	static final int AUTO_SYNC_MINUTES = 60;
	static final int SYNC_TICKS = AUTO_SYNC_MINUTES * 100;
	/** "Update my page" once a minute at most */
	static final long UPDATE_EVERY_MS = 60_000L;
	/** what a snapshot carries (the rest of read()'s fields are the socket's) */
	static final String[] SNAP_FIELDS = {"name", "type", "worn", "inv", "bag", "pouch", "seeds", "barrel", "quiver",
		"charges", "skills", "quests", "diaries", "combat", "slayer", "delve", "delve_lost", "clog", "counts"};   // (delve: update 2; clog: the collection log's counter; counts: kill and clue counts, 11/16/2026; delve_lost: the delve levels lost to deaths, 11/19/2026)

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

	// (the game's own item search in the chatbox, as the bank tags' icon picker: the goal item's, 11/17/2026)
	@Inject
	private net.runelite.client.game.chatbox.ChatboxItemSearch goalSearch;

	@Inject
	private ChatMessageManager chat;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	/** what they're doing as Discord shows it (11/16/2026; Presence), the client's thread */
	private final Presence presence = new Presence();
	/** the snapshot (11/16/2026): game ticks logged in since the last; one asked for (the button, the hour) and waiting
	 *  for a tick out of anywhere dangerous; when the last went (ms; 0 none this session); whether anything's been posted
	 *  this session (so logging out says so); a scene just loaded, its area read on the next tick */
	private int syncTicks;
	private volatile boolean syncDue;
	private volatile long snapAt, updateAt;
	/** a press of "Update my page" too soon, waiting for the minute (updateNow) */
	private volatile boolean updateWaiting;
	private volatile boolean posted;
	private boolean sceneDue;
	/** worlds where nothing goes to the globe: PvP, Deadman, high risk, Bounty Hunter (11/10/2026: not the PvP Arena's
	 *  or Last Man Standing's, there only for those minigames, which are ordinary minigames now) */
	private static final EnumSet<WorldType> RISKY = EnumSet.of(WorldType.PVP, WorldType.DEADMAN, WorldType.HIGH_RISK,
		WorldType.BOUNTY);
	// (quests and diaries, read again when what they depend on changes, or every 5 minutes; the slayer task's names
	// looked up again when its varps change; 11/15/2026)
	private JsonObject questsSeen, diariesSeen, slayerSeen;
	private long progressSig = Long.MIN_VALUE;
	private int progressAt = -1;
	private String slayerKey;
	/** the player's name as the game shows it, from the last tick */
	private volatile String shownName;
	/** stopped for good until the key changes (a wrong key, or another RuneLite took over) */
	private volatile boolean stopped;
	/** the character logged in, as a channel has its name (null: not known yet, or logged out). 11/15/2026, Jace: logged
	 *  into his alt, the side tab still said sburb, and the alt's kit would have gone to sburb's page: a key is now this
	 *  character's own (kept per account), and nothing goes on a key whose page isn't the character logged in. */
	private volatile String character = null;
	/** this key is a challenger's (taking a name back, 11/15/2026): what it sends only proves the name, till it's proven */
	private volatile boolean challenging = false;
	// proving, as the side tab follows it (11/17/2026): what the relay last said, the total experience in the update it
	// said "ready" to, and whether more's been gained since (noticed here, nothing sent)
	private volatile String proofState = null;
	// the register's newest note as last read (its id), and whether this login's been told of new ones
	private volatile long notesLast = 0;
	private volatile boolean notesSaid = false;
	private volatile long postXp = -1, readyXp = -1;
	private volatile boolean readyStale = false;
	/** movePage tried this session (a page under the name's old form) */
	private boolean movedThisSession = false;
	/** the seed box's and the chugging barrel's slots as last sent by the game (onItemContainerChanged), or null */
	private JsonElement seedsSeen, barrelSeen;
	/** (11/14/2026) each item's charges as the game last told of them, by item id (kept on this RuneLite's profile for
	 *  the account, so they're known after a restart); the item last checked, and on which tick; the client's thread */
	private final Map<Integer, Integer> charges = new HashMap<>();
	private int checkedItem = -1, checkedTick = -100, ticks;
	/** (11/14/2026) the Doom of Mokhaiotl board's counts as last read (also kept on the profile), or null */
	private JsonElement delveSeen;
	/** (11/19/2026) the delve levels lost to deaths (Delves: this run's, and all lost, kept on the profile), and those
	 *  lost as they stood when the board was last read (sent beside it), or null */
	private final Delves delves = new Delves();
	private JsonElement delveLostSeen;
	/** (11/14/2026) the bank changed on this tick (sent a few ticks after the last change), or -1 */
	private int bankChanged = -1;
	/** (11/16/2026) the deathbank as it was last seen, {where, locked, ids, quantities}, sent a couple of ticks after
	 *  it last changed (a withdrawal of several goes once); the tick it changed, or -1 */
	private Object[] deathbankSeen;
	private int deathbankChanged = -1;
	/** (11/16/2026, Jace: "make it fill in the moment they die too") what they carried and wore as they fell, {ids,
	 *  how many}, the region they fell in and the tick; the game's line that a deathbank was made, once it comes */
	private int[][] deathCarried;
	private int deathRegion = -1;
	private int deathTick = -1;
	private String deathLine;
	private static final MediaType JSON = MediaType.parse("application/json");
	/** the encounters (11/21/2026): the recorder, on RuneLite's event bus while the plugin's on, and its files */
	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;
	/** the frame as drawn, for the loot window's picture (round three) */
	@Inject
	private net.runelite.client.ui.DrawManager drawManager;
	private Encounters encounters;
	private EncounterShelf shelf;
	/** an encounter's file going up gzipped: 8 MB at most (the relay's MAX_FIGHT) */
	static final int FIGHT_MOST = 8 * 1024 * 1024;
	private static final MediaType GZIP = MediaType.parse("application/gzip");
	private static final MediaType OCTETS = MediaType.parse("application/octet-stream");
	/** (11/21/2026, round two) each recording's scene moment makings, by its packed file, till it's packed and built; the
	 *  sender's timer and whether a send is under way */
	private final Map<String, EncounterScene.Taken> takenFor = new java.util.concurrent.ConcurrentHashMap<>();
	private ScheduledFuture<?> sendTimer;
	private final java.util.concurrent.atomic.AtomicBoolean sending = new java.util.concurrent.atomic.AtomicBoolean();
	/** the board beside the Doom of Mokhaiotl (the Delve Calculator plugin's: its scoreboard interface, each level's
	 *  count in child 46 + 3 a level, 1 to 8, then the waves past 8) */
	private static final int DELVE_BOARD = 920, DELVE_FIRST = 46, DELVE_STEP = 3;
	/** the Doom's lair, its delves' regions (the Delve Calculator plugin's: its DELVE_REGION_IDS) */
	private static final int[] DELVE_REGIONS = {5269, 13668, 14180};

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
		shelf = new EncounterShelf(gson, this::getPluginDirectory);
		encounters = new Encounters(client, clientThread, gson, encounterHost());
		eventBus.register(encounters);
		// (round three: what a crash left behind goes up by itself too, once packed, with the switch on; Jace, 11/21/2026)
		shelf.tidy(encounterMost(), encounters.busy(), config.encounterSend(), this::listEncounters);
		// (each encounter up by itself: what's due tried every 30 s, and as each is ready; 11/21/2026)
		sendTimer = executor.scheduleWithFixedDelay(this::pumpSends, 20, 30, TimeUnit.SECONDS);
		Attach a = key();
		panel.who(a == null ? null : a.getChannel());
		if (a != null)
		{
			readMotions();
			readProof();
		}
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.stop();
		}
		if (encounters != null)
		{
			// (stopped on the client's thread, as every game call here; packed on its own thread after)
			Encounters e = encounters;
			clientThread.invoke(() -> e.shutDown("plugin turned off"));
			eventBus.unregister(e);
			encounters = null;
		}
		if (sendTimer != null)
		{
			sendTimer.cancel(false);
			sendTimer = null;
		}
		if (shelf != null)
		{
			shelf.shutDown();
		}
		// (RuneLite closing, or the plugin switched off, while they're logged in: as a logout)
		if (posted && client.getGameState() == GameState.LOGGED_IN)
		{
			post(off(), null);
		}
		posted = false;
	}

	private static JsonObject off()
	{
		JsonObject o = new JsonObject();
		o.addProperty("off", true);
		return o;
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
			readMotions();
			readProof();
			readAccess();
		}
		if (e.getKey().equals("stationKey"))
		{
			stopped = false;
			snapAt = 0;
			presence.unsent();
			showSnap();
		}
		// (sending switched off: what's on the page comes off it; the snapshot's parts switched off go with the next)
		if (e.getKey().equals("send") && !config.send())
		{
			post(Kit.cleared(), null);
			presence.unsent();
			posted = false;
		}
		if (e.getKey().equals("presence") && !config.presence())
		{
			JsonObject none = new JsonObject();
			none.add("presence", JsonNull.INSTANCE);
			post(none, null);
			presence.unsent();
		}
		// (the model: on, it goes now; off, it's taken down. The bank: off, it's taken down; on, it goes next time it's used)
		if (e.getKey().equals("model"))
		{
			if (config.model())
			{
				clientThread.invokeLater(this::sendModel);
			}
			else
			{
				configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET);
				keep("model", null);
			}
		}
		// (the goal: off, it's taken down; on, the one set goes again)
		if (e.getKey().equals("goal"))
		{
			if (config.goal())
			{
				clientThread.invokeLater(() -> sendGoal(false));
			}
			else
			{
				keep("goal", null);
			}
		}
		if (e.getKey().equals("encounters") && panel != null)
		{
			panel.recordingOn(config.encounters());
		}
		if (e.getKey().equals("encounterSend") && panel != null)
		{
			panel.sendingOn(config.encounterSend());
			// (switched on: what's waiting goes now; 11/21/2026)
			pumpSends();
		}
		if (e.getKey().equals("bank") && !config.bank())
		{
			keep("bank", null);
		}
		if (e.getKey().equals("deathbank") && !config.deathbank())
		{
			deathbankChanged = -1;
			keep("deathbank", null);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			goalPanel();
			readProof();   // (proven since the last logout? the side tab says so)
			sceneDue = true;   // (a scene loaded: its area, as Discord reads it, on the next tick)
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			// (logged out, or hopping: the area's read again where they land)
			sceneDue = false;
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			notesSaid = false;   // (the register's new notes, said again at the next login)
			// (logged out: the snapshot's and what they're doing's, said once if anything went this session)
			if (posted)
			{
				post(off(), null);
				posted = false;
			}
			presence.loggedOut();
			syncDue = false;   // (the hour's count waits while they're logged out)
			updateWaiting = false;   // (a press waiting for its minute: logged out, it doesn't go)
			if (panel != null)
			{
				panel.updateWait(0);
			}
			// (the seed box and the barrel as the game last sent them: another account may log in next)
			seedsSeen = barrelSeen = null;
			charges.clear();
			delveSeen = delveLostSeen = null;
			delves.left();
			delves.load(null);
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			character = null;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
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
		// (a delve's run over once they're away from the boss: claimed and gone, or left in the chest, nothing lost)
		if (delves.running() && !atDelve())
		{
			delves.left();
		}
		// (the bank: sent three ticks after it last changed, so a whole deposit goes once)
		if (bankChanged >= 0 && ticks - bankChanged >= 3)
		{
			bankChanged = -1;
			sendBank();
		}
		deathbankFromDeath();
		if (deathbankChanged >= 0 && ticks - deathbankChanged >= 2)
		{
			deathbankChanged = -1;
			sendDeathbank();
		}
		// (where they are, read every tick, the relay in reach or not: somewhere dangerous holds things back at once)
		WorldPoint at = here();
		int x = at == null ? -1 : at.getX(), y = at == null ? -1 : at.getY(), region = at == null ? -1 : at.getRegionID();
		boolean riskyWorld = riskyWorld();
		boolean wild = wilderness(x, y, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1);
		boolean pvpArea = pvpArea(x, y, region, client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1);
		// (the snapshot and what they're doing, 11/16/2026: never from anywhere dangerous, whatever else is switched on)
		snapTick(held(pvpWorld(), wild, pvpArea), at, me);
		boolean safe = globeSafe(riskyWorld, wild, pvpArea);
		long ms = System.currentTimeMillis();
	}

	// ---- the snapshot, and what they're doing as Discord shows it (11/16/2026, the Plugin Hub's answer;
	// docs/protocol.md, "The snapshot")

	/** Each tick logged in: the area read where a scene just loaded (as Discord reads it), the hour counted, a snapshot
	 *  asked for sent once they're somewhere safe, and what they're doing sent as it changes (cleared at once somewhere
	 *  dangerous). */
	private void snapTick(boolean danger, WorldPoint at, Player me)
	{
		if (sceneDue && at != null && me != null)
		{
			sceneDue = false;
			presence.scene(at.getRegionID(), me.getWorldLocation().getPlane());
		}
		if (!config.send() || key() == null)
		{
			return;
		}
		if (++syncTicks >= SYNC_TICKS)
		{
			syncTicks = 0;
			syncDue = true;
		}
		long now = System.currentTimeMillis();
		// (a press too soon, its minute up: it goes now, as a press would)
		if (updateWaiting && System.currentTimeMillis() - updateAt >= UPDATE_EVERY_MS)
		{
			updateWaiting = false;
			updateNow();
		}
		if (syncDue && !danger)
		{
			syncDue = false;
			syncTicks = 0;
			snapshot(now);
			return;
		}
		if (!config.presence())
		{
			return;
		}
		JsonObject p = presence.due(now, danger);
		if (p != null)
		{
			JsonObject body = new JsonObject();
			body.add("presence", p);
			presence.took(p, now);
			post(body, null);
		}
	}

	/** The snapshot (on the client's thread): what's switched on of read()'s fields (SNAP_FIELDS), every item's name and
	 *  price, and what they're doing; posted once. */
	private void snapshot(long now)
	{
		JsonObject body = snapshotBody(now, true);
		List<String> named;
		synchronized (clogNew)
		{
			named = new ArrayList<>(clogNew);
		}
		panel.snapStatus("updating your page…");
		post(body, ok ->
		{
			if (ok)
			{
				snapAt = System.currentTimeMillis();
				// (the slots named with it: gone with it, so the next update names only newer ones)
				synchronized (clogNew)
				{
					clogNew.removeAll(named);
				}
				sendHeldKeeps();   // (the Hub's version: the bank and the deathbank, held since they looked)
				showSnap();
			}
		});
	}

	/** What a snapshot carries (on the client's thread): what's switched on of read()'s fields (SNAP_FIELDS), every
	 *  item's name and price, and what they're doing. `taking`: what they're doing counted as sent (a real snapshot);
	 *  false for "Show what's sent", which only shows it. */
	JsonObject snapshotBody(long now, boolean taking)
	{
		Map<String, JsonElement> m = read();
		JsonObject body = new JsonObject();
		for (String f : SNAP_FIELDS)
		{
			if (m.containsKey(f))
			{
				body.add(f, m.get(f));
			}
		}
		JsonObject items = new JsonObject();
		for (int id : Kit.ids(m))
		{
			JsonArray np = new JsonArray();
			np.add(itemManager.getItemComposition(id).getName());
			np.add(itemManager.getItemPrice(id));
			items.add(String.valueOf(id), np);
		}
		body.add("items", items);
		body.addProperty("off", false);
		body.addProperty("paused", false);
		if (config.presence())
		{
			JsonObject p = presence.now(now, false);
			body.add("presence", p);
			if (taking)
			{
				presence.took(p, now);
			}
		}
		return body;
	}

	/** the game's chat line for a new collection log slot (its "Collection log - New addition notification" setting;
	 *  Dink's CollectionNotifier reads the same line) */
	private static final java.util.regex.Pattern CLOG_ADDED = java.util.regex.Pattern.compile("^New item added to your collection log: (.+?)\\s*$");
	/** at most this many slots' names with one update */
	static final int CLOG_NEW_MOST = 8;
	/** the slots the chat named since the last update went */
	private final List<String> clogNew = new ArrayList<>();
	/** kill counts and clue counts as the chat says them, kept per account (Counts; 11/16/2026) */
	private final Counts counts = new Counts();

	/** The item a chat line says was added to the collection log, or null. */
	static String clogAdded(String line)
	{
		java.util.regex.Matcher m = line == null ? null : CLOG_ADDED.matcher(line);
		return m != null && m.matches() ? m.group(1).trim() : null;
	}

	/** The collection log's counter (on the client's thread): {"count", "max", "new": [names]}, or null while the game
	 *  hasn't said its count. */
	private JsonObject clogRead()
	{
		int count = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT);
		int max = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_MAX);
		if (count <= 0 || max <= 0 || count > max)
		{
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("count", count);
		o.addProperty("max", max);
		JsonArray names = new JsonArray();
		synchronized (clogNew)
		{
			for (String n : clogNew)
			{
				names.add(n);
			}
		}
		o.add("new", names);
		return o;
	}

	/** "Show what's sent" (11/16/2026, update 2: as OSRS Map Companion's "Show what's sent"): exactly what "Update my
	 *  page" would post now, read on the client's thread and shown in a window; nothing's sent. */
	private void showSent()
	{
		clientThread.invokeLater(() ->
		{
			Attach a = key();
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			{
				panel.snapStatus("log in first: it shows what this character would send");
				return;
			}
			JsonObject body = snapshotBody(System.currentTimeMillis(), false);
			String text = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(body);
			String to = a == null ? "your page, once it's made" : "Outside.wiki/" + a.getChannel() + " (through " + HOME_RELAY.replaceAll("^https?://", "") + ")";
			panel.showSent(to, text, config.send());
		});
	}

	/** "Update my page" (the side tab): a snapshot now, once a minute at most, never from anywhere dangerous. */
	private void updateNow()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			{
				panel.snapStatus("log in first");
				return;
			}
			if (!config.send())
			{
				panel.snapStatus("switch on \"Send to my Outside page\" in the plugin's settings first");
				return;
			}
			if (key() == null)
			{
				panel.snapStatus("make your page first");
				return;
			}
			long now = System.currentTimeMillis();
			if (now - updateAt < UPDATE_EVERY_MS)
			{
				// (a press too soon waits, and goes by itself once the minute's up: onGameTick, updateWaiting)
				updateWaiting = true;
				panel.updateWait(updateAt + UPDATE_EVERY_MS);
				panel.snapStatus("updated a moment ago: this one goes when the minute's up");
				return;
			}
			WorldPoint at = here();
			int x = at == null ? -1 : at.getX(), y = at == null ? -1 : at.getY(), region = at == null ? -1 : at.getRegionID();
			if (held(pvpWorld(), wilderness(x, y, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1),
				pvpArea(x, y, region, client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1)))
			{
				panel.snapStatus("not from here: the Wilderness, a PvP area or world. It goes once you're out.");
				syncDue = true;
				return;
			}
			updateAt = now;
			updateWaiting = false;
			panel.updateWait(0);
			syncTicks = 0;
			snapshot(now);
		});
	}

	/** The side tab's line: when the page was last updated from here. */
	private void showSnap()
	{
		if (panel != null)
		{
			panel.snapped(snapAt);
		}
	}

	/** Posts {"game": fields} to the relay (POST /game; docs/protocol.md, "The snapshot"), for the character logged in
	 *  only, with a key for its page; `done` hears whether it was taken. A refused key stops it till the key changes. */
	private void post(JsonObject fields, java.util.function.Consumer<Boolean> done)
	{
		Attach a = key();
		if (a == null || stopped || (character == null && !fields.has("off")))
		{
			if (done != null)
			{
				done.accept(false);
			}
			return;
		}
		posted = posted || !fields.has("off");
		long sentXp = fields.has("skills") && fields.get("skills").isJsonObject() && fields.getAsJsonObject("skills").size() > 0 ? postXp : -1;
		JsonObject msg = new JsonObject();
		msg.add("game", fields);
		Request req = new Request.Builder().url(a.postUrl(VERSION)).header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, Kit.wire(gson).toJson(msg))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: the relay's out of reach", ex);
				if (done != null)
				{
					panel.snapStatus("the relay's out of reach: try again in a bit");
					done.accept(false);
				}
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					int code = res.code();
					if (code == 403)
					{
						stopped = true;
						say(challenging ? "someone else proved this name first, so your claim on it has ended"
							: "the relay didn't take your station key: paste it again in the plugin's settings");
					}
					else if (code == 429 && done != null)
					{
						panel.snapStatus("too often: try again in a minute");
					}
					else if (code == 200 && res.body() != null)
					{
						// (what the page's proof did with it, while it's still to be made: docs/protocol.md "Proving a name")
						try
						{
							JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
							if (o != null && o.has("proof"))
							{
								proofSaid(o.get("proof").getAsString(), o.has("why") ? o.get("why").getAsString() : null, sentXp);
							}
						}
						catch (IOException | RuntimeException ignored)
						{
							// (an answer it can't read: the update itself was taken)
						}
					}
					// (taken, 204 or 200 with the proof's news: the pasted key kept with the account too. 11/19/2026, Jace's test
					// in the game: only 200 kept it at first, and a taken update is 204 unless the page is still to be proven)
					if (code == 204 || code == 200)
					{
						keepKey(a);
					}
					if (done != null)
					{
						done.accept(code == 204 || code == 200);
					}
				}
			}
		});
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
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			for (String f : new String[]{"worn", "inv", "bag", "pouch", "seeds", "barrel"})
			{
				m.put(f, new JsonArray());
			}
			m.put("quiver", JsonNull.INSTANCE);
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
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("charges", new JsonObject());
		}
		if (config.delve() && delveSeen != null)
		{
			m.put("delve", delveSeen);
			// (the levels lost to deaths as they stood when the board was read: Delves; none sent till it's read again)
			m.put("delve_lost", delveLostSeen == null ? JsonNull.INSTANCE : delveLostSeen);
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("delve", JsonNull.INSTANCE);
			m.put("delve_lost", JsonNull.INSTANCE);
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
			postXp = client.getOverallExperience();
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("skills", new JsonObject());
		}
		if (config.progress())
		{
			long sig = Progress.signature(client);
			if (sig != progressSig || progressAt < 0 || ticks - progressAt >= PROGRESS_EVERY)
			{
				progressSig = sig;
				progressAt = ticks;
				questsSeen = Progress.readQuests(client);
				diariesSeen = Progress.readDiaries(client);
			}
			m.put("quests", questsSeen);
			m.put("diaries", diariesSeen);
			m.put("combat", Progress.readCombat(client));
			// (11/16/2026, the collection log's counter: slots filled and in all, as the game keeps them, and the names of
			// those the game's chat said were added since the last update; nothing till the game has said its count)
			JsonObject clog = clogRead();
			if (clog != null)
			{
				m.put("clog", clog);
			}
			// (kill counts and clue counts, as the chat last said each; kept on this RuneLite's profile)
			JsonObject c = counts.json();
			if (c != null)
			{
				m.put("counts", c);
			}
		}
		else
		{
			// (switched off: taken off their page)
			m.put("quests", JsonNull.INSTANCE);
			m.put("diaries", JsonNull.INSTANCE);
			m.put("combat", JsonNull.INSTANCE);
			m.put("clog", JsonNull.INSTANCE);
			m.put("counts", JsonNull.INSTANCE);
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
		else if (e.getContainerId() == InventoryID.BANK && config.bank())
		{
			bankChanged = ticks;
		}
		// (a deathbank's window fills GRAVESTONE too, as a gravestone's does: the gravestone's own window open, it's a
		// gravestone's, not sent; read now, while the window's IF1 says which deathbank it is)
		else if (e.getContainerId() == InventoryID.GRAVESTONE && config.deathbank()
			&& client.getWidget(InterfaceID.GravestoneGeneric.UNIVERSE) == null)
		{
			Object[] where = Kept.deathbankWhere(client.getVarpValue(VarPlayerID.IF1));
			Item[] items = e.getItemContainer() == null ? new Item[0] : e.getItemContainer().getItems();
			int[] ids = new int[items.length];
			int[] qs = new int[items.length];
			for (int i = 0; i < items.length; i++)
			{
				ids[i] = items[i].getId();
				qs[i] = items[i].getQuantity();
			}
			// (no deathbank's window open and nothing in it: emptied as they took the last out)
			deathbankSeen = where == null ? new Object[]{"", false, new int[0], new int[0], false} : new Object[]{where[0], where[1], ids, qs, false};
			deathbankChanged = ticks;
		}
	}

	// ---- (11/14/2026) charges, the delve board, the bank and the model: Kept
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
		// (a collection log slot the game's chat names, for the next update's counter: clogRead)
		if ((e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM) && config.progress())
		{
			String added = clogAdded(Text.removeTags(e.getMessage()));
			synchronized (clogNew)
			{
				if (added != null && clogNew.size() < CLOG_NEW_MOST && !clogNew.contains(added))
				{
					clogNew.add(added);
				}
			}
			if (counts.take(Text.removeTags(e.getMessage())))
			{
				configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "counts", gson.toJson(counts.json()));
			}
		}
		// (a delve level done, counted in its run: Delves)
		if ((e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM) && config.delve())
		{
			delves.done(Text.removeTags(e.getMessage()));
		}
		if (e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM)
		{
			deathbankLine(Text.removeTags(e.getMessage()));
		}
		if ((e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM) && config.goal())
		{
			goalLine(clogAdded(Text.removeTags(e.getMessage())));
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

	/** The board beside the Doom of Mokhaiotl opened: its counts read (as the Delve Calculator plugin reads them). */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == DELVE_BOARD && config.delve())
		{
			clientThread.invokeLater(this::readDelve);
		}
	}

	private void readDelve()
	{
		JsonArray counts = new JsonArray();
		boolean any = false;
		for (int i = 0; i < 9; i++)
		{
			Widget w = client.getWidget(DELVE_BOARD, DELVE_FIRST + i * DELVE_STEP);
			String t = w == null || w.getText() == null ? "" : w.getText().replaceAll("[^0-9]", "");
			int n = 0;
			if (!t.isEmpty() && t.length() < 10)
			{
				n = Integer.parseInt(t);
				any = true;
			}
			counts.add(n);
		}
		if (any)
		{
			delveSeen = counts;
			// (the levels lost to deaths, as they stand now: the board just read counts them too)
			JsonArray lost = new JsonArray();
			for (int n : delves.lost)
			{
				lost.add(n);
			}
			delveLostSeen = lost;
			saveKept();
		}
	}

	/** ::outsidemodel: the model again, as they look now. */
	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		// (::outsidegoal opens the item search to add a goal; ::outsidegoal clear takes them all off)
		if ("outsidegoal".equalsIgnoreCase(e.getCommand()))
		{
			if (e.getArguments().length > 0 && "clear".equalsIgnoreCase(e.getArguments()[0]))
			{
				goalClear();
			}
			else
			{
				goalPick();
			}
			return;
		}
		if ("outsideforget".equalsIgnoreCase(e.getCommand()))
		{
			String name = motionName(e.getArguments());
			if (name == null || e.getArguments().length == 0)
			{
				sayModel("which motion? ::outsideforget and its name");
				return;
			}
			keep("motion", name, null);
			sayModel("taking the motion \"" + name + "\" off your page");
			return;
		}
		if ("outsidemotion".equalsIgnoreCase(e.getCommand()))
		{
			if (config.model())
			{
				String name = motionName(e.getArguments());
				if (e.getArguments().length > 0 && name == null)
				{
					sayModel("a motion's name: up to 24 letters, numbers, spaces, - or _");
					return;
				}
				armMotion(name);
			}
			else
			{
				sayModel("switch on \"Character model\" in the plugin's settings first");
			}
			return;
		}
		if ("outsidemodel".equalsIgnoreCase(e.getCommand()))
		{
			if (config.model())
			{
				sendStill();
			}
			else
			{
				sayModel("switch on \"Character model\" in the plugin's settings first");
			}
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

	// ---- the goal items (Goal.java; 11/17/2026, update 3; a list since 11/19/2026) ----

	/** This account's goals, as kept (the one goal kept before 11/19/2026 read into the list once, then let go). */
	private Goal.Kept goals()
	{
		String kept = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.LIST);
		if (kept != null)
		{
			return Goal.Kept.from(kept, gson);
		}
		Integer item = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM, Integer.class);
		if (item == null || item <= 0)
		{
			return new Goal.Kept();
		}
		Goal.Kept k = Goal.Kept.fromOne(item, configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT, Long.class),
			configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS), gson);
		goalsKeep(k);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS);
		return k;
	}

	private void goalsKeep(Goal.Kept k)
	{
		if (k.list.isEmpty())
		{
			configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.LIST);
		}
		else
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, Goal.LIST, k.json(gson));
		}
	}

	private String itemName(int id)
	{
		return itemManager.getItemComposition(id).getName();
	}

	/** The game's item search in the chatbox, for a goal: as you type, only the items that match, a click adds it. */
	private void goalPick()
	{
		clientThread.invokeLater(() ->
		{
			if (!config.goal())
			{
				say("switch on \"Goal items\" in the plugin's settings first");
				return;
			}
			if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
			{
				say("log in first: your goals are your account's own");
				return;
			}
			if (goals().list.size() >= Goal.MOST)
			{
				say("you have " + Goal.MOST + " goals, the most there can be: take one off (its \u2715 in the side tab) first");
				return;
			}
			goalSearch.tooltipText("Add to my Outside goals")
				.onItemSelected(id -> clientThread.invokeLater(() -> goalChosen(id)))
				.build();
		});
	}

	/** An item chosen: added to this account's goals, and the goals sent (on the client's thread). */
	private void goalChosen(int id)
	{
		int item = itemManager.canonicalize(id);
		Goal.Kept k = goals();
		String name = itemName(item);
		if (k.find(item) != null)
		{
			say(name + " is one of your goals already");
			return;
		}
		if (!k.add(item))
		{
			say("you have " + Goal.MOST + " goals, the most there can be: take one off first");
			return;
		}
		goalsKeep(k);
		sendGoalSoon();
		goalPanel(k);
		say(k.list.size() == 1
			? "your goal is " + name + ": your Outside page shows it, your kill count and how dry you are"
			: name + " is added to your goals: your Outside page lists it" + (k.model ? " under " + itemName(k.shown) : "") + ", with your kill count and how dry you are");
	}

	/** The one shown as its 3D model on the page (the side tab's choice; on the client's thread). */
	private void goalShown(int id)
	{
		Goal.Kept k = goals();
		if (!k.show(id))
		{
			return;
		}
		goalsKeep(k);
		sendGoalSoon();
		goalPanel(k);
	}

	/** None shown as a model: every goal listed with its picture (the side tab's "No 3D model"; on the client's thread). */
	private void goalNoModel()
	{
		Goal.Kept k = goals();
		if (!k.noModel())
		{
			return;
		}
		goalsKeep(k);
		sendGoalSoon();
		goalPanel(k);
	}

	/** One goal taken off (its \u2715; on the client's thread). */
	private void goalForget(int id)
	{
		Goal.Kept k = goals();
		if (!k.forget(id))
		{
			return;
		}
		goalsKeep(k);
		goalPanel(k);
		if (k.list.isEmpty())
		{
			goalSoon++;
			keep("goal", null);
			say("your goal's off your Outside page");
		}
		else
		{
			sendGoalSoon();
			say(itemName(id) + " is off your goals");
		}
	}

	/** The side tab's goals, as this account's stand (on the client's thread). */
	private void goalPanel()
	{
		goalPanel(goals());
	}

	private void goalPanel(Goal.Kept k)
	{
		if (panel == null)
		{
			return;
		}
		List<OutsidePanel.GoalRow> rows = new ArrayList<>();
		for (Goal.One g : k.list)
		{
			rows.add(new OutsidePanel.GoalRow(g.id, itemName(g.id), g.got > 0, k.model && g.id == k.shown));
		}
		panel.goals(rows, Goal.MOST, k.model);
	}

	/** Every goal taken off (::outsidegoal clear). */
	private void goalClear()
	{
		goalsKeep(new Goal.Kept());
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS);
		goalSoon++;
		keep("goal", null);
		goalPanel();
		say("your goals are off your Outside page");
	}

	/** (the goals sent a moment after the last change, so a few presses in a row are one upload: the relay takes 24 an
	 *  hour, every kind together) */
	private int goalSoon;

	private void sendGoalSoon()
	{
		int mine = ++goalSoon;
		executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (mine == goalSoon)
			{
				sendGoal(false);
			}
		}), 2, TimeUnit.SECONDS);
	}

	/** The goals set for this account to the keep: the one shown with its model as the game draws it (its own recolours
	 *  and retextures), the rest under it, and when each was got with the counts then (on the client's thread). */
	private void sendGoal()
	{
		sendGoal(false);
	}

	/** byItself: the game's collection log line sent it, not the player's press, so in the Hub's version it waits for the
	 *  next update (Update my page, or the hourly one), as the bank does: the reviewer's terms, a press or the hourly sync. */
	private void sendGoal(boolean byItself)
	{
		Goal.Kept k = goals();
		if (!config.goal() || k.list.isEmpty())
		{
			return;
		}
		JsonObject shown = null;
		List<JsonObject> more = new ArrayList<>();
		for (Goal.One g : k.pageOrder())
		{
			net.runelite.api.ItemComposition c = itemManager.getItemComposition(g.id);
			JsonObject model = shown == null && k.model ? goalModel(c) : null;
			JsonObject one = Goal.body(g.id, c.getName(), model, g.got, g.counts);
			if (shown == null)
			{
				shown = one;
			}
			else
			{
				more.add(one);
			}
		}
		JsonObject body = Goal.body(shown, more);
		if (byItself && HOLD_KEEPS)
		{
			synchronized (keepHeld)
			{
				keepHeld.put("goal", body);
			}
			return;
		}
		keep("goal", body);
	}

	private int goalModelSaid;

	/** The item's model as the game draws it (null: it couldn't be read, said in the chat; its picture goes instead). */
	private JsonObject goalModel(net.runelite.api.ItemComposition c)
	{
		// (11/18/2026, the first goal on the Hub came without its model: its textures were copied whether it had any or
		// not, and an item with none, most of them, threw there; now each step only as the item needs it, the model sent
		// unlit if lighting it fails, and why it couldn't be read said in the chat)
		JsonObject model = null;
		String why = null;
		net.runelite.api.ModelData md = null;
		try
		{
			md = client.loadModelData(c.getInventoryModel());
			if (md == null)
			{
				why = "the game gave no model for it";
			}
			else
			{
				short[] from = c.getColorToReplace(), to = c.getColorToReplaceWith();
				if (from != null && to != null && from.length > 0)
				{
					md = md.cloneColors();
					for (int i = 0; i < Math.min(from.length, to.length); i++)
					{
						md.recolor(from[i], to[i]);
					}
				}
				short[] tf = c.getTextureToReplace(), tt = c.getTextureToReplaceWith();
				if (tf != null && tt != null && tf.length > 0 && md.getFaceTextures() != null)
				{
					md = md.cloneTextures();
					for (int i = 0; i < Math.min(tf.length, tt.length); i++)
					{
						md.retexture(tf[i], tt[i]);
					}
				}
			}
		}
		catch (RuntimeException ex)
		{
			why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
			md = null;
		}
		if (md != null)
		{
			try
			{
				Model m = md.light();
				if (m != null)
				{
					model = wholeModel(modelJson(m), null, null, null);
				}
			}
			catch (RuntimeException ex)
			{
				why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
			}
			if (model == null)
			{
				// (unlit, its own colours: the page lights it)
				try
				{
					model = Kept.model(md.getVerticesX(), md.getVerticesY(), md.getVerticesZ(), md.getVerticesCount(),
						md.getFaceIndices1(), md.getFaceIndices2(), md.getFaceIndices3(), md.getFaceCount(),
						null, null, md.getFaceColors(), md.getFaceTransparencies(), md.getFaceTextures());
				}
				catch (RuntimeException ex)
				{
					why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
				}
			}
		}
		if (model == null && goalModelSaid != c.getId())
		{
			goalModelSaid = c.getId();   // (said once an item, not with every change to the list)
			log.debug("outside: the goal's model: {}", why);
			say("your goal " + c.getName() + " goes to your page as its picture: its 3D model couldn't be read (" + (why == null ? "no reason given" : why) + ")");
		}
		return model;
	}

	/** The game's collection log line: a goal's, it's got. */
	private void goalLine(String added)
	{
		if (added == null)
		{
			return;
		}
		for (Goal.One g : goals().list)
		{
			if (g.got <= 0 && Goal.isIt(added, itemName(g.id)))
			{
				goalGot(g.id);
				return;
			}
		}
	}

	/** Loot as RuneLite reads it (a kill's, or the Loot Tracker's: a raid's chest, a casket, a minigame's reward): a
	 *  goal among it, it's got (11/18/2026, update 4: the collection log's line shows only the first time, and only
	 *  with its notification on in the game's settings). */
	private void goalLoot(java.util.Collection<net.runelite.client.game.ItemStack> items)
	{
		if (items == null || !config.goal())
		{
			return;
		}
		Goal.Kept k = goals();
		if (k.list.isEmpty())
		{
			return;
		}
		for (net.runelite.client.game.ItemStack it : items)
		{
			Goal.One g = it == null ? null : k.find(itemManager.canonicalize(it.getId()));
			if (g != null && g.got <= 0)
			{
				goalGot(g.id);
			}
		}
	}

	// (RuneLite's event bus takes a listener only when it's named "on" and its event's name: anything else and the plugin
	// won't start. 11/19/2026: update 4 named these goalOnNpcLoot and goalOnLoot, to keep clear of the field reports'
	// own listener, and the Hub's version wouldn't switch on. One listener now, which hands the reports theirs.)
	@Subscribe
	public void onNpcLootReceived(net.runelite.client.events.NpcLootReceived e)
	{
		goalLoot(e.getItems());
	}

	@Subscribe
	public void onLootReceived(net.runelite.client.plugins.loottracker.LootReceived e)
	{
		goalLoot(e.getItems());
	}

	/** A goal's got (once): when, and the counts then, kept with it and sent (in the Hub's version, with the next
	 *  update). */
	private void goalGot(int id)
	{
		Goal.Kept k = goals();
		Goal.One g = k.find(id);
		if (g == null || g.got > 0)
		{
			return;
		}
		g.got = System.currentTimeMillis();
		g.counts = counts.json();
		goalsKeep(k);
		sendGoal(true);
		goalPanel(k);
		say(HOLD_KEEPS ? "your goal " + itemName(id) + "'s got: your Outside page says so with your next update" : "your goal " + itemName(id) + "'s got: your Outside page says so");
	}

	private OutsidePanel.Hooks panelHooks()
	{
		return new OutsidePanel.Hooks()
		{
			@Override
			public void addGoal()
			{
				goalPick();
			}

			@Override
			public void showGoal(int id)
			{
				clientThread.invokeLater(() -> goalShown(id));
			}

			@Override
			public void forgetGoal(int id)
			{
				clientThread.invokeLater(() -> goalForget(id));
			}

			@Override
			public void noGoalModel()
			{
				clientThread.invokeLater(() -> goalNoModel());
			}

			@Override
			public void capture(String name)
			{
				clientThread.invokeLater(() ->
				{
					if (!config.model())
					{
						panel.status("switch on \"Character model\" in the plugin's settings first");
						return;
					}
					if (client.getLocalPlayer() == null)
					{
						panel.status("log in first");
						return;
					}
					String n = name.isEmpty() ? null : motionName(name.split("\\s+"));
					if (!name.isEmpty() && n == null)
					{
						panel.status("a name: up to 24 letters, numbers, spaces, - or _");
						return;
					}
					armMotion(n);
				});
			}

			@Override
			public void refresh()
			{
				readMotions();
				readProof();
			}

			@Override
			public void first(String name)
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				String url = a.keepUrl("motions") + "&first=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
				http.newCall(new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION).post(RequestBody.create(JSON, "")).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.status("the relay's out of reach");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						r.close();
						panel.status(name.isEmpty() ? "your page shows your model standing still first" : "your page shows \"" + name + "\" first");
					}
				});
			}

			@Override
			public void forget(String name)
			{
				keep("motion", name, null);
			}

			@Override
			public void captureStill()
			{
				clientThread.invokeLater(() ->
				{
					if (!config.model())
					{
						sayModel("switch on \"Character model\" in the plugin's settings first");
						return;
					}
					if (client.getLocalPlayer() == null)
					{
						panel.status("log in first");
						return;
					}
					sendStill();
				});
			}

			@Override
			public void removeModel()
			{
				configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET);
				keep("model", null);
				panel.status("taking your model off your page");
			}

			@Override
			public boolean ground()
			{
				return config.ground();
			}

			@Override
			public void setGround(boolean on)
			{
				configManager.setConfiguration(OutsideConfig.GROUP, "ground", on);
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
					panel.keyStatus("copied: on your other computer, paste it in Outside's settings (Advanced, Station key)");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.keyStatus("the clipboard's busy: try again");
				}
			}

			@Override
			public void update()
			{
				updateNow();
			}

			@Override
			public void showSent()
			{
				OutsidePlugin.this.showSent();
			}

			@Override
			public void copyDink()
			{
				Attach a = key();
				if (a == null)
				{
					panel.dinkStatus("make your page first");
					return;
				}
				try
				{
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(a.dinkAddress()), null);
					panel.dinkStatus("copied: paste it into Dink's Primary Webhook URLs, on its own line");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.dinkStatus("the clipboard's busy: try again");
				}
			}

			@Override
			public void aetherAsMe()
			{
				Attach a = key();
				if (a == null)
				{
					say("log in on a character with a page first");
					return;
				}
				net.runelite.client.util.LinkBrowser.browse(a.aetherPage(friendsOnly ? watchCode(false) : null));
			}

			@Override
			public void openPage()
			{
				Attach a = key();
				if (a != null)
				{
					net.runelite.client.util.LinkBrowser.browse(a.page(friendsOnly ? watchCode(false) : null));
				}
			}

			@Override
			public void friendsOnly(boolean on)
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				String code = on ? watchCode(true) : "";
				JsonObject body = new JsonObject();
				body.addProperty("code", code);
				panel.accessStatus(on ? "making it friends only…" : "opening it to anyone…");
				http.newCall(new Request.Builder().url(a.codeUrl())
					.header("User-Agent", "outside-plugin/" + VERSION).put(RequestBody.create(JSON, gson.toJson(body))).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.accessStatus("the relay's out of reach: try again");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						try (Response res = r)
						{
							if (res.code() != 200)
							{
								panel.accessStatus("the relay said no (" + res.code() + ")");
								return;
							}
							friendsOnly = on;
							panel.access(on);
							panel.accessStatus(on ? "friends only now: copy the friends' link for them" : "open to anyone now");
						}
					}
				});
			}

			@Override
			public void copyFriendsLink()
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				try
				{
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(a.page(watchCode(true))), null);
					panel.accessStatus("copied: give it only to friends");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.accessStatus("the clipboard's busy: try again");
				}
			}

			@Override
			public void takeDown()
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				panel.accessStatus("taking it down…");
				http.newCall(new Request.Builder().url(a.takeDownUrl())
					.header("User-Agent", "outside-plugin/" + VERSION).delete().build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.accessStatus("the relay's out of reach: nothing was taken down");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						try (Response res = r)
						{
							if (res.code() != 204)
							{
								panel.accessStatus("the relay said no (" + res.code() + "): nothing was taken down");
								return;
							}
						}
						// (the key's no good now: forgotten here too, and in the settings if it was this page's)
						String gone = stationKey();
						configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, "stationKey");
						configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, "watchCode");
						if (gone != null && gone.trim().equals(config.stationKey().trim()))
						{
							configManager.unsetConfiguration(OutsideConfig.GROUP, "stationKey");
						}
						friendsOnly = false;
						posted = false;   // (nothing more to say to a page that's gone)
						panel.accessStatus(null);
						panel.who(null);
						say("your Outside page is taken down");
					}
				});
			}

			/** "Edit my page" (11/21/2026, update 6; round nine, Jace: "it should probably just be an edit your page
			 *  button"): the page opened as its owner, as the register's button opens it (owned below): arranging, the
			 *  theme song, the encounters' moments and the register, for 12 hours. */
			@Override
			public void editPage()
			{
				owned(panel::editStatus);
			}

			/** The register's own button, the same as Edit my page (round nine). */
			@Override
			public void tendChat()
			{
				owned(panel::tendStatus);
			}

			/** The page opened as its owner (11/16/2026, "Tend my page's chat"; since 11/21/2026, round nine, Edit my page
			 *  too; docs/protocol.md "Tending the page's chat from the page"): a token made here, handed to the relay with
			 *  the page's key, and the page opened with it in the fragment, which no server sees. The page, the relay
			 *  and the fight pages take it for everything the owner does there, for 12 hours: the register, arranging
			 *  and the theme song, the encounters' moments. */
			private void owned(java.util.function.Consumer<String> status)
			{
				Attach a = key();
				if (a == null)
				{
					status.accept("log in on a character with a page first");
					return;
				}
				String token = Tend.token(new java.security.SecureRandom());
				status.accept("opening your page…");
				http.newCall(new Request.Builder().url(a.tendUrl())
					.header("User-Agent", "outside-plugin/" + VERSION)
					.post(RequestBody.create(JSON, Tend.body(token, gson))).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						status.accept("the relay's out of reach: try again in a moment");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						try (Response res = r)
						{
							if (res.code() != 200)
							{
								status.accept("the relay said no (" + res.code() + ")");
								return;
							}
						}
						status.accept("opened in your browser as its owner, for 12 hours in that browser");
						// (read: what's there now isn't new any more)
						if (notesLast > 0)
						{
							configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "registerSeen", Long.toString(notesLast));
						}
						panel.notes(0);
						net.runelite.client.util.LinkBrowser.browse(a.tendPage(token));
					}
				});
			}
			@Override
			public boolean recordingOn()
			{
				return config.encounters();
			}

			@Override
			public void setRecordingOn(boolean on)
			{
				configManager.setConfiguration(OutsideConfig.GROUP, "encounters", on);
			}

			@Override
			public void recordToggle()
			{
				clientThread.invoke(() ->
				{
					if (encounters == null)
					{
						return;
					}
					if (encounters.running())
					{
						encounters.stopByHand();
						panel.encounterStatus(null);
						return;
					}
					String no = encounters.startByHand();
					panel.encounterStatus(no);
				});
			}

			@Override
			public void encountersShown()
			{
				panel.encounterStatus(null);
				listEncounters();
				readFights();
			}

			@Override
			public void putFight(String file, boolean shown)
			{
				putEncounter(file, shown);
			}

			@Override
			public void fightShown(String file, boolean shown)
			{
				showEncounter(file, shown);
			}

			@Override
			public void openFight(String file)
			{
				openEncounter(file);
			}

			@Override
			public boolean sendingOn()
			{
				return config.encounterSend();
			}

			@Override
			public void setSendingOn(boolean on)
			{
				configManager.setConfiguration(OutsideConfig.GROUP, "encounterSend", on);
			}

			@Override
			public void keepFight(String file, boolean keep)
			{
				markEncounter(file, "kept=" + (keep ? "1" : "0"), keep ? "kept for good on your page" : "no longer kept for good: it goes with its days");
			}

			@Override
			public void featureFight(String file)
			{
				markEncounter(file, "featured=1", "featured on your page (and kept for good)");
			}

			@Override
			public void takeDownFight(String file)
			{
				takeDownEncounter(file);
			}

			@Override
			public void deleteEncounter(String file)
			{
				if (shelf != null && encounters != null)
				{
					shelf.delete(file, encounters.busy(), OutsidePlugin.this::listEncounters, panel::encounterStatus);
				}
			}

			@Override
			public void copyEncountersFolder()
			{
				if (shelf == null)
				{
					return;
				}
				shelf.where(path ->
				{
					try
					{
						java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(path), null);
						panel.encounterStatus("copied: " + path);
					}
					catch (IllegalStateException | java.awt.HeadlessException ex)
					{
						panel.encounterStatus("the clipboard's busy: it's " + path);
					}
				}, panel::encounterStatus);
			}
		};
	}

	/** Whether this character's page is friends only, as last read (readAccess) or set here. */
	private volatile boolean friendsOnly;

	/** This character's watch code, kept with their account; made the first time it's asked for (make), else "". */
	private String watchCode(boolean make)
	{
		String c = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "watchCode");
		if ((c == null || c.isEmpty()) && make)
		{
			byte[] b = new byte[9];
			new java.security.SecureRandom().nextBytes(b);
			c = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "watchCode", c);
		}
		return c == null ? "" : c;
	}

	/** Who sees the page, read as anyone would: the kept game refused without a code is friends only (an outstation
	 *  transmitting sets its own watch code, so this reads what's true now, not what was set here). */
	private void readAccess()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			return;
		}
		http.newCall(new Request.Builder().url(a.gameReadUrl()).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
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
					friendsOnly = res.code() == 403;
					panel.access(friendsOnly);
				}
			}
		});
	}

	/** The station key for the character logged in: their own (kept with their account by "Make my Outside page"), else
	 *  the one in the plugin's settings. */
	private String stationKey()
	{
		String own = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "stationKey");
		// (11/17/2026, the review before the outstation's v0.2.0: a key pasted in the settings, for the character
		// logged in, wins over the one Make my Outside page kept with the account, so a player who made a page here
		// first and was issued an outstation later can put its key in Advanced, as the outstation's setup says)
		String pasted = config.stationKey();
		if (pasted != null && !pasted.trim().isEmpty())
		{
			Attach p = Attach.parse(pasted, gson);
			String c = character;
			if (p != null && (c == null || isOwn(p.getChannel(), c)))
			{
				return pasted;
			}
		}
		return own != null && !own.trim().isEmpty() ? own : pasted;
	}

	/** A pasted key the relay's just taken kept with the account too (11/19/2026: Jace's key, pasted from his outstation,
	 *  was only in the settings, and his PC's copy went missing, likely RuneLite's cloud sync between his two computers;
	 *  the one kept with the account is a second copy, which RuneLite's settings Reset doesn't touch either). */
	private void keepKey(Attach taken)
	{
		String pasted = config.stationKey();
		Attach p = pasted == null ? null : Attach.parse(pasted, gson);
		if (p == null || taken == null || !p.getChannel().equals(taken.getChannel()) || !p.getKey().equals(taken.getKey())
			|| character == null || !isOwn(p.getChannel(), character))
		{
			return;
		}
		String own = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "stationKey");
		if (!pasted.trim().equals(own == null ? "" : own.trim()))
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "stationKey", pasted.trim());
		}
	}

	/** The key read, or null: none, or one whose page isn't the character logged in (another account's). */
	private Attach key()
	{
		Attach a = Attach.parse(stationKey(), gson);
		String c = character;
		return a != null && c != null && !isOwn(a.getChannel(), c) ? null : a;
	}

	/** Another character logged in (or the first, once its name is known): the side tab, and the relay on their key. */
	private void characterChanged()
	{
		progressAt = -1;
		questsSeen = null;
		slayerKey = null;
		slayerSeen = null;
		Attach any = Attach.parse(stationKey(), gson), a = key();
		// (a page under the name's old form: moved to the name as it is, once a session)
		if (a != null && character != null && !a.getChannel().equalsIgnoreCase(character) && !movedThisSession)
		{
			movedThisSession = true;
			long hash = client.getAccountHash();
			movePage(a, character, hash == -1 ? null : Long.toString(hash));
		}
		if (panel != null)
		{
			panel.who(a == null ? null : a.getChannel());
			panel.otherKey(any != null && a == null ? any.getChannel() : null, character);
			readMotions();
			readProof();
			readAccess();
		}
		stopped = false;
		snapAt = 0;
		showSnap();
	}

	/** A player's name as a channel has it (since 11/16/2026, Jace: "a space and underscored name are the same name in
	 *  practice, in the game. oranberries and oran berries/oran_berries are not the same"): lower case, each run of the
	 *  spaces, hyphens and underscores the game treats alike one "_" (its names use a no-break space), none at either
	 *  end, 1 to 12 long; null when it can't be one. "Oran Berries" is oran_berries; "OranBerries", oranberries. */
	static String channelName(String shown)
	{
		if (shown == null)
		{
			return null;
		}
		String n = shown.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\u00a0_-]+", "_").replaceAll("^_+|_+$", "");
		return n.length() <= 12 && n.matches("[a-z0-9]+(_[a-z0-9]+)*") ? n : null;
	}

	/** Whether a key's channel is this character's: its own form, or a page made before 11/16/2026 (no "_" in it, the
	 *  name's spaces taken out), which stays theirs till it's moved (movePage). */
	static boolean isOwn(String keyChannel, String character)
	{
		if (keyChannel == null || character == null)
		{
			return false;
		}
		String k = keyChannel.toLowerCase(java.util.Locale.ROOT);
		return k.equals(character) || (k.indexOf('_') < 0 && k.equals(character.replace("_", "")));
	}

	/** A page made before 11/16/2026 under the name with its spaces taken out (outside.wiki/oranberries for Oran Berries)
	 *  moved to the name as it is (oran_berries): a page claimed under it (a new key, unproven till the next logout's
	 *  look, as any claim), the old one taken down with the old key once the new is theirs. Once a session; a name
	 *  someone else has proven or was vouched (409), or the relay out of reach, leaves the old page as it is. */
	private void movePage(Attach old, String name, String account)
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", name);
		if (account != null)
		{
			body.addProperty("account", account);
		}
		http.newCall(new Request.Builder().url(HOME_RELAY + "/claim").header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, gson.toJson(body))).build()).enqueue(new Callback()
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
					JsonObject o = null;
					try
					{
						o = gson.fromJson(res.body() != null ? res.body().string() : "", JsonObject.class);
					}
					catch (RuntimeException ignored)
					{
					}
					if (res.code() != 200 || o == null || !o.has("code") || Attach.parse(o.get("code").getAsString(), gson) == null
						|| (o.has("proof") && "challenging".equals(o.get("proof").getAsString())))
					{
						return;
					}
					String gone = stationKey();
					configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "stationKey", o.get("code").getAsString());
					if (gone != null && gone.trim().equals(config.stationKey().trim()))
					{
						configManager.unsetConfiguration(OutsideConfig.GROUP, "stationKey");
					}
					syncDue = true;
					say("your Outside page moved to Outside.wiki/" + name + " (your name as the game has it)");
					http.newCall(new Request.Builder().url(old.takeDownUrl()).header("User-Agent", "outside-plugin/" + VERSION)
						.delete().build()).enqueue(new Callback()
					{
						@Override
						public void onFailure(Call call, IOException ex)
						{
						}

						@Override
						public void onResponse(Call call, Response r2)
						{
							r2.close();
						}
					});
				}
				catch (IOException ignored)
				{
				}
			}
		});
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
						// (its first snapshot, on the next tick somewhere safe: they asked for their page)
						syncDue = true;
						if (back)
						{
							// (taking a name back: someone else claimed it first and hasn't proven it)
							panel.claiming(false, "Outside.wiki/" + name + " was claimed by someone who hasn't proven it. Gain any experience,"
								+ " press Update my page, wait a minute, then log out: when the official hiscores show it, the page is yours.");
							say("you're taking back Outside.wiki/" + name + ": gain any experience, press Update my page, wait a minute, then log out");
						}
						else
						{
							panel.claiming(false, "your page: Outside.wiki/" + name + " (marked unproven till it's proven yours)");
							say("your Outside page is made: Outside.wiki/" + name);
						}
						return;
					}
					if (res.code() == 409)
					{
						// (11/19/2026: where its key is, Jace's PC having lost his: on the computer that shows the page, or the outstation)
						panel.claiming(false, "Outside.wiki/" + name + " is proven or vouched for already. If it's yours, copy its key on a"
							+ " computer where Outside shows your page (the Page tab's Copy my station key) and paste it in this plugin's"
							+ " settings (Advanced, Station key).");
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

	/** What the relay said of the page's proof, into the side tab: from a read of it, or an update's own answer (`sentXp`:
	 *  the total experience that update carried, -1 for a read). A "ready" is remembered with it, so experience gained
	 *  after it can be noticed (onStatChanged). Skills switched off, nothing can prove it: said so. */
	private void proofSaid(String proof, String why, long sentXp)
	{
		proofState = proof;
		challenging = "challenging".equals(proof);
		boolean open = "unproven".equals(proof) || challenging;
		if (!open || why == null || !why.startsWith("ready"))
		{
			readyXp = -1;
		}
		else if (sentXp > 0)
		{
			readyXp = sentXp;
			readyStale = false;
		}
		if (open && !config.skills())
		{
			why = "Skills is switched off in the plugin's settings (On your page): proving needs it on, as it's your"
				+ " experience the official hiscores confirm.";
		}
		else if (open && readyStale && readyXp > 0)
		{
			why = STALE;
		}
		if (panel != null)
		{
			panel.proof(proof, why);
		}
	}

	private static final String STALE = "You've gained experience since your page said ready: once you've stopped, press"
		+ " Update my page again, then log out when this says ready.";

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
		String seen = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "registerSeen");
		http.newCall(new Request.Builder().url(a.proofUrl() + "?key=" + java.net.URLEncoder.encode(a.getKey(), java.nio.charset.StandardCharsets.UTF_8)
			+ "&seen=" + (seen != null && seen.matches("\\d{1,12}") ? seen : "0"))
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
					proofSaid(proof, o != null && o.has("why") ? o.get("why").getAsString() : null, -1);
					// (the register's notes not read here yet, 11/17/2026: the side tab's line, and once a login a line in
					// the chat box)
					if (o != null && o.has("notes") && o.get("notes").isJsonObject())
					{
						JsonObject n = o.getAsJsonObject("notes");
						int count = n.has("n") ? n.get("n").getAsInt() : 0;
						notesLast = n.has("last") ? n.get("last").getAsLong() : 0;
						panel.notes(count);
						if (count > 0 && !notesSaid)
						{
							notesSaid = true;
							say(count == 1 ? "1 new note in your Outside page's register (the side tab's Page tab)"
								: count + " new notes in your Outside page's register (the side tab's Page tab)");
						}
					}
				}
				catch (IOException | RuntimeException ignored)
				{
				}
			}
		});
	}

	private void readMotions()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			return;
		}
		String url = a.keepUrl("motions").replaceFirst("\\?key=[^&]*", "?code=");
		http.newCall(new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.showMotions(null, "");
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try
				{
					String t = r.body() == null ? "" : r.body().string();
					JsonObject o = gson.fromJson(t, JsonObject.class);
					List<OutsidePanel.Motion> list = new ArrayList<>();
					if (o != null && o.has("motions"))
					{
						for (JsonElement e : o.getAsJsonArray("motions"))
						{
							JsonObject m = e.getAsJsonObject();
							list.add(new OutsidePanel.Motion(m.get("name").getAsString(), m.has("frames") ? m.get("frames").getAsInt() : 0));
						}
					}
					panel.showMotions(list, o != null && o.has("first") && !o.get("first").isJsonNull() ? o.get("first").getAsString() : (list.stream().anyMatch(m -> "idle".equals(m.name)) ? "idle" : ""));
				}
				catch (IOException | RuntimeException ex)
				{
					panel.showMotions(null, "");
				}
				finally
				{
					r.close();
				}
			}
		});
	}

	/** The account's own still, set (Capture a still, ::outsidemodel): a capture no longer replaces it. */
	static final String STILL_SET = "stillSet";

	/** Capture a still (11/18/2026, update 4, Cranky): the model as it is the moment it's pressed, mid-emote too, as the
	 *  page's still, kept until another still or the model's taken off. */
	private void sendStill()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getModel() == null)
		{
			sayModel("your model isn't there to send yet: try Capture a still in a moment");
			return;
		}
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET, true);
		sendModel();
	}

	private void sendModel()
	{
		Player me = client.getLocalPlayer();
		Model m = me == null ? null : me.getModel();
		if (m == null)
		{
			sayModel("your model isn't there to send yet: try Capture a still in a moment");
			return;
		}
		// (each written out the moment it's read: the client hands out one shared model for anything animated, so reading
		// the pet's after the player's overwrote the player's; 11/15/2026, Jace saw only his pet)
		Snap snap = snap(m, true);
		// (11/15/2026: their effects as they are now, each a model of its own, and the ground round them if they let it go)
		List<Fx> fx = effects(me, null);
		JsonObject player = Kept.trim(snap.json, ownOf(snap, fx));
		addEffects(player, fx);
		addGround(player, me);
		Model pet = petModel();
		keep("model", wholeModel(player, pet == null ? null : modelJson(pet), null, null));
	}

	/** The pet following them (11/15/2026, Jace: "one thing i would like to have it do is capture the pet"), or null. */
	private Model petModel()
	{
		NPC pet = client.getFollower();
		return pet == null ? null : pet.getModel();
	}

	// ---- effects and the ground (11/15/2026, Jace: "can you build the effects (spot animations) and the ground into the
	// model viewer/capturer?"). Effects: each spot animation on them (a special attack's, a teleport's, an emote's), its
	// own model raised by its height, kept apart from theirs (Kept.ownVertices: the client may merge them into the
	// player's model), so the page can draw them, and a motion's frames carry them frame by frame. The ground: the tiles
	// round them (GROUND_TILES each way) as the scene has them, painted and shaped, turned into their model's own space;
	// only with "Ground under my model" on, and only on a capture they ask for (never the live view's own).

	private static final int GROUND_TILES = 5, FX_MOST = 4, FX_MODELS = 8;

	/** A model read at once (the client's model object is shared): its vertices copied, and as JSON if asked. */
	private static final class Snap
	{
		JsonObject json;
		float[] x, z, y;
		int n;
	}

	private Snap snap(Model m, boolean json)
	{
		Snap s = new Snap();
		s.n = m.getVerticesCount();
		s.x = java.util.Arrays.copyOf(m.getVerticesX(), s.n);
		s.y = java.util.Arrays.copyOf(m.getVerticesY(), s.n);
		s.z = java.util.Arrays.copyOf(m.getVerticesZ(), s.n);
		s.json = json ? modelJson(m) : null;
		return s;
	}

	/** One effect on them now: its key (id, vertices, faces), its vertices (at its height, as RuneLite has them), and
	 *  its model as JSON unless it's one already known. */
	private static final class Fx
	{
		String key;
		JsonObject json;
		float[] x, y, z;
		int n;
	}

	private List<Fx> effects(Player me, Set<String> known)
	{
		List<Fx> out = new ArrayList<>();
		if (me.getSpotAnims() == null)
		{
			return out;
		}
		for (ActorSpotAnim sa : me.getSpotAnims())
		{
			if (out.size() >= FX_MOST)
			{
				break;
			}
			Model m = sa.getModel();
			if (m == null || m.getVerticesCount() <= 0 || m.getFaceCount() <= 0)
			{
				continue;
			}
			Fx f = new Fx();
			f.n = m.getVerticesCount();
			f.key = sa.getId() + "." + f.n + "." + m.getFaceCount();
			f.x = java.util.Arrays.copyOf(m.getVerticesX(), f.n);
			f.y = java.util.Arrays.copyOf(m.getVerticesY(), f.n);
			f.z = java.util.Arrays.copyOf(m.getVerticesZ(), f.n);
			// (11/19/2026: taken as RuneLite hands it over, already at its height. Raised again by sa.getHeight(), every
			// effect sat one height too high: a friend's arrow drawn from his bow came out 96 over it, at the top of his
			// head (Jace: "my friends SalutCFlagg's arrow its flying above his bow, make it look right"), and Ja's
			// casting at 308, over his head, not at his chest)
			if (known == null || !known.contains(f.key))
			{
				f.json = Kept.model(f.x, f.y, f.z, f.n, m.getFaceIndices1(), m.getFaceIndices2(), m.getFaceIndices3(), m.getFaceCount(),
					m.getFaceColors1(), m.getFaceColors2(), m.getFaceColors3(), m.getUnlitFaceColors(), m.getFaceTransparencies(),
					m.getFaceTextures(), m.getFaceRenderPriorities(), m.getTextureFaces(), m.getTexIndices1(), m.getTexIndices2(),
					m.getTexIndices3());
			}
			out.add(f);
		}
		return out;
	}

	/** How many of the snapped model's vertices are the player's own (the effects merged after them, in either order). */
	private static int ownOf(Snap s, List<Fx> fx)
	{
		if (fx == null || fx.isEmpty())
		{
			return s.n;
		}
		for (int pass = 0; pass < 2; pass++)
		{
			List<Fx> l = new ArrayList<>(fx);
			if (pass == 1)
			{
				java.util.Collections.reverse(l);
			}
			List<float[]> xs = new ArrayList<>(), zs = new ArrayList<>();
			int[] counts = new int[l.size()];
			for (int k = 0; k < l.size(); k++)
			{
				xs.add(l.get(k).x);
				zs.add(l.get(k).z);
				counts[k] = l.get(k).n;
			}
			int own = Kept.ownVertices(s.x, s.z, s.n, xs, zs, counts);
			if (own != s.n)
			{
				return own;
			}
		}
		return s.n;
	}

	/** The still capture's effects: "fx", each a model. */
	private static void addEffects(JsonObject o, List<Fx> fx)
	{
		JsonArray a = new JsonArray();
		for (Fx f : fx)
		{
			if (f.json != null)
			{
				a.add(f.json);
			}
		}
		if (a.size() > 0)
		{
			o.add("fx", a);
		}
	}

	/** A motion's effects, frame by frame: each effect's model once ("m", by its key), then each frame's effects there,
	 *  their key and their vertices then ("f", one list a frame). */
	private static final class FxTrack
	{
		final Map<String, JsonObject> models = new LinkedHashMap<>();
		final List<JsonArray> rows = new ArrayList<>();

		void add(List<Fx> fx)
		{
			JsonArray row = new JsonArray();
			for (Fx f : fx)
			{
				if (!models.containsKey(f.key))
				{
					if (f.json == null || models.size() >= FX_MODELS)
					{
						continue;
					}
					models.put(f.key, f.json);
				}
				JsonObject e = new JsonObject();
				e.addProperty("k", f.key);
				e.addProperty("v", Kept.frame(f.x, f.y, f.z, f.n));
				row.add(e);
			}
			rows.add(row);
		}

		void into(JsonObject frames)
		{
			if (models.isEmpty())
			{
				return;
			}
			JsonObject fx = new JsonObject(), m = new JsonObject();
			models.forEach(m::add);
			JsonArray f = new JsonArray();
			rows.forEach(f::add);
			fx.add("m", m);
			fx.add("f", f);
			frames.add("fx", fx);
		}
	}

	/** The ground round them, with "Ground under my model" on: "ground", a model in theirs's own space. */
	private void addGround(JsonObject o, Player me)
	{
		if (!config.ground())
		{
			return;
		}
		try
		{
			JsonObject g = groundJson(me);
			if (g != null && g.getAsJsonArray("f").size() > 0)
			{
				o.add("ground", g);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("outside: the ground", ex);
		}
	}

	private JsonObject groundJson(Player me)
	{
		WorldView wv = me.getWorldView();
		LocalPoint lp = me.getLocalLocation();
		if (wv == null || lp == null)
		{
			return null;
		}
		Scene scene = wv.getScene();
		int plane = wv.getPlane();
		Tile[][][] tiles = scene.getTiles();
		int[][][] hs = wv.getTileHeights();
		int px = lp.getX(), py = lp.getY(), ph = Perspective.getTileHeight(client, lp, plane);
		// (the client turns a model by its orientation as it draws it (Perspective.modelToCanvas): x' = x cos + z sin,
		// z' = z cos - x sin; the ground's turned back the other way into the model's own space)
		double ang = me.getOrientation() * Math.PI / 1024.0, cs = Math.cos(ang), sn = Math.sin(ang);
		int sx0 = lp.getSceneX(), sy0 = lp.getSceneY();
		List<int[]> tris = new ArrayList<>();
		for (int dx = -GROUND_TILES; dx <= GROUND_TILES; dx++)
		{
			for (int dy = -GROUND_TILES; dy <= GROUND_TILES; dy++)
			{
				int x = sx0 + dx, y = sy0 + dy;
				if (x < 0 || y < 0 || x >= tiles[plane].length - 1 || y >= tiles[plane][x].length - 1)
				{
					continue;
				}
				Tile t = tiles[plane][x][y];
				if (t == null)
				{
					continue;
				}
				int bx = x * 128, by = y * 128;
				SceneTilePaint p = t.getSceneTilePaint();
				if (p != null)
				{
					int tex = p.getTexture();
					int[] sw = {bx, hs[plane][x][y], by}, se = {bx + 128, hs[plane][x + 1][y], by};
					int[] ne = {bx + 128, hs[plane][x + 1][y + 1], by + 128}, nw = {bx, hs[plane][x][y + 1], by + 128};
					tris.add(tri(ne, nw, se, p.getNeColor(), p.getNwColor(), p.getSeColor(), tex, bx, by, px, py, ph, cs, sn));
					tris.add(tri(sw, se, nw, p.getSwColor(), p.getSeColor(), p.getNwColor(), tex, bx, by, px, py, ph, cs, sn));
				}
				SceneTileModel tm = t.getSceneTileModel();
				if (tm != null && tm.getFaceX() != null)
				{
					int[] fa = tm.getFaceX(), fb = tm.getFaceY(), fc = tm.getFaceZ(), vx = tm.getVertexX(), vy = tm.getVertexY(), vz = tm.getVertexZ();
					int[] ca = tm.getTriangleColorA(), cb = tm.getTriangleColorB(), cc = tm.getTriangleColorC(), tt = tm.getTriangleTextureId();
					for (int i = 0; i < fa.length; i++)
					{
						int[] a = {vx[fa[i]], vy[fa[i]], vz[fa[i]]}, b = {vx[fb[i]], vy[fb[i]], vz[fb[i]]}, c = {vx[fc[i]], vy[fc[i]], vz[fc[i]]};
						tris.add(tri(a, b, c, ca[i], cb[i], cc[i], tt == null ? -1 : tt[i], bx, by, px, py, ph, cs, sn));
					}
				}
			}
		}
		return Kept.ground(tris);
	}

	/** One of the ground's triangles as Kept.ground takes it: each corner (x, height, the scene's y) moved into the
	 *  player's model space, its colours, its texture and each corner's place on its tile as u, v. */
	private static int[] tri(int[] a, int[] b, int[] c, int ca, int cb, int cc, int tex, int bx, int by, int px, int py, int ph, double cs, double sn)
	{
		int[] q = new int[19];
		int[][] k = {a, b, c};
		for (int i = 0; i < 3; i++)
		{
			double dx = k[i][0] - px, dz = k[i][2] - py;
			q[i * 3] = (int) Math.round(dx * cs - dz * sn);
			q[i * 3 + 1] = k[i][1] - ph;
			q[i * 3 + 2] = (int) Math.round(dz * cs + dx * sn);
			q[13 + i * 2] = Math.round((k[i][0] - bx) / 128f * 4096);
			q[14 + i * 2] = Math.round((k[i][2] - by) / 128f * 4096);
		}
		q[9] = ca;
		q[10] = cb;
		q[11] = cc;
		q[12] = tex;
		return q;
	}

	private JsonObject modelJson(Model m)
	{
		// (11/15/2026: the game's own lighting and textures too, for the page to draw it as the game does)
		return Kept.model(m.getVerticesX(), m.getVerticesY(), m.getVerticesZ(), m.getVerticesCount(),
			m.getFaceIndices1(), m.getFaceIndices2(), m.getFaceIndices3(), m.getFaceCount(),
			m.getFaceColors1(), m.getFaceColors2(), m.getFaceColors3(), m.getUnlitFaceColors(), m.getFaceTransparencies(),
			m.getFaceTextures(), m.getFaceRenderPriorities(), m.getTextureFaces(), m.getTexIndices1(), m.getTexIndices2(),
			m.getTexIndices3());
	}

	/** The player's model, their pet's beside it (if one's following), each with the frames of a motion if one was
	 *  recorded, every texture either uses and the brightness the player has the game at (the palette's). */
	private JsonObject wholeModel(JsonObject o, JsonObject petJson, JsonObject frames, JsonObject petFrames)
	{
		JsonObject pet = petJson;
		if (frames != null)
		{
			o.add("frames", frames);
		}
		if (pet != null)
		{
			JsonObject p = pet;
			if (petFrames != null)
			{
				p.add("frames", petFrames);
			}
			o.add("pet", p);
		}
		// each texture it uses, as the client has it (128 x 128, 0 a see-through texel), a PNG each
		JsonObject pics = new JsonObject();
		List<JsonObject> parts = new ArrayList<>();
		parts.add(o);
		if (pet != null)
		{
			parts.add(o.getAsJsonObject("pet"));
		}
		// (11/15/2026: the effects' and the ground's textures too)
		if (o.has("ground"))
		{
			parts.add(o.getAsJsonObject("ground"));
		}
		if (o.has("fx"))
		{
			o.getAsJsonArray("fx").forEach(e -> parts.add(e.getAsJsonObject()));
		}
		if (o.has("frames") && o.getAsJsonObject("frames").has("fx"))
		{
			o.getAsJsonObject("frames").getAsJsonObject("fx").getAsJsonObject("m").entrySet().forEach(e -> parts.add(e.getValue().getAsJsonObject()));
		}
		for (JsonObject part : parts)
		{
			if (!part.has("t") || client.getTextureProvider() == null)
			{
				continue;
			}
			for (JsonElement e : part.getAsJsonArray("t"))
			{
				int id = e.getAsInt();
				if (id < 0 || pics.has(String.valueOf(id)) || pics.size() >= 16)
				{
					continue;
				}
				String png = texturePng(id);
				if (png != null)
				{
					pics.addProperty(String.valueOf(id), png);
				}
			}
		}
		if (pics.size() > 0)
		{
			o.add("tex", pics);
		}
		if (client.getTextureProvider() != null)
		{
			o.addProperty("b", Math.round(client.getTextureProvider().getBrightness() * 1000) / 1000.0);
		}
		return o;
	}

	// ---- the motion loop (11/15/2026, Jace: "build the pet and the motion loop"): ::outsidemotion gets ready, then
	// records the next animation they do (an emote, a special attack), from its start to its end, at most MOTION_MOST
	// frames; nothing begun within MOTION_WAIT, it records their stance standing (its breathing) for MOTION_IDLE. A frame
	// every MOTION_EVERY client cycles (20 ms each: 12.5 frames a second), the pet's with the player's.
	private static final int MOTION_EVERY = 4, MOTION_MOST = 50, MOTION_WAIT = 400, MOTION_IDLE = 150;
	private int motionArmed = -1, motionStart = -1, motionLast;
	private boolean motionIdle;
	private JsonObject motionBase, motionPet;
	private FxTrack motionFx = new FxTrack(), autoFx = new FxTrack();
	private int motionCount, motionPetCount;
	private final List<String> motionFrames = new ArrayList<>(), motionPetFrames = new ArrayList<>();

	/** A motion's name from the command's words (lower case), or null: none given, or not a name the relay takes. */
	static String motionName(String[] args)
	{
		if (args == null || args.length == 0)
		{
			return null;
		}
		String n = String.join(" ", args).trim().toLowerCase();
		return n.matches("[a-z0-9_ -]{1,24}") ? n : null;
	}

	/** the name the motion being recorded goes under (null: "idle" if they stood, "motion" if they did something) */
	private String motionAs;

	private void armMotion(String name)
	{
		motionAs = name;
		motionArmed = client.getGameCycle();
		if (panel != null)
		{
			panel.capturing(true);
		}
		motionStart = -1;
		sayModel("recording the next thing you do (an emote, a special attack): do it now. Nothing in 8 seconds, and it records you standing.");
	}

	@Subscribe
	public void onClientTick(net.runelite.api.events.ClientTick e)
	{
		if (motionArmed < 0)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			motionArmed = -1;
			if (panel != null)
			{
				panel.capturing(false);
			}
			return;
		}
		int now = client.getGameCycle();
		if (motionStart < 0)
		{
			boolean moving = me.getAnimation() != -1;
			if (!moving && now - motionArmed < MOTION_WAIT)
			{
				return;
			}
			motionIdle = !moving;
			motionStart = now;
			motionLast = now - MOTION_EVERY;
			motionFrames.clear();
			motionPetFrames.clear();
			// (each written out as it's read: one shared model object for anything animated)
			Model base = me.getModel();
			if (base == null)
			{
				motionArmed = -1;
				if (panel != null)
				{
					panel.capturing(false);
				}
				return;
			}
			Snap bs = snap(base, true);
			List<Fx> bfx = effects(me, null);
			motionCount = ownOf(bs, bfx);
			motionBase = Kept.trim(bs.json, motionCount);
			motionFx = new FxTrack();
			addGround(motionBase, me);
			Model pet = petModel();
			motionPetCount = pet == null ? 0 : pet.getVerticesCount();
			motionPet = pet == null ? null : modelJson(pet);
		}
		if (now - motionLast < MOTION_EVERY)
		{
			return;
		}
		motionLast = now;
		Model m = me.getModel();
		Snap ms = m == null ? null : snap(m, false);
		List<Fx> mfx = ms == null ? null : effects(me, motionFx.models.keySet());
		if (ms != null && ownOf(ms, mfx) == motionCount)
		{
			motionFrames.add(Kept.frame(ms.x, ms.y, ms.z, motionCount));
			motionFx.add(mfx);
			Model p = motionPet == null ? null : petModel();
			motionPetFrames.add(p != null && p.getVerticesCount() == motionPetCount
				? Kept.frame(p.getVerticesX(), p.getVerticesY(), p.getVerticesZ(), p.getVerticesCount()) : null);
		}
		boolean done = motionFrames.size() >= MOTION_MOST
			|| (motionIdle ? now - motionStart >= MOTION_IDLE : me.getAnimation() == -1 && motionFrames.size() > 2);
		if (!done)
		{
			return;
		}
		motionArmed = -1;
		JsonObject frames = Kept.frames(motionFrames, 1000.0 / (MOTION_EVERY * 20));
		motionFx.into(frames);
		JsonObject petFrames = motionPet != null && !motionPetFrames.contains(null) ? Kept.frames(motionPetFrames, 1000.0 / (MOTION_EVERY * 20)) : null;
		sayModel("recorded " + motionFrames.size() + " frames" + (motionIdle ? " of you standing" : "") + ": sending it to your page");
		// (11/15/2026, Jace: "build the motion library": kept under its name beside the others)
		String name = motionAs != null ? motionAs : motionIdle ? "idle" : "motion";
		sayModel("it goes on your page as \"" + name + "\"");
		// (the side tab's one button, 11/15/2026: the still model goes too, from the motion's first moment; since
		// 11/18/2026 only while they haven't set a still of their own with Capture a still)
		if (configManager.getRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET) == null)
		{
			keep("model", wholeModel(motionBase.deepCopy(), motionPet == null ? null : motionPet.deepCopy(), null, null));
		}
		keep("motion", name, wholeModel(motionBase, motionPet, frames, petFrames));
		if (panel != null)
		{
			panel.capturing(false);
		}
	}

	/** A texture as the client has it, a PNG in base64 (0 a see-through texel), or null. */
	private String texturePng(int id)
	{
		try
		{
			int[] px = client.getTextureProvider().load(id);
			int size = px == null ? 0 : (int) Math.round(Math.sqrt(px.length));
			if (size == 0 || size * size != px.length)
			{
				return null;
			}
			java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for (int i = 0; i < px.length; i++)
			{
				im.setRGB(i % size, i / size, px[i] == 0 ? 0 : 0xff000000 | px[i]);
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(im, "png", out);
			return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (RuntimeException | IOException e)
		{
			return null;
		}
	}

	/** The whole bank, its tabs and each item's name and price (the client's thread). */
	private void sendBank()
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null || !config.bank())
		{
			return;
		}
		Item[] items = bank.getItems();
		int[] ids = new int[items.length];
		int[] qs = new int[items.length];
		Map<Integer, Object[]> names = new LinkedHashMap<>();
		for (int i = 0; i < items.length; i++)
		{
			// (a placeholder as its item, none of it)
			int id = items[i].getId();
			int real = id > 0 ? itemManager.canonicalize(id) : id;
			ids[i] = real;
			qs[i] = real != id ? 0 : items[i].getQuantity();
			if (real > 0 && !names.containsKey(real))
			{
				names.put(real, new Object[]{itemManager.getItemComposition(real).getName(), (long) itemManager.getItemPrice(real)});
			}
		}
		int[] tabVarbits = {VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
			VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9};
		int[] tabs = new int[9];
		for (int i = 0; i < 9; i++)
		{
			tabs[i] = client.getVarbitValue(tabVarbits[i]);
		}
		keep("bank", Kept.bank(ids, qs, tabs, names));
	}

	/** Their own death: a delve's levels lost with it (11/19/2026), and what they carried and wore as they fell. */
	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		Player me = client.getLocalPlayer();
		if (me == null || e.getActor() != me)
		{
			return;
		}
		if (config.delve() && atDelve() && delves.died())
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "delveLost", delves.keep());
		}
		deathbankDied();
	}

	/** Whether they're in the Doom of Mokhaiotl's lair (DELVE_REGIONS, by the instance's own map). */
	private boolean atDelve()
	{
		WorldPoint at = here();
		if (at == null)
		{
			return false;
		}
		for (int r : DELVE_REGIONS)
		{
			if (at.getRegionID() == r)
			{
				return true;
			}
		}
		return false;
	}

	/** Their own death (11/16/2026): what they carried and wore as they fell, and where. */
	private void deathbankDied()
	{
		if (!config.deathbank())
		{
			return;
		}
		List<Item> all = new ArrayList<>();
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c != null)
			{
				java.util.Collections.addAll(all, c.getItems());
			}
		}
		deathCarried = new int[2][all.size()];
		for (int i = 0; i < all.size(); i++)
		{
			deathCarried[0][i] = all.get(i).getId();
			deathCarried[1][i] = all.get(i).getQuantity();
		}
		WorldPoint at = here();
		deathRegion = at == null ? -1 : at.getRegionID();
		deathTick = ticks;
		deathLine = null;
	}

	/** The game's line that a death made a deathbank: once they're back on their feet, deathbankFromDeath sends it. */
	private void deathbankLine(String said)
	{
		if (deathTick >= 0 && said != null && said.startsWith(Kept.DEATHBANK_LINE))
		{
			deathLine = said;
		}
	}

	/** Each tick: a death the game said made a deathbank, once they're back (their hitpoints restored, a few ticks on),
	 *  sent as what it took (Kept.lost), "from" the death; a death with no such line within half an hour, forgotten.
	 *  Locked (its fee unpaid), but an ultimate ironman's Zulrah's, which has none. */
	private void deathbankFromDeath()
	{
		if (deathTick < 0)
		{
			return;
		}
		if (!config.deathbank() || ticks - deathTick > 3000)
		{
			deathTick = -1;
			deathCarried = null;
			deathLine = null;
			return;
		}
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		if (deathLine == null || ticks - deathTick < 3 || hp < Math.min(10, client.getRealSkillLevel(Skill.HITPOINTS)))
		{
			return;
		}
		List<Item> now = new ArrayList<>();
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c != null)
			{
				java.util.Collections.addAll(now, c.getItems());
			}
		}
		int[] ids = new int[now.size()];
		int[] qs = new int[now.size()];
		for (int i = 0; i < now.size(); i++)
		{
			ids[i] = now.get(i).getId();
			qs[i] = now.get(i).getQuantity();
		}
		int[][] lost = Kept.lost(deathCarried[0], deathCarried[1], ids, qs);
		String where = Kept.deathbankAt(deathLine, deathRegion);
		boolean locked = !("Zulrah".equals(where) && client.getVarbitValue(VarbitID.IRONMAN) == 2);
		deathTick = -1;
		deathCarried = null;
		deathLine = null;
		if (lost[0].length > 0)
		{
			deathbankSeen = new Object[]{where, locked, lost[0], lost[1], true};
			deathbankChanged = ticks;
		}
	}

	/** The deathbank as last seen (deathbankSeen), each item's name and price; emptied, taken off the page (the client's thread). */
	private void sendDeathbank()
	{
		Object[] d = deathbankSeen;
		if (d == null || !config.deathbank())
		{
			return;
		}
		int[] ids = (int[]) d[2];
		int[] qs = (int[]) d[3];
		Map<Integer, Object[]> names = new LinkedHashMap<>();
		for (int i = 0; i < ids.length; i++)
		{
			int id = ids[i];
			if (id > 0 && qs[i] > 0 && !names.containsKey(id))
			{
				names.put(id, new Object[]{itemManager.getItemComposition(id).getName(), (long) itemManager.getItemPrice(id)});
			}
		}
		JsonObject body = Kept.deathbank((String) d[0], (boolean) d[1], ids, qs, names, (boolean) d[4]);
		keep("deathbank", body);
	}

	/* (11/17/2026, the Hub's update 2: the bank and the deathbank on the page, under the reviewer's terms, "an 'Update my
	   page' action and an hourly sync": in the Hub's version each is read as now, when they look in it, and held here,
	   the latest of each, till the next update takes it (Update my page, or the hourly one); the model, captured by their
	   own press, goes at once, and a switch turned off takes its part down at once. The whole plugin sends each at once.) */
	private final Map<String, JsonObject> keepHeld = new LinkedHashMap<>();
	static final boolean HOLD_KEEPS = true;

	/** Whether this keep waits for the next update (the Hub's version): the bank's or the deathbank's, not a take-down. */
	private boolean holdKeep(String what, JsonObject body)
	{
		if (body == null || !("bank".equals(what) || "deathbank".equals(what)))
		{
			synchronized (keepHeld)
			{
				keepHeld.remove(what);   // (a take-down: nothing held of it goes later)
			}
			return false;
		}
		boolean first;
		synchronized (keepHeld)
		{
			first = keepHeld.put(what, body) == null;
		}
		if (first)
		{
			sayAbout(what, "your " + what + " goes up with your next update (Update my page, or the hourly one)");
		}
		return true;
	}

	/** What's held goes, after an update's gone. */
	private void sendHeldKeeps()
	{
		Map<String, JsonObject> due;
		synchronized (keepHeld)
		{
			due = new LinkedHashMap<>(keepHeld);
			keepHeld.clear();
		}
		due.forEach(this::sendKeep);
	}

	/** Sent to the relay's keep (POST; null: DELETE), off the client's thread; a failure said in the chat. */
	private void keep(String what, JsonObject body)
	{
		keep(what, null, body);
	}

	private void keep(String what, String name, JsonObject body)
	{
		if (name == null && HOLD_KEEPS && holdKeep(what, body))
		{
			return;
		}
		sendKeep(what, name, body);
	}

	private void sendKeep(String what, JsonObject body)
	{
		sendKeep(what, null, body);
	}

	private void sendKeep(String what, String name, JsonObject body)
	{
		Attach a = key();
		if (a == null || !config.send())
		{
			return;
		}
		String url = a.keepUrl(what) + (name == null ? "" : "&name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
		Request.Builder b = new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION);
		b = body == null ? b.delete() : b.post(RequestBody.create(JSON, gson.toJson(body)));
		http.newCall(b.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: keep {} failed", what, ex);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				int code = r.code();
				String why = "";
				try
				{
					// (the relay's own words for what it refused: {"error": "..."})
					String t = r.body() == null ? "" : r.body().string();
					JsonObject e = t.startsWith("{") ? gson.fromJson(t, JsonObject.class) : null;
					why = e != null && e.has("error") ? ": " + e.get("error").getAsString() : "";
				}
				catch (IOException | RuntimeException ex)
				{
					why = "";
				}
				r.close();
				if ("motion".equals(what) || "model".equals(what))
				{
					readMotions();
				}
				if (body != null && code == 200)
				{
					if (!"encounter".equals(what))
					{
						sayAbout(what, "model".equals(what) ? "your still is on your page" : "motion".equals(what) ? "the motion \"" + name + "\" is on your page" : "your " + what + " is on your page");
					}
				}
				else if (body != null && code != 200)
				{
					sayAbout(what, "your " + what + " didn't go up (the relay said " + code + why + ")");
				}
			}
		});
	}

	/** Charges and the delve board, as kept on this RuneLite's profile for the account. */
	/** Charges, as kept on this RuneLite's profile for the account. */
	private void loadKept()
	{
		charges.clear();
		counts.load(null);
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
			String n = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "counts");
			counts.load(n == null ? null : gson.fromJson(n, JsonObject.class));
			String d = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "delve");
			delveSeen = d == null ? null : gson.fromJson(d, JsonArray.class);
			delves.load(configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "delveLost"));
			String dl = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "delveLostSeen");
			delveLostSeen = dl == null ? null : gson.fromJson(dl, JsonArray.class);
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
		if (delveSeen != null)
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "delve", gson.toJson(delveSeen));
		}
		if (delveLostSeen != null)
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "delveLostSeen", gson.toJson(delveLostSeen));
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		// (what they're doing as Discord shows it: their own experience going up, 11/16/2026)
		presence.experience(e.getSkill().getName(), e.getXp(), System.currentTimeMillis());
		// (proving, 11/17/2026: experience gained after the relay said "ready" makes the update it said it to out of date by
		// logout, so the side tab says to update again; noticed here, nothing sent)
		if (readyXp > 0 && !readyStale && client.getOverallExperience() > readyXp && panel != null)
		{
			readyStale = true;
			panel.proof(proofState, STALE);
		}
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
	 * Safe for the globe's features (following and its world, the activity, the stars): not on a risky world (PvP,
	 * Deadman, high risk, Bounty Hunter), not in the Wilderness, not in a PvP area (each as above: Ferox Enclave and the
	 * PvP minigames are safe). Instances are (11/09/2026), unless their template lies in the Wilderness.
	 */
	static boolean globeSafe(boolean riskyWorld, boolean wilderness, boolean pvpArea)
	{
		return !riskyWorld && !wilderness && !pvpArea;
	}

	/**
	 * Safe for the activity's label (11/10/2026, Jace: "Wilderness bosses should be included since we have the option
	 * for 'In the Wilderness too'"): wherever the globe's features are, and in the Wilderness too (its PvP-area flag
	 * with it) when they've said so (OutsideConfig.wilderness), since they've already chosen to transmit there. Never
	 * on a risky world. Following and the stars stay off in the Wilderness whatever the setting (globeSafe): only the
	 * label goes, two minutes behind as ever.
	 */
	static boolean activitySafe(boolean riskyWorld, boolean wilderness, boolean pvpArea, boolean wildernessToo)
	{
		return globeSafe(riskyWorld, wilderness, pvpArea) || (wildernessToo && wilderness && !riskyWorld);
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

	/** On a world where nothing goes to the globe (RISKY, or any the client counts as PvP). */
	private boolean riskyWorld()
	{
		EnumSet<WorldType> types = client.getWorldType();
		if (pvpWorld())
		{
			return true;
		}
		for (WorldType t : types)
		{
			if (RISKY.contains(t))
			{
				return true;
			}
		}
		return false;
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

	// ---- the encounters (11/21/2026; ghillie's claude/outstation.md, "Encounters on Outside: the night's design";
	// docs/protocol.md, "Recorded fights"): the recorder's needs, the side tab's list, and putting one on the page

	/** What the recorder (Encounters) needs from the plugin. */
	private Encounters.Host encounterHost()
	{
		return new Encounters.Host()
		{
			@Override
			public net.runelite.client.util.Filepath folder() throws IOException
			{
				return getPluginDirectory();
			}

			@Override
			public boolean hasPage()
			{
				return key() != null;
			}

			@Override
			public boolean autoOn()
			{
				return config.encounters();
			}

			@Override
			public boolean otherPlayers()
			{
				return config.encounterPlayers();
			}

			@Override
			public String npcIds()
			{
				return config.encounterNpcs();
			}

			@Override
			public long most()
			{
				return encounterMost();
			}

			@Override
			public String version()
			{
				return VERSION;
			}

			@Override
			public void recording(String words, boolean running)
			{
				if (panel != null)
				{
					panel.encounterRecording(words, running);
				}
			}

			@Override
			public void filesChanged(String packed)
			{
				Encounters e = encounters;
				EncounterShelf sh = shelf;
				if (sh != null && packed != null)
				{
					sh.packed(packed, encounterMost(), e == null ? java.util.Collections.emptySet() : e.busy(), () ->
					{
						listEncounters();
						// (11/21/2026, round two: its moment built; the sender waits for it)
						sh.moment(packed, takenFor.remove(packed), () ->
						{
							listEncounters();
							pumpSends();
						});
					});
				}
			}

			@Override
			public Object[] itemLabel(int id, int qty)
			{
				return groundItemLabel(id, qty);
			}

			@Override
			public long[] prices(int id)
			{
				return itemPrices(id);
			}

			@Override
			public void picture(java.awt.Rectangle bounds, java.util.function.Consumer<byte[]> done)
			{
				framePicture(bounds, done);
			}

			@Override
			public void ended(String packed, EncounterScene.Taken taken, String reason)
			{
				if (taken != null)
				{
					takenFor.put(packed, taken);
				}
				// (never from anywhere dangerous; with the switch on, every other end goes up by itself, "Record now"'s
				// too (Jace, 11/21/2026: "in case someone wants to record something that isn't a fight"). Queued now,
				// in the index before it's even packed, so a client closed meanwhile still sends it next time; the sender
				// waits for its file and its moment. A part split at the length limit isn't sent: the last part sends
				// them all, joined; round three)
				if (Encounters.SPLIT.equals(reason))
				{
					return;
				}
				if (shelf != null && taken != null)
				{
					shelf.expect(packed);
				}
				if (shelf != null && config.encounterSend() && !"the Wilderness or PvP".equals(reason))
				{
					shelf.queue(packed, OutsidePlugin.this::listEncounters);
				}
			}
		};
	}

	/** An item's GE price (RuneLite's own) and high alchemy value, each, a noted one's the item it notes (as the Ground
	 *  Items plugin reads them; coins 1 and 1): {ge, ha}, or null. The client's thread. (11/21/2026, round three) */
	private long[] itemPrices(int id)
	{
		net.runelite.api.ItemComposition def = itemManager.getItemComposition(id);
		if (def == null)
		{
			return null;
		}
		int real = def.getNote() != -1 ? def.getLinkedNoteId() : id;
		net.runelite.api.ItemComposition base = real != id ? itemManager.getItemComposition(real) : def;
		if (base == null)
		{
			return null;
		}
		boolean coins = real == net.runelite.api.gameval.ItemID.COINS;
		return new long[]{coins ? 1 : itemManager.getItemPrice(real), coins ? 1 : base.getHaPrice()};
	}

	/**
	 * The next frame the client draws (RuneLite's DrawManager, the frame as the player sees it), cropped to a box on the
	 * canvas and written as a PNG off the client's thread: the loot's window as it was drawn (11/21/2026, round three).
	 * Nothing leaves this computer from here: it goes into the encounter's moment beside its file.
	 */
	private void framePicture(java.awt.Rectangle bounds, java.util.function.Consumer<byte[]> done)
	{
		drawManager.requestNextFrameListener(image ->
		{
			int w = image.getWidth(null), h = image.getHeight(null);
			// (the frame at another size than the canvas the box was measured on, stretched mode's: the box scaled to it;
			// 11/21/2026, round nine)
			int cw = client.getCanvasWidth(), ch = client.getCanvasHeight();
			java.awt.Rectangle at = bounds;
			if (cw > 0 && ch > 0 && (w != cw || h != ch))
			{
				double sx = (double) w / cw, sy = (double) h / ch;
				at = new java.awt.Rectangle((int) Math.round(bounds.x * sx), (int) Math.round(bounds.y * sy),
					(int) Math.round(bounds.width * sx), (int) Math.round(bounds.height * sy));
			}
			java.awt.Rectangle b = at.intersection(new java.awt.Rectangle(0, 0, w, h));
			if (b.isEmpty())
			{
				return;
			}
			java.awt.image.BufferedImage crop = new java.awt.image.BufferedImage(b.width, b.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = crop.createGraphics();
			g.drawImage(image, 0, 0, b.width, b.height, b.x, b.y, b.x + b.width, b.y + b.height, null);
			g.dispose();
			executor.execute(() ->
			{
				try
				{
					done.accept(Encounters.png(crop));
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("outside: the loot window's picture couldn't be written", e);
				}
			});
		});
	}

	/**
	 * A ground item's name as RuneLite's Ground Items plugin writes it over its tile (1.13.7, GroundItemsOverlay with its
	 * defaults; ghillie's replay/3d/moment.js itemText, the browser's twin): the name (a noted one's, the item it notes),
	 * "(qty)" past 1, then for all but coins "(GE: … gp)" from RuneLite's own prices and "(HA: … gp)", each times the
	 * quantity, by QuantityFormatter; its colour by the higher of the two (the plugin's tiers). {text, colour, value}.
	 * The client's thread.
	 */
	private Object[] groundItemLabel(int id, int qty)
	{
		net.runelite.api.ItemComposition def = itemManager.getItemComposition(id);
		if (def == null)
		{
			return null;
		}
		int real = def.getNote() != -1 ? def.getLinkedNoteId() : id;
		net.runelite.api.ItemComposition base = real != id ? itemManager.getItemComposition(real) : def;
		if (base == null)
		{
			return null;
		}
		boolean coins = real == net.runelite.api.gameval.ItemID.COINS;
		long ge = coins ? 1 : itemManager.getItemPrice(real), ha = coins ? 1 : base.getHaPrice();
		long value = Math.max(ge * qty, ha * qty);
		StringBuilder text = new StringBuilder(base.getName());
		if (qty > 1)
		{
			text.append(" (").append(net.runelite.client.util.QuantityFormatter.quantityToStackSize(qty)).append(")");
		}
		if (id != net.runelite.api.gameval.ItemID.COINS)
		{
			if (ge * qty > 0)
			{
				text.append(" (GE: ").append(net.runelite.client.util.QuantityFormatter.quantityToStackSize(ge * qty)).append(" gp)");
			}
			if (ha * qty > 0)
			{
				text.append(" (HA: ").append(net.runelite.client.util.QuantityFormatter.quantityToStackSize(ha * qty)).append(" gp)");
			}
		}
		return new Object[]{text.toString(), EncounterScene.tier(value), value};
	}

	/** "Keep up to (MB)", in bytes. */
	private long encounterMost()
	{
		return Math.max(20, config.encounterKeepMb()) * 1024L * 1024L;
	}

	/** The side tab's list read again (the files thread), for the character logged in. */
	private void listEncounters()
	{
		Encounters e = encounters;
		if (shelf == null || panel == null || e == null)
		{
			return;
		}
		shelf.list(e.busy(), (rows, more) -> panel.encounters(rows, more, shownName), panel::encounterStatus);
	}

	/** The page's own list of its encounters (GET /fights/<channel>?key=, on the press that opens the tab): whether the
	 *  page takes them at all (404: the relay's fights are off), whether a new one starts shown, what's still on it, which
	 *  are kept for good and featured, and how many may be kept (11/21/2026). */
	private void readFights()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			return;
		}
		http.newCall(new Request.Builder().url(a.fightsUrl()).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.encounterStatus("the relay's out of reach: what's on your page isn't known just now");
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					String body = res.body() == null ? "" : res.body().string();
					JsonObject o = null;
					try
					{
						o = gson.fromJson(body, JsonObject.class);
					}
					catch (RuntimeException ignored)
					{
						// (an answer that isn't JSON: said by its code below)
					}
					if (res.code() == 404 && o != null && o.has("error") && o.get("error").getAsString().contains("fights aren't on"))
					{
						panel.fightsState(false, false, 0, 0);
						return;
					}
					if (res.code() != 200 || o == null || !o.has("fights") || !o.get("fights").isJsonArray())
					{
						panel.encounterStatus("the relay said " + res.code() + (o != null && o.has("error") ? ": " + o.get("error").getAsString() : ""));
						return;
					}
					boolean first = o.has("public_default") && o.get("public_default").getAsBoolean();
					int most = o.has("kept") && o.get("kept").isJsonPrimitive() ? o.get("kept").getAsInt() : 12;
					String featured = o.has("featured") && o.get("featured").isJsonPrimitive() ? o.get("featured").getAsString() : null;
					Map<String, boolean[]> fights = new HashMap<>();
					int kept = 0;
					for (JsonElement f : o.getAsJsonArray("fights"))
					{
						if (f.isJsonObject() && f.getAsJsonObject().has("id"))
						{
							JsonObject fo = f.getAsJsonObject();
							String id = fo.get("id").getAsString();
							boolean k = fo.has("kept") && fo.get("kept").getAsBoolean();
							kept += k ? 1 : 0;
							fights.put(id, new boolean[]{fo.has("public") && fo.get("public").getAsBoolean(), k, id.equals(featured)});
						}
					}
					panel.fightsState(true, first, kept, most);
					shelf.reconcile(a.getChannel(), fights, OutsidePlugin.this::listEncounters);
				}
				catch (IOException | RuntimeException ex)
				{
					panel.encounterStatus("the relay's answer couldn't be read");
				}
			}
		});
	}

	/** "Put on my page" (the side tab): the recording stripped as the page strips one (EncounterStrip), what was taken out
	 *  said, and only once that's agreed to, sent with its moment (postFight). */
	private void putEncounter(String file, boolean shown)
	{
		Attach a = key();
		if (a == null || shelf == null)
		{
			panel.encounterStatus("log in on a character with a page first");
			return;
		}
		String me = shownName;
		panel.putting(file, true);
		panel.encounterStatus("reading it…");
		shelf.prepare(file, p ->
		{
			if (p.summary.player != null && me != null && !OutsidePanel.sameName(p.summary.player, me))
			{
				panel.putting(file, false);
				panel.encounterStatus("that was recorded on " + p.summary.player + ": log in on that character to put it on its page");
				return;
			}
			if (p.gz.length + (p.moment == null ? 0 : p.moment.length) > FIGHT_MOST)
			{
				panel.putting(file, false);
				panel.encounterStatus(String.format("it's %.1f MB packed: a page takes 8 MB at most", p.gz.length / 1048576.0));
				return;
			}
			panel.encounterStatus(null);
			panel.confirmPut(file, p.summary.who(), (int) p.summary.ticks, p.removed, shown, p.gz.length,
				() -> postFight(a, p, shown, false, 1));
		}, problem ->
		{
			panel.putting(file, false);
			panel.encounterStatus(problem);
		});
	}

	/**
	 * Each encounter up by itself (11/21/2026; ghillie's claude/outstation.md, "Encounters, round two"; EncounterQueue):
	 * with "Put each encounter on my page" on and a page for the character logged in, the oldest recording due goes, one
	 * at a time, off the client's thread (the shelf's files thread strips it, OkHttp sends it). One whose last send went
	 * unanswered is looked for on the page first, and not sent again if it's there.
	 */
	private void pumpSends()
	{
		EncounterShelf sh = shelf;
		Encounters e = encounters;
		Attach a = key();
		String me = shownName;
		if (sh == null || e == null || a == null || me == null || !config.encounterSend() || client.getGameState() != GameState.LOGGED_IN
			|| !sending.compareAndSet(false, true))
		{
			return;
		}
		sh.nextDue(System.currentTimeMillis(), me, e.busy(), due ->
		{
			if (due == null)
			{
				sending.set(false);
				return;
			}
			if (due.unsure)
			{
				findOnPage(a, due, () -> sendDue(a, due));
			}
			else
			{
				sendDue(a, due);
			}
		});
	}

	/** One sent before with no answer seen: the page's list read, and if it's there it's taken as sent (never twice). */
	private void findOnPage(Attach a, EncounterShelf.Due due, Runnable notThere)
	{
		http.newCall(new Request.Builder().url(a.fightsUrl()).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				// (still out of reach: as unsure as before, and later)
				shelf.sent(due.file, EncounterQueue.lost(System.currentTimeMillis(), 1), null, null, null, OutsidePlugin.this::sendDone);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					JsonObject o = gson.fromJson(res.body() == null ? "" : res.body().string(), JsonObject.class);
					if (res.code() == 200 && o != null && o.has("fights") && o.get("fights").isJsonArray())
					{
						for (JsonElement f : o.getAsJsonArray("fights"))
						{
							if (!f.isJsonObject())
							{
								continue;
							}
							JsonObject fo = f.getAsJsonObject();
							Map<String, Object> m = new HashMap<>();
							m.put("at", fo.has("at") ? fo.get("at").getAsDouble() : 0.0);
							m.put("ticks", fo.has("ticks") ? fo.get("ticks").getAsDouble() : 0.0);
							if (EncounterQueue.sameFight(m, due.at, due.ticks) && fo.has("id"))
							{
								log.debug("outside: {} was on the page already: not sent again", due.file);
								shelf.sent(due.file, new EncounterQueue.After(EncounterQueue.SENT, 0, null), fo.get("id").getAsString(), a.getChannel(),
									fo.has("public") && fo.get("public").getAsBoolean(), OutsidePlugin.this::sendDone);
								return;
							}
						}
					}
				}
				catch (IOException | RuntimeException ex)
				{
					// (an answer that can't be read: sent as if it isn't there; the relay's list is what's known)
				}
				notThere.run();
			}
		});
	}

	private void sendDue(Attach a, EncounterShelf.Due due)
	{
		String me = shownName;
		shelf.prepare(due.file, p ->
		{
			if (p.summary.player != null && (me == null || !OutsidePanel.sameName(p.summary.player, me)))
			{
				shelf.sent(due.file, new EncounterQueue.After(EncounterQueue.DUE, 0, "waiting for " + p.summary.player + " to log in"), null, null,
					null, this::sendDone);
				return;
			}
			if (p.gz.length > FIGHT_MOST)
			{
				shelf.sent(due.file, new EncounterQueue.After(EncounterQueue.REFUSED, 0, String.format("%.1f MB packed: a page takes 8 MB at most",
					p.gz.length / 1048576.0)), null, null, null, this::sendDone);
				return;
			}
			if (p.gz.length + (p.moment == null ? 0 : p.moment.length) > FIGHT_MOST)
			{
				p.moment = null;
				p.momentWhy = "the recording and its moment together are past the page's 8 MB";
			}
			shelf.entryOf(due.file, e ->
			{
				Boolean shown = e != null && e.has("shown") ? e.get("shown").getAsBoolean() : null;
				postFight(a, p, shown, true, e == null ? 1 : shelf.triesOf(e));
			});
		}, problem -> shelf.sent(due.file, new EncounterQueue.After(EncounterQueue.REFUSED, 0, problem), null, null, null, this::sendDone));
	}

	/** A send finished (whatever it came to): the next due one, if any. */
	private void sendDone()
	{
		listEncounters();
		sending.set(false);
		if (sendTimer != null)
		{
			executor.schedule(this::pumpSends, 2, TimeUnit.SECONDS);
		}
	}

	/**
	 * A recording onto the page: POST /fights/<channel>?key=, multipart: meta (fights.js's put(): foe, label, at, ticks,
	 * died, taken, dealt, and public only when the player's chosen; without it the relay's public_default), fight (the
	 * stripped recording, gzipped), and its scene moment with moment_tick when it has one (docs/protocol.md, "One upload
	 * with its moment"). {@code auto}: the sender's (its state kept by EncounterQueue), else Put on my page's.
	 */
	private void postFight(Attach a, EncounterShelf.Prepared p, Boolean shown, boolean auto, int tries)
	{
		String file = p.file;
		if (!auto)
		{
			panel.encounterStatus("putting it on your page…");
		}
		okhttp3.MultipartBody.Builder mb = new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
			.addFormDataPart("meta", EncounterStrip.meta(p.summary, shown, p.moment == null ? p.momentWhy : null))
			.addFormDataPart("fight", "fight.jsonl.gz", RequestBody.create(GZIP, p.gz));
		if (p.moment != null)
		{
			mb.addFormDataPart("moment", "moment.bin", RequestBody.create(OCTETS, p.moment));
			mb.addFormDataPart("moment_tick", String.valueOf(p.momentTick));
		}
		http.newCall(new Request.Builder().url(a.fightsUrl()).header("User-Agent", "outside-plugin/" + VERSION).post(mb.build()).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.putting(file, false);
				EncounterQueue.After after = EncounterQueue.lost(System.currentTimeMillis(), tries);
				if (!auto)
				{
					panel.encounterStatus("the relay's out of reach: it goes up by itself once it can");
				}
				shelf.sent(file, after, null, null, null, auto ? OutsidePlugin.this::sendDone : OutsidePlugin.this::listEncounters);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					String text = res.body() == null ? "" : res.body().string();
					JsonObject o = null;
					try
					{
						o = gson.fromJson(text, JsonObject.class);
					}
					catch (RuntimeException ignored)
					{
						// (said by its code below)
					}
					String error = o != null && o.has("error") && o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : null;
					EncounterQueue.After after = EncounterQueue.answered(res.code(), res.header("Retry-After"), error, System.currentTimeMillis(), tries);
					panel.putting(file, false);
					if (res.code() == 201 && o != null && o.has("fight") && o.get("fight").isJsonObject())
					{
						JsonObject f = o.getAsJsonObject("fight");
						String id = f.get("id").getAsString();
						boolean pub = f.has("public") && f.get("public").getAsBoolean();
						shelf.sent(file, after, id, a.getChannel(), pub, auto ? OutsidePlugin.this::sendDone : OutsidePlugin.this::listEncounters);
						if (!auto)
						{
							panel.encounterStatus(p.summary.who() + " is on your page" + (pub ? "" : ", kept to yourself"));
						}
						return;
					}
					if (res.code() == 404 && error != null && error.contains("fights aren't on"))
					{
						panel.fightsState(false, false, 0, 0);
					}
					if (!auto)
					{
						panel.encounterStatus(res.code() == 403 ? "the relay didn't take your page's key: nothing went"
							: res.code() == 429 ? "nothing went: " + after.why : "nothing went: " + (error != null ? error : "the relay said " + res.code()));
					}
					// (Put on my page's own failure leaves a queued one as it was; the sender's, as the answer says)
					if (auto || EncounterQueue.SENT.equals(after.state))
					{
						shelf.sent(file, after, null, null, null, auto ? OutsidePlugin.this::sendDone : OutsidePlugin.this::listEncounters);
					}
					else
					{
						listEncounters();
					}
				}
				catch (IOException | RuntimeException ex)
				{
					panel.putting(file, false);
					if (!auto)
					{
						panel.encounterStatus("the relay's answer couldn't be read");
					}
					shelf.sent(file, EncounterQueue.lost(System.currentTimeMillis(), tries), null, null, null,
						auto ? OutsidePlugin.this::sendDone : OutsidePlugin.this::listEncounters);
				}
			}
		});
	}

	/** One on the page changed (POST /fights/<channel>/<id>?…): kept for good or not, or featured (featuring keeps it). A
	 *  409's reason (twelve kept already) said as the relay says it. */
	private void markEncounter(String file, String what, String done)
	{
		Attach a = key();
		if (shelf == null || a == null)
		{
			return;
		}
		shelf.entryOf(file, e ->
		{
			String id = e != null && e.has("fight") ? e.get("fight").getAsString() : null;
			String channel = e != null && e.has("channel") ? e.get("channel").getAsString() : null;
			if (id == null || !a.getChannel().equals(channel))
			{
				panel.encounterStatus("that one isn't on this character's page");
				listEncounters();
				return;
			}
			http.newCall(new Request.Builder().url(a.fightUrl(id, what)).header("User-Agent", "outside-plugin/" + VERSION)
				.post(RequestBody.create(JSON, "{}")).build()).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					panel.encounterStatus("the relay's out of reach: try again in a bit");
					listEncounters();
				}

				@Override
				public void onResponse(Call call, Response r)
				{
					try (Response res = r)
					{
						JsonObject o = null;
						try
						{
							o = gson.fromJson(res.body() == null ? "" : res.body().string(), JsonObject.class);
						}
						catch (RuntimeException ignored)
						{
							// (said by its code)
						}
						if (res.code() == 200 && o != null)
						{
							Boolean kept = o.has("kept") ? o.get("kept").getAsBoolean() : null;
							Boolean featured = o.has("featured") ? o.get("featured").getAsBoolean() : null;
							shelf.marks(file, kept, featured, () ->
							{
								listEncounters();
								readFights();
							});
							panel.encounterStatus(done);
							return;
						}
						if (res.code() == 404)
						{
							shelf.onPage(file, null, null, null, OutsidePlugin.this::listEncounters);
							panel.encounterStatus("that one isn't on your page any more");
							return;
						}
						panel.encounterStatus(o != null && o.has("error") ? o.get("error").getAsString() : "the relay said " + res.code());
						listEncounters();
					}
					catch (IOException ex)
					{
						panel.encounterStatus("the relay's answer couldn't be read");
					}
				}
			});
		});
	}

	/** "Take down": off the page (DELETE /fights/<channel>/<id>), its moment with it; the file stays here, and the sender
	 *  never puts it up again by itself. */
	private void takeDownEncounter(String file)
	{
		Attach a = key();
		if (shelf == null || a == null)
		{
			return;
		}
		shelf.entryOf(file, e ->
		{
			String id = e != null && e.has("fight") ? e.get("fight").getAsString() : null;
			if (id == null)
			{
				return;
			}
			http.newCall(new Request.Builder().url(a.fightUrl(id, (String) null)).header("User-Agent", "outside-plugin/" + VERSION)
				.delete().build()).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					panel.encounterStatus("the relay's out of reach: it's still up; try again in a bit");
				}

				@Override
				public void onResponse(Call call, Response r)
				{
					try (Response res = r)
					{
						if (res.code() == 204 || res.code() == 404)
						{
							shelf.onPage(file, null, null, null, () ->
							{
								shelf.sent(file, new EncounterQueue.After(EncounterQueue.SENT, 0, "taken down from your page"), null, null, null,
									OutsidePlugin.this::listEncounters);
								readFights();
							});
							panel.encounterStatus("taken down from your page; it's still on this computer");
							return;
						}
						panel.encounterStatus("the relay said " + res.code() + ": it's still up");
					}
				}
			});
		});
	}

	/** "Shown to anyone" ticked or not: on the page at once (POST /fights/<channel>/<id>?public=), or kept as the choice
	 *  for when it goes. */
	private void showEncounter(String file, boolean shown)
	{
		Attach a = key();
		if (shelf == null)
		{
			return;
		}
		shelf.entryOf(file, e ->
		{
			String id = e != null && e.has("fight") ? e.get("fight").getAsString() : null;
			String channel = e != null && e.has("channel") ? e.get("channel").getAsString() : null;
			if (id == null || a == null || !a.getChannel().equals(channel))
			{
				shelf.onPage(file, null, null, shown, OutsidePlugin.this::listEncounters);
				return;
			}
			http.newCall(new Request.Builder().url(a.fightUrl(id, shown)).header("User-Agent", "outside-plugin/" + VERSION)
				.post(RequestBody.create(JSON, "{}")).build()).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					panel.encounterStatus("the relay's out of reach: try again in a bit");
					listEncounters();
				}

				@Override
				public void onResponse(Call call, Response r)
				{
					try (Response res = r)
					{
						if (res.code() == 404)
						{
							// (taken down on the page, or gone with its days: no longer on it)
							shelf.onPage(file, null, null, shown, OutsidePlugin.this::listEncounters);
							panel.encounterStatus("that one isn't on your page any more");
							return;
						}
						if (res.code() != 200)
						{
							panel.encounterStatus("the relay said " + res.code());
							listEncounters();
							return;
						}
						shelf.onPage(file, channel, id, shown, OutsidePlugin.this::listEncounters);
						panel.encounterStatus(shown ? "shown to anyone now" : "kept to yourself now");
					}
				}
			});
		});
	}

	/** "Open": its page on Outside.wiki; one kept to themselves opens with a tending token in the fragment (as Read and
	 *  tend my register's), which only the page's own browser then holds. */
	private void openEncounter(String file)
	{
		Attach a = key();
		if (a == null || shelf == null)
		{
			return;
		}
		shelf.entryOf(file, e ->
		{
			String id = e != null && e.has("fight") ? e.get("fight").getAsString() : null;
			boolean shown = e != null && e.has("shown") && e.get("shown").getAsBoolean();
			if (id == null)
			{
				return;
			}
			String page = a.fightPage(id, friendsOnly ? watchCode(false) : null);
			if (shown)
			{
				net.runelite.client.util.LinkBrowser.browse(page);
				return;
			}
			String token = Tend.token(new java.security.SecureRandom());
			http.newCall(new Request.Builder().url(a.tendUrl()).header("User-Agent", "outside-plugin/" + VERSION)
				.post(RequestBody.create(JSON, Tend.body(token, gson))).build()).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException ex)
				{
					panel.encounterStatus("the relay's out of reach: try again in a moment");
				}

				@Override
				public void onResponse(Call call, Response r)
				{
					try (Response res = r)
					{
						if (res.code() != 200)
						{
							panel.encounterStatus("the relay said no (" + res.code() + ")");
							return;
						}
					}
					net.runelite.client.util.LinkBrowser.browse(page + "#tend=" + token);
				}
			});
		});
	}

	private void say(String words)
	{
		clientThread.invokeLater(() -> chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("Outside: " + words).build()));
	}

	/** A line about the character model or its motions: in the chat, and under Capture my character in the side tab's
	 *  Model tab (11/18/2026: every line went there, so the goal's turned up under the capture button). */
	private void sayModel(String words)
	{
		if (panel != null)
		{
			panel.status(words);
		}
		say(words);
	}

	/** A keep's line: under Capture my character only when it's the model's or a motion's. */
	private void sayAbout(String what, String words)
	{
		if ("model".equals(what) || "motion".equals(what))
		{
			sayModel(words);
		}
		else
		{
			say(words);
		}
	}
}
