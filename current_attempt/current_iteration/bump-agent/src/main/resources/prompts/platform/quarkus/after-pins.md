THE REGIME: the platform BOM sets every version a scanner will count here, and
none of those artifacts is written in this module's build files. A CVE in netty
or in an extension is a CVE in a member of the platform's set and it moves when
quarkus.platform.version moves. Measured on apedano/account-service: that one
property going 3.1.2.Final to 3.2.4.Final carried netty 4.1.93.Final to
4.1.94.Final, commons-io 2.11.0 to 2.13.0 and quarkus-core, quarkus-resteasy and
quarkus-vertx-http from 3.1.2.Final to 3.2.4.Final, none of them named in the pom
before or after. So "nothing is declared, so nothing can be raised" reads that
fact backwards: nothing is declared because the platform manages it.

TWO MOVES LOOK LIKE THE FIX AND FORK THE PLATFORM INSTEAD. bump_patch takes a
groupId and an artifactId and writes UpgradeDependencyVersion with
overrideManagedVersion true, so naming io.quarkus:quarkus-vertx-http or
io.netty:netty-codec-http there writes a version onto an artifact the BOM
manages. org.openrewrite.maven.AddManagedDependency into this module's own
dependencyManagement does the same thing more plainly. Either one splits the set
every extension here was built against, and both leave a report that looks
better than the build is.

THE MOVE IS THE PROPERTY, and there is no bump_line for this group: it migrates
Spring Boot lines and says so for anything else. apply_recipe with
org.openrewrite.maven.ChangePropertyValue is what moves it, and its two arguments
are key and newValue. Not property and value, not propertyKey and propertyValue:
both were tried on a Quarkus pom here, both came back rc=0 with "Recipe
validation error ... key: is required", a NullPointerException, and a working
tree that had not moved. Where the enabling phase moved this property already,
that call and its diff are in history and changed_in, two tool calls away. Where
the number is a gradle.properties entry or a literal inside enforcedPlatform
instead, pair the property recipe with
org.openrewrite.gradle.UpgradeDependencyVersion naming the platform BOM, and let
the working-tree report say which arm matched.

THE VERSION YOU LAND ON MUST EXIST FOR THE PLUGIN AS WELL AS THE BOM, and this
phase is the one that gets that wrong, because it wants the newest and the newest
is published separately for the two. inspect_jar has the plugin,
io.quarkus.platform:quarkus-maven-plugin, stopping at 3.2.4.Final on the 3.2 line
where the BOM runs to 3.2.10.Final, and the 3.24 line carrying 3.24.4 for the BOM
and not for the plugin. Stay on the major and minor the module is already on,
take the highest version present in both lists, and leave the line crossing
alone: 2 to 3 is the jakarta rename through this module's own sources, and the
module gate that would have caught it is already behind you.

Then read the pom back rather than the report. declared_versions has a row for
the property and no row for quarkus-maven-plugin, whose block carries
<executions>, so a raised property row is not evidence the plugin moved with it.
One property was read at three places on the module measured here. If
apply_recipe answers that nothing changed in the working tree, the usual reason
is that the property is declared in a parent this module does not own; say that
rather than writing a second copy of it here. Already at the head of its line for
both coordinates is a finished module and a real DONE.
