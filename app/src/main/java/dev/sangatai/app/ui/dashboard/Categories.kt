package dev.braindump.splitter.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.IdeaCategory

/** iOS system hues, readable on both the light and dark glass. */
internal val IdeaCategory.color: Color
    get() = when (this) {
        IdeaCategory.PERSONAL -> Color(0xFFFF375F)
        IdeaCategory.WORK -> Color(0xFF0A84FF)
        IdeaCategory.HEALTH -> Color(0xFF30D158)
        IdeaCategory.LEARNING -> Color(0xFFFF9F0A)
        IdeaCategory.MONEY -> Color(0xFF40C8E0)
        IdeaCategory.CREATIVE -> Color(0xFFBF5AF2)
        IdeaCategory.TECH -> Color(0xFF5E5CE6)
        IdeaCategory.OTHER -> Color(0xFF8E8E93)
    }

internal val IdeaCategory.emoji: String
    get() = when (this) {
        IdeaCategory.PERSONAL -> "🏠"
        IdeaCategory.WORK -> "💼"
        IdeaCategory.HEALTH -> "💪"
        IdeaCategory.LEARNING -> "📚"
        IdeaCategory.MONEY -> "💰"
        IdeaCategory.CREATIVE -> "🎨"
        IdeaCategory.TECH -> "💻"
        IdeaCategory.OTHER -> "📌"
    }

@get:StringRes
internal val IdeaCategory.label: Int
    get() = when (this) {
        IdeaCategory.PERSONAL -> R.string.category_personal
        IdeaCategory.WORK -> R.string.category_work
        IdeaCategory.HEALTH -> R.string.category_health
        IdeaCategory.LEARNING -> R.string.category_learning
        IdeaCategory.MONEY -> R.string.category_money
        IdeaCategory.CREATIVE -> R.string.category_creative
        IdeaCategory.TECH -> R.string.category_tech
        IdeaCategory.OTHER -> R.string.category_other
    }

/** Small tinted pill: emoji plus name. */
@Composable
internal fun CategoryTag(category: IdeaCategory, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(category.color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The hue tints the pill; text stays the theme color so orange and green stay legible on white.
        Text(
            text = "${category.emoji} ${stringResource(category.label)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
