NOTHING MANAGES THIS MODULE'S VERSIONS. Every place listed above is a place this module
writes for itself, and no parent will supply a level it leaves out, so a module that declares
none compiles at the toolchain's own version and needs nothing added.

The recipes move declarations. They do not move the plugin that reads them, and here that
plugin's version is this module's own: a compiler plugin too old for the target takes the
raised number and then fails the build on it, which check_target cannot show you because by
then the pom reads correctly. The tell is try_build saying DID NOT COMPILE with an error
naming the compiler plugin while check_target reports nothing left below the target.
org.openrewrite.maven.UpgradePluginVersion through apply_recipe is what moves it.

Dependency versions belong to the pin stages, and on a module nothing manages that boundary
matters: a version raised here to get past a compile is a conflict this module now owns,
taken on in the one stage that is judged on targets. Where the target cannot move without
one, say so in your answer rather than settling it here.
