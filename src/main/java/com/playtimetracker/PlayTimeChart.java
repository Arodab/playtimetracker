package com.playtimetracker;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * A bar chart of the last N days of tracked time, painted by hand.
 *
 * <p>Drawn rather than pulled from a charting library on purpose: the hub ships plugin jars to
 * every user, and a dependency worth hundreds of kilobytes for one 200-pixel panel is not a
 * trade worth making. Everything here is Graphics2D and arithmetic.
 */
class PlayTimeChart extends JPanel
{
	private static final DateTimeFormatter AXIS_FORMAT = DateTimeFormatter.ofPattern("d/M");
	private static final int TOP_PAD = 14;
	private static final int BOTTOM_PAD = 16;
	private static final int LEFT_PAD = 2;
	private static final int RIGHT_PAD = 2;
	private static final int MIN_BAR_WIDTH = 3;
	private static final int GAP = 1;

	private final PlayTimePlugin plugin;

	/** Bar the pointer is over, or -1. Index 0 is the oldest day shown. */
	private int hovered = -1;
	private LocalDate[] dates = new LocalDate[0];
	private long[] values = new long[0];
	private long max = 0;

	PlayTimeChart(final PlayTimePlugin plugin)
	{
		this.plugin = plugin;
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setPreferredSize(new Dimension(0, 96));
		setToolTipText("");

		addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				final int was = hovered;
				hovered = barAt(e.getX());
				if (was != hovered)
				{
					repaint();
				}
			}
		});
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseExited(MouseEvent e)
			{
				if (hovered != -1)
				{
					hovered = -1;
					repaint();
				}
			}
		});
	}

	/**
	 * Re-reads the plugin's records. Called from the game thread, so the actual repaint is handed
	 * to the EDT rather than done here.
	 */
	void refresh()
	{
		final int days = Math.max(1, plugin.getConfig().chartDays());
		final LocalDate end = plugin.today();

		final LocalDate[] newDates = new LocalDate[days];
		final long[] newValues = new long[days];
		long newMax = 0;

		for (int i = 0; i < days; i++)
		{
			// Index 0 is the oldest day, so the chart reads left-to-right in time order.
			final LocalDate date = end.minusDays(days - 1L - i);
			final long ticks = plugin.ticksOn(date);
			newDates[i] = date;
			newValues[i] = ticks;
			newMax = Math.max(newMax, ticks);
		}

		dates = newDates;
		values = newValues;
		max = newMax;

		SwingUtilities.invokeLater(this::repaint);
	}

	private int barAt(final int x)
	{
		if (values.length == 0)
		{
			return -1;
		}
		final int plotWidth = getWidth() - LEFT_PAD - RIGHT_PAD;
		if (plotWidth <= 0)
		{
			return -1;
		}
		final double slot = plotWidth / (double) values.length;
		final int index = (int) ((x - LEFT_PAD) / slot);
		return index >= 0 && index < values.length ? index : -1;
	}

	@Override
	public String getToolTipText(final MouseEvent event)
	{
		final int index = barAt(event.getX());
		if (index < 0)
		{
			return null;
		}
		return dates[index].format(DateTimeFormatter.ofPattern("EEE d MMM"))
			+ ": " + plugin.formatTicks(values[index]);
	}

	@Override
	protected void paintComponent(final Graphics g)
	{
		super.paintComponent(g);

		final Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setFont(FontManager.getRunescapeSmallFont());
			final FontMetrics fm = g2.getFontMetrics();

			final int width = getWidth();
			final int height = getHeight();
			final int plotWidth = width - LEFT_PAD - RIGHT_PAD;
			final int plotHeight = height - TOP_PAD - BOTTOM_PAD;

			if (values.length == 0 || plotWidth <= 0 || plotHeight <= 0)
			{
				return;
			}

			// Baseline.
			g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1f));
			g2.drawLine(LEFT_PAD, TOP_PAD + plotHeight, width - RIGHT_PAD, TOP_PAD + plotHeight);

			if (max == 0)
			{
				// Nothing tracked in range — say so rather than drawing an empty grid.
				final String msg = "No play time in the last " + values.length + " days";
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, TOP_PAD + plotHeight / 2);
				return;
			}

			// Peak label, top-left, so the bars have a scale to be read against.
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g2.drawString("peak " + shortDuration(max), LEFT_PAD, TOP_PAD - 3);

			// Fractional so every requested day fits whatever the width: integer division floors,
			// and at 60 days in a 200px panel the rounding error drops the most recent bars.
			final double slot = plotWidth / (double) values.length;
			// Only spend a pixel on the gap when bars are wide enough to still read as bars.
			final int barWidth = Math.max(1, (int) Math.floor(slot) - (slot >= MIN_BAR_WIDTH ? GAP : 0));
			final Color barColour = plugin.getConfig().chartColour();

			for (int i = 0; i < values.length; i++)
			{
				final int x = LEFT_PAD + (int) Math.round(i * slot);

				// Round up so a day with any time at all is always visible as at least one pixel.
				final int barHeight = values[i] == 0
					? 0
					: Math.max(1, (int) Math.ceil((double) values[i] * plotHeight / max));
				final int y = TOP_PAD + plotHeight - barHeight;

				if (values[i] == 0)
				{
					// A dim stub marks a day with no play, distinguishing it from a gap.
					g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
					g2.fillRect(x, TOP_PAD + plotHeight - 1, barWidth, 1);
					continue;
				}

				g2.setColor(i == hovered ? barColour.brighter() : barColour);
				g2.fillRect(x, y, barWidth, barHeight);
			}

			// Date labels at each end only — there is no room for more at this width.
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			final int labelY = height - 4;
			g2.drawString(dates[0].format(AXIS_FORMAT), LEFT_PAD, labelY);
			final String last = dates[dates.length - 1].format(AXIS_FORMAT);
			g2.drawString(last, width - RIGHT_PAD - fm.stringWidth(last), labelY);

			if (hovered >= 0 && hovered < values.length)
			{
				final String label = dates[hovered].format(AXIS_FORMAT) + "  " + shortDuration(values[hovered]);
				final int labelWidth = fm.stringWidth(label);
				// Keep the readout inside the panel when hovering near either edge.
				final int lx = Math.min(Math.max(LEFT_PAD, LEFT_PAD + (int) Math.round(hovered * slot) - labelWidth / 2),
					width - RIGHT_PAD - labelWidth);
				g2.setColor(Color.WHITE);
				g2.drawString(label, lx, labelY);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	/** Compact form for axis furniture: "3h 20m", "45m", "0m". */
	private static String shortDuration(final long ticks)
	{
		final long minutes = ticks / 100;
		final long hours = minutes / 60;
		final long mins = minutes % 60;
		return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
	}
}
