package com.playtimetracker;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "Play Time Tracker"
)
public class PlayTimePlugin extends Plugin
{
	public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy");
	/** External baseline (game total minus RuneLite-tracked time): pre-plugin + off-client time. */
	public static final String EXTERNAL_TIME_KEY = "OLD";
	/** The external baseline captured at the first read: time that existed before this plugin tracked. */
	public static final String PRE_INSTALL_KEY = "PREINSTALL";
	/** Last game total (ticks) synced from. The playtime value only refreshes at login, so we sync once per change. */
	public static final String GAME_TOTAL_KEY = "GAMETOTAL";
	/** Chat command to wipe the current character's data: {@code ::resetplaytime confirm}. */
	private static final String RESET_COMMAND = "resetplaytime";

	// e.g. "Time Played: 1378 days, 14 hours"
	private static final Pattern TIME_PLAYED_PATTERN =
		Pattern.compile("([\\d,]+)\\s*days?[,\\s]+([\\d,]+)\\s*hours?", Pattern.CASE_INSENSITIVE);
	// Sanity ceiling for the playtime varc read as minutes (~114 years) — rejects nonsense values.
	private static final long MAX_SANE_PLAYTIME_MINUTES = 60_000_000L;
	private static final int LOGIN_READ_TRIES = 30;

	private boolean loadedData = false;
	private String currentPlayer = null;
	private boolean pendingSummaryRead = false;
	private int summaryReadTries = 0;

	private long sessionTicks = 0;
	private long totalTicks = 0;

	// Cached period totals, recomputed once per tick so the panel and overlay read them cheaply.
	private long todayTicks = 0;
	private long weekTicks = 0;
	private long monthTicks = 0;
	private long yearTicks = 0;

	public long getSessionTicks() {
		return sessionTicks;
	}

	public long getTotalTicks() {
		return totalTicks;
	}

	public String getCurrentPlayer() {
		return currentPlayer;
	}

	/** Total time this plugin has tracked in RuneLite (excludes the external/baseline records). */
	public long getTrackedTicks() {
		long sum = 0;
		for (PlayTimeRecord r : records.values()) {
			if (r != null && !isSpecialKey(r.getDate())) {
				sum += r.getTime();
			}
		}
		return sum;
	}

	/** Off-client time accumulated since this plugin started tracking (mobile, other clients, plugin off). */
	public long getExternalSinceInstallTicks() {
		return Math.max(0, recordTicks(EXTERNAL_TIME_KEY) - recordTicks(PRE_INSTALL_KEY));
	}

	/** Calendar days from the first tracked day to today (inclusive), at least 1. */
	public long getDaysSinceTrackingStarted() {
		LocalDate earliest = null;
		for (PlayTimeRecord r : records.values()) {
			if (r == null || r.getDate() == null || isSpecialKey(r.getDate())) {
				continue;
			}
			final LocalDate d = parseDate(r.getDate());
			if (d != null && (earliest == null || d.isBefore(earliest))) {
				earliest = d;
			}
		}
		if (earliest == null) {
			return 1;
		}
		return Math.max(1, ChronoUnit.DAYS.between(earliest, LocalDate.now()) + 1);
	}

	public long getTodayTicks() {
		return todayTicks;
	}

	public long getWeekTicks() {
		return weekTicks;
	}

	public long getMonthTicks() {
		return monthTicks;
	}

	public long getYearTicks() {
		return yearTicks;
	}

	public long getWeekAvgTicks() {
		final LocalDate today = LocalDate.now();
		final LocalDate start = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).getFirstDayOfWeek()));
		return weekTicks / periodDays(start, today);
	}

	public long getMonthAvgTicks() {
		final LocalDate today = LocalDate.now();
		return monthTicks / periodDays(today.withDayOfMonth(1), today);
	}

	public long getYearAvgTicks() {
		final LocalDate today = LocalDate.now();
		return yearTicks / periodDays(today.withDayOfYear(1), today);
	}

	public long getTrackedAvgTicks() {
		return getTrackedTicks() / getDaysSinceTrackingStarted();
	}

	private static long periodDays(LocalDate start, LocalDate end) {
		return Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);
	}

	/** Recomputes the cached today/week/month/year totals; call once per tick. */
	private void updateStatCache() {
		final PlayTimeRecord rec = getCurrentRecord();
		todayTicks = rec == null ? 0 : rec.getTime();
		final LocalDate today = LocalDate.now();
		final DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
		weekTicks = ticksBetweenDates(today.with(TemporalAdjusters.previousOrSame(firstDay)), today);
		monthTicks = ticksBetweenDates(today.withDayOfMonth(1), today);
		yearTicks = ticksBetweenDates(today.withDayOfYear(1), today);
	}

	private long ticksBetweenDates(LocalDate startDate, LocalDate endDate) {
		final long days = ChronoUnit.DAYS.between(startDate, endDate);
		long ticks = 0;
		for (int i = 0; i <= days; i++) {
			final PlayTimeRecord r = records.get(startDate.plusDays(i).format(DATE_FORMAT));
			if (r != null) {
				ticks += r.getTime();
			}
		}
		return ticks;
	}

	/** Formats tracker ticks (0.6s each, 100/min) as "Xd, Yh, Zm[, Ws]". */
	public String formatTicks(long time) {
		final long days = time / (100 * 60 * 24);
		time -= days * (100 * 60 * 24);
		final long hours = time / (100 * 60);
		time -= hours * 100 * 60;
		final long min = time / 100;
		time -= min * 100;
		if (config.showSeconds()) {
			return String.format("%dd, %dh, %dm, %ds", days, hours, min, (long) (time * 0.6));
		}
		return String.format("%dd, %dh, %dm", days, hours, min);
	}

	private PlayTimePanel panel;
	private NavigationButton navButton;

	@Inject
	private TimeRecordWriter writer;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PlayTimeConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PlayTimeOverlay overlay;

	private PlayTimeRecord record;
	public Map<String, PlayTimeRecord> records = new ConcurrentHashMap<>();

	@Override
	protected void startUp() throws Exception
	{
		panel = new PlayTimePanel(this);

		final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "pluginicon.png");

		navButton = NavigationButton.builder()
				.tooltip("Play Time")
				.priority(6)
				.icon(icon)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// Enabled mid-session: sync play time without waiting for a login event.
			pendingSummaryRead = true;
			summaryReadTries = LOGIN_READ_TRIES;
		}

		// Data loads in onGameTick once the local player (character name) is known.
		panel.showView();
	}

	public PlayTimeConfig getConfig() {
		return config;
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(overlay);
		saveData();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.showView();
			return;
		}

		final Player local = client.getLocalPlayer();
		final String name = local == null ? null : local.getName();
		if (name == null || name.isEmpty())
		{
			// Character not fully loaded yet; don't track under an empty key.
			panel.showView();
			return;
		}
		if (!name.equals(currentPlayer))
		{
			loadDataFor(name);
		}

		sessionTicks++;
		totalTicks++;

		final PlayTimeRecord rec = getCurrentRecord();
		rec.setTime(rec.getTime() + 1);

		// Read the account-summary total after tracking, and never let it block tracking.
		if (pendingSummaryRead && config.countExternalTime())
		{
			try
			{
				final Long gameTicks = readAccountSummaryTicks();
				if (gameTicks != null)
				{
					applyExternalBaseline(gameTicks);
					pendingSummaryRead = false;
				}
				else if (--summaryReadTries <= 0)
				{
					pendingSummaryRead = false;
				}
			}
			catch (Exception ex)
			{
				log.debug("Play Time Tracker: account summary read failed", ex);
				pendingSummaryRead = false;
			}
		}

		if (sessionTicks % 10 == 0) {
			saveData();
		}
		updateStatCache();
		panel.showView();
	}

	public PlayTimeRecord getCurrentRecord() {
		if (!loadedData) {
			return null;
		}
		final String today = LocalDate.now().format(DATE_FORMAT);
		if (record != null && today.equals(record.getDate())) {
			return record;
		}
		PlayTimeRecord rec = records.get(today);
		if (rec == null) {
			rec = new PlayTimeRecord(today, 0);
			records.put(rec.getDate(), rec);
		}
		record = rec;
		return rec;
	}

	public void resetCounter() {
		sessionTicks = 0;
	}

	/** Loads the given character's records, replacing any currently-held data. */
	public void loadDataFor(String player) {
		records.clear();
		record = null;
		totalTicks = 0;
		writer.setPlayerUsername(player);
		ArrayList<PlayTimeRecord> recs = writer.loadPlayTimeRecords();
		for (PlayTimeRecord rec : recs) {
			if (rec == null || rec.getDate() == null) {
				continue;
			}
			records.put(rec.getDate(), rec);
			// PREINSTALL / GAMETOTAL are reference markers, not part of the running total.
			if (!PRE_INSTALL_KEY.equals(rec.getDate()) && !GAME_TOTAL_KEY.equals(rec.getDate())) {
				totalTicks += rec.getTime();
			}
		}
		currentPlayer = player;
		loadedData = true;
	}

	public void saveData() {
		if (!loadedData) {
			return;
		}
		writer.writePlayTimeFile(new ArrayList<>(records.values()));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			// Try reading total play time shortly after login (the varc is populated for the
			// in-game play-time reminder), so we can sync without opening the summary panel.
			pendingSummaryRead = true;
			summaryReadTries = LOGIN_READ_TRIES;
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// Persist the current character, then clear state so the next login loads
			// the correct character's data instead of mixing characters together.
			saveData();
			sessionTicks = 0;
			loadedData = false;
			pendingSummaryRead = false;
			summaryReadTries = 0;
			currentPlayer = null;
			record = null;
			records.clear();
			totalTicks = 0;
		}
		if (panel != null) {
			panel.showView();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.ACCOUNT_SUMMARY_SIDEPANEL)
		{
			// Read a few ticks later, once the panel's text has been populated.
			pendingSummaryRead = true;
			summaryReadTries = 5;
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!RESET_COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		final String[] args = event.getArguments();
		if (args == null || args.length == 0 || !"confirm".equalsIgnoreCase(args[0]))
		{
			addGameMessage("Play Time Tracker: type ::" + RESET_COMMAND + " confirm to wipe this character's tracked time.");
			return;
		}
		final String who = currentPlayer;
		records.clear();
		record = null;
		totalTicks = 0;
		sessionTicks = 0;
		if (loadedData)
		{
			saveData();
		}
		addGameMessage("Play Time Tracker: tracker reset" + (who != null ? " for " + who : "") + ".");
	}

	private void addGameMessage(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
	}

	/**
	 * Reads total "Time Played" in tracker ticks, or null if unavailable. The value comes from the
	 * minute-resolution {@code ACCOUNT_SUMMARY_PLAYTIME} varc; when the summary panel happens to be
	 * open we cross-check it against the displayed days/hours to confirm the unit.
	 */
	private Long readAccountSummaryTicks()
	{
		final int minutes = client.getVarcIntValue(VarClientID.ACCOUNT_SUMMARY_PLAYTIME);
		log.debug("Play Time Tracker: summary read triggered, varc = {} min", minutes);
		if (minutes <= 0)
		{
			return null;
		}

		final Widget contents = client.getWidget(InterfaceID.AccountSummarySidepanel.SUMMARY_CONTENTS);
		if (contents != null)
		{
			final StringBuilder sb = new StringBuilder();
			collectText(contents, sb);
			final Matcher m = TIME_PLAYED_PATTERN.matcher(sb);
			if (m.find())
			{
				try
				{
					final long panelDays = Long.parseLong(m.group(1).replace(",", ""));
					final long panelHours = Long.parseLong(m.group(2).replace(",", ""));
					if (minutes / 1440 == panelDays && (minutes % 1440) / 60 == panelHours)
					{
						return minutes * 100L;
					}
					log.debug("Play Time Tracker: playtime varc ({} min) disagrees with panel {}d {}h; skipping",
							minutes, panelDays, panelHours);
					return null;
				}
				catch (NumberFormatException ignored)
				{
					// fall through to the bounded read
				}
			}
		}

		// Panel not open (e.g. reading on login): trust the varc as minutes within a sane bound.
		if (minutes < MAX_SANE_PLAYTIME_MINUTES)
		{
			return minutes * 100L;
		}
		return null;
	}

	private static void collectText(Widget widget, StringBuilder sb)
	{
		if (widget == null)
		{
			return;
		}
		final String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			sb.append(text).append('\n');
		}
		collectChildren(widget.getStaticChildren(), sb);
		collectChildren(widget.getDynamicChildren(), sb);
		collectChildren(widget.getNestedChildren(), sb);
	}

	private static void collectChildren(Widget[] children, StringBuilder sb)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			collectText(child, sb);
		}
	}

	/**
	 * Uses the game's true "Time Played" as the total. The gap over RuneLite-tracked time is the
	 * external baseline; the part of it accrued after installation is reported as off-client time.
	 */
	private void applyExternalBaseline(long gameTicks)
	{
		// The playtime value only refreshes at login. Ignore re-reads of the same frozen value —
		// otherwise the baseline would shrink as tracked time grows during the session.
		if (gameTicks == recordTicks(GAME_TOTAL_KEY))
		{
			return;
		}
		final long trackedSum = getTrackedTicks();
		long baseline = gameTicks - trackedSum;
		if (baseline < 0)
		{
			// Minute-resolution rounding; never let the baseline go negative.
			baseline = 0;
		}
		final long prev = recordTicks(EXTERNAL_TIME_KEY);
		setSpecialRecord(EXTERNAL_TIME_KEY, baseline);
		// Capture the pre-install baseline once so "external since install" starts at zero.
		if (records.get(PRE_INSTALL_KEY) == null)
		{
			setSpecialRecord(PRE_INSTALL_KEY, baseline);
		}
		setSpecialRecord(GAME_TOTAL_KEY, gameTicks);
		totalTicks = baseline + trackedSum;
		log.debug("Play Time Tracker: baseline {} -> {} (game={} tracked={} external={})",
				prev, baseline, gameTicks, trackedSum, getExternalSinceInstallTicks());
		saveData();
	}

	private void setSpecialRecord(String key, long ticks)
	{
		final PlayTimeRecord r = records.get(key);
		if (r == null)
		{
			records.put(key, new PlayTimeRecord(key, ticks));
		}
		else
		{
			r.setTime(ticks);
		}
	}

	private long recordTicks(String key)
	{
		final PlayTimeRecord r = records.get(key);
		return r == null ? 0 : r.getTime();
	}

	private static boolean isSpecialKey(String date)
	{
		return EXTERNAL_TIME_KEY.equals(date) || PRE_INSTALL_KEY.equals(date) || GAME_TOTAL_KEY.equals(date);
	}

	/** CSV of per-day play time (excludes the special baseline records), sorted by date ascending. */
	public String toDailyCsv() {
		List<PlayTimeRecord> daily = new ArrayList<>();
		for (PlayTimeRecord r : new ArrayList<>(records.values())) {
			if (r == null || r.getDate() == null || isSpecialKey(r.getDate()) || parseDate(r.getDate()) == null) {
				continue;
			}
			daily.add(r);
		}
		daily.sort(Comparator.comparing((PlayTimeRecord r) -> parseDate(r.getDate())));

		StringBuilder sb = new StringBuilder("date,hours,minutes,seconds\n");
		for (PlayTimeRecord r : daily) {
			final long ticks = r.getTime();
			// 1 game tick = 0.6s; 100 ticks = 1 minute, 6000 ticks = 1 hour.
			final double hours = ticks / 6000.0;
			final double minutes = ticks / 100.0;
			final long seconds = Math.round(ticks * 0.6);
			sb.append(parseDate(r.getDate()).format(DateTimeFormatter.ISO_LOCAL_DATE)).append(',')
					.append(String.format(Locale.US, "%.4f", hours)).append(',')
					.append(String.format(Locale.US, "%.2f", minutes)).append(',')
					.append(seconds).append('\n');
		}
		return sb.toString();
	}

	private static LocalDate parseDate(String s) {
		try {
			return LocalDate.parse(s, DATE_FORMAT);
		} catch (Exception ex) {
			return null;
		}
	}

	@Provides
	PlayTimeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PlayTimeConfig.class);
	}
}
