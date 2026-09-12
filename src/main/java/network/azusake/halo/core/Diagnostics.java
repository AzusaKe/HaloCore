package network.azusake.halo.core;

/** Host-configurable diagnostics; no logging framework is required by the core. */
public final class Diagnostics {
    @FunctionalInterface public interface Sink { void log(String level, String message, Object... args); }
    private static volatile Sink sink = (level, message, args) -> {};
    public static void setSink(Sink value) { sink = java.util.Objects.requireNonNull(value); }
    public static Logger logger(Object name) { return new Logger(); }
    public static final class Logger {
        public void info(String message,Object... args) { sink.log("info",message,args); }
        public void warn(String message,Object... args) { sink.log("warn",message,args); }
        public void debug(String message,Object... args) { sink.log("debug",message,args); }
        public void trace(String message,Object... args) { sink.log("trace",message,args); }
    }
}
