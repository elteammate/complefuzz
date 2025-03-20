package org.example

import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import sootup.core.model.SourceType
import sootup.core.types.ArrayType
import sootup.core.types.ClassType
import sootup.core.types.PrimitiveType
import sootup.core.types.Type
import sootup.java.bytecode.frontend.inputlocation.ArchiveBasedAnalysisInputLocation
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.types.JavaClassType
import sootup.java.core.views.JavaView
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.lang.model.element.Modifier
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

        var bestSolution: DependencySolver.Solution<Node, Dep>? = null

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

sealed interface SootNode {
    sealed interface SootType : SootNode {
        val type: Type
    }

    data class Primitive(override val type: PrimitiveType) : SootType
    data class Class(val cls: JavaSootClass) : SootType {
        override val type get() = cls.type
    }
    sealed interface Method : SootNode
    data class PublicConstructor(val ctor: JavaSootMethod) : Method
    data class StaticMethod(val method: JavaSootMethod) : Method
}

sealed interface SootDependency : Dependency<SootNode> {
    data class CallMethod(val method: JavaSootMethod, val params: List<SootNode.SootType>) : SootDependency {
        override val dependencies: List<SootNode> = params
        override fun cost(): Int = 1
    }

    data class UseConstructor(val ctor: JavaSootMethod) : SootDependency {
        override val dependencies: List<SootNode> = listOf(SootNode.PublicConstructor(ctor))
        override fun cost(): Int = 0
    }

    data class JdkInitialization(val cls: JavaSootClass) : SootDependency {
        override val dependencies: List<SootNode> = emptyList()
        override fun cost(): Int = 2
    }

    data class Upcast(val subclass: JavaSootClass, val superclass: JavaSootClass) : SootDependency {
        override val dependencies: List<SootNode> = listOf(SootNode.Class(subclass))
        override fun cost(): Int = 0
    }

    data class Primitive(val type: PrimitiveType) : SootDependency {
        override val dependencies: List<SootNode> = emptyList()
        override fun cost(): Int = 0
    }
}

class SootDependencyContext(val view: JavaView) {
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

    fun dependenciesOf(node: SootNode): List<SootDependency> {
        return when (node) {
            is SootNode.Class -> {
                val cls = node.cls
                if (cls.type.packageName.name.startsWith("java.")) {
                    return listOf(SootDependency.JdkInitialization(cls))
                }

                val constructors = cls.methods
                    .filter { it.isPublic && it.name == "<init>" }
                    .map { SootDependency.UseConstructor(it) }

                val upcasts = subclasses[cls.name]
                    ?.map { SootDependency.Upcast(it, cls) } ?: emptyList()

                constructors + upcasts
            }

            is SootNode.PublicConstructor -> {
                val ctor = node.ctor
                listOf(SootDependency.CallMethod(
                    ctor,
                    ctor.parameterTypes.mapNotNull {
                        when (it) {
                            is PrimitiveType -> SootNode.Primitive(it)
                            is JavaClassType -> SootNode.Class(view.getClass(it).get())
                            // else -> throw NotImplementedError("Type $it not supported as method parameter")
                            else -> null
                        }
                    }
                ))
            }

            is SootNode.StaticMethod -> {
                listOf(SootDependency.CallMethod(
                    node.method,
                    node.method.parameterTypes.mapNotNull {
                        when (it) {
                            is PrimitiveType -> SootNode.Primitive(it)
                            is JavaClassType -> SootNode.Class(view.getClass(it).get())
                            // else -> throw NotImplementedError("Type $it not supported as method parameter")
                            else -> null
                        }
                    }
                ))
            }

            is SootNode.Primitive -> {
                listOf(SootDependency.Primitive(node.type))
            }
        }
    }
}

class CodeGenerator<Node>(private val builder: MethodSpec.Builder) {
    private val names: MutableMap<Node, String> = mutableMapOf()
    private val usedNames = mutableSetOf<String>()

    fun getFreshName(hint: String = "var"): String {
        if (!usedNames.contains(hint)) return hint
        for (nonce in generateSequence(1) { it + 1 }) {
            val name = "${hint}$nonce"
            if (usedNames.contains(name)) return name
        }
        throw UnsupportedOperationException()
    }

    fun addComment(comment: String) {
        builder.addComment(comment)
    }

    fun addStatement(statement: String) {
        builder.addStatement(statement)
    }

    fun registerValue(name: String, node: Node) {
        names[node] = name
        usedNames.add(name)
    }

    fun getValue(node: Node): String? {
        return names[node]
    }
}

fun Type.getName(): String {
    return when (this) {
        is PrimitiveType -> name
        is ClassType -> fullyQualifiedName
        is ArrayType -> "${elementType.getName()}[]"
        else -> throw NotImplementedError("Cannot get name for $this")
    }
}

fun CodeGenerator<SootNode>.getAnyValue(type: Type): String? {
    return if (type is PrimitiveType) {
        when (type) {
            is PrimitiveType.CharType -> "'?'"
            is PrimitiveType.BooleanType -> "true"
            is PrimitiveType.ByteType -> "0"
            is PrimitiveType.ShortType -> "0"
            is PrimitiveType.IntType -> "0"
            is PrimitiveType.LongType -> "0"
            is PrimitiveType.FloatType -> "0f"
            is PrimitiveType.DoubleType -> "0.0"
            else -> throw NotImplementedError("Cannot get value for primitive $type")
        }
    } else if (type is ClassType) {
        if (type.fullyQualifiedName == "java.lang.String") {
            "\"string\""
        } else {
            null
        }
    } else {
        throw NotImplementedError("Cannot get value for $this")
    }
}

fun CodeGenerator<SootNode>.emit(node: SootNode, dependency: SootDependency) {
    this.addComment("$dependency")
    when (dependency) {
        is SootDependency.CallMethod -> {
            val method = dependency.method
            if (method.name == "<init>") {
                val declType = dependency.method.declClassType.getName()
                val name = getFreshName(method.declClassType.className)
                val params = dependency.params.map {
                    getValue(it) ?: getAnyValue(it.type)
                }.joinToString("\n")
                this.addStatement("$declType $name = new $declType($params)")
                this.registerValue(name, node)
            }
        }
        is SootDependency.UseConstructor -> {
            val name = getValue(dependency.dependencies[0])!!
            this.registerValue(name, node)
        }
        else -> throw NotImplementedError("$dependency")
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

    val context = SootDependencyContext(view)

    val solver = MonteCarloDependencySolver<SootNode, SootDependency> {
        context.dependenciesOf(it)
    }

    val classToCreate = view.identifierFactory
        .getClassType("org.apache.commons.text.StringSubstitutor")
    val sootClass = view.getClass(classToCreate).get()

    val result = solver.solve(SootNode.Class(sootClass))
    if (result == null) {
        println("No solution found")
    }

    println(result?.dependencyOrder?.joinToString("\n"))
    if (result == null) return

    val javaFile = JavaFile.builder("org.example",
        TypeSpec.classBuilder("Main").apply {
            addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            addMethod(MethodSpec.methodBuilder("main").apply {
                addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                returns(TypeName.VOID)
                addParameter(Array<String>::class.java, "args")

                val codeGenerator = CodeGenerator<SootNode>(this)

                for ((node, dep) in result.creationOrder.zip(result.dependencyOrder)) {
                    codeGenerator.emit(node, dep)
                }
            }.build())
        }.build()
    ).build()

    javaFile.writeTo(System.out)
}