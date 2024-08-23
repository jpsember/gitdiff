package js.gitdiff;

import static js.base.Tools.*;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import js.app.AppOper;
import js.app.CmdLineArgs;
import js.base.BasePrinter;
import js.base.SystemCall;
import js.data.DataUtil;
import js.file.BackupManager;
import js.file.Files;
import js.geometry.MyMath;
import js.gitdiff.gen.HunkCursor;
import js.gitutil.GitRepo;
import gitutil.gen.FileEntry;
import gitutil.gen.FileState;
import gitutil.gen.Hunk;
import js.json.JSMap;
import js.system.SystemUtil;

public class GitDiffOper extends AppOper {

  @Override
  protected String shortHelp() {
    return "displays git diff, allow accepting, editing, reverting changes";
  }

  @Override
  protected void longHelp(BasePrinter b) {
       b.pr("[ forget | distance <x> | unaccept]*");
  }
  
  @Override
  public String userCommand() {
    return "gitdiff";
  }

  private static final String CLARG_FORGET = "forget";
  private static final String CLARG_DISTANCE = "distance";
  private static final String CLARG_UNACCEPT = "unaccept";

  @Override
  public void addCommandLineArgs(CmdLineArgs ca) {
    ca.add(CLARG_FORGET).desc("Forget any previously accepted differences").shortName("f");
    ca.add(CLARG_DISTANCE).def(0).desc("revision distance from previous commit").shortName("d");
    ca.add(CLARG_UNACCEPT).desc("unaccept most recent change").shortName("u");
  }

  @Override
  public void perform() {
    diff();
    reportUnusual();

    // Use a backup directory that is OUTSIDE of the repo, but with a name 
    // that hopefully is unique to this repo
    String repoName = repo().rootDirectory().getName();
    mBackupRootDir = new File(Files.homeDirectory(), ".gitdiff_backups/" + repoName);

    {
      // Look for any old backup directory, and warn if it exists
      File oldBackupDir = new File(repo().rootDirectory(), ".gitdiff_backups");
      if (oldBackupDir.exists()) {
        pr("*** Deprecated backup directory still exists:", INDENT, oldBackupDir);
        pr("*** Deleting it!");
        files().deleteDirectory(oldBackupDir);
      }
    }

    mBackups = new BackupManager(files(), repo().rootDirectory())//
        .withBackupRootDirectory(mBackupRootDir);
    mBackups.setVerbose(verbose());
    saveBackups();

    remove_skipped(cmdLineArgs().get(CLARG_FORGET));
    if (cmdLineArgs().get(CLARG_UNACCEPT)) {
      unaccept();
    }

    Integer skip_file_index = null;
    Integer accept_file_index = null;

    int horizontal_offset = 0;
    int scroll_amount = 20;

    HunkCursor.Builder cursor = HunkCursor.DEFAULT_INSTANCE.toBuilder();

    //  We repeat this loop until we run out of hunks, or we're told to quit
    boolean reset_scroll = true;
    boolean quit_flag = false;
    boolean alternate = false;

    long targetHash = 0;

    while (!quit_flag) {

      if (targetHash != 0) {
        setCursorForHash(cursor, targetHash);
        targetHash = 0;
      }

      if (cursor.fileIndex() >= diff().fileEntries().size())
        break;
      FileEntry file_ent = diff().fileEntries().get(cursor.fileIndex());
      if (cursor.hunkIndex() >= file_ent.hunks().size()) {
        cursor.fileIndex(cursor.fileIndex() + 1);
        cursor.hunkIndex(0);
        continue;
      }
      if (reset_scroll)
        horizontal_offset = 0;
      reset_scroll = true;

      Hunk h = file_ent.hunks().get(cursor.hunkIndex());
      long hunk_hash = calculateHash(file_ent, h);

      // Has user already dealt with this hunk?
      if (getHunkStatus(hunk_hash) != HUNK_UNKNOWN) {
        cursor.hunkIndex(cursor.hunkIndex() + 1);
        continue;
      }
      //
      if (skip_file_index != null && skip_file_index == cursor.fileIndex()) {
        setHunkStatus(hunk_hash, HUNK_SKIPPED);
        continue;
      }
      if (accept_file_index != null && accept_file_index == cursor.fileIndex()) {
        setHunkStatus(hunk_hash, HUNK_ACCEPTED);
        // Sleep a bit so timestamps can still be reliably used for undoing
        SystemUtil.runUnchecked(() -> Thread.sleep(50));
        continue;
      }
      pr("\n\n\n\n");
      // Print different number linefeeds each time so user
      // knows he's making progress
      if (alternate)
        pr("\n");
      alternate ^= true;

      String x = diff().generateHunkDisplay(file_ent, h, horizontal_offset);
      System.out.println(x);

      while (!quit_flag) {

        StringBuilder sb = new StringBuilder();
        {
          String branchName = repo().branchName();
          if (!(branchName.equals("main") || branchName.equals("master"))) {
            sb.append(BaseTerminal.RED);
            sb.append("*** Branch: ");
            sb.append(branchName);
            sb.append("*** ");
            sb.append(BaseTerminal.RESET);
          }
        }
        sb.append(
            "a)ccept, A)ll in file, e)dit, R)evert, s)kip, S)kipfile, m)ark, q)uit, u)naccept, ag)ain: ");
        System.out.print(sb.toString());

        String cmd = BaseTerminal.readCharAsString();

        pr();
        boolean valid = true;

        switch (cmd) {
        case "q":
          quit_flag = true;
          break;
        case "-":
          horizontal_offset = Math.max(0, horizontal_offset - scroll_amount);
          reset_scroll = false;
          break;
        case "=":
          horizontal_offset = Math.min(horizontal_offset + scroll_amount, 250);
          reset_scroll = false;
          break;
        case "a":
          setHunkStatus(hunk_hash, HUNK_ACCEPTED);
          break;
        case "A":
          setHunkStatus(hunk_hash, HUNK_ACCEPTED);
          accept_file_index = cursor.fileIndex();
          break;
        case "u": {
          long cursorHash = unaccept();
          discardGitDiff();
          targetHash = cursorHash;
        }
          break;
        case "e": {
          if (file_ent.state() == FileState.DELETED) {
            pr("File was deleted! Try reverting it first.");
            valid = false;
          } else {
            int lineNumber = h.r2Begin();
            File absFilePath = repo().absoluteFile(file_ent.path());
            SystemUtil.runUnchecked(() -> {
              Process p = Runtime.getRuntime().exec("/bin/bash");
              OutputStream stdin = p.getOutputStream();
              PrintWriter pw = new PrintWriter(stdin);
              pw.println("vi +" + lineNumber + " " + absFilePath + " < /dev/tty > /dev/tty");
              pw.close();
              p.waitFor();
            });
            discardGitDiff();
          }
        }
          break;
        case "R":
          revert(file_ent, h);
          discardGitDiff();
          break;
        case "g":
          // (just display the results and repeat)
          break;
        case "s":
          setHunkStatus(hunk_hash, HUNK_SKIPPED);
          break;
        case "S":
          setHunkStatus(hunk_hash, HUNK_SKIPPED);
          skip_file_index = cursor.fileIndex();
          break;
        case "m":
          if (file_ent.state() == FileState.DELETED) {
            pr("File was deleted! Try reverting it first.");
            valid = false;
            break;
          }
          insertMark(file_ent, h);
          discardGitDiff();
          break;
        default:
          valid = false;
          pr("Invalid choice!");
          break;
        }
        if (valid)
          break;
      }
    }

    // Report summary of accepted, skipped changes
    {
      int acceptCount = 0;
      int skippedCount = 0;
      int changeCount = 0;
      int entryCount = diff().fileEntries().size();
      for (FileEntry ent : diff().fileEntries()) {
        for (Hunk h : ent.hunks()) {
          changeCount++;
          int status = getHunkStatus(calculateHash(ent, h));
          switch (status) {
          case HUNK_ACCEPTED:
            acceptCount++;
            break;
          case HUNK_SKIPPED:
            skippedCount++;
            break;
          }
        }
      }
      if (changeCount == 0 && entryCount == 0)
        pr("...no changes");
      else if (changeCount != 0)
        pr("...changes: " + changeCount + "  accepted: " + acceptCount + "  skipped: " + skippedCount);
      else
        pr("...changes:", entryCount);
    }
    reportUnusual();
  }

  private void reportUnusual() {
    if (!repo().untrackedFiles().isEmpty()) {
      pr();
      pr("*** Untracked files exist:");
      printFiles(repo().untrackedFiles());
    }
    if (!repo().unmergedFiles().isEmpty()) {
      pr();
      pr("*** Merge conflicts exist:");
      printFiles(repo().unmergedFiles());
    }
  }

  private void printFiles(List<FileEntry> entries) {
    for (FileEntry e : entries)
      pr("  ", repo().fileRelativeToDirectory(e.path(), null));
  }

  private void setCursorForHash(HunkCursor.Builder cursor, long targetHash) {
    int fi = INIT_INDEX;
    for (FileEntry file_ent : diff().fileEntries()) {
      fi++;
      int hi = INIT_INDEX;
      for (Hunk h : file_ent.hunks()) {
        hi++;
        long hunk_hash = calculateHash(file_ent, h);
        if (hunk_hash == targetHash) {
          cursor.fileIndex(fi).hunkIndex(hi);
          return;
        }
      }
    }
  }

  private long calculateHash(FileEntry file_ent, Hunk h) {
    String hstr = file_ent.path() + h.toJson().toString();
    mCRC.reset();
    mCRC.update(DataUtil.toByteArray(hstr));
    return mCRC.getValue();
  }

  /**
   * Discard the most recent accepted hunk, and return its hash (or zero if no
   * accepted hunks found)
   */
  private long unaccept() {
    long mostRecentTimestamp = 0;
    long mostRecentHunkHash = 0;

    long outHash = 0;

    for (FileEntry fe : diff().fileEntries()) {
      for (Hunk h : fe.hunks()) {
        long hash = calculateHash(fe, h);
        if (getHunkStatus(hash) != HUNK_ACCEPTED)
          continue;
        long hunkTimestamp = getHunkTimestamp(hash);
        if (mostRecentTimestamp < hunkTimestamp) {
          mostRecentTimestamp = hunkTimestamp;
          mostRecentHunkHash = hash;
          outHash = hash;
        }
      }
    }
    if (mostRecentHunkHash != 0) {
      hunkMap().remove(hunkKey(mostRecentHunkHash));
      writeHunkMap();
    }
    return outHash;
  }

  /**
   * Remove any skipped flags (but not ones that have already been accepted?)
   */
  private void remove_skipped(boolean forget_all) {
    if (files().missingWithDryRunActive(mBackupRootDir))
      return;

    // Determine the set of keys corresponding to the current git state, so we can 
    // remove any stale ones
    Set<String> validKeys = hashSet();
    for (FileEntry fe : diff().fileEntries()) {
      for (Hunk h : fe.hunks()) {
        validKeys.add(hunkKey(calculateHash(fe, h)));
      }
    }

    for (String key : hunkMap().keySet()) {
      JSMap m = hunkMap().getMap(key);
      int state = m.getInt(HUNK_KEY_STATUS);
      if (forget_all || state < HUNK_ACCEPTED || !validKeys.contains(key)) {
        hunkMap().remove(key);
      }
    }
    writeHunkMap();
  }

  private GitRepo repo() {
    if (mGitRepo == null) {
      mGitRepo = new GitRepo(Files.currentDirectory());
    }
    return mGitRepo;
  }

  private GitDiff diff() {
    if (mGitDiff == null) {
      mGitRepo = null;
      String rev_name = repo().past_commit_name(-1 - cmdLineArgs().getInt(CLARG_DISTANCE));
      mGitDiff = new GitDiff(rev_name);
      mGitDiff.setVerbose(verbose());
    }
    return mGitDiff;
  }

  private void discardGitDiff() {
    mGitDiff = null;
  }

  private void saveBackups() {
    for (FileEntry fe : diff().fileEntries()) {
      if (fe.state() == FileState.DELETED || fe.state() == FileState.ADDED)
        continue;

      // If only the mode has changed, we don't need to back anything up
      if (fe.oldMode() != fe.mode()) {
        checkState(fe.path().isEmpty(), "Unexpected:", INDENT, fe);
        continue;
      }
      checkState(!fe.path().isEmpty(), "Empty path:", INDENT, fe);
      File relativeToRepo = repo().absoluteFile(fe.path());
      mBackups.makeBackup(relativeToRepo);
    }
  }

  private static String dump(List<String> strs) {
    StringBuilder sb = new StringBuilder();
    for (String s : strs) {
      sb.append("===> '");
      sb.append(s);
      sb.append("'\n");
    }
    return sb.toString();
  }

  private void dumpPatch(Hunk h) {
    if (!verbose())
      return;
    log("patch", INDENT, h.r1Begin(), h.r1Count(), CR, h.r2Begin(), h.r2Count());
    log(dump(h.lines()));
  }

  /**
   * Undoing a patch turns out to be simpler than I thought; as long as the
   * *second* range reflects the current state of the file, it doesn't matter if
   * the *first* range is invalid
   */
  private List<String> undoPatch(Hunk h, List<String> lines) {
    List<String> out = arrayList();

    // Copy unchanged the lines *before* the affected area
    out.addAll(lines.subList(0, h.r2Begin()));

    for (String line : h.lines()) {
      char marker = line.charAt(0);
      String content = line.substring(1);
      switch (marker) {
      default:
        throw badArg("Marker unexpected:", quote(Character.toString(marker)), quote(line));
      case '-':
      case ' ':
        out.add(content);
        break;
      case '+':
        break;
      }
    }

    // Copy unchanged the lines *after* the affected area
    out.addAll(lines.subList(h.r2Begin() + h.r2Count(), lines.size()));
    return out;
  }

  private void revert(FileEntry fileEntry, Hunk hunk) {
    if (fileEntry.state() == FileState.DELETED) {
      File filePath = repo().absoluteFile(fileEntry.origPath());
      new SystemCall().withVerbose(verbose())//
          .arg("git", "checkout", "--", filePath)//
          .assertSuccess();
      return;
    }
    File filePath = repo().absoluteFile(hunk.filename());
    String currentContent = Files.readString(filePath);
    List<String> currentLines = split(currentContent, '\n');
    if (verbose()) {
      log("Before revert:");
      dumpPatch(hunk);
    }
    currentLines = undoPatch(hunk, currentLines);
    String content = String.join("\n", currentLines);

    // If the old content was missing a final linefeed, make sure we restore that property
    if (hunk.missingNewline1()) {
      content = chomp(content, "\n");
    } else if (hunk.missingNewline2()) {
      // On the other hand, if old content had a final linefeed, and this hunk does not, 
      // then the hunk must be at the end of the file; restore that linefeed
      content = content + "\n";
    }
    files().writeString(filePath, content);
  }

  private void insertMark(FileEntry fileEntry, Hunk hunk) {
    File filePath = repo().absoluteFile(hunk.filename());
    String content = Files.readString(filePath);
    List<String> currentLines = split(content, '\n');
    // Place the mark just before the first modified line (if one exists); assume there's one line of context
    int markLineNumber = hunk.r2Begin() + 1;
    markLineNumber = MyMath.clamp(markLineNumber, 0, currentLines.size());
    currentLines.add(markLineNumber, GitRepo.MARK_SENTINEL_TEXT);
    content = String.join("\n", currentLines);
    files().writeString(filePath, content);
  }

  // ------------------------------------------------------------------
  // Hunk state map
  // ------------------------------------------------------------------

  private JSMap hunkMap() {
    if (mHunkMap == null)
      mHunkMap = JSMap.fromFileIfExists(hunkFile());
    return mHunkMap;
  }

  private static String hunkKey(long hashCode) {
    return "" + hashCode;
  }

  private JSMap hunkMapEntry(long hashcode) {
    return hunkMap().createMapIfMissing(hunkKey(hashcode));
  }

  private long getHunkTimestamp(long hashcode) {
    return hunkMapEntry(hashcode).getLong(HUNK_KEY_TIMESTAMP);
  }

  private static final String HUNK_KEY_STATUS = "s";
  private static final String HUNK_KEY_TIMESTAMP = "t";

  private int getHunkStatus(long hashcode) {
    String key = hunkKey(hashcode);
    JSMap m = hunkMap().optJSMap(key);
    if (m == null)
      return HUNK_UNKNOWN;
    return m.getInt(HUNK_KEY_STATUS);
  }

  private void setHunkStatus(long hunkHashCode, int statusCode) {
    String key = hunkKey(hunkHashCode);
    if (statusCode == HUNK_UNKNOWN)
      hunkMap().remove(key);
    else {
      JSMap entry = hunkMap().createMapIfMissing(hunkKey(hunkHashCode));
      entry//
          .put(HUNK_KEY_STATUS, statusCode)//
          .put(HUNK_KEY_TIMESTAMP, System.currentTimeMillis())//
      ;
    }
    writeHunkMap();
  }

  private void writeHunkMap() {
    files().writePretty(hunkFile(), hunkMap());
  }

  private File hunkFile() {
    return new File(mBackupRootDir, "hunks.json");
  }

  // Hunk status codes
  //
  private static final int HUNK_UNKNOWN = 0;
  private static final int HUNK_SKIPPED = 1;
  private static final int HUNK_ACCEPTED = 2;

  // ------------------------------------------------------------------

  private GitDiff mGitDiff;
  private GitRepo mGitRepo;
  private BackupManager mBackups;
  private File mBackupRootDir;
  private CRC32 mCRC = new CRC32();
  private JSMap mHunkMap;
}
