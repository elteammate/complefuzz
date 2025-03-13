package org.example

import sootup.core.cache.provider.LRUCacheProvider
import sootup.java.bytecode.frontend.inputlocation.DownloadJarAnalysisInputLocation
import sootup.java.core.views.JavaView
import kotlin.random.Random


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

    fun solve(node: Node): Solution<Node, Dep>?
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

class MonteCarloDependencySolver<Node, Dep: Dependency<Node>>(
    val dependencyFn: (Node) -> List<Dep>,
): DependencySolver<Node, Dep> {
    data class Config(
        var numberOfTrials: Int,
        var costLimit: Int,
        var complexityLimit: Int,
        var random: Random,
    )

    val config = Config(
        numberOfTrials = 1000,
        costLimit = 1000,
        complexityLimit = 100,
        random = Random.Default,
    )

    private val memoizedDependencies = mutableMapOf<Node, List<Dep>>()

    private fun getDependencies(node: Node): List<Dep> {
        return memoizedDependencies.getOrPut(node) {
            dependencyFn(node)
        }
    }

    override fun solve(node: Node): DependencySolver.Solution<Node, Dep>? {
        val rng = config.random

        var bestSolution: DependencySolver.Solution<Node, Dep>? = null;

        for (trial in 0 until config.numberOfTrials) {
            val creationOrder = mutableListOf<Node>()
            val dependencyOrder = mutableListOf<Dep>()
            var cost = 0
            val created = mutableSetOf<Node>()

            fun recurse(node: Node, depth: Int): Boolean {
                if (created.contains(node)) return true
                val dep = getDependencies(node).random(rng)
                cost += dep.cost()
                if (cost > config.costLimit) return false

                for (depNode in dep.dependencies) {
                    val success = recurse(depNode, depth + 1)
                    if (!success) return false
                }

                created.add(node)
                creationOrder.add(node)
                dependencyOrder.add(dep)
                return true
            }

            val success = recurse(node, 0)
            if (!success) continue

            if (bestSolution == null || cost < bestSolution.cost) {
                bestSolution = DependencySolver.Solution(
                    result = node,
                    creationOrder = creationOrder,
                    cost = cost,
                    dependencyOrder = dependencyOrder,
                )
            }
        }

        return bestSolution
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