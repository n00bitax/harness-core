package software.wings.service.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static software.wings.beans.Event.Builder.anEvent;
import static software.wings.utils.WingsTestConstants.ARTIFACT_ID;

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import software.wings.beans.Event;
import software.wings.beans.Event.Type;
import software.wings.service.impl.EventEmitter.Channel;

/**
 * Created by peeyushaggarwal on 8/16/16.
 */
public class EventEmitterTest {
  /**
   * The Mockito rule.
   */
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @InjectMocks private EventEmitter eventEmitter = new EventEmitter(null);

  @Mock private BroadcasterFactory broadcasterFactory;

  @Mock private Broadcaster broadcaster;

  @Mock private Broadcaster specificBroadcaster;

  @Test
  public void shouldSendToBothIdAndGeneralChannel() throws Exception {
    when(broadcasterFactory.lookup("/stream/artifacts/" + ARTIFACT_ID)).thenReturn(specificBroadcaster);
    when(broadcasterFactory.lookup("/stream/artifacts")).thenReturn(broadcaster);
    Event event = anEvent().withUuid(ARTIFACT_ID).withType(Type.UPDATE).build();
    eventEmitter.send(Channel.ARTIFACTS, anEvent().withUuid(ARTIFACT_ID).withType(Type.UPDATE).build());
    verify(broadcaster).broadcast(event);
    verify(specificBroadcaster).broadcast(event);
  }

  @Test
  public void shouldSendToGeneralChannelWhenIdChannelNotConnected() throws Exception {
    when(broadcasterFactory.lookup("/stream/artifacts")).thenReturn(broadcaster);
    Event event = anEvent().withUuid(ARTIFACT_ID).withType(Type.UPDATE).build();
    eventEmitter.send(Channel.ARTIFACTS, anEvent().withUuid(ARTIFACT_ID).withType(Type.UPDATE).build());
    verify(broadcaster).broadcast(event);
    verifyZeroInteractions(specificBroadcaster);
  }

  @Test
  public void shouldSendToIdChannelWhenGeneralChannelNotConnected() throws Exception {
    when(broadcasterFactory.lookup("/stream/artifacts")).thenReturn(broadcaster);
    Event event = anEvent().withUuid(ARTIFACT_ID).withType(Type.UPDATE).build();
    eventEmitter.send(Channel.ARTIFACTS, anEvent().withUuid(ARTIFACT_ID).withType(Type.UPDATE).build());
    verify(broadcaster).broadcast(event);
    verifyZeroInteractions(specificBroadcaster);
  }
}
