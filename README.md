# dumpd

**BrainDump.** Dump rough thoughts by voice or text in one tap. Gemini Nano, the copy that's already on your phone, rewrites them as a clear, polished Markdown note with a title and a category. The app has no INTERNET permission.

## How it flows

1. **Capture.** Use the Quick Settings tile, the home-screen widget (resizes from a single mic button up to a 2x2 card), a launcher shortcut (long-press the icon), or the Dump and mic buttons. Voice runs through the phone's on-device speech recognizer and fills the text box live.
2. **Polish.** The dump goes into Room, then straight to Gemini Nano via the ML Kit GenAI Prompt API while the sheet is still open. Then the sheet closes and a toast shows the title. If the active Nano build returns nothing, the other builds AICore has installed are tried, and the one that works is used from then on.
3. **Read and refine.** The dashboard groups notes by day. Tap a card to read the whole note, chat with Gemini Nano to change it, or share it as Markdown. Settings has export, shortcuts, and every prompt and limit the model runs with.
4. **Persona.** After 3 dumps, Gemini Nano reads your own raw words (never its notes) and sums up how you write: voice, tone, mood, structure, detail and focus. That short card, not your other dumps, goes along with each new dump so notes sound like you. It's re-learned every 5 dumps; the "Your persona" card on Home shows it with a few stats and lets you turn it off, re-learn it, or forget it.

Gemini Nano only answers apps in the foreground. If you swipe the sheet away mid-polish, or the model isn't ready, the dump is kept and polished the next time you open the app. Failed ideas get a Retry button.

## Privacy and your data

- **No network access.** ML Kit and MediaPipe both depend on Google's `datatransport` library, which adds `INTERNET` to the APK so it can upload usage metrics. The manifest removes that permission (`tools:node="remove"`), so Android refuses the app any socket. CI reads the permissions of the built APK and fails the build if network access ever comes back. In release builds R8 also strips MediaPipe's metrics call.
- **No backups or transfers.** `allowBackup="false"` plus `data_extraction_rules.xml` exclude everything from cloud backup and from device-to-device transfer.
- **No hidden data.** The app ships no seed data. The only binary besides code is the embedding model, `app/src/main/assets/universal_sentence_encoder.tflite`, and the build checks its SHA-256. `AppDataManager.inventory()` lists every file the app and its libraries keep.
- **Export everything.** `AppDataManager.exportJson()` writes one JSON file containing every person, tag history, rant, interaction, connection, note, the persona, and every setting including edited prompts.
- **Delete for real.** The database runs with `secure_delete`, so deleted rows are overwritten. Deleting a rant takes back the tags and connections it created, plus any people that existed only because of it. Deleting a person can also delete every rant that named them. Wipe empties the database, compacts the file, and deletes the metrics ML Kit queued for upload.
- **Everything is tunable.** Both social-graph prompts, the sampling, tag caps and decay, and every retrieval weight are in `CrmTuning`, persisted and resettable. `AICoreManager.lastTrace` holds the last prompt and the raw Gemini Nano answer in memory, for tuning.

What the app can't control: Gemini Nano runs inside AICore, a Google system service, and the app can't see what that service does. Google says Gemini Nano inference happens on the device. To check for yourself, run a network monitor such as PCAPdroid.

## Device requirements

- Android 12+ (SDK 31), arm64.
- A phone with Gemini Nano in AICore (Pixel 9/10 series and other supported devices), with AICore and Private Compute Services updated from the Play Store.
- Voice: an on-device speech pack for your language. Packs exist per region (en-US, not en-PK), so the app uses an installed pack in your language before asking the system to download one.

## CI

`.github/workflows/android-build.yml` runs on every push to `main` and uploads an artifact named after the repo:

- `<repo>.apk`: R8-optimized release build. Install this one.
- `<repo>-debug.apk`: unminified debug build, for troubleshooting.

Optional signing secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without the secrets, the release APK is signed with a CI debug key. That key is cached, so later builds install over earlier ones.
