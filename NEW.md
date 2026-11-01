Here's my estimate for each of the 9, based on what the code has today. Days assume one person working with me, and every build has to go through CI because there's no local build.

┌─────┬───────────────┬─────────────┬───────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  #  │    Feature    │ Complexity  │ Rough effort  │                                                   What makes it that size                                                   │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 9   │ Parser unit   │ 🟢 Low      │ ½ day         │ The parsers live inside AICoreManager, which needs AiSettings and a Context. Moving them into a plain object makes them     │
│     │ tests         │             │               │ easy to test. There's no src/test folder yet, so the test dependencies need adding too.                                     │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 6   │ Pin / archive │ 🟢 Low      │ ½–1 day       │ Two columns, a Room migration (4→5), a pinned section at the top of Days, and an archive filter.                            │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ To-do list    │ 🟢→🟡       │ 1 day, +1–2   │ Markdown.kt already draws - [ ] checkboxes, but you can't tick them. Gathering them into one list is easy. Ticking one      │
│ 3   │ across notes  │ Low–Med     │ with          │ means rewriting that line in the note body, and a later refine can move or reword the line. Reminders add                   │
│     │               │             │ reminders     │ POST_NOTIFICATIONS, the exact-alarm rules and an alarm receiver.                                                            │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ Biometric     │             │               │ Needs the androidx.biometric library. BiometricPrompt only works in a FragmentActivity, and MainActivity and                │
│ 8   │ lock          │ 🟡 Low–Med  │ 1 day         │ CaptureActivity are both plain ComponentActivity. You'd also decide whether the tile or widget capture gets locked; I'd     │
│     │               │             │               │ keep capture open and lock only reading.                                                                                    │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ Mood over     │             │               │ The cheap way is a Mood: line in the note prompt, read back the same way as Category:. That costs no extra Nano call, just  │
│ 4   │ time          │ 🟡 Medium   │ 1–2 days      │ a column and a migration. Risk: nano-v3 already struggles with the format, and one more required line raises the failure    │
│     │               │             │               │ rate. The chart is simple.                                                                                                  │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ Encrypted     │             │               │ Saving to a file already exists (saveExport). Encryption with a password needs no new library. The hard part is restore:    │
│ 7   │ backup /      │ 🟡 Medium   │ 2–3 days      │ merge or replace, IDs that already exist, backups from older database versions, and the persona's preferences. Bugs here    │
│     │ restore       │             │               │ lose data, so it needs careful testing.                                                                                     │
├─────┼───────────────┼─────────────┼───────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │               │             │               │ Nano can't run in the background, so there's no scheduled job; the recap has to be built the first time you open the app    │
│ 2   │ Weekly recap  │ 🟡          │ 2–3 days      │ after Sunday. A week of notes won't fit in Nano's input, so it needs a two-step summary: summarize each note, then combine  │
│     │               │ Medium–High │               │ them. That's several Nano calls, each of which can fail and be retried. The output also needs a place to be stored, a new   │
│     │               │             │               │ table or SharedPreferences.                                                                                                 │
├─────┼───────────────┼─────────────┼───────────────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ Link related  │             │               │ ML Kit GenAI has no embeddings, so "related" means keyword overlap through a full-text search table (another migration).    │
│ 5   │ dumps         │ 🔴 High     │ 3–4 days      │ Merging is the harder  cap cuts merged notes off, so long merges come out incomplete. It also  │
│     │               │             │               │ needs a decent UI for "these 3 are similar, merge?".                                                                        │
├─────┼───────────────┼─────────────┼───────────────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │               │             │               │ It's the most useful feature and also the hardest. It needs a full-text search table plus a migration to find matching      │
│ 1   │ Ask your      │ 🔴 High     │ 4–5 days      │ notes, a step that fit's small input, and a chat screen (RefineSheet can be reused). It also   │
│     │ dumps         │             │               │ has to cite which notes the answer came from. Small models make things up when the notes don't contain the answer, so it    │
│     │               │             │               │ needs a "not in your ns tuning on a real phone.                                                │
└─────┴───────────────┴─────────────┴───────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Shared cost: items 4, 5, 6 and 1 each add a Room migration. Doing them together in one 4→5 migration saves repeated work and testing.

Suggested order:
1. Tests first (#9), because everything AI-related depends on the parsers.
2. Then the quick wins: pin/archive and the to-do list (#6, #3).
3. Then backup (#7), because it protects your data before the bigger schema
4. Then "Ask your dumps" (#1) as the main feature.
5. Mood and the weekly recap fit well after that, since both reuse the perso