package org.example

import sootup.core.cache.provider.LRUCacheProvider
import sootup.java.bytecode.frontend.inputlocation.DownloadJarAnalysisInputLocation
import sootup.java.core.views.JavaView


interface Dependency<Node> {
    val dependencies: List<Node>
    fun cost(): Int
}


class DependencySolver<Node, Dep: Dependency<Node>>(
    val dependencyFn: (Node) -> List<Dep>,
) {
    data class Solution<Node, Dep: Dependency<Node>>(
        val result: Node,
        val creationOrder: List<Node>,
        val cost: Int,
        val dependencyOrder: List<Dep>,
    )

    private data class Result<Node, Dep: Dependency<Node>>(
        val node: Node,
        var usedDep: Dep?,
        var cost: Int,
        var relaxed: Boolean,
    )

    private val dependencies = mutableMapOf<Node, List<Dep>>()
    private val backlinks = mutableMapOf<Node, MutableSet<Node>>()
    private val results = mutableMapOf<Node, Result<Node, Dep>>()
    private val relaxQueue = mutableListOf<Node>()

    private fun transitivelyCloseDependencies(node: Node) {
        if (this.dependencies.containsKey(node)) return

        val queue = mutableListOf(node)

        while (queue.isNotEmpty()) {
            val current = queue.removeLast()
            relaxQueue.add(current)
            val dependencies = dependencyFn(current)
            this.dependencies[current] = dependencies
            this.results[current] = Result(
                current,
                null,
                Int.MAX_VALUE,
                false,
            )
            for (dep in dependencies) {
                for (depNode in dep.dependencies) {
                    backlinks.getOrDefault(depNode, mutableSetOf()).add(current)
                    if (this.dependencies.containsKey(depNode)) continue
                    queue.add(depNode)
                }
            }
        }
    }

    private fun relaxUntilStable() {
        while (relaxQueue.isNotEmpty()) {
            val current = relaxQueue.removeLast()
            val result = results[current] ?: throw IllegalStateException("Dependency not found")
            if (result.relaxed) continue
            result.relaxed = true
            for (dep in dependencies[current]!!) {

            }
        }
    }

    fun solve(node: Node): Solution<Node, Dep> {
        transitivelyCloseDependencies(node)
        relaxUntilStable()

    }
}


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