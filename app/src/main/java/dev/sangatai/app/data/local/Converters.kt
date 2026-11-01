package dev.braindump.splitter.data.local

import androidx.room.TypeConverter
import dev.braindump.splitter.domain.model.IdeaStatus

class Converters {

    @TypeConverter
    fun fromStatus(status: IdeaStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): IdeaStatus =
        IdeaStatus.entries.firstOrNull { it.name == value } ?: IdeaStatus.PENDING
}
