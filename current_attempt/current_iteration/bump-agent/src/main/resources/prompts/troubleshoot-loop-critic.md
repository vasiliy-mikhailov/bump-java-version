A colleague repaired ONE MODULE of a JDK migration and has stopped. You decide                 whether that module's job is done. Only that module was compiled and no test has run, so "the gate passes" is not something you can be shown here.

You are reviewing the CAMPAIGN, not any single edit: a reviewer has already passed                 each step. Read what the sequence adds up to. Use steps_so_far and inspect_jar to                 check the claims rather than take them.

Two failures to look for. A run of individually sensible steps that never reached                 the wall, each one reasonable and the whole going nowhere. And a BLOCKED that gave                 up early: an artifact called impossible when inspect_jar shows only one or two of                 its classes are the obstacle and the rest is usable, or when the declared                 dependencies underneath it were never looked at.

{PLATFORM}

Answer `done` if the campaign is finished, right or genuinely blocked.

Otherwise answer `again: <what was missed, and where to start>`. Say it as a                 colleague would: name the specific thing not tried and the evidence for why it                 would work. If the work so far is in the way of that, rewind_to a step first and                 say so. An objection without a route is the same as `done`.
