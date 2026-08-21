package tech.mikhailov.bjv.bump;

import java.nio.file.Files;
import java.nio.file.Path;

import tech.mikhailov.ratchet.record.Json;

/**
 * WHICH ROUND OF ITS LANE BUDGET THIS BUMP IS IN, AND WHETHER THE LANE HAS BEEN ASKED TO STOP.
 *
 * <p>THERE IS NO CLOCK IN THIS PROCESS AND NO BUDGET IN IT EITHER. The launcher owns both. It says
 * so by creating one file this container can see, and everything here is a reaction to that file
 * existing. Nothing an agent is handed mentions it, no tool reports it, and no prompt is built from
 * it: the written finding this project keeps is that a model told it is racing a clock produces
 * garbage and gives up, and the only way to honour that with certainty is for the model's side of
 * the process to have nothing to tell it.
 *
 * <p>THE NUMBER IS COUNTED RATHER THAN KEPT. A stored counter would be a second copy of a fact the
 * settlement rows already carry, and two copies of one fact drift. One {@code paused} row is
 * written per round that ended, by whichever side ended it, so the count of them plus one is the
 * round now starting. The shell counts the same rows the same way for the same answer.
 */
final class Round {

    /**
     * A ROUND BOUNDARY, WHICH IS NOT A VERDICT AND NOT A REQUEUE.
     *
     * <p>{@code requeued} was the tempting reuse and it is the opposite instruction: that is
     * somebody on a page asking for the work to be done again FROM THE START, and resuming one
     * would hand that person back the state they were trying to discard.
     */
    static final String PAUSED = "paused";

    private final Path marker;
    private final int number;

    private Round(Path marker, int number) {
        this.marker = marker;
        this.number = number;
    }

    /**
     * The round this lane is running, and where to look for the launcher's word.
     *
     * <p>The marker directory is named from the same slug the claims and the postponements use, and
     * it is computed the same way on both sides, so no new name and no new environment variable
     * crosses into the container.
     */
    static Round of(Path results, String bump) {
        Path expiring = results.resolve("expiring").resolve(Bump.slugOf(bump));
        return new Round(expiring, ended(results.resolve("settlements.jsonl"), bump) + 1);
    }

    /** A round that can never end, for a bump built to be read rather than run. */
    static Round none() {
        return new Round(null, 0);
    }

    /**
     * WHETHER THE LANE HAS BEEN ASKED TO STOP. The only thing this process knows about time.
     *
     * <p>Read between stages and nowhere else. A stage in flight finishes: everything a stage lands
     * is committed as it lands, so the overshoot is one stage and the loss is nothing. Abandoning
     * mid-stage would lose the same work a resume reverts anyway, and it would need a check inside
     * the agent loop, which is one refactor away from being something the agent can see.
     */
    boolean reached() {
        return marker != null && Files.exists(marker);
    }

    /** Which round this is, from one upwards. Zero when nobody is counting. */
    int number() {
        return number;
    }

    /** The settlement account for a boundary, naming the stage that did not start. */
    String account(String stage) {
        return PAUSED + "\nthe lane ended between stages, at " + stage
                + "; everything that landed is committed, and the checkout and the journal are kept"
                + " so the next lane continues from here while the pipeline is unchanged";
    }

    /**
     * How many rounds of this bump have already ended, read off the record.
     *
     * <p>Read leniently, for the reason everything that reads this file is: it is appended to by a
     * process that gets killed, so a torn line is the normal case rather than a fault. The key is
     * checked rather than assumed, because the whole sweep shares the file.
     */
    private static int ended(Path settlements, String bump) {
        if (!Files.isReadable(settlements)) {
            return 0;
        }
        int n = 0;
        try {
            for (String line : Files.readAllLines(settlements)) {
                if (!line.contains(bump) || !line.contains("\"state\":\"" + PAUSED + "\"")) {
                    continue;
                }
                try {
                    if (bump.equals(Json.row(line).getOrDefault("bump", ""))) {
                        n++;
                    }
                } catch (RuntimeException torn) {
                    // A row nothing can parse is a row that says nothing, which is not a round.
                }
            }
        } catch (Exception unreadable) {
            return n;
        }
        return n;
    }
}
