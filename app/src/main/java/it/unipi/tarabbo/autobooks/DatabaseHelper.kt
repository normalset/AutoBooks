package it.unipi.tarabbo.autobooks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.documentnode.epub4j.epub.EpubReader
import java.io.InputStream
import androidx.core.database.sqlite.transaction


class DatabaseHelper (context : Context) : SQLiteOpenHelper(context , DATABASE_NAME , null , DATABASE_VERSION){
    companion object{
        const val DATABASE_NAME = "AutoBooksDB.db"
        const val DATABASE_VERSION = 1
    }

    override fun onCreate(db : SQLiteDatabase){
        //Called in case the db doesnt exist yet
        val createDefaultTable = """
            CREATE TABLE IF NOT EXISTS Books (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            author TEXT NOT NULL,
            numChapters INT NOT NULL,
            chaptersRead INT,
            isFavourite BOOLEAN,
            coverImage BLOB
            );
        """.trimIndent()
        db.execSQL(createDefaultTable)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        TODO("Not yet implemented")
    }

    // Create a new book table
    fun createTable(tableName : String , data:Map<String, Any?>) : Boolean{
        val db = writableDatabase
        val contentValues = ContentValues()

        for ((key, value) in data) {
            when (value) {
                is String -> contentValues.put(key, value)
                is Int -> contentValues.put(key, value)
                is Long -> contentValues.put(key, value)
                is Float -> contentValues.put(key, value)
                is Double -> contentValues.put(key, value)
                is Boolean -> contentValues.put(key, if (value) 1 else 0)
                null -> contentValues.putNull(key)
                else -> throw IllegalArgumentException("Unsupported type for column $key")
            }
        }

        val result = db.insert(tableName, null, contentValues)
        return result != -1L
    }

    /*
        * Utility functions to add data and query
    */
    fun insertBook(
        title:String,
        author:String,
        numChapters:Int,
        chaptersRead:Int = 0 ,
        isFavourite : Boolean = false,
        coverImage : ByteArray? = null
    ) : Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title)
            put("author", author)
            put("numChapters", numChapters)
            put("chaptersRead", chaptersRead)
            put("isFavourite", if (isFavourite) 1 else 0)
            if(coverImage != null) put ("coverImage" , coverImage)
        }
        val bookId = db.insert("Books", null, values)

        //create corresponding book table
        if (bookId != -1L) {
            createChaptersTable(bookId)
        }
        Log.d("DB_LOG", "Inserted book with ID: $bookId")
        return bookId
    }

    private fun createChaptersTable(bookId : Long){
        val tableName = "book_${bookId}_chapters"
        val query = """
            CREATE TABLE IF NOT EXISTS $tableName (
                chapterNumber INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                text TEXT,
                isRead INTEGER DEFAULT 0,
                audioGenerated BOOLEAN DEFAULT FALSE,
                isFavourite BOOLEAN DEFAULT FALSE,
                audioData BLOB
            );
        """.trimIndent()
        writableDatabase.execSQL(query)

    }

    //Return False if error occurred
    fun insertChapter(bookId: Long , chapterNumber: Int , title : String, text : String) : Boolean{
        val db = writableDatabase
        val tableName = "book_${bookId}_chapters"

        val values = ContentValues().apply {
            put("chapterNumber", chapterNumber)
            put("title", title)
            put("isRead", 0)
            put("text" , text)
        }

        val result = db.insert(tableName, null , values)
        return result != -1L
    }

    fun getAllBooks(): Cursor? {
        return readableDatabase.rawQuery("SELECT * FROM books", null)
    }

//    fun getChaptersForBook(bookId: Long): Cursor? {
//        val tableName = "book_${bookId}_chapters"
//        return readableDatabase.rawQuery("SELECT * FROM $tableName", null)
//    }

    fun getReadPercentage(bookId: Long) : Float{
        val tableName = "book_${bookId}_chapters"
        val db = readableDatabase

        val totalQuery = "SELECT COUNT(*) FROM $tableName"
        val readQuery = "SELECT COUNT(*) FROM $tableName WHERE isRead = 1"

        val totalCursor = db.rawQuery(totalQuery , null)
        val readCursor = db.rawQuery(readQuery , null)

        val total = if (totalCursor.moveToFirst()) totalCursor.getInt(0) else 0
        val read = if (readCursor.moveToFirst()) readCursor.getInt(0) else 0

        totalCursor.close()
        readCursor.close()

        return if(total > 0) (read.toFloat()/total) * 100f else 0f
    }

    fun insertBookFromEpub(context: Context, epubInputStream: InputStream): Long {
        return try {
            val book = EpubReader().readEpub(epubInputStream)

            val title = book.metadata?.titles?.firstOrNull() ?: "Untitled"
            val author = book.metadata?.authors?.firstOrNull()?.let { author ->
                listOfNotNull(author.firstname, author.lastname).joinToString(" ")
            } ?: "Unknown"

            val chapters = book.tableOfContents?.tocReferences ?: emptyList()
            val numChapters = chapters.size

            Log.d("DB_LOG", "Title: $title , Author: $author , Chapters: $numChapters")

            //get cover image as a byte array
            val coverImageBytes : ByteArray? = book.coverImage?.data

            val bookId = insertBook(
                title = title,
                author = author,
                numChapters = numChapters,
                chaptersRead = 0,
                isFavourite = false,
                coverImage = coverImageBytes
            )

            if (bookId == -1L) return -1L

            chapters.forEachIndexed { index, chapter ->
                val chapterTitle = chapter.title ?: "Chapter ${index + 1}"
                val html = chapter.resource?.reader?.readText() ?: ""
                val text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                insertChapter(bookId, index + 1, chapterTitle, text)
            }

            bookId
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    // [DEBUG FUNCTION]
    fun logAllBooksAndChapters(context: Context) {
        val dbHelper = DatabaseHelper(context)
        val booksCursor = dbHelper.getAllBooks()

        if (booksCursor != null && booksCursor.moveToFirst()) {
            do {
                val bookId = booksCursor.getLong(booksCursor.getColumnIndexOrThrow("id"))
                val title = booksCursor.getString(booksCursor.getColumnIndexOrThrow("title"))
                val author = booksCursor.getString(booksCursor.getColumnIndexOrThrow("author"))
                val numChapters = booksCursor.getInt(booksCursor.getColumnIndexOrThrow("numChapters"))
                val chaptersRead = booksCursor.getInt(booksCursor.getColumnIndexOrThrow("chaptersRead"))
                val isFavourite = booksCursor.getInt(booksCursor.getColumnIndexOrThrow("isFavourite")) == 1

                Log.d("DB_LOG", "Book ID: $bookId")
                Log.d("DB_LOG", "Title: $title")
                Log.d("DB_LOG", "Author: $author")
                Log.d("DB_LOG", "Chapters: $numChapters")
                Log.d("DB_LOG", "Chapters Read: $chaptersRead")
                Log.d("DB_LOG", "isFavourite: $isFavourite")

                val chapters = dbHelper.getChaptersForBook(bookId)
                chapters.forEach { c -> Log.d("DB_LOG_logAllBooksAndChapters" , "${c.chapterNumber} : ${c.title} , isRead: ${c.isRead}") }

            } while (booksCursor.moveToNext())
            booksCursor.close()
        } else {
            Log.d("DB_LOG", "No books found in database.")
        }
    }

    fun getBookById(id: Long): Book? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM books WHERE id = ?", arrayOf(id.toString()))
        var book: Book? = null
        cursor.use {
            if (it.moveToFirst()) {
                book = Book(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    title = it.getString(it.getColumnIndexOrThrow("title")),
                    author = it.getString(it.getColumnIndexOrThrow("author")),
                    numChapters = it.getLong(it.getColumnIndexOrThrow("numChapters")),
                    chaptersRead = it.getInt(it.getColumnIndexOrThrow("chaptersRead")),
                    isFavourite = it.getInt(it.getColumnIndexOrThrow("isFavourite")) == 1,
                    coverImage = it.getBlob(it.getColumnIndexOrThrow("coverImage"))
                )
            }
        }
        return book
    }

    fun getChaptersForBook(bookId: Long): List<Chapter> {
        try{
            val chapters = mutableListOf<Chapter>()
            val db = readableDatabase
            val tableName = "book_${bookId}_chapters"
            val cursor = db.rawQuery("SELECT * FROM $tableName", null)
            cursor.use {
                while (it.moveToNext()) {
                    chapters.add(
                        Chapter(
                            bookId = bookId,
                            chapterNumber = it.getLong(it.getColumnIndexOrThrow("chapterNumber")),
                            title = it.getString(it.getColumnIndexOrThrow("title")),
                            text = it.getString(it.getColumnIndexOrThrow("text")),
                            isRead = it.getInt(it.getColumnIndexOrThrow("isRead")),
                            audioGenerated = it.getInt(it.getColumnIndexOrThrow("audioGenerated")) == 1,
                            isFavourite = it.getInt(it.getColumnIndexOrThrow("isFavourite")) == 1,
                            audioData = it.getBlob(it.getColumnIndexOrThrow("audioData"))
                        )
                    )
                }
            }
            return chapters
        }catch(e : Exception){
            Log.d("ERROR_DB_LOG" , "ERROR in getChaptersForBook: " + e.message.toString())
            return mutableListOf()
        }
    }

    //Function to mark as read a chapter and update book counter
    fun markChapterAsRead(bookId: Long, chapterNumber: Long): Boolean {
        Log.d("DB_LOG", "Marking chapter $chapterNumber as read for book $bookId")
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val chapterValues = ContentValues().apply {
                put("isRead", 1)
            }
            val chapterUpdateCount = db.update(
                "book_${bookId}_chapters",
                chapterValues,
                "chapterNumber = ?",
                arrayOf(chapterNumber.toString())
            )

            if (chapterUpdateCount == 0) {
                db.endTransaction()
                return false
            }

            val incrementChaptersReadSQL = """
        UPDATE Books
        SET chaptersRead = chaptersRead + 1
        WHERE id = ?
    """
            val stmt = db.compileStatement(incrementChaptersReadSQL)
            stmt.bindLong(1, bookId)
            stmt.executeUpdateDelete()

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e("DB_LOG", "Error marking chapter as read: ${e.message}")
            false
        } finally {
            db.endTransaction()
        }
    }

    // Function to mark a book as a fav, used in the BookDetail Fragment
    fun setFavourite(bookId: Long , isFavourite: Boolean){
        val db = writableDatabase
        val values = ContentValues().apply {
            put("isFavourite", if (isFavourite) 1 else 0)
        }
        db.update("Books", values, "id = ?", arrayOf(bookId.toString()))
    }
}