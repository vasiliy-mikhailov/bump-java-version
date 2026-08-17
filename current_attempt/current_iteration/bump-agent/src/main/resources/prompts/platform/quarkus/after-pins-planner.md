THE LIST YOU ARE HOLDING NAMES ONLY SPRING BOOT and this module declares none of
it, so NOTHING-OUTSTANDING is a true answer to that list. It is also the answer
this regime has given every time it was asked: apedano/account-service and
AlanSilvaLima/curso-rest-quarkus both closed this phase on it, both were right
about the list, and neither looked at the one number a Quarkus module has. That
number is the half worth planning.

THE LIST CANNOT NAME THE ONLY LEVER THIS REGIME HAS. The platform BOM sets every
version this module resolves, so the artifacts a scanner counts are not declared
in the build files at all and none of them can be raised on its own. Measured on
apedano/account-service before its bump: 35 CRITICAL+HIGH over 232 packages, of
which the netty family carried 23, quarkus-vertx-http, quarkus-core and
quarkus-resteasy 5 between them, and jackson 3. Not one of those artifacts
appears in its pom. What moves all of them at once is quarkus.platform.version.

THE ENABLING PHASE MOVES THAT NUMBER ONLY AS FAR AS THE HOP NEEDS. On that same
module it went 3.1.2.Final to 3.2.4.Final, which carried netty 4.1.93.Final to
4.1.94.Final and commons-io 2.11.0 to 2.13.0 without either being named
anywhere, and the run finished 35 to 35, cleared 0, introduced 0. What is left
to you is the distance from where it parked to the head of the same major and
minor line, which is a patch: it carries the fixes and changes no API.

THAT HEAD IS PER COORDINATE, NOT PER LINE, so ask inspect_jar for both rather
than assuming. It answers "no jar" for a BOM, which is a pom, and lists the
versions anyway. On the 3.2 line the mirror carries the BOM to 3.2.10.Final while
io.quarkus.platform:quarkus-maven-plugin stops at 3.2.4.Final; on the 3.24 line
the BOM has 3.24.1, 3.24.2, 3.24.4 and 3.24.5 and the plugin has 3.24.1, 3.24.2
and 3.24.5. Plan the highest version present in BOTH and write it into the plan.
apedano was already at that version and had nothing left to take, which is a
finished module; curso-rest-quarkus sat at 3.24.1 with 3.24.5 in both lists.

CROSSING A LINE IS NOT A PLAN THIS PHASE CAN CARRY. The module gate is behind you
and so are its repair turns, and the next thing to read your work is the
repository gate building and testing every module at once. Crossing the platform
from 2 to 3 renames javax through this module's own sources, fifteen files in one
repository here and sixteen in the other, and every io.quarkiverse artifact has
to cross with it. That is the enabling phase's work when a hop needs it, not
polish on a module that already compiles.

WHERE THE NUMBER LIVES DECIDES WHOSE TURN IT IS. Name the module, the property
and the version. declared_versions prints a property row and has no row for
quarkus-maven-plugin, so read the pom for the places that property is read:
three of them on the module measured here, its own declaration, the BOM import
and the plugin. A property declared in a parent this module does not own is that
parent module's turn, and this module is then genuinely NOTHING-OUTSTANDING.
