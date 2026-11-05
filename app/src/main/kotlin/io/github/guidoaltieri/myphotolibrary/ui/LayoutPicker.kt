package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Layouts

/**
 * Organizzazione in cartelle per data (Importa e iCloud Foto): schemi pronti o personalizzato con
 * {anno} {mese} {giorno} {nome_mese}, anteprima del percorso e uso delle cartelle esistenti.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderLayoutPicker(layout: String, onLayout: (String) -> Unit, useExisting: Boolean, onUseExisting: (Boolean) -> Unit, destLabel: String) {
    var custom by remember { mutableStateOf(Layouts.LAYOUTS.none { it.first == layout }) }
    var open by remember { mutableStateOf(false) }
    Column {
        Text("Organizza in cartelle", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ExposedDropdownMenuBox(open, { open = it }) {
            OutlinedTextField(if (custom) "Personalizzato" else Layouts.LAYOUTS.first { it.first == layout }.second, {}, readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(open, { open = false }) {
                Layouts.LAYOUTS.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { onLayout(k); custom = false; open = false }) }
                DropdownMenuItem({ Text("Personalizzato…") }, { custom = true; open = false })
            }
        }
        if (custom) {
            val err = Layouts.validate(layout)
            OutlinedTextField(layout, onLayout, label = { Text("Schema (${Layouts.TOKENS})") }, singleLine = true,
                isError = err != null, supportingText = { Text(err ?: "es. Foto {anno}/{mese} {nome_mese}") }, modifier = Modifier.fillMaxWidth())
        }
        Text(Layouts.preview(layout, destLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(useExisting, onUseExisting)
            Text("Usa le cartelle anno/mese già esistenti (es. «05 - Maggio», «2024-05»)")
        }
    }
}
