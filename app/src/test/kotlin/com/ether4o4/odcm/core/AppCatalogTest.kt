package com.ether4o4.odcm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppCatalogTest {
    private fun app(pkg: String, label: String) = AppCatalog.App(pkg, label)

    @Test fun exactPackageWinsOverAnotherAppsLabel() {
        val wanted = app("com.mail.client", "Inbox")
        val catalog = AppCatalog(listOf(app("com.other", "com.mail.client"), wanted))
        assertEquals(AppCatalog.Resolution.Found(wanted), catalog.resolve(" com.mail.client "))
    }

    @Test fun exactLabelWinsOverFuzzyMatches() {
        val wanted = app("com.browser.stable", "Chrome")
        val catalog = AppCatalog(listOf(app("com.browser.beta", "Chrome Beta"), wanted))
        assertEquals(AppCatalog.Resolution.Found(wanted), catalog.resolve("chrome"))
    }

    @Test fun duplicateLabelsRequireAnExplicitChoice() {
        val apps = listOf(app("org.mail", "Mail"), app("com.mail", "Mail"))
        val result = AppCatalog(apps).resolve("mail") as AppCatalog.Resolution.Ambiguous
        assertEquals(listOf("com.mail", "org.mail"), result.candidates.map { it.packageName })
    }

    @Test fun tiedFuzzyMatchesAreDeterministicAndNeverSelectedArbitrarily() {
        val apps = listOf(app("com.chrome.dev", "Chrome Dev"), app("com.chrome.beta", "Chrome Beta"))
        val forward = AppCatalog(apps).resolve("chrome")
        val reverse = AppCatalog(apps.reversed()).resolve("chrome")
        assertTrue(forward is AppCatalog.Resolution.Ambiguous)
        assertEquals(forward, reverse)
    }

    @Test fun duplicateLauncherActivitiesDoNotCreateAmbiguity() {
        val wanted = app("com.chrome", "Chrome")
        val catalog = AppCatalog(listOf(wanted, wanted, wanted))
        assertEquals(1, catalog.apps.size)
        assertEquals(AppCatalog.Resolution.Found(wanted), catalog.resolve("Chrome"))
    }

    @Test fun duplicatePackagesChooseStableLabel() {
        val apps = listOf(app("com.chrome", "Web Browser"), app("com.chrome", "Chrome"))
        assertEquals(AppCatalog(apps).apps, AppCatalog(apps.reversed()).apps)
        assertEquals(1, AppCatalog(apps).apps.size)
    }

    @Test fun matchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val wanted = app("com.inbox", "INBOX")
            assertEquals(AppCatalog.Resolution.Found(wanted), AppCatalog(listOf(wanted)).resolve("inbox"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun blankAndUnknownNamesDoNotChooseAnApp() {
        val catalog = AppCatalog(listOf(app("com.mail", "Mail")))
        assertEquals(AppCatalog.Resolution.Missing, catalog.resolve(" "))
        assertEquals(AppCatalog.Resolution.Missing, catalog.resolve("nonexistent"))
    }

    @Test fun contextContainsOnlyRelevantUnblockedApps() {
        val catalog = AppCatalog(listOf(
            app("com.gmail", "Gmail"), app("com.chrome", "Chrome"), app("com.bank", "Bank"),
        ))
        val context = catalog.contextFor("Open Gmail, Chrome, and Bank", listOf(" COM.BANK "))
        assertTrue(context.contains("Gmail [com.gmail]"))
        assertTrue(context.contains("Chrome [com.chrome]"))
        assertFalse(context.contains("Bank"))
        assertFalse(catalog.contextFor("Open Gmail").contains("Chrome"))
    }

    @Test fun genericRequestsAndPackagePrefixesDoNotDumpTheInventory() {
        val catalog = AppCatalog(listOf(app("com.google.android.gm", "Gmail"), app("com.google.chrome", "Chrome")))
        assertEquals("", catalog.contextFor("please open an app for me"))
        assertEquals("", catalog.contextFor("google"))
        assertEquals("", catalog.contextFor("something unrelated"))
    }

    @Test fun contextMatchesPackageNamesAndUnicodeAppNames() {
        val catalog = AppCatalog(listOf(app("com.inbox", "Bandeja"), app("com.notes", "筆記")))
        assertTrue(catalog.contextFor("open com.inbox").contains("Bandeja [com.inbox]"))
        assertTrue(catalog.contextFor("open 筆記").contains("筆記 [com.notes]"))
    }

    @Test fun contextIsCappedAndExactMentionComesFirst() {
        val apps = (1..20).map { app("com.mail.$it", "Mail $it") } + app("com.mail", "Mail")
        val context = AppCatalog(apps).contextFor("Open Mail")
        val lines = context.lines().filter { it.startsWith("- ") }
        assertEquals(AppCatalog.MAX_CONTEXT_APPS, lines.size)
        assertEquals("- Mail [com.mail]", lines.first())
        assertTrue(context.length <= AppCatalog.MAX_CONTEXT_CHARS)
    }

    @Test fun contextKeepsLongLabelsOnOneBoundedLine() {
        val label = "Mail\n\u202E" + "x".repeat(2000)
        val context = AppCatalog(listOf(app("com.mail", label))).contextFor("mail")
        assertEquals(1, context.lines().count { it.startsWith("- ") })
        assertFalse(context.contains('\u202E'))
        assertTrue(context.length <= AppCatalog.MAX_CONTEXT_CHARS)
    }
}
