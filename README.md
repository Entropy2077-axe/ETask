# ETask

ETask is an Android calendar and task planner with an AI-assisted natural-language workflow.
It combines Etar's mature Android calendar experience with a lightweight local task list and
an OpenAI-compatible planner that defaults to DeepSeek.

## Features

- Month, week, day, and agenda calendar views.
- Android Calendar Provider integration, including offline calendars and ICS support.
- Local tasks with create, complete, and delete actions.
- Natural-language planning for tasks and calendar events.
- Review generated plans before saving them.
- DeepSeek defaults (`https://api.deepseek.com`, `deepseek-chat`).
- Pull available models and test the configured AI connection.
- Android 6.0 and newer.

## AI setup

Open the navigation drawer and select **AI settings**. Enter your API key, optionally change the
OpenAI-compatible base URL, then use **Pull models** or **Test connection**. API credentials are
stored locally on the Android device and are never committed to this repository.

## Build

Open this directory in Android Studio, or run:

```powershell
$env:JAVA_HOME='C:\Projects\AndroidStudio\jbr'
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture and attribution

See [ETASK_INTEGRATION.md](ETASK_INTEGRATION.md) for integration details. ETask is based on Etar,
which in turn includes Android Open Source Project Calendar code. The original copyright and
license notices are retained. This project is distributed under GPLv3; see [LICENSE](LICENSE) and
[LICENSE.apache2](LICENSE.apache2).
