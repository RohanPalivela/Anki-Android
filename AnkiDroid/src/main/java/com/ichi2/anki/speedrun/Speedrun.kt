/*
 *  Copyright (c) 2026 Rohan Palivela
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.speedrun

import anki.speedrun.MissReason
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.observability.undoableOp

/**
 * Speedrun (MCAT fork) — Android port of the reusable pieces of pylib's
 * `anki.speedrun`.
 *
 * The engine itself (question-gated activation, coverage sweep, Memory model)
 * lives in the shared Rust core and is reached through the generated
 * [anki.speedrun] RPCs, exactly like desktop. This object only mirrors the thin
 * business logic that desktop keeps in Python: choosing which served questions
 * to show, recording the latest miss-reason as a synced note tag, and reading
 * the guided-session caps. Nothing here re-implements the engine.
 *
 * Because every custom object (questions, tags, `revlog`, blueprint config,
 * session progress) is a native Anki object, a single desktop import syncs to
 * the phone for free — Android never imports content itself; it studies what
 * synced in.
 */
object Speedrun {
    // Native data model (mirrors anki.speedrun; part of the frozen contract).
    const val QUESTION_NOTETYPE_NAME = "SpeedrunQuestion"
    const val QUESTIONS_DECK_NAME = "Speedrun::Questions"
    const val FLASHCARDS_DECK_NAME = "Speedrun::Cards"

    // Tag taxonomy (must match the Rust engine + desktop constants).
    const val TOPIC_TAG_PREFIX = "topic::"
    const val POOL_SERVED_TAG = "pool::served"
    const val POOL_HELDOUT_TAG = "pool::heldout"
    const val MISS_TAG_PREFIX = "miss::"
    const val FIRST_PRINCIPLES_TAG = "speedrun-first-principles"

    // Config keys (must match rslib/src/speedrun/blueprint.rs + pylib).
    const val SESSION_STATE_CONFIG_KEY = "speedrunSessionState"
    private const val SESSION_PRACTICE_CAP_CONFIG_KEY = "speedrunSessionPracticeCap"
    private const val SESSION_FLASHCARD_CAP_CONFIG_KEY = "speedrunSessionFlashcardCap"
    private const val SESSION_RECAP_CAP_CONFIG_KEY = "speedrunSessionRecapCap"
    private const val DEFAULT_SESSION_PRACTICE_CAP = 10
    private const val DEFAULT_SESSION_FLASHCARD_CAP = 20
    private const val DEFAULT_SESSION_RECAP_CAP = 5

    /** Suffix used for the `miss::<reason>` note tag (SPOV3 taxonomy). */
    private val missReasonTagSuffix =
        mapOf(
            MissReason.KNOWLEDGE_GAP to "knowledge-gap",
            MissReason.MISSING_CONTEXT to "missing-context",
            MissReason.MISUNDERSTANDING to "misunderstanding",
            MissReason.CARELESS to "careless",
        )

    /** Only memory problems activate linked cards (D-2b). */
    fun activates(reason: MissReason): Boolean = reason == MissReason.KNOWLEDGE_GAP || reason == MissReason.MISSING_CONTEXT

    data class SessionCaps(
        val practice: Int,
        val flashcards: Int,
        val recap: Int,
    )

    /** Bare topic name from a note's first `topic::` tag, if any. */
    fun topicOfNote(note: Note): String? = note.tags.firstOrNull { it.startsWith(TOPIC_TAG_PREFIX) }?.removePrefix(TOPIC_TAG_PREFIX)

    // --- Gating / engine RPC wrappers (Rust-implemented, shared with desktop) --

    /** True once served questions have synced in; practice is gated on this. */
    suspend fun hasQuestionBank(): Boolean = withCol { findNotes("note:$QUESTION_NOTETYPE_NAME tag:$POOL_SERVED_TAG").isNotEmpty() }

    /**
     * Persist the latest miss reason as a `miss::<reason>` note tag (replacing
     * any prior one) then run gated activation. Returns the number of cards
     * actually unsuspended (0 for non-qualifying reasons or when nothing linked
     * was suspended). Mirrors `Speedrun.record_miss_reason`.
     */
    suspend fun recordMissReason(
        questionNoteId: NoteId,
        reason: MissReason,
    ): Int {
        val suffix = missReasonTagSuffix[reason]
        if (suffix != null) {
            withCol {
                val note = getNote(questionNoteId)
                note.tags = note.tags.filterNot { it.startsWith(MISS_TAG_PREFIX) }.toMutableList()
                note.tags.add("$MISS_TAG_PREFIX$suffix")
                updateNote(note)
            }
        }
        return activateCardsForMiss(questionNoteId, reason)
    }

    /** Unsuspend a missed question's linked cards iff the reason qualifies. */
    suspend fun activateCardsForMiss(
        questionNoteId: NoteId,
        reason: MissReason,
    ): Int {
        val resp = withCol { backend.activateCardsForMiss(questionNoteId, reason) }
        // Notify observers (e.g. an open dashboard) without re-applying the op,
        // which the Rust transact already committed undoably.
        undoableOp { resp.changes }
        return resp.activatedCardIdsList.size
    }

    /** Re-activate a spread across topics. 0 -> configured default (never none). */
    suspend fun runCoverageSweep(sampleSize: Int = 0): Int {
        val resp = withCol { backend.runCoverageSweep(sampleSize) }
        undoableOp { resp.changes }
        return resp.activatedCardIdsList.size
    }

    // --- Served-question selection (mirrors pylib served_questions_*) ----------

    /** Note ids of served (never held-out) practice questions. */
    fun servedQuestionNoteIds(col: Collection): List<NoteId> =
        col.findNotes(
            "note:$QUESTION_NOTETYPE_NAME tag:$POOL_SERVED_TAG -tag:$POOL_HELDOUT_TAG",
        )

    /**
     * Served question note ids interleaved across topics (no topic blocking —
     * the desirable-difficulty win). Mirrors `served_questions_interleaved`.
     *
     * @param topics restrict to these bare topic names (recap targeting).
     * @param exclude drop these note ids (keep recap disjoint from practice).
     * @param unseenFirst order never-practised questions ahead of practised
     *   ones (practised sorted oldest-review-first) so a capped batch never
     *   replays the same leading questions while fresh ones remain.
     */
    fun servedQuestionsInterleaved(
        col: Collection,
        topics: Set<String>? = null,
        exclude: Set<NoteId> = emptySet(),
        unseenFirst: Boolean = false,
    ): List<NoteId> {
        fun inScope(nid: NoteId): String? {
            if (nid in exclude) return null
            val topic = topicOfNote(col.getNote(nid)) ?: ""
            if (topics != null && topic !in topics) return null
            return topic
        }

        if (!unseenFirst) {
            val groups = LinkedHashMap<String, MutableList<NoteId>>()
            for (nid in servedQuestionNoteIds(col)) {
                val topic = inScope(nid) ?: continue
                groups.getOrPut(topic) { mutableListOf() }.add(nid)
            }
            return roundRobin(groups.values.toList())
        }

        val unseenGroups = LinkedHashMap<String, MutableList<NoteId>>()
        val seen = mutableListOf<Triple<Long, String, NoteId>>()
        for (nid in servedQuestionNoteIds(col)) {
            val topic = inScope(nid) ?: continue
            val card = col.getNote(nid).cards(col).firstOrNull()
            if (card == null || card.reps == 0) {
                unseenGroups.getOrPut(topic) { mutableListOf() }.add(nid)
            } else {
                seen.add(Triple(card.lastReviewTimeSecs ?: 0L, topic, nid))
            }
        }
        val result = roundRobin(unseenGroups.values.toList()).toMutableList()
        seen.sortBy { it.first }
        val seenGroups = LinkedHashMap<String, MutableList<NoteId>>()
        for ((_, topic, nid) in seen) {
            seenGroups.getOrPut(topic) { mutableListOf() }.add(nid)
        }
        result.addAll(roundRobin(seenGroups.values.toList()))
        return result
    }

    /** Interleave per-topic note-id lists so consecutive items differ in topic. */
    private fun roundRobin(groups: List<List<NoteId>>): List<NoteId> {
        val result = mutableListOf<NoteId>()
        val cursors = IntArray(groups.size)
        var remaining = groups.sumOf { it.size }
        while (remaining > 0) {
            for (i in groups.indices) {
                if (cursors[i] < groups[i].size) {
                    result.add(groups[i][cursors[i]])
                    cursors[i]++
                    remaining--
                }
            }
        }
        return result
    }

    /**
     * True if the given topic has any *memory flashcards* (non-question notes
     * sharing its `topic::` tag). Used only to phrase the "nothing activated"
     * message honestly, since questions also carry `topic::` tags.
     */
    fun topicHasLinkedFlashcards(
        col: Collection,
        topic: String,
    ): Boolean = col.findCards("tag:$TOPIC_TAG_PREFIX$topic -note:$QUESTION_NOTETYPE_NAME").isNotEmpty()

    fun flashcardsDeckId(col: Collection): DeckId? = col.decks.idForName(FLASHCARDS_DECK_NAME)

    // --- Guided-session caps + progress state ---------------------------------

    fun sessionCaps(col: Collection): SessionCaps {
        fun cap(
            key: String,
            default: Int,
        ): Int {
            val value = col.config.get<Int>(key) ?: return default
            return if (value > 0) value else default
        }
        return SessionCaps(
            practice = cap(SESSION_PRACTICE_CAP_CONFIG_KEY, DEFAULT_SESSION_PRACTICE_CAP),
            flashcards = cap(SESSION_FLASHCARD_CAP_CONFIG_KEY, DEFAULT_SESSION_FLASHCARD_CAP),
            recap = cap(SESSION_RECAP_CAP_CONFIG_KEY, DEFAULT_SESSION_RECAP_CAP),
        )
    }
}
