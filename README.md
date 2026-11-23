<h1 align="center">xrncal for Android</h1>

A calendar for Android phones that looks and works like One Calendar's phone app, syncs over
CalDAV, and shows the Vietnamese lunar date on every day. It is the phone side of
[xrncal](https://github.com/Xernnn/xrncal-desktop), the laptop calendar: both read the same
calendars from the same CalDAV server, so an event made on one shows on the other. Kotlin and
Jetpack Compose, minimum Android 12.

**Status:** phase 1 (version 0.1.0). [docs/spec.md](docs/spec.md) says what is in it, what
is left for later, and which of One Calendar's layouts it copies.

## What it does

- **Month** (the default), **List**, **Week** and **Day** views, swiped between periods, with
  One Calendar's top bar (`+` and a Today button) and a drawer of views and calendars.
- The **lunar date** (âm lịch) on each day of the month, in each day header and in an event's
  detail, worked out on the phone by the same method as the laptop app.
- **Events**: detail, create, edit, delete; all-day; start and end in any time zone; repeat
  (every day, weekdays, week, month, year, or custom); reminders; location; notes. One date of a
  repeating event, or all of them.
- **Reminders** as notifications at their minute (exact alarms), set again after every sync and
  after a reboot.
- **Holiday calendars**: Vietnam's public and lunar holidays and a few international days,
  worked out on the phone and turned on and off in the drawer.
- **Search** across every calendar, accents ignored.

## Sync

One CalDAV account (any server; tested against Radicale 3.8.1) and one calendar on the phone that
never leaves it. The app finds the calendars from the server's address (`/.well-known/caldav`,
then the principal's calendar home), keeps each event's iCalendar text as the server sent it, and
sends changes back with `If-Match`, so it never writes over an edit made elsewhere: that becomes a
conflict to settle with "Keep mine" or "Keep theirs". It works offline and sends what changed when
the server is back. Sync runs on opening the app, after each change and every 30 minutes in the
background. The password is kept sealed with an Android Keystore key.

xrncal on the laptop and this app agree on what they write: events in their own time zone with a
`VTIMEZONE`, reminders as `VALARM`s (as in One Calendar, an event without one never rings; the
default reminders only fill in a new event's), and everything another program wrote kept as it
was.

## Build and test

```sh
./gradlew :domain:test              # iCalendar, recurrence, lunar dates, holidays, reminders, layouts
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

- [docs/spec.md](docs/spec.md): phase 1's scope, the One Calendar layouts copied, the data model,
  sync and reminders.

## License

MIT, see [LICENSE](LICENSE).
