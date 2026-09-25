package com.playtimetracker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Random;

/**
 * Renders {@link PlayTimeChart} straight to a PNG so its layout can be checked without launching
 * a client. Test-only: never shipped, and not referenced by the plugin.
 */
public class ChartRender
{
	public static void main(String[] args) throws Exception
	{
		final File out = new File(args.length > 0 ? args[0] : "chart-preview.png");

		// PlayTimeConfig is an interface whose methods all have defaults, so it can be
		// instantiated directly — no Guice, no client.
		final int days = args.length > 1 ? Integer.parseInt(args[1]) : 14;
		final PlayTimeConfig config = new PlayTimeConfig()
		{
			@Override
			public int chartDays()
			{
				return days;
			}
		};

		final PlayTimePlugin plugin = new PlayTimePlugin();
		final Field configField = PlayTimePlugin.class.getDeclaredField("config");
		configField.setAccessible(true);
		configField.set(plugin, config);

		// A fortnight of plausible play: a couple of blank days, one long session, one short.
		final LocalDate today = plugin.today();
		final Random random = new Random(7);
		for (int i = 0; i < config.chartDays(); i++)
		{
			final LocalDate date = today.minusDays(i);
			final long ticks;
			if (i == 3 || i == 9)
			{
				ticks = 0;
			}
			else if (i == 6)
			{
				ticks = 86_400;
			}
			else
			{
				ticks = 1_500 + random.nextInt(40_000);
			}
			if (ticks > 0)
			{
				final String key = date.format(PlayTimePlugin.DATE_FORMAT);
				plugin.records.put(key, new PlayTimeRecord(key, ticks));
			}
		}

		final PlayTimeChart chart = new PlayTimeChart(plugin);
		// PluginPanel is 225px wide; the chart gets that minus the stats panel's 12px borders.
		final int width = 201;
		final int height = 96;
		chart.setSize(width, height);
		chart.refresh();

		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		chart.paint(image.getGraphics());
		ImageIO.write(image, "png", out);

		System.out.println("wrote " + out.getAbsolutePath());
		System.out.println("zone=" + plugin.zone() + " weekStartsOn=" + plugin.firstDayOfWeek()
			+ " today=" + today);

		System.out.println();
		System.out.println("::playtimedebug, no rollover seen yet:");
		for (String line : plugin.dayBoundaryLines())
		{
			System.out.println("  " + line);
		}

		// Simulate the client having watched the day turn over an hour early, which is the exact
		// symptom this command exists to expose.
		final Field at = PlayTimePlugin.class.getDeclaredField("lastRolloverAt");
		at.setAccessible(true);
		at.set(plugin, ZonedDateTime.now(plugin.zone()).toLocalDate()
			.minusDays(1).atTime(23, 0).atZone(plugin.zone()));
		final Field fromKey = PlayTimePlugin.class.getDeclaredField("lastRolloverFrom");
		fromKey.setAccessible(true);
		fromKey.set(plugin, today.minusDays(1).format(PlayTimePlugin.DATE_FORMAT));

		System.out.println();
		System.out.println("::playtimedebug, after an hour-early rollover:");
		for (String line : plugin.dayBoundaryLines())
		{
			System.out.println("  " + line);
		}
		System.out.println();

		for (final WeekStart ws : WeekStart.values())
		{
			final PlayTimePlugin p = new PlayTimePlugin();
			final Field f = PlayTimePlugin.class.getDeclaredField("config");
			f.setAccessible(true);
			f.set(p, new PlayTimeConfig()
			{
				@Override
				public WeekStart weekStart()
				{
					return ws;
				}
			});
			System.out.printf("  weekStart=%-22s -> %s%n", ws, p.firstDayOfWeek());
		}
	}
}
