package com.bspurling.freephase.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bspurling.freephase.BuildConfig
import com.bspurling.freephase.R
import java.io.BufferedReader

class MainActivity : AppCompatActivity() {

    private companion object {
        const val EDF_URL = "https://www.edfenergy.com/tariff-information-labels/freePhase"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }

        val toolbar = Toolbar(this).apply {
            title = getString(R.string.widget_label)
            setNavigationOnClickListener { finish() }
        }
        val postcodeChip = TextView(this).apply {
            text = BuildConfig.POSTCODE
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("postcode", BuildConfig.POSTCODE))
                Toast.makeText(this@MainActivity, R.string.postcode_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val openInChrome = TextView(this).apply {
            text = getString(R.string.open_in_chrome)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EDF_URL)))
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(postcodeChip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(openInChrome, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            visibility = View.VISIBLE
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val js = assets.open("edf_autofill.js").bufferedReader().use(BufferedReader::readText)
                        .replace("__POSTCODE__", BuildConfig.POSTCODE.replace("\"", "\\\""))
                    view.evaluateJavascript(js, null)
                }
            }
            loadUrl(EDF_URL)
        }

        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
