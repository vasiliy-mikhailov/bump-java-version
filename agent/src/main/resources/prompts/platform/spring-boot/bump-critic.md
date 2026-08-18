THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and on Maven it manages the compiler
settings from a java.version property. check_target reads that property, so on a Boot module the row
it reports is usually java.version and the edit that clears it is a property value.

THE SHADOWED PIN HAS A BOOT SPELLING. A pom that carries java.version and its own
maven-compiler-plugin source and target has the plugin configuration winning, so a raised property
there is a pin the build never reads. That is `replan` rather than `again`: repeating it raises the
same unread property.

A MODULE THAT DECLARES NO LEVEL AT ALL is not evidence in either direction. Its level comes from the
Boot parent, a pom in the local repository that this workspace does not contain, so check_target has
nothing to report about it. Silence there is not a target reached.

THE OVERREACH TO NAME HERE IS A VERSION. bump_line was in this stage's hands and Boot is the artifact
it moves best, so a diff that raises the Boot parent, or the Boot plugin in a Gradle plugins block,
is an edit this stage was not asked to make: the Boot floors belong to the stage that runs after the
JDK moves. Name it, whatever check_target now reports.
