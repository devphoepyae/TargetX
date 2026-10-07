# TargetX
Generic, brand-agnostic courier KPI and commission tracking Android application built with Kotlin, Jetpack Compose, and Supabase.

## Getting started

Requirements: JDK 17, Android SDK (platform 35).

1. Copy `local.properties.example` to `local.properties` (git-ignored) and set:
   ```properties
   sdk.dir=/path/to/Android/sdk
   SUPABASE_URL=https://your-project-ref.supabase.co
   SUPABASE_ANON_KEY=your-anon-public-key
   ```
   `SUPABASE_URL` / `SUPABASE_ANON_KEY` can also be supplied as environment variables (e.g. in CI). They are exposed to the app as `BuildConfig` fields. If missing, the app still builds and the login screen shows a "not configured" message.
2. Build and test:
   ```bash
   ./gradlew assembleDebug testDebugUnitTest lintDebug
   ```

## Project structure

```text
app/src/main/java/com/targetx/app/
├── data/      # Supabase config, repository implementations
├── domain/    # Models, repository interfaces, use cases, validation (pure Kotlin)
├── ui/        # Compose screens + ViewModels (auth, home, root session gate, theme)
└── di/        # Hilt modules (Supabase client, repository bindings)
```

See [SPECIFICATION.md](SPECIFICATION.md) for the full product and architecture specification.
