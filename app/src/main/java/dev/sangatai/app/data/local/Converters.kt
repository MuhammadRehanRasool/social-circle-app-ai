package dev.sangatai.app.data.local

import androidx.room.TypeConverter
import dev.sangatai.app.domain.model.IdeaStatus

class Converters {

    @TypeConverter
    fun fromStatus(status: IdeaStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): IdeaStatus =
        IdeaStatus.entries.firstOrNull { it.name == value } ?: IdeaStatus.PENDING
}
