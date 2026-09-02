package stream.cliamp.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import stream.cliamp.mobile.data.provider.ProviderAccount
import stream.cliamp.mobile.data.provider.ProviderCatalog
import stream.cliamp.mobile.ui.components.CliampIcons
import stream.cliamp.mobile.ui.components.Gutter
import stream.cliamp.mobile.ui.components.HairlineDivider
import stream.cliamp.mobile.ui.components.ListRow
import stream.cliamp.mobile.ui.components.ScreenHeader
import stream.cliamp.mobile.ui.components.SectionLabel
import stream.cliamp.mobile.ui.theme.CliampType
import stream.cliamp.mobile.ui.theme.LocalPalette
import stream.cliamp.mobile.ui.theme.Mono

/**
 * Configured providers, and a `+` to add one. Hairline rows, because a provider
 * without state is a list entry; the wizard is where it becomes an object.
 */
@Composable
fun ProvidersScreen(
    accounts: List<ProviderAccount>,
    onAdd: () -> Unit,
    onOpen: (ProviderAccount) -> Unit,
    onEdit: (ProviderAccount) -> Unit,
    onRemove: (ProviderAccount) -> Unit,
) {
    val p = LocalPalette.current

    Column(Modifier.fillMaxSize().background(p.ground)) {
        ScreenHeader {
            Row(
                Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("Providers", CliampType.screenTitle, p.ink)
                // clear of QueueBar, which floats over this corner everywhere
                Box(
                    Modifier
                        .padding(end = 56.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, p.keyBorder, RoundedCornerShape(6.dp))
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CliampIcons.Plus, "add provider", Modifier.size(13.dp), tint = p.accent)
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (accounts.isEmpty()) {
                item {
                    Column(
                        Modifier.padding(horizontal = Gutter, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Mono(
                            "no providers yet.",
                            CliampType.rowPrimary,
                            p.inkSecondary,
                        )
                        Mono(
                            "point cliamp at a music server you own and it reads the " +
                                "library over its own api. credentials stay on the phone, " +
                                "encrypted by the keystore.",
                            CliampType.body,
                            p.inkTertiary,
                        )
                    }
                }
            } else {
                item { SectionLabel("connected — ${accounts.size}") }
                items(accounts.size, key = { accounts[it].id }) { i ->
                    val a = accounts[i]
                    val spec = ProviderCatalog.byKey(a.providerKey)
                    ListRow(
                        onClick = { onOpen(a) },
                        verticalPadding = 12.dp,
                        leading = {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(1.dp, p.chipBorder, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(CliampIcons.Server, null, Modifier.size(14.dp), tint = p.amber)
                            }
                        },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Browsing is the primary action now, so editing
                                // moves out of the row tap and onto its own control.
                                Mono(
                                    "EDIT",
                                    CliampType.tabLabel,
                                    p.inkSecondary,
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onEdit(a) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                                Mono(
                                    "DROP",
                                    CliampType.tabLabel,
                                    p.destructiveInk,
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onRemove(a) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        },
                    ) {
                        Mono(a.label.ifBlank { spec?.name ?: a.providerKey }, CliampType.rowPrimaryMedium, p.ink, maxLines = 1)
                        Mono(a.url.ifBlank { spec?.name.orEmpty() }, CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 12.dp)) {
                    SectionLabel("available")
                    ProviderCatalog.all.forEach { spec ->
                        ListRow(
                            onClick = onAdd,
                            verticalPadding = 12.dp,
                            leading = {
                                Icon(CliampIcons.Plus, null, Modifier.size(12.dp), tint = p.accent)
                            },
                        ) {
                            Mono(spec.name, CliampType.rowPrimary, p.ink, maxLines = 1)
                            Mono(spec.intro.first(), CliampType.rowSecondary, p.inkTertiary, maxLines = 1)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 14.dp)) {
                        Mono(
                            "coming: " + ProviderCatalog.planned.joinToString(", ") { it.lowercase() },
                            CliampType.meta,
                            p.inkFaint,
                        )
                    }
                    HairlineDivider()
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
