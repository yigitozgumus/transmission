package com.trendyol.transmission.idprocessor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

private const val COMBINED_RESOLVER_PACKAGE = "com.trendyol.transmission.router"

class TransmissionIdProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TransmissionIdProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
    }
}

private class TransmissionIdProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private data class AnnotatedRoot(
        val declaration: KSClassDeclaration,
        val scope: String,
    )

    private val unscopedRoots = mutableListOf<AnnotatedRoot>()
    private val scopedRoots = mutableMapOf<String, MutableList<AnnotatedRoot>>()
    private val processedNames = mutableSetOf<String>()
    private var combinedGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation(GENERATE_ID_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val valid = symbols.filter { it.validate() }
        val invalid = symbols.filterNot { it.validate() }

        valid.forEach { root ->
            val qualifiedName = root.qualifiedName?.asString()
            if (qualifiedName !in processedNames) {
                processedNames.add(qualifiedName!!)
                generateIds(root)
            }
        }

        if (invalid.isEmpty() && processedNames.isNotEmpty() && !combinedGenerated) {
            generateCombinedResolvers()
            combinedGenerated = true
        }

        return invalid.toList()
    }

    private fun generateIds(root: KSClassDeclaration) {
        if (Modifier.SEALED !in root.modifiers || root.classKind != ClassKind.INTERFACE) {
            logger.error("@GenerateTransmissionId can only be used on sealed interfaces.", root)
            return
        }

        val scope = annotationScope(root)
        val annotated = AnnotatedRoot(root, scope)
        if (scope.isEmpty()) {
            unscopedRoots.add(annotated)
        } else {
            scopedRoots.getOrPut(scope) { mutableListOf() }.add(annotated)
        }

        val packageName = root.packageName.asString()
        val rootName = root.simpleName.asString()
        val generatedName = generatedObjectName(root) ?: "${rootName}Id"
        val subclasses = root.getSealedSubclasses().toList()

        if (subclasses.isEmpty()) {
            logger.warn("No sealed subclasses found for $rootName.", root)
        }

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, root.containingFile!!),
            packageName = packageName,
            fileName = generatedName,
        )

        file.bufferedWriter().use { writer ->
            writer.appendLine("@file:OptIn(com.trendyol.transmission.router.ExperimentalTransmissionIdGeneration::class)")
            writer.appendLine()
            writer.appendLine("package $packageName")
            writer.appendLine()
            writer.appendLine("import com.trendyol.transmission.Transmission")
            writer.appendLine("import com.trendyol.transmission.router.TransmissionId")
            writer.appendLine("import com.trendyol.transmission.router.TransmissionIdResolver")
            writer.appendLine()
            writer.appendLine("public object $generatedName : TransmissionIdResolver {")
            subclasses.forEach { subclass ->
                val propertyName = subclass.idPropertyName()
                val idValue = "${root.qualifiedName!!.asString()}.${subclass.qualifiedName!!.asString().substringAfterLast('.')}"
                writer.appendLine("    public val $propertyName: TransmissionId<${subclass.qualifiedName!!.asString()}> = TransmissionId(\"$idValue\")")
            }
            writer.appendLine()
            writer.appendLine("    override fun idOf(transmission: Transmission): TransmissionId<*>? {")
            writer.appendLine("        return when (transmission) {")
            subclasses.forEach { subclass ->
                writer.appendLine("            is ${subclass.qualifiedName!!.asString()} -> ${subclass.idPropertyName()}")
            }
            writer.appendLine("            else -> null")
            writer.appendLine("        }")
            writer.appendLine("    }")
            writer.appendLine("}")
        }
    }

    private fun generateCombinedResolvers() {
        if (unscopedRoots.isNotEmpty()) {
            generatePerScopeResolver(COMBINED_RESOLVER_PACKAGE, combinedResolverName(null), unscopedRoots)
        }
        scopedRoots.forEach { (scope, roots) ->
            generatePerScopeResolver(COMBINED_RESOLVER_PACKAGE, combinedResolverName(scope), roots)
        }
        generateAutoDiscoverExtension()
    }

    private fun generatePerScopeResolver(packageName: String, resolverName: String, roots: List<AnnotatedRoot>) {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true),
            packageName = packageName,
            fileName = resolverName,
        )
        file.bufferedWriter().use { writer ->
            writer.appendLine("@file:OptIn(com.trendyol.transmission.router.ExperimentalTransmissionIdGeneration::class)")
            writer.appendLine()
            writer.appendLine("package $packageName")
            writer.appendLine()
            writer.appendLine("import com.trendyol.transmission.Transmission")
            writer.appendLine("import com.trendyol.transmission.router.TransmissionId")
            writer.appendLine("import com.trendyol.transmission.router.TransmissionIdResolver")
            writer.appendLine()
            writer.appendLine("public object $resolverName : TransmissionIdResolver {")
            writer.appendLine("    private val resolvers: List<TransmissionIdResolver> = listOf(")
            roots.forEach { root ->
                val decl = root.declaration
                val pkg = decl.packageName.asString()
                val rootName = decl.simpleName.asString()
                val resolverObjectName = generatedObjectName(decl) ?: "${rootName}Id"
                writer.appendLine("        $pkg.$resolverObjectName,")
            }
            writer.appendLine("    )")
            writer.appendLine()
            writer.appendLine("    override fun idOf(transmission: Transmission): TransmissionId<*>? {")
            writer.appendLine("        return resolvers.firstNotNullOfOrNull { it.idOf(transmission) }")
            writer.appendLine("    }")
            writer.appendLine("}")
        }
    }

    private fun generateAutoDiscoverExtension() {
        val hasUnscoped = unscopedRoots.isNotEmpty()
        val scopes = scopedRoots.keys.sorted()
        val unsuffixedResolverName = combinedResolverName(null)
        val D = "$"

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true),
            packageName = COMBINED_RESOLVER_PACKAGE,
            fileName = "TransmissionIdAutoDiscovery",
        )
        file.bufferedWriter().use { writer ->
            writer.appendLine("@file:OptIn(com.trendyol.transmission.router.ExperimentalTransmissionIdGeneration::class)")
            writer.appendLine()
            writer.appendLine("package $COMBINED_RESOLVER_PACKAGE")
            writer.appendLine()
            writer.appendLine("import com.trendyol.transmission.router.TransmissionIdResolver")
            writer.appendLine("import com.trendyol.transmission.router.builder.TransmissionRouterBuilderScope")
            writer.appendLine()

            if (scopes.isNotEmpty()) {
                writer.appendLine("private val scopeds: Map<String, TransmissionIdResolver> = mapOf(")
                scopes.forEach { scope ->
                    writer.appendLine("    \"$scope\" to ${combinedResolverName(scope)},")
                }
                writer.appendLine(")")
                writer.appendLine()
            }

            if (hasUnscoped) {
                writer.appendLine("@kotlin.OptIn(ExperimentalTransmissionIdGeneration::class)")
                writer.appendLine("public fun TransmissionRouterBuilderScope.autoDiscoverIds(): Unit {")
                writer.appendLine("    addTransmissionIdResolver($unsuffixedResolverName)")
                writer.appendLine("}")
                writer.appendLine()
            }

            if (scopes.isNotEmpty()) {
                val availMsg = scopes.joinToString(", ") { "\"$it\"" }
                val errMsg = "Unknown scope: ${D}scope. Available: ${D}{scopeds.keys.joinToString(\", \")}"
                writer.appendLine("@kotlin.OptIn(ExperimentalTransmissionIdGeneration::class)")
                writer.appendLine("public fun TransmissionRouterBuilderScope.autoDiscoverIds(scope: String): Unit {")
                writer.appendLine("    val resolver = scopeds[scope]")
                writer.appendLine("        ?: error(\"$errMsg\")")
                writer.appendLine("    addTransmissionIdResolver(resolver)")
                writer.appendLine("}")
            }
        }
    }

    private fun combinedResolverName(scope: String?): String {
        if (scope == null || scope.isEmpty()) return "GeneratedTransmissionId"
        return scope.replaceFirstChar { it.uppercase() } + "TransmissionId"
    }

    private fun annotationScope(root: KSClassDeclaration): String {
        val annotation = root.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == GENERATE_ID_ANNOTATION
        } ?: return ""
        return annotation.arguments
            .firstOrNull { it.name?.asString() == "scope" }
            ?.value
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun generatedObjectName(root: KSClassDeclaration): String? {
        val annotation = root.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == GENERATE_ID_ANNOTATION
        } ?: return null
        return annotation.arguments
            .firstOrNull { it.name?.asString() == "name" }
            ?.value
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }

    private fun KSClassDeclaration.idPropertyName(): String {
        return qualifiedName!!.asString()
            .substringAfterLast('.')
            .replace(Regex("[^A-Za-z0-9_]"), "_")
    }

    private companion object {
        const val GENERATE_ID_ANNOTATION = "com.trendyol.transmission.router.GenerateTransmissionId"
    }
}
