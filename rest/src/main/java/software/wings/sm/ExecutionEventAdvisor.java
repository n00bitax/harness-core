package software.wings.sm;

/**
 * Created by rishi on 1/23/17.
 */
public interface ExecutionEventAdvisor {
  public ExecutionInterruptType onExecutionEvent(ExecutionEvent executionEvent);
}
