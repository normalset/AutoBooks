package it.unipi.tarabbo.autobooks.ui.home

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.unipi.tarabbo.autobooks.Book
import it.unipi.tarabbo.autobooks.DatabaseHelper
import it.unipi.tarabbo.autobooks.R
import it.unipi.tarabbo.autobooks.databinding.FragmentHomeBinding
import it.unipi.tarabbo.autobooks.ui.BookDetailFragment
import kotlin.text.replace

/*
   *   Known Problems
   *  App crashes if dark/light mode is changed while mediaplayer is running
 */


class HomeFragment : Fragment() {


    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    //Adapters
    private var favouritesAdapter : BookAdapter? = null
    private var  allBooksAdapter : BookAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume(){
        super.onResume()
        loadBooks()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // Create adapters and define onBookClick lambda function implementation
        favouritesAdapter = BookAdapter(showText = false) {book -> openBookDetail(book)}
        allBooksAdapter = BookAdapter(showText = true) {book -> openBookDetail(book)}

        binding.favouritesRecyclerView.apply{
            adapter = favouritesAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        binding.allBooksRecyclerView.apply{
            adapter = allBooksAdapter
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        }

        try{
            loadBooks()
        }catch(e : Error){
            Log.d("DB_LOG_HOMEVIEW" , e.message.toString())
        }
    }

    fun loadBooks(){
        val dbHelper = DatabaseHelper(requireContext())
        //dbHelper.logAllBooksAndChapters(requireContext())
        val books = mutableListOf<Book>()
        val cursor = dbHelper.getAllBooks()
        cursor?.use{
            while(it.moveToNext()){
                val book = Book(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    title = it.getString(it.getColumnIndexOrThrow("title")),
                    author = it.getString(it.getColumnIndexOrThrow("author")),
                    numChapters = it.getLong(it.getColumnIndexOrThrow("numChapters")),
                    chaptersRead = it.getInt(it.getColumnIndexOrThrow("chaptersRead")),
                    isFavourite = it.getInt(it.getColumnIndexOrThrow("isFavourite")) == 1,
                    coverImage = it.getBlob(it.getColumnIndexOrThrow("coverImage"))
                )
                books.add(book)
            }
        }
        val favourites = books.filter { it.isFavourite }

        Log.d("DB_LOG_HOMEVIEW" , "All books size: ${books.size},Favourites size: ${favourites.size}")

        favouritesAdapter?.submitList(favourites)
        allBooksAdapter?.submitList(books)
    }

    class BookAdapter(
        private val showText : Boolean = true,
        private val onBookClick: ((Book) -> Unit)? = null)
        : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

        private val books = mutableListOf<Book>()

        fun submitList(newBooks: List<Book>) {
            books.clear()
            books.addAll(newBooks)
        }

        inner class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title = view.findViewById<TextView>(R.id.bookTitle)
            private val author = view.findViewById<TextView>(R.id.bookAuthor)
            private val numChapters = view.findViewById<TextView>(R.id.bookChapters)
            private val coverImageView = view.findViewById<ImageView>(R.id.bookCover)
            private val completion = view.findViewById<TextView>(R.id.bookCompletion)


            fun bind(book: Book) {
                if(showText){
                    title.text = book.title
                    author.text = book.author
                    val chaptersText =  "Chapters : ${book.numChapters}"
                    numChapters.text =chaptersText
                    val completionText = "Completion : ${(book.chaptersRead * 100 / book.numChapters)}%"
                    completion.text = completionText
                }


                try{
                    if(book.coverImage != null) {
                        val bitmap = BitmapFactory.decodeByteArray(book.coverImage, 0, book.coverImage.size)
                        coverImageView.setImageBitmap(bitmap)
                    } else {
                        coverImageView.setImageResource(R.drawable.default_bookcover) // Use a placeholder if no image
                    }
                }catch( e : Exception){
                    Log.d("ERR_HOMEFRAGMENT" , "Error trying to load cover image : ${e.message}")
                }

                //Set onClickListener
                itemView.setOnClickListener {
                    onBookClick?.invoke(book) // when a book is clicked call the lambda function with book
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val layout = if(showText) R.layout.item_book else R.layout.item_book_coveronly
            val view = LayoutInflater.from(parent.context)
                .inflate(layout, parent, false)
            return BookViewHolder(view)
        }

        override fun getItemCount() = books.size

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            holder.bind(books[position])
        }
    }

    //Function to swap fragment to chapter details
    private fun openBookDetail(book : Book){
        Log.d("HOME_FRAGMENT" ,"Book item clicked, id : ${book.id} , title : ${book.title}" )

        val action = HomeFragmentDirections.actionNavBooksToNavBookDetail(book.id)
        findNavController().navigate(action)
    }
}

