package it.unipi.tarabbo.autobooks.ui

import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.res.Configuration
import android.health.connect.datatypes.units.Power
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import it.unipi.tarabbo.autobooks.R
import it.unipi.tarabbo.autobooks.Chapter
import it.unipi.tarabbo.autobooks.DatabaseHelper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.experimental.Experimental
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.lifecycleScope
import it.unipi.tarabbo.autobooks.TTSHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class ChapterReaderFragment : Fragment(){

    private val args : ChapterReaderFragmentArgs by navArgs()
    private var ttsHelper : TTSHelper? = null

    private var lineRanges : List<IntRange> = emptyList()
    private var currentLine = 0
    private var playingAudio : Boolean = false
    var audioAvailable : Boolean = false

    private var powerManager : android.os.PowerManager? = null
    private var wakeLock : android.os.PowerManager.WakeLock? = null

    lateinit var progressBar : com.google.android.material.progressindicator.LinearProgressIndicator

    override fun onCreateView(
        inflater : LayoutInflater ,
        container : ViewGroup? ,
        savedInstanceState : Bundle?
    ): View?{
        return inflater.inflate(R.layout.chapter_reader , container , false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Enable Add Book Fab before destroying view
        val fab = requireActivity().findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab)
        fab.isEnabled = true
        fab.visibility = View.VISIBLE

        // Enable toolbar
        val toolbar = requireActivity().findViewById<Toolbar>(R.id.toolbar)
        toolbar.isEnabled = true
        toolbar.visibility = View.VISIBLE

        try{
            ttsHelper?.stopAudio()
            ttsHelper?.clearPlaybackNotification(requireContext())
        }catch(e : Exception){
            Log.d("CH_READER_ONDRESTROY" , "Got error message trying to stop audio : ${e.message}")
        }

        try{
            // Release TTSHelper mediaplayer before destroying view
            TTSHelper.mediaPlayer?.release()
            TTSHelper.mediaPlayer = null
        }catch( e : Exception){
            Log.d("CH_READER_ONDRESTROY" , "Got error message trying to release mediaplayer : ${e.message}")
        }

        //Release the wakelock if the view gets destroyed
        wakeLock?.release()
    }

    override fun onViewCreated(view : View, savedInstanceState: Bundle?){
        super.onViewCreated(view, savedInstanceState)

        //Set wake lock so that the screen doesnt dim while the chapter reader is on screen
        powerManager = requireContext().getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK , "AutoBooks:ChapterReaderWakeLock")
        wakeLock?.acquire(10*60*1000L /*10 minutes*/)

        //Update chapter isRead status if first time open
        if(args.chapter.isRead == 0){
            lifecycleScope.launch(Dispatchers.IO) {
                DatabaseHelper(requireContext()).markChapterAsRead(args.chapter.bookId , args.chapter.chapterNumber)
            }
        }

        try{
            // Disable add book fab
            val fab = requireActivity().findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab)
            fab.isEnabled = false
            fab.visibility = View.GONE

            // Disable toolbar
            val toolbar = requireActivity().findViewById<Toolbar>(R.id.toolbar)
            toolbar.isEnabled = false
            toolbar.visibility = View.GONE

            //Setup TTSHelper
            val dbHelper = DatabaseHelper(requireContext())
            ttsHelper = TTSHelper(requireContext() , dbHelper)

            //Create Playback notification channel
            ttsHelper!!.createPlaybackNotificationChannel(requireContext())
        }catch(e : Exception){
           Log.d("ERR_CH_READER_SETUP1" , e.toString())
        }

        try{
            val checkTTSLaucher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
                    result ->
                if(result.resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS){
                    Log.d("TTS_LOG" , "TTS engine is already installed")
                }else{
                    Log.d("TTS_LOG" , "TTS engine was not installed, instal intent")
                    val installIntent = Intent()
                    installIntent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
                    startActivity(installIntent)
                }
            }
            val install = checkTTSLaucher.launch(Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA))
        }catch(e : Exception){
            Log.d("ERR_CH_READER" , "Error while checking tts engine installation : ${e.toString()}")
        }

        try {
            val chapter = args.chapter

            //Log.d("CH_READER" , chapter.toString())

            val chapterTitle = view.findViewById<TextView>(R.id.chapterReaderTitle)
            val chapterText = view.findViewById<TextView>(R.id.chapterReaderTextView)
            val button = view.findViewById<ImageButton>(R.id.chapterReaderButton)

            val titleText = "${chapter.chapterNumber}: ${chapter.title}"
            chapterTitle.text = titleText
            chapterText.text = chapter.text

            val buttonIcon = if(chapter.audioGenerated){
                R.drawable.download_done
            }else{
                R.drawable.download
            }
            button.setImageResource(buttonIcon)

            audioAvailable = chapter.audioGenerated

            //set tts button listener
            button.setOnClickListener {
                if (audioAvailable) { // chapters audio already generated
                    // Show confirmation dialog asking the user if they want to delete the audio
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Delete Audio")
                        .setMessage("Audio for this chapter already exists. Do you want to delete it?")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val dbHelper = DatabaseHelper(requireContext())
                                    dbHelper.deleteChapterAudioBlob(chapter.bookId, chapter.chapterNumber)

                                    // UI updates must be done on main thread, se we launch Dispatchers.Main instead of IO
                                    launch(Dispatchers.Main) {
                                        button.setImageResource(R.drawable.download)
                                        Toast.makeText(requireContext(), "Chapter ${chapter.chapterNumber} audio deleted", Toast.LENGTH_SHORT).show()
                                        audioAvailable = false
                                    }
                                } catch (e: Exception) {
                                    Log.e("CH_READER_DELETEAUDIO", "Failed to delete audio: ${e.message}")
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else { // audio not yet generated, generate it
                    // Generate audio for chapter
                    ttsHelper?.createTableIfNeeded()
                    Toast.makeText(requireContext(), "Generating audio for chapter ${chapter.chapterNumber}", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        val result = ttsHelper?.generateAudioForChapter(chapter)
                        if (result == true) {
                            Log.d("CH_READER+TTS_HELPER", "Audio generated for chapter ${chapter.chapterNumber}")
                            button.setImageResource(R.drawable.download_done)
                            audioAvailable = true
                        } else {
                            Log.d("CH_READER+TTS_HELPER", "Failed to generate audio for chapter ${chapter.chapterNumber}")
                        }
                    }
                }
            }

            //Setup Media Control buttons
            progressBar = view.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.chapterProgressBar)
            val playButton = view.findViewById<ImageButton>(R.id.playPauseButton)
            val prevButton = view.findViewById<ImageButton>(R.id.prevLineButton)
            val nextButton = view.findViewById<ImageButton>(R.id.nextLineButton)

            setupMediaControls(
                playButton ,
                prevButton ,
                nextButton ,
                ttsHelper!! ,
                chapter
            )

            //Compute line ranges for highlight
            currentLine = 0
            val filteredLines = chapter.text.split("\n").filter { it.isNotBlank() }
            lineRanges = computeLineRanges(filteredLines , chapter)

            //Display all text with no highlight at the start
            chapterText.text = chapter.text


        }catch (e : Exception){
            Log.d("ERR_CH_READER" , e.toString())
        }
    }

    fun setupMediaControls(
        playButton : ImageButton ,
        prevButton : ImageButton ,
        nextButton : ImageButton,
        ttsHelper : TTSHelper,
        chapter : Chapter
        ){

        playButton.setOnClickListener {
            if(audioAvailable){
                if(playingAudio){
                    ttsHelper.stopAudio()
                    playingAudio = false
                    playButton.setImageResource(R.drawable.play_button_arrowhead)
                }else{
                    playLine(ttsHelper , chapter)
                    playingAudio = true
                    playButton.setImageResource(R.drawable.pause)
                }
            }else{
                Toast.makeText(requireContext() , "Generate Audio in the Top Right Corner", Toast.LENGTH_SHORT).show()
            }
        }

        prevButton.setOnClickListener {
           if(audioAvailable){
               if(playingAudio){
                   //Stop current audio and play previous line
                   ttsHelper.stopAudio()
                   playingAudio = false
                   playButton.setImageResource(R.drawable.play_button_arrowhead)
                   if(currentLine > 1) currentLine--
                   updateProgressBar()
                   playLine(ttsHelper , chapter)
               }else{
                   //Stop current audio and DO NOT play previous line
                   ttsHelper.stopAudio()
                   playingAudio = false
                   playButton.setImageResource(R.drawable.play_button_arrowhead)
                   if(currentLine > 0) currentLine--
                   updateProgressBar()
               }
           }
        }

        nextButton.setOnClickListener {
            if(audioAvailable){
                if(playingAudio){
                    //Stop current audio and play next line
                    ttsHelper.stopAudio()
                    playingAudio = false
                    playButton.setImageResource(R.drawable.play_button_arrowhead)
                    if(currentLine < lineRanges.size) currentLine++
                    updateProgressBar()
                    playLine(ttsHelper , chapter)
                }else{
                    //Stop current audio and DO NOT play next line
                    ttsHelper.stopAudio()
                    playingAudio = false
                    playButton.setImageResource(R.drawable.play_button_arrowhead)
                    if(currentLine > lineRanges.size) currentLine++
                    updateProgressBar()
                }
            }
        }
    }

    fun updateProgressBar(){
        val progress = ((currentLine + 1) *100) / lineRanges.size
        progressBar.progress = progress.toInt()
    }

    fun computeLineRanges(filteredLines: List<String>, chapter: Chapter) : List<IntRange>{
        val ranges = mutableListOf<IntRange>()
        var searchStart = 0

        for (line in filteredLines) {
            // Find next occurrence of this filtered line starting from searchStart
            val startIndex = chapter.text.indexOf(line, searchStart)
            if (startIndex == -1) {
                // Not found, fallback: skip or throw error
                continue
            }
            val endIndex = startIndex + line.length - 1
            ranges.add(startIndex..endIndex)

            // Move searchStart after this line for next search to avoid matching earlier lines
            searchStart = endIndex + 1
        }
        return ranges
    }

    // Highlight the line with a background color span
    private fun highlightLine(lineIndex: Int , chapterText : String , chapterReaderTextView : TextView) {
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val spannable = SpannableStringBuilder(chapterText)

                // Remove old spans
                val spans = spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java)
                for (span in spans) {
                    spannable.removeSpan(span)
                }

                if (lineIndex in lineRanges.indices) {
                    val range = lineRanges[lineIndex]
                    val color = resources.getColor(R.color.md_theme_inverseOnSurface)
                    spannable.setSpan(
                        BackgroundColorSpan(color),
                        range.first,
                        range.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                chapterReaderTextView.text = spannable
            }catch(e: Exception){
                Log.e("ERR_CH_READER_HIGHLIGHTLINE" , e.toString())
            }
        }
    }

    fun playLine(ttsHelper : TTSHelper, chapter : Chapter){
        Log.d("PLAYLINE" , "Playing line $currentLine")
        updateProgressBar()
        lifecycleScope.launch(Dispatchers.IO){
            try{
                val lineAudio = ttsHelper.getAudioForLine(chapter.bookId , chapter.chapterNumber , currentLine)
                if( lineAudio != null){
                    ttsHelper.onPlaybackComplete = {
                        onLineFinished(ttsHelper , chapter)
                    }
                    //play line audio, highlight line, update notification and progress bar
                    ttsHelper.playAudioBytes(lineAudio)
                    highlightLine(currentLine , chapter.text , view?.findViewById(R.id.chapterReaderTextView)!!)
                    ttsHelper.showPlaybackProgressNotification(requireContext() , currentLine + 1 , lineRanges.size , chapter.title)
                }
            }catch(e : Exception){
                Log.d("CH_READER" , "PlayLine : ${e.message}")
            }

        }
    }

    fun onLineFinished(ttsHelper : TTSHelper , chapter: Chapter){
        Log.d("ONLINEFINISHED" , "Finished playing line $currentLine")
        currentLine++
        Log.d("ONLINEFINISHED" , "lineRanges.size = ${lineRanges.size}")
        if(currentLine < lineRanges.size){
            //ReadNextLine if there is one
            playLine(ttsHelper , chapter)
        } else {
            //clear playback notification
            ttsHelper.clearPlaybackNotification(requireContext())
        }
    }
}