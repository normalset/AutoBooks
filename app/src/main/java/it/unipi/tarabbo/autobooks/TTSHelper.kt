package it.unipi.tarabbo.autobooks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TTSHelper(private val context: Context, private val dbHelper: DatabaseHelper) {

    companion object {
        private const val TAG = "TTSHelper"
        private const val TABLE_NAME = "chapterTextLines"
    }

    private var tts: TextToSpeech? = null
    var mediaPlayer: MediaPlayer? = MediaPlayer()

    //Callback to notify when playback is complete
    var onPlaybackComplete: (() -> Unit)? = null

    /**
     * Creates the chapterTextLines table if it doesn't exist
     * Columns:
     * - bookId (INTEGER)
     * - chapterNumber (INTEGER)
     * - lineIndex (INTEGER)
     * - audioData (BLOB)
     * Primary key on (bookId, chapterNumber, lineIndex)
     */
    fun createTableIfNeeded() {
        val db: SQLiteDatabase = dbHelper.writableDatabase
        val createTableQuery = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                bookId INTEGER NOT NULL,
                chapterNumber INTEGER NOT NULL,
                lineIndex INTEGER NOT NULL,
                audioData BLOB NOT NULL,
                PRIMARY KEY (bookId, chapterNumber, lineIndex)
            );
        """.trimIndent()
        db.execSQL(createTableQuery)
        Log.d(TAG, "Table $TABLE_NAME checked/created")
    }

    /**
     * Generate audio for each line in chapter text, save blobs in DB, and mark chapter as audioGenerated=true
     * This function runs asynchronously using coroutines and suspend keyword
     */
    suspend fun generateAudioForChapter(chapter: Chapter): Boolean {
        // If already generated, skip
        if (chapter.audioGenerated) {
            Log.d(TAG, "Audio already generated for chapter ${chapter.chapterNumber}")
            return true
        }

        //Create Notification Manager to track progress
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(context)

        // Split text into lines by newline
        val lines = chapter.text.split("\n").filter { it.isNotBlank() }

        // Initialize TextToSpeech synchronously
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
               try{
                   //setup language and voice from sharedPreferences
                   val prefs = context.getSharedPreferences("tts_prefs" , Context.MODE_PRIVATE)
                   val languageTag = prefs.getString("selected_language" , Locale.getDefault().toLanguageTag())
                   val voiceName = prefs.getString("selected_voice" , null)

                   val selectedLocale = Locale.forLanguageTag(languageTag ?: "en-US") //set to english if there is no default language

                   if(voiceName != null){
                       val voice = tts?.voices?.find {it.name == voiceName}
                       if(voice != null){
                           tts?.voice = voice
                       }
                   }
               }catch(e : Exception){
                   Log.e(TAG, "Error initializing TTS engine, language and voice: ${e.message}")
               }
            } else {
                Log.e(TAG, "Failed to initialize TTS engine")
            }
        }

        // Wait for TTS to be initialized
        waitForTTSInit()

        val db = dbHelper.writableDatabase

        for ((index, line) in lines.withIndex()) {
            val audioFile = File(context.cacheDir, "chapter_${chapter.chapterNumber}_line_$index.wav")

            // Synthesize to file and wait for completion
            val success = synthesizeToFile(line, audioFile)

            if (!success) {
                Log.e(TAG, "Failed to synthesize line $index")
                return false
            }

            // Read audio file bytes
            val audioBytes = audioFile.readBytes()

            // Save in DB
            val values = ContentValues().apply {
                put("bookId", chapter.bookId)
                put("chapterNumber", chapter.chapterNumber)
                put("lineIndex", index)
                put("audioData", audioBytes)
            }
            val insertResult = db.insertWithOnConflict(
                TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE
            )
            if (insertResult == -1L) {
                Log.e(TAG, "Failed to insert audio for line $index")
                return false
            }

            // Optionally delete temp file after saving
            audioFile.delete()
            Log.d(TAG, "Audio line $index generated and saved")
            showProgressNotification(context , notificationManager , lines.size, index+1, chapter.chapterNumber)
        }

        // Update chapter audioGenerated flag in chapters table
        val chapterTableName = "book_${chapter.bookId}_chapters"
        val updateValues = ContentValues().apply {
            put("audioGenerated", true)
        }
        db.update(
            chapterTableName,
            updateValues,
            "chapterNumber = ?",
            arrayOf(chapter.chapterNumber.toString())
        )

        Log.d(TAG, "Audio generation completed for chapter ${chapter.chapterNumber}")
        showCompletedNotification(context , notificationManager)
        tts?.shutdown()
        return true
    }

    /**
     * Retrieve the audio bytes for a given chapter line from DB
     */
    fun getAudioForLine(bookId: Long, chapterNumber: Long, lineIndex: Int): ByteArray? {
        try{
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT audioData FROM $TABLE_NAME WHERE bookId = ? AND chapterNumber = ? AND lineIndex = ?",
                arrayOf(bookId.toString(), chapterNumber.toString(), lineIndex.toString())
            )
            cursor.use {
                if (it.moveToFirst()) {
                    return it.getBlob(it.getColumnIndexOrThrow("audioData"))
                }
            }
            return null
        }catch(e : Exception){
            Log.e("TTS_HELPER_LOG" , "ERROR in getAudioForLine: " + e.message)
            return null
        }
    }

    /**
     * Play audio byte array using MediaPlayer by writing it to a temp file first
     */
    fun playAudioBytes(audioBytes: ByteArray) {
        try {
            // Stop and release previous MediaPlayer if any
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }

            val tempFile = File.createTempFile("tts_audio", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
//                    it.release()
                    tempFile.delete()
                    Log.d(TAG, "Playback completed and temp file deleted")

                    // Notify that playback is done
                    onPlaybackComplete?.invoke()
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio bytes: ${e.message}")
        }
    }

    /**
     * Helper to synthesize a given text line to a file, suspending until done
     */
    private suspend fun synthesizeToFile(text: String, file: File): Boolean = suspendCoroutine { cont ->
        val params = Bundle()
        val utteranceId = UUID.randomUUID().toString()

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Started synthesizing utterance")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Synthesis done for utterance")
                cont.resume(true)
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Error synthesizing utterance")
                cont.resume(false)
            }
        })

        val result = tts?.synthesizeToFile(text, params, file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to start synthesis to file")
            cont.resume(false)
        }
    }

    /**
     * Helper to suspend until TTS is initialized
     */
    private suspend fun waitForTTSInit() = suspendCoroutine<Unit> { cont ->
        // This can be improved with proper synchronization if needed
        Thread {
            var retries = 0
            while (tts == null || tts?.language == null) {
                Thread.sleep(100)
                retries++
                if (retries > 50) { // Timeout ~5 sec
                    Log.e(TAG, "Timeout waiting for TTS initialization")
                    cont.resume(Unit)
                    return@Thread
                }
            }
            cont.resume(Unit)
        }.start()
    }

    fun stopAudio() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    Log.d("MEDIAPLAYER_LOG", "MediaPlayer stopped")
                    player.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w("MEDIAPLAYER_LOG", "MediaPlayer is in illegal state for isPlaying or stop: ${e.message}")
                // Possibly already stopped or released, ignore or handle as needed
            } finally {
                Log.d("MEDIAPLAYER_LOG", "MediaPlayer released")
                player.release()
                mediaPlayer = null  // Also clear the reference so you don't reuse released player
            }
        }
    }


    /*
    * Functions for the progress notification
    */

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tts_generation_channel",
                "TTS Generation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for TTS audio generation progress"
            }

            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgressNotification(
        context: Context,
        notificationManager: NotificationManager,
        totalSteps: Int,
        currentStep: Int,
        chapterNumber: Long
    ) {
        val builder = NotificationCompat.Builder(context, "tts_generation_channel")
            .setSmallIcon(R.drawable.ic_music_note) // your icon here
            .setContentTitle("Generating audiobook for chapter ${chapterNumber}")
            .setContentText("Processing line $currentStep of $totalSteps")
            .setOnlyAlertOnce(true)
            .setProgress(totalSteps, currentStep, false)
            .setOngoing(true) // makes it sticky until dismissed

        notificationManager.notify(1001, builder.build())
    }

    fun showCompletedNotification(context: Context, notificationManager: NotificationManager) {
        val builder = NotificationCompat.Builder(context, "tts_generation_channel")
            .setSmallIcon(R.drawable.ic_music_note) // your done icon here
            .setContentTitle("Audiobook generation complete")
            .setContentText("The chapter's audiobook has been generated successfully.")
            .setProgress(0, 0, false)
            .setOngoing(false)

        notificationManager.notify(1001, builder.build())
    }

}
