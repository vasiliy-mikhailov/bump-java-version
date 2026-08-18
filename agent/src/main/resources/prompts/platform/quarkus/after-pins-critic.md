THE REGIME: the floors above name no Quarkus artifact, and a colleague who
concludes from that that none of them applies here has skipped the check. Most
of that list resolves in this module through the platform BOM: quarkus-bom
manages byte-buddy and byte-buddy-agent, mockito-core, hamcrest,
junit-platform-launcher, glassfish jaxb-runtime, the org.jacoco jars and
javax.annotation-api, at numbers that follow the platform version. At 3.16.1
that is byte-buddy 1.14.18, mockito 5.14.1 and jacoco 0.8.12; at 3.36.1 it is
byte-buddy 1.18.8, mockito 5.21.0 and jacoco 0.8.14. So a floor can be met here
with nothing written into the module, and a floor can be unmet here with nothing
in the module to blame. Either way the row that answers is
quarkus.platform.version.

WHAT MAKES `done` REAL HERE. Every floor the BOM manages is at or above its
number for the platform this module is on, or the colleague named the platform
version and the numbers it carries and said which floor is short. The property
itself sits at the highest version present in BOTH inspect_jar lists, the BOM
and quarkus-maven-plugin, on the major and minor line the module is already on,
or the colleague named the two lists and showed why nothing higher is reachable.
apedano/account-service was already at that version and had nothing to take,
which is a finished module. A colleague who never named the property or the
lists has not attempted this phase, and that is `again` with the property named,
not `done`.

THE ANSWER THAT IS GREEN IN THE REPORT AND DEAD AT THE GATE. declared_versions
shows the property raised and has no row for quarkus-maven-plugin at all where
its block carries <executions>, and a property row appears only where the tag
name ends in version, so version.io.quarkus has none. A property moved to a
number the plugin list lacks therefore reads as a clean pass and fails in
augmentation, and the module gate that would have caught it has already run.
Read the pom, count where the property is read, three places on the module
measured here, and answer `again` with both version lists named.

THE COMPILER PLUGIN FLOOR IS ON YOUR LIST AND HAS NO ROW OF ITS OWN. Its block
carries <configuration> in a generated Quarkus pom, so nothing prints for that
coordinate and the number arrives through ${compiler-plugin.version}. A
colleague reporting it satisfied should be reading that property, not the
absence of a row. It is often satisfied already, because the bump phase's own
recipes raise it: on curso-rest-quarkus it went 3.14.0 to 3.15.0 before this
phase started.

A NEW ROW WITH A NUMBER ON IT IS THE OTHER WAY TO LOOK BETTER THAN THE BUILD IS,
and the list now hands a colleague the coordinates to write. A net.bytebuddy,
org.mockito, io.netty or io.quarkus version put into a dependency or into this
module's dependencyManagement quietens a scanner and a floor check at once and
forks the set every extension here was built against; bump_patch writes exactly
that shape, with overrideManagedVersion, from a coordinate the pom never
declared. Refuse it, and say the platform property instead. An io.quarkiverse
artifact is the exception and carries its own number, so a row there is the
colleague's to set. And a platform crossed from 2 to 3 in a hardening pass
leaves this module's own sources on javax with the repository gate as the first
thing to read them, which is `replan` rather than a bad attempt at a good plan.
