package dev.relism.flash.http;

import dev.relism.fpr.core.ByteView;
import java.util.List;

/** Immutable empty header collection shared by requests without trailers. */
public enum EmptyHeaderView implements HeaderView {
  INSTANCE;

  @Override public String first(String name) { return null; }
  @Override public List<String> all(String name) { return List.of(); }
  @Override public List<String> all() { return List.of(); }
  @Override public ByteView view(String name) { return null; }
  @Override public boolean valueEqualsIgnoreCase(String name, String value) { return false; }
  @Override public boolean contains(String name) { return false; }
  @Override public int count() { return 0; }
  @Override public void forEach(HeaderConsumer consumer) {}
}
