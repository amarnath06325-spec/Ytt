package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserRobolectricTest {

    @Test
    fun `read string resources including incognito`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        val incognitoTitle = context.getString(R.string.incognito_title)
        val newIncognitoTab = context.getString(R.string.new_incognito_tab)
        val exitIncognito = context.getString(R.string.exit_incognito)

        assertEquals("Anup Web", appName)
        assertEquals("You’ve gone Incognito", incognitoTitle)
        assertEquals("New Incognito tab", newIncognitoTab)
        assertEquals("Exit Incognito mode", exitIncognito)
    }

    @Test
    fun `build search url generates correct urls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val googleUrl = BrowserPreferences.buildSearchUrl(context, "kotlin android")
        assertEquals("https://www.google.com/search?q=kotlin+android", googleUrl)

        val directUrl = BrowserPreferences.buildSearchUrl(context, "https://github.com")
        assertEquals("https://github.com", directUrl)

        val domainUrl = BrowserPreferences.buildSearchUrl(context, "wikipedia.org")
        assertEquals("https://wikipedia.org", domainUrl)
    }
}
