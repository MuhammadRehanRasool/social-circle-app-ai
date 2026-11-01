package dev.braindump.splitter.data.ai.embedding

import dev.braindump.splitter.data.local.crm.PersonDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everyone's vector in one flat in-memory matrix, searched by brute-force dot product. At
 * personal-CRM scale (thousands of people, 100 dimensions) that's well under a millisecond, and
 * exact. Loaded from Room on first use; the repository keeps it in step on every write.
 */
@Singleton
class PersonVectorIndex @Inject constructor(
    private val dao: PersonDao,
    private val engine: TextEmbeddingEngine,
) {
    private val mutex = Mutex()

    // Guarded by mutex.
    private var loaded = false
    private var dim = 0
    private var ids = LongArray(0)
    private var matrix = FloatArray(0)
    private var size = 0
    private val rowOf = HashMap<Long, Int>()

    suspend fun put(id: Long, vector: FloatArray) = mutex.withLock {
        ensureLoadedLocked()
        putLocked(id, vector)
    }

    suspend fun remove(id: Long) = mutex.withLock {
        if (loaded) removeLocked(id)
    }

    /** Forgets everything; the next search reloads from the database. */
    suspend fun invalidate() = mutex.withLock {
        loaded = false
        dim = 0
        ids = LongArray(0)
        matrix = FloatArray(0)
        size = 0
        rowOf.clear()
    }

    suspend fun count(): Int = mutex.withLock {
        ensureLoadedLocked()
        size
    }

    /** The [k] people closest to [query] (unit length), best first, as id to cosine similarity. */
    suspend fun search(query: FloatArray, k: Int): List<Pair<Long, Float>> = mutex.withLock {
        ensureLoadedLocked()
        if (size == 0 || k <= 0 || query.size != dim) return@withLock emptyList()
        val heap = PriorityQueue<Pair<Long, Float>>(k + 1, compareBy<Pair<Long, Float>> { it.second })
        for (row in 0 until size) {
            val offset = row * dim
            var score = 0f
            for (d in 0 until dim) score += matrix[offset + d] * query[d]
            heap.add(ids[row] to score)
            if (heap.size > k) heap.poll()
        }
        heap.sortedByDescending { it.second }
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        for (row in dao.vectors(engine.modelId)) {
            row.embedding?.let { putLocked(row.id, it) }
        }
        loaded = true
    }

    private fun putLocked(id: Long, vector: FloatArray) {
        if (vector.isEmpty()) return
        if (size == 0 && rowOf.isEmpty()) dim = vector.size
        if (vector.size != dim) return // a stale vector from another model; it'll be re-embedded
        val existing = rowOf[id]
        if (existing != null) {
            vector.copyInto(matrix, existing * dim)
            return
        }
        if ((size + 1) * dim > matrix.size) {
            val capacity = maxOf(16, size * 2)
            matrix = matrix.copyOf(capacity * dim)
            ids = ids.copyOf(capacity)
        }
        vector.copyInto(matrix, size * dim)
        ids[size] = id
        rowOf[id] = size
        size++
    }

    /** Moves the last row into the gap, so rows stay packed. */
    private fun removeLocked(id: Long) {
        val row = rowOf.remove(id) ?: return
        val last = size - 1
        if (row != last) {
            matrix.copyInto(matrix, row * dim, last * dim, last * dim + dim)
            ids[row] = ids[last]
            rowOf[ids[row]] = row
        }
        size--
    }
}
