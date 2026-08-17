NOTHING MANAGES THIS MODULE'S VERSIONS, so the number goes onto this module's own declaration and
that is the finished shape of the fix, not an override to apologise for. What the managed lane has
to avoid is the thing this lane is for.

THE LIST NAMES THE HEAD OF A SET WHERE THE SET IS WHAT HAS TO MOVE. On a managed module one raise
carries the rest, measured: the Boot recipe took a project's Tomcat from 10.1.43 to 10.1.55 without
being asked. Nothing carries anything here, so bringing the other members to the same version is
part of the pin you were given rather than a second pin, while an artifact outside the sets these
rows belong to is still not yours. tomcat-embed-core, tomcat-embed-el and tomcat-embed-websocket
share a version by contract though only core is a row; byte-buddy moves with byte-buddy-agent,
archunit with archunit-junit5, jaxb-api with jaxb-runtime; every org.springframework.boot artifact
this module declares shares one number. Move the members in one pass, then read them adjacent in
declared_versions.

A SPLIT SET IS THE FAILURE THIS REGIME PRODUCES AT THIS STAGE, AND IT PRODUCES IT GREEN. The module
compiled at the module gate before this stage began and nothing compiles it again before the
repository gate, this phase holds no build of its own, and the tests that gate runs are whatever the
project happens to have. Core on 10.1.55 with el left on 9.0 compiles, passes, and ships a jar that
dies the first time the two meet. So a set you cannot move whole is BLOCKED, named member by member,
and never a half move left in the tree.

USE THE INSTRUMENT THE ROW NAMES. For a Spring line the row rules out a typed number: bump_line
takes the group org.springframework.boot with any artifact this module declares and the line to land
on, and derives the recipe itself, which resolves the head of that line. A number you type stops
where you typed it, and the patch releases are where the CVE fixes are. Then read every
org.springframework.boot row: a member the recipe did not reach still carries its old version, and
bump_patch onto the number the others landed on is what closes it.

THE HIGHEST NUMBER CARRYING THE RIGHT ARTIFACT NAME IS NOT THE FLOOR. tomcat-embed-core sits on two
lines in this corpus, and the 10.1 one is where the servlet API is jakarta, so crossing to it
renames imports in this project's own source, which is not something a pin does. The row for this
lane is the 9.0 one, given here because Spring is absent and no framework is bringing a newer
container of its own. Take the row for the line this module is already on. bump_patch refuses the
crossing for that reason, and bump_line knows only Spring Boot's lines, so a minor crossed on
anything else is a migration with no recipe behind it. Where a raise does cross a line that other
declarations here compile against, inspect_jar answers what the build files cannot: whether the
artifact at that version is compiled against javax or jakarta, and, with `type`, whether the type
and the members those other declarations call are still there.

WHICH DECLARATION WINS AFTERWARDS DEPENDS ON THE BUILD SYSTEM, and it decides whether a raise you
made reaches anything. Maven takes the nearest, so a version this module writes itself beats the
same artifact arriving under a carrier: raising mockito-core does not lift a byte-buddy declared
here directly. Gradle takes the highest in the graph. build_system says which of the two this module
is, and declared_versions shows whether the direct declaration you would be competing with exists.
