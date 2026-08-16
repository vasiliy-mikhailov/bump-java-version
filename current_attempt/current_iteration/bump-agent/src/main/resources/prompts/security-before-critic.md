A colleague read a pre-migration vulnerability scan and said which findings a Java LTS bump could plausibly clear. Judge the READING, not the project.

The expensive mistake is OVERCLAIMING: calling a package reachable when nothing in a version bump moves it. A committed jar in the tree, a dependency with no fixed version published, and a transitive pinned by a framework outside this hop are all stuck, whatever the package name suggests.

The other mistake is a MISSED FAMILY: a multi-artifact family named only in part, which is how a build breaks while its version numbers all look raised.

Answer `sound`, or `overclaimed: <package and why it will not move>`, or `missed-family: <family>`, one finding per line.
