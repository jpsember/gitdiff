package js.gitdiff;

import static js.base.Tools.*;

import java.io.IOException;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import js.file.Files;

public final class BaseTerminal {

  public static int readCharacter() {
    try {
      return openTerminal().read();
    } catch (IOException e) {
      throw Files.asFileException(e);
    }
  }

  public static int terminalWidth() {
    openTerminal();
    int width = sTerminal.getWidth();
    if (width <= 0) {
      alert("!terminalWidth is undefined; are we running in Eclipse?");
      width = 120;
    }
    return width;
  }

  public static String readCharAsString() {
    return Character.toString((char) BaseTerminal.readCharacter());
  }

  public static void shutdown() {
    if (sReader == null)
      return;
    try {
      sReader.close();
      sTerminal.close();
    } catch (IOException e) {
      pr("Caught:", INDENT, e);
    } finally {
      sReader = null;
      sTerminal = null;
    }
  }

  private static String color(String expr) {
    return Character.toString((char) 0x1b) + "[" + expr + "m";
  }

  //  Black: \u001b[30m
  //  Green: \u001b[32m
  //  Yellow: \u001b[33m
  //  Magenta: \u001b[35m
  //  Cyan: \u001b[36m
  //  White: \u001b[37m

  public static String BLUE = color("34");
  public static String RED = color("31");
  public static String RESET = color("0");

  private static NonBlockingReader openTerminal() {
    if (sReader != null)
      return sReader;
    try {
      Runtime.getRuntime().addShutdownHook(new Thread() {
        public void run() {
          shutdown();
        };
      });
      Terminal terminal = TerminalBuilder.builder().jna(true).system(true).build();

      // raw mode means we get keypresses rather than line buffered input
      terminal.enterRawMode();
      sTerminal = terminal;
      sReader = terminal.reader();

      return sReader;
    } catch (IOException e) {
      throw asRuntimeException(e);
    }
  }

  private static NonBlockingReader sReader;
  private static Terminal sTerminal;

}
