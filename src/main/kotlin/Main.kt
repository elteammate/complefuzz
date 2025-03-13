package org.example

import sootup.core.cache.provider.LRUCacheProvider
import sootup.java.bytecode.frontend.inputlocation.DownloadJarAnalysisInputLocation
import sootup.java.core.views.JavaView


interface Dependency<Node> {
    val dependencies: List<Node>
    fun cost(): Int
}

interface DependencySolver<Node, Dep: Dependency<Node>> {
    data class Solution<Node, Dep: Dependency<Node>>(
        val result: Node,
        val creationOrder: List<Node>,
        val cost: Int,
        val dependencyOrder: List<Dep>,
    )

    fun solve(node: Node): Solution<Node, Dep>
}

class TransitiveClosureDependencySolver<Node, Dep: Dependency<Node>>(
    val dependencyFn: (Node) -> List<Dep>,
): DependencySolver<Node, Dep> {
    private val dependencies = mutableMapOf<Node, List<Dep>>()
    private val backlinks = mutableMapOf<Node, MutableSet<Node>>()

    private fun transitivelyCloseDependencies(node: Node) {
        if (this.dependencies.containsKey(node)) return

        val queue = mutableListOf(node)

        while (queue.isNotEmpty()) {
            val current = queue.removeLast()
            val dependencies = dependencyFn(current)
            this.dependencies[current] = dependencies
            for (dep in dependencies) {
                for (depNode in dep.dependencies) {
                    backlinks.getOrDefault(depNode, mutableSetOf()).add(current)
                    if (this.dependencies.containsKey(depNode)) continue
                    queue.add(depNode)
                }
            }
        }
    }

    override fun solve(node: Node): DependencySolver.Solution<Node, Dep> {
        transitivelyCloseDependencies(node)
        throw NotImplementedError("TODO")
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

    println(constructors.joinToString("\n"))
}