package com.playtimetracker;

import java.time.DayOfWeek;
import java.util.Locale;
import java.time.temporal.WeekFields;

/**
 * Which day "this week" starts on. The locale default is US-centric for some users and
 * ISO (Monday) for others, so it is worth being able to pin it rather than inherit it.
 */
public enum WeekStart
{
	LOCALE("Follow system locale", null),
	MONDAY("Monday", DayOfWeek.MONDAY),
	SUNDAY("Sunday", DayOfWeek.SUNDAY);

	private final String name;
	private final DayOfWeek day;

	WeekStart(String name, DayOfWeek day)
	{
		this.name = name;
		this.day = day;
	}

	/** The first day of the week, resolving LOCALE against the running JVM's locale. */
	public DayOfWeek resolve()
	{
		return day != null ? day : WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
	}

	@Override
	public String toString()
	{
		return name;
	}
}
