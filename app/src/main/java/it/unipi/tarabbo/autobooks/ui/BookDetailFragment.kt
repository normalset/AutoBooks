package it.unipi.tarabbo.autobooks.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.unipi.tarabbo.autobooks.Book
import it.unipi.tarabbo.autobooks.Chapter
import it.unipi.tarabbo.autobooks.DatabaseHelper
import it.unipi.tarabbo.autobooks.R
import it.unipi.tarabbo.autobooks.TTSHelper
import kotlinx.coroutines.launch

class BookDetailFragment : Fragment(){

    // Get the book ID from the arguments in the navigation graph
    private val args: BookDetailFragmentArgs by navArgs()

    private var book : Book? = null

    companion object {
        private const val ARG_BOOK_ID = "book_id"

        fun newInstance(bookId: Long): BookDetailFragment {
            val fragment = BookDetailFragment()
            val args = Bundle()
            args.putLong(ARG_BOOK_ID, bookId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_book_detail, container, false)
        val bookId = args.bookId
        Log.d("BookDetailFragment", "Received bookId: $bookId")
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bookId = args.bookId
        Log.d("BookDetailFragment", "Received bookId: $bookId")

        //Load book
        val dbHelper = DatabaseHelper(requireContext())
        book= dbHelper.getBookById(bookId)

        val titleText = view.findViewById<TextView>(R.id.bookDetailTitle)
        val authorText = view.findViewById<TextView>(R.id.bookDetailAuthor)
        val coverImage = view.findViewById<ImageView>(R.id.bookDetailCover)
        val chaptersRecyclerView = view.findViewById<RecyclerView>(R.id.chaptersRecyclerView)
        val favouriteButton = view.findViewById<ImageButton>(R.id.bookFavouriteButton)

        if(book?.isFavourite == true){
            favouriteButton.setImageResource(R.drawable.favourite_full)
        }else{
            favouriteButton.setImageResource(R.drawable.favourite_empty)
        }

        //set favourite button listener
        favouriteButton.setOnClickListener {
            if (book?.isFavourite == true) {
                favouriteButton.setImageResource(R.drawable.favourite_empty)
                dbHelper.setFavourite(bookId, false)
            } else {
                favouriteButton.setImageResource(R.drawable.favourite_full)
                dbHelper.setFavourite(bookId, true)
            }
        }

        book?.let{
            b ->
            titleText.text = b.title
            authorText.text = b.author
            if(b.coverImage != null){
                val bitmap = BitmapFactory.decodeByteArray(b.coverImage, 0, b.coverImage.size)
                coverImage.setImageBitmap(bitmap)
            }else{
                coverImage.setImageResource(R.drawable.default_bookcover)
            }

            //Fetch chapter list from the db
            val dbHelper = DatabaseHelper(requireContext())
            var chapters = dbHelper.getChaptersForBook(b.id)

            chaptersRecyclerView.apply{
                layoutManager = LinearLayoutManager(requireContext())
                adapter = ChapterAdapter(chapters)
                // Add divider line between items
                val dividerItemDecoration = DividerItemDecoration(
                    context,
                    DividerItemDecoration.VERTICAL
                )
                // Load your custom drawable and set it as the divider
                val dividerDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.custom_chapter_divider)
                if (dividerDrawable != null) {
                    dividerItemDecoration.setDrawable(dividerDrawable)
                }

                addItemDecoration(dividerItemDecoration)
            }
        }
    }

    class ChapterAdapter(private val chapters : List<Chapter>) : RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder>(){

        //Chapter text to speech to generate audio files
        var ttsHelper : TTSHelper? = null
        var dbHelper : DatabaseHelper? = null

        inner class ChapterViewHolder(view : View) : RecyclerView.ViewHolder(view){
            private val chapterTitle = view.findViewById<TextView>(R.id.chapterTitle)
            private val statusIcon = view.findViewById<ImageButton>(R.id.chapterButton)
            fun bind(chapter : Chapter){
                chapterTitle.text = chapter.title

                //Set chapters icon based on their status
                val iconRes = if(chapter.audioGenerated){
                    R.drawable.download_done
                } else {
                    R.drawable.download
                }
                statusIcon.setImageResource(iconRes)

                //If text is clicked, navigate to chapter reader fragment with chapter data
                chapterTitle.setOnClickListener{
                    val action = BookDetailFragmentDirections.actionNavBookDetailToNavChapterReader(chapter)
                    itemView.findNavController().navigate(action)
                }

                //if button is clicked, generate chapter audio
                statusIcon.setOnClickListener {
                    if (!chapter.audioGenerated) {
                        // Disable button to prevent multiple clicks
                        statusIcon.isEnabled = false

                        // Initialize your TTSHelper if not already
                        if (dbHelper == null) {
                            dbHelper = DatabaseHelper(itemView.context)
                        }

                        // Initialize your TTSHelper if not already
                        if (ttsHelper == null) {
                            ttsHelper = TTSHelper(itemView.context , dbHelper!!)
                        }

                        // Use a CoroutineScope tied to the View lifecycle or Fragment lifecycle
                        (itemView.context as? androidx.fragment.app.FragmentActivity)?.lifecycleScope?.launch {
                            try {
                                // Show notification channel for progress
                                ttsHelper?.createNotificationChannel(itemView.context)

                                // Generate audio with progress notification
                                val success = ttsHelper?.generateAudioForChapter(chapter)


                                if (success == true) {
                                    // Update UI and data
                                    statusIcon.setImageResource(R.drawable.download_done)
                                    Toast.makeText(itemView.context, "Audiobook generated", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(itemView.context, "Failed to save audio.", Toast.LENGTH_SHORT).show()
                                }

                            } catch (e: Exception) {
                                Toast.makeText(itemView.context, "Error generating audio: ${e.message}", Toast.LENGTH_LONG).show()
                                Log.e("ChapterAdapter", "Audio generation error", e)
                            } finally {
                                // Re-enable button
                                statusIcon.isEnabled = true
                            }
                        }
                    } else {
                        Toast.makeText(itemView.context, "Audio already generated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chapter, parent, false)
            return ChapterViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
            holder.bind(chapters[position])
        }

        override fun getItemCount() = chapters.size
    }
}