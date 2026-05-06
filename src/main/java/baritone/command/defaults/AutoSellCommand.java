/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.process.AutoSellProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class AutoSellCommand extends Command {

    public AutoSellCommand(IBaritone baritone) {
        super(baritone, "autosell");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        AutoSellProcess process = ((Baritone) baritone).getAutoSellProcess();
        if (!args.hasAny()) {
            // toggle
            boolean newValue = !Baritone.settings().autoSellEnabled.value;
            Baritone.settings().autoSellEnabled.value = newValue;
            if (!newValue) {
                process.reset();
            }
            logDirect("AutoSell " + (newValue ? "ENABLED" : "DISABLED"));
            return;
        }
        String arg = args.getString().toLowerCase();
        switch (arg) {
            case "on":
            case "enable":
            case "true":
                Baritone.settings().autoSellEnabled.value = true;
                logDirect("AutoSell ENABLED");
                break;
            case "off":
            case "disable":
            case "false":
                Baritone.settings().autoSellEnabled.value = false;
                process.reset();
                logDirect("AutoSell DISABLED");
                break;
            case "now":
            case "trigger":
                if (!Baritone.settings().autoSellEnabled.value) {
                    Baritone.settings().autoSellEnabled.value = true;
                    logDirect("AutoSell auto-enabled for manual trigger");
                }
                process.triggerNow();
                logDirect("AutoSell: manual trigger requested");
                break;
            case "status":
                logDirect("AutoSell is "
                        + (Baritone.settings().autoSellEnabled.value ? "ENABLED" : "DISABLED")
                        + " (threshold=" + Baritone.settings().autoSellInventoryThreshold.value
                        + ", command=/" + Baritone.settings().autoSellCommand.value + ")");
                break;
            default:
                logDirect("Unknown autosell sub-command: " + arg + ". Use on / off / now / status.");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append("on", "off", "now", "status")
                    .filterPrefix(args.getString())
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Toggle auto-sell of inventory via /seller";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "When enabled, AutoSell monitors the bot's main inventory while #farm runs.",
                "Once at least autoSellInventoryThreshold (default 90%) of the 36 main slots are non-empty,",
                "the bot pauses farming, runs the configured slash command (default /seller), clicks the",
                "category slot containing a cactus block (the 'Фермер' category), then right-clicks the slot",
                "containing a melon block to sell the entire stack. Once the menu is closed, farming resumes.",
                "",
                "Usage:",
                "> autosell           - toggle on/off",
                "> autosell on|off    - explicit on/off",
                "> autosell now       - immediately trigger a sell cycle (also enables autosell)",
                "> autosell status    - print current state",
                "",
                "Related settings: autoSellEnabled, autoSellInventoryThreshold, autoSellCommand,",
                "autoSellTimeoutTicks, autoSellWaitAfterSellTicks, autoSellCooldownTicks, autoSellMaxFailures."
        );
    }
}
