package dev.braindump.splitter.domain.repository

import dev.braindump.splitter.domain.model.AskOutcome
import dev.braindump.splitter.domain.model.Capture
import dev.braindump.splitter.domain.model.IngestOutcome
import dev.braindump.splitter.domain.model.Person
import dev.braindump.splitter.domain.model.PersonDetail
import kotlinx.coroutines.flow.Flow

/** The social graph: people, what was said about them, and who can help with what. */
interface PeopleRepository {

    /** Everyone, most recently mentioned first. */
    fun observePeople(): Flow<List<Person>>

    fun observeRecentPeople(limit: Int): Flow<List<Person>>

    fun observePerson(personId: Long): Flow<PersonDetail?>

    /** Every rant, newest first, with what became of it. */
    fun observeCaptures(): Flow<List<Capture>>

    /**
     * Saves the rant, then has Gemini Nano read it right away. Call while the app is in the
     * foreground; anything Nano can't do now stays queued for [drainPending].
     */
    suspend fun ingest(rawText: String): IngestOutcome

    /** Processes queued rants. Call while the app is in the foreground. */
    suspend fun drainPending()

    suspend fun retryCapture(captureId: Long)

    /** Semantic search over everyone's tags, then Gemini Nano picks and explains the best matches. */
    suspend fun askNetwork(query: String): AskOutcome

    // The user's own edits. They always win over what the AI inferred.

    /** Adds someone by hand. Returns null for a blank name. */
    suspend fun addPerson(name: String, tags: List<String> = emptyList()): Long?

    /** Renames, and stops longer spoken names from overwriting it. */
    suspend fun renamePerson(personId: Long, name: String)

    /** Adds a tag as the user's. Returns false if it isn't a usable tag. */
    suspend fun addTag(personId: Long, tag: String): Boolean

    /** Removes a tag and stops the AI from adding it back. */
    suspend fun removeTag(personId: Long, tag: String)

    /** Lets the AI use a removed tag again, restoring whatever past rants said. */
    suspend fun unsuppressTag(personId: Long, tag: String)

    /** Names a relation ("siblings"); an empty list removes the labels. */
    suspend fun setConnectionLabels(personA: Long, personB: Long, labels: List<String>)

    /** Folds [dropId] into [keepId]: interactions, tags, aliases and connections. */
    suspend fun mergePeople(keepId: Long, dropId: Long)

    // Deleting. Deleted text is overwritten in the database file (secure_delete).

    /**
     * Deletes a person with their interactions and connections. With [alsoDeleteRants], every
     * rant that mentioned them is deleted too, so their name is gone from the raw text as well.
     */
    suspend fun deletePerson(personId: Long, alsoDeleteRants: Boolean = false)

    /** Deletes one interaction; the tags it gave are taken back. */
    suspend fun deleteInteraction(interactionId: Long)

    /**
     * Deletes a rant and everything it produced: its interactions, the tags they gave, the
     * connections it created, and people who existed only because of it.
     */
    suspend fun deleteCapture(captureId: Long)

    /** Embeds people whose vector is missing or from another model. Returns how many were done. */
    suspend fun reembedStale(): Int

    /** Throws every vector away and embeds everyone again (after changing tuning). */
    suspend fun reembedAll(): Int
}
