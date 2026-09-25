package com.playtimetracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Drives {@link PlayTimePlugin#applyExternalBaseline} the way a real session does — tracked time
 * advancing one tick at a time against a game total that only moves in whole minutes — and checks
 * that no off-client time is reported for an account that never left the client.
 *
 * <p>This is the 47s bug: reported raw, the minute-quantised baseline leaves up to a minute of
 * phase difference that looks like mobile play.
 */
public class ExternalNoise
{
	public static void main(String[] args) throws Exception
	{
		final PlayTimePlugin plugin = new PlayTimePlugin();
		final Field cfg = PlayTimePlugin.class.getDeclaredField("config");
		cfg.setAccessible(true);
		cfg.set(plugin, new PlayTimeConfig()
		{
		});

		final Method apply = PlayTimePlugin.class
			.getDeclaredMethod("applyExternalBaseline", long.class);
		apply.setAccessible(true);

		final String todayKey = plugin.today().format(PlayTimePlugin.DATE_FORMAT);
		final PlayTimeRecord today = new PlayTimeRecord(todayKey, 0);
		plugin.records.put(todayKey, today);

		// A pre-existing account: 11 days of play before the plugin was installed.
		final long preInstallTrue = 11L * 24 * 60 * 100;

		long worstRaw = 0;
		long worstReported = 0;
		int nonZeroReports = 0;

		// 90 minutes of play, one tick at a time. Nothing happens off-client at any point.
		for (int tick = 1; tick <= 90 * 100; tick++)
		{
			today.setTime(tick);

			// The game's true total advances with us; the varc only exposes whole minutes.
			final long trueTotal = preInstallTrue + tick;
			final long reportedMinutes = trueTotal / TICKS_PER_MINUTE;
			apply.invoke(plugin, reportedMinutes * TICKS_PER_MINUTE);

			final long raw = raw(plugin);
			final long reported = plugin.getExternalSinceInstallTicks();
			worstRaw = Math.max(worstRaw, raw);
			worstReported = Math.max(worstReported, reported);
			if (reported > 0)
			{
				nonZeroReports++;
			}
		}

		System.out.println("90 minutes played, zero of it off-client:");
		System.out.printf("  worst raw OLD-PREINSTALL : %d ticks (%d s)%n",
			worstRaw, Math.round(worstRaw * 0.6));
		System.out.printf("  worst reported external  : %d ticks (%d s)%n",
			worstReported, Math.round(worstReported * 0.6));
		System.out.printf("  ticks reporting non-zero : %d of 9000%n", nonZeroReports);
		System.out.println(worstReported == 0
			? "  PASS - no phantom off-client time"
			: "  FAIL - still reporting phantom off-client time");

		// And a genuine 20 minutes off-client must still come through.
		final long trueTotal = preInstallTrue + 90 * 100 + 20 * 100;
		apply.invoke(plugin, (trueTotal / TICKS_PER_MINUTE) * TICKS_PER_MINUTE);
		final long real = plugin.getExternalSinceInstallTicks();
		System.out.printf("%nafter 20 real minutes off-client: reported %d ticks (%d min) - %s%n",
			real, real / TICKS_PER_MINUTE, real / TICKS_PER_MINUTE == 20 ? "PASS" : "CHECK");
	}

	private static final long TICKS_PER_MINUTE = 100L;

	private static long raw(final PlayTimePlugin p) throws Exception
	{
		final Method rt = PlayTimePlugin.class.getDeclaredMethod("recordTicks", String.class);
		rt.setAccessible(true);
		return (long) rt.invoke(p, PlayTimePlugin.EXTERNAL_TIME_KEY)
			- (long) rt.invoke(p, PlayTimePlugin.PRE_INSTALL_KEY);
	}
}
