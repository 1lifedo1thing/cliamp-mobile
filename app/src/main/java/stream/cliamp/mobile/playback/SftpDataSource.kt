package stream.cliamp.mobile.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import stream.cliamp.mobile.data.provider.SftpLibrary
import stream.cliamp.mobile.data.provider.SshPool
import java.io.IOException
import java.io.InputStream

/**
 * Reads a track straight off an SSH host, so playing from a server is a stream
 * rather than a download.
 *
 * The URI carries the account and the absolute remote path and nothing else -
 * `cliamp-sftp://<accountId>/srv/music/…` - so it is safe to write into history
 * and the queue. Credentials stay in the encrypted provider store and are
 * looked up here, at the moment the file is opened.
 *
 * [DataSpec.position] is honoured by opening the remote file at that offset,
 * which is what lets the player seek inside a track and what lets the extractor
 * go back for a header it needs.
 */
@UnstableApi
class SftpDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var lease: SshPool.Lease? = null
    private var file: RemoteFile? = null
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri

        val accountId = dataSpec.uri.authority
            ?: throw IOException("sftp uri without an account: ${dataSpec.uri}")
        val path = dataSpec.uri.path
            ?: throw IOException("sftp uri without a path: ${dataSpec.uri}")
        val account = SftpLibrary.account(accountId)
            ?: throw IOException("no ssh account $accountId; it may have been removed")

        val held = SshPool.lease(account)
        lease = held
        try {
            val remote = held.sftp.open(path, setOf(OpenMode.READ))
            file = remote
            val length = remote.length()
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                length - dataSpec.position
            }
            if (bytesRemaining < 0) {
                throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            }
            // A plain RemoteFileInputStream costs a round trip per read, which
            // over anything but a LAN is not enough throughput to keep a FLAC
            // fed. The read-ahead stream keeps several requests in flight.
            stream = remote.ReadAheadRemoteFileInputStream(READ_AHEAD, dataSpec.position)
        } catch (t: Throwable) {
            closeQuietly()
            throw if (t is IOException) t else IOException(t)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = stream ?: throw IOException("sftp read before open")
        val wanted = minOf(bytesRemaining, length.toLong()).toInt()
        val read = source.read(buffer, offset, wanted)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeQuietly()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /**
     * The lease goes back last and always. Leaking one would take a connection
     * out of the pool of two for the rest of the session.
     */
    private fun closeQuietly() {
        runCatching { stream?.close() }
        runCatching { file?.close() }
        runCatching { lease?.close() }
        stream = null
        file = null
        lease = null
    }

    companion object {
        const val SCHEME = "cliamp-sftp"

        /** Percent-encodes, so spaces and `#` in a filename survive the round trip. */
        fun uriFor(accountId: String, path: String): String = Uri.Builder()
            .scheme(SCHEME)
            .authority(accountId)
            .path(path)
            .build()
            .toString()

        /** Requests kept in flight ahead of the reader. */
        private const val READ_AHEAD = 16
    }
}

/**
 * Sends `cliamp-sftp://` to [SftpDataSource] and everything else - http, file,
 * content - to the chain the app already had.
 *
 * Media3 picks a data source before it knows the URI, so the choice cannot be
 * made in the factory; both are built and the scheme decides at [open].
 * Building an [SftpDataSource] costs nothing until then, since it connects
 * only when a file is actually opened.
 */
@UnstableApi
class CliampDataSourceFactory(
    private val fallback: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        SchemeRouter(fallback.createDataSource(), SftpDataSource())
}

@UnstableApi
private class SchemeRouter(
    private val fallback: DataSource,
    private val sftp: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        sftp.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (dataSpec.uri.scheme == SftpDataSource.SCHEME) sftp else fallback
        // Assigned before opening on purpose: Media3 calls close() after a
        // failed open, and that has to reach the source that failed.
        active = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("read before open")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        val current = active
        active = null
        current?.close()
    }
}
