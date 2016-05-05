package com.github.davidmoten.fsm.rx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.github.davidmoten.fsm.example.generated.MicrowaveBehaviour;
import com.github.davidmoten.fsm.example.generated.MicrowaveBehaviourBase;
import com.github.davidmoten.fsm.example.generated.MicrowaveStateMachine;
import com.github.davidmoten.fsm.example.microwave.ButtonPressed;
import com.github.davidmoten.fsm.example.microwave.DoorOpened;
import com.github.davidmoten.fsm.example.microwave.Microwave;
import com.github.davidmoten.fsm.example.microwave.TimerTimesOut;
import com.github.davidmoten.fsm.runtime.Clock;
import com.github.davidmoten.fsm.runtime.EntityStateMachine;
import com.github.davidmoten.fsm.runtime.Signaller;
import com.github.davidmoten.fsm.runtime.rx.ClassId;
import com.github.davidmoten.fsm.runtime.rx.Processor;
import com.github.davidmoten.fsm.runtime.rx.StateMachineFactory;

import rx.functions.Func2;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;

public class ProcessorTest {

    @Test
    public void testRxProcessor() throws InterruptedException {
        // special scheduler that we will use to schedule signals
        TestScheduler signalScheduler = new TestScheduler();

        Processor<String> processor = createProcessor(signalScheduler);

        // do some tests with the processor
        TestSubscriber<EntityStateMachine<?>> ts = TestSubscriber.create();
        processor.observable().doOnNext(m -> System.out.println(m.state())).subscribe(ts);

        ClassId<Microwave, String> microwave = ClassId.create(Microwave.class, "1");

        // button is pressed
        processor.signal(microwave, new ButtonPressed());
        ts.assertValueCount(1);
        assertEquals(MicrowaveStateMachine.State.COOKING, ts.getOnNextEvents().get(0).state());
        signalScheduler.advanceTimeBy(29, TimeUnit.SECONDS);
        ts.assertValueCount(1);

        // cooking times out
        signalScheduler.advanceTimeBy(1, TimeUnit.SECONDS);

        ts.assertNoErrors();
        ts.assertValueCount(2);
        {
            List<EntityStateMachine<?>> list = ts.getOnNextEvents();
            assertEquals(MicrowaveStateMachine.State.COOKING, list.get(0).state());
            assertEquals(MicrowaveStateMachine.State.COOKING_COMPLETE, list.get(1).state());
        }

        // open the door
        processor.signal(microwave, new DoorOpened());
        ts.assertValueCount(3);
        assertEquals(MicrowaveStateMachine.State.DOOR_OPEN, ts.getOnNextEvents().get(2).state());

        // press the button (will not start cooking)
        processor.signal(microwave, new ButtonPressed());
        {
            // should not be a transition
            ts.assertValueCount(4);
            List<EntityStateMachine<?>> list = ts.getOnNextEvents();
            assertEquals(MicrowaveStateMachine.State.DOOR_OPEN, list.get(3).state());
            assertFalse(list.get(3).transitionOccurred());
        }

        // stop the subscription
        processor.onCompleted();

        ts.awaitTerminalEvent();
    }

    @Test
    public void testCancelSignal() {
        // special scheduler that we will use to schedule signals
        TestScheduler signalScheduler = new TestScheduler();

        Processor<String> processor = createProcessor(signalScheduler);

        // do some tests with the processor
        TestSubscriber<EntityStateMachine<?>> ts = TestSubscriber.create();
        processor.observable().doOnNext(m -> System.out.println(m.state())).subscribe(ts);

        ClassId<Microwave, String> microwave = ClassId.create(Microwave.class, "1");

        // button is pressed
        processor.signal(microwave, new ButtonPressed());
        ts.assertValueCount(1);
        assertEquals(MicrowaveStateMachine.State.COOKING, ts.getOnNextEvents().get(0).state());
        // advance by less time than the timeout
        signalScheduler.advanceTimeBy(10, TimeUnit.SECONDS);
        ts.assertValueCount(1);
        processor.cancelSignalToSelf(microwave);

        // cooking would time out by now if signal had not been cancelled
        signalScheduler.advanceTimeBy(30, TimeUnit.SECONDS);
        ts.assertValueCount(1);

        // now cancel a non-existent signal to get coverage
        processor.cancelSignalToSelf(microwave);
        ts.assertValueCount(1);

        processor.onCompleted();
        ts.assertNoErrors();
    }

    private static Processor<String> createProcessor(TestScheduler signalScheduler) {
        MicrowaveBehaviour behaviour = createMicrowaveBehaviour();

        // define how to instantiate state machines from identifiers
        Func2<Class<?>, String, EntityStateMachine<?>> stateMachineFactory = StateMachineFactory
                .cls(Microwave.class)
                .<String> hasFactory(id -> MicrowaveStateMachine.create(Microwave.fromId(id), id,
                        behaviour, MicrowaveStateMachine.State.READY_TO_COOK,
                        Clock.from(signalScheduler)))
                .build();

        // build a processor
        Processor<String> processor = Processor.stateMachineFactory(stateMachineFactory)
                .processingScheduler(Schedulers.immediate()).signalScheduler(signalScheduler)
                .build();
        return processor;
    }

    private static MicrowaveBehaviourBase createMicrowaveBehaviour() {
        return new MicrowaveBehaviourBase() {
            @Override
            public Microwave onEntry_Cooking(Signaller<Microwave> signaller, Microwave microwave,
                    Object id, ButtonPressed event) {
                signaller.signalToSelf(new TimerTimesOut(), 30, TimeUnit.SECONDS);
                return microwave;
            }
        };
    }
}
