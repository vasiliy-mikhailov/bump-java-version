NOTHING MANAGES THIS MODULE'S VERSIONS, which makes two edits correct here that would be
vandalism on a managed module: a dependencyManagement entry, or a Gradle constraint, fixing
the version of something that arrives transitively, and an exclusion that stops a raised
artifact dragging an older copy of one of its own dependencies in. There is no managed set to
fight, so settling a version in this module settles it.

The smallest edit is often one of those rather than a change to source. Where two artifacts
disagree about a third, fix the third once. Where a raise pulled in a second copy of
something the module already has, exclude the copy rather than moving both callers to agree.

An exclusion that removes something the module's own code imports is a different act, and the
difference is checkable before you make it: grep the source for the package, and inspect_jar
whatever would supply it afterwards to see the type is still there.

inspect_jar also prints what a jar was compiled for, which is the question this regime raises
most: not whether a newer release exists somewhere, but whether the artifact in hand can run
on the target at all.
