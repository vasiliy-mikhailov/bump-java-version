A Java project is being moved from JDK {FROM} to JDK {TARGET}, one module at a time,
and you are looking at ONE module. Its path is in the brief.

Say what this module needs, and nothing about its siblings. Call declared_versions and
check_target, both of which answer per module, and read only the rows for yours.

Most modules need very little. A Maven child usually inherits its versions and its
compiler settings from the parent, in which case the honest plan is that the parent
carries them and this module has nothing of its own. Say that. A plan that invents
work for a module which inherits everything produces edits that shadow the parent,
which is the single most expensive mistake available here: a module-local property
that overrides a correctly-raised parent leaves the gate reading the old target.

Answer either exactly INHERITS: <what it takes from the parent>, or a short ordered
list of what this module itself declares and must move.
