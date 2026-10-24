package com.abhi.familyprotection

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun singleWildcardMatchesExactlyOnePackageSegment() {
        assertTrue(AppListPreferences.matchesPattern("com.appname.client", "com.appname.*"))
        assertFalse(AppListPreferences.matchesPattern("com.appname.client.debug", "com.appname.*"))
        assertFalse(AppListPreferences.matchesPattern("com.appname", "com.appname.*"))
    }

    @Test
    fun doubleWildcardMatchesOneOrMorePackageSegments() {
        assertTrue(AppListPreferences.matchesPattern("com.appname.client", "com.appname.**"))
        assertTrue(AppListPreferences.matchesPattern("com.appname.client.debug", "com.appname.**"))
        assertFalse(AppListPreferences.matchesPattern("com.appname", "com.appname.**"))
    }

    @Test
    fun wildcardCanMatchSegmentsOnBothSides() {
        assertTrue(AppListPreferences.matchesPattern("vendor.appname.client", "*.appname.*"))
        assertFalse(AppListPreferences.matchesPattern("vendor.region.appname.client", "*.appname.*"))
    }

    @Test
    fun packagePatternsRejectPartialWildcardsAndEmptySegments() {
        assertFalse(AppListPreferences.isValidPackagePattern("com.app*"))
        assertFalse(AppListPreferences.isValidPackagePattern("com..app"))
        assertTrue(AppListPreferences.isValidPackagePattern("com.app.**"))
    }

    @Test
    fun immediateBlockEntriesAcceptPackageWildcards() {
        assertTrue(AppListPreferences.isValidPackageName("com.example.*"))
        assertTrue(AppListPreferences.isValidPackageName("com.example.**"))
        assertFalse(AppListPreferences.isValidPackageName("com.example.app*"))
    }
}