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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.NoteId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guided Speedrun session on Android — the fixed Practice → Memory flashcards →
 * Recap sequence, ported from desktop's `SpeedrunSession`
 * (qt/aqt/speedrun/session.py). The student only answers and may stop; every
 * transition is decided here.
 *
 * **In-lesson progress sync.** Progress is persisted to the collection config
 * key [Speedrun.SESSION_STATE_CONFIG_KEY] using the *exact same JSON schema* as
 * desktop, so it rides Anki's normal sync. Pausing a lesson on desktop and
 * pressing Start on the phone (after a sync) resumes the same phase and
 * question, and vice-versa — a real cross-device "pick up where you left off".
 *
 * Phase 2 uses the native FSRS reviewer scoped to `Speedrun::Cards` (the shared
 * engine), so activated memory cards are reviewed exactly as in normal Anki.
 */
class SpeedrunSessionActivity : AnkiActivity() {
    private lateinit var status: TextView
    private lateinit var phaseButtons: LinearLayout

    private lateinit var caps: Speedrun.SessionCaps

    // Persisted state (mirrors session.py fields / JSON keys).
    private var phase = 0
    private var practiceIds = mutableListOf<NoteId>()
    private var practiceIndex = 0
    private var recapIds = mutableListOf<NoteId>()
    private var recapIndex = 0
    private val studiedTopics = sortedSetOf<String>()
    private val missedTopics = sortedSetOf<String>()
    private val practiceShown = sortedSetOf<Long>()
    private var practiceAnswered = 0
    private var practiceCorrect = 0
    private var recapAnswered = 0
    private var recapCorrect = 0
    private var flashcardsReviewed = 0
    private var activatedTotal = 0

    private var finished = false
    private var flashcardsDueBefore = 0

    private val studyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (phase) {
                1 -> onPhase1Result(result)
                3 -> onPhase3Result(result)
            }
        }

    private val reviewerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onFlashcardReviewReturned()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out of the flashcard prompt (phase 2) pauses the session
        // instead of discarding progress, so a partly-finished session resumes
        // on the next Start — matching desktop, where navigating away mid-review
        // pauses at phase 2. Phases 1 and 3 own their own back handling in the
        // study screen (this activity is backgrounded while they run).
        onBackPressedDispatcher.addCallback(this) {
            if (!finished && phase == 2) {
                pause()
            } else {
                finish()
            }
        }
        buildUi()
        launchCatchingTask {
            if (!Speedrun.hasQuestionBank()) {
                status.text = getString(R.string.speedrun_bank_required_body)
                addButton(R.string.speedrun_close) { finish() }
                return@launchCatchingTask
            }
            withCol {
                caps = Speedrun.sessionCaps(this)
                loadState(config.getObject(Speedrun.SESSION_STATE_CONFIG_KEY, JSONObject()))
                pruneStaleQuestionIds(this)
            }
            start()
        }
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        status =
            TextView(this).apply {
                textSize = 17f
                gravity = Gravity.CENTER
            }
        root.addView(status)
        phaseButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(phaseButtons)
        setContentView(root)
    }

    // --- Phase machine (mirrors session.py) -----------------------------------

    private fun start() {
        when (phase) {
            2 -> enterPhase2()
            3 -> enterPhase3()
            else -> enterPhase1()
        }
    }

    private fun enterPhase1() {
        phase = 1
        launchCatchingTask {
            if (practiceIds.isEmpty()) {
                practiceIds =
                    withCol {
                        Speedrun.servedQuestionsInterleaved(this, unseenFirst = true)
                    }.take(caps.practice).toMutableList()
                practiceIndex = 0
            }
            if (practiceIds.isEmpty() || practiceIndex >= practiceIds.size) {
                enterPhase2()
                return@launchCatchingTask
            }
            status.text = getString(R.string.speedrun_session_practice_title)
            phaseButtons.removeAllViews()
            studyLauncher.launch(
                SpeedrunStudyActivity.getIntent(
                    context = this@SpeedrunSessionActivity,
                    noteIds = practiceIds,
                    mode = SpeedrunStudyActivity.MODE_PRACTICE,
                    title = getString(R.string.speedrun_session_practice_title),
                    startIndex = practiceIndex,
                ),
            )
        }
    }

    private fun onPhase1Result(result: ActivityResult) {
        val r = StudyResult.from(result)
        studiedTopics.addAll(r.involvedTopics)
        missedTopics.addAll(r.missedTopics)
        practiceShown.addAll(r.shownNoteIds)
        practiceAnswered += r.answered
        practiceCorrect += r.correct
        activatedTotal += r.activated
        if (!r.completed) {
            practiceIndex = r.resumeIndex
            pause()
            return
        }
        practiceIndex = practiceIds.size
        enterPhase2()
    }

    private fun enterPhase2() {
        phase = 2
        launchCatchingTask {
            val deckId = withCol { Speedrun.flashcardsDeckId(this) }
            if (deckId == null) {
                enterPhase3()
                return@launchCatchingTask
            }
            val due =
                withCol {
                    decks.select(deckId)
                    sched.counts().count()
                }
            if (due == 0) {
                enterPhase3()
                return@launchCatchingTask
            }
            flashcardsDueBefore = due
            showFlashcardPrompt()
        }
    }

    private fun showFlashcardPrompt() {
        // Persist phase-2 progress immediately so leaving the app (back press or
        // process death) mid-flashcards resumes here, mirroring desktop's
        // resumable phase 2 (session.py persists a phase=2 state on pause).
        launchCatchingTask { withCol { config.set(Speedrun.SESSION_STATE_CONFIG_KEY, saveState()) } }
        status.text = getString(R.string.speedrun_session_flashcards_prompt)
        phaseButtons.removeAllViews()
        addButton(R.string.speedrun_session_review_cards) {
            reviewerLauncher.launch(Reviewer.getIntent(this))
        }
        addButton(R.string.speedrun_session_continue) { enterPhase3() }
        addButton(R.string.speedrun_session_stop) { finishSession(stopped = true) }
    }

    private fun onFlashcardReviewReturned() {
        if (phase != 2) return
        launchCatchingTask {
            val dueNow = withCol { sched.counts().count() }
            flashcardsReviewed += (flashcardsDueBefore - dueNow).coerceAtLeast(0)
            flashcardsDueBefore = dueNow
            // Re-show the prompt (capped or not) so the student explicitly
            // continues to the recap, mirroring the deliberate desktop hand-off.
            if (flashcardsReviewed >= caps.flashcards || dueNow == 0) {
                enterPhase3()
            } else {
                showFlashcardPrompt()
            }
        }
    }

    private fun enterPhase3() {
        phase = 3
        launchCatchingTask {
            if (recapIds.isEmpty()) {
                recapIds =
                    if (studiedTopics.isEmpty()) {
                        mutableListOf()
                    } else {
                        withCol {
                            Speedrun.servedQuestionsInterleaved(
                                col = this,
                                topics = studiedTopics.toSet(),
                                exclude = practiceShown.toSet(),
                            )
                        }.take(caps.recap).toMutableList()
                    }
                recapIndex = 0
            }
            if (recapIds.isEmpty() || recapIndex >= recapIds.size) {
                finishSession(stopped = false)
                return@launchCatchingTask
            }
            status.text = getString(R.string.speedrun_session_recap_title)
            phaseButtons.removeAllViews()
            studyLauncher.launch(
                SpeedrunStudyActivity.getIntent(
                    context = this@SpeedrunSessionActivity,
                    noteIds = recapIds,
                    mode = SpeedrunStudyActivity.MODE_RECAP,
                    title = getString(R.string.speedrun_session_recap_title),
                    startIndex = recapIndex,
                ),
            )
        }
    }

    private fun onPhase3Result(result: ActivityResult) {
        val r = StudyResult.from(result)
        recapAnswered += r.answered
        recapCorrect += r.correct
        activatedTotal += r.activated
        if (!r.completed) {
            recapIndex = r.resumeIndex
            pause()
            return
        }
        finishSession(stopped = false)
    }

    // --- Pause / finish -------------------------------------------------------

    private fun pause() {
        if (finished) return
        launchCatchingTask {
            withCol { config.set(Speedrun.SESSION_STATE_CONFIG_KEY, saveState()) }
            showThemedToast(this@SpeedrunSessionActivity, getString(R.string.speedrun_session_paused), false)
            finish()
        }
    }

    private fun finishSession(stopped: Boolean) {
        if (finished) return
        finished = true
        phase = 0
        launchCatchingTask {
            withCol { config.remove(Speedrun.SESSION_STATE_CONFIG_KEY) }
            showSummary(stopped)
            finish()
        }
    }

    private fun showSummary(stopped: Boolean) {
        if (stopped) {
            showThemedToast(this, getString(R.string.speedrun_session_stopped), false)
            return
        }
        val answered = practiceAnswered + recapAnswered
        if (answered == 0 && flashcardsReviewed == 0) {
            showThemedToast(this, getString(R.string.speedrun_session_nothing), false)
            return
        }
        val correct = practiceCorrect + recapCorrect
        val message =
            buildString {
                append(getString(R.string.speedrun_session_complete))
                append(" ")
                append(
                    getString(
                        R.string.speedrun_session_complete_detail,
                        answered,
                        correct,
                        flashcardsReviewed,
                    ),
                )
                if (studiedTopics.isNotEmpty()) {
                    // Mirror desktop: humanize "organic-chemistry" -> "organic chemistry".
                    val topics = studiedTopics.map { it.replace("-", " ") }.sorted().joinToString(", ")
                    append(" ")
                    append(getString(R.string.speedrun_session_complete_topics, topics))
                }
            }
        showThemedToast(this, message, false)
    }

    // --- State (de)serialization — identical schema to desktop ----------------

    private fun loadState(state: JSONObject) {
        phase = state.optInt("phase", 0)
        practiceIds = state.optLongArray("practice_ids")
        practiceIndex = state.optInt("practice_index", 0)
        recapIds = state.optLongArray("recap_ids")
        recapIndex = state.optInt("recap_index", 0)
        studiedTopics.addAll(state.optStringList("studied_topics"))
        missedTopics.addAll(state.optStringList("missed_topics"))
        practiceShown.addAll(state.optLongArray("practice_shown"))
        practiceAnswered = state.optInt("practice_answered", 0)
        practiceCorrect = state.optInt("practice_correct", 0)
        recapAnswered = state.optInt("recap_answered", 0)
        recapCorrect = state.optInt("recap_correct", 0)
        flashcardsReviewed = state.optInt("flashcards_reviewed", 0)
        activatedTotal = state.optInt("activated_total", 0)
    }

    /**
     * Drop any persisted question ids that no longer exist before resuming.
     *
     * Re-importing the bank (e.g. on desktop, then syncing) assigns fresh note
     * ids, so a synced [Speedrun.SESSION_STATE_CONFIG_KEY] can point at dangling
     * ids from an older collection state. Serving one would crash the study
     * screen with "no such note". We prune dangling ids and rebase the resume
     * index onto the survivors; if the whole persisted batch is stale (a
     * cross-collection state), we discard the session and start fresh rather
     * than resuming meaningless progress. Fresh sessions (no persisted ids)
     * are untouched — [enterPhase1]/[enterPhase3] build their own lists.
     */
    private fun pruneStaleQuestionIds(col: Collection) {
        val hadIds = practiceIds.isNotEmpty() || recapIds.isNotEmpty()
        if (!hadIds) return
        val valid = Speedrun.servedQuestionNoteIds(col).toHashSet()
        val (prunedPractice, newPracticeIndex) = pruneList(practiceIds, practiceIndex, valid)
        val (prunedRecap, newRecapIndex) = pruneList(recapIds, recapIndex, valid)
        if (prunedPractice.isEmpty() && prunedRecap.isEmpty()) {
            // Nothing survived: reset to a clean slate so start() re-enters phase 1.
            phase = 0
            practiceIds = mutableListOf()
            practiceIndex = 0
            recapIds = mutableListOf()
            recapIndex = 0
            studiedTopics.clear()
            missedTopics.clear()
            practiceShown.clear()
            practiceAnswered = 0
            practiceCorrect = 0
            recapAnswered = 0
            recapCorrect = 0
            flashcardsReviewed = 0
            activatedTotal = 0
            return
        }
        practiceIds = prunedPractice
        practiceIndex = newPracticeIndex
        recapIds = prunedRecap
        recapIndex = newRecapIndex
    }

    /** Keep only ids in [valid], rebasing [index] onto the surviving prefix. */
    private fun pruneList(
        ids: List<NoteId>,
        index: Int,
        valid: Set<NoteId>,
    ): Pair<MutableList<NoteId>, Int> {
        val kept = mutableListOf<NoteId>()
        var newIndex = 0
        ids.forEachIndexed { i, id ->
            if (id in valid) {
                if (i < index) newIndex++
                kept.add(id)
            }
        }
        return kept to newIndex
    }

    private fun saveState(): JSONObject =
        JSONObject().apply {
            put("phase", phase)
            put("practice_ids", JSONArray(practiceIds))
            put("practice_index", practiceIndex)
            put("recap_ids", JSONArray(recapIds))
            put("recap_index", recapIndex)
            put("studied_topics", JSONArray(studiedTopics.toList()))
            put("missed_topics", JSONArray(missedTopics.toList()))
            put("practice_shown", JSONArray(practiceShown.toList()))
            put("practice_answered", practiceAnswered)
            put("practice_correct", practiceCorrect)
            put("recap_answered", recapAnswered)
            put("recap_correct", recapCorrect)
            put("flashcards_reviewed", flashcardsReviewed)
            put("activated_total", activatedTotal)
        }

    private fun addButton(
        labelRes: Int,
        onClick: () -> Unit,
    ) {
        phaseButtons.addView(
            Button(this).apply {
                setText(labelRes)
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** Parsed result of a [SpeedrunStudyActivity] phase. */
    private data class StudyResult(
        val completed: Boolean,
        val shownNoteIds: List<Long>,
        val involvedTopics: List<String>,
        val missedTopics: List<String>,
        val answered: Int,
        val correct: Int,
        val activated: Int,
        val resumeIndex: Int,
    ) {
        companion object {
            fun from(result: ActivityResult): StudyResult {
                val data = result.data
                if (result.resultCode != RESULT_OK || data == null) {
                    return StudyResult(false, emptyList(), emptyList(), emptyList(), 0, 0, 0, 0)
                }
                return StudyResult(
                    completed = data.getBooleanExtra(SpeedrunStudyActivity.RESULT_COMPLETED, false),
                    shownNoteIds = data.getLongArrayExtra(SpeedrunStudyActivity.RESULT_SHOWN_NOTE_IDS)?.toList() ?: emptyList(),
                    involvedTopics = data.getStringArrayExtra(SpeedrunStudyActivity.RESULT_INVOLVED_TOPICS)?.toList() ?: emptyList(),
                    missedTopics = data.getStringArrayExtra(SpeedrunStudyActivity.RESULT_MISSED_TOPICS)?.toList() ?: emptyList(),
                    answered = data.getIntExtra(SpeedrunStudyActivity.RESULT_ANSWERED, 0),
                    correct = data.getIntExtra(SpeedrunStudyActivity.RESULT_CORRECT, 0),
                    activated = data.getIntExtra(SpeedrunStudyActivity.RESULT_ACTIVATED, 0),
                    resumeIndex = data.getIntExtra(SpeedrunStudyActivity.RESULT_RESUME_INDEX, 0),
                )
            }
        }
    }

    companion object {
        fun getIntent(context: Context): Intent = Intent(context, SpeedrunSessionActivity::class.java)
    }
}

private fun JSONObject.optLongArray(key: String): MutableList<Long> {
    val array = optJSONArray(key) ?: return mutableListOf()
    return MutableList(array.length()) { array.getLong(it) }
}

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return List(array.length()) { array.getString(it) }
}
