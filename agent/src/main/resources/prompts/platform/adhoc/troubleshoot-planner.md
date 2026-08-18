NOTHING MANAGES THIS MODULE'S VERSIONS, which adds a family to the list above and it is the
commonest one here: version skew the pin stages have just created. One artifact moved, a
second still calls the shape the first used to have, and javac reports a symbol that does not
exist. That reads exactly like an API the JDK removed.

Where the symbol lived tells them apart. history and changed_in say which versions this run
moved; inspect_jar reads the artifact resolving now and, given a type, prints its members. A
symbol missing from a jar this run raised is skew, and the campaign is about settling a
version rather than about the JDK. A symbol missing from a java or javax package is the
removal family the list above names, and the two want different campaigns.

The shape of a skew campaign follows from the regime. Skew is settled once, at the artifact
the others disagree about, and not caller by caller: with nothing holding a version still, a
campaign that fixes each caller in turn keeps finding another caller. Say which artifact the
campaign is about, and the finish is that artifact resolving to one version.
