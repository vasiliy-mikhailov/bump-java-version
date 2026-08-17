THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, so a version is almost never the smallest
edit here. Writing one onto a starter or another managed artifact to clear a compile takes it out of
the set that the pin stages either side of this gate are keeping consistent. This stage owns source.

JAVAX IS NOT ONE THING. The EE packages Boot 3 moved are servlet, persistence, validation,
annotation and transaction, and their jakarta equivalents are the same path with the first segment
changed. javax.sql, javax.crypto, javax.naming, javax.net and javax.security.auth are JDK packages
that did not move and never will, so rewriting one of those to jakarta looks exactly like the
correct edit and produces a package that does not exist. Before retyping an import, inspect_jar the
artifact it came from: an artifact that ships only the javax package is the abandoned-jar case this
brief describes below, where the blocking classes are supplied rather than the import retyped.

WHETHER A TYPE STILL EXISTS is a question inspect_jar answers with `type`, against the version this
project actually resolved rather than against recall. The security base class a configuration
extends and the bean-override annotation a test uses are both questions about which
spring-security-config and which spring-test the managed set handed this module, and the answer
differs by Boot line.

THIS MODULE'S OWN META-INF/spring.factories is the case the paragraph above does not cover. The same
rename applies to a spring.factories under this module's src/main/resources, and grep finds it
without opening a jar.

TEST SOURCES ARE OUT OF REACH AND THE GATE COMPILES THEM. edit_file answers REFUSED on any path
under src/test, and Boot 3 fallout lands there heavily: javax imports, a context annotation, a mock
annotation the new line deprecated. A wall in a test source is a real BLOCKED, named with the file
and the line, and worth more than an edit elsewhere that does not clear it.
