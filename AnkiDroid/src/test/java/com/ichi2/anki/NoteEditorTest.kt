/*
 Copyright (c) 2020 David Allison <davidallisongithub@gmail.com>

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3 of the License, or (at your option) any later
 version.

 This program is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 this program.  If not, see <http://www.gnu.org/licenses/>.
 */
@file:Suppress("SameParameterValue")

package com.ichi2.anki

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.config.ConfigKey
import com.ichi2.anim.ActivityTransitionAnimation.Direction.DEFAULT
import com.ichi2.anki.NoteEditorTest.FromScreen.DECK_LIST
import com.ichi2.anki.NoteEditorTest.FromScreen.REVIEWER
import com.ichi2.anki.api.AddContentApi.Companion.DEFAULT_DECK_ID
import com.ichi2.anki.common.annotations.DuplicatedCode
import com.ichi2.anki.dialogs.DeckSelectionDialog.SelectableDeck
import com.ichi2.anki.libanki.Consts
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Decks.Companion.CURRENT_DECK
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.noteeditor.NoteEditorLauncher
import com.ichi2.testutils.getString
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Ignore
import org.junit.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class NoteEditorTest : RobolectricTest() {
    @Test
    @Config(qualifiers = "en")
    fun verifyCardsList() {
        val n = getNoteEditorEditingExistingBasicNote("Test", "Note", DECK_LIST)
        assertThat(
            "Cards list is correct",
            (n.requireView().findViewById<TextView>(R.id.CardEditorCardsButton)).text.toString(),
            equalTo("Cards: Card 1"),
        )
    }

    @Test
    fun errorSavingNoteWithNoFirstFieldDisplaysNoFirstField() =
        runTest {
            val noteEditor =
                getNoteEditorAdding(NoteType.BASIC)
                    .withNoFirstField()
                    .build()
            noteEditor.saveNote()
            val actualResourceId = noteEditor.snackbarErrorText
            assertThat(actualResourceId, equalTo(CollectionManager.TR.addingTheFirstFieldIsEmpty()))
        }

    @Test
    fun testErrorMessageNull() =
        runTest {
            val noteEditor =
                getNoteEditorAdding(NoteType.BASIC)
                    .withNoFirstField()
                    .build()

            noteEditor.saveNote()
            assertThat(noteEditor.addNoteErrorMessage, equalTo(CollectionManager.TR.addingTheFirstFieldIsEmpty()))

            noteEditor.setFieldValueFromUi(0, "Hello")

            noteEditor.saveNote()
            assertThat(noteEditor.addNoteErrorMessage, equalTo(null))
        }

//    @Test
//    @RustCleanup("needs update for new backend")
//    fun errorSavingInvalidNoteWithAllFieldsDisplaysInvalidTemplate() {
//        val noteEditor = getNoteEditorAdding(NoteType.THREE_FIELD_INVALID_TEMPLATE)
//            .withFirstField("A")
//            .withSecondField("B")
//            .withThirdField("C")
//            .build()
//        val actualResourceId = noteEditor.addNoteErrorResource
//        assertThat(actualResourceId, equalTo(R.string.note_editor_no_cards_created_all_fields))
//    }
//
//    @Test
//    @RustCleanup("needs update for new backend")
//    fun errorSavingInvalidNoteWitSomeFieldsDisplaysEnterMore() {
//        val noteEditor = getNoteEditorAdding(NoteType.THREE_FIELD_INVALID_TEMPLATE)
//            .withFirstField("A")
//            .withThirdField("C")
//            .build()
//        val actualResourceId = noteEditor.addNoteErrorResource
//        assertThat(actualResourceId, equalTo(R.string.note_editor_no_cards_created))
//    }

    @Test
    fun errorSavingClozeNoteWithNoFirstFieldDisplaysClozeError() =
        runTest {
            val noteEditor =
                getNoteEditorAdding(NoteType.CLOZE)
                    .withNoFirstField()
                    .build()
            noteEditor.saveNote()
            val actualResourceId = noteEditor.snackbarErrorText
            assertThat(actualResourceId, equalTo(CollectionManager.TR.addingTheFirstFieldIsEmpty()))
        }

    @Test
    fun errorSavingClozeNoteWithNoClozeDeletionsDisplaysClozeError() =
        runTest {
            val noteEditor =
                getNoteEditorAdding(NoteType.CLOZE)
                    .withFirstField("NoCloze")
                    .build()
            noteEditor.saveNote()
            val actualResourceId = noteEditor.snackbarErrorText
            assertThat(
                actualResourceId,
                equalTo(CollectionManager.TR.addingYouHaveAClozeDeletionNote()),
            )
        }

    @Test
    fun errorSavingNoteWithNoTemplatesShowsNoCardsCreated() =
        runTest {
            val noteEditor =
                getNoteEditorAdding(NoteType.BACK_TO_FRONT)
                    .withFirstField("front is not enough")
                    .build()
            noteEditor.saveNote()
            val actualResourceId = noteEditor.snackbarErrorText
            assertThat(actualResourceId, equalTo(getString(R.string.note_editor_no_cards_created)))
        }

    @Test
    fun clozeNoteWithNoClozeDeletionsDoesNotSave() =
        runTest {
            val initialCards = cardCount
            val editor =
                getNoteEditorAdding(NoteType.CLOZE)
                    .withFirstField("no cloze deletions")
                    .build()
            editor.saveNote()
            assertThat(cardCount, equalTo(initialCards))
        }

    @Test
    fun clozeNoteWithClozeDeletionsDoesSave() =
        runTest {
            val initialCards = cardCount
            val editor =
                getNoteEditorAdding(NoteType.CLOZE)
                    .withFirstField("{{c1::AnkiDroid}} is fantastic")
                    .build()
            editor.saveNote()
            assertThat(cardCount, equalTo(initialCards + 1))
        }

    @Test
    @Ignore("Not yet implemented")
    fun clozeNoteWithClozeInWrongFieldDoesNotSave() =
        runTest {
            // Anki Desktop blocks with "Continue?", we should just block to match the above test
            val initialCards = cardCount
            val editor =
                getNoteEditorAdding(NoteType.CLOZE)
                    .withSecondField("{{c1::AnkiDroid}} is fantastic")
                    .build()
            editor.saveNote()
            assertThat(cardCount, equalTo(initialCards))
        }

    @Test
    fun verifyStartupAndCloseWithNoCollectionDoesNotCrash() {
        enableNullCollection()
        val intent = NoteEditorLauncher.AddNote().toIntent(targetContext)
        ActivityScenario.launchActivityForResult<NoteEditorActivity>(intent).use { scenario ->
            scenario.onNoteEditor { noteEditor ->
                noteEditor.requireActivity().onBackPressedDispatcher.onBackPressed()
                assertThat("Pressing back should finish the activity", noteEditor.requireActivity().isFinishing)
            }
            val result = scenario.result
            assertThat("Activity should be cancelled as no changes were made", result.resultCode, equalTo(Activity.RESULT_CANCELED))
        }
    }

    @Test
    fun testHandleMultimediaActionsDisplaysBottomSheet() {
        val intent = NoteEditorLauncher.AddNote().toIntent(targetContext)
        ActivityScenario.launchActivityForResult<NoteEditorActivity>(intent).use { scenario ->
            scenario.onNoteEditor { noteEditor ->
                noteEditor.showMultimediaBottomSheet()

                onView(withId(R.id.multimedia_action_image)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.multimedia_action_audio)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.multimedia_action_drawing)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.multimedia_action_recording)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.multimedia_action_video)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(R.id.multimedia_action_camera)).inRoot(isDialog()).check(matches(isDisplayed()))
            }
        }
    }

    @Test
    fun copyNoteCopiesDeckId() {
        val currentDid = addDeck("Basic::Test")
        col.config.set(CURRENT_DECK, currentDid)
        val n = super.addBasicNote("Test", "Note")
        n.notetype.did = currentDid
        val editor = getNoteEditorEditingExistingBasicNote("Test", "Note", DECK_LIST)
        col.config.set(CURRENT_DECK, Consts.DEFAULT_DECK_ID) // Change DID if going through default path
        val copyNoteBundle = getCopyNoteIntent(editor)
        val newNoteEditor = openNoteEditorWithArgs(copyNoteBundle)
        assertThat("Selected deck ID should be the current deck id", editor.deckId, equalTo(currentDid))
        assertThat(
            "Deck ID in the intent should be the selected deck id",
            copyNoteBundle.getLong(NoteEditorFragment.EXTRA_DID, -404L),
            equalTo(currentDid),
        )
        assertThat("Deck ID in the new note should be the ID provided in the intent", newNoteEditor.deckId, equalTo(currentDid))
    }

    @Test
    fun stickyFieldsAreUnchangedAfterAdd() =
        runTest {
            // #6795 - newlines were converted to <br>
            val basic = makeNoteForType(NoteType.BASIC)

            // Enable sticky "Front" field
            basic!!.fields[0].sticky = true
            val initFirstField = "Hello"
            val initSecondField = "unused"
            val newFirstField = "Hello" + FieldEditText.NEW_LINE + "World" // /r/n on Windows under Robolectric
            val editor =
                getNoteEditorAdding(NoteType.BASIC)
                    .withFirstField(initFirstField)
                    .withSecondField(initSecondField)
                    .build()
            assertThat(editor.currentFieldStrings.toList(), contains(initFirstField, initSecondField))
            editor.setFieldValueFromUi(0, newFirstField)
            assertThat(editor.currentFieldStrings.toList(), contains(newFirstField, initSecondField))

            editor.saveNote()
            advanceRobolectricLooper()
            val actual = editor.currentFieldStrings.toList()

            assertThat("newlines should be preserved, second field should be blanked", actual, contains(newFirstField, ""))
        }

    @Test
    fun processTextIntentShouldCopyFirstField() {
        ensureCollectionLoadIsSynchronous()
        val i = Intent(Intent.ACTION_PROCESS_TEXT)
        i.putExtra(Intent.EXTRA_PROCESS_TEXT, "hello\nworld")
        val editor = openNoteEditorWithArgs(i.extras!!, i.action)
        val actual = editor.currentFieldStrings.toList()

        assertThat(actual, contains("hello\nworld", ""))
    }

    @Test
    fun previewWorksWithNoError() {
        // #6923 regression test - Low value - Could not make this fail as onSaveInstanceState did not crash under Robolectric.
        val editor = getNoteEditorAddingNote(DECK_LIST)
        assertDoesNotThrow { runBlocking { editor.performPreview() } }
    }

    @Test
    fun clearFieldWorks() {
        // #7522
        val editor = getNoteEditorAddingNote(DECK_LIST)
        editor.setFieldValueFromUi(1, "Hello")
        assertThat(editor.currentFieldStrings[1], equalTo("Hello"))
        editor.clearField(1)
        assertThat(editor.currentFieldStrings[1], equalTo(""))
    }

    @Test
    fun insertIntoFocusedFieldStartsAtSelection() {
        val editor = getNoteEditorAddingNote(DECK_LIST)
        val field: EditText = editor.getFieldForTest(0)
        editor.insertStringInField(field, "Hello")
        field.setSelection(3)
        editor.insertStringInField(field, "World")
        assertThat(editor.getFieldForTest(0).text.toString(), equalTo("HelWorldlo"))
    }

    @Test
    fun insertIntoFocusedFieldReplacesSelection() {
        val editor = getNoteEditorAddingNote(DECK_LIST)
        val field: EditText = editor.getFieldForTest(0)
        editor.insertStringInField(field, "12345")
        field.setSelection(2, 3) // select "3"
        editor.insertStringInField(field, "World")
        assertThat(editor.getFieldForTest(0).text.toString(), equalTo("12World45"))
    }

    @Test
    fun insertIntoFocusedFieldReplacesSelectionIfBackwards() {
        // selections can be backwards if the user uses keyboards
        val editor = getNoteEditorAddingNote(DECK_LIST)
        val field: EditText = editor.getFieldForTest(0)
        editor.insertStringInField(field, "12345")
        field.setSelection(3, 2) // select "3" (right to left)
        editor.insertStringInField(field, "World")
        assertThat(editor.getFieldForTest(0).text.toString(), equalTo("12World45"))
    }

    @Test
    fun defaultsToCapitalized() {
        // Requested in #3758, this seems like a sensible default
        val editor = getNoteEditorAddingNote(DECK_LIST)
        assertThat("Fields should have their first word capitalized by default", editor.getFieldForTest(0).isCapitalized, equalTo(true))
    }

    @Test
    fun pasteHtmlAsPlainTextTest() {
        val editor = getNoteEditorAddingNote(DECK_LIST)
        editor.setCurrentlySelectedNoteType(col.notetypes.byName("Basic")!!.id)
        val field = editor.getFieldForTest(0)
        field.clipboard!!.setPrimaryClip(ClipData.newHtmlText("text", "text", """<span style="color: red">text</span>"""))
        assertTrue(field.clipboard!!.hasPrimaryClip())
        assertNotNull(field.clipboard!!.primaryClip)

        // test pasting in the middle (cursor mode: selecting)
        editor.setField(0, "012345")
        field.setSelection(1, 2) // selecting "1"
        assertTrue(field.pastePlainText())
        assertEquals("0text2345", field.fieldText)
        assertEquals(5, field.selectionStart)
        assertEquals(5, field.selectionEnd)

        // test pasting in the middle (cursor mode: selecting backwards)
        editor.setField(0, "012345")
        field.setSelection(2, 1) // selecting "1"
        assertTrue(field.pastePlainText())
        assertEquals("0text2345", field.fieldText)
        assertEquals(5, field.selectionStart)
        assertEquals(5, field.selectionEnd)

        // test pasting in the middle (cursor mode: normal)
        editor.setField(0, "012345")
        field.setSelection(4) // after "3"
        assertTrue(field.pastePlainText())
        assertEquals("0123text45", field.fieldText)
        assertEquals(8, field.selectionStart)
        assertEquals(8, field.selectionEnd)

        // test pasting at the start
        editor.setField(0, "012345")
        field.setSelection(0) // before "0"
        assertTrue(field.pastePlainText())
        assertEquals("text012345", field.fieldText)
        assertEquals(4, field.selectionStart)
        assertEquals(4, field.selectionEnd)

        // test pasting at the end
        editor.setField(0, "012345")
        field.setSelection(6) // after "5"
        assertTrue(field.pastePlainText())
        assertEquals("012345text", field.fieldText)
        assertEquals(10, field.selectionStart)
        assertEquals(10, field.selectionEnd)
    }

    @Test
    fun `can open with corrupt current deck - Issue 14096`() {
        col.config.set(CURRENT_DECK, '"' + "1688546411954" + '"')
        getNoteEditorAddingNote(DECK_LIST).apply {
            assertThat("current deck is default after corruption", deckId, equalTo(DEFAULT_DECK_ID))
        }
    }

    @Test
    fun `can switch two image occlusion note types 15579`() {
        val otherOcclusion = getSecondImageOcclusionNoteType()
        getNoteEditorAdding(NoteType.IMAGE_OCCLUSION).build().apply {
            val position = requireNotNull(noteTypeSpinner!!.getItemIndex(otherOcclusion.name)) { "could not find ${otherOcclusion.name}" }
            noteTypeSpinner!!.setSelection(position)
        }
    }

    @Test
    fun `edit note in filtered deck from reviewer - 15919`() {
        // TODO: As a future extension, the filtered deck should be displayed
        // in the UI
        addDeck("A")

        // by default, the first deck is selected, so move the card to the second deck
        val homeDeckId = addDeck("B", setAsSelected = true)
        val note = addBasicNote().updateCards { did = homeDeckId }
        moveToDynamicDeck(note)

        // ensure note is correctly setup
        assertThat("home deck", note.firstCard().oDid, equalTo(homeDeckId))
        assertThat("current deck", note.firstCard().did, not(equalTo(homeDeckId)))

        // act
        val editor = getNoteEditorEditingExistingBasicNote(note, REVIEWER)

        // assert
        assertThat("current deck is the home deck", editor.deckId, equalTo(homeDeckId))
        assertThat("no unsaved changes", !editor.hasUnsavedChanges())
    }

    @Test
    fun `decide by note type preference - 13931`() =
        runTest {
            col.config.setBool(ConfigKey.Bool.ADDING_DEFAULTS_TO_CURRENT_DECK, false)
            addDeck("Basic")
            val reversedDeckId = addDeck("Reversed", setAsSelected = true)

            assertThat("setup: deckId", col.notetypes.byName("Basic")!!.did, equalTo(1))

            getNoteEditorAdding(NoteType.BASIC).build().also { editor ->
                editor.onDeckSelected(SelectableDeck(reversedDeckId, "Reversed"))
                editor.setField(0, "Hello")
                editor.saveNote()
            }

            col.notetypes.clearCache()

            assertThat("a note was added", col.noteCount(), equalTo(1))
            assertThat("note type deck is updated", col.notetypes.byName("Basic")!!.did, equalTo(reversedDeckId))

            getNoteEditorAdding(NoteType.BASIC).build().also { editor ->
                assertThat("Deck ID is remembered", editor.deckId, equalTo(reversedDeckId))
            }
        }

    @Test
    fun `editing card in filtered deck retains deck`() =
        runTest {
            val homeDeckId = addDeck("A")
            val note = addBasicNote().updateCards { did = homeDeckId }
            moveToDynamicDeck(note)

            // ensure note is correctly setup
            assertThat("home deck", note.firstCard().oDid, equalTo(homeDeckId))
            assertThat("current deck", note.firstCard().did, not(equalTo(homeDeckId)))

            getNoteEditorEditingExistingBasicNote(note, REVIEWER).apply {
                setField(0, "Hello")
                saveNote()
            }

            // ensure note is correctly setup
            assertThat("after: home deck", note.firstCard().oDid, equalTo(homeDeckId))
            assertThat("after: current deck", note.firstCard().did, not(equalTo(homeDeckId)))
        }

    private fun moveToDynamicDeck(note: Note): DeckId {
        val dyn = addDynamicDeck("All")
        col.decks.select(dyn)
        col.sched.rebuildDyn()
        assertThat("card is in dynamic deck", note.firstCard().did, equalTo(dyn))
        return dyn
    }

    private fun getSecondImageOcclusionNoteType(): NotetypeJson {
        val imageOcclusionNotes = col.notetypes.filter { it.isImageOcclusion }
        return if (imageOcclusionNotes.size >= 2) {
            imageOcclusionNotes.first { it.name != "Image Occlusion" }
        } else {
            col.notetypes.byName("Image Occlusion")!!.createClone()
        }
    }

    private fun getCopyNoteIntent(editor: NoteEditorFragment): Bundle {
        val editorShadow = shadowOf(editor.requireActivity())
        editor.copyNote()
        val intent = editorShadow.peekNextStartedActivityForResult().intent
        return intent.extras ?: Bundle()
    }

    private fun Spinner.getItemIndex(toFind: Any): Int? {
        for (i in 0 until count) {
            if (this.getItemAtPosition(i) != toFind) continue
            return i
        }
        return null
    }

    private val cardCount: Int
        get() = col.cardCount()

    private fun getNoteEditorAdding(noteType: NoteType): NoteEditorTestBuilder {
        val n = makeNoteForType(noteType)
        return NoteEditorTestBuilder(n)
    }

    private fun makeNoteForType(noteType: NoteType): NotetypeJson? =
        when (noteType) {
            NoteType.BASIC -> col.notetypes.byName("Basic")
            NoteType.CLOZE -> col.notetypes.byName("Cloze")
            NoteType.BACK_TO_FRONT -> {
                val name = super.addStandardNoteType("Reversed", arrayOf("Front", "Back"), "{{Back}}", "{{Front}}")
                col.notetypes.byName(name)
            }
            NoteType.THREE_FIELD_INVALID_TEMPLATE -> {
                val name = super.addStandardNoteType("Invalid", arrayOf("Front", "Back", "Side"), "", "")
                col.notetypes.byName(name)
            }
            NoteType.IMAGE_OCCLUSION -> col.notetypes.byName("Image Occlusion")
        }

    private fun getNoteEditorAddingNote(from: FromScreen): NoteEditorFragment {
        ensureCollectionLoadIsSynchronous()
        val bundle =
            when (from) {
                REVIEWER -> NoteEditorLauncher.AddNoteFromReviewer().toBundle()
                DECK_LIST -> NoteEditorLauncher.AddNote().toBundle()
            }
        return openNoteEditorWithArgs(bundle)
    }

    private fun getNoteEditorEditingExistingBasicNote(
        front: String,
        back: String,
        from: FromScreen,
    ): NoteEditorFragment {
        val n = super.addBasicNote(front, back)
        return getNoteEditorEditingExistingBasicNote(n, from)
    }

    private fun getNoteEditorEditingExistingBasicNote(
        n: Note,
        from: FromScreen,
    ): NoteEditorFragment {
        val bundle =
            when (from) {
                REVIEWER -> NoteEditorLauncher.EditCard(n.firstCard().id, DEFAULT).toBundle()
                DECK_LIST -> NoteEditorLauncher.AddNote().toBundle()
            }
        return openNoteEditorWithArgs(bundle)
    }

    fun openNoteEditorWithArgs(
        arguments: Bundle,
        action: String? = null,
    ): NoteEditorFragment {
        val activity =
            startActivityNormallyOpenCollectionWithIntent(
                NoteEditorActivity::class.java,
                NoteEditorLauncher.PassArguments(arguments).toIntent(targetContext, action),
            )
        return activity.getNoteEditorFragment()
    }

    @DuplicatedCode("NoteEditor in androidTest")
    @Throws(Throwable::class)
    fun ActivityScenario<NoteEditorActivity>.onNoteEditor(block: (NoteEditorFragment) -> Unit) {
        val wrapped = AtomicReference<Throwable?>(null)
        this.onActivity { activity: NoteEditorActivity ->
            try {
                val editor = activity.getNoteEditorFragment()
                block(editor)
            } catch (t: Throwable) {
                wrapped.set(t)
            }
        }
        wrapped.get()?.let { throw it }
    }

    @DuplicatedCode("NoteEditor in androidTest")
    fun NoteEditorActivity.getNoteEditorFragment(): NoteEditorFragment =
        supportFragmentManager.findFragmentById(R.id.note_editor_fragment_frame) as NoteEditorFragment

    private enum class FromScreen {
        DECK_LIST,
        REVIEWER,
    }

    /** We don't use constants here to allow for additional note types to be defined  */
    private enum class NoteType {
        BASIC,
        CLOZE,

        /**Basic, but Back is on the front  */
        BACK_TO_FRONT,
        THREE_FIELD_INVALID_TEMPLATE,
        IMAGE_OCCLUSION,
    }

    inner class NoteEditorTestBuilder(
        notetype: NotetypeJson?,
    ) {
        private val notetype: NotetypeJson
        private var firstField: String? = null
        private var secondField: String? = null

        fun build(): NoteEditorFragment {
            val editor = buildInternal()
            advanceRobolectricLooper()
            advanceRobolectricLooper()
            advanceRobolectricLooper()
            advanceRobolectricLooper()
            // 4 is insufficient
            advanceRobolectricLooper()
            advanceRobolectricLooper()
            return editor
        }

        fun buildInternal(): NoteEditorFragment {
            col.notetypes.setCurrent(notetype)
            val noteEditor = getNoteEditorAddingNote(REVIEWER)
            advanceRobolectricLooper()
            // image occlusion does not need a first field
            if (this.firstField != null) {
                noteEditor.setFieldValueFromUi(0, firstField)
            }
            if (secondField != null) {
                noteEditor.setFieldValueFromUi(1, secondField)
            }
            return noteEditor
        }

        fun withNoFirstField(): NoteEditorTestBuilder = this

        fun withFirstField(text: String?): NoteEditorTestBuilder {
            firstField = text
            return this
        }

        fun withSecondField(text: String?): NoteEditorTestBuilder {
            secondField = text
            return this
        }

        init {
            assertNotNull(notetype) { "model was null" }
            this.notetype = notetype
        }
    }
}
