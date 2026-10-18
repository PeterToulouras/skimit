package dev.skimit.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecorderTest {

  private static List<String> trace() throws IOException {
    Path out = Files.createTempFile("skimit", ".trace");
    Recorder.dump(out);
    return Files.readAllLines(out);
  }

  @Test
  void anEffectCausedInsideAnotherIsTheSameEffect() throws IOException {
    int place = Recorder.register("shop/OrderService#place#()V");
    int impl = Recorder.register("shop/JpaOrderRepository#save#(J)V");
    int save = Recorder.sink("shop/OrderRepository#save#(J)V");
    int persist = Recorder.sink("jakarta/persistence/EntityManager#persist#(Ljava/lang/Object;)V");
    Recorder.enter(place);
    Recorder.hit(save);
    Recorder.enter(impl);
    Recorder.hit(persist);
    Recorder.done(persist);
    Recorder.exit(impl);
    Recorder.done(save);
    // Once the save returned, a write from the same method is an effect of its own again.
    Recorder.hit(persist);
    Recorder.done(persist);
    Recorder.exit(place);
    List<String> t = trace();
    assertTrue(t.contains("reaches shop/OrderService#place#()V shop/OrderRepository#save#(J)V"), t.toString());
    assertTrue(t.contains("reaches shop/OrderService#place#()V jakarta/persistence/EntityManager#persist#(Ljava/lang/Object;)V"), t.toString());
    assertFalse(t.contains("reaches shop/JpaOrderRepository#save#(J)V jakarta/persistence/EntityManager#persist#(Ljava/lang/Object;)V"), t.toString());
  }

  @Test
  void framesBeforeABoundaryAreNotOnTheWayToAnEffect() throws IOException {
    int test = Recorder.register("shop/OrderControllerTests#creates#()V");
    int handler = Recorder.register("shop/OrderController#create#()V");
    int send = Recorder.sink("shop/Mailer#send#()V");
    Recorder.enter(test);
    Recorder.enter(Recorder.BOUNDARY);
    Recorder.enter(handler);
    Recorder.hit(send);
    Recorder.done(send);
    Recorder.exit(handler);
    Recorder.exit(Recorder.BOUNDARY);
    Recorder.exit(test);
    List<String> t = trace();
    assertTrue(t.contains("reaches shop/OrderController#create#()V shop/Mailer#send#()V"), t.toString());
    assertFalse(t.contains("reaches shop/OrderControllerTests#creates#()V shop/Mailer#send#()V"), t.toString());
  }
}
