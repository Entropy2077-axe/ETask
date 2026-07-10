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

## Build

Open this directory in Android Studio or run:

```powershell
$env:JAVA_HOME='C:\Projects\AndroidStudio\jbr'
.\gradlew.bat :app:assembleDebug
```
