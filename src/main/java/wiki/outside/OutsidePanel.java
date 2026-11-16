package wiki.outside;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's side tab (11/15/2026). It shows:
 * <ul>
 * <li>whose page it is and a way to open it; making a page for the character logged in, and proving it theirs;</li>
 * <li>"Update my page" and when it was last updated (11/16/2026, the snapshot);</li>
 * </ul>
 * Everything it does goes through the plugin (OutsidePlugin's panel hooks); this only draws.
 */
public class OutsidePanel extends PluginPanel
{
	/** what the panel asks the plugin to do */
	public interface Hooks
	{

		void refresh();

		void openPage();

		void claim();

		void update();

		void friendsOnly(boolean on);

		void copyFriendsLink();

		void takeDown();

	}

	private static final Color LIME = new Color(0xb9, 0xff, 0x00);
	private final Hooks hooks;
	private final JLabel who = new JLabel(" ");
	private final JLabel status = new JLabel(" ");
	private final JPanel start = new JPanel();
	private final JPanel page = new JPanel();
	private final JButton claim = new JButton("Make my Outside page");
	private final JLabel claimStatus = new JLabel(" ");
	private final JLabel proofLine = small(" ");
	private final JLabel otherLine = small(" ");
	// (11/16/2026, the snapshot: "Update my page", when it was last updated from here, and what the last press did)
	private final JLabel snapLine = small(" ");
	private final JLabel snapStatus = small(" ");
	private long snapAt;
	private final javax.swing.Timer snapClock = new javax.swing.Timer(30_000, e -> snapLine.setText(snapWords(snapAt)));
	// (11/16/2026, the permissions review: who sees the page, and taking it down, with the plugin alone)
	private final JLabel access = small(" ");
	private final JButton accessButton = new JButton("Make it friends only");
	private final JPanel friendsLink = new JPanel();
	private final JLabel accessStatus = small(" ");
	private boolean friends;

	public OutsidePanel(Hooks hooks)
	{
		this.hooks = hooks;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Outside");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(LIME);
		col.add(left(title));
		who.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		col.add(left(who));
		proofLine.setForeground(new Color(0xff, 0x69, 0xb4));
		proofLine.setVisible(false);
		col.add(left(proofLine));
		col.add(Box.createVerticalStrut(8));
		// (11/15/2026, the plugin first: a player from the Plugin Hub with no outstation and no invite makes their own page
		// here, under the name they're logged in as; docs/protocol.md "Claiming a name")
		start.setLayout(new BoxLayout(start, BoxLayout.Y_AXIS));
		start.add(left(small("<html><div style='width:155px'>No page yet. Log in, then make one: it's named after the character you're logged in as,"
			+ " and shows what you wear and carry, your stats, quests, diaries and slayer task, updated when you ask and"
			+ " hourly while you play. Each character has a page and a key of its own.</div></html>")));
		otherLine.setVisible(false);
		start.add(left(otherLine));
		start.add(Box.createVerticalStrut(4));
		claim.addActionListener(e -> hooks.claim());
		start.add(wide(claim));
		claimStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		start.add(left(claimStatus));
		start.add(Box.createVerticalStrut(10));
		start.setVisible(false);
		col.add(start);
		// (what needs a page: only when this character has one; 11/15/2026, Jace: on his alt, sburb's motions still showed)
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.add(wide(button("Open my page", hooks::openPage)));
		page.add(Box.createVerticalStrut(6));
		page.add(wide(button("Update my page", hooks::update)));
		snapLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(snapLine));
		snapStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(snapStatus));
		page.add(left(small("<html><div style='width:155px'>Your gear, stats, quests, diaries and slayer task go when you press"
			+ " it, and by themselves every hour you're logged in; never from the Wilderness or a PvP world.</div></html>")));
		snapClock.start();
		page.add(Box.createVerticalStrut(14));

		page.add(wide(button("Refresh", hooks::refresh)));
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(status));
		page.add(Box.createVerticalStrut(14));

		page.add(left(heading("Who sees it")));
		page.add(left(access));
		page.add(Box.createVerticalStrut(4));
		accessButton.addActionListener(e -> hooks.friendsOnly(!friends));
		page.add(wide(accessButton));
		friendsLink.setLayout(new BoxLayout(friendsLink, BoxLayout.Y_AXIS));
		friendsLink.add(Box.createVerticalStrut(4));
		friendsLink.add(wide(button("Copy the friends' link", hooks::copyFriendsLink)));
		friendsLink.setVisible(false);
		page.add(friendsLink);
		accessStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		page.add(left(accessStatus));
		page.add(Box.createVerticalStrut(14));
		page.add(wide(button("Take my page down", () ->
		{
			int sure = javax.swing.JOptionPane.showConfirmDialog(this,
				"Take your Outside page down? Everything on it goes, and the name is free for anyone to claim."
					, "Take my page down", javax.swing.JOptionPane.OK_CANCEL_OPTION,
				javax.swing.JOptionPane.WARNING_MESSAGE);
			if (sure == javax.swing.JOptionPane.OK_OPTION)
			{
				hooks.takeDown();
			}
		})));
		access(null);

		page.setVisible(false);
		col.add(page);

		add(col, BorderLayout.NORTH);
	}

	/** Whose page it is (their channel), or that there's no key yet. */
	public void who(String channel)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean changed = !who.getText().equals(channel == null ? "no page yet" : "outside.wiki/" + channel);
			who.setText(channel == null ? "no page yet" : "outside.wiki/" + channel);
			start.setVisible(channel == null);
			page.setVisible(channel != null);
			if (changed)
			{
				// (another page, or none: what the last one said goes)
				status.setText(" ");
				claimStatus.setText(" ");
			}
			revalidate();
		});
	}

	/** Who sees the page: true friends only, false anyone, null not read yet. */
	public void access(Boolean friendsOnly)
	{
		SwingUtilities.invokeLater(() ->
		{
			friends = Boolean.TRUE.equals(friendsOnly);
			access.setText("<html><div style='width:155px'>" + (friendsOnly == null ? "Anyone with the link, unless you've made it friends only."
				: friends ? "Friends only: only those you give the friends' link to. It's off the globe and the feed."
				: "Anyone with the link, and you show on outside.wiki's globe and feed while you play.") + "</div></html>");
			accessButton.setText(friends ? "Open it to anyone" : "Make it friends only");
			friendsLink.setVisible(friends);
			revalidate();
		});
	}

	/** A line under "Who sees it". */
	public void accessStatus(String words)
	{
		SwingUtilities.invokeLater(() -> accessStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	/** When the key in the settings is another character's (its page), and who's logged in: said under the button. */
	public void otherKey(String theirs, String me)
	{
		SwingUtilities.invokeLater(() ->
		{
			otherLine.setText(theirs == null ? " " : "<html><div style='width:155px'>The station key in the plugin's settings is outside.wiki/" + esc(theirs)
				+ "'s, so nothing of " + esc(me) + "'s goes there. Make " + esc(me) + " a page of its own here.</div></html>");
			otherLine.setVisible(theirs != null);
			revalidate();
		});
	}

	/** Whether the page is proven theirs (11/15/2026): "unproven" says how it's proven; anything else, nothing. */
	public void proof(String proof, String why)
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean un = "unproven".equals(proof) || "challenging".equals(proof);
			proofLine.setText("challenging".equals(proof)
				? "<html><div style='width:155px'>Someone claimed this name first and hasn't proven it. Gain any experience, press Update"
				+ " my page, wait a minute, then log out: when the official hiscores show it, the page is yours, and their key stops working." + whyLine(why) + "</div></html>"
				: un ? "<html><div style='width:155px'>Your page is marked unproven. Gain any experience, press Update my page, wait a"
				+ " minute, then log out:"
				+ " within a few minutes the official hiscores show it, and your page is proven yours." + whyLine(why) + "</div></html>" : " ");
			proofLine.setVisible(un);
			revalidate();
		});
	}

	/** The plugin's off: the clock stops. */
	public void stop()
	{
		snapClock.stop();
	}

	/** When the page was last updated from here (ms; 0: not this session). */
	public void snapped(long at)
	{
		SwingUtilities.invokeLater(() ->
		{
			snapAt = at;
			snapLine.setText(snapWords(at));
			if (at > 0)
			{
				snapStatus.setText(" ");
			}
		});
	}

	/** A line under "Update my page" (what the last press did). */
	public void snapStatus(String words)
	{
		SwingUtilities.invokeLater(() -> snapStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	/** "updated 14 minutes ago", or nothing before the first this session. */
	static String snapWords(long at)
	{
		if (at <= 0)
		{
			return " ";
		}
		long m = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
		return m < 1 ? "updated just now" : m == 1 ? "updated a minute ago" : m < 60 ? "updated " + m + " minutes ago"
			: m < 120 ? "updated an hour ago" : "updated " + (m / 60) + " hours ago";
	}

	/** While a claim's asked, the button waits; then a line under it (null: none). */
	public void claiming(boolean on, String words)
	{
		SwingUtilities.invokeLater(() ->
		{
			claim.setEnabled(!on);
			claim.setText(on ? "Making your page…" : "Make my Outside page");
			claimStatus.setText(words == null ? " " : "<html><div style='width:155px'>" + esc(words) + "</div></html>");
		});
	}

	/** A line under the capture button. */
	public void status(String words)
	{
		SwingUtilities.invokeLater(() -> status.setText("<html><div style='width:155px'>" + esc(words) + "</div></html>"));
	}

	/** What the proof last did (the relay's words), under the line. */
	private static String whyLine(String why)
	{
		return why == null || why.isEmpty() ? "" : "<br><br>Last: " + esc(why);
	}

	private static JLabel heading(String t)
	{
		JLabel l = new JLabel(t);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		return l;
	}

	private static JLabel small(String t)
	{
		JLabel l = new JLabel(t);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private static JButton button(String t, Runnable r)
	{
		JButton b = new JButton(t);
		b.addActionListener(e -> r.run());
		return b;
	}

	private static JPanel left(java.awt.Component c)
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
		p.add(c);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 40));
		return p;
	}

	private static JPanel wide(JButton b)
	{
		JPanel p = new JPanel(new GridLayout(1, 1));
		p.add(b);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		return p;
	}

	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
