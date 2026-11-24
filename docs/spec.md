# xrncal for Android: spec

A native calendar for the phone (Kotlin, Jetpack Compose, minimum Android 12). It is the phone
side of xrncal, the laptop calendar (`xrncal-desktop`, Electron), and like it is made to look and
work like **One Calendar** by Code Spark, here its Android phone app. It syncs over CalDAV, the
same way the laptop app does, so both show the same calendars from the same server, and adds the
Vietnamese lunar date to every day.

Phase 1 (0.1.0) made an app to use every day instead of One Calendar on the phone. Phase 2 ("Phase
2: close the gap" below) brings it close to both One Calendar's phone app and the owner's laptop
xrncal: their look (a wallpaper under see-through views), One Calendar's top bar, filter, year
view and editor, the laptop's colours, second time zone, emoji and typed dates, widgets, and
reminders the way One Calendar has them. Where phase 2 changes a phase 1 rule, the phase 2
section says so and wins.

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
icon, light and dark following the phone, and (since xrncal's touches, below) the laptop
xrncal's accent and today red. Figures and short
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
- VALARM: alarms counted back from the start are reminders. An event with none never rings
  (phase 2; phase 1 applied the defaults). The "none" marker other calendars write (an
  `ACTION:NONE` alarm at a fixed time in 1976) is read as none and no longer written. Alarms this
  app can't show are kept.
- COLOR (RFC 7986): an event's own colour, a hex value (phase 2).

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
eight days ahead; an event's own VALARMs only: one without any never rings) and sets one exact
alarm for the soonest. When it goes off, every reminder due then rings, each once (the laptop's rule: one stays
due until its event starts, and of several overdue only the latest rings), and the next alarm is
set. A notification shows the title, the time and the place; tapping it opens the event, and
its Open and Snooze actions do what they say (phase 2).
Permissions: `USE_EXACT_ALARM` (Android 13+), `SCHEDULE_EXACT_ALARM` (Android 12),
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`; `ManifestTest` checks each.

## Settings (phase 1; phase 2 splits them into One Calendar's Options pages, item 11)

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

## Phase 2: close the gap to One Calendar and the laptop

The owner used 0.1.0 beside One Calendar on the same data and beside xrncal on the laptop, and
found it "not really close enough to both". Phase 2 closes those gaps, in the order the owner
meets them. One Calendar's phone app (seen on the owner's phone, its public listing and help
pages) sets the layout; the laptop app sets the extras (wallpapers, second zone, emoji, typed
dates, suggestions, event colours). Its name, logo and artwork stay out.

### Defaults: the owner's laptop and One Calendar

A new install starts as the owner has both set up: dark theme, the "Cyber City" wallpaper with
overlay darkness 0.15 and blur 2; 24-hour clock; week from Monday; week numbers and lunar dates
on; second time zone Asia/Ho_Chi_Minh (also first in the editor's zone list); time picker in
15-minute steps; opens on Week; day starts at 08:00; hour size Medium; month day pop-up on; new
events an hour long; reminders 15 minutes (timed) and 12 hours (all-day). An update from 0.1.0
keeps every choice the owner made, except that the two reminder defaults still at 0.1.0's own
values (10 minutes, none) move to One Calendar's (15 minutes, 12 hours): 0.1.0 saved every
setting whenever one changed, so the stored values can't say whether they were chosen.

### 1. Wallpaper and translucent chrome

A picture behind every view, as on the laptop and in One Calendar's themes: the picture (cropped
to fill, blurred on Android 12 and later), a black overlay at the chosen darkness, then the views
drawn over it with see-through backgrounds: the top bar and the weekday row darker, the grid
lines faint, the chips solid. The laptop's four presets ship with the app (Misty Forest, Purple
Sunset, Cyber City, Cosmic Night: Unsplash photos under the Unsplash License, as the laptop notes
them), plus "No image" (the plain One Calendar themes of phase 1) and **Import from the gallery**
through Android's photo picker, which needs no permission: the picture is scaled down to 1600 px
and kept in the app's own files. Settings, Theme: wallpaper, overlay darkness (0 to 0.8), blur
(0 to 20), app theme (System, Light, Dark; Dark by default) and the signal colour (today's date
and the time line; One Calendar's red by default until xrncal's touches made it the laptop's).

### 2. Colours

- **Calendar colours** are One Calendar's palette, the Google-style colours (Tomato #D50000,
  Flamingo, Tangerine #F4511E, Banana #F6BF26, Sage, Basil #0B8043, Peacock #039BE5, Blueberry
  #3F51B5, Lavender, Grape #8E24AA, Graphite #616161, and Google's calendar colours such as Cocoa
  #795548, Mango #F09300 and Pistachio #7CB342). Options, Calendar colors lists every calendar as
  a bar in its colour with a picker, as One Calendar does.
- **A colour picked for a server calendar is written to the server**: a CalDAV `PROPPATCH` of
  Apple's `calendar-color` (`#RRGGBBFF`, as Apple and DAVx⁵ write it) on the collection, sent at
  the start of the next sync. Until it goes through the calendar keeps the new colour here and is
  marked; the server's colour then wins again at every sync, so the laptop (which reads the same
  property) and the phone agree. A server that refuses it leaves the colour here and the reason on
  the calendar. The phone's own calendar and the holiday calendars change here only.
- **Per-event colour** (One Calendar and the laptop both have it): the colour square beside the
  editor's calendar row; "Calendar colour" or one of the palette. Written as RFC 7986 `COLOR`
  with a lower-case `#rrggbb`, as the laptop writes it; read back from any hex value (a CSS name
  another program wrote is kept and ignored).

### Reminders (owner's decision: like One Calendar)

- **An event with no VALARM of its own never rings.** Phase 1's "no VALARM means the default"
  is gone, on the phone and (by the owner's same decision) on the laptop.
- The defaults (timed 15 minutes, all-day 12 hours, in Options, Reminders) only fill in a **new**
  event's reminder, which is then saved as a real VALARM the laptop sees.
- An event whose reminders are all removed is written with no VALARM. The `ACTION:NONE` marker
  of phase 1 no longer means anything to either app and is no longer written; it is still read as
  "none", and a save that leaves an event with no reminders drops it.
- After the update the alarm 0.1.0 set for an event with no VALARM is cancelled: the update
  broadcast, the app's start and every sync work the alarm out again under the new rule.
- The editor offers One Calendar's list: None, at the start, 5, 10, 15, 30 and 45 minutes, 1, 2,
  3, 4, 6, 12 and 18 hours, 1 and 2 days, 1 week. Several reminders stay allowed, as on the laptop
  ("+" adds one).

### 3. Month view like One Calendar's

- Each cell holds as many chips as fit its height (five on the owner's phone), and **"+N" beside
  the day number** counts the ones left out ("26+1"). No line is kept back for it any more.
- A chip is **two lines of small text tall**, as One Calendar's: a long title wraps onto its
  second line instead of being cut to a few letters ("Quarterly / review" rather than 0.2.0's
  "Quarterl"). The day number is smaller (13 sp, a 16 dp row) to leave room for five.
- The lunar day stays at the right of the number (xrncal's own); today's number is in the signal
  colour and its cell tinted (white on a circle of it since xrncal's touches, below).
- A chip shows the title only, as One Calendar's month does. Options, Views can lead timed chips
  with their start time, as the laptop's wider month does ("10:30 Dentist"); off by default,
  because on a phone the time left 3 or 4 letters of the title.
- Tapping a day opens One Calendar's **day pop-up** (item 7) when "Month day pop-up" is on (the
  default), else that day's Day view.

### 4. Week and day views like One Calendar's and the laptop's

- **The day starts at 08:00**: the grid opens scrolled to the "Start time of day" (Options,
  Views), placed again while the layout settles until the owner scrolls. Below midnight the grid
  keeps a blank as tall as needed for the day's start to reach the top, as One Calendar does: in
  0.2.0 and 0.2.1 the day's start to midnight was shorter than the screen, the grid could not
  scroll that far, and it opened with midnight at the bottom and about 05:00 at the top.
- **Hour size** Small (30 dp), Medium (45 dp, the default: One Calendar's hour on the owner's
  phone, so 08:00 to midnight about fills the screen as it does there) or Large (56 dp). Pinch zoom of the grid is left out:
  it fights the pager's swipe, and the setting does the job.
- **The gutter writes the hour as a number only** (the owner's choice): `08`, `09` … `23`, or
  One Calendar's `8a` on the 12-hour clock.
- **The second time zone** (laptop): under each hour in the gutter, that hour in the second zone,
  smaller, when it differs from the phone's, written the same way (`04`); a zone off the whole
  hour keeps its minutes (`02:30`).
- Event blocks: the title, then the time range (when the block has room).
- Week header: One Calendar's "Mo 23" with the lunar day under it; today's in the signal colour
  (the laptop's stacked header since xrncal's touches, below).

### 5. Top bar like One Calendar's

- Title and subtitle: Month "November"; Week "November" over "week 48"; Day "Tuesday 24" over
  "November"; List "November"; Year "2026" (the year is added to a month that isn't this year's).
  **Tapping the subtitle zooms out** to the month.
- Buttons: **+** (a menu with "Add appointment"), **Filter** (a pop-up of every calendar with a
  checkbox in its colour, grouped by account, the holiday calendars last, as One Calendar's
  filter), and **Today** (today's date in a calendar glyph).
- The drawer follows One Calendar's: List, Month, Week, Day, **Year**; Search; Sync; Accounts,
  Options. The calendar switches of phase 1 move to the filter.

### 6. Year view

Twelve mini months, three across (One Calendar and the laptop). Each day with events is tinted
in the colour of the calendar with the most events that day (a tie goes to the one whose event
comes first), with the lunar day small under the date; today in the signal colour. Tapping a day
opens its Day view. Swipe between years.

### 7. Day pop-up

As One Calendar's: the day as a red title ("TUESDAY 24", the lunar date beside it), **Add
appointment**, **Day view**, then the day's events as cards in their colours with their times.
Tapping an event opens it.

### 8. Tapping an event opens the editor straight away

As in One Calendar and on the laptop; the detail page of phase 1 is gone.

- The editor's bar: ✕, "Edit", ⋮ (**Copy**: a new event with the same fields; **Share**: the
  event as an `.ics` file through Android's share sheet), **Delete**, ✓.
- **Delete** closes the editor and shows "Deleted" with **Undo** for a few seconds, as the laptop
  does; Undo puts the event back as it was (made again on the server if the delete had already
  gone there).
- A date of a repeating event opens with that date's fields; **"This event" or "All events" is
  asked on ✓ or Delete**, when there is something to save, not before the editor opens.
- Events that can't be changed (a read-only calendar, a holiday) open the same editor with its
  fields locked and no ✓ or Delete. An event that couldn't be sent shows why at the top, with
  Keep mine and Keep theirs.
- The lunar date shows under the start date.

### 9. Editor like One Calendar's, with the laptop's extras

In One Calendar's order:

1. **Subject**, with an **emoji button** in front: the laptop's panel, the emojis picked most
   first, then its built-in set by kind; the pick replaces the emoji the title starts with.
   **Title suggestions** from the events already there (the laptop's ranking) show under the
   field as it is typed.
2. **Natural-language entry** for a new event (the laptop's `natural-entry.ts`, ported with its
   test vectors): a date, time, length or repeat typed into the title ("Lunch with Sam tomorrow at
   12:30", "Họp 3h chiều thứ 6") fills those fields, and "Saved as …" shows the shorter title it
   will save.
3. **Calendar**, with its account under it, and the **event colour** square.
4. **All day**; **Start** and **End** (date, time, and the time-zone globe; the time list is in
   the 15-minute steps of Options; the zone list leads with the preferred zones and the phone's).
5. **Repeat**, One Calendar's compact form: "every [N] [days / weeks / months / years]", "on [M T
   W T F S S]" for weeks (monthly: on the date or on the nth weekday), "ends [never / on date /
   after N times]". A rule this form can't show is kept as it is ("Custom").
6. **Location**, with **suggestions from places used before** (the laptop's ranking).
7. **Reminder**, One Calendar's list (above), several allowed.
8. **Description**.

### 10. Search

One Calendar's **Future** and **Past** tabs: Future lists each matching event at its next date,
soonest first; Past at its last date before now, latest first.

### 11. Options

One Calendar's Options list, each a page:

- **Views**: first day of week, time format, week numbers, startup view, month day pop-up, start
  time of day, hour size, lunar dates, second time zone, times in month chips.
- **Appointments**: default calendar, default length, suggestions (titles and places).
- **Calendar colors**: every calendar's colour (item 2).
- **Theme**: item 1.
- **Reminders**: the defaults for timed and all-day events; notifications: hide details on the
  lock screen, the snooze length, and Android's notification settings for the app.
- **Synchronisation**: history (how far back events are shown and searched: 1, 2, 3 (the
  default, as One Calendar), 5 years or all; the phone still keeps the older ones' text, so the
  sync needs no new rules), and the automatic interval (15 minutes to 6 hours; 30 minutes by
  default, as in phase 1).
- **Time zone**: use the phone's (the default), or a chosen zone the app shows everything in.
- **Widget options**: item 12.
- **Emoji options**: on or off, and the favourites (the most used, as the laptop counts them;
  cleared here).

**Accounts** (the drawer, as One Calendar's Accounts): the CalDAV account (address, user,
password, Connect, Sync now, Sign out), the last sync, and the events that couldn't be sent, each
with Keep mine and Keep theirs.

### 12. Home-screen widgets

The owner keeps One Calendar's Agenda widget on the home screen. RemoteViews, as the other xrn
apps' widgets (no Glance: one less library, and the list uses Android 12's
`RemoteCollectionItems`, so no service is needed):

- **Agenda**: the next 14 days that have events, each under a "Tuesday 24 November" header, each
  event as a line with its colour, time and title. Tapping an event opens it in the app; tapping
  a header or the empty part opens the app.
- **Month**: this month as a grid with each day's events as coloured bars, drawn as a picture of
  the widget's size; tapping opens the app on the month.
- **Date icon**: today's weekday and date in a calendar glyph; tapping opens the app.
- Widget options: theme (dark or light), text colour, background colour and opacity, and which
  calendars show. Widgets are drawn again after every sync and change, and at midnight.

### 13. Notifications

A reminder's notification has **Open** and **Snooze** (5, 10, 15, 30 minutes or an hour, as the
laptop offers; the length is chosen in Options, Reminders, 10 minutes by default). A snoozed
reminder rings again at its new time, even after a reboot.

### 14. Accessibility

Every icon button has a content description ("Menu", "Add", "Filter", "Today", "Delete", …), so
TalkBack names it and tests can find it.

### Left out (and why)

- One Calendar's Tasks (needs a separate app), Invites, the birthday emoji and Security options.
- Guests and the PEOPLE tab: later.
- Lunar-yearly repeats typed into a title ("Giỗ ông 12/8 âm lịch"): the date is filled in, the
  yearly lunar repeat is not (the phone has no lunar repeat yet; the laptop writes each year's
  date).

### Tests added in phase 2

- Unit: the month cell's chips and "+N"; the reminder rule (no VALARM, no ring; defaults only on
  new events; the alarm 0.1.0 set for one is cancelled; snoozing); the settings migration; repeat
  form round trips to RRULE; natural-language parsing and the suggestion rankings with the
  laptop's vectors; the year view's tint; the widgets' data; the colour palette.
- Radicale: a calendar colour set by PROPPATCH read back by a second device; a per-event `COLOR`
  round trip; a new event's default reminder reaching the server as a VALARM.
- Previews of every new screen from made-up calendars, rendered to PNGs.
- `ManifestTest`: the widgets' receivers and the share provider (Snooze is an explicit broadcast to the reminder receiver, which needs no filter).

## xrncal's touches: the laptop's look over One Calendar's layout

One Calendar's layout stays (top bar, drawer, month cells, list, editor); these carry the laptop
xrncal's signature touches over it.

- **Colours.** The accent is the laptop's: #2383e2 in the light theme, the blurple #5865f2 in the
  dark, in place of One Calendar's light blue. It colours the Options bars, an editor bar with no
  calendar colour to take, switches, the selected drawer item, the `+` menu, links and buttons; an
  editor's bar in its calendar's colour stays so. The default signal colour (today) is the
  laptop's red, #eb5757 light and #f0616d dark. Settings saved before (version 2 or older) with One
  Calendar's red (#f44336), the old default, move to the new one, since every save wrote it
  whether or not it was picked; any other colour stays, and so does One Calendar's red picked
  after the update.
- **Moon markers.** Mùng 1 (the new moon) and rằm (the full moon), the lunar days people keep,
  are marked as the laptop's LunarLabel marks them, in place of the plain lunar date: a small chip
  tinted in the moon's colour (15% of it for the ground) holding a ring ○ for the new moon or a
  dot ● for the full moon, and the date with its month ("1/10", "15/10", "15/7*" in a leap month).
  The shapes differ, so the colour is not the only sign. The colours are the laptop's, never
  today's red: teal #4fd1c1 and gold #f2c14e in the dark theme, #0b7166 and #8a5a00 in the light.
  Tết is marked as a mùng 1, with the laptop's "Tết" for its text. The week header's lunar line,
  the day header (the chip, then the year's name), the month cell (the glyph and the coloured
  date without the chip's ground, which a seventh of a phone can't hold; a "+N" beside the number
  gives way to it) and the list's day headers carry them. Other days keep the plain lunar date.
- **The laptop's week header**, in place of One Calendar's "Mo 23" on one line: the weekday in
  small capitals on top ("MO"), the date big under it, the lunar date (or the moon's chip) small
  under that. Today's weekday is in the today colour and its date white on a filled circle of it;
  Saturday's and Sunday's names are in the today colour a little faded, as the laptop's. The day
  view's header is the same stack in the gutter's width, with the lunar date and the week level
  with the date beside it. The header is taller than One Calendar's line; the grid's room below
  midnight still lets it open at the day's start.
- **Today on a filled circle** everywhere a date is drawn in a grid, as the laptop draws it: the
  week and day headers, the month cell's number (a circle a little taller than the 16 dp number
  row, so the cell keeps its five lines of chips) and the year's today, tinted day or not.
- **Weekend names in the today colour**: Saturday and Sunday in the month's weekday row, as in the
  week header.

## Other apps

One Calendar answers what other apps ask of a calendar on the phone; xrncal takes that over, so
that once One Calendar is gone, "Add to calendar" in Gmail, a browser or Messages comes here and
not to another calendar app.

1. **"Add to calendar"**: Android's insert intent for `CalendarContract.Events` (`INSERT`, and the
   older `EDIT`, for `vnd.android.cursor.dir/event` and `vnd.android.cursor.item/event`) opens the
   editor on a new event in the default calendar, filled in from the request's extras: title,
   description, location, begin and end, all-day, repeat rule (one lib-recur can't read is left
   out), time zone (the event's own when the zone database knows it, else the app's) and guests
   (`Intent.EXTRA_EMAIL`, a string or a list), which go into the notes as a "Guests:" line since
   the editor has no guests field. Missing times are a new event's: the next whole hour, as long
   as Options say; an all-day event covers the days its times fall on, whether the app sent
   midnight UTC (Android's own way) or the phone's midnight. The new event's reminder is the
   default for its kind. Nothing is saved until ✓. It is an activity of its own, not the
   calendar's singleTask one, so it opens in the asking app's task and ✓ or ✕ goes back there.
   The request gave the times, so a title typed over isn't read for a date. An `EDIT` of an event
   in Android's own calendar store (which xrncal doesn't use) opens a new event with whatever the
   request carries.

2. **.ics files** (a tap in Files, a mail's attachment, a download): `VIEW` for `text/calendar`,
   `text/x-vcalendar` and `application/ics`, from `content:` and `file:` addresses, and a name
   ending in `.ics` for one sent untyped or as `application/octet-stream`. The file is read
   through the content resolver with the read the sending app grants, so no storage permission
   is asked; it is parsed by `domain/ical` (to ICalParser's 8 MB).
   - **One VEVENT** opens the editor as in item 1, with the event's fields: times in its own zone
     when the zone database knows it, else (UTC, floating, a zone only the file's VTIMEZONE
     explains) the same moment in the app's zone; its repeat rule; its reminders, or the
     defaults when it has none; its attendees as the "Guests:" line.
   - **Several** open the import: how many events (a UID's VEVENTs, a series and its changed
     dates, are one), the dates they span, the calendar to import into (the default first), and
     Import. Each event becomes a resource of its own in that calendar, written as the file has
     it (its VEVENTs and the VTIMEZONEs they name; no METHOD), and is sent at the next sync. An
     event whose UID the calendar holds is there already and skipped, so opening the same file
     twice adds nothing; one whose UID another calendar on the phone holds is saved under a UID
     made from the calendar and its own (the same at every import), so no two calendars share a
     UID; one without a UID gets a new one. Then the counts: Added, Already there.
   - A file that won't parse, or holds no event, says so in one line ("Not a calendar file", "No
     events", "Can't open this file").

Tests: the extras (a map in) to a draft (`InsertRequestTest`); an .ics with one event to a draft,
with several to an import plan, skipping a UID already there (`IcsFilesTest`), in `domain/`; the
extras' names against Android's own and an import into the store (`OtherAppsTest`);
`ManifestTest` for the filters and that no storage permission is asked; previews of the import.

## Later

"This and following" edits of a series; calendar names, and making calendars on the server; a
jump-to-date picker; a whole calendar exported as .ics (one event is shared as one); Google and
Microsoft accounts; guests.
