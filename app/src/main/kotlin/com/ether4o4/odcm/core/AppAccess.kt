package com.ether4o4.odcm.core

/** Installed apps and intent-based launching, separate from Android for runner tests. */
interface AppAccess {
    fun catalog(): AppCatalog
    fun launch(packageName: String): Boolean
}
