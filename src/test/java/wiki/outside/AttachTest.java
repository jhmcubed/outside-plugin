package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The relay's addresses made from a station key (11/15/2026: your year's routes and the plugin's own reports). */
public class AttachTest
{
	@Test
	public void theRecordsAndTheReportsRoutes()
	{
		Attach a = new Attach("wss://relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		// (the page's address for the Dink plugin's webhooks, 11/16/2026: copied for the player, never fetched)
		assertEquals("https://relay.ghillie.workers.dev/dink/jhmcubed?key=k3y-k3y-k3y", a.dinkAddress());
		assertEquals("https://relay.ghillie.workers.dev/game/jhmcubed?key=k3y-k3y-k3y&v=0.3.0&proof=1", a.postUrl("0.3.0"));
		assertEquals("https://relay.ghillie.workers.dev/proof/jhmcubed", a.proofUrl());
	}

	@Test
	public void friendsOnlyAndTakingThePageDown()
	{
		Attach a = new Attach("wss://relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		assertEquals("https://relay.ghillie.workers.dev/code/jhmcubed?key=k3y-k3y-k3y", a.codeUrl());
		assertEquals("https://relay.ghillie.workers.dev/channels/jhmcubed?key=k3y-k3y-k3y", a.takeDownUrl());
		assertEquals("https://relay.ghillie.workers.dev/game/jhmcubed", a.gameReadUrl());
		assertEquals("https://outside.wiki/jhmcubed?code=a-b_c", a.page("a-b_c"));
		assertEquals("https://outside.wiki/jhmcubed", a.page(""));
	}

	@Test
	public void tendingThePagesChat()
	{
		// (11/16/2026: the token handed in by the page's key, and the page opened with it in the fragment)
		Attach a = new Attach("wss://relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		assertEquals("https://relay.ghillie.workers.dev/tend/jhmcubed?key=k3y-k3y-k3y", a.tendUrl());
		assertEquals("https://outside.wiki/jhmcubed#tend=abc", a.tendPage("abc"));
	}

	@Test
	public void onlyTheOutsideRelay()
	{
		assertTrue(Attach.isHome("https://relay.ghillie.workers.dev"));
		assertTrue(Attach.isHome("wss://relay.ghillie.workers.dev/"));
		assertTrue(Attach.isHome("relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("https://relay.ghillie.workers.dev.evil.example"));
		assertFalse(Attach.isHome("https://evil.example/relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("https://relay.ghillie.workers.dev:8443"));
		assertFalse(Attach.isHome("https://user@relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("http://relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("http://127.0.0.1:8787"));
		assertFalse(Attach.isHome(null));
	}

	@Test
	public void theHubsVersionTakesNoOtherRelay()
	{
		com.google.gson.Gson gson = new com.google.gson.Gson();
		assertNull(Attach.parse(key("https://evil.example"), gson));
		Attach a = Attach.parse(key("wss://relay.ghillie.workers.dev"), gson);
		assertEquals(Attach.HOME, a.getRelay());
		assertEquals("https://relay.ghillie.workers.dev/proof/jhmcubed", a.proofUrl());
	}
	
	private static String key(String relay)
	{
		String json = "{\"relay\":\"" + relay + "\",\"channel\":\"jhmcubed\",\"key\":\"k3y-k3y-k3y\"}";
		return "outstation1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
