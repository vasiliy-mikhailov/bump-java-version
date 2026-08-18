NOTHING MANAGES THIS MODULE'S VERSIONS, and two edits that read as gaming elsewhere are the
correct repair here. A dependencyManagement entry, or a Gradle constraint, that fixes the
version of a transitive is this module doing the job no manager does for it. An exclusion
that drops a second copy of an artifact the module already has is the same.

What separates those from gaming is what the module loses. An exclusion that removes
something the module's own source imports has deleted functionality, and grep for the package
settles which of the two you are reading. A version pinned DOWN deserves the same look: with
no managed set to reassert a number, a lower version is a decision somebody typed, and
holding a bytecode reader (jacoco, byte-buddy, archunit) below the release that understands
the new class file is pinning a tool so it stops reading, under another name.

A dependency added to make a compile pass is not gaming by itself. The modules the JDK
removed are supplied exactly that way. Read what it replaced before calling it either thing.
