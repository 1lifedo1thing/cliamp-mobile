package stream.cliamp.mobile.data.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import stream.cliamp.mobile.data.db.CliampDatabase
import stream.cliamp.mobile.data.db.ProviderEntity
import stream.cliamp.mobile.net.Http

/**
 * Configured provider accounts. Secret fields are encrypted through
 * [SecretStore] before they ever reach disk, and decrypted only when handed to
 * a client, so what sits in the DataStore file is ciphertext.
 */
class ProviderStore(private val context: Context) {

    private val dao by lazy { CliampDatabase.get(context).providers() }

    val accounts: Flow<List<ProviderAccount>> =
        dao.all().map { rows -> rows.map { it.toAccount() } }

    suspend fun read(): List<ProviderAccount> = dao.read().map { it.toAccount() }

    suspend fun save(account: ProviderAccount) {
        val spec = ProviderCatalog.byKey(account.providerKey)
        val secretKeys = spec?.fields?.filter { it.secret }?.map { it.key }?.toSet() ?: emptySet()
        val sealed = account.values.mapValues { (k, v) ->
            if (k in secretKeys && v.isNotEmpty()) SecretStore.encrypt(v) else v
        }
        dao.upsert(
            ProviderEntity(
                id = account.id,
                providerKey = account.providerKey,
                label = account.label,
                valuesJson = Http.json.encodeToString(sealed),
            )
        )
        // Editing a host or a password leaves open SSH connections still
        // speaking as whoever the account used to be, so they are retired here
        // rather than left to drop on their own.
        SshPool.evict(account.id)
    }

    suspend fun remove(id: String) {
        dao.remove(id)
        SshPool.evict(id)
        SftpLibrary.forget(id)
    }

    /** Secrets are opened here and nowhere else. */
    private fun ProviderEntity.toAccount(): ProviderAccount {
        val stored = runCatching {
            Http.json.decodeFromString<Map<String, String>>(valuesJson)
        }.getOrDefault(emptyMap())
        return ProviderAccount(
            id = id,
            providerKey = providerKey,
            label = label,
            values = stored.mapValues { (_, v) -> SecretStore.decrypt(v) },
        )
    }
}
