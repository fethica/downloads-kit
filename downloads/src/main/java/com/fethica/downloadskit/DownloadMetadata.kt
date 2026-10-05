package com.fethica.downloadskit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * What a host attached to a download, carried inside `DownloadRequest.data` so the
 * offline screen can be rendered from the media3 index alone.
 *
 * Decoding never throws: a blob written by an older version, or by something else
 * entirely, comes back as an empty record and the row still draws.
 */
internal data class DownloadMetadata(
    val title: String,
    val subtitle: String?,
    val group: String?,
    val artwork: ByteArray? = null,
) {
    /** This record with a blank group filed as no group; see [normalizeGroup]. */
    fun withFiledGroup(): DownloadMetadata {
        val filed = normalizeGroup(group)
        return if (filed == group) this else copy(group = filed)
    }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeUTF(title)
            out.writeNullableUtf(subtitle)
            out.writeNullableUtf(group)
            if (artwork == null) {
                out.writeInt(-1)
            } else {
                out.writeInt(artwork.size)
                out.write(artwork)
            }
        }
        return bytes.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadMetadata) return false
        return title == other.title &&
            subtitle == other.subtitle &&
            group == other.group &&
            artwork.contentEquals(other.artwork)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + (subtitle?.hashCode() ?: 0)
        result = 31 * result + (group?.hashCode() ?: 0)
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        private const val VERSION = 1

        val EMPTY: DownloadMetadata = DownloadMetadata(title = "", subtitle = null, group = null)

        fun decode(data: ByteArray?): DownloadMetadata {
            if (data == null || data.isEmpty()) return EMPTY
            return runCatching {
                DataInputStream(ByteArrayInputStream(data)).use { input ->
                    val version = input.readByte().toInt()
                    if (version != VERSION) return EMPTY
                    val title = input.readUTF()
                    val subtitle = input.readNullableUtf()
                    val group = input.readNullableUtf()
                    val artworkSize = input.readInt()
                    val artwork = if (artworkSize < 0) {
                        null
                    } else {
                        ByteArray(artworkSize).also { input.readFully(it) }
                    }
                    DownloadMetadata(title, subtitle, group, artwork)
                }
            }.getOrDefault(EMPTY)
        }
    }
}

private fun DataOutputStream.writeNullableUtf(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null
