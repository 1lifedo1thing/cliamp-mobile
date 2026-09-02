package stream.cliamp.mobile.data.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import stream.cliamp.mobile.net.Http

private val Context.providerStore: DataStore<Preferences> by preferencesDataStore("providers")

/**
 * Configured provider accounts. Secret fields are encrypted through
 * [SecretStore] before they ever reach disk, and decrypted only when handed to
 * a client, so what sits in the DataStore file is ciphertext.
 */
class ProviderStore(private val context: Context) {

    private val accountsKey = stringPreferencesKey("accounts")

    val accounts: Flow<List<ProviderAccount>> =
        context.providerStore.data.map { prefs -> decode(prefs[accountsKey]) }

    suspend fun read(): List<ProviderAccount> = accounts.first()

    suspend fun save(account: ProviderAccount) {
        val spec = ProviderCatalog.byKey(account.providerKey)
        val secretKeys = spec?.fields?.filter { it.secret }?.map { it.key }?.toSet() ?: emptySet()
        val sealed = account.copy(
            values = account.values.mapValues { (k, v) ->
                if (k in secretKeys && v.isNotEmpty()) SecretStore.encrypt(v) else v
            }
        )
        context.providerStore.edit { prefs ->
            val list = decodeRaw(prefs[accountsKey]).filterNot { it.id == sealed.id }
            prefs[accountsKey] = Http.json.encodeToString(list + sealed.toStored())
        }
    }

    suspend fun remove(id: String) {
        context.providerStore.edit { prefs ->
            val list = decodeRaw(prefs[accountsKey]).filterNot { it.id == id }
            prefs[accountsKey] = Http.json.encodeToString(list)
        }
    }

    /** Stored form keeps secrets sealed; this is the only place they are opened. */
    private fun decode(raw: String?): List<ProviderAccount> =
        decodeRaw(raw).map { stored ->
            stored.toAccount().let { acc ->
                acc.copy(values = acc.values.mapValues { (_, v) -> SecretStore.decrypt(v) })
            }
        }

    private fun decodeRaw(raw: String?): List<StoredAccount> =
        raw?.let { runCatching { Http.json.decodeFromString<List<StoredAccount>>(it) }.getOrNull() }
            ?: emptyList()
}

@Serializable
private data class StoredAccount(
    val id: String,
    val providerKey: String,
    val label: String,
    val values: Map<String, String>,
) {
    fun toAccount() = ProviderAccount(id, providerKey, label, values)
}

private fun ProviderAccount.toStored() = StoredAccount(id, providerKey, label, values)
