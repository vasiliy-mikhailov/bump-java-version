NOTHING MANAGES THIS MODULE'S VERSIONS. There is no managed set to follow or to override, so
every conflict a raise causes is settled in this module and by this stage.

PINNING WHAT THE MODULE DOES NOT DECLARE IS THE RIGHT MOVE HERE, and the wrong one on a
managed module. Where a floor artifact arrives under a carrier rather than in a build file,
org.openrewrite.maven.AddManagedDependency writes the version into dependencyManagement and
org.openrewrite.gradle.UpgradeTransitiveDependencyVersion adds the Gradle constraint. Nothing
else reaches a version this module never writes.

A SPLIT FAMILY IS THE FAILURE THIS REGIME PRODUCES. tomcat-embed-core, tomcat-embed-el and
tomcat-embed-websocket share a version by contract; byte-buddy moves with byte-buddy-agent,
archunit with archunit-junit5, jaxb-api with jaxb-runtime. Name every member the module
declares in the same recipe, then read them adjacent in declared_versions. Core raised with
el left behind is worse than not having run at all, because it compiles.

THE HIGHEST NUMBER CARRYING THE RIGHT ARTIFACT NAME IS NOT THE FLOOR. tomcat-embed-core sits
on two lines in this corpus and the 10.1 one is where the servlet API is jakarta, so crossing
to it renames imports in this project's own source, which is not something a pin does. Take
the row for the line this module is already on. bump_patch refuses the crossing for that
reason, and bump_line knows only Spring Boot's lines, so a minor crossed on anything else is
a migration with no recipe behind it.

WHICH DECLARATION WINS AFTERWARDS DEPENDS ON THE BUILD SYSTEM. Maven takes the nearest, so a
version this module writes itself beats the same artifact arriving under a carrier: raising
mockito-core does not lift a byte-buddy declared here directly. Gradle takes the highest in
the graph. build_system says which of the two this module is, and declared_versions shows
whether the direct declaration you would be competing with exists.
