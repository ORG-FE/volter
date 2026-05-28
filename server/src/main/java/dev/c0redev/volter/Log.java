package dev.c0redev.volter;

import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class Log {
  private static final DateTimeFormatter TS = DateTimeFormatter
      .ofPattern("HH:mm:ss.SSS")
      .withZone(ZoneId.systemDefault());
  private static boolean debug;
  private static boolean quicTrace;
  private static volatile boolean julReady;

  static void setDebug(boolean on) {
    debug = on;
  }

  static void setQuicTrace(boolean on) {
    quicTrace = on;
  }

  static boolean quicTrace() {
    return quicTrace;
  }

  
  static synchronized void configureJul() {
    if (julReady) {
      return;
    }
    julReady = true;

    Logger root = Logger.getLogger("");
    root.setUseParentHandlers(false);
    for (var h : root.getHandlers()) {
      root.removeHandler(h);
    }
    ConsoleHandler ch = new ConsoleHandler();
    ch.setFormatter(new PrettyFormatter());
    if (quicTrace) {
      ch.setLevel(Level.ALL);
    } else if (debug) {
      ch.setLevel(Level.INFO);
    } else {
      ch.setLevel(Level.WARNING);
    }
    root.addHandler(ch);
    root.setLevel(Level.ALL);

    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);

    if (quicTrace) {
      Logger.getLogger("io.netty.incubator.codec.quic").setLevel(Level.FINEST);
      Logger.getLogger("io.netty.handler.ssl").setLevel(Level.FINE);
      Logger.getLogger("io.netty.channel.nio").setLevel(Level.FINE);
      Logger.getLogger("dev.c0redev.volter").setLevel(Level.FINEST);
    }
  }

  static Logger logger(Class<?> c) {
    configureJul();
    Logger l = Logger.getLogger(c.getName());
    l.setUseParentHandlers(true);
    if (!debug && !quicTrace) {
      l.setLevel(Level.WARNING);
    } else if (quicTrace) {
      l.setLevel(Level.FINEST);
    } else {
      l.setLevel(Level.INFO);
    }
    return l;
  }

  private static final class PrettyFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
      StringBuilder out = new StringBuilder(192);
      out.append(TS.format(Instant.ofEpochMilli(record.getMillis())));
      out.append(' ');
      out.append(mark(record.getLevel()));
      out.append(' ');
      out.append(loggerName(record.getLoggerName()));
      if (debug || quicTrace) {
        out.append(" [");
        out.append(Thread.currentThread().getName());
        out.append(']');
      }
      out.append(": ");
      out.append(formatMessage(record));
      out.append(System.lineSeparator());
      Throwable thrown = record.getThrown();
      if (thrown != null) {
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));
        out.append(sw);
      }
      return out.toString();
    }

    private static String mark(Level level) {
      int v = level.intValue();
      if (v >= Level.SEVERE.intValue()) return "[x]";
      if (v >= Level.WARNING.intValue()) return "[!]";
      if (v >= Level.INFO.intValue()) return "[+]";
      if (v >= Level.FINE.intValue()) return "[~]";
      return "[.]";
    }

    private static String loggerName(String name) {
      if (name == null || name.isBlank()) return "root";
      int i = name.lastIndexOf('.');
      return i >= 0 && i + 1 < name.length() ? name.substring(i + 1) : name;
    }
  }
}
