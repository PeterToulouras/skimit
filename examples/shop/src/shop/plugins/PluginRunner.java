package shop.plugins;

public class PluginRunner {
  /** Loads a class by name: the analysis cannot know what this runs, and says so. */
  public Object load(String className) throws ReflectiveOperationException {
    return Class.forName(className).getDeclaredConstructor().newInstance();
  }

  public String describe(String className) throws ReflectiveOperationException {
    return "plugin " + load(className);
  }
}
