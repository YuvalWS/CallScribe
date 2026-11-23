package app.callscribe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** "Date" filter chip with a preset menu and a custom-range dialog. [code] is a [DateSelection] string. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFilterChip(code: String, onChange: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val active = code.isNotEmpty()
    Box {
        FilterChip(
            selected = active,
            onClick = { menu = true },
            label = { Text(if (active) DateSelection.label(code, today) else "Date") },
            leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) },
            trailingIcon = if (active) {
                {
                    Icon(
                        Icons.Default.Close, null,
                        Modifier.size(18.dp).clickable { onChange("") }
                            .semantics { contentDescription = "Clear date filter" },
                    )
                }
            } else null,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DatePreset.entries.forEach { p ->
                DropdownMenuItem(text = { Text(p.title) }, onClick = { menu = false; onChange(DateSelection.ofPreset(p)) })
            }
            DropdownMenuItem(text = { Text("Custom range…") }, onClick = { menu = false; picker = true })
            if (active) DropdownMenuItem(text = { Text("Any date") }, onClick = { menu = false; onChange("") })
        }
    }
    if (picker) {
        val current = DateSelection.resolve(code, today)
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = current?.let { DateFilter.toPickerMillis(it.start) },
            initialSelectedEndDateMillis = current?.let { DateFilter.toPickerMillis(it.end) },
        )
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis!!
                        val e = state.selectedEndDateMillis ?: s
                        picker = false
                        onChange(DateSelection.ofCustom(DateFilter.custom(s, e)))
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { picker = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state, Modifier.fillMaxWidth().heightIn(max = 520.dp), showModeToggle = false)
        }
    }
}
