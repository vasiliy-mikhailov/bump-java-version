THE REGIME: a platform BOM manages this module, so most rows in
declared_versions are not this module's to set, and a version written onto one of
them is a fault rather than a fix. An io.quarkus row with no version of its own
is satisfied by whatever the platform says. The row to read is the platform's.

WHAT THE REPORT CANNOT SHOW YOU. quarkus-maven-plugin has no row at all: its
block carries <executions>, and rows are cut from blocks whose children are flat
tags. A property row appears only where the tag name ends in version, so
quarkus.platform.version has one and version.io.quarkus has none. A report
showing the property raised is therefore not evidence the plugin moved with it.
Read the pom, or grep the property name and count where it is read: three places
on the module measured here, its own declaration, the BOM import and the plugin.

A NUMBER THAT EXISTS FOR THE BOM NEED NOT EXIST FOR THE PLUGIN. inspect_jar lists
what each coordinate has, and io.quarkus.platform:quarkus-bom runs to
3.2.10.Final on the 3.2 line while io.quarkus.platform:quarkus-maven-plugin stops
at 3.2.4.Final. A colleague who moved the property to the first of those has left
a build that cannot resolve its own plugin, and that is `again` with both lists
named rather than `done`.

The rows that do carry a number are the ones to hold a colleague to. An
io.quarkiverse extension is outside the platform and moves only by hand, and one
left on its javax release while the platform crossed to Quarkus 3 takes the whole
build down in augmentation rather than at any pin you can see here.
