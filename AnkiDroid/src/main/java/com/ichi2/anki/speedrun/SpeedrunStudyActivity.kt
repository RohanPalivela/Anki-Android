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
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import anki.scheduler.CardAnswer.Rating
import anki.speedrun.MissReason
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.NoteId

/**
 * Speedrun question-first study screen — the SPOV3 core surface, ported from
 * desktop's `SpeedrunStudyDialog` (qt/aqt/speedrun/study.py).
 *
 * Serves `pool::served` questions, grades each answer through the native
 * `answerCard` path (so it lands in `revlog` and syncs), and on an incorrect
 * answer offers a miss-reason chooser. Qualifying misses (knowledge-gap /
 * missing-context) unsuspend the question's linked cards via the shared Rust
 * gating RPC; the screen then reports how many cards were activated.
 *
 * Standalone use serves every served question. The guided session
 * ([SpeedrunSessionActivity]) injects a capped, interleaved note-id list, a
 * mode/title, and a start index, and reads back a [Result] via
 * `setResult`/extras so it can drive the fixed Practice/Recap sequence.
 */
class SpeedrunStudyActivity : AnkiActivity() {
    private var mode = MODE_STANDALONE
    private lateinit var noteIds: MutableList<NoteId>
    private var index = 0
    private var allowSweep = true

    private var answeredCount = 0
    private var correctCount = 0
    private var activatedTotal = 0

    private val shownNoteIds = mutableListOf<NoteId>()
    private val involvedTopics = mutableSetOf<String>()
    private val missedTopics = mutableSetOf<String>()
    private var currentTopic: String? = null
    private var completed = false
    private var atEnd = false

    private var answeredCurrent = false
    private var currentCardId: CardId? = null
    private var currentCorrectIndex = -1
    private var explanation = ""
    private var source = ""

    private val optionButtons = mutableListOf<Button>()

    // Views
    private lateinit var progressLabel: TextView
    private lateinit var topicLabel: TextView
    private lateinit var stemLabel: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var resultLabel: TextView
    private lateinit var explanationLabel: TextView
    private lateinit var missContainer: LinearLayout
    private lateinit var activationLabel: TextView
    private lateinit var nextButton: Button
    private lateinit var tallyLabel: TextView
    private lateinit var sweepButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Back must hand a StudyPhaseResult (resume index, answered/correct
        // counts, shown ids, involved/missed topics) back to the guided session
        // so it can pause at the right question. The legacy onBackPressed()
        // override is not invoked under predictive back on newer Android, which
        // silently returned RESULT_CANCELED and reset the session to question 1;
        // the dispatcher callback fires reliably.
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_STANDALONE
        val injected = intent.getLongArrayExtra(EXTRA_NOTE_IDS)
        index = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0)
        allowSweep = intent.getBooleanExtra(EXTRA_ALLOW_SWEEP, true) && mode == MODE_STANDALONE
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(com.ichi2.anki.R.string.speedrun_study_title)

        buildUi()

        if (injected != null) {
            noteIds = injected.toMutableList()
            index = index.coerceAtMost(noteIds.size)
            afterQuestionsReady()
        } else {
            // Standalone: load the whole served pool.
            launchCatchingTask {
                noteIds = withCol { Speedrun.servedQuestionsInterleaved(this) }.toMutableList()
                afterQuestionsReady()
            }
        }
    }

    private fun afterQuestionsReady() {
        when {
            noteIds.isEmpty() -> showEmptyState()
            index >= noteIds.size -> showFinishedState()
            else -> loadQuestion()
        }
    }

    // --- UI construction ------------------------------------------------------

    private fun buildUi() {
        val pad = dp(16)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        val scroll =
            ScrollView(this).apply {
                addView(
                    root,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        setContentView(scroll)

        progressLabel = textView(bold = true).also { root.addView(it) }
        topicLabel = textView().apply { alpha = 0.7f }.also { root.addView(it) }
        stemLabel =
            textView(sizeSp = 18f)
                .apply {
                    setPadding(0, dp(10), 0, dp(10))
                }.also { root.addView(it) }

        optionsBox =
            LinearLayout(this)
                .apply { orientation = LinearLayout.VERTICAL }
                .also { root.addView(it) }

        resultLabel = textView(bold = true, sizeSp = 17f).apply { visibility = View.GONE }.also { root.addView(it) }
        explanationLabel = textView().apply { visibility = View.GONE }.also { root.addView(it) }

        missContainer =
            LinearLayout(this)
                .apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    setPadding(0, dp(6), 0, 0)
                }.also { root.addView(it) }
        missContainer.addView(textView(bold = true).apply { text = getString(com.ichi2.anki.R.string.speedrun_study_why_missed) })
        val missRow =
            LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((reason, labelRes) in MISS_BUTTONS) {
            val b =
                Button(this).apply {
                    text = getString(labelRes)
                    setOnClickListener { onMissReason(reason) }
                }
            missRow.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        missContainer.addView(missRow)

        activationLabel =
            textView(bold = true)
                .apply {
                    visibility = View.GONE
                    setTextColor(Color.parseColor("#1565c0"))
                }.also { root.addView(it) }

        nextButton =
            Button(this)
                .apply {
                    text = getString(com.ichi2.anki.R.string.speedrun_study_next)
                    visibility = View.GONE
                    setOnClickListener { onNext() }
                }.also { root.addView(it) }

        tallyLabel = textView().apply { setPadding(0, dp(10), 0, 0) }.also { root.addView(it) }

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sweepButton =
            Button(this).apply {
                text = getString(com.ichi2.anki.R.string.speedrun_study_run_sweep)
                visibility = if (allowSweep) View.VISIBLE else View.GONE
                setOnClickListener { onSweep() }
            }
        footer.addView(sweepButton)
        val close =
            Button(this).apply {
                text =
                    getString(
                        if (mode == MODE_STANDALONE) {
                            com.ichi2.anki.R.string.speedrun_close
                        } else {
                            com.ichi2.anki.R.string.speedrun_session_stop
                        },
                    )
                setOnClickListener { finishWithResult() }
            }
        footer.addView(close)
        root.addView(footer)

        updateTally()
    }

    // --- Question lifecycle ---------------------------------------------------

    private fun loadQuestion() {
        answeredCurrent = false
        resultLabel.visibility = View.GONE
        explanationLabel.visibility = View.GONE
        missContainer.visibility = View.GONE
        activationLabel.visibility = View.GONE
        nextButton.visibility = View.GONE
        clearOptions()

        val noteId = noteIds[index]
        launchCatchingTask {
            val q =
                withCol {
                    val note = getNote(noteId)
                    val opts = optionLines(note.getItem("options"))
                    LoadedQuestion(
                        cardId = note.cards(this).firstOrNull()?.id,
                        topic = Speedrun.topicOfNote(note),
                        stem = note.getItem("stem"),
                        options = opts,
                        correctIndex = SpeedrunStudyActivity.correctIndex(note.getItem("correct"), opts.size),
                        explanation = note.getItem("explanation"),
                        source = note.getItem("source"),
                    )
                }
            currentCardId = q.cardId
            currentTopic = q.topic
            currentCorrectIndex = q.correctIndex
            explanation = q.explanation
            source = q.source
            if (noteId !in shownNoteIds) shownNoteIds.add(noteId)
            q.topic?.let { involvedTopics.add(it) }

            progressLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_progress, index + 1, noteIds.size)
            topicLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_topic, q.topic ?: "—")
            stemLabel.text = q.stem

            q.options.forEachIndexed { i, text ->
                val letter = ('A' + i)
                val b =
                    Button(this@SpeedrunStudyActivity).apply {
                        this.text = "$letter.  $text"
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setOnClickListener { onOption(i) }
                    }
                optionsBox.addView(b)
                optionButtons.add(b)
            }
        }
    }

    private fun onOption(chosenIndex: Int) {
        if (answeredCurrent || currentCardId == null) return
        answeredCurrent = true
        val isCorrect = chosenIndex == currentCorrectIndex

        optionButtons.forEachIndexed { i, b ->
            b.isEnabled = false
            when (i) {
                currentCorrectIndex -> {
                    b.setBackgroundColor(Color.parseColor("#2e7d32"))
                    b.setTextColor(Color.WHITE)
                }
                chosenIndex -> {
                    b.setBackgroundColor(Color.parseColor("#c62828"))
                    b.setTextColor(Color.WHITE)
                }
            }
        }

        val cid = currentCardId!!
        launchCatchingTask {
            // Native review write (Again=incorrect, Good=correct) -> revlog.
            withCol {
                val card = getCard(cid)
                card.startTimer()
                sched.answerCard(card, if (isCorrect) Rating.GOOD else Rating.AGAIN)
            }
            answeredCount++
            if (isCorrect) {
                correctCount++
                resultLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_correct)
                resultLabel.setTextColor(Color.parseColor("#2e7d32"))
            } else {
                currentTopic?.let { missedTopics.add(it) }
                resultLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_incorrect)
                resultLabel.setTextColor(Color.parseColor("#c62828"))
            }
            resultLabel.visibility = View.VISIBLE

            var expl = explanation
            if (source.isNotEmpty()) expl = "$expl\n$source"
            explanationLabel.text = "${getString(com.ichi2.anki.R.string.speedrun_study_explanation)}: $expl"
            explanationLabel.visibility = View.VISIBLE

            if (isCorrect) {
                nextButton.visibility = View.VISIBLE
            } else {
                missContainer.visibility = View.VISIBLE
            }
            updateTally()
        }
    }

    private fun onMissReason(reason: MissReason) {
        val noteId = noteIds[index]
        launchCatchingTask {
            val count = Speedrun.recordMissReason(noteId, reason)
            activatedTotal += count
            activationLabel.text =
                when {
                    count > 0 -> resources.getQuantityString(com.ichi2.anki.R.plurals.speedrun_study_activated, count, count)
                    Speedrun.activates(reason) -> {
                        val hasLinked = withCol { currentTopic?.let { Speedrun.topicHasLinkedFlashcards(this, it) } ?: false }
                        if (hasLinked) {
                            getString(com.ichi2.anki.R.string.speedrun_study_already_active)
                        } else {
                            getString(com.ichi2.anki.R.string.speedrun_study_no_linked_cards)
                        }
                    }
                    else -> getString(com.ichi2.anki.R.string.speedrun_study_none_activated)
                }
            activationLabel.visibility = View.VISIBLE
            missContainer.visibility = View.GONE
            nextButton.visibility = View.VISIBLE
            updateTally()
        }
    }

    private fun onNext() {
        if (atEnd) {
            finishWithResult()
            return
        }
        index++
        if (index >= noteIds.size) {
            showFinishedState()
        } else {
            loadQuestion()
        }
    }

    private fun onSweep() {
        launchCatchingTask {
            val count = Speedrun.runCoverageSweep()
            activatedTotal += count
            showThemedToast(this@SpeedrunStudyActivity, getString(com.ichi2.anki.R.string.speedrun_study_sweep_done, count), false)
            updateTally()
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun clearOptions() {
        for (b in optionButtons) optionsBox.removeView(b)
        optionButtons.clear()
    }

    private fun updateTally() {
        tallyLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_tally, answeredCount, correctCount, activatedTotal)
    }

    private fun showEmptyState() {
        progressLabel.text = ""
        stemLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_no_questions)
        sweepButton.isEnabled = false
        if (mode != MODE_STANDALONE) {
            completed = true
            atEnd = true
            nextButton.text = getString(com.ichi2.anki.R.string.speedrun_session_continue)
            nextButton.visibility = View.VISIBLE
        }
    }

    private fun showFinishedState() {
        clearOptions()
        resultLabel.visibility = View.GONE
        explanationLabel.visibility = View.GONE
        missContainer.visibility = View.GONE
        activationLabel.visibility = View.GONE
        progressLabel.text = ""
        topicLabel.text = ""
        stemLabel.text = getString(com.ichi2.anki.R.string.speedrun_study_finished)
        if (mode == MODE_STANDALONE) {
            nextButton.visibility = View.GONE
            return
        }
        completed = true
        atEnd = true
        nextButton.text = getString(com.ichi2.anki.R.string.speedrun_session_continue)
        nextButton.visibility = View.VISIBLE
    }

    private fun finishWithResult() {
        val resumeIndex = if (answeredCurrent) index + 1 else index
        val data =
            Intent().apply {
                putExtra(RESULT_COMPLETED, completed)
                putExtra(RESULT_SHOWN_NOTE_IDS, shownNoteIds.toLongArray())
                putExtra(RESULT_INVOLVED_TOPICS, involvedTopics.toTypedArray())
                putExtra(RESULT_MISSED_TOPICS, missedTopics.toTypedArray())
                putExtra(RESULT_ANSWERED, answeredCount)
                putExtra(RESULT_CORRECT, correctCount)
                putExtra(RESULT_ACTIVATED, activatedTotal)
                putExtra(RESULT_RESUME_INDEX, resumeIndex)
            }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun textView(
        bold: Boolean = false,
        sizeSp: Float = 15f,
    ): TextView =
        TextView(this).apply {
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val MODE_STANDALONE = "standalone"
        const val MODE_PRACTICE = "practice"
        const val MODE_RECAP = "recap"

        const val EXTRA_MODE = "mode"
        const val EXTRA_NOTE_IDS = "noteIds"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_INDEX = "startIndex"
        const val EXTRA_ALLOW_SWEEP = "allowSweep"

        const val RESULT_COMPLETED = "completed"
        const val RESULT_SHOWN_NOTE_IDS = "shownNoteIds"
        const val RESULT_INVOLVED_TOPICS = "involvedTopics"
        const val RESULT_MISSED_TOPICS = "missedTopics"
        const val RESULT_ANSWERED = "answered"
        const val RESULT_CORRECT = "correct"
        const val RESULT_ACTIVATED = "activated"
        const val RESULT_RESUME_INDEX = "resumeIndex"

        private val MISS_BUTTONS =
            listOf(
                MissReason.KNOWLEDGE_GAP to com.ichi2.anki.R.string.speedrun_miss_knowledge_gap,
                MissReason.MISSING_CONTEXT to com.ichi2.anki.R.string.speedrun_miss_missing_context,
                MissReason.MISUNDERSTANDING to com.ichi2.anki.R.string.speedrun_miss_misunderstanding,
                MissReason.CARELESS to com.ichi2.anki.R.string.speedrun_miss_careless,
            )

        fun getIntent(
            context: Context,
            noteIds: List<NoteId>? = null,
            mode: String = MODE_STANDALONE,
            title: String? = null,
            startIndex: Int = 0,
        ): Intent =
            Intent(context, SpeedrunStudyActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                noteIds?.let { putExtra(EXTRA_NOTE_IDS, it.toLongArray()) }
                title?.let { putExtra(EXTRA_TITLE, it) }
                putExtra(EXTRA_START_INDEX, startIndex)
            }

        /** Split a question's `options` field into trimmed, non-empty lines. */
        fun optionLines(optionsField: String): List<String> = optionsField.lines().map { it.trim() }.filter { it.isNotEmpty() }

        /**
         * Resolve a `correct` field to a 0-based option index, or -1 if invalid.
         * Accepts a letter (A-D) or a 1-based number.
         */
        fun correctIndex(
            correctField: String,
            numOptions: Int,
        ): Int {
            val text = correctField.trim()
            if (text.isEmpty()) return -1
            val idx =
                if (text[0].isLetter()) {
                    text[0].uppercaseChar() - 'A'
                } else {
                    (text.toIntOrNull() ?: return -1) - 1
                }
            return if (idx in 0 until numOptions) idx else -1
        }
    }
}

/** Fields pulled from a `SpeedrunQuestion` note for a single practice item. */
private data class LoadedQuestion(
    val cardId: CardId?,
    val topic: String?,
    val stem: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val source: String,
)
