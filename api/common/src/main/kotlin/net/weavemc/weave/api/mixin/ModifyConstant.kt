package net.weavemc.weave.api.mixin

annotation class ModifyConstant(
    val id: String = "",
    val method: String = "",
    val constant: Constant = Constant(),
)
