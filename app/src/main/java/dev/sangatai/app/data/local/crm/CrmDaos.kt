package dev.sangatai.app.data.local.crm

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val PERSON_ROW_COLUMNS =
    "id, name, aliases_json, dynamic_tags_json, suppressed_tags_json, (embedding IS NOT NULL) AS has_embedding, " +
        "created_by_user, created_at, last_interaction_at, relation_to_user, " +
        "(SELECT f.label FROM relation_facts f WHERE f.person_a = people.id AND f.person_b IS NULL " +
        "ORDER BY f.timestamp DESC, f.id DESC LIMIT 1) AS ai_relation, " +
        "(SELECT COUNT(*) FROM interaction_logs l WHERE l.person_id = people.id) AS mention_count"

@Dao
interface PersonDao {

    @Insert
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    /** Their interactions and connections go too (ON DELETE CASCADE). */
    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun get(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PersonEntity>

    @Query("SELECT $PERSON_ROW_COLUMNS FROM people ORDER BY last_interaction_at DESC, id DESC")
    fun observeAll(): Flow<List<PersonRow>>

    @Query("SELECT $PERSON_ROW_COLUMNS FROM people ORDER BY last_interaction_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PersonRow>>

    @Query("SELECT $PERSON_ROW_COLUMNS FROM people WHERE id = :id")
    fun observe(id: Long): Flow<PersonRow?>

    /** Exact name, or a name this person was heard as before. [aliasPattern] is `%"key"%`. */
    @Query("SELECT * FROM people WHERE name_key = :key OR aliases_json LIKE :aliasPattern ORDER BY last_interaction_at DESC")
    suspend fun byKeyOrAlias(key: String, aliasPattern: String): List<PersonEntity>

    @Query("SELECT * FROM people WHERE name_key = :key ORDER BY last_interaction_at DESC")
    suspend fun byKey(key: String): List<PersonEntity>

    /** People whose name starts with the word [word]. [prefixPattern] is `word %`. */
    @Query("SELECT * FROM people WHERE name_key = :word OR name_key LIKE :prefixPattern ORDER BY last_interaction_at DESC")
    suspend fun byFirstWord(word: String, prefixPattern: String): List<PersonEntity>

    @Query("SELECT id, embedding FROM people WHERE embedding IS NOT NULL AND embedding_model = :model")
    suspend fun vectors(model: String): List<PersonVector>

    /** Missing or another model's vector. A person with no tags gets the model id and no vector, so they aren't retried. */
    @Query("SELECT * FROM people WHERE embedding_model IS NOT :model AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun needingEmbedding(model: String, afterId: Long, limit: Int): List<PersonEntity>

    @Query("UPDATE people SET embedding = NULL, embedding_model = NULL")
    suspend fun clearEmbeddings()

    @Query("SELECT * FROM people ORDER BY id")
    suspend fun all(): List<PersonEntity>

    @Query("DELETE FROM people")
    suspend fun deleteAll()
}

@Dao
interface InteractionLogDao {

    @Insert
    suspend fun insert(log: InteractionLogEntity): Long

    @Query("SELECT * FROM interaction_logs WHERE id = :id")
    suspend fun get(id: Long): InteractionLogEntity?

    @Query("DELETE FROM interaction_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM interaction_logs WHERE person_id = :personId ORDER BY timestamp DESC, id DESC")
    fun observeForPerson(personId: Long): Flow<List<InteractionLogEntity>>

    @Query("SELECT * FROM interaction_logs WHERE person_id = :personId ORDER BY timestamp ASC, id ASC")
    suspend fun forPerson(personId: Long): List<InteractionLogEntity>

    @Query("SELECT COUNT(*) FROM interaction_logs WHERE person_id = :personId")
    suspend fun countForPerson(personId: Long): Int

    @Query("SELECT DISTINCT person_id FROM interaction_logs WHERE capture_id = :captureId")
    suspend fun personIdsForCapture(captureId: Long): List<Long>

    @Query("DELETE FROM interaction_logs WHERE capture_id = :captureId")
    suspend fun deleteByCapture(captureId: Long)

    @Query("UPDATE interaction_logs SET person_id = :to WHERE person_id = :from")
    suspend fun reassign(from: Long, to: Long)

    @Query("UPDATE interaction_logs SET tags_added_json = :tags WHERE id = :id")
    suspend fun setTagsAdded(id: Long, tags: StringSet)

    @Query("SELECT DISTINCT capture_id FROM interaction_logs WHERE person_id = :personId AND capture_id IS NOT NULL")
    suspend fun captureIdsForPerson(personId: Long): List<Long>

    /**
     * Everyone named in the same rants as [personId], with how many rants and the latest one.
     * Rants naming more than [maxClique] people are left out: a list of 20 names isn't 190 friendships.
     */
    @Query(
        """
        SELECT b.person_id AS other_id, COUNT(DISTINCT a.capture_id) AS mentions, MAX(a.timestamp) AS last_at
        FROM interaction_logs a
        JOIN interaction_logs b ON b.capture_id = a.capture_id AND b.person_id != a.person_id
        JOIN captures c ON c.id = a.capture_id
        WHERE a.person_id = :personId AND c.mention_count <= :maxClique
        GROUP BY b.person_id
        """
    )
    suspend fun coMentions(personId: Long, maxClique: Int): List<CoMention>

    @Query("SELECT * FROM interaction_logs ORDER BY id")
    suspend fun all(): List<InteractionLogEntity>
}

@Dao
interface ConnectionDao {

    @Query("SELECT * FROM connections WHERE person_a = :a AND person_b = :b")
    suspend fun get(a: Long, b: Long): ConnectionEntity?

    @Insert
    suspend fun insert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Query("DELETE FROM connections WHERE edge_id = :edgeId")
    suspend fun deleteById(edgeId: Long)

    @Query("SELECT * FROM connections WHERE person_a = :personId OR person_b = :personId")
    suspend fun forPerson(personId: Long): List<ConnectionEntity>

    @Query(
        """
        SELECT c.edge_id AS edge_id, p.id AS other_id, p.name AS other_name, c.relation_json AS relation_json
        FROM connections c
        JOIN people p ON p.id = CASE WHEN c.person_a = :personId THEN c.person_b ELSE c.person_a END
        WHERE c.person_a = :personId OR c.person_b = :personId
        ORDER BY p.name COLLATE NOCASE
        """
    )
    fun observeForPerson(personId: Long): Flow<List<ConnectionRow>>

    @Query("SELECT * FROM connections ORDER BY edge_id")
    suspend fun all(): List<ConnectionEntity>

    /** Every edge, for the network graph. */
    @Query("SELECT * FROM connections ORDER BY edge_id")
    fun observeAll(): Flow<List<ConnectionEntity>>
}

@Dao
interface RelationFactDao {

    @Insert
    suspend fun insert(fact: RelationFactEntity): Long

    /** Facts between [personId] and other people, newest first. */
    @Query(
        """
        SELECT CASE WHEN person_a = :personId THEN person_b ELSE person_a END AS other_id, label
        FROM relation_facts
        WHERE (person_a = :personId AND person_b IS NOT NULL) OR person_b = :personId
        ORDER BY timestamp DESC, id DESC
        """
    )
    suspend fun pairFactsFor(personId: Long): List<FactRow>

    @Query("SELECT * FROM relation_facts WHERE capture_id = :captureId ORDER BY id")
    suspend fun forCapture(captureId: Long): List<RelationFactEntity>

    /** The people on the other side of [personId]'s facts from one rant, before those facts go. */
    @Query(
        """
        SELECT DISTINCT CASE WHEN person_a = :personId THEN person_b ELSE person_a END
        FROM relation_facts
        WHERE capture_id = :captureId AND person_b IS NOT NULL AND (person_a = :personId OR person_b = :personId)
        """
    )
    suspend fun partnersIn(captureId: Long, personId: Long): List<Long>

    @Query("DELETE FROM relation_facts WHERE capture_id = :captureId AND (person_a = :personId OR person_b = :personId)")
    suspend fun deleteFor(captureId: Long, personId: Long)

    // Merging: point the dropped person's facts at the kept one, then drop facts that became self-loops.
    @Query("UPDATE relation_facts SET person_a = :to WHERE person_a = :from")
    suspend fun reassignA(from: Long, to: Long)

    @Query("UPDATE relation_facts SET person_b = :to WHERE person_b = :from")
    suspend fun reassignB(from: Long, to: Long)

    @Query("DELETE FROM relation_facts WHERE person_a = person_b")
    suspend fun deleteSelfLoops()

    @Query("SELECT * FROM relation_facts ORDER BY id")
    suspend fun all(): List<RelationFactEntity>
}

@Dao
interface CaptureDao {

    @Insert
    suspend fun insert(capture: CaptureEntity): Long

    @Update
    suspend fun update(capture: CaptureEntity)

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: Long): CaptureEntity?

    @Query("SELECT id FROM captures WHERE status = 'PENDING' ORDER BY created_at ASC, id ASC")
    suspend fun pendingIds(): List<Long>

    @Query(
        """
        SELECT c.id, c.raw_text, c.status, c.attempts, c.last_error, c.summary, c.mention_count, c.created_at,
            (SELECT group_concat(p.name, char(31)) FROM interaction_logs l JOIN people p ON p.id = l.person_id
             WHERE l.capture_id = c.id) AS people_names
        FROM captures c
        ORDER BY c.created_at DESC, c.id DESC
        """
    )
    fun observeRows(): Flow<List<CaptureRow>>

    @Query(
        "UPDATE captures SET status = 'PENDING', attempts = 0, last_error = NULL, updated_at = :now " +
            "WHERE id = :id AND status = 'FAILED'"
    )
    suspend fun resetFailed(id: Long, now: Long): Int

    @Query("DELETE FROM captures WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM captures ORDER BY id")
    suspend fun all(): List<CaptureEntity>

    @Query("DELETE FROM captures")
    suspend fun deleteAll()
}
