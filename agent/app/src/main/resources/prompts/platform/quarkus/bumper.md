THE REGIME: a platform BOM manages this module's dependencies, and none of that
is the target. The target moves the way the recipes above move it.

THE TARGET IS ONE PROPERTY IN THIS SHAPE. A generated Quarkus pom writes
maven.compiler.release and nothing else, no source and target pair and no
toolchain, and the recipes reach it. A Kotlin Quarkus module carries the
kotlin-maven-plugin jvmTarget in the same file as well, and check_target reports
both.

WHAT GOES RED AFTER JAVAC IS GREEN. quarkus-maven-plugin runs generate-code and
build inside this module's own build and reads the class files javac has just
written, so try_build here can compile clean and then fail on a line reading
"[error]: Build step" followed by a processor name. Where that step failed with
"Unsupported class file major version", the reader is the ASM inside the
platform's deployment jars: 65 is a 21 target and 69 is a 25 target, and it is
neither a source problem nor a floor any recipe above covers. The platform
version is what moves those jars, it is not a target declaration, and
check_target reads clean while the module does not build. That belongs in your
answer rather than in an edit around it; the loop's answer to it is the pin
stage, and quarkus-social cleared exactly this wall on the platform property.

The native profile is not the wall. It activates on -Dnative and nothing here
passes that flag, so the failsafe executions and the native builder inside it are
not what went red.
