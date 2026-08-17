THE REGIME: a platform BOM manages this module's dependency versions, which is a
fact about its dependencies and not about its target. check_target reports the
same pins here as anywhere and the gate still takes the lowest.

WHERE THE TARGET SITS IN THIS SHAPE. A generated Quarkus pom declares it once, as
the maven.compiler.release property, with no source and target pair and no
toolchain beside it, so the honest plan is often one line. The exception worth
looking for is Kotlin: timefold-quickstarts carried maven.compiler.release and
the kotlin-maven-plugin jvmTarget in the same pom, and a plan that names one of
them leaves the module below the target.

WHAT IS NOT IN THIS PLAN AND STILL DECIDES WHETHER IT WORKS. The platform version
is not a target declaration, check_target will never report it, and moving it
belongs to the pin stages. It is also what the module's own build steps read the
fresh bytecode with, so a module raised past what its platform understands
compiles and then fails inside the Quarkus build rather than in javac. If the
platform looks older than the target you are planning for, name it as an
observation and keep your list to the declarations check_target reports.
