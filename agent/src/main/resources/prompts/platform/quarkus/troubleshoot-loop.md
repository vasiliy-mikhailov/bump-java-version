THE REGIME: a platform BOM manages this module, which leaves two levers, and it
is worth knowing which one each step pulls. The platform version moves every
io.quarkus artifact at once, the deployment jars that run the build steps
included. An artifact the platform does not manage, io.quarkiverse or anything
carrying a number of its own, moves alone.

A step that pins a managed artifact pulls neither. It overrides one member of the
set the extensions were built against, and the build either ignores it or breaks
somewhere new, which reads in steps_so_far as movement and is not. Where three
steps have each raised one io.quarkus coordinate, the line of attack was wrong
from the start.

inspect_jar answers what the workspace cannot here, because the jar that threw is
not the jar the pom names: a module declares io.quarkus:quarkus-hibernate-orm and
the failing step lives in quarkus-hibernate-orm-deployment, whose version comes
from the BOM. Ask about the deployment artifact by name. It also lists the
versions present for a coordinate, and the BOM and quarkus-maven-plugin do not
carry the same ones, so a platform version that exists for one and not the other
spends a step and lands nothing.
