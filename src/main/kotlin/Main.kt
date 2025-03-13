package org.example

import sootup.core.cache.provider.LRUCacheProvider
import sootup.java.bytecode.frontend.inputlocation.DownloadJarAnalysisInputLocation
import sootup.java.core.views.JavaView


fun main() {
    val inputLocations = DownloadJarAnalysisInputLocation(
        "https://repo1.maven.org/maven2/org/apache/commons/commons-text/1.13.0/commons-text-1.13.0.jar",
        emptyList(),
        emptyList(),
    )
    val view = JavaView(listOf(inputLocations), LRUCacheProvider(1000))

    val classToCreate = view.identifierFactory
        .getClassType("org.apache.commons.text.StringSubstitutor")

    val sootClass = view.getClass(classToCreate).get()

    val constructors = sootClass.methods
        .filter { it.name == "<init>" }
        .map { it.body. }

    println(constructors.joinToString("\n"))
}