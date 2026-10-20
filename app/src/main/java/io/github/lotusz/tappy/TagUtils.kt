package io.github.lotusz.tappy

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log

object TagUtils {

    const val TOGGLE_URI = "tappy://toggle"

    /**
     * Only NFC Forum Type 2 stickers/keyfobs (NTAG21x, MIFARE Ultralight) can be paired.
     * Bank and travel cards are ISO-DEP smart cards, so they're rejected before anything is written.
     */
    fun isPairable(tag: Tag): Boolean =
        MifareUltralight::class.java.name in tag.techList && IsoDep::class.java.name !in tag.techList

    fun idOf(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

    /**
     * Writes a tappy:// URI plus an Android Application Record so tapping the tag opens Tappy
     * directly, even when the app isn't running. The tag is still matched by UID; this just
     * makes dispatch reliable. Returns false if the tag can't be written (UID matching still works).
     */
    fun writeLaunchRecord(tag: Tag, packageName: String): Boolean {
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createUri(TOGGLE_URI),
                NdefRecord.createApplicationRecord(packageName),
            )
        )
        return try {
            Ndef.get(tag)?.use { ndef ->
                ndef.connect()
                if (!ndef.isWritable || ndef.maxSize < message.byteArrayLength) return false
                ndef.writeNdefMessage(message)
                return true
            }
            NdefFormatable.get(tag)?.use { formatable ->
                formatable.connect()
                formatable.format(message)
                return true
            }
            false
        } catch (e: Exception) {
            Log.w("Tappy", "Could not write tag", e)
            false
        }
    }
}
