<h1 align="center">xrncal for Android</h1>

A calendar for Android phones that looks and works like One Calendar's phone app, syncs over
CalDAV, and shows the Vietnamese lunar date on every day. It is the phone side of
[xrncal](https://github.com/Xernnn/xrncal-desktop), the laptop calendar: both read the same
calendars from the same CalDAV server, so an event made on one shows on the other. Kotlin and
Jetpack Compose, minimum Android 12.

**Status:** phase 2 (on top of 0.1.0). [docs/spec.md](docs/spec.md) says what each phase
holds, what is left for later, and which of One Calendar's layouts it copies.

## What it does

- **Month**, **List**, **Week**, **Day** and **Year** views, swiped between periods, under One
  Calendar's top bar (the period and its subtitle, `+`, the calendar **filter**, Today) and
  drawer; a **wallpaper** behind every view (the laptop's four, or one from the gallery) with
  see-through bars and grid.
- The month fills each day with one-line chips and counts the rest ("24+2"); tapping a day opens
  One Calendar's day pop-up. Week and day open at the day's start, at the hour size chosen, with
  a **second time zone** in the gutter. The year tints each day in its busiest calendar's colour.
- The **lunar date** (âm lịch) on each day of the month, in the headers, the year and the
  editor, worked out on the phone by the same method as the laptop app; mùng 1 and rằm carry the
  laptop's moon chips (○ 1/10, ● 15/10).
- **xrncal's own look** over One Calendar's layout: the laptop's accent and today red, its week
  header (the weekday over the date, today's on a filled circle, the weekend named in red), and
  the hours as numbers only (08, 09 … 23).
- **Events** open straight into One Calendar's editor: emoji, title and place suggestions and
  dates typed into a new event's title (all as the laptop does), calendar and **event colour**,
  all-day, start and end in any time zone, One Calendar's compact repeat form, reminders,
  description; Copy, Share as .ics, Delete with Undo. One date of a repeating event, that date and
  the ones after it, or all.
- **Colours**: One Calendar's palette; a calendar's colour is written to the server, so the
  laptop shows it too.
- **Reminders** as notifications at their minute (exact alarms) with Open and Snooze, set again
  after every sync and after a reboot. As in One Calendar, an event rings only for its own
  reminders; the defaults fill in new events.
- **Widgets**: an agenda of the days ahead with their events as chips of their calendar's colour;
  a month and the date icon.
- **Holiday calendars**: Vietnam's public and lunar holidays and a few international days,
  worked out on the phone.
- **Search** with One Calendar's Future and Past tabs, accents ignored; **Options** laid out as
  One Calendar's.
- **Other apps**: "Add to calendar" in Gmail, a browser or Messages opens the editor on a new
  event, filled in and saved only on ✓; an **.ics file** opens its event the same way, or imports
  several into a calendar (skipping those already there); a clock widget's "open the calendar
  at this time" opens that day. An event's **place** opens in the maps app, from the editor or
  the day pop-up.

## Sync

One CalDAV account (any server; tested against Radicale 3.8.1) and one calendar on the phone that
never leaves it. The app finds the calendars from the server's address (`/.well-known/caldav`,
then the principal's calendar home), keeps each event's iCalendar text as the server sent it, and
sends changes back with `If-Match`, so it never writes over an edit made elsewhere: that becomes a
conflict to settle with "Keep mine" or "Keep theirs". It works offline and sends what changed when
the server is back. Sync runs on opening the app, after each change and every 30 minutes in the
background (Options sets the interval). A calendar colour picked on the phone is written to the
server as Apple's `calendar-color` (PROPPATCH), which the laptop reads too. The password is kept sealed with an Android Keystore key.

xrncal on the laptop and this app agree on what they write: events in their own time zone with a
`VTIMEZONE`, reminders as `VALARM`s (as in One Calendar, an event without one never rings; the
default reminders only fill in a new event's), and everything another program wrote kept as it
was.

## Build and test

```sh
./gradlew :domain:test              # iCalendar, recurrence, lunar dates, holidays, reminders, layouts, typed dates, suggestions
./gradlew :app:testDebugUnitTest    # sync against a real Radicale, reminders, the manifest
./gradlew :app:assembleDebug
```

The sync tests start Radicale 3.8.1 on a free port with a throwaway folder and a made-up user,
and are skipped when it isn't installed: `python3 -m venv <dir> && <dir>/bin/pip install
radicale==3.8.1`, then run the tests with `XRNCAL_RADICALE=<dir>/bin/radicale` (or have
`radicale` on the `PATH`). CI does this on every push.

The domain tests carry the laptop app's own test vectors (lunar dates, anniversaries, all-day
ends, reminders, recurrence limits, summer-time changes), so both apps answer the same.

`app/src/debug/.../ui/Previews.kt` draws every screen from made-up calendars without a phone:
`android preview render "?:app" --fqn=com.xern.xrncal.ui.PreviewsKt.MonthPreview -o <folder>`.

Release builds are signed with the owner's key, read from `../xrn-signing.properties` beside the
repository; elsewhere a debug build uses the machine's debug key.

## Credits

- [lib-recur](https://github.com/dmfs/lib-recur) (Apache-2.0) expands recurrence rules.
- [OkHttp](https://square.github.io/okhttp/) (Apache-2.0) carries the CalDAV requests.
- MiSans by Xiaomi is the font.
- The four wallpapers are the laptop app's: photos from [Unsplash](https://unsplash.com) (ids
  photo-1506703719100-a0f3a48c0f86, -1448375240586-882707db888b, -1518495973542-4542c06a5843 and
  -1519501025264-65ba15a82390), used under the Unsplash License, not this repository's MIT licence.
- The lunar calendar follows Hồ Ngọc Đức's astronomical method.
- The layout follows One Calendar by Code Spark; none of its name, logo or artwork is used.

## Docs

- [docs/spec.md](docs/spec.md): each phase's scope, the One Calendar layouts copied, the data
  model, sync and reminders.

## License

MIT, see [LICENSE](LICENSE).
