package net.weavemc.loader.mapping

import com.grappenmaker.mappings.*
import net.weavemc.internals.GameInfo.MinecraftClient
import net.weavemc.internals.GameInfo.MinecraftVersion
import net.weavemc.loader.WeaveLoader
import net.weavemc.internals.GameInfo.gameClient
import net.weavemc.internals.GameInfo.gameVersion
import net.weavemc.internals.MappingsRetrieval
import net.weavemc.loader.util.FileManager
import net.weavemc.loader.injection.InjectionClassWriter
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile

object MappingsHandler {
    private val vanillaJar = FileManager.getVanillaMinecraftJar()

    val mergedMappings by lazy {
        MappingsRetrieval.loadMergedWeaveMappings(gameVersion.versionName, vanillaJar)
    }

    val environmentNamespace by lazy {
        when (gameClient) {
            // TODO: correct version
            MinecraftClient.LUNAR -> if (gameVersion < MinecraftVersion.V1_16_5) "mcp-named" else "mojmap-named"
            MinecraftClient.FORGE -> "mcp-srg"
            MinecraftClient.VANILLA, MinecraftClient.LABYMOD, MinecraftClient.BADLION -> "official"
        }
    }

    internal fun classLoaderBytesProvider(expectedNamespace: String): (String) -> ByteArray? {
        val names = if (expectedNamespace != "official") mergedMappings.mappings.asASMMapping(
            from = expectedNamespace,
            to = "official",
            includeMethods = false,
            includeFields = false
        ) else emptyMap()

        val mapper = SimpleRemapper(names.toList().associate { (k, v) -> v to k })
        val callback = ClasspathLoaders.fromLoader(WeaveLoader::class.java.classLoader)

        return { name -> callback(names[name] ?: name)?.remap(mapper) }
    }

    internal fun jarBytesProvider(jarsToUse: List<JarFile>, expectedNamespace: String): (String) -> ByteArray? {
        val names = if (expectedNamespace != "official") mergedMappings.mappings.asASMMapping(
            from = expectedNamespace,
            to = "official",
            includeMethods = false,
            includeFields = false
        ) else emptyMap()
        // flip the namespace to remap the vanilla class to the mod's namespace
        val mapper = SimpleRemapper(names.entries.associate { (k, v) -> v to k })

        val cache = hashMapOf<String, ByteArray?>()
        val lookup = jarsToUse.flatMap { j ->
            j.entries().asSequence().filter { it.name.endsWith(".class") }
                .map { it.name.dropLast(6) to { j.getInputStream(it).readBytes() } }
        }.toMap()

        return { name ->
            val mappedName = names[name] ?: name
            if (mappedName in lookup) cache.getOrPut(mappedName) { lookup.getValue(mappedName)().remap(mapper) }
            else null
        }
    }

    fun mapper(from: String, to: String, loader: (name: String) -> ByteArray? = classLoaderBytesProvider(from)) =
            MappingsRemapper(mergedMappings.mappings, from, to, loader = loader)

    internal val environmentRemapper = mapper("official", environmentNamespace)
    internal val environmentUnmapper = environmentRemapper.reverse()

    private val mappable by lazy {
        val id = mergedMappings.mappings.namespace("official")
        mergedMappings.mappings.classes.mapTo(hashSetOf()) { it.names[id] }
    }

    internal fun ByteArray.remap(remapper: Remapper, bypassMappableCheck: Boolean = false): ByteArray {
        val reader = ClassReader(this)
        if (reader.className !in mappable && !bypassMappableCheck) return this

        val writer = ClassWriter(reader, 0)
        reader.accept(MinecraftRemapper(writer, remapper), 0)

        return writer.toByteArray()
    }

    internal fun ClassNode.remap(remapper: Remapper, flags: Int = ClassWriter.COMPUTE_MAXS): ClassNode {
        val classWriter = InjectionClassWriter(flags)
        accept(classWriter)

        val bytes = classWriter.toByteArray()
        val remapped = bytes.remap(remapper, true)

        val classReader = ClassReader(remapped)
        val classNode = ClassNode()
        classReader.accept(classNode, 0)

        return classNode
    }

    // Remapper meant to be used with loaded Minecraft classes.
    // It will ignore MixinMerged methods (an issue with LabyMod)
    private class MinecraftRemapper(
        parent: ClassVisitor,
        remapper: Remapper
    ): ClassRemapper(Opcodes.ASM9, parent, remapper) {
        private val annotationNodes: MutableList<SimpleAnnotationNode> = mutableListOf()

        override fun createAnnotationRemapper(
            descriptor: String?,
            parent: AnnotationVisitor?
        ): AnnotationVisitor {
            val node = SimpleAnnotationNode(parent, descriptor)
            annotationNodes += node
            return node
        }

        override fun createMethodRemapper(parent: MethodVisitor): MethodVisitor {
            return MinecraftMethodRemapper(annotationNodes.map { it.descriptor ?: "" }, parent, remapper)
        }
    }
    private class MinecraftMethodRemapper(
        private val annotations: List<String>,
        private val parent: MethodVisitor,
        remapper: Remapper
    ): LambdaAwareMethodRemapper(parent, remapper) {
        override fun visitInvokeDynamicInsn(name: String, descriptor: String, handle: Handle, vararg args: Any) {
            if (annotations.contains("org/spongepowered/asm/mixin/transformer/meta/MixinMerged"))
                parent.visitInvokeDynamicInsn(name, descriptor, handle, args)
            else
                super.visitInvokeDynamicInsn(name, descriptor, handle, *args)
        }
    }

    fun remapModJar(
        mappings: Mappings,
        input: File,
        output: File,
        from: String = "official",
        to: String = environmentNamespace,
        classpath: List<File> = listOf(),
    ) {
        val jarsToUse = (classpath + input).map { JarFile(it) }

        println(environmentNamespace)

        com.grappenmaker.mappings.remapModJar(
            mappings,
            input,
            output,
            from,
            to,
            jarBytesProvider(jarsToUse, from)
        )

        jarsToUse.forEach { it.close() }
    }
}