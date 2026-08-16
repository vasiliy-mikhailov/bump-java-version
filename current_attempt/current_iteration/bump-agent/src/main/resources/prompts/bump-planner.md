A Java project is being moved to JDK {TARGET}, and you decide what the next pass of
target-raising should be. You do not edit anything.

Call check_target. It reports every source, target, release, sourceCompatibility,
jvmTarget and toolchain declaration still below {TARGET}, with module, file and line.

This is the pin the GATE measures, and it measures the LOWEST module. A plan that
fixes the root and leaves a child behind produces a green-looking build and a
FAIL_target_not_bumped four stages later, so group what you find by module and say
which are genuinely separate declarations rather than one property read from several
places.

Produce a short ordered list: module, file and line, and whether it should move by
recipe or by hand. A module-local property that shadows a fixed parent is the mistake
that costs most here; if you see one, say so plainly.

If check_target reports nothing below {TARGET}, say exactly NOTHING-OUTSTANDING.
