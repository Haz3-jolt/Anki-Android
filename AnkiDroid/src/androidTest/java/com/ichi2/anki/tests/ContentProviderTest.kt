/***************************************************************************************
 *                                                                                      *
 * Copyright (c) 2015 Frank Oltmanns <frank.oltmanns@gmail.com>                         *
 * Copyright (c) 2015 Timothy Rae <timothy.rae@gmail.com>                               *
 * Copyright (c) 2016 Mark Carter <mark@marcardar.com>                                  *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/
package com.ichi2.anki.tests

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWindow
import android.net.Uri
import anki.notetypes.StockNotetype
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.common.utils.annotation.KotlinCleanup
import com.ichi2.anki.common.utils.emptyStringArray
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Decks
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteTypeId
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.libanki.Notetypes
import com.ichi2.anki.libanki.QueueType
import com.ichi2.anki.libanki.Utils
import com.ichi2.anki.libanki.addNotetypeLegacy
import com.ichi2.anki.libanki.backend.BackendUtils
import com.ichi2.anki.libanki.exception.ConfirmModSchemaException
import com.ichi2.anki.libanki.getStockNotetype
import com.ichi2.anki.libanki.sched.Scheduler
import com.ichi2.anki.provider.pureAnswer
import com.ichi2.anki.testutil.DatabaseUtils.cursorFillWindow
import com.ichi2.anki.testutil.GrantStoragePermission.storagePermission
import com.ichi2.anki.testutil.addNote
import com.ichi2.anki.testutil.grantPermissions
import com.ichi2.testutils.common.assertThrows
import kotlinx.serialization.json.Json
import net.ankiweb.rsdroid.exceptions.BackendNotFoundException
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
import org.json.JSONObject.NULL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import timber.log.Timber
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.junit.JUnitAsserter.assertNotNull

/**
 * Test cases for [com.ichi2.anki.provider.CardContentProvider].
 *
 *
 * These tests should cover all supported operations for each URI.
 */
class ContentProviderTest : InstrumentedTest() {
    @get:Rule
    var runtimePermissionRule = grantPermissions(storagePermission, FlashCardsContract.READ_WRITE_PERMISSION)

    // Whether tear down should be executed. I.e. if set up was not cancelled.
    private var tearDown = false

    private var numDecksBeforeTest = 0

    /* initialCapacity set to expected value when the test is written.
     * Should create no problem if we forget to change it when more tests are added.
     */
    private val testDeckIds: MutableList<Long> = ArrayList(TEST_DECKS.size + 1)
    private lateinit var createdNotes: ArrayList<Uri>
    private var noteTypeId: NoteTypeId = 0L
    private var dummyFields = emptyStringArray(1)

    /**
     * Initially create one note for each note type.
     */
    @Before
    fun setUp() {
        Timber.i("setUp()")
        createdNotes = ArrayList()
        tearDown = true
        // Add a new basic note type that we use for testing purposes (existing note types could potentially be corrupted)
        val noteType = createBasicNoteType()
        noteTypeId = noteType.id
        val fields = noteType.fieldsNames
        // Use the names of the fields as test values for the notes which will be added
        dummyFields = fields.toTypedArray()
        // create test decks and add one note for every deck
        numDecksBeforeTest = col.decks.count()
        for (fullName in TEST_DECKS) {
            val path = Decks.path(fullName)
            var partialName: String? = ""
            /* Looping over all parents of full name. Adding them to
             * mTestDeckIds ensures the deck parents decks get deleted
             * too at tear-down.
             */
            for (s in path) {
                partialName += s
                /* If parent already exists, don't add the deck, so
                 * that we are sure it won't get deleted at
                 * set-down, */
                val did = col.decks.byName(partialName!!)?.id ?: col.decks.id(partialName)
                testDeckIds.add(did)
                createdNotes.add(setupNewNote(col, noteTypeId, did, dummyFields, TEST_TAG))
                partialName += "::"
            }
        }
        // Add a note to the default deck as well so that testQueryNextCard() works
        createdNotes.add(setupNewNote(col, noteTypeId, 1, dummyFields, TEST_TAG))
    }

    private fun createBasicNoteType(name: String = BASIC_NOTE_TYPE_NAME): NotetypeJson {
        val noteType =
            col
                .getStockNotetype(StockNotetype.Kind.KIND_BASIC)
                .also { it.name = name }
        col.addNotetypeLegacy(BackendUtils.toJsonBytes(noteType))
        return col.notetypes.byName(name)!!
    }

    /**
     * Remove the notes and decks created in setUp().
     */
    @After
    @Throws(Exception::class)
    fun tearDown() {
        Timber.i("tearDown()")
        if (!tearDown) {
            return
        }
        // Delete all notes
        val remnantNotes = col.findNotes("tag:$TEST_TAG")
        if (remnantNotes.isNotEmpty()) {
            col.removeNotes(noteIds = remnantNotes)

            assertEquals(
                "Check that remnant notes have been deleted",
                0,
                col.findNotes("tag:$TEST_TAG").size,
            )
        }
        // delete test decks
        col.decks.remove(testDeckIds)
        assertEquals(
            "Check that all created decks have been deleted",
            numDecksBeforeTest,
            col.decks.count(),
        )
        // Delete test note type
        col.modSchemaNoCheck()
        removeAllNoteTypesByName(col, BASIC_NOTE_TYPE_NAME)
        removeAllNoteTypesByName(col, TEST_NOTE_TYPE_NAME)
    }

    @Throws(Exception::class)
    private fun removeAllNoteTypesByName(
        col: com.ichi2.anki.libanki.Collection,
        name: String,
    ) {
        var testNoteType = col.notetypes.byName(name)
        while (testNoteType != null) {
            col.notetypes.rem(testNoteType)
            testNoteType = col.notetypes.byName(name)
        }
    }

    @Test
    fun testDatabaseUtilsInvocationWorks() {
        // called by android.database.CursorToBulkCursorAdapter
        // This is called by API clients implicitly, but isn't done by this test class
        val firstNote = getFirstCardFromScheduler(col)
        val noteProjection =
            arrayOf(
                FlashCardsContract.Note._ID,
                FlashCardsContract.Note.FLDS,
                FlashCardsContract.Note.TAGS,
            )
        val resolver = contentResolver
        val cursor =
            resolver.query(
                FlashCardsContract.Note.CONTENT_URI_V2,
                noteProjection,
                "id=" + firstNote!!.nid,
                null,
                null,
            )
        assertNotNull(cursor)
        val window = CursorWindow("test")

        // Note: We duplicated the code as it did not appear to be accessible via reflection
        val initialPosition = cursor.position
        cursorFillWindow(cursor, 0, window)
        assertThat("position should not change", cursor.position, equalTo(initialPosition))
        assertThat("Count should be copied", window.numRows, equalTo(cursor.count))
    }

    /**
     * Check that inserting and removing a note into default deck works as expected
     */
    @Test
    fun testInsertAndRemoveNote() {
        // Get required objects for test
        val cr = contentResolver
        // Add the note
        val values =
            ContentValues().apply {
                put(FlashCardsContract.Note.MID, noteTypeId)
                put(FlashCardsContract.Note.FLDS, Utils.joinFields(TEST_NOTE_FIELDS))
                put(FlashCardsContract.Note.TAGS, TEST_TAG)
            }
        val newNoteUri = cr.insert(FlashCardsContract.Note.CONTENT_URI, values)
        assertNotNull("Check that URI returned from addNewNote is not null", newNoteUri)
        val col = reopenCol() // test that the changes are physically saved to the DB
        // Check that it looks as expected
        assertNotNull("check note URI path", newNoteUri!!.lastPathSegment)
        val addedNote = Note(col, newNoteUri.lastPathSegment!!.toLong())
        addedNote.load(col)
        assertEquals(
            "Check that fields were set correctly",
            addedNote.fields,
            TEST_NOTE_FIELDS.toMutableList(),
        )
        assertEquals("Check that tag was set correctly", TEST_TAG, addedNote.tags[0])
        val noteType: NotetypeJson? = col.notetypes.get(noteTypeId)
        assertNotNull("Check note type", noteType)
        val expectedNumCards = noteType!!.templates.length()
        assertEquals("Check that correct number of cards generated", expectedNumCards, addedNote.numberOfCards(col))
        // Now delete the note
        cr.delete(newNoteUri, null, null)

        assertThrows<RuntimeException>("RuntimeException is thrown when deleting note") {
            addedNote.load(col)
        }
    }

    /**
     * Check that inserting a note with an invalid noteTypeId returns a reasonable exception
     */
    @Test
    fun testInsertNoteWithBadNoteTypeId() {
        val invalidNoteTypeId = 12
        val values =
            ContentValues().apply {
                put(FlashCardsContract.Note.MID, invalidNoteTypeId)
                put(FlashCardsContract.Note.FLDS, Utils.joinFields(TEST_NOTE_FIELDS))
                put(FlashCardsContract.Note.TAGS, TEST_TAG)
            }
        assertThrows<BackendNotFoundException> {
            contentResolver.insert(FlashCardsContract.Note.CONTENT_URI, values)
        }
    }

    /**
     * Check that inserting and removing a note into default deck works as expected
     */
    @Test
    @Throws(Exception::class)
    fun testInsertTemplate() {
        // Get required objects for test
        val cr = contentResolver
        var col = col
        // Add a new basic note type that we use for testing purposes (existing note types could potentially be corrupted)
        var noteType: NotetypeJson? = createBasicNoteType()
        val noteTypeId = noteType!!.id
        // Add the note
        val noteTypeUri = ContentUris.withAppendedId(FlashCardsContract.Model.CONTENT_URI, noteTypeId)
        val testIndex =
            TEST_NOTE_TYPE_CARDS.size - 1 // choose the last one because not the same as the basic note type template
        val expectedOrd = noteType.templates.length()
        val cv =
            ContentValues().apply {
                put(FlashCardsContract.CardTemplate.NAME, TEST_NOTE_TYPE_CARDS[testIndex])
                put(FlashCardsContract.CardTemplate.QUESTION_FORMAT, TEST_NOTE_TYPE_QFMT[testIndex])
                put(FlashCardsContract.CardTemplate.ANSWER_FORMAT, TEST_NOTE_TYPE_AFMT[testIndex])
                put(FlashCardsContract.CardTemplate.BROWSER_QUESTION_FORMAT, TEST_NOTE_TYPE_QFMT[testIndex])
                put(FlashCardsContract.CardTemplate.BROWSER_ANSWER_FORMAT, TEST_NOTE_TYPE_AFMT[testIndex])
            }
        val templatesUri = Uri.withAppendedPath(noteTypeUri, "templates")
        val templateUri = cr.insert(templatesUri, cv)
        col = reopenCol() // test that the changes are physically saved to the DB
        assertNotNull("Check template uri", templateUri)
        assertEquals(
            "Check template uri ord",
            expectedOrd.toLong(),
            ContentUris.parseId(
                templateUri!!,
            ),
        )
        noteType = col.notetypes.get(noteTypeId)
        assertNotNull("Check note type", noteType)
        val template = noteType!!.templates[expectedOrd]
        assertEquals(
            "Check template JSONObject ord",
            expectedOrd,
            template.ord,
        )
        assertEquals(
            "Check template name",
            TEST_NOTE_TYPE_CARDS[testIndex],
            template.name,
        )
        assertEquals("Check qfmt", TEST_NOTE_TYPE_QFMT[testIndex], template.qfmt)
        assertEquals("Check afmt", TEST_NOTE_TYPE_AFMT[testIndex], template.afmt)
        assertEquals("Check bqfmt", TEST_NOTE_TYPE_QFMT[testIndex], template.bqfmt)
        assertEquals("Check bafmt", TEST_NOTE_TYPE_AFMT[testIndex], template.bafmt)
        col.notetypes.rem(noteType)
    }

    /**
     * Check that inserting and removing a note into default deck works as expected
     */
    @Test
    @Throws(Exception::class)
    fun testInsertField() {
        // Get required objects for test
        val cr = contentResolver
        var col = col
        var noteType: NotetypeJson? = createBasicNoteType()
        val noteTypeId = noteType!!.id
        val initialFieldsArr = noteType.fields
        val initialFieldCount = initialFieldsArr.length()
        val noteTypeUri = ContentUris.withAppendedId(FlashCardsContract.Model.CONTENT_URI, noteTypeId)
        val insertFieldValues = ContentValues()
        insertFieldValues.put(FlashCardsContract.Model.FIELD_NAME, TEST_FIELD_NAME)
        val fieldUri = cr.insert(Uri.withAppendedPath(noteTypeUri, "fields"), insertFieldValues)
        assertNotNull("Check field uri", fieldUri)
        // Ensure that the changes are physically saved to the DB
        col = reopenCol()
        noteType = col.notetypes.get(noteTypeId)
        // Test the field is as expected
        val fieldId = ContentUris.parseId(fieldUri!!)
        assertEquals("Check field id", initialFieldCount.toLong(), fieldId)
        assertNotNull("Check note type", noteType)
        val fldsArr = noteType!!.fields
        assertEquals(
            "Check fields length",
            (initialFieldCount + 1),
            fldsArr.length(),
        )
        assertEquals(
            "Check last field name",
            TEST_FIELD_NAME,
            fldsArr.last().name,
        )
        col.notetypes.rem(noteType)
    }

    /**
     * Test queries to notes table using direct SQL URI
     */
    @Test
    fun testQueryDirectSqlQuery() {
        // search for correct mid
        val cr = contentResolver
        cr
            .query(
                FlashCardsContract.Note.CONTENT_URI_V2,
                null,
                "mid=$noteTypeId",
                null,
                null,
            ).use { cursor ->
                assertNotNull(cursor)
                assertEquals(
                    "Check number of results",
                    createdNotes.size,
                    cursor.count,
                )
            }
        // search for bogus mid
        cr.query(FlashCardsContract.Note.CONTENT_URI_V2, null, "mid=0", null, null).use { cursor ->
            assertNotNull(cursor)
            assertEquals("Check number of results", 0, cursor.count)
        }
        // check usage of selection args
        cr.query(FlashCardsContract.Note.CONTENT_URI_V2, null, "mid=?", arrayOf("0"), null).use { cursor ->
            assertNotNull(cursor)
        }
    }

    /**
     * Test that a query for all the notes added in setup() looks correct
     */
    @Test
    fun testQueryNoteIds() {
        val cr = contentResolver
        // Query all available notes
        val allNotesCursor =
            cr.query(FlashCardsContract.Note.CONTENT_URI, null, "tag:$TEST_TAG", null, null)
        assertNotNull(allNotesCursor)
        allNotesCursor.use {
            assertEquals(
                "Check number of results",
                createdNotes.size,
                it.count,
            )
            while (it.moveToNext()) {
                // Check that it's possible to leave out columns from the projection
                for (i in FlashCardsContract.Note.DEFAULT_PROJECTION.indices) {
                    val projection =
                        removeFromProjection(FlashCardsContract.Note.DEFAULT_PROJECTION, i)
                    val noteId =
                        it.getString(it.getColumnIndex(FlashCardsContract.Note._ID))
                    val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId)
                    cr.query(noteUri, projection, null, null, null).use { singleNoteCursor ->
                        assertNotNull(
                            "Check that there is a valid cursor for detail data",
                            singleNoteCursor,
                        )
                        assertEquals(
                            "Check that there is exactly one result",
                            1,
                            singleNoteCursor!!.count,
                        )
                        assertTrue(
                            "Move to beginning of cursor after querying for detail data",
                            singleNoteCursor.moveToFirst(),
                        )
                        // Check columns
                        assertEquals(
                            "Check column count",
                            projection.size,
                            singleNoteCursor.columnCount,
                        )
                        for (j in projection.indices) {
                            assertEquals(
                                "Check column name $j",
                                projection[j],
                                singleNoteCursor.getColumnName(j),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Check that a valid Cursor is returned when querying notes table with non-default projections
     */
    @Test
    fun testQueryNotesProjection() {
        val cr = contentResolver
        // Query all available notes
        for (i in FlashCardsContract.Note.DEFAULT_PROJECTION.indices) {
            val projection = removeFromProjection(FlashCardsContract.Note.DEFAULT_PROJECTION, i)
            cr
                .query(
                    FlashCardsContract.Note.CONTENT_URI,
                    projection,
                    "tag:$TEST_TAG",
                    null,
                    null,
                ).use { allNotesCursor ->
                    assertNotNull("Check that there is a valid cursor", allNotesCursor)
                    assertEquals(
                        "Check number of results",
                        createdNotes.size,
                        allNotesCursor!!.count,
                    )
                    // Check columns
                    assertEquals(
                        "Check column count",
                        projection.size,
                        allNotesCursor.columnCount,
                    )
                    for (j in projection.indices) {
                        assertEquals(
                            "Check column name $j",
                            projection[j],
                            allNotesCursor.getColumnName(j),
                        )
                    }
                }
        }
    }

    @Suppress("SameParameterValue")
    private fun removeFromProjection(
        inputProjection: Array<String>,
        idx: Int,
    ): Array<String?> {
        val outputProjection = arrayOfNulls<String>(inputProjection.size - 1)
        if (idx >= 0) {
            System.arraycopy(inputProjection, 0, outputProjection, 0, idx)
        }
        for (i in idx + 1 until inputProjection.size) {
            outputProjection[i - 1] = inputProjection[i]
        }
        return outputProjection
    }

    /**
     * Check that updating the flds column works as expected
     * FIXME hanging sometimes. API30? API29?
     */
    @Test
    fun testUpdateNoteFields() {
        val cr = contentResolver
        val cv = ContentValues()
        // Change the fields so that the first field is now "newTestValue"
        val dummyFields2 = dummyFields.clone()
        dummyFields2[0] = TEST_FIELD_VALUE
        for (uri in createdNotes) {
            // Update the flds
            cv.put(FlashCardsContract.Note.FLDS, Utils.joinFields(dummyFields2))
            cr.update(uri, cv, null, null)
            cr
                .query(uri, FlashCardsContract.Note.DEFAULT_PROJECTION, null, null, null)
                .use { noteCursor ->
                    assertNotNull(
                        "Check that there is a valid cursor for detail data after update",
                        noteCursor,
                    )
                    assertEquals(
                        "Check that there is one and only one entry after update",
                        1,
                        noteCursor!!.count,
                    )
                    assertTrue("Move to first item in cursor", noteCursor.moveToFirst())
                    val newFields =
                        Utils.splitFields(
                            noteCursor.getString(noteCursor.getColumnIndex(FlashCardsContract.Note.FLDS)),
                        )
                    assertEquals(
                        "Check that the flds have been updated correctly",
                        newFields,
                        dummyFields2.toMutableList(),
                    )
                }
        }
    }

    /**
     * Check that inserting a new note type works as expected
     */
    @Test
    fun testInsertAndUpdateNoteType() {
        val cr = contentResolver
        var cv =
            ContentValues().apply {
                // Insert a new note type
                put(FlashCardsContract.Model.NAME, TEST_NOTE_TYPE_NAME)
                put(FlashCardsContract.Model.FIELD_NAMES, Utils.joinFields(TEST_NOTE_TYPE_FIELDS))
                put(FlashCardsContract.Model.NUM_CARDS, TEST_NOTE_TYPE_CARDS.size)
            }
        val noteTypeUri = cr.insert(FlashCardsContract.Model.CONTENT_URI, cv)
        assertNotNull("Check inserted note type isn't null", noteTypeUri)
        assertNotNull("Check last path segment exists", noteTypeUri!!.lastPathSegment)
        val noteTypeId = noteTypeUri.lastPathSegment!!.toLong()
        var col = reopenCol()
        try {
            var noteType = col.notetypes.get(noteTypeId)
            assertNotNull("Check note type", noteType)
            assertEquals("Check note type name", TEST_NOTE_TYPE_NAME, noteType!!.name)
            assertEquals(
                "Check templates length",
                TEST_NOTE_TYPE_CARDS.size,
                noteType.templates.length(),
            )
            assertEquals(
                "Check field length",
                TEST_NOTE_TYPE_FIELDS.size,
                noteType.fields.length(),
            )
            val fields = noteType.fields
            for (i in 0 until fields.length()) {
                assertEquals(
                    "Check name of fields",
                    TEST_NOTE_TYPE_FIELDS[i],
                    fields[i].name,
                )
            }
            // Test updating the note type CSS (to test updating NOTE_TYPES_ID Uri)
            cv = ContentValues()
            cv.put(FlashCardsContract.Model.CSS, TEST_NOTE_TYPE_CSS)
            assertThat(
                cr.update(noteTypeUri, cv, null, null),
                greaterThan(0),
            )
            col = reopenCol()
            noteType = col.notetypes.get(noteTypeId)
            assertNotNull("Check note type", noteType)
            assertEquals("Check css", TEST_NOTE_TYPE_CSS, noteType!!.css)
            // Update each of the templates in note type (to test updating NOTE_TYPES_ID_TEMPLATES_ID Uri)
            for (i in TEST_NOTE_TYPE_CARDS.indices) {
                cv =
                    ContentValues().apply {
                        put(FlashCardsContract.CardTemplate.NAME, TEST_NOTE_TYPE_CARDS[i])
                        put(FlashCardsContract.CardTemplate.QUESTION_FORMAT, TEST_NOTE_TYPE_QFMT[i])
                        put(FlashCardsContract.CardTemplate.ANSWER_FORMAT, TEST_NOTE_TYPE_AFMT[i])
                        put(FlashCardsContract.CardTemplate.BROWSER_QUESTION_FORMAT, TEST_NOTE_TYPE_QFMT[i])
                        put(FlashCardsContract.CardTemplate.BROWSER_ANSWER_FORMAT, TEST_NOTE_TYPE_AFMT[i])
                    }
                val tmplUri =
                    Uri.withAppendedPath(
                        Uri.withAppendedPath(noteTypeUri, "templates"),
                        i.toString(),
                    )
                assertThat(
                    "Update rows",
                    cr.update(tmplUri, cv, null, null),
                    greaterThan(0),
                )
                col = reopenCol()
                noteType = col.notetypes.get(noteTypeId)
                assertNotNull("Check note type", noteType)
                val template = noteType!!.templates[i]
                assertEquals(
                    "Check template name",
                    TEST_NOTE_TYPE_CARDS[i],
                    template.name,
                )
                assertEquals("Check qfmt", TEST_NOTE_TYPE_QFMT[i], template.qfmt)
                assertEquals("Check afmt", TEST_NOTE_TYPE_AFMT[i], template.afmt)
                assertEquals("Check bqfmt", TEST_NOTE_TYPE_QFMT[i], template.bqfmt)
                assertEquals("Check bafmt", TEST_NOTE_TYPE_AFMT[i], template.bafmt)
            }
        } finally {
            // Delete the note type (this will force a full-sync)
            col.modSchemaNoCheck()
            try {
                val noteType = col.notetypes.get(noteTypeId)
                assertNotNull("Check note type", noteType)
                col.notetypes.rem(noteType!!)
            } catch (e: ConfirmModSchemaException) {
                // This will never happen
            }
        }
    }

    /**
     * Query .../models URI
     */
    @Test
    fun testQueryAllNoteType() {
        val cr = contentResolver
        // Query all available note types
        val allNoteTypes = cr.query(FlashCardsContract.Model.CONTENT_URI, null, null, null, null)
        assertNotNull(allNoteTypes)
        allNoteTypes.use {
            assertThat(
                "Check that there is at least one result",
                allNoteTypes.count,
                greaterThan(0),
            )
            while (allNoteTypes.moveToNext()) {
                val noteTypeId =
                    allNoteTypes.getLong(allNoteTypes.getColumnIndex(FlashCardsContract.Model._ID))
                val noteTypeUri =
                    Uri.withAppendedPath(
                        FlashCardsContract.Model.CONTENT_URI,
                        noteTypeId.toString(),
                    )
                val singleNoteType = cr.query(noteTypeUri, null, null, null, null)
                assertNotNull(singleNoteType)
                singleNoteType.use {
                    assertEquals(
                        "Check that there is exactly one result",
                        1,
                        it.count,
                    )
                    assertTrue("Move to beginning of cursor", it.moveToFirst())
                    val nameFromNoteTypes =
                        allNoteTypes.getString(allNoteTypes.getColumnIndex(FlashCardsContract.Model.NAME))
                    val nameFromNoteType =
                        it.getString(allNoteTypes.getColumnIndex(FlashCardsContract.Model.NAME))
                    assertEquals(
                        "Check that note type names are the same",
                        nameFromNoteType,
                        nameFromNoteTypes,
                    )
                    val flds =
                        allNoteTypes.getString(allNoteTypes.getColumnIndex(FlashCardsContract.Model.FIELD_NAMES))
                    assertThat(
                        "Check that valid number of fields",
                        Utils.splitFields(flds).size,
                        greaterThanOrEqualTo(1),
                    )
                    val numCards =
                        allNoteTypes.getInt(allNoteTypes.getColumnIndex(FlashCardsContract.Model.NUM_CARDS))
                    assertThat(
                        "Check that valid number of cards",
                        numCards,
                        greaterThanOrEqualTo(1),
                    )
                }
            }
        }
    }

    /**
     * Move all the cards from their old decks to the first deck that was added in setup()
     */
    @Test
    fun testMoveCardsToOtherDeck() {
        val cr = contentResolver
        // Query all available notes
        val allNotesCursor =
            cr.query(FlashCardsContract.Note.CONTENT_URI, null, "tag:$TEST_TAG", null, null)
        assertNotNull(allNotesCursor)
        allNotesCursor.use {
            assertEquals(
                "Check number of results",
                createdNotes.size,
                it.count,
            )
            while (it.moveToNext()) {
                // Now iterate over all cursors
                val cardsUri =
                    Uri.withAppendedPath(
                        Uri.withAppendedPath(
                            FlashCardsContract.Note.CONTENT_URI,
                            it.getString(it.getColumnIndex(FlashCardsContract.Note._ID)),
                        ),
                        "cards",
                    )
                cr.query(cardsUri, null, null, null, null).use { cardsCursor ->
                    assertNotNull(
                        "Check that there is a valid cursor after query for cards",
                        cardsCursor,
                    )
                    assertThat(
                        "Check that there is at least one result for cards",
                        cardsCursor!!.count,
                        greaterThan(0),
                    )
                    while (cardsCursor.moveToNext()) {
                        val targetDid = testDeckIds[0]
                        // Move to test deck (to test NOTES_ID_CARDS_ORD Uri)
                        val values = ContentValues()
                        values.put(FlashCardsContract.Card.DECK_ID, targetDid)
                        val cardUri =
                            Uri.withAppendedPath(
                                cardsUri,
                                cardsCursor.getString(cardsCursor.getColumnIndex(FlashCardsContract.Card.CARD_ORD)),
                            )
                        cr.update(cardUri, values, null, null)
                        reopenCol()
                        val movedCardCur = cr.query(cardUri, null, null, null, null)
                        assertNotNull(
                            "Check that there is a valid cursor after moving card",
                            movedCardCur,
                        )
                        assertTrue(
                            "Move to beginning of cursor after moving card",
                            movedCardCur!!.moveToFirst(),
                        )
                        val did =
                            movedCardCur.getLong(movedCardCur.getColumnIndex(FlashCardsContract.Card.DECK_ID))
                        assertEquals("Make sure that card is in new deck", targetDid, did)
                    }
                }
            }
        }
    }

    /**
     * Check that querying the current note type gives a valid result
     */
    @Test
    fun testQueryCurrentNoteType() {
        val cr = contentResolver
        val uri =
            Uri.withAppendedPath(
                FlashCardsContract.Model.CONTENT_URI,
                FlashCardsContract.Model.CURRENT_MODEL_ID,
            )
        val noteTypeCursor = cr.query(uri, null, null, null, null)
        assertNotNull(noteTypeCursor)
        noteTypeCursor.use {
            assertEquals(
                "Check that there is exactly one result",
                1,
                it.count,
            )
            assertTrue("Move to beginning of cursor", it.moveToFirst())
            assertNotNull(
                "Check non-empty field names",
                it.getString(it.getColumnIndex(FlashCardsContract.Model.FIELD_NAMES)),
            )
            assertTrue(
                "Check at least one template",
                it.getInt(it.getColumnIndex(FlashCardsContract.Model.NUM_CARDS)) > 0,
            )
        }
    }

    /**
     * Check that an Exception is thrown when unsupported operations are performed
     */
    @Test
    fun testUnsupportedOperations() {
        val cr = contentResolver
        val dummyValues = ContentValues()
        // Can't update most tables in bulk -- only via ID
        val updateUris =
            arrayOf(
                FlashCardsContract.Note.CONTENT_URI,
                FlashCardsContract.Model.CONTENT_URI,
                FlashCardsContract.Deck.CONTENT_ALL_URI,
                FlashCardsContract.Note.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .appendPath("cards")
                    .build(),
            )
        for (uri in updateUris) {
            try {
                cr.update(uri, dummyValues, null, null)
                fail("Update on $uri was supposed to throw exception")
            } catch (_: UnsupportedOperationException) {
                // This was expected ...
            } catch (_: IllegalArgumentException) {
                // ... or this.
            }
        }
        // Only note/<id> is supported
        val deleteUris =
            arrayOf(
                FlashCardsContract.Note.CONTENT_URI,
                FlashCardsContract.Note.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .appendPath("cards")
                    .build(),
                FlashCardsContract.Note.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .appendPath("cards")
                    .appendPath("2345")
                    .build(),
                FlashCardsContract.Model.CONTENT_URI,
                FlashCardsContract.Model.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .build(),
            )
        for (uri in deleteUris) {
            assertThrows<UnsupportedOperationException>("Delete on $uri was supposed to throw exception") {
                cr.delete(uri, null, null)
            }
        }
        // Can't do an insert with specific ID on the following tables
        val insertUris =
            arrayOf(
                FlashCardsContract.Note.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .build(),
                FlashCardsContract.Note.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .appendPath("cards")
                    .build(),
                FlashCardsContract.Note.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .appendPath("cards")
                    .appendPath("2345")
                    .build(),
                FlashCardsContract.Model.CONTENT_URI
                    .buildUpon()
                    .appendPath("1234")
                    .build(),
            )
        for (uri in insertUris) {
            try {
                cr.insert(uri, dummyValues)
                fail("Insert on $uri was supposed to throw exception")
            } catch (_: UnsupportedOperationException) {
                // This was expected ...
            } catch (_: IllegalArgumentException) {
                // ... or this.
            }
        }
    }

    /**
     * Test query to decks table
     */
    @Test
    fun testQueryAllDecks() {
        val decks = col.decks
        val decksCursor =
            contentResolver
                .query(
                    FlashCardsContract.Deck.CONTENT_ALL_URI,
                    FlashCardsContract.Deck.DEFAULT_PROJECTION,
                    null,
                    null,
                    null,
                )
        assertNotNull(decksCursor)
        decksCursor.use {
            assertEquals(
                "Check number of results",
                decks.count(),
                it.count,
            )
            while (it.moveToNext()) {
                val deckID =
                    it.getLong(it.getColumnIndex(FlashCardsContract.Deck.DECK_ID))
                val deckName =
                    it.getString(it.getColumnIndex(FlashCardsContract.Deck.DECK_NAME))
                val deck = decks.getLegacy(deckID)!!
                assertNotNull("Check that the deck we received actually exists", deck)
                assertEquals(
                    "Check that the received deck has the correct name",
                    deck.getString("name"),
                    deckName,
                )
            }
        }
    }

    /**
     * Test query to specific deck ID
     */
    @Test
    fun testQueryCertainDeck() {
        val deckId = testDeckIds[0]
        val deckUri =
            Uri.withAppendedPath(
                FlashCardsContract.Deck.CONTENT_ALL_URI,
                deckId.toString(),
            )
        contentResolver.query(deckUri, null, null, null, null).use { decksCursor ->
            if (decksCursor == null || !decksCursor.moveToFirst()) {
                fail("No deck received. Should have delivered deck with id $deckId")
            } else {
                val returnedDeckID =
                    decksCursor.getLong(decksCursor.getColumnIndex(FlashCardsContract.Deck.DECK_ID))
                val returnedDeckName =
                    decksCursor.getString(decksCursor.getColumnIndex(FlashCardsContract.Deck.DECK_NAME))
                val realDeck = col.decks.getLegacy(deckId)!!
                assertEquals(
                    "Check that received deck ID equals real deck ID",
                    deckId,
                    returnedDeckID,
                )
                assertEquals(
                    "Check that received deck name equals real deck name",
                    realDeck.getString("name"),
                    returnedDeckName,
                )
            }
        }
    }

    /**
     * Test that query for the next card in the schedule returns a valid result without any deck selector
     */
    @Test
    fun testQueryNextCard() {
        val sched = col.sched
        val reviewInfoCursor =
            contentResolver.query(
                FlashCardsContract.ReviewInfo.CONTENT_URI,
                null,
                null,
                null,
                null,
            )
        assertNotNull(reviewInfoCursor)
        assertEquals("Check that we actually received one card", 1, reviewInfoCursor.count)
        reviewInfoCursor.moveToFirst()
        val cardOrd =
            reviewInfoCursor.getInt(reviewInfoCursor.getColumnIndex(FlashCardsContract.ReviewInfo.CARD_ORD))
        val noteID =
            reviewInfoCursor.getLong(reviewInfoCursor.getColumnIndex(FlashCardsContract.ReviewInfo.NOTE_ID))
        var nextCard: Card? = null
        for (i in 0..9) { // minimizing fails, when sched.reset() randomly chooses between multiple cards
            nextCard = sched.card
            if (nextCard != null && nextCard.nid == noteID && nextCard.ord == cardOrd) break
        }
        assertNotNull("Check that there actually is a next scheduled card", nextCard)
        assertEquals(
            "Check that received card and actual card have same note id",
            nextCard!!.nid,
            noteID,
        )
        assertEquals(
            "Check that received card and actual card have same card ord",
            nextCard.ord,
            cardOrd,
        )
    }

    /**
     * Test that query for the next card in the schedule returns a valid result WITH a deck selector
     */
    @Test
    @Synchronized
    fun testQueryCardFromCertainDeck() {
        val deckToTest = testDeckIds[0]
        val deckSelector = "deckID=?"
        val deckArguments = arrayOf(deckToTest.toString())
        val sched = col.sched
        val selectedDeckBeforeTest = col.decks.selected()
        col.decks.select(1) // select Default deck
        val reviewInfoCursor =
            contentResolver.query(
                FlashCardsContract.ReviewInfo.CONTENT_URI,
                null,
                deckSelector,
                deckArguments,
                null,
            )
        assertNotNull(reviewInfoCursor)
        assertEquals("Check that we actually received one card", 1, reviewInfoCursor.count)
        reviewInfoCursor.use {
            it.moveToFirst()
            val cardOrd =
                it.getInt(it.getColumnIndex(FlashCardsContract.ReviewInfo.CARD_ORD))
            val noteID =
                it.getLong(it.getColumnIndex(FlashCardsContract.ReviewInfo.NOTE_ID))
            assertEquals(
                "Check that the selected deck has not changed",
                1,
                col.decks.selected(),
            )
            col.decks.select(deckToTest)
            var nextCard: Card? = null
            for (i in 0..9) { // minimizing fails, when sched.reset() randomly chooses between multiple cards
                nextCard = sched.card
                if (nextCard != null && nextCard.nid == noteID && nextCard.ord == cardOrd) break
                try {
                    Thread.sleep(500)
                } catch (e: Exception) {
                    Timber.e(e)
                } // Reset counts is executed in background.
            }
            assertNotNull("Check that there actually is a next scheduled card", nextCard)
            assertEquals(
                "Check that received card and actual card have same note id",
                nextCard!!.nid,
                noteID,
            )
            assertEquals(
                "Check that received card and actual card have same card ord",
                nextCard.ord,
                cardOrd,
            )
        }
        col.decks.select(selectedDeckBeforeTest)
    }

    /**
     * Test changing the selected deck
     */
    @Test
    fun testSetSelectedDeck() {
        val deckId = testDeckIds[0]
        val cr = contentResolver
        val selectDeckUri = FlashCardsContract.Deck.CONTENT_SELECTED_URI
        val values = ContentValues()
        values.put(FlashCardsContract.Deck.DECK_ID, deckId)
        cr.update(selectDeckUri, values, null, null)
        val col = reopenCol()
        assertEquals(
            "Check that the selected deck has been correctly set",
            deckId,
            col.decks.selected(),
        )
    }

    private fun getFirstCardFromScheduler(col: com.ichi2.anki.libanki.Collection): Card? {
        val deckId = testDeckIds[0]
        col.decks.select(deckId)
        return col.sched.card
    }

    /**
     * Test giving the answer for a reviewed card
     */
    @Test
    fun testAnswerCard() {
        val card = getFirstCardFromScheduler(col)
        val cardId = card!!.id

        // the card starts out being new
        assertEquals("card is initial new", QueueType.New, card.queue)
        val cr = contentResolver
        val reviewInfoUri = FlashCardsContract.ReviewInfo.CONTENT_URI
        val noteId = card.nid
        val cardOrd = card.ord

        @Suppress("DEPRECATION")
        val earlyGraduatingEase = com.ichi2.anki.libanki.sched.Ease.EASY
        val values =
            ContentValues().apply {
                val timeTaken: Long = 5000 // 5 seconds
                put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
                put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
                put(FlashCardsContract.ReviewInfo.EASE, earlyGraduatingEase.value)
                put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTaken)
            }
        val updateCount = cr.update(reviewInfoUri, values, null, null)
        assertEquals("Check if update returns 1", 1, updateCount)
        try {
            Thread.currentThread().join(500)
        } catch (e: Exception) {
            // do nothing
        }
        val newCard = col.sched.card
        if (newCard != null) {
            if (newCard.nid == card.nid && newCard.ord == card.ord) {
                fail("Next scheduled card has not changed")
            }
        }

        // lookup the card after update, ensure it's not new anymore
        val cardAfterReview = col.getCard(cardId)
        assertEquals("card is now type rev", QueueType.Rev, cardAfterReview.queue)
    }

    /**
     * Test burying a card through the ReviewInfo endpoint
     */
    @Test
    fun testBuryCard() {
        // get the first card due
        // ----------------------
        val card = getFirstCardFromScheduler(col)

        // verify that the card is not already user-buried
        assertNotEquals(
            "Card is not user-buried before test",
            QueueType.SiblingBuried,
            card!!.queue,
        )

        // retain the card id, we will lookup the card after the update
        val cardId = card.id

        // bury it through the API
        // -----------------------
        val cr = contentResolver
        val reviewInfoUri = FlashCardsContract.ReviewInfo.CONTENT_URI
        val noteId = card.nid
        val cardOrd = card.ord
        val bury = 1
        val values =
            ContentValues().apply {
                put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
                put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
                put(FlashCardsContract.ReviewInfo.BURY, bury)
            }
        val updateCount = cr.update(reviewInfoUri, values, null, null)
        assertEquals("Check if update returns 1", 1, updateCount)

        // verify that it did get buried
        // -----------------------------
        val cardAfterUpdate = col.getCard(cardId)
        // QueueType.MANUALLY_BURIED was also used for SIBLING_BURIED in sched v1
        assertEquals(
            "Card is user-buried",
            QueueType.ManuallyBuried,
            cardAfterUpdate.queue,
        )

        // cleanup, unbury cards
        // ---------------------
        col.sched.unburyCards()
    }

    /**
     * Test suspending a card through the ReviewInfo endpoint
     */
    @Test
    fun testSuspendCard() {
        // get the first card due
        // ----------------------
        val card = getFirstCardFromScheduler(col)

        // verify that the card is not already suspended
        assertNotEquals(
            "Card is not suspended before test",
            QueueType.Suspended,
            card!!.queue,
        )

        // retain the card id, we will lookup the card after the update
        val cardId = card.id

        // suspend it through the API
        // --------------------------
        val cr = contentResolver
        val reviewInfoUri = FlashCardsContract.ReviewInfo.CONTENT_URI
        val noteId = card.nid
        val cardOrd = card.ord

        val values =
            ContentValues().apply {
                put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
                put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
                put(FlashCardsContract.ReviewInfo.SUSPEND, 1)
            }
        val updateCount = cr.update(reviewInfoUri, values, null, null)
        assertEquals("Check if update returns 1", 1, updateCount)

        // verify that it did get suspended
        // --------------------------------
        val cardAfterUpdate = col.getCard(cardId)
        assertEquals("Card is suspended", QueueType.Suspended, cardAfterUpdate.queue)

        // cleanup, unsuspend card and reschedule
        // --------------------------------------
        col.sched.unsuspendCards(listOf(cardId))
    }

    /**
     * Update tags on a note
     */
    @Test
    fun testUpdateTags() {
        // get the first card due
        // ----------------------
        val card = getFirstCardFromScheduler(col)
        val note = card!!.note(col)
        val noteId = note.id

        // make sure the tag is what we expect initially
        // ---------------------------------------------
        val tagList: List<String> = note.tags
        assertEquals("only one tag", 1, tagList.size)
        assertEquals("check tag value", TEST_TAG, tagList[0])

        // update tags
        // -----------
        val tag2 = "mynewtag"
        val cr = contentResolver
        val updateNoteUri =
            Uri.withAppendedPath(
                FlashCardsContract.Note.CONTENT_URI,
                noteId.toString(),
            )
        val values = ContentValues()
        values.put(FlashCardsContract.Note.TAGS, "$TEST_TAG $tag2")
        val updateCount = cr.update(updateNoteUri, values, null, null)
        assertEquals("updateCount is 1", 1, updateCount)

        // lookup the note now and verify tags
        // -----------------------------------
        val noteAfterUpdate = col.getNote(noteId)
        val newTagList: List<String> = noteAfterUpdate.tags
        assertEquals("two tags", 2, newTagList.size)
        assertEquals("check first tag", TEST_TAG, newTagList[0])
        assertEquals("check second tag", tag2, newTagList[1])
    }

    /** Test that a null did will not crash the provider (#6378)  */
    @Test
    fun testProviderProvidesDefaultForEmptyNoteTypeDeck() {
        assumeTrue(
            "This causes mild data corruption - should not be run on a collection you care about",
            isEmulator(),
        )
        col.notetypes
            .all()[0]
            .jsonObject
            .put("did", NULL)

        val cr = contentResolver
        // Query all available note types
        val allNoteTypes = cr.query(FlashCardsContract.Model.CONTENT_URI, null, null, null, null)
        assertNotNull(allNoteTypes)
    }

    @Test
    fun pureAnswerHandledQuotedHtmlElement() {
        // <hr id="answer"> is also used
        val noteTypeName = addNonClozeNoteType("Test", arrayOf("One", "Two"), "{{One}}", "{{One}}<hr id=\"answer\">{{Two}}")
        val note = col.newNote(col.notetypes.byName(noteTypeName)!!)
        note.setItem("One", "1")
        note.setItem("Two", "2")
        col.addNote(note)
        val card = note.cards(col)[0]

        assertThat(card.pureAnswer(col), equalTo("2"))
    }

    @Test
    fun testRenderCardWithAudio() {
        // issue 14866 - regression from 2.16
        val sound = "[sound:ankidroid_audiorec3272438736816461323.3gp]"
        val invalid1 = "[anki:play:q:100]" // index
        val invalid2 = "[anki:play:f:0]" // f doesn't exist
        val invalid3 = "[anki:play:a:text]" // string instead of text

        val back = "$invalid1$invalid2$invalid3"
        val note = addNoteUsingBasicNoteType("Hello$sound", back)
        val ord = 0

        val noteUri =
            Uri.withAppendedPath(
                FlashCardsContract.Note.CONTENT_URI,
                note.id.toString(),
            )
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        val specificCardUri = Uri.withAppendedPath(cardsUri, ord.toString())

        contentResolver
            .query(
                specificCardUri,
                // projection
                arrayOf(FlashCardsContract.Card.QUESTION, FlashCardsContract.Card.ANSWER),
                // selection is ignored for this URI
                null,
                // selectionArgs is ignored for this URI
                null,
                // sortOrder is ignored for this URI
                null,
            )?.let { cursor ->
                if (!cursor.moveToFirst()) {
                    fail("no rows in cursor")
                }

                fun getString(id: String) = cursor.getString(cursor.getColumnIndex(id))
                val question = getString(FlashCardsContract.Card.QUESTION)
                val answer = getString(FlashCardsContract.Card.ANSWER)

                assertThat("[sound: tag should remain", question, containsString(sound))
                assertThat("[sound: tag should remain", answer, containsString(sound))
            } ?: fail("query returned null")
    }

    @Test
    fun testMediaFilesAddedCorrectlyInReviewInfo() {
        val imageFileName = "img.jpg"
        val audioFileName = "test.mp3"
        addNoteUsingBasicNoteType("""Hello <img src="$imageFileName"> [sound:$audioFileName]""")
            .firstCard(col)
            .update {
                queue = QueueType.New
                due = col.sched.today
            }

        queryReviewInfo { cursor ->
            val media =
                cursor
                    .getString(cursor.getColumnIndex(FlashCardsContract.ReviewInfo.MEDIA_FILES))
                    .let { Json.decodeFromString<List<String>>(it) }

            assertThat(
                "media files returned",
                media,
                allOf(
                    hasItem(imageFileName),
                    hasItem(audioFileName),
                ),
            )
        }
    }

    private fun queryReviewInfo(block: (Cursor) -> Unit) {
        contentResolver
            .query(
                FlashCardsContract.ReviewInfo.CONTENT_URI,
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                assertTrue("has rows") { cursor.moveToFirst() }
                block(cursor)
            }
    }

    @Test
    fun emptyCards_noCardsInCollection() {
        assertThat("deleted when collection empty", emptyCards(notetypes.cloze), equalTo(0))
    }

    @Test
    fun emptyCards_emptyCard() {
        val note =
            addTempClozeNote("{{c1::A}} {{c2::B}}").update {
                fields[0] = "{{c1::A}}"
            }
        assertThat("initial cards", note.numberOfCards(col), equalTo(2))
        assertThat("cards removed by empty_cards", emptyCards(notetypes.cloze), equalTo(1))
        assertThat("remaining cards after empty_cards", note.numberOfCards(col), equalTo(1))
    }

    @Test
    fun emptyCards_emptyNote() {
        // deleting the note was requested by the original implementer of this functionality in 2016
        // https://github.com/ankidroid/Anki-Android/issues/18435#issuecomment-2954066920
        val note =
            addTempClozeNote("{{c1::A}} {{c2::B}}").update {
                fields[0] = "Invalid Cloze"
            }
        assertThat("initial cards", note.numberOfCards(col), equalTo(2))
        assertThat("cards removed by empty_cards", emptyCards(notetypes.cloze), equalTo(2))
        assertThat("remaining cards after empty_cards", note.numberOfCards(col), equalTo(0))
        assertThrows<BackendNotFoundException>("note should be deleted") { col.backend.getNote(note.id) }
    }

    @Test
    fun emptyCards_nothingToDo() {
        val note = addTempClozeNote("{{c1::A}}")
        assertThat("initial cards", note.numberOfCards(col), equalTo(1))
        assertThat("cards removed by empty_cards", emptyCards(notetypes.cloze), equalTo(0))
        assertThat("remaining cards after empty_cards", note.numberOfCards(col), equalTo(1))
    }

    @Test
    fun emptyCards_nonMatchingNoteType() {
        val noteTypeToEmpty = notetypes.basic
        val note =
            addTempClozeNote("{{c1::A}} {{c2::B}}").update {
                fields[0] = "{{c1::A}}"
            }
        assertThat("initial cards", note.numberOfCards(col), equalTo(2))
        assertThat("cards removed by empty_cards", emptyCards(noteTypeToEmpty), equalTo(0))
        assertThat("remaining cards after empty_cards", note.numberOfCards(col), equalTo(2))
    }

    @Test
    fun emptyCards_invalidNoteType() {
        assertThat("empty_cards: invalid input", emptyCardsForId(0), equalTo(-1))
    }

    @Test
    fun emptyCards_invalidUri() {
        val emptyCardsUri =
            FlashCardsContract.Model.CONTENT_URI
                .buildUpon()
                .appendPath("should_be_numeric")
                .appendPath("empty_cards")
                .build()
        val exception = assertThrows<IllegalArgumentException> { contentResolver.delete(emptyCardsUri, null, null) }
        assertThat(exception.message, containsString("must be either numeric or"))
    }

    /**
     * Helper for "empty_cards"
     *
     * see `NOTE_TYPES_ID_EMPTY_CARDS`
     */
    private fun emptyCards(noteType: NotetypeJson): Int = emptyCardsForId(noteType.id)

    /** @see emptyCardsForId */
    private fun emptyCardsForId(noteTypeId: NoteTypeId): Int {
        val emptyCardsUri =
            FlashCardsContract.Model.CONTENT_URI
                .buildUpon()
                .appendPath(noteTypeId.toString())
                .appendPath("empty_cards")
                .build()
        return contentResolver.delete(emptyCardsUri, null, null)
    }

    /** Adds a note which will be removed by [tearDown] */
    private fun addTempClozeNote(text: String): Note =
        addClozeNote(text).update {
            tags.add(TEST_TAG)
        }

    private fun reopenCol(): com.ichi2.anki.libanki.Collection {
        Timber.i("closeCollection: %s", "ContentProviderTest: reopenCol")
        CollectionManager.closeCollectionBlocking()
        return col
    }

    private val contentResolver: ContentResolver
        get() = testContext.contentResolver

    companion object {
        private const val BASIC_NOTE_TYPE_NAME = "com.ichi2.anki.provider.test.basic.x94oa3F"
        private const val TEST_FIELD_NAME = "TestFieldName"
        private const val TEST_FIELD_VALUE = "test field value"
        private const val TEST_TAG = "aldskfhewjklhfczmxkjshf"

        // In case of change in TEST_DECKS, change mTestDeckIds for efficiency
        private val TEST_DECKS =
            arrayOf(
                "cmxieunwoogyxsctnjmv",
                "sstuljxgmfdyugiujyhq",
                "pdsqoelhmemmmbwjunnu",
                "scxipjiyozczaaczoawo",
                "cmxieunwoogyxsctnjmv::abcdefgh::ZYXW",
                "cmxieunwoogyxsctnjmv::INSBGDS",
            )
        private const val TEST_NOTE_TYPE_NAME = "com.ichi2.anki.provider.test.a1x6h9l"
        private val TEST_NOTE_TYPE_FIELDS = arrayOf("FRONTS", "BACK")
        private val TEST_NOTE_TYPE_CARDS = arrayOf("cArD1", "caRD2")
        private val TEST_NOTE_TYPE_QFMT = arrayOf("{{FRONTS}}", "{{BACK}}")
        private val TEST_NOTE_TYPE_AFMT = arrayOf("{{BACK}}", "{{FRONTS}}")
        private val TEST_NOTE_FIELDS = arrayOf("dis is za Fr0nt", "Te\$t")
        private const val TEST_NOTE_TYPE_CSS = "styleeeee"

        @Suppress("SameParameterValue")
        private fun setupNewNote(
            col: com.ichi2.anki.libanki.Collection,
            noteTypeId: NoteTypeId,
            did: DeckId,
            fields: Array<String>,
            tag: String,
        ): Uri {
            val newNote = Note.fromNotetypeId(col, noteTypeId)
            for (idx in fields.indices) {
                newNote.setField(idx, fields[idx])
            }
            newNote.addTag(tag)
            assertThat(
                "At least one card added for note",
                col.addNote(newNote),
                greaterThanOrEqualTo(1),
            )
            for (c in newNote.cards(col)) {
                c.did = did
                col.updateCard(c, skipUndoEntry = true)
            }
            return Uri.withAppendedPath(
                FlashCardsContract.Note.CONTENT_URI,
                newNote.id.toString(),
            )
        }
    }

    @KotlinCleanup("duplicate of TestClass method")
    fun addNonClozeNoteType(
        name: String,
        fields: Array<String>,
        qfmt: String,
        afmt: String,
    ): String {
        val noteType = col.notetypes.new(name)
        for (field in fields) {
            col.notetypes.addFieldLegacy(noteType, col.notetypes.newField(field))
        }
        val t =
            Notetypes.newTemplate("Card 1").also { t ->
                t.qfmt = qfmt
                t.afmt = afmt
            }
        col.notetypes.addTemplate(noteType, t)
        col.notetypes.add(noteType)
        return name
    }
}

/**
 * Unbury all buried cards in all decks. Only used for tests.
 */
fun Scheduler.unburyCards() {
    for (did in col.decks.allNamesAndIds().map { it.id }) {
        unburyDeck(did)
    }
}
