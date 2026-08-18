THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and on Maven the target is one of them.
spring-boot-starter-parent drives maven-compiler-plugin from a java.version property, so what
check_target names on a Boot module is usually java.version rather than a compiler setting. That is
a property, so org.openrewrite.maven.ChangePropertyValue through apply_recipe reaches it in every
pom of the chain at once, where edit_file reaches one file per call.

RAISING THE PROPERTY IS THE WHOLE EDIT on a module that has only the property. Adding a
maven-compiler-plugin configuration beside it looks thorough and gives the module a second
declaration that outranks the first, which is the shape the next stage has to unpick.

THE BOOT PLUGIN LINE ON GRADLE IS THE MANAGER'S VERSION, NOT THE TARGET. It appears in the list of
places a version can live above, and raising it moves the whole managed set. The Boot plugin sets no
source or target compatibility of its own, so nothing in that line is what check_target is
complaining about.

BOOT ITSELF IS NOT THIS STAGE'S TO MOVE. bump_line is in your hands and Boot is the artifact it
moves best, and the Boot floors are still applied by the stage that runs after the JDK has moved,
because Boot 3 cannot resolve on a project below 17. A parent raised here is a version change made
by a stage that was asked to change targets, and it is read as one.
