package com.l4.notchlive

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Opens a match in 365Scores: its app if it's installed and takes the link, else the browser.
 * No screen of its own (the notch and notifications start it, then it's gone).
 */
class MatchActivity : Activity() {
    companion object {
        private const val PKG_365 = "com.scores365"

        fun intent(ctx: Context, url: String): Intent =
            Intent(ctx, MatchActivity::class.java)
                .putExtra("url", url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")
        if (url != null) open(Uri.parse(url))
        finish()
    }

    private fun open(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(Intent(view).setPackage(PKG_365))
            return
        } catch (e: ActivityNotFoundException) {
            // 365Scores isn't installed, or doesn't take web links.
        } catch (e: SecurityException) {
            // Same.
        }
        try {
            startActivity(view)
        } catch (e: Exception) {
            // No browser either.
        }
    }
}
