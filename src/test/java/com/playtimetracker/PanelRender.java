package com.playtimetracker;

import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Random;

/**
 * Renders the whole side panel to PNGs, one per tab, without launching a client. Test-only.
 */
public class PanelRender
{
	public static void main(String[] args) throws Exception
	{
		final File dir = new File(args.length > 0 ? args[0] : ".");

		final PlayTimeConfig config = new PlayTimeConfig()
		{
			@Override
			public boolean showAverages()
			{
				return true;
			}
		};

		final PlayTimePlugin plugin = new PlayTimePlugin();
		set(plugin, "config", config);

		final LocalDate today = plugin.today();
		final Random random = new Random(3);
		long tracked = 0;
		for (int i = 0; i < 21; i++)
		{
			final LocalDate date = today.minusDays(i);
			final long ticks = (i == 4 || i == 11) ? 0 : 2_000 + random.nextInt(38_000);
			if (ticks > 0)
			{
				final String key = date.format(PlayTimePlugin.DATE_FORMAT);
				plugin.records.put(key, new PlayTimeRecord(key, ticks));
				tracked += ticks;
			}
		}
		// Baselines: a long-standing account, with some genuine off-client time since install.
		final long preInstall = 12_500_000L;
		plugin.records.put(PlayTimePlugin.PRE_INSTALL_KEY,
			new PlayTimeRecord(PlayTimePlugin.PRE_INSTALL_KEY, preInstall));
		plugin.records.put(PlayTimePlugin.EXTERNAL_TIME_KEY,
			new PlayTimeRecord(PlayTimePlugin.EXTERNAL_TIME_KEY, preInstall + 96_000L));
		plugin.records.put(PlayTimePlugin.GAME_TOTAL_KEY,
			new PlayTimeRecord(PlayTimePlugin.GAME_TOTAL_KEY, preInstall + 96_000L + tracked));

		set(plugin, "loadedData", true);
		set(plugin, "currentPlayer", "Badora II");
		set(plugin, "sessionTicks", 4_200L);
		set(plugin, "totalTicks", preInstall + 96_000L + tracked);

		// today/week/month/year are cached per tick by the plugin, so without a running client
		// they would all read zero and the render would be a lie.
		final java.lang.reflect.Method updateStats =
			PlayTimePlugin.class.getDeclaredMethod("updateStatCache");
		updateStats.setAccessible(true);
		updateStats.invoke(plugin);

		final PlayTimePanel panel = new PlayTimePanel(plugin);
		panel.setSize(225, 480);
		panel.showView();
		panel.doLayout();
		layoutAll(panel);

		write(panel, new File(dir, "panel-time.png"));

		// Switch to the second tab by walking the tree for the group — the tabs themselves are
		// locals inside showView, and exposing them just for a screenshot is not worth it.
		final MaterialTabGroup group = findGroup(panel);
		if (group == null)
		{
			System.out.println("no MaterialTabGroup found");
			return;
		}
		// group.select(tab) is what swaps the displayed content; tab.select() only sets the tab's
		// own state and hands back a boolean.
		group.select(group.getTab(1));
		panel.doLayout();
		layoutAll(panel);
		write(panel, new File(dir, "panel-mobile.png"));

		System.out.println("mobile tab labels:");
		printLabels(panel);
	}

	private static void write(final PlayTimePanel panel, final File out) throws Exception
	{
		final BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(),
			BufferedImage.TYPE_INT_RGB);
		panel.paint(img.getGraphics());
		ImageIO.write(img, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	/** Swing only lays out realised windows, so force it down the tree for an offscreen paint. */
	private static void layoutAll(final Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutAll(child);
			}
		}
	}

	private static MaterialTabGroup findGroup(final Component c)
	{
		if (c instanceof MaterialTabGroup)
		{
			return (MaterialTabGroup) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				final MaterialTabGroup found = findGroup(child);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void printLabels(final Component c)
	{
		if (c instanceof JLabel && c.isShowing() || c instanceof JLabel)
		{
			final String t = ((JLabel) c).getText();
			if (t != null && !t.isEmpty() && !t.startsWith("<html>") && c.isVisible())
			{
				System.out.println("  " + t);
			}
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				printLabels(child);
			}
		}
	}

	private static void set(final Object target, final String field, final Object value)
		throws Exception
	{
		final Field f = PlayTimePlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}
}
