NOTHING MANAGES THIS MODULE'S VERSIONS. There is no managed set to follow or to override, so
the number goes onto this module's own declaration, and every conflict a raise causes is
settled in this module and by this stage.

ONE ROW, AND bump_patch IS THE WHOLE MOVE. Every lombok floor in this project is on the 1.18
line, so whatever the hop the raise is a patch: give bump_patch the coordinates and the
version, and it picks the actuator for Maven or Gradle without you having to know which this
module is. Then read declared_versions. If lombok still prints low, the version was not where
the recipe looked, and the placement column says where it is instead: a property wants
org.openrewrite.maven.ChangePropertyValue, and a Gradle module that prints the same coordinate
twice, once to compile against and once as the processor, needs the second row moved as well
as the first.

KOTLIN IS NOT A PATCH MOVE, AND NOTHING HERE MIGRATES IT. The row asks for a 2.x where the
module declares a 1.x, so bump_patch refuses it and says so, and bump_line covers Spring
Boot's lines only and says that too. Neither refusal is an obstacle; both are tools declining
to write a number across a crossing they cannot carry. apply_recipe is the way through: emit
org.openrewrite.maven.UpgradeDependencyVersion and
org.openrewrite.gradle.UpgradeDependencyVersion in one recipe, naming the artifacts this
module actually declares, the library and the compiler together, on the one version. A
compiler left behind while the library moves is the fallback the row is about, and the gate
reads it as a bump that never happened.

WHERE NO RECIPE IN THE TOOL DESCRIPTION REACHES THE PLACEMENT, SAY SO AS BLOCKED, naming the
placement declared_versions printed. A recipe id that does not exist is skipped with a
warning and the run still reports success, so a guessed name turns a pin you could have
reported into one nobody knows about.

AN ARTIFACT FROM THE LONG LIST IS NOT OWED HERE. It is owed at after-pins, where the module
has already compiled and a break can be attributed to the version that caused it. Raising it
early on this lane puts a number on a module with no manager to reconcile it, before anything
has compiled that could tell you it was wrong.
