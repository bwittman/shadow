package shadow.test.typecheck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import shadow.Main;

import java.util.ArrayList;

public class UtilityTests {

  private final ArrayList<String> args = new ArrayList<>();

  @BeforeEach
  public void setup() throws Exception {
    // args.add("-v");
    args.add("--typecheck");

    String os = System.getProperty("os.name").toLowerCase();

    args.add("-c");
    if (os.contains("windows")) args.add("windows.json");
    else if (os.contains("mac")) args.add("mac.json");
    else args.add("linux.json");
  }

  @Test
  public void testArrayList() throws Exception {
    args.add("shadow/utility/ArrayList.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testHashSet() throws Exception {
    args.add("shadow/utility/HashSet.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testIllegalModificationException() throws Exception {
    args.add("shadow/utility/IllegalModificationException.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testLinkedList() throws Exception {
    args.add("shadow/utility/LinkedList.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testList() throws Exception {
    args.add("shadow/utility/List.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testRandom() throws Exception {
    args.add("shadow/utility/Random.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testSet() throws Exception {
    args.add("shadow/utility/Set.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testArrayDeque() throws Exception {
    args.add("shadow/utility/ArrayDeque.shadow");
    Main.run(args.toArray(new String[] {}));
  }

  @Test
  public void testHashMap() throws Exception {
    args.add("shadow/utility/HashMap.shadow");
    Main.run(args.toArray(new String[] {}));
  }
}
