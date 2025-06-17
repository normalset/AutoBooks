package it.unipi.tarabbo.autobooks
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Chapter(
    val bookId : Long ,
    val chapterNumber: Long,
    val title : String,
    val text : String ,
    val isRead : Int ,
    val audioGenerated : Boolean ,
    val isFavourite : Boolean,
    val audioData : ByteArray? = null
) : Parcelable