package io.github.hubertrybak.codestory

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.HeavyPlatformTestCase

/** Proves custom IDE tasks execute in the pinned current Rider, not the compile SDK. */
class RiderRuntimeTest : HeavyPlatformTestCase() {
    fun `test the pinned current Rider and Java 25 are actually loaded`() {
        val expectedBuild = checkNotNull(System.getProperty("codeStory.expectedRiderBuild")) {
            "The current-runtime test task must pass the pinned Rider build."
        }
        val build = ApplicationInfo.getInstance().build
        val javaFeature = Runtime.version().feature()
        assertEquals("RD", build.productCode)
        assertEquals(expectedBuild, build.asStringWithoutProductCode())
        assertTrue("Current Rider must run on Java 25 or newer, not the Java 21 compiler toolchain", javaFeature >= 25)
        println("CODE_STORY_RUNTIME product=${build.productCode} build=${build.asStringWithoutProductCode()} java=$javaFeature vendor=${System.getProperty("java.vendor")}")
    }
}
