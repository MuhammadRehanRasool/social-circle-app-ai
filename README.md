# SangatAI

**SangatAI** (*sangat*: the company you keep) maps the real people in your life from what you say about them. Talk about your day by voice or text. Gemini Nano, the copy already on your phone, picks out every person you mention, who they are to you, and how they know each other. The home screen is your circle drawn as a live network graph. The app has no INTERNET permission.

## How it flows

1. **Tell.** Tap **Tell** (voice) or **+** (type), or use the Quick Settings tile, the home-screen widget or a launcher shortcut. Say something like *"Had chai with my cousin Bilal, his wife Hina just joined Sara's company."* Voice runs through the phone's on-device speech recognizer.
2. **Map.** What you said is saved first, then Gemini Nano reads it into people (Bilal, Hina, Sara), their relation to you (Bilal: cousin), links between them (Bilal ↔ Hina: married) and short tags (`new_job`). A card shows who was found, and who is new. If Nano isn't ready, the text waits in a queue and is read the next time the app is open.
3. **Circle.** You sit in the middle of a force-directed graph. The more often and recently someone comes up, the closer they sit to you. Lines join people who are related or who you name together. Colors show the circle they're in: family, friends, work, others, or not sure yet. Chips filter by circle. Tap a person to highlight their ties, tap again to open them. Drag people around, pan, pinch to zoom.
4. **People.** Each profile has their relation to you, your tags, their ties (tap to jump from person to person), and a timeline of everything you said about them. Everything is editable: rename, set the relation, add or remove tags, label a tie ("siblings"), link two people by hand, merge duplicates, forget one entry, delete.
5. **Who can help?** The search button finds people by their tags (on-device embeddings), then Gemini Nano explains the best matches.
6. **What you said.** Every capture exactly as said, with who it was saved to. Retry failed ones; delete one and everything it added (tags, relations, ties, and people only it mentioned) goes with it.

## Privacy and your data

- **No network access.** ML Kit and MediaPipe both depend on Google's `datatransport` library, which adds `INTERNET` to the APK so it can upload usage metrics. The manifest removes that permission (`tools:node="remove"`), so Android refuses the app any socket. CI reads the permissions of the built APK and fails the build if network access ever comes back. In release builds R8 also strips MediaPipe's metrics call.
- **No backups or transfers.** `allowBackup="false"` plus `data_extraction_rules.xml` exclude everything from cloud backup and from device-to-device transfer.
- **No hidden data.** The app ships no seed data. The only binary besides code is the embedding model, `app/src/main/assets/universal_sentence_encoder.tflite`, and the build checks its SHA-256. Settings → What's stored lists every file the app and its libraries keep.
- **Export everything.** Settings → Export everything writes one JSON file with every person, tag history, relation, rant, interaction, connection, leftover old note, and every setting including edited prompts.
- **Delete for real.** The database runs with `secure_delete`, so deleted rows are overwritten. Deleting a rant takes back the tags and connections it created, plus any people that existed only because of it. Deleting a person can also delete every rant that named them. Wipe empties the database, compacts the file, and deletes the metrics ML Kit queued for upload.
- **Everything is tunable.** Both prompts, the sampling, tag caps and decay, and every retrieval weight are in `CrmTuning`, persisted and resettable. `AICoreManager.lastTrace` holds the last prompt and the raw Gemini Nano answer in memory, for tuning.

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
