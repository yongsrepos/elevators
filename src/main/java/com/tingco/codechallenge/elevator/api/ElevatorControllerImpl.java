package com.tingco.codechallenge.elevator.api;

import com.google.common.collect.Range;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.tingco.codechallenge.elevator.api.events.EventFactory;
import com.tingco.codechallenge.elevator.api.events.impl.NewlyFree;
import com.tingco.codechallenge.elevator.api.exceptions.InvalidRidingElevatorIdException;
import com.tingco.codechallenge.elevator.api.exceptions.MissingRidingElevatorException;
import com.tingco.codechallenge.elevator.api.exceptions.MissingWaitingDirectionException;
import com.tingco.codechallenge.elevator.api.exceptions.OutOfFloorRangeException;
import com.tingco.codechallenge.elevator.api.requests.UserRiding;
import com.tingco.codechallenge.elevator.api.requests.UserWaiting;
import com.tingco.codechallenge.elevator.api.utils.ComparatorFactory;
import com.tingco.codechallenge.elevator.api.validators.RidingRequestValidator;
import com.tingco.codechallenge.elevator.api.validators.WaitingRequestValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * Created by Yong Huang on 2017-11-20.
 */
@Service
public class ElevatorControllerImpl implements ElevatorController {
    private static final Logger LOGGER = LogManager.getLogger(ElevatorControllerImpl.class);

    @Value("${com.tingco.elevator.numberofelevators}")
    private int numberOfElevators;

    @Autowired
    ElevatorConfiguration elevatorConfiguration;

    @Autowired
    EventBus eventBus;

    // to be initialized before put into service
    private Range<Integer> elevatorFloorRange;
    private Set<Integer> validElevatorIds;

    private List<ElevatorImpl> elevators = new ArrayList<>();
    private Queue<ElevatorImpl> freeElevators = new LinkedBlockingDeque<>();

    private PriorityQueue<UserWaiting> upwardsWaitingQueue = new PriorityQueue<>(ComparatorFactory.naturalOrderByFloorNumber());
    private PriorityQueue<UserWaiting> downwardsWaitingQueue = new PriorityQueue<>(ComparatorFactory.reverseOrderByFloorNumber());
    private Queue<UserWaiting> timedQueueForAllWaitingRequests = new LinkedBlockingQueue<>();

    @PostConstruct
    void init() {
        for (int id = 1; id <= numberOfElevators; id++) {
            ElevatorImpl elevator = new ElevatorImpl(this.eventBus, id, this.elevatorConfiguration);
            elevators.add(elevator);
            freeElevators.offer(elevator);
            this.eventBus.register(elevator);
        }

        this.eventBus.register(this);
        this.elevatorFloorRange = Range.closed(this.elevatorConfiguration.getBottomFloor(), this.elevatorConfiguration.getTopFloor());
        this.validElevatorIds = this.elevators.stream().map(Elevator::getId).collect(Collectors.toSet());
    }

    @Override public Elevator requestElevator(int toFloor) {
        if (!this.freeElevators.isEmpty()) {
            Elevator freeElevator = this.freeElevators.poll();
            // TODO: post event to the allocated elevator
            return freeElevator;
        }

        // TODO: design the algorithms here

        return this.freeElevators.poll();
    }

    @Override public List<Elevator> getElevators() {
        return new ArrayList<>(this.elevators);
    }

    @Override public void releaseElevator(Elevator elevator) {
        this.freeElevators.offer((ElevatorImpl) elevator);
    }

    /**
     * Process new user waiting request by either : 1. allocating an elevator moving at the same direction as requested but not bypassing the waiting floor yet
     * or 2. enqueuing the request.
     * When there are more than one elevator moving at the requested direction, choose the one that has least request between its latest addressed floor
     * and the waiting floor.
     *
     * @param userWaiting the user waiting request
     * @throws OutOfFloorRangeException         if the requested floor is beyond the closed range: [bottom, top]
     * @throws MissingWaitingDirectionException if the waiting request misses requested direction, e.g. UP, DOWN.
     */
    public void createUserWaitingRequest(UserWaiting userWaiting)
        throws OutOfFloorRangeException, MissingWaitingDirectionException {
        WaitingRequestValidator.validate(userWaiting, this.elevatorFloorRange);

        if (this.timedQueueForAllWaitingRequests.contains(userWaiting)) {
            LOGGER.info("Waiting request={} already exists.", userWaiting);
            return;
        }

        final int waitingFloor = userWaiting.getWaitingFloor();
        final Elevator.Direction towards = userWaiting.getTowards();

        if (!this.freeElevators.isEmpty()) {
            Elevator freeElevator = this.freeElevators.poll();
            this.eventBus.post(EventFactory.createUserWaiting(freeElevator.getId(), towards, waitingFloor));

            LOGGER.info("Allocating elevator={} to waiting request={}", freeElevator.getId(), userWaiting);
            return;
        }

        switch (userWaiting.getTowards()) {
            case UP:
                List<ElevatorImpl> lowerFloorElevatorsMovingUpwards = getLowerFloorElevatorsMovingUpwards(waitingFloor);

                if (lowerFloorElevatorsMovingUpwards.isEmpty()) {
                    LOGGER.info("Enqueue waiting request={} because of no elevator moving upwards.", userWaiting);

                    this.timedQueueForAllWaitingRequests.offer(userWaiting);
                    this.upwardsWaitingQueue.offer(userWaiting);
                } else {
                    sendUpwardsRequestToBestSuitElevator(waitingFloor, lowerFloorElevatorsMovingUpwards);
                }

                return;
            case DOWN:
                List<ElevatorImpl> upperFloorElevatorsMovingDownwards = getUpperFloorElevatorsMovingDownwards(waitingFloor);

                if (upperFloorElevatorsMovingDownwards.isEmpty()) {
                    LOGGER.info("Enqueue waiting request={} because of no elevator moving downwards.", userWaiting);

                    this.timedQueueForAllWaitingRequests.offer(userWaiting);
                    this.downwardsWaitingQueue.offer(userWaiting);
                } else {
                    sendDownwardsRequestToBestSuitElevator(waitingFloor, upperFloorElevatorsMovingDownwards);
                }

                return;
            default:
                LOGGER.info("Ignore waiting request={}.", userWaiting);
                return;
        }
    }

    private List<ElevatorImpl> getLowerFloorElevatorsMovingUpwards(final int waitingFloor) {
        return this.elevators
            .stream()
            .filter(elevator -> elevator.getAddressedFloor() < waitingFloor)
            .filter(elevator -> elevator.isOnUpPath())
            .collect(Collectors.toList());
    }

    private void sendUpwardsRequestToBestSuitElevator(final int waitingFloor, List<ElevatorImpl> lowerFloorElevatorsMovingOnUpPath) {
        lowerFloorElevatorsMovingOnUpPath
            .stream()
            .sorted(ComparatorFactory.byAmountOfUpwardsRequestsLowerThanFloor(waitingFloor))
            .findFirst()
            .ifPresent(
                elevator -> {
                    LOGGER.info("Sending upwards waiting request={} to best suit elevator={}.", waitingFloor, elevator.getId());
                    this.eventBus.post(EventFactory.createUserWaiting(elevator.getId(), Elevator.Direction.UP, waitingFloor));
                });
    }

    private List<ElevatorImpl> getUpperFloorElevatorsMovingDownwards(int waitingFloor) {
        return this.elevators
            .stream()
            .filter(elevator -> elevator.getAddressedFloor() > waitingFloor)
            .filter(elevator -> elevator.isOnDownPath())
            .collect(Collectors.toList());
    }

    private void sendDownwardsRequestToBestSuitElevator(final int waitingFloor, List<ElevatorImpl> upperFloorElevatorsMovingOnDownPath) {
        upperFloorElevatorsMovingOnDownPath
            .stream()
            .sorted(ComparatorFactory.byAmountOfDownwardsRequestsHigherThanFloor(waitingFloor))
            .findFirst()
            .ifPresent(
                elevator -> {
                    LOGGER.info("Sending downwards waiting request={} to best suit elevator={}.", waitingFloor, elevator.getId());
                    this.eventBus.post(EventFactory.createUserWaiting(elevator.getId(), Elevator.Direction.DOWN, waitingFloor));
                });
    }

    /**
     * Delegates the riding request to currently occupied elevator(identified by the elevator id in the request) if the requested floor is not in the elevator's
     * request queue;otherwise ignore the request.
     *
     * @param userRiding an riding request from inside of an elevator.
     * @throws OutOfFloorRangeException         if the requested floor is out of the closed range [bottom, top].
     * @throws MissingRidingElevatorException   if no riding elevator id is provided.
     * @throws InvalidRidingElevatorIdException if the riding elevator id is provided but not a valid one.
     */
    public void createUserRidingRequest(UserRiding userRiding) throws OutOfFloorRangeException, MissingRidingElevatorException,
        InvalidRidingElevatorIdException {
        RidingRequestValidator.validate(userRiding, this.elevatorFloorRange, this.validElevatorIds);

        final int toFloor = userRiding.getToFloor();
        final int ridingElevatorId = userRiding.getRidingElevatorId();

        this.elevators
            .stream()
            .filter(elevator -> elevator.getId() == ridingElevatorId)
            .filter(elevator -> !elevator.isFloorAlreadyRequested(toFloor))//if a floor is already requested, then ignore the request.
            .anyMatch(theRidingElevator -> {
                    LOGGER.info("Sending request riding request={} to elevator={}.", userRiding, theRidingElevator.getId());
                    this.eventBus.post(EventFactory.createFloorRequested(ridingElevatorId, toFloor));
                    return true;
                }
            );
    }

    @Subscribe
    void onElevatorNewlyFree(NewlyFree newlyFree) {
        LOGGER.info("Elevator={} is newly freed.", newlyFree.getElevatorId());

        int freedElevatorId = newlyFree.getElevatorId();

        if (timedQueueForAllWaitingRequests.isEmpty()) {
            LOGGER.info("No pending request. Enqueue the freed elevator={}.", freedElevatorId);

            this.elevators
                .stream()
                .filter(elevator -> elevator.getId() == freedElevatorId)
                .anyMatch(newlyFreedElevator -> this.freeElevators.offer(newlyFreedElevator));
            return;
        }

        UserWaiting longestWaitingRequest = this.timedQueueForAllWaitingRequests.poll();

        LOGGER.info("Allocating free elevator={} to longest waiting request={}.", freedElevatorId, longestWaitingRequest);

        this.eventBus
            .post(EventFactory.createUserWaiting(freedElevatorId, longestWaitingRequest.getTowards(), longestWaitingRequest.getWaitingFloor()));

        switch (longestWaitingRequest.getTowards()) {
            case UP:
                this.upwardsWaitingQueue.remove(longestWaitingRequest);
                break;
            case DOWN:
                this.downwardsWaitingQueue.remove(longestWaitingRequest);
                break;
            default:
                break;
        }
    }
}
