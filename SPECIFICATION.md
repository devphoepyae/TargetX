# TargetX — Technical Specification

> **Status:** Draft v1.0 (Phase 1 baseline)
> **Product:** Generic, brand-agnostic courier KPI & commission utility for Android
> **Distribution:** Public (Google Play)

---

## Table of Contents

1. [Product Overview](#1-product-overview)
2. [Tech Stack & Architecture Principles](#2-tech-stack--architecture-principles)
3. [Core Business Logic](#3-core-business-logic)
4. [Functional Requirements](#4-functional-requirements)
5. [Android Dependency Stack](#5-android-dependency-stack)
6. [Clean Architecture Directory Layout](#6-clean-architecture-directory-layout)
7. [Supabase PostgreSQL Schema](#7-supabase-postgresql-schema)
8. [Gemini Vision OCR Specification](#8-gemini-vision-ocr-specification)
9. [Security, Privacy & Compliance](#9-security-privacy--compliance)
10. [Testing Strategy](#10-testing-strategy)
11. [Phase 1 Implementation Roadmap](#11-phase-1-implementation-roadmap)
12. [Open Questions & Risks](#12-open-questions--risks)

---

## 1. Product Overview

TargetX lets an individual courier take **three screenshots** from their courier operator app at the end of a shift, extracts the relevant counts with a vision LLM, and instantly shows:

- **KPI points** for the day
- **Delivery commission**, **pickup commission**, and **total daily commission**
- A **history** of daily logs synced to the cloud

### 1.1 Brand-agnostic principle

TargetX is **not affiliated with any courier company**. The app must not ship third-party logos, trademarks, or brand colors. All operator-specific behavior (KPI weights, commission rates, currency label) is expressed as **data** (a "preset"), never as hard-coded brand logic. The default preset is named **`REX`**; additional presets can be added without code changes to the domain layer.

### 1.2 Target users

| Persona | Need |
|---|---|
| Courier / rider | Know today's KPI points and earnings in < 30 s after the shift |
| Courier with non-standard contract | Configure own rates/weights via **Custom Mode** |

### 1.3 Non-goals (Phase 1)

- Team/manager dashboards, payroll export, multi-tenant admin
- Direct integration with any courier operator's API
- iOS / web clients

---

## 2. Tech Stack & Architecture Principles

| Concern | Choice |
|---|---|
| Language | 100% Kotlin (JVM target 17) |
| UI | Jetpack Compose + Material 3 |
| Architecture | Clean Architecture (data / domain / presentation) + MVVM + Unidirectional Data Flow |
| DI | **Hilt** (chosen over Koin for compile-time graph validation and first-class `ViewModel`/`WorkManager` integration) |
| Async | Kotlin Coroutines + Flow |
| Backend | Supabase (Auth, PostgreSQL with RLS, Edge Functions) |
| OCR / extraction | Google Gemini Flash Vision (`gemini-1.5-flash` per product brief; model ID is configuration — see [§12](#12-open-questions--risks)) |
| Local persistence | Room (offline cache + sync queue), DataStore (preferences, encrypted session) |
| Networking | Retrofit + OkHttp (OCR proxy), Ktor (used internally by supabase-kt) |
| Images | Coil (thumbnails/previews), Android Photo Picker |
| Background work | WorkManager (deferred sync of daily logs) |
| Min / Target / Compile SDK | 26 / 35 / 35 |

### 2.1 Architecture rules

1. **Dependency rule:** `presentation → domain ← data`. The `domain` package is pure Kotlin — no `android.*`, no Supabase, no Retrofit imports.
2. **Unidirectional Data Flow:** every screen has a `UiState` (immutable data class), `UiEvent` (user intents), and `UiEffect` (one-off side effects such as navigation or snackbars).
   - `ViewModel` exposes `val state: StateFlow<UiState>` and `val effects: Flow<UiEffect>` (backed by a `Channel`).
   - Composables call `viewModel.onEvent(event)`; they never mutate state directly.
3. **Use cases** encapsulate a single business action (`CalculateDailyResultUseCase`, `ExtractScreenshotUseCase`, ...). ViewModels depend on use cases, never on repositories' implementations.
4. **Money and points are never `Float`/`Double` in storage or calculation.** Commission is `Long` (smallest currency unit, e.g. whole Ks). KPI uses `BigDecimal` in the domain and `numeric(10,2)` in Postgres.
5. **Errors** cross layer boundaries as a sealed `DomainError`, wrapped in `Result<T>` / a custom `Outcome<T>`; exceptions never leak to the UI.

```text
┌──────────────────────── presentation ────────────────────────┐
│ Composable ──UiEvent──▶ ViewModel ──calls──▶ UseCase         │
│     ▲                       │                                 │
│     └──── StateFlow<UiState>┘  Channel<UiEffect>              │
└──────────────────────────────┬───────────────────────────────┘
                               ▼
┌────────────────────────── domain ────────────────────────────┐
│ UseCases · Models · Repository interfaces · Calculator       │
└──────────────────────────────┬───────────────────────────────┘
                               ▼ (implemented by)
┌─────────────────────────── data ─────────────────────────────┐
│ RepositoryImpl ─▶ Room DAO / DataStore / Supabase / Retrofit │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. Core Business Logic

### 3.1 Auto-login

- On successful sign-in, the Supabase session (access + refresh token) is persisted locally (encrypted, see [§9](#9-security-privacy--compliance)).
- On cold start, `SplashViewModel` waits for supabase-kt's `sessionStatus` to leave the `Initializing` state:
  - `Authenticated` → navigate directly to **Home** (main flow), no login screen shown.
  - `NotAuthenticated` / refresh failure → navigate to **Sign In**.
- Token refresh is automatic (`alwaysAutoRefresh = true`). If offline at launch with a stored session, the user still enters the main flow in offline mode; sync resumes when connectivity returns.

### 3.2 Three-screenshot OCR validation

The user supplies exactly **three screenshots**, each in a dedicated slot. Each slot has its own extraction contract:

| Slot | Screen type key | Extract | Explicitly ignore |
|---|---|---|---|
| 1 | `DASHBOARD_OVERVIEW` | `pickup_done`, `delivery_done` | `dropoff` (any variant), all other tiles |
| 2 | `OUTBOUND_REPORT` | `pickup_waybill_qty` per waybill type | All amount / currency columns |
| 3 | `INBOUND_REPORT` | `completed_qty`, `cash_collect_qty` per waybill type | All amount / currency columns |

**Waybill types** (canonical enum): `BP`, `E`, `C`, `CC`, `I`, `OTHER`.
Any row label not matching the first five is mapped to `OTHER` (summed if multiple). Missing types are treated as `0`.

#### Validation pipeline (per screenshot)

1. **Pre-checks (on-device):** MIME is image/*, size ≤ 7 MB after compression, SHA-256 hash differs from the other two slots (prevents uploading the same screenshot twice).
2. **Screen-type check:** Gemini returns `detected_screen_type`; if it differs from the slot's expected type → reject with "This looks like the *X* screen; please use slot *Y*".
3. **Schema check:** response must parse against the JSON schema in [§8](#8-gemini-vision-ocr-specification); all quantities are non-negative integers.
4. **Confidence check:** if `confidence < 0.80` or `unreadable_fields` is non-empty → values are pre-filled but flagged; the user must confirm/correct on the Review screen.
5. **Date check:** if `report_date` is detected and ≠ selected log date → warning (not blocking).

#### Cross-screenshot consistency checks (warnings, not blocking)

| Check | Rule |
|---|---|
| Delivery consistency | `dashboard.delivery_done` vs `Σ inbound.completed_qty` — warn if they differ |
| Cash-collect sanity | For each type, `cash_collect_qty ≤ completed_qty` |
| Pickup sanity | Warn if `pickup_done > 0` and `Σ outbound.pickup_waybill_qty == 0` |

The user always gets a **human-in-the-loop Review screen** where every extracted number is editable before the result is saved. Edited fields are recorded (`manually_edited_fields`) for audit and prompt-quality tracking.

### 3.3 Calculation formulas (REX preset defaults)

Definitions:

```text
pickup_waybill_total   = Σ outbound.pickup_waybill_qty   over {BP, E, C, CC, I, OTHER}
completed_total        = Σ inbound.completed_qty          over {BP, E, C, CC, I, OTHER}
cash_collect_total     = Σ inbound.cash_collect_qty       over {BP, E, C, CC, I, OTHER}   (informational)
```

| Output | Formula (REX defaults) |
|---|---|
| **KPI Points** | `(delivery_done × 1.0) + (pickup_done × 1.0) + (pickup_waybill_total × 0.2)` |
| **Delivery Commission** | `completed_total × 100 Ks` |
| **Pickup Commission** | `pickup_waybill_total × 50 Ks` |
| **Total Daily Commission** | `Delivery Commission + Pickup Commission` |

Notes:
- `delivery_done` and `pickup_done` come from the **Dashboard**; `pickup_waybill_total` from the **Outbound** report; `completed_total` from the **Inbound** report.
- `cash_collect_qty` is extracted and stored but **does not** affect KPI or commission in the REX preset.
- KPI is rounded half-up to 2 decimals for display; commission is an exact integer.

#### Worked example

| Input | Value |
|---|---|
| delivery_done | 40 |
| pickup_done | 10 |
| Outbound pickup_waybill_qty | BP 12, E 5, C 3, CC 2, I 1, OTHER 2 → **25** |
| Inbound completed_qty | BP 20, E 8, C 4, CC 3, I 2, OTHER 1 → **38** |

```text
KPI                 = 40×1.0 + 10×1.0 + 25×0.2 = 55.00
Delivery Commission = 38 × 100 = 3,800 Ks
Pickup Commission   = 25 × 50  = 1,250 Ks
Total Commission    = 5,050 Ks
```

### 3.4 Dual mode: Preset vs Custom

All formula coefficients live in a `CalculationProfile`:

```kotlin
data class CalculationProfile(
    val mode: CalculationMode,              // PRESET or CUSTOM
    val presetCode: String?,                // "REX" when mode == PRESET
    val kpiDeliveryWeight: BigDecimal,      // default 1.0
    val kpiPickupWeight: BigDecimal,        // default 1.0
    val kpiWaybillWeight: BigDecimal,       // default 0.2
    val deliveryRatePerUnit: Long,          // default 100
    val pickupRatePerUnit: Long,            // default 50
    val currencyLabel: String,              // default "Ks"
    val includedWaybillTypes: Set<WaybillType> = WaybillType.entries.toSet(),
)

enum class CalculationMode { PRESET, CUSTOM }
enum class WaybillType { BP, E, C, CC, I, OTHER }
```

- **Preset mode (default):** values are read-only and come from the bundled preset table (`REX` → defaults above). Presets are defined in a single Kotlin object `Presets` in the domain layer so that new presets are a data-only change.
- **Custom mode:** enabled in **Settings → Calculation mode**. The user can edit every coefficient, the currency label, and which waybill types count toward totals. "Reset to REX defaults" restores the preset values.
- Each saved `daily_log` stores a **snapshot of the profile used**, so historic results never change when settings change later.

Pure domain calculator:

```kotlin
class DailyResultCalculator {
    fun calculate(input: DailyInput, profile: CalculationProfile): DailyResult {
        val waybills  = input.outbound.filterKeys { it in profile.includedWaybillTypes }.values.sumOf { it.pickupWaybillQty }
        val completed = input.inbound.filterKeys  { it in profile.includedWaybillTypes }.values.sumOf { it.completedQty }

        val kpi = (input.deliveryDone.toBigDecimal() * profile.kpiDeliveryWeight) +
                  (input.pickupDone.toBigDecimal()   * profile.kpiPickupWeight) +
                  (waybills.toBigDecimal()           * profile.kpiWaybillWeight)

        val deliveryCommission = completed.toLong() * profile.deliveryRatePerUnit
        val pickupCommission   = waybills.toLong()  * profile.pickupRatePerUnit

        return DailyResult(
            kpiPoints = kpi.setScale(2, RoundingMode.HALF_UP),
            deliveryCommission = deliveryCommission,
            pickupCommission = pickupCommission,
            totalCommission = deliveryCommission + pickupCommission,
            pickupWaybillTotal = waybills,
            completedTotal = completed,
        )
    }
}
```

---

## 4. Functional Requirements

### 4.1 Screens (Phase 1)

| Route | Screen | Purpose |
|---|---|---|
| `splash` | Splash / Session gate | Resolve auto-login, route to `auth` or `home` |
| `auth/sign_in`, `auth/sign_up` | Auth | Email + password via Supabase Auth; password reset link |
| `home` | Today | Today's result card (or CTA "Add today's screenshots"), quick stats for the week |
| `capture` | Capture | 3 slots (Dashboard / Outbound / Inbound), pick via Photo Picker, per-slot status (idle / uploading / extracted / error) |
| `review` | Review & confirm | Editable extracted values, warnings from §3.2, live-recalculated result |
| `result/{date}` | Daily result | KPI, delivery/pickup/total commission, profile snapshot used |
| `history` | History | Paginated list of daily logs, monthly totals |
| `settings` | Settings | Calculation mode (Preset/Custom), custom coefficients, currency label, account, sign-out, delete account |

### 4.2 Key user stories & acceptance criteria

| ID | Story | Acceptance criteria |
|---|---|---|
| US-01 | As a returning user I open the app and land on Home | With a valid stored session, `home` is the first interactive screen; no auth screen flashes |
| US-02 | I upload 3 screenshots and get my earnings | All 3 slots extracted → Review screen shows totals computed with active profile in ≤ 15 s p90 on 4G |
| US-03 | I put a screenshot in the wrong slot | Slot shows an error naming the detected screen type; nothing is saved |
| US-04 | OCR misreads a number | I can edit it on Review; result recalculates instantly; edit is recorded |
| US-05 | I have a custom contract | In Custom Mode I set my rates; new logs use them; old logs keep their snapshot |
| US-06 | I save while offline | Log is saved to Room with `sync_state = PENDING` and uploaded by WorkManager when online |
| US-07 | I re-upload for the same date | Prompt "Replace existing log for <date>?"; upsert on `(user_id, log_date)` |
| US-08 | I want to leave | Settings → Delete account removes profile, settings and logs (cascade) |

### 4.3 Non-functional requirements

- **Performance:** cold start to Home ≤ 1.5 s on mid-range device (with stored session).
- **Reliability:** OCR requests retried up to 2× with exponential backoff on 429/5xx; idempotent upsert for logs.
- **Accessibility:** all touch targets ≥ 48 dp, content descriptions for icons, supports font scale 200%.
- **Localization:** strings externalized; English + Burmese (`my`) in Phase 1; numbers formatted with locale grouping.
- **Theming:** Material 3 dynamic color with neutral fallback palette (no brand colors).

---

## 5. Android Dependency Stack

Versions below are a known-good baseline; keep them current via Dependabot/Renovate and verify compatibility (Kotlin ↔ KSP ↔ Compose compiler) on each bump.

### 5.1 `gradle/libs.versions.toml`

```toml
[versions]
agp                 = "8.7.3"
kotlin              = "2.0.21"
ksp                 = "2.0.21-1.0.28"
coroutines          = "1.9.0"
serialization       = "1.7.3"
composeBom          = "2024.12.01"
activityCompose     = "1.9.3"
lifecycle           = "2.8.7"
navigation          = "2.8.5"
hilt                = "2.52"
hiltAndroidx        = "1.2.0"
room                = "2.6.1"
datastore           = "1.1.1"
workManager         = "2.10.0"
retrofit            = "2.11.0"
okhttp              = "4.12.0"
coil                = "3.0.4"
supabase            = "3.0.3"
ktor                = "3.0.2"
tink                = "1.15.0"
timber              = "5.0.1"
# test
junit               = "4.13.2"
mockk               = "1.13.13"
turbine             = "1.2.0"
truth               = "1.4.4"
androidxTestExt     = "1.2.1"
espresso            = "3.6.1"

[libraries]
# Kotlin
kotlinx-coroutines-android   = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test      = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json   = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }

# Compose
androidx-compose-bom         = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui          = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling  = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3   = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
androidx-compose-ui-test     = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-activity-compose    = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-navigation-compose  = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }

# Lifecycle
androidx-lifecycle-runtime-compose   = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }

# DI
hilt-android                 = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler                = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltAndroidx" }
androidx-hilt-work           = { module = "androidx.hilt:hilt-work", version.ref = "hiltAndroidx" }
androidx-hilt-compiler       = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltAndroidx" }

# Persistence
androidx-room-runtime        = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx            = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler       = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-work-runtime-ktx    = { module = "androidx.work:work-runtime-ktx", version.ref = "workManager" }
tink-android                 = { module = "com.google.crypto.tink:tink-android", version.ref = "tink" }

# Networking
retrofit                     = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp                       = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging               = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }

# Images
coil-compose                 = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }

# Supabase (supabase-kt)
supabase-bom                 = { module = "io.github.jan-tennert.supabase:bom", version.ref = "supabase" }
supabase-auth                = { module = "io.github.jan-tennert.supabase:auth-kt" }
supabase-postgrest           = { module = "io.github.jan-tennert.supabase:postgrest-kt" }
supabase-functions           = { module = "io.github.jan-tennert.supabase:functions-kt" }
ktor-client-okhttp           = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }

# Logging
timber                       = { module = "com.jakewharton.timber:timber", version.ref = "timber" }

# Test
junit                        = { module = "junit:junit", version.ref = "junit" }
mockk                        = { module = "io.mockk:mockk", version.ref = "mockk" }
turbine                      = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
truth                        = { module = "com.google.truth:truth", version.ref = "truth" }
androidx-test-ext-junit      = { module = "androidx.test.ext:junit", version.ref = "androidxTestExt" }
androidx-espresso-core       = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }
androidx-room-testing        = { module = "androidx.room:room-testing", version.ref = "room" }

[plugins]
android-application   = { id = "com.android.application", version.ref = "agp" }
kotlin-android        = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose        = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization  = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp                   = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt                  = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

### 5.2 Dependency rationale

| Area | Library | Why |
|---|---|---|
| DI | Hilt | Compile-time verified graph; `@HiltViewModel`, `@HiltWorker` |
| Concurrency | Coroutines + Flow | Structured concurrency; `StateFlow` for UDF state |
| Local DB | Room | Offline cache of `daily_logs`, pending-sync queue, Flow-based queries |
| Preferences | DataStore | Settings cache, onboarding flags, encrypted Supabase session |
| Encryption | Tink (Android Keystore-backed AEAD) | Encrypts session blob stored in DataStore |
| HTTP | Retrofit + OkHttp | Typed client for the OCR Edge Function; interceptors for auth header, retry, logging (debug only) |
| Serialization | kotlinx.serialization | Shared by Retrofit converter, supabase-kt and Room type converters |
| Images | Coil 3 | Thumbnail previews of selected screenshots |
| Backend | supabase-kt (auth, postgrest, functions) + Ktor OkHttp engine | Official community Kotlin SDK; session auto-refresh |
| Background | WorkManager | Guaranteed sync of offline-saved logs |
| Logging | Timber | Debug-only tree; no PII in release logs |

### 5.3 Build configuration

- `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_ANON_KEY`, `BuildConfig.OCR_FUNCTION_PATH` read from `local.properties` (git-ignored) or CI secrets.
- **No Gemini API key in the APK** (see §8.1).
- R8 full mode enabled for release; keep rules for kotlinx.serialization models.
- Build variants: `debug` (logging interceptor, StrictMode) and `release`.

---

## 6. Clean Architecture Directory Layout

Phase 1 ships as a single `:app` module with strict package boundaries (enforced via Konsist/ArchUnit test). Packages map 1:1 to future Gradle modules (`:core:domain`, `:core:data`, `:feature:*`) if the codebase grows.

```text
TargetX/
├── SPECIFICATION.md
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── supabase/
│   ├── migrations/
│   │   └── 20260101000000_init_schema.sql
│   └── functions/
│       └── ocr-extract/
│           └── index.ts                 # Gemini proxy (Deno)
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   └── java/com/targetx/app/
        │       ├── TargetXApp.kt                    # @HiltAndroidApp
        │       ├── MainActivity.kt                  # single-activity host
        │       │
        │       ├── core/
        │       │   ├── common/                      # Outcome, DispatcherProvider, Clock
        │       │   ├── error/                       # DomainError sealed hierarchy
        │       │   └── util/                        # ImageCompressor, Hashing, DateUtils
        │       │
        │       ├── domain/
        │       │   ├── model/
        │       │   │   ├── WaybillType.kt
        │       │   │   ├── ScreenType.kt
        │       │   │   ├── DashboardExtraction.kt
        │       │   │   ├── OutboundExtraction.kt
        │       │   │   ├── InboundExtraction.kt
        │       │   │   ├── DailyInput.kt
        │       │   │   ├── DailyResult.kt
        │       │   │   ├── DailyLog.kt
        │       │   │   ├── CalculationProfile.kt
        │       │   │   └── UserProfile.kt
        │       │   ├── preset/
        │       │   │   └── Presets.kt               # REX defaults
        │       │   ├── calculator/
        │       │   │   └── DailyResultCalculator.kt
        │       │   ├── validation/
        │       │   │   └── ExtractionValidator.kt   # §3.2 rules & cross-checks
        │       │   ├── repository/                  # interfaces only
        │       │   │   ├── AuthRepository.kt
        │       │   │   ├── OcrRepository.kt
        │       │   │   ├── DailyLogRepository.kt
        │       │   │   └── SettingsRepository.kt
        │       │   └── usecase/
        │       │       ├── auth/  (ObserveSessionUseCase, SignInUseCase, SignUpUseCase, SignOutUseCase, DeleteAccountUseCase)
        │       │       ├── ocr/   (ExtractScreenshotUseCase, ValidateExtractionSetUseCase)
        │       │       ├── log/   (CalculateDailyResultUseCase, SaveDailyLogUseCase, ObserveDailyLogsUseCase, GetDailyLogUseCase)
        │       │       └── settings/ (ObserveCalculationProfileUseCase, UpdateCalculationProfileUseCase, ResetToPresetUseCase)
        │       │
        │       ├── data/
        │       │   ├── local/
        │       │   │   ├── db/
        │       │   │   │   ├── TargetXDatabase.kt
        │       │   │   │   ├── dao/DailyLogDao.kt
        │       │   │   │   ├── entity/DailyLogEntity.kt
        │       │   │   │   └── converter/Converters.kt
        │       │   │   └── datastore/
        │       │   │       ├── SettingsDataStore.kt
        │       │   │       └── EncryptedSessionManager.kt   # implements supabase-kt SessionManager
        │       │   ├── remote/
        │       │   │   ├── supabase/
        │       │   │   │   ├── SupabaseClientProvider.kt
        │       │   │   │   └── dto/ (DailyLogDto, SettingsDto, ProfileDto)
        │       │   │   └── ocr/
        │       │   │       ├── OcrApi.kt                    # Retrofit interface
        │       │   │       ├── AuthHeaderInterceptor.kt
        │       │   │       └── dto/ (OcrRequestDto, OcrResponseDto)
        │       │   ├── mapper/                              # DTO/Entity <-> domain
        │       │   ├── repository/
        │       │   │   ├── AuthRepositoryImpl.kt
        │       │   │   ├── OcrRepositoryImpl.kt
        │       │   │   ├── DailyLogRepositoryImpl.kt        # Room = source of truth, Supabase sync
        │       │   │   └── SettingsRepositoryImpl.kt
        │       │   └── sync/
        │       │       └── DailyLogSyncWorker.kt            # @HiltWorker
        │       │
        │       ├── di/
        │       │   ├── AppModule.kt
        │       │   ├── NetworkModule.kt
        │       │   ├── SupabaseModule.kt
        │       │   ├── DatabaseModule.kt
        │       │   └── RepositoryModule.kt                  # @Binds interfaces -> impls
        │       │
        │       └── presentation/
        │           ├── navigation/ (TargetXNavHost.kt, Routes.kt)
        │           ├── theme/      (Color.kt, Type.kt, Theme.kt)
        │           ├── components/ (ResultCard, ScreenshotSlot, NumberField, WarningBanner)
        │           ├── splash/     (SplashScreen, SplashViewModel)
        │           ├── auth/       (SignInScreen, SignUpScreen, AuthViewModel, AuthContract)
        │           ├── home/       (HomeScreen, HomeViewModel, HomeContract)
        │           ├── capture/    (CaptureScreen, CaptureViewModel, CaptureContract)
        │           ├── review/     (ReviewScreen, ReviewViewModel, ReviewContract)
        │           ├── result/     (ResultScreen, ResultViewModel, ResultContract)
        │           ├── history/    (HistoryScreen, HistoryViewModel, HistoryContract)
        │           └── settings/   (SettingsScreen, SettingsViewModel, SettingsContract)
        │
        ├── test/java/com/targetx/app/      # JVM unit tests (domain, ViewModels, mappers)
        └── androidTest/java/com/targetx/app/ # Room, Compose UI tests
```

### 6.1 UDF contract template

```kotlin
// presentation/capture/CaptureContract.kt
data class CaptureUiState(
    val slots: Map<ScreenType, SlotState> = ScreenType.entries.associateWith { SlotState.Empty },
    val logDate: LocalDate,
    val canContinue: Boolean = false,
)

sealed interface SlotState {
    data object Empty : SlotState
    data class Processing(val previewUri: Uri) : SlotState
    data class Extracted(val previewUri: Uri, val lowConfidence: Boolean) : SlotState
    data class Error(val previewUri: Uri?, val message: UiText) : SlotState
}

sealed interface CaptureUiEvent {
    data class ImagePicked(val slot: ScreenType, val uri: Uri) : CaptureUiEvent
    data class RetrySlot(val slot: ScreenType) : CaptureUiEvent
    data class ClearSlot(val slot: ScreenType) : CaptureUiEvent
    data object ContinueClicked : CaptureUiEvent
}

sealed interface CaptureUiEffect {
    data class NavigateToReview(val draftId: String) : CaptureUiEffect
    data class ShowMessage(val message: UiText) : CaptureUiEffect
}
```

---

## 7. Supabase PostgreSQL Schema

Brand-agnostic: no operator names in table/column names. Preset identity is data (`preset_code`).

### 7.1 Entity overview

```text
auth.users (Supabase-managed)
   │ 1:1
   ▼
public.users ──1:1──▶ public.settings
   │ 1:N
   ▼
public.daily_logs   (unique per user + log_date)
```

### 7.2 Migration `supabase/migrations/20260101000000_init_schema.sql`

```sql
-- ============================================================
-- Extensions & enums
-- ============================================================
create extension if not exists "pgcrypto";

create type public.calculation_mode as enum ('preset', 'custom');
create type public.sync_source      as enum ('ocr', 'manual', 'ocr_edited');

-- ============================================================
-- Shared trigger: updated_at
-- ============================================================
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

-- ============================================================
-- users  (public profile, 1:1 with auth.users)
-- ============================================================
create table public.users (
  id            uuid primary key references auth.users (id) on delete cascade,
  display_name  text check (char_length(display_name) <= 80),
  locale        text not null default 'en',
  timezone      text not null default 'Asia/Yangon',
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create trigger trg_users_updated_at
  before update on public.users
  for each row execute function public.set_updated_at();

-- ============================================================
-- settings  (1:1 with users) — active calculation profile
-- ============================================================
create table public.settings (
  user_id                 uuid primary key references public.users (id) on delete cascade,
  mode                    public.calculation_mode not null default 'preset',
  preset_code             text not null default 'REX',
  kpi_delivery_weight     numeric(6,3) not null default 1.000 check (kpi_delivery_weight >= 0),
  kpi_pickup_weight       numeric(6,3) not null default 1.000 check (kpi_pickup_weight   >= 0),
  kpi_waybill_weight      numeric(6,3) not null default 0.200 check (kpi_waybill_weight  >= 0),
  delivery_rate_per_unit  integer      not null default 100   check (delivery_rate_per_unit >= 0),
  pickup_rate_per_unit    integer      not null default 50    check (pickup_rate_per_unit   >= 0),
  currency_label          text         not null default 'Ks'  check (char_length(currency_label) between 1 and 8),
  included_waybill_types  text[]       not null default array['BP','E','C','CC','I','OTHER']
                          check (included_waybill_types <@ array['BP','E','C','CC','I','OTHER']),
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now()
);

create trigger trg_settings_updated_at
  before update on public.settings
  for each row execute function public.set_updated_at();

-- ============================================================
-- daily_logs  — one row per user per day
-- ============================================================
create table public.daily_logs (
  id                      uuid primary key default gen_random_uuid(),
  user_id                 uuid not null references public.users (id) on delete cascade,
  log_date                date not null,

  -- Dashboard (slot 1)
  pickup_done             integer not null default 0 check (pickup_done   >= 0),
  delivery_done           integer not null default 0 check (delivery_done >= 0),

  -- Outbound (slot 2): {"BP":12,"E":5,"C":3,"CC":2,"I":1,"OTHER":2}
  outbound_pickup_waybill jsonb   not null default '{}'::jsonb,
  pickup_waybill_total    integer not null default 0 check (pickup_waybill_total >= 0),

  -- Inbound (slot 3): {"BP":{"completed":20,"cash_collect":15}, ...}
  inbound_breakdown       jsonb   not null default '{}'::jsonb,
  completed_total         integer not null default 0 check (completed_total    >= 0),
  cash_collect_total      integer not null default 0 check (cash_collect_total >= 0),

  -- Results (computed on device, verified by tests; stored for history)
  kpi_points              numeric(10,2) not null default 0,
  delivery_commission     bigint        not null default 0 check (delivery_commission >= 0),
  pickup_commission       bigint        not null default 0 check (pickup_commission   >= 0),
  total_commission        bigint        not null default 0
                          check (total_commission = delivery_commission + pickup_commission),

  -- Snapshot of the profile used for this calculation (immutable history)
  profile_snapshot        jsonb   not null,

  -- Provenance / audit
  source                  public.sync_source not null default 'ocr',
  manually_edited_fields  text[]  not null default '{}',
  ocr_model               text,
  ocr_warnings            text[]  not null default '{}',
  client_updated_at       timestamptz not null default now(),   -- last-write-wins conflict key

  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),

  constraint uq_daily_logs_user_date unique (user_id, log_date),
  constraint chk_outbound_is_object  check (jsonb_typeof(outbound_pickup_waybill) = 'object'),
  constraint chk_inbound_is_object   check (jsonb_typeof(inbound_breakdown) = 'object')
);

create index idx_daily_logs_user_date on public.daily_logs (user_id, log_date desc);

create trigger trg_daily_logs_updated_at
  before update on public.daily_logs
  for each row execute function public.set_updated_at();

-- ============================================================
-- Auto-provision profile + settings on sign-up
-- ============================================================
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.users (id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data ->> 'display_name', null));
  insert into public.settings (user_id) values (new.id);
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ============================================================
-- Row Level Security — every row is private to its owner
-- ============================================================
alter table public.users      enable row level security;
alter table public.settings   enable row level security;
alter table public.daily_logs enable row level security;

create policy "users_select_own" on public.users
  for select using ((select auth.uid()) = id);
create policy "users_update_own" on public.users
  for update using ((select auth.uid()) = id) with check ((select auth.uid()) = id);

create policy "settings_select_own" on public.settings
  for select using ((select auth.uid()) = user_id);
create policy "settings_update_own" on public.settings
  for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy "daily_logs_select_own" on public.daily_logs
  for select using ((select auth.uid()) = user_id);
create policy "daily_logs_insert_own" on public.daily_logs
  for insert with check ((select auth.uid()) = user_id);
create policy "daily_logs_update_own" on public.daily_logs
  for update using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "daily_logs_delete_own" on public.daily_logs
  for delete using ((select auth.uid()) = user_id);

-- ============================================================
-- Monthly summary view (respects RLS via security_invoker)
-- ============================================================
create view public.monthly_summary
with (security_invoker = true) as
select
  user_id,
  date_trunc('month', log_date)::date as month,
  count(*)                            as days_logged,
  sum(kpi_points)                     as kpi_points,
  sum(delivery_commission)            as delivery_commission,
  sum(pickup_commission)              as pickup_commission,
  sum(total_commission)               as total_commission
from public.daily_logs
group by user_id, date_trunc('month', log_date);
```

### 7.3 `profile_snapshot` JSON shape

```json
{
  "mode": "preset",
  "preset_code": "REX",
  "kpi_delivery_weight": 1.0,
  "kpi_pickup_weight": 1.0,
  "kpi_waybill_weight": 0.2,
  "delivery_rate_per_unit": 100,
  "pickup_rate_per_unit": 50,
  "currency_label": "Ks",
  "included_waybill_types": ["BP", "E", "C", "CC", "I", "OTHER"]
}
```

### 7.4 Sync strategy

- **Room is the source of truth for the UI.** `DailyLogRepositoryImpl` writes to Room first (`sync_state = PENDING`), then enqueues `DailyLogSyncWorker` (unique work, `NetworkType.CONNECTED`).
- Worker performs `upsert(onConflict = "user_id,log_date")`; server row with newer `client_updated_at` wins.
- On login / pull-to-refresh, logs are fetched for the visible range and merged into Room.
- Account deletion: an Edge Function `delete-account` (service role) deletes the `auth.users` row; cascades remove all public data.

---

## 8. Gemini Vision OCR Specification

### 8.1 Request flow (API key never on device)

```text
App ──(Retrofit, Supabase JWT, base64 image + screen_type)──▶ Supabase Edge Function `ocr-extract`
     Edge Function ──(x-goog-api-key from function secret)──▶ Gemini generateContent
     Edge Function ◀── JSON (schema-constrained) ──
App ◀── normalized JSON ── (Edge Function validates schema, strips extra fields)
```

- Shipping a Gemini key inside a public APK allows anyone to extract and abuse it; the Edge Function holds `GEMINI_API_KEY` as a Supabase secret and enforces auth (JWT verification) plus a per-user rate limit (e.g. 60 requests/day).
- Model ID is an Edge Function env var (`GEMINI_MODEL`, default `gemini-1.5-flash`) so it can be changed without an app release.
- One request **per screenshot** (3 requests, executed in parallel) keeps prompts small, isolates failures, and lets each slot retry independently.

### 8.2 App → Edge Function contract

`POST {SUPABASE_URL}/functions/v1/ocr-extract`

```json
{
  "expected_screen_type": "OUTBOUND_REPORT",
  "image_mime_type": "image/jpeg",
  "image_base64": "<...>",
  "client_request_id": "b3f1c2d0-...",
  "log_date": "2026-10-07"
}
```

Images are downscaled on-device to max 1600 px long edge, JPEG quality 85, before upload.

### 8.3 Edge Function → Gemini request

`POST https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`

```json
{
  "systemInstruction": { "parts": [{ "text": "<SYSTEM PROMPT §8.4>" }] },
  "contents": [{
    "role": "user",
    "parts": [
      { "inlineData": { "mimeType": "image/jpeg", "data": "<base64>" } },
      { "text": "<SCREEN-SPECIFIC PROMPT §8.5>" }
    ]
  }],
  "generationConfig": {
    "temperature": 0,
    "topP": 1,
    "maxOutputTokens": 1024,
    "responseMimeType": "application/json",
    "responseSchema": "<SCHEMA §8.6 for the expected screen type>"
  }
}
```

### 8.4 System prompt (shared)

```text
You are a precise data-extraction engine for courier operations screenshots.
You read numbers from a single mobile-app screenshot and return ONLY JSON that
matches the provided response schema.

Rules:
1. Never guess. If a value is not clearly visible, set it to null and add the
   field name to "unreadable_fields".
2. Quantities are non-negative integers. Remove thousands separators.
   Convert Burmese digits (၀၁၂၃၄၅၆၇၈၉) to ASCII digits.
3. NEVER extract money amounts, prices, fees, or anything with a currency
   symbol or "Amount"/"Amt"/"Ks"/"MMK" header. Ignore them completely.
4. First classify the screenshot into exactly one of:
   DASHBOARD_OVERVIEW, OUTBOUND_REPORT, INBOUND_REPORT, UNKNOWN.
   If it is not the expected type, still return the classification and set
   all extraction fields to null.
5. Waybill type labels map to: BP, E, C, CC, I. Any other row label maps to
   OTHER (sum multiple such rows). If a type row is absent, return 0 for it.
   Ignore "Total"/"Grand total" rows (do not double-count them), but report
   the visible total in "visible_total" for cross-checking when present.
6. If a report date is visible, return it as YYYY-MM-DD in "report_date",
   else null.
7. "confidence" is your overall confidence (0.0–1.0) that every returned
   number is exactly what is printed on screen.
```

### 8.5 Screen-specific user prompts

**DASHBOARD_OVERVIEW**

```text
Expected screen: DASHBOARD_OVERVIEW (daily summary tiles/cards).
Extract:
- pickup_done   : the count labelled as completed/done pickups.
- delivery_done : the count labelled as completed/done deliveries.
Do NOT extract any "dropoff"/"drop-off"/"drop off" value, even if it looks
similar. Ignore pending, failed, returned, and any amount values.
```

**OUTBOUND_REPORT**

```text
Expected screen: OUTBOUND_REPORT (table of waybill types).
For each waybill type row (BP, E, C, CC, I, OTHER) extract only the
pickup waybill quantity column ("Pickup Waybill Qty" or equivalent count
column) into pickup_waybill_qty. Ignore every amount/currency column.
```

**INBOUND_REPORT**

```text
Expected screen: INBOUND_REPORT (table of waybill types).
For each waybill type row (BP, E, C, CC, I, OTHER) extract:
- completed_qty    : completed/delivered quantity column
- cash_collect_qty : cash-collect (COD) quantity column
Ignore every amount/currency column (e.g. COD amount, total amount).
```

### 8.6 JSON output schemas

Gemini `responseSchema` uses the OpenAPI-subset format (`type` in upper case, `nullable`, `enum`, `required`). The Edge Function re-validates the result before returning it to the app.

**Common envelope** (all screen types):

| Field | Type | Notes |
|---|---|---|
| `detected_screen_type` | enum `DASHBOARD_OVERVIEW \| OUTBOUND_REPORT \| INBOUND_REPORT \| UNKNOWN` | Required |
| `report_date` | string (YYYY-MM-DD) \| null | |
| `confidence` | number 0–1 | Required |
| `unreadable_fields` | string[] | Required (may be empty) |
| `data` | object \| null | Screen-specific, see below |

**DASHBOARD_OVERVIEW schema**

```json
{
  "type": "OBJECT",
  "properties": {
    "detected_screen_type": { "type": "STRING", "enum": ["DASHBOARD_OVERVIEW", "OUTBOUND_REPORT", "INBOUND_REPORT", "UNKNOWN"] },
    "report_date":          { "type": "STRING", "nullable": true },
    "confidence":           { "type": "NUMBER" },
    "unreadable_fields":    { "type": "ARRAY", "items": { "type": "STRING" } },
    "data": {
      "type": "OBJECT",
      "nullable": true,
      "properties": {
        "pickup_done":   { "type": "INTEGER", "nullable": true },
        "delivery_done": { "type": "INTEGER", "nullable": true }
      },
      "required": ["pickup_done", "delivery_done"]
    }
  },
  "required": ["detected_screen_type", "confidence", "unreadable_fields", "data"]
}
```

Example output:

```json
{
  "detected_screen_type": "DASHBOARD_OVERVIEW",
  "report_date": "2026-10-07",
  "confidence": 0.97,
  "unreadable_fields": [],
  "data": { "pickup_done": 10, "delivery_done": 40 }
}
```

**OUTBOUND_REPORT schema**

```json
{
  "type": "OBJECT",
  "properties": {
    "detected_screen_type": { "type": "STRING", "enum": ["DASHBOARD_OVERVIEW", "OUTBOUND_REPORT", "INBOUND_REPORT", "UNKNOWN"] },
    "report_date":          { "type": "STRING", "nullable": true },
    "confidence":           { "type": "NUMBER" },
    "unreadable_fields":    { "type": "ARRAY", "items": { "type": "STRING" } },
    "data": {
      "type": "OBJECT",
      "nullable": true,
      "properties": {
        "rows": {
          "type": "ARRAY",
          "items": {
            "type": "OBJECT",
            "properties": {
              "waybill_type":       { "type": "STRING", "enum": ["BP", "E", "C", "CC", "I", "OTHER"] },
              "pickup_waybill_qty": { "type": "INTEGER", "nullable": true }
            },
            "required": ["waybill_type", "pickup_waybill_qty"]
          }
        },
        "visible_total": { "type": "INTEGER", "nullable": true }
      },
      "required": ["rows"]
    }
  },
  "required": ["detected_screen_type", "confidence", "unreadable_fields", "data"]
}
```

Example output:

```json
{
  "detected_screen_type": "OUTBOUND_REPORT",
  "report_date": "2026-10-07",
  "confidence": 0.93,
  "unreadable_fields": [],
  "data": {
    "rows": [
      { "waybill_type": "BP", "pickup_waybill_qty": 12 },
      { "waybill_type": "E", "pickup_waybill_qty": 5 },
      { "waybill_type": "C", "pickup_waybill_qty": 3 },
      { "waybill_type": "CC", "pickup_waybill_qty": 2 },
      { "waybill_type": "I", "pickup_waybill_qty": 1 },
      { "waybill_type": "OTHER", "pickup_waybill_qty": 2 }
    ],
    "visible_total": 25
  }
}
```

**INBOUND_REPORT schema**

```json
{
  "type": "OBJECT",
  "properties": {
    "detected_screen_type": { "type": "STRING", "enum": ["DASHBOARD_OVERVIEW", "OUTBOUND_REPORT", "INBOUND_REPORT", "UNKNOWN"] },
    "report_date":          { "type": "STRING", "nullable": true },
    "confidence":           { "type": "NUMBER" },
    "unreadable_fields":    { "type": "ARRAY", "items": { "type": "STRING" } },
    "data": {
      "type": "OBJECT",
      "nullable": true,
      "properties": {
        "rows": {
          "type": "ARRAY",
          "items": {
            "type": "OBJECT",
            "properties": {
              "waybill_type":     { "type": "STRING", "enum": ["BP", "E", "C", "CC", "I", "OTHER"] },
              "completed_qty":    { "type": "INTEGER", "nullable": true },
              "cash_collect_qty": { "type": "INTEGER", "nullable": true }
            },
            "required": ["waybill_type", "completed_qty", "cash_collect_qty"]
          }
        },
        "visible_completed_total":    { "type": "INTEGER", "nullable": true },
        "visible_cash_collect_total": { "type": "INTEGER", "nullable": true }
      },
      "required": ["rows"]
    }
  },
  "required": ["detected_screen_type", "confidence", "unreadable_fields", "data"]
}
```

Example output:

```json
{
  "detected_screen_type": "INBOUND_REPORT",
  "report_date": "2026-10-07",
  "confidence": 0.91,
  "unreadable_fields": [],
  "data": {
    "rows": [
      { "waybill_type": "BP", "completed_qty": 20, "cash_collect_qty": 15 },
      { "waybill_type": "E", "completed_qty": 8, "cash_collect_qty": 6 },
      { "waybill_type": "C", "completed_qty": 4, "cash_collect_qty": 2 },
      { "waybill_type": "CC", "completed_qty": 3, "cash_collect_qty": 3 },
      { "waybill_type": "I", "completed_qty": 2, "cash_collect_qty": 0 },
      { "waybill_type": "OTHER", "completed_qty": 1, "cash_collect_qty": 1 }
    ],
    "visible_completed_total": 38,
    "visible_cash_collect_total": 27
  }
}
```

### 8.7 Post-processing (Edge Function + app)

1. Reject if JSON fails schema validation → `OCR_INVALID_RESPONSE` (app retries once).
2. Merge duplicate `waybill_type` rows by summing; fill missing types with `0`.
3. Compare row sum to `visible_total*` when present → add warning `TOTAL_MISMATCH` on discrepancy.
4. Clamp nothing silently — any negative or non-integer value → field marked unreadable.
5. Return to app with `ocr_model` and `latency_ms` for diagnostics.

### 8.8 Error mapping

| Condition | `DomainError` | UX |
|---|---|---|
| Wrong screen in slot | `WrongScreenType(detected)` | Slot error with detected type |
| `UNKNOWN` screen | `UnrecognizedScreenshot` | "Couldn't recognize this screenshot" |
| Low confidence / unreadable fields | `LowConfidence(fields)` | Proceed; fields highlighted on Review |
| 401 from function | `SessionExpired` | Force re-login |
| 429 | `RateLimited` | "Try again later" with retry-after |
| Network / 5xx | `Network` / `Server` | Retry button |

---

## 9. Security, Privacy & Compliance

- **Session storage:** custom `EncryptedSessionManager` implements supabase-kt's `SessionManager`, serializing `UserSession` to DataStore encrypted with a Tink AEAD key wrapped by Android Keystore. Cleared on sign-out.
- **Secrets:** only the Supabase **anon** key ships in the APK (safe by design with RLS). Gemini key and service-role key live only in Supabase function secrets.
- **RLS everywhere:** every table has owner-only policies (§7.2); tested with an integration test using two users.
- **Screenshot privacy:** screenshots may contain customer names/addresses. They are processed in memory, sent only to the OCR function, **never stored** server-side, and deleted from app cache after extraction. The prompt instructs the model to return only counts.
- **Transport:** HTTPS only (`usesCleartextTraffic=false`); network security config without user CAs in release.
- **Logging:** no tokens, images or extracted values in release logs.
- **Play Store:** Data Safety form declares email (account), app activity (daily counts), and photos processed ephemerally; in-app **account deletion** (required by Play policy) and a privacy policy URL.
- **Trademark hygiene:** UI copy refers to generic "Dashboard / Outbound / Inbound report"; no operator branding.

---

## 10. Testing Strategy

| Layer | Tooling | Must cover |
|---|---|---|
| Domain | JUnit4 + Truth | `DailyResultCalculator` (REX worked example, custom profiles, excluded types, zero inputs, large values), `ExtractionValidator` (all §3.2 checks), preset integrity |
| ViewModels | coroutines-test + Turbine + MockK | State transitions per `UiEvent`, effects emitted once |
| Data | Room in-memory DB, MockWebServer for `OcrApi`, mappers | DTO ↔ domain round-trip, sync conflict resolution |
| UI | Compose UI test | Capture slot states, Review editing recalculates, Settings mode toggle |
| Architecture | Konsist (or ArchUnit) | `domain` has no Android/data imports; ViewModels don't depend on `data` |
| OCR quality | Golden-set script (Edge Function) | ≥ 30 anonymized sample screenshots per type; target ≥ 98% field-level exact-match |
| Backend | Supabase CLI local stack + pgTAP / SQL tests | RLS isolation, `handle_new_user` trigger, constraints |

CI (GitHub Actions): `./gradlew lint ktlintCheck detekt testDebugUnitTest assembleDebug` on every PR; Supabase migrations validated with `supabase db lint`.

---

## 11. Phase 1 Implementation Roadmap

Goal of Phase 1: **an installable MVP** where a user signs up once, is auto-logged-in afterwards, uploads 3 screenshots, reviews extracted values, and sees/saves KPI + commission in REX or Custom mode, with history synced to Supabase.

| Milestone | Scope | Deliverables | Exit criteria |
|---|---|---|---|
| **M0 — Project foundation** | Gradle setup, version catalog, Hilt, Compose theme, navigation skeleton, CI, ktlint/detekt | Empty screens wired through `TargetXNavHost`; GitHub Actions green | `assembleDebug` + unit tests pass in CI |
| **M1 — Domain core** | Models, `Presets` (REX), `DailyResultCalculator`, `ExtractionValidator`, use case interfaces | 100% unit-test coverage of calculator & validator | Worked example in §3.3 reproduces exactly |
| **M2 — Supabase backend** | Migration (§7.2), RLS, sign-up trigger, `ocr-extract` and `delete-account` Edge Functions | `supabase/` folder; local stack scripts | RLS tests pass; function returns schema-valid JSON for sample images |
| **M3 — Auth & auto-login** | supabase-kt client, `EncryptedSessionManager`, Splash gate, Sign in/up, sign-out | Auth screens + `AuthRepositoryImpl` | Kill & relaunch app → lands on Home without login (US-01) |
| **M4 — OCR capture flow** | Photo Picker, compression, hashing, `OcrApi` (Retrofit/OkHttp), parallel extraction, slot UI states | Capture screen end-to-end | Wrong-slot detection works (US-03); p90 ≤ 15 s (US-02) |
| **M5 — Review, result & persistence** | Review screen with editing + warnings, Result screen, Room DB, `DailyLogSyncWorker`, upsert | Daily log saved locally and in Supabase | Offline save syncs later (US-06); re-upload replaces (US-07) |
| **M6 — Settings & Custom mode** | Settings screen, profile editing/validation, reset to preset, profile snapshot on save | `SettingsRepositoryImpl` (DataStore cache + Supabase) | Changing rates doesn't alter old logs (US-05) |
| **M7 — History & polish** | History list, monthly summary, empty/error states, localization (en/my), accessibility pass | History screen | Accessibility checks pass; all strings localized |
| **M8 — Release readiness** | R8, privacy policy, Data Safety, account deletion, crash-free smoke test on 3 devices, internal testing track | Signed AAB on Play internal track | US-01 … US-08 verified on release build |

### 11.1 Phase 1 definition of done

- All user stories US-01 … US-08 pass on a release build.
- Domain layer unit-test coverage ≥ 90%; calculator & validator 100%.
- OCR golden-set field accuracy ≥ 98% with the configured model.
- No Gemini or service-role key present in the APK (verified by `apkanalyzer`/strings scan in CI).
- RLS isolation test passes (user A cannot read/write user B's rows).

### 11.2 Phase 2 candidates (out of scope)

- Per-waybill-type commission rates in Custom Mode
- Additional built-in presets; remotely-delivered presets
- Weekly/monthly targets and progress notifications
- Export (CSV/PDF) of history
- Home-screen widget showing today's totals
- On-device OCR fallback (ML Kit) for offline extraction

---

## 12. Open Questions & Risks

| # | Item | Impact | Proposed handling |
|---|---|---|---|
| R1 | **Gemini 1.5 Flash lifecycle.** Google has published retirement dates for the Gemini 1.5 model family; verify the model is still served before implementation. | OCR outage if model is retired | Model ID is server-side config (`GEMINI_MODEL`); validate the golden set against the current Flash model (e.g. a newer `gemini-*-flash`) and switch without an app release |
| R2 | Operator apps change their UI/labels | Extraction accuracy drops | Prompts describe semantics, not pixel positions; golden-set regression run on prompt/model change; human-in-the-loop Review |
| R3 | `delivery_done` (dashboard) vs inbound `completed_qty` may legitimately differ | User confusion | Shown as a non-blocking warning; formulas use the sources defined in §3.3 |
| R4 | Screenshots contain customer PII | Privacy/legal | Ephemeral processing, no server storage, documented in privacy policy |
| R5 | Abuse of OCR endpoint (cost) | Unbounded Gemini spend | JWT-required function, per-user daily quota, Google Cloud budget alerts |
| R6 | Timezone of `log_date` | Wrong day attribution near midnight | `log_date` chosen explicitly by the user (defaults to device-local today) |
| Q1 | Should `cash_collect_qty` ever affect commission? | Formula scope | Stored now; can be added as a Custom-mode coefficient in Phase 2 |
| Q2 | Sign-in methods beyond email/password (Google, phone OTP)? | Auth scope | Email/password in Phase 1; Google Sign-In via Credential Manager as Phase 2 candidate |
