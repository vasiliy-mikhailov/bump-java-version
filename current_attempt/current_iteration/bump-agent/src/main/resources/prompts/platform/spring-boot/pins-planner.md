THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS. Lombok, byte-buddy and its agent,
mockito-core, hamcrest, jackson and tomcat are all inside the set Boot manages, and most of the
floors above name one of them. So the plan here is usually a line about the manager rather than a
line for each artifact.

WHAT A MANAGED ROW LOOKS LIKE IN declared_versions. A dependency that states no version prints, in
the version column, "(managed by org.springframework.boot:spring-boot-starter-parent <version>)",
or "(managed by something this module does not name)" when this module's own files name nothing
that could be managing it. Neither is a version below a floor. The first names the number and where
it lives; the second means the manager is a parent pom of this repository, and that parent is its
own module a few rows up in the same report. Plan against the module that owns the number.

WHICH MOVE EXISTS DEPENDS ON HOW BOOT REACHES THIS MODULE, and the report says which of the three
it is:
- a parent row, whether this module names a Boot parent itself (41 modules here) or reaches one at
  the end of a chain of in-repo poms (185, and none of them says Boot anywhere in its own file): a
  property in this module's own properties block overrides the managed version.
- an import-scope row for spring-boot-dependencies (11 modules): a property will not override it,
  because the property is read in the imported pom's own context. The override is a
  dependencyManagement entry ahead of the import, and the report lists rows in reading order, so
  ahead of it is something you can check.
- the Boot Gradle plugin (40 files): the raise is a version arriving through the platform rather
  than a version written onto a declaration of this module's own.

THE PHASE YOU ARE IN DECIDES THE REST. The list above is the whole list. Where it carries a Boot
line, the move is Boot itself and it brings its members with it. Where it carries none, Boot is not
yours to move in this pass, and a member below its floor is met by overriding that one version.

THE PLAN THAT LOOKS RIGHT AND IS WRONG is a line reading "raise lombok to the floor in this
module". Carried out literally it writes a version onto a managed dependency, which takes that
artifact out of the set for good: the next Boot raise moves everything around it and leaves it
where it was. Name which of the three placements you mean, so nobody downstream has to guess.
