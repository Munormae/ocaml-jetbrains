package dev.munormae.project

import org.jetbrains.jps.model.JpsDummyElement
import org.jetbrains.jps.model.JpsElementFactory
import org.jetbrains.jps.model.ex.JpsElementTypeBase
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension
import org.jetbrains.jps.model.serialization.module.JpsModuleSourceRootDummyPropertiesSerializer
import org.jetbrains.jps.model.serialization.module.JpsModuleSourceRootPropertiesSerializer

internal object OCamlSourceRootType :
    JpsElementTypeBase<JpsDummyElement>(),
    JpsModuleSourceRootType<JpsDummyElement> {
    override fun createDefaultProperties(): JpsDummyElement =
        JpsElementFactory.getInstance().createDummyElement()
}

internal object OCamlTestSourceRootType :
    JpsElementTypeBase<JpsDummyElement>(),
    JpsModuleSourceRootType<JpsDummyElement> {
    override fun createDefaultProperties(): JpsDummyElement =
        JpsElementFactory.getInstance().createDummyElement()

    override fun isForTests(): Boolean = true
}

class OCamlJpsModelSerializerExtension : JpsModelSerializerExtension() {
    override fun getModuleSourceRootPropertiesSerializers():
        List<JpsModuleSourceRootPropertiesSerializer<*>> = listOf(
            JpsModuleSourceRootDummyPropertiesSerializer(OCamlSourceRootType, OCAML_SOURCE_ROOT_TYPE_ID),
            JpsModuleSourceRootDummyPropertiesSerializer(OCamlTestSourceRootType, OCAML_TEST_ROOT_TYPE_ID),
        )
}

internal const val OCAML_SOURCE_ROOT_TYPE_ID = "ocaml-source"
internal const val OCAML_TEST_ROOT_TYPE_ID = "ocaml-test"
