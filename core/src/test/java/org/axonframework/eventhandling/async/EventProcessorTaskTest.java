/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventhandling.async;

import org.axonframework.common.MockException;
import org.axonframework.eventhandling.*;
import org.axonframework.eventsourcing.StubDomainEvent;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.interceptors.Transaction;
import org.axonframework.messaging.interceptors.TransactionManager;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWorkFactory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.gt;
import static org.mockito.Mockito.*;

/**
 * @author Allard Buijze
 */
@SuppressWarnings("unchecked")
public class EventProcessorTaskTest {

    private static final Set<MessageHandlerInterceptor<EventMessage<?>>> NO_INTERCEPTORS = Collections.emptySet();

    private EventProcessorTask testSubject;
    private Transaction mockTransaction;
    private TransactionManager mockTransactionManager;
    private EventProcessingMonitor eventProcessingMonitor;
    private MultiplexingEventProcessingMonitor multiplexingEventProcessingMonitor;

    @Before
    public void setUp() throws Exception {
        mockTransactionManager = mock(TransactionManager.class);
        mockTransaction = mock(Transaction.class);
        when(mockTransactionManager.startTransaction()).thenReturn(mockTransaction);
        eventProcessingMonitor = mock(EventProcessingMonitor.class);
        multiplexingEventProcessingMonitor = new MultiplexingEventProcessingMonitor(eventProcessingMonitor);
    }

    @Test
    public void testEventProcessingSchedule_EventRetried() {
        MockEventListener listener = executeEventProcessing(RetryPolicy.retryAfter(500, TimeUnit.MILLISECONDS));

        verify(mockTransactionManager, times(4)).startTransaction();
        verify(mockTransaction, times(3)).commit();
        verify(mockTransaction, times(1)).rollback();
        assertEquals(4, listener.handledEvents.size());
    }

    @Test
    public void testEventProcessingSchedule_FailedEventIgnored() {
        MockEventListener listener = executeEventProcessing(RetryPolicy.proceed());

        // each event is handled twice, since we retry the entire batch
        assertEquals(3, listener.handledEvents.size());
        verify(mockTransactionManager, times(3)).startTransaction();
        verify(mockTransaction, times(3)).commit();
    }

    @Test
    public void testEventProcessingDelayed_ScheduledExecutorService() throws Exception {
        EventMessage<? extends StubDomainEvent> event1 = new GenericEventMessage<>(new StubDomainEvent());
        EventMessage<? extends StubDomainEvent> event2 = new GenericEventMessage<>(new StubDomainEvent());
        final EventListener listener = mock(EventListener.class);
        ScheduledExecutorService mockExecutorService = mock(ScheduledExecutorService.class);
        testSubject = new EventProcessorTask(mockExecutorService, new NullShutdownCallback(),
                                         new DefaultErrorHandler(RetryPolicy.retryAfter(500, TimeUnit.MILLISECONDS)),
                                         new DefaultUnitOfWorkFactory(mockTransactionManager),
                                         Collections.singleton(listener), multiplexingEventProcessingMonitor,
                                         NO_INTERCEPTORS);

        doThrow(new MockException()).doNothing().when(listener).handle(event1);

        testSubject.scheduleEvent(event1);
        testSubject.scheduleEvent(event2);
        testSubject.run();
        verify(mockExecutorService).schedule(eq(testSubject), gt(400L), eq(TimeUnit.MILLISECONDS));
        // since the scheduler is a mock, we simulate the execution:
        testSubject.run();
        InOrder inOrder = inOrder(listener, mockTransactionManager, mockTransaction);
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event1);
        inOrder.verify(mockTransaction).rollback();
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event1);
        inOrder.verify(mockTransaction).commit();
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event2);
        inOrder.verify(mockTransaction).commit();
    }

    @Test
    public void testEventProcessingDelayed_ExecutorDoesNotSupportScheduling() throws Exception {
        EventMessage<? extends StubDomainEvent> event1 = new GenericEventMessage<>(new StubDomainEvent());
        EventMessage<? extends StubDomainEvent> event2 = new GenericEventMessage<>(new StubDomainEvent());
        final EventListener listener = mock(EventListener.class);
        ExecutorService mockExecutorService = mock(ExecutorService.class);
        testSubject = new EventProcessorTask(mockExecutorService, new NullShutdownCallback(),
                                         new DefaultErrorHandler(RetryPolicy.retryAfter(500, TimeUnit.MILLISECONDS)),
                                         new DefaultUnitOfWorkFactory(mockTransactionManager),
                                         Collections.singleton(listener), multiplexingEventProcessingMonitor,
                                         NO_INTERCEPTORS);
        doThrow(new MockException()).doNothing().when(listener).handle(event1);
        testSubject.scheduleEvent(event1);
        testSubject.scheduleEvent(event2);
        long t1 = System.currentTimeMillis();
        testSubject.run();
        // we simulate the immediate scheduling of the yielded task by executing run again
        testSubject.run();
        long t2 = System.currentTimeMillis();
        // we allow some slack, because thread scheduling doesn't give us much guarantees about timing
        long waitTime = t2 - t1;
        assertTrue("Wait time was too short: " + waitTime, waitTime > 480);

        InOrder inOrder = inOrder(listener, mockTransactionManager, mockTransaction);
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event1);
        inOrder.verify(mockTransaction).rollback();
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event1);
        inOrder.verify(mockTransaction).commit();
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event2);
        inOrder.verify(mockTransaction).commit();
    }

    /**
     * This test verifies issue #15 (http://code.google.com/p/axonframework/issues/detail?id=15)
     */
    @Test
    public void testEventProcessingRetried_TransactionStartupFails() throws Exception {
        EventMessage<? extends StubDomainEvent> event1 = new GenericEventMessage<>(new StubDomainEvent());
        EventMessage<? extends StubDomainEvent> event2 = new GenericEventMessage<>(new StubDomainEvent());
        final EventListener listener = mock(EventListener.class);
        ExecutorService mockExecutorService = mock(ExecutorService.class);
        testSubject = new EventProcessorTask(mockExecutorService, new NullShutdownCallback(),
                                         new DefaultErrorHandler(RetryPolicy.retryAfter(500, TimeUnit.MILLISECONDS)),
                                         new DefaultUnitOfWorkFactory(mockTransactionManager),
                                         Collections.singleton(listener), multiplexingEventProcessingMonitor,
                                         NO_INTERCEPTORS);

        doThrow(new MockException()).doReturn(mockTransaction).when(mockTransactionManager).startTransaction();
        testSubject.scheduleEvent(event1);
        testSubject.scheduleEvent(event2);
        testSubject.run();
        verify(mockExecutorService, times(2)).execute(eq(testSubject));
        verify(listener, never()).handle(event1);
        // since the scheduler is a mock, we simulate the execution:
        testSubject.run();
        InOrder inOrder = inOrder(listener, mockTransactionManager, mockTransaction);
        inOrder.verify(mockTransactionManager, times(2)).startTransaction();
        // make sure the first event is not skipped by verifying that event1 is handled
        inOrder.verify(listener).handle(event1);
        inOrder.verify(mockTransaction).commit();
        inOrder.verify(mockTransactionManager).startTransaction();
        inOrder.verify(listener).handle(event2);
        inOrder.verify(mockTransaction).commit();
    }

    private MockEventListener executeEventProcessing(RetryPolicy policy) {
        ExecutorService mockExecutorService = mock(ExecutorService.class);
        final MockEventListener listener = new MockEventListener();
        testSubject = new EventProcessorTask(mockExecutorService, new NullShutdownCallback(),
                                         new DefaultErrorHandler(policy),
                                         new DefaultUnitOfWorkFactory(mockTransactionManager),
                                         Collections.<EventListener>singleton(listener),
                                         multiplexingEventProcessingMonitor,
                                         NO_INTERCEPTORS);
        doNothing().doThrow(new RejectedExecutionException()).when(mockExecutorService).execute(isA(Runnable.class));
        testSubject.scheduleEvent(new GenericEventMessage<>(new StubDomainEvent()));
        listener.failOnEvent = 2;
        testSubject.scheduleEvent(new GenericEventMessage<>(new StubDomainEvent()));
        testSubject.scheduleEvent(new GenericEventMessage<>(new StubDomainEvent()));

        testSubject.run();
        return listener;
    }

    private class MockEventListener implements EventListener {

        private int failOnEvent;
        private List<EventMessage<?>> handledEvents = new LinkedList<>();

        @Override
        public void handle(EventMessage event) {
            handledEvents.add(event);
            if (--failOnEvent == 0) {
                throw new MockException();
            }
        }
    }

    private static class NullShutdownCallback implements EventProcessorTask.ShutdownCallback {

        @Override
        public void afterShutdown(EventProcessorTask scheduler) {
        }
    }
}
