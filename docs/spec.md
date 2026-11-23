# xrncal for Android: phase 1 spec

A native calendar for the phone (Kotlin, Jetpack Compose, minimum Android 12). It is the phone
side of xrncal, the laptop calendar (`xrncal-desktop`, Electron), and like it is made to look and
work like **One Calendar** by Code Spark, here its Android phone app. It syncs over CalDAV, the
same way the laptop app does, so both show the same calendars from the same server, and adds the
Vietnamese lunar date to every day.

Phase 1 is an app to use every day instead of One Calendar on the phone. Widgets and the rest
come after (see "Phase 2").

## Phase 1: in and out

In:

- One CalDAV account (any server; tested against Radicale 3.8.1) and one local calendar on the
  phone that never leaves it.
- Month (the default), List, Week and Day views; swipe between periods; Today; the drawer with
  views, calendars (colour, on/off) and holiday calendars; search.
- The Vietnamese lunar date on every month cell, in each day header and in the event detail.
- Event detail; create, edit and delete; all-day; start and end with a time zone; calendar;
  repeat (the common rules and custom ones); reminders; location; notes. One date of a series or
  the whole series.
- Reminders as notifications at their minute (exact alarms), set again after every sync, every
  change and a reboot; tapping one opens the event.
- Sync on open, after every change, and every 30 minutes in the background; offline changes kept
  and sent later; conflicts shown, never overwritten.
- Settings: the account, week start, default view, default reminders, default event length,
  12/24-hour clock (following the phone by default), week numbers, lunar dates. The theme follows
  the phone (light or dark).

Out of phase 1 (phase 2 or later):

- Home-screen widgets (One Calendar has month, agenda and date widgets).
- Year view (One Calendar has one: 12 mini-months, 3 across).
- One Calendar's colour filter button; here the drawer's calendar switches do that job.
- Google and Microsoft accounts (the owner has none on the phone), invitations and guests.
- Editing the lunar-anniversary rule: the laptop app writes each year's date as an ordinary
  event, which the phone shows like any other.
- "This and following" edits of a series (phase 1 has this date and all dates).
- Moving an event by dragging, pinch zoom of the time grid, a jump-to-date picker.
- Changing a calendar's colour or name, or making calendars on the server.
- Import and export of .ics files, sharing an event.

## The One Calendar phone layouts being copied

From its public Play listing screenshots (2021, Android phone and tablet), its website and help
pages, its feature-request board and Play reviews. Its name, logo and artwork stay out.

**Top bar.** Flat, the same colour as the weekday row (light grey in light, dark grey in dark): the
menu button, the period's name left-aligned in large regular type ("September"; the year is added
here when it isn't this year), then on the right `+` (new event) and the Today button, a calendar
glyph with today's day number in it. One Calendar's third icon, a colour filter, is left for
phase 2. There is no floating add button and no bottom bar.

**Drawer** (from the left, by the menu button or a swipe from the edge): the views List, Month,
Week, Day (the selected one highlighted in a light accent tint), then Search and Sync, then
Settings. One Calendar's drawer holds no calendar list (its colour filter does that); the brief
for this app asks for one, so below the views come the calendars, each with its colour and a
switch, grouped by account (the server's name, then "Phone"), and the holiday calendars.

**Month view** (the default).

- A narrow column of ISO week numbers on the left (can be turned off), the weekday row above
  ("Mon Tue …", short names, left-aligned in their columns).
- Rows share the height equally; thin horizontal lines between weeks, one vertical line after the
  week numbers, none between days.
- The day number at the top-left of each cell; days of other months in light grey.
- **Today**: the number bold and red, the cell a faint tint.
- **The lunar date** (this app's addition, as on the laptop): small, at the top-right of each
  cell. `1/8` on the first of a lunar month, `15/8` at the full moon, otherwise the day.
- **Events** as chips filled with the calendar's colour, title only (no time on the phone), each
  two lines tall, full cell width, with small corners. Events spanning days (and all-day events
  over several days) are one continuous one-line bar across the days, on the top lines of the
  row, continued on the next row past the end of the week.
- A cell with more than fits shows `+N` beside its day number.
- **Tapping a day** opens a pop-up over the month listing that day's events (with the day's date
  and lunar date); tapping an event there opens it; `+` in it starts an event on that day.
- Swipe left and right between months (no vertical scroll).

**List view.** A compact month pinned at the top (week numbers, one-letter weekdays, the dates,
and above each date thin bars in its events' colours; multi-day events as one bar across the
dates; today red), and below it one continuous list of the days that have events: a day header
("24 FRIDAY", today's in red, with the lunar date), a month header ("OCTOBER") when the month
changes, and each event as a full-width card filled with its calendar's colour: title, then
"16:00 – 17:00" and the location. A day's events are one block with thin dividers. Empty days are
left out. The list scrolls on into the following months; tapping a date in the pinned month jumps
the list to it.

**Week view.** One Calendar's phone app has week styles not shown in any public screenshot; its
Windows app and reviews show a seven-day time grid with the all-day row on top and the hours on
the left. That is what this app draws: seven columns from the chosen first day of the week, a
header with each day's weekday, number and lunar date, the all-day row, and the hour grid.
One Calendar has no 3-day view, so neither has this app.

**Day view.** One day's time grid: hour labels on the left, the all-day row on top, events side
by side where they overlap, a line at the current time. Swipe left and right between days.

**Event detail.** One Calendar opens its editor straight from an event; the brief asks for a
detail view, so this app shows one first: an app bar in the calendar's colour with back, edit and
a menu (delete), then the title, the date and time (with its zone when it isn't the phone's),
the lunar date, the repeat rule, the reminders, the calendar, the location and the notes.

**Editor.** Full screen; the app bar takes the chosen calendar's colour; ✕ at the left, "Add" or
"Edit", ✓ at the right. Then, in this order:

1. Subject.
2. Calendar (its colour and name; read-only calendars aren't offered).
3. All day (a switch).
4. Start: date, time, and a globe button for the time zone.
5. End: the same.
6. Repeat (a switch; on, it opens the choices: every day, weekdays, every week, every month,
   every year, or custom: every N days/weeks/months/years, on which weekdays, monthly on the
   date or on the nth weekday, ending never, on a date or after N times).
7. Location.
8. Reminders (One Calendar allows one; this app keeps a list, as the laptop app does).
9. Notes.

A new event starts at the next whole hour, as long as Settings says (an hour unless changed),
in the default calendar, with the default reminder. One Calendar's PEOPLE tab (guests) is out.
Editing or deleting a date of a repeating event asks "This event" or "All events" first, so the
editor opens on that date's fields or the series'. One date of a series stays in its calendar.

**Look.** One Calendar's default light theme (#F5F5F5 bars, white page) and dark theme (#202125
page, #36373B bars); calendar colours drawn as solid fills with white text (dark text on a light
colour). On top of that, the xrn family's: MiSans VF for every text, xrncal's tile as the app
icon, its blue (#308FFE) as the accent, light and dark following the phone. Figures and short
labels only: no hints, explanations, empty-state sentences or legends.

## Data model

Everything is kept in Room on the phone; the server is a copy of the calendars, not the store
the screens read.

- **calendars**: id (the collection URL for CalDAV, `local:<uuid>` for the phone's own), account
  (`caldav` or `local`), name, colour (the server's `calendar-color`, else one from the palette),
  read-only (no `write` in `current-user-privilege-set`), shown (the drawer switch), sync state
  (ctag, sync-token), the last error.
- **resources**: one per calendar object resource: its local id, calendar, href and ETag on the
  server, **its iCalendar text as the server sent it (or as edited here)**, and columns worked out
  from it for finding it: UID, title, the span its dates can fall in (open-ended for an endless
  series), whether it repeats, its folded words for search. Sync state: dirty (to send), deleted
  (to delete on the server), a problem (conflict, gone from the server, refused) with the server's
  copy for a conflict, a version that counts local changes.

Keeping the text whole is what lets an edit here change only what it means to: the app parses
the resource, changes the properties it models, and writes it back with everything else (guests,
categories, alarms it can't show, X- properties of other programs) as it was.

The screens ask for occurrences between two days: the resources of the shown calendars whose span
meets the range are parsed (and kept parsed while they don't change), and expanded in the phone's
zone. The holiday calendars are worked out on the phone (`domain/Holidays.kt`) and never stored.

## iCalendar

`domain/ical`: a parser and writer for the whole of RFC 5545's syntax (line folding at 75 octets,
parameters, TEXT escapes), keeping every component and property it reads. On top of it:

- DTSTART/DTEND/DURATION as dates, UTC times, zoned times (TZID) and floating times. A TZID is read
  from the zone database when it is an IANA name, a Windows name ("W. Europe Standard Time") or an
  IANA name with a prefix; else from the file's own VTIMEZONE; else in the phone's zone.
- Events are written in their own zone with a VTIMEZONE worked out from the zone database (the
  laptop app's rule: today's yearly rule as an RRULE, earlier changes listed).
- RRULE, RDATE, EXDATE; overrides by RECURRENCE-ID (moved or cancelled dates); an override whose
  series isn't in the resource shows on its own.
- VALARM: alarms counted back from the start are reminders. No VALARM at all means the defaults
  apply (as on the laptop); an event whose reminders were all removed is written with the "none"
  marker other calendars use (an `ACTION:NONE` alarm at a fixed time in 1976), which the laptop
  app also reads as none. Alarms this app can't show are kept.

**Recurrence** uses `org.dmfs:lib-recur` 0.17.1 (Apache-2.0): maintained, pure Java with no
Android dependence, the whole of RFC 5545's RRULE (BYSETPOS, BYWEEKNO, BYYEARDAY…), and a
fast-forward that jumps to the range without walking every date before it. The laptop app's
bounded-rrule.ts limits are kept on top of it: rules more often than daily, with more than 24
times a day, or longer than 512 characters are not expanded (the first date shows alone); the
walk is metered per series and per calendar, a calendar yields at most 50,000 dates per query,
and no date is looked for more than a year before the range. Series are expanded on wall-clock
time and each date turned into an instant in the event's zone, so a weekly 09:00 stays at 09:00
across summer time. lib-recur always counts DTSTART as the first date, as RFC 5545 says (the
laptop's rrule.js leaves it out when the rule wouldn't produce it).

## Sync (CalDAV)

All server work runs in `SyncEngine`, which switches to an injected IO dispatcher whoever calls
it, under one mutex.

1. **Discovery** (Settings, signing in): the address as given; if no calendars are there,
   `current-user-principal` from it, or from `/.well-known/caldav`; then `calendar-home-set`.
   The calendar home is stored.
2. **Calendars**: one PROPFIND (Depth 1) on the home: display name, Apple `calendar-color`,
   `getctag`, privileges, resource type and supported components. Collections that hold events
   are calendars; new ones show, gone ones are removed with their events.
3. **Push** each calendar's changes first: a new resource is PUT to `<calendar>/<name>.ics` with
   `If-None-Match: *`, a changed one with `If-Match: <etag>`, a deleted one is DELETEd with
   `If-Match`. A 412 marks the resource as a conflict: it is not sent again until the owner
   picks "Keep mine" (sent again without the condition) or "Keep theirs" (the server's copy
   taken); the event shows a mark and Settings lists every conflict. A refusal (400, 403, 415…)
   is kept the same way with the server's answer. A move to another calendar is a PUT there and
   then a DELETE of the old resource.
4. **Pull** a calendar only when its ctag changed or this sync sent something: a
   `sync-collection` REPORT with the stored sync-token gives the changed and removed hrefs
   (an empty token the first time); a server without it, or one that refuses the token, is
   compared by listing every resource's ETag instead. Changed resources are fetched with
   `calendar-multiget` (100 at a time). A resource changed here and not yet sent is never
   overwritten by a pull, and one deleted on the server while changed here becomes a conflict.
   A listing that isn't a multistatus removes nothing. (The laptop app found that asking Radicale
   for the sync-token on every PROPFIND costs seconds on a large calendar, so the token is read
   only from the REPORT, and only when the ctag says something changed.)
5. **Names**: a resource is named after its UID where a server takes that as a path; a UID with
   `: ' " * ? , / \ < > | # %`, a control character, a leading dot or more than 180 bytes (which
   Radicale refuses) is named after its SHA-256 instead, as on the laptop.
6. **Read-only** calendars can't be chosen in the editor, and their events have no edit or delete.
7. **When**: on opening the app, two seconds after a change, every 30 minutes in the background
   (WorkManager, with a network), and once after a reboot. Android refusing the app the network
   in the background is waited out, not shown as the server being down.
8. **The login** (address, user name, password) lives in Settings; the password is sealed with
   AES-GCM under an Android Keystore key, never in plain preferences. Plain `http://` is allowed
   only to the machine itself and an emulator's host, in debug builds.

## Reminders

After every sync, every change, a start of the app, a reboot, an update and a change of time or
zone, the phone works out the next reminders (occurrences of the shown calendars from a day ago to
eight days ahead; an event's own VALARMs, or the defaults) and sets one exact alarm for the
soonest. When it goes off, every reminder due then rings, each once (the laptop's rule: one stays
due until its event starts, and of several overdue only the latest rings), and the next alarm is
set. A notification shows the title, the time and the place; tapping it opens the event.
Permissions: `USE_EXACT_ALARM` (Android 13+), `SCHEDULE_EXACT_ALARM` (Android 12),
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`; `ManifestTest` checks each.

## Settings

- Account: server address, user name, password; Connect; the calendars it found and the last
  sync; Sync now; Sign out (removes the account's calendars from the phone).
- Conflicts and refused changes, each with Keep mine and Keep theirs.
- Week starts on Monday, Sunday or Saturday (the phone's by default).
- Default view: Month, List, Week or Day.
- Default reminder for timed events (10 minutes) and for all-day events (none).
- New event length (15 to 120 minutes, an hour by default).
- Clock: as the phone, 12-hour or 24-hour.
- Week numbers; lunar dates.
- Default calendar for new events.

## Tests

- `domain/`: JVM tests with the laptop app's vectors: lunar dates and anniversaries, holidays,
  all-day ends, reminders, event length, clock minutes, VTIMEZONE (read back every 13 hours over
  the span for 17 zones), bounded recurrence (the same dates as lib-recur's own walk for 37 rules,
  and the cost limits), expansion across summer time, UNTIL in zones, Windows zone names; and the
  iCalendar round trip, edits, repeat rules, the month grid, the list and the time grid.
- `app/`: the sync engine against a real **Radicale 3.8.1** started on a free port with a
  throwaway folder and a made-up user (skipped when it isn't installed; `XRNCAL_RADICALE` names
  its executable): discovery, the first sync, incremental sync, an offline edit sent later, a
  412 conflict, a series with an override, deletes both ways. `ManifestTest`. Reminder planning.
- CI (GitHub Actions) installs Radicale 3.8.1 with pip and runs every test and a debug build.

## Phase 2

Widgets (month and agenda); Year view; the colour filter; "this and following"; calendar colours
and names; a jump-to-date picker; .ics import, export and share; Google and Microsoft accounts.
