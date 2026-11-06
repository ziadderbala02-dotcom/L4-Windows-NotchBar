package com.l4.notchlive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Opens a match in the 365Scores app when it takes one of our links, else the match page in
 * the browser. No screen of its own (the notch and notifications start it, then it's gone).
 */
class MatchActivity : Activity() {
    companion object {
        const val PKG_365 = "com.scores365"

        fun intent(ctx: Context, m: Match): Intent =
            Intent(ctx, MatchActivity::class.java)
                .putStringArrayListExtra("app", ArrayList(Api.appLinks(m)))
                .putExtra("web", Api.matchUrl(m))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** The first link the 365Scores app says it can open, or null. */
        fun appLink(ctx: Context, links: List<String>): String? = links.firstOrNull { l ->
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(l)).setPackage(PKG_365)
            ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
        }

        fun installed(ctx: Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(PKG_365) != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = intent.getStringArrayListExtra("app") ?: arrayListOf()
        val web = intent.getStringExtra("web")
        val link = appLink(this, app)
        val opened = link != null && start(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PKG_365))
        if (!opened && web != null) start(Intent(Intent.ACTION_VIEW, Uri.parse(web)))
        finish()
    }

    private fun start(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
