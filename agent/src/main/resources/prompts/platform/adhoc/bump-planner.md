NOTHING MANAGES THIS MODULE'S VERSIONS, its compiler settings included. Every declaration
check_target names here was written here, so there is no parent to fix once for several
children, and the shadowing mistake described above takes a different shape: both halves of
it sit in the same file.

The pair to look for is a property and a literal. check_target reports a line where it finds
a number, so a property (java.version, maven.compiler.source, maven.compiler.target,
maven.compiler.release) is reported, a plugin configuration that interpolates that property
is not, and a plugin configuration carrying its own number is reported on a line of its own.
Two reported lines in one file are two edits. On Gradle the same doubling reads as a
toolchain block beside a sourceCompatibility, or a Kotlin jvmTarget beside either.

A module check_target says nothing about is finished, and that is a stronger statement here
than it would be under a manager: nothing above this module declares a level, so javac
compiles at the toolchain's own version and the gate reads the target it asked for. Adding a
declaration to a module that never had one is work this bump does not want.
