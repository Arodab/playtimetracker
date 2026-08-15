package com.playtimetracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("play-time")
public interface PlayTimeConfig extends Config
{
	@ConfigSection(
			name = "On-screen overlay",
			description = "Show any of the stats individually as a movable on-screen overlay",
			position = 10,
			closedByDefault = true
	)
	String overlaySection = "overlay";

	@ConfigItem(keyName = "overlayEnabled", name = "Enable overlay", description = "Show the on-screen overlay", section = overlaySection, position = 1)
	default boolean overlayEnabled() { return false; }

	@ConfigItem(keyName = "overlaySession", name = "Session", description = "Show session time on the overlay", section = overlaySection, position = 2)
	default boolean overlaySession() { return false; }

	@ConfigItem(keyName = "overlayToday", name = "Today", description = "Show today's time on the overlay", section = overlaySection, position = 3)
	default boolean overlayToday() { return false; }

	@ConfigItem(keyName = "overlayWeek", name = "This week", description = "Show this week's time on the overlay", section = overlaySection, position = 4)
	default boolean overlayWeek() { return false; }

	@ConfigItem(keyName = "overlayWeekAvg", name = "This week avg/day", description = "Show this week's daily average on the overlay", section = overlaySection, position = 5)
	default boolean overlayWeekAvg() { return false; }

	@ConfigItem(keyName = "overlayMonth", name = "This month", description = "Show this month's time on the overlay", section = overlaySection, position = 6)
	default boolean overlayMonth() { return false; }

	@ConfigItem(keyName = "overlayMonthAvg", name = "This month avg/day", description = "Show this month's daily average on the overlay", section = overlaySection, position = 7)
	default boolean overlayMonthAvg() { return false; }

	@ConfigItem(keyName = "overlayYear", name = "This year", description = "Show this year's time on the overlay", section = overlaySection, position = 8)
	default boolean overlayYear() { return false; }

	@ConfigItem(keyName = "overlayYearAvg", name = "This year avg/day", description = "Show this year's daily average on the overlay", section = overlaySection, position = 9)
	default boolean overlayYearAvg() { return false; }

	@ConfigItem(keyName = "overlayTotalTracked", name = "Total tracked", description = "Show total tracked time on the overlay", section = overlaySection, position = 10)
	default boolean overlayTotalTracked() { return false; }

	@ConfigItem(keyName = "overlayTrackedAvg", name = "Total tracked avg/day", description = "Show the since-tracking daily average on the overlay", section = overlaySection, position = 11)
	default boolean overlayTrackedAvg() { return false; }

	@ConfigItem(keyName = "overlayExternal", name = "External/mobile", description = "Show external/mobile time on the overlay", section = overlaySection, position = 12)
	default boolean overlayExternal() { return false; }

	@ConfigItem(keyName = "overlayTotalInGame", name = "Total (in-game)", description = "Show the in-game total on the overlay", section = overlaySection, position = 13)
	default boolean overlayTotalInGame() { return false; }

	@ConfigItem(
			keyName = "showAverages",
			name = "Show Averages",
			description = "Show average values for week and month."
	)
	default boolean showAverages()
	{
		return false;
	}

	@ConfigItem(
			keyName = "showSeconds",
			name = "Show Seconds",
			description = "Show seconds on times"
	)
	default boolean showSeconds()
	{
		return false;
	}

	@ConfigItem(
			keyName = "countExternalTime",
			name = "Count time outside RuneLite",
			description = "When the in-game Account Summary panel is open, use its 'Time Played' as your true total "
					+ "(captures time played on mobile or before installing the plugin)."
	)
	default boolean countExternalTime()
	{
		return true;
	}
}
