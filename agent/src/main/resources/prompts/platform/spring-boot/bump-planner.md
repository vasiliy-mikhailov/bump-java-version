THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and on Maven it manages the compiler
settings with them. spring-boot-starter-parent drives maven-compiler-plugin from a java.version
property, so the declaration check_target names on a Boot module is usually that property rather
than maven.compiler.source or a plugin configuration. check_target reads java.version as a target
declaration, so it arrives in the list with its file and line like any other.

A SECOND PIN IN THE SAME POM HAS A BOOT SPELLING. A pom carrying java.version and its own
maven-compiler-plugin source and target has the plugin configuration winning and the property
looking raised. Those are genuinely separate declarations, and which one the build reads is the part
worth saying.

A BOOT MODULE THAT DECLARES NO LEVEL AT ALL is not silent because it is finished. Its level comes
from the Boot parent, a pom in the local repository rather than a file in this workspace, so
check_target has nothing to report about it. Declaring java.version in the outermost pom of this
repository's own chain makes the level both true and visible, and every child that inherits it moves
with the one edit.

ON GRADLE, BOOT ADDS NOTHING TO LOOK FOR. The Boot plugin sets no source or target compatibility of
its own, so the target lives wherever any Gradle project keeps it and there is no Boot knob to hunt
for.

THE MOVE THAT BELONGS TO A LATER STAGE IS BOOT ITSELF. Boot 3 needs Java 17, which is why the Boot
floors are applied after the JDK has moved and not here. A plan that raises the parent on the way to
the target is planning another stage's work in one whose critic reads any non-target edit as
overreach.
