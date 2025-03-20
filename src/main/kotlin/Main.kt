package org.example

import sootup.core.model.SourceType
import sootup.core.types.PrimitiveType
import sootup.java.bytecode.frontend.inputlocation.ArchiveBasedAnalysisInputLocation
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.types.JavaClassType
import sootup.java.core.views.JavaView
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.jar.JarFile
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
        var minCost: Int,
        var random: Random,
    )

    val config = Config(
        numberOfTrials = 1000,
        costLimit = 1000,
        complexityLimit = 100,
        minCost = 0,
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
                if (depth > config.complexityLimit) return false
                if (getDependencies(node).isEmpty()) return false

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
            if (cost < config.minCost) continue

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

sealed interface UnderstandingDependency : Dependency<UnderstandingNode> {
    data class CallMethod(val ctor: JavaSootMethod, val params: List<UnderstandingNode.Class>) : UnderstandingDependency {
        override val dependencies: List<UnderstandingNode> = params
        override fun cost(): Int = 1
    }

    data class UseConstructor(val ctor: JavaSootMethod) : UnderstandingDependency {
        override val dependencies: List<UnderstandingNode> = listOf(UnderstandingNode.PublicConstructor(ctor))
        override fun cost(): Int = 0
    }

    data class JdkInitialization(val cls: JavaSootClass) : UnderstandingDependency {
        override val dependencies: List<UnderstandingNode> = emptyList()
        override fun cost(): Int = 2
    }

    data class Upcast(val subclass: JavaSootClass, val superclass: JavaSootClass) : UnderstandingDependency {
        override val dependencies: List<UnderstandingNode> = listOf(UnderstandingNode.Class(subclass))
        override fun cost(): Int = 0
    }
}

sealed interface UnderstandingNode {
    data class Class(val cls: JavaSootClass) : UnderstandingNode
    data class PublicConstructor(val ctor: JavaSootMethod) : UnderstandingNode
}


class DependencyMiner(val view: JavaView) {
    val subclasses: Map<String, List<JavaSootClass>>

    init {
        val subclasses = mutableMapOf<String, MutableList<JavaSootClass>>()
        for (cls in view.classes) {
            if (cls.superclass.isEmpty) continue
            val maybeSuperclass = view.getClass(cls.superclass.get())
            if (maybeSuperclass.isEmpty) continue
            val superclass = maybeSuperclass.get()
            subclasses.getOrPut(superclass.name) { mutableListOf() }.add(cls)
            for (iface in cls.interfaces) {
                val maybeIface = view.getClass(iface)
                if (maybeIface.isEmpty) continue
                val ifaceCls = maybeIface.get()
                subclasses.getOrPut(ifaceCls.name) { mutableListOf() }.add(cls)
            }
        }

        this.subclasses = subclasses
    }

    fun dependenciesOf(node: UnderstandingNode): List<UnderstandingDependency> {
        return when (node) {
            is UnderstandingNode.Class -> {
                val cls = node.cls
                if (cls.type.packageName.name.startsWith("java.")) {
                    return listOf(UnderstandingDependency.JdkInitialization(cls))
                }

                val constructors = cls.methods
                    .filter { it.isPublic && it.name == "<init>" }
                    .map { UnderstandingDependency.UseConstructor(it) }

                val upcasts = subclasses[cls.name]
                    ?.map { UnderstandingDependency.Upcast(it, cls) } ?: emptyList()

                constructors + upcasts
            }

            is UnderstandingNode.PublicConstructor -> {
                val ctor = node.ctor
                listOf(UnderstandingDependency.CallMethod(
                    ctor,
                    ctor.parameterTypes.mapNotNull {
                        when (it) {
                            is PrimitiveType -> null
                            is JavaClassType -> UnderstandingNode.Class(view.getClass(it).get())
                            // else -> throw NotImplementedError("Type $it not supported as method parameter")
                            else -> null
                        }
                    }
                ))
            }
        }
    }
}

fun downloadJar(url: String): File {
    val fileName = url.substringAfterLast("/")
    val targetDir = File("testing-jars")
    if (!targetDir.exists()) {
        targetDir.mkdirs()
    }
    val targetFile = File(targetDir, fileName)

    if (targetFile.exists()) {
        return targetFile
    }

    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .GET()
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

    if (response.statusCode() != 200) {
        throw RuntimeException("Failed to download file: HTTP error code ${response.statusCode()}")
    }

    response.body().use { inputStream ->
        targetFile.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    return targetFile
}


fun main() {
    val jar = downloadJar("https://repo1.maven.org/maven2/org/apache/commons/commons-text/1.13.0/commons-text-1.13.0.jar")
    val sources = downloadJar("https://repo1.maven.org/maven2/org/apache/commons/commons-text/1.13.0/commons-text-1.13.0-sources.jar")

    val view = JavaView(listOf(
        ArchiveBasedAnalysisInputLocation(jar.toPath(), SourceType.Library),
        DefaultRuntimeAnalysisInputLocation(),
    ))

    val classToCreate = view.identifierFactory
        .getClassType("sun.jvm.hotspot.oops.NarrowKlassField")
    val sootClass = view.getClass(classToCreate).get()

    val miner = DependencyMiner(view)

    val solver = MonteCarloDependencySolver<UnderstandingNode, UnderstandingDependency> {
        miner.dependenciesOf(it)
    }

    val result = solver.solve(UnderstandingNode.Class(sootClass))
    if (result == null) {
        println("No solution found")
    }

    println(result?.dependencyOrder?.joinToString("\n"))

    /*
    for (obj in result?.creationOrder ?: emptyList()) {
        when (obj) {
            is UnderstandingNode.Class -> {
                println(obj.cls)
                println(obj.cls.classSource.sourcePath)
                println(obj.cls.position.firstLine)
            }
            is UnderstandingNode.PublicConstructor -> {
                println(obj.ctor)
                println(view.getClass(obj.ctor.declClassType).get().classSource)
                println(obj.ctor.position)
            }
        }
    }
     */
    return

    for (cls in view.classes) {
        val result = solver.solve(UnderstandingNode.Class(cls)) ?: continue
        if (result.cost > 10) {
            println("======================================")
            println("High cost for ${cls.name}: ${result.cost}")
            println(result.dependencyOrder.joinToString("\n"))
            println("======================================")
            println("\n")
        }
    }
}