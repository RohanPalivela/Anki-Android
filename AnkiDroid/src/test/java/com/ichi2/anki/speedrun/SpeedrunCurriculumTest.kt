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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.testutils.EmptyApplication
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Parity guards for the curriculum layer's pure pieces (the collection-backed
 * `curriculum()` / `weakConcepts()` need the libanki test harness, which is
 * exercised on desktop by `pylib/tests/test_speedrun.py`). These lock the
 * cross-language contract Android must keep byte-identical to pylib:
 * concept-label humanisation and the JSON shape the shared home page consumes.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class SpeedrunCurriculumTest {
    @Test
    fun humanizeSlugMatchesPylib() {
        // Mirrors pylib's humanize_slug: only the first character is upper-cased.
        assertThat(Speedrun.humanizeSlug("amino-acids"), equalTo("Amino acids"))
        assertThat(Speedrun.humanizeSlug("work-energy"), equalTo("Work energy"))
        assertThat(Speedrun.humanizeSlug("biology"), equalTo("Biology"))
        assertThat(Speedrun.humanizeSlug(""), equalTo(""))
    }

    @Test
    fun conceptAccuracyAndPractisedDerivedFromAnswers() {
        val fresh = concept(answered = 0, correct = 0)
        assertThat(fresh.practiced, equalTo(false))
        assertThat(fresh.accuracy, closeTo(0.0, 1e-9))

        val half = concept(answered = 4, correct = 2)
        assertThat(half.practiced, equalTo(true))
        assertThat(half.accuracy, closeTo(0.5, 1e-9))
    }

    @Test
    fun conceptJsonExposesTheContractKeys() {
        val json = concept(answered = 2, correct = 1).toJson()
        for (
        key in
        listOf(
            "concept",
            "label",
            "topic",
            "servedQuestions",
            "lessonCards",
            "answered",
            "correct",
            "lessonsActivated",
            "lessonsReviewed",
            "accuracy",
            "practiced",
        )
        ) {
            assertThat("missing key $key", json.has(key), equalTo(true))
        }
        assertThat(json.getBoolean("practiced"), equalTo(true))
        assertThat(json.getDouble("accuracy"), closeTo(0.5, 1e-9))
    }

    @Test
    fun curriculumJsonExposesTopLevelKeys() {
        val curriculum =
            Speedrun.Curriculum(
                topics =
                    listOf(
                        Speedrun.TopicProgress(
                            topic = "biology",
                            label = "Biology",
                            weight = 0.18,
                            mastery = 0.0,
                            masteryKnown = false,
                            concepts = listOf(concept(answered = 0, correct = 0)),
                            servedQuestions = 2,
                            lessonCards = 1,
                            answered = 0,
                            correct = 0,
                        ),
                    ),
                overallMastery = 0.0,
                masteryAbstained = true,
            )
        val json = curriculum.toJson()
        assertThat(json.has("topics"), equalTo(true))
        assertThat(json.has("overallMastery"), equalTo(true))
        assertThat(json.has("masteryAbstained"), equalTo(true))
        assertThat(json.getJSONArray("topics").length(), equalTo(1))
    }

    private fun concept(
        answered: Int,
        correct: Int,
    ) = Speedrun.ConceptProgress(
        concept = "glycolysis",
        label = "Glycolysis",
        topic = "biology",
        servedQuestions = 2,
        lessonCards = 1,
        answered = answered,
        correct = correct,
        lessonsActivated = 0,
        lessonsReviewed = 0,
    )
}
