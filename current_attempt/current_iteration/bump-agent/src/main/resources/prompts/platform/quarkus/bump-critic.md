THE REGIME: a platform BOM manages this module's dependencies. None of that is
the target, and check_target answers the only question you are asking.

A generated Quarkus pom declares the target once, as maven.compiler.release, so
the usual answer here is one line and then clean. The second pin in the same
file, which is the mistake this stage pays most for, has a specific shape on this
regime: a Kotlin Quarkus module carries the kotlin-maven-plugin jvmTarget beside
the release property, and timefold-quickstarts needed both raised in one pom.

TWO EDITS THAT LOOK LIKE OVERREACH HERE AND ARE NOT. A moved
quarkus.platform.version is not a target pin and check_target says nothing about
it either way, but it is what this module's own build steps read the new bytecode
with, and quarkus-social reached 21 with that edit in the pom. A moved
io.quarkiverse version is the same kind of move for an extension the platform
does not manage. Renamed javax imports are the third: crossing the platform from
2 to 3 is what makes them jakarta, and both repositories here that crossed it
renamed them through fifteen source files or more.

What is worth naming: a quarkus block, or an execution or goal removed from
quarkus-maven-plugin, or an extension dropped from the dependencies. Those change
what the module builds, and a right target does not excuse them.
