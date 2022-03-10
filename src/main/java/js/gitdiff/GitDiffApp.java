package js.gitdiff;

import static js.base.Tools.*;

import js.app.App;

public class GitDiffApp extends App {

  public static void main(String[] args) {
    loadTools();
    new GitDiffApp().startApplication(args);
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public boolean supportArgsFile() {
    return false;
  }

  @Override
  protected void registerOperations() {
    registerOper(new GitDiffOper());
  }

}
