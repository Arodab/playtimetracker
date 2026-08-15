package com.playtimetracker;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;

/**
 * Optional on-screen overlay showing any subset of the tracker's stats, chosen individually in config.
 */
class PlayTimeOverlay extends OverlayPanel
{
	private final PlayTimePlugin plugin;
	private final PlayTimeConfig config;

	@Inject
	private PlayTimeOverlay(PlayTimePlugin plugin, PlayTimeConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.overlayEnabled() || plugin.getSessionTicks() == 0 || plugin.getCurrentPlayer() == null)
		{
			return null;
		}

		panelComponent.getChildren().clear();

		addLine(config.overlaySession(), "Session", plugin.getSessionTicks());
		addLine(config.overlayToday(), "Today", plugin.getTodayTicks());
		addLine(config.overlayWeek(), "Week", plugin.getWeekTicks());
		addLine(config.overlayWeekAvg(), "Week avg/day", plugin.getWeekAvgTicks());
		addLine(config.overlayMonth(), "Month", plugin.getMonthTicks());
		addLine(config.overlayMonthAvg(), "Month avg/day", plugin.getMonthAvgTicks());
		addLine(config.overlayYear(), "Year", plugin.getYearTicks());
		addLine(config.overlayYearAvg(), "Year avg/day", plugin.getYearAvgTicks());
		addLine(config.overlayTotalTracked(), "Tracked", plugin.getTrackedTicks());
		addLine(config.overlayTrackedAvg(), "Tracked avg/day", plugin.getTrackedAvgTicks());
		addLine(config.overlayExternal(), "External", plugin.getExternalSinceInstallTicks());
		addLine(config.overlayTotalInGame(), "Total", plugin.getTotalTicks());

		if (panelComponent.getChildren().isEmpty())
		{
			return null;
		}

		panelComponent.getChildren().add(0, TitleComponent.builder().text("Play Time").build());
		return super.render(graphics);
	}

	private void addLine(boolean enabled, String label, long ticks)
	{
		if (!enabled)
		{
			return;
		}
		panelComponent.getChildren().add(LineComponent.builder()
				.left(label)
				.right(plugin.formatTicks(ticks))
				.build());
	}
}
