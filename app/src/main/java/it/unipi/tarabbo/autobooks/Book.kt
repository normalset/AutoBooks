package it.unipi.tarabbo.autobooks

data class Book(
    val id : Long,
    val title : String,
    val author : String,
    val numChapters : Long,
    val chaptersRead : Int,
    val isFavourite : Boolean,
    val coverImage : ByteArray?
)

