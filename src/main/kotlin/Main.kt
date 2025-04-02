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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import javax.lang.model.element.Modifier
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random


/**
 * Describes a dependency between nodes in a graph.
 *
 * To create a node, at least one dependency must be satisfied.
 * Dependency is satisfied if all its requirements are satisfied.
 */
interface Dependency<Node> {
    val of: Node
    val requirements: List<Node>
    fun cost(): Int
}

/**
 * Generic way of solving dependencies.
 *
 * The problem we are trying to solve essentially reduces to the following:
 * we are given a graph, and each node has a set of dependencies.
 * We need to find a way to create a given node. To create a node, we need to satisfy
 * at least one of its dependencies. To satisfy a dependency,
 * we need to create all its requirements. Optionally,
 * we assign a cost to each dependency, and we want to minimize the total cost.
 *
 * This class is generic and can be used to solve a wide range of problems,
 * even though we are only interested in one:
 * generating Java code for a creation of a given class.
 * To achieve this, we need to know what constructor to call.
 * That constructor may have parameters, which may be other classes.
 * These classes may have their own constructors, and so on.
 *
 * The solution to this problem is a sequence of dependencies that need to be satisfied
 * in order to create the desired node. The sequence is ordered in such a way that
 * each dependency is satisfied after all its requirements are satisfied.
 *
 * Solutions are not guaranteed to be optimal. Moreover, the problem is NP-hard,
 * so finding the optimal solution is computationally expensive. The only guarantee
 * is that the solution is valid and satisfies all the requirements. Solutions may
 * contain duplicate nodes. Algorithm may be incomplete and may not find a solution
 * even if one exists.
 *
 * @param Node type of nodes in the graph
 * @param Dep type of dependencies in the graph
 * @see Dependency
 */
interface DependencySolver<Node, Dep: Dependency<Node>> {
    /**
     * Solution to the dependency problem.
     * @see DependencySolver
     */
    data class Solution<Node, Dep: Dependency<Node>>(
        val result: Node,
        val creationOrder: List<Node>,
        val cost: Int,
        val dependencyOrder: List<Dep>,
    )

    /**
     * Solves the dependency problem for a given node.
     *
     * @return solution to the problem, or null if no solution was found
     */
    fun solve(node: Node): Solution<Node, Dep>?
}


/**
 * A Monte Carlo solver for dependency problems.
 *
 * This solver is not guaranteed to find the optimal solution, but
 * it has reasonable performance for small to medium-sized problems.
 *
 * Algorithm works as follows:
 * It runs a fixed number of trials. In each trial, it tries to create a given node
 * by satisfying a [Dependency] chosen at random. It then recursively creates all
 * requirements of that dependency. If the cost of the solution exceeds a given limit,
 * or if the depth of the search exceeds a given limit, the trial
 * is discarded.
 *
 * @param dependencyFn for a given node, returns a list of dependencies
 * @param config configuration for the solver
 * @see DependencySolver
 */
class MonteCarloDependencySolver<Node, Dep: Dependency<Node>>(
    var config: Config = Config.DEFAULT,
    val dependencyFn: (Node) -> List<Dep>,
): DependencySolver<Node, Dep> {
    /**
     * Configuration for the solver.
     * @param numberOfTrials total number of trials to run
     * @param costLimit restart if the cost exceeds this limit
     * @param depthLimit restart if the search depth exceeds this limit
     * @param minCost discard solutions with cost less than this
     * @param random random number generator
     */
    data class Config(
        var numberOfTrials: Int,
        var costLimit: Int,
        var depthLimit: Int,
        var minCost: Int,
        var random: Random,
    ) {
        companion object {
            val DEFAULT = Config(
                numberOfTrials = 1000,
                costLimit = 1000,
                depthLimit = 100,
                minCost = 0,
                random = Random.Default,
            )
        }
    }

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
                if (depth > config.depthLimit) return false
                if (getDependencies(node).isEmpty()) return false

                val dep = getDependencies(node).random(rng)
                cost += dep.cost()
                if (cost > config.costLimit) return false

                for (depNode in dep.requirements) {
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


/**
 * This represents a node in the graph of dependencies.
 *
 * We represent a codebase using nodes describing different constructs in the codebase,
 * such as classes, methods, etc. They are essentially wrappers around Soot types.
 */
sealed interface SootNode {
    sealed interface SootType : SootNode {
        val type: Type
    }

    data class Primitive(override val type: PrimitiveType) : SootType

    data class Class(val cls: JavaSootClass) : SootType {
        override val type get() = cls.type
    }

    sealed interface Method : SootNode {
        val method: JavaSootMethod
    }

    data class ConstructorCall(val ctor: JavaSootMethod) : Method {
        override val method get() = ctor
    }

    data class StaticMethodCall(override val method: JavaSootMethod) : Method

    data class MethodCall(override val method: JavaSootMethod) : Method

    data class Array(val elementType: Type, val dimension: Int) : SootType {
        override val type get() = ArrayType(elementType, dimension)
    }
}

/**
 * This represents a dependency between nodes in the graph.
 */
sealed interface SootDependency : Dependency<SootNode> {
    /**
     * A dependency that requires calling a method.
     * It's not really necessary to have to "create" both "method" and class
     * to simply create a class, but it's a pretty transparent way to represent
     * the dependency.
     */
    data class CallMethod(
        override val of: SootNode.Method,
        val receiver: SootNode.SootType?,
        val params: List<SootNode.SootType>
    ) : SootDependency {
        val method get() = of.method
        override val requirements: List<SootNode> = run {
            if (receiver != null) {
                listOf(receiver) + params
            } else {
                params
            }
        }
        override fun cost(): Int = 1
    }

    /**
     * Represents that to create a class, we need to call its constructor.
     */
    data class UseMethod(
        override val of: SootNode.Class,
        val method: SootNode.Method
    ) : SootDependency {
        init { assert(method.method.declClassType == of.type) }
        override val requirements: List<SootNode> = listOf(method)
        override fun cost(): Int = 0
    }

    /**
     * Class that is likely to be simple to initialize, as it's part of the JDK.
     */
    data class JdkInitialization(override val of: SootNode.Class) : SootDependency {
        override val requirements: List<SootNode> = emptyList()
        override fun cost(): Int = 2
    }

    /**
     * Represents a class for which a subclass can be created.
     */
    data class Upcast(val superclass: SootNode.Class, val subclass: SootNode.Class) : SootDependency {
        override val of: SootNode.Class = superclass
        override val requirements: List<SootNode> = listOf(subclass)
        override fun cost(): Int = 0
    }

    /**
     * Represents a primitive type.
     */
    data class Primitive(override val of: SootNode.Primitive) : SootDependency {
        override val requirements: List<SootNode> = emptyList()
        override fun cost(): Int = 0
    }

    data class EmptyArray(override val of: SootNode.Array) : SootDependency {
        override val requirements: List<SootNode> = emptyList()
        override fun cost(): Int = 3
    }
}

/**
 * Context for Soot-based dependency solver. It wraps Soot view and provides
 * additional functionality to extract dependencies.
 *
 * At the moment, it only reconstructs the inheritance hierarchy.
 */
class SootDependencyContext(private val view: JavaView) {
    private val subclasses: Map<String, List<JavaSootClass>>
    private val methodByReturnType: Map<JavaSootClass, List<JavaSootMethod>>

    init {
        val subclasses = mutableMapOf<String, MutableList<JavaSootClass>>()
        val methodByReturnType = mutableMapOf<JavaSootClass, MutableList<JavaSootMethod>>()
        for (cls in view.classes) {
            if (!cls.isPublic) continue
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

            for (method in cls.methods) {
                if (!method.isPublic) continue
                if (method.name == "<init>" || method.name == "<clinit>") continue
                if (method.returnType !is JavaClassType) continue
                val returnType = view.getClass(method.returnType as JavaClassType).getOrNull() ?: continue

                methodByReturnType.getOrPut(returnType) { mutableListOf() }.add(method)
            }
        }

        this.subclasses = subclasses
        this.methodByReturnType = methodByReturnType
    }

    private fun getParameters(method: JavaSootMethod): List<SootNode.SootType> {
        return method.parameterTypes.mapNotNull {
            when (it) {
                is PrimitiveType -> SootNode.Primitive(it)
                is JavaClassType -> SootNode.Class(view.getClass(it).get())
                is ArrayType -> SootNode.Array(it.elementType, it.dimension)
                else -> throw IllegalArgumentException("Cannot get parameters for $it")
            }
        }
    }

    /**
     * Extracts dependencies for a given node.
     */
    fun dependenciesOf(node: SootNode): List<SootDependency> {
        return when (node) {
            is SootNode.Class -> {
                val cls = node.cls
                if (cls.type.packageName.name.startsWith("java.")) {
                    return listOf(SootDependency.JdkInitialization(node))
                }

                val constructors = cls.methods
                    .filter { it.isPublic && it.name == "<init>" }
                    .map { SootDependency.UseMethod(node, SootNode.ConstructorCall(it)) }

                val upcasts = subclasses[cls.name]
                    ?.map { SootDependency.Upcast(node, SootNode.Class(cls)) } ?: emptyList()

                val methods = methodByReturnType[cls]
                    ?.map { SootDependency.UseMethod(node, SootNode.MethodCall(it)) } ?: emptyList()

                constructors + upcasts + methods
            }

            is SootNode.ConstructorCall -> {
                val ctor = node.ctor
                listOf(SootDependency.CallMethod(node, null, getParameters(ctor)))
            }

            is SootNode.StaticMethodCall -> {
                listOf(SootDependency.CallMethod(node, null, getParameters(node.method)))
            }

            is SootNode.MethodCall -> {
                val receiver = SootNode.Class(view.getClass(node.method.declClassType).getOrNull() ?: return emptyList())
                listOf(SootDependency.CallMethod(
                    node,
                    receiver,
                    getParameters(node.method)
                ))
            }

            is SootNode.Primitive -> {
                listOf(SootDependency.Primitive(node))
            }

            is SootNode.Array -> {
                listOf(SootDependency.EmptyArray(node))
            }
        }
    }
}

/**
 * Wrapper around JavaPoet's Builder.
 *
 * Provides a few useful utilities for generating Java code.
 */
class CodeGenerator<Node>(private val builder: MethodSpec.Builder) {
    private val names: MutableMap<Node, String> = mutableMapOf()
    private val usedNames = mutableSetOf<String>()

    /**
     * Generates a fresh variable name, guaranteed to be unique.
     */
    fun getFreshName(hint: String = ""): String {
        val hint = hint.replace('$', '_') + "_var"
        if (!usedNames.contains(hint)) return hint
        for (nonce in generateSequence(1) { it + 1 }) {
            val name = "${hint}$nonce"
            if (!usedNames.contains(name)) return name
        }
        throw UnsupportedOperationException()
    }

    /**
     * Adds a comment to the generated code.
     */
    fun addComment(comment: String) {
        builder.addComment(comment.replace('$', '.'))
    }

    /**
     * Adds a statement to the generated code.
     */
    fun addStatement(statement: String) {
        builder.addStatement(statement.replace('$', '.'))
    }

    /**
     * Registers a value with a given name. After that,
     * the value can be queried by the name using [getValue].
     *
     * Some values are easier to create in-place. See [getAnyValue].
     */
    fun registerValue(name: String, node: Node) {
        names[node] = name
        usedNames.add(name)
    }

    /**
     * Gets the name of a value registered with [registerValue].
     */
    fun getValue(node: Node): String? {
        return names[node]
    }
}

/**
 * Returns java name for a given type.
 */
fun Type.getName(): String {
    return when (this) {
        is PrimitiveType -> name
        is ClassType -> fullyQualifiedName.replace('$', '.')
        is ArrayType -> "${elementType.getName()}${"[]".repeat(dimension)}"
        else -> throw NotImplementedError("Cannot get name for $this")
    }
}

/**
 * Returns a string representation of some value of given type,
 * or null, if such value cannot be created easily.
 *
 * For example, it's easy to create a string value, we can
 * always return "string".
 */
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

/**
 * Generate code for a given dependency in the context of a given code generator.
 */
fun CodeGenerator<SootNode>.emit(dependency: SootDependency) {
    this.addComment("$dependency")
    val node = dependency.of
    when (dependency) {
        is SootDependency.CallMethod -> {
            val method = dependency.method
            when (dependency.of) {
                is SootNode.ConstructorCall -> {
                    val declType = method.declClassType
                    val name = getFreshName(declType.getName().substringAfterLast("."))
                    val params = dependency.params.map {
                        getValue(it) ?: getAnyValue(it.type)
                    }.joinToString("\n")
                    this.addStatement("${declType.getName()} $name = new $declType($params)")
                    this.registerValue(name, node)
                }

                is SootNode.StaticMethodCall -> {
                    val declType = method.returnType
                    val name = getFreshName(declType.getName().substringAfterLast("."))
                    val params = dependency.params.map {
                        getValue(it) ?: getAnyValue(it.type)
                    }.joinToString("\n")
                    this.addStatement("${declType.getName()} $name = $declType.${method.name}($params)")
                    this.registerValue(name, node)
                }

                is SootNode.MethodCall -> {
                    val receiver = dependency.receiver
                    val receiverName = getValue(receiver!!)!!
                    val declType = method.returnType
                    val name = getFreshName(declType.getName().substringAfterLast("."))
                    val params = dependency.params.map {
                        getValue(it) ?: getAnyValue(it.type)
                    }.joinToString("\n")
                    this.addStatement("${declType.getName()} $name = $receiverName.${method.name}($params)")
                    this.registerValue(name, node)
                }
            }
        }
        is SootDependency.UseMethod -> {
            val name = getValue(dependency.requirements[0])!!
            this.registerValue(name, node)
        }
        is SootDependency.JdkInitialization -> {
            val declType = dependency.of.cls.type
            val name = getFreshName(declType.getName().substringAfterLast("."))
            this.addStatement("${declType.getName()} $name = new $declType()")
            this.registerValue(name, node)
        }

        is SootDependency.Upcast -> {
            val subclass = getValue(dependency.subclass)!!
            val superclass = dependency.superclass.cls.type.getName()
            val name = getFreshName(superclass.substringAfterLast("."))
            this.addStatement("$superclass $name = ($superclass) $subclass")
            this.registerValue(name, node)
        }

        is SootDependency.Primitive -> {
            val name = getFreshName(dependency.of.type.name)
            this.addStatement("${dependency.of.type.name} $name = ${getAnyValue(dependency.of.type)}")
            this.registerValue(name, node)
        }

        is SootDependency.EmptyArray -> {
            val elementTypeName = dependency.of.elementType.getName()
            val name = getFreshName(elementTypeName.substringAfterLast("."))
            val dimensionSuffix = "[]".repeat(dependency.of.dimension)
            this.addStatement("$elementTypeName$dimensionSuffix $name = new $elementTypeName[0]")
            this.registerValue(name, node)
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

fun compileJavaCode(javaCode: String, jars: List<File>): Boolean {
    val tempDir = Files.createTempDirectory("Sandbox").toFile()
    val tempFile = File(tempDir, "Main.java")
    tempFile.writeText(javaCode)

    try {
        val classpath = if (jars.isNotEmpty()) jars.joinToString(File.pathSeparator) else ""

        val processBuilder = ProcessBuilder().apply {
            command(
                "javac",
                "-cp", classpath,
                tempFile.absolutePath
            )
            redirectErrorStream(true)
        }

        val process = processBuilder.start()
        val exitCode = process.waitFor()

        val output = process.inputStream.readAllBytes().decodeToString()
        println(output)

        if (output.isEmpty()) {
            println("============ Successfully generated example ============")
            println(javaCode)
            println("========================================================")
        }

        return exitCode == 0
    } catch (e: IOException) {
        e.printStackTrace()
        return false
    } finally {
        tempFile.delete()
        tempDir.delete()
    }
}

fun main() {
    val jarUrls = listOf(
        "https://repo1.maven.org/maven2/org/apache/commons/commons-text/1.13.0/commons-text-1.13.0.jar",
    )

    val jars = jarUrls.map { downloadJar(it) }

    val view = JavaView(
        jars.map {
            ArchiveBasedAnalysisInputLocation(it.toPath(), SourceType.Library)
        } + listOf(
            DefaultRuntimeAnalysisInputLocation(),
        )
    )

    val context = SootDependencyContext(view)

    val solver = MonteCarloDependencySolver<SootNode, SootDependency> {
        context.dependenciesOf(it)
    }

    for (cls in view.classes) {
        val result = solver.solve(SootNode.Class(cls))
        if (result == null) {
            println("No solution found for class ${cls.name}")
            continue
        }

        val javaFile = JavaFile.builder("org.example",
            TypeSpec.classBuilder("Main").apply {
                addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                addMethod(MethodSpec.methodBuilder("main").apply {
                    addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    returns(TypeName.VOID)
                    addParameter(Array<String>::class.java, "args")

                    val codeGenerator = CodeGenerator<SootNode>(this)

                    for (dep in result.dependencyOrder) {
                        codeGenerator.emit(dep)
                    }
                }.build())
            }.build()
        ).build()

        val code = javaFile.toString()
        assert(compileJavaCode(code, jars))
    }
}
