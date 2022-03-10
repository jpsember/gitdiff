package js.gitdiff.gen;

import js.data.AbstractData;
import js.json.JSMap;

public class HunkCursor implements AbstractData {

  public int fileIndex() {
    return mFileIndex;
  }

  public int hunkIndex() {
    return mHunkIndex;
  }

  @Override
  public Builder toBuilder() {
    return new Builder(this);
  }

  public static final String FILE_INDEX = "file_index";
  public static final String HUNK_INDEX = "hunk_index";

  @Override
  public String toString() {
    return toJson().prettyPrint();
  }

  @Override
  public JSMap toJson() {
    JSMap m = new JSMap();
    m.put(FILE_INDEX, mFileIndex);
    m.put(HUNK_INDEX, mHunkIndex);
    return m;
  }

  @Override
  public HunkCursor build() {
    return this;
  }

  @Override
  public HunkCursor parse(Object obj) {
    return new HunkCursor((JSMap) obj);
  }

  private HunkCursor(JSMap m) {
    mFileIndex = m.opt(FILE_INDEX, 0);
    mHunkIndex = m.opt(HUNK_INDEX, 0);
  }

  public static Builder newBuilder() {
    return new Builder(DEFAULT_INSTANCE);
  }

  @Override
  public boolean equals(Object object) {
    if (this == object)
      return true;
    if (object == null || !(object instanceof HunkCursor))
      return false;
    HunkCursor other = (HunkCursor) object;
    if (other.hashCode() != hashCode())
      return false;
    if (!(mFileIndex == other.mFileIndex))
      return false;
    if (!(mHunkIndex == other.mHunkIndex))
      return false;
    return true;
  }

  @Override
  public int hashCode() {
    int r = m__hashcode;
    if (r == 0) {
      r = 1;
      r = r * 37 + mFileIndex;
      r = r * 37 + mHunkIndex;
      m__hashcode = r;
    }
    return r;
  }

  protected int mFileIndex;
  protected int mHunkIndex;
  protected int m__hashcode;

  public static final class Builder extends HunkCursor {

    private Builder(HunkCursor m) {
      mFileIndex = m.mFileIndex;
      mHunkIndex = m.mHunkIndex;
    }

    @Override
    public Builder toBuilder() {
      return this;
    }

    @Override
    public int hashCode() {
      m__hashcode = 0;
      return super.hashCode();
    }

    @Override
    public HunkCursor build() {
      HunkCursor r = new HunkCursor();
      r.mFileIndex = mFileIndex;
      r.mHunkIndex = mHunkIndex;
      return r;
    }

    public Builder fileIndex(int x) {
      mFileIndex = x;
      return this;
    }

    public Builder hunkIndex(int x) {
      mHunkIndex = x;
      return this;
    }

  }

  public static final HunkCursor DEFAULT_INSTANCE = new HunkCursor();

  private HunkCursor() {
  }

}
