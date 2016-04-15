/*
 * Copyright (c) 2010-2016. Axon Framework
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

package org.axonframework.test;

import org.axonframework.commandhandling.NoHandlerForCommandException;
import org.axonframework.commandhandling.annotation.CommandHandler;
import org.axonframework.commandhandling.annotation.TargetAggregateIdentifier;
import org.axonframework.eventhandling.annotation.EventHandler;
import org.axonframework.eventsourcing.annotation.AggregateIdentifier;
import org.axonframework.eventstore.EventStoreException;
import org.junit.Test;

import static org.axonframework.commandhandling.model.AggregateLifecycle.apply;

public class FixtureTest_ExceptionHandling {

    private final FixtureConfiguration<MyAggregate> fixture = Fixtures.newGivenWhenThenFixture(MyAggregate.class);

    @Test
    public void testCreateAggregate() {
        fixture.givenCommands().when(
                new CreateMyAggregateCommand("14")
        ).expectEvents(
                new MyAggregateCreatedEvent("14")
        );
    }

    @Test
    public void testWhenUnhandledCommand() {
        fixture.givenCommands(new CreateMyAggregateCommand("14")).when(
                new UnhandledCommand("14")
        ).expectException(NoHandlerForCommandException.class);
    }

    @Test(expected = FixtureExecutionException.class)
    public void givenUnhandledCommand() {
        fixture.givenCommands(
                new CreateMyAggregateCommand("14"),
                new UnhandledCommand("14")
        );
    }

    @Test
    public void testWhenExceptionTriggeringCommand() {
        fixture.givenCommands(new CreateMyAggregateCommand("14")).when(
                new ExceptionTriggeringCommand("14")
        ).expectException(RuntimeException.class);
    }

    @Test(expected = RuntimeException.class)
    public void testGivenExceptionTriggeringCommand() {
        fixture.givenCommands(
                new CreateMyAggregateCommand("14"),
                new ExceptionTriggeringCommand("14")
        );
    }

    @Test
    public void testGivenCommandWithInvalidIdentifier() {
        fixture.givenCommands(
                new CreateMyAggregateCommand("1")
        ).when(
                new ValidMyAggregateCommand("2")
        ).expectException(EventStoreException.class);
    }

    @Test(expected = FixtureExecutionException.class)
    public void testWhenCommandWithInvalidIdentifier() {
        fixture.givenCommands(
                new CreateMyAggregateCommand("1"),
                new ValidMyAggregateCommand("2")
        );
    }

    private static abstract class AbstractMyAggregateCommand {
        @TargetAggregateIdentifier
        public final String id;

        protected AbstractMyAggregateCommand(String id) {
            this.id = id;
        }
    }

    private static class CreateMyAggregateCommand extends AbstractMyAggregateCommand {
        protected CreateMyAggregateCommand(String id) {
            super(id);
        }
    }

    private static class ExceptionTriggeringCommand extends AbstractMyAggregateCommand {
        protected ExceptionTriggeringCommand(String id) {
            super(id);
        }
    }

    private static class ValidMyAggregateCommand extends AbstractMyAggregateCommand {
        protected ValidMyAggregateCommand(String id) {
            super(id);
        }
    }

    private static class UnhandledCommand extends AbstractMyAggregateCommand {
        protected UnhandledCommand(String id) {
            super(id);
        }
    }

    private static class MyAggregateCreatedEvent {
        public final String id;

        public MyAggregateCreatedEvent(String id) {
            this.id = id;
        }
    }

    private static class MyAggregate {
        @AggregateIdentifier
        String id;

        private MyAggregate() {
        }

        @CommandHandler
        public MyAggregate(CreateMyAggregateCommand cmd) {
            apply(new MyAggregateCreatedEvent(cmd.id));
        }

        @CommandHandler
        public void handle(ValidMyAggregateCommand cmd) {
            /* no-op */
        }

        @CommandHandler
        public void handle(ExceptionTriggeringCommand cmd) {
            throw new RuntimeException("Error");
        }

        @EventHandler
        private void on(MyAggregateCreatedEvent event) {
            this.id = event.id;
        }
    }
}