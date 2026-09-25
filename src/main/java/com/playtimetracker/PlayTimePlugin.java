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
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
	/** Chat command that reports exactly when the tracked day rolls over: {@code ::playtimedebug}. */
	private static final String DEBUG_COMMAND = "playtimedebug";

	// e.g. "Time Played: 1378 days, 14 hours"
	private static final Pattern TIME_PLAYED_PATTERN =
		Pattern.compile("([\\d,]+)\\s*days?[,\\s]+([\\d,]+)\\s*hours?", Pattern.CASE_INSENSITIVE);
	// Sanity ceiling for the playtime varc read as minutes (~114 years) — rejects nonsense values.
	private static final long MAX_SANE_PLAYTIME_MINUTES = 60_000_000L;
	private static final int LOGIN_READ_TRIES = 30;
	/** 100 ticks a minute, and the resolution the game's own Time Played is reported at. */
	private static final long TICKS_PER_MINUTE = 100L;
	/**
	 * Ceiling on a single credited login/loading gap, in ticks (5 minutes). A stalled connect or a
	 * machine coming back from sleep must not inject a huge block of time nobody played.
	 */
	private static final long MAX_GAP_TICKS = 5 * TICKS_PER_MINUTE;

	private boolean loadedData = false;
	private String currentPlayer = null;
	private boolean pendingSummaryRead = false;
	private int summaryReadTries = 0;

	private long sessionTicks = 0;
	private long totalTicks = 0;

	// Cached period totals, recomputed once per tick so the panel and overlay read them cheaply.
	private long todayTicks = 0;
	// Previous observation of today's total, to catch it going backwards within a single day.
	private long lastTodayTicks = 0;
	private String lastTodayKey = null;
	private long lastDropMessageMs = 0;
	// When this client last saw the date key change, and what it changed from. Null until seen.
	private ZonedDateTime lastRolloverAt = null;
	private String lastRolloverFrom = null;
	/** Wall clock at the moment we left LOGGED_IN for a login or an in-session load, else 0. */
	private long gapStartMs = 0;
	/** Ticks owed for that gap, credited on the first tick once records are loaded. */
	private long pendingGapTicks = 0;
	private static final long DROP_MESSAGE_COOLDOWN_MS = 60_000L;
	private long weekTicks = 0;
	private long monthTicks = 0;
	private long yearTicks = 0;

	/**
	 * The zone that decides when a day rolls over: whatever this computer reports. Kept as a
	 * method rather than inlining {@code ZoneId.systemDefault()} at each call site so there is one
	 * definition of "today", and so {@code ::playtimedebug} reports the same value the records use.
	 */
	public ZoneId zone() {
		return ZoneId.systemDefault();
	}

	/** Today's date in the configured zone. Every day key in the records map comes from here. */
	public LocalDate today() {
		return LocalDate.now(zone());
	}

	/** The day "This week" counts from. */
	public DayOfWeek firstDayOfWeek() {
		return config.weekStart().resolve();
	}

	/** Tracked ticks recorded on the given day, or 0. Used by the chart. */
	public long ticksOn(LocalDate date) {
		final PlayTimeRecord r = records.get(date.format(DATE_FORMAT));
		return r == null ? 0 : r.getTime();
	}

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

	/**
	 * Off-client time accumulated since this plugin started tracking (mobile, other clients,
	 * plugin off).
	 *
	 * <p>Reported to the minute, and zero below that, because it cannot be known more precisely
	 * than that. The baseline is {@code gameTicks - trackedSum}, where the game total arrives as
	 * whole minutes while the tracked sum advances every tick — so the baseline sawtooths by up to
	 * a minute between varc updates, and PREINSTALL was captured at an arbitrary point on that
	 * sawtooth. Subtracting the two leaves up to a minute of phase difference that is not play
	 * time. Reporting it raw showed 47s of "mobile" on an account that had never left the client.
	 */
	public long getExternalSinceInstallTicks() {
		final long raw = recordTicks(EXTERNAL_TIME_KEY) - recordTicks(PRE_INSTALL_KEY);
		if (raw < TICKS_PER_MINUTE) {
			return 0;
		}
		return raw - (raw % TICKS_PER_MINUTE);
	}

	/** Off-client time per calendar day since tracking began. */
	public long getExternalAvgTicks() {
		return getExternalSinceInstallTicks() / getDaysSinceTrackingStarted();
	}

	/** Time that existed before this plugin ever ran — the game's total at first sync. */
	public long getPreInstallTicks() {
		return recordTicks(PRE_INSTALL_KEY);
	}

	/** Share of time since install that happened off this client, 0-100. */
	public int getExternalSharePercent() {
		final long external = getExternalSinceInstallTicks();
		final long total = external + getTrackedTicks();
		return total == 0 ? 0 : (int) Math.round(external * 100.0 / total);
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
		return Math.max(1, ChronoUnit.DAYS.between(earliest, today()) + 1);
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
		final LocalDate today = today();
		final LocalDate start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek()));
		return weekTicks / periodDays(start, today);
	}

	public long getMonthAvgTicks() {
		final LocalDate today = today();
		return monthTicks / periodDays(today.withDayOfMonth(1), today);
	}

	public long getYearAvgTicks() {
		final LocalDate today = today();
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
		final LocalDate today = today();
		final String todayKey = today.format(DATE_FORMAT);
		// Today's total only ever climbs within a day. A drop with the date unchanged means the
		// record was replaced or the map rebuilt underneath us — which is the "random reset" users
		// report and nothing else here can see. WARN so it reaches a hub user's log without --debug.
		if (todayKey.equals(lastTodayKey) && todayTicks < lastTodayTicks) {
			log.warn("Play Time Tracker: today's total DROPPED {} -> {} on {} "
							+ "(records={}, keys={}, player={}, session={}, loaded={})",
					lastTodayTicks, todayTicks, todayKey, records.size(), records.keySet(),
					debugName(currentPlayer), sessionTicks, loadedData);
			notifyDrop(lastTodayTicks, todayTicks);
		}
		lastTodayKey = todayKey;
		lastTodayTicks = todayTicks;
		final DayOfWeek firstDay = firstDayOfWeek();
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

	// --- TEMPORARY DIAGNOSTIC (remove once the "random reset" report is settled) ---
	/** Renders a name so invisible differences (nbsp, tabs, trailing space) show up in the log. */
	private static String debugName(String s)
	{
		if (s == null)
		{
			return "<null>";
		}
		final StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			if (c < 0x20 || c > 0x7E)
			{
				sb.append(String.format("\\u%04X", (int) c));
			}
			else
			{
				sb.append(c);
			}
		}
		return sb.append('"').toString();
	}

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

		log.debug("Play Time Tracker: startUp zone={} (system {}) offset={} weekStartsOn={} today={}",
				zone(), ZoneId.systemDefault(),
				zone().getRules().getOffset(java.time.Instant.now()),
				firstDayOfWeek(), today().format(DATE_FORMAT));

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
			try
			{
				loadDataFor(name);
			}
			catch (Exception ex)
			{
				// A throw here repeats every tick and stops the counter dead, because it lands
				// before sessionTicks++ (see CRASH_NOTES.md). Carry on with an empty set instead;
				// the writer refuses to persist over a file it could not read, so nothing is lost.
				log.warn("Play Time Tracker: could not load data for {}, tracking from empty",
						debugName(name), ex);
				currentPlayer = name;
				loadedData = true;
			}
		}

		sessionTicks++;
		totalTicks++;

		final PlayTimeRecord rec = getCurrentRecord();
		if (rec == null)
		{
			// No record to bill this tick to, but the session counter still runs, so the panel
			// shows the tracker is alive rather than sitting on "Login for times to be displayed".
			panel.showView();
			return;
		}
		rec.setTime(rec.getTime() + 1);

		if (pendingGapTicks > 0)
		{
			// Time spent logging in or loading, now that there is a record to bill it to.
			rec.setTime(rec.getTime() + pendingGapTicks);
			sessionTicks += pendingGapTicks;
			totalTicks += pendingGapTicks;
			log.debug("Play Time Tracker: credited {} ticks of login/loading time", pendingGapTicks);
			pendingGapTicks = 0;
		}

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
		final String today = today().format(DATE_FORMAT);
		if (record != null && today.equals(record.getDate())) {
			return record;
		}
		PlayTimeRecord rec = records.get(today);
		if (rec == null) {
			// This is the exact moment "Today" goes to zero. Log why.
			log.debug("Play Time Tracker: minting NEW record for {} (previous cached record={}, "
							+ "records held={}, keys={}, player={})",
					today,
					record == null ? "<none>" : record.getDate() + "=" + record.getTime(),
					records.size(), records.keySet(), debugName(currentPlayer));
			rec = new PlayTimeRecord(today, 0);
			records.put(rec.getDate(), rec);
		}
		if (record != null && !record.getDate().equals(rec.getDate())) {
			// The day actually turned over while this client was watching. That wall-clock time is
			// the one worth reporting: it is when the reset was *observed*, not when the calendar
			// says it should have happened, and a gap between the two is the bug users report.
			lastRolloverAt = ZonedDateTime.now(zone());
			lastRolloverFrom = record.getDate();
			log.info("Play Time Tracker: day rolled over {} -> {} at {}",
					lastRolloverFrom, rec.getDate(), lastRolloverAt.toLocalTime());
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
		// A file from the original Play Time plugin carries OLD but neither PREINSTALL nor
		// GAMETOTAL. With no PREINSTALL to subtract, the entire pre-plugin lifetime is reported as
		// off-client time. Seed it from OLD: at the moment of migration, none of that baseline had
		// accrued since this plugin started tracking. Without an Account Summary read to capture it
		// later — which never happens if "Count time outside RuneLite" is off — it stays wrong.
		if (records.get(PRE_INSTALL_KEY) == null && records.get(EXTERNAL_TIME_KEY) != null) {
			setSpecialRecord(PRE_INSTALL_KEY, recordTicks(EXTERNAL_TIME_KEY));
			log.debug("Play Time Tracker: {} had OLD without PREINSTALL (migrated file); seeded PREINSTALL={}",
					debugName(player), recordTicks(PRE_INSTALL_KEY));
		}
		currentPlayer = player;
		loadedData = true;
		log.debug("Play Time Tracker: loadDataFor({}) read {} records, tracked={}, keys={}",
				debugName(player), recs.size(), getTrackedTicks(), records.keySet());
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
		// The game's Time Played covers logging in and loading; GameTick does not fire during
		// either, so that time has to be measured on the wall clock instead of counted. Without
		// it the plugin runs ~17s short per session, and that shortfall surfaces as phantom
		// off-client time once a few sessions have accumulated past a minute.
		if (state == GameState.LOGGING_IN || state == GameState.LOADING
				|| state == GameState.HOPPING)
		{
			if (gapStartMs == 0)
			{
				gapStartMs = System.currentTimeMillis();
			}
		}
		else if (state != GameState.LOGGED_IN)
		{
			// LOGIN_SCREEN, CONNECTION_LOST and friends: not in game, and the server may have
			// ended the session. Discard rather than guess.
			gapStartMs = 0;
		}

		if (state == GameState.LOGGED_IN)
		{
			if (gapStartMs > 0)
			{
				final long elapsed = (System.currentTimeMillis() - gapStartMs) / 600L;
				gapStartMs = 0;
				if (elapsed > 0)
				{
					// Credited on the next tick: records are not loaded yet at this point.
					pendingGapTicks += Math.min(elapsed, MAX_GAP_TICKS);
				}
			}
			// Try reading total play time shortly after login (the varc is populated for the
			// in-game play-time reminder), so we can sync without opening the summary panel.
			pendingSummaryRead = true;
			summaryReadTries = LOGIN_READ_TRIES;
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			// Persist the current character, then clear state so the next login loads
			// the correct character's data instead of mixing characters together.
			log.debug("Play Time Tracker: LOGIN_SCREEN clearing all state for {} (today={}, tracked={})",
					debugName(currentPlayer),
					record == null ? "<none>" : record.getDate() + "=" + record.getTime(),
					getTrackedTicks());
			saveData();
			sessionTicks = 0;
			loadedData = false;
			// Drop any uncredited login/loading gap: we never reached the game, so it was not play.
			pendingGapTicks = 0;
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
		if (DEBUG_COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			printDayBoundaryDebug();
			return;
		}
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

	/**
	 * Says it in chat as well as the log. Most users will never open client.log, so an anomaly that
	 * only ever appears there is invisible to exactly the people who can report it. Throttled hard:
	 * this runs off a per-tick check, and a stuck condition would otherwise spam every 600ms.
	 */
	private void notifyDrop(long from, long to)
	{
		final long now = System.currentTimeMillis();
		if (now - lastDropMessageMs < DROP_MESSAGE_COOLDOWN_MS)
		{
			return;
		}
		lastDropMessageMs = now;
		addGameMessage("Play Time Tracker: today's tracked time went backwards ("
				+ formatTicks(from) + " -> " + formatTicks(to)
				+ "). This is a bug - please report it with your client log.");
	}

	/**
	 * Answers "why did my day reset then?" in chat, without a log file.
	 *
	 * <p>The plugin's day comes from the JVM's zone, and that is not always the zone on the
	 * taskbar: Windows maps its registry zone through the JRE's bundled tzdb, and when that
	 * mapping fails Java quietly falls back to a fixed offset with no DST rules. The clock stays
	 * right and the date boundary moves, usually by an hour. Printing the JVM's idea of the wall
	 * clock next to the next rollover makes that visible in one line.
	 */
	private void printDayBoundaryDebug()
	{
		for (String line : dayBoundaryLines())
		{
			addGameMessage(line);
		}

		final ZoneId z = zone();
		final ZonedDateTime now = ZonedDateTime.now(z);
		log.info("Play Time Tracker: debug zone={} offset={} now={} today={} dayBeganAt={} "
						+ "rollsOverAt={} weekStartsOn={} lastSeenRollover={} from={}",
				z, now.getOffset(), now.toLocalTime(), now.toLocalDate(),
				now.toLocalDate().atStartOfDay(z), now.toLocalDate().plusDays(1).atStartOfDay(z),
				firstDayOfWeek(), lastRolloverAt, lastRolloverFrom);
	}

	/** The {@code ::playtimedebug} text, separated from sending it so it can be checked offline. */
	List<String> dayBoundaryLines()
	{
		final ZoneId z = zone();
		final ZonedDateTime now = ZonedDateTime.now(z);
		final ZonedDateTime dayStart = now.toLocalDate().atStartOfDay(z);
		final ZonedDateTime rollover = now.toLocalDate().plusDays(1).atStartOfDay(z);
		final long sinceStart = ChronoUnit.MINUTES.between(dayStart, now);
		final long minutesLeft = ChronoUnit.MINUTES.between(now, rollover);
		final LocalDate weekStart = now.toLocalDate()
				.with(TemporalAdjusters.previousOrSame(firstDayOfWeek()));

		final List<String> lines = new ArrayList<>();
		lines.add("Play Time Tracker: clock reads "
				+ now.toLocalTime().truncatedTo(ChronoUnit.SECONDS)
				+ " in " + z + " (UTC" + now.getOffset() + ").");
		lines.add("Today is " + now.toLocalDate() + ": began at " + dayStart.toLocalTime()
				+ " (" + hm(sinceStart) + " ago), next reset at "
				+ rollover.toLocalTime() + " in " + hm(minutesLeft) + ".");

		if (lastRolloverAt == null)
		{
			lines.add("This client has not seen a day reset yet - it has not been running across "
					+ "one. Today's record was loaded, not rolled.");
		}
		else
		{
			lines.add("Last reset actually seen: " + lastRolloverFrom + " -> "
					+ lastRolloverAt.toLocalDate() + " at " + lastRolloverAt.toLocalTime()
					+ " (" + hm(ChronoUnit.MINUTES.between(lastRolloverAt, now)) + " ago). "
					+ "That should read " + dayStart.toLocalTime() + " - if it does not, that is the bug.");
		}
		lines.add("Week starts " + firstDayOfWeek() + ", so this week runs from " + weekStart + ".");
		lines.add("If that clock time does not match your own, the day will reset at the wrong "
				+ "hour - report it with these lines.");
		return lines;
	}

	private static String hm(long minutes)
	{
		return (minutes / 60) + "h " + (minutes % 60) + "m";
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
