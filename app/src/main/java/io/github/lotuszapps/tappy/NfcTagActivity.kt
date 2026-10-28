package io.github.lotuszapps.tappy

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Invisible activity the system launches when a tag is tapped while Tappy isn't in front. */
class NfcTagActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
        if (tag != null) {
            val result = BlockController.onTagScanned(this, TagUtils.idOf(tag))
            // Other people's stickers can also land here via TECH_DISCOVERED; stay quiet for them.
            if (result != BlockController.TagResult.WRONG_TAG) {
                Toast.makeText(applicationContext, BlockController.describe(this, result), Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
