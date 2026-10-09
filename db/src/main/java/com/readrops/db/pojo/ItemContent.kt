package com.readrops.db.pojo

import androidx.room.ColumnInfo
import com.readrops.db.entities.Item
import java.time.LocalDateTime

/**
 * Partial [Item] used to update the content of an existing item
 * without touching its id, feed, remote id or read/star state
 */
data class ItemContent(
    val id: Int,
    val title: String?,
    val description: String?,
    @ColumnInfo(name = "clean_description") val cleanDescription: String?,
    val link: String?,
    @ColumnInfo(name = "image_link") val imageLink: String?,
    val author: String?,
    @ColumnInfo(name = "pub_date") val pubDate: LocalDateTime?,
    val content: String?,
    @ColumnInfo(name = "read_time") val readTime: Double,
) {

    constructor(id: Int, item: Item) : this(
        id = id,
        title = item.title,
        description = item.description,
        cleanDescription = item.cleanDescription,
        link = item.link,
        imageLink = item.imageLink,
        author = item.author,
        pubDate = item.pubDate,
        content = item.content,
        readTime = item.readTime,
    )
}
