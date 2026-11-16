package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Charges from the game's chat, the bank and the model as the relay keeps them (Kept, 11/14/2026). */
public class KeptTest
{
	@Test
	public void chargesAsTheGameSaysThem()
	{
		assertEquals(Integer.valueOf(1234), Kept.charges("Your tonalztics of ralos has 1,234 charges left."));
		assertEquals(Integer.valueOf(1), Kept.charges("Your ring of recoil has 1 charge left."));
		assertEquals(Integer.valueOf(57), Kept.charges("Your archivist's folio has 57 charges remaining."));
		assertEquals(Integer.valueOf(12), Kept.charges("Uses remaining: 12"));
		assertEquals(Integer.valueOf(0), Kept.charges("Your blowpipe has run out of charges."));
		assertNull(Kept.charges("You eat the shark."));
		assertNull(Kept.charges(null));
	}

	@Test
	public void anItemByTheNameTheGameSays()
	{
		assertEquals("tonalztics of ralos", Kept.spoken("Tonalztics of ralos (charged)"));
		assertTrue(Kept.about("Your tonalztics of ralos has 50 charges.", "Tonalztics of ralos (charged)"));
		assertFalse(Kept.about("Your ring has 5 charges.", "Ring (i)"));   // (too short a name to tell by)
		assertFalse(Kept.about("Your blowpipe has 5 charges.", "Shark"));
	}

}
