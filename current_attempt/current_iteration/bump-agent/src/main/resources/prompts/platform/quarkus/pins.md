THE REGIME: a platform BOM manages this module and one property drives it. The
move is that property rather than the artifact under it. An io.quarkus row with
no version of its own follows the platform, and writing a version onto it
overrides the set every extension in this module was built against.

THERE IS NO bump_line FOR THIS GROUP. It migrates Spring Boot lines and says so
plainly for anything else. The move here is apply_recipe with
org.openrewrite.maven.ChangePropertyValue, which this corpus has run 330 times,
and its two arguments are key and newValue. Not property and value, not
propertyKey and propertyValue: both were tried on a Quarkus pom here and both
came back rc=0 with "Recipe validation error ... key: is required", a
NullPointerException, and a working tree that had not moved.
org.openrewrite.gradle.ChangeProperty is reported as a class that cannot be
found.

THE PROPERTY FEEDS TWO ARTIFACTS THAT ARE PUBLISHED SEPARATELY, so their version
lists differ and only what is in both is safe. inspect_jar has
io.quarkus.platform:quarkus-bom running to 3.2.10.Final on the 3.2 line while
io.quarkus.platform:quarkus-maven-plugin stops at 3.2.4.Final, and
io.quarkus:quarkus-bom carrying 3.1.0 through 3.1.3.Final while
io.quarkus:quarkus-maven-plugin carries no 3.1 at all. It answers "no jar" for a
BOM, which is a pom, and lists the versions anyway. Afterwards grep the property
name: one property is read in several places and the report shows only some of
them, three on the module measured here, its own declaration, the BOM import and
the plugin version.

CROSSING FROM 2 TO 3 IS A JAKARTA MIGRATION, not a number. Both repositories here
that crossed it renamed javax to jakarta through their own sources, 15 files in
one and 16 in the other, and the recipe for that is
org.openrewrite.java.migrate.jakarta.JavaxEEApiToJakarta. An io.quarkiverse
artifact is outside the platform, keeps its own number, and has to cross with it:
pagopa-gpd-payments-pull went to 3.2.0.Final and took
io.quarkiverse.quarkus-reactive-h2-client from 0.1.1 to 0.2.2.
