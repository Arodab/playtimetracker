# Play Time Tracker

Tracks your Old School RuneScape in-game play time **per character** — by session, day,
week, month, year, and all-time — and can account for time played away from RuneLite
(mobile or other clients).

## Features

- **Per-character tracking.** Time is stored per character (by display name), so alts don't
  mix together.
- **Time periods:** Session, Today, This week, This month, This year, Total tracked, and
  Total (in-game), shown in the side panel.
- **Daily averages** (optional): average play time per day for the week, month, year, and
  since you started tracking.
- **External / mobile time.** The plugin reads your account's true "Time Played" from the
  in-game **Account Summary** panel and reports the gap over RuneLite-tracked time as
  external/mobile time — capturing play on mobile, other clients, or while the plugin was
  off. It syncs at login (the game exposes this value in whole minutes).
    - **Total tracked** = time recorded in RuneLite.
    - **External/mobile** = off-client time accrued since you installed the plugin.
    - **Total (in-game)** = your account's real total (earlier history + external + tracked).
- **Daily CSV export.** The "Export daily CSV" button saves one row per day
  (`date,hours,minutes,seconds`, ISO dates) — ready to group by week/month or plot.
- **On-screen overlay.** Optionally show any of the stats individually as a movable overlay
  (see the "On-screen overlay" config section).
- **Reset command.** Type `::resetplaytime confirm` in chat to wipe the current character's
  tracked data (for example, when reusing a save from another account).

## Usage

Install from the RuneLite Plugin Hub and tracking begins automatically. Data is stored
locally on this device (`.runelite/playTime/<character>.log`), so play time is not synced
across devices — instead, external play (including mobile) is reconciled from the in-game
Account Summary total at each login.

## Configuration

- **Show Averages** — display the per-day averages.
- **Show Seconds** — show seconds in addition to days/hours/minutes (turn off to save a little
  performance).
- **Count time outside RuneLite** — use the Account Summary "Time Played" to capture
  mobile / off-client time (on by default).
- **On-screen overlay** — enable the overlay and tick which stats to show, each individually.

## Credits

Based on the original [Play Time](https://github.com/andham97/play-time) plugin by
**andham97**, whose code this is built on. Licensed under BSD-2-Clause — see [LICENSE](LICENSE).
