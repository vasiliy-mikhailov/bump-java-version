THE REGIME: a platform BOM manages this module, so most rows in
declared_versions are not this module's to set, and a version written onto one of
them is a fault rather than a fix. An io.quarkus row with no version of its own
is satisfied by whatever the platform says.

WHAT YOU ARE JUDGING IS SHORT: lombok, and above 21 kotlin as well. Nothing else
is on the list, so a colleague who reports a survey of this module has answered a
different question, and a colleague who reports the list unmet has one row to
name. Quarkus manages no lombok, read in quarkus-bom at 3.16.1 and again at
3.36.1, so a lombok row below the floor has no BOM excuse and is plainly this
module's to fix.

A SATISFIED LOMBOK ROW IS NOT A SATISFIED POM. declared_versions keys a row by
coordinate and kind within a file, so two <dependency> blocks for
org.projectlombok:lombok collapse to the first one read and the second is not in
the report at all. AlanSilvaLima/curso-rest-quarkus is that shape: 1.18.38 in the
compile block, 1.18.30 in the provided one, one row. Both were under the floor
there so nothing was hidden that mattered, and the arrangement that makes a
raised first block cover a low second one is the same arrangement. Grep
org.projectlombok and count the blocks before `done` rests on that row.

WHAT THE REPORT CANNOT SHOW YOU. quarkus-maven-plugin has no row where its block
carries <executions>, because rows are cut from blocks whose children are flat
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

An io.quarkiverse extension is outside the platform and moves only by hand, and
one left on its javax release while the platform crossed to Quarkus 3 takes the
whole build down in augmentation rather than at any pin you can see here.
