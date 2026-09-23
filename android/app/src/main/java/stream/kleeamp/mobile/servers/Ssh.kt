package stream.kleeamp.mobile.servers

import android.util.Base64
import android.util.Log
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "kleeamp/ssh"

/** How to reach one host, read out of a provider account's saved fields. */
data class SshConfig(
    val host: String,
    val port: Int,
    val user: String,
    val auth: String,
    val password: String,
    val privateKey: String,
    val passphrase: String,
    /** `SHA256:...` of the host key, pinned at the first successful probe. */
    val fingerprint: String,
    val folders: List<String>,
) {
    val where: String get() = if (port == SSH_DEFAULT_PORT) "$user@$host" else "$user@$host:$port"
}

const val SSH_DEFAULT_PORT = 22

fun ProviderAccount.ssh(): SshConfig = sshConfig(values)

fun sshConfig(values: Map<String, String>): SshConfig {
    val host = values["host"].orEmpty().trim()
    val port = values["port"]?.trim()?.toIntOrNull() ?: SSH_DEFAULT_PORT
    return SshConfig(
        host = host,
        port = port,
        user = values["user"].orEmpty().trim(),
        auth = values["_auth"] ?: "password",
        password = values["password"].orEmpty(),
        privateKey = values["key"].orEmpty(),
        passphrase = values["passphrase"].orEmpty(),
        fingerprint = pinnedFingerprint(values["fingerprint"].orEmpty(), host, port),
        folders = parseFolders(values["folders"].orEmpty()),
    )
}

/**
 * The stored fingerprint carries the host it was seen on - `nas:22 SHA256:…` -
 * and only counts for that one.
 *
 * Without the prefix, pointing an existing account at a different machine would
 * check the new host against the old host's key and refuse to connect, with
 * nothing but "the host key does not match" to explain why. Repointing is not a
 * key change; it is a different host, and it goes back to trusting on first use.
 */
private fun pinnedFingerprint(stored: String, host: String, port: Int): String {
    val trimmed = stored.trim()
    val seenOn = trimmed.substringBefore(' ', "")
    val key = trimmed.substringAfter(' ', "").trim()
    return if (seenOn == "$host:$port" && key.isNotBlank()) key else ""
}

/** How a freshly pinned key is written back into the account's fields. */
fun storedFingerprint(cfg: SshConfig, fingerprint: String): String =
    "${cfg.host}:${cfg.port} $fingerprint"

/**
 * One folder per line is what the field asks for, but commas are what people
 * type, so both are accepted. Trailing slashes are dropped so that `/srv/music`
 * and `/srv/music/` index to the same paths rather than to two libraries.
 */
fun parseFolders(raw: String): List<String> = raw
    .split('\n', ',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { if (it.length > 1) it.trimEnd('/') else it }
    .distinct()

/**
 * OpenSSH's own fingerprint format, so what the wizard shows can be compared
 * against `ssh-keygen -lf` on the server without any conversion in between.
 */
fun fingerprintOf(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val sha = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.encodeToString(sha, Base64.NO_WRAP or Base64.NO_PADDING)
}

/**
 * Trust-on-first-use, pinned afterwards.
 *
 * The first probe has nothing to compare against, so it records what the host
 * offered and the wizard shows it; every connection after that must present the
 * same key or it is refused. sshj's own PromiscuousVerifier would accept any
 * key forever, which is the whole of what SSH host keys defend against.
 *
 * [learned] is how the recorded fingerprint gets back out to the wizard, since
 * a verifier cannot return anything but a boolean.
 */
class PinnedHostKey(
    private val expected: String,
    private val learned: ((String) -> Unit)? = null,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val actual = runCatching { fingerprintOf(key) }.getOrElse { return false }
        if (expected.isBlank()) {
            learned?.invoke(actual)
            return true
        }
        if (actual == expected) return true
        Log.w(TAG, "host key changed for $hostname: expected $expected, got $actual")
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/**
 * SSH connections, pooled per account.
 *
 * A handshake costs a few hundred milliseconds and several round trips, which
 * is far too much to pay per track, so connections are kept and reused. Several
 * per account rather than one: a library scan walks hundreds of directories, and
 * sharing a single SFTP channel with playback would put those listings in front
 * of the reads feeding the decoder.
 *
 * Every entry carries the generation its account was on when it was opened, so
 * editing a host or password retires the old connections instead of leaving
 * them serving the previous credentials until they happen to drop.
 */
object SshPool {

    // One for the playing track, one for the next - the player primes it before
    // the transition - and one for a scan running alongside both.
    private const val MAX_PER_ACCOUNT = 3
    private const val WAIT_MS = 30_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val KEEPALIVE_SECONDS = 30
    /**
     * An idle entry older than this is retired on borrow instead of reused:
     * NATs and firewalls drop idle TCP long before SSH notices, so a pooled
     * connection that has sat quietly is the classic half-open read hang.
     */
    private const val IDLE_TTL_MS = 120_000L
    /** Absolute age cap: even a busy entry is re-handshaked past this. */
    private const val MAX_AGE_MS = 15 * 60_000L
    /** Consecutive connect failures before an account backs off... */
    private const val BREAKER_FAILURES = 3
    /** ...for this long, so a dead host fails fast instead of stacking
     * 15 s handshake timeouts behind every tap. */
    private const val BREAKER_COOLDOWN_MS = 10_000L

    internal class Pooled(
        val accountId: String,
        val generation: Int,
        val client: SSHClient,
        val sftp: SFTPClient,
    ) {
        val createdAt = System.currentTimeMillis()
        @Volatile var lastReleasedAt = createdAt
        val healthy: Boolean get() = client.isConnected && client.isAuthenticated

        /** Too long idle - or alive - to hand out without a fresh handshake. */
        fun stale(now: Long) = now - lastReleasedAt > IDLE_TTL_MS || now - createdAt > MAX_AGE_MS

        fun close() {
            runCatching { sftp.close() }
            runCatching { client.disconnect() }
        }
    }

    private data class Breaker(var failures: Int = 0, var until: Long = 0)
    private val breakers = HashMap<String, Breaker>()

    private val lock = ReentrantLock()
    private val freed = lock.newCondition()
    private val idle = HashMap<String, ArrayDeque<Pooled>>()
    private val leased = HashMap<String, Int>()
    private val generation = HashMap<String, Int>()

    /**
     * A checked-out connection. Playback holds one for as long as a track is
     * open, which is why this exists alongside [useSftp]: a data source cannot
     * do its reading inside a lambda.
     */
    class Lease internal constructor(internal val entry: Pooled) : Closeable {
        val sftp: SFTPClient get() = entry.sftp
        override fun close() = release(entry)

        /** Retire a connection that just proved itself broken (failed open
         * or read) instead of recycling it for the next borrow to trip over. */
        fun discard() = discard(entry)
    }

    fun lease(account: ProviderAccount): Lease = Lease(acquire(account))

    /**
     * Runs [block] against a connected SFTP client. Blocking on purpose: the
     * callers are ExoPlayer's loader thread and scans already on
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun <T> useSftp(account: ProviderAccount, block: (SFTPClient) -> T): T =
        lease(account).use { block(it.sftp) }

    /** Retires everything held for [accountId]; the next use reconnects. */
    fun evict(accountId: String) {
        val doomed: List<Pooled>
        lock.withLock {
            generation[accountId] = (generation[accountId] ?: 0) + 1
            breakers.remove(accountId)
            doomed = idle.remove(accountId)?.toList() ?: emptyList()
            freed.signalAll()
        }
        doomed.forEach { it.close() }
    }

    private fun acquire(account: ProviderAccount): Pooled {
        val id = account.id
        val deadline = System.currentTimeMillis() + WAIT_MS
        var gen = 0
        // Tearing down a connection that has already died can sit on a socket
        // for a while, and doing it under the pool lock would stall playback.
        val dead = ArrayList<Pooled>(2)
        try {
            lock.withLock {
                breakers[id]?.let { breaker ->
                    if (System.currentTimeMillis() < breaker.until) {
                        throw IOException("ssh to ${account.ssh().host} cooling down after failures")
                    }
                }
                while (true) {
                    val queue = idle.getOrPut(id) { ArrayDeque() }
                    val now = System.currentTimeMillis()
                    while (queue.isNotEmpty()) {
                        val candidate = queue.removeFirst()
                        if (candidate.healthy && !candidate.stale(now)) {
                            leased[id] = (leased[id] ?: 0) + 1
                            return candidate
                        }
                        dead += candidate
                    }
                    val out = leased[id] ?: 0
                    if (out < MAX_PER_ACCOUNT) {
                        leased[id] = out + 1
                        gen = generation[id] ?: 0
                        break
                    }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) throw IOException("no free connection to ${account.ssh().host}")
                    freed.await(remaining, TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            dead.forEach { it.close() }
        }
        // Connecting outside the monitor for the same reason: a handshake
        // against an unreachable host takes the full connect timeout.
        val fresh = try {
            val client = connect(account.ssh())
            // A client that connects but cannot open an SFTP channel would
            // otherwise be left holding a socket nothing can reach.
            try {
                Pooled(id, gen, client, client.newSFTPClient())
            } catch (failure: Throwable) {
                runCatching { client.disconnect() }
                throw failure
            }
        } catch (failure: Throwable) {
            lock.withLock {
                leased[id] = (leased[id] ?: 1) - 1
                val breaker = breakers.getOrPut(id) { Breaker() }
                if (++breaker.failures >= BREAKER_FAILURES) {
                    breaker.until = System.currentTimeMillis() + BREAKER_COOLDOWN_MS
                }
                freed.signalAll()
            }
            throw failure
        }
        lock.withLock { breakers.remove(id) }
        return fresh
    }

    /**
     * Drop a known-bad entry without recycling it. The socket teardown stays
     * outside the lock: closing a dead connection can sit on it for a while.
     */
    internal fun discard(entry: Pooled) {
        lock.withLock {
            leased[entry.accountId] = (leased[entry.accountId] ?: 1) - 1
            freed.signalAll()
        }
        entry.close()
    }

    internal fun release(entry: Pooled) {
        var keep = entry.healthy
        lock.withLock {
            val id = entry.accountId
            leased[id] = (leased[id] ?: 1) - 1
            if (keep && entry.generation != (generation[id] ?: 0)) keep = false
            if (keep) {
                entry.lastReleasedAt = System.currentTimeMillis()
                idle.getOrPut(id) { ArrayDeque() }.addLast(entry)
            }
            freed.signalAll()
        }
        if (!keep) entry.close()
    }

    fun connect(cfg: SshConfig, verifier: HostKeyVerifier = PinnedHostKey(cfg.fingerprint)): SSHClient {
        registerBouncyCastle()
        require(cfg.host.isNotBlank()) { "no host configured" }
        val client = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
        client.addHostKeyVerifier(verifier)
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = SOCKET_TIMEOUT_MS
        try {
            client.connect(cfg.host, cfg.port)
            client.auth(cfg.user, authMethods(client, cfg))
            // A phone sleeps, and a NAT that has seen no packets for a few
            // minutes forgets the mapping. Without this the connection looks
            // fine and the next read hangs until the socket timeout.
            client.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            throw t
        }
        return client
    }

    /**
     * `none` first, always.
     *
     * Tailscale SSH authenticates on tailnet identity - the WireGuard session
     * is the credential - and offers no password or public key method at all,
     * so it accepts `none` and nothing else. OpenSSH's own client opens with a
     * `none` request too, to enumerate what the server will take, so this costs
     * one packet against an ordinary sshd and makes a Tailscale host work
     * whichever credential type the account was set up with.
     */
    private fun authMethods(client: SSHClient, cfg: SshConfig): List<AuthMethod> = buildList {
        add(AuthNone())
        when (cfg.auth) {
            "none" -> Unit
            "key" -> add(AuthPublickey(keyProvider(client, cfg)))
            else -> add(AuthPassword(PasswordUtils.createOneOff(cfg.password.toCharArray())))
        }
    }

    private fun keyProvider(client: SSHClient, cfg: SshConfig): KeyProvider {
        val passfinder = cfg.passphrase.takeIf { it.isNotEmpty() }
            ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        // sshj needs the -----BEGIN/-----END header lines to know what format
        // the key is; pasting just the base64 body gives it nothing to look at.
        // Try what was pasted, then re-wrapped for the two common headers, and
        // keep whichever actually loads.
        val raw = cfg.privateKey.trim()
        val attempts = buildList {
            add(raw)
            if (!raw.startsWith("-----BEGIN")) {
                val body = raw.lines().joinToString("") { it.trim() }
                if (body.isNotBlank()) {
                    add("-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----")
                    add("-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----")
                }
            }
        }.distinct()
        var last: Throwable? = null
        for (attempt in attempts) {
            try {
                return client.loadKeys(attempt + "\n", null, passfinder)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IOException("no private key given")
    }

    /**
     * Android ships a cut-down BouncyCastle under the name `BC`, and it is
     * missing most of what a modern key exchange needs - ed25519, curve25519,
     * chacha20-poly1305. sshj looks the provider up by that name, finds the
     * stripped one and fails on any current server, and `addProvider` will not
     * overwrite a name that is already taken, so the platform's has to come out
     * first.
     *
     * This only moves BouncyCastle. TLS still goes through Conscrypt
     * (`AndroidOpenSSL`) and the provider secrets still go through
     * `AndroidKeyStore`, neither of which is touched by this.
     */
    private fun registerBouncyCastle() {
        if (bouncyCastleReady) return
        synchronized(this) {
            if (bouncyCastleReady) return
            runCatching {
                val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                if (existing != null && existing !is BouncyCastleProvider) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                }
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
                    Security.addProvider(BouncyCastleProvider())
                }
            }.onFailure { Log.w(TAG, "bouncycastle registration failed: ${it.message}") }
            bouncyCastleReady = true
        }
    }

    @Volatile private var bouncyCastleReady = false
}

/**
 * Connects, verifies the folders and comes back with what the wizard needs to
 * save: the host key it now trusts, and the folders it will index.
 *
 * Nothing is written until this succeeds, which for SSH matters more than for
 * an HTTP provider - a wrong path or a refused key would otherwise surface as
 * an empty library with nothing to point at.
 */
suspend fun probeSsh(values: Map<String, String>): Result<ProviderIdentity> =
    withContext(Dispatchers.IO) {
        val cfg = sshConfig(values)
        runCatching {
            var pinned = cfg.fingerprint
            SshPool.connect(cfg, PinnedHostKey(cfg.fingerprint) { pinned = it }).use { client ->
                client.newSFTPClient().use { sftp ->
                    val folders = cfg.folders.ifEmpty { suggestMusicFolders(sftp) }
                    if (folders.isEmpty()) {
                        error("connected, but found no music folder - give it a path")
                    }
                    val missing = folders.filterNot { isDirectory(sftp, it) }
                    if (missing.isNotEmpty()) {
                        error("not a folder on the server: " + missing.joinToString(", "))
                    }
                    ProviderIdentity(
                        name = cfg.where,
                        detail = pinned,
                        values = mapOf(
                            "fingerprint" to storedFingerprint(cfg, pinned),
                            "folders" to folders.joinToString("\n"),
                        ),
                    )
                }
            }
        }.recoverCatching { throw IOException(sshFailureMessage(it), it) }
    }

/**
 * sshj's own messages are accurate and unhelpful - "Exhausted available
 * authentication methods" is not what went wrong from where the user is
 * standing.
 */
private fun sshFailureMessage(t: Throwable): String {
    val raw = t.message.orEmpty()
    return when {
        t is UserAuthException || raw.contains("Exhausted available authentication") ->
            "the server refused those credentials"
        raw.contains("subsystem", ignoreCase = true) ->
            "connected, but the server does not offer sftp"
        t is TransportException && raw.contains("verif", ignoreCase = true) ||
            raw.contains("Could not verify") ->
            "the host key does not match the one saved for this account"
        t is UnknownHostException -> "no such host"
        t is ConnectException || t is SocketTimeoutException -> "could not reach the host on that port"
        raw.isBlank() -> "could not connect"
        else -> raw
    }
}
