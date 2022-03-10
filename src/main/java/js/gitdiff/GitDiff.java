package js.gitdiff;

import static js.base.Tools.*;

import java.util.List;

import js.base.BaseObject;
import js.base.SystemCall;
import js.file.Files;
import gitutil.gen.FileEntry;
import gitutil.gen.FileState;
import gitutil.gen.Hunk;
import js.parsing.StringParser;

public final class GitDiff extends BaseObject {

  public GitDiff(String commitName) {
    mCommitName = commitName;
  }

  private final String mCommitName;

  public List<FileEntry> fileEntries() {
    if (mFileEntries == null)
      parseGitDiff();
    return mFileEntries;
  }

  // ------------------------------------------------------------------
  // Reading lines from gitdiff output
  // ------------------------------------------------------------------

  private List<String> mLines;
  private int mCursor;

  private String peek() {
    if (mCursor == mLines.size())
      return null;
    return mLines.get(mCursor);
  }

  private String readLine() {
    String line = peek();
    checkState(line != null, "Unexpected end of file");
    mCursor++;
    if (verbose())
      log(mCursor, ">>>", quote(line));
    return line;
  }

  // ------------------------------------------------------------------

  private void parseGitDiff() {
    mFileEntries = arrayList();
    String content = chomp(makeGitDiffSystemCall());
    if (content.isEmpty())
      return;
    mCursor = 0;
    mLines = split(content, '\n');
    while (peek() != null) {
      readGitDiffHeader();
      FileEntry.Builder fileEntry = FileEntry.newBuilder();
      readExtendedHeader(fileEntry);
      if (peek() == null || peek().startsWith("diff --git")) {
      } else if (peek().startsWith("Binary")) {
        processBinaryFile(fileEntry);
      } else {
        log("read start of diff");
        readUnifiedHeader(fileEntry);
        readHunks(fileEntry);
      }
      mFileEntries.add(fileEntry.build());
    }
  }

  private String makeGitDiffSystemCall() {
    SystemCall s = new SystemCall().withVerbose(verbose());
    s.arg("git", "diff");
    if (!nullOrEmpty(mCommitName))
      s.arg(mCommitName);
    s.arg("-U1");
    return s.assertSuccess().systemOut();
  }

  private StringParser readIntoParser() {
    return new StringParser(readLine());
  }

  private void readGitDiffHeader() {
    log("read git diff header");
    StringParser p = readIntoParser();
    p.read("diff --git");
  }

  private void readExtendedHeader(FileEntry.Builder fileEntry) {
    fileEntry.state(FileState.MODIFIED);

    // We don't know if we are going to consume the next line, so 
    // only do so on subsequent returns to the top of the while loop
    boolean readLine = false;

    while (true) {
      if (readLine)
        readLine();
      readLine = true;

      // see https://git-scm.com/docs/git-diff

      String line = peek();
      if (line == null)
        break;

      StringParser p = new StringParser(line);

      if (p.readIf("index")) {
        continue;
      }
      if (p.readIf("similarity index ")) {
        p.readRemaining();
        continue;
      }

      if (p.readIf("old mode ")) {
        fileEntry.oldMode(p.readRemaining());
        continue;
      }
      if (p.readIf("new mode ")) {
        fileEntry.mode(p.readRemaining());
        continue;
      }
      if (p.readIf("deleted file mode ")) {
        fileEntry.mode(p.readRemaining());
        fileEntry.state(FileState.DELETED);
        continue;
      }
      if (p.readIf("new file mode ")) {
        fileEntry.mode(p.readRemaining());
        fileEntry.state(FileState.ADDED);
        continue;
      }
      if (p.readIf("copy from ")) {
        fileEntry.origPath(p.readPath());
        continue;
      }
      if (p.readIf("copy to ")) {
        fileEntry.path(p.readPath());
        continue;
      }
      if (p.readIf("rename from ")) {
        fileEntry.state(FileState.RENAMED);
        fileEntry.origPath(p.readPath());
        continue;
      }
      if (p.readIf("rename to ")) {
        fileEntry.state(FileState.RENAMED);
        fileEntry.path(p.readPath());
        continue;
      }
      break;
    }
  }

  private void readUnifiedHeader(FileEntry.Builder fileEntry) {
    log("readUnifiedHeader");

    StringParser p;
    String pathA, pathB;

    p = readIntoParser();
    p.read("--- ");
    pathA = p.readPath();
    p.assertDone();

    p = readIntoParser();
    p.read("+++ ");
    pathB = p.readPath();
    p.assertDone();

    if (fileEntry.state() != FileState.ADDED) {
      checkArgument(pathA.startsWith("a/"));
      fileEntry.origPath(pathA.substring(2));
    }
    if (fileEntry.state() != FileState.DELETED) {
      checkArgument(pathB.startsWith("b/"));
      fileEntry.path(pathB.substring(2));
    }
    log("file entry:", INDENT, fileEntry);
  }

  private void processBinaryFile(FileEntry.Builder fileEntry) {
    log("processBinaryFile");

    StringParser p;
    String pathA, pathB;

    p = readIntoParser();
    p.read("Binary files ");
    pathA = p.readPath();
    p.read(" and ");
    pathB = p.readPath();
    p.read(" differ");
    p.assertDone();

    if (fileEntry.state() != FileState.ADDED) {
      checkArgument(pathA.startsWith("a/"));
      fileEntry.origPath(pathA.substring(2));
    }
    if (fileEntry.state() != FileState.DELETED) {
      checkArgument(pathB.startsWith("b/"));
      fileEntry.path(pathB.substring(2));
    }
    log("file entry:", INDENT, fileEntry);
  }

  private void readHunks(FileEntry.Builder fileEntry) {

    while (true) {

      String x = peek();
      if (x == null || !x.startsWith("@@"))
        break;
      StringParser p = readIntoParser();

      Hunk.Builder h = Hunk.newBuilder();
      h.filename(fileEntry.path());

      // The range information line has this format (from https://en.wikipedia.org/wiki/Diff#Unified_format):
      //
      //  @@ -l[,s] +l[,s] @@[ optional section heading]
      //

      // Let's have the line numbers start at zero for simplicity later
      //
      p.read("@@ -");
      h.r1Begin(p.readInteger() - 1);
      h.r1Count(1);
      if (p.readIf(","))
        h.r1Count(p.readInteger());
      p.read(" +");
      h.r2Begin(p.readInteger() - 1);
      h.r2Count(1);
      if (p.readIf(","))
        h.r2Count(p.readInteger());
      p.read(" @@");

      int sourceIndex = 0;
      while (true) {
        x = peek();
        if (nullOrEmpty(x))
          break;
        char c = x.charAt(0);
        if (" +-\\".indexOf(c) < 0) {
          break;
        }

        // We need to detect the 'no newline' message to set the according flag in the hunk
        if (c == '-') {
          sourceIndex = 1;
        } else if (c == '+') {
          sourceIndex = 2;
        } else if (c == '\\') {
          if (!x.equals("\\ No newline at end of file"))
            badArg("Unknown message:", quote(x));
          switch (sourceIndex) {
          default:
            badState("No newline, but no file to attach it to");
            break;
          case 1:
            h.missingNewline1(true);
            break;
          case 2:
            h.missingNewline2(true);
            break;
          }
          // Don't include the newline message in the text, since we've extracted it to the appropriate flags
          readLine();
          continue;
        }
        h.lines().add(x);
        readLine();
      }
      fileEntry.hunks().add(h.build());
    }
    checkState(!fileEntry.hunks().isEmpty(), "missing hunks");
  }

  private List<FileEntry> mFileEntries;

  private static String optionalSubstring(String string, int startPosition) {
    if (startPosition < string.length())
      return string.substring(startPosition);
    return "";
  }

  private static final String sCutoffPrefix = "\u2056\u2058\u2059\u205c\u2055";
  private static final String sCutoffSuffix = new StringBuilder(sCutoffPrefix).reverse().toString();

  public String generateHunkDisplay(FileEntry fileEntry, Hunk hunk, int horizontalOffset) {
    int terminalWidth = BaseTerminal.terminalWidth() - 1;

    List<String> a = arrayList();
    List<String> b = arrayList();
    List<String> c = arrayList();

    int width = (terminalWidth - 8) / 2;
    int dashSize = terminalWidth;
    int lineNumber = 0;
    while (lineNumber < hunk.lines().size()) {
      String x = hunk.lines().get(lineNumber);
      String y = x.substring(1);
      String z = x.substring(0, 1);

      String x2 = null;
      String z2 = "?";
      String y2 = "";
      if (lineNumber + 1 < hunk.lines().size()) {
        x2 = hunk.lines().get(lineNumber + 1);
        y2 = x2.substring(1);
        z2 = x2.substring(0, 1);
      }

      String prefix = (horizontalOffset != 0) ? sCutoffPrefix : "";

      y = prefix + optionalSubstring(y, horizontalOffset);
      y2 = prefix + optionalSubstring(y2, horizontalOffset);

      if (z.equals("-") && z2.equals("+")) {
        a.add(y);
        b.add(y2);
        // If the only difference is whitespace, indicate as much
        String w1 = replaceTabsWithSpaces(y);
        String w2 = replaceTabsWithSpaces(y2);
        if (trimRight(w1).equals(trimRight(w2)))
          c.add("ww");
        else
          c.add("++");
        lineNumber++;
      } else {
        switch (z) {
        case " ":
          a.add(y);
          b.add(y);
          c.add("  ");
          break;
        case "-":
          a.add(y);
          b.add("");
          c.add("+.");
          break;
        case "+":
          a.add("");
          b.add(y);
          c.add(".+");
          break;
        case "\\":
          a.add("(missing linefeed)");
          b.add("");
          c.add("+.");
          break;
        }
      }
      lineNumber++;
    }

    StringBuilder sb = new StringBuilder();
    dashes(sb, dashSize, null);
    sb.append('\n');

    // Experiment: display source code in a different color
    //
    sb.append(BaseTerminal.BLUE);

    int maxLines = 20;
    if (a.size() <= maxLines) {
      for (int i = 0; i < a.size(); i++) {
        String x = replaceTabsWithSpaces(a.get(i));
        String y = replaceTabsWithSpaces(b.get(i));
        pad(sb, x, width);
        sb.append("   ");
        sb.append(c.get(i));
        sb.append("   ");
        pad(sb, y, width);
        sb.append('\n');
      }
    } else {
      for (int i = 0; i < maxLines / 2; i++) {
        String x = replaceTabsWithSpaces(a.get(i));
        String y = replaceTabsWithSpaces(b.get(i));
        pad(sb, x, width);
        sb.append("   ");
        sb.append(c.get(i));
        sb.append("   ");
        pad(sb, y, width);
        sb.append('\n');
      }

      sb.append('\n');
      pad(sb, "", width);
      sb.append("   :\n");
      pad(sb, "", width - 4);
      sb.append("(");
      sb.append(a.size() - maxLines);
      sb.append(" lines)\n");
      pad(sb, "", width);
      sb.append("   :\n");
      sb.append('\n');
      for (int j = 0; j < maxLines / 2; j++) {
        int i = a.size() - (maxLines / 2) + j;
        String x = replaceTabsWithSpaces(a.get(i));
        String y = replaceTabsWithSpaces(b.get(i));
        pad(sb, x, width);
        sb.append("   ");
        sb.append(c.get(i));
        sb.append("   ");
        pad(sb, y, width);
        sb.append('\n');
      }
    }
    sb.append('\n');

    sb.append(BaseTerminal.RESET);

    // Include the entire relative path of the file, unless it is pretty big
    //
    String filename = fileEntry.path();
    String prefix = "";

    if (fileEntry.state() == FileState.DELETED) {
      prefix = "Deleted";
      filename = fileEntry.origPath();
    } else if (fileEntry.state() == FileState.ADDED) {
      prefix = "New file";
    }
    if (filename.length() > terminalWidth * .8f)
      filename = Files.basename(filename);
    String fileSummary;
    if (!prefix.isEmpty())
      fileSummary = "*** " + prefix + ": " + filename + " ***";
    else
      fileSummary = filename;
    dashes(sb, dashSize, fileSummary);
    return sb.toString();
  }

  /**
   * Fix up whitespace for a single line of text: trim trailing whitespace, and
   * replace tabs with spaces. Trims any trailing linefeeds.
   */
  private static String replaceTabsWithSpaces(String line) {
    int tabWidth = 2;
    line = trimRight(line);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\t') {
        int j = (sb.length() + tabWidth);
        j -= j % tabWidth;
        tab(sb, j);
      } else
        sb.append(c);
    }
    return sb.toString();
  }

  private static void pad(StringBuilder sb, String str, int len) {
    int targetLength = len + sb.length();
    if (str.length() > len) {
      sb.append(str.substring(0, len - sCutoffSuffix.length()));
      sb.append(sCutoffSuffix);
    } else {
      sb.append(str);
      while (sb.length() < targetLength)
        sb.append(' ');
    }
  }

  private static final String DASHES = repeatText("-", 300);

  private static void dashes(StringBuilder target, int width, String msg) {
    String s = DASHES.substring(0, width);
    if (msg != null) {
      msg = "   " + msg + "   ";
      int cut0 = (width - msg.length()) / 2;
      int cut1 = cut0 + msg.length();
      s = s.substring(0, cut0) + msg + s.substring(cut1);
    }
    target.append(s);
    target.append('\n');
  }
}
