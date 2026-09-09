package dev.munormae.icons

import com.intellij.ui.IconManager

object OCamlIcons {
    @JvmField
    val File = IconManager.getInstance().getIcon("/icons/ocaml.svg", javaClass.classLoader)

    @JvmField
    val Dune = IconManager.getInstance().getIcon("/icons/dune.svg", javaClass.classLoader)

    @JvmField
    val Opam = IconManager.getInstance().getIcon("/icons/opam.svg", javaClass.classLoader)
}
