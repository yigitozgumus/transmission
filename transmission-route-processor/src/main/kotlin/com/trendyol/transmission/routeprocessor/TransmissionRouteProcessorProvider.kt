package com.trendyol.transmission.routeprocessor

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

class TransmissionRouteProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TransmissionRouteProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
    }
}

private class TransmissionRouteProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation(GENERATE_ROUTES_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        symbols
            .filter { it.validate() }
            .forEach(::generateRoutes)

        return symbols.filterNot { it.validate() }.toList()
    }

    private fun generateRoutes(root: KSClassDeclaration) {
        if (Modifier.SEALED !in root.modifiers || root.classKind != ClassKind.INTERFACE) {
            logger.error("@GenerateTransmissionRoutes can only be used on sealed interfaces.", root)
            return
        }

        val packageName = root.packageName.asString()
        val rootName = root.simpleName.asString()
        val generatedName = routeObjectName(root) ?: "${rootName}Routes"
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
            writer.appendLine("package $packageName")
            writer.appendLine()
            writer.appendLine("import com.trendyol.transmission.Transmission")
            writer.appendLine("import com.trendyol.transmission.router.TransmissionRouteKey")
            writer.appendLine("import com.trendyol.transmission.router.TransmissionRouteResolver")
            writer.appendLine()
            writer.appendLine("public object $generatedName : TransmissionRouteResolver {")
            subclasses.forEach { subclass ->
                val propertyName = subclass.routePropertyName()
                val routeValue = "${root.qualifiedName!!.asString()}.${subclass.qualifiedName!!.asString().substringAfterLast('.')}"
                writer.appendLine("    public val $propertyName: TransmissionRouteKey = TransmissionRouteKey(\"$routeValue\")")
            }
            writer.appendLine()
            writer.appendLine("    override fun keyOf(transmission: Transmission): TransmissionRouteKey? {")
            writer.appendLine("        return when (transmission) {")
            subclasses.forEach { subclass ->
                writer.appendLine("            is ${subclass.qualifiedName!!.asString()} -> ${subclass.routePropertyName()}")
            }
            writer.appendLine("            else -> null")
            writer.appendLine("        }")
            writer.appendLine("    }")
            writer.appendLine("}")
        }
    }

    private fun routeObjectName(root: KSClassDeclaration): String? {
        val annotation = root.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == GENERATE_ROUTES_ANNOTATION
        } ?: return null
        return annotation.arguments
            .firstOrNull { it.name?.asString() == "name" }
            ?.value
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }

    private fun KSClassDeclaration.routePropertyName(): String {
        return qualifiedName!!.asString()
            .substringAfterLast('.')
            .replace(Regex("[^A-Za-z0-9_]"), "_")
    }

    private companion object {
        const val GENERATE_ROUTES_ANNOTATION = "com.trendyol.transmission.router.GenerateTransmissionRoutes"
    }
}
