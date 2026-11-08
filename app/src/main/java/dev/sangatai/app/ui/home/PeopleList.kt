package dev.sangatai.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sangatai.app.domain.model.Person
import dev.sangatai.app.ui.common.Avatar
import dev.sangatai.app.ui.common.RelationPill
import dev.sangatai.app.ui.common.glassFieldColors
import dev.sangatai.app.ui.common.relativeTime
import dev.sangatai.app.ui.common.tagText
import dev.sangatai.app.ui.theme.GlassPanel
import java.util.Locale

/** Everyone, most recently talked about first, with a search over names, relations and tags. */
@Composable
fun PeopleList(
    people: List<Person>,
    loading: Boolean,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(people, query) {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            people
        } else {
            people.filter { p ->
                p.name.lowercase(Locale.getDefault()).contains(q) ||
                    p.relation?.contains(q) == true ||
                    p.tags.any { tagText(it.tag).contains(q) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "search") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search names, relations, tags") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = glassFieldColors(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item(key = "add") {
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Add someone by hand",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        if (shown.isEmpty() && !loading) {
            item(key = "empty") {
                Text(
                    text = if (people.isEmpty()) "Nobody yet. Tell SangatAI about your day." else "Nobody matches “${query.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(shown, key = { it.id }) { person ->
            PersonRow(person, onClick = { onOpen(person.id) })
        }
    }
}

@Composable
private fun PersonRow(person: Person, onClick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(person.name, person.relation)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (person.relation != null) RelationPill(person.relation, Modifier.padding(start = 8.dp))
                }
                val tags = person.tags.take(3).joinToString(" · ") { tagText(it.tag) }
                if (tags.isNotEmpty()) {
                    Text(
                        tags,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                relativeTime(person.lastInteractionAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
