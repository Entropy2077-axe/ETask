# ETask integration

ETask combines Etar's Android calendar views and Calendar Provider integration with the core
local task workflow represented by Tasks.org. The source application remains GPLv3 and retains
the upstream Etar license and notices.

## Added modules

- Local SQLite task list with create, complete, and delete actions.
- Unified navigation from the calendar drawer to Tasks, AI Planner, and AI Settings.
- OpenAI-compatible AI settings, defaulting to `https://api.deepseek.com` and
  `deepseek-chat`.
- Model discovery through `GET /models` and a connection test.
- Natural-language planning through `POST /chat/completions`, with a preview before changes.
- AI tasks are stored locally; AI events are inserted into the first visible writable Android
  calendar after runtime permission is granted.
- AI planning uses a multi-turn Chinese conversation. Ambiguous requests trigger a clarifying
  question instead of silently guessing.
- Stable user preferences are summarized into local habit memory and included in later planning
  requests. Users can inspect and clear this memory from AI Settings.
- Task and chat screens use recycling list adapters; database, calendar, and network work runs
  away from the main thread to reduce UI stalls.
- AI reads the complete device calendar catalog and assigns generated items to a writable calendar
  by subject, allowing offline calendars such as humanities, mathematics, or physics to act as
  categories. Locations and RFC5545 recurrence rules are preserved when calendar events are saved.
- Month view caches expensive event DNA drawing data per week and size. Agenda rows cache timezone
  and clock-format configuration and avoid redundant layout work during scrolling.

## Build

Open this directory in Android Studio or run:

```powershell
$env:JAVA_HOME='C:\Projects\AndroidStudio\jbr'
.\gradlew.bat :app:assembleDebug
```
