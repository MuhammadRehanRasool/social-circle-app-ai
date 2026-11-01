package dev.braindump.splitter.data.repository

import androidx.room.withTransaction
import dev.braindump.splitter.data.ai.AICoreManager
import dev.braindump.splitter.data.ai.CrmParsers
import dev.braindump.splitter.data.ai.CrmSettings
import dev.braindump.splitter.data.ai.CrmTuning
import dev.braindump.splitter.data.ai.SplitException
import dev.braindump.splitter.data.ai.embedding.PersonVectorIndex
import dev.braindump.splitter.data.ai.embedding.TextEmbeddingEngine
import dev.braindump.splitter.data.crm.NameKey
import dev.braindump.splitter.data.crm.TagScoring
import dev.braindump.splitter.data.local.BrainDumpDatabase
import dev.braindump.splitter.data.local.crm.CaptureDao
import dev.braindump.splitter.data.local.crm.CaptureEntity
import dev.braindump.splitter.data.local.crm.ConnectionDao
import dev.braindump.splitter.data.local.crm.ConnectionEntity
import dev.braindump.splitter.data.local.crm.ConnectionRow
import dev.braindump.splitter.data.local.crm.InteractionLogDao
import dev.braindump.splitter.data.local.crm.InteractionLogEntity
import dev.braindump.splitter.data.local.crm.PersonDao
import dev.braindump.splitter.data.local.crm.PersonEntity
import dev.braindump.splitter.data.local.crm.PersonRow
import dev.braindump.splitter.data.local.crm.Relation
import dev.braindump.splitter.data.local.crm.StringSet
import dev.braindump.splitter.data.local.crm.TagMap
import dev.braindump.splitter.data.local.crm.TagStat
import dev.braindump.splitter.domain.model.AskOutcome
import dev.braindump.splitter.domain.model.Capture
import dev.braindump.splitter.domain.model.CaptureStatus
import dev.braindump.splitter.domain.model.Connection
import dev.braindump.splitter.domain.model.IngestOutcome
import dev.braindump.splitter.domain.model.Interaction
import dev.braindump.splitter.domain.model.Person
import dev.braindump.splitter.domain.model.PersonDetail
import dev.braindump.splitter.domain.model.PersonMatch
import dev.braindump.splitter.domain.model.PersonTag
import dev.braindump.splitter.domain.repository.PeopleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeopleRepositoryImpl @Inject constructor(
    private val db: BrainDumpDatabase,
    private val people: PersonDao,
    private val logs: InteractionLogDao,
    private val edges: ConnectionDao,
    private val captures: CaptureDao,
    private val ai: AICoreManager,
    private val embedder: TextEmbeddingEngine,
    private val index: PersonVectorIndex,
    private val settings: CrmSettings,
) : PeopleRepository {

    /** One drain at a time; held across Gemini Nano calls. */
    private val drainMutex = Mutex()

    /** Every write to the graph, so the vector index is updated in the same order as the database. */
    private val graphMutex = Mutex()

    private val tuning: CrmTuning get() = settings.tuning.value

    private fun now() = System.currentTimeMillis()

    // ---------------------------------------------------------------- reading

    override fun observePeople(): Flow<List<Person>> =
        people.observeAll().map { rows -> rows.map { it.toDomain(now(), tuning) } }

    override fun observeRecentPeople(limit: Int): Flow<List<Person>> =
        people.observeRecent(limit).map { rows -> rows.map { it.toDomain(now(), tuning) } }

    override fun observePerson(personId: Long): Flow<PersonDetail?> =
        combine(
            people.observe(personId),
            logs.observeForPerson(personId),
            edges.observeForPerson(personId),
        ) { row, interactions, connections ->
            row?.let {
                PersonDetail(
                    person = it.toDomain(now(), tuning),
                    interactions = interactions.map { log -> log.toDomain() },
                    connections = connections.map { edge -> edge.toDomain() },
                )
            }
        }

    override fun observeCaptures(): Flow<List<Capture>> =
        captures.observeAll().map { rows -> rows.map { it.toDomain() } }

    // ---------------------------------------------------------------- ingestion

    override suspend fun ingest(rawText: String): IngestOutcome {
        val text = rawText.trim()
        if (text.isEmpty()) return IngestOutcome.Empty
        val at = now()
        // Saved before Gemini Nano runs: a crash, a swipe or a busy model never loses the rant.
        val id = captures.insert(CaptureEntity(rawText = text, createdAt = at, updatedAt = at))
        drainPending()
        val capture = captures.get(id) ?: return IngestOutcome.Empty
        return when (capture.status) {
            CaptureStatus.DONE -> IngestOutcome.Saved(id, capture.summary.orEmpty(), capture.mentionCount)
            CaptureStatus.PENDING -> IngestOutcome.Queued(id, capture.lastError)
            CaptureStatus.FAILED -> IngestOutcome.Failed(id, capture.lastError)
        }
    }

    override suspend fun retryCapture(captureId: Long) {
        if (captures.resetFailed(captureId, now()) > 0) drainPending()
    }

    override suspend fun drainPending() {
        drainMutex.withLock { drainLocked() }
    }

    private suspend fun drainLocked() {
        val handled = HashSet<Long>()
        // Re-query after each pass so rants captured mid-run are picked up too.
        var batch = captures.pendingIds()
        while (batch.isNotEmpty()) {
            for (id in batch) {
                handled += id
                val capture = captures.get(id)?.takeIf { it.status == CaptureStatus.PENDING } ?: continue
                val crm = tuning
                try {
                    // Gemini Nano runs outside any transaction; it can take seconds.
                    val extraction = ai.extractMentions(capture.rawText, crm)
                    graphMutex.withLock {
                        val ops = db.withTransaction { applyExtraction(capture, extraction, crm) }
                        applyIndex(ops)
                    }
                } catch (e: SplitException.Deferred) {
                    // Every other queued rant would hit the same wall; wait for the next foreground.
                    captures.update(capture.copy(lastError = e.message, updatedAt = now()))
                    return
                } catch (e: SplitException) {
                    val attempts = capture.attempts + 1
                    val giveUp = !e.retryable || attempts >= MAX_ATTEMPTS
                    captures.update(
                        capture.copy(
                            status = if (giveUp) CaptureStatus.FAILED else CaptureStatus.PENDING,
                            attempts = attempts,
                            lastError = e.message,
                            updatedAt = now(),
                        )
                    )
                }
            }
            batch = captures.pendingIds().filterNot { it in handled }
        }
    }

    /**
     * Writes one rant into the graph. Runs inside a transaction, so a rant is applied entirely
     * or not at all. Returns the vector-index changes to make once it has committed.
     */
    private suspend fun applyExtraction(
        capture: CaptureEntity,
        extraction: CrmParsers.Extraction,
        crm: CrmTuning,
    ): List<IndexOp> {
        // The time the user spoke, so a rant processed later isn't post-dated.
        val at = capture.createdAt
        val ops = ArrayList<IndexOp>()
        // person id -> (interaction id, tags it contributed)
        val logged = LinkedHashMap<Long, Pair<Long, Set<String>>>()

        for (mention in extraction.mentioned) {
            val existing = resolvePerson(mention.name)
            val stamp = now()
            val base = existing ?: PersonEntity(
                name = mention.name,
                nameKey = NameKey.of(mention.name),
                createdAt = stamp,
                updatedAt = stamp,
                lastInteractionAt = at,
            )
            val name = if (base.nameLocked) base.name else NameKey.longerName(base.name, mention.name)
            val key = NameKey.of(name)
            val contributed = mention.tags.filterNot { it in base.suppressedTags }.toSet()

            val merged = base.copy(
                name = name,
                nameKey = key,
                // Every key this person has gone by, so "Ali" still resolves after becoming "Ali Khan".
                aliases = aliasesOf(base.aliases.values + NameKey.of(mention.name) + base.nameKey, key),
                dynamicTags = TagScoring.merge(
                    current = base.dynamicTags,
                    incoming = contributed,
                    at = at,
                    suppressed = base.suppressedTags,
                    maxTags = crm.maxTagsPerPerson,
                    halfLifeDays = crm.tagHalfLifeDays.toDouble(),
                ),
                updatedAt = stamp,
                lastInteractionAt = maxOf(base.lastInteractionAt, at),
            )
            val (row, op) = withEmbedding(merged, crm, stamp)
            val personId = if (existing == null) people.insert(row) else row.id.also { people.update(row) }
            ops += op.copy(personId = personId)

            val previous = logged[personId]
            if (previous != null) {
                // Two names in this rant were the same person: one interaction, both names' tags.
                val union = previous.second + contributed
                logs.setTagsAdded(previous.first, StringSet(union))
                logged[personId] = previous.first to union
                continue
            }
            val logId = logs.insert(
                InteractionLogEntity(
                    personId = personId,
                    captureId = capture.id,
                    timestamp = at,
                    rawText = capture.rawText,
                    nanoSummary = extraction.summary.ifEmpty { null },
                    tagsAdded = StringSet(contributed),
                )
            )
            logged[personId] = logId to contributed
        }

        // Before the edges: the co-mention query skips rants naming more than maxCoMentionClique people.
        captures.update(
            capture.copy(
                status = CaptureStatus.DONE,
                summary = extraction.summary,
                mentionCount = logged.size,
                lastError = null,
                updatedAt = now(),
            )
        )
        for (personId in logged.keys) rebuildEdges(personId, crm)
        return ops
    }

    /**
     * Conservative on purpose: a duplicate can be merged later, a wrong merge mixes up two people.
     * 1. Exact name or a known alias (the most recent if several).
     * 2. "Ali" matches the only person whose name starts with Ali; "Ali Khan" matches the only plain "Ali".
     * 3. Otherwise a new person.
     */
    private suspend fun resolvePerson(name: String): PersonEntity? {
        val key = NameKey.of(name)
        if (key.isEmpty()) return null
        people.byKeyOrAlias(key, "%\"$key\"%").firstOrNull()?.let { return it }
        val candidates = if (NameKey.wordCount(key) == 1) {
            people.byFirstWord(key, "$key %")
        } else {
            people.byKey(NameKey.firstWord(key))
        }
        return candidates.singleOrNull()
    }

    /** Edges of [personId] recomputed from interaction logs, keeping labels the user set. */
    private suspend fun rebuildEdges(personId: Long, crm: CrmTuning) {
        val coMentions = logs.coMentions(personId, crm.maxCoMentionClique).associateBy { it.otherId }
        val seen = HashSet<Long>()
        for (edge in edges.forPerson(personId)) {
            val other = if (edge.personA == personId) edge.personB else edge.personA
            seen += other
            val co = coMentions[other]
            val relation = edge.relation.copy(
                coMentions = co?.mentions ?: 0,
                lastAt = co?.lastAt ?: edge.relation.lastAt,
            )
            when {
                relation.coMentions == 0 && relation.labels.isEmpty() -> edges.deleteById(edge.edgeId)
                relation != edge.relation -> edges.update(edge.copy(relation = relation))
            }
        }
        for ((other, co) in coMentions) {
            if (other in seen || other == personId) continue
            edges.insert(
                ConnectionEntity(
                    personA = minOf(personId, other),
                    personB = maxOf(personId, other),
                    relation = Relation(coMentions = co.mentions, lastAt = co.lastAt),
                )
            )
        }
    }

    // ---------------------------------------------------------------- embeddings

    /** What to do to a person's row in the vector index after the transaction commits. */
    private data class IndexOp(val personId: Long, val vector: FloatArray?, val remove: Boolean)

    /**
     * A fresh vector for a row whose tags changed. No tags: no vector, but marked current so it
     * isn't retried. Embedder failed: the old vector stays and the row is marked stale.
     */
    private suspend fun withEmbedding(row: PersonEntity, crm: CrmTuning, now: Long): Pair<PersonEntity, IndexOp> {
        val text = TagScoring.profileText(row.dynamicTags, now, crm.embedTags, crm.tagHalfLifeDays.toDouble())
        if (text.isBlank()) {
            return row.copy(embedding = null, embeddingModel = embedder.modelId) to IndexOp(row.id, null, remove = true)
        }
        val vector = embedder.embed(text, asQuery = false)
            ?: return row.copy(embeddingModel = null) to IndexOp(row.id, null, remove = false)
        return row.copy(embedding = vector, embeddingModel = embedder.modelId) to IndexOp(row.id, vector, remove = false)
    }

    private suspend fun applyIndex(ops: List<IndexOp>) {
        for (op in ops) {
            when {
                op.remove -> index.remove(op.personId)
                op.vector != null -> index.put(op.personId, op.vector)
            }
        }
    }

    override suspend fun reembedStale(): Int = graphMutex.withLock { reembedStaleLocked() }

    override suspend fun reembedAll(): Int = graphMutex.withLock {
        people.clearEmbeddings()
        index.invalidate()
        reembedStaleLocked()
    }

    private suspend fun reembedStaleLocked(): Int {
        val crm = tuning
        var done = 0
        var afterId = 0L
        // Walks forward by id, so a row the embedder can't handle is skipped instead of retried forever.
        var batch = people.needingEmbedding(embedder.modelId, afterId, EMBED_BATCH)
        while (batch.isNotEmpty()) {
            for (person in batch) {
                afterId = person.id
                val (row, op) = withEmbedding(person, crm, now())
                if (row.embeddingModel == null) continue
                people.update(row.copy(updatedAt = person.updatedAt))
                applyIndex(listOf(op.copy(personId = person.id)))
                done++
            }
            batch = people.needingEmbedding(embedder.modelId, afterId, EMBED_BATCH)
        }
        return done
    }

    // ---------------------------------------------------------------- "who can help"

    override suspend fun askNetwork(query: String): AskOutcome {
        val crm = tuning
        val ask = query.trim().take(crm.maxQueryChars)
        if (ask.isEmpty()) return AskOutcome.Empty
        val queryVector = embedder.embed(ask, asQuery = true)
            ?: return AskOutcome.Unavailable("The on-device search model isn't available")

        // 1. Retrieve: nearest by cosine, then a small boost per tag word that's literally in the query.
        val now = now()
        val halfLife = crm.tagHalfLifeDays.toDouble()
        val queryWords = wordsOf(ask)
        val hits = index.search(queryVector, crm.retrievalPool).filter { it.second >= crm.minSimilarity }
        if (hits.isEmpty()) return AskOutcome.NoOne
        val rows = people.byIds(hits.map { it.first }).associateBy { it.id }
        val ranked = hits.mapNotNull { (id, cosine) ->
            val person = rows[id] ?: return@mapNotNull null
            val lexical = person.dynamicTags.tags.keys.count { tag -> tag.split('_').any { it in queryWords } }
            Triple(person, cosine, cosine + crm.lexicalBoost * minOf(lexical, 3))
        }.sortedByDescending { it.third }.take(crm.shortlistSize)
        if (ranked.isEmpty()) return AskOutcome.NoOne

        val shortlist = ranked.map { (person, cosine, _) ->
            PersonMatch(person.id, person.name, localReason(person, queryWords, now, halfLife), cosine, fromAi = false)
        }

        // 2. Reason: Gemini Nano picks and explains. Short ids map back locally, so it can't name anyone else.
        val byShortId = LinkedHashMap<String, Pair<PersonEntity, Float>>()
        ranked.forEachIndexed { i, (person, cosine, _) -> byShortId["P${i + 1}"] = person to cosine }
        val lines = buildString {
            for ((shortId, entry) in byShortId) {
                val line = candidateLine(shortId, entry.first, now, crm)
                if (length + line.length + 1 > crm.candidateBlockChars) break
                if (isNotEmpty()) append('\n')
                append(line)
            }
        }

        return try {
            val picks = ai.rankCandidates(ask, lines, byShortId.keys, crm)
            val matches = picks.mapNotNull { pick ->
                val (person, cosine) = byShortId[pick.shortId] ?: return@mapNotNull null
                PersonMatch(
                    personId = person.id,
                    name = person.name,
                    reason = pick.reason.ifBlank { localReason(person, queryWords, now, halfLife) },
                    similarity = cosine,
                    fromAi = pick.reason.isNotBlank(),
                )
            }
            if (matches.isEmpty()) AskOutcome.NoOne else AskOutcome.Matches(matches, shortlist)
        } catch (e: SplitException) {
            // No Gemini Nano right now: still answer, from retrieval alone.
            AskOutcome.Matches(
                matches = shortlist.take(crm.maxMatches),
                shortlist = shortlist,
                degraded = e.message ?: "Gemini Nano isn't available",
            )
        }
    }

    /** "P3 | Sara | car_expert (3, 2d), high_trust (1, 30d)" */
    private fun candidateLine(shortId: String, person: PersonEntity, now: Long, crm: CrmTuning): String {
        val tags = TagScoring.ranked(person.dynamicTags, now, crm.tagHalfLifeDays.toDouble())
            .take(crm.candidateTags)
            .joinToString(", ") { tag ->
                val stat = person.dynamicTags.tags.getValue(tag)
                "$tag (${stat.count}, ${daysBetween(stat.lastAt, now)}d)"
            }
        return "$shortId | ${person.name.replace('|', '/')} | ${tags.ifEmpty { "no tags" }}"
    }

    /** "Tagged car expert, high trust": the tags that share a word with the query, else the strongest. */
    private fun localReason(person: PersonEntity, queryWords: Set<String>, now: Long, halfLife: Double): String {
        val ranked = TagScoring.ranked(person.dynamicTags, now, halfLife)
        val matching = ranked.filter { tag -> tag.split('_').any { it in queryWords } }
        val shown = matching.ifEmpty { ranked }.take(3)
        return if (shown.isEmpty()) "No tags yet" else "Tagged " + shown.joinToString(", ") { it.replace('_', ' ') }
    }

    // ---------------------------------------------------------------- user edits

    override suspend fun addPerson(name: String, tags: List<String>): Long? {
        val clean = NameKey.clean(name)
        val key = NameKey.of(clean)
        if (key.isEmpty()) return null
        val crm = tuning
        val at = now()
        val userTags = tags.mapNotNull(TagScoring::normalize).distinct()
            .associateWith { TagStat(count = 1, firstAt = at, lastAt = at, byUser = true) }
        return graphMutex.withLock {
            val (row, op) = withEmbedding(
                PersonEntity(
                    name = clean,
                    nameKey = key,
                    dynamicTags = TagMap(userTags),
                    nameLocked = true,
                    createdByUser = true,
                    createdAt = at,
                    updatedAt = at,
                    lastInteractionAt = at,
                ),
                crm,
                at,
            )
            val id = people.insert(row)
            applyIndex(listOf(op.copy(personId = id)))
            id
        }
    }

    override suspend fun renamePerson(personId: Long, name: String) {
        val clean = NameKey.clean(name)
        val key = NameKey.of(clean)
        if (key.isEmpty()) return
        graphMutex.withLock {
            val person = people.get(personId) ?: return@withLock
            people.update(
                person.copy(
                    name = clean,
                    nameKey = key,
                    aliases = aliasesOf(person.aliases.values + person.nameKey, key),
                    nameLocked = true,
                    updatedAt = now(),
                )
            )
        }
    }

    override suspend fun addTag(personId: Long, tag: String): Boolean {
        val clean = TagScoring.normalize(tag) ?: return false
        editPerson(personId) { person, at ->
            val stat = person.dynamicTags.tags[clean]
            val updated = stat?.copy(byUser = true, lastAt = maxOf(stat.lastAt, at))
                ?: TagStat(count = 1, firstAt = at, lastAt = at, byUser = true)
            person.copy(
                dynamicTags = TagMap(person.dynamicTags.tags + (clean to updated)),
                suppressedTags = person.suppressedTags - clean,
            )
        }
        return true
    }

    override suspend fun removeTag(personId: Long, tag: String) {
        editPerson(personId) { person, _ ->
            person.copy(
                dynamicTags = TagMap(person.dynamicTags.tags - tag),
                suppressedTags = person.suppressedTags + tag,
            )
        }
    }

    override suspend fun unsuppressTag(personId: Long, tag: String) {
        graphMutex.withLock {
            val ops = db.withTransaction {
                val person = people.get(personId) ?: return@withTransaction emptyList<IndexOp>()
                people.update(person.copy(suppressedTags = person.suppressedTags - tag, updatedAt = now()))
                // Brings back whatever past rants said about this tag.
                listOfNotNull(rebuildPerson(personId, tuning))
            }
            applyIndex(ops)
        }
    }

    private suspend fun editPerson(personId: Long, change: (PersonEntity, Long) -> PersonEntity) {
        graphMutex.withLock {
            val at = now()
            val person = people.get(personId) ?: return@withLock
            val (row, op) = withEmbedding(change(person, at).copy(updatedAt = at), tuning, at)
            people.update(row)
            applyIndex(listOf(op.copy(personId = personId)))
        }
    }

    override suspend fun setConnectionLabels(personA: Long, personB: Long, labels: List<String>) {
        if (personA == personB) return
        val a = minOf(personA, personB)
        val b = maxOf(personA, personB)
        val clean = labels.map { it.trim().take(MAX_LABEL_CHARS) }.filter { it.isNotEmpty() }.distinct()
        graphMutex.withLock {
            db.withTransaction {
                if (people.get(a) == null || people.get(b) == null) return@withTransaction
                val edge = edges.get(a, b)
                when {
                    edge == null && clean.isNotEmpty() ->
                        edges.insert(ConnectionEntity(personA = a, personB = b, relation = Relation(labels = clean)))
                    edge == null -> Unit
                    edge.relation.coMentions == 0 && clean.isEmpty() -> edges.deleteById(edge.edgeId)
                    else -> edges.update(edge.copy(relation = edge.relation.copy(labels = clean)))
                }
                Unit
            }
        }
    }

    override suspend fun mergePeople(keepId: Long, dropId: Long) {
        if (keepId == dropId) return
        graphMutex.withLock {
            val ops = db.withTransaction {
                val keep = people.get(keepId) ?: return@withTransaction emptyList<IndexOp>()
                val drop = people.get(dropId) ?: return@withTransaction emptyList<IndexOp>()
                val crm = tuning
                val labeled = edges.forPerson(dropId).filter { it.relation.labels.isNotEmpty() }

                logs.reassign(dropId, keepId)
                // Tags are rebuilt from the combined interactions; the user's own tags from both are kept.
                val userTags = TagScoring.union(
                    TagMap(keep.dynamicTags.tags.filterValues { it.byUser }),
                    TagMap(drop.dynamicTags.tags.filterValues { it.byUser }),
                )
                people.update(
                    keep.copy(
                        aliases = aliasesOf(keep.aliases.values + drop.aliases.values + drop.nameKey, keep.nameKey),
                        dynamicTags = userTags,
                        suppressedTags = StringSet(keep.suppressedTags.values + drop.suppressedTags.values),
                        createdByUser = keep.createdByUser || drop.createdByUser,
                        createdAt = minOf(keep.createdAt, drop.createdAt),
                        lastInteractionAt = maxOf(keep.lastInteractionAt, drop.lastInteractionAt),
                        updatedAt = now(),
                    )
                )
                people.deleteById(dropId) // its own edges cascade away
                val op = rebuildPerson(keepId, crm, deleteIfOrphan = false)

                // Carry the user's labels over to the merged person.
                for (edge in labeled) {
                    val other = if (edge.personA == dropId) edge.personB else edge.personA
                    if (other == keepId) continue
                    val a = minOf(keepId, other)
                    val b = maxOf(keepId, other)
                    val target = edges.get(a, b)
                    if (target == null) {
                        edges.insert(ConnectionEntity(personA = a, personB = b, relation = Relation(labels = edge.relation.labels)))
                    } else {
                        val labels = (target.relation.labels + edge.relation.labels).distinct()
                        edges.update(target.copy(relation = target.relation.copy(labels = labels)))
                    }
                }
                listOfNotNull(op, IndexOp(dropId, null, remove = true))
            }
            applyIndex(ops)
        }
    }

    // ---------------------------------------------------------------- deleting

    override suspend fun deletePerson(personId: Long, alsoDeleteRants: Boolean) {
        graphMutex.withLock {
            val ops = db.withTransaction {
                val ops = ArrayList<IndexOp>()
                if (alsoDeleteRants) {
                    for (captureId in logs.captureIdsForPerson(personId)) {
                        ops += deleteCaptureLocked(captureId, skip = personId)
                    }
                }
                people.deleteById(personId)
                ops += IndexOp(personId, null, remove = true)
                ops
            }
            applyIndex(ops)
        }
    }

    override suspend fun deleteInteraction(interactionId: Long) {
        graphMutex.withLock {
            val ops = db.withTransaction {
                val log = logs.get(interactionId) ?: return@withTransaction emptyList<IndexOp>()
                logs.deleteById(interactionId)
                listOfNotNull(rebuildPerson(log.personId, tuning))
            }
            applyIndex(ops)
        }
    }

    override suspend fun deleteCapture(captureId: Long) {
        graphMutex.withLock {
            val ops = db.withTransaction { deleteCaptureLocked(captureId, skip = null) }
            applyIndex(ops)
        }
    }

    private suspend fun deleteCaptureLocked(captureId: Long, skip: Long?): List<IndexOp> {
        val affected = logs.personIdsForCapture(captureId)
        logs.deleteByCapture(captureId)
        captures.deleteById(captureId)
        val crm = tuning
        return affected.filter { it != skip }.mapNotNull { rebuildPerson(it, crm) }
    }

    /**
     * Recomputes a person's tags from the interactions they still have (plus the user's own tags),
     * re-embeds them and rebuilds their edges. A person the AI created who has nothing left is
     * deleted, so a deleted rant leaves nothing behind.
     */
    private suspend fun rebuildPerson(personId: Long, crm: CrmTuning, deleteIfOrphan: Boolean = true): IndexOp? {
        val person = people.get(personId) ?: return null
        val remaining = logs.forPerson(personId)
        val hasUserTags = person.dynamicTags.tags.values.any { it.byUser }
        if (deleteIfOrphan && remaining.isEmpty() && !person.createdByUser && !hasUserTags) {
            people.deleteById(personId)
            return IndexOp(personId, null, remove = true)
        }
        val at = now()
        val tags = TagScoring.rebuild(
            userTags = person.dynamicTags.tags,
            contributions = remaining.map { it.timestamp to it.tagsAdded.values },
            suppressed = person.suppressedTags,
            maxTags = crm.maxTagsPerPerson,
            halfLifeDays = crm.tagHalfLifeDays.toDouble(),
            now = at,
        )
        val (row, op) = withEmbedding(
            person.copy(
                dynamicTags = tags,
                lastInteractionAt = remaining.maxOfOrNull { it.timestamp } ?: person.createdAt,
                updatedAt = at,
            ),
            crm,
            at,
        )
        people.update(row)
        rebuildEdges(personId, crm)
        return op.copy(personId = personId)
    }

    // ---------------------------------------------------------------- mapping

    private fun aliasesOf(keys: Collection<String>, currentKey: String): StringSet =
        StringSet(keys.filter { it.isNotEmpty() && it != currentKey }.toSortedSet())

    private fun PersonRow.toDomain(now: Long, crm: CrmTuning): Person {
        val halfLife = crm.tagHalfLifeDays.toDouble()
        val tags = TagScoring.ranked(dynamicTags, now, halfLife).map { tag ->
            val stat = dynamicTags.tags.getValue(tag)
            PersonTag(tag, stat.count, stat.firstAt, stat.lastAt, stat.byUser, TagScoring.score(stat, now, halfLife))
        }
        return Person(
            id = id,
            name = name,
            aliases = aliases.values,
            tags = tags,
            suppressedTags = suppressedTags.values,
            lastInteractionAt = lastInteractionAt,
            createdAt = createdAt,
            hasEmbedding = hasEmbedding,
            createdByUser = createdByUser,
        )
    }

    private fun InteractionLogEntity.toDomain() = Interaction(
        id = id,
        personId = personId,
        captureId = captureId,
        timestamp = timestamp,
        rawText = rawText,
        summary = nanoSummary,
        tagsAdded = tagsAdded.values,
    )

    private fun ConnectionRow.toDomain() = Connection(
        edgeId = edgeId,
        otherId = otherId,
        otherName = otherName,
        coMentions = relation.coMentions,
        lastAt = relation.lastAt,
        labels = relation.labels,
    )

    private fun CaptureEntity.toDomain() = Capture(
        id = id,
        rawText = rawText,
        status = status,
        attempts = attempts,
        error = lastError,
        summary = summary,
        mentionCount = mentionCount,
        createdAt = createdAt,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val EMBED_BATCH = 50
        const val MAX_LABEL_CHARS = 40
        const val DAY_MS = 86_400_000L
        val WORD = Regex("""[^\p{L}\p{N}]+""")

        fun wordsOf(text: String): Set<String> =
            text.lowercase(Locale.ROOT).split(WORD).filter { it.length > 2 }.toSet()

        fun daysBetween(then: Long, now: Long): Long = ((now - then) / DAY_MS).coerceAtLeast(0)
    }
}
